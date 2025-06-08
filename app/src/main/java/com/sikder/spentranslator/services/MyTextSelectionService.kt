package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sikder.spentranslator.MainActivity
import com.sikder.spentranslator.R
import com.sikder.spentranslator.TranslationApiClient
import com.sikder.spentranslator.utils.OcrHelper
import java.util.concurrent.Executors

class MyTextSelectionService : AccessibilityService() {

    private val TAG = "TextSelectionService"
    private var lastSelectedText: CharSequence? = null
    private var lastProcessedTime: Long = 0
    private val DEBOUNCE_THRESHOLD = 500L
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val NOTIFICATION_CHANNEL_ID = "SpentTranslatorChannel"
    private val NOTIFICATION_ID = 1

    companion object {
        const val ACTION_START_FEATURE = "com.sikder.spentranslator.ACTION_START_FEATURE"
        const val ACTION_STOP_FEATURE = "com.sikder.spentranslator.ACTION_STOP_FEATURE"
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.sikder.spentranslator.ACTION_REQUEST_SCREEN_CAPTURE"

        // These variables are static so MainActivity can set them after getting permission
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
            // We no longer need a foreground notification here because the
            // floating widget itself serves as a persistent indicator.
            Log.i(TAG, "Select-to-Translate feature ACTIVATED.")
            // Start the new FloatingControlService to show the widget
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
            // Stop the FloatingControlService to hide the widget
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

        // Using standard text selection properties. OCR/other methods can be added as fallbacks if needed.
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
            Log.i(TAG, "Processing NEW selected text: \"$selectedText\"")
            processText(selectedText.toString())
        }
    }

    private fun processText(text: String) {
        val sharedPreferences = getSharedPreferences("SpentTranslatorPrefs", Context.MODE_PRIVATE)
        val sourceLangCode = sharedPreferences.getString("source_lang_code", "auto") ?: "auto"
        val targetLangCode = sharedPreferences.getString("target_lang_code", TranslateLanguage.BENGALI) ?: TranslateLanguage.BENGALI

        Log.d(TAG, "processText called. Source setting: '$sourceLangCode', Target: '$targetLangCode'")

        if (sourceLangCode == "auto") {
            // Step 1: Identify language first
            TranslationApiClient.identifyLanguage(text) { detectedLangCode ->
                if (detectedLangCode != "und" && detectedLangCode.isNotBlank()) {
                    // Step 2: Translate using the detected language
                    Log.i(TAG, "Language auto-detected as '$detectedLangCode'. Now translating.")
                    TranslationApiClient.translate(text, detectedLangCode, targetLangCode) { translatedText ->
                        showTranslationTooltip(translatedText)
                    }
                } else {
                    Log.w(TAG, "Language could not be auto-detected. Translation cancelled.")
                    // Optionally show a "could not detect" message
                }
            }
        } else {
            // Translate directly if source language is specified
            TranslationApiClient.translate(text, sourceLangCode, targetLangCode) { translatedText ->
                showTranslationTooltip(translatedText)
            }
        }
    }

    private fun showTranslationTooltip(translatedText: String?) {
        if (translatedText != null) {
            Log.i(TAG, "Translated text received, starting HoverTranslateService.")
            val intent = Intent(this, HoverTranslateService::class.java).apply {
                putExtra(HoverTranslateService.EXTRA_TEXT_TO_SHOW, translatedText)
            }
            startService(intent)
        } else {
            Log.e(TAG, "Translation resulted in null.")
            // Optionally show a "translation failed" message
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        this.serviceInfo = info
        Log.i(TAG, "Accessibility Service Connected.")
    }

    override fun onInterrupt() {
        Log.e(TAG, "Accessibility Service Interrupted.")
        stopFeature()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility Service Destroyed.")
        stopFeature()
    }
}