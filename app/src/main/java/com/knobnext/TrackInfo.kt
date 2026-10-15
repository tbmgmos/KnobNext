package com.knobnext

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/** How much to say about a track. */
/**
 * [sentences]: at most, for a model; [noteSentences]: facts in a note read as they are; [introNote]: facts
 * after an introduction.
 */
enum class InfoLength(val sentences: Int, val noteSentences: Int, val introNote: Int) { SHORT(2, 1, 0), NORMAL(3, 2, 1), LONG(5, 3, 2) }

/** Where the story comes from. */
enum class InfoSource {
    /** Gemini Nano tells what it knows itself; when the system refuses it, Wikipedia steps in. */
    GEMINI,

    /** Facts from Wikipedia and MusicBrainz, retold by a model on the phone. */
    COMBINED,

    /** The Wikipedia facts as they are, no model. */
    WIKI,

    /** A large model in the cloud with the person's key (Gemini looks things up in Google), the facts as its footing. */
    CLOUD,
}

/** How the story reaches the person: spoken, shown in the round card on screen (no sound), or both. */
enum class InfoDelivery { VOICE, BUBBLE, BOTH }

/** The language of the story: one picked, or the phone's. */
enum class InfoLanguage(val code: String?) {
    RU("ru"), EN("en"), SYSTEM(null);

    fun resolve(): String = code ?: if (Locale.getDefault().language == "ru") "ru" else "en"
}

/**
 * A short spoken note about the playing track. The first time KnobNext tells about an artist, it
 * introduces them (who they are, where and when they began, what they play); after that each telling is
 * one note on one subject, with facts not told yet: the song if there is something about it, else its
 * album, else the next part of the artist's story, in the order the articles tell it. Facts come from whole Wikipedia articles (song, album,
 * artist) and MusicBrainz; each telling takes the next facts in turn, and a model, when one can run,
 * retells them and is told what was already said. Gemini Nano runs only while the app is on screen
 * (a system rule), so from a gesture the downloaded model retells, or the facts are read as they are.
 * Everything is in one language. Only the title, the artist and the album leave the phone; the facts
 * found are kept on the phone, so the next telling is quicker and works offline.
 */
object TrackInfo {
    data class Track(val title: String, val artist: String?, val album: String?, val year: String?)

    /**
     * The playing track as the story knows it, from the player's data, whichever player it is. Players
     * fill different fields: Spotify the usual ones; others the artist only as the subtitle or the album
     * artist, the album as the description, the year as a date; a video or a browser all of it as the
     * title ("Artist - Song") with no artist at all.
     */
    fun trackOf(meta: android.media.MediaMetadata, player: String? = null): Track? {
        // "<unknown>", "Unknown artist": a file without tags (Poweramp, any local player) says nothing.
        fun text(key: String) = meta.getString(key)?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in PLACEHOLDERS }
        var title = text(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: text(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        var artist = text(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: text(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: text(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: text(android.media.MediaMetadata.METADATA_KEY_AUTHOR)
            ?: text(android.media.MediaMetadata.METADATA_KEY_COMPOSER)
        if (artist == null) {
            // A file's name for the title: "01 - Artist - Song.mp3".
            title = title.replace(FILE_EXTENSION, "").replace(TRACK_NUMBER, "").trim().ifEmpty { title }
        }
        // "Artist - Song" in the title: a video, a browser tab, a file. Where people upload (YouTube,
        // SoundCloud, VK), the artist field is whoever uploaded it (a channel, a label, a fan) and the
        // title names the musician; a music player's own artist is kept unless the title repeats it.
        // "Song - Remastered 2011", "Song - Live" are a version, not an artist and a song.
        TITLE_DASH.find(title)?.takeIf { m -> !VERSION_TAIL.containsMatchIn(m.groupValues[2]) }?.let { m ->
            val before = m.groupValues[1].trim()
            val same = artist?.let { Journal.key(it) == Journal.key(before) } == true
            if (artist == null || same || player in UPLOADS || player.orEmpty().let { p -> BROWSERS.any { p.startsWith(it) } }) {
                artist = before
                title = m.groupValues[2].trim()
            }
        }
        val album = (text(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: text(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
            // A description that only repeats the artist or the title is no album.
            ?.takeIf { d -> d != artist && d != title && d.length <= MAX_ALBUM_CHARS }
        val year = meta.getLong(android.media.MediaMetadata.METADATA_KEY_YEAR).takeIf { it in 1000..9999 }?.toString()
            ?: text(android.media.MediaMetadata.METADATA_KEY_DATE)?.let { Regex("""\b(1[89]|20)\d\d\b""").find(it)?.value }
        return Track(title = title, artist = artist, album = album, year = year)
    }

    /** The cover, wherever the player put it. */
    fun coverOf(meta: android.media.MediaMetadata?): android.graphics.Bitmap? = meta?.let {
        it.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: it.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: it.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    /** Where people upload what they like: the artist field names the uploader, not the musician. */
    private val UPLOADS = setOf(
        "com.soundcloud.android", "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "app.revanced.android.youtube", "app.revanced.android.apps.youtube.music", "com.vanced.android.youtube",
        "org.schabi.newpipe", "com.vkontakte.android", "com.vk.music", "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill", "org.telegram.messenger",
    )
    private val BROWSERS = listOf(
        "com.android.chrome", "com.chrome.", "org.mozilla.", "com.brave.browser", "com.yandex.browser",
        "com.opera.", "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.vivaldi.browser", "com.kiwibrowser",
    )

    /** What follows a dash when it is a version of the song, not the song after its artist. */
    private val VERSION_TAIL = Regex(
        """^\s*\(?\s*(remaster|live|version|edit|mix|mono|stereo|bonus|demo|acoustic|instrumental|radio|single|bonus|from |deluxe|explicit|clean|unplugged|ремастер|live|концерт)""",
        RegexOption.IGNORE_CASE,
    )
    private val PLACEHOLDERS = setOf(
        "<unknown>", "unknown", "unknown artist", "unknown album", "неизвестный исполнитель", "неизвестный альбом",
        "неизвестно", "various artists", "<неизвестно>",
    )
    private val FILE_EXTENSION = Regex("""(?i)\.(mp3|flac|m4a|aac|ogg|opus|wav|wma|ape|alac)$""")
    private val TRACK_NUMBER = Regex("""^\d{1,3}\s*[-.)_]\s*""")
    private val TITLE_DASH = Regex("""^(.{2,60}?)\s+[-–—]\s+(.{2,}?)$""")
    private const val MAX_ALBUM_CHARS = 120

    /** An article's facts (one sentence each) and the language they are in. */
    class Text(val facts: List<String>, val lang: String)

    /** What is known, by subject; each subject from several sources (Wikipedia, TheAudioDB, Deezer, MusicBrainz). */
    class Facts(
        val song: List<Text>,
        val artist: List<Text>,
        val album: String?,
        val year: String?,
        val albumText: List<Text>,
        /** Who the artist is, in the story's language: the article's opening definition, or Wikidata's basics. */
        val intro: List<String> = emptyList(),
        /** The artists around this one (members' other bands, influences), a sentence each. */
        val circle: List<String> = emptyList(),
        /** Their names and Wikidata items, for the history. */
        val related: List<String> = emptyList(),
        val relatedIds: List<String> = emptyList(),
        /** The artist's Wikidata item: one artist, however the player spells them. */
        val artistId: String? = null,
        /** The album's cover (Cover Art Archive), for the history when the player had none. */
        val coverUrl: String? = null,
        /** The artist's own pages ("site", "bandcamp", "soundcloud" to address). */
        val pages: Map<String, String> = emptyMap(),
        /** MusicBrainz's slower facts have joined these. */
        val deep: Boolean = false,
        /** The artist's MusicBrainz id from Wikidata, so MusicBrainz finds the right artist. */
        val mbid: String? = null,
    ) {
        val empty get() = song.isEmpty() && artist.isEmpty() && albumText.isEmpty()
    }

    /**
     * One subject's facts from all its sources, taking from each in turn so that consecutive tellings
     * hear different sources; [lang]: only facts in that language.
     */
    private fun merged(texts: List<Text>, lang: String? = null): List<String> {
        val lists = texts.filter { lang == null || it.lang == lang }.map { it.facts }
        val out = ArrayList<String>()
        var i = 0
        while (lists.any { i < it.size }) {
            lists.forEach { if (i < it.size) out += it[i] }
            i++
        }
        return out
    }

    /** The opening line from the player's own data, said at once: title, artist, album, year. */
    fun header(context: Context, track: Track, lang: String): String = buildString {
        append(Speech.announcement(track.title, track.artist, clean = true))
        val res = localized(context, lang)
        val detail = listOfNotNull(
            track.album?.let { res.getString(R.string.info_album, it) },
            track.year?.let { res.getString(R.string.info_year, it) },
        )
        if (detail.isNotEmpty()) append(". ").append(detail.joinToString(", ").replaceFirstChar { it.uppercase() })
        append(".")
    }

    /**
     * The story after the opening line, or null when nothing was found. [note] gets a line for the
     * event log whenever a step falls back to the next one.
     */
    suspend fun story(
        context: Context,
        track: Track,
        length: InfoLength,
        lang: String,
        source: InfoSource,
        note: (String) -> Unit,
    ): String? = coroutineScope {
        val started = System.currentTimeMillis()
        // With the history off nothing is remembered: every story is a first meeting.
        val remember = Prefs(context).infoHistory
        val history = History(context, track.artist.takeIf { remember })
        // Never told about this artist in KnobNext: the listener is introduced to them first.
        var firstMeet = track.artist != null && history.told().isEmpty()
        // Nothing was known about them before: when something is now, that is news in itself.
        val wasUnknown = remember && track.artist != null && Journal.entry(context, Journal.key(track.artist))?.unknown == true
        var album: String? = track.album
        var found: Facts? = null
        // The note as the history keeps it: without "About the song:", the card has its own headings.
        var bare: String? = null
        // What the story ends up being about, for the history.
        var told: Plan? = null
        // How long each way took goes to the log: the story is meant to follow the title without a pause.
        fun ready(story: String, how: String): String {
            note(context.getString(R.string.log_info_ready, (System.currentTimeMillis() - started) / 1000.0, how))
            // For the settings: what told the last story.
            Prefs(context).storyLast = how
            history.add(story)
            if (remember && track.artist != null) {
                val topic = when {
                    told?.intro?.isNotEmpty() == true || told == null && firstMeet -> Journal.Topic.INTRO
                    else -> when (told?.topic) {
                        Topic.SONG -> Journal.Topic.SONG
                        Topic.ALBUM -> Journal.Topic.ALBUM
                        Topic.ARTIST -> Journal.Topic.ARTIST
                        Topic.CIRCLE -> Journal.Topic.CIRCLE
                        null -> null
                    }
                }
                // A reminder, or "that is all I know", is nothing new for the history.
                val news = (bare ?: story).takeUnless { told?.repeat == true || told?.exhausted == true }
                Journal.record(
                    context, track.artist, track.title, Speech.cleanTitle(track.title), album, news, topic,
                    found = true, id = found?.artistId, related = found?.related.orEmpty(), relatedIds = found?.relatedIds.orEmpty(),
                    coverUrl = found?.coverUrl, pages = found?.pages.orEmpty(),
                )
            }
            // The artists a circle note named: the next "play the circle" plays them, and the story says how.
            var offer: String? = null
            if (told?.topic == Topic.CIRCLE) {
                val named = told?.facts.orEmpty().joinToString(" ").lowercase(Locale.ROOT)
                val names = found?.related.orEmpty().filter { named.contains(it.lowercase(Locale.ROOT)) }.distinct()
                if (names.isNotEmpty()) {
                    Prefs(context).circleNames = names
                    offer = circleOffer(context, lang, names.first())
                }
            }
            val spoken = listOfNotNull(story, offer).joinToString(" ")
            if (!wasUnknown) return spoken
            return localized(context, lang).getString(R.string.info_found_now, Speech.cleanArtist(track.artist!!)) + " " + spoken
        }
        // The facts are fetched while the model thinks, not after it: when it refuses, they are already here.
        // Apart from the story: an answer from the model does not wait for them, and they are still kept.
        val factsAhead = background.async { runCatching { facts(context, track, lang) }.getOrNull() }
        lastSources = emptyList()
        if (source == InfoSource.CLOUD && CloudBrain.order(Prefs(context)).isNotEmpty()) {
            // The facts are its footing when they come quickly; the rest it looks up itself.
            // Slow to come: what the artist base has, at once.
            val quick = withTimeoutOrNull(CLOUD_FACTS_WAIT_MS) { factsAhead.await() } ?: ArtistBase.enrich(context, track, lang, null)
            val answer = fromCloud(context, track, quick, length, lang, history.told(), firstMeet)
            if (answer != null) {
                found = quick
                album = quick?.album ?: album
                lastSources = answer.sources
                return@coroutineScope ready(answer.text, answer.by ?: Prefs(context).cloudService.title)
            }
            note(context.getString(R.string.log_info_cloud_failed, listOfNotNull(CloudBrain.lastError, CloudBrain.lastDetail).joinToString(" — ")))
        }
        if (source == InfoSource.GEMINI) {
            fromGemini(track, length, lang, history.told(), firstMeet)?.let {
                return@coroutineScope ready(it, "Gemini Nano")
            }
            note(context.getString(R.string.log_info_gemini_failed, NanoBrain.lastError ?: "-"))
        }
        val facts = factsAhead.await()
        if (facts == null || facts.empty) {
            note(context.getString(R.string.log_info_no_facts))
            // Without the network nothing could be found: that says nothing about the artist.
            if (remember && track.artist != null && online(context)) {
                Journal.record(context, track.artist, track.title, Speech.cleanTitle(track.title), track.album, null, null)
            }
            return@coroutineScope null
        }
        album = facts.album
        found = facts
        // Known under another spelling ("Kino" after "Кино"): no second introduction.
        if (firstMeet && remember && facts.artistId != null && Journal.knows(context, facts.artistId)) firstMeet = false
        val keep = { plan: Plan -> told = plan }
        if (source != InfoSource.WIKI) {
            // Gemini already said no (or can't run from the background): only the downloaded model is left.
            val brain = if (source == InfoSource.GEMINI) Brains.background(context) else null
            retell(context, track, facts, length, lang, history.told(), brain, onlyBackground = source == InfoSource.GEMINI, firstMeet, keep)
                ?.let { return@coroutineScope ready(it, brain?.name ?: context.getString(R.string.log_info_way_model)) }
            note(context.getString(R.string.log_info_plain))
        }
        plainStory(context, track, facts, length, lang, firstMeet, keep) { bare = it }?.let { ready(it, context.getString(R.string.log_info_way_plain)) }
    }

    // ---- A large model in the cloud ----

    /** Where the last story from the cloud was found (Gemini's search): the card names them. */
    @Volatile var lastSources: List<CloudBrain.Source> = emptyList()

    private suspend fun fromCloud(
        context: Context,
        track: Track,
        facts: Facts?,
        length: InfoLength,
        lang: String,
        told: List<String>,
        firstMeet: Boolean,
    ): CloudBrain.Answer? {
        val prefs = Prefs(context)
        // The dates it is not to guess, as far as there is time for them (the voice is waiting).
        val dates = within(STORY_DATES_WAIT_MS) { reference(context, track.artist, facts?.album ?: track.album, facts?.mbid) }
        val prompt = buildString {
            appendTrack(track, facts?.album ?: track.album, facts?.year ?: track.year)
            reference(dates, track, facts, lang)
            footing(facts, lang)
            appendLine(shape(length, facts?.album ?: track.album, lang, firstMeet, topic = null))
            appendTold(told, lang)
        }
        // The voice is waiting after the opening line: the cloud gets a few seconds, then the story is told
        // from the facts. A late answer is not lost: the next telling about this track takes it.
        val key = "${track.artist}\u0000${track.title}\u0000$lang"
        val late = lateAnswers.remove(key)?.takeIf { System.currentTimeMillis() - it.first < LATE_ANSWER_MS }?.second
        val asked = late ?: background.async {
            CloudBrain.ask(prefs, host(lang), prompt, length.sentences * TOKENS_PER_SENTENCE * CLOUD_TOKENS_FACTOR + EXTRA_TOKENS)
        }
        val answer = withTimeoutOrNull(CLOUD_STORY_WAIT_MS) { asked.await() } ?: return null.also {
            if (asked.isActive) {
                lateAnswers[key] = System.currentTimeMillis() to asked
                CloudBrain.lastError = "не успела за ${CLOUD_STORY_WAIT_MS / 1000} с — ответ возьмётся в следующий раз"
            }
        }
        val checked = checked(answer, allowedYears(dates, track, facts), dates) ?: return null
        val text = clean(checked.text, length, lang) ?: return null
        return CloudBrain.Answer(text, checked.sources, checked.by)
    }

    /** Cloud answers that came too late for their telling, by track: the next telling takes them. */
    private val lateAnswers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, kotlinx.coroutines.Deferred<CloudBrain.Answer?>>>()
    private const val LATE_ANSWER_MS = 30L * 60 * 1000

    /** What the round card can ask the cloud model about the track or an artist down the circle. */
    enum class Question { SONG, MAKING, START, ROOTS, NEXT, WHO }

    /**
     * [question] about [track] (or, with [artist], about that artist alone) to the cloud model, with what
     * the open sources found as its footing. Null when there is no key or it could not answer.
     */
    suspend fun ask(context: Context, track: Track?, artist: String?, question: Question, lang: String, facts: Facts?): CloudBrain.Answer? {
        val ru = lang == "ru"
        val who = artist ?: track?.artist?.let(Speech::cleanArtist) ?: return null
        val album = if (artist == null) facts?.album ?: track?.album else null
        val dates = within(ASK_DATES_WAIT_MS) { reference(context, who, album, facts?.mbid) }
        val prompt = buildString {
            if (artist == null && track != null) appendTrack(track, facts?.album ?: track.album, facts?.year ?: track.year) else appendLine("Artist: $who")
            reference(dates, track.takeIf { artist == null }, facts, lang)
            footing(facts, lang)
            appendLine(
                when (question) {
                    Question.SONG -> if (ru) "О чём эта песня и как её понимают? Без цитат из текста." else "What is this song about, and how is it read? No quotes from the lyrics."
                    Question.MAKING -> if (ru) "Как создавалась и записывалась эта песня: кто, где, что необычного." else "How was this song written and recorded: who, where, what was unusual."
                    Question.START -> if (ru) "С чего начать знакомство с $who: два-три альбома или песни и почему именно они." else "Where to start with $who: two or three albums or songs and why those."
                    Question.ROOTS -> if (ru) "Откуда этот звук: на кого опирался $who, из какой сцены вышел." else "Where does this sound come from: whom did $who build on, what scene did they come from."
                    Question.NEXT -> if (ru) {
                        "Посоветуй трёх исполнителей, которых стоит послушать после $who, — не самых очевидных. Каждого с новой строки: «Имя — чем связан и почему понравится»."
                    } else {
                        "Suggest three artists to hear after $who — not the obvious ones. Each on its own line: \"Name — how they are connected and why\"."
                    }
                    Question.WHO -> if (ru) "Кто такой $who: коротко — откуда, когда, что играет и чем известен." else "Who is $who: briefly — where from, when, what they play, what they are known for."
                },
            )
            appendLine(if (ru) "До пяти коротких предложений." else "Five short sentences at most.")
        }
        val asked = withContext(Dispatchers.IO) { CloudBrain.ask(Prefs(context), host(lang), prompt) } ?: return null
        val answer = checked(asked, allowedYears(dates, track.takeIf { artist == null }, facts), dates) ?: return null
        // A list of names keeps its lines; anything else reads as one note.
        val text = if (question == Question.NEXT) answer.text.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.isNotBlank() }.joinToString("\n")
        else speakable(answer.text.replace(Regex("[*#_`]"), "")).trim()
        return CloudBrain.Answer(text, answer.sources, answer.by)
    }

    /** Who the cloud model is to be, and what it must not do. */
    private fun host(lang: String) = if (lang == "ru") {
        "Ты музыкальный журналист и радиоведущий. Отвечай только на русском языке. Говори только проверенные факты: " +
            "найди их в поиске, если можешь; если не уверен — не говори. Не противоречь фактам, которые тебе дали. " +
            "Годы и даты называй только из «Справки» и фактов ниже; если нужной даты там нет — не называй её вовсе. " +
            "Никогда не придумывай подробности: студии, города, людей, технику записи, даты, звуки. Если в поиске и в данных фактах " +
            "о чём-то нет — так и скажи одной фразой («подробностей о записи найти не удалось») и расскажи то, что известно точно. " +
            "Не цитируй тексты песен. Не повторяй в начале название и исполнителя — их уже назвали. Без списков, ссылок и разметки, если не попросили."
    } else {
        "You are a music journalist and radio host. Answer only in English. Say only verified facts: look them up if you can; " +
            "if unsure, leave it out. Do not contradict the facts you are given. Take years and dates only from the Reference and " +
            "the facts below; if the one needed is not there, give no date at all. Never invent details — studios, places, people, " +
            "recording techniques, dates, sounds; if the search and the given facts say nothing about something, say so in one " +
            "sentence and tell what is known for sure. Do not quote lyrics. Do not repeat the title and artist " +
            "at the start — they were just said. No lists, links or markup unless asked."
    }

    // ---- Dates: given, never guessed, and checked ----

    private val referenceCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Brainz.Dates?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Brainz.Dates?>) = size > MAX_REFERENCES
    })

    /** [artist]'s and [album]'s verified dates (MusicBrainz), kept on disk like the facts. */
    suspend fun reference(context: Context, artist: String?, album: String?, mbid: String?): Brainz.Dates? {
        val who = artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() } ?: return null
        val key = "${normalize(who)}\u0000${album?.let { normalize(Speech.cleanTitle(it)) }.orEmpty()}"
        referenceCache[key]?.let { return it }
        // Gathered ahead: at once, and without the network. An album the base does not know is looked up.
        // A card MusicBrainz did not answer for has no studio albums to give: then MusicBrainz is asked now.
        ArtistBase.card(context, who)?.takeIf { it.complete || it.albums.any { a -> a.studio } }
            ?.let { ArtistBase.dates(it, album) }?.takeIf { album == null || it.albumDate != null }?.let {
            referenceCache[key] = it
            return it
        }
        val file = File(dir(context), DATES_PREFIX + sha1(key))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FACTS_FRESH_MS) {
            runCatching { Brainz.Dates.fromJson(JSONObject(file.readText())) }.getOrNull()?.let { referenceCache[key] = it; return it }
        }
        val found = withContext(Dispatchers.IO) { runCatching { Brainz.dates(who, album, mbid) }.getOrNull() } ?: return null
        referenceCache[key] = found
        runCatching { file.writeText(found.toJson().toString()) }
        return found
    }

    /** The Reference: the dates as MusicBrainz and the player give them, to be used and nothing else. */
    private fun StringBuilder.reference(dates: Brainz.Dates?, track: Track?, facts: Facts?, lang: String) {
        val ru = lang == "ru"
        val lines = ArrayList<String>()
        dates?.began?.let { b -> lines += (if (ru) "Исполнитель начал в $b" else "The artist began in $b") + (dates.ended?.let { if (ru) ", закончил в $it" else ", ended in $it" } ?: "") }
        dates?.albumDate?.let { lines += if (ru) "Альбом «${dates.album}» вышел: $it" else "The album \"${dates.album}\" came out: $it" }
        (track?.year ?: facts?.year)?.let { lines += if (ru) "Год этого трека по данным плеера и источников: $it" else "This track's year per the player and sources: $it" }
        if (dates != null && dates.albums.isNotEmpty()) {
            lines += (if (ru) "Студийные альбомы: " else "Studio albums: ") + dates.albums.joinToString("; ") { (t, y) -> "$t ($y)" }
        }
        if (lines.isEmpty()) return
        appendLine(if (ru) "Справка (проверено по MusicBrainz; даты — только отсюда):" else "Reference (checked against MusicBrainz; dates from here only):")
        lines.forEach { appendLine("- $it") }
    }

    /** Every year the model may name: those of the Reference and of the facts it was given. */
    internal fun allowedYears(dates: Brainz.Dates?, track: Track?, facts: Facts?): Set<String> {
        val texts = buildList {
            dates?.let { d -> addAll(listOfNotNull(d.began, d.ended, d.albumDate)); d.albums.forEach { add(it.second) } }
            addAll(listOfNotNull(track?.year, facts?.year))
            facts?.let { f -> (f.intro + f.circle + (f.song + f.albumText + f.artist).flatMap { it.facts }).forEach { add(it) } }
        }
        return texts.flatMap { t -> YEAR.findAll(t).map { it.value }.toList() }.toSet()
    }

    /**
     * The answer with every sentence that names a year not given to it taken out: a model's memory for
     * dates is what fails most. An answer found by a search (it names its sources) is let be.
     */
    internal fun checked(answer: CloudBrain.Answer, allowed: Set<String>, dates: Brainz.Dates? = null): CloudBrain.Answer? {
        if (answer.sources.isNotEmpty()) return answer
        // Each album the sentence names, with the year it came out: "Nevermind вышел в 1993" names 1993,
        // a year of the band's (In Utero's), and still is wrong.
        val albums = (dates?.albums.orEmpty() + listOfNotNull(dates?.album?.let { a -> dates.albumDate?.let { a to it.take(4) } }))
            .map { (t, y) -> normalize(Speech.cleanTitle(t)) to y }.filter { it.first.length >= MIN_TITLE_CHARS }
        var dropped = 0
        val lines = answer.text.lines().map { line ->
            Regex("(?<=[.!?…])\\s+").split(line).filter { sentence ->
                val years = YEAR.findAll(sentence).filterNot { decade(sentence, it) }.map { it.value }.toList()
                val said = normalize(sentence)
                val named = albums.filter { (t, _) -> said.contains(t) }.map { it.second }.toSet()
                val ok = years.all { it in allowed } && (named.isEmpty() || years.isEmpty() || years.any { it in named })
                if (!ok) dropped++
                ok
            }.joinToString(" ")
        }
        lastDropped = dropped
        val text = lines.filter { it.isNotBlank() }.joinToString("\n").trim()
        if (text.isEmpty()) {
            CloudBrain.lastError = "в ответе были только непроверенные даты"
            return null
        }
        return CloudBrain.Answer(text, answer.sources, answer.by)
    }

    /** "the 1990s", «в 1990-х»: a decade, not a date to check. */
    private fun decade(sentence: String, m: MatchResult): Boolean {
        val after = sentence.substring(m.range.last + 1).take(3).lowercase(Locale.ROOT)
        return after.startsWith("-х") || after.startsWith("-е") || after.startsWith("х ") || after.startsWith("s") || after.startsWith("'s")
    }

    /** How many sentences the last check took out, for the log. */
    @Volatile var lastDropped = 0

    private val YEAR = Regex("(?<![\\d])(1[89]\\d\\d|20\\d\\d)(?![\\d])")

    /** What the open sources found, as the model's footing. */
    private fun StringBuilder.footing(facts: Facts?, lang: String) {
        facts ?: return
        val known = (facts.intro + merged(facts.song) + merged(facts.albumText) + merged(facts.artist) + facts.circle)
            .distinct().take(FOOTING_FACTS)
        if (known.isEmpty()) return
        appendLine(if (lang == "ru") "Что известно из открытых источников (опирайся на это):" else "What the open sources say (build on this):")
        known.forEach { appendLine("- " + it.take(MAX_FACT_CHARS)) }
    }

    // ---- Gemini on its own ----

    private suspend fun fromGemini(track: Track, length: InfoLength, lang: String, told: List<String>, firstMeet: Boolean): String? {
        val nano = NanoBrain.ready() ?: return null.also { if (NanoBrain.lastError == null) NanoBrain.lastError = "Gemini Nano не готов" }
        val prompt = buildString {
            appendLine(
                if (lang == "ru") {
                    "Ты радиоведущий. Расскажи слушателю короткую заметку о треке, его альбоме или исполнителе, только на русском языке. " +
                        "Говори только то, в чём уверен. " +
                        "Не цитируй текст песни. Не повторяй название и исполнителя в начале — их уже назвали. Без списков и разметки."
                } else {
                    "You are a radio host. Tell the listener a short note about the track, its album or its artist, only in English. " +
                        "Say only what you are sure of. " +
                        "Do not quote the lyrics. Do not repeat the title and artist at the start — they were just said. No lists, no markup."
                },
            )
            appendTrack(track, track.album, track.year)
            appendLine(shape(length, track.album, lang, firstMeet, topic = null))
            appendTold(told, lang)
        }
        val answer = within(MODEL_TIMEOUT_MS) {
            nano.complete(prompt, length.sentences * TOKENS_PER_SENTENCE + EXTRA_TOKENS, creative = true)
        } ?: return null
        return clean(answer, length, lang)
    }

    // ---- Facts, retold ----

    private suspend fun retell(
        context: Context,
        track: Track,
        facts: Facts,
        length: InfoLength,
        lang: String,
        told: List<String>,
        brain: Brain?,
        onlyBackground: Boolean,
        firstMeet: Boolean,
        keep: (Plan) -> Unit,
    ): String? {
        if (onlyBackground && brain == null) return null
        // The same choice as without a model: the introduction, then one subject's untold facts. Moved on
        // only when the model really tells them: else the plain story takes the same facts.
        val rotation = Rotation(context)
        val plan = plan(context, track, facts, length, lang = null, firstMeet, perFact = FACTS_FOR_MODEL, rotation = rotation)
        val said = retold(context, track, facts, length, lang, told, brain, firstMeet, plan)
        if (said == null) rotation.undo() else keep(plan)
        return said
    }

    private suspend fun retold(
        context: Context,
        track: Track,
        facts: Facts,
        length: InfoLength,
        lang: String,
        told: List<String>,
        brain: Brain?,
        firstMeet: Boolean,
        plan: Plan,
    ): String? {
        if (plan.intro.isEmpty() && plan.facts.isEmpty()) return null
        // Everything told: the model would only make something up. The plain story says so.
        if (plan.exhausted) return null
        val prompt = buildString {
            appendLine(
                if (lang == "ru") {
                    "Ты радиоведущий. Перескажи слушателю факты ниже, только на русском языке (переведи, если они на другом). " +
                        "Используй только эти факты, ничего не выдумывай. Выбери самые интересные. Не цитируй текст песни. " +
                        "Не повторяй название и исполнителя в начале — их уже назвали. Без списков и разметки."
                } else {
                    "You are a radio host. Retell the facts below to the listener, only in English (translate them if needed). " +
                        "Use only these facts, make nothing up. Pick the most interesting ones. Do not quote the lyrics. " +
                        "Do not repeat the title and artist at the start — they were just said. No lists, no markup."
                },
            )
            appendTrack(track, facts.album, facts.year)
            if (plan.intro.isNotEmpty()) appendLine("Who the artist is: " + plan.intro.joinToString(" "))
            when (plan.topic) {
                Topic.SONG -> appendLine("About the song: " + plan.facts.joinToString(" "))
                Topic.ALBUM -> appendLine("About the album: " + plan.facts.joinToString(" "))
                Topic.ARTIST -> appendLine("From the artist's story: " + plan.facts.joinToString(" "))
                Topic.CIRCLE -> appendLine("The artists around them: " + plan.facts.joinToString(" "))
                null -> Unit
            }
            appendLine(shape(length, facts.album, lang, firstMeet && plan.intro.isNotEmpty(), plan.topic))
            if (plan.repeat) {
                appendLine(
                    if (lang == "ru") "Всё новое уже рассказано: коротко напомни один из этих фактов, начни со слова «Напомню»."
                    else "Everything new has been told: briefly remind one of these facts, starting with \"As a reminder\".",
                )
            } else {
                appendTold(told, lang)
            }
        }
        val tokens = length.sentences * TOKENS_PER_SENTENCE + EXTRA_TOKENS
        // A model on the phone does not stop when asked: it is waited for this long, and left to finish.
        val answer = within(MODEL_TIMEOUT_MS) {
            if (brain != null) brain.complete(prompt, tokens, creative = true) else Brains.ask(context, prompt, tokens, creative = true)?.second
        } ?: return null
        return clean(answer, length, lang)
    }

    /** Without a model: the introduction or one note, read as it is, in the story's language only. */
    private fun plainStory(
        context: Context,
        track: Track,
        facts: Facts,
        length: InfoLength,
        lang: String,
        firstMeet: Boolean,
        keep: (Plan) -> Unit,
        /** The same story without the headings said aloud ("About the song:"), for the history. */
        keepBare: (String) -> Unit,
    ): String? {
        val plan = plan(context, track, facts, length, lang, firstMeet, perFact = 0).also(keep)
        val res = localized(context, lang)
        if (plan.exhausted && plan.intro.isEmpty()) {
            return track.artist?.let { res.getString(R.string.info_all_told, Speech.cleanArtist(it)) }
        }
        val said = plan.facts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        keepBare((plan.intro + listOfNotNull(said)).joinToString(" "))
        val note = said?.let { if (plan.repeat) res.getString(R.string.info_remind, it) else it }?.let { body ->
            when (plan.topic) {
                Topic.SONG -> res.getString(R.string.info_note_song, body)
                Topic.ALBUM -> facts.album?.let { res.getString(R.string.info_note_album, quote(Speech.cleanTitle(it), lang), body) }
                    ?: res.getString(R.string.info_note_album_unnamed, body)
                Topic.ARTIST -> res.getString(R.string.info_note_artist, body)
                Topic.CIRCLE -> res.getString(R.string.info_note_circle, body)
                null -> body
            }
        }
        return (plan.intro + listOfNotNull(note)).joinToString(" ").takeIf { it.isNotBlank() }
    }

    /**
     * A sentence as it is said: no asides in brackets (nested ones too: "(«Нирва́на»; МФА: [...])"), no
     * stress marks, ordinary spaces (Wikipedia puts a no-break space before the dash).
     */
    internal fun speakable(text: String): String {
        var t = text.replace('\u00A0', ' ').replace(STRESS, "")
        while (true) {
            val next = t.replace(BRACKETS, "")
            if (next == t) break
            t = next
        }
        return t.replace(Regex("\\s+([,.;:!?])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun quote(name: String, lang: String) = if (lang == "ru") "«$name»" else "\"$name\""

    /** What one telling is about. */
    private enum class Topic { SONG, ALBUM, ARTIST, CIRCLE }

    /**
     * The introduction (the first time only) and one subject's next facts. [repeat]: all was told, these
     * are the ones told longest ago; [exhausted]: all was told and there is too little to go round again.
     */
    private class Plan(
        val intro: List<String>,
        val topic: Topic?,
        val facts: List<String>,
        val repeat: Boolean = false,
        val exhausted: Boolean = false,
    )

    /**
     * One subject per telling, the first that still has facts not told: the song, else its album, else
     * the artist's story; when all have been told, the first that has any. [lang] null: facts in any
     * language (a model translates them); [perFact] > 0: that many facts for a model to choose from.
     */
    private fun plan(
        context: Context,
        track: Track,
        facts: Facts,
        length: InfoLength,
        lang: String?,
        firstMeet: Boolean,
        perFact: Int,
        rotation: Rotation = Rotation(context),
    ): Plan {
        val intro = if (firstMeet) facts.intro.take(INTRO_SENTENCES) else emptyList()
        fun clean(list: List<String>) = brief(list).filter { standsAlone(it) }
        val artist = artistFacts(facts, lang)
        // The Wikidata item when known: "Kino" and "Кино" share one story.
        val who = facts.artistId ?: track.artist?.let(Journal::key)
        val artistKey = "artist:$who"
        // The artists around this one come in once the listener knows them a little, or their story is told.
        val widen = artist.size - rotation.left(artistKey, artist.size) >= CIRCLE_AFTER || rotation.left(artistKey, artist.size) == 0
        val topics = listOf(
            Triple(Topic.SONG, "song:${track.title}", clean(merged(facts.song, lang))),
            // An album's article often begins with how the band came to be: that belongs to the artist's story.
            Triple(Topic.ALBUM, "album:${facts.album}", clean(merged(facts.albumText, lang)).filterNot { BAND_STORY.containsMatchIn(it) }),
            // The artist's story in its own order: the articles tell it from the beginning.
            Triple(Topic.ARTIST, artistKey, artist),
            Triple(Topic.CIRCLE, "circle:$who", if (widen) facts.circle else emptyList()),
        )
        val sentences = when {
            perFact > 0 -> perFact
            intro.isNotEmpty() -> length.introNote
            else -> length.noteSentences
        }
        if (sentences == 0) return Plan(intro, null, emptyList())
        val any = topics.filter { it.third.isNotEmpty() }
        if (any.isEmpty()) return Plan(intro, null, emptyList())
        // Subjects with untold facts take turns for the same track: the song first, then the album, then
        // the artist's story; each new track begins with its song again.
        val fresh = any.filter { (_, key, list) -> rotation.left(key, list.size) > 0 }
        if (fresh.isNotEmpty()) {
            val turn = fresh[rotation.bump("turn:$who/${track.title}") % fresh.size]
            return Plan(intro, turn.first, rotation.next(turn.second, turn.third, sentences))
        }
        // All told. Too little to go round again: say so. Otherwise remind, the oldest first, each subject in turn.
        if (any.sumOf { it.third.size } < FEW_FACTS) return Plan(intro, null, emptyList(), exhausted = true)
        val turn = any[rotation.bump("repeat:$who") % any.size]
        return Plan(intro, turn.first, rotation.next(turn.second, turn.third, sentences), repeat = true)
    }

    /**
     * The artist's facts as one story: the article in its own order, and now and then a fact from the
     * other sources (members, concerts, listeners), which would otherwise wait behind a long article
     * and never be heard.
     */
    private fun artistFacts(facts: Facts, lang: String?): List<String> {
        val texts = facts.artist.filter { lang == null || it.lang == lang }
        val story = texts.firstOrNull()?.facts.orEmpty()
        // A fact the article already gives ("formed in 1987") is not woven in again.
        val told = story.joinToString(" ").lowercase(Locale.ROOT)
        val others = merged(texts.drop(1)).filterNot { fact ->
            val marks = Regex("""\b\d{4}\b""").findAll(fact).map { it.value }.toList()
            marks.isNotEmpty() && marks.all { told.contains(it) } && fact.length < SHORT_FACT_CHARS / 2
        }
        val out = ArrayList<String>()
        var next = 0
        story.forEachIndexed { i, fact ->
            out += fact
            if ((i + 1) % WEAVE_EVERY == 0 && next < others.size) out += others[next++]
        }
        out += others.drop(next)
        return brief(out).filter { standsAlone(it) }
    }

    /**
     * "To hear Foo Fighters: double tap on the back" — when a gesture plays the circle's artists, the
     * note that named them says which.
     */
    private fun circleOffer(context: Context, lang: String, artist: String): String? {
        val prefs = Prefs(context)
        val gesture = Gesture.entries.firstOrNull { prefs.action(it, prefs.lastPlayer) == Action.PLAY_CIRCLE } ?: return null
        val res = localized(context, lang)
        val how = res.getString(com.knobnext.ui.gestureLabel(gesture)).replaceFirstChar { it.lowercase(Locale.ROOT) }
        return res.getString(R.string.info_circle_offer, artist, how)
    }

    /** Forgets that the artist was introduced and how far their story got: the next story starts afresh. */
    fun forgetArtist(context: Context, artist: String, id: String?) {
        File(dir(context), "told-" + sha1(Journal.key(artist))).delete()
        for (who in listOfNotNull(Journal.key(artist), id)) {
            Rotation(context).forget("artist:$who")
            Rotation(context).forget("circle:$who")
        }
    }

    /** Forgets every artist: whom KnobNext introduced and how far each story got. */
    fun forgetEveryone(context: Context) {
        dir(context).listFiles { f -> f.name.startsWith("told-") }?.forEach { it.delete() }
        Rotation(context).forgetAll()
    }

    /**
     * A sentence that makes sense on its own. Cut out of an article, one that leans on the one before
     * ("He also…", "In the same year…", "This became…") just confuses.
     */
    private fun standsAlone(sentence: String): Boolean {
        val s = sentence.trim().lowercase(Locale.ROOT)
        return DEPENDENT_STARTS.none { s.startsWith(it) } &&
            sentence.count { it == ',' } <= MAX_COMMAS &&
            // "…parodies were shot by various bloggers:" leads into a list that is not there.
            !s.endsWith(":") &&
            // "I", "we", "us": a quote from someone, out of its context.
            !FIRST_PERSON.containsMatchIn(sentence)
    }

    /**
     * Facts that are quick to hear: without asides in brackets (dates, original spellings), and only the
     * short ones when there are any.
     */
    private fun brief(facts: List<String>): List<String> {
        val compact = facts.map(::speakable).filter { it.length >= MIN_FACT_CHARS }
        return compact.filter { it.length <= SHORT_FACT_CHARS }.ifEmpty { compact }
    }

    private fun StringBuilder.appendTrack(track: Track, album: String?, year: String?) {
        appendLine("Track: ${Speech.cleanTitle(track.title)}")
        track.artist?.let { appendLine("Artist: ${Speech.cleanArtist(it)}") }
        album?.let { appendLine("Album: $it") }
        year?.let { appendLine("Year: $it") }
    }

    /**
     * What was told before, so the facts change: only the last few stories, each by its first sentence.
     * Whole stories made the prompt grow with every listen, and the model took seconds longer to read
     * it before answering: the pause after the title grew.
     */
    private fun StringBuilder.appendTold(told: List<String>, lang: String) {
        if (told.isEmpty()) return
        appendLine(if (lang == "ru") "Это уже рассказывали — расскажи другие факты:" else "Already told — tell other facts:")
        told.takeLast(TOLD_IN_PROMPT).forEach { story ->
            val gist = sentences(story).firstOrNull() ?: story
            appendLine("- ${gist.take(MAX_TOLD_CHARS)}")
        }
    }

    /** What the model is asked to tell: the introduction the first time, then one note on one subject. */
    private fun shape(length: InfoLength, album: String?, lang: String, firstMeet: Boolean, topic: Topic?): String {
        val ru = lang == "ru"
        val albumName = album?.let { " " + quote(it, lang) } ?: ""
        val known = if (ru) "Слушатель уже знает исполнителя — не представляй его заново. " else "The listener already knows the artist — do not introduce them again. "
        val what = when {
            firstMeet -> if (ru) {
                "Слушатель впервые слышит об этом исполнителе: начни с короткой визитки — кто это, откуда, когда начали, в каком жанре." +
                    if (length == InfoLength.SHORT) "" else " Затем одна короткая заметка о песне или альбоме."
            } else {
                "The listener hears about this artist for the first time: start with a short introduction — who they are, where they come from, when they began, what they play." +
                    if (length == InfoLength.SHORT) "" else " Then one short note about the song or the album."
            }
            topic == Topic.SONG -> known + if (ru) "Одна короткая заметка о самой песне." else "One short note about the song itself."
            topic == Topic.ALBUM -> known + if (ru) "Одна короткая заметка об альбоме$albumName." else "One short note about the album$albumName."
            topic == Topic.ARTIST -> known + if (ru) "Одна короткая заметка: следующий эпизод из истории исполнителя." else "One short note: the next part of the artist's story."
            topic == Topic.CIRCLE -> known + if (ru) {
                "Одна короткая заметка о том, с кем связан исполнитель: другие группы участников или кто на него повлиял."
            } else {
                "One short note on whom the artist is connected with: the members' other bands, or their influences."
            }
            else -> known + if (ru) {
                "Одна короткая заметка на одну тему: о самой песне, если есть что сказать; иначе об альбоме$albumName; иначе новый эпизод из истории исполнителя."
            } else {
                "One short note on one subject: the song itself if there is something to say; else the album$albumName; else a new part of the artist's story."
            }
        }
        return "$what ${brevity(length, ru)}"
    }

    private fun brevity(length: InfoLength, ru: Boolean): String =
        if (ru) "Не больше ${length.sentences} предложений, каждое короткое — до $MAX_WORDS слов, без дат в скобках и длинных перечислений."
        else "No more than ${length.sentences} sentences, each short — up to $MAX_WORDS words, no dates in brackets, no long lists."

    private fun clean(answer: String, length: InfoLength, lang: String): String? {
        val text = sentences(answer.replace(Regex("[*#_`]"), "").replace(BRACKETS, "").trim())
            .take(length.sentences + 1).joinToString(" ")
        // A model that ignored the language is no better than none.
        return text.takeIf { it.length >= MIN_ANSWER_CHARS && inLanguage(it, lang) }
    }

    // ---- Facts ----

    /**
     * Everything found about the track, fetched all at once and kept on the phone, with what the artist
     * base gathered ahead ([ArtistBase]): its facts alone when nothing could be fetched.
     */
    suspend fun facts(context: Context, track: Track, lang: String): Facts? {
        // The story, the card and the "news" mark ask about the same track at once: it is fetched once.
        val key = "${track.artist}\u0000${track.title}\u0000${track.album}\u0000$lang"
        val running = synchronized(fetching) {
            fetching[key]?.takeIf { it.isActive } ?: background.async { factsNow(context, track, lang) }.also { fetching[key] = it }
        }
        return try {
            running.await()
        } finally {
            synchronized(fetching) { if (fetching[key] === running && !running.isActive) fetching.remove(key) }
        }
    }

    private val fetching = HashMap<String, kotlinx.coroutines.Deferred<Facts?>>()

    private suspend fun factsNow(context: Context, track: Track, lang: String): Facts? {
        // No album from the player (YouTube, SoundCloud): ListenBrainz says which recording and album it is.
        val match = if (track.album == null) within(LOOKUP_WAIT_MS) { ListenBrainz.lookup(context, track.artist, track.title) } else null
        val known = if (match?.album != null) track.copy(album = match.album) else track
        var found = found(context, known, lang)
        // Nothing under the player's spelling ("Kino", a title with a channel's additions): under MusicBrainz's.
        if ((found == null || found.empty) && match != null &&
            (match.artist != track.artist || match.title != Speech.cleanTitle(track.title))
        ) {
            found = found(context, known.copy(title = match.title, artist = match.artist), lang) ?: found
        }
        return ArtistBase.enrich(context, known, lang, found)
    }

    private suspend fun found(context: Context, track: Track, lang: String): Facts? {
        val file = File(dir(context), FACTS_PREFIX + sha1("${track.title}\u0000${track.artist}\u0000$lang"))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FACTS_FRESH_MS) {
            runCatching { readFacts(JSONObject(file.readText())) }.getOrNull()?.let { cached ->
                deepenLater(context, track, lang, file, cached)
                return cached
            }
        }
        val fetched = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val title = Speech.cleanTitle(track.title)
                    val artist = track.artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() }
                    // Each source has its own time: a slow one (a VPN, a busy server) is left out, and what the
                    // others found is still told. One shared limit used to throw everything away. A request
                    // does not stop when cancelled: it is left to finish on its own, not waited for.
                    suspend fun <T> timed(block: suspend () -> T?): T? = within(SOURCE_TIMEOUT_MS, block)
                    val more = async { timed { MusicSources.gather(context, title, artist, track.album, lang) } }
                    val song = async { timed { findArticle(lang) { l -> findSong(l, title, artist) } } }
                    val about = async { artist?.let { a -> timed { findArticle(lang) { l -> findArtist(l, a) } } } }
                    val brainz = async { if (artist != null && (track.album == null || track.year == null)) timed { musicBrainz(title, artist) } else null }
                    // The album's article: right away when the player names the album, else once MusicBrainz has.
                    val albumText = async {
                        val name = track.album ?: brainz.await()?.first
                        name?.let { a -> timed { findArticle(lang) { l -> findAlbum(l, a, artist) } } }
                    }
                    // Wikidata, for the very items behind the articles found.
                    val data = async {
                        val songArticle = song.await()
                        val albumArticle = albumText.await()
                        val artistArticle = about.await()
                        timed {
                            Wikidata.gather(
                                context, lang, songArticle?.page, albumArticle?.page, artistArticle?.page,
                                // From when the articles are here, not from the start: a slow article
                                // used to leave Wikidata no time at all.
                                deadline = System.currentTimeMillis() + WIKIDATA_DEADLINE_MS,
                                said = listOfNotNull(songArticle, albumArticle, artistArticle)
                                    .mapNotNull { it.text?.facts?.joinToString(" ") }.joinToString(" ").let(::speakable),
                                albumKnown = track.album != null,
                            )
                        }
                    }
                    val b = brainz.await()
                    val m = more.await()
                    val d = data.await()
                    val artistText = about.await()?.text
                    val songText = song.await()?.text
                    val albumArticle = albumText.await()?.text
                    // Last.fm's texts are often Wikipedia's own: only where Wikipedia has nothing in this language.
                    val lf = m?.lastFm
                    val wikiArtist = artistText?.takeIf { it.lang == lang }
                    val lfArtist = lf?.artist?.takeIf { wikiArtist == null && (artistText == null || it.lang == lang) }
                    Facts(
                        song = listOfNotNull(songText, d?.song) + m?.song.orEmpty() +
                            listOfNotNull(lf?.song?.takeIf { songText == null }, lf?.songNumbers?.takeIf { it.isNotEmpty() }?.let { Text(it, lang) }),
                        artist = listOfNotNull(artistText, lfArtist, d?.artist) + m?.artist.orEmpty() +
                            listOfNotNull(lf?.artistNumbers?.takeIf { it.isNotEmpty() }?.let { Text(it, lang) }),
                        album = track.album ?: b?.first ?: m?.albumName,
                        year = track.year ?: b?.second ?: m?.year,
                        albumText = listOfNotNull(albumArticle, d?.album) + m?.album.orEmpty() + listOfNotNull(lf?.album?.takeIf { albumArticle == null }),
                        intro = intro(context, artist, wikiArtist ?: lfArtist, d?.artistIntro.orEmpty().ifEmpty { m?.basics.orEmpty() }, lang),
                        circle = d?.circle.orEmpty() + listOfNotNull(lf?.similar),
                        related = d?.related.orEmpty() + lf?.similarNames.orEmpty(),
                        relatedIds = d?.relatedIds.orEmpty(),
                        artistId = d?.artistId,
                        mbid = d?.mbid,
                    )
                }
            }
        }
        // Offline or slow: whatever was found before, however old.
        if (fetched == null || fetched.empty) {
            val old = if (file.exists()) runCatching { readFacts(JSONObject(file.readText())) }.getOrNull() else null
            if (old != null || fetched == null) return old
            // Nothing quick at all: a small artist may still be in MusicBrainz, worth the wait.
            return within(DEEP_WAIT_MS) { deepen(context, track, lang, file, fetched) }?.takeIf { !it.empty } ?: fetched
        }
        runCatching { file.writeText(writeFacts(fetched).toString()) }
        trimCache(context)
        deepenLater(context, track, lang, file, fetched)
        return fetched
    }

    /**
     * An artist the round card's circle named, by name alone (no track playing of theirs): who they are
     * and the artists around them, for the next step along the circle.
     */
    suspend fun aboutArtist(context: Context, name: String, lang: String): Facts? {
        val artist = Speech.cleanArtist(name).takeIf { it.isNotBlank() } ?: return null
        val file = File(dir(context), ARTIST_PREFIX + sha1("${normalize(artist)}\u0000$lang"))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FACTS_FRESH_MS) {
            runCatching { readFacts(JSONObject(file.readText())) }.getOrNull()?.let { return it }
        }
        val found = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val article = within(SOURCE_TIMEOUT_MS) { findArticle(lang) { l -> findArtist(l, artist) } }
                val d = within(SOURCE_TIMEOUT_MS) {
                    Wikidata.gather(
                        context, lang, null, null, article?.page,
                        deadline = System.currentTimeMillis() + WIKIDATA_DEADLINE_MS,
                        said = article?.text?.facts?.joinToString(" ")?.let(::speakable).orEmpty(),
                    )
                }
                Facts(
                    song = emptyList(),
                    artist = listOfNotNull(article?.text, d?.artist),
                    album = null,
                    year = null,
                    albumText = emptyList(),
                    intro = intro(context, artist, article?.text?.takeIf { it.lang == lang }, d?.artistIntro.orEmpty(), lang),
                    circle = d?.circle.orEmpty(),
                    related = d?.related.orEmpty(),
                    relatedIds = d?.relatedIds.orEmpty(),
                    artistId = d?.artistId,
                    mbid = d?.mbid,
                )
            }
        } ?: return null
        if (!found.empty || found.intro.isNotEmpty()) {
            runCatching { file.writeText(writeFacts(found).toString()) }
            trimCache(context)
        }
        return found
    }

    /**
     * The recording itself for the round card (who plays on it, samples, covers): MusicBrainz, a request
     * a second, so it comes a few seconds after the card opens, and is kept.
     */
    suspend fun record(context: Context, track: Track, lang: String, mbid: String?): Brainz.Record? {
        val artist = track.artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() } ?: return null
        val file = File(dir(context), RECORD_PREFIX + sha1("${normalize(Speech.cleanTitle(track.title))}\u0000${normalize(artist)}\u0000$lang"))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FACTS_FRESH_MS) {
            runCatching { Brainz.Record.fromJson(JSONObject(file.readText())) }.getOrNull()?.let { return it }
        }
        val found = withContext(Dispatchers.IO) {
            runCatching { Brainz.record(lang, track.title, artist, track.album, mbid, track.year) }.getOrNull()
        } ?: return null
        runCatching { file.writeText(found.toJson().toString()) }
        trimCache(context)
        return found
    }

    /** What a card says of the artist: who they are, then the rest of their facts, a sentence each. */
    fun aboutLines(facts: Facts, lang: String): List<String> {
        val seen = HashSet<String>()
        return (facts.intro + artistFacts(facts, lang)).filter { seen.add(normalize(it).take(DUPLICATE_CHARS)) }
    }

    /** The sentence of [circle] that names [name]: why they are in it ("Dave Grohl also plays in …"). */
    fun why(circle: List<String>, name: String): String? {
        val n = normalize(Speech.cleanArtist(name))
        return circle.firstOrNull { normalize(it).contains(n) }
    }

    // ---- MusicBrainz, in the background ----

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [block] in the background, waited for at most [ms]. A network request or a model on the phone does
     * not stop when cancelled, so a plain timeout would wait it out anyway: this one leaves it to finish.
     */
    private suspend fun <T> within(ms: Long, block: suspend () -> T?): T? {
        val running = background.async { runCatching { block() }.getOrNull() }
        return withTimeoutOrNull(ms) { running.await() }
    }
    private val deepening = java.util.Collections.synchronizedSet(HashSet<String>())

    /** MusicBrainz's facts join the cached ones for the next telling (it allows a request a second). */
    private fun deepenLater(context: Context, track: Track, lang: String, file: File, base: Facts) {
        if (base.deep || !deepening.add(file.name)) return
        background.launch {
            try {
                deepen(context, track, lang, file, base)
            } finally {
                deepening.remove(file.name)
            }
        }
    }

    private suspend fun deepen(context: Context, track: Track, lang: String, file: File, base: Facts): Facts? {
        val artist = track.artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() } ?: return null
        val said = (base.song + base.artist + base.albumText).flatMap { it.facts }.joinToString(" ") + " " + base.intro.joinToString(" ")
        val b = runCatching { Brainz.gather(context, lang, track.title, artist, base.album ?: track.album, said, base.mbid) }.getOrNull()
            // Not found, or not reachable: tried again next time.
            ?: return null
        val intro = base.intro.ifEmpty {
            if (b.basics.isEmpty()) emptyList() else listOf(localized(context, lang).getString(R.string.info_intro_basics, artist)) + b.basics
        }
        val merged = Facts(
            song = base.song + listOfNotNull(b.song),
            artist = base.artist + listOfNotNull(b.artist),
            album = base.album,
            year = base.year,
            albumText = base.albumText,
            intro = intro,
            circle = base.circle + b.circle,
            related = base.related + b.related,
            relatedIds = base.relatedIds,
            artistId = base.artistId,
            coverUrl = b.coverUrl ?: base.coverUrl,
            pages = b.pages + base.pages,
            deep = true,
            mbid = base.mbid,
        )
        // Nothing to say even now: not kept, so the next look is a fresh one.
        if (!merged.empty) runCatching { file.writeText(writeFacts(merged).toString()) }
        // The story was told before these came: the history's card gets the cover and pages now.
        if (Prefs(context).infoHistory && (b.coverUrl != null || b.pages.isNotEmpty())) {
            Journal.learned(context, artist, base.artistId, base.album ?: track.album, b.coverUrl, b.pages)
        }
        return merged
    }

    /**
     * Who the artist is: the article's opening sentence when it defines them ("Nirvana — американская
     * рок-группа…"), else Wikidata's basics (when and where they began, genres).
     */
    private fun intro(context: Context, artist: String?, article: Text?, basics: List<String>, lang: String): List<String> {
        val lead = article?.takeIf { it.lang == lang }?.facts?.firstOrNull()?.let { brief(listOf(it)).firstOrNull() }
        val defines = lead != null && if (lang == "ru") {
            lead.indexOf(" — ").let { it in 1..MAX_INTRO_NAME } || lead.contains(" является ")
        } else {
            Regex("""\b(is|was|are|were)\b""").containsMatchIn(lead.take(MAX_INTRO_NAME + 20))
        }
        if (defines) return listOf(namedAsKnown(lead!!, artist, lang))
        // Wikidata's basics say nothing of whom they are about: the name goes first.
        if (basics.isEmpty() || artist == null) return emptyList()
        return listOf(localized(context, lang).getString(R.string.info_intro_basics, artist)) + basics
    }

    /**
     * The definition under the name the listener knows: an article that opens with another one ("Grebz —
     * украинский коллектив…" for «Грибы») would leave them wondering who that is.
     */
    private fun namedAsKnown(lead: String, artist: String?, lang: String): String {
        if (artist == null || lang != "ru") return lead
        val dash = lead.indexOf(" — ").takeIf { it in 1..MAX_INTRO_NAME } ?: return lead
        val subject = lead.substring(0, dash).trim().trimEnd(',')
        val known = normalize(artist)
        val said = normalize(subject)
        if (known.isEmpty() || said.contains(known) || known.contains(said)) return lead
        return "$artist, или $subject, — " + lead.substring(dash + 3)
    }

    /**
     * An article in [lang]; found only in another language, it is followed to its [lang] version, and
     * kept foreign (for a model to translate) only when there is no such version.
     */
    private fun findArticle(lang: String, find: (String) -> String?): Article? {
        find(lang)?.let { page -> return Article(text(lang, page)?.let { Text(it, lang) }, Wikidata.Page(lang, page)) }
        val other = if (lang == "ru") "en" else "ru"
        val page = find(other) ?: return null
        translation(other, page, lang)?.let { translated ->
            text(lang, translated)?.let { return Article(Text(it, lang), Wikidata.Page(lang, translated)) }
        }
        return Article(text(other, page)?.let { Text(it, other) }, Wikidata.Page(other, page))
    }

    /** An article found: its facts, and where it is (for the Wikidata item behind it). */
    private class Article(val text: Text?, val page: Wikidata.Page)

    /** The song's own article, when Wikipedia has one that is about a song by this artist. */
    private fun findSong(lang: String, title: String, artist: String?): String? =
        find(lang, "\"$title\" ${artist.orEmpty()} " + if (lang == "ru") "песня" else "song", title, SONG_WORDS, artist, startsWith = false)

    /** The album's article: a page named after it that is about a record by this artist. */
    private fun findAlbum(lang: String, album: String, artist: String?): String? {
        val name = Speech.cleanTitle(album)
        return find(lang, "\"$name\" ${artist.orEmpty()} " + if (lang == "ru") "альбом" else "album", name, ALBUM_WORDS, artist, startsWith = false)
    }

    /** The artist's article: a page named after them that is about a musician or a band. */
    private fun findArtist(lang: String, artist: String): String? =
        find(lang, "$artist " + if (lang == "ru") "музыка" else "music", artist, ARTIST_WORDS, null, startsWith = true)

    private fun find(lang: String, query: String, name: String, kind: List<String>, artist: String?, startsWith: Boolean): String? {
        for (page in search(lang, query)) {
            val title = normalize(page)
            if (if (startsWith) !title.startsWith(normalize(name)) else !title.contains(normalize(name))) continue
            val summary = summary(lang, page) ?: continue
            val about = (summary.optString("description") + " " + summary.optString("extract")).lowercase()
            if (kind.none { about.contains(it) }) continue
            if (artist != null && !about.contains(normalize(artist).substringBefore(' '))) continue
            return page
        }
        return null
    }

    private fun search(lang: String, query: String): List<String> {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=$SEARCH_RESULTS&srsearch=" +
            URLEncoder.encode(query, "UTF-8")
        val results = getJson(url)?.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it).getString("title") }
    }

    private fun summary(lang: String, page: String): JSONObject? =
        getJson("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(page.replace(' ', '_'), "UTF-8"))
            ?.takeIf { it.optString("type") != "disambiguation" }

    /** The whole article as plain text, cut into facts: sentences of a fair length, before the reference sections. */
    private fun text(lang: String, page: String): List<String>? {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exsectionformat=plain" +
            "&redirects=1&format=json&utf8=1&titles=" + URLEncoder.encode(page, "UTF-8")
        val pages = getJson(url)?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val extract = pages.keys().asSequence().map { pages.getJSONObject(it).optString("extract") }.firstOrNull { it.isNotBlank() }
            ?: return null
        return splitFacts(extract).takeIf { it.isNotEmpty() }
    }

    /** A text cut into facts: sentences of a fair length, before the reference sections. */
    internal fun splitFacts(text: String): List<String> {
        val facts = ArrayList<String>()
        for (line in text.take(MAX_ARTICLE_CHARS).lines()) {
            val l = line.trim()
            if (STOP_SECTIONS.any { l.equals(it, ignoreCase = true) }) break
            if (l.length < MIN_LINE_CHARS) continue
            // Asides go before the line is cut into sentences: "(род. 26 августа 1976, Уфа)" would cut the
            // definition at "род.", and a transcription in brackets makes it look too long.
            sentences(speakable(l)).filterTo(facts) { it.length in MIN_FACT_CHARS..MAX_FACT_CHARS }
        }
        return facts
    }

    /** The title of the same article in [to], from Wikipedia's links between languages. */
    private fun translation(from: String, page: String, to: String): String? {
        val url = "https://$from.wikipedia.org/w/api.php?action=query&prop=langlinks&format=json&utf8=1&lllang=$to&titles=" +
            URLEncoder.encode(page, "UTF-8")
        val pages = getJson(url)?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        for (key in pages.keys()) {
            val link = pages.getJSONObject(key).optJSONArray("langlinks")?.optJSONObject(0) ?: continue
            return link.optString("*").takeIf { it.isNotBlank() }
        }
        return null
    }

    /** The album and year of the earliest release with this recording. */
    /** What the last album search found, for the checks. */
    @Volatile internal var lastAlbumSearch: String? = null

    internal fun musicBrainz(title: String, artist: String): Pair<String?, String?>? {
        val song = "recording:\"${title.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\""
        // The song's many versions (live, compilations) all score 100: the studio album's is asked for first,
        // then any official release, so that the first listed is not a bootleg or a sampler.
        val studio = albumSearch("$song AND status:official AND primarytype:album AND NOT secondarytype:live AND NOT secondarytype:compilation")
        if (studio?.first != null) return studio
        return albumSearch("$song AND status:official") ?: studio
    }

    private fun albumSearch(query: String): Pair<String?, String?>? {
        val json = brainzSpaced("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=$ALBUM_SEARCH_LIMIT&query=" + URLEncoder.encode(query, "UTF-8"))
            ?: return null
        val found = json.optJSONArray("recordings") ?: return null
        val recordings = (0 until found.length()).mapNotNull { found.optJSONObject(it) }.filter { it.optInt("score") >= MIN_SCORE }
        lastAlbumSearch = "${recordings.size} из ${found.length()} записей"
        if (recordings.isEmpty()) return null
        val year = recordings.mapNotNull { r -> r.optString("first-release-date").take(4).takeIf { it.length == 4 && it.all(Char::isDigit) } }.minOrNull()
        val releases = recordings.flatMap { r -> r.optJSONArray("releases")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty() }
            .filter { it.optString("status") == "Official" && it.optString("title").isNotBlank() && !DATED_BOOTLEG.containsMatchIn(it.optString("title")) }
        fun kind(r: JSONObject) = r.optJSONObject("release-group")?.optString("primary-type")
        fun plain(r: JSONObject) = (r.optJSONObject("release-group")?.optJSONArray("secondary-types")?.length() ?: 0) == 0
        // The earliest official studio album; else an EP or a single.
        val first = (releases.filter { kind(it) == "Album" && plain(it) }.ifEmpty { releases.filter { kind(it) == "EP" || kind(it) == "Single" } })
            .minByOrNull { it.optString("date").ifBlank { "9999" } }
        // The year the album first came out: its release group's. The releases found are often reissues
        // (Nevermind's 2011 remaster), and a recording's own date is the remaster's.
        val released = first?.optJSONObject("release-group")?.optString("id")?.takeIf { it.isNotBlank() }?.let { id ->
            brainzSpaced("https://musicbrainz.org/ws/2/release-group/$id?fmt=json")?.optString("first-release-date")
                ?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        }
        return first?.optString("title") to (released ?: year.takeIf { first == null })
    }

    /** MusicBrainz allows a request a second: these searches keep to it (two can follow one another). */
    private val brainzLock = Any()
    private var brainzLast = 0L

    private fun brainzSpaced(url: String): JSONObject? = synchronized(brainzLock) {
        val wait = brainzLast + BRAINZ_SPACING_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        try {
            getJson(url)
        } finally {
            brainzLast = System.currentTimeMillis()
        }
    }

    internal fun getJson(url: String, userAgent: String = USER_AGENT): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = REQUEST_TIMEOUT_MS
            conn.readTimeout = REQUEST_TIMEOUT_MS
            // Both services ask apps to say who they are.
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    // ---- Remembering ----

    /** Which facts of each article were told last, so the next telling takes the following ones. */
    private class Rotation(context: Context) {
        private val sp = context.getSharedPreferences("trackinfo_rotation", Context.MODE_PRIVATE)

        /** The counters as they were before this telling moved them, for [undo]. */
        private val before = HashMap<String, Int>()

        private fun moving(k: String) {
            if (k !in before) before[k] = sp.getInt(k, 0)
        }

        /** The telling did not happen (the model gave nothing): its facts were not told after all. */
        fun undo() {
            if (before.isEmpty()) return
            val e = sp.edit()
            before.forEach { (k, v) -> e.putInt(k, v) }
            e.apply()
            before.clear()
        }

        /** How many of [size] facts under [key] have not been told yet. */
        fun left(key: String, size: Int): Int = (size - sp.getInt(sha1(key), 0)).coerceAtLeast(0)

        /** A counter under [key]: its value before this call. */
        fun bump(key: String): Int {
            val k = sha1(key)
            val v = sp.getInt(k, 0)
            moving(k)
            sp.edit().putInt(k, v + 1).apply()
            return v
        }

        fun forget(key: String) = sp.edit().remove(sha1(key)).apply()

        fun forgetAll() = sp.edit().clear().apply()

        fun next(key: String, facts: List<String>, n: Int): List<String> {
            if (facts.isEmpty()) return emptyList()
            val k = sha1(key)
            // The first sentence usually just says what the thing is; start after it, come back to it last.
            val start = (sp.getInt(k, 0) + 1) % facts.size
            val taken = (0 until minOf(n, facts.size)).map { facts[(start + it) % facts.size] }
            moving(k)
            sp.edit().putInt(k, sp.getInt(k, 0) + taken.size).apply()
            return taken
        }
    }

    /** The last stories about an artist, so the model is asked for something new. */
    private class History(context: Context, artist: String?) {
        private val file = artist?.let { File(dir(context), "told-" + sha1(Journal.key(it))) }

        fun told(): List<String> = file?.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

        fun add(story: String): String {
            file?.let { f -> runCatching { f.writeText((told() + story.replace('\n', ' ')).takeLast(MAX_TOLD).joinToString("\n")) } }
            return story
        }
    }

    private fun writeFacts(f: Facts): JSONObject {
        val o = JSONObject()
        fun putText(name: String, texts: List<Text>) {
            o.put(name, JSONArray(texts.map { JSONObject().put("lang", it.lang).put("facts", JSONArray(it.facts)) }))
        }
        putText("song", f.song)
        putText("artist", f.artist)
        putText("albumText", f.albumText)
        f.album?.let { o.put("album", it) }
        f.year?.let { o.put("year", it) }
        o.put("intro", JSONArray(f.intro))
        o.put("circle", JSONArray(f.circle))
        o.put("related", JSONArray(f.related))
        o.put("relatedIds", JSONArray(f.relatedIds))
        f.artistId?.let { o.put("artistId", it) }
        f.coverUrl?.let { o.put("coverUrl", it) }
        f.mbid?.let { o.put("mbid", it) }
        o.put("pages", JSONObject(f.pages))
        o.put("deep", f.deep)
        return o
    }

    private fun readFacts(o: JSONObject): Facts {
        fun text(name: String): List<Text> {
            val arr = o.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val list = t.getJSONArray("facts")
                Text((0 until list.length()).map { list.getString(it) }, t.getString("lang"))
            }
        }
        fun strings(name: String) = o.optJSONArray(name)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        return Facts(
            text("song"), text("artist"), o.optString("album").ifBlank { null }, o.optString("year").ifBlank { null }, text("albumText"),
            strings("intro"), strings("circle"), strings("related"), strings("relatedIds"), o.optString("artistId").ifBlank { null },
            coverUrl = o.optString("coverUrl").ifBlank { null },
            pages = o.optJSONObject("pages")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }.orEmpty(),
            deep = o.optBoolean("deep"),
            mbid = o.optString("mbid").ifBlank { null },
        )
    }

    // ---- Helpers ----

    private fun sentences(text: String) = Regex("(?<=[.!?…])\\s+").split(text).map { it.trim() }.filter { it.isNotBlank() }

    /** Mostly Cyrillic letters for Russian, mostly Latin for English. */
    private fun inLanguage(text: String, lang: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val cyrillic = letters.count { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
        return if (lang == "ru") cyrillic * 10 >= letters.length * 6 else cyrillic * 10 <= letters.length * 2
    }

    internal fun localized(context: Context, lang: String): Context {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale(lang))
        return context.createConfigurationContext(config)
    }

    private fun dir(context: Context) = File(context.filesDir, "trackinfo").apply { mkdirs() }

    /** Whether the phone has a network that reaches the internet. */
    internal fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    /** Facts kept in an older shape go; of the current ones, only the latest [MAX_CACHED]. */
    private fun trimCache(context: Context) {
        dir(context).listFiles { f -> f.name.startsWith("facts") && !f.name.startsWith(FACTS_PREFIX) }?.forEach { it.delete() }
        for (prefix in listOf(FACTS_PREFIX, ARTIST_PREFIX, RECORD_PREFIX, DATES_PREFIX)) {
            val files = dir(context).listFiles { f -> f.name.startsWith(prefix) } ?: continue
            if (files.size <= MAX_CACHED) continue
            files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHED).forEach { it.delete() }
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val SONG_WORDS = listOf("song", "single", "track", "песн", "сингл", "композиц", "трек", "баллад")
    private val ALBUM_WORDS = listOf("album", " ep ", "compilation", "soundtrack", "альбом", "сборник", "саундтрек", "пластинк")
    private val ARTIST_WORDS = listOf(
        "band", "singer", "musician", "rapper", "group", "duo", "composer", "dj", "songwriter", "artist",
        "групп", "певец", "певица", "музыкант", "рэпер", "исполнител", "композитор", "коллектив", "дуэт",
    )

    /** Where the facts end and the lists begin. */
    private val STOP_SECTIONS = listOf(
        "Примечания", "Ссылки", "Литература", "Источники", "Список композиций", "Участники записи", "Дискография",
        "Notes", "References", "External links", "Track listing", "Personnel", "Discography", "See also", "Further reading",
    )

    private const val SEARCH_RESULTS = 2
    private const val REQUEST_TIMEOUT_MS = 6_000
    /**
     * A safety net only: every source has its own [SOURCE_TIMEOUT_MS], and the longest chain (MusicBrainz's
     * album, its article, then Wikidata) is three of them. Past this everything found would be lost.
     */
    private const val FETCH_TIMEOUT_MS = 35_000L

    /** One source (an article, a database) is waited for this long; the others are not held up. */
    private const val SOURCE_TIMEOUT_MS = 10_000L

    // The last Wikidata request starts this long after the articles came, each can take up to REQUEST_TIMEOUT_MS.
    private const val WIKIDATA_DEADLINE_MS = 6_000L
    // Past this, the story is no longer "right after the title": the facts are read as they are.
    private const val MODEL_TIMEOUT_MS = 10_000L
    private const val FACTS_FRESH_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_ARTICLE_CHARS = 20_000
    private const val MIN_LINE_CHARS = 40
    private const val MIN_FACT_CHARS = 30
    private const val MAX_FACT_CHARS = 300
    private const val SHORT_FACT_CHARS = 150
    private const val MAX_WORDS = 15
    private val BRACKETS = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]")
    private val STRESS = Regex("[\\u0300\\u0301]")
    private val FIRST_PERSON = Regex(
        "(?<!\\p{L})(я|мы|нас|нам|нами|мне|меня|мной|мой|моя|моё|мои|наш|наша|наше|наши|i|we|us|my|our|me)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    private const val FACTS_FOR_MODEL = 5
    private const val MAX_TOLD = 5
    private const val INTRO_SENTENCES = 2

    /** Fewer facts than this, all told: say there is nothing more rather than go round again. */
    private const val FEW_FACTS = 3
    private const val MAX_INTRO_NAME = 90
    private const val MAX_COMMAS = 4

    /** How sentences that lean on the one before begin (lowercased). */
    private val DEPENDENT_STARTS = listOf(
        "он ", "она ", "они ", "оно ", "его ", "её ", "ее ", "их ", "ему ", "ей ", "им ", "это ", "этот ", "эта ", "эти ", "этом ",
        "там ", "тогда ", "также ", "тоже ", "кроме того", "однако", "позже", "позднее", "впоследствии", "после этого", "затем ",
        "вскоре", "в том же", "в следующем", "в этом ", "при этом", "поэтому", "но ", "и ", "а ", "так ", "здесь ",
        "с одной стороны", "с другой стороны", "во-первых", "во-вторых", "впрочем", "тем не менее", "в результате",
        "в итоге", "благодаря этому", "из-за этого", "на этом", "вместе с тем", "помимо этого",
        "he ", "she ", "they ", "it ", "his ", "her ", "their ", "this ", "these ", "that ", "those ", "also ", "however",
        "later", "then ", "soon ", "the following", "in the same", "afterwards", "after that", "but ", "and ", "so ", "there ",
    )
    private const val MAX_TOLD_CHARS = 140
    private const val TOLD_IN_PROMPT = 3
    private const val TOKENS_PER_SENTENCE = 50
    private const val EXTRA_TOKENS = 60
    private const val MIN_ANSWER_CHARS = 20
    private const val MIN_SCORE = 90
    private const val MAX_CACHED = 300

    /** How KnobNext signs its requests: the name and where it lives, as the services ask. */
    const val USER_AGENT = "KnobNext (https://github.com/tbmgmos/KnobNext)"

    /** The same, without the address: for a service that refuses the first (see [MusicSources.checkLastFm]). */
    val PLAIN_AGENT = "KnobNext/" + BuildConfig.VERSION_NAME

    private const val ALBUM_SEARCH_LIMIT = 25
    private const val BRAINZ_SPACING_MS = 1_100L

    /** A live bootleg named by its date and place. */
    private val DATED_BOOTLEG = Regex("^\\d{4}[-‐]\\d\\d[-‐]\\d\\d:")

    /** ListenBrainz is waited for this long to say which album a track is from. */
    private const val LOOKUP_WAIT_MS = 4_000L

    /** The cached facts' shape: a new one when the facts gain a field (now: MusicBrainz's and Last.fm's). */
    private const val FACTS_PREFIX = "facts8-"

    /** Verified dates: waited for this long in a story (the voice is waiting), longer for a question. */
    private const val STORY_DATES_WAIT_MS = 5_000L
    private const val ASK_DATES_WAIT_MS = 10_000L
    private const val MAX_REFERENCES = 40

    /** An album title this short ("45", "9") is found inside other words: not looked for in sentences. */
    private const val MIN_TITLE_CHARS = 4
    private const val DATES_PREFIX = "dates1-"

    /** How long the cloud model waits for the facts before it is asked without them. */
    private const val CLOUD_FACTS_WAIT_MS = 4_000L

    /** How long a story waits for the cloud model before it is told from the facts. */
    private const val CLOUD_STORY_WAIT_MS = 12_000L

    /** Facts given to the cloud model as its footing. */
    private const val FOOTING_FACTS = 14

    /** A thinking model spends tokens before it answers: more room than for the phone's model. */
    private const val CLOUD_TOKENS_FACTOR = 4

    /** An artist's own facts, looked up from the round card's circle. */
    private const val ARTIST_PREFIX = "artist1-"

    /** The recording as MusicBrainz knows it, for the round card. */
    private const val RECORD_PREFIX = "record4-"

    /** Two sentences that begin with this many of the same characters are one. */
    private const val DUPLICATE_CHARS = 60

    /** In the artist's story, a fact from the other sources after this many of the article's. */
    private const val WEAVE_EVERY = 3

    /** With nothing quick found, how long MusicBrainz is waited for (a few requests, one a second). */
    private const val DEEP_WAIT_MS = 5_000L

    /** Notes about the artist before the artists around them come in. */
    private const val CIRCLE_AFTER = 3

    /** How the start of a band reads in an album's article. */
    private val BAND_STORY = Regex(
        """(образовал(ась|ись|ся)?|основан[аы]?|сформирова(л|н)\w*|собрал(ась|ись)) .{0,40}\b(19|20)\d\d|\b(formed|founded) in\b""",
        RegexOption.IGNORE_CASE,
    )
}
