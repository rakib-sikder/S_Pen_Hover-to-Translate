package com.sikder.spentranslator

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.EditText // Import EditText
import android.widget.TextView // Import TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
// import androidx.localbroadcastmanager.content.LocalBroadcastManager // Keep if you switched to this
import com.sikder.spentranslator.services.MyTextSelectionService

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableOverlay: Button
    private lateinit var btnToggleSelectToTranslate: Button

    // New UI elements
    private lateinit var etSourceText: EditText
    private lateinit var btnTranslateInApp: Button
    private lateinit var tvTargetText: TextView

    private val NOTIFICATION_PERMISSION_REQ_CODE = 123

    companion object {
        const val ACTION_UPDATE_UI = "com.sikder.spentranslator.ACTION_UPDATE_UI"
    }

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_UI) {
                Log.d(TAG, "Received UI update broadcast from service.")
                updateButtonStates()
            }
        }
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                updateButtonStates()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize existing buttons
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)
        btnToggleSelectToTranslate = findViewById(R.id.btnToggleSelectToTranslate)

        // Initialize new UI elements for in-app translation
        etSourceText = findViewById(R.id.etSourceText)
        btnTranslateInApp = findViewById(R.id.btnTranslateInApp)
        tvTargetText = findViewById(R.id.tvTargetText)

        // Set click listener for the new Translate button
        btnTranslateInApp.setOnClickListener {
            val sourceText = etSourceText.text.toString()
            if (sourceText.isNotBlank()) {
                // For now, we'll use hardcoded languages. You can add UI for language selection later.
                val sourceLang = "en" // Example source language
                val targetLang = "bn" // Example target language

                TranslationApiClient.translate(sourceText, sourceLang, targetLang) { translatedText ->
                    if (translatedText != null) {
                        tvTargetText.text = translatedText
                    } else {
                        tvTargetText.text = "Translation failed"
                        Toast.makeText(this, "Translation error", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Please enter text to translate", Toast.LENGTH_SHORT).show()
            }
        }

        // Existing button listeners
        btnEnableAccessibility.setOnClickListener {
            if (!isAccessibilityServiceSystemEnabled(this)) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Toast.makeText(this, "Please find and enable '${getString(R.string.accessibility_service_label)}'", Toast.LENGTH_LONG).show()
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening Accessibility Settings", e)
                }
            } else {
                Toast.makeText(this, "Accessibility Service is already enabled in system settings.", Toast.LENGTH_SHORT).show()
            }
        }

        btnEnableOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                try {
                    overlayPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening Overlay Permission Settings", e)
                }
            }
        }

        btnToggleSelectToTranslate.setOnClickListener {
            if (!isAccessibilityServiceSystemEnabled(this) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))) {
                Toast.makeText(this, getString(R.string.select_to_translate_permissions_needed), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, MyTextSelectionService::class.java)
            if (MyTextSelectionService.isFeatureActive) {
                serviceIntent.action = MyTextSelectionService.ACTION_STOP_FEATURE
                MyTextSelectionService.isFeatureActive = false
                Log.d(TAG, "Sending STOP_FEATURE command to service.")
            } else {
                serviceIntent.action = MyTextSelectionService.ACTION_START_FEATURE
                MyTextSelectionService.isFeatureActive = true
                Log.d(TAG, "Sending START_FEATURE command to service.")
                // Minimize app after starting the feature
                val minimizeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(minimizeIntent)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceIntent.action == MyTextSelectionService.ACTION_START_FEATURE) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting/stopping MyTextSelectionService", e)
                MyTextSelectionService.isFeatureActive = !MyTextSelectionService.isFeatureActive // Revert
            }
            updateButtonStates()
        }
        requestNotificationPermission()
        // Using application context for receiver for wider lifecycle, or use registerReceiver with appropriate flags
        // For UI updates tied to activity lifecycle, a simpler register/unregister in onStart/onStop or onResume/onPause
        // with this as the context is also common.
        // Let's stick to the previous registration in onCreate for now.
        registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, updating button states.")
        updateButtonStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(uiUpdateReceiver)
    }

    private fun updateButtonStates() {
        // System Accessibility Service Permission
        if (isAccessibilityServiceSystemEnabled(this)) {
            btnEnableAccessibility.text = getString(R.string.accessibility_service_enabled_text)
            btnEnableAccessibility.isEnabled = false
        } else {
            btnEnableAccessibility.text = getString(R.string.enable_accessibility_service)
            btnEnableAccessibility.isEnabled = true
        }

        // Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                btnEnableOverlay.text = getString(R.string.overlay_permission_granted_text)
                btnEnableOverlay.isEnabled = false
            } else {
                btnEnableOverlay.text = getString(R.string.enable_overlay_permission)
                btnEnableOverlay.isEnabled = true
            }
        } else {
            btnEnableOverlay.text = getString(R.string.overlay_permission_not_required_text)
            btnEnableOverlay.isEnabled = false
        }

        // Toggle Select-to-Translate Feature Button
        if (!isAccessibilityServiceSystemEnabled(this) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))) {
            btnToggleSelectToTranslate.text = getString(R.string.select_to_translate_permissions_needed)
            btnToggleSelectToTranslate.isEnabled = false
        } else {
            btnToggleSelectToTranslate.isEnabled = true
            if (MyTextSelectionService.isFeatureActive) {
                btnToggleSelectToTranslate.text = getString(R.string.stop_select_to_translate)
            } else {
                btnToggleSelectToTranslate.text = getString(R.string.start_select_to_translate)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(this, "Notification permission is needed to show service status.", Toast.LENGTH_LONG).show()
                }
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQ_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQ_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notification permission needed for service status.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isAccessibilityServiceSystemEnabled(context: Context): Boolean {
        val serviceComponent = ComponentName(context, MyTextSelectionService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (enabledServicesSetting == null) return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            if (componentNameString.equals(serviceComponent.flattenToString(), ignoreCase = true) ||
                componentNameString.equals(serviceComponent.toShortString(), ignoreCase = true)) { // Added toShortString check for robustness
                return true
            }
        }
        return false
    }
}