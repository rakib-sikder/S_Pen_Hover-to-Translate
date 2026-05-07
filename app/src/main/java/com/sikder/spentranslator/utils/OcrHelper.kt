package com.sikder.spentranslator.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sikder.spentranslator.model.OcrWord

object OcrHelper {
    private const val TAG = "OcrHelper"

    /**
     * Runs ML Kit OCR on [bitmap] and returns every recognized word
     * with its screen-space bounding box.
     *
     * Used by HoverWatchService to hit-test the S Pen (x, y) position
     * against word bounding boxes and translate only the hovered word.
     */
    fun recognizeWords(bitmap: Bitmap, callback: (List<OcrWord>) -> Unit) {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Bitmap is recycled, cannot process for OCR.")
            callback(emptyList())
            return
        }

        val image      = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val words = mutableListOf<OcrWord>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox ?: continue
                            words.add(OcrWord(text = element.text, bounds = box))
                        }
                    }
                }
                callback(words)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed.", e)
                callback(emptyList())
            }
    }

    /**
     * Legacy helper — returns the full recognized string.
     * Still used by CaptureService.captureAndOcr().
     */
    fun recognizeTextFromBitmap(bitmap: Bitmap, callback: (String?) -> Unit) {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Bitmap is recycled, cannot process for OCR.")
            callback(null)
            return
        }
        val image      = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                callback(if (text.isNotBlank()) text else null)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed.", e)
                callback(null)
            }
    }
}
