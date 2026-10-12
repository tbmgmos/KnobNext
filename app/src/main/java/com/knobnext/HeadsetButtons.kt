package com.knobnext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.view.KeyEvent

/**
 * Buttons of Bluetooth and wired headphones, which Android sends to the media session that was active
 * last, not to accessibility services. While headphones are connected KnobNext keeps a session of its own
 * that mirrors the player's state, so it is the one the buttons come to; each press is then either
 * turned into a gesture (which has an action assigned, the same as the press itself by default) or passed
 * on to the player untouched.
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

    val active: Boolean get() = session != null

    // Counting presses of the play/pause button.
    private var presses = 0
    private var lastKey = 0
    private var holdFired = false
    private var lastRepeatAt = 0L
    private val resolve = Runnable { resolvePresses() }
    private val hold = Runnable {
        holdFired = true
        presses = 0
        handler.removeCallbacks(resolve)
        onGesture(Gesture.HS_PLAY_PAUSE_HOLD, lastKey)
    }

    /** Keeps the session in step with the headphones: there while they are connected, gone otherwise. */
    fun update() {
        val device = if (prefs.enabled && prefs.headsetButtons && !media.isInCall()) media.externalOutput() else null
        if (device != null && session == null) {
            open(device.productName?.toString().orEmpty())
        } else if (device == null && session != null) {
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
    }

    fun release() {
        close()
    }

    // ---- The session ----

    private fun open(device: String) {
        val s = MediaSession(context, SESSION_TAG)
        s.setCallback(callback, handler)
        s.isActive = true
        session = s
        pushed = null
        push()
        log(context.getString(R.string.log_headset_on, device.ifBlank { "?" }))
    }

    private fun close() {
        if (session == null) return
        handler.removeCallbacks(resolve)
        handler.removeCallbacks(hold)
        presses = 0
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
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
        if (down && event.repeatCount == 0) {
            log(context.getString(R.string.log_headset_key, KeyEvent.keyCodeToString(code)))
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

    private fun mapped(g: Gesture) = prefs.action(g, prefs.lastPlayer) != Action.NONE

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
        onGesture(gesture, event.keyCode)
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
        onGesture(gesture, lastKey)
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
