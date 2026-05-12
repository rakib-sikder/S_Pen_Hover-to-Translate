package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
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
        private const val TAG            = "SPenTranslate"
        var targetLanguage: String       = TranslateLanguage.ENGLISH
        var targetLanguageName: String   = "English"
        var isRunning: Boolean           = false

        private const val DWELL_MS       = 900L   // hold still this long to trigger
        private const val STILL_PX       = 25f    // movement threshold to reset dwell
        private const val DISMISS_PX     = 100f   // movement threshold to hide tooltip
        private const val EXIT_DEBOUNCE  = 350L   // ignore EXIT if ENTER follows within this

        enum class Mode { WORD, PARAGRAPH }
        var currentMode: Mode = Mode.WORD
    }

    private var windowManager: WindowManager? = null

    // Overlay views
    private var cursorView: View?          = null  // ● visible dot showing pen position
    private var highlightView: View?       = null  // blue box over detected word
    private var tooltipView: LinearLayout? = null  // translation card
    private var modeBtnView: LinearLayout? = null  // WORD/PARA toggle

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    // Pen coordinates — updated from onMotionEvent (preferred) or node bounds
    private var penX = 0f
    private var penY = 0f

    // Where dwell started (locked position)
    private var dwellX = 0f
    private var dwellY = 0f

    // Where the last translation happened (to avoid dismiss+redraw blink)
    private var lastTxX = -999f
    private var lastTxY = -999f

    private var isProcessing = false

    private val dwellRunnable = Runnable {
        if (!isProcessing) {
            isProcessing = true
            Log.d(TAG, "⏱ Dwell fired → OCR at pen=(${penX.toInt()},${penY.toInt()}) anchor=(${dwellX.toInt()},${dwellY.toInt()})")
            captureAndTranslate(dwellX, dwellY)
        }
    }

    // Debounced exit — only fires if no ENTER arrives within EXIT_DEBOUNCE ms
    private val exitRunnable = Runnable {
        Log.d(TAG, "EXIT confirmed → cancelling dwell")
        mainHandler.removeCallbacks(dwellRunnable)
        isProcessing = false
        removeCursor()
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning     = true
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

        showModeButton()
        Log.d(TAG, "✓ Connected — target=$targetLanguageName mode=$currentMode")
    }

    override fun onInterrupt() { removeAllOverlays() }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        removeAllOverlays()
        removeModeButton()
        tts?.shutdown()
    }

    // ── onMotionEvent — raw S Pen XY (fires on some devices) ─────

    override fun onMotionEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                penX = event.rawX
                penY = event.rawY
                updateCursor(penX, penY)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                removeCursor()
            }
        }
    }

    // ── Accessibility Events ──────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {

            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                // Cancel pending EXIT — pen is still in range
                mainHandler.removeCallbacks(exitRunnable)

                // Get node bounds for Y coordinate (reliable)
                val node = event.source
                val nb   = Rect()
                node?.getBoundsInScreen(nb)
                node?.recycle()

                // X: use raw pen position if available (node X is always screen centre)
                // Y: use node centre Y (more reliable than raw for vertical position)
                val x = if (penX > 0f) penX else nb.centerX().toFloat()
                val y = if (!nb.isEmpty)  nb.centerY().toFloat()
                else if (penY > 0f) penY
                else return

                // Update cursor position
                penX = x; penY = y
                updateCursor(x, y)

                Log.d(TAG, "HOVER_ENTER x=${x.toInt()} y=${y.toInt()} nodeBounds=$nb")

                // How far did we move from dwell anchor?
                val movedFromAnchor = dist(x, y, dwellX, dwellY)

                if (movedFromAnchor > STILL_PX) {
                    // Pen moved — reset dwell timer
                    dwellX = x; dwellY = y
                    isProcessing = false
                    mainHandler.removeCallbacks(dwellRunnable)

                    // Only dismiss overlays if moved FAR from last translation
                    val movedFromLastTx = dist(x, y, lastTxX, lastTxY)
                    Log.d(TAG, "  moved ${movedFromAnchor.toInt()}px from anchor, ${movedFromLastTx.toInt()}px from lastTx")

                    if (movedFromLastTx > DISMISS_PX) {
                        removeHighlight()
                        removeTooltip()
                        Log.d(TAG, "  → overlays dismissed (moved far)")
                    }

                    // Start fresh dwell
                    mainHandler.postDelayed(dwellRunnable, DWELL_MS)
                    Log.d(TAG, "  → dwell started (${DWELL_MS}ms)")
                } else {
                    Log.d(TAG, "  → still (${movedFromAnchor.toInt()}px < ${STILL_PX}px), dwell continues")
                }
            }

            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                // Don't cancel immediately — debounce rapid EXIT/ENTER pairs
                mainHandler.removeCallbacks(exitRunnable)
                mainHandler.postDelayed(exitRunnable, EXIT_DEBOUNCE)
                Log.d(TAG, "HOVER_EXIT (will confirm in ${EXIT_DEBOUNCE}ms)")
            }
        }
    }

    // ── Screen capture + OCR ──────────────────────────────────────

    private fun captureAndTranslate(x: Float, y: Float) {
        val capture = CaptureService.instance
        if (capture == null) {
            Log.w(TAG, "⚠ CaptureService not running — tap Start in app")
            isProcessing = false
            return
        }

        Log.d(TAG, "📸 Capturing screen... target=(${x.toInt()},${y.toInt()})")

        capture.captureAndOcr { words ->
            isProcessing = false

            if (words.isNullOrEmpty()) {
                Log.w(TAG, "OCR returned 0 words")
                return@captureAndOcr
            }

            Log.d(TAG, "OCR found ${words.size} words:")
            words.take(8).forEach { w ->
                val hit = if (w.bounds.contains(x.toInt(), y.toInt())) " ← ★ HIT" else ""
                Log.d(TAG, "  \"${w.text}\" bounds=${w.bounds}$hit")
            }

            when (currentMode) {
                Mode.WORD      -> handleWord(words, x, y)
                Mode.PARAGRAPH -> handleParagraph(words, x, y)
            }
        }
    }

    // ── WORD mode ─────────────────────────────────────────────────

    private fun handleWord(words: List<OcrWord>, x: Float, y: Float) {
        // Exact hit — bounding box contains pen point
        var target = words.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }

        if (target != null) {
            Log.d(TAG, "✓ EXACT hit: \"${target.text}\" at bounds=${target.bounds}")
        } else {
            // Nearest word within 150px
            val nearest = words
                .map { w -> Pair(w, dist(w.bounds.centerX().toFloat(), w.bounds.centerY().toFloat(), x, y)) }
                .filter { it.second < 150f }
                .minByOrNull { it.second }

            target = nearest?.first
            if (target != null) {
                Log.d(TAG, "✓ NEAREST hit: \"${target.text}\" dist=${nearest!!.second.toInt()}px bounds=${target.bounds}")
            } else {
                Log.w(TAG, "✗ No word found within 150px of (${x.toInt()},${y.toInt()})")
                Log.w(TAG, "  Closest was: ${words.minByOrNull { dist(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat(), x, y) }?.let { "\"${it.text}\" at ${dist(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat(), x, y).toInt()}px" }}")
                return
            }
        }

        lastTxX = x; lastTxY = y
        showHighlight(target.bounds)
        translate(target.text, target.bounds)
    }

    // ── PARAGRAPH mode ────────────────────────────────────────────

    private fun handleParagraph(words: List<OcrWord>, x: Float, y: Float) {
        val avgH      = words.map { it.bounds.height() }.average().toFloat()
        val tolerance = (avgH * 0.9f).coerceIn(20f, 70f)

        var lineWords = words
            .filter { Math.abs(it.bounds.centerY() - y) <= tolerance }
            .sortedBy { it.bounds.left }

        if (lineWords.isEmpty()) {
            val nearY = words.minByOrNull { Math.abs(it.bounds.centerY() - y) }
                ?.bounds?.centerY() ?: return
            lineWords = words
                .filter { Math.abs(it.bounds.centerY() - nearY) <= tolerance }
                .sortedBy { it.bounds.left }
        }

        if (lineWords.isEmpty()) return

        val sentence   = lineWords.joinToString(" ") { it.text }
        val lineBounds = Rect(
            lineWords.first().bounds.left,  lineWords.first().bounds.top,
            lineWords.last().bounds.right,  lineWords.last().bounds.bottom
        )
        Log.d(TAG, "✓ PARAGRAPH: \"$sentence\" bounds=$lineBounds")
        lastTxX = x; lastTxY = y
        showHighlight(lineBounds)
        translate(sentence, lineBounds)
    }

    // ── Translation ───────────────────────────────────────────────

    private fun translate(text: String, bounds: Rect) {
        Log.d(TAG, "🌐 Translating \"$text\" → $targetLanguageName")
        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { lang ->
                val src = if (lang == "und") TranslateLanguage.ENGLISH else lang
                Log.d(TAG, "   detected lang=$src")
                if (src == targetLanguage) { showTooltip(text, text, bounds); return@addOnSuccessListener }
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(src)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).translate(text)
                    .addOnSuccessListener { t -> Log.d(TAG, "   → \"$t\""); showTooltip(t, text, bounds) }
                    .addOnFailureListener { Log.e(TAG,"   translate failed",it); showTooltip("⚠ Download model in app", text, bounds) }
            }
            .addOnFailureListener {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).translate(text)
                    .addOnSuccessListener { t -> showTooltip(t, text, bounds) }
                    .addOnFailureListener { showTooltip("⚠ Download model in app", text, bounds) }
            }
    }

    // ── Cursor dot ────────────────────────────────────────────────

    private fun updateCursor(x: Float, y: Float) {
        mainHandler.post {
            val wm = windowManager ?: return@post

            if (cursorView == null) {
                // Create cursor dot on first appearance
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#CC1A73E8"))   // blue dot
                        setStroke(2, Color.WHITE)
                    }
                }
                val p = WindowManager.LayoutParams(
                    24, 24,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = (x - 12).toInt().coerceAtLeast(0)
                    this.y = (y - 12).toInt().coerceAtLeast(0)
                }
                try { wm.addView(dot, p); cursorView = dot }
                catch (e: Exception) { Log.e(TAG, "Cursor add failed", e) }
            } else {
                // Move existing cursor dot
                try {
                    val lp = cursorView!!.layoutParams as WindowManager.LayoutParams
                    lp.x = (x - 12).toInt().coerceAtLeast(0)
                    lp.y = (y - 12).toInt().coerceAtLeast(0)
                    wm.updateViewLayout(cursorView, lp)
                } catch (e: Exception) {
                    cursorView = null  // stale — recreate next time
                }
            }
        }
    }

    private fun removeCursor() {
        mainHandler.post {
            cursorView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
            cursorView = null
        }
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
                y = (bounds.top  - 3).coerceAtLeast(0)
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

    // ── Translation tooltip (Samsung dark card style) ─────────────

    private fun showTooltip(translated: String, original: String, wordBounds: Rect) {
        mainHandler.post {
            removeTooltip()
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels

            val tooltipEstH = 140
            val tipY = if (wordBounds.top - tooltipEstH - 12 > 80)
                wordBounds.top - tooltipEstH - 12
            else wordBounds.bottom + 12
            val tipX = wordBounds.left.coerceIn(8, screenW - 340)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = tipX; y = tipY.coerceIn(80, screenH - 200)
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

            // Row 1: translated + speak
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 18, 14, 14)
            }
            row1.addView(TextView(this).apply {
                text = translated
                textSize = if (currentMode == Mode.WORD) 20f else 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(makeBtn("▶") { tts?.speak(original, TextToSpeech.QUEUE_FLUSH, null, null) })
            card.addView(row1)

            // Divider
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })

            // Row 2: original + copy
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 14, 14, 18)
            }
            row2.addView(TextView(this).apply {
                text = original
                textSize = if (currentMode == Mode.WORD) 15f else 13f
                setTextColor(Color.parseColor("#AAFFFFFF"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row2.addView(makeBtn("⧉") {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("translation", translated))
                removeAllOverlays()
            })
            card.addView(row2)

            tooltipView = card
            try {
                windowManager?.addView(card, params)
                val ms = if (currentMode == Mode.PARAGRAPH) 9000L else 6000L
                mainHandler.postDelayed({ removeAllOverlays() }, ms)
            } catch (e: Exception) { Log.e(TAG, "Tooltip failed", e); tooltipView = null }
        }
    }

    private fun makeBtn(icon: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 15f
        setTextColor(Color.parseColor("#AAFFFFFF"))
        gravity = Gravity.CENTER
        setPadding(10, 6, 10, 6)
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
        // Don't remove cursor — it should always follow the pen
    }

    // ── Mode button ───────────────────────────────────────────────

    private fun showModeButton() {
        removeModeButton()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            setTextColor(0xFF1A73E8.toInt())
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        btn.addView(label)
        btn.setOnClickListener {
            currentMode = if (currentMode == Mode.WORD) Mode.PARAGRAPH else Mode.WORD
            label.text  = modeLabel()
            removeAllOverlays()
            Log.d(TAG, "Mode → $currentMode")
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