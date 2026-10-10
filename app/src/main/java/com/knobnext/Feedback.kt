package com.knobnext

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Each action has its own vibration pattern, so it can be told apart without looking at the screen. */
class Haptics(context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)

    fun play(action: Action) {
        if (vibrator?.hasVibrator() != true) return
        if (action == Action.NONE || action == Action.VOLUME_UP || action == Action.VOLUME_DOWN) return
        vibrator.vibrate(effect(action))
    }

    /** A light tick: "you've reached the long-hold threshold, keep holding for the second action". */
    fun tick() {
        if (vibrator?.hasVibrator() != true) return
        vibrator.vibrate(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
            },
        )
    }

    private fun effect(action: Action): VibrationEffect {
        val seek = VibrationEffect.createWaveform(longArrayOf(0, 18, 70, 18, 70, 18), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return when (action) {
                Action.NEXT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                Action.PREVIOUS -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                Action.PLAY_PAUSE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                else -> seek
            }
        }
        return when (action) {
            Action.NEXT -> VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
            Action.PREVIOUS -> VibrationEffect.createWaveform(longArrayOf(0, 30, 90, 30), -1)
            Action.PLAY_PAUSE -> VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            else -> seek
        }
    }
}

/**
 * Reads the proximity sensor only around a gesture: armed on the first press, released shortly after,
 * so it costs no battery while idle.
 */
class ProximityProbe(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var covered = false
    private var kept = false
    private val disarm = Runnable { if (!kept) release() }

    /** Keeps the sensor on (for motion gestures, which have no key press to arm it) or lets it go. */
    fun keep(on: Boolean) {
        if (on == kept) return
        kept = on
        if (on) {
            val s = sensor ?: return
            handler.removeCallbacks(disarm)
            if (!registered) {
                covered = false
                registered = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } else {
            release()
        }
    }

    fun arm() {
        val s = sensor ?: return
        handler.removeCallbacks(disarm)
        handler.postDelayed(disarm, ARM_MS)
        if (registered) return
        covered = false
        registered = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun isCovered(): Boolean = registered && covered

    fun release() {
        kept = false
        handler.removeCallbacks(disarm)
        if (registered) sensors?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val range = sensor?.maximumRange ?: return
        covered = event.values[0] < minOf(range, 5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ARM_MS = 3_000L
    }
}

/** Sound played when a track is switched; OFF plays nothing. */
enum class CueStyle { OFF, SOFT, TWO_TONE, CLICK }

/** How loud the cue is, relative to the music volume. */
enum class CueVolume(val gain: Float) { LOW(0.12f), MEDIUM(0.25f), HIGH(0.5f) }

/**
 * Short sounds generated on the fly (no audio files). They play with the navigation-guidance usage,
 * which follows the music volume and output (headphones), and without audio focus, so the music is
 * neither paused nor ducked. "Forward" goes up in pitch, "back" goes down.
 */
class Cues {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var track: AudioTrack? = null

    fun play(style: CueStyle, forward: Boolean, volume: CueVolume) {
        if (style == CueStyle.OFF) return
        val pcm = render(style, forward)
        try {
            track?.release()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setVolume(volume.gain)
            t.play()
            track = t
        } catch (_: Exception) {
            // No audio output right now (e.g. during a call): a cue is not worth an error.
            track = null
        }
    }

    fun release() {
        track?.release()
        track = null
    }

    private fun render(style: CueStyle, forward: Boolean): ShortArray = when (style) {
        CueStyle.SOFT -> tone(if (forward) 880.0 else 587.0, 110)
        CueStyle.TWO_TONE ->
            if (forward) tone(659.0, 70) + silence(35) + tone(880.0, 90) else tone(880.0, 70) + silence(35) + tone(659.0, 90)
        CueStyle.CLICK -> tone(if (forward) 2_400.0 else 1_800.0, 20)
        CueStyle.OFF -> ShortArray(0)
    }

    /** A sine with a quick fade-in and a smooth fade-out, so it starts and ends without a click. */
    private fun tone(hz: Double, ms: Int): ShortArray {
        val n = RATE * ms / 1000
        val attack = minOf(RATE * 5 / 1000, n / 4)
        return ShortArray(n) { i ->
            val fadeIn = if (i < attack) i.toDouble() / attack else 1.0
            val fadeOut = Math.cos(Math.PI / 2 * i / n).let { it * it }
            val sample = Math.sin(2 * Math.PI * hz * i / RATE) * fadeIn * fadeOut * 0.8
            (sample * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun silence(ms: Int) = ShortArray(RATE * ms / 1000)

    private companion object {
        const val RATE = 44_100
    }
}
