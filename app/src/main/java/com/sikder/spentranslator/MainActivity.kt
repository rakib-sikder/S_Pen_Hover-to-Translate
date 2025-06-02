package com.sikder.spentranslator // Use your actual package name

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement // For Column arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column // For layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue // Import getValue
import androidx.compose.runtime.mutableStateOf // Import mutableStateOf
import androidx.compose.runtime.remember // Import remember
import androidx.compose.runtime.setValue // Import setValue
import androidx.compose.ui.Alignment // For Column alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp // For padding
import com.sikder.spentranslator.ui.theme.SPenTranslatorTheme

class MainActivity : ComponentActivity() {
    private val TAG = "SPenComposeDebug"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Remember a mutable state variable to hold our S Pen log message
            var sPenLogMessage by remember { mutableStateOf("Hover S Pen over this area...") }

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
                                        val x = String.format("%.2f", position.x) // Format for better readability
                                        val y = String.format("%.2f", position.y)

                                        val newLog: String = when (event.type) {
                                            PointerEventType.Enter -> "S Pen ENTER: x=$x, y=$y"
                                            PointerEventType.Move -> "S Pen MOVE: x=$x, y=$y"
                                            PointerEventType.Exit -> "S Pen EXIT: x=$x, y=$y"
                                            else -> sPenLogMessage // Keep current message for other event types
                                        }
                                        sPenLogMessage = newLog // Update the state
                                        Log.d(TAG, newLog) // Still log to Logcat for detailed debugging
                                    }
                                }
                            }
                        }
                ) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        // Display the S Pen log message on screen
                        ScreenContent(
                            logMessage = sPenLogMessage,
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
fun ScreenContent(logMessage: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp), // Add some padding around the content
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "S Pen Debug Info:")
        Text(
            text = logMessage, // Display the sPenLogMessage state
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SPenTranslatorTheme {
        ScreenContent("Preview: S Pen MOVE: x=123.45, y=678.90")
    }
}