package com.knobnext

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects taps on the back of the phone from the accelerometer.
 *
 * A tap on the back pushes the phone towards the user: a short spike along +Z (the axis pointing out
 * of the screen). A tap on the screen pushes it away, so its first spike is along −Z and is ignored.
 * Taps are only counted when the phone was otherwise still, which filters out walking and handling.
 * Riding (a scooter, a bike, a bumpy road) is told apart from tapping in three ways: constant vibration
 * keeps the detector shut for a moment after it calms down; a tap is a sharp pulse, while a bump keeps
 * the phone shaking for tens of milliseconds, so wide pulses are dropped; and a sensor that has gone
 * silent is noticed by [starved], so the service can register it again.
 * The sensor is registered only while [start]ed — the service does that while music is playing.
 */
class BackTapDetector(
    context: Context,
    private val threshold: () -> Float,
    /** Whether a third tap could still mean something; if not, two taps fire without waiting. */
    private val wantsTriple: () -> Boolean,
    /** A tap-like spike was ignored, and why; called on the sensor thread, as often as it happens. */
    private val onRejected: (TapStats.Verdict) -> Unit,
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
    private var lastSampleAt = 0L
    private var sampleMs = 5f
    private var busyUntil = 0L
    private var pendingAt = 0L
    private var pendingAbove = 0f

    /** When the sensor last reported, on the elapsed-realtime clock. */
    @Volatile private var lastEventAt = 0L

    private val finish = Runnable {
        val count = taps
        taps = 0
        if (count in 2..3) {
            TapStats.note(TapStats.Verdict.TAPS)
            main.post { onTaps(count) }
        } else if (count == 1) {
            // A single knock is not a gesture, but if the second tap of two keeps being lost, this shows it.
            TapStats.note(TapStats.Verdict.SINGLE)
            onRejected(TapStats.Verdict.SINGLE)
        }
    }

    val available: Boolean get() = accelerometer != null

    /** Registered, yet nothing has come from the sensor for a while. */
    fun starved(): Boolean = running && SystemClock.elapsedRealtime() - lastEventAt > STARVED_MS

    /** Registers the sensor again at the same rate. */
    fun restart() {
        if (!running) return
        sensors?.unregisterListener(this)
        reset()
        sensors?.registerListener(this, accelerometer ?: return, if (fast) SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_GAME, sensorHandler)
    }

    private fun reset() {
        initialized = false
        taps = 0
        pendingAt = 0L
        busyUntil = 0L
        lastEventAt = SystemClock.elapsedRealtime()
        sensorHandler.removeCallbacks(finish)
    }

    /**
     * [fastRate] false reads the sensor about 50 times a second instead of as often as it can: less
     * battery, but short taps may be missed. Called again with another rate, it re-registers.
     */
    fun start(fastRate: Boolean = true) {
        val sensor = accelerometer ?: return
        if (running && fast == fastRate) return
        if (running) sensors?.unregisterListener(this)
        running = true
        TapStats.running = true
        fast = fastRate
        reset()
        val rate = if (fastRate) SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_GAME
        sensors.registerListener(this, sensor, rate, sensorHandler)
    }

    fun stop() {
        if (!running) return
        running = false
        TapStats.running = false
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
        lastEventAt = SystemClock.elapsedRealtime()
        TapStats.lastEventAt = lastEventAt
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
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz)
        val dt = (now - lastSampleAt).coerceIn(0L, 50L).toFloat()
        lastSampleAt = now
        if (dt > 0) sampleMs += (dt - sampleMs) * 0.05f
        TapStats.rateHz = (1000f / sampleMs.coerceAtLeast(1f)).toInt()
        TapStats.noisePercent = (noise / (limit * SHAKE_RATIO) * 100).toInt()

        // Strong constant vibration (a bumpy ride) keeps the detector shut a little after it calms down,
        // so the lull between two bumps is not taken for quiet. (Not in the middle of a tap sequence.)
        if (noise >= limit * SHAKE_RATIO && taps == 0 && pendingAt == 0L) busyUntil = now + VIBRATION_HOLD_MS

        if (dz < -limit) lastNegativeAt = now
        val spike = now - lastPeakAt > REFRACTORY_MS &&
            pendingAt == 0L &&
            dz > limit &&
            dz > SIDEWAYS_RATIO * max(abs(dx), abs(dy)) &&
            now - lastNegativeAt > FRONT_TAP_RINGING_MS
        if (spike) {
            lastPeakAt = now
            // A tap only has to stand out from the shaking around it. A fixed limit on the shaking shut the
            // detector whenever the phone was held or carried, often for good stretches at a time.
            if (noise < limit * SHAKE_RATIO && dz > noise * STAND_OUT && now >= busyUntil) {
                pendingAt = now
                pendingAbove = 0f
            } else {
                TapStats.note(TapStats.Verdict.VIBRATION)
                onRejected(TapStats.Verdict.VIBRATION)
            }
        }

        if (pendingAt != 0L) {
            // How long the phone keeps shaking after the spike: a tap dies out at once, a bump doesn't.
            if (magnitude > limit * PULSE_RATIO) pendingAbove += dt
            if (now - pendingAt >= JUDGE_MS) {
                // Too slow a sensor (power saving) can't tell the pulse's width.
                val bump = sampleMs <= PRECISE_SAMPLE_MS && pendingAbove > MAX_PULSE_MS
                val at = pendingAt
                pendingAt = 0L
                if (bump) {
                    busyUntil = now + BUMP_HOLD_MS
                    TapStats.note(TapStats.Verdict.BUMP)
                    onRejected(TapStats.Verdict.BUMP)
                } else {
                    countTap(at)
                }
            }
        }

        // Background motion level. A tap's own ringing is left out, or it would hide the next tap.
        val ringing = pendingAt != 0L || now - lastPeakAt < RING_MS
        if (!ringing) noise += (minOf(magnitude, limit) - noise) * NOISE_SMOOTHING
    }

    private fun countTap(at: Long) {
        if (taps == 0 || at - lastTapAt > TAP_GAP_MS) taps = 0
        taps++
        lastTapAt = at
        sensorHandler.removeCallbacks(finish)
        if (taps >= 3 || taps == 2 && !wantsTriple()) {
            // Nothing longer can follow: answer now instead of waiting for another tap.
            sensorHandler.post(finish)
        } else {
            sensorHandler.postDelayed(finish, SETTLE_MS)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SMOOTHING = 0.08f
        const val NOISE_SMOOTHING = 0.02f
        const val SIDEWAYS_RATIO = 1.3f
        /** Background shaking, as a share of the tap threshold, past which nothing counts as a tap. */
        const val SHAKE_RATIO = 0.6f

        /** How many times stronger than the background shaking a tap has to be. */
        const val STAND_OUT = 5f
        const val RING_MS = 120L
        const val REFRACTORY_MS = 90L
        const val FRONT_TAP_RINGING_MS = 40L
        const val TAP_GAP_MS = 500L
        const val SETTLE_MS = 450L
        const val VIBRATION_HOLD_MS = 1_200L
        const val BUMP_HOLD_MS = 700L
        const val JUDGE_MS = 60L
        const val MAX_PULSE_MS = 45f
        const val PULSE_RATIO = 0.5f
        const val PRECISE_SAMPLE_MS = 12f
        const val STARVED_MS = 4_000L
    }
}

/**
 * What became of the taps since the service started, for the motion page and the diagnostics: when taps
 * stop working, this tells why (shaking, a bump, a single knock, the pocket check, a silent sensor).
 */
object TapStats {
    enum class Verdict { TAPS, VIBRATION, BUMP, SINGLE, POCKET, NOT_PLAYING, RESTARTED }

    @Volatile var running = false
    @Volatile var rateHz = 0

    /** Background shaking, as a share of the level that shuts the detector. */
    @Volatile var noisePercent = 0

    /** When the sensor last reported, on the elapsed-realtime clock. */
    @Volatile var lastEventAt = 0L
    @Volatile var last: Verdict? = null
    @Volatile var lastAt = 0L
    private val counts = java.util.concurrent.atomic.AtomicIntegerArray(Verdict.entries.size)

    fun note(v: Verdict) {
        counts.incrementAndGet(v.ordinal)
        last = v
        lastAt = SystemClock.elapsedRealtime()
    }

    fun count(v: Verdict): Int = counts.get(v.ordinal)
}
