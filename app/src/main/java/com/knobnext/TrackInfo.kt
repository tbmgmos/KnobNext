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
enum class InfoLength(val sentences: Int) { SHORT(2), NORMAL(3), LONG(5) }

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
 * A few spoken sentences about the playing track: a little about the song, one fact about its album
 * and one about the artist, different each time. Facts come from whole Wikipedia articles (song, album,
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
    class Facts(val song: List<Text>, val artist: List<Text>, val album: String?, val year: String?, val albumText: List<Text>) {
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
    ): String? {
        val history = History(context, track.artist)
        if (source == InfoSource.GEMINI) {
            fromGemini(track, length, lang, history.told())?.let { return history.add(it) }
            note(context.getString(R.string.log_info_gemini_failed, NanoBrain.lastError ?: "-"))
        }
        val facts = facts(context, track, lang)
        if (facts == null || facts.empty) {
            note(context.getString(R.string.log_info_no_facts))
            return null
        }
        if (source != InfoSource.WIKI) {
            // Gemini already said no (or can't run from the background): only the downloaded model is left.
            val brain = if (source == InfoSource.GEMINI) Brains.background(context) else null
            retell(context, track, facts, length, lang, history.told(), brain, onlyBackground = source == InfoSource.GEMINI)
                ?.let { return history.add(it) }
            note(context.getString(R.string.log_info_plain))
        }
        return plainStory(context, track, facts, length, lang)?.let(history::add)
    }

    // ---- Gemini on its own ----

    private suspend fun fromGemini(track: Track, length: InfoLength, lang: String, told: List<String>): String? {
        val nano = NanoBrain.ready() ?: return null.also { if (NanoBrain.lastError == null) NanoBrain.lastError = "Gemini Nano не готов" }
        val prompt = buildString {
            appendLine(
                if (lang == "ru") {
                    "Ты радиоведущий. Расскажи слушателю о треке, его альбоме и исполнителе, только на русском языке. " +
                        "Говори только то, в чём уверен; если о треке знаешь мало — расскажи больше об альбоме, а если и о нём мало — об исполнителе. " +
                        "Не цитируй текст песни. Не повторяй название и исполнителя в начале — их уже назвали. Без списков и разметки."
                } else {
                    "You are a radio host. Tell the listener about the track, its album and its artist, only in English. " +
                        "Say only what you are sure of; if you know little about the track, say more about the album, and if little about it too, about the artist. " +
                        "Do not quote the lyrics. Do not repeat the title and artist at the start — they were just said. No lists, no markup."
                },
            )
            appendTrack(track, track.album, track.year)
            appendLine(structure(length, track.album, lang))
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
    ): String? {
        if (onlyBackground && brain == null) return null
        val rotation = Rotation(context)
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
            val song = rotation.next("song:${track.title}", merged(facts.song), FACTS_FOR_MODEL)
            val album = rotation.next("album:${facts.album}", merged(facts.albumText), FACTS_FOR_MODEL)
            val artist = rotation.next("artist:${track.artist}", merged(facts.artist), FACTS_FOR_MODEL)
            if (song.isNotEmpty()) appendLine("About the song: " + song.joinToString(" "))
            if (album.isNotEmpty()) appendLine("About the album: " + album.joinToString(" "))
            if (artist.isNotEmpty()) appendLine("About the artist: " + artist.joinToString(" "))
            appendLine(structure(length, facts.album, lang, shares(length, song.size, album.size, artist.size)))
            appendTold(told, lang)
        }
        val tokens = length.sentences * TOKENS_PER_SENTENCE + EXTRA_TOKENS
        val answer = withTimeoutOrNull(MODEL_TIMEOUT_MS) {
            if (brain != null) brain.complete(prompt, tokens, creative = true) else Brains.ask(context, prompt, tokens, creative = true)?.second
        } ?: return null
        return clean(answer, length, lang)
    }

    /** Without a model: the next facts in turn, in the story's language only. */
    private fun plainStory(context: Context, track: Track, facts: Facts, length: InfoLength, lang: String): String? {
        val rotation = Rotation(context)
        val song = brief(merged(facts.song, lang))
        val album = brief(merged(facts.albumText, lang))
        val artist = brief(merged(facts.artist, lang))
        val n = shares(length, song.size, album.size, artist.size)
        fun take(key: String, list: List<String>, count: Int) = if (count > 0) rotation.next(key, list, count) else emptyList()
        val parts = take("song:${track.title}", song, n.song) +
            take("album:${facts.album}", album, n.album) +
            take("artist:${track.artist}", artist, n.artist)
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
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

    /** How many facts go to the song, the album and the artist. */
    private data class Shares(val song: Int, val album: Int, val artist: Int)

    /**
     * Facts per part for this length, given how many each part has. Nothing about the song → its share goes
     * to the album; nothing about the album either → to the artist. If the artist runs short, what's left
     * goes back to the album, then to the song, so the story stays as long as the facts allow.
     */
    private fun shares(length: InfoLength, song: Int, album: Int, artist: Int): Shares {
        val want = when (length) {
            InfoLength.SHORT -> intArrayOf(0, 1, 1)
            InfoLength.NORMAL -> intArrayOf(1, 1, 1)
            InfoLength.LONG -> intArrayOf(1, 2, 2)
        }
        val have = intArrayOf(song, album, artist)
        fun move(from: Int, to: Int) {
            val extra = want[from] - minOf(want[from], have[from])
            want[from] -= extra
            want[to] += extra
        }
        move(0, 1)
        move(1, 2)
        move(2, 1)
        move(1, 0)
        want[0] = minOf(want[0], have[0])
        return Shares(want[0], want[1], want[2])
    }

    private fun StringBuilder.appendTrack(track: Track, album: String?, year: String?) {
        appendLine("Track: ${Speech.cleanTitle(track.title)}")
        track.artist?.let { appendLine("Artist: ${Speech.cleanArtist(it)}") }
        album?.let { appendLine("Album: $it") }
        year?.let { appendLine("Year: $it") }
    }

    private fun StringBuilder.appendTold(told: List<String>, lang: String) {
        if (told.isEmpty()) return
        appendLine(if (lang == "ru") "Это уже рассказывали — расскажи другие факты:" else "Already told — tell other facts:")
        told.forEach { appendLine("- ${it.take(MAX_TOLD_CHARS)}") }
    }

    /** What the model is asked to cover. */
    private fun structure(length: InfoLength, album: String?, lang: String, known: Shares? = null): String {
        val ru = lang == "ru"
        val albumName = album?.let { if (ru) " «$it»" else " \"$it\"" } ?: ""
        if (known != null) {
            // The facts are in front of the model: say exactly what to cover, the gaps already filled in.
            val parts = listOfNotNull(
                known.song.takeIf { it > 0 }?.let { if (ru) "о самой песне" else "about the song itself" },
                known.album.takeIf { it > 0 }?.let { if (ru) "об альбоме$albumName" else "about the album$albumName" },
                known.artist.takeIf { it > 0 }?.let { if (ru) "об исполнителе или группе" else "about the artist or band" },
            )
            if (parts.isNotEmpty()) {
                return if (ru) "Расскажи по порядку: ${parts.joinToString(", затем ")}. ${brevity(length, ru)}"
                else "Tell, in this order: ${parts.joinToString(", then ")}. ${brevity(length, ru)}"
            }
        }
        return if (ru) {
            "Построй рассказ так: ${if (length == InfoLength.SHORT) "" else "сначала коротко о самой песне, затем "}" +
                "ОДИН короткий интересный факт об альбоме$albumName, из которого эта песня, " +
                "затем ОДИН короткий интересный факт об исполнителе или группе. " +
                "Если о песне сказать нечего — вместо этого расскажи ещё один факт об альбоме; " +
                "если и об альбоме нечего — расскажи больше об исполнителе. ${brevity(length, ru)}"
        } else {
            "Structure: ${if (length == InfoLength.SHORT) "" else "first briefly about the song itself, then "}" +
                "ONE short interesting fact about the album$albumName the song is from, " +
                "then ONE short interesting fact about the artist or band. " +
                "If there is nothing to say about the song, tell one more fact about the album instead; " +
                "if nothing about the album either, tell more about the artist. ${brevity(length, ru)}"
        }
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
        val file = File(dir(context), "facts2-" + sha1("${track.title}\u0000${track.artist}\u0000$lang"))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FACTS_FRESH_MS) {
            runCatching { return readFacts(JSONObject(file.readText())) }
        }
        val fetched = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val title = Speech.cleanTitle(track.title)
                    val artist = track.artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() }
                    val more = async { runCatching { MusicSources.gather(context, title, artist, track.album, lang) }.getOrNull() }
                    val song = async { article(lang) { l -> findSong(l, title, artist) } }
                    val about = async { artist?.let { a -> article(lang) { l -> findArtist(l, a) } } }
                    val brainz = async { if (artist != null && (track.album == null || track.year == null)) musicBrainz(title, artist) else null }
                    // The album's article: right away when the player names the album, else once MusicBrainz has.
                    val albumText = async {
                        val name = track.album ?: brainz.await()?.first
                        name?.let { a -> article(lang) { l -> findAlbum(l, a, artist) } }
                    }
                    val b = brainz.await()
                    val m = more.await()
                    Facts(
                        song = listOfNotNull(song.await()) + m?.song.orEmpty(),
                        artist = listOfNotNull(about.await()) + m?.artist.orEmpty(),
                        album = track.album ?: b?.first ?: m?.albumName,
                        year = track.year ?: b?.second ?: m?.year,
                        albumText = listOfNotNull(albumText.await()) + m?.album.orEmpty(),
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
     * An article in [lang]; found only in another language, it is followed to its [lang] version, and
     * kept foreign (for a model to translate) only when there is no such version.
     */
    private fun article(lang: String, find: (String) -> String?): Text? {
        find(lang)?.let { page -> return text(lang, page)?.let { Text(it, lang) } }
        val other = if (lang == "ru") "en" else "ru"
        val page = find(other) ?: return null
        translation(other, page, lang)?.let { translated -> text(lang, translated)?.let { return Text(it, lang) } }
        return text(other, page)?.let { Text(it, other) }
    }

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
        return Facts(text("song"), text("artist"), o.optString("album").ifBlank { null }, o.optString("year").ifBlank { null }, text("albumText"))
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
    private const val MODEL_TIMEOUT_MS = 20_000L
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
    private const val MAX_TOLD_CHARS = 300
    private const val TOKENS_PER_SENTENCE = 50
    private const val EXTRA_TOKENS = 60
    private const val MIN_ANSWER_CHARS = 20
    private const val MIN_SCORE = 90
    private const val MAX_CACHED = 300
}
