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
        // The same band in Latin letters: one record in the history, no second introduction.
        TrackInfo.Track("Звезда по имени Солнце", "Kino", null, null),
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
        // MusicBrainz, deeper: what joins the facts in the background after the first telling.
        println("\n===== MusicBrainz (Last.fm: ${if (BuildConfig.LASTFM_KEY.isBlank()) "нет ключа" else "есть ключ"}, setlist.fm: ${if (BuildConfig.SETLISTFM_KEY.isBlank()) "нет ключа" else "есть ключ"})")
        for (track in tracks) {
            val found = Brainz.gather(context, "ru", track.title, track.artist!!, track.album, said = "")
            println("${track.artist}: песня ${found?.song?.facts} | исполнитель ${found?.artist?.facts} | основы ${found?.basics} | круг ${found?.circle} | обложка ${found?.coverUrl} | страницы ${found?.pages}")
            found?.let { f ->
                val live = Setlist.gather(context, "ru", f.artistMbid, track.title)
                println("   setlist.fm: песня ${live?.song?.facts} | исполнитель ${live?.artist?.facts}")
            }
        }
        // The artists around each one, as the circle would tell them.
        println("\n===== Круг")
        for (track in tracks) {
            val facts = TrackInfo.facts(context, track, "ru")
            println("${track.artist}: ${facts?.circle?.joinToString(" | ") ?: "(ничего)"}  → ${facts?.related}")
        }
        // History off: nothing is remembered, so every story starts with the introduction again.
        Prefs(context).infoHistory = false
        println("\n===== История выключена: Nirvana дважды")
        repeat(2) { i ->
            val story = TrackInfo.story(context, tracks[0], InfoLength.SHORT, "ru", InfoSource.WIKI) { println("   · $it") }
            println("#${i + 1}: ${story ?: "(ничего)"}")
        }
        Prefs(context).infoHistory = true
        println("\n===== Знакомства")
        Journal.entries(context).forEach { e ->
            println("${e.name}: заметок ${e.notes.size}, спрашивали ${e.asked}, альбомов ${e.albums}, неизвестен=${e.unknown}, последний трек ${e.lastTrack} / ${e.lastAlbum}")
            e.via?.let { println("   через $it") }
            e.notes.forEach { n -> println("   [${n.topic ?: "-"}] ${n.album ?: "-"} · ${n.track}: ${n.text.take(80)}") }
        }
        assertTrue("Nothing was told about any artist: the sources could not be reached", told > 0)
    }

    private companion object {
        const val TELLINGS = 7
    }
}
