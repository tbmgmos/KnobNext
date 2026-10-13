package com.knobnext

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.delay

/**
 * From the story to the music: an artist the circle named ("Dave Grohl has also played in Foo
 * Fighters") is played in the player the person already uses, the way a voice assistant asks for an
 * artist. Quietly through the player's session when it can search and really does; else the player is
 * opened on that artist; else on a search for them.
 */
object CirclePlay {
    /**
     * What happened, for the log: [player] is the app asked, null when there was none; [opened] when the
     * player had to be opened (its session took the search and did nothing, or has none).
     */
    class Result(val ok: Boolean, val artist: String?, val player: String?, val opened: Boolean = false)

    /** The artists a story named lately: an hour on, they are no longer what the person just heard. */
    fun fresh(prefs: Prefs): List<String> =
        prefs.circleNames.takeIf { System.currentTimeMillis() - prefs.circleAt < FRESH_MS }.orEmpty()

    /** The next of [names] (each call takes the next one), in [controller]'s player or the last one used. */
    suspend fun next(context: Context, controller: MediaController?, prefs: Prefs, names: List<String>): Result {
        if (names.isEmpty()) return Result(false, null, null)
        val name = names[prefs.circlePick % names.size]
        prefs.circlePick = prefs.circlePick + 1
        return play(context, controller, prefs.lastPlayer, name)
    }

    /** [name], the artist; with [title], that one track of theirs (one put aside to listen to later). */
    suspend fun play(context: Context, controller: MediaController?, lastPlayer: String?, name: String, title: String? = null): Result {
        val prefs = Prefs(context)
        val focus = Bundle().apply {
            if (title == null) {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            } else {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_TITLE, title)
            }
            putString(MediaStore.EXTRA_MEDIA_ARTIST, name)
        }
        val query = if (title == null) name else "$name $title"
        // The player can search itself: nothing opens, the music just changes. Some players (Spotify)
        // say they can, take the request and do nothing: the music is watched for a moment, and such a
        // player is asked the other way from then on.
        val pkg = controller?.packageName
        if (controller != null && pkg !in prefs.searchDeaf &&
            (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
        ) {
            val before = nowPlaying(controller)
            val sent = runCatching { controller.transportControls.playFromSearch(query, focus) }.isSuccess
            if (sent && changed(controller, before, name)) return Result(true, name, pkg)
            if (sent && pkg != null) prefs.searchDeaf = prefs.searchDeaf + pkg
        }
        val target = pkg ?: lastPlayer ?: return Result(false, name, null)
        val tries = listOfNotNull(
            // What assistants send: the player opens and plays the artist.
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtras(focus).putExtra(SearchManager.QUERY, query),
            // Spotify's own address for a search, should it not take the assistants' request.
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).takeIf { target == SPOTIFY },
            // Else its own search, on that name.
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, query),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.setPackage(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return Result(true, name, target, opened = true)
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return Result(false, name, target)
    }

    private fun nowPlaying(c: MediaController): String? =
        c.metadata?.let { "${it.getString(MediaMetadata.METADATA_KEY_TITLE)}\u0000${it.getString(MediaMetadata.METADATA_KEY_ARTIST)}" }

    /** Whether the player took the request: [name] plays, or at least another track than [before]. */
    private suspend fun changed(c: MediaController, before: String?, name: String): Boolean {
        val wanted = Journal.key(name)
        val until = SystemClock.elapsedRealtime() + CHECK_MS
        while (SystemClock.elapsedRealtime() < until) {
            delay(POLL_MS)
            val artist = c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.let(Journal::key)
            if (!artist.isNullOrEmpty() && wanted.isNotEmpty() && (artist.contains(wanted) || wanted.contains(artist))) return true
            if (nowPlaying(c) != before && c.playbackState?.state == PlaybackState.STATE_PLAYING) return true
        }
        return false
    }

    private const val FRESH_MS = 60L * 60 * 1000
    private const val SPOTIFY = "com.spotify.music"

    /** How long a player is given to start what was asked through its session. */
    private const val CHECK_MS = 4_000L
    private const val POLL_MS = 250L
}
