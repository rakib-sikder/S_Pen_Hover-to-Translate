package com.sikder.spentranslator.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject // For parsing JSON

class TranslationApiClient {

    private val client = OkHttpClient()
    private val TAG = "TranslationApiClient"

    // This function will run on a background thread thanks to withContext(Dispatchers.IO)
    suspend fun translate(
        textToTranslate: String,
        sourceLang: String,
        targetLang: String
    ): String? { // Return nullable String for potential errors
        val langPair = "${sourceLang.toLowerCase()}|${targetLang.toLowerCase()}"
        val apiUrl = "https://api.mymemory.translated.net/get?q=${encodeURIComponent(textToTranslate)}&langpair=$langPair"

        Log.d(TAG, "Requesting translation from URL: $apiUrl")

        val request = Request.Builder()
            .url(apiUrl)
            .build()

        return withContext(Dispatchers.IO) { // Perform network call on IO dispatcher
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "API Raw Response: $responseBody")
                        // Parse the JSON response
                        val jsonResponse = JSONObject(responseBody)
                        val responseData = jsonResponse.optJSONObject("responseData")
                        val translatedText = responseData?.optString("translatedText")

                        if (translatedText.isNullOrEmpty() || translatedText.equals(textToTranslate, ignoreCase = true)) {
                            // Try to get from matches if primary is not good
                            val matches = jsonResponse.optJSONArray("matches")
                            if (matches != null && matches.length() > 0) {
                                // Simplistic: take the first match's translation
                                // A more robust approach would iterate and check quality/reliability
                                val firstMatch = matches.getJSONObject(0)
                                val matchTranslation = firstMatch.optString("translation")
                                if (!matchTranslation.isNullOrEmpty() && !matchTranslation.equals(textToTranslate, ignoreCase = true)) {
                                    Log.d(TAG, "Using translation from matches: $matchTranslation")
                                    return@withContext matchTranslation
                                }
                            }
                            Log.w(TAG, "Translated text is empty, same as input, or not found in responseData.")
                            return@withContext null // Or throw an exception
                        }
                        Log.d(TAG, "Translated Text: $translatedText")
                        return@withContext translatedText
                    } else {
                        Log.e(TAG, "API response body is null")
                        return@withContext null
                    }
                } else {
                    Log.e(TAG, "API request failed with code: ${response.code}")
                    Log.e(TAG, "Response message: ${response.message}")
                    Log.e(TAG, "Response body: ${response.body?.string()}") // Log error body
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network request failed: ${e.message}", e)
                return@withContext null
            }
        }
    }

    // Helper function for URL encoding (OkHttp usually handles this for query parameters if built properly,
    // but explicit encoding for path segments or complex queries is safer)
    private fun encodeURIComponent(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
            .replace("\\+".toRegex(), "%20")
    }
}