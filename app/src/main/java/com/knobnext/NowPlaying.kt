package com.knobnext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

data class NowPlaying(
    val controller: MediaController,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val art: Bitmap?,
    val playing: Boolean,
)

/**
 * Follows the most relevant media session and reports changes on the main thread.
 *
 * Every active session is watched, not only the one shown: when another app starts playing, the
 * system doesn't always report a change of the session list (the session may have been there,
 * paused, all along), so only its own playback callback tells that it took over.
 */
class NowPlayingTracker(
    private val context: Context,
    private val prefs: Prefs,
    private val onChange: (NowPlaying?) -> Unit,
) {
    private val media = Media(context)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var current: MediaController? = null
    private val watched = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var started = false

    // The system's list includes KnobNext's own session (the one that catches headphone buttons). Taken
    // for the player, it would mirror itself, and every copy would report a change that triggers the next.
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        refresh(media.players(list.orEmpty()))
    }

    private val refreshSoon = Runnable { refresh(media.controllers()) }

    fun start() {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, media.listenerComponent, handler)
        } catch (_: SecurityException) {
            onChange(null)
            return
        }
        started = true
        refresh(media.controllers())
    }

    fun stop() {
        started = false
        handler.removeCallbacks(refreshSoon)
        sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        watched.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        current = null
    }

    private fun watch(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { token ->
            watched.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        list.forEach { c ->
            if (c.sessionToken in watched) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (c.sessionToken == current?.sessionToken) publish()
                    // Playing or paused anywhere may change which player comes first.
                    later()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (c.sessionToken == current?.sessionToken) publish()
                }

                override fun onSessionDestroyed() = later()
            }
            c.registerCallback(cb, handler)
            watched[c.sessionToken] = c to cb
        }
    }

    /** Another session changed: re-pick after the burst of callbacks a switch brings settles. */
    private fun later() {
        handler.removeCallbacks(refreshSoon)
        handler.postDelayed(refreshSoon, REPICK_DELAY_MS)
    }

    private fun refresh(list: List<MediaController>) {
        if (!started) return
        handler.removeCallbacks(refreshSoon)
        watch(list)
        val best = media.pick(list, prefs.pausedPlayer)
        if (best?.sessionToken != current?.sessionToken) current = best
        publish()
    }

    private fun publish() {
        val c = current
        if (c == null) {
            onChange(null)
            return
        }
        prefs.rememberPlayer(c.packageName)
        // Gestures follow the player that plays now; keep the remembered one (profiles, sensors,
        // "open the player") in step without waiting for the next key press.
        if (c.isPlaying() && c.packageName != prefs.lastPlayer &&
            (!prefs.appFilter || c.packageName in prefs.allowedApps)
        ) {
            prefs.lastPlayer = c.packageName
        }
        val meta = c.metadata
        onChange(
            NowPlaying(
                controller = c,
                appLabel = appLabel(context, c.packageName),
                // Read the same way for every player (see TrackInfo.trackOf).
                title = meta?.let { TrackInfo.trackOf(it, c.packageName) }?.title,
                artist = meta?.let { TrackInfo.trackOf(it, c.packageName) }?.artist,
                art = TrackInfo.coverOf(meta),
                playing = c.isPlaying(),
            ),
        )
    }

    private companion object {
        const val REPICK_DELAY_MS = 150L
    }
}

fun appLabel(context: Context, packageName: String): String =
    try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

fun appIcon(context: Context, packageName: String): Bitmap? =
    try {
        context.packageManager.getApplicationIcon(packageName).toBitmap()
    } catch (_: Exception) {
        null
    }

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w = intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = intrinsicHeight.takeIf { it > 0 } ?: 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bmp
}
