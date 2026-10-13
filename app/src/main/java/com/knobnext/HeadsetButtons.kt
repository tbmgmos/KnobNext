package com.knobnext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Buttons of Bluetooth and wired headphones, which Android sends to the media session that was active
 * last, not to accessibility services. While headphones are connected KnobNext keeps a session of its own
 * that mirrors the player's state, so it is the one the buttons come to; each press is then either
 * turned into a gesture (which has an action assigned, the same as the press itself by default) or passed
 * on to the player untouched.
 *
 * Android does not simply give the buttons to the session that was active last: it gives them to the
 * session of the app that started playing sound last, which is always the player. So while music plays,
 * KnobNext plays inaudible silence of its own, started again just after the player (re)starts, so it is
 * the app that started last and the buttons come to it. Whenever any other sound starts (a player that
 * restarts its sound on the next track a little late, a video, a voice message), it does so again; and
 * when it starts (after an update, a call) a moment of silence is enough even with the music paused.
 *
 * What a button sends is up to the headphones: a tap is usually play/pause, two taps next, three
 * previous. Devices that send only play/pause can have the presses counted here (two or three in a row,
 * or a hold); waiting for a second press costs a short delay, so it is done only when one is assigned.
 */
class HeadsetButtons(
    private val context: Context,
    private val handler: Handler,
    private val prefs: Prefs,
    private val media: Media,
    private val log: (String) -> Unit,
    /** A press became a gesture; [key] is the key that caused it. */
    private val onGesture: (Gesture, key: Int) -> Unit,
) {
    private var session: MediaSession? = null
    private var mirrored: MediaController? = null
    private var holdApplied: Boolean? = null
    private var pushed: String? = null

    // The silence that makes KnobNext the app that played sound last (see above).
    private val silence = Silence()
    private var mirroredPlaying = false
    private var mirroredTitle: String? = null
    private val claim = Runnable { claimButtons() }
    private val endBurst = Runnable { if (mirrored?.isPlaying() != true) silence.stop() }
    private var lastClaimAt = 0L

    // Sound that starts while this is set is KnobNext's own silence, not someone taking the buttons.
    private var ownUntil = 0L
    private val audio = context.getSystemService(AudioManager::class.java)
    private var sounding: Set<AudioPlaybackConfiguration> = emptySet()
    private var ownSound: Set<AudioPlaybackConfiguration> = emptySet()
    private val soundWatch = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = onSound(configs)
    }

    /** Whether that silence plays now: the system then can't tell music from it. */
    val silencePlaying: Boolean get() = silence.playing

    val active: Boolean get() = session != null

    // Counting presses of the play/pause button.
    private var presses = 0
    private var lastKey = 0
    private var holdFired = false
    private var lastRepeatAt = 0L
    private var lastPixelAt = 0L
    private val resolve = Runnable { resolvePresses() }
    private val hold = Runnable {
        holdFired = true
        presses = 0
        handler.removeCallbacks(resolve)
        emit(Gesture.HS_PLAY_PAUSE_HOLD, lastKey)
    }

    /**
     * Keeps the session in step with what is wanted: there while headphones are connected (and their
     * buttons are used), or while Pixel's Quick Tap is caught, gone otherwise.
     */
    fun update() {
        val idle = prefs.enabled && !media.isInCall()
        val device = if (idle && prefs.headsetButtons) media.externalOutput() else null
        val pixel = idle && prefs.pixelTap
        if ((device != null || pixel) && session == null) {
            open(device?.productName?.toString().orEmpty(), headset = device != null)
        } else if (device == null && !pixel && session != null) {
            close()
        }
        applyHold(prefs.headsetHold)
    }

    /** The player that plays now; its state and track are copied to the session. */
    fun mirror(controller: MediaController?) {
        // Never itself: copying its own state would report a change, which would be copied again.
        if (controller?.packageName == context.packageName) return
        mirrored = controller
        push()
        val playing = controller?.isPlaying() == true
        val title = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (playing && (!mirroredPlaying || title != mirroredTitle)) {
            // The player has just (re)started its sound: start ours after it.
            handler.removeCallbacks(claim)
            handler.postDelayed(claim, CLAIM_DELAY_MS)
        } else if (!playing && mirroredPlaying) {
            // Paused: no silence for nothing. The buttons stay with KnobNext, which played last.
            handler.removeCallbacks(endBurst)
            silence.stop()
        }
        mirroredPlaying = playing
        mirroredTitle = title
    }

    /**
     * Plays the silence anew, so KnobNext is the app that started sound last and gets the buttons: on and
     * on while music plays, a moment when it is paused (being the last to have played is what counts).
     */
    private fun claimButtons() {
        if (session == null) return
        val now = SystemClock.uptimeMillis()
        // Not more often than this: an app doing the same must not start a tug of war.
        val wait = lastClaimAt + MIN_CLAIM_GAP_MS - now
        if (wait > 0) {
            handler.removeCallbacks(claim)
            handler.postDelayed(claim, wait)
            return
        }
        lastClaimAt = now
        ownUntil = now + OWN_SOUND_MS
        handler.removeCallbacks(endBurst)
        silence.stop()
        silence.start()
        if (mirrored?.isPlaying() != true) handler.postDelayed(endBurst, BURST_MS)
    }

    /** Some sound started or stopped: one that is not KnobNext's own takes the buttons, so they are claimed back. */
    private fun onSound(configs: List<AudioPlaybackConfiguration>) {
        val now = configs.toSet()
        val started = now - sounding
        sounding = now
        if (started.isEmpty() || session == null) return
        if (SystemClock.uptimeMillis() < ownUntil) {
            // Our silence (it can stop and start again in one go, so it is remembered as ours).
            ownSound = (ownSound intersect now) + started
            return
        }
        // Only a player's sound can take the buttons (an app plays media with a session); a notification
        // or a spoken prompt can't, and KnobNext's own stories are spoken.
        if (started.none { it !in ownSound && it.audioAttributes.usage in MEDIA_USAGES }) return
        log(context.getString(R.string.log_buttons_reclaim))
        handler.removeCallbacks(claim)
        handler.postDelayed(claim, CLAIM_DELAY_MS)
    }

    fun release() {
        close()
    }

    // ---- The session ----

    private fun open(device: String, headset: Boolean) {
        val s = MediaSession(context, SESSION_TAG)
        s.setCallback(callback, handler)
        s.isActive = true
        session = s
        pushed = null
        push()
        // Started anew (after an update, a call): the player played last, so the buttons are claimed at
        // once, music playing or not, rather than only when it next starts.
        handler.postDelayed(claim, CLAIM_DELAY_MS)
        sounding = runCatching { audio.activePlaybackConfigurations.toSet() }.getOrDefault(emptySet())
        runCatching { audio.registerAudioPlaybackCallback(soundWatch, handler) }
        if (!headset) return log(context.getString(R.string.log_pixel_tap_on))
        HeadsetLive.setDevice(device.ifBlank { "?" })
        if (device.isNotBlank() && prefs.lastHeadset != device) prefs.lastHeadset = device
        log(context.getString(R.string.log_headset_on, device.ifBlank { "?" }))
    }

    private fun close() {
        if (session == null) return
        handler.removeCallbacks(resolve)
        handler.removeCallbacks(hold)
        handler.removeCallbacks(claim)
        handler.removeCallbacks(endBurst)
        runCatching { audio.unregisterAudioPlaybackCallback(soundWatch) }
        sounding = emptySet()
        ownSound = emptySet()
        silence.stop()
        presses = 0
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        HeadsetLive.setDevice(null)
        log(context.getString(R.string.log_headset_off))
    }

    private fun push() {
        val s = session ?: return
        val player = mirrored
        val state = player?.playbackState
        val meta = player?.metadata
        // Only what changed: every update goes through the system to all who watch the session.
        val key = listOf(
            state?.state, state?.position, state?.lastPositionUpdateTime, state?.playbackSpeed, state?.actions,
            meta?.getString(MediaMetadata.METADATA_KEY_TITLE), meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
        ).joinToString("\u0000")
        if (key == pushed) return
        pushed = key
        // Plain values only: the player's extras and custom actions may hold its own classes, which this
        // app can't unpack when they are sent on.
        val builder = PlaybackState.Builder()
        if (state != null) {
            builder.setState(state.state, state.position, state.playbackSpeed, state.lastPositionUpdateTime)
            builder.setBufferedPosition(state.bufferedPosition)
        } else {
            builder.setState(PlaybackState.STATE_PAUSED, 0, 0f)
        }
        // Whatever the player can do, the headphones are allowed to ask: the press is passed on.
        builder.setActions((state?.actions ?: 0L) or ACTIONS)
        // Text only: pictures would be copied through the system for nothing.
        val copy = MediaMetadata.Builder().apply {
            for (k in TEXT_KEYS) meta?.getString(k)?.let { putString(k, it) }
            meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
        }.build()
        // A failed copy only means the headphones show less; it must not take the service down.
        runCatching {
            s.setPlaybackState(builder.build())
            s.setMetadata(copy)
        }.onFailure { log(context.getString(R.string.log_headset_error, it.javaClass.simpleName)) }
    }

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = if (android.os.Build.VERSION.SDK_INT >= 33) {
                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            } ?: return false
            return onKey(event)
        }

        // The same buttons, when the sender uses the session's own controls instead of key events.
        override fun onPlay() = press(KeyEvent.KEYCODE_MEDIA_PLAY)
        override fun onPause() = press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        override fun onSkipToNext() = press(KeyEvent.KEYCODE_MEDIA_NEXT)
        override fun onSkipToPrevious() = press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        override fun onFastForward() = press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        override fun onRewind() = press(KeyEvent.KEYCODE_MEDIA_REWIND)
        override fun onStop() = press(KeyEvent.KEYCODE_MEDIA_STOP)
    }

    private fun press(code: Int) {
        onKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        onKey(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---- Presses ----

    private fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        // A call has its own use of the headset button.
        if (media.isInCall()) return false
        // KnobNext sent this key itself (no player to ask): it must not become a gesture again.
        if (Media.justSent(code)) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        // Who sent the key: Quick Tap's come from the system's interface, the headphones' through Bluetooth.
        val sender = if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { session?.currentControllerInfo?.packageName }.getOrNull() else null
        if (down && event.repeatCount == 0) {
            log(context.getString(R.string.log_headset_key, KeyEvent.keyCodeToString(code), sender ?: "?"))
        }
        val headphones = media.externalOutput() != null
        val playKey = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
        // No headphones: a play/pause key can only be Quick Tap's. With headphones, it is Quick Tap's when
        // the system's interface sent it; else the headphones' (or, if their buttons are not used here, the player's).
        if (prefs.pixelTap && playKey && (!headphones || fromQuickTap(sender))) {
            pixelTap(event)
            return true
        }
        if (!prefs.headsetButtons) {
            passThrough(event)
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> playButton(event)
            KeyEvent.KEYCODE_MEDIA_NEXT -> single(event, Gesture.HS_NEXT)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> single(event, Gesture.HS_PREVIOUS)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> single(event, Gesture.HS_FORWARD)
            KeyEvent.KEYCODE_MEDIA_REWIND -> single(event, Gesture.HS_REWIND)
            else -> passOn(event)
        }
        return true
    }

    /** While the page shows presses, every kind is told apart, assigned or not. */
    private fun mapped(g: Gesture) = HeadsetLive.learning || prefs.action(g, prefs.lastPlayer) != Action.NONE

    /** The press is acted on, or, while the headphones page is open, only shown there. */
    private fun emit(gesture: Gesture, key: Int) {
        if (HeadsetLive.learning) HeadsetLive.report(gesture, key) else onGesture(gesture, key)
    }

    /** The package that sent a key is Pixel's own interface, which is where Quick Tap lives. */
    private fun fromQuickTap(sender: String?): Boolean =
        sender != null && (sender in QUICK_TAP_SENDERS || "columbus" in sender)

    /** Quick Tap's "play/pause media": one press is one gesture, whichever way the system sends it. */
    private fun pixelTap(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        val now = event.eventTime
        if (now - lastPixelAt < PIXEL_DEBOUNCE_MS) return
        lastPixelAt = now
        onGesture(Gesture.PIXEL_TAP, event.keyCode)
    }

    /** The key goes to the player as it came: its buttons are not used here. */
    private fun passThrough(event: KeyEvent) {
        val player = media.pick(media.controllers(), prefs.pausedPlayer) ?: return
        player.dispatchMediaButtonEvent(event)
    }

    /** A key with one meaning: acts when pressed; held down, only seeking repeats. */
    private fun single(event: KeyEvent, gesture: Gesture) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (event.repeatCount > 0) {
            val action = prefs.action(gesture, prefs.lastPlayer)
            val seek = action == Action.SEEK_FORWARD || action == Action.SEEK_BACK
            val now = event.eventTime
            if (!seek || now - lastRepeatAt < REPEAT_MS) return
            lastRepeatAt = now
        }
        emit(gesture, event.keyCode)
    }

    private fun playButton(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return
            lastKey = event.keyCode
            holdFired = false
            handler.removeCallbacks(hold)
            if (mapped(Gesture.HS_PLAY_PAUSE_HOLD)) handler.postDelayed(hold, HOLD_MS)
            return
        }
        handler.removeCallbacks(hold)
        if (holdFired) {
            holdFired = false
            return
        }
        presses++
        handler.removeCallbacks(resolve)
        val more = presses == 1 && (mapped(Gesture.HS_PLAY_PAUSE_DOUBLE) || mapped(Gesture.HS_PLAY_PAUSE_TRIPLE)) ||
            presses == 2 && mapped(Gesture.HS_PLAY_PAUSE_TRIPLE)
        if (more) handler.postDelayed(resolve, MULTI_MS) else resolvePresses()
    }

    private fun resolvePresses() {
        val n = presses
        presses = 0
        if (n == 0) return
        // Presses with nothing assigned mean what Android means by them: two next, three previous.
        val gesture = when {
            n == 1 -> Gesture.HS_PLAY_PAUSE
            n == 2 -> if (mapped(Gesture.HS_PLAY_PAUSE_DOUBLE)) Gesture.HS_PLAY_PAUSE_DOUBLE else Gesture.HS_NEXT
            else -> if (mapped(Gesture.HS_PLAY_PAUSE_TRIPLE)) Gesture.HS_PLAY_PAUSE_TRIPLE else Gesture.HS_PREVIOUS
        }
        emit(gesture, lastKey)
    }

    /** A key KnobNext has no gesture for: the player gets it as it came. */
    private fun passOn(event: KeyEvent) {
        if (event.keyCode !in PASS_ON_KEYS) return
        val player = media.pick(media.controllers(), prefs.pausedPlayer) ?: return
        player.dispatchMediaButtonEvent(event)
    }

    // ---- The headset's assistant button ----

    /** Lets the system hand the headset's voice button to KnobNext (or takes that back). */
    private fun applyHold(on: Boolean) {
        if (holdApplied == on) return
        holdApplied = on
        HeadsetHold.enable(context, on)
    }

    private companion object {
        const val SESSION_TAG = "KnobNext"
        const val MULTI_MS = 350L
        const val HOLD_MS = 600L
        const val REPEAT_MS = 300L
        const val PIXEL_DEBOUNCE_MS = 400L
        const val CLAIM_DELAY_MS = 400L

        /** Where Quick Tap sends its key from: Pixel's system interface. */
        val QUICK_TAP_SENDERS = setOf("com.android.systemui", "com.google.android.systemui")

        /** The shortest time between two claims of the buttons. */
        const val MIN_CLAIM_GAP_MS = 2_000L

        /** How long sound that starts is taken for KnobNext's own silence after it starts it. */
        const val OWN_SOUND_MS = 600L

        /** The moment of silence played to claim the buttons while the music is paused. */
        const val BURST_MS = 300L

        val MEDIA_USAGES = setOf(
            android.media.AudioAttributes.USAGE_MEDIA, android.media.AudioAttributes.USAGE_GAME,
            android.media.AudioAttributes.USAGE_UNKNOWN,
        )
        val PASS_ON_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_MEDIA_CLOSE,
            KeyEvent.KEYCODE_MEDIA_EJECT, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
        )
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
        val TEXT_KEYS = listOf(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        )
    }
}

/** The headset's voice-assistant button: an activity that exists for the system only while this is on. */
object HeadsetHold {
    /** Set by the service while it runs: true when the press was used. */
    @Volatile var handler: (() -> Boolean)? = null

    /**
     * Only when the state really changes: every change is announced to the whole system, and the services
     * of the app (player access among them) are bound again because of it.
     */
    fun enable(context: Context, on: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context, HeadsetHoldActivity::class.java)
        try {
            // "Default" is what the manifest says: off.
            val isOn = pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (isOn == on) return
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {
        }
    }
}
