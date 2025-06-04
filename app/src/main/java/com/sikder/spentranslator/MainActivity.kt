package com.sikder.spentranslator

import android.content.Intent // Keep for potential future use (e.g., sharing)
import android.os.Bundle
import android.util.Log
import android.widget.Toast // For user feedback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextField // For ExposedDropdownMenuBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sikder.spentranslator.network.TranslationApiClient // Ensure this path is correct
// Removed HoverTranslateService import as it's not used in this version
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
    // Add more as needed
)

class MainActivity : ComponentActivity() {
    private val TAG = "StandardTranslator"
    private val apiClient = TranslationApiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var translatedText by remember { mutableStateOf("Translation will appear here.") }
            // Explicitly typing the state variable
            var isLoadingTranslation: Boolean by remember { mutableStateOf(false) }
            var textToTranslateInput by remember { mutableStateOf("") }

            var sourceLanguageCode by remember { mutableStateOf("en") }
            var targetLanguageCode by remember { mutableStateOf("bn") }

            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current // Keep for Toasts or other context needs

            SPenTranslatorTheme {
                // Removed the Box with pointerInput modifier
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScreenContent(
                        textToTranslate = textToTranslateInput,
                        onTextToTranslateChange = { newText -> textToTranslateInput = newText },
                        translation = translatedText,
                        isLoading = isLoadingTranslation, // This passes the state to ScreenContent
                        sourceLangCode = sourceLanguageCode,
                        onSourceLangChange = { newLangCode -> sourceLanguageCode = newLangCode },
                        targetLangCode = targetLanguageCode,
                        onTargetLangChange = { newLangCode -> targetLanguageCode = newLangCode },
                        availableLanguages = supportedLanguages,
                        onTranslateClick = { // This lambda is defined in MainActivity's setContent scope
                            if (textToTranslateInput.isNotBlank()) {
                                isLoadingTranslation = true // Accessing isLoadingTranslation from MainActivity's scope
                                coroutineScope.launch {
                                    Log.d(TAG, "Attempting to translate '$textToTranslateInput' from $sourceLanguageCode to $targetLanguageCode")
                                    val result = apiClient.translate(textToTranslateInput, sourceLanguageCode, targetLanguageCode)
                                    translatedText = result ?: "Translation failed / not found for '$textToTranslateInput'."
                                    isLoadingTranslation = false // Accessing isLoadingTranslation from MainActivity's scope
                                    Log.d(TAG, "Translation API call finished. Result: $translatedText")
                                }
                            } else {
                                Toast.makeText(context, "Please enter text to translate", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        Log.d(TAG, "MainActivity (Standard Translator) loaded.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenContent(
    textToTranslate: String,
    onTextToTranslateChange: (String) -> Unit,
    translation: String,
    isLoading: Boolean, // This is the parameter used within ScreenContent
    sourceLangCode: String,
    onSourceLangChange: (String) -> Unit,
    targetLangCode: String,
    onTargetLangChange: (String) -> Unit,
    availableLanguages: List<Pair<String, String>>,
    onTranslateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Simple Translator",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

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
            minLines = 3,
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

        Button(
            onClick = onTranslateClick,
            enabled = !isLoading, // Uses the 'isLoading' parameter of ScreenContent
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF))
        ) {
            Text("Translate")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) { // Uses the 'isLoading' parameter of ScreenContent
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            Text("Translating...")
        } else {
            Text(text = "Translation:", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                text = translation,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
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
            textToTranslate = "Hello there",
            onTextToTranslateChange = {},
            translation = "ওহে আচ্ছা",
            isLoading = false,
            sourceLangCode = "en",
            onSourceLangChange = {},
            targetLangCode = "bn",
            onTargetLangChange = {},
            availableLanguages = supportedLanguages,
            onTranslateClick = {}
        )
    }
}