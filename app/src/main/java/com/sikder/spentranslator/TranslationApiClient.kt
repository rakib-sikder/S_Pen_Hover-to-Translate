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
    // Key: "sourceLangCode-targetLangCode", e.g., "en-bn"
    private val translators = mutableMapOf<String, Translator>()

    /**
     * Translates text using ML Kit On-Device Translation.
     * Manages model downloads if needed.
     *
     * @param text The text to translate.
     * @param sourceLangCode The ISO 639-1 code for the source language (e.g., "en").
     * @param targetLangCode The ISO 639-1 code for the target language (e.g., "bn").
     * @param callback Callback function that receives the translated string or null on failure.
     */
    fun translate(
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        callback: (String?) -> Unit
    ) {
        if (text.isBlank()) {
            Log.w(TAG, "Input text is blank, not attempting translation.")
            callback(null) // Return null for blank input
            return
        }

        val optionsKey = "$sourceLangCode-$targetLangCode"
        val translator: Translator

        // Get or create translator instance
        synchronized(this) {
            if (translators.containsKey(optionsKey)) {
                translator = translators[optionsKey]!!
                Log.d(TAG, "Reusing existing translator for $sourceLangCode -> $targetLangCode")
            } else {
                try {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(mlKitLanguageCode(sourceLangCode))
                        .setTargetLanguage(mlKitLanguageCode(targetLangCode))
                        .build()
                    translator = Translation.getClient(options)
                    translators[optionsKey] = translator
                    Log.i(TAG, "Created new translator for $sourceLangCode -> $targetLangCode")
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating translator for $sourceLangCode -> $targetLangCode. ${e.message}", e)
                    callback(null)
                    return
                }
            }
        }

        Log.i(TAG, "Preparing to translate: \"$text\" from $sourceLangCode to $targetLangCode")

        // Define download conditions. For example, require Wi-Fi.
        // Remove .requireWifi() to allow download over mobile data (inform users).
        val conditions = DownloadConditions.Builder()
            //.requireWifi() // Uncomment if you want to restrict model downloads to Wi-Fi
            .build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                Log.i(TAG, "Language models for $sourceLangCode -> $targetLangCode are ready or successfully downloaded.")
                // Models are downloaded, now translate.
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.i(TAG, "Successfully translated \"$text\" to \"$translatedText\"")
                        callback(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Error translating text: \"$text\" from $sourceLangCode to $targetLangCode. ${exception.message}", exception)
                        callback(null)
                    }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error downloading/ensuring language models for $sourceLangCode -> $targetLangCode. ${exception.message}", exception)
                // Attempt to translate anyway if model might exist but check failed (less common)
                // Or simply callback with null. For simplicity, we'll callback null here.
                // A more robust solution might check if the model is actually available despite downloadModelIfNeeded failing.
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.w(TAG, "Translation succeeded despite initial model download check failure for \"$text\" to \"$translatedText\"")
                        callback(translatedText)
                    }
                    .addOnFailureListener { translateException ->
                        Log.e(TAG, "Translation failed after model download check failure for: \"$text\". ${translateException.message}", translateException)
                        callback(null)
                    }
            }
    }

    /**
     * Converts common ISO 639-1 language codes to ML Kit's TranslateLanguage string constants.
     * ML Kit uses BCP-47 language tags.
     * See all supported languages: https://developers.google.com/ml-kit/language/translation/langid
     */
    private fun mlKitLanguageCode(langCode: String): String {
        return when (langCode.lowercase()) {
            "en" -> TranslateLanguage.ENGLISH
            "bn" -> TranslateLanguage.BENGALI
            "es" -> TranslateLanguage.SPANISH
            "hi" -> TranslateLanguage.HINDI
            "ar" -> TranslateLanguage.ARABIC
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "ru" -> TranslateLanguage.RUSSIAN
            "zh" -> TranslateLanguage.CHINESE // Typically "zh" for "zh-CN" or "zh-TW", ML Kit handles it as general Chinese
            // Add more languages your app will support here
            else -> {
                Log.w(TAG, "Unsupported language code '$langCode' provided. Please map it in mlKitLanguageCode or check ML Kit supported languages. Defaulting to ENGLISH for ML Kit call, but this may lead to errors if the source text is not English.")
                // Returning the original code and letting ML Kit decide or fail might be an option,
                // or defaulting to a known supported language like English.
                // For safety, if you can't map it, it's better to indicate failure early or have a clear default.
                // For now, if unmapped, ML Kit will likely fail if it's not a BCP-47 tag it recognizes.
                // Let's return a known valid code to avoid ML Kit crashing, though translation might be wrong.
                // Better would be to return an error/null in the callback if langCode is unsupported.
                // For this implementation, we'll pass it through and log a warning.
                // Ideally, you should validate langCode before this point.
                langCode // Or throw IllegalArgumentException("Unsupported language code: $langCode")
            }
        }
    }

    /**
     * Call this method to release translator resources, e.g., when your app is closing
     * or the services that use it are being destroyed.
     */
    fun closeAllTranslators() {
        synchronized(this) {
            if (translators.isNotEmpty()) {
                Log.i(TAG, "Closing ${translators.size} cached translators...")
                translators.values.forEach {
                    try {
                        it.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing a translator instance.", e)
                    }
                }
                translators.clear()
                Log.i(TAG, "All translators closed and cache cleared.")
            } else {
                Log.d(TAG, "No translators in cache to close.")
            }
        }
    }
}