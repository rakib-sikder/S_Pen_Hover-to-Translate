package com.sikder.spentranslator

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope // Import this
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sikder.spentranslator.network.TranslationApiClient
import com.sikder.spentranslator.ui.theme.SPenTranslatorTheme
import kotlinx.coroutines.launch // Ensure this import is present

class MainActivity : ComponentActivity() {
    private val TAG = "SPenComposeDebug"
    private val apiClient = TranslationApiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var sPenLogMessage by remember { mutableStateOf("Hover S Pen over this area...") }
            var translatedText by remember { mutableStateOf("Translation will appear here.") }
            var isLoadingTranslation by remember { mutableStateOf(false) }

            val textToActuallyTranslate = "name"

            // Get a CoroutineScope bound to this Composable's lifecycle
            val coroutineScope = rememberCoroutineScope()

            SPenTranslatorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event: PointerEvent = awaitPointerEvent()
                                    val isStylus = event.changes.any { it.type == PointerType.Stylus }

                                    if (isStylus) {
                                        val position = event.changes.first().position
                                        val x = String.format("%.2f", position.x)
                                        val y = String.format("%.2f", position.y)
                                        val eventType = event.type
                                        val newLog: String = when (eventType) {
                                            PointerEventType.Enter -> "S Pen ENTER: x=$x, y=$y"
                                            PointerEventType.Move -> "S Pen MOVE: x=$x, y=$y"
                                            PointerEventType.Exit -> "S Pen EXIT: x=$x, y=$y"
                                            else -> sPenLogMessage
                                        }
                                        sPenLogMessage = newLog
                                        Log.d(TAG, newLog)

                                        if (eventType == PointerEventType.Exit && !isLoadingTranslation) {
                                            isLoadingTranslation = true
                                            // Use the scope obtained from rememberCoroutineScope()
                                            coroutineScope.launch {
                                                Log.d(TAG, "Attempting to translate '$textToActuallyTranslate'")
                                                val result = apiClient.translate(textToActuallyTranslate, "en", "bn")
                                                translatedText = result ?: "Translation failed / not found."
                                                isLoadingTranslation = false
                                                Log.d(TAG, "Translation API call finished. Result: $translatedText")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        ScreenContent(
                            logMessage = sPenLogMessage,
                            translation = translatedText,
                            isLoading = isLoadingTranslation,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
        Log.d(TAG, "MainActivity (Compose) loaded. On-screen S Pen log display active.")
    }
}

// ScreenContent and Preview functions remain the same as the corrected version from before
@Composable
fun ScreenContent(logMessage: String, translation: String, isLoading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "S Pen Debug Info:")
        Text(
            text = logMessage,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            Text("Loading translation...")
        } else {
            Text(text = "Translation (en to bn):")
            Text(
                text = translation,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SPenTranslatorTheme {
        ScreenContent(
            logMessage = "Preview: S Pen MOVE: x=123.45, y=678.90",
            translation = "পূর্বরূপ অনুবাদ",
            isLoading = false
        )
    }
}