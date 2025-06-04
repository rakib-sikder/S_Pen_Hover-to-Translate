package com.sikder.spentranslator.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Button
import androidx.annotation.RequiresApi
import com.sikder.spentranslator.R // Make sure R is imported correctly

class HoverTranslateService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private val TAG = "HoverTranslateService"
    private val HIDE_DELAY_MS = 5000L // Hide after 5 seconds
    private val handler = Handler(Looper.getMainLooper())
    private var currentText: String? = null


    companion object {
        const val EXTRA_TEXT_TO_SHOW = "extra_text_to_show"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We are not using binding
    }

    override fun onCreate() {
        super.onCreate()
        // ADD THIS LOG
        Log.d(TAG, "onCreate called")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ADD THIS LOG AT THE VERY BEGINNING
        Log.d(TAG, "onStartCommand called. Intent: $intent, Flags: $flags, StartId: $startId")

        val textToShow = intent?.getStringExtra(EXTRA_TEXT_TO_SHOW)
        Log.d(TAG, "Text received from intent: \"$textToShow\"")

        if (textToShow.isNullOrBlank()) {
            Log.w(TAG, "No text to show, stopping service or hiding view.")
            hideTooltipAndStop()
            return START_NOT_STICKY
        }

        currentText = textToShow
        Log.i(TAG, "Calling showOrUpdateTooltip with text: \"$currentText\"")
        showOrUpdateTooltip(currentText!!)
        scheduleHideTooltip()
        return START_STICKY
    }
    @RequiresApi(Build.VERSION_CODES.S)
    private fun showOrUpdateTooltip(text: String) {
        Log.d(TAG, "showOrUpdateTooltip called with text: \"$text\"")
        if (floatingView == null) {
            Log.d(TAG, "floatingView is null, creating new view.")
            // ... existing inflater and params code ...
            try {
                Log.d(TAG, "Attempting to addView to windowManager.")
                windowManager.addView(floatingView, params as ViewGroup.LayoutParams?)
                Log.i(TAG, "View added to windowManager successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding view to window manager", e)
                return
            }
        } else {
            Log.d(TAG, "floatingView already exists, updating text.")
        }
        // ... existing code to update text and close button ...
    }


    private fun scheduleHideTooltip() {
        handler.removeCallbacksAndMessages(null) // Remove previous callbacks
        handler.postDelayed({
            hideTooltipAndStop()
        }, HIDE_DELAY_MS)
    }

    private fun hideTooltipAndStop() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view from window manager", e)
            }
            floatingView = null
        }
        // Optionally stop the service if it's no longer needed
        // stopSelf() // Consider if you want the service to stop completely or just hide the view
        Log.i(TAG, "Tooltip hidden.")
    }


    override fun onDestroy() {
        super.onDestroy()
        hideTooltipAndStop() // Ensure view is removed when service is destroyed
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "HoverTranslateService Destroyed")
    }
}