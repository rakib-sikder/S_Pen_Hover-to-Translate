package com.sikder.spentranslator

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName // Corrected import (was missing from user's provided code for this specific context)
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils // For isAccessibilityServiceSystemEnabled
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity // Changed to AppCompatActivity for broader compatibility & features
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Recommended for internal broadcasts
import com.sikder.spentranslator.services.MyTextSelectionService
import com.sikder.spentranslator.TranslationApiClient

class MainActivity : AppCompatActivity() { // Changed to AppCompatActivity

    private val TAG = "MainActivity" // Added TAG for logging

    companion object {
        const val ACTION_UPDATE_UI = "com.sikder.spentranslator.UPDATE_UI"
        // Removed private for request codes to be potentially accessible if needed elsewhere, though not typical
        const val OVERLAY_PERMISSION_REQUEST = 100
        const val ACCESSIBILITY_SERVICE_REQUEST = 200
        const val SCREEN_CAPTURE_REQUEST_CODE = 300
        const val NOTIFICATION_PERMISSION_REQUEST = 400
    }

    private lateinit var btnEnableOverlay: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnToggleSelectToTranslate: Button
    private lateinit var etSourceText: EditText
    private lateinit var btnTranslateInApp: Button
    private lateinit var tvTargetText: TextView

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_UI) {
                Log.d(TAG, "Received UI update broadcast.")
                updateButtonStates()
            }
        }
    }

    // Receiver for screen capture requests from the service
    private val screenCaptureRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MyTextSelectionService.ACTION_REQUEST_SCREEN_CAPTURE) {
                Log.i(TAG, "Received request from service for screen capture permission.")
                startScreenCaptureIntent()
            }
        }
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // No need to check resultCode, just update state based on Settings.canDrawOverlays
            Log.d(TAG, "Overlay permission activity returned.")
            updateButtonStates()
        }

    // Using ActivityResultLauncher for starting settings for result (more modern than onActivityResult)
    private val accessibilitySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Accessibility settings activity returned.")
            updateButtonStates() // Re-check status when returning
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.i(TAG, "Screen capture permission GRANTED. ResultCode: ${result.resultCode}")
                MyTextSelectionService.mediaProjectionResultCode = result.resultCode
                MyTextSelectionService.mediaProjectionIntent = result.data
                // Optionally, notify the service it can now proceed if it was waiting
                // This might involve sending another broadcast or the service re-checking the static vars.
                // For simplicity, the service will try OCR again on next selection if these are set.
            } else {
                Log.w(TAG, "Screen capture permission DENIED or cancelled. ResultCode: ${result.resultCode}")
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
                // Clear any old intent/result code in the service if permission is denied
                MyTextSelectionService.mediaProjectionResultCode = null
                MyTextSelectionService.mediaProjectionIntent = null
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Use your XML layout

        // Initialize UI elements
        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnToggleSelectToTranslate = findViewById(R.id.btnToggleSelectToTranslate)
        etSourceText = findViewById(R.id.etSourceText)
        btnTranslateInApp = findViewById(R.id.btnTranslateInApp)
        tvTargetText = findViewById(R.id.tvTargetText)

        // Set up listeners
        btnEnableOverlay.setOnClickListener { requestOverlayPermission() }
        btnEnableAccessibility.setOnClickListener { requestAccessibilityPermission() }
        btnToggleSelectToTranslate.setOnClickListener { toggleSelectToTranslateFeature() }
        btnTranslateInApp.setOnClickListener { performInAppTranslation() }

        // Register receivers
        LocalBroadcastManager.getInstance(this).registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI))

        // For screenCaptureRequestReceiver, since it's for service->activity communication,
        // ensure it's correctly flagged if not using LocalBroadcastManager.
        // For this internal communication triggered by the service starting an activity,
        // it's simpler if the service directly starts an activity that returns a result.
        // The broadcast approach for requesting MainActivity to start another activity for result is a bit convoluted.
        // Let's keep it for now, but consider simplifying the screen capture initiation.
        // For now, adding the flag for non-exported receiver.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenCaptureRequestReceiver, IntentFilter(MyTextSelectionService.ACTION_REQUEST_SCREEN_CAPTURE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenCaptureRequestReceiver, IntentFilter(MyTextSelectionService.ACTION_REQUEST_SCREEN_CAPTURE))
        }


        checkNotificationPermission() // For foreground service notification
        updateButtonStates() // Initial button state setup
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Updating button states.")
        updateButtonStates() // Refresh states when activity resumes
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(uiUpdateReceiver)
        unregisterReceiver(screenCaptureRequestReceiver)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            try {
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.overlay_settings_error), Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error opening Overlay Permission Settings", e)
            }
        } else {
            Toast.makeText(this, "Overlay permission already granted or not required.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAccessibilityPermission() {
        if (!isAccessibilityServiceSystemEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Toast.makeText(this, "Please find and enable '${getString(R.string.accessibility_service_label)}'", Toast.LENGTH_LONG).show()
            try {
                accessibilitySettingsLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.accessibility_settings_error), Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error opening Accessibility Settings", e)
            }
        } else {
            Toast.makeText(this, "Accessibility Service already enabled in system settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            } else {
                // Permission already granted
                Log.d(TAG, "Notification permission already granted.")
            }
        }
    }

    // This method IS part of ComponentActivity and AppCompatActivity
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults) // Crucial to call super
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.notification_permission_granted), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.notification_permission_denied), Toast.LENGTH_LONG).show()
                }
                return // Prevent fall-through if more cases are added later
            }
            // Handle other permission request codes if you add them
        }
    }

    private fun startScreenCaptureIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager?
            if (mediaProjectionManager != null) {
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else {
                Log.e(TAG, "MediaProjectionManager not available.")
                Toast.makeText(this, "Screen capture service not available.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Screen capture not supported on this Android version.", Toast.LENGTH_SHORT).show()
        }
    }

    // onActivityResult is deprecated. Modern approach uses ActivityResultLauncher (like overlayPermissionLauncher).
    // Screen capture should ideally use registerForActivityResult as well.
    // The screenCaptureLauncher above is an example of this.
    // The MyTextSelectionService.mediaProjectionResultCode and Intent would be set in screenCaptureLauncher's callback.

    private fun toggleSelectToTranslateFeature() {
        if (!Settings.canDrawOverlays(this) || !isAccessibilityServiceSystemEnabled()) {
            Toast.makeText(this, getString(R.string.select_to_translate_permissions_needed), Toast.LENGTH_LONG).show()
            // Optionally direct user to settings again
            if (!Settings.canDrawOverlays(this)) requestOverlayPermission()
            if (!isAccessibilityServiceSystemEnabled()) requestAccessibilityPermission()
            return
        }

        val serviceIntent = Intent(this, MyTextSelectionService::class.java)
        if (MyTextSelectionService.isFeatureActive) {
            serviceIntent.action = MyTextSelectionService.ACTION_STOP_FEATURE
            MyTextSelectionService.isFeatureActive = false // Optimistic update
            Log.d(TAG, "Sending STOP_FEATURE command to service.")
        } else {
            serviceIntent.action = MyTextSelectionService.ACTION_START_FEATURE
            MyTextSelectionService.isFeatureActive = true // Optimistic update
            Log.d(TAG, "Sending START_FEATURE command to service.")
            // Minimize app after starting the feature
            val minimizeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(minimizeIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error minimizing app", e)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                serviceIntent.action == MyTextSelectionService.ACTION_START_FEATURE) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting/stopping MyTextSelectionService", e)
            // Revert optimistic update if service call failed
            MyTextSelectionService.isFeatureActive = !MyTextSelectionService.isFeatureActive
        }
        updateButtonStates() // Update UI immediately
    }

    private fun isAccessibilityServiceSystemEnabled(): Boolean {
        val serviceComponent = ComponentName(this, MyTextSelectionService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (enabledServicesSetting.isNullOrEmpty()) { // More robust check
            return false
        }

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

    private fun updateButtonStates() {
        val overlayEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceSystemEnabled()
        val featureActive = MyTextSelectionService.isFeatureActive // Using static flag from service

        btnEnableOverlay.isEnabled = !overlayEnabled
        btnEnableOverlay.text = if (overlayEnabled) getString(R.string.overlay_permission_granted_text)
        else getString(R.string.enable_overlay_permission)

        btnEnableAccessibility.isEnabled = !accessibilityEnabled
        btnEnableAccessibility.text = if (accessibilityEnabled) getString(R.string.accessibility_service_enabled_text)
        else getString(R.string.enable_accessibility_service)

        btnToggleSelectToTranslate.isEnabled = overlayEnabled && accessibilityEnabled
        btnToggleSelectToTranslate.text = if (featureActive) getString(R.string.stop_select_to_translate)
        else getString(R.string.start_select_to_translate)
        if (!btnToggleSelectToTranslate.isEnabled) {
            btnToggleSelectToTranslate.text = getString(R.string.select_to_translate_permissions_needed)
        }
    }

    private fun performInAppTranslation() {
        val sourceText = etSourceText.text.toString().trim()
        if (sourceText.isNotBlank()) {
            // Languages can be made selectable later
            TranslationApiClient.translate(sourceText, "en", "bn") { translatedText ->
                runOnUiThread { // Ensure UI updates are on the main thread
                    if (translatedText != null) {
                        tvTargetText.text = translatedText
                    } else {
                        tvTargetText.text = getString(R.string.translation_failed)
                        Toast.makeText(this, getString(R.string.translation_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.enter_text_to_translate), Toast.LENGTH_SHORT).show()
        }
    }
}