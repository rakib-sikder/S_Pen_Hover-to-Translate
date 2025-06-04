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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Optional for local broadcasts
import com.sikder.spentranslator.services.MyTextSelectionService

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableOverlay: Button
    private lateinit var btnToggleSelectToTranslate: Button

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
                updateButtonStates() // Update button text after returning
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)
        btnToggleSelectToTranslate = findViewById(R.id.btnToggleSelectToTranslate)

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
                MyTextSelectionService.isFeatureActive = false // Optimistically update
                Log.d(TAG, "Sending STOP_FEATURE command to service.")
            } else {
                serviceIntent.action = MyTextSelectionService.ACTION_START_FEATURE
                MyTextSelectionService.isFeatureActive = true // Optimistically update
                Log.d(TAG, "Sending START_FEATURE command to service.")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceIntent.action == MyTextSelectionService.ACTION_START_FEATURE) {
                    startForegroundService(serviceIntent) // Required for starting foreground service from background on O+
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting/stopping MyTextSelectionService", e)
                MyTextSelectionService.isFeatureActive = !MyTextSelectionService.isFeatureActive // Revert optimistic update
            }
            updateButtonStates() // Update UI immediately
        }
        requestNotificationPermission() // For foreground service notification
        // In onCreate() of MainActivity.kt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33 where these flags are more strictly enforced for Context.registerReceiver
            registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI), Context.RECEIVER_NOT_EXPORTED)
        } else {
            // For older versions, the flag might not be available in this specific registerReceiver overload,
            // or the behavior was less strict. However, it's good practice.
            // If targeting below API 33 with this receiver, and it's not meant to be exported,
            // often it was handled by manifest declaration if it was a manifest-declared receiver,
            // or LocalBroadcastManager was used for purely internal broadcasts.
            // For a context-registered receiver listening to custom intents, and if your minSdk is lower,
            // this flag might not be needed or available for the older registerReceiver method.
            // However, since your compileSdk and targetSdk are high, it's best to include it with a version check.
            // A simpler way for a local receiver without worrying about export flags is LocalBroadcastManager.
            // BUT, to fix the immediate crash for API 31+ targets:
            registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI), RECEIVER_NOT_EXPORTED) // For API 33+, this overload exists.
            // For API 31, 32, if using targetSdk 31+, this rule still applies.
            // Let's assume Context.RECEIVER_NOT_EXPORTED is what's needed.
            // The error implies this flag should be specifiable.
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, updating button states.")
        // Check actual service running state for more accuracy if possible
        // For simplicity, we rely on the static flag and broadcasts for now
        updateButtonStates()
    }

    override fun onPause() {
        super.onPause()
        // Consider if unregistering receiver is needed, depends on lifecycle.
        // For this app, keeping it registered while activity is alive is fine.
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(uiUpdateReceiver)
    }


    private fun updateButtonStates() {
        // System Accessibility Service Permission
        if (isAccessibilityServiceSystemEnabled(this)) {
            btnEnableAccessibility.text = getString(R.string.accessibility_service_enabled_text)
            btnEnableAccessibility.isEnabled = false // Disable if already enabled in system
        } else {
            btnEnableAccessibility.text = getString(R.string.enable_accessibility_service)
            btnEnableAccessibility.isEnabled = true
        }

        // Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                btnEnableOverlay.text = getString(R.string.overlay_permission_granted_text)
                btnEnableOverlay.isEnabled = false // Disable if already granted
            } else {
                btnEnableOverlay.text = getString(R.string.enable_overlay_permission)
                btnEnableOverlay.isEnabled = true
            }
        } else {
            btnEnableOverlay.text = getString(R.string.overlay_permission_not_required_text)
            btnEnableOverlay.isEnabled = false // Not applicable, so disable
        }

        // Toggle Select-to-Translate Feature Button
        if (!isAccessibilityServiceSystemEnabled(this) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))) {
            btnToggleSelectToTranslate.text = getString(R.string.select_to_translate_permissions_needed)
            btnToggleSelectToTranslate.isEnabled = false
        } else {
            btnToggleSelectToTranslate.isEnabled = true
            if (MyTextSelectionService.isFeatureActive) { // Check our static flag
                btnToggleSelectToTranslate.text = getString(R.string.stop_select_to_translate)
            } else {
                btnToggleSelectToTranslate.text = getString(R.string.start_select_to_translate)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
            if (componentNameString.equals(serviceComponent.flattenToString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}