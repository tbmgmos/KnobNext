package com.knobnext

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

enum class Mode {
    /** Something is playing: holds and the combo are intercepted. */
    FULL,

    /** Nothing is playing: keys behave normally, only "both keys together" is caught (to resume). */
    COMBO_ONLY,
}

/** Whom a gesture should control and how much to intercept; null means "don't touch the keys". */
class Target(val controller: MediaController?, val mode: Mode)

class Media(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    val listenerComponent = ComponentName(context, KnobNotificationListener::class.java)

    fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == listenerComponent }
    }

    /** Drops KnobNext's own session from a list the system gave: it only mirrors the real player. */
    fun players(list: List<MediaController>): List<MediaController> = list.filter { it.packageName != context.packageName }

    /** Every player's session; KnobNext's own (the one that catches headphone buttons) is not a player. */
    fun controllers(): List<MediaController> =
        if (!hasAccess()) {
            emptyList()
        } else {
            try {
                players(sessions.getActiveSessions(listenerComponent))
            } catch (_: SecurityException) {
                emptyList()
            }
        }

    /**
     * The session that plays, in the system's priority order (the one that started playing last comes
     * first). When none plays: [paused], the player a gesture paused, so play goes back to it and not
     * to some app that merely showed a video preview; otherwise the system's first.
     */
    fun pick(list: List<MediaController>, paused: String? = null): MediaController? =
        list.firstOrNull { it.isPlaying() }
            ?: paused?.let { p -> list.firstOrNull { it.packageName == p } }
            ?: list.firstOrNull()

    /** Set by the service: true while it plays silence to keep the keys working on pause. */
    var silencePlaying: () -> Boolean = { false }

    /**
     * Music is audible, not counting KnobNext's own silence. While that plays, the system can't tell
     * the two apart, so the players' own state decides.
     */
    fun musicActive(): Boolean = if (!silencePlaying()) audio.isMusicActive else controllers().any { it.isPlaying() }

    fun isInCall(): Boolean = when (audio.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    fun headphonesConnected(): Boolean = externalOutput() != null

    /** Headphones or a speaker the sound goes to besides the phone's own. */
    fun externalOutput(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADPHONE_TYPES }

    /** Decides whether gestures should be handled now and which player they go to. */
    fun resolve(prefs: Prefs): Target? {
        if (!prefs.enabled || isInCall()) return null
        if (prefs.headphonesOnly && !headphonesConnected()) return null

        val all = controllers()
        all.forEach { prefs.rememberPlayer(it.packageName) }
        val filtering = prefs.appFilter && hasAccess()
        val controller = pick(if (filtering) all.filter { it.packageName in prefs.allowedApps } else all, prefs.pausedPlayer)
        if (filtering && controller == null) return null
        controller?.packageName?.let { if (it != prefs.lastPlayer) prefs.lastPlayer = it }

        // Sound must be audible, and it must be the player's: a game or a video without a media session
        // also makes sound, and then a hold would skip (and start) a paused player. Without player
        // access there is no player to ask, so the sound alone decides.
        val playing = musicActive() && (controller?.isPlaying() ?: !filtering)
        val mode = if (playing || !prefs.onlyWhenPlaying) Mode.FULL else Mode.COMBO_ONLY
        return Target(controller, mode)
    }

    fun perform(action: Action, controller: MediaController?, seekMs: Long = SEEK_STEP_MS) {
        val controls = controller?.transportControls
        when (action) {
            Action.NONE -> Unit
            Action.NEXT -> controls?.skipToNext() ?: sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            Action.PREVIOUS -> controls?.skipToPrevious() ?: sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Action.PLAY_PAUSE -> when {
                controller == null -> sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                controller.isPlaying() -> controller.transportControls.pause()
                else -> controller.transportControls.play()
            }
            Action.SEEK_FORWARD -> seekBy(controller, seekMs) ?: sendKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            Action.SEEK_BACK -> seekBy(controller, -seekMs) ?: sendKey(KeyEvent.KEYCODE_MEDIA_REWIND)
            Action.VOLUME_UP -> volumeStep(AudioManager.ADJUST_RAISE)
            Action.VOLUME_DOWN -> volumeStep(AudioManager.ADJUST_LOWER)
            // Handled by Extras: they need the service (speech, alarms, launching apps).
            Action.ANNOUNCE, Action.LIKE, Action.SHUFFLE, Action.REPEAT, Action.SLEEP_TIMER, Action.OPEN_PLAYER,
            Action.RESTART, Action.SWITCH_PLAYER, Action.FLASHLIGHT, Action.RINGER, Action.TIME_LEFT, Action.POCKET, Action.VOICE,
            Action.ASK_GEMINI, Action.TRACK_INFO, Action.PLAY_CIRCLE -> Unit
        }
    }

    /** Current position and duration in ms, or null when the player doesn't report them. */
    fun progress(controller: MediaController?): Pair<Long, Long>? {
        val state = controller?.playbackState ?: return null
        val duration = controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0) return null
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        return position.coerceIn(0, duration) to duration
    }

    private fun seekBy(controller: MediaController?, deltaMs: Long): Unit? {
        val state = controller?.playbackState ?: return null
        if (state.actions and PlaybackState.ACTION_SEEK_TO == 0L) return null
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        var target = (position + deltaMs).coerceAtLeast(0)
        val duration = controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration > 0) target = target.coerceAtMost(duration - 1000)
        controller.transportControls.seekTo(target)
        return Unit
    }

    /** [showUi] false keeps the volume panel away (pocket mode: the screen stays black). */
    fun volumeStep(direction: Int, showUi: Boolean = true) {
        audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
    }

    fun sendKey(code: Int) {
        // The key comes back to KnobNext if its session is the one that catches media buttons.
        sentCode = code
        sentAt = SystemClock.elapsedRealtime()
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    companion object {
        const val SEEK_STEP_MS = 10_000L

        @Volatile private var sentCode = 0
        @Volatile private var sentAt = 0L

        /** Whether this key is the one [sendKey] sent a moment ago, coming back. */
        fun justSent(code: Int): Boolean = code == sentCode && SystemClock.elapsedRealtime() - sentAt < ECHO_MS

        private const val ECHO_MS = 500L

        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
    }
}

fun MediaController.isPlaying(): Boolean = when (playbackState?.state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
    else -> false
}
