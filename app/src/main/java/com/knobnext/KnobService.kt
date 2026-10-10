package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.media.MediaMetadata
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
    private val cues = Cues()
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
        /** Nothing plays: only actions that work without playback may run. */
        val idleOnly: Boolean = false,
    ) : Runnable {
        var handled = false

        /** A hold gesture (not a volume ramp or a combo) went off: release time tells how deliberate it was. */
        var holdFired = false

        /** Hold reached, but a long-hold action exists: the hold action waits for release. */
        private var holdArmed: Action? = null
        private var rampDirection = 0
        private var rampSteps = 0
        private val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP

        // Capped: if the release is ever lost, the volume must not keep changing on its own.
        val ramp = object : Runnable {
            override fun run() {
                media.volumeStep(rampDirection)
                if (++rampSteps < RAMP_MAX_STEPS) handler.postDelayed(this, RAMP_STEP_MS)
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
            val pkg = controller?.packageName
            val action = prefs.action(gesture, pkg)
            val long = prefs.action(if (up) Gesture.LONG_HOLD_UP else Gesture.LONG_HOLD_DOWN, pkg)
            val volume = action == Action.VOLUME_UP || action == Action.VOLUME_DOWN
            when {
                volume && !GestureTest.active -> {
                    rampDirection = if (action == Action.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    ramp.run()
                }
                !tapHold && long != Action.NONE -> {
                    holdArmed = action
                    if (prefs.vibrate) haptics.tick()
                    handler.postDelayed(longHold, longHoldMs() - prefs.longPressMs)
                }
                else -> {
                    holdFired = !tapHold
                    fireGesture(gesture, controller)
                }
            }
        }

        /** Key released: a hold that was waiting to see whether it becomes a long hold fires now. */
        fun release() {
            cancel()
            holdArmed?.let { action ->
                val canRun = action != Action.NONE && (!idleOnly || action.worksWhenIdle)
                if (canRun || GestureTest.active) {
                    holdFired = true
                    fireGesture(if (up) Gesture.HOLD_UP else Gesture.HOLD_DOWN, controller)
                } else {
                    // Only the long hold was meant to work now (nothing assigned to the hold, or it needs
                    // playback): a press released before it is just a volume press, not a swallowed key.
                    media.volumeStep(if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
                }
            }
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
    // Keeps sensors alive while nothing plays (the CPU may sleep otherwise): after a flip pause, and
    // during the resume window when a motion gesture can resume playback.
    private lateinit var wakeLock: PowerManager.WakeLock
    private var flipPausedAt = 0L

    // When a gesture paused playback, or 0. For a while after that a gesture assigned to play/pause
    // can resume it — see resumeWindowOpen.
    private var pausedAt = 0L

    // The sleep timer is fading the music out.
    private var fading = false

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
        // A sleep-timer fade was cut short by the process dying: the music is at a low volume.
        prefs.fadeRestoreVolume.takeIf { it >= 0 }?.let {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            prefs.fadeRestoreVolume = -1
        }
        lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeWatcher = VolumeWatcher(this, handler, ::sequencesActive, ::forgetPresses, ::onVolumeObserved)
        volumeWatcher.start()
        backTap = BackTapDetector(this, { tapThreshold }) { count ->
            fireGesture(if (count == 2) Gesture.BACK_TAP_2 else Gesture.BACK_TAP_3)
        }
        flip = FlipDetector(this, ::onFlip)
        twist = TwistDetector(this) { fireGesture(Gesture.TWIST) }
        wave = WaveDetector(this, { prefs.waveCount }, { fireGesture(Gesture.WAVE) })
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KnobNext:sensors").apply {
            setReferenceCounted(false)
        }
        handler.post(motionCheck)
        GestureTest.onChange = { handler.post { updateSensors() } }
        SleepTimer.fader = { handler.post { fadeOutAndPause() } }
        ScreenOffService.sync(this)
        Setup.returnToAppIfRequested(this)
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) log(getString(R.string.log_service_stopped))
        ScreenOffService.stop(this)
        GestureTest.onChange = null
        SleepTimer.fader = null
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) {
            volumeWatcher.stop()
            backTap.release()
            flip.stop()
            twist.stop()
            wave.stop()
            if (wakeLock.isHeld) wakeLock.release()
            extras.release()
            proximity.release()
            cues.release()
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
            KeyEvent.ACTION_UP -> onUp(code, (event.eventTime - event.downTime).toInt())
            else -> false
        }
    }

    private fun onDown(code: Int, repeatCount: Int): Boolean {
        presses[code]?.let { press ->
            if (repeatCount > 0) return !press.passive
            // A new press while the previous one is still "down": its release was lost (the screen went
            // off mid-press). Drop it, otherwise this key would stay swallowed for good.
            press.cancel()
            presses.remove(code)
            dropCombo()
        }

        val other = presses[otherKey(code)]
        if (other != null) {
            val pkg = other.controller?.packageName
            val bothAction = prefs.action(Gesture.BOTH, pkg)
            val bothHold = prefs.action(Gesture.BOTH_HOLD, pkg)
            val noCombo = bothAction == Action.NONE && bothHold == Action.NONE
            if (noCombo && !GestureTest.active) return onSingleDown(code, repeatCount)
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
        return onSingleDown(code, repeatCount)
    }

    private fun onSingleDown(code: Int, repeatCount: Int): Boolean {
        if (repeatCount > 0) return false
        val testing = GestureTest.active
        // While gestures are being checked, keys are taken even when nothing plays or the app is off.
        val target = media.resolve(prefs) ?: if (testing) Target(null, Mode.FULL) else return false
        val pkg = target.controller?.packageName
        val up = code == KeyEvent.KEYCODE_VOLUME_UP
        val hold = prefs.action(if (up) Gesture.HOLD_UP else Gesture.HOLD_DOWN, pkg)
        val long = prefs.action(if (up) Gesture.LONG_HOLD_UP else Gesture.LONG_HOLD_DOWN, pkg)
        val tapHoldAction = prefs.action(if (up) Gesture.TAP_HOLD_UP else Gesture.TAP_HOLD_DOWN, pkg)
        val tapHold = tapHoldAction != Action.NONE &&
            SystemClock.uptimeMillis() - (lastTapUpAt[code] ?: 0L) <= TAP_HOLD_WINDOW_MS
        val anyCombo = prefs.action(Gesture.BOTH, pkg) != Action.NONE || prefs.action(Gesture.BOTH_HOLD, pkg) != Action.NONE
        // With "tap, then hold" assigned, the tap has to be taken too, to know when it was released.
        val anyKeyGesture = tapHoldAction != Action.NONE || hold != Action.NONE || long != Action.NONE || anyCombo
        if (!anyKeyGesture && !testing) return false

        if (target.mode == Mode.COMBO_ONLY && !testing) {
            // Nothing plays: keys behave as usual, except holds that can start playback.
            val idleHold = if (tapHold) tapHoldAction.worksWhenIdle else hold.worksWhenIdle || long.worksWhenIdle
            if (!idleHold) {
                if (anyCombo) presses[code] = Press(code, target.controller, passive = true)
                return false
            }
        }

        armPocketGuard()
        val press = Press(code, target.controller, tapHold = tapHold, idleOnly = target.mode == Mode.COMBO_ONLY && !testing)
        presses[code] = press
        when {
            tapHold -> handler.postDelayed(press, TAP_HOLD_DELAY_MS)
            // While checking, a hold is recognized even without an action ("not assigned").
            hold != Action.NONE || long != Action.NONE || testing -> handler.postDelayed(press, prefs.longPressMs.toLong())
        }
        return true
    }

    private fun onUp(code: Int, heldMs: Int): Boolean {
        val press = presses.remove(code) ?: return false
        combo?.let {
            it.releasedEarly()
            if (presses.isEmpty()) combo = null
        }
        press.release()
        if (press.passive) return false
        if (press.handled) {
            lastTapUpAt.remove(code)
            if (press.holdFired && !GestureTest.active) prefs.recordHoldOvershoot(heldMs - prefs.longPressMs)
        } else {
            val direction = if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            if (GestureTest.active) {
                // No volume change while checking: the tap goes straight to the sequence detector.
                GestureTest.report(GestureTest.Event.Note(getString(if (direction > 0) R.string.test_tap_up else R.string.test_tap_down)))
                val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                onVolumeChange(direction, volume, volume, VolumeWatcher.Source.KEY)
            } else {
                media.volumeStep(if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
                prefs.recordTap(heldMs)
            }
            lastTapUpAt[code] = SystemClock.uptimeMillis()
        }
        return true
    }

    /** Forgets keys that are "down": with the screen off their releases never arrive. */
    private fun forgetPresses() {
        presses.values.forEach { it.cancel() }
        presses.clear()
        dropCombo()
    }

    private fun dropCombo() {
        combo?.let { handler.removeCallbacks(it) }
        combo = null
    }

    private fun otherKey(code: Int) =
        if (code == KeyEvent.KEYCODE_VOLUME_UP) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP

    private fun longHoldMs() = maxOf(prefs.longPressMs + LONG_HOLD_EXTRA_MS, LONG_HOLD_MIN_MS)

    // ---- Press sequences ----

    private fun sequencesActive(): Boolean {
        val mode = prefs.doubleMode
        if (mode == DoubleMode.OFF) return false
        if (GestureTest.active) return true
        if (!prefs.enabled) return false
        // With the screen off and nothing playing, Android ignores the volume keys altogether, so a
        // pause made there can only be undone with the screen on: allow that during the resume window.
        return mode == DoubleMode.ALWAYS || !power.isInteractive || (resumeWindowOpen() && !audio.isMusicActive)
    }

    private fun resumeWindowOpen(): Boolean {
        val minutes = prefs.resumeMinutes
        return minutes > 0 && pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt < minutes * 60_000L
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
        if (source != VolumeWatcher.Source.KEY) {
            val via = getString(if (source == VolumeWatcher.Source.BROADCAST) R.string.log_via_broadcast else R.string.log_via_poll)
            log(getString(R.string.log_volume, if (direction > 0) "↑" else "↓", previous, value, via))
        }
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
            GestureTest.report(GestureTest.Event.Note(getString(R.string.test_held)))
            return
        }
        if (sequence.isEmpty()) {
            sequenceBase = previous
            armPocketGuard()
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
        val testing = GestureTest.active
        val enabled = prefs.enabled || testing
        // While checking gestures the sensors run as if music played.
        val playing = enabled && (audio.isMusicActive || testing)
        val now = SystemClock.elapsedRealtime()
        // Playing again some other way (the player itself, headphones): the window is no longer needed.
        if (audio.isMusicActive && pausedAt != 0L && now - pausedAt > PAUSE_SETTLE_MS) pausedAt = 0L
        val resumable = enabled && !playing && resumeWindowOpen()

        val player = prefs.lastPlayer
        fun wanted(on: Boolean, vararg gestures: Gesture) =
            on && (playing || resumable && gestures.any { prefs.action(it, player).worksWhenIdle })

        val tap = wanted(prefs.backTap, Gesture.BACK_TAP_2, Gesture.BACK_TAP_3)
        val turn = wanted(prefs.twist, Gesture.TWIST)
        if (tap) backTap.start() else backTap.stop()
        if (turn) twist.start() else twist.stop()
        if (wanted(prefs.wave, Gesture.WAVE)) wave.start() else wave.stop()
        // Taps and twists have no key press to arm the pocket check, so keep it on while they run
        // (unless the wave detector already watches the same sensor).
        proximity.keep(prefs.pocketGuard && (tap || turn) && !wave.running && !testing)
        val flipPending = flipPausedAt != 0L && now - flipPausedAt < FLIP_RESUME_WINDOW_MS
        if (enabled && prefs.flip && (playing || flipPending)) flip.start() else flip.stop()

        // The accelerometer and gyroscope don't wake the CPU; without sound playing it may sleep.
        // (The proximity sensor used for the wave does wake it.) Right after a pause, stay up until
        // the sensors have been rearranged for the silence.
        val settling = pausedAt != 0L && now - pausedAt < PAUSE_SETTLE_MS * 2
        val keepAwake = flip.running && flipPending || !playing && (tap || turn) || settling || fading
        if (keepAwake && !wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_MAX_MS)
        } else if (!keepAwake && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    /** Face down pauses; turning the phone back up resumes, but only playback that we paused. */
    private fun onFlip(faceDown: Boolean) {
        if (GestureTest.active) {
            if (prefs.vibrate) haptics.tick()
            return GestureTest.report(GestureTest.Event.Note(getString(if (faceDown) R.string.test_flip_down else R.string.test_flip_up)))
        }
        if (faceDown) {
            if (!audio.isMusicActive) return
            media.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            flipPausedAt = SystemClock.elapsedRealtime()
            pausedAt = flipPausedAt
            // Nothing plays now: keep the CPU up so turning the phone back is noticed.
            stayAwake()
            if (prefs.vibrate) haptics.play(Action.PLAY_PAUSE)
            log(getString(R.string.log_flip_paused))
        } else if (flipPausedAt != 0L && SystemClock.elapsedRealtime() - flipPausedAt < FLIP_RESUME_WINDOW_MS) {
            flipPausedAt = 0L
            pausedAt = 0L
            media.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            if (prefs.vibrate) haptics.play(Action.PLAY_PAUSE)
            log(getString(R.string.log_flip_resumed))
        }
    }

    // ---- Firing ----

    /** Runs the action assigned to [gesture], logging why it didn't when it doesn't. */
    private fun fireGesture(gesture: Gesture, controller: MediaController? = null, restoreVolumeTo: Int? = null) {
        val name = getString(gestureLabel(gesture))
        val target = media.resolve(prefs)
        val player = controller ?: target?.controller
        val action = prefs.action(gesture, player?.packageName)
        if (GestureTest.active) {
            // Only checking: undo the presses' volume change and show what would happen.
            restoreVolumeTo?.let(::restoreVolume)
            if (prefs.vibrate) haptics.tick()
            GestureTest.report(GestureTest.Event.Recognized(gesture, action))
            return log(getString(R.string.log_test, name))
        }
        if (action == Action.NONE) return log(getString(R.string.log_no_action, name))
        if (target == null || (target.mode == Mode.COMBO_ONLY && !action.worksWhenIdle)) {
            return log(getString(R.string.log_not_playing, name))
        }
        if (pocketCovered()) {
            // The presses were accidental: undo their volume change too.
            restoreVolumeTo?.let(::restoreVolume)
            return log(getString(R.string.log_pocket, name))
        }
        restoreVolumeTo?.let(::restoreVolume)
        // Same decision as Media.perform: the player's own state, or the audio when there is no player.
        val pausing = action == Action.PLAY_PAUSE && (player?.isPlaying() ?: audio.isMusicActive)
        if (action == Action.PLAY_PAUSE) {
            pausedAt = if (pausing) SystemClock.elapsedRealtime() else 0L
            if (pausing) stayAwake()
        }
        val outcome = execute(action, player)
        log(outcome.message?.let { "$name → ${getString(actionLabel(action))}: $it" }
            ?: getString(R.string.log_fired, name, getString(actionLabel(action))))
        playCue(action, outcome, pausing)
        if ((action == Action.NEXT || action == Action.PREVIOUS) && prefs.announceSkips && !power.isInteractive) {
            announceNewTrack(player)
        }
    }

    /** Says the new track's name once the player reports it (or after a short wait, whatever it is then). */
    private fun announceNewTrack(controller: MediaController?) {
        val c = controller ?: return
        val before = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        var done = false
        lateinit var callback: MediaController.Callback
        val finish = Runnable {
            if (done) return@Runnable
            done = true
            c.unregisterCallback(callback)
            extras.perform(Action.ANNOUNCE, c)
        }
        callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
                if (title != before) {
                    handler.removeCallbacks(finish)
                    finish.run()
                }
            }
        }
        c.registerCallback(callback, handler)
        handler.postDelayed(finish, ANNOUNCE_WAIT_MS)
    }

    /** Sleep timer: lower the volume step by step, pause, then put the volume back for next time. */
    private fun fadeOutAndPause() {
        if (fading) return
        val start = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        log(getString(R.string.log_sleep_fade))
        if (!audio.isMusicActive || start <= 0) {
            media.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            return
        }
        fading = true
        // Written down first: if the process dies mid-fade, the next start puts the volume back.
        prefs.fadeRestoreVolume = start
        stayAwake()
        val interval = SleepTimer.FADE_MS / start
        var level = start
        var silentSince = 0L
        val step = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                if (!audio.isMusicActive) {
                    // A moment of silence is usually the next track loading; only a longer one means the
                    // music was paused by hand, and then the fade is over.
                    if (silentSince == 0L) silentSince = now
                    if (now - silentSince < FADE_SILENCE_MS) {
                        handler.postDelayed(this, minOf(interval, FADE_SILENCE_CHECK_MS))
                    } else {
                        finishFade(start)
                    }
                    return
                }
                silentSince = 0L
                level--
                ignoreVolumeUntil = now + interval + IGNORE_OWN_CHANGE_MS
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                lastVolume = level
                if (level > 0) {
                    handler.postDelayed(this, interval)
                } else {
                    media.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    handler.postDelayed({ finishFade(start) }, RESTORE_AFTER_FADE_MS)
                }
            }
        }
        handler.post(step)
    }

    private fun finishFade(volume: Int) {
        restoreVolume(volume)
        prefs.fadeRestoreVolume = -1
        fading = false
    }

    /** Until the next sensor check decides; with the screen off the CPU could otherwise sleep before it. */
    private fun stayAwake() {
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_MAX_MS)
    }

    private fun restoreVolume(value: Int) {
        ignoreVolumeUntil = SystemClock.elapsedRealtime() + IGNORE_OWN_CHANGE_MS
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        lastVolume = value
    }

    // While the wave detector runs it already watches the proximity sensor; a second listener on the
    // same sensor can make it re-report "near" and confuse it.
    private fun armPocketGuard() {
        if (prefs.pocketGuard && !wave.running) proximity.arm()
    }

    private fun pocketCovered(): Boolean =
        prefs.pocketGuard && if (wave.running) wave.covered else proximity.isCovered()

    private fun execute(action: Action, controller: MediaController?): Outcome {
        val outcome = when (action) {
            Action.ANNOUNCE, Action.LIKE, Action.SHUFFLE, Action.REPEAT, Action.SLEEP_TIMER, Action.OPEN_PLAYER,
            Action.RESTART, Action.SWITCH_PLAYER, Action.FLASHLIGHT, Action.RINGER ->
                extras.perform(action, controller)
            else -> {
                media.perform(action, controller)
                Outcome.DONE
            }
        }
        if (prefs.vibrate) haptics.play(action)
        return outcome
    }

    /** The sound for what just happened, if cues are on for it. */
    private fun playCue(action: Action, outcome: Outcome, pausing: Boolean) {
        val style = prefs.cueStyle
        if (style == CueStyle.OFF || prefs.cueScreenOffOnly && power.isInteractive) return
        val unchanged = outcome.result == Outcome.Result.UNCHANGED
        val cue = when {
            outcome.result == Outcome.Result.FAILED -> Cue.ERROR
            else -> when (action) {
                Action.NEXT -> Cue.FORWARD
                Action.PREVIOUS, Action.RESTART -> Cue.BACK
                Action.PLAY_PAUSE -> if (pausing) Cue.PAUSE else Cue.PLAY
                Action.SEEK_FORWARD -> Cue.SEEK_FORWARD
                Action.SEEK_BACK -> Cue.SEEK_BACK
                Action.LIKE -> if (unchanged) Cue.NEUTRAL else Cue.LIKE
                Action.SHUFFLE, Action.REPEAT, Action.SWITCH_PLAYER, Action.FLASHLIGHT, Action.RINGER ->
                    if (unchanged) Cue.NEUTRAL else Cue.TOGGLE
                Action.SLEEP_TIMER -> if (unchanged) Cue.NEUTRAL else Cue.TIMER
                Action.OPEN_PLAYER -> Cue.NEUTRAL
                // Volume is heard anyway; "announce" speaks.
                Action.NONE, Action.VOLUME_UP, Action.VOLUME_DOWN, Action.ANNOUNCE -> null
            }
        } ?: return
        if (prefs.cueScope == CueScope.TRACKS && cue != Cue.FORWARD && cue != Cue.BACK) return
        cues.play(cue, style, prefs.cueVolume)
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
        const val RAMP_MAX_STEPS = 60
        const val LONG_HOLD_EXTRA_MS = 800L
        const val LONG_HOLD_MIN_MS = 1_500L
        const val BOTH_HOLD_MS = 700L
        const val MOTION_CHECK_MS = 2_000L
        const val FLIP_RESUME_WINDOW_MS = 30 * 60 * 1000L
        const val WAKE_LOCK_MAX_MS = 60 * 60 * 1000L
        const val PAUSE_SETTLE_MS = 3_000L
        const val MAX_REPLAYED_STEPS = 4
        const val LOG_LINES = 10
        const val SEQUENCE_GAP_MS = 450L
        const val HUMAN_MIN_GAP_MS = 70L
        const val CONFIRM_MS = 300L
        const val RAMP_QUIET_MS = 600L
        const val IGNORE_OWN_CHANGE_MS = 400L
        const val ANNOUNCE_WAIT_MS = 2_500L
        const val RESTORE_AFTER_FADE_MS = 1_000L
        const val FADE_SILENCE_MS = 4_000L
        const val FADE_SILENCE_CHECK_MS = 500L
    }
}
