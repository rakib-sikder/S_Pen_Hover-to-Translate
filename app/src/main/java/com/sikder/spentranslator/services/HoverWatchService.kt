package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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

class HoverWatchService : AccessibilityService() {

    companion object {
        private const val TAG          = "SPenTranslate"
        var targetLanguage: String     = TranslateLanguage.ENGLISH
        var targetLanguageName: String = "English"
        var isRunning: Boolean         = false
        private const val DWELL_MS     = 800L

        // Translation mode — toggled by the floating mode button
        enum class Mode { WORD, PARAGRAPH }
        var currentMode: Mode = Mode.WORD
    }

    private var windowManager: WindowManager? = null
    private var tooltipView: LinearLayout?    = null
    private var modeBtnView: LinearLayout?    = null
    private var lastProcessedText             = ""
    private val mainHandler                   = Handler(Looper.getMainLooper())
    private var lastOcrWords: List<OcrWord>   = emptyList()
    private var lastHoverX                    = 0f
    private var lastHoverY                    = 0f

    private val dwellRunnable = Runnable {
        triggerOcrFallback(lastHoverX, lastHoverY)
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning     = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes =
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED      or
                        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER             or
                        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
            info.flags =
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 0
        }

        Log.d(TAG, "✓ Service connected. Target=$targetLanguageName mode=$currentMode")
        showModeButton()
        showTooltip("✓ S Pen Translate active\nHover over any text", null)
        mainHandler.postDelayed({ removeTooltip() }, 2500)
    }

    override fun onInterrupt() { removeTooltip() }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        removeTooltip()
        removeModeButton()
    }

    // ── onMotionEvent — track raw S Pen XY ───────────────────────

    override fun onMotionEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_ENTER -> {
                lastHoverX = event.rawX
                lastHoverY = event.rawY
            }
        }
    }

    // ── Accessibility Events ──────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {

            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                mainHandler.removeCallbacks(dwellRunnable)
                val node = event.source
                val fullText = extractBestText(node)
                node?.recycle()

                if (fullText.isNullOrBlank()) {
                    mainHandler.postDelayed(dwellRunnable, DWELL_MS)
                    return
                }

                // Apply mode: word or paragraph
                val textToTranslate = when (currentMode) {
                    Mode.WORD      -> pickNearestWord(fullText, lastHoverX)
                    Mode.PARAGRAPH -> fullText.trim()
                }

                Log.d(TAG, "[${currentMode}] hover text=\"$textToTranslate\"")
                if (textToTranslate.isBlank() || textToTranslate == lastProcessedText) return
                lastProcessedText = textToTranslate
                translateAndShow(textToTranslate)
            }

            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                mainHandler.removeCallbacks(dwellRunnable)
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = extractSelectedText(event) ?: return
                if (text.isBlank() || text == lastProcessedText) return
                lastProcessedText = text
                Log.d(TAG, "Selection: \"$text\"")
                translateAndShow(text)
            }
        }
    }

    // ── Text extraction helpers ───────────────────────────────────

    private fun extractBestText(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) return desc
        val hint = node.hintText?.toString()?.trim()
        if (!hint.isNullOrBlank()) return hint
        return null
    }

    /**
     * In WORD mode: pick the single word from fullText that is
     * closest to where the S Pen is hovering horizontally.
     * Simple approach: split by spaces, pick word at proportional position.
     */
    private fun pickNearestWord(fullText: String, hoverX: Float): String {
        val words = fullText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 1) return fullText.trim()

        // Use hoverX as a rough proportion of screen width (1080px typical)
        // to pick which word index the pen is hovering over
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val proportion  = (hoverX / screenWidth).coerceIn(0f, 1f)
        val index       = (proportion * words.size).toInt().coerceIn(0, words.lastIndex)
        return words[index].trim().trimEnd('.', ',', '!', '?', ';', ':')
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

    // ── OCR fallback (for canvas/PDF/web views) ───────────────────

    private fun triggerOcrFallback(x: Float, y: Float) {
        val capture = CaptureService.instance ?: return
        Log.d(TAG, "OCR fallback at ($x, $y) mode=$currentMode")

        capture.captureAndOcr { words ->
            if (words.isNullOrEmpty()) return@captureAndOcr
            lastOcrWords = words

            val textToTranslate = when (currentMode) {
                Mode.WORD -> {
                    // Exact word hit
                    val hit = words.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }
                        ?: words.minByOrNull {
                            val dx = (it.bounds.centerX() - x).toDouble()
                            val dy = (it.bounds.centerY() - y).toDouble()
                            dx * dx + dy * dy
                        }
                    hit?.text
                }
                Mode.PARAGRAPH -> {
                    // Find all words on same line (within 50px vertically of hover)
                    val lineWords = words.filter {
                        Math.abs(it.bounds.centerY() - y) < 50
                    }.sortedBy { it.bounds.left }
                    if (lineWords.isEmpty()) {
                        words.minByOrNull {
                            val dx = (it.bounds.centerX() - x).toDouble()
                            val dy = (it.bounds.centerY() - y).toDouble()
                            dx * dx + dy * dy
                        }?.text
                    } else {
                        lineWords.joinToString(" ") { it.text }
                    }
                }
            }

            val text = textToTranslate ?: return@captureAndOcr
            if (text == lastProcessedText) return@captureAndOcr
            lastProcessedText = text
            translateAndShow(text)
        }
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
                    .addOnSuccessListener { translated ->
                        Log.d(TAG, "✓ \"$text\" → \"$translated\"")
                        showTooltip(translated, text)
                    }
                    .addOnFailureListener {
                        showTooltip("⚠ Download model first", null)
                    }
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

    // ── Floating mode button (Word / Paragraph) ───────────────────

    private fun showModeButton() {
        removeModeButton()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 160
        }

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

        // Toggle mode on click
        btn.setOnClickListener {
            currentMode = if (currentMode == Mode.WORD) Mode.PARAGRAPH else Mode.WORD
            modeLabel.text = if (currentMode == Mode.WORD) "W\nO\nR\nD" else "¶\nP\nA\nR\nA"
            lastProcessedText = "" // reset so next hover retranslates
            Log.d(TAG, "Mode switched to $currentMode")
            showTooltip("Mode: ${currentMode.name}", null)
            mainHandler.postDelayed({ removeTooltip() }, 1200)
        }

        modeBtnView = btn
        try { windowManager?.addView(btn, params) } catch (e: Exception) {
            Log.e(TAG, "Failed to add mode button", e)
            modeBtnView = null
        }
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
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 80
            }

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

            // Header row: "→ Bengali  |  WORD"
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

            // Translated text
            container.addView(TextView(this).apply {
                text     = translated
                textSize = if (currentMode == Mode.WORD) 22f else 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 8, 0, 0)
            })

            // Original text (small)
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