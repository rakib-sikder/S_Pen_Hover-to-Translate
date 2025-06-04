package com.sikder.spentranslator

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

object TranslationApiClient {

    private const val TAG = "TranslationApiClientMLKit"

    // Cache for translators to reuse them and their downloaded models
    private val translators = mutableMapOf<String, Translator>()

    fun translate(
        text: String,
        sourceLangCode: String, // e.g., "en"
        targetLangCode: String, // e.g., "bn"
        callback: (String?) -> Unit
    ) {
        if (text.isBlank()) {
            Log.w(TAG, "Input text is blank, not translating.")
            callback(null)
            return
        }

        val optionsKey = "$sourceLangCode-$targetLangCode"
        val translator = translators[optionsKey] ?: synchronized(this) {
            // Double check if another thread created it while waiting for synchronized block
            translators[optionsKey] ?: try {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlKitLanguageCode(sourceLangCode))
                    .setTargetLanguage(mlKitLanguageCode(targetLangCode))
                    .build()
                Translation.getClient(options).also {
                    translators[optionsKey] = it
                    Log.i(TAG, "Created new translator for $sourceLangCode -> $targetLangCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating translator for $sourceLangCode -> $targetLangCode", e)
                callback(null)
                return
            }
        }

        Log.i(TAG, "Attempting to translate: \"$text\" from $sourceLangCode to $targetLangCode")

        // Models need to be downloaded.
        // For simplicity, we try to download if needed, then translate.
        // A more robust app would manage downloads more explicitly, show progress, etc.
        val conditions = DownloadConditions.Builder()
            .requireWifi() // Example: download only on Wi-Fi. Remove if not desired.
            .build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                Log.i(TAG, "Language models for $sourceLangCode -> $targetLangCode are ready or downloaded.")
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.i(TAG, "Successfully translated \"$text\" to \"$translatedText\"")
                        callback(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Error translating text: \"$text\"", exception)
                        callback(null)
                    }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error downloading/ensuring language models for $sourceLangCode -> $targetLangCode", exception)
                callback(null)
            }
    }

    /**
     * Converts common ISO 639-1 language codes to ML Kit's TranslateLanguage string constants.
     * Add more mappings as needed.
     */
    private fun mlKitLanguageCode(langCode: String): String {
        return when (langCode.lowercase()) {
            "en" -> TranslateLanguage.ENGLISH
            "bn" -> TranslateLanguage.BENGALI
            "es" -> TranslateLanguage.SPANISH
            "hi" -> TranslateLanguage.HINDI
            "ar" -> TranslateLanguage.ARABIC
            "fr" -> TranslateLanguage.FRENCH
            // Add more languages your app will support
            else -> {
                Log.w(TAG, "Unsupported language code '$langCode', defaulting to ENGLISH. Please map it in mlKitLanguageCode.")
                TranslateLanguage.ENGLISH // Default or throw an exception
            }
        }
    }

    // Optional: Call this when your app is closing or services are destroyed to clean up.
    // However, for a long-running accessibility service, translators might be kept alive.
    fun closeAllTranslators() {
        synchronized(this) {
            Log.i(TAG, "Closing all translators...")
            translators.values.forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing a translator", e)
                }
            }
            translators.clear()
            Log.i(TAG, "All translators closed and cache cleared.")
        }
    }
}