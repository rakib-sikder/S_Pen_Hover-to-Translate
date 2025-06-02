package com.sikder.spentranslator.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color // For android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.sikder.spentranslator.R // Assuming you might create an overlay_layout.xml later

class HoverTranslateService : Service() {

    private val TAG = "HoverTranslateService"
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service Created")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // TODO: Initialize S Pen SDK here if its lifecycle is tied to the service
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Service Started")

        // Check for overlay permission.
        // The permission MUST be granted by the user through settings, typically initiated from an Activity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission is not granted. Cannot show overlay.")
            // Optionally, you could send a broadcast to your Activity to notify the user,
            // or stop the service if the overlay is essential for its operation.
            // For now, we just log and don't show the overlay.
            // Consider stopping the service if overlay is critical:
            // stopSelf()
            // return START_NOT_STICKY
        } else {
            // Show the overlay if permission is granted and view doesn't exist
            if (overlayView == null) {
                showOverlay()
            }
        }

        // TODO: Start S Pen hover detection logic here.
        // TODO: If doing long work, consider starting as a Foreground Service
        //       with a persistent notification.

        return START_STICKY // Or START_NOT_STICKY if you don't want it to auto-restart
    }

    private fun showOverlay() {
        // You can inflate a custom layout or create a view programmatically
        // For simplicity, let's create a TextView programmatically for now.
        val dynamicOverlayView = TextView(this).apply {
            text = "Hover Service Active!"
            setBackgroundColor(Color.argb(200, 60, 60, 60)) // Semi-transparent dark gray
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12) // Use dp later if needed, this is pixels
            // You might want to convert dp to pixels for padding:
            // val paddingInDp = 8
            // val scale = resources.displayMetrics.density
            // val paddingInPx = (paddingInDp * scale + 0.5f).toInt()
            // setPadding(paddingInPx, paddingInPx, paddingInPx, paddingInPx)
        }
        overlayView = dynamicOverlayView

        // Define layout parameters for the overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // Width
            WindowManager.LayoutParams.WRAP_CONTENT, // Height
            // Window type: TYPE_APPLICATION_OVERLAY is preferred for Oreo (API 26) and above.
            // It requires SYSTEM_ALERT_WINDOW permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                // For older versions, TYPE_PHONE might be used, but it has more restrictions
                // and also requires SYSTEM_ALERT_WINDOW.
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // Window flags:
            // FLAG_NOT_FOCUSABLE: The window won't get key input focus (other apps can still be interacted with).
            // FLAG_NOT_TOUCH_MODAL: Allows touch events to go to windows below this one.
            // FLAG_LAYOUT_IN_SCREEN: Allows window to extend outside of the screen.
            // FLAG_WATCH_OUTSIDE_TOUCH: If you want to detect touches outside your window (requires FLAG_NOT_TOUCH_MODAL false or careful setup)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT // Allows for transparency
        ).apply {
            // Position of the overlay window
            gravity = Gravity.TOP or Gravity.START // Position it at top-left initially
            x = 100 // X-coordinate from the left edge
            y = 300 // Y-coordinate from the top edge
        }

        try {
            if (overlayView?.windowToken == null) { // Check if view is not already attached
                windowManager.addView(overlayView, params)
                Log.d(TAG, "Overlay view added successfully.")
            } else {
                Log.d(TAG, "Overlay view is already attached or has a window token.")
                // You might want to update it instead: windowManager.updateViewLayout(overlayView, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view to WindowManager: ${e.message}", e)
            // This might happen if permission is missing despite the check, or other WindowManager issues.
            overlayView = null // Reset if adding failed
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
                Log.d(TAG, "Overlay view removed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}", e)
            }
            overlayView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called, returning null.")
        // We are not using binding, so return null.
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay() // Ensure overlay is removed when service is destroyed
        Log.d(TAG, "onDestroy: Service Stopped and Destroyed")
        // TODO: Clean up S Pen SDK resources here
    }
}