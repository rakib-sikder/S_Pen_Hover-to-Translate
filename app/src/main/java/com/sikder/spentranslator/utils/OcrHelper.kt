package com.sikder.spentranslator.utils

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrHelper {
    private const val TAG = "OcrHelper"

    fun recognizeTextFromBitmap(bitmap: Bitmap, callback: (String?) -> Unit) {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Bitmap is recycled, cannot process for OCR.")
            callback(null)
            return
        }
        Log.d(TAG, "Starting text recognition from bitmap.")
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val recognizedText = visionText.text
                if (recognizedText.isNotBlank()) {
                    Log.i(TAG, "OCR Success. Recognized text: \"${recognizedText.take(150)}...\"")
                    callback(recognizedText)
                } else {
                    Log.w(TAG, "OCR Success but no text found or text is blank.")
                    callback(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed.", e)
                callback(null)
            }
    }
}