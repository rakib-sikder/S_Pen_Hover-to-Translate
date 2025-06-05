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
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sikder.spentranslator.MainActivity
import com.sikder.spentranslator.R
import com.sikder.spentranslator.TranslationApiClient

class MyTextSelectionService : AccessibilityService() {

    private val TAG = "TextSelectionService"
    private var lastSelectedText: CharSequence? = null
    private var lastProcessedTime: Long = 0
    private val DEBOUNCE_THRESHOLD = 500L

    private val NOTIFICATION_CHANNEL_ID = "SpentTranslatorChannel"
    private val NOTIFICATION_ID = 1

    companion object {
        const val ACTION_START_FEATURE = "com.sikder.spentranslator.ACTION_START_FEATURE"
        const val ACTION_STOP_FEATURE = "com.sikder.spentranslator.ACTION_STOP_FEATURE"
        // This static flag is a simple way for MainActivity to know the state.
        // It's reset if the app process is killed. The BroadcastReceiver in MainActivity
        // helps keep the UI in sync if the service is stopped by the system.
        var isFeatureActive = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_FEATURE -> {
                if (!isFeatureActive) {
                    isFeatureActive = true
                    startForegroundServiceNotification()
                    Log.i(TAG, "Select-to-Translate feature ACTIVATED.")
                    val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                        action = InstructionTooltipService.ACTION_SHOW_INSTRUCTION
                    }
                    startService(instructionIntent)
                    // Notify MainActivity to update its UI
                    updateActivityUi()
                }
            }
            ACTION_STOP_FEATURE -> {
                if (isFeatureActive) {
                    isFeatureActive = false
                    stopForeground(true)
                    Log.i(TAG, "Select-to-Translate feature DEACTIVATED.")
                    val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                        action = InstructionTooltipService.ACTION_HIDE
                    }
                    startService(instructionIntent)
                    // Notify MainActivity to update its UI
                    updateActivityUi()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_select_to_translate_active_title))
            .setContentText(getString(R.string.notification_select_to_translate_active_text))
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            isFeatureActive = false // Revert state if foreground fails
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name) + " Service Channel"
            val descriptionText = "Channel for SpentTranslator foreground service notification"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isFeatureActive || event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return
        }

        Log.i(TAG, ">>> Text Selection Event Detected (Package: ${event.packageName}) <<<")
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < DEBOUNCE_THRESHOLD) {
            Log.d(TAG, "Debounced event.")
            return
        }
        lastProcessedTime = currentTime

        val sourceNode: AccessibilityNodeInfo? = event.source
        if (sourceNode == null) {
            Log.w(TAG, "Source node is null.")
            return
        }

        sourceNode.let { node ->
            var selectedText: CharSequence? = null

            // Use the standard method to get selected text
            if (node.textSelectionStart != -1 && node.textSelectionEnd != -1 &&
                node.textSelectionStart < node.textSelectionEnd && node.text != null &&
                node.textSelectionEnd <= node.text.length) {
                try {
                    selectedText = node.text?.subSequence(node.textSelectionStart, node.textSelectionEnd)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting subsequence from selection indices", e)
                }
            }

            if (!selectedText.isNullOrEmpty() && selectedText.toString().isNotBlank()) {
                if (selectedText != lastSelectedText) {
                    lastSelectedText = selectedText
                    Log.i(TAG, "Processing NEW selected text: \"$selectedText\"")

                    // Show "You selected: [text]" feedback
                    val feedbackIntent = Intent(this, InstructionTooltipService::class.java).apply {
                        action = InstructionTooltipService.ACTION_SHOW_SELECTED_TEXT
                        putExtra(InstructionTooltipService.EXTRA_SELECTED_TEXT, selectedText.toString())
                    }
                    startService(feedbackIntent)
                    processText(selectedText.toString())
                } else {
                    Log.i(TAG, "Selected text is same as last, showing feedback only.")
                    val feedbackIntent = Intent(this, InstructionTooltipService::class.java).apply {
                        action = InstructionTooltipService.ACTION_SHOW_SELECTED_TEXT
                        putExtra(InstructionTooltipService.EXTRA_SELECTED_TEXT, selectedText.toString())
                    }
                    startService(feedbackIntent)
                }
            } else {
                Log.d(TAG, "No valid text extracted to process.")
            }
        }
    }

    private fun processText(text: String) {
        // Read language preferences saved by MainActivity
        val sharedPreferences = getSharedPreferences("SpentTranslatorPrefs", Context.MODE_PRIVATE)
        val sourceLang = sharedPreferences.getString("source_lang_code", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        val targetLang = sharedPreferences.getString("target_lang_code", TranslateLanguage.BENGALI) ?: TranslateLanguage.BENGALI

        Log.d(TAG, "processText called for '$text' from $sourceLang to $targetLang")

        TranslationApiClient.translate(text, sourceLang, targetLang) { translatedText ->
            if (translatedText != null) {
                Log.i(TAG, "Translated text received, starting HoverTranslateService.")
                val intent = Intent(this, HoverTranslateService::class.java).apply {
                    putExtra(HoverTranslateService.EXTRA_TEXT_TO_SHOW, translatedText)
                }
                try {
                    startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start HoverTranslateService.", e)
                }
            } else {
                Log.e(TAG, "Translation failed for: \"$text\"")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        this.serviceInfo = info
        Log.i(TAG, "Accessibility Service Connected and configured (awaiting start command).")
        updateActivityUi()
    }

    override fun onInterrupt() {
        Log.e(TAG, "Accessibility Service Interrupted. Stopping feature.")
        handleStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility Service Destroyed. Stopping feature.")
        handleStop()
    }

    private fun handleStop() {
        if (isFeatureActive) {
            isFeatureActive = false
            stopForeground(true)
            val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                action = InstructionTooltipService.ACTION_HIDE
            }
            startService(instructionIntent)
            updateActivityUi()
        }
    }

    private fun updateActivityUi() {
        val intent = Intent(MainActivity.ACTION_UPDATE_UI)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}