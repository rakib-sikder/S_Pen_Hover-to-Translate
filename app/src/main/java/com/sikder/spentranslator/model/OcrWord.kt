package com.sikder.spentranslator.model

import android.graphics.Rect

/**
 * Represents a single word found by ML Kit OCR,
 * with its exact position on the physical screen.
 *
 * @param text   the recognized word string
 * @param bounds screen-space bounding box in pixels (absolute coords)
 */
data class OcrWord(
    val text: String,
    val bounds: Rect
)
