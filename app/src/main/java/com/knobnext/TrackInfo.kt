package com.knobnext

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/** How much to say about a track. */
enum class InfoLength(val sentences: Int) { SHORT(2), NORMAL(4), LONG(7) }

/** The language of the story: one picked, or the phone's. */
enum class InfoLanguage(val code: String?) {
    RU("ru"), EN("en"), SYSTEM(null);

    fun resolve(): String = code ?: if (Locale.getDefault().language == "ru") "ru" else "en"
}

/**
 * A few spoken sentences about the playing track, without any account or key: facts from Wikipedia
 * (the song's article, else the artist's) and MusicBrainz (album, year), retold by the on-phone model
 * when there is one. Everything is in one language: articles are looked up in it first, an article
 * found in another language is followed to its translation, and what is left foreign is translated by
 * the model, or left out when there is none. Only the title, the artist and the album leave the phone.
 * Stories are kept on disk, so a track asked about again is told at once, even offline.
 */
object TrackInfo {
    data class Track(val title: String, val artist: String?, val album: String?, val year: String?)

    /** A fact and the language it is written in. */
    class Text(val text: String, val lang: String)

    class Facts(val song: Text?, val artist: Text?, val album: String?, val year: String?) {
        val empty get() = song == null && artist == null && album == null && year == null
    }

    fun cached(context: Context, track: Track, length: InfoLength, lang: String): String? =
        cacheFile(context, track, length, lang).takeIf { it.exists() }?.readText()

    fun remember(context: Context, track: Track, length: InfoLength, lang: String, story: String) {
        runCatching { cacheFile(context, track, length, lang).writeText(story) }
        trimCache(context)
    }

    /** Everything found about the track, fetched all at once; null when nothing came back in time. */
    suspend fun facts(track: Track, lang: String): Facts? = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                val title = Speech.cleanTitle(track.title)
                val artist = track.artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() }
                val song = async { article(lang) { l -> findSong(l, title, artist) } }
                val about = async { artist?.let { a -> article(lang) { l -> findArtist(l, a) } } }
                val brainz = async { if (artist != null && (track.album == null || track.year == null)) musicBrainz(title, artist) else null }
                val b = brainz.await()
                Facts(song.await(), about.await(), track.album ?: b?.first, track.year ?: b?.second)
            }
        }
    }

    /** The opening line, made of facts that need no translation: title, artist, album, year. */
    fun header(context: Context, track: Track, facts: Facts, lang: String): String = buildString {
        append(Speech.announcement(track.title, track.artist, clean = true))
        val res = localized(context, lang)
        val detail = listOfNotNull(
            facts.album?.let { res.getString(R.string.info_album, it) },
            facts.year?.let { res.getString(R.string.info_year, it) },
        )
        if (detail.isNotEmpty()) append(". ").append(detail.joinToString(", ").replaceFirstChar { it.uppercase() })
        append(".")
    }

    /** Without a model: the facts that are already in [lang], cut at a sentence end. */
    fun plainStory(facts: Facts, length: InfoLength, lang: String): String? {
        val body = listOfNotNull(facts.song, facts.artist).filter { it.lang == lang }.joinToString(" ") { it.text }
        if (body.isBlank()) return null
        return Regex("(?<=[.!?…])\\s+").split(body).filter { it.isNotBlank() }.take(length.sentences).joinToString(" ")
    }

    /** The story told by the on-phone model, strictly in [lang] and from these facts only. */
    suspend fun retell(context: Context, track: Track, facts: Facts, length: InfoLength, lang: String): String? {
        if (facts.song == null && facts.artist == null) return null
        val prompt = buildString {
            appendLine(
                if (lang == "ru") {
                    "Ты радиоведущий. Расскажи слушателю о треке в ${length.sentences} предложениях. " +
                        "Пиши ТОЛЬКО на русском языке: если факты ниже на другом языке — переведи их. " +
                        "Используй только эти факты, ничего не выдумывай. Не цитируй текст песни. " +
                        "Не повторяй название и исполнителя в первом предложении — их уже назвали. Без списков и разметки."
                } else {
                    "You are a radio host. Tell the listener about the track in ${length.sentences} sentences. " +
                        "Write ONLY in English: translate the facts below if they are in another language. " +
                        "Use only these facts, make nothing up. Do not quote the lyrics. " +
                        "Do not repeat the title and artist at the start — they were just said. No lists, no markup."
                },
            )
            appendLine("Track: ${Speech.cleanTitle(track.title)}")
            track.artist?.let { appendLine("Artist: ${Speech.cleanArtist(it)}") }
            facts.album?.let { appendLine("Album: $it") }
            facts.year?.let { appendLine("Year: $it") }
            facts.song?.let { appendLine("About the song: ${it.text.take(MAX_FACT_CHARS)}") }
            facts.artist?.let { appendLine("About the artist: ${it.text.take(MAX_FACT_CHARS)}") }
        }
        val answer = withTimeoutOrNull(RETELL_TIMEOUT_MS) {
            Brains.ask(context, prompt, length.sentences * TOKENS_PER_SENTENCE)?.second
        } ?: return null
        val text = answer.replace(Regex("[*#_`]"), "").trim()
        // A model that ignored the language is no better than none.
        if (text.length < MIN_ANSWER_CHARS || !inLanguage(text, lang)) return null
        return text
    }

    // ---- Wikipedia ----

    /**
     * An article in [lang]; found only in another language, it is followed to its [lang] version, and
     * kept foreign (for the model to translate) only when there is no such version.
     */
    private fun article(lang: String, find: (String) -> Pair<String, JSONObject>?): Text? {
        find(lang)?.let { (_, summary) -> return Text(summary.optString("extract"), lang) }
        val other = if (lang == "ru") "en" else "ru"
        val (page, summary) = find(other) ?: return null
        translation(other, page, lang)?.let { translated ->
            summary(lang, translated)?.optString("extract")?.takeIf { it.isNotBlank() }?.let { return Text(it, lang) }
        }
        return Text(summary.optString("extract"), other)
    }

    /** The song's own article, when Wikipedia has one that is about a song by this artist. */
    private fun findSong(lang: String, title: String, artist: String?): Pair<String, JSONObject>? {
        val query = "\"$title\" ${artist.orEmpty()} " + if (lang == "ru") "песня" else "song"
        for (page in search(lang, query)) {
            if (!normalize(page).contains(normalize(title))) continue
            val summary = summary(lang, page) ?: continue
            val about = (summary.optString("description") + " " + summary.optString("extract")).lowercase()
            if (SONG_WORDS.none { about.contains(it) }) continue
            if (artist != null && !about.contains(normalize(artist).substringBefore(' '))) continue
            if (summary.optString("extract").isBlank()) continue
            return page to summary
        }
        return null
    }

    /** The artist's article: a page named after them that is about a musician or a band. */
    private fun findArtist(lang: String, artist: String): Pair<String, JSONObject>? {
        val query = "$artist " + if (lang == "ru") "музыка" else "music"
        for (page in search(lang, query)) {
            if (!normalize(page).startsWith(normalize(artist))) continue
            val summary = summary(lang, page) ?: continue
            val about = (summary.optString("description") + " " + summary.optString("extract").take(300)).lowercase()
            if (ARTIST_WORDS.none { about.contains(it) }) continue
            if (summary.optString("extract").isBlank()) continue
            return page to summary
        }
        return null
    }

    private fun search(lang: String, query: String): List<String> {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=$SEARCH_RESULTS&srsearch=" +
            URLEncoder.encode(query, "UTF-8")
        val results = get(url)?.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it).getString("title") }
    }

    private fun summary(lang: String, page: String): JSONObject? =
        get("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(page.replace(' ', '_'), "UTF-8"))
            ?.takeIf { it.optString("type") != "disambiguation" }

    /** The title of the same article in [to], from Wikipedia's links between languages. */
    private fun translation(from: String, page: String, to: String): String? {
        val url = "https://$from.wikipedia.org/w/api.php?action=query&prop=langlinks&format=json&utf8=1&lllang=$to&titles=" +
            URLEncoder.encode(page, "UTF-8")
        val pages = get(url)?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        for (key in pages.keys()) {
            val link = pages.getJSONObject(key).optJSONArray("langlinks")?.optJSONObject(0) ?: continue
            return link.optString("*").takeIf { it.isNotBlank() }
        }
        return null
    }

    // ---- MusicBrainz ----

    /** The album and year of the earliest release with this recording. */
    private fun musicBrainz(title: String, artist: String): Pair<String?, String?>? {
        val query = "recording:\"${title.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\""
        val json = get("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=1&query=" + URLEncoder.encode(query, "UTF-8"))
            ?: return null
        val recording = json.optJSONArray("recordings")?.optJSONObject(0) ?: return null
        if (recording.optInt("score") < MIN_SCORE) return null
        val year = recording.optString("first-release-date").take(4).takeIf { it.length == 4 }
        val album = recording.optJSONArray("releases")?.optJSONObject(0)?.optString("title")?.takeIf { it.isNotBlank() }
        return album to year
    }

    private fun get(url: String): JSONObject? = runCatching {
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

    // ---- Helpers ----

    /** Mostly Cyrillic letters for Russian, mostly Latin for English. */
    private fun inLanguage(text: String, lang: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val cyrillic = letters.count { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
        return if (lang == "ru") cyrillic * 10 >= letters.length * 6 else cyrillic * 10 <= letters.length * 2
    }

    private fun localized(context: Context, lang: String): Context {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale(lang))
        return context.createConfigurationContext(config)
    }

    private fun cacheFile(context: Context, track: Track, length: InfoLength, lang: String) =
        File(File(context.filesDir, "trackinfo").apply { mkdirs() }, sha1("${track.title}\u0000${track.artist}\u0000$length\u0000$lang"))

    private fun trimCache(context: Context) {
        val files = File(context.filesDir, "trackinfo").listFiles() ?: return
        if (files.size <= MAX_CACHED) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHED).forEach { it.delete() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val SONG_WORDS = listOf("song", "single", "track", "песн", "сингл", "композиц", "трек", "баллад")
    private val ARTIST_WORDS = listOf(
        "band", "singer", "musician", "rapper", "group", "duo", "composer", "dj", "songwriter", "artist",
        "групп", "певец", "певица", "музыкант", "рэпер", "исполнител", "композитор", "коллектив", "дуэт",
    )

    private const val SEARCH_RESULTS = 2
    private const val REQUEST_TIMEOUT_MS = 3_000
    private const val FETCH_TIMEOUT_MS = 7_000L
    private const val RETELL_TIMEOUT_MS = 20_000L
    private const val MAX_FACT_CHARS = 1_000
    private const val TOKENS_PER_SENTENCE = 50
    private const val MIN_ANSWER_CHARS = 20
    private const val MIN_SCORE = 90
    private const val MAX_CACHED = 200
}
