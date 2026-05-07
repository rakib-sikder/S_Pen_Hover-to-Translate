package com.sikder.spentranslator.spen

import android.content.Context
import android.util.Log

/**
 * SPenHoverListener — wraps Samsung's SPenRemote SDK to track S Pen position.
 *
 * Adapted from PenMouseS (github.com/jojczak/PenMouseS) which uses:
 *   - com.samsung.android.sdk.pen.SPenRemote (older SDK)
 *   - or samsung.pen.remote.SpenRemote (newer SDK)
 *
 * The Samsung S Pen SDK reports:
 *   - Hover enter/exit
 *   - X/Y position of the pen cursor on screen
 *   - Button presses (we use button press as manual trigger)
 *
 * HOW TO ADD THE SDK:
 * 1. Go to https://developer.samsung.com/galaxy-spen-remote/overview.html
 * 2. Download the S Pen Remote SDK
 * 3. Copy the .aar file to app/libs/
 * 4. Uncomment the implementation line in build.gradle
 * 5. Replace the stub below with real SDK calls
 *
 * The callback signature: (x: Float, y: Float, isHovering: Boolean)
 * - x, y: screen coordinates in pixels
 * - isHovering: true = pen is in range, false = pen left
 */
class SPenHoverListener(
    private val context: Context,
    private val onHover: (x: Float, y: Float, isHovering: Boolean) -> Unit
) {
    private val tag = "SPenHoverListener"

    /**
     * --- REAL SAMSUNG SDK INTEGRATION ---
     *
     * Uncomment the block below and replace this stub once you've added the SDK .aar.
     *
     * The PenMouseS project uses the approach in:
     *   mousemode/src/main/java/.../mousemode/MouseModeManager.kt
     *
     * Key SDK classes:
     *   SpenRemote.getInstance()
     *   SpenRemote.isFeatureEnabled(SpenRemote.FEATURE_TYPE_AIR_GESTURE)
     *   spenRemote.connect(context, connectionCallback)
     *   spenGesture.registerSpenGestureCallback(gestureCallback, handler)
     *
     * The SpenGestureCallback gives you:
     *   onAirMotionEvent(event: MotionEvent) — x/y of the "air cursor"
     */

    /*
    import samsung.pen.remote.SpenRemote
    import samsung.pen.remote.feature.SpenGesture

    private var spenRemote: SpenRemote? = null
    private var spenGesture: SpenGesture? = null

    private val connectionCallback = object : SpenRemote.ConnectionResultCallback {
        override fun onSuccess(remote: SpenRemote) {
            spenRemote = remote
            spenGesture = remote.get(SpenGesture::class.java)
            spenGesture?.registerSpenGestureCallback(gestureCallback, null)
            Log.d(tag, "S Pen SDK connected")
        }
        override fun onFailure(errorCode: Int) {
            Log.e(tag, "S Pen SDK connection failed: $errorCode")
        }
    }

    private val gestureCallback = object : SpenGesture.Callback {
        override fun onAirMotionEvent(event: MotionEvent) {
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    onHover(event.rawX, event.rawY, true)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    onHover(0f, 0f, false)
                }
            }
        }
    }
    */

    fun register() {
        Log.d(tag, "Registering S Pen hover listener")
        /*
         * STUB — uncomment the block above and call:
         * SpenRemote.getInstance().connect(context, connectionCallback)
         */

        // For testing without real hardware, you can simulate hover events
        // by posting fake coordinates from MainActivity or a test screen.
        Log.w(tag, "Using stub — replace with real Samsung SDK for production")
    }

    fun unregister() {
        Log.d(tag, "Unregistering S Pen hover listener")
        /*
         * Uncomment below once SDK is integrated:
         * spenGesture?.unregisterSpenGestureCallback(gestureCallback)
         * spenRemote?.disconnect(context)
         */
    }

    /**
     * Call this from your test code or debugger to simulate a hover event.
     * Useful for testing the OCR/translation pipeline without physical S Pen.
     */
    fun simulateHover(x: Float, y: Float, isHovering: Boolean) {
        onHover(x, y, isHovering)
    }
}