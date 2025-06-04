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
import android.widget.TextView
import com.sikder.spentranslator.R

class InstructionTooltipService : Service() {

    private lateinit var windowManager: WindowManager
    private var instructionView: View? = null
    private val TAG = "InstructionTooltip"
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_SHOW_INSTRUCTION = "com.sikder.spentranslator.ACTION_SHOW_INSTRUCTION"
        const val ACTION_SHOW_SELECTED_TEXT = "com.sikder.spentranslator.ACTION_SHOW_SELECTED_TEXT"
        const val ACTION_HIDE = "com.sikder.spentranslator.ACTION_HIDE"
        const val EXTRA_SELECTED_TEXT = "extra_selected_text"

        private const val HIDE_DELAY_INSTRUCTION_MS = 10000L // 10 seconds for initial instruction
        private const val HIDE_DELAY_FEEDBACK_MS = 3000L  // 3 seconds for "you selected" feedback
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action: ${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW_INSTRUCTION -> {
                val instruction = getString(R.string.instruction_please_select)
                showOrUpdateView(instruction, HIDE_DELAY_INSTRUCTION_MS)
            }
            ACTION_SHOW_SELECTED_TEXT -> {
                val selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT) ?: "text"
                val feedback = getString(R.string.feedback_you_selected, selectedText)
                showOrUpdateView(feedback, HIDE_DELAY_FEEDBACK_MS)
            }
            ACTION_HIDE -> {
                hideViewAndStop()
            }
        }
        return START_NOT_STICKY // Don't restart if killed, it's command-driven
    }

    private fun showOrUpdateView(text: String, hideDelay: Long) {
        if (instructionView == null) {
            Log.d(TAG, "Creating new instruction view.")
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            instructionView = inflater.inflate(R.layout.instruction_tooltip_layout, null)

            val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // Make it not touchable
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = 200 // Offset from bottom

            try {
                windowManager.addView(instructionView, params)
                Log.i(TAG, "Instruction view added.")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding instruction view to window manager", e)
                instructionView = null // Ensure it's null if addView failed
                return
            }
        }

        val textView = instructionView?.findViewById<TextView>(R.id.instruction_text)
        textView?.text = text
        Log.d(TAG, "Instruction view text updated to: $text")

        // Schedule to hide
        handler.removeCallbacksAndMessages(null) // Remove previous hide callbacks
        handler.postDelayed({ hideViewAndStop() }, hideDelay)
    }

    private fun hideViewAndStop() {
        instructionView?.let {
            try {
                windowManager.removeView(it)
                Log.i(TAG, "Instruction view removed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing instruction view", e)
            }
            instructionView = null
        }
        stopSelf() // Stop the service as its job is done
        Log.d(TAG, "Service stopping.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Ensure view is removed if service is destroyed unexpectedly
        hideViewAndStop()
        Log.d(TAG, "onDestroy")
    }
}