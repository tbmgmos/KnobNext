package com.knobnext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
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

/** Follows the most relevant media session and reports changes on the main thread. */
class NowPlayingTracker(
    private val context: Context,
    private val prefs: Prefs,
    private val onChange: (NowPlaying?) -> Unit,
) {
    private val media = Media(context)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var current: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        follow(media.pick(list.orEmpty()))
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = follow(media.pick(media.controllers()))
    }

    fun start() {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, media.listenerComponent, handler)
        } catch (_: SecurityException) {
            onChange(null)
            return
        }
        follow(media.pick(media.controllers()))
    }

    fun stop() {
        sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        current?.unregisterCallback(callback)
        current = null
    }

    private fun follow(controller: MediaController?) {
        // A different player may have started; re-pick even if the same session object is reported.
        val best = media.pick(media.controllers()) ?: controller
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = best
            best?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = current
        if (c == null) {
            onChange(null)
            return
        }
        prefs.rememberPlayer(c.packageName)
        val meta = c.metadata
        onChange(
            NowPlaying(
                controller = c,
                appLabel = appLabel(context, c.packageName),
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                playing = c.isPlaying(),
            ),
        )
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
