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
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.sikder.spentranslator.R

class HoverTranslateService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private val TAG = "HoverTranslateService"
    private val HIDE_DELAY_MS = 5000L
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_TEXT_TO_SHOW = "extra_text_to_show"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val textToShow = intent?.getStringExtra(EXTRA_TEXT_TO_SHOW)
        if (textToShow.isNullOrBlank()) {
            hideTooltipAndStop()
            return START_NOT_STICKY
        }
        showOrUpdateTooltip(textToShow)
        return START_STICKY
    }

    private fun showOrUpdateTooltip(text: String) {
        if (floatingView == null) {
            floatingView = LayoutInflater.from(this).inflate(R.layout.tooltip_layout, null)
            val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 100
            try {
                windowManager.addView(floatingView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding view to window manager", e)
                floatingView = null
                return
            }
        }
        floatingView?.findViewById<TextView>(R.id.tooltip_text)?.text = text
        floatingView?.findViewById<Button>(R.id.tooltip_close_button)?.setOnClickListener {
            hideTooltipAndStop()
        }
        // Reschedule hide timer every time new text is shown
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ hideTooltipAndStop() }, HIDE_DELAY_MS)
    }

    private fun hideTooltipAndStop() {
        floatingView?.let {
            if (it.windowToken != null) {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing view", e)
                }
            }
            floatingView = null
        }
        // stopSelf() // Don't stop the service, just hide the view.
        // The service can be reused for the next translation.
        // It will be stopped by the system if idle for a long time.
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        hideTooltipAndStop() // Ensure view is removed
    }
}