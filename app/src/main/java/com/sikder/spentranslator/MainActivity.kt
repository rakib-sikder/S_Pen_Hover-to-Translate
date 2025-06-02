package com.sikder.spentranslator


import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch // Essential for the launch builder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sikder.spentranslator.network.TranslationApiClient // Ensure this path is correct
import com.sikder.spentranslator.ui.theme.SPenTranslatorTheme
import kotlinx.coroutines.launch // Ensure this import for launch is present

class MainActivity : ComponentActivity() {
    private val TAG = "SPenComposeDebug"
    // Ensure TranslationApiClient is in the correct package or imported correctly
    // and that its 'translate' method is a suspend function.
    private val apiClient = TranslationApiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var sPenLogMessage by remember { mutableStateOf("Hover S Pen to see coordinates...") }
            var translatedText by remember { mutableStateOf("Translation will appear here.") }
            var isLoadingTranslation by remember { mutableStateOf(false) }
            var textToTranslateInput by remember { mutableStateOf("") }

            // Get a CoroutineScope bound to this Composable's lifecycle
            val coroutineScope = rememberCoroutineScope()
            SPenTranslatorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { // key1 = Unit: doesn't restart on recomposition
                            // `awaitPointerEventScope` provides a CoroutineScope
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
                                            else -> sPenLogMessage // Keep current message if not updating
                                        }
                                        // State writes in Compose should be on the main thread.
                                        // `awaitPointerEventScope` runs on the main thread.
                                        sPenLogMessage = newLog
                                        Log.d(TAG, newLog)

                                        // Translate the text from the input field on hover exit
                                        if (event.type == PointerEventType.Exit &&
                                            !isLoadingTranslation &&
                                            textToTranslateInput.isNotBlank()
                                        ) {
                                            isLoadingTranslation = true
                                            // Use the coroutineScope obtained from rememberCoroutineScope()
                                            coroutineScope.launch { // <<-- CHANGE THIS LINE
                                                Log.d(TAG, "Attempting to translate '$textToTranslateInput'")
                                                val result = apiClient.translate(textToTranslateInput, "en", "bn")
                                                translatedText = result ?: "Translation failed / not found for '$textToTranslateInput'."
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
                            textToTranslate = textToTranslateInput,
                            onTextToTranslateChange = { newText -> textToTranslateInput = newText },
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

@Composable
fun ScreenContent(
    logMessage: String,
    textToTranslate: String,
    onTextToTranslateChange: (String) -> Unit,
    translation: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top, // Align content to the top
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "S Pen Debug Info:", modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = logMessage,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = textToTranslate,
            onValueChange = onTextToTranslateChange,
            label = { Text("Enter English text to translate") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = false,
            colors = TextFieldDefaults.colors( // Basic dark theme friendly colors
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.Gray,
                focusedContainerColor = Color(0xFF3A3A3C), // Slightly different from background
                unfocusedContainerColor = Color(0xFF2C2C2E),
                disabledContainerColor = Color(0xFF2C2C2E),
                cursorColor = Color.White,
                focusedIndicatorColor = Color.Cyan, // Accent color for focus
                unfocusedIndicatorColor = Color.DarkGray,
                focusedLabelColor = Color.Cyan,
                unfocusedLabelColor = Color.Gray,
            )
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            Text("Translating...")
        } else {
            Text(text = "Translation (en to bn):", modifier = Modifier.padding(top = 8.dp))
            Text(
                text = translation,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1E) // Dark background for preview
@Composable
fun DefaultPreview() {
    SPenTranslatorTheme {
        ScreenContent(
            logMessage = "S Pen Hover: x=100.00, y=200.00",
            textToTranslate = "Hello there",
            onTextToTranslateChange = {}, // Dummy callback for preview
            translation = "ওহে আচ্ছা", // Example Bangla translation
            isLoading = false
        )
    }
}