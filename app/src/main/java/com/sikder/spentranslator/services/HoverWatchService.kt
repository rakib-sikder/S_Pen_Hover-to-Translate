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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
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
        private const val TAG = "SPenTranslate"
        var targetLanguage: String     = TranslateLanguage.ENGLISH
        var targetLanguageName: String = "English"
        var isRunning: Boolean         = false

        enum class Mode { WORD, PARAGRAPH }
        var currentMode: Mode = Mode.WORD
    }

    private var windowManager: WindowManager? = null

    // Overlays
    private var highlightView: android.view.View? = null  // blue word selection box
    private var tooltipView: LinearLayout?         = null  // translation bubble
    private var modeBtnView: LinearLayout?         = null  // W/P mode toggle

    private var lastProcessedText = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastOcrWords: List<OcrWord> = emptyList()

    // Exact S Pen position from onMotionEvent
    private var penX = 0f
    private var penY = 0f

    // Last word's screen bounds — used to position tooltip next to the word
    private var lastWordBounds = Rect()

    private var tts: TextToSpeech? = null

    private val dwellRunnable = Runnable { triggerOcrFallback(penX, penY) }

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

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.ENGLISH
        }

        Log.d(TAG, "✓ Connected. Target=$targetLanguageName")
        showModeButton()
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

    // ── S Pen exact position ──────────────────────────────────────

    override fun onMotionEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                penX = event.rawX
                penY = event.rawY
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

                if (node == null) {
                    mainHandler.postDelayed(dwellRunnable, 800)
                    return
                }

                val fullText = getTextFromNode(node)

                if (fullText.isNullOrBlank()) {
                    node.recycle()
                    mainHandler.postDelayed(dwellRunnable, 800)
                    return
                }

                // Get node screen bounds for tooltip positioning
                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                val textToTranslate = when (currentMode) {
                    Mode.WORD      -> getWordAtPosition(fullText, penX, nodeBounds)
                    Mode.PARAGRAPH -> fullText.trim()
                }

                // Word bounds = estimate position within node for the specific word
                lastWordBounds = estimateWordBounds(
                    text        = fullText,
                    word        = textToTranslate,
                    nodeBounds  = nodeBounds,
                    penX        = penX,
                    penY        = penY
                )

                node.recycle()

                Log.d(TAG, "[${currentMode.name}] \"$textToTranslate\" bounds=$lastWordBounds")

                if (textToTranslate.isBlank() || textToTranslate == lastProcessedText) return
                lastProcessedText = textToTranslate

                // Show selection highlight on the word
                showHighlight(lastWordBounds)
                translateAndShow(textToTranslate, lastWordBounds)
            }

            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                mainHandler.removeCallbacks(dwellRunnable)
                // Keep highlight and tooltip visible for reading
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val selected = extractSelectedText(event) ?: return
                if (selected.isBlank() || selected == lastProcessedText) return
                lastProcessedText = selected
                // Use pen position for tooltip placement
                lastWordBounds = Rect(
                    penX.toInt() - 50, penY.toInt() - 20,
                    penX.toInt() + 50, penY.toInt() + 20
                )
                showHighlight(lastWordBounds)
                translateAndShow(selected, lastWordBounds)
            }
        }
    }

    // ── Word picking ──────────────────────────────────────────────

    private fun getWordAtPosition(fullText: String, x: Float, nodeBounds: Rect): String {
        val trimmed = fullText.trim()
        val words   = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size == 1) return words[0].trimPunctuation()
        if (words.isEmpty()) return trimmed

        if (nodeBounds.isEmpty || nodeBounds.width() == 0) return words[0].trimPunctuation()

        // Map pen X → character position → word
        val relX       = (x - nodeBounds.left).coerceIn(0f, nodeBounds.width().toFloat())
        val proportion = relX / nodeBounds.width()
        val penCharPos = proportion * trimmed.length

        var charCursor = 0
        for (word in words) {
            val wordEnd = charCursor + word.length
            if (penCharPos <= wordEnd + 1) {
                return word.trimPunctuation()
            }
            charCursor += word.length + 1
        }
        return words.last().trimPunctuation()
    }

    /**
     * Estimate the screen rect of a specific word within a node.
     * Used to position the highlight and tooltip right on the word.
     */
    private fun estimateWordBounds(
        text: String,
        word: String,
        nodeBounds: Rect,
        penX: Float,
        penY: Float
    ): Rect {
        if (nodeBounds.isEmpty) {
            // Fallback to pen position
            return Rect(
                (penX - 40).toInt(), (penY - 16).toInt(),
                (penX + 40).toInt(), (penY + 16).toInt()
            )
        }

        val trimmed   = text.trim()
        val wordIndex = trimmed.indexOf(word)
        if (wordIndex < 0) return nodeBounds

        val totalChars = trimmed.length.toFloat()
        val wordStart  = wordIndex / totalChars
        val wordEnd    = (wordIndex + word.length) / totalChars

        val left   = (nodeBounds.left + wordStart * nodeBounds.width()).toInt()
        val right  = (nodeBounds.left + wordEnd   * nodeBounds.width()).toInt()
        val top    = nodeBounds.top
        val bottom = nodeBounds.bottom

        return Rect(left, top, right, bottom)
    }

    private fun getTextFromNode(node: AccessibilityNodeInfo?): String? {
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

    private fun String.trimPunctuation() =
        this.trim('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']')

    // ── OCR fallback ──────────────────────────────────────────────

    private fun triggerOcrFallback(x: Float, y: Float) {
        val capture = CaptureService.instance ?: return
        capture.captureAndOcr { words ->
            if (words.isNullOrEmpty()) return@captureAndOcr
            lastOcrWords = words

            when (currentMode) {
                Mode.WORD -> {
                    val hit = words.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }
                        ?: words.minByOrNull {
                            val dx = (it.bounds.centerX() - x).toDouble()
                            val dy = (it.bounds.centerY() - y).toDouble()
                            dx * dx + dy * dy
                        } ?: return@captureAndOcr
                    if (hit.text == lastProcessedText) return@captureAndOcr
                    lastProcessedText = hit.text
                    lastWordBounds    = hit.bounds
                    showHighlight(hit.bounds)
                    translateAndShow(hit.text, hit.bounds)
                }
                Mode.PARAGRAPH -> {
                    val lineWords = words.filter { Math.abs(it.bounds.centerY() - y) < 60 }
                        .sortedBy { it.bounds.left }
                    if (lineWords.isEmpty()) return@captureAndOcr
                    val joined = lineWords.joinToString(" ") { it.text }
                    if (joined == lastProcessedText) return@captureAndOcr
                    lastProcessedText = joined
                    val lineBounds = Rect(
                        lineWords.first().bounds.left,  lineWords.first().bounds.top,
                        lineWords.last().bounds.right,  lineWords.last().bounds.bottom
                    )
                    lastWordBounds = lineBounds
                    showHighlight(lineBounds)
                    translateAndShow(joined, lineBounds)
                }
            }
        }
    }

    // ── Translation ───────────────────────────────────────────────

    private fun translateAndShow(text: String, wordBounds: Rect) {
        Log.d(TAG, "Translating \"$text\" → $targetLanguageName")
        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { lang ->
                val src = if (lang == "und") TranslateLanguage.ENGLISH else lang
                if (src == targetLanguage) {
                    showTooltip(text, text, wordBounds)
                    return@addOnSuccessListener
                }
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(src)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).translate(text)
                    .addOnSuccessListener { showTooltip(it, text, wordBounds) }
                    .addOnFailureListener { showTooltip("⚠ Download model first", text, wordBounds) }
            }
            .addOnFailureListener {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).translate(text)
                    .addOnSuccessListener { showTooltip(it, text, wordBounds) }
                    .addOnFailureListener { showTooltip("⚠ Download model first", text, wordBounds) }
            }
    }

    // ── Blue word highlight ───────────────────────────────────────

    private fun showHighlight(bounds: Rect) {
        mainHandler.post {
            removeHighlight()
            if (bounds.isEmpty) return@post

            val view = android.view.View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#551A73E8"))  // semi-transparent blue
                    cornerRadius = 4f
                }
            }

            val params = WindowManager.LayoutParams(
                bounds.width() + 8,
                bounds.height() + 4,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (bounds.left - 4).coerceAtLeast(0)
                y = (bounds.top  - 2).coerceAtLeast(0)
            }

            highlightView = view
            try { windowManager?.addView(view, params) }
            catch (e: Exception) { Log.e(TAG, "Highlight failed", e); highlightView = null }
        }
    }

    private fun removeHighlight() {
        highlightView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            highlightView = null
        }
    }

    // ── Translation tooltip near the word ─────────────────────────

    private fun showTooltip(translated: String, original: String, wordBounds: Rect) {
        mainHandler.post {
            removeTooltip()

            val screenH = resources.displayMetrics.heightPixels

            // Decide: show above or below the word?
            // If word is in top half → show below. If bottom half → show above.
            val showBelow = wordBounds.centerY() < screenH / 2
            val tooltipY  = if (showBelow) {
                wordBounds.bottom + 12
            } else {
                wordBounds.top - 120  // estimated tooltip height
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = wordBounds.left.coerceAtLeast(8)
                    .coerceAtMost(resources.displayMetrics.widthPixels - 300)
                y = tooltipY.coerceIn(80, screenH - 200)
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 18, 28, 18)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 18f
                    setStroke(0, Color.TRANSPARENT)
                }
                elevation = 24f
            }

            // Translated text (large, primary)
            container.addView(TextView(this).apply {
                text      = translated
                textSize  = if (currentMode == Mode.WORD) 20f else 15f
                typeface  = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF111111"))
            })

            // Original word (small, muted)
            container.addView(TextView(this).apply {
                text     = original
                textSize = 12f
                setTextColor(Color.parseColor("#FF888888"))
                setPadding(0, 4, 0, 8)
            })

            // Divider
            container.addView(android.view.View(this).apply {
                setBackgroundColor(Color.parseColor("#FFE0E0E0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
            })

            // Action buttons row: Speak + Copy
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }

            // Speak button
            btnRow.addView(makeIconBtn("🔊") {
                tts?.speak(original, TextToSpeech.QUEUE_FLUSH, null, null)
            })

            // Divider
            btnRow.addView(android.view.View(this).apply {
                setBackgroundColor(Color.parseColor("#FFE0E0E0"))
                layoutParams = LinearLayout.LayoutParams(1, 40).also { it.setMargins(12, 0, 12, 0) }
            })

            // Copy button
            btnRow.addView(makeIconBtn("📋") {
                val clip = ClipData.newPlainText("translation", translated)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                removeAllOverlays()
            })

            // Divider
            btnRow.addView(android.view.View(this).apply {
                setBackgroundColor(Color.parseColor("#FFE0E0E0"))
                layoutParams = LinearLayout.LayoutParams(1, 40).also { it.setMargins(12, 0, 12, 0) }
            })

            // Close button
            btnRow.addView(makeIconBtn("✕") {
                removeAllOverlays()
                lastProcessedText = ""
            })

            container.addView(btnRow)

            tooltipView = container

            try {
                windowManager?.addView(container, params)
                // Auto-dismiss
                val ms = if (currentMode == Mode.PARAGRAPH) 8000L else 5000L
                mainHandler.postDelayed({ removeAllOverlays() }, ms)
            } catch (e: Exception) {
                Log.e(TAG, "Tooltip failed", e)
                tooltipView = null
            }
        }
    }

    private fun makeIconBtn(emoji: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text     = emoji
            textSize = 18f
            setPadding(16, 8, 16, 8)
            setOnClickListener { onClick() }
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 8f
            }
        }
    }

    private fun removeTooltip() {
        tooltipView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            tooltipView = null
        }
    }

    private fun removeAllOverlays() {
        mainHandler.removeCallbacks(dwellRunnable)
        removeHighlight()
        removeTooltip()
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
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 200 }

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 16, 14, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A2E"))
                cornerRadius = 12f
                setStroke(1, Color.parseColor("#881A73E8"))
            }
            elevation = 10f
        }

        val label = TextView(this).apply {
            text     = modeLabel()
            textSize = 10f
            setTextColor(0xFF1A73E8.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity  = Gravity.CENTER
        }
        btn.addView(label)
        btn.setOnClickListener {
            currentMode = if (currentMode == Mode.WORD) Mode.PARAGRAPH else Mode.WORD
            label.text        = modeLabel()
            lastProcessedText = ""
            removeAllOverlays()
        }
        modeBtnView = btn
        try { windowManager?.addView(btn, params) }
        catch (e: Exception) { Log.e(TAG, "Mode button failed", e); modeBtnView = null }
    }

    private fun modeLabel() = if (currentMode == Mode.WORD) "W\nO\nR\nD" else "¶\nP\nA\nR\nA"

    private fun removeModeButton() {
        modeBtnView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            modeBtnView = null
        }
    }
}