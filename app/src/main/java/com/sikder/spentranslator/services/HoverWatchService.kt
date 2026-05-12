package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sikder.spentranslator.model.OcrWord
import java.util.Locale

class HoverWatchService : AccessibilityService() {

    companion object {
        private const val TAG           = "SPenTranslate"
        var targetLanguage: String      = TranslateLanguage.ENGLISH
        var targetLanguageName: String  = "English"
        var isRunning: Boolean          = false

        private const val DWELL_MS      = 900L
        private const val STILL_PX      = 25f
        private const val DISMISS_PX    = 100f
        private const val EXIT_DEBOUNCE = 350L

        enum class Mode { WORD, PARAGRAPH }
        var currentMode: Mode = Mode.WORD

        var instance: HoverWatchService? = null
    }

    private var windowManager: WindowManager? = null
    private var penOverlay: PenOverlayView? = null

    private var highlightView: View?        = null
    private var tooltipView: LinearLayout?  = null
    private var modeBtnView: LinearLayout?  = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    private var penX = 0f
    private var penY = 0f
    private var dwellX = 0f
    private var dwellY = 0f
    private var lastTxX = -999f
    private var lastTxY = -999f
    private var isProcessing = false

    private val dwellRunnable = Runnable {
        if (!isProcessing) {
            isProcessing = true
            Log.d(TAG, "⏱ Dwell fired → OCR at (${dwellX.toInt()},${dwellY.toInt()})")
            captureAndTranslate(dwellX, dwellY)
        }
    }

    private val exitRunnable = Runnable {
        Log.d(TAG, "EXIT confirmed — S Pen removed")
        mainHandler.removeCallbacks(dwellRunnable)
        isProcessing = false
        // S Pen সরে গেছে → overlay সম্পূর্ণ remove করো যাতে touch কাজ করে
        removePenOverlay()
        Log.d(TAG, "✓ Overlay removed — touch restored")
    }

    // ── PenOverlayView ─────────────────────────────────────────────
    //
    // KEY DESIGN:
    //
    // We need FLAG_NOT_TOUCHABLE so finger touches pass through — but Samsung
    // won't deliver hover events (ACTION_HOVER_MOVE) via setOnHoverListener to
    // a FLAG_NOT_TOUCHABLE window (confirmed by logs: listener never fired).
    //
    // Solution: override dispatchGenericMotionEvent() on the View itself.
    // Generic motion events (which S Pen hover IS) are dispatched through the
    // view's own event dispatch chain — this path is separate from the input
    // dispatcher's touchable-flag check. The window can be FLAG_NOT_TOUCHABLE
    // (so fingers pass through) while the view still receives hover via
    // dispatchGenericMotionEvent (because that goes through the view hierarchy,
    // not the input dispatcher window filter).
    //
    // The dot is drawn on this view's own canvas so it's always on top with
    // no separate window Z-order issues.

    inner class PenOverlayView(context: Context) : View(context) {

        init {
            isClickable = false
            isFocusable = false
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE1A73E8")
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val dotRadius = 10f

        private var cx = -999f
        private var cy = -999f

        fun moveDot(x: Float, y: Float) {
            cx = x; cy = y
            postInvalidate()
        }

        fun hideDot() {
            cx = -999f; cy = -999f
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (cx < 0f) return
            canvas.drawCircle(cx, cy, dotRadius, fillPaint)
            canvas.drawCircle(cx, cy, dotRadius - 1.5f, strokePaint)
        }

        // This receives S Pen hover even with FLAG_NOT_TOUCHABLE on the window,
        // because generic motion events travel through the View dispatch chain,
        // not through the input dispatcher's per-window touchable filter.
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val x = event.rawX
                    val y = event.rawY
                    penX = x; penY = y
                    mainHandler.removeCallbacks(exitRunnable)
                    moveDot(x+3, y- 71f)
                    handlePenMove(x+3, y - 71f)
                    Log.v(TAG, "🖊 pen (${x.toInt()},${y.toInt()})")
                    return true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    hideDot()
                    mainHandler.removeCallbacks(exitRunnable)
                    mainHandler.postDelayed(exitRunnable, EXIT_DEBOUNCE)
                    return true
                }
            }
            return super.dispatchGenericMotionEvent(event)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning     = true
        instance      = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes =
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or
                        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
            info.flags =
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 0
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.ENGLISH
        }

//        addPenOverlay()  // শুরুতে add করো — accessibility event এ remove/re-add হবে
        showModeButton()
        Log.d(TAG, "✓ Connected — target=$targetLanguageName mode=$currentMode")
    }

    override fun onInterrupt() { removeAllOverlays() }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance  = null
        mainHandler.removeCallbacksAndMessages(null)
        removePenOverlay()
        removeAllOverlays()
        removeModeButton()
        tts?.shutdown()
    }

    // ── Add the pen overlay ────────────────────────────────────────

    private fun addPenOverlay(penHovering: Boolean = false) {
        removePenOverlay()
        val wm = windowManager ?: return

        val overlay = PenOverlayView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_TOUCHABLE কখনো দেবো না — দিলে hover আসে না এই device এ
            // touch pass-through এর জন্য view এর onTouchEvent এ false return করবো
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(overlay, params)
            penOverlay = overlay
            Log.d(TAG, "✓ Pen overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Pen overlay add FAILED", e)
        }
    }

    private fun removePenOverlay() {
        penOverlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        penOverlay = null
    }

    // ── onMotionEvent — not firing on this device ─────────────────

    override fun onMotionEvent(event: MotionEvent) { }

    // ── Accessibility Events ───────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                mainHandler.removeCallbacks(exitRunnable)
                // S Pen screen এ এসেছে → overlay add করো
                if (penOverlay == null) {
                    addPenOverlay()
                    Log.d(TAG, "✓ Overlay added — S Pen detected")
                }
            }
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                mainHandler.removeCallbacks(exitRunnable)
                mainHandler.postDelayed(exitRunnable, EXIT_DEBOUNCE)
            }
        }
    }

    // ── Dwell logic ───────────────────────────────────────────────

    private fun handlePenMove(x: Float, y: Float) {
        val movedFromAnchor = dist(x, y, dwellX, dwellY)
        if (movedFromAnchor > STILL_PX) {
            dwellX = x; dwellY = y
            isProcessing = false
            mainHandler.removeCallbacks(dwellRunnable)

            if (dist(x, y, lastTxX, lastTxY) > DISMISS_PX) {
                removeHighlight()
                removeTooltip()
            }

            mainHandler.postDelayed(dwellRunnable, DWELL_MS)
        }
    }

    // ── Screen capture + OCR ──────────────────────────────────────

    private fun captureAndTranslate(x: Float, y: Float) {
        val capture = CaptureService.instance
        if (capture == null) {
            Log.w(TAG, "⚠ CaptureService not running")
            isProcessing = false
            return
        }
        capture.captureAndOcr { words ->
            isProcessing = false
            if (words.isNullOrEmpty()) { Log.w(TAG, "OCR: 0 words"); return@captureAndOcr }
            when (currentMode) {
                Mode.WORD      -> handleWord(words, x, y)
                Mode.PARAGRAPH -> handleParagraph(words, x, y)
            }
        }
    }

    // ── WORD mode ─────────────────────────────────────────────────

    private fun handleWord(words: List<OcrWord>, x: Float, y: Float) {
        var target = words.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }
        if (target == null) {
            target = words
                .map { Pair(it, dist(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat(), x, y)) }
                .filter { it.second < 150f }
                .minByOrNull { it.second }?.first
        }
        if (target == null) { Log.w(TAG, "No word within 150px"); return }
        lastTxX = x; lastTxY = y
        showHighlight(target.bounds)
        translate(target.text, target.bounds)
    }

    // ── PARAGRAPH mode ────────────────────────────────────────────

    private fun handleParagraph(words: List<OcrWord>, x: Float, y: Float) {
        val tol = (words.map { it.bounds.height() }.average().toFloat() * 0.9f).coerceIn(20f, 70f)
        var lineWords = words.filter { Math.abs(it.bounds.centerY() - y) <= tol }.sortedBy { it.bounds.left }
        if (lineWords.isEmpty()) {
            val nearY = words.minByOrNull { Math.abs(it.bounds.centerY() - y) }?.bounds?.centerY() ?: return
            lineWords = words.filter { Math.abs(it.bounds.centerY() - nearY) <= tol }.sortedBy { it.bounds.left }
        }
        if (lineWords.isEmpty()) return
        val sentence = lineWords.joinToString(" ") { it.text }
        val bounds   = Rect(
            lineWords.first().bounds.left, lineWords.first().bounds.top,
            lineWords.last().bounds.right, lineWords.last().bounds.bottom
        )
        lastTxX = x; lastTxY = y
        showHighlight(bounds)
        translate(sentence, bounds)
    }

    // ── Translation ───────────────────────────────────────────────

    // ── Translation ───────────────────────────────────────────────

    private fun translate(text: String, bounds: Rect) {
        // প্রথমে ML Kit offline try করো
        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { lang ->
                val src = if (lang == "und") TranslateLanguage.ENGLISH else lang
                if (src == targetLanguage) { showTooltip(text, text, bounds); return@addOnSuccessListener }
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(targetLanguage).build()
                ).translate(text)
                    .addOnSuccessListener { showTooltip(it, text, bounds) }
                    .addOnFailureListener {
                        // ML Kit fail → MyMemory online fallback
                        Log.w(TAG, "ML Kit failed, trying MyMemory...")
                        translateOnline(text, bounds)
                    }
            }
            .addOnFailureListener {
                // Language detect fail → MyMemory online fallback
                Log.w(TAG, "Lang detect failed, trying MyMemory...")
                translateOnline(text, bounds)
            }
    }

    private fun translateOnline(text: String, bounds: Rect) {
        Thread {
            try {
                val encoded = java.net.URLEncoder.encode(text, "UTF-8")
                val langCode = targetLanguage.take(2)
                val url = java.net.URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=auto|$langCode")
                val response = url.readText()
                val json = org.json.JSONObject(response)
                val translated = json.getJSONObject("responseData").getString("translatedText")
                Log.d(TAG, "✓ MyMemory translated: $translated")
                mainHandler.post { showTooltip(translated, text, bounds) }
            } catch (e: Exception) {
                Log.e(TAG, "MyMemory failed: ${e.message}")
                mainHandler.post { showTooltip("⚠ Translation failed", text, bounds) }
            }
        }.start()
    }

    // ── Blue word highlight ───────────────────────────────────────

    private fun showHighlight(bounds: Rect) {
        mainHandler.post {
            removeHighlight()
            if (bounds.isEmpty) return@post
            val view = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#441A73E8"))
                    cornerRadius = 5f
                    setStroke(2, Color.parseColor("#BB1A73E8"))
                }
            }
            val p = WindowManager.LayoutParams(
                bounds.width() + 10, bounds.height() + 6,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (bounds.left - 5).coerceAtLeast(0)
                y = (bounds.top - 3).coerceAtLeast(0)
            }
            highlightView = view
            try { windowManager?.addView(view, p) }
            catch (e: Exception) { Log.e(TAG, "Highlight failed", e); highlightView = null }
        }
    }

    private fun removeHighlight() {
        highlightView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        highlightView = null
    }

    // ── Translation tooltip ───────────────────────────────────────

    private fun showTooltip(translated: String, original: String, wordBounds: Rect) {
        mainHandler.post {
            removeTooltip()
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val tipY    = if (wordBounds.top - 152 > 80) wordBounds.top - 152 else wordBounds.bottom + 12
            val tipX    = wordBounds.left.coerceIn(8, screenW - 340)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = tipX
                y = tipY.coerceIn(80, screenH - 200)
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumWidth = 220
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F0111111"))
                    cornerRadius = 18f
                }
                elevation = 24f
            }

            fun row(pad: Int) = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, pad, 14, pad)
            }

            val r1 = row(18)
            r1.addView(TextView(this).apply {
                text = translated
                textSize = if (currentMode == Mode.WORD) 20f else 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            r1.addView(makeBtn("▶") { tts?.speak(original, TextToSpeech.QUEUE_FLUSH, null, null) })
            card.addView(r1)

            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })

            val r2 = row(14)
            r2.addView(TextView(this).apply {
                text = original
                textSize = if (currentMode == Mode.WORD) 15f else 13f
                setTextColor(Color.parseColor("#AAFFFFFF"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            r2.addView(makeBtn("⧉") {
                (getSystemService(ClipboardManager::class.java))
                    .setPrimaryClip(ClipData.newPlainText("translation", translated))
                removeAllOverlays()
            })
            card.addView(r2)

            tooltipView = card
            try {
                windowManager?.addView(card, params)
                mainHandler.postDelayed(
                    { removeAllOverlays() },
                    if (currentMode == Mode.PARAGRAPH) 9000L else 6000L
                )
            } catch (e: Exception) { Log.e(TAG, "Tooltip failed", e); tooltipView = null }
        }
    }

    private fun makeBtn(icon: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 15f; setTextColor(Color.parseColor("#AAFFFFFF"))
        gravity = Gravity.CENTER; setPadding(10, 6, 10, 6)
        setOnClickListener { onClick() }
    }

    private fun removeTooltip() {
        tooltipView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        tooltipView = null
    }

    private fun removeAllOverlays() {
        mainHandler.removeCallbacks(dwellRunnable)
        removeHighlight()
        removeTooltip()
        // Do NOT removePenOverlay() here — tracking must stay alive permanently
    }

    // ── Mode button ───────────────────────────────────────────────

    private fun showModeButton() {
        removeModeButton()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 220 }

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 18, 14, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A2E"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#881A73E8"))
            }
            elevation = 12f
        }
        val label = TextView(this).apply {
            text = modeLabel(); textSize = 10f
            setTextColor(0xFF1A73E8.toInt()); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        btn.addView(label)
        btn.setOnClickListener {
            currentMode = if (currentMode == Mode.WORD) Mode.PARAGRAPH else Mode.WORD
            label.text = modeLabel()
            removeAllOverlays()
        }
        modeBtnView = btn
        try { windowManager?.addView(btn, params) }
        catch (e: Exception) { Log.e(TAG, "Mode btn failed", e); modeBtnView = null }
    }

    private fun modeLabel() = if (currentMode == Mode.WORD) "W\nO\nR\nD" else "¶\nP\nA\nR\nA"

    private fun removeModeButton() {
        modeBtnView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        modeBtnView = null
    }

    // ── Utility ───────────────────────────────────────────────────

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
}