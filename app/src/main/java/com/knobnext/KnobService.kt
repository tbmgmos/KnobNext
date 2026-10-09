package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KnobService : AccessibilityService() {

    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private lateinit var prefs: Prefs
    private lateinit var media: Media
    private lateinit var haptics: Haptics
    private lateinit var proximity: ProximityProbe
    private val handler = Handler(Looper.getMainLooper())

    // ---- Screen on: key events from the accessibility framework ----

    // Volume keys currently held. A consumed DOWN needs its UP consumed too; a passive press was left to
    // the system (nothing playing) and is tracked only so that pressing the other key can resume playback.
    private val presses = HashMap<Int, Press>()

    private inner class Press(
        val keyCode: Int,
        val controller: MediaController?,
        val passive: Boolean = false,
    ) : Runnable {
        var handled = false

        override fun run() {
            handled = true
            val gesture = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) Gesture.HOLD_UP else Gesture.HOLD_DOWN
            fire(prefs.action(gesture), controller)
        }
    }

    // ---- Screen off: the system still changes the volume and announces it ----

    private var offDirection = 0
    private var offCount = 0
    private var offLastAt = 0L
    private var offBaseVolume = 0
    private var rampUntil = 0L
    private var ignoreVolumeUntil = 0L
    private val offFire = Runnable { fireScreenOffGesture() }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            val previous = intent.getIntExtra(EXTRA_PREV_VALUE, -1)
            if (value < 0 || previous < 0 || value == previous) return
            onScreenOffVolumeChange(if (value > previous) 1 else -1, previous)
        }
    }

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        power = getSystemService(PowerManager::class.java)
        prefs = Prefs(this)
        media = Media(this)
        haptics = Haptics(this)
        proximity = ProximityProbe(this)
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, filter)
        }
        Setup.returnToAppIfRequested(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) {
            unregisterReceiver(volumeReceiver)
            proximity.release()
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

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
        presses[code]?.let { return !it.passive }

        val other = presses[otherKey(code)]
        val bothAction = prefs.action(Gesture.BOTH)
        if (other != null && bothAction != Action.NONE) {
            if (!other.handled) {
                handler.removeCallbacks(other)
                other.handled = true
                fire(bothAction, other.controller)
            }
            presses[code] = Press(code, other.controller).apply { handled = true }
            return true
        }

        if (repeatCount > 0) return false
        val hold = prefs.action(if (code == KeyEvent.KEYCODE_VOLUME_UP) Gesture.HOLD_UP else Gesture.HOLD_DOWN)
        if (hold == Action.NONE && bothAction == Action.NONE) return false
        val target = media.resolve(prefs) ?: return false

        if (target.mode == Mode.COMBO_ONLY) {
            if (bothAction != Action.NONE) presses[code] = Press(code, target.controller, passive = true)
            return false
        }

        if (prefs.pocketGuard) proximity.arm()
        val press = Press(code, target.controller)
        presses[code] = press
        if (hold != Action.NONE) handler.postDelayed(press, prefs.longPressMs.toLong())
        return true
    }

    private fun onUp(code: Int): Boolean {
        val press = presses.remove(code) ?: return false
        handler.removeCallbacks(press)
        if (press.passive) return false
        if (!press.handled) {
            val direction =
                if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    private fun otherKey(code: Int) =
        if (code == KeyEvent.KEYCODE_VOLUME_UP) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP

    /**
     * Two quick presses in the same direction = gesture. A third change right after means the key is
     * being held (auto-repeat) to ramp the volume, so the pending gesture is cancelled.
     */
    private fun onScreenOffVolumeChange(direction: Int, previous: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreVolumeUntil) return
        if (power.isInteractive || !prefs.enabled || !prefs.screenOff) {
            resetScreenOff()
            return
        }
        if (now < rampUntil) {
            rampUntil = now + RAMP_QUIET_MS
            return
        }
        if (offCount > 0 && direction == offDirection && now - offLastAt <= DOUBLE_PRESS_MS) {
            offCount++
            offLastAt = now
            if (offCount == 2) {
                handler.postDelayed(offFire, CONFIRM_MS)
            } else {
                resetScreenOff()
                rampUntil = now + RAMP_QUIET_MS
            }
        } else {
            handler.removeCallbacks(offFire)
            offDirection = direction
            offCount = 1
            offLastAt = now
            offBaseVolume = previous
            if (prefs.pocketGuard) proximity.arm()
        }
    }

    private fun fireScreenOffGesture() {
        val direction = offDirection
        val base = offBaseVolume
        resetScreenOff()
        val action = prefs.action(if (direction > 0) Gesture.DOUBLE_UP_OFF else Gesture.DOUBLE_DOWN_OFF)
        if (action == Action.NONE) return
        val target = media.resolve(prefs) ?: return
        if (target.mode == Mode.COMBO_ONLY && action != Action.PLAY_PAUSE) return
        if (prefs.pocketGuard && proximity.isCovered()) return
        ignoreVolumeUntil = SystemClock.elapsedRealtime() + IGNORE_OWN_CHANGE_MS
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, base, 0)
        fire(action, target.controller)
    }

    private fun resetScreenOff() {
        handler.removeCallbacks(offFire)
        offCount = 0
        offDirection = 0
    }

    private fun fire(action: Action, controller: MediaController?) {
        if (action == Action.NONE) return
        if (prefs.pocketGuard && proximity.isCovered()) return
        media.perform(action, controller)
        if (prefs.vibrate) haptics.play(action)
    }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        const val DOUBLE_PRESS_MS = 450L
        const val CONFIRM_MS = 250L
        const val RAMP_QUIET_MS = 600L
        const val IGNORE_OWN_CHANGE_MS = 400L
    }
}
