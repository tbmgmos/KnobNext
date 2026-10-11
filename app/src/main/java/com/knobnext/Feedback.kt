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

    /**
     * One notch of the seek "wheel": a light tick, or a firmer click on [strong] (every minute). Uses the
     * crisp haptic primitives where the phone has them.
     */
    fun detent(strong: Boolean, lightness: Float = 0.5f) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val primitive = if (strong) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(primitive)) {
            v.vibrate(VibrationEffect.startComposition().addPrimitive(primitive, if (strong) 0.8f else lightness).compose())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(if (strong) 20L else 8L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Seeking let go: a soft double tick, "landed here". */
    fun landing() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(tick)) {
            v.vibrate(VibrationEffect.startComposition().addPrimitive(tick, 0.6f).addPrimitive(tick, 0.6f, 60).compose())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 12, 60, 12), -1))
        }
    }

    /** The start or the end of the track: nowhere further to go. */
    fun bump() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            } else {
                VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
            },
        )
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

/** Timbre of the sound cues; OFF plays nothing. (TWO_TONE is shown as "melodic"; the name is kept for saved settings.) */
enum class CueStyle { OFF, SOFT, TWO_TONE, CLICK }

/** How loud the cue is, relative to the music volume. */
enum class CueVolume(val gain: Float) { LOW(0.12f), MEDIUM(0.25f), HIGH(0.5f) }

/**
 * Kinds of actions whose sound can be turned on or off separately. [sample] is what the preview plays;
 * ERRORS is the "didn't work" sound of any action.
 */
enum class CueGroup(vararg val sample: Cue) {
    TRACKS(Cue.FORWARD, Cue.BACK),
    PLAYBACK(Cue.PAUSE, Cue.PLAY),
    SEEK(Cue.SEEK_FORWARD, Cue.SEEK_BACK),
    LIKE(Cue.LIKE),
    MODES(Cue.TOGGLE),
    TIMER(Cue.TIMER),
    OTHER(Cue.TOGGLE),
    ERRORS(Cue.ERROR),
    ;

    companion object {
        /** The group an action's sound belongs to; null for actions that never make one. */
        fun of(action: Action): CueGroup? = when (action) {
            Action.NEXT, Action.PREVIOUS, Action.RESTART -> TRACKS
            Action.PLAY_PAUSE -> PLAYBACK
            Action.SEEK_FORWARD, Action.SEEK_BACK -> SEEK
            Action.LIKE -> LIKE
            Action.SHUFFLE, Action.REPEAT, Action.SWITCH_PLAYER -> MODES
            Action.SLEEP_TIMER -> TIMER
            Action.FLASHLIGHT, Action.RINGER, Action.OPEN_PLAYER, Action.POCKET -> OTHER
            // Volume is heard anyway; "announce" and "time left" speak.
            Action.NONE, Action.VOLUME_UP, Action.VOLUME_DOWN, Action.ANNOUNCE, Action.TIME_LEFT -> null
        }
    }
}

/**
 * One motif per kind of result, as (frequency Hz, length ms) notes, so the ear can tell what happened
 * without looking: up = forward, down = back, a chord = like, a low fall = it didn't work.
 */
enum class Cue(vararg val notes: Pair<Double, Int>) {
    FORWARD(784.0 to 70, 1047.0 to 90),
    BACK(1047.0 to 70, 784.0 to 90),
    PLAY(880.0 to 140),
    PAUSE(440.0 to 140),
    SEEK_FORWARD(988.0 to 40, 988.0 to 40),
    SEEK_BACK(659.0 to 40, 659.0 to 40),
    LIKE(523.0 to 60, 659.0 to 60, 784.0 to 120),
    UNLIKE(784.0 to 60, 659.0 to 60, 523.0 to 120),
    TOGGLE(880.0 to 45, 698.0 to 45, 880.0 to 70),
    TIMER(784.0 to 80, 659.0 to 80, 523.0 to 140),
    NEUTRAL(698.0 to 60),
    ERROR(330.0 to 90, 262.0 to 150),
}

/**
 * Short sounds generated on the fly (no audio files). They play with the navigation-guidance usage,
 * which follows the music volume and output (headphones), and without audio focus, so the music is
 * neither paused nor ducked.
 */
class Cues {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var track: AudioTrack? = null

    fun play(cue: Cue, style: CueStyle, volume: CueVolume) {
        if (style == CueStyle.OFF) return
        val pcm = render(cue, style)
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

    /** The same motif in each timbre: soft = plain sine, melodic = with overtones and a bit longer, click = short ticks. */
    private fun render(cue: Cue, style: CueStyle): ShortArray {
        var out = ShortArray(0)
        cue.notes.forEachIndexed { i, (hz, ms) ->
            if (i > 0) out += ShortArray(RATE * (if (style == CueStyle.CLICK) 45 else 30) / 1000)
            out += when (style) {
                CueStyle.CLICK -> tone(hz * 2, 20, overtones = false)
                CueStyle.TWO_TONE -> tone(hz, ms * 6 / 5, overtones = true)
                else -> tone(hz, ms, overtones = false)
            }
        }
        return out
    }

    /** A note with a quick fade-in and a smooth fade-out, so it starts and ends without a click. */
    private fun tone(hz: Double, ms: Int, overtones: Boolean): ShortArray {
        val n = RATE * ms / 1000
        val attack = minOf(RATE * 5 / 1000, n / 4)
        return ShortArray(n) { i ->
            val fadeIn = if (i < attack) i.toDouble() / attack else 1.0
            val fadeOut = Math.cos(Math.PI / 2 * i / n).let { it * it }
            val phase = 2 * Math.PI * hz * i / RATE
            var wave = Math.sin(phase)
            if (overtones) wave = (wave + 0.35 * Math.sin(2 * phase) + 0.15 * Math.sin(3 * phase)) / 1.5
            (wave * fadeIn * fadeOut * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val RATE = 44_100
    }
}
