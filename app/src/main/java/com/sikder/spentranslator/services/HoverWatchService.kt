package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sikder.spentranslator.model.OcrWord
import kotlin.math.abs
import kotlin.math.sqrt

class HoverWatchService : AccessibilityService() {

    companion object {
        private const val TAG          = "SPenTranslate"
        var targetLanguage: String     = TranslateLanguage.ENGLISH
        var targetLanguageName: String = "English"
        var isRunning: Boolean         = false

        private const val DWELL_MS          = 700L
        private const val MOVE_THRESHOLD_PX = 25f

        enum class Mode { WORD, PARAGRAPH }
        var currentMode: Mode = Mode.WORD
    }

    private var windowManager: WindowManager? = null
    private var tooltipView: LinearLayout?    = null
    private var modeBtnView: LinearLayout?    = null
    private var lastProcessedText             = ""
    private val mainHandler                   = Handler(Looper.getMainLooper())

    private var lastHoverX  = 0f
    private var lastHoverY  = 0f
    private var dwellStartX = 0f
    private var dwellStartY = 0f
    private var pendingRunnable: Runnable? = null

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning     = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes =
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER             or
                        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
            info.flags =
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 0
        }

        showModeButton()
        showTooltip("S Pen Translate active\nHover over any text", null)
        mainHandler.postDelayed({ removeTooltip() }, 2500)
    }

    override fun onInterrupt() { removeTooltip() }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cancelPending()
        removeTooltip()
        removeModeButton()
    }

    // ── Motion: reset dwell if pen moves ─────────────────────────

    override fun onMotionEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_ENTER -> {
                val newX = event.rawX
                val newY = event.rawY
                val moved = sqrt(
                    (newX - dwellStartX) * (newX - dwellStartX) +
                            (newY - dwellStartY) * (newY - dwellStartY)
                )
                if (moved > MOVE_THRESHOLD_PX && pendingRunnable != null) {
                    cancelPending()
                    dwellStartX = newX
                    dwellStartY = newY
                }
                lastHoverX = newX
                lastHoverY = newY
            }
        }
    }

    // ── Accessibility Events ──────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {

            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                cancelPending()

                // Snapshot everything from the node immediately
                val node         = event.source
                val nodeBounds   = Rect().also { node?.getBoundsInScreen(it) }
                val fullText     = extractBestText(node)
                node?.recycle()

                // If the node has no text at all, nothing we can do
                if (fullText.isNullOrBlank()) {
                    Log.d(TAG, "No text on hovered node — skipping")
                    return
                }

                // Record dwell start position
                dwellStartX      = lastHoverX
                dwellStartY      = lastHoverY
                val capturedX    = lastHoverX
                val capturedY    = lastHoverY
                val capturedText = fullText
                val capturedBounds = Rect(nodeBounds)

                pendingRunnable = Runnable {
                    pendingRunnable = null
                    handleHoverTranslate(capturedText, capturedX, capturedY, capturedBounds)
                }.also { mainHandler.postDelayed(it, DWELL_MS) }
            }

            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                cancelPending()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = extractSelectedText(event) ?: return
                if (text.isBlank() || text == lastProcessedText) return
                lastProcessedText = text
                translateAndShow(text)
            }
        }
    }

    // ── Core translation logic ────────────────────────────────────

    /**
     * Called after the dwell timer fires.
     *
     * Strategy:
     * 1. If CaptureService (OCR) is running → use it for exact word hit-testing.
     *    OCR gives real pixel bounding boxes per word, so we can do an exact
     *    contains(penX, penY) check. This is the most accurate.
     *
     * 2. If CaptureService is NOT running → fall back to accessibility text
     *    with character-index-based word picking. This is always available and
     *    works even without screen capture permission.
     */
    private fun handleHoverTranslate(
        fullText: String,
        hoverX: Float,
        hoverY: Float,
        nodeBounds: Rect
    ) {
        when (currentMode) {
            Mode.WORD -> {
                val capture = CaptureService.instance
                if (capture != null) {
                    // Best path: OCR gives exact word bounds
                    capture.captureAndOcr { words ->
                        val word = if (!words.isNullOrEmpty()) {
                            findWordAtPoint(words, hoverX, hoverY)
                        } else null

                        val text = word
                            ?: pickWordByCharIndex(fullText, hoverX, nodeBounds) // fallback
                        if (text.isBlank() || text == lastProcessedText) return@captureAndOcr
                        lastProcessedText = text
                        Log.d(TAG, "WORD (OCR): \"$text\"")
                        translateAndShow(text)
                    }
                } else {
                    // Fallback path: no OCR, use char-index estimation
                    val text = pickWordByCharIndex(fullText, hoverX, nodeBounds)
                    if (text.isBlank() || text == lastProcessedText) return
                    lastProcessedText = text
                    Log.d(TAG, "WORD (text fallback): \"$text\"")
                    translateAndShow(text)
                }
            }

            Mode.PARAGRAPH -> {
                val text = fullText.trim()
                if (text == lastProcessedText) return
                lastProcessedText = text
                Log.d(TAG, "PARAGRAPH: \"$text\"")
                translateAndShow(text)
            }
        }
    }

    // ── Word picking ──────────────────────────────────────────────

    /**
     * OCR path: find the word whose bounding box contains the pen position.
     * Falls back to the nearest word if no exact hit.
     */
    private fun findWordAtPoint(words: List<OcrWord>, x: Float, y: Float): String? {
        val exact = words.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }
        if (exact != null) return exact.text.clean()

        // No exact hit — pick nearest by distance
        val nearest = words.minByOrNull {
            val dx = (it.bounds.centerX() - x).toDouble()
            val dy = (it.bounds.centerY() - y).toDouble()
            dx * dx + dy * dy
        }
        return nearest?.text?.clean()
    }

    /**
     * Accessibility fallback: estimate which word the pen is on using the
     * character index corresponding to the pen's X position within the node.
     *
     * This works by:
     * 1. Treating the node's width as spanning the full text uniformly
     * 2. Calculating which character index the pen X maps to
     * 3. Finding which word contains that character index
     *
     * Much better than splitting by word count because it accounts for the
     * fact that different words have different lengths.
     */
    private fun pickWordByCharIndex(fullText: String, hoverX: Float, nodeBounds: Rect): String {
        val text  = fullText.trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 1) return words.firstOrNull()?.clean() ?: text

        val nodeLeft  = nodeBounds.left.toFloat()
        val nodeWidth = nodeBounds.width().toFloat()

        // Calculate which character in the full string the pen is over
        val charIndex = if (nodeWidth > 20f) {
            val xRatio = ((hoverX - nodeLeft) / nodeWidth).coerceIn(0f, 1f)
            (xRatio * text.length).toInt().coerceIn(0, text.length - 1)
        } else {
            // Bounds unavailable — use screen proportion
            val xRatio = (hoverX / resources.displayMetrics.widthPixels).coerceIn(0f, 1f)
            (xRatio * text.length).toInt().coerceIn(0, text.length - 1)
        }

        // Walk through words and find which one contains charIndex
        var pos = 0
        for (word in words) {
            val start = text.indexOf(word, pos)
            if (start == -1) continue
            val end = start + word.length
            if (charIndex in start..end) {
                Log.d(TAG, "charIndex=$charIndex → word=\"$word\" (${start}..${end})")
                return word.clean()
            }
            pos = end
        }

        // Nothing matched (shouldn't happen) — return nearest by proportion
        val proportion = if (nodeWidth > 20f)
            ((hoverX - nodeLeft) / nodeWidth).coerceIn(0f, 1f)
        else
            (hoverX / resources.displayMetrics.widthPixels).coerceIn(0f, 1f)
        val idx = (proportion * words.size).toInt().coerceIn(0, words.lastIndex)
        return words[idx].clean()
    }

    private fun String.clean() = this.trim().trimEnd('.', ',', '!', '?', ';', ':')

    // ── Helpers ───────────────────────────────────────────────────

    private fun cancelPending() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRunnable = null
    }

    private fun extractBestText(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) return desc
        return null
    }

    private fun extractSelectedText(event: AccessibilityEvent): String? {
        val node  = event.source ?: return null
        val text  = node.text   ?: run { node.recycle(); return null }
        val start = node.textSelectionStart
        val end   = node.textSelectionEnd
        node.recycle()
        return if (start >= 0 && end > start && end <= text.length)
            text.substring(start, end).trim()
        else null
    }

    // ── Translation ───────────────────────────────────────────────

    private fun translateAndShow(text: String) {
        Log.d(TAG, "Translating \"$text\" → $targetLanguageName")

        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { lang ->
                val src = if (lang == "und") TranslateLanguage.ENGLISH else lang
                if (src == targetLanguage) { showTooltip(text, null); return@addOnSuccessListener }
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(src)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).translate(text)
                    .addOnSuccessListener { showTooltip(it, text) }
                    .addOnFailureListener  { showTooltip("⚠ Download model first", null) }
            }
            .addOnFailureListener {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).translate(text)
                    .addOnSuccessListener { showTooltip(it, text) }
                    .addOnFailureListener { showTooltip("⚠ Download model first", null) }
            }
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
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 160 }

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 20, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A2E"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#881A73E8"))
            }
            elevation = 10f
        }

        val modeLabel = TextView(this).apply {
            text     = if (currentMode == Mode.WORD) "W\nO\nR\nD" else "¶\nP\nA\nR\nA"
            textSize = 11f
            setTextColor(0xFF1A73E8.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity  = Gravity.CENTER
        }
        btn.addView(modeLabel)

        btn.setOnClickListener {
            currentMode    = if (currentMode == Mode.WORD) Mode.PARAGRAPH else Mode.WORD
            modeLabel.text = if (currentMode == Mode.WORD) "W\nO\nR\nD" else "¶\nP\nA\nR\nA"
            lastProcessedText = ""
            showTooltip("Mode: ${currentMode.name}", null)
            mainHandler.postDelayed({ removeTooltip() }, 1200)
        }

        modeBtnView = btn
        try { windowManager?.addView(btn, params) }
        catch (e: Exception) { Log.e(TAG, "Mode button error", e); modeBtnView = null }
    }

    private fun removeModeButton() {
        modeBtnView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            modeBtnView = null
        }
    }

    // ── Tooltip ───────────────────────────────────────────────────

    private fun showTooltip(translated: String, original: String?) {
        mainHandler.post {
            removeTooltip()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(44, 24, 44, 24)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EE111111"))
                    cornerRadius = 22f
                    setStroke(1, Color.parseColor("#55FFFFFF"))
                }
                elevation = 20f
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text     = "→ $targetLanguageName"
                textSize = 11f
                setTextColor(0xFF88AAFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            header.addView(TextView(this).apply {
                text     = "  ·  ${currentMode.name}"
                textSize = 10f
                setTextColor(0xFF666688.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            container.addView(header)

            container.addView(TextView(this).apply {
                text     = translated
                textSize = if (currentMode == Mode.WORD) 22f else 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 8, 0, 0)
            })

            if (!original.isNullOrBlank()) {
                container.addView(TextView(this).apply {
                    text     = original
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                    setPadding(0, 6, 0, 0)
                })
            }

            tooltipView = container
            try {
                windowManager?.addView(container, params)
                val dismissMs = if (currentMode == Mode.PARAGRAPH) 7000L else 4000L
                mainHandler.postDelayed({ removeTooltip() }, dismissMs)
            } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
                tooltipView = null
            }
        }
    }

    private fun removeTooltip() {
        tooltipView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            tooltipView = null
        }
    }
}