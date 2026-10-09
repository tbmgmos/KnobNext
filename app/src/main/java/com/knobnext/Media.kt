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

/** Whom a gesture should control, or null when gestures must not be intercepted right now. */
class Target(val controller: MediaController?)

class Media(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    val listenerComponent = ComponentName(context, KnobNotificationListener::class.java)

    fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == listenerComponent }
    }

    fun controllers(): List<MediaController> =
        if (!hasAccess()) {
            emptyList()
        } else {
            try {
                sessions.getActiveSessions(listenerComponent)
            } catch (_: SecurityException) {
                emptyList()
            }
        }

    /** Active sessions are ordered by priority; prefer one that is actually playing. */
    fun pick(list: List<MediaController>): MediaController? = list.firstOrNull { it.isPlaying() } ?: list.firstOrNull()

    fun isInCall(): Boolean = when (audio.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    fun headphonesConnected(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }

    /**
     * Decides whether gestures should be handled now and which player they go to.
     * [lastCommandAt] is used only without notification access, to allow resuming a player we paused.
     */
    fun resolve(prefs: Prefs, lastCommandAt: Long): Target? {
        if (!prefs.enabled || isInCall()) return null
        if (prefs.headphonesOnly && !headphonesConnected()) return null

        if (!hasAccess()) {
            if (!prefs.onlyWhenPlaying) return Target(null)
            val recent = SystemClock.elapsedRealtime() - lastCommandAt < RESUME_WINDOW_MS
            return if (audio.isMusicActive || recent) Target(null) else null
        }

        val all = controllers()
        all.forEach { prefs.rememberPlayer(it.packageName) }
        val allowed = if (prefs.appFilter) all.filter { it.packageName in prefs.allowedApps } else all
        val controller = pick(allowed)

        if (prefs.appFilter && controller == null) return null
        if (!prefs.onlyWhenPlaying) return Target(controller)
        if (controller == null) return if (audio.isMusicActive) Target(null) else null
        return if (controller.isPlaying() || controller.recentlyPaused()) Target(controller) else null
    }

    fun perform(action: Action, controller: MediaController?) {
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
            Action.SEEK_FORWARD -> seekBy(controller, SEEK_STEP_MS) ?: sendKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            Action.SEEK_BACK -> seekBy(controller, -SEEK_STEP_MS) ?: sendKey(KeyEvent.KEYCODE_MEDIA_REWIND)
        }
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

    private fun sendKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    companion object {
        const val SEEK_STEP_MS = 10_000L
        const val RESUME_WINDOW_MS = 5 * 60 * 1000L

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

/** Paused recently (by anyone): gestures stay active so playback can be resumed the same way. */
fun MediaController.recentlyPaused(): Boolean {
    val state = playbackState ?: return false
    return state.state == PlaybackState.STATE_PAUSED &&
        SystemClock.elapsedRealtime() - state.lastPositionUpdateTime < Media.RESUME_WINDOW_MS
}
