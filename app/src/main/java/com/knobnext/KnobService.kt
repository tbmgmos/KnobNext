package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.media.session.MediaController
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

    // ---- Double press: detected from volume change broadcasts. This is the only signal available with
    // the screen off, where Android does not pass volume keys to accessibility services at all. ----

    private var dblDirection = 0
    private var dblCount = 0
    private var dblLastAt = 0L
    private var dblBaseVolume = 0
    private var rampUntil = 0L
    private var ignoreVolumeUntil = 0L
    private var lastVolume = -1
    private val dblFire = Runnable { fireDoublePress() }
    private lateinit var volumeWatcher: VolumeWatcher

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        power = getSystemService(PowerManager::class.java)
        prefs = Prefs(this)
        log(getString(R.string.log_service_started))
        media = Media(this)
        haptics = Haptics(this)
        proximity = ProximityProbe(this)
        lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeWatcher = VolumeWatcher(this, handler, ::doublePressActive, ::onVolumeObserved)
        volumeWatcher.start()
        // From the background a foreground service may only start when battery optimization is off.
        if (Setup.batteryUnrestricted(this)) ScreenOffService.sync(this)
        Setup.returnToAppIfRequested(this)
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) log(getString(R.string.log_service_stopped))
        stopService(android.content.Intent(this, ScreenOffService::class.java))
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) {
            volumeWatcher.stop()
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
    private fun doublePressActive(): Boolean {
        val mode = prefs.doubleMode
        return prefs.enabled && (mode == DoubleMode.ALWAYS || (mode == DoubleMode.SCREEN_OFF && !power.isInteractive))
    }

    /**
     * One press is one volume step, so a jump of several steps in one report means several presses
     * whose broadcasts were merged; replay them as separate steps.
     */
    private fun onVolumeObserved(value: Int, source: VolumeWatcher.Source) {
        val previous = lastVolume
        lastVolume = value
        if (source == VolumeWatcher.Source.SYNC) {
            resetDouble()
            return
        }
        if (previous < 0 || value == previous) return
        val direction = if (value > previous) 1 else -1
        val steps = minOf(Math.abs(value - previous), MAX_REPLAYED_STEPS)
        for (i in 0 until steps) {
            onVolumeChange(direction, previous + i * direction, previous + (i + 1) * direction, source)
        }
    }

    private fun onVolumeChange(direction: Int, previous: Int, value: Int, source: VolumeWatcher.Source) {
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreVolumeUntil) return
        if (!doublePressActive()) {
            resetDouble()
            return
        }
        val via = getString(if (source == VolumeWatcher.Source.BROADCAST) R.string.log_via_broadcast else R.string.log_via_poll)
        log(getString(R.string.log_volume, if (direction > 0) "↑" else "↓", previous, value, via))
        if (now < rampUntil) {
            rampUntil = now + RAMP_QUIET_MS
            return
        }
        if (dblCount > 0 && direction == dblDirection && now - dblLastAt <= DOUBLE_PRESS_MS) {
            dblCount++
            dblLastAt = now
            if (dblCount == 2) {
                handler.postDelayed(dblFire, CONFIRM_MS)
            } else {
                resetDouble()
                rampUntil = now + RAMP_QUIET_MS
                log(getString(R.string.log_held))
            }
        } else {
            handler.removeCallbacks(dblFire)
            dblDirection = direction
            dblCount = 1
            dblLastAt = now
            dblBaseVolume = previous
            if (prefs.pocketGuard) proximity.arm()
        }
    }

    private fun fireDoublePress() {
        val gesture = if (dblDirection > 0) Gesture.DOUBLE_UP else Gesture.DOUBLE_DOWN
        val base = dblBaseVolume
        resetDouble()
        val name = getString(if (gesture == Gesture.DOUBLE_UP) R.string.gesture_double_up else R.string.gesture_double_down)
        val action = prefs.action(gesture)
        if (action == Action.NONE) return log(getString(R.string.log_no_action, name))
        val target = media.resolve(prefs)
        if (target == null || (target.mode == Mode.COMBO_ONLY && action != Action.PLAY_PAUSE)) {
            return log(getString(R.string.log_not_playing, name))
        }
        if (prefs.pocketGuard && proximity.isCovered()) return log(getString(R.string.log_pocket, name))
        ignoreVolumeUntil = SystemClock.elapsedRealtime() + IGNORE_OWN_CHANGE_MS
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, base, 0)
        lastVolume = base
        fire(action, target.controller)
        log(getString(R.string.log_fired, name, getString(com.knobnext.ui.actionLabel(action))))
    }

    private fun resetDouble() {
        handler.removeCallbacks(dblFire)
        dblCount = 0
        dblDirection = 0
    }

    private fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
        val screen = getString(if (power.isInteractive) R.string.log_screen_on else R.string.log_screen_off)
        val entry = "$time $screen · $message"
        val older = prefs.lastEvent?.lines().orEmpty().take(LOG_LINES - 1)
        prefs.lastEvent = (listOf(entry) + older).joinToString("\n")
    }

    private fun fire(action: Action, controller: MediaController?) {
        if (action == Action.NONE) return
        if (prefs.pocketGuard && proximity.isCovered()) return
        media.perform(action, controller)
        if (prefs.vibrate) haptics.play(action)
    }

    private companion object {
        const val MAX_REPLAYED_STEPS = 4
        const val LOG_LINES = 5
        const val DOUBLE_PRESS_MS = 450L
        const val CONFIRM_MS = 250L
        const val RAMP_QUIET_MS = 600L
        const val IGNORE_OWN_CHANGE_MS = 400L
    }
}
