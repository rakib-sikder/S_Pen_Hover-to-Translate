package com.sikder.spentranslator.spen

import android.content.Context
import android.util.Log
import com.samsung.android.sdk.penremote.AirMotionEvent
import com.samsung.android.sdk.penremote.ButtonEvent
import com.samsung.android.sdk.penremote.SpenEventListener
import com.samsung.android.sdk.penremote.SpenRemote
import com.samsung.android.sdk.penremote.SpenUnit
import com.samsung.android.sdk.penremote.SpenUnitManager

/**
 * SPenHoverListener — connects to Samsung S Pen Remote SDK and tracks
 * Air Motion (gyroscope) events + button press/release.
 *
 * Based on PenMouseS (github.com/jojczak/PenMouseS) SPenManager.kt
 *
 * SDK classes (from your app/libs/ JARs):
 *   com.samsung.android.sdk.penremote.SpenRemote
 *   com.samsung.android.sdk.penremote.SpenUnitManager
 *   com.samsung.android.sdk.penremote.SpenUnit
 *   com.samsung.android.sdk.penremote.AirMotionEvent
 *   com.samsung.android.sdk.penremote.ButtonEvent
 *   com.samsung.android.sdk.penremote.SpenEventListener
 *
 * NOTE: The Samsung SDK gives DELTA (relative) movement, not absolute (x,y).
 * We accumulate deltas into a cursor position ourselves — same as PenMouseS.
 *
 * Callback: (x: Float, y: Float, isHovering: Boolean)
 *   x, y = accumulated absolute screen position
 *   isHovering = false when button released (pen "lifted")
 */
class SPenHoverListener(
    private val context: Context,
    private val onHover: (x: Float, y: Float, isHovering: Boolean) -> Unit
) {
    private val tag = "SPenHoverListener"

    // Accumulated cursor position (starts at screen centre)
    private var cursorX = 0f
    private var cursorY = 0f
    private var isButtonDown = false

    private var sPenUnitManager: SpenUnitManager? = null

    // ── SDK Connection ─────────────────────────────────────────────

    fun register() {
        if (!isSPenSupported()) {
            Log.w(tag, "S Pen SDK not supported on this device — falling back to accessibility hover events")
            return
        }

        // Start cursor at screen centre
        val dm = context.resources.displayMetrics
        cursorX = dm.widthPixels / 2f
        cursorY = dm.heightPixels / 2f

        SpenRemote.getInstance().connect(context, object : SpenRemote.ConnectionResultCallback {
            override fun onSuccess(unitManager: SpenUnitManager?) {
                Log.i(tag, "✓ S Pen SDK connected")
                sPenUnitManager = unitManager
                registerListeners()
            }

            override fun onFailure(errorCode: Int) {
                Log.e(tag, "S Pen SDK connection failed: errorCode=$errorCode")
                // Fallback: accessibility hover events will still work
            }
        })
    }

    fun unregister() {
        Log.d(tag, "Unregistering S Pen listeners")
        sPenUnitManager?.let { mgr ->
            try { mgr.unregisterSpenEventListener(mgr.getUnit(SpenUnit.TYPE_AIR_MOTION)) } catch (_: Exception) {}
            try { mgr.unregisterSpenEventListener(mgr.getUnit(SpenUnit.TYPE_BUTTON)) } catch (_: Exception) {}
        }
        sPenUnitManager = null
        try { SpenRemote.getInstance().disconnect(context) } catch (_: Exception) {}
    }

    // ── Register Air Motion + Button listeners ─────────────────────

    private fun registerListeners() {
        val mgr = sPenUnitManager ?: return

        // ── Air Motion (gyroscope) → deltaX / deltaY ────────────
        // This fires continuously as the user moves the S Pen in the air.
        // deltaX = horizontal movement (right = positive)
        // deltaY = vertical movement (up = positive — NOTE: inverted from screen Y!)
        val airMotionUnit = mgr.getUnit(SpenUnit.TYPE_AIR_MOTION)
        val airMotionListener = SpenEventListener { event ->
            val airEvent = AirMotionEvent(event)
            val dx = airEvent.deltaX
            val dy = airEvent.deltaY   // up = positive, so we negate for screen Y

            val dm = context.resources.displayMetrics
            val sensitivity = SENSITIVITY

            cursorX = (cursorX + dx * sensitivity).coerceIn(0f, dm.widthPixels.toFloat())
            cursorY = (cursorY - dy * sensitivity).coerceIn(0f, dm.heightPixels.toFloat())

            Log.v(tag, "AirMotion dx=$dx dy=$dy → cursor=(${cursorX.toInt()},${cursorY.toInt()})")
            onHover(cursorX, cursorY, true)
        }

        try {
            mgr.registerSpenEventListener(airMotionListener, airMotionUnit)
            Log.i(tag, "Air motion listener registered")
        } catch (e: Exception) {
            Log.e(tag, "Failed to register air motion listener", e)
        }

        // ── Button press/release ─────────────────────────────────
        // ACTION_DOWN = S Pen button pressed (treat as "pen on screen")
        // ACTION_UP   = S Pen button released
        val buttonUnit = mgr.getUnit(SpenUnit.TYPE_BUTTON)
        val buttonListener = SpenEventListener { event ->
            val btnEvent = ButtonEvent(event)
            when (btnEvent.action) {
                ButtonEvent.ACTION_DOWN -> {
                    isButtonDown = true
                    Log.d(tag, "Button DOWN at ($cursorX, $cursorY)")
                    onHover(cursorX, cursorY, true)
                }
                ButtonEvent.ACTION_UP -> {
                    isButtonDown = false
                    Log.d(tag, "Button UP")
                    onHover(0f, 0f, false)
                }
            }
        }

        try {
            mgr.registerSpenEventListener(buttonListener, buttonUnit)
            Log.i(tag, "Button listener registered")
        } catch (e: Exception) {
            Log.e(tag, "Failed to register button listener", e)
        }
    }

    // ── S Pen support check (same approach as PenMouseS) ──────────

    private fun isSPenSupported(): Boolean = try {
        val remote = SpenRemote.getInstance()
        val buttonSupported   = remote.isFeatureEnabled(SpenRemote.FEATURE_TYPE_BUTTON)
        val airMotionSupported = remote.isFeatureEnabled(SpenRemote.FEATURE_TYPE_AIR_MOTION)
        Log.i(tag, "S Pen support: button=$buttonSupported airMotion=$airMotionSupported")
        buttonSupported && airMotionSupported
    } catch (e: Exception) {
        Log.e(tag, "S Pen SDK check failed", e)
        false
    }

    companion object {
        // How many pixels the cursor moves per unit of S Pen delta
        // PenMouseS uses delta * sensitivity * 20 — start with 15 for translate use case
        private const val SENSITIVITY = 15f
    }
}