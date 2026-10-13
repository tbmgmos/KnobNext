package com.knobnext

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore

/**
 * From the story to the music: an artist the circle named ("Dave Grohl has also played in Foo
 * Fighters") is played in the player the person already uses, the way a voice assistant asks for an
 * artist. Quietly through the player's session when it can search; else the player is opened on that
 * artist; else on a search for them.
 */
object CirclePlay {
    /** What happened, for the log: [player] is the app asked, null when there was none. */
    class Result(val ok: Boolean, val artist: String?, val player: String?)

    /** The artists a story named lately: an hour on, they are no longer what the person just heard. */
    fun fresh(prefs: Prefs): List<String> =
        prefs.circleNames.takeIf { System.currentTimeMillis() - prefs.circleAt < FRESH_MS }.orEmpty()

    /** The next of [names] (each call takes the next one), in [controller]'s player or the last one used. */
    fun next(context: Context, controller: MediaController?, prefs: Prefs, names: List<String>): Result {
        if (names.isEmpty()) return Result(false, null, null)
        val name = names[prefs.circlePick % names.size]
        prefs.circlePick = prefs.circlePick + 1
        return play(context, controller, prefs.lastPlayer, name)
    }

    private const val FRESH_MS = 60L * 60 * 1000

    fun play(context: Context, controller: MediaController?, lastPlayer: String?, name: String): Result {
        val focus = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_ARTIST, name)
        }
        // The player can search itself: nothing opens, the music just changes.
        if (controller != null && (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
            val ok = runCatching { controller.transportControls.playFromSearch(name, focus) }.isSuccess
            if (ok) return Result(true, name, controller.packageName)
        }
        val pkg = controller?.packageName ?: lastPlayer ?: return Result(false, name, null)
        val tries = listOf(
            // What assistants send: the player plays the artist.
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtras(focus).putExtra(SearchManager.QUERY, name),
            // Else its own search, on that name.
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, name),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return Result(true, name, pkg)
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return Result(false, name, pkg)
    }
}
