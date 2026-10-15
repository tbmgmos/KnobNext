package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.knobnext.ui.actionLabel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.knobnext.ui.gestureLabel

class KnobService : AccessibilityService() {

    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private lateinit var prefs: Prefs
    private lateinit var media: Media
    private lateinit var extras: Extras
    private lateinit var bubble: TrackBubble

    // The track the round cover last appeared for by itself: once per track.
    private var bubbledTrack: String? = null
    private lateinit var haptics: Haptics
    private lateinit var proximity: ProximityProbe
    private val cues = Cues()

    // Plays silence after a gesture pause, so the keys keep working with the screen off.
    private val silence = Silence()

    // The music is paused for a story about the track: the silence stays until it ends.
    private var storyHold = false
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

        // Seeking held down: repeats, a bigger jump the longer the key is held. Capped like the ramp.
        private var seekRepeats = 0
        private var seekAction = Action.NONE
        val seekRamp = object : Runnable {
            override fun run() {
                seekRepeats++
                val factor = when {
                    seekRepeats < SEEK_FASTER_AFTER -> 1
                    seekRepeats < SEEK_FASTER_AFTER * 2 -> 2
                    else -> 3
                }
                media.perform(seekAction, controller, prefs.seekStepMs(controller?.packageName) * factor)
                if (prefs.vibrate) haptics.tick()
                if (seekRepeats < SEEK_MAX_REPEATS) handler.postDelayed(this, SEEK_REPEAT_MS)
            }
        }

        // Smooth seeking held down: small seeks five times a second, faster the longer it is held, with a
        // vibration notch at every seek step of the track and a firmer one at every minute.
        private var scrubDirection = 0
        private var scrubPosition = 0.0
        private var scrubEnd = 0L
        private var scrubStartedAt = 0L
        private var scrubScale = 1.0
        private var scrubNotchMs = 10_000L
        private var scrubLastNotch = 0L
        private var scrubLastMinute = 0L
        private var scrubSteps = 0
        private var scrubVolume = -1

        /** Smooth seeking is running (the other key reverses it instead of making a combo). */
        var scrubbing = false
            private set

        val scrub = object : Runnable {
            override fun run() {
                val c = controller ?: return
                val held = SystemClock.elapsedRealtime() - scrubStartedAt
                scrubPosition += scrubDirection * scrubSpeed(held) * scrubScale * SCRUB_TICK_MS
                val atBound = scrubPosition <= 0.0 || scrubPosition >= scrubEnd
                scrubPosition = scrubPosition.coerceIn(0.0, scrubEnd.toDouble())
                val target = scrubPosition.toLong()
                c.transportControls.seekTo(target)
                if (prefs.vibrate) {
                    val notch = target / scrubNotchMs
                    val minute = target / 60_000
                    // Like a wheel spinning faster: the faster it goes, the lighter each notch.
                    val lightness = (0.6f - (scrubSpeed(held) / 60.0).toFloat() * 0.3f).coerceIn(0.3f, 0.6f)
                    when {
                        atBound -> haptics.bump()
                        minute != scrubLastMinute -> haptics.detent(strong = true)
                        notch != scrubLastNotch -> haptics.detent(strong = false, lightness = lightness)
                    }
                    scrubLastNotch = notch
                    scrubLastMinute = minute
                }
                if (!atBound && ++scrubSteps < SCRUB_MAX_STEPS) handler.postDelayed(this, SCRUB_TICK_MS)
            }
        }

        /** Starts smooth seeking from the player's position; false when the player doesn't report it. */
        fun startScrub(action: Action): Boolean {
            val (position, duration) = media.progress(controller) ?: return false
            scrubDirection = if (action == Action.SEEK_FORWARD) 1 else -1
            scrubPosition = position.toDouble()
            scrubEnd = (duration - SCRUB_END_MARGIN_MS).coerceAtLeast(0L)
            scrubStartedAt = SystemClock.elapsedRealtime()
            scrubNotchMs = prefs.seekStepMs(controller?.packageName)
            scrubScale = scrubNotchMs / 10_000.0
            scrubLastNotch = position / scrubNotchMs
            scrubLastMinute = position / 60_000
            scrubSteps = 0
            scrubbing = true
            if (prefs.scrubDuck) {
                scrubVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val quieter = maxOf(1, scrubVolume * SCRUB_DUCK_PERCENT / 100)
                if (quieter < scrubVolume) {
                    ignoreVolumeUntil = Long.MAX_VALUE
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, quieter, 0)
                    lastVolume = quieter
                } else {
                    scrubVolume = -1
                }
            }
            handler.post(scrub)
            return true
        }

        /** The other key pressed while seeking: turn around, slowly again, for fine positioning. */
        fun reverseScrub() {
            scrubDirection = -scrubDirection
            scrubStartedAt = SystemClock.elapsedRealtime()
            if (prefs.vibrate) haptics.detent(strong = true)
        }

        /** Ends smooth seeking; [landed] when the key was let go (not when the press was dropped). */
        fun stopScrub(landed: Boolean) {
            if (!scrubbing) return
            scrubbing = false
            handler.removeCallbacks(scrub)
            if (scrubVolume >= 0) {
                restoreVolume(scrubVolume)
                scrubVolume = -1
            }
            if (!landed) return
            if (prefs.vibrate) haptics.landing()
            if (prefs.scrubSpeak) extras.say(positionPhrase(scrubPosition.toLong()))
        }

        // Capped: if the release is ever lost, the volume must not keep changing on its own.
        val ramp = object : Runnable {
            override fun run() {
                media.volumeStep(rampDirection, showUi = !screenDark())
                if (++rampSteps < RAMP_MAX_STEPS) handler.postDelayed(this, RAMP_STEP_MS)
            }
        }

        private val longHold = Runnable {
            holdArmed = null
            val gesture = if (up) Gesture.LONG_HOLD_UP else Gesture.LONG_HOLD_DOWN
            val action = prefs.action(gesture, controller?.packageName)
            // The key is still down: a long hold assigned to seeking seeks until it is let go.
            if ((action == Action.SEEK_FORWARD || action == Action.SEEK_BACK) && !GestureTest.active) {
                startSeeking(gesture, action)
            } else {
                fireGesture(gesture, controller)
            }
        }

        /** Seeking for as long as the key is held: smooth when the player reports its position, else jumps. */
        private fun startSeeking(gesture: Gesture, action: Action) {
            val smooth = prefs.seekSmooth && media.progress(controller) != null
            if (prefs.seekSmooth && !smooth) log(getString(R.string.log_seek_no_position))
            // The start goes through the normal path (checks, log, sound); then the hold drives it.
            if (fireGesture(gesture, controller, startOnly = smooth)) {
                seekAction = action
                if (!smooth || !startScrub(action)) {
                    seekRepeats = 0
                    handler.postDelayed(seekRamp, SEEK_REPEAT_MS)
                }
            }
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
            val seek = action == Action.SEEK_FORWARD || action == Action.SEEK_BACK
            when {
                volume && !GestureTest.active -> {
                    rampDirection = if (action == Action.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    // The ramp itself is silent at first; a tick says the hold was taken.
                    if (prefs.vibrate) haptics.tick()
                    ramp.run()
                }
                // A seeking hold drives the seek for as long as it is held; a long hold on the same key
                // could never be reached (it would fire in the middle of seeking), so it is not waited for.
                seek && !GestureTest.active -> {
                    holdFired = !tapHold
                    startSeeking(gesture, action)
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
            stopScrub(landed = true)
            cancel()
            holdArmed?.let { action ->
                val canRun = action != Action.NONE && (!idleOnly || action.worksWhenIdle)
                if (canRun || GestureTest.active) {
                    holdFired = true
                    fireGesture(if (up) Gesture.HOLD_UP else Gesture.HOLD_DOWN, controller)
                } else {
                    // Only the long hold was meant to work now (nothing assigned to the hold, or it needs
                    // playback): a press released before it is just a volume press, not a swallowed key.
                    media.volumeStep(if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, showUi = !screenDark())
                }
            }
            holdArmed = null
        }

        fun cancel() {
            handler.removeCallbacks(this)
            handler.removeCallbacks(ramp)
            handler.removeCallbacks(seekRamp)
            stopScrub(landed = false)
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
    private lateinit var headset: HeadsetButtons
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
    private var fadeVolume = -1
    private var fadeStep: Runnable? = null
    private val shake by lazy { ShakeDetector(this) { extendDuringFade(viaShake = true) } }

    // Read by the sensor thread at ~400 Hz, so they are cached here instead of hitting preferences.
    @Volatile private var tapThreshold = TapSensitivity.MEDIUM.threshold
    @Volatile private var backTapTriple = true

    // Follows every player: which one plays now, and new tracks to speak when that option is on.
    private var trackWatcher: NowPlayingTracker? = null
    private val scrobbler by lazy { ListenBrainz.Scrobbler(this) }
    private var announcedTrack: String? = null
    private var trackPlayer: String? = null

    private val tapNoteAt = java.util.concurrent.ConcurrentHashMap<TapStats.Verdict, Long>()
    private var backgroundRetryAt = 0L

    /** Says in the log why a tap was not taken, each reason at most once in a while (any thread). */
    private fun noteTap(why: TapStats.Verdict) {
        val at = SystemClock.elapsedRealtime()
        if (at - (tapNoteAt[why] ?: 0L) < TAP_NOTE_MS) return
        tapNoteAt[why] = at
        val reason = getString(
            when (why) {
                TapStats.Verdict.VIBRATION -> R.string.log_tap_vibration
                TapStats.Verdict.BUMP -> R.string.log_tap_bump
                TapStats.Verdict.TURN -> R.string.log_tap_turn
                TapStats.Verdict.SINGLE -> R.string.log_tap_single
                TapStats.Verdict.RESTARTED -> R.string.log_tap_restarted
                else -> return
            },
        )
        handler.post { log(reason) }
    }

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
    private val sequenceGaps = ArrayList<Int>()
    private var sequenceGap = SEQUENCE_GAP_MS
    private lateinit var volumeWatcher: VolumeWatcher

    // ---- Screen off, key held: the system repeats the volume step, and nothing else does that fast. ----
    private var blindHold = 0
    private var blindHoldBase = 0
    private var blindHoldStepAt = 0L
    private var blindSeek = Action.NONE
    private val blindHoldEnd = object : Runnable {
        override fun run() {
            val quiet = SystemClock.elapsedRealtime() - blindHoldStepAt
            if (quiet >= BLIND_RELEASE_MS) finishBlindHold() else handler.postDelayed(this, BLIND_RELEASE_MS - quiet)
        }
    }
    private val blindSeekRepeat = object : Runnable {
        override fun run() {
            if (blindHold == 0) return
            val controller = media.resolve(prefs)?.controller
            media.perform(blindSeek, controller, prefs.seekStepMs(controller?.packageName))
            if (prefs.vibrate) haptics.tick()
            handler.postDelayed(this, SEEK_REPEAT_MS)
        }
    }

    // ---- Voice commands ----
    private lateinit var voice: VoiceListener
    private val scope = kotlinx.coroutines.MainScope()

    // ---- Pocket mode ----
    private lateinit var pocket: PocketScreen
    private var pocketShownAt = 0L
    private var pocketLeftAt = 0L
    private var pocketQuietSince = 0L
    private var pocketPending = false
    private val pocketCheck = object : Runnable {
        override fun run() = checkPocket()
    }

    // ---- Power button: seen only as the screen turning off and on ----
    private var screenTurnedAt = 0L
    private var screenOnAt = 0L
    private var ownScreenChangeAt = 0L
    private var pocketPowerOffAt = 0L

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        power = getSystemService(PowerManager::class.java)
        prefs = Prefs(this)
        prefs.migrate()
        log(getString(R.string.log_service_started))
        media = Media(this)
        media.silencePlaying = { silence.playing || ::headset.isInitialized && headset.silencePlaying }
        extras = Extras(this, media, prefs)
        bubble = TrackBubble(this, prefs, log = { handler.post { log(it) } }, act = ::fromBubble, read = extras::readAloud)
        extras.bubble = bubble
        haptics = Haptics(this)
        proximity = ProximityProbe(this)
        // A sleep-timer fade was cut short by the process dying: the music is at a low volume.
        prefs.fadeRestoreVolume.takeIf { it >= 0 }?.let {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            prefs.fadeRestoreVolume = -1
        }
        lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        pocket = PocketScreen(this, ::onPocketLeft)
        voice = VoiceListener(this, handler)
        volumeWatcher = VolumeWatcher(this, handler, ::sequencesActive, { prefs.blindGestures }, ::onScreen, ::onVolumeObserved)
        volumeWatcher.start()
        backTap = BackTapDetector(this, { tapThreshold }, { backTapTriple }, ::noteTap) { count ->
            fireGesture(if (count == 2) Gesture.BACK_TAP_2 else Gesture.BACK_TAP_3)
        }
        // Build 22241d6 could take KnobNext's own session for the player and remember it as one.
        prefs.forgetPlayer(packageName)
        headset = HeadsetButtons(this, handler, prefs, media, { handler.post { log(it) } }, ::onHeadsetKey)
        HeadsetHold.handler = ::onHeadsetHold
        flip = FlipDetector(this, ::onFlip)
        twist = TwistDetector(this) { fireGesture(Gesture.TWIST) }
        wave = WaveDetector(this, { prefs.waveCount }, { fireGesture(Gesture.WAVE) })
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KnobNext:sensors").apply {
            setReferenceCounted(false)
        }
        extras.log = { handler.post { log(it) } }
        extras.keepKeysAlive = { on ->
            storyHold = on
            if (on) silence.start() else if (pausedAt == 0L) silence.stop()
        }
        extras.onLikeNotConfirmed = { pkg ->
            val reason = getString(R.string.log_like_not_confirmed)
            log(reason)
            playCue(Action.LIKE, Outcome.failed(reason), pausing = false, pkg = pkg)
        }
        sequenceGap = learnedSequenceGap()
        handler.post(motionCheck)
        GestureTest.onChange = { handler.post { updateSensors() } }
        SleepTimer.fader = { handler.post { fadeOutAndPause() } }
        PocketScreen.toggle = { handler.post { togglePocket() } }
        PowerHold.handler = ::onPowerHold
        VoiceListener.trigger = { handler.post { startVoice() } }
        // Bound by the system as an accessibility service, the app may start it from the background.
        ScreenOffService.sync(this, inForeground = true)
        Setup.returnToAppIfRequested(this)
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) log(getString(R.string.log_service_stopped))
        // Only when it is no longer wanted (the accessibility service turned off, gestures off). The
        // system also restarts this service on its own; stopping the background service then left the
        // sensors without it until the app was next opened, and Android stops sending sensor readings
        // to an app about a minute after it goes to the background: taps stopped working.
        if (!ScreenOffService.shouldRun(this)) ScreenOffService.stop(this)
        GestureTest.onChange = null
        SleepTimer.fader = null
        PocketScreen.toggle = null
        PowerHold.handler = null
        HeadsetHold.handler = null
        VoiceListener.trigger = null
        pendingAnnounce?.invoke()
        scrobbler.stop()
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) {
            pocket.hide(PocketScreen.Reason.STOPPED)
            voice.cancel()
            scope.cancel()
            silence.stop()
            volumeWatcher.stop()
            trackWatcher?.stop()
            backTap.release()
            headset.release()
            flip.stop()
            twist.stop()
            wave.stop()
            if (fading) shake.stop()
            if (wakeLock.isHeld) wakeLock.release()
            extras.release()
            bubble.release()
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
        if (other != null && other.scrubbing) {
            other.reverseScrub()
            presses[code] = Press(code, other.controller).apply { handled = true }
            return true
        }
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
                media.volumeStep(if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, showUi = !screenDark())
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

    /** How fast smooth seeking runs, in track milliseconds per real millisecond, after holding [heldMs]. */
    private fun scrubSpeed(heldMs: Long): Double = when {
        // A slow start to land precisely, then faster and faster.
        heldMs < 700 -> 3.0
        heldMs < 2_000 -> 3.0 + (heldMs - 700) / 1_300.0 * 9.0
        heldMs < 4_000 -> 12.0 + (heldMs - 2_000) / 2_000.0 * 18.0
        else -> 30.0 + minOf((heldMs - 4_000) / 3_000.0, 1.0) * 30.0
    }

    /** "12 минут 34 секунды" for a track position. */
    private fun positionPhrase(positionMs: Long): String {
        val total = (positionMs / 1000).toInt()
        val minutes = total / 60
        val seconds = total % 60
        return buildList {
            if (minutes > 0) add(resources.getQuantityString(R.plurals.minutes, minutes, minutes))
            if (seconds > 0 || minutes == 0) add(resources.getQuantityString(R.plurals.seconds, seconds, seconds))
        }.joinToString(" ")
    }

    private fun longHoldMs() = maxOf(prefs.longPressMs + LONG_HOLD_EXTRA_MS, LONG_HOLD_MIN_MS)

    // ---- Press sequences ----

    private fun sequencesActive(): Boolean {
        val mode = prefs.doubleMode
        if (mode == DoubleMode.OFF) return false
        if (GestureTest.active) return true
        if (!prefs.enabled) return false
        // With the screen off and nothing playing, Android ignores the volume keys altogether, so a
        // pause made there can only be undone with the screen on: allow that during the resume window.
        return mode == DoubleMode.ALWAYS || screenDark() || (resumeWindowOpen() && !media.musicActive())
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
        if (blindHold != 0) {
            // Still held: keep the volume where it was before the hold.
            blindHoldStepAt = now
            quietRestore(blindHoldBase)
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
        if (sequence.isNotEmpty() && now - sequenceLastAt > sequenceGap) resetSequence()
        // With the screen off, only volume changes tell what the keys do. Both keys pressed together
        // give a step and its opposite at (nearly) the same moment; a key held down gives steps faster
        // than anyone presses, repeated by the system.
        val blind = prefs.blindGestures && !power.isInteractive && source == VolumeWatcher.Source.POLL
        if (blind && sequence.size == 1 && direction == -sequence[0] && now - sequenceLastAt < BOTH_GAP_MS) {
            val base = sequenceBase
            resetSequence()
            rampUntil = now + RAMP_QUIET_MS
            log(getString(R.string.log_blind_both))
            fireGesture(Gesture.BOTH, restoreVolumeTo = base)
            return
        }
        val tooFast = sequence.isNotEmpty() && now - sequenceLastAt < HUMAN_MIN_GAP_MS &&
            source != VolumeWatcher.Source.BROADCAST
        if (blind && tooFast && sequence.all { it == direction }) {
            val base = sequenceBase
            resetSequence()
            startBlindHold(direction, base, now)
            return
        }
        if (tooFast || sequence.size >= 3) {
            resetSequence()
            rampUntil = now + RAMP_QUIET_MS
            log(getString(R.string.log_held))
            GestureTest.report(GestureTest.Event.Note(getString(R.string.test_held)))
            return
        }
        if (sequence.isEmpty()) {
            sequenceBase = previous
            sequenceGaps.clear()
            armPocketGuard()
        } else {
            sequenceGaps += (now - sequenceLastAt).toInt()
        }
        sequence += direction
        sequenceLastAt = now
        handler.removeCallbacks(sequenceFire)
        // Two presses with no three-press gesture that could still follow: no reason to wait for a third.
        // With the screen off, a moment more: a held key's repeats would follow right away.
        val wait = when {
            longerSequencePossible() -> CONFIRM_MS
            !power.isInteractive -> HOLD_CHECK_MS
            else -> 0L
        }
        if (sequence.size >= 2) handler.postDelayed(sequenceFire, wait)
    }

    /** A key held with the screen off: its hold gesture, without the volume running away meanwhile. */
    private fun startBlindHold(direction: Int, base: Int, now: Long) {
        val gesture = if (direction > 0) Gesture.HOLD_UP else Gesture.HOLD_DOWN
        val target = media.resolve(prefs)
        val action = prefs.action(gesture, target?.controller?.packageName)
        val volume = action == Action.VOLUME_UP && direction > 0 || action == Action.VOLUME_DOWN && direction < 0
        if (volume && !GestureTest.active) {
            // The hold changes the volume anyway: the system already does just that.
            rampUntil = now + RAMP_QUIET_MS
            return
        }
        blindHold = direction
        blindHoldBase = base
        blindHoldStepAt = now
        quietRestore(base)
        log(getString(R.string.log_blind_hold))
        val seek = action == Action.SEEK_FORWARD || action == Action.SEEK_BACK
        if (fireGesture(gesture, target?.controller) && seek) {
            blindSeek = action
            handler.postDelayed(blindSeekRepeat, SEEK_REPEAT_MS)
        }
        handler.removeCallbacks(blindHoldEnd)
        handler.postDelayed(blindHoldEnd, BLIND_RELEASE_MS)
    }

    private fun finishBlindHold() {
        if (blindHold == 0) return
        blindHold = 0
        handler.removeCallbacks(blindSeekRepeat)
        handler.removeCallbacks(blindHoldEnd)
        quietRestore(blindHoldBase)
        rampUntil = SystemClock.elapsedRealtime() + RAMP_QUIET_MS
    }

    /**
     * Puts the volume back without hiding the next steps (unlike [restoreVolume]): a held key keeps
     * stepping, and those steps are how its release is noticed.
     */
    private fun quietRestore(value: Int) {
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != value) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        lastVolume = value
    }

    // ---- Pocket mode ----

    /** The screen is off, or as good as off: pocket mode's black screen. */
    private fun screenDark(): Boolean = !power.isInteractive || ::pocket.isInitialized && pocket.active

    private fun onScreen(on: Boolean) {
        if (!on) onScreenOff()
        // Taps that run only with the screen off start and stop with it, not at the next check.
        if (prefs.backTap && prefs.backTapScreenOff) handler.post { updateSensors() }
        val now = SystemClock.elapsedRealtime()
        if (on) screenOnAt = now
        // Turned on or off by us (pocket mode, locking): not the power button.
        if (now - ownScreenChangeAt < OWN_SCREEN_CHANGE_MS) {
            screenTurnedAt = 0L
            return
        }
        if (screenTurnedAt != 0L && now - screenTurnedAt < POWER_DOUBLE_MS) {
            screenTurnedAt = 0L
            onPowerDouble(now)
        } else {
            screenTurnedAt = now
        }
    }

    /** The power button pressed twice: off and on again, or on and off again. */
    private fun onPowerDouble(now: Long) {
        if (prefs.action(Gesture.POWER_DOUBLE, prefs.lastPlayer) == Action.NONE && !GestureTest.active) return
        // The first press took the phone out of pocket mode; the gesture was meant, not leaving.
        val fromPocket = now - pocketPowerOffAt < POWER_DOUBLE_MS * 2
        log(getString(R.string.log_power_double))
        if (fireGesture(Gesture.POWER_DOUBLE) && fromPocket) enterPocket()
    }

    /**
     * The power button held, passed on by [AssistActivity]. Returns false when there was nothing to
     * do here, so the usual assistant opens instead.
     */
    private fun onPowerHold(): Boolean {
        val testing = GestureTest.active
        if (!testing && (!prefs.enabled || prefs.action(Gesture.POWER_HOLD, prefs.lastPlayer) == Action.NONE)) return false
        // The hold itself woke the phone: once the action is done, the screen can go off again.
        val wokeIt = SystemClock.elapsedRealtime() - screenOnAt < POWER_HOLD_WAKE_MS &&
            getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        log(getString(R.string.log_power_hold))
        val fired = fireGesture(Gesture.POWER_HOLD)
        if (fired && wokeIt && !pocket.active) handler.postDelayed({ lockScreen() }, RELOCK_DELAY_MS)
        return fired || testing
    }

    /** A button of the headphones: the play/pause key also arrives as separate "play" and "pause". */
    private fun onHeadsetKey(gesture: Gesture, key: Int) {
        if (gesture == Gesture.HS_PLAY_PAUSE) {
            val playing = media.musicActive()
            // The headphones can be behind: "pause" for what is already paused (taken out of the ear) is not a press.
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE && !playing || key == KeyEvent.KEYCODE_MEDIA_PLAY && playing) {
                log(getString(R.string.log_headset_same, KeyEvent.keyCodeToString(key)))
                return
            }
        }
        fireGesture(gesture, fromHeadset = true)
    }

    /** The headset's voice button, passed to KnobNext. */
    private fun onHeadsetHold(): Boolean {
        if (HeadsetLive.learning) {
            HeadsetLive.report(Gesture.HS_ASSIST, 0)
            return true
        }
        val testing = GestureTest.active
        if (!testing && (!prefs.enabled || !prefs.headsetHold || prefs.action(Gesture.HS_ASSIST, prefs.lastPlayer) == Action.NONE)) return false
        log(getString(R.string.log_headset_assist))
        return fireGesture(Gesture.HS_ASSIST, fromHeadset = true) || testing
    }

    private fun powerDoubleAssigned() = prefs.action(Gesture.POWER_DOUBLE, prefs.lastPlayer) != Action.NONE

    private fun onScreenOff() {
        forgetPresses()
        finishBlindHold()
        val now = SystemClock.elapsedRealtime()
        if (pocket.active) {
            // The power button in pocket mode: the screen goes off for real.
            pocketPowerOffAt = now
            pocket.hide(PocketScreen.Reason.POWER)
            return
        }
        val requested = pocketPending
        pocketPending = false
        // Just left (a second power press, or ended by silence): stay off.
        if (now - pocketLeftAt < POCKET_SETTLE_MS) return
        val auto = prefs.pocketAuto && prefs.enabled && media.musicActive() && !media.isInCall()
        if (requested || auto) {
            // A second press may follow (the power-button gesture): wait until it can't any more.
            val wait = if (!requested && powerDoubleAssigned()) POWER_DOUBLE_MS + POCKET_WAKE_DELAY_MS else POCKET_WAKE_DELAY_MS
            handler.postDelayed({ if (!power.isInteractive && !pocket.active) enterPocket() }, wait)
        }
    }

    private fun enterPocket(): Boolean {
        if (!power.isInteractive) ownScreenChangeAt = SystemClock.elapsedRealtime()
        if (!pocket.show()) {
            log(getString(R.string.log_pocket_failed))
            return false
        }
        pocketShownAt = SystemClock.elapsedRealtime()
        pocketQuietSince = 0L
        log(getString(R.string.log_pocket_on))
        handler.removeCallbacks(pocketCheck)
        handler.postDelayed(pocketCheck, POCKET_CHECK_MS)
        return true
    }

    /** Ends pocket mode on a call, after long silence, or when the screen could not be turned on. */
    private fun checkPocket() {
        if (!pocket.active) return
        val now = SystemClock.elapsedRealtime()
        when {
            // The system didn't let the screen turn on: don't leave a black window for the next time.
            !power.isInteractive && now - pocketShownAt < POCKET_SETTLE_MS -> return pocket.hide(PocketScreen.Reason.FAILED)
            media.isInCall() -> return pocket.hide(PocketScreen.Reason.CALL)
            media.musicActive() || fading -> pocketQuietSince = 0L
            pocketQuietSince == 0L -> pocketQuietSince = now
            now - pocketQuietSince > POCKET_IDLE_MS -> return pocket.hide(PocketScreen.Reason.IDLE)
        }
        handler.postDelayed(pocketCheck, POCKET_CHECK_MS)
    }

    private fun onPocketLeft(reason: PocketScreen.Reason) {
        pocketLeftAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(pocketCheck)
        if (reason == PocketScreen.Reason.STOPPED) return
        log(
            getString(
                when (reason) {
                    PocketScreen.Reason.TAP -> R.string.log_pocket_tap
                    PocketScreen.Reason.POWER -> R.string.log_pocket_power
                    PocketScreen.Reason.CALL -> R.string.log_pocket_call
                    PocketScreen.Reason.IDLE -> R.string.log_pocket_idle
                    PocketScreen.Reason.ACTION -> R.string.log_pocket_off
                    PocketScreen.Reason.FAILED, PocketScreen.Reason.STOPPED -> R.string.log_pocket_no_wake
                },
            ),
        )
        // Nothing to listen to any more, or turned off by a gesture: the screen goes off as usual.
        if (reason == PocketScreen.Reason.IDLE || reason == PocketScreen.Reason.ACTION) lockScreen()
    }

    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && power.isInteractive) {
            ownScreenChangeAt = SystemClock.elapsedRealtime()
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    // ---- Voice commands ----

    /** Listens for one spoken command; the same gesture again while listening cancels it. */
    private fun startVoice(): Outcome {
        if (voice.listening) {
            voice.cancel()
            return Outcome.unchanged(getString(R.string.log_voice_cancelled))
        }
        if (!voice.hasMicPermission()) {
            extras.say(getString(R.string.speak_voice_permission))
            return Outcome.failed(getString(R.string.voice_err_permission))
        }
        voice.listen(::onVoice)
        return Outcome.done(getString(R.string.log_voice_listening))
    }

    private fun onVoice(result: VoiceListener.Result) {
        when (result) {
            is VoiceListener.Result.Failed -> {
                log(getString(R.string.log_voice_failed, result.reason))
                playCue(Action.VOICE, Outcome.failed(result.reason), pausing = false, pkg = prefs.lastPlayer)
            }
            is VoiceListener.Result.Heard -> {
                val players = prefs.seenPlayers.associateBy { appLabel(this, it).lowercase() }
                val match = result.phrases.firstNotNullOfOrNull { p -> CommandRules.parse(p, players)?.let { p to it } }
                if (match != null) return runCommand(match.first, match.second)
                val heard = result.phrases.first()
                if (!prefs.smartVoice) return notUnderstood(heard)
                // Not one of the known phrases: the on-phone model may still make sense of it.
                scope.launch {
                    val brains = Brains.all(this@KnobService)
                    val answer = Brains.ask(this@KnobService, CommandBrain.prompt(heard, players.keys), BRAIN_COMMAND_TOKENS)
                    val command = answer?.let { CommandBrain.parse(it.second, players) }
                    when {
                        command != null -> runCommand(heard, command, via = answer?.first?.name)
                        brains.isNotEmpty() && answer == null -> {
                            log(getString(R.string.log_brain_failed, brains.joinToString { it.name }, NanoBrain.lastError ?: "-"))
                            notUnderstood(heard)
                        }
                        else -> notUnderstood(heard)
                    }
                }
            }
        }
    }

    private fun notUnderstood(heard: String) {
        log(getString(R.string.log_voice_unknown, heard))
        playCue(Action.VOICE, Outcome.failed(heard), pausing = false, pkg = prefs.lastPlayer)
        extras.say(getString(R.string.speak_voice_unknown, heard))
    }

    /** Carries out a spoken command, with the same log, vibration and sound as a gesture. */
    private fun runCommand(phrase: String, command: Command, via: String? = null) {
        val controller = media.resolve(prefs)?.controller ?: media.pick(media.controllers())
        val pkg = controller?.packageName
        val playing = controller?.isPlaying() ?: media.musicActive()
        var cueAction = Action.NONE
        var pausing = false
        val outcome: Outcome = when (command) {
            is Command.Run -> {
                cueAction = command.action
                pausing = command.action == Action.PLAY_PAUSE && playing
                if (command.action == Action.VOICE) Outcome.DONE else execute(command.action, controller)
            }
            Command.Play -> {
                cueAction = Action.PLAY_PAUSE
                resumedByUs()
                controller?.transportControls?.play() ?: media.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                Outcome.DONE
            }
            Command.Pause -> {
                cueAction = Action.PLAY_PAUSE
                pausing = true
                controller?.transportControls?.pause() ?: media.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                pausedByUs(pkg)
                Outcome.DONE
            }
            is Command.SeekBy -> {
                cueAction = if (command.ms > 0) Action.SEEK_FORWARD else Action.SEEK_BACK
                media.perform(cueAction, controller, kotlin.math.abs(command.ms))
                Outcome.DONE
            }
            is Command.SeekTo -> {
                cueAction = Action.SEEK_FORWARD
                if (controller == null) Outcome.failed(getString(R.string.log_needs_access)) else {
                    controller.transportControls.seekTo(command.ms)
                    Outcome.DONE
                }
            }
            is Command.VolumeBy -> {
                val direction = if (command.steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(kotlin.math.abs(command.steps).coerceAtMost(MAX_VOICE_VOLUME_STEPS)) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                }
                Outcome.DONE
            }
            is Command.VolumeTo -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * command.percent + 50) / 100, 0)
                lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                Outcome.DONE
            }
            is Command.Sleep -> {
                cueAction = Action.SLEEP_TIMER
                SleepTimer.start(this, prefs, command.minutes)
                extras.say(getString(R.string.speak_sleep_in, resources.getQuantityString(R.plurals.minutes, command.minutes, command.minutes)))
                Outcome.DONE
            }
            Command.SleepToTrackEnd -> {
                cueAction = Action.SLEEP_TIMER
                val progress = media.progress(controller)
                if (progress == null) Outcome.failed(getString(R.string.log_voice_no_length)) else {
                    SleepTimer.startIn(this, prefs, progress.second - progress.first)
                    extras.say(getString(R.string.speak_sleep_track_end))
                    Outcome.DONE
                }
            }
            Command.SleepCancel -> {
                cueAction = Action.SLEEP_TIMER
                SleepTimer.cancel(this, prefs)
                extras.say(getString(R.string.speak_sleep_cancelled))
                Outcome.undone(getString(R.string.log_sleep_cancelled))
            }
            is Command.Open -> {
                cueAction = Action.OPEN_PLAYER
                Outcome.of(extras.openApp(command.packageName))
            }
        }
        val failed = outcome.result == Outcome.Result.FAILED
        log(
            getString(R.string.log_voice_heard, phrase) + (via?.let { " ($it)" } ?: "") + " → " + describe(command) +
                (outcome.message?.let { ": $it" } ?: ""),
        )
        if (cueAction != Action.NONE && command !is Command.Run) {
            if (prefs.vibrate) haptics.play(cueAction)
        }
        if (cueAction != Action.NONE || failed) playCue(if (failed) Action.VOICE else cueAction, outcome, pausing, pkg)
    }

    private fun describe(command: Command): String = when (command) {
        is Command.Run -> getString(actionLabel(command.action))
        Command.Play -> getString(R.string.voice_cmd_play)
        Command.Pause -> getString(R.string.voice_cmd_pause)
        is Command.SeekBy -> getString(
            if (command.ms > 0) R.string.voice_cmd_seek_forward else R.string.voice_cmd_seek_back,
            kotlin.math.abs(command.ms) / 1000,
        )
        is Command.SeekTo -> getString(R.string.voice_cmd_seek_to, positionPhrase(command.ms))
        is Command.VolumeBy -> getString(R.string.voice_cmd_volume_by, command.steps)
        is Command.VolumeTo -> getString(R.string.voice_cmd_volume_to, command.percent)
        is Command.Sleep -> getString(R.string.voice_cmd_sleep, command.minutes)
        Command.SleepToTrackEnd -> getString(R.string.voice_cmd_sleep_end)
        Command.SleepCancel -> getString(R.string.voice_cmd_sleep_off)
        is Command.Open -> getString(R.string.voice_cmd_open, appLabel(this, command.packageName))
    }

    /** The pocket-mode action: on, or off again (the screen then goes off). */
    private fun togglePocket(): Outcome {
        if (pocket.active) {
            pocket.hide(PocketScreen.Reason.ACTION)
            return Outcome.undone(getString(R.string.log_pocket_off))
        }
        if (!power.isInteractive || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return if (enterPocket()) Outcome.DONE else Outcome.failed(getString(R.string.log_pocket_failed))
        }
        // The screen is on, perhaps unlocked: lock it first, so leaving the black screen needs the
        // unlock again, then come back black once it is off.
        pocketPending = true
        handler.postDelayed({ pocketPending = false }, POCKET_SETTLE_MS)
        pocketLeftAt = 0L
        ownScreenChangeAt = SystemClock.elapsedRealtime()
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        return Outcome.DONE
    }

    private fun longerSequencePossible(): Boolean {
        if (sequence.size >= 3) return true
        if (GestureTest.active) return true
        val longer = when (sequence) {
            listOf(1, 1) -> Gesture.TRIPLE_UP
            listOf(-1, -1) -> Gesture.TRIPLE_DOWN
            else -> return false
        }
        return prefs.action(longer, prefs.lastPlayer) != Action.NONE
    }

    /** Time allowed between presses of a sequence: learned from this person's own presses. */
    private fun learnedSequenceGap(): Long {
        val gaps = prefs.sequenceGaps
        if (gaps.size < MIN_GAP_SAMPLES) return SEQUENCE_GAP_MS
        val slow = gaps.sorted()[((gaps.size - 1) * 0.9).toInt()]
        return (slow * 1.6).toLong().coerceIn(SEQUENCE_GAP_MIN_MS, SEQUENCE_GAP_MAX_MS)
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
        val gaps = sequenceGaps.toList()
        resetSequence()
        gesture ?: return
        if (fireGesture(gesture, restoreVolumeTo = base)) {
            prefs.recordSequenceGaps(gaps)
            sequenceGap = learnedSequenceGap()
        }
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
        val playing = enabled && (media.musicActive() || testing)
        val now = SystemClock.elapsedRealtime()
        // Playing again some other way (the player itself, headphones): the window is no longer needed.
        if (media.musicActive() && pausedAt != 0L && now - pausedAt > PAUSE_SETTLE_MS) {
            pausedAt = 0L
            // Nor is the flip's: picking the phone up later must not start music paused by hand since.
            flipPausedAt = 0L
        }
        checkSilence()
        val resumable = enabled && !playing && resumeWindowOpen()

        val player = prefs.lastPlayer
        // While a track story holds the music paused, the gesture that tells it must still be heard,
        // or the story could not be stopped the way it was started.
        fun wanted(on: Boolean, vararg gestures: Gesture) =
            on && (playing ||
                storyHold && gestures.any { prefs.action(it, player) == Action.TRACK_INFO } ||
                resumable && gestures.any { prefs.action(it, player).worksWhenIdle })

        headset.update()
        val tap = wanted(prefs.backTap && (!prefs.backTapScreenOff || screenDark()), Gesture.BACK_TAP_2, Gesture.BACK_TAP_3)
        val turn = wanted(prefs.twist, Gesture.TWIST)
        backTapTriple = testing || prefs.action(Gesture.BACK_TAP_3, player) != Action.NONE
        // The background service is what keeps sensor readings coming with the screen off. If it is not
        // running though it should, start it again (not more often than every so often, and say so).
        val sensorsOn = tap || turn || flip.running || wave.running
        if (sensorsOn && !ScreenOffService.running && now - backgroundRetryAt > BACKGROUND_RETRY_MS && ScreenOffService.shouldRun(this)) {
            backgroundRetryAt = now
            log(getString(R.string.log_background_restart))
            ScreenOffService.sync(this, inForeground = true)
        }
        if (tap) {
            backTap.start(fastRate = !(prefs.backTapEco && screenDark()))
            // Sound plays, so the CPU is awake and the sensor should be reporting; if not, it needs registering again.
            if (playing && backTap.starved()) {
                backTap.restart()
                TapStats.note(TapStats.Verdict.RESTARTED)
                noteTap(TapStats.Verdict.RESTARTED)
            }
        } else {
            backTap.stop()
        }
        if (turn) twist.start() else twist.stop()
        if (wanted(prefs.wave, Gesture.WAVE)) wave.start() else wave.stop()
        // Taps and twists have no key press to arm the pocket check, so keep it on while they run
        // (unless the wave detector already watches the same sensor).
        proximity.keep(prefs.pocketGuard && (tap || turn) && !wave.running && !testing)
        val flipPending = flipPausedAt != 0L && now - flipPausedAt < FLIP_RESUME_WINDOW_MS
        if (enabled && prefs.flip && (playing || flipPending)) flip.start() else flip.stop()

        updateTrackWatcher()

        // The accelerometer and gyroscope don't wake the CPU. (The proximity sensor used for the wave
        // does.) Right after a pause, stay up until the sensors have been rearranged for the silence.
        val settling = pausedAt != 0L && now - pausedAt < PAUSE_SETTLE_MS * 2
        // Sound playing does not keep the CPU up either: on Pixel, music with the screen off is played by
        // the audio chip, and the processor sleeps between buffers. The accelerometer's readings then wait
        // in the sensor's own memory and are lost when it fills, so taps stopped being seen a while after
        // the screen went off. Whenever taps or the twist are watched, the CPU stays up.
        val keepAwake = flip.running && flipPending || tap || turn || settling || fading
        if (keepAwake) {
            // Not counted: acquiring again only moves the time limit, so it never runs out while needed.
            wakeLock.acquire(WAKE_LOCK_MAX_MS)
        } else if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    /**
     * Watches all players: keeps the remembered player (its profile decides which sensors run) in
     * step with the one that plays now, and speaks new tracks when that is turned on.
     */
    private fun updateTrackWatcher() {
        // Listens are written down whether or not the gestures are on.
        val scrobbling = prefs.lbScrobble && prefs.lbToken.isNotBlank()
        val want = (prefs.enabled || GestureTest.active || scrobbling) && media.hasAccess()
        if (want && trackWatcher == null) {
            announcedTrack = null
            trackWatcher = NowPlayingTracker(this, prefs, ::onTrack).also { it.start() }
        } else if (!want && trackWatcher != null) {
            trackWatcher?.stop()
            trackWatcher = null
            scrobbler.stop()
        }
    }

    /** A track changed (or just its state): speak it once, if it is new, playing and the screen is off. */
    private fun onTrack(now: NowPlaying?) {
        // Written down to ListenBrainz when so set, whatever player it is, gestures on or off.
        scrobbler.onTrack(now)
        // The rest is the gestures' (the watcher may run for the listens alone).
        if (!prefs.enabled && !GestureTest.active) return
        headset.mirror(now?.controller)
        // Playing again, whichever way: the silence that kept the keys alive is no longer needed.
        if (now?.playing == true && silence.playing) stopSilence(R.string.log_silence_resumed)
        val pkg = now?.controller?.packageName
        if (pkg != null && pkg != trackPlayer) {
            trackPlayer = pkg
            // Another app took over: its own gestures may want other sensors.
            handler.post { updateSensors() }
        }
        showBubbleFor(now)
        prefetchFor(now)
        if (!prefs.announceAllTracks) {
            announcedTrack = null
            return
        }
        val title = now?.title ?: return
        val key = now.controller.packageName + "/" + title
        if (key == announcedTrack) return
        val first = announcedTrack == null
        announcedTrack = key
        // The track already playing when the option was turned on is not news.
        if (first || !screenDark() || !now.playing || GestureTest.active) return
        extras.perform(Action.ANNOUNCE, now.controller)
    }

    /**
     * A new track playing: its round cover at the edge of the screen for a few seconds, when so set: for
     * every track, or only when there is something to find out (marked on the cover).
     */
    private fun showBubbleFor(now: NowPlaying?) {
        val auto = prefs.bubbleAuto
        if (auto == BubbleAuto.OFF || !prefs.enabled || GestureTest.active || now?.playing != true) return
        val title = now.title ?: return
        val key = now.controller.packageName + "/" + now.artist + "/" + title
        if (key == bubbledTrack) return
        bubbledTrack = key
        if (prefs.bubbleWhere == BubbleWhere.LOCKED && !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        // An open card is being read: the next track does not take it away.
        if (bubble.open) return
        val track = now.controller.metadata?.let { TrackInfo.trackOf(it, now.controller.packageName) } ?: TrackInfo.Track(title, now.artist, null, null)
        if (auto == BubbleAuto.EVERY) bubble.show(track, now.art, now.controller, expand = false)
        scope.launch {
            val news = newsOf(track) ?: return@launch
            // Moved on to another track meanwhile: this one is no longer news.
            if (bubbledTrack != key) return@launch
            if (auto == BubbleAuto.EVERY) {
                bubble.mark(track, news)
            } else if (!bubble.open) {
                bubble.show(track, now.art, now.controller, expand = false, mark = news)
            }
        }
    }

    /**
     * A new track playing: its facts fetched ahead, so that the card, the story or a question opens with
     * them at hand. On Wi-Fi only (on mobile data they are fetched when asked for); with the card showing
     * by itself they are fetched for it anyway.
     */
    private fun prefetchFor(now: NowPlaying?) {
        if (now?.playing != true || prefs.bubbleAuto != BubbleAuto.OFF) return
        val title = now.title ?: return
        val key = now.controller.packageName + "/" + now.artist + "/" + title
        if (key == prefetched) return
        prefetched = key
        val unmetered = runCatching { !getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)
        if (!unmetered) return
        val track = now.controller.metadata?.let { TrackInfo.trackOf(it, now.controller.packageName) } ?: return
        TrackInfo.prefetch(this, track, prefs.infoLanguage.resolve())
    }

    private var prefetched: String? = null

    /**
     * What there is to find out about [track]: its artist met for the first time (and something known
     * of them), or a story about this very song not yet told.
     */
    private suspend fun newsOf(track: TrackInfo.Track): BubbleNews? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val artist = track.artist?.takeIf { it.isNotBlank() } ?: return@withContext null
        val facts = runCatching { TrackInfo.facts(this@KnobService, track, prefs.infoLanguage.resolve()) }.getOrNull()
        if (facts == null || facts.empty && facts.intro.isEmpty()) return@withContext null
        // Marked as met only once there is something to tell: offline, they stay new for next time.
        if (Journal.firstMeeting(this@KnobService, artist)) return@withContext BubbleNews.NEW_ARTIST
        val told = Journal.entry(this@KnobService, Journal.key(artist))?.notes.orEmpty()
            .any { Speech.cleanTitle(it.track).equals(Speech.cleanTitle(track.title), ignoreCase = true) }
        if (facts.song.isNotEmpty() && !told) BubbleNews.STORY else null
    }

    /** A touch on the round cover or its card: done as a gesture would be, with its sound and log line. */
    private fun fromBubble(action: Action, controller: MediaController?): Outcome {
        val player = controller ?: media.resolve(prefs)?.controller
        return perform(getString(R.string.bubble_source), action, player)
    }

    /**
     * [action] done for [source] (a gesture's name, the round card) on [player]: play/pause decided as
     * Media.perform does (the player's state, or the audio when there is none), remembered for the pause
     * window, logged and given its sound.
     */
    private fun perform(source: String, action: Action, player: MediaController?): Outcome {
        val pausing = action == Action.PLAY_PAUSE && (player?.isPlaying() ?: media.musicActive())
        if (action == Action.PLAY_PAUSE && !pausing) resumedByUs()
        val outcome = execute(action, player)
        if (action == Action.PLAY_PAUSE && pausing) pausedByUs(player?.packageName)
        log(outcome.message?.let { "$source → ${getString(actionLabel(action))}: $it" }
            ?: getString(R.string.log_fired, source, getString(actionLabel(action))))
        playCue(action, outcome, pausing, player?.packageName)
        return outcome
    }

    /** Face down pauses; turning the phone back up resumes, but only playback that we paused. */
    private fun onFlip(faceDown: Boolean) {
        if (GestureTest.active) {
            if (prefs.vibrate) haptics.tick()
            return GestureTest.report(GestureTest.Event.Note(getString(if (faceDown) R.string.test_flip_down else R.string.test_flip_up)))
        }
        if (faceDown) {
            if (!media.musicActive()) return
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
    /** Returns whether the action actually ran (not blocked, not just checked). */
    private fun fireGesture(
        gesture: Gesture,
        controller: MediaController? = null,
        restoreVolumeTo: Int? = null,
        /** Only check and announce the start: the caller performs the action itself (smooth seeking). */
        startOnly: Boolean = false,
        /** The press came from headphones, which are not in a pocket whatever the proximity sensor says. */
        fromHeadset: Boolean = false,
    ): Boolean {
        val name = getString(gestureLabel(gesture))
        val target = media.resolve(prefs)
        val player = controller ?: target?.controller
        val action = prefs.action(gesture, player?.packageName)
        if (GestureTest.active) {
            // Only checking: undo the presses' volume change and show what would happen.
            restoreVolumeTo?.let(::restoreVolume)
            if (prefs.vibrate) haptics.tick()
            GestureTest.report(GestureTest.Event.Recognized(gesture, action))
            log(getString(R.string.log_test, name))
            return false
        }
        if (action == Action.NONE) {
            log(getString(R.string.log_no_action, name))
            return false
        }
        val tapGesture = gesture == Gesture.BACK_TAP_2 || gesture == Gesture.BACK_TAP_3
        if (target == null || (target.mode == Mode.COMBO_ONLY && !action.worksWhenIdle)) {
            if (tapGesture) TapStats.note(TapStats.Verdict.NOT_PLAYING)
            log(getString(R.string.log_not_playing, name))
            return false
        }
        if (!fromHeadset && pocketCovered()) {
            if (tapGesture) TapStats.note(TapStats.Verdict.POCKET)
            // The presses were accidental: undo their volume change too.
            restoreVolumeTo?.let(::restoreVolume)
            log(getString(R.string.log_pocket, name))
            return false
        }
        restoreVolumeTo?.let(::restoreVolume)
        if (action == Action.SLEEP_TIMER && fading) {
            // The music is already fading out: the gesture means "not yet".
            extendDuringFade(viaShake = false)
            return true
        }
        if (startOnly) {
            log(getString(R.string.log_fired, name, getString(actionLabel(action))))
            playCue(action, Outcome.DONE, pausing = false, pkg = player?.packageName)
            return true
        }
        perform(name, action, player)
        // With "every new track" on, the track watcher speaks anyway.
        val skipped = action == Action.NEXT || action == Action.PREVIOUS
        if (skipped && prefs.announceSkips && !prefs.announceAllTracks && screenDark()) {
            announceNewTrack(player)
        }
        return true
    }

    /** Says the new track's name once the player reports it (or after a short wait, whatever it is then). */
    /**
     * Says the new track's name once the player reports it and it settles (several skips in a row: only
     * the last one), or after a short wait, whatever it is then. One at a time: a new skip takes over.
     */
    private fun announceNewTrack(controller: MediaController?) {
        val c = controller ?: return
        pendingAnnounce?.invoke()
        val before = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        lateinit var callback: MediaController.Callback
        lateinit var finish: Runnable
        val drop = {
            handler.removeCallbacks(finish)
            c.unregisterCallback(callback)
            pendingAnnounce = null
        }
        finish = Runnable {
            drop()
            extras.perform(Action.ANNOUNCE, c)
        }
        callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
                if (title != before) {
                    handler.removeCallbacks(finish)
                    handler.postDelayed(finish, ANNOUNCE_SETTLE_MS)
                }
            }
        }
        pendingAnnounce = drop
        c.registerCallback(callback, handler)
        handler.postDelayed(finish, ANNOUNCE_WAIT_MS)
    }

    /** Takes back the announcement waiting for a new track (a newer skip, the service stopping). */
    private var pendingAnnounce: (() -> Unit)? = null

    /** Sleep timer: lower the volume step by step, pause, then put the volume back for next time. */
    private fun fadeOutAndPause() {
        if (fading) return
        val start = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        log(getString(R.string.log_sleep_fade))
        if (!media.musicActive() || start <= 0) {
            media.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            return
        }
        fading = true
        fadeVolume = start
        // Written down first: if the process dies mid-fade, the next start puts the volume back.
        prefs.fadeRestoreVolume = start
        stayAwake()
        // Heard the music fading and not asleep yet: a shake gives 15 more minutes.
        shake.start()
        val interval = SleepTimer.FADE_MS / start
        var level = start
        var silentSince = 0L
        val step = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                if (!media.musicActive()) {
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
        fadeStep = step
        handler.post(step)
    }

    /** "Not yet" while the music fades out: put the volume back and pause later instead. */
    private fun extendDuringFade(viaShake: Boolean) {
        if (!fading) return
        finishFade(fadeVolume)
        SleepTimer.startIn(this, prefs, SleepTimer.EXTEND_MINUTES * 60_000L)
        if (prefs.vibrate) haptics.play(Action.SLEEP_TIMER)
        playCue(Action.SLEEP_TIMER, Outcome.DONE, pausing = false, pkg = prefs.lastPlayer)
        val clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(prefs.sleepUntil))
        log(
            if (viaShake) getString(R.string.log_shake_extended, SleepTimer.EXTEND_MINUTES)
            else getString(R.string.log_sleep_extended, SleepTimer.EXTEND_MINUTES, clock),
        )
    }

    private fun finishFade(volume: Int) {
        fadeStep?.let { handler.removeCallbacks(it) }
        fadeStep = null
        shake.stop()
        restoreVolume(volume)
        prefs.fadeRestoreVolume = -1
        fading = false
    }

    /** Until the next sensor check decides; with the screen off the CPU could otherwise sleep before it. */
    /**
     * A gesture paused [pkg]: remember it (play goes back to it), open the resume window and, with
     * the screen off in mind, keep the volume keys alive with silence for that window.
     */
    private fun pausedByUs(pkg: String?) {
        pausedAt = SystemClock.elapsedRealtime()
        if (pkg != null) prefs.pausedPlayer = pkg
        stayAwake()
        if (prefs.keysOnPause && prefs.resumeMinutes > 0 && !silence.playing) {
            // Started a moment later: some players report "paused" only after their audio stopped.
            handler.postDelayed({
                val stillPlaying = media.controllers().any { it.isPlaying() }
                if (resumeWindowOpen() && !stillPlaying && !silence.playing && silence.start()) log(getString(R.string.log_silence_on))
            }, SILENCE_DELAY_MS)
        }
    }

    private fun resumedByUs() {
        pausedAt = 0L
        if (silence.playing) stopSilence(null)
    }

    private fun stopSilence(reason: Int?) {
        silence.stop()
        reason?.let { log(getString(it)) }
    }

    /** The silence lasts only for the resume window, and never alongside real playback. */
    private fun checkSilence() {
        if (!silence.playing || storyHold) return
        when {
            !resumeWindowOpen() -> stopSilence(R.string.log_silence_window)
            media.controllers().any { it.isPlaying() } -> stopSilence(R.string.log_silence_resumed)
            media.isInCall() -> stopSilence(null)
        }
    }

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

    // In pocket mode the phone is in the pocket on purpose.
    private fun pocketCovered(): Boolean =
        prefs.pocketGuard && !pocket.active && if (wave.running) wave.covered else proximity.isCovered()

    private fun execute(action: Action, controller: MediaController?): Outcome {
        val outcome = when (action) {
            Action.ANNOUNCE, Action.LIKE, Action.SHUFFLE, Action.REPEAT, Action.SLEEP_TIMER, Action.OPEN_PLAYER,
            Action.RESTART, Action.SWITCH_PLAYER, Action.FLASHLIGHT, Action.RINGER, Action.TIME_LEFT,
            Action.ASK_GEMINI, Action.TRACK_INFO, Action.PLAY_CIRCLE, Action.SHOW_BUBBLE ->
                extras.perform(action, controller)
            Action.POCKET -> togglePocket()
            Action.VOICE -> startVoice()
            else -> {
                media.perform(action, controller, prefs.seekStepMs(controller?.packageName))
                Outcome.DONE
            }
        }
        if (prefs.vibrate) haptics.play(action)
        return outcome
    }

    /** The sound for what just happened, if cues are on for it. */
    private fun playCue(action: Action, outcome: Outcome, pausing: Boolean, pkg: String?) {
        val style = prefs.cueStyle
        if (style == CueStyle.OFF || prefs.cueScreenOffOnly && !screenDark() || prefs.cuesOff(pkg)) return
        val group = CueGroup.of(action) ?: return
        val failed = outcome.result == Outcome.Result.FAILED
        // A failure has its own switch: someone may want to hear only that something didn't work.
        if ((if (failed) CueGroup.ERRORS else group) !in prefs.cueGroups) return
        val cue = when {
            failed -> Cue.ERROR
            outcome.result == Outcome.Result.UNCHANGED -> Cue.NEUTRAL
            outcome.result == Outcome.Result.UNDONE -> if (action == Action.LIKE) Cue.UNLIKE else Cue.NEUTRAL
            else -> when (action) {
                Action.NEXT -> Cue.FORWARD
                Action.PREVIOUS, Action.RESTART -> Cue.BACK
                Action.PLAY_PAUSE -> if (pausing) Cue.PAUSE else Cue.PLAY
                Action.SEEK_FORWARD -> Cue.SEEK_FORWARD
                Action.SEEK_BACK -> Cue.SEEK_BACK
                Action.LIKE -> Cue.LIKE
                Action.SLEEP_TIMER -> Cue.TIMER
                Action.OPEN_PLAYER -> Cue.NEUTRAL
                else -> Cue.TOGGLE
            }
        }
        cues.play(cue, style, prefs.cueVolume)
    }

    private fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
        val screen = getString(
            when {
                !power.isInteractive -> R.string.log_screen_off
                ::pocket.isInitialized && pocket.active -> R.string.log_screen_pocket
                else -> R.string.log_screen_on
            },
        )
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
        const val TAP_NOTE_MS = 10_000L
        const val BACKGROUND_RETRY_MS = 30_000L
        const val FLIP_RESUME_WINDOW_MS = 30 * 60 * 1000L
        const val WAKE_LOCK_MAX_MS = 60 * 60 * 1000L
        const val PAUSE_SETTLE_MS = 3_000L
        const val MAX_REPLAYED_STEPS = 4
        const val LOG_LINES = 30
        const val SEQUENCE_GAP_MS = 450L
        const val SEQUENCE_GAP_MIN_MS = 350L
        const val SEQUENCE_GAP_MAX_MS = 700L
        const val MIN_GAP_SAMPLES = 8
        const val SEEK_REPEAT_MS = 500L
        const val SEEK_FASTER_AFTER = 4
        const val SEEK_MAX_REPEATS = 40
        const val SCRUB_TICK_MS = 200L
        const val SCRUB_MAX_STEPS = 600
        const val SCRUB_END_MARGIN_MS = 1_500L
        const val SCRUB_DUCK_PERCENT = 40
        const val HUMAN_MIN_GAP_MS = 70L

        /** Opposite steps this close together with the screen off: both keys pressed at once. */
        const val BOTH_GAP_MS = 100L

        /** With the screen off, how long a double press waits to rule out a held key's repeats. */
        const val HOLD_CHECK_MS = 150L

        /** No step for this long: the held key was let go. */
        const val BLIND_RELEASE_MS = 350L
        const val POCKET_WAKE_DELAY_MS = 400L
        const val POCKET_CHECK_MS = 1_000L
        const val POCKET_SETTLE_MS = 2_000L
        const val POCKET_IDLE_MS = 2 * 60 * 1000L

        /** The screen turned off and on (or on and off) within this: the power button pressed twice. */
        const val POWER_DOUBLE_MS = 700L

        /** Screen changes this soon after we turned it on or off ourselves are ours. */
        const val OWN_SCREEN_CHANGE_MS = 1_500L

        /** The screen came on this shortly before the assistant was called: the hold turned it on. */
        const val POWER_HOLD_WAKE_MS = 2_500L
        const val MAX_VOICE_VOLUME_STEPS = 15
        const val SILENCE_DELAY_MS = 800L
        const val BRAIN_COMMAND_TOKENS = 16
        const val RELOCK_DELAY_MS = 400L
        const val CONFIRM_MS = 300L
        const val RAMP_QUIET_MS = 600L
        const val IGNORE_OWN_CHANGE_MS = 400L
        const val ANNOUNCE_WAIT_MS = 2_500L

        /** A new title is said once no other follows this soon (skipping through several). */
        const val ANNOUNCE_SETTLE_MS = 600L
        const val RESTORE_AFTER_FADE_MS = 1_000L
        const val FADE_SILENCE_MS = 4_000L
        const val FADE_SILENCE_CHECK_MS = 500L
    }
}
