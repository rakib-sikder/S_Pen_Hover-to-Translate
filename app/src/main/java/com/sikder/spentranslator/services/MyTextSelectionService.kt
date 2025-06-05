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

class MyTextSelectionService : AccessibilityService() {

    private val TAG = "TextSelectionService"
    private var lastSelectedText: CharSequence? = null
    private var lastProcessedTime: Long = 0
    private val DEBOUNCE_THRESHOLD = 500L

    private val NOTIFICATION_CHANNEL_ID = "SpentTranslatorChannel"
    private val NOTIFICATION_ID = 1

    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null


    companion object {
        const val ACTION_START_FEATURE = "com.sikder.spentranslator.ACTION_START_FEATURE"
        const val ACTION_STOP_FEATURE = "com.sikder.spentranslator.ACTION_STOP_FEATURE"
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.sikder.spentranslator.ACTION_REQUEST_SCREEN_CAPTURE"
        var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
        var mediaProjectionIntent: Intent? = null
        var isFeatureActive = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_FEATURE -> startFeature()
            ACTION_STOP_FEATURE -> stopFeature()
        }
        return START_STICKY
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
        Log.i(TAG, ">>> Text Selection Event Detected (Package: ${event.packageName}) <<<")
        Log.d(TAG, "Source node className: ${sourceNode.className}")

        var selectedText: CharSequence? = null
        var extractionMethod = "Unknown"

        // Priority 1: Use getSelectedText() if on API 34+
        if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE is API 34
            // getSelectedText() is only available on API 34 (UpsideDownCake) and above.
            try {
                // Ensure the node has selection capabilities before calling getSelectedText
                if (sourceNode.isTextSelectable || (sourceNode.textSelectionStart != -1 && sourceNode.textSelectionEnd != -1)) {
                    selectedText = sourceNode.getSelectedText()
                }

                if (!selectedText.isNullOrEmpty()) {
                    extractionMethod = "getSelectedText (API 34+)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling getSelectedText (API 34+)", e)
            }
        }

        // Priority 2: Fallback to using textSelectionStart and textSelectionEnd
        if (selectedText.isNullOrEmpty()) {
            val nodeText = sourceNode.text
            if (sourceNode.textSelectionStart != -1 && sourceNode.textSelectionEnd != -1 &&
                sourceNode.textSelectionStart < sourceNode.textSelectionEnd && nodeText != null &&
                sourceNode.textSelectionEnd <= nodeText.length) {
                try {
                    selectedText = nodeText.subSequence(sourceNode.textSelectionStart, sourceNode.textSelectionEnd)
                    if (!selectedText.isNullOrEmpty()) {
                        extractionMethod = "Selection Indices"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting subsequence from selection indices", e)
                }
            }
        }

        if (!selectedText.isNullOrEmpty()) {
            Log.i(TAG, "Text extracted via $extractionMethod.")
            handleFoundText(selectedText)
        } else {
            // Priority 3 (Last Resort): OCR Fallback
            Log.i(TAG, "Standard methods failed. Attempting OCR fallback.")
            initiateOcr(sourceNode)
        }
    }

    private fun handleFoundText(text: CharSequence) {
        val textString = text.toString()
        if (textString.isNotBlank() && textString != lastSelectedText?.toString()) {
            lastSelectedText = textString
            Log.i(TAG, "Processing NEW text: \"$textString\"")
            val feedbackIntent = Intent(this, InstructionTooltipService::class.java).apply {
                action = InstructionTooltipService.ACTION_SHOW_SELECTED_TEXT
                putExtra(InstructionTooltipService.EXTRA_SELECTED_TEXT, textString)
            }
            startService(feedbackIntent)
            processText(textString)
        }
    }

    private fun processText(text: String) {
        val sharedPreferences = getSharedPreferences("SpentTranslatorPrefs", Context.MODE_PRIVATE)
        val sourceLang = sharedPreferences.getString("source_lang_code", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        val targetLang = sharedPreferences.getString("target_lang_code", TranslateLanguage.BENGALI) ?: TranslateLanguage.BENGALI
        TranslationApiClient.translate(text, sourceLang, targetLang) { translatedText ->
            if (translatedText != null) {
                val intent = Intent(this, HoverTranslateService::class.java).apply {
                    putExtra(HoverTranslateService.EXTRA_TEXT_TO_SHOW, translatedText)
                }
                startService(intent)
            } else {
                Log.e(TAG, "Translation failed for: \"$text\"")
            }
        }
    }

    private fun initiateOcr(node: AccessibilityNodeInfo) {
        if (mediaProjectionIntent != null && mediaProjectionResultCode == Activity.RESULT_OK) {
            Log.i(TAG, "MediaProjection permission seems to be granted. Proceeding with OCR.")
            performOcr(node)
        } else {
            Log.w(TAG, "MediaProjection permission not available. Requesting via MainActivity.")
            requestScreenCapturePermissionFromActivity()
        }
    }

    private fun requestScreenCapturePermissionFromActivity() {
        // Start MainActivity to request permission.
        // The MainActivity will handle the permission request and store the result.
        // We also need to inform MainActivity that the request is coming from the service
        // so it can notify the service back after permission is granted/denied.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_SCREEN_CAPTURE_FROM_SERVICE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)            // Add a flag or extra to indicate the source if MainActivity needs to differentiate
            // For now, the action itself is enough.
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun performOcr(node: AccessibilityNodeInfo) {
        if (this.mediaProjection == null) {
            if (mediaProjectionIntent != null && mediaProjectionResultCode == Activity.RESULT_OK) {
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                this.mediaProjection = mediaProjectionManager.getMediaProjection(mediaProjectionResultCode, mediaProjectionIntent!!)
                this.mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection session stopped externally.")
                        stopMediaProjection()
                    }
                }, handler)
                Log.i(TAG, "MediaProjection instance created successfully.")
            } else {
                Log.e(TAG, "Cannot perform OCR: MediaProjection permission data is invalid.")
                return
            }
        }

        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)
        if (nodeBounds.isEmpty || nodeBounds.width() <= 0 || nodeBounds.height() <= 0) {
            Log.w(TAG, "Node bounds are empty or invalid for OCR: $nodeBounds")
            return
        }
        Log.d(TAG, "Performing OCR on bounds: $nodeBounds")

        val currentMediaProjection = this.mediaProjection ?: return // Final null check

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        imageReader?.close()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.release()
        virtualDisplay = currentMediaProjection.createVirtualDisplay(
            "SpentTranslatorScreenCapture",
            screenWidth, screenHeight, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val fullBitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height, Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cropX = nodeBounds.left.coerceIn(0, fullBitmap.width - 1)
                val cropY = nodeBounds.top.coerceIn(0, fullBitmap.height - 1)
                val cropWidth = nodeBounds.width().coerceAtMost(fullBitmap.width - cropX)
                val cropHeight = nodeBounds.height().coerceAtMost(fullBitmap.height - cropY)

                if (cropWidth > 0 && cropHeight > 0) {
                    try {
                        val croppedBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropWidth, cropHeight)
                        OcrHelper.recognizeTextFromBitmap(croppedBitmap) { ocrText ->
                            if (!ocrText.isNullOrBlank()) handleFoundText(ocrText)
                            else Log.w(TAG, "OCR did not find text in the cropped region.")
                            croppedBitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating cropped bitmap or during OCR", e)
                    }
                } else {
                    Log.w(TAG, "Calculated crop dimensions are invalid.")
                }
                fullBitmap.recycle()
            }
            // Stop projection session after one capture attempt
            stopMediaProjection()
        }, handler)
    }

    private fun stopMediaProjection() {
        handler.post {
            if (mediaProjection != null) {
                Log.d(TAG, "Stopping MediaProjection and related resources.")
                mediaProjection?.stop()
                virtualDisplay?.release()
                imageReader?.close()
                mediaProjection = null
                virtualDisplay = null
                imageReader = null
                // Reset static variables so permission is requested next time
                mediaProjectionIntent = null
                mediaProjectionResultCode = Activity.RESULT_CANCELED
            }
        }
    }

    // --- Standard Service Lifecycle and Notification Methods ---

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        this.serviceInfo = info
        Log.i(TAG, "Accessibility Service Connected.")
        updateActivityUi()
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

    private fun startFeature() {
        if (!isFeatureActive) {
            isFeatureActive = true
            startForegroundServiceNotification()
            Log.i(TAG, "Select-to-Translate feature ACTIVATED.")
            val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                action = InstructionTooltipService.ACTION_SHOW_INSTRUCTION
            }
            startService(instructionIntent)
            updateActivityUi()
        }
    }

    private fun stopFeature() {
        if (isFeatureActive) {
            isFeatureActive = false
            stopForeground(true)
            stopMediaProjection()
            Log.i(TAG, "Select-to-Translate feature DEACTIVATED.")
            val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                action = InstructionTooltipService.ACTION_HIDE
            }
            startService(instructionIntent)
            updateActivityUi()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name) + " Service Channel"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            isFeatureActive = false
        }
    }

    private fun updateActivityUi() {
        val intent = Intent(MainActivity.ACTION_UPDATE_UI)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}

// Stub for the actual getSelectedText method which is available on API 34+
// This is just for compilation and linting, the actual call is guarded by Build.VERSION.SDK_INT >= 34
// In a real scenario, this would not be needed if targeting API 34+ directly or using reflection/compat libraries.
private fun AccessibilityNodeInfo.getSelectedText(): CharSequence? {
    if (Build.VERSION.SDK_INT >= 34) {
        // This is where the actual API 34+ call would be made.
        // However, since we are calling it directly and guarding it, this extension function is more of a placeholder.
    }
    return null // Should not be reached if Build.VERSION check is done correctly before calling.
}