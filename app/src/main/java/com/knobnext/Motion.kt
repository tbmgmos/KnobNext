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
    }

    protected open fun onStart() = Unit

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Face down for a moment → [onFlip] (true); turned back up → [onFlip] (false).
 * The first reading after start only sets the baseline, so starting while already face down does nothing.
 */
class FlipDetector(context: Context, private val onFlip: (faceDown: Boolean) -> Unit) :
    SensorGesture(context, Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_UI) {
    private var faceDown: Boolean? = null
    private var candidateSince = 0L

    override fun onStart() {
        faceDown = null
        candidateSince = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        val z = event.values[2]
        val now = SystemClock.elapsedRealtime()
        val current = faceDown
        if (current == null) {
            faceDown = z < FACE_DOWN_Z
            return
        }
        val flipped = if (current) z > FACE_UP_Z else z < FACE_DOWN_Z
        if (!flipped) {
            candidateSince = 0L
            return
        }
        if (candidateSince == 0L) candidateSince = now
        if (now - candidateSince >= STABLE_MS) {
            faceDown = !current
            candidateSince = 0L
            onFlip(!current)
        }
    }

    private companion object {
        const val FACE_DOWN_Z = -8.0f
        const val FACE_UP_Z = 5.0f
        const val STABLE_MS = 600L
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

/** Two short covers of the proximity sensor (a hand waved over the top of the phone). */
class WaveDetector(context: Context, private val onWave: () -> Unit) :
    SensorGesture(context, Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL) {
    private var nearSince = 0L
    private var waves = 0
    private var firstWaveAt = 0L
    private var initialized = false

    override fun onStart() {
        waves = 0
        nearSince = 0L
        initialized = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val range = event.sensor.maximumRange
        val near = event.values[0] < minOf(range, 5f)
        val now = SystemClock.elapsedRealtime()
        if (!initialized) {
            initialized = true
            if (near) nearSince = -1L // covered at start (pocket, face): ignore until uncovered
            return
        }
        if (near) {
            if (nearSince == 0L) nearSince = now
            return
        }
        val coveredFor = if (nearSince > 0) now - nearSince else Long.MAX_VALUE
        nearSince = 0L
        if (coveredFor > MAX_COVER_MS) {
            waves = 0
            return
        }
        if (waves == 0 || now - firstWaveAt > WINDOW_MS) {
            waves = 0
            firstWaveAt = now
        }
        waves++
        if (waves >= 2) {
            waves = 0
            onWave()
        }
    }

    private companion object {
        const val MAX_COVER_MS = 1_000L
        const val WINDOW_MS = 1_500L
    }
}
