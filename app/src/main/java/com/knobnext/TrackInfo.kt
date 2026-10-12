package com.knobnext

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
}

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
        // How long each way took goes to the log: the story is meant to follow the title without a pause.
        fun ready(story: String, how: String): String {
            note(context.getString(R.string.log_info_ready, (System.currentTimeMillis() - started) / 1000.0, how))
            return story
        }
        val history = History(context, track.artist)
        // Never told about this artist in KnobNext: the listener is introduced to them first.
        val firstMeet = track.artist != null && history.told().isEmpty()
        // The facts are fetched while Gemini thinks, not after it: when it refuses, they are already here.
        val factsAhead = async { runCatching { facts(context, track, lang) }.getOrNull() }
        if (source == InfoSource.GEMINI) {
            fromGemini(track, length, lang, history.told(), firstMeet)?.let {
                factsAhead.cancel()
                return@coroutineScope ready(history.add(it), "Gemini Nano")
            }
            note(context.getString(R.string.log_info_gemini_failed, NanoBrain.lastError ?: "-"))
        }
        val facts = factsAhead.await()
        if (facts == null || facts.empty) {
            note(context.getString(R.string.log_info_no_facts))
            return@coroutineScope null
        }
        if (source != InfoSource.WIKI) {
            // Gemini already said no (or can't run from the background): only the downloaded model is left.
            val brain = if (source == InfoSource.GEMINI) Brains.background(context) else null
            retell(context, track, facts, length, lang, history.told(), brain, onlyBackground = source == InfoSource.GEMINI, firstMeet)
                ?.let { return@coroutineScope ready(history.add(it), brain?.name ?: context.getString(R.string.log_info_way_model)) }
            note(context.getString(R.string.log_info_plain))
        }
        plainStory(context, track, facts, length, lang, firstMeet)?.let { ready(history.add(it), context.getString(R.string.log_info_way_plain)) }
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
        val answer = withTimeoutOrNull(MODEL_TIMEOUT_MS) {
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
    ): String? {
        if (onlyBackground && brain == null) return null
        // The same choice as without a model: the introduction, then one subject's untold facts.
        val plan = plan(context, track, facts, length, lang = null, firstMeet, perFact = FACTS_FOR_MODEL)
        if (plan.intro.isEmpty() && plan.facts.isEmpty()) return null
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
                null -> Unit
            }
            appendLine(shape(length, facts.album, lang, firstMeet && plan.intro.isNotEmpty(), plan.topic))
            appendTold(told, lang)
        }
        val tokens = length.sentences * TOKENS_PER_SENTENCE + EXTRA_TOKENS
        val answer = withTimeoutOrNull(MODEL_TIMEOUT_MS) {
            if (brain != null) brain.complete(prompt, tokens, creative = true) else Brains.ask(context, prompt, tokens, creative = true)?.second
        } ?: return null
        return clean(answer, length, lang)
    }

    /** Without a model: the introduction or one note, read as it is, in the story's language only. */
    private fun plainStory(context: Context, track: Track, facts: Facts, length: InfoLength, lang: String, firstMeet: Boolean): String? {
        val plan = plan(context, track, facts, length, lang, firstMeet, perFact = 0)
        val res = localized(context, lang)
        val note = plan.facts.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { body ->
            when (plan.topic) {
                Topic.SONG -> res.getString(R.string.info_note_song, body)
                Topic.ALBUM -> facts.album?.let { res.getString(R.string.info_note_album, quote(Speech.cleanTitle(it), lang), body) }
                    ?: res.getString(R.string.info_note_album_unnamed, body)
                Topic.ARTIST -> res.getString(R.string.info_note_artist, body)
                null -> body
            }
        }
        return (plan.intro + listOfNotNull(note)).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun quote(name: String, lang: String) = if (lang == "ru") "«$name»" else "\"$name\""

    /** What one telling is about. */
    private enum class Topic { SONG, ALBUM, ARTIST }

    /** The introduction (the first time only) and one subject's next facts. */
    private class Plan(val intro: List<String>, val topic: Topic?, val facts: List<String>)

    /**
     * One subject per telling, the first that still has facts not told: the song, else its album, else
     * the artist's story; when all have been told, the first that has any. [lang] null: facts in any
     * language (a model translates them); [perFact] > 0: that many facts for a model to choose from.
     */
    private fun plan(context: Context, track: Track, facts: Facts, length: InfoLength, lang: String?, firstMeet: Boolean, perFact: Int): Plan {
        val rotation = Rotation(context)
        val intro = if (firstMeet) facts.intro.take(INTRO_SENTENCES) else emptyList()
        fun clean(list: List<String>) = brief(list).filter { standsAlone(it) }
        val topics = listOf(
            Triple(Topic.SONG, "song:${track.title}", clean(merged(facts.song, lang))),
            Triple(Topic.ALBUM, "album:${facts.album}", clean(merged(facts.albumText, lang))),
            // The artist's story in its own order: the articles tell it from the beginning.
            Triple(Topic.ARTIST, "artist:${track.artist}", clean(facts.artist.filter { lang == null || it.lang == lang }.flatMap { it.facts })),
        )
        val sentences = when {
            perFact > 0 -> perFact
            intro.isNotEmpty() -> length.introNote
            else -> length.noteSentences
        }
        if (sentences == 0) return Plan(intro, null, emptyList())
        val chosen = topics.firstOrNull { (_, key, list) -> list.isNotEmpty() && rotation.left(key, list.size) > 0 }
            ?: topics.firstOrNull { it.third.isNotEmpty() }
            ?: return Plan(intro, null, emptyList())
        return Plan(intro, chosen.first, rotation.next(chosen.second, chosen.third, sentences))
    }

    /**
     * A sentence that makes sense on its own. Cut out of an article, one that leans on the one before
     * ("He also…", "In the same year…", "This became…") just confuses.
     */
    private fun standsAlone(sentence: String): Boolean {
        val s = sentence.trimStart().lowercase(Locale.ROOT)
        return DEPENDENT_STARTS.none { s.startsWith(it) } && sentence.count { it == ',' } <= MAX_COMMAS
    }

    /**
     * Facts that are quick to hear: without asides in brackets (dates, original spellings), and only the
     * short ones when there are any.
     */
    private fun brief(facts: List<String>): List<String> {
        val compact = facts.map { it.replace(BRACKETS, "").replace(Regex("\\s+([,.;:!?])"), "$1").replace(Regex("\\s{2,}"), " ").trim() }
            .filter { it.length >= MIN_FACT_CHARS }
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

    /** Everything found about the track, fetched all at once and kept on the phone. */
    suspend fun facts(context: Context, track: Track, lang: String): Facts? {
        val file = File(dir(context), "facts4-" + sha1("${track.title}\u0000${track.artist}\u0000$lang"))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FACTS_FRESH_MS) {
            runCatching { return readFacts(JSONObject(file.readText())) }
        }
        val started = System.currentTimeMillis()
        val fetched = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val title = Speech.cleanTitle(track.title)
                    val artist = track.artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() }
                    val more = async { runCatching { MusicSources.gather(context, title, artist, track.album, lang) }.getOrNull() }
                    val song = async { findArticle(lang) { l -> findSong(l, title, artist) } }
                    val about = async { artist?.let { a -> findArticle(lang) { l -> findArtist(l, a) } } }
                    val brainz = async { if (artist != null && (track.album == null || track.year == null)) musicBrainz(title, artist) else null }
                    // The album's article: right away when the player names the album, else once MusicBrainz has.
                    val albumText = async {
                        val name = track.album ?: brainz.await()?.first
                        name?.let { a -> findArticle(lang) { l -> findAlbum(l, a, artist) } }
                    }
                    // Wikidata, for the very items behind the articles found.
                    val data = async {
                        runCatching {
                            Wikidata.gather(
                                context, lang, song.await()?.page, albumText.await()?.page, about.await()?.page,
                                deadline = started + WIKIDATA_DEADLINE_MS,
                            )
                        }.getOrNull()
                    }
                    val b = brainz.await()
                    val m = more.await()
                    val d = data.await()
                    val artistText = about.await()?.text
                    Facts(
                        song = listOfNotNull(song.await()?.text, d?.song) + m?.song.orEmpty(),
                        artist = listOfNotNull(artistText, d?.artist) + m?.artist.orEmpty(),
                        album = track.album ?: b?.first ?: m?.albumName,
                        year = track.year ?: b?.second ?: m?.year,
                        albumText = listOfNotNull(albumText.await()?.text, d?.album) + m?.album.orEmpty(),
                        intro = intro(artistText, d?.artistIntro.orEmpty(), lang),
                    )
                }
            }
        }
        // Offline or slow: whatever was found before, however old.
        if (fetched == null || fetched.empty) {
            return if (file.exists()) runCatching { readFacts(JSONObject(file.readText())) }.getOrNull() else fetched
        }
        runCatching { file.writeText(writeFacts(fetched).toString()) }
        trimCache(context)
        return fetched
    }

    /**
     * Who the artist is: the article's opening sentence when it defines them ("Nirvana — американская
     * рок-группа…"), else Wikidata's basics (when and where they began, genres).
     */
    private fun intro(article: Text?, basics: List<String>, lang: String): List<String> {
        val lead = article?.takeIf { it.lang == lang }?.facts?.firstOrNull()?.let { brief(listOf(it)).firstOrNull() }
        val defines = lead != null && if (lang == "ru") {
            lead.indexOf(" — ").let { it in 1..MAX_INTRO_NAME } || lead.contains(" является ")
        } else {
            Regex("""\b(is|was|are|were)\b""").containsMatchIn(lead.take(MAX_INTRO_NAME + 20))
        }
        return if (defines) listOf(lead!!) else basics
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
            sentences(l).filterTo(facts) { it.length in MIN_FACT_CHARS..MAX_FACT_CHARS }
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
    private fun musicBrainz(title: String, artist: String): Pair<String?, String?>? {
        val query = "recording:\"${title.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\""
        val json = getJson("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=1&query=" + URLEncoder.encode(query, "UTF-8"))
            ?: return null
        val recording = json.optJSONArray("recordings")?.optJSONObject(0) ?: return null
        if (recording.optInt("score") < MIN_SCORE) return null
        val year = recording.optString("first-release-date").take(4).takeIf { it.length == 4 }
        val album = recording.optJSONArray("releases")?.optJSONObject(0)?.optString("title")?.takeIf { it.isNotBlank() }
        return album to year
    }

    internal fun getJson(url: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = REQUEST_TIMEOUT_MS
            conn.readTimeout = REQUEST_TIMEOUT_MS
            // Both services ask apps to say who they are.
            conn.setRequestProperty("User-Agent", "KnobNext (https://github.com/tbmgmos/KnobNext)")
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

        /** How many of [size] facts under [key] have not been told yet. */
        fun left(key: String, size: Int): Int = (size - sp.getInt(sha1(key), 0)).coerceAtLeast(0)

        fun next(key: String, facts: List<String>, n: Int): List<String> {
            if (facts.isEmpty()) return emptyList()
            val k = sha1(key)
            // The first sentence usually just says what the thing is; start after it, come back to it last.
            val start = (sp.getInt(k, 0) + 1) % facts.size
            val taken = (0 until minOf(n, facts.size)).map { facts[(start + it) % facts.size] }
            sp.edit().putInt(k, sp.getInt(k, 0) + taken.size).apply()
            return taken
        }
    }

    /** The last stories about an artist, so the model is asked for something new. */
    private class History(context: Context, artist: String?) {
        private val file = artist?.let { File(dir(context), "told-" + sha1(it.lowercase(Locale.ROOT))) }

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
        val intro = o.optJSONArray("intro")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
        return Facts(text("song"), text("artist"), o.optString("album").ifBlank { null }, o.optString("year").ifBlank { null }, text("albumText"), intro)
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

    private fun trimCache(context: Context) {
        dir(context).listFiles { f -> f.name.startsWith("facts-") }?.forEach { it.delete() }
        val files = dir(context).listFiles { f -> f.name.startsWith("facts2-") } ?: return
        if (files.size <= MAX_CACHED) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHED).forEach { it.delete() }
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
    private const val REQUEST_TIMEOUT_MS = 3_500
    private const val FETCH_TIMEOUT_MS = 8_000L

    // The last Wikidata request starts by then, each can take up to REQUEST_TIMEOUT_MS: within FETCH_TIMEOUT_MS.
    private const val WIKIDATA_DEADLINE_MS = 4_000L
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
    private const val FACTS_FOR_MODEL = 5
    private const val MAX_TOLD = 5
    private const val INTRO_SENTENCES = 2
    private const val MAX_INTRO_NAME = 60
    private const val MAX_COMMAS = 4

    /** How sentences that lean on the one before begin (lowercased). */
    private val DEPENDENT_STARTS = listOf(
        "он ", "она ", "они ", "оно ", "его ", "её ", "ее ", "их ", "ему ", "ей ", "им ", "это ", "этот ", "эта ", "эти ", "этом ",
        "там ", "тогда ", "также ", "тоже ", "кроме того", "однако", "позже", "позднее", "впоследствии", "после этого", "затем ",
        "вскоре", "в том же", "в следующем", "в этом ", "при этом", "поэтому", "но ", "и ", "а ", "так ", "здесь ",
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
}
