package com.knobnext

import android.content.Context
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
        if (vibrator?.hasVibrator() != true || action == Action.NONE) return
        vibrator.vibrate(effect(action))
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
    private val disarm = Runnable { release() }

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
