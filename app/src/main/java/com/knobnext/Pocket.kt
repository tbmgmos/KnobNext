package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Pocket mode: the screen stays on but shows only black, above everything including the lock screen,
 * and takes all touches. For the system the phone is in use, so the volume keys reach the service as
 * with the screen on — holds, smooth seeking and both keys together work in the pocket.
 *
 * A black screen of an OLED panel costs little, but the phone doesn't sleep either: it is meant for
 * while music plays, and the service ends it once it has been quiet for a while.
 *
 * Leaving: a double tap (not while the proximity sensor is covered, i.e. in the pocket), the power
 * button (the screen then really goes off), a call, or long silence.
 */
class PocketScreen(
    private val service: AccessibilityService,
    private val onLeft: (Reason) -> Unit,
) {
    enum class Reason { TAP, POWER, CALL, IDLE, ACTION, FAILED, STOPPED }

    private val windows = service.getSystemService(WindowManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val sensors = service.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var hint: TextView? = null
    private var covered = false

    @Suppress("DEPRECATION")
    private val screenLock = power.newWakeLock(
        PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
        "KnobNext:pocket",
    ).apply { setReferenceCounted(false) }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            covered = event.values[0] < minOf(event.sensor.maximumRange, NEAR_CM)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val hideHint = Runnable { hint?.visibility = View.INVISIBLE }

    val active: Boolean get() = view != null

    /** Shows the black screen; when the screen is off, turns it on for that. */
    fun show(): Boolean {
        if (view != null) return true
        val root = FrameLayout(service).apply { setBackgroundColor(Color.BLACK) }
        val text = TextView(service).apply {
            setText(R.string.pocket_hint)
            setTextColor(HINT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        root.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        listenToTouches(root)

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            // As dark as the panel goes; the content is black anyway.
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            lowestRefreshRate()?.let { preferredRefreshRate = it }
        }
        try {
            windows.addView(root, params)
        } catch (_: Exception) {
            return false
        }
        view = root
        hint = text
        // Turns the screen on when it is off, and keeps it on (dimmed) for as long as the mode lasts.
        screenLock.acquire(MAX_MS)
        covered = false
        proximity?.let { sensors.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        return true
    }

    fun hide(reason: Reason) {
        val v = view ?: return
        view = null
        hint = null
        handler.removeCallbacks(hideHint)
        try {
            windows.removeView(v)
        } catch (_: Exception) {
        }
        if (screenLock.isHeld) screenLock.release()
        if (proximity != null) sensors.unregisterListener(proximityListener)
        onLeft(reason)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun listenToTouches(root: View) {
        val detector = GestureDetector(
            service,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!covered) showHint()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // In the pocket the fabric can tap twice too.
                    if (!covered) hide(Reason.TAP)
                    return true
                }
            },
        )
        root.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    /** The hint shows briefly, each time somewhere else, so nothing stays lit in one place. */
    private fun showHint() {
        val root = view ?: return
        val text = hint ?: return
        val maxX = (root.width - text.width).coerceAtLeast(1)
        val maxY = (root.height - text.height).coerceAtLeast(1)
        text.x = Random.nextInt(maxX / 4, maxX * 3 / 4 + 1).toFloat()
        text.y = Random.nextInt(maxY / 4, maxY * 3 / 4 + 1).toFloat()
        text.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, HINT_MS)
    }

    private fun lowestRefreshRate(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .minOfOrNull { it.refreshRate }
    }

    companion object {
        /** Not lit enough to read in a dark pocket, readable when looked at. */
        private val HINT_COLOR = Color.rgb(0x60, 0x60, 0x60)
        private const val HINT_MS = 3_000L
        private const val NEAR_CM = 3f

        /** A safety net: the mode ends by itself long before this. */
        private const val MAX_MS = 4 * 60 * 60 * 1000L

        /** Set by the service while it runs: toggles the mode (from the app's settings). */
        @Volatile var toggle: (() -> Unit)? = null
    }
}
