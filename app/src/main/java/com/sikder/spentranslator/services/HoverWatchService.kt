package com.sikder.spentranslator.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sikder.spentranslator.model.OcrWord
import com.sikder.spentranslator.spen.SPenHoverListener
import com.sikder.spentranslator.utils.OcrHelper

class HoverWatchService : AccessibilityService() {

    companion object {
        var targetLanguage: String     = TranslateLanguage.ENGLISH
        var targetLanguageName: String = "English"
        var isRunning: Boolean         = false
    }

    private var windowManager: WindowManager? = null
    private var tooltipView: LinearLayout?    = null
    private var lastProcessedText: String     = ""

    // S Pen hover listener — provides real (x, y) coordinates
    private lateinit var sPenHoverListener: SPenHoverListener

    // Latest OCR word list from CaptureService — refreshed on each hover
    private var lastOcrWords: List<OcrWord> = emptyList()

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning     = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        sPenHoverListener = SPenHoverListener(this) { x, y, isHovering ->
            if (isHovering) {
                onSPenHover(x, y)
            } else {
                removeTooltip()
                lastProcessedText = ""
            }
        }
        sPenHoverListener.register()
    }

    override fun onInterrupt() {
        removeTooltip()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sPenHoverListener.unregister()
        removeTooltip()
    }

    // ──────────────────────────────────────────────────────────────
    // Accessibility Events — fallback text selection path
    // ──────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val text = extractSelectedText(event)
                if (!text.isNullOrBlank() && text.trim() != lastProcessedText) {
                    lastProcessedText = text.trim()
                    translateAndShow(lastProcessedText)
                }
            }
        }
    }

    private fun extractSelectedText(event: AccessibilityEvent): String? {
        val node  = event.source ?: return null
        val text  = node.text   ?: return null
        val start = node.textSelectionStart
        val end   = node.textSelectionEnd
        return if (start >= 0 && end > start && end <= text.length)
            text.substring(start, end)
        else null
    }

    // ──────────────────────────────────────────────────────────────
    // S Pen Hover Path — hit-test coordinates against OCR words
    // ──────────────────────────────────────────────────────────────

    private fun onSPenHover(x: Float, y: Float) {
        // 1. Try hit-testing against already-captured OCR word list
        val hit = findWordAt(x, y, lastOcrWords)
        if (hit != null && hit != lastProcessedText) {
            lastProcessedText = hit
            translateAndShow(hit)
            return
        }

        // 2. No cached OCR words — request fresh screen capture + OCR
        val capture = CaptureService.instance ?: return
        capture.captureAndOcr { words ->
            if (words == null) return@captureAndOcr
            lastOcrWords = words
            val word = findWordAt(x, y, words)
            if (word != null && word != lastProcessedText) {
                lastProcessedText = word
                translateAndShow(word)
            }
        }
    }

    /** Returns the text of whichever OcrWord's bounding box contains (x, y). */
    private fun findWordAt(x: Float, y: Float, words: List<OcrWord>): String? =
        words.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }?.text

    // ──────────────────────────────────────────────────────────────
    // Translation
    // ──────────────────────────────────────────────────────────────

    private fun translateAndShow(text: String) {
        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                val src = if (langCode == "und") TranslateLanguage.ENGLISH else langCode
                if (src == targetLanguage) {
                    showTooltip("[$targetLanguageName] $text")
                    return@addOnSuccessListener
                }
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(src)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).translate(text)
                    .addOnSuccessListener { showTooltip(it) }
                    .addOnFailureListener { showTooltip("⚠ Download model first") }
            }
    }

    // ──────────────────────────────────────────────────────────────
    // Floating Tooltip
    // ──────────────────────────────────────────────────────────────

    private fun showTooltip(translatedText: String) {
        removeTooltip()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD111111"))
                cornerRadius = 18f
                setStroke(1, Color.parseColor("#44FFFFFF"))
            }
            elevation = 12f
        }

        container.addView(TextView(this).apply {
            text = "→ $targetLanguageName"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })

        container.addView(TextView(this).apply {
            text = translatedText
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 6, 0, 0)
        })

        tooltipView = container
        try {
            windowManager?.addView(container, params)
            container.postDelayed({ removeTooltip() }, 5000)
        } catch (_: Exception) {
            tooltipView = null
        }
    }

    private fun removeTooltip() {
        tooltipView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            tooltipView = null
        }
    }
}