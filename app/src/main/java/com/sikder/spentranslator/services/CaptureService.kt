package com.sikder.spentranslator.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.WindowMetrics
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sikder.spentranslator.model.OcrWord
import com.sikder.spentranslator.utils.OcrHelper

class CaptureService : Service() {

    companion object {
        private const val TAG        = "CaptureService"
        private const val CHANNEL_ID = "spen_capture_channel"
        private const val NOTIF_ID   = 101

        const val EXTRA_RESULT_CODE      = "extra_result_code"
        const val EXTRA_DATA             = "extra_data"
        const val EXTRA_TARGET_LANG      = "extra_target_lang"
        const val EXTRA_TARGET_LANG_NAME = "extra_target_lang_name"

        var targetLanguage: String     = TranslateLanguage.ENGLISH
        var targetLanguageName: String = "English"

        var instance: CaptureService? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?         = null

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        // FIX 2: On Android 14+, startForeground() for mediaProjection type MUST
        // include FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION as the third argument.
        // Omitting it throws SecurityException even if the permission is in the manifest.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        targetLanguage     = intent?.getStringExtra(EXTRA_TARGET_LANG)      ?: TranslateLanguage.ENGLISH
        targetLanguageName = intent?.getStringExtra(EXTRA_TARGET_LANG_NAME) ?: "English"

        if (resultCode != Int.MIN_VALUE && data != null) {
            setupProjection(resultCode, data)
        } else {
            Log.e(TAG, "Invalid projection data — resultCode=$resultCode, data=$data")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        teardown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────
    // MediaProjection Setup
    // ──────────────────────────────────────────────────────────────

    private fun setupProjection(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        // FIX 1: Android 14 requires registering a callback BEFORE createVirtualDisplay().
        // Without this, createVirtualDisplay() throws IllegalStateException:
        // "Must register a callback before starting capture"
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped — tearing down")
                teardown()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val (width, height, density) = getDisplaySize()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SPenTranslateCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "Virtual display ready — ${width}x${height} @ ${density}dpi")
    }

    private fun getDisplaySize(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            val density = resources.displayMetrics.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            @Suppress("DEPRECATION")
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Screen Capture → OcrWord list
    // ──────────────────────────────────────────────────────────────

    fun captureAndOcr(callback: (List<OcrWord>?) -> Unit) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            callback(null)
            return
        }
        try {
            val plane      = image.planes[0]
            val buffer     = plane.buffer
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val bitmap     = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride,
                image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            OcrHelper.recognizeWords(bitmap) { words ->
                bitmap.recycle()
                callback(words)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture/OCR error", e)
            callback(null)
        } finally {
            image.close()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────────────────────

    private fun teardown() {
        try { virtualDisplay?.release()  } catch (_: Exception) {}
        try { mediaProjection?.stop()    } catch (_: Exception) {}
        try { imageReader?.close()       } catch (_: Exception) {}
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
    }

    // ──────────────────────────────────────────────────────────────
    // Foreground Notification
    // ──────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "S Pen Translate", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("S Pen Hover-to-Translate")
            .setContentText("Hover S Pen over text to translate")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
}
