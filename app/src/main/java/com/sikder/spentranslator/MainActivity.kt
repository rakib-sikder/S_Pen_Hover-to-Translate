package com.sikder.spentranslator // Use your actual package name

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box // Import Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput // Import pointerInput
import androidx.compose.ui.tooling.preview.Preview
import com.sikder.spentranslator.ui.theme.SPenTranslatorTheme
import androidx.compose.ui.input.pointer.PointerType // Import PointerType

class MainActivity : ComponentActivity() {
    private val TAG = "SPenComposeDebug" // Tag for logging

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SPenTranslatorTheme {
                // Apply the pointerInput modifier to a Composable that fills the screen,
                // for example, a Box wrapping your Scaffold or the Scaffold itself.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { // The 'key1 = Unit' means this doesn't restart unless Unit changes (which it won't)
                            awaitPointerEventScope {
                                while (true) { // Loop to continuously listen for events
                                    val event: PointerEvent = awaitPointerEvent()

                                    // Check if the primary pointer (first touch/hover point) is a STYLUS
                                    val isStylus = event.changes.any { it.type == PointerType.Stylus }

                                    if (isStylus) {
                                        when (event.type) {
                                            PointerEventType.Enter -> {
                                                val position = event.changes.first().position
                                                Log.d(TAG, "S Pen HOVER ENTER at: x=${position.x}, y=${position.y}")
                                            }
                                            PointerEventType.Move -> {
                                                val position = event.changes.first().position
                                                Log.d(TAG, "S Pen HOVER MOVE at: x=${position.x}, y=${position.y}")
                                            }
                                            PointerEventType.Exit -> {
                                                val position = event.changes.first().position
                                                Log.d(TAG, "S Pen HOVER EXIT at: x=${position.x}, y=${position.y}")
                                            }
                                            // You might also be interested in PointerEventType.Press, .Release if S Pen button acts as a click
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Greeting(
                            name = "Android with S Pen Hover",
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
        Log.d(TAG, "MainActivity (Compose) loaded. Hover listener (pointerInput) set up for stylus.")
        Log.i(TAG, "Please check Samsung S Pen SDK documentation for Compose-specific utilities or listeners.")
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SPenTranslatorTheme {
        Greeting("Android")
    }
}

// IMPORTANT NOTES FOR S PEN SDK with COMPOSE:
// 1. Official SDK Documentation is Key:
//    The Samsung S Pen SDK is your primary resource. It might provide:
//    - Its own Composable functions or Modifiers specifically for S Pen events.
//    - Instructions on how to initialize the SDK in a Compose environment.
//    - Details on any permissions needed in AndroidManifest.xml.

// 2. This `pointerInput` example is a general Compose way to detect pointer events.
//    It will detect any stylus, including the S Pen. The S Pen SDK might offer more
//    detailed information (pressure, button state, specific S Pen tool type)
//    than what's available through the generic PointerEvent.

// 3. Global Hover Detection:
//    This example, like the previous View-based one, detects hover events *within your app's UI*.
//    Detecting hover events system-wide (over other apps) is a much more complex problem
//    and would likely require a background service and potentially the S Pen SDK's
//    advanced features (if available for global listening) or Accessibility Services.

// 4. Initialization:
//    If the S Pen SDK requires initialization (e.g., `Spen.getInstance(context)`),
//    you'd typically do this in `onCreate` or an `Application` class, or perhaps
//    within a `LaunchedEffect` in Compose if the SDK's context requirements allow.