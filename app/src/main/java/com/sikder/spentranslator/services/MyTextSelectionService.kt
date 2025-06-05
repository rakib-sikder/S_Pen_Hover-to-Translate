package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat // Added
import android.graphics.Rect
import android.hardware.display.DisplayManager // Added
import android.hardware.display.VirtualDisplay // Added
import android.media.ImageReader // Added
import android.media.projection.MediaProjection // Added
import android.media.projection.MediaProjectionManager // Added
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
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

    private val handler = Handler(Looper.getMainLooper()) // Used for delays

    // For MediaProjection instance if managing within the service
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null


    companion object {
        const val ACTION_START_FEATURE = "com.sikder.spentranslator.ACTION_START_FEATURE"
        const val ACTION_STOP_FEATURE = "com.sikder.spentranslator.ACTION_STOP_FEATURE"
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.sikder.spentranslator.ACTION_REQUEST_SCREEN_CAPTURE"
        // These are set by MainActivity after user grants permission
        var mediaProjectionIntent: Intent? = null
        var mediaProjectionResultCode: Int? = null
        var isFeatureActive = false
    }

    // ... onStartCommand, startForegroundServiceNotification, createNotificationChannel remain the same ...
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
                    stopMediaProjection() // Stop projection if feature is stopped
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            isFeatureActive = false
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
        if (!isFeatureActive) return

        Log.d(TAG, "onAccessibilityEvent (active): eventType=${event?.eventType}, pkg=${event?.packageName}")
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                Log.i(TAG, ">>> TYPE_VIEW_TEXT_SELECTION_CHANGED event detected (active) <<<")
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
                Log.d(TAG, "Source node className: ${sourceNode.className}")

                sourceNode.let { node ->
                    var extractedText: CharSequence? = null

                    // Attempt 1: Using textSelectionStart/End
                    if (node.textSelectionStart != -1 && node.textSelectionEnd != -1 &&
                        node.textSelectionStart < node.textSelectionEnd && node.text != null &&
                        node.textSelectionEnd <= node.text.length) {
                        try {
                            extractedText = node.text?.subSequence(node.textSelectionStart, node.textSelectionEnd)
                            if (!extractedText.isNullOrEmpty()) {
                                Log.i(TAG, "Extracted selected text via indices: \"$extractedText\"")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error getting subsequence from selection indices", e)
                            extractedText = null
                        }
                    }

                    if (!extractedText.isNullOrEmpty()) {
                        handleFoundText(extractedText)
                    } else {
                        // Attempt 2: OCR as fallback
                        Log.i(TAG, "Direct text extraction failed. Attempting OCR.")
                        initiateOcr(node)
                    }
                }
            }
        }
    }

    private fun initiateOcr(node: AccessibilityNodeInfo) {
        if (mediaProjectionIntent != null && mediaProjectionResultCode != null) {
            Log.i(TAG, "MediaProjection intent available, proceeding with OCR.")
            if (this.mediaProjection == null) { // Check if we need to create it
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                try {
                    this.mediaProjection = mediaProjectionManager.getMediaProjection(mediaProjectionResultCode!!, mediaProjectionIntent!!)
                    this.mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection session stopped.")
                            // Clean up virtual display and image reader if projection stops unexpectedly
                            virtualDisplay?.release()
                            imageReader?.close()
                            this@MyTextSelectionService.mediaProjection = null // Clear our reference
                        }
                    }, handler) // Register callback on a handler
                    Log.i(TAG, "MediaProjection instance created.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating MediaProjection instance", e)
                    // Request permission again if it failed (e.g., intent was stale)
                    requestScreenCapturePermissionFromActivity()
                    return
                }
            }
            performOcr(node) // Pass the current mediaProjection implicitly
        } else {
            Log.w(TAG, "MediaProjection intent not available. Requesting screen capture permission.")
            requestScreenCapturePermissionFromActivity()
        }
    }

    private fun requestScreenCapturePermissionFromActivity() {
        val requestIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REQUEST_SCREEN_CAPTURE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            startActivity(requestIntent)
            Log.d(TAG, "Sent request to MainActivity for screen capture permission.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MainActivity to request screen capture.", e)
        }
    }


    private fun performOcr(node: AccessibilityNodeInfo) {
        val currentMediaProjection = this.mediaProjection // Use the member variable
        if (currentMediaProjection == null) {
            Log.e(TAG, "MediaProjection is null in performOcr. Cannot capture screen.")
            // Optionally re-request permission or notify user
            initiateOcr(node) // This might re-trigger permission request if mediaProjectionIntent is also null
            return
        }

        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)
        if (nodeBounds.isEmpty || nodeBounds.width() <= 0 || nodeBounds.height() <= 0) {
            Log.w(TAG, "Node bounds are empty or invalid for OCR: $nodeBounds")
            return
        }
        Log.d(TAG, "Performing OCR on bounds: $nodeBounds")

        // Ensure ImageReader is set up correctly for the bounds we want to capture
        // It's often easier to capture a larger region (or full screen) and then crop the bitmap,
        // or capture only the needed region if the VirtualDisplay is created for those bounds.
        // For simplicity, let's capture based on screen dimensions and then crop.
        // This setup should ideally happen once per MediaProjection session or be more dynamic.

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // Close previous ImageReader if it exists to avoid errors
        imageReader?.close()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        // Release previous virtual display if it exists
        virtualDisplay?.release()
        virtualDisplay = currentMediaProjection.createVirtualDisplay(
            "SpentTranslatorScreenCapture",
            screenWidth, screenHeight, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, // Use AUTO_MIRROR for general screen content
            imageReader!!.surface, null, handler // Use the existing handler
        )

        Log.d(TAG, "VirtualDisplay created. Waiting for image...")

        // Delay to allow image to be captured by ImageReader.
        // A more robust solution uses ImageReader.OnImageAvailableListener
        handler.postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "Image acquired: ${image.width}x${image.height}")
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width // Use image.width for full row

                val fullBitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride, // to account for padding
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close() // Close image as soon as possible

                Log.d(TAG, "Full screen bitmap created. Attempting to crop to nodeBounds: $nodeBounds")

                // Ensure bounds are within the bitmap dimensions
                val cropX = nodeBounds.left.coerceIn(0, fullBitmap.width)
                val cropY = nodeBounds.top.coerceIn(0, fullBitmap.height)
                val cropWidth = nodeBounds.width().coerceAtMost(fullBitmap.width - cropX)
                val cropHeight = nodeBounds.height().coerceAtMost(fullBitmap.height - cropY)

                if (cropWidth > 0 && cropHeight > 0) {
                    try {
                        val croppedBitmap = Bitmap.createBitmap(
                            fullBitmap,
                            cropX,
                            cropY,
                            cropWidth,
                            cropHeight
                        )
                        Log.i(TAG, "Bitmap cropped successfully to ${croppedBitmap.width}x${croppedBitmap.height}")
                        OcrHelper.recognizeTextFromBitmap(croppedBitmap) { ocrText ->
                            if (!ocrText.isNullOrBlank()) {
                                Log.i(TAG, "Text extracted via OCR: \"$ocrText\"")
                                handleFoundText(ocrText)
                            } else {
                                Log.w(TAG, "OCR did not find any text in the cropped region.")
                            }
                            if (!croppedBitmap.isRecycled) croppedBitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating cropped bitmap or during OCR", e)
                    }

                } else {
                    Log.w(TAG, "Calculated crop dimensions are invalid (cropWidth=$cropWidth, cropHeight=$cropHeight). OCRing full screen as fallback.")
                    // Fallback: OCR the full bitmap if cropping is problematic (less ideal)
                    // OcrHelper.recognizeTextFromBitmap(fullBitmap) { ocrText -> ... }
                }
                if (!fullBitmap.isRecycled) fullBitmap.recycle()

            } else {
                Log.w(TAG, "Failed to acquire latest image for screen capture from ImageReader.")
            }
            // Consider if virtualDisplay should be released here or managed per MediaProjection session
            // For this example, let's keep it simple. It will be released if projection stops or on new capture.
        }, 500) // Increased delay slightly, but OnImageAvailableListener is better

    }

    private fun stopMediaProjection() {
        Log.d(TAG, "Stopping MediaProjection and related resources.")
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        // Clear static intents so permission is requested next time if needed
        mediaProjectionIntent = null
        mediaProjectionResultCode = null
    }


    private fun handleFoundText(text: CharSequence) {
        // ... (this method remains the same as the previous version)
        val textString = text.toString()
        if (textString.isNotBlank()) {
            if (textString != lastSelectedText?.toString()) {
                lastSelectedText = textString
                Log.i(TAG, "Processing NEW text: \"$textString\"")
                val feedbackIntent = Intent(this, InstructionTooltipService::class.java).apply {
                    action = InstructionTooltipService.ACTION_SHOW_SELECTED_TEXT
                    putExtra(InstructionTooltipService.EXTRA_SELECTED_TEXT, textString)
                }
                startService(feedbackIntent)
                processText(textString)
            } else {
                Log.i(TAG, "Text \"$textString\" is same as last, showing feedback only.")
                val feedbackIntent = Intent(this, InstructionTooltipService::class.java).apply {
                    action = InstructionTooltipService.ACTION_SHOW_SELECTED_TEXT
                    putExtra(InstructionTooltipService.EXTRA_SELECTED_TEXT, textString)
                }
                startService(feedbackIntent)
            }
        } else {
            Log.d(TAG, "handleFoundText: Extracted text is blank, not processing.")
        }
    }

    private fun processText(text: String) {
        // ... (this method remains the same)
        Log.d(TAG, "processText called with text: \"$text\"")
        TranslationApiClient.translate(text, "en", "bn") { translatedText ->
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
        // handler is now a member variable, initialized at the top or in onCreate
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        this.serviceInfo = info
        Log.i(TAG, "Accessibility Service Connected and configured (awaiting start command). MediaProjection available: ${mediaProjectionIntent != null && mediaProjectionResultCode != null}")
        sendBroadcast(Intent(MainActivity.ACTION_UPDATE_UI))
    }

    override fun onInterrupt() {
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
        stopMediaProjection() // Clean up MediaProjection on interrupt
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility Service Destroyed. Stopping feature.")
        if (isFeatureActive) {
            isFeatureActive = false
            stopForeground(true) // This might be redundant if onInterrupt was called
            val instructionIntent = Intent(this, InstructionTooltipService::class.java).apply {
                action = InstructionTooltipService.ACTION_HIDE
            }
            startService(instructionIntent)
        }
        stopMediaProjection() // Clean up MediaProjection on destroy
        handler.removeCallbacksAndMessages(null) // Clean up handler
    }
}