package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.knobnext.ui.actionLabel
import com.knobnext.ui.gestureLabel

class KnobService : AccessibilityService() {

    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private lateinit var prefs: Prefs
    private lateinit var media: Media
    private lateinit var extras: Extras
    private lateinit var haptics: Haptics
    private lateinit var proximity: ProximityProbe
    private val handler = Handler(Looper.getMainLooper())

    // ---- Screen on: key events from the accessibility framework ----

    // Volume keys currently held. A consumed DOWN needs its UP consumed too; a passive press was left to
    // the system (nothing playing) and is tracked only so that pressing the other key can resume playback.
    private val presses = HashMap<Int, Press>()

    // When each key was last released after a short press: a press right after it is "tap, then hold".
    private val lastTapUpAt = HashMap<Int, Long>()

    private inner class Press(
        val keyCode: Int,
        val controller: MediaController?,
        val passive: Boolean = false,
        val tapHold: Boolean = false,
    ) : Runnable {
        var handled = false

        /** Hold reached, but a long-hold action exists: the hold action waits for release. */
        private var holdArmed: Action? = null
        private var rampDirection = 0
        private val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP

        val ramp = object : Runnable {
            override fun run() {
                media.volumeStep(rampDirection)
                handler.postDelayed(this, RAMP_STEP_MS)
            }
        }

        private val longHold = Runnable {
            holdArmed = null
            fireGesture(if (up) Gesture.LONG_HOLD_UP else Gesture.LONG_HOLD_DOWN, controller)
        }

        override fun run() {
            handled = true
            val gesture = when {
                tapHold && up -> Gesture.TAP_HOLD_UP
                tapHold -> Gesture.TAP_HOLD_DOWN
                up -> Gesture.HOLD_UP
                else -> Gesture.HOLD_DOWN
            }
            val action = prefs.action(gesture)
            val long = prefs.action(if (up) Gesture.LONG_HOLD_UP else Gesture.LONG_HOLD_DOWN)
            when {
                action == Action.VOLUME_UP || action == Action.VOLUME_DOWN -> {
                    rampDirection = if (action == Action.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    ramp.run()
                }
                !tapHold && long != Action.NONE -> {
                    holdArmed = action
                    if (prefs.vibrate) haptics.tick()
                    handler.postDelayed(longHold, longHoldMs() - prefs.longPressMs)
                }
                else -> fireGesture(gesture, controller)
            }
        }

        /** Key released: a hold that was waiting to see whether it becomes a long hold fires now. */
        fun release() {
            cancel()
            holdArmed?.let { fireGesture(if (up) Gesture.HOLD_UP else Gesture.HOLD_DOWN, controller) }
            holdArmed = null
        }

        fun cancel() {
            handler.removeCallbacks(this)
            handler.removeCallbacks(ramp)
            handler.removeCallbacks(longHold)
        }
    }

    /** Both keys pressed together; becomes "both held" if they stay down. */
    private inner class Combo(val controller: MediaController?) : Runnable {
        var done = false

        override fun run() {
            done = true
            fireGesture(Gesture.BOTH_HOLD, controller)
        }

        fun releasedEarly() {
            if (done) return
            done = true
            handler.removeCallbacks(this)
            fireGesture(Gesture.BOTH, controller)
        }
    }

    private var combo: Combo? = null

    // ---- Motion gestures: sensors run only while music plays ----

    private lateinit var backTap: BackTapDetector
    private lateinit var flip: FlipDetector
    private lateinit var twist: TwistDetector
    private lateinit var wave: WaveDetector
    private lateinit var flipWakeLock: PowerManager.WakeLock
    private var flipPausedAt = 0L

    // Read by the sensor thread at ~400 Hz, so it is cached here instead of hitting preferences.
    @Volatile private var tapThreshold = TapSensitivity.MEDIUM.threshold

    private val motionCheck = object : Runnable {
        override fun run() {
            updateSensors()
            handler.postDelayed(this, MOTION_CHECK_MS)
        }
    }

    // ---- Press sequences: detected from volume changes. This is the only signal available with the
    // screen off, where Android does not pass volume keys to accessibility services at all. ----

    private val sequence = ArrayList<Int>()
    private var sequenceLastAt = 0L
    private var sequenceBase = 0
    private var rampUntil = 0L
    private var ignoreVolumeUntil = 0L
    private var lastVolume = -1
    private val sequenceFire = Runnable { fireSequence() }
    private lateinit var volumeWatcher: VolumeWatcher

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        power = getSystemService(PowerManager::class.java)
        prefs = Prefs(this)
        log(getString(R.string.log_service_started))
        media = Media(this)
        extras = Extras(this, media, prefs)
        haptics = Haptics(this)
        proximity = ProximityProbe(this)
        lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeWatcher = VolumeWatcher(this, handler, ::sequencesActive, ::onVolumeObserved)
        volumeWatcher.start()
        backTap = BackTapDetector(this, { tapThreshold }) { count ->
            fireGesture(if (count == 2) Gesture.BACK_TAP_2 else Gesture.BACK_TAP_3)
        }
        flip = FlipDetector(this, ::onFlip)
        twist = TwistDetector(this) { fireGesture(Gesture.TWIST) }
        wave = WaveDetector(this) { fireGesture(Gesture.WAVE) }
        flipWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KnobNext:flip").apply {
            setReferenceCounted(false)
        }
        handler.post(motionCheck)
        // From the background a foreground service may only start when battery optimization is off.
        if (Setup.batteryUnrestricted(this)) ScreenOffService.sync(this)
        Setup.returnToAppIfRequested(this)
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) log(getString(R.string.log_service_stopped))
        stopService(Intent(this, ScreenOffService::class.java))
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) {
            volumeWatcher.stop()
            backTap.release()
            flip.stop()
            twist.stop()
            wave.stop()
            if (flipWakeLock.isHeld) flipWakeLock.release()
            extras.release()
            proximity.release()
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---- Keys ----

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
        val bothHold = prefs.action(Gesture.BOTH_HOLD)
        if (other != null && (bothAction != Action.NONE || bothHold != Action.NONE)) {
            if (!other.handled) {
                other.cancel()
                other.handled = true
                val c = Combo(other.controller)
                combo = c
                if (bothHold == Action.NONE) c.releasedEarly() else handler.postDelayed(c, BOTH_HOLD_MS)
            }
            presses[code] = Press(code, other.controller).apply { handled = true }
            return true
        }

        if (repeatCount > 0) return false
        val up = code == KeyEvent.KEYCODE_VOLUME_UP
        val hold = prefs.action(if (up) Gesture.HOLD_UP else Gesture.HOLD_DOWN)
        val long = prefs.action(if (up) Gesture.LONG_HOLD_UP else Gesture.LONG_HOLD_DOWN)
        val tapHold = SystemClock.uptimeMillis() - (lastTapUpAt[code] ?: 0L) <= TAP_HOLD_WINDOW_MS &&
            prefs.action(if (up) Gesture.TAP_HOLD_UP else Gesture.TAP_HOLD_DOWN) != Action.NONE
        val anyCombo = bothAction != Action.NONE || bothHold != Action.NONE
        if (!tapHold && hold == Action.NONE && long == Action.NONE && !anyCombo) return false
        val target = media.resolve(prefs) ?: return false

        if (target.mode == Mode.COMBO_ONLY) {
            if (anyCombo) presses[code] = Press(code, target.controller, passive = true)
            return false
        }

        if (prefs.pocketGuard) proximity.arm()
        val press = Press(code, target.controller, tapHold = tapHold)
        presses[code] = press
        when {
            tapHold -> handler.postDelayed(press, TAP_HOLD_DELAY_MS)
            hold != Action.NONE || long != Action.NONE -> handler.postDelayed(press, prefs.longPressMs.toLong())
        }
        return true
    }

    private fun onUp(code: Int): Boolean {
        val press = presses.remove(code) ?: return false
        combo?.let {
            it.releasedEarly()
            if (presses.isEmpty()) combo = null
        }
        press.release()
        if (press.passive) return false
        if (press.handled) {
            lastTapUpAt.remove(code)
        } else {
            media.volumeStep(if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
            lastTapUpAt[code] = SystemClock.uptimeMillis()
        }
        return true
    }

    private fun otherKey(code: Int) =
        if (code == KeyEvent.KEYCODE_VOLUME_UP) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP

    private fun longHoldMs() = maxOf(prefs.longPressMs + LONG_HOLD_EXTRA_MS, LONG_HOLD_MIN_MS)

    // ---- Press sequences ----

    private fun sequencesActive(): Boolean {
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
            resetSequence()
            return
        }
        if (previous < 0 || value == previous) return
        val direction = if (value > previous) 1 else -1
        val steps = minOf(Math.abs(value - previous), MAX_REPLAYED_STEPS)
        for (i in 0 until steps) {
            onVolumeChange(direction, previous + i * direction, previous + (i + 1) * direction, source)
        }
    }

    /**
     * Collects up to three quick steps (e.g. ↑↑, ↑↑↑, ↑↓). Steps closer together than a person can press
     * — or more than three — mean the key is held or the volume is ramping, so nothing fires.
     */
    private fun onVolumeChange(direction: Int, previous: Int, value: Int, source: VolumeWatcher.Source) {
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreVolumeUntil) return
        if (!sequencesActive()) {
            resetSequence()
            return
        }
        val via = getString(if (source == VolumeWatcher.Source.BROADCAST) R.string.log_via_broadcast else R.string.log_via_poll)
        log(getString(R.string.log_volume, if (direction > 0) "↑" else "↓", previous, value, via))
        if (now < rampUntil) {
            rampUntil = now + RAMP_QUIET_MS
            return
        }
        if (sequence.isNotEmpty() && now - sequenceLastAt > SEQUENCE_GAP_MS) resetSequence()
        val tooFast = sequence.isNotEmpty() && now - sequenceLastAt < HUMAN_MIN_GAP_MS &&
            source != VolumeWatcher.Source.BROADCAST
        if (tooFast || sequence.size >= 3) {
            resetSequence()
            rampUntil = now + RAMP_QUIET_MS
            log(getString(R.string.log_held))
            return
        }
        if (sequence.isEmpty()) {
            sequenceBase = previous
            if (prefs.pocketGuard) proximity.arm()
        }
        sequence += direction
        sequenceLastAt = now
        handler.removeCallbacks(sequenceFire)
        if (sequence.size >= 2) handler.postDelayed(sequenceFire, CONFIRM_MS)
    }

    private fun fireSequence() {
        val gesture = when (sequence) {
            listOf(1, 1) -> Gesture.DOUBLE_UP
            listOf(-1, -1) -> Gesture.DOUBLE_DOWN
            listOf(1, 1, 1) -> Gesture.TRIPLE_UP
            listOf(-1, -1, -1) -> Gesture.TRIPLE_DOWN
            listOf(1, -1) -> Gesture.UP_DOWN
            listOf(-1, 1) -> Gesture.DOWN_UP
            else -> null
        }
        val base = sequenceBase
        resetSequence()
        gesture ?: return
        fireGesture(gesture, restoreVolumeTo = base)
    }

    private fun resetSequence() {
        handler.removeCallbacks(sequenceFire)
        sequence.clear()
    }

    // ---- Motion ----

    private fun updateSensors() {
        tapThreshold = prefs.tapSensitivity.threshold
        val playing = prefs.enabled && audio.isMusicActive
        if (playing && prefs.backTap) backTap.start() else backTap.stop()
        if (playing && prefs.twist) twist.start() else twist.stop()
        if (playing && prefs.wave) wave.start() else wave.stop()
        val flipPending = SystemClock.elapsedRealtime() - flipPausedAt < FLIP_RESUME_WINDOW_MS
        if (prefs.enabled && prefs.flip && (playing || flipPending)) flip.start() else flip.stop()
        if (!flipPending && flipWakeLock.isHeld) flipWakeLock.release()
    }

    /** Face down pauses; turning the phone back up resumes, but only playback that we paused. */
    private fun onFlip(faceDown: Boolean) {
        if (faceDown) {
            if (!audio.isMusicActive) return
            media.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            flipPausedAt = SystemClock.elapsedRealtime()
            // Nothing plays now, so the CPU could sleep and miss the phone being turned back.
            flipWakeLock.acquire(FLIP_RESUME_WINDOW_MS)
            if (prefs.vibrate) haptics.play(Action.PLAY_PAUSE)
            log(getString(R.string.log_flip_paused))
        } else if (SystemClock.elapsedRealtime() - flipPausedAt < FLIP_RESUME_WINDOW_MS) {
            flipPausedAt = 0L
            if (flipWakeLock.isHeld) flipWakeLock.release()
            media.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            if (prefs.vibrate) haptics.play(Action.PLAY_PAUSE)
            log(getString(R.string.log_flip_resumed))
        }
    }

    // ---- Firing ----

    /** Runs the action assigned to [gesture], logging why it didn't when it doesn't. */
    private fun fireGesture(gesture: Gesture, controller: MediaController? = null, restoreVolumeTo: Int? = null) {
        val name = getString(gestureLabel(gesture))
        val action = prefs.action(gesture)
        if (action == Action.NONE) return log(getString(R.string.log_no_action, name))
        val target = media.resolve(prefs)
        if (target == null || (target.mode == Mode.COMBO_ONLY && !action.worksWhenIdle)) {
            return log(getString(R.string.log_not_playing, name))
        }
        if (prefs.pocketGuard && proximity.isCovered()) return log(getString(R.string.log_pocket, name))
        if (restoreVolumeTo != null) {
            ignoreVolumeUntil = SystemClock.elapsedRealtime() + IGNORE_OWN_CHANGE_MS
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolumeTo, 0)
            lastVolume = restoreVolumeTo
        }
        val problem = execute(action, controller ?: target.controller)
        log(problem?.let { "$name → ${getString(actionLabel(action))}: $it" }
            ?: getString(R.string.log_fired, name, getString(actionLabel(action))))
    }

    private fun execute(action: Action, controller: MediaController?): String? {
        val problem = when (action) {
            Action.ANNOUNCE, Action.LIKE, Action.SHUFFLE, Action.REPEAT, Action.SLEEP_TIMER, Action.OPEN_PLAYER ->
                extras.perform(action, controller)
            else -> {
                media.perform(action, controller)
                null
            }
        }
        if (prefs.vibrate) haptics.play(action)
        return problem
    }

    private fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
        val screen = getString(if (power.isInteractive) R.string.log_screen_on else R.string.log_screen_off)
        val entry = "$time $screen · $message"
        val older = prefs.lastEvent?.lines().orEmpty().take(LOG_LINES - 1)
        prefs.lastEvent = (listOf(entry) + older).joinToString("\n")
    }

    private companion object {
        const val TAP_HOLD_WINDOW_MS = 350L
        const val TAP_HOLD_DELAY_MS = 200L
        const val RAMP_STEP_MS = 110L
        const val LONG_HOLD_EXTRA_MS = 800L
        const val LONG_HOLD_MIN_MS = 1_500L
        const val BOTH_HOLD_MS = 700L
        const val MOTION_CHECK_MS = 2_000L
        const val FLIP_RESUME_WINDOW_MS = 30 * 60 * 1000L
        const val MAX_REPLAYED_STEPS = 4
        const val LOG_LINES = 6
        const val SEQUENCE_GAP_MS = 450L
        const val HUMAN_MIN_GAP_MS = 70L
        const val CONFIRM_MS = 300L
        const val RAMP_QUIET_MS = 600L
        const val IGNORE_OWN_CHANGE_MS = 400L
    }
}
