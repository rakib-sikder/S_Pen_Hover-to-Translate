package com.sikder.spentranslator.utils // Or your chosen package for utility classes

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions // For Latin script recognizer

object OcrHelper {
    private const val TAG = "OcrHelper"

    /**
     * Recognizes text from a given Bitmap using ML Kit Text Recognition.
     *
     * @param bitmap The input image from which to recognize text.
     * @param callback A function to call with the recognition result.
     * It receives the recognized text as a String, or null if recognition
     * failed or no text was found.
     */
    fun recognizeTextFromBitmap(bitmap: Bitmap, callback: (String?) -> Unit) {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Bitmap is recycled, cannot process for OCR.")
            callback(null)
            return
        }

        Log.d(TAG, "Starting text recognition from bitmap. Width: ${bitmap.width}, Height: ${bitmap.height}")
        val image = InputImage.fromBitmap(bitmap, 0) // 0 for rotationDegrees if bitmap is upright

        // Get an instance of the TextRecognizer for Latin script
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val recognizedText = visionText.text
                if (recognizedText.isNotBlank()) {
                    // Log only a part of the text if it's very long
                    val logText = if (recognizedText.length > 150) "${recognizedText.substring(0, 150)}..." else recognizedText
                    Log.i(TAG, "OCR Success. Recognized text: \"$logText\"")
                    callback(recognizedText)
                } else {
                    Log.w(TAG, "OCR Success but no text found or the extracted text is blank.")
                    callback(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed.", e)
                callback(null)
            }
            .addOnCompleteListener {
                // It's generally the responsibility of the caller that created/obtained the Bitmap
                // to manage its lifecycle (e.g., recycling it after OCR if it's no longer needed).
                // If OcrHelper creates a copy or is the sole owner, then recycle here.
                // For now, we assume the caller manages the original bitmap.
                Log.d(TAG, "Text recognition process complete.")
            }
    }
}