package com.sikder.spentranslator

import android.Manifest // Needed for POST_NOTIFICATIONS
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager // Needed for checking permission status
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log // Good to have for debugging
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat // Needed for requesting permissions
import androidx.core.content.ContextCompat // Needed for checking permissions
import com.sikder.spentranslator.services.MyTextSelectionService

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity" // For logging

    // Launcher for overlay permission result
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Overlay permission was not granted.", Toast.LENGTH_LONG).show()
                }
                updateButtonStates() // Update button text after returning from settings
            }
        }

    // Request code for notification permission
    private val NOTIFICATION_PERMISSION_REQ_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Make sure activity_main.xml is in res/layout

        val btnEnableAccessibility: Button = findViewById(R.id.btnEnableAccessibility)
        val btnEnableOverlay: Button = findViewById(R.id.btnEnableOverlay)
        // You can add a button for notification permission if you want explicit user trigger,
        // or request it automatically as done below.

        btnEnableAccessibility.setOnClickListener {
            if (!isAccessibilityServiceEnabled(this, MyTextSelectionService::class.java)) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Toast.makeText(this, "Please find and enable '${getString(R.string.accessibility_service_label)}'", Toast.LENGTH_LONG).show()
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open Accessibility Settings.", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Error opening Accessibility Settings", e)
                }
            } else {
                Toast.makeText(this, "Accessibility Service is already enabled.", Toast.LENGTH_SHORT).show()
            }
        }

        btnEnableOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                try {
                    overlayPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open Overlay Permission Settings.", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Error opening Overlay Permission Settings", e)
                }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                Toast.makeText(this, "Overlay permission is not required before Android M.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay permission is already granted.", Toast.LENGTH_SHORT).show()
            }
        }

        // Request Notification Permission when activity is created (for Android 13+)
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, updating button states.")
        updateButtonStates() // Update button states when returning to the activity
    }

    private fun updateButtonStates() {
        val btnEnableAccessibility: Button = findViewById(R.id.btnEnableAccessibility)
        val btnEnableOverlay: Button = findViewById(R.id.btnEnableOverlay)

        if (isAccessibilityServiceEnabled(this, MyTextSelectionService::class.java)) {
            btnEnableAccessibility.text = getString(R.string.accessibility_service_enabled_text) // Using string resource
        } else {
            btnEnableAccessibility.text = getString(R.string.enable_accessibility_service)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                btnEnableOverlay.text = getString(R.string.overlay_permission_granted_text) // Using string resource
            } else {
                btnEnableOverlay.text = getString(R.string.enable_overlay_permission)
            }
        } else {
            btnEnableOverlay.text = getString(R.string.overlay_permission_not_required_text) // Using string resource
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33 (Android 13)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission already granted.")
                // You could update a UI element here if you had one for notification status
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                // Show an explanation to the user why you need this permission
                // For example, show a dialog and then request permission
                Log.d(TAG, "Showing rationale for notification permission.")
                Toast.makeText(this, "Notification permission is needed to show service status.", Toast.LENGTH_LONG).show()
                // Then request:
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQ_CODE)
            }
            else {
                // No explanation needed; request the permission
                Log.d(TAG, "Requesting notification permission.")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQ_CODE)
            }
        } else {
            Log.d(TAG, "Notification permission not required for this Android version (Below TIRAMISU).")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQ_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Notification permission granted by user.")
                } else {
                    Toast.makeText(this, "Notification permission was not granted.", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "Notification permission denied by user.")
                    // Optionally, guide the user to app settings if they permanently deny it
                    // and the feature is critical.
                }
                return
            }
            // Handle other permission request codes if you have them
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (enabledServicesSetting == null) { // Check for null directly
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }
}