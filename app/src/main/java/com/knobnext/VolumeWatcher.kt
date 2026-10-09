package com.knobnext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler

/**
 * Reports music volume values from two sources, because neither is reliable on every device:
 *  - the system VOLUME_CHANGED_ACTION broadcast;
 *  - the active players' media sessions (needs notification access). With the screen off the system
 *    routes volume keys through the playing session, which then reports the new volume to controllers.
 * Callers de-duplicate by value, so the same step reported by both sources counts once.
 */
class VolumeWatcher(
    private val context: Context,
    private val media: Media,
    private val handler: Handler,
    private val onVolume: (value: Int, source: Source) -> Unit,
) {
    enum class Source { BROADCAST, SESSION }

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val watched = mutableListOf<MediaController>()
    private var watchingSessions = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            if (value >= 0) onVolume(value, Source.BROADCAST)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        follow(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
            if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
                onVolume(info.currentVolume, Source.SESSION)
            }
        }
    }

    fun start() {
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        ensureSessions()
    }

    /** Notification access may be granted after the service started; cheap to call often. */
    fun ensureSessions() {
        if (watchingSessions || !media.hasAccess()) return
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, media.listenerComponent, handler)
            watchingSessions = true
            follow(media.controllers())
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        if (watchingSessions) sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        watched.forEach { it.unregisterCallback(callback) }
        watched.clear()
    }

    private fun follow(controllers: List<MediaController>) {
        watched.forEach { it.unregisterCallback(callback) }
        watched.clear()
        controllers.forEach {
            it.registerCallback(callback, handler)
            watched += it
        }
    }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }
}
