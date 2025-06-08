package com.sikder.spentranslator

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

object TranslationApiClient {

    private const val TAG = "TranslationApiClient"
    private val translators = mutableMapOf<String, Translator>()

    /**
     * Identifies the language of a given text.
     * @param text The text to identify.
     * @param callback The function to call with the detected language code (e.g., "en")
     * or "und" (undetermined) if it fails.
     */
    fun identifyLanguage(text: String, callback: (String) -> Unit) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                if (languageCode == "und") {
                    Log.w(TAG, "Can't identify language of text.")
                } else {
                    Log.i(TAG, "Language identified: $languageCode")
                }
                callback(languageCode)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Language identification failed.", e)
                callback("und") // Return "undetermined" on failure
            }
    }

    /**
     * Translates text using ML Kit On-Device Translation.
     * Manages model downloads if needed.
     */
    fun translate(
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        callback: (String?) -> Unit
    ) {
        if (text.isBlank() || sourceLangCode == targetLangCode) {
            callback(null)
            return
        }

        val optionsKey = "$sourceLangCode-$targetLangCode"
        val translator: Translator

        synchronized(this) {
            if (translators.containsKey(optionsKey)) {
                translator = translators[optionsKey]!!
            } else {
                try {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLangCode)
                        .setTargetLanguage(targetLangCode)
                        .build()
                    translator = Translation.getClient(options)
                    translators[optionsKey] = translator
                    Log.i(TAG, "Created new translator for $sourceLangCode -> $targetLangCode")
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating translator.", e)
                    callback(null)
                    return
                }
            }
        }

        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                Log.i(TAG, "Language models are ready for $sourceLangCode -> $targetLangCode.")
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.i(TAG, "Successfully translated to \"$translatedText\"")
                        callback(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Error translating text.", exception)
                        callback(null)
                    }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error downloading language models.", exception)
                callback(null)
            }
    }
}