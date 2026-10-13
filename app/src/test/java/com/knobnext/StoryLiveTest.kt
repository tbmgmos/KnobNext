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
        // What the services say to keys that cannot work: the answers the settings screen turns into messages.
        fun show(r: KeyCheck) = when (r) {
            is KeyCheck.Works -> "работает"
            is KeyCheck.Rejected -> "отказ: HTTP ${r.http}, код ${r.error}, от API ${r.fromApi}: ${r.message}"
            is KeyCheck.NoAnswer -> "нет ответа: ${r.kind} ${r.detail}"
        }
        println("\n===== Проверка ключей")
        println("Last.fm, 32 нуля: ${show(MusicSources.checkLastFm("0".repeat(32)))}")
        println("Last.fm, 31 символ: ${show(MusicSources.checkLastFm("0".repeat(31)))}")
        // The real keys, from the repository's secrets (never printed): what a server far from the phone is told.
        BuildConfig.LASTFM_KEY.takeIf { it.isNotBlank() }?.let { println("Last.fm, ключ из секрета: ${show(MusicSources.checkLastFm(it))}") }
        // MusicBrainz, deeper: what joins the facts in the background after the first telling.
        println("\n===== MusicBrainz (Last.fm: ${if (Prefs(context).lastFmKeyInUse.isBlank()) "нет ключа" else "есть ключ"})")
        for (track in tracks) {
            // As the background step does: with what was already found, and Wikidata's MusicBrainz id.
            val quick = TrackInfo.facts(context, track, "ru")
            val said = quick?.let { q -> (q.song + q.artist + q.albumText).flatMap { it.facts }.joinToString(" ") }.orEmpty()
            val found = Brainz.gather(context, "ru", track.title, track.artist!!, track.album, said, quick?.mbid)
            println("${track.artist}: песня ${found?.song?.facts} | исполнитель ${found?.artist?.facts} | основы ${found?.basics} | круг ${found?.circle} | обложка ${found?.coverUrl} | страницы ${found?.pages}")
        }
        // Covers for the history: found by the album's name, then fetched (through the archive's redirects).
        println("\n===== Обложки")
        for ((artist, album) in listOf("Nirvana" to "Nevermind", "Кино" to "Группа крови", "Земфира" to "Прости меня моя любовь")) {
            val url = Brainz.coverOf(artist, album) ?: MusicSources.deezerCover(artist, album)
            val bytes = url?.let { u ->
                runCatching {
                    val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "KnobNext (https://github.com/tbmgmos/KnobNext)")
                    if (conn.responseCode == 200) conn.inputStream.use { it.readBytes().size } else -conn.responseCode
                }.getOrElse { -1 }
            }
            println("$artist / $album: $url → ${bytes ?: "-"} байт")
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
