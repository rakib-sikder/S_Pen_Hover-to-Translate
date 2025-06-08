package com.sikder.spentranslator.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sikder.spentranslator.Language
import com.sikder.spentranslator.R

class FloatingControlService : Service() {

    private val TAG = "FloatingControlService"
    private lateinit var windowManager: WindowManager
    private var floatingWidget: View? = null
    private lateinit var widgetParams: WindowManager.LayoutParams
    private lateinit var sharedPreferences: SharedPreferences

    private val supportedLanguages = listOf(
        // The first item is our special "Auto" option
        Language("Auto Detect", "auto"),
        Language("English", TranslateLanguage.ENGLISH),
        Language("Bengali", TranslateLanguage.BENGALI),
        Language("Spanish", TranslateLanguage.SPANISH),
        Language("Hindi", TranslateLanguage.HINDI),
        Language("Arabic", TranslateLanguage.ARABIC),
        Language("French", TranslateLanguage.FRENCH)
        // Add more languages to match MainActivity
    )

    private val targetLanguages = supportedLanguages.filter { it.code != "auto" } // Target cannot be "auto"

    companion object {
        const val ACTION_SHOW = "com.sikder.spentranslator.ACTION_SHOW_WIDGET"
        const val ACTION_HIDE = "com.sikder.spentranslator.ACTION_HIDE_WIDGET"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sharedPreferences = getSharedPreferences("SpentTranslatorPrefs", Context.MODE_PRIVATE)
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action: ${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW -> {
                if (floatingWidget == null) {
                    initializeFloatingWidget()
                }
            }
            ACTION_HIDE -> {
                removeFloatingWidget()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun removeFloatingWidget() {
        floatingWidget?.let {
            if (it.windowToken != null) {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing widget", e)
                }
            }
            floatingWidget = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeFloatingWidget() {
        val inflater = LayoutInflater.from(this)
        floatingWidget = inflater.inflate(R.layout.floating_control_layout, null)

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        widgetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Allows touches to pass through to apps below
            PixelFormat.TRANSLUCENT
        )
        widgetParams.gravity = Gravity.TOP or Gravity.START
        widgetParams.x = 100
        widgetParams.y = 100

        try {
            windowManager.addView(floatingWidget, widgetParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding widget view to window manager", e)
            return
        }

        // --- Setup UI elements inside the widget ---
        val sourceSpinner = floatingWidget?.findViewById<Spinner>(R.id.spinner_source_lang_widget)
        val targetSpinner = floatingWidget?.findViewById<Spinner>(R.id.spinner_target_lang_widget)
        val swapButton = floatingWidget?.findViewById<ImageView>(R.id.swap_languages_button)
        val closeButton = floatingWidget?.findViewById<ImageView>(R.id.close_widget_button)
        val dragHandle = floatingWidget?.findViewById<ImageView>(R.id.drag_handle)

        // Populate Spinners
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedLanguages.map { it.displayName })
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceSpinner?.adapter = sourceAdapter

        val targetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, targetLanguages.map { it.displayName })
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        targetSpinner?.adapter = targetAdapter

        // Restore and set saved languages
        val savedSourceCode = sharedPreferences.getString("source_lang_code", "auto")
        val savedTargetCode = sharedPreferences.getString("target_lang_code", TranslateLanguage.BENGALI)

        sourceSpinner?.setSelection(supportedLanguages.indexOfFirst { it.code == savedSourceCode }.coerceAtLeast(0))
        targetSpinner?.setSelection(targetLanguages.indexOfFirst { it.code == savedTargetCode }.coerceAtLeast(0))

        // Set listeners
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                saveLanguagePreferences()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        sourceSpinner?.onItemSelectedListener = listener
        targetSpinner?.onItemSelectedListener = listener

        swapButton?.setOnClickListener {
            val sourcePos = sourceSpinner?.selectedItemPosition ?: 0
            val targetPos = targetSpinner?.selectedItemPosition ?: 0
            // Only swap if source is not "Auto Detect"
            if (sourcePos > 0) {
                sourceSpinner?.setSelection(targetPos + 1) // +1 to account for "Auto" in source list
                targetSpinner?.setSelection(sourcePos - 1) // -1 because target list has no "Auto"
                saveLanguagePreferences()
            }
        }

        closeButton?.setOnClickListener {
            // This button should stop the whole feature
            // We tell MyTextSelectionService to stop, which will in turn stop this service
            val stopIntent = Intent(this, MyTextSelectionService::class.java).apply {
                action = MyTextSelectionService.ACTION_STOP_FEATURE
            }
            startService(stopIntent)
        }

        // Add drag functionality
        dragHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = widgetParams.x
                        initialY = widgetParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        widgetParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        widgetParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingWidget, widgetParams)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun saveLanguagePreferences() {
        val sourceSpinner = floatingWidget?.findViewById<Spinner>(R.id.spinner_source_lang_widget)
        val targetSpinner = floatingWidget?.findViewById<Spinner>(R.id.spinner_target_lang_widget)

        if (sourceSpinner == null || targetSpinner == null) return

        val sourceLangCode = supportedLanguages[sourceSpinner.selectedItemPosition].code
        val targetLangCode = targetLanguages[targetSpinner.selectedItemPosition].code

        Log.d(TAG, "Saving language preferences from widget: Source=$sourceLangCode, Target=$targetLangCode")
        sharedPreferences.edit()
            .putString("source_lang_code", sourceLangCode)
            .putString("target_lang_code", targetLangCode)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingWidget()
        Log.d(TAG, "onDestroy")
    }
}