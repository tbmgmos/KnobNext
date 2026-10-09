package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KnobService : AccessibilityService() {

    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    // Volume keys whose DOWN we consumed; their UP must be consumed too.
    private val presses = HashMap<Int, Press>()

    // Keep intercepting for a while after our own media command, so a paused player
    // (isMusicActive == false) can still be resumed with the same buttons.
    private var lastMediaCommandAt = 0L

    private inner class Press(val keyCode: Int) : Runnable {
        var handled = false

        override fun run() {
            handled = true
            val next = (keyCode == KeyEvent.KEYCODE_VOLUME_UP) != prefs.swap
            sendMediaKey(if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        prefs = Prefs(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(code, event.repeatCount)
            KeyEvent.ACTION_UP -> onUp(code)
            else -> false
        }
    }

    private fun onDown(code: Int, repeatCount: Int): Boolean {
        if (presses.containsKey(code)) return true

        val other = presses[otherKey(code)]
        if (other != null && prefs.comboPlayPause) {
            if (!other.handled) {
                handler.removeCallbacks(other)
                other.handled = true
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            presses[code] = Press(code).apply { handled = true }
            return true
        }

        if (repeatCount > 0 || !shouldIntercept()) return false

        val press = Press(code)
        presses[code] = press
        handler.postDelayed(press, prefs.longPressMs.toLong())
        return true
    }

    private fun onUp(code: Int): Boolean {
        val press = presses.remove(code) ?: return false
        handler.removeCallbacks(press)
        if (!press.handled) {
            val direction =
                if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    private fun shouldIntercept(): Boolean {
        if (!prefs.enabled) return false
        when (audio.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> return false
        }
        if (!prefs.onlyWhenPlaying) return true
        return audio.isMusicActive ||
            SystemClock.elapsedRealtime() - lastMediaCommandAt < RESUME_WINDOW_MS
    }

    private fun sendMediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        lastMediaCommandAt = SystemClock.elapsedRealtime()
        if (prefs.vibrate) {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun otherKey(code: Int) =
        if (code == KeyEvent.KEYCODE_VOLUME_UP) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP

    companion object {
        private const val RESUME_WINDOW_MS = 5 * 60 * 1000L
    }
}
