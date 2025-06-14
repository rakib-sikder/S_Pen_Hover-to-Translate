package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sikder.spentranslator.TranslationApiClient
import java.util.concurrent.Executors

class MyTextSelectionService : AccessibilityService() {

    private val TAG = "TextSelectionService"
    private var lastSelectedText: CharSequence? = null
    private var lastProcessedTime: Long = 0
    private val DEBOUNCE_THRESHOLD = 500L
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    companion object {
        const val ACTION_START_FEATURE = "com.sikder.spentranslator.ACTION_START_FEATURE"
        const val ACTION_STOP_FEATURE = "com.sikder.spentranslator.ACTION_STOP_FEATURE"
        var overlaysDisabledForTesting = true
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.sikder.spentranslator.ACTION_REQUEST_SCREEN_CAPTURE"

        var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
        var mediaProjectionIntent: Intent? = null
        var isFeatureActive = false
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FEATURE -> startFeature()
            ACTION_STOP_FEATURE -> stopFeature()
        }
        return START_STICKY
    }

    private fun startFeature() {
        if (!isFeatureActive) {
            isFeatureActive = true
            Log.i(TAG, "Select-to-Translate feature ACTIVATED.")
            val widgetIntent = Intent(this, FloatingControlService::class.java).apply {
                action = FloatingControlService.ACTION_SHOW
            }
            startService(widgetIntent)
        }
    }

    private fun stopFeature() {
        if (isFeatureActive) {
            isFeatureActive = false
            Log.i(TAG, "Select-to-Translate feature DEACTIVATED.")
            val widgetIntent = Intent(this, FloatingControlService::class.java).apply {
                action = FloatingControlService.ACTION_HIDE
            }
            startService(widgetIntent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isFeatureActive || event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < DEBOUNCE_THRESHOLD) {
            return
        }
        lastProcessedTime = currentTime

        val sourceNode: AccessibilityNodeInfo = event.source ?: return
        var selectedText: CharSequence? = null

        val nodeText = sourceNode.text
        if (sourceNode.textSelectionStart != -1 && sourceNode.textSelectionEnd != -1 &&
            sourceNode.textSelectionStart < sourceNode.textSelectionEnd && nodeText != null &&
            sourceNode.textSelectionEnd <= nodeText.length) {
            try {
                selectedText = nodeText.subSequence(sourceNode.textSelectionStart, sourceNode.textSelectionEnd)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting subsequence from selection indices", e)
            }
        }

        if (!selectedText.isNullOrEmpty() && selectedText.toString() != lastSelectedText?.toString()) {
            lastSelectedText = selectedText
            val textToProcess = selectedText.toString()

            // *** CHANGED: Moved the processing to a background thread ***
            backgroundExecutor.execute {
                processText(textToProcess)
            }
        }
    }

    private fun processText(text: String) {
        val sharedPreferences = getSharedPreferences("SpentTranslatorPrefs", Context.MODE_PRIVATE)
        val sourceLangCode = sharedPreferences.getString("source_lang_code", "auto") ?: "auto"
        val targetLangCode = sharedPreferences.getString("target_lang_code", TranslateLanguage.BENGALI) ?: TranslateLanguage.BENGALI

        if (sourceLangCode == "auto") {
            TranslationApiClient.identifyLanguage(text) { detectedLangCode ->
                if (detectedLangCode != "und" && detectedLangCode.isNotBlank()) {
                    TranslationApiClient.translate(text, detectedLangCode, targetLangCode) { translatedText ->
                        showTranslationTooltip(translatedText)
                    }
                } else {
                    Log.w(TAG, "Language could not be auto-detected.")
                }
            }
        } else {
            TranslationApiClient.translate(text, sourceLangCode, targetLangCode) { translatedText ->
                showTranslationTooltip(translatedText)
            }
        }
    }

    private fun showTranslationTooltip(translatedText: String?) {
        if (translatedText != null) {
            val intent = Intent(this, HoverTranslateService::class.java).apply {
                putExtra(HoverTranslateService.EXTRA_TEXT_TO_SHOW, translatedText)
            }
            startService(intent)
        } else {
            Log.e(TAG, "Translation resulted in null.")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
//            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
//                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        this.serviceInfo = info
    }

    override fun onInterrupt() {
        // CHANGED: Simply log the event. Do not call stopFeature().
        // The system or MainActivity will handle the shutdown.
        Log.e(TAG, "Accessibility Service Interrupted.")
        isFeatureActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // CHANGED: The service's only responsibility on destroy is to clean up itself.
        // It should not tell other services to stop.
        isFeatureActive = false
        backgroundExecutor.shutdown()
        Log.i(TAG, "Accessibility Service Destroyed.")
        // REMOVED the call to stopFeature()
    }
}