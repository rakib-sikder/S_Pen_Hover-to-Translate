package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat // Import this
import com.sikder.spentranslator.MainActivity // Import MainActivity
import com.sikder.spentranslator.R // Import R
import com.sikder.spentranslator.TranslationApiClient
// HoverTranslateService is already imported

class MyTextSelectionService : AccessibilityService() {

    private val TAG = "TextSelectionService"
    private var lastSelectedText: CharSequence? = null
    private var lastProcessedTime: Long = 0
    private val DEBOUNCE_THRESHOLD = 500L

    private val NOTIFICATION_CHANNEL_ID = "SpentTranslatorChannel"
    private val NOTIFICATION_ID = 1

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service onServiceConnected.")

        // --- Start Foreground Service Logic ---
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SpentTranslator Active")
            .setContentText("Text selection service is running.")
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your actual app icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification persistent
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            // Handle different exceptions based on Android version if needed
            // For example, ForegroundServiceStartNotAllowedException on Android 12+ if conditions not met
            // Or MissingForegroundServiceTypeException if type is not declared in manifest for targetSdk 34+
        }
        // --- End Foreground Service Logic ---


        // Configure the service (your existing logic)
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        this.serviceInfo = info
        Log.i(TAG, "Accessibility Service Configured.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SpentTranslator Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW to minimize interruption
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    // ... (onAccessibilityEvent and processText methods remain the same as your last version) ...
    // Make sure they are here:
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.d(TAG, "onAccessibilityEvent received: eventType=${event?.eventType}, packageName=${event?.packageName}")

        if (event == null) {
            Log.d(TAG, "Event is null, returning.")
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                Log.i(TAG, ">>> TYPE_VIEW_TEXT_SELECTION_CHANGED event detected <<<")
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProcessedTime < DEBOUNCE_THRESHOLD) {
                    Log.d(TAG, "Debounced event, returning.")
                    return
                }
                lastProcessedTime = currentTime

                val sourceNode: AccessibilityNodeInfo? = event.source
                if (sourceNode == null) {
                    Log.w(TAG, "Source node is null, returning.")
                    return
                }
                Log.d(TAG, "Source node className: ${sourceNode.className}")

                sourceNode.let { node ->
                    var selectedText: CharSequence? = null
                    Log.d(TAG, "Attempting to extract selected text...")
                    Log.d(TAG, "Node text: \"${node.text}\", SelectionStart: ${node.textSelectionStart}, SelectionEnd: ${node.textSelectionEnd}")

                    if (node.textSelectionStart != -1 && node.textSelectionEnd != -1 &&
                        node.textSelectionStart < node.textSelectionEnd && node.text != null &&
                        node.textSelectionEnd <= node.text.length) {
                        selectedText = node.text?.subSequence(node.textSelectionStart, node.textSelectionEnd)
                        if (!selectedText.isNullOrEmpty()) {
                            Log.i(TAG, "Extracted selected text using selection indices: \"$selectedText\"")
                        } else {
                            Log.w(TAG, "Subsequence from selection indices was null or empty.")
                        }
                    } else {
                        Log.w(TAG, "Conditions for selection indices not met.")
                        if(node.text == null) Log.d(TAG, "Reason: node.text is null")
                        if(node.textSelectionStart == -1) Log.d(TAG, "Reason: node.textSelectionStart is -1")
                    }

                    if (!selectedText.isNullOrEmpty() && selectedText.toString().isNotBlank()) {
                        if (selectedText != lastSelectedText) {
                            lastSelectedText = selectedText
                            Log.i(TAG, "Processing NEW selected text: \"$selectedText\"")
                            processText(selectedText.toString())
                        } else {
                            Log.i(TAG, "Selected text \"$selectedText\" is the same as last processed, skipping.")
                        }
                    } else {
                        if (selectedText == null) {
                            Log.d(TAG, "No text selected or extracted (selectedText is null).")
                        } else if (selectedText.toString().isBlank()){
                            Log.d(TAG, "Extracted text is blank, not processing.")
                        }
                    }
                }
            }
            else -> {
                // Log.v(TAG, "Ignoring event type: ${AccessibilityEvent.eventTypeToString(event.eventType)}");
            }
        }
    }

    private fun processText(text: String) {
        Log.d(TAG, "processText called with text: \"$text\"")
        TranslationApiClient.translate(text, "en", "es") { translatedText ->
            if (translatedText != null) {
                Log.i(TAG, "Translated text: \"$translatedText\" for original: \"$text\"")
                val intent = Intent(this, HoverTranslateService::class.java).apply {
                    putExtra(HoverTranslateService.EXTRA_TEXT_TO_SHOW, translatedText)
                }
                try {
                    Log.d(TAG, "Attempting to start HoverTranslateService...")
                    startService(intent)
                    Log.i(TAG, "HoverTranslateService started successfully.")
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Could not start HoverTranslateService (IllegalStateException).", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start HoverTranslateService (Exception).", e)
                }
            } else {
                Log.e(TAG, "Translation failed for: \"$text\"")
            }
        }
    }


    override fun onInterrupt() {
        Log.e(TAG, "Accessibility Service Interrupted. Stopping foreground.")
        stopForeground(true) // Stop foreground when service is interrupted
        // You might also call stopSelf() if the service should completely stop
    }

    override fun onDestroy() { // It's good practice to also handle onDestroy
        super.onDestroy()
        Log.i(TAG, "Accessibility Service Destroyed. Stopping foreground.")
        stopForeground(true) // Ensure foreground is stopped
    }
}