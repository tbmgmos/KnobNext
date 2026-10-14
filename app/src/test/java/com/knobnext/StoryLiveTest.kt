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
        // Once said "nothing is known" now and then: a slow source threw the rest away.
        TrackInfo.Track("All the Small Things", "blink-182", "Enema of the State", null),
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
            is KeyCheck.Rejected -> "отказ: HTTP ${r.http}, код ${r.error}, от API ${r.fromApi}: ${r.message}" +
                (r.plain?.let { " | простая подпись: ${it.javaClass.simpleName}" } ?: "") +
                (r.browser?.let { " | как браузер: ${it.javaClass.simpleName}" } ?: "")
            is KeyCheck.NoAnswer -> "нет ответа: ${r.kind} ${r.detail}"
        }
        println("\n===== Проверка ключей")
        println("Last.fm, 32 нуля: ${show(MusicSources.checkLastFm(context, "0".repeat(32)))}")
        println("Last.fm, 31 символ: ${show(MusicSources.checkLastFm(context, "0".repeat(31)))}")
        // The real keys, from the repository's secrets (never printed): what a server far from the phone is told.
        BuildConfig.LASTFM_KEY.takeIf { it.isNotBlank() }?.let { println("Last.fm, ключ из секрета: ${show(MusicSources.checkLastFm(context, it))}") }
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

    /** The dates the cloud model is given and held to: MusicBrainz's, and a wrong year taken out. */
    @Test
    fun dates() = runBlocking {
        assumeTrue(System.getenv("LIVE_STORIES") == "1")
        val context = RuntimeEnvironment.getApplication()
        for (track in listOf(
            TrackInfo.Track("Smells Like Teen Spirit", "Nirvana", "Nevermind", null),
            TrackInfo.Track("All the Small Things", "blink-182", "Enema of the State", null),
            TrackInfo.Track("At Your Funeral", "Saves the Day", "Stay What You Are", null),
            TrackInfo.Track("Группа крови", "Кино", "Группа крови", null),
        )) {
            val facts = TrackInfo.facts(context, track, "ru")
            val d = TrackInfo.reference(context, track.artist, facts?.album ?: track.album, facts?.mbid)
            println("\n===== Даты: ${track.artist} — ${track.album}")
            println("начали ${d?.began}, закончили ${d?.ended}; альбом «${d?.album}» вышел ${d?.albumDate}")
            println("альбомы: ${d?.albums?.joinToString("; ") { "${it.first} (${it.second})" }}")
            val allowed = TrackInfo.allowedYears(d, track, facts)
            val album = d?.album ?: track.album
            val wrong = CloudBrain.Answer("Альбом $album вышел в 1993 году. Группа выступала и в 1990-х. Это одна из самых известных их песен.", emptyList())
            println("проверка: «${wrong.text}» → «${TrackInfo.checked(wrong, allowed, d)?.text}» (убрано ${TrackInfo.lastDropped})")
            val right = CloudBrain.Answer("Альбом $album вышел в ${d?.albumDate?.take(4)} году.", emptyList())
            println("верная дата: «${right.text}» → «${TrackInfo.checked(right, allowed, d)?.text}»")
        }
    }

    /** ListenBrainz: tracks as players write them, recognised; an album for a player that gives none. */
    @Test
    fun listenBrainz() = runBlocking {
        assumeTrue(System.getenv("LIVE_STORIES") == "1")
        val context = RuntimeEnvironment.getApplication()
        // Recognising needs a ListenBrainz user's token (the LISTENBRAINZ_TOKEN secret); without it, only the album fallback.
        System.getenv("LISTENBRAINZ_TOKEN")?.takeIf { it.isNotBlank() }?.let { Prefs(context).lbToken = it }
        println("\n===== ListenBrainz: распознавание (токен: ${if (Prefs(context).lbToken.isBlank()) "нет" else "есть"})")
        val asked = listOf(
            "Nirvana" to "Smells Like Teen Spirit (Official Music Video)",
            "Kino" to "Gruppa Krovi",
            "Кино" to "Группа крови",
            "blink-182" to "All The Small Things",
            "Radiohead" to "Creep",
            "Daft Punk" to "One More Time",
            "Nobody Knows This Band 4711" to "Nonexistent Song 4711",
        )
        for ((artist, title) in asked) {
            val m = ListenBrainz.lookup(context, artist, title)
            println("$artist — $title → ${m?.let { "${it.artist} — ${it.title} [альбом: ${it.album}] recording ${it.recordingMbid}" } ?: "не узнан"}")
            if (m == null) println("   ответ: ${ListenBrainz.lastReply}")
        }
        // As YouTube gives it: no album. The card should still have one.
        val facts = TrackInfo.facts(context, TrackInfo.Track("Smells Like Teen Spirit", "Nirvana", null, null), "ru")
        println("без альбома от плеера → альбом ${facts?.album}, год ${facts?.year}")
        for ((artist, title) in listOf("Nirvana" to "Smells Like Teen Spirit", "Кино" to "Группа крови", "Radiohead" to "Creep")) {
            kotlinx.coroutines.delay(1_500)
            val pick = TrackInfo.musicBrainz(title, artist)
            println("альбом по MusicBrainz: $artist — $title → ${pick?.first} (${pick?.second}); ${TrackInfo.lastAlbumSearch}")
        }
    }

    /** The artist base: what MusicBrainz, Discogs and Wikipedia give for a card, and the story using it. */
    @Test
    fun base() = runBlocking {
        assumeTrue(System.getenv("LIVE_STORIES") == "1")
        val context = RuntimeEnvironment.getApplication()
        for (name in listOf("Nirvana", "Кино", "blink-182")) {
            val started = System.currentTimeMillis()
            val card = ArtistBase.build(context, name, "ru")
            ArtistBase.keep(context, name, card)
            println("\n===== База: $name (${(System.currentTimeMillis() - started) / 1000} с)")
            println("MusicBrainz ${card.mbid}, Discogs ${card.discogsId}, Wikidata ${card.wikidata}; группа ${card.band}, ${card.began}–${card.ended.orEmpty()}")
            println("состав: ${card.members.joinToString { it.name + (it.years?.let { y -> " ($y)" } ?: "") }}")
            println("лейблы: ${card.labels}")
            println("записей ${card.albums.size}, студийных ${card.albums.count { it.studio }}:")
            card.albums.take(14).forEach { println("   ${it.date ?: "????"}  ${it.title}  [${it.kind ?: "Discogs"}${if (it.studio) ", студийный" else ""}]") }
            println("Discogs о них: ${card.profile.take(2).joinToString(" ")}")
            println("вступление: ${card.intro.firstOrNull()}")
            println("фактов из Википедии: ${card.about.size}")
        }
        println("\n===== Справка из базы")
        val d = TrackInfo.reference(context, "Nirvana", "Nevermind", null)
        println("Nevermind: ${d?.albumDate}; альбомы: ${d?.albums?.joinToString { "${it.first} (${it.second})" }}")
        val k = TrackInfo.reference(context, "Кино", "Группа крови", null)
        println("Группа крови: ${k?.albumDate}")
        val facts = TrackInfo.facts(context, TrackInfo.Track("Smells Like Teen Spirit", "Nirvana", "Nevermind", null), "ru")
        println("из базы в фактах: ${facts?.artist?.takeLast(2)?.joinToString(" | ") { t -> "[${t.lang}] " + t.facts.take(2).joinToString(" ") }}")
        assertTrue("карточка Nirvana пуста", ArtistBase.card(context, "Nirvana") != null)
    }

    /** The cloud models the CI has keys for (GEMINI_API_KEY, GROQ_API_KEY): a story and a question each. */
    @Test
    fun cloud() = runBlocking {
        val keys = mapOf(CloudService.GEMINI to "GEMINI_API_KEY", CloudService.GROQ to "GROQ_API_KEY")
            .mapValues { System.getenv(it.value).orEmpty() }.filterValues { it.isNotBlank() }
        assumeTrue(System.getenv("LIVE_STORIES") == "1" && keys.isNotEmpty())
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        prefs.devMode = true
        // Each service on its own: no switching to another, so that its own answer (or failure) shows.
        prefs.cloudFallback = false
        for ((service, key) in keys) {
            prefs.cloudService = service
            prefs.setCloudKey(service, key)
            println("\n===== Облако ${service.title}: ключ ${CloudBrain.check(service, key).javaClass.simpleName}")
            val track = TrackInfo.Track("The Man Who Sold the World", "Nirvana", "MTV Unplugged in New York", null)
            val story = TrackInfo.story(context, track, InfoLength.NORMAL, "ru", InfoSource.CLOUD) { println("   · $it") }
            println("рассказ: $story")
            println("ответила: ${CloudBrain.lastUsed}; ошибка: ${CloudBrain.lastError} ${CloudBrain.lastDetail.orEmpty()}")
            println("источники: ${TrackInfo.lastSources.joinToString { it.title }}")
            val next = TrackInfo.ask(context, track, null, TrackInfo.Question.NEXT, "ru", null)
            println("что дальше: ${next?.text ?: CloudBrain.lastError}")
            prefs.setCloudKey(service, "")
        }
    }

    /** The round card's "about the recording": who plays, who produced, samples, covers. */
    @Test
    fun recordings() = runBlocking {
        assumeTrue(System.getenv("LIVE_STORIES") == "1")
        val context = RuntimeEnvironment.getApplication()
        for (track in listOf(
            TrackInfo.Track("Smells Like Teen Spirit", "Nirvana", "Nevermind", null),
            TrackInfo.Track("The Man Who Sold the World", "Nirvana", "MTV Unplugged in New York", null),
            TrackInfo.Track("Firestarter", "The Prodigy", null, null),
            TrackInfo.Track("One More Time", "Daft Punk", "Discovery", null),
            TrackInfo.Track("Группа крови", "Кино", "Группа крови", null),
        )) {
            val mbid = TrackInfo.facts(context, track, "ru")?.mbid
            val r = TrackInfo.record(context, track, "ru", mbid)
            println("\n===== О записи: ${track.artist} — ${track.title} (mbid $mbid)")
            if (r == null) {
                println("(ничего)")
                continue
            }
            println("играют: ${r.players.joinToString("; ") { (who, what) -> "$who — ${what.joinToString(", ")}" }}")
            println("продюсер: ${r.producers} | студия: ${r.studio} | написали: ${r.writers} | кавер: ${r.cover}")
            println("первоисточник: ${r.original?.let { "${it.artist} — ${it.title} (${it.year})" }} | своя первая запись: ${r.firstYear}")
            println("сэмплы: ${r.samples.joinToString { "${it.artist} — ${it.title}" }} | сэмплировали: ${r.sampledBy.joinToString { "${it.artist} — ${it.title}" }}")
            println("перепевали (${r.coverCount}): ${r.covers.joinToString { "${it.artist} (${it.year})" }}")
        }
    }

    private companion object {
        const val TELLINGS = 7
    }
}
