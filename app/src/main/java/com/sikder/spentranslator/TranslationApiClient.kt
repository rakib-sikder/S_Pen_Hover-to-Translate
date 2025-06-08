package com.sikder.spentranslator

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

object TranslationApiClient {
    private const val TAG = "TranslationApiClient"
    private val translators = mutableMapOf<String, Translator>()

    fun translate(
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        callback: (String?) -> Unit
    ) {
        if (text.isBlank()) {
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
                translator.translate(text)
                    .addOnSuccessListener { translatedText -> callback(translatedText) }
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