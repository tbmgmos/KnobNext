package com.knobnext

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects taps on the back of the phone from the accelerometer.
 *
 * A tap on the back pushes the phone towards the user: a short spike along +Z (the axis pointing out
 * of the screen). A tap on the screen pushes it away, so its first spike is along −Z and is ignored.
 * Taps are only counted when the phone was otherwise still, which filters out walking and handling.
 * The sensor is registered only while [start]ed — the service does that while music is playing.
 */
class BackTapDetector(
    context: Context,
    private val threshold: () -> Float,
    /** Whether a third tap could still mean something; if not, two taps fire without waiting. */
    private val wantsTriple: () -> Boolean,
    private val onTaps: (count: Int) -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val thread = HandlerThread("back-tap").apply { start() }
    private val sensorHandler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var running = false
    private var fast = true
    private var initialized = false
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var noise = 0f
    private var lastPeakAt = 0L
    private var lastNegativeAt = 0L
    private var lastTapAt = 0L
    private var taps = 0

    private val finish = Runnable {
        val count = taps
        taps = 0
        if (count in 2..3) main.post { onTaps(count) }
    }

    val available: Boolean get() = accelerometer != null

    /**
     * [fastRate] false reads the sensor about 50 times a second instead of as often as it can: less
     * battery, but short taps may be missed. Called again with another rate, it re-registers.
     */
    fun start(fastRate: Boolean = true) {
        val sensor = accelerometer ?: return
        if (running && fast == fastRate) return
        if (running) sensors?.unregisterListener(this)
        running = true
        fast = fastRate
        initialized = false
        taps = 0
        val rate = if (fastRate) SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_GAME
        sensors.registerListener(this, sensor, rate, sensorHandler)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
        sensorHandler.removeCallbacks(finish)
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val now = event.timestamp / 1_000_000
        if (!initialized) {
            gx = x; gy = y; gz = z
            initialized = true
            return
        }
        // Low-pass tracks gravity and slow movement; what's left is the sharp part of the motion.
        gx += (x - gx) * SMOOTHING
        gy += (y - gy) * SMOOTHING
        gz += (z - gz) * SMOOTHING
        val dx = x - gx
        val dy = y - gy
        val dz = z - gz
        val limit = threshold()

        if (dz < -limit) lastNegativeAt = now
        val isTap = now - lastPeakAt > REFRACTORY_MS &&
            dz > limit &&
            dz > SIDEWAYS_RATIO * max(abs(dx), abs(dy)) &&
            now - lastNegativeAt > FRONT_TAP_RINGING_MS &&
            noise < limit * QUIET_RATIO
        if (isTap) {
            lastPeakAt = now
            if (taps == 0 || now - lastTapAt > TAP_GAP_MS) taps = 0
            taps++
            lastTapAt = now
            sensorHandler.removeCallbacks(finish)
            if (taps >= 3 || taps == 2 && !wantsTriple()) {
                // Nothing longer can follow: answer now instead of waiting for another tap.
                sensorHandler.post(finish)
            } else {
                sensorHandler.postDelayed(finish, SETTLE_MS)
            }
        }

        // Background motion level; spikes are clipped so the taps themselves barely raise it.
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz)
        noise += (minOf(magnitude, limit * QUIET_RATIO * 2) - noise) * NOISE_SMOOTHING
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SMOOTHING = 0.08f
        const val NOISE_SMOOTHING = 0.02f
        const val SIDEWAYS_RATIO = 1.3f
        const val QUIET_RATIO = 0.3f
        const val REFRACTORY_MS = 90L
        const val FRONT_TAP_RINGING_MS = 40L
        const val TAP_GAP_MS = 500L
        const val SETTLE_MS = 450L
    }
}
