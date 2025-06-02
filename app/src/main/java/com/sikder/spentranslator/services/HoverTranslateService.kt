package com.sikder.spentranslator.services

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.sikder.spentranslator.MainActivity // To open app from notification
import com.sikder.spentranslator.R // For notification icon (add a small icon to res/drawable or use mipmap/ic_launcher)

class HoverTranslateService : Service() {

    private val TAG = "HoverTranslateService"
    private val NOTIFICATION_CHANNEL_ID = "HoverTranslateServiceChannel"
    private val NOTIFICATION_ID = 1337 // Unique ID for the notification

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayTextView: TextView? = null

    // TODO: S Pen SDK related variables and listeners will go here

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service Created")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        // TODO: S Pen SDK Initialization will go here
        Log.w(TAG, "S Pen SDK specific initialization needs to be implemented.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Service Started. Action: ${intent?.action}")

        // --- Start as a Foreground Service ---
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SPen Translator Active")
            .setContentText("Hover service is running.")
            // IMPORTANT: Replace R.mipmap.ic_launcher with a proper small notification icon (e.g., in res/drawable)
            // For example, create a simple ic_notification.xml in res/drawable
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable by swipe
            .build()

        // Service type for media projection is required on Android Q (API 29) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Service started in foreground.")
        // --- End Foreground Service Setup ---

        // Show overlay if permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission is not granted. Cannot show overlay.")
        } else {
            if (overlayView == null) {
                showOverlay()
            }
        }

        // Check if this intent is for starting MediaProjection
        if (intent?.action == ACTION_START_MEDIA_PROJECTION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = intent.getParcelableExtra(EXTRA_DATA_INTENT)

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection data received by service. Ready to start capture.")
                // TODO: Get MediaProjectionManager instance
                // val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // TODO: Get MediaProjection object:
                // val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                // TODO: Use this mediaProjection object to start screen capture (e.g., with ImageReader and VirtualDisplay)
                // For now, we just log. The next step would be to implement startScreenCapture(mediaProjection)
                overlayTextView?.post { overlayTextView?.text = "Screen Capture Ready!"}

            } else {
                Log.e(TAG, "MediaProjection data is invalid or permission denied. ResultCode: $resultCode")
                overlayTextView?.post { overlayTextView?.text = "Screen Capture Failed!"}
                // stopSelf() // Optionally stop service if screen capture is essential and failed
            }
        }

        // TODO: Start S Pen hover detection logic using S Pen SDK.

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Hover Translate Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound/vibration by default
            ).apply {
                description = "Channel for SPen Translator foreground service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    private fun showOverlay() {
        overlayTextView = TextView(this).apply {
            text = "S Pen Service Active..." // Initial text
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            setTextColor(Color.WHITE)
            val paddingInDp = 8; val scale = resources.displayMetrics.density
            val paddingInPx = (paddingInDp * scale + 0.5f).toInt()
            setPadding(paddingInPx, paddingInPx, paddingInPx, paddingInPx)
            Log.d(TAG, "Overlay TextView created.")
        }
        overlayView = overlayTextView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 150
        }

        try {
            if (overlayView?.windowToken == null && overlayView?.parent == null) {
                windowManager.addView(overlayView, params)
                Log.d(TAG, "Overlay view added successfully.")
            } else {
                Log.d(TAG, "Overlay view might already be attached or has a window token.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view: ${e.message}", e)
            overlayView = null; overlayTextView = null
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                if (it.parent != null) { // Check if view is still attached
                    windowManager.removeView(it)
                    Log.d(TAG, "Overlay view removed successfully.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}", e)
            }
        }
        overlayView = null
        overlayTextView = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called, returning null.")
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE) // Use this to remove notification correctly
        Log.d(TAG, "onDestroy: Service Stopped and Destroyed")
        // TODO: S Pen SDK Cleanup (unregister listeners, release resources)
        Log.w(TAG, "S Pen SDK specific cleanup needs to be implemented.")
    }

    companion object {
        const val ACTION_START_MEDIA_PROJECTION = "com.sikder.spentranslator.ACTION_START_MEDIA_PROJECTION"
        const val EXTRA_RESULT_CODE = "com.sikder.spentranslator.RESULT_CODE"
        const val EXTRA_DATA_INTENT = "com.sikder.spentranslator.DATA_INTENT"
    }
}