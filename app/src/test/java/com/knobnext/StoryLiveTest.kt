package com.knobnext

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The track story against the real sources (Wikipedia, Wikidata, MusicBrainz, TheAudioDB, Deezer), the
 * way a gesture asks for it: no model, the facts read as they are. Prints what would be said, telling
 * after telling, so the introduction, the notes and their order can be read in the CI log. Runs only
 * with LIVE_STORIES=1 (it needs the network).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryLiveTest {
    private val tracks = listOf(
        TrackInfo.Track("Smells Like Teen Spirit", "Nirvana", "Nevermind", null),
        TrackInfo.Track("Группа крови", "Кино", "Группа крови", null),
        TrackInfo.Track("Хочешь?", "Земфира", "Прости меня моя любовь", null),
        TrackInfo.Track("Creep", "Radiohead", "Pablo Honey", null),
        TrackInfo.Track("Тает лёд", "Грибы", null, null),
        TrackInfo.Track("Nonexistent Song 4711", "Nobody Knows This Band 4711", null, null),
    )

    @Test
    fun stories() = runBlocking {
        assumeTrue(System.getenv("LIVE_STORIES") == "1")
        val context = RuntimeEnvironment.getApplication()
        var told = 0
        for (length in listOf(InfoLength.NORMAL)) {
            for (track in tracks) {
                println("\n===== ${track.artist} — ${track.title} (${length.name})")
                repeat(TELLINGS) { i ->
                    val story = TrackInfo.story(context, track, length, "ru", InfoSource.WIKI) { println("   · $it") }
                    println("#${i + 1}: ${story ?: "(ничего)"}")
                    if (story != null) told++
                }
            }
        }
        println("\n===== Знакомства")
        Journal.entries(context).forEach { e ->
            println("${e.name}: заметок ${e.notes.size}, спрашивали ${e.asked}, рассказано ${e.progress}%, неизвестен=${e.unknown}")
        }
        assertTrue("Nothing was told about any artist: the sources could not be reached", told > 0)
    }

    private companion object {
        const val TELLINGS = 5
    }
}
