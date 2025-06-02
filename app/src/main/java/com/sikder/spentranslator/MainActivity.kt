package com.sikder.spentranslator

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sikder.spentranslator.network.TranslationApiClient // Ensure this path is correct
import com.sikder.spentranslator.services.HoverTranslateService // Import your service
import com.sikder.spentranslator.ui.theme.SPenTranslatorTheme
import kotlinx.coroutines.launch

// Define language list (can be moved to a constants file or ViewModel later)
val supportedLanguages = listOf(
    "English" to "en",
    "Bengali" to "bn",
    "Spanish" to "es",
    "French" to "fr",
    "German" to "de",
    "Hindi" to "hi",
    "Arabic" to "ar",
    "Japanese" to "ja",
    "Russian" to "ru",
    "Portuguese" to "pt"
)

class MainActivity : ComponentActivity() {
    private val TAG = "SPenComposeDebug"
    private val apiClient = TranslationApiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // sPenLogMessage state is kept for Logcat debugging, but not passed to UI for display
            var sPenLogMessage by remember { mutableStateOf("S Pen activity...") }
            var translatedText by remember { mutableStateOf("Translation will appear here.") }
            var isLoadingTranslation by remember { mutableStateOf(false) }
            var textToTranslateInput by remember { mutableStateOf("") }

            var sourceLanguageCode by remember { mutableStateOf("en") }
            var targetLanguageCode by remember { mutableStateOf("bn") }

            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current

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
                                            else -> sPenLogMessage // Keep updating for Logcat
                                        }
                                        sPenLogMessage = newLog // Update state for Logcat if needed
                                        Log.d(TAG, newLog) // Log S Pen movement

                                        if (eventType == PointerEventType.Exit &&
                                            !isLoadingTranslation &&
                                            textToTranslateInput.isNotBlank()
                                        ) {
                                            isLoadingTranslation = true
                                            coroutineScope.launch {
                                                Log.d(TAG, "Attempting to translate '$textToTranslateInput' from $sourceLanguageCode to $targetLanguageCode")
                                                val result = apiClient.translate(textToTranslateInput, sourceLanguageCode, targetLanguageCode)
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
                            // logMessage parameter is removed from the call
                            textToTranslate = textToTranslateInput,
                            onTextToTranslateChange = { newText -> textToTranslateInput = newText },
                            translation = translatedText,
                            isLoading = isLoadingTranslation,
                            sourceLangCode = sourceLanguageCode,
                            onSourceLangChange = { newLangCode -> sourceLanguageCode = newLangCode },
                            targetLangCode = targetLanguageCode,
                            onTargetLangChange = { newLangCode -> targetLanguageCode = newLangCode },
                            availableLanguages = supportedLanguages,
                            onStartServiceClick = {
                                Log.d(TAG, "Start Service button clicked")
                                Intent(context, HoverTranslateService::class.java).also { intent ->
                                    context.startService(intent)
                                }
                            },
                            onStopServiceClick = {
                                Log.d(TAG, "Stop Service button clicked")
                                Intent(context, HoverTranslateService::class.java).also { intent ->
                                    context.stopService(intent)
                                }
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
        Log.d(TAG, "MainActivity (Compose) loaded.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenContent(
    // logMessage parameter removed
    textToTranslate: String,
    onTextToTranslateChange: (String) -> Unit,
    translation: String,
    isLoading: Boolean,
    sourceLangCode: String,
    onSourceLangChange: (String) -> Unit,
    targetLangCode: String,
    onTargetLangChange: (String) -> Unit,
    availableLanguages: List<Pair<String, String>>,
    onStartServiceClick: () -> Unit,
    onStopServiceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Removed S Pen Debug Info Text Composables
        // Text(text = "S Pen Debug Info:", modifier = Modifier.padding(bottom = 8.dp))
        // Text(
        // text = logMessage,
        // modifier = Modifier.padding(bottom = 16.dp)
        // )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguageSelector(
                label = "From",
                selectedLangCode = sourceLangCode,
                onLanguageChange = onSourceLangChange,
                availableLanguages = availableLanguages,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            LanguageSelector(
                label = "To",
                selectedLangCode = targetLangCode,
                onLanguageChange = onTargetLangChange,
                availableLanguages = availableLanguages,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = textToTranslate,
            onValueChange = onTextToTranslateChange,
            label = { Text("Enter text to translate") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = false,
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.Gray,
                focusedContainerColor = Color(0xFF3A3A3C),
                unfocusedContainerColor = Color(0xFF2C2C2E),
                disabledContainerColor = Color(0xFF2C2C2E),
                cursorColor = Color.White,
                focusedIndicatorColor = Color.Cyan,
                unfocusedIndicatorColor = Color.DarkGray,
                focusedLabelColor = Color.Cyan,
                unfocusedLabelColor = Color.Gray,
            )
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            Text("Translating...")
        } else {
            Text(text = "Translation:", modifier = Modifier.padding(top = 8.dp))
            Text(
                text = translation,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onStartServiceClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Start Hover Service")
            }
            Button(
                onClick = onStopServiceClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Text("Stop Hover Service")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(
    label: String,
    selectedLangCode: String,
    onLanguageChange: (String) -> Unit,
    availableLanguages: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLanguageDisplayName = availableLanguages.find { it.second == selectedLangCode }?.first ?: selectedLangCode

    Column(modifier = modifier) {
        Text(text = label, color = Color.Gray, modifier = Modifier.padding(bottom = 4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            TextField(
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                readOnly = true,
                value = selectedLanguageDisplayName,
                onValueChange = {},
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF3A3A3C),
                    unfocusedContainerColor = Color(0xFF2C2C2E),
                    disabledContainerColor = Color(0xFF2C2C2E),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                availableLanguages.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption.first) },
                        onClick = {
                            onLanguageChange(selectionOption.second)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1E)
@Composable
fun DefaultPreview() {
    SPenTranslatorTheme {
        ScreenContent(
            // logMessage parameter removed from preview call
            textToTranslate = "Hello there",
            onTextToTranslateChange = {},
            translation = "ওহে আচ্ছা",
            isLoading = false,
            sourceLangCode = "en",
            onSourceLangChange = {},
            targetLangCode = "bn",
            onTargetLangChange = {},
            availableLanguages = supportedLanguages,
            onStartServiceClick = {},
            onStopServiceClick = {}
        )
    }
}