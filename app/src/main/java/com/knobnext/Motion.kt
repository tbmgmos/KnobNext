package com.knobnext

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/** Base for sensor gestures that are registered only while needed. Callbacks arrive on the main thread. */
abstract class SensorGesture(context: Context, type: Int, private val rate: Int) : SensorEventListener {
    protected val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(type, true) ?: sensors?.getDefaultSensor(type)
    var running = false
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        if (running) return
        running = true
        onStart()
        sensors?.registerListener(this, s, rate)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
        onStop()
    }

    protected open fun onStart() = Unit

    protected open fun onStop() = Unit

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Face down for a moment → [onFlip] (true); turned back up → [onFlip] (false).
 *
 * Reads the gravity sensor (the fused, smooth direction of gravity; a smoothed accelerometer where
 * there is none), so moving the phone doesn't break the reading, and allows about 45° of tilt, so a
 * cushion or an uneven table still counts. Face down also needs the proximity sensor covered — the
 * screen lies against something — which tells a phone put down from one held above the face in bed.
 * The first reading after start only sets the baseline, so starting while already face down does nothing.
 */
class FlipDetector(context: Context, private val onFlip: (faceDown: Boolean) -> Unit) : SensorEventListener {
    private val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val gravitySensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val motion: Sensor? = gravitySensor
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    var running = false
        private set

    private var faceDown: Boolean? = null
    private var candidateSince = 0L
    private var lastFlipAt = 0L
    private var z = Float.NaN
    // No proximity sensor: gravity alone decides.
    private var covered = proximity == null

    fun start() {
        val m = motion ?: return
        if (running) return
        running = true
        faceDown = null
        candidateSince = 0L
        z = Float.NaN
        covered = proximity == null
        sensors?.registerListener(this, m, SensorManager.SENSOR_DELAY_UI)
        proximity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> covered = event.values[0] < minOf(event.sensor.maximumRange, NEAR_CM)
            else -> {
                val raw = event.values[2]
                // The gravity sensor is already smooth; the raw accelerometer gets a low-pass filter.
                z = if (z.isNaN() || event.sensor.type == Sensor.TYPE_GRAVITY) raw else z + SMOOTHING * (raw - z)
            }
        }
        if (z.isNaN()) return
        val down = z < FACE_DOWN_Z && covered
        val up = z > FACE_UP_Z
        val now = SystemClock.elapsedRealtime()
        val current = faceDown
        if (current == null) {
            faceDown = down
            return
        }
        val flipped = if (current) up else down
        if (!flipped) {
            candidateSince = 0L
            return
        }
        if (candidateSince == 0L) candidateSince = now
        if (now - candidateSince >= STABLE_MS && now - lastFlipAt >= COOLDOWN_MS) {
            faceDown = !current
            candidateSince = 0L
            lastFlipAt = now
            onFlip(!current)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** About 45° from flat, screen down. */
        const val FACE_DOWN_Z = -6.9f

        /** Clearly turned back over (above about 20° screen up). */
        const val FACE_UP_Z = 3.4f
        const val STABLE_MS = 400L
        const val COOLDOWN_MS = 1_000L
        const val SMOOTHING = 0.2f
        const val NEAR_CM = 3f
    }
}

/**
 * Two quick back-and-forth twists of the wrist: alternating rotation spikes around the phone's long
 * axis (gyroscope Y). Three alternating peaks within a short window count as one gesture.
 */
class TwistDetector(context: Context, private val onTwist: () -> Unit) :
    SensorGesture(context, Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME) {
    private var lastSign = 0
    private var peaks = 0
    private var firstPeakAt = 0L
    private var lastPeakAt = 0L
    private var quietUntil = 0L

    override fun onStart() {
        peaks = 0
        lastSign = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now < quietUntil) return
        val y = event.values[1]
        if (abs(y) < RATE_RAD_S) return
        val sign = if (y > 0) 1 else -1
        if (sign == lastSign || now - lastPeakAt < MIN_GAP_MS) return
        if (peaks == 0 || now - firstPeakAt > WINDOW_MS) {
            peaks = 0
            firstPeakAt = now
        }
        peaks++
        lastSign = sign
        lastPeakAt = now
        if (peaks >= PEAKS) {
            peaks = 0
            lastSign = 0
            quietUntil = now + COOLDOWN_MS
            onTwist()
        }
    }

    private companion object {
        const val RATE_RAD_S = 6.0f
        const val MIN_GAP_MS = 60L
        const val WINDOW_MS = 800L
        const val PEAKS = 3
        const val COOLDOWN_MS = 1_500L
    }
}

/**
 * A hand passing over the proximity sensor (top edge of the screen).
 *
 * The sensor only reports "near" / "far" when the state changes, and on many phones (Pixel included)
 * it reports "far" with a noticeable delay to avoid flicker during calls. So waves are counted when the
 * hand *arrives* ("near"), not when it leaves. Covered for longer than [POCKET_MS] means pocket, face or
 * a table edge: the count resets and nothing fires until the sensor is uncovered again.
 *
 * With [required] = 1 the gesture fires when the hand leaves (so a long cover can still be rejected);
 * with 2 it fires right on the second arrival.
 */
class WaveDetector(
    context: Context,
    private val required: () -> Int,
    private val onWave: () -> Unit,
    private val onState: ((near: Boolean, waves: Int) -> Unit)? = null,
) : SensorGesture(context, Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_FASTEST) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var near: Boolean? = null
    private var blocked = false
    private var nearSince = 0L
    private var waves = 0
    private var firstWaveAt = 0L

    /** The sensor is covered right now (also used as the pocket check while this detector runs). */
    val covered: Boolean get() = running && near == true

    private val pocketCheck = Runnable {
        blocked = true
        waves = 0
        onState?.invoke(true, 0)
    }

    override fun onStart() {
        near = null
        blocked = false
        waves = 0
        handler.removeCallbacks(pocketCheck)
    }

    override fun onStop() = handler.removeCallbacks(pocketCheck)

    override fun onSensorChanged(event: SensorEvent) {
        val isNear = event.values[0] < minOf(event.sensor.maximumRange, NEAR_CM)
        val previous = near
        near = isNear
        val now = SystemClock.elapsedRealtime()
        if (previous == null) {
            // Already covered when we started listening: wait until it is uncovered.
            blocked = isNear
            onState?.invoke(isNear, 0)
            return
        }
        if (previous == isNear) {
            // The sensor only reports changes, so "near" twice means a "far" was lost in between
            // (the system dropped it): count it as a new arrival instead of getting stuck.
            if (!isNear) return
            handler.removeCallbacks(pocketCheck)
            blocked = false
        }

        if (isNear) {
            if (blocked) return
            nearSince = now
            if (waves == 0 || now - firstWaveAt > WINDOW_MS) {
                waves = 0
                firstWaveAt = now
            }
            waves++
            onState?.invoke(true, waves)
            handler.postDelayed(pocketCheck, POCKET_MS)
            if (required() >= 2 && waves >= required()) {
                waves = 0
                onWave()
            }
        } else {
            handler.removeCallbacks(pocketCheck)
            if (blocked) {
                blocked = false
                onState?.invoke(false, 0)
                return
            }
            if (required() <= 1 && waves >= 1 && now - nearSince <= POCKET_MS) {
                waves = 0
                onWave()
            }
            onState?.invoke(false, waves)
        }
    }

    private companion object {
        const val NEAR_CM = 5f
        const val POCKET_MS = 3_000L
        const val WINDOW_MS = 2_000L
    }
}

/** A firm shake (two hard jolts within a short time), used to extend the sleep timer while it fades. */
class ShakeDetector(context: Context, private val onShake: () -> Unit) :
    SensorGesture(context, Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME) {
    private var firstJoltAt = 0L
    private var lastJoltAt = 0L
    private var quietUntil = 0L

    override fun onStart() {
        firstJoltAt = 0L
        lastJoltAt = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val force = Math.sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
        val now = SystemClock.elapsedRealtime()
        if (now < quietUntil || abs(force) < JOLT) return
        if (now - lastJoltAt < MIN_GAP_MS) return
        if (firstJoltAt == 0L || now - firstJoltAt > WINDOW_MS) firstJoltAt = now
        else {
            firstJoltAt = 0L
            quietUntil = now + COOLDOWN_MS
            onShake()
        }
        lastJoltAt = now
    }

    private companion object {
        const val JOLT = 12.0
        const val MIN_GAP_MS = 120L
        const val WINDOW_MS = 800L
        const val COOLDOWN_MS = 2_000L
    }
}
