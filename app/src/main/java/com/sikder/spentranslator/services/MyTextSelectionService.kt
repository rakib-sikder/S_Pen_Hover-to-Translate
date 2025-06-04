package com.sikder.spentranslator.services

// ... other imports remain the same ...
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
                    // Show initial instruction
                    Log.d(TAG, "Attempting to show initial instruction tooltip...") // <<<< ADD/VERIFY THIS LOG
                    val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                        action = InstructionTooltipService.ACTION_SHOW_INSTRUCTION
                    }
                    startService(instructionIntent)
                }
                if (!isFeatureActive) {
                    isFeatureActive = true
                    startForegroundServiceNotification()
                    Log.i(TAG, "Select-to-Translate feature ACTIVATED.")
                    // Show initial instruction
                    val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                        action = InstructionTooltipService.ACTION_SHOW_INSTRUCTION
                    }
                    startService(instructionIntent)
                }
            }

            ACTION_STOP_FEATURE -> {
                if (isFeatureActive) {
                    isFeatureActive = false
                    stopForeground(true)
                    Log.i(TAG, "Select-to-Translate feature DEACTIVATED.")
                    // Hide instruction
                    val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                        action = InstructionTooltipService.ACTION_HIDE
                    }
                    startService(instructionIntent)
                }
            }
        }
        return START_STICKY
    }

    // ... startForegroundServiceNotification() and createNotificationChannel() methods remain the same ...
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SpentTranslator Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isFeatureActive) {
            return
        }
        Log.d(TAG, "onAccessibilityEvent (active): eventType=${event?.eventType}, pkg=${event?.packageName}")
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                Log.i(TAG, ">>> TYPE_VIEW_TEXT_SELECTION_CHANGED event detected (active) <<<")
                // ... (debounce logic remains the same) ...
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
                // ... (existing sourceNode.let block, slightly modified) ...
                sourceNode.let { node ->
                    var selectedText: CharSequence? = null
                    // ... (existing text extraction logic using textSelectionStart/End) ...
                    if (node.textSelectionStart != -1 && node.textSelectionEnd != -1 &&
                        node.textSelectionStart < node.textSelectionEnd && node.text != null &&
                        node.textSelectionEnd <= node.text.length) {
                        selectedText = node.text?.subSequence(node.textSelectionStart, node.textSelectionEnd)
                    }

                    if (!selectedText.isNullOrEmpty() && selectedText.toString().isNotBlank()) {
                        if (selectedText != lastSelectedText) {
                            lastSelectedText = selectedText // Update lastSelectedText here

                            Log.i(TAG, "Captured selected text: \"$selectedText\"")

                            // Show "You selected: [text]" in InstructionTooltipService
                            val feedbackIntent = Intent(this, InstructionTooltipService::class.java).apply {
                                action = InstructionTooltipService.ACTION_SHOW_SELECTED_TEXT
                                putExtra(InstructionTooltipService.EXTRA_SELECTED_TEXT, selectedText.toString())
                            }
                            startService(feedbackIntent)

                            // Proceed to translate
                            processText(selectedText.toString())
                        } else {
                            Log.i(TAG, "Selected text \"$selectedText\" is same as last, skipping actual processing but will show feedback.")
                            // Optionally, still show feedback even if text is same as last for immediate confirmation
                            val feedbackIntent = Intent(this, InstructionTooltipService::class.java).apply {
                                action = InstructionTooltipService.ACTION_SHOW_SELECTED_TEXT
                                putExtra(InstructionTooltipService.EXTRA_SELECTED_TEXT, selectedText.toString())
                            }
                            startService(feedbackIntent)
                        }
                    } else {
                        Log.d(TAG, "No valid text extracted to process for feedback/translation.")
                    }
                }
            }
        }
    }

    // ... processText(), onServiceConnected(), onInterrupt(), onDestroy() methods remain largely the same ...
    // Make sure onServiceConnected sends the broadcast, and onInterrupt/onDestroy also call ACTION_HIDE for InstructionTooltipService
    private fun processText(text: String) {
        Log.d(TAG, "processText called with text: \"$text\"")
        // TranslationApiClient.translate(...) will call HoverTranslateService
        TranslationApiClient.translate(text, "en", "bn") { translatedText -> // Changed to bn
            if (translatedText != null) {
                Log.i(TAG, "Translated text: \"$translatedText\" for original: \"$text\"")
                val intent = Intent(this, HoverTranslateService::class.java).apply {
                    putExtra(HoverTranslateService.EXTRA_TEXT_TO_SHOW, translatedText)
                }
                try {
                    Log.d(TAG, "Attempting to start HoverTranslateService...")
                    startService(intent)
                    Log.i(TAG, "HoverTranslateService started successfully.")
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
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE_UI))
    }

    override fun onInterrupt() {
         // Important to call super
        Log.e(TAG, "Accessibility Service Interrupted. Stopping feature.")
        if (isFeatureActive) {
            isFeatureActive = false
            stopForeground(true)
            val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                action = InstructionTooltipService.ACTION_HIDE
            }
            startService(instructionIntent)
            sendBroadcast(Intent(MainActivity.ACTION_UPDATE_UI))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility Service Destroyed. Stopping feature.")
        if (isFeatureActive) { // Check if it was active to avoid issues
            isFeatureActive = false
            stopForeground(true) // This might already be called if onInterrupt was called.
            val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                action = InstructionTooltipService.ACTION_HIDE
            }
            startService(instructionIntent) // Attempt to hide if it was showing
        }
        // No need to send broadcast from onDestroy as activity might not be listening
    }
}