package com.knobnext

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

/**
 * A few spoken sentences about the playing track, without any account or key: facts from Wikipedia
 * (the song's article, else the artist's) and MusicBrainz (album, year), retold by the on-phone model
 * when there is one, otherwise taken as they are. The model is told to use only these facts, so it
 * has nothing to make up. Only the title, the artist and the album leave the phone.
 * Answers are kept on disk, so a track asked about again is told at once, even offline.
 */
object TrackInfo {
    data class Track(val title: String, val artist: String?, val album: String?, val year: String?)

    private class Facts(val song: String?, val artist: String?, val album: String?, val year: String?)

    suspend fun describe(context: Context, track: Track, length: InfoLength): String? {
        val language = if (Locale.getDefault().language == "ru") "ru" else "en"
        val key = sha1("${track.title}\u0000${track.artist}\u0000$length\u0000$language")
        val cached = File(cacheDir(context), key)
        if (cached.exists()) return cached.readText()
        val facts = withTimeoutOrNull(FETCH_TIMEOUT_MS) { gather(track, language) } ?: return null
        if (facts.song == null && facts.artist == null && facts.album == null && facts.year == null) return null
        val text = retell(context, track, facts, length, language) ?: template(context, track, facts, length)
        runCatching { cached.writeText(text) }
        trimCache(context)
        return text
    }

    // ---- Facts ----

    private suspend fun gather(track: Track, language: String): Facts = withContext(Dispatchers.IO) {
        val title = Speech.cleanTitle(track.title)
        val artist = track.artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() }
        val languages = if (language == "ru") listOf("ru", "en") else listOf("en")
        var song: String? = null
        var about: String? = null
        for (lang in languages) {
            if (song == null) song = wikiSong(lang, title, artist)
            if (about == null && artist != null) about = wikiArtist(lang, artist)
            if (song != null && about != null) break
        }
        val brainz = if (artist != null && (track.album == null || track.year == null)) musicBrainz(title, artist) else null
        Facts(song, about, track.album ?: brainz?.first, track.year ?: brainz?.second)
    }

    /** The song's own article, when Wikipedia has one that is about a song by this artist. */
    private fun wikiSong(lang: String, title: String, artist: String?): String? {
        val query = "\"$title\" ${artist.orEmpty()} " + if (lang == "ru") "песня" else "song"
        for (page in search(lang, query)) {
            if (!normalize(page).contains(normalize(title))) continue
            val summary = summary(lang, page) ?: continue
            val about = (summary.optString("description") + " " + summary.optString("extract")).lowercase()
            if (SONG_WORDS.none { about.contains(it) }) continue
            if (artist != null && !about.contains(normalize(artist).substringBefore(' '))) continue
            return summary.optString("extract").takeIf { it.isNotBlank() }
        }
        return null
    }

    /** The artist's article: a page named after them that is about a musician or a band. */
    private fun wikiArtist(lang: String, artist: String): String? {
        val query = "$artist " + if (lang == "ru") "музыка" else "music"
        for (page in search(lang, query)) {
            if (!normalize(page).startsWith(normalize(artist))) continue
            val summary = summary(lang, page) ?: continue
            val about = (summary.optString("description") + " " + summary.optString("extract").take(300)).lowercase()
            if (ARTIST_WORDS.none { about.contains(it) }) continue
            return summary.optString("extract").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun search(lang: String, query: String): List<String> {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=3&srsearch=" +
            URLEncoder.encode(query, "UTF-8")
        val json = get(url) ?: return emptyList()
        val results = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it).getString("title") }
    }

    private fun summary(lang: String, page: String): JSONObject? =
        get("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(page.replace(' ', '_'), "UTF-8"))
            ?.takeIf { it.optString("type") != "disambiguation" }

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
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            // Both services ask apps to say who they are.
            conn.setRequestProperty("User-Agent", "KnobNext (https://github.com/tbmgmos/KnobNext)")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    // ---- Telling ----

    private suspend fun retell(context: Context, track: Track, facts: Facts, length: InfoLength, language: String): String? {
        val prompt = buildString {
            appendLine(
                if (language == "ru") {
                    "Расскажи слушателю об этом треке в ${length.sentences} предложениях на русском языке, " +
                        "как диктор радио: живо, но без восторгов. Используй только факты ниже, ничего не добавляй от себя. " +
                        "Не цитируй текст песни. Без списков, кавычек-ёлочек можно. Если фактов о песне нет — расскажи об исполнителе."
                } else {
                    "Tell the listener about this track in ${length.sentences} sentences, like a radio host: lively, " +
                        "not gushing. Use only the facts below, add nothing of your own. Do not quote the lyrics. No lists. " +
                        "If there are no facts about the song, talk about the artist."
                },
            )
            appendLine("Track: ${Speech.cleanTitle(track.title)}")
            track.artist?.let { appendLine("Artist: ${Speech.cleanArtist(it)}") }
            facts.album?.let { appendLine("Album: $it") }
            facts.year?.let { appendLine("Year: $it") }
            facts.song?.let { appendLine("About the song: ${it.take(MAX_FACT_CHARS)}") }
            facts.artist?.let { appendLine("About the artist: ${it.take(MAX_FACT_CHARS)}") }
        }
        val answer = Brains.ask(context, prompt, length.sentences * TOKENS_PER_SENTENCE)?.second ?: return null
        return answer.replace(Regex("[*#_`]"), "").trim().takeIf { it.length > MIN_ANSWER_CHARS }
    }

    /** Without a model: the facts themselves, cut to the wanted length at a sentence end. */
    private fun template(context: Context, track: Track, facts: Facts, length: InfoLength): String {
        val head = buildString {
            append(Speech.announcement(track.title, track.artist, clean = true))
            val detail = listOfNotNull(
                facts.album?.let { context.getString(R.string.info_album, it) },
                facts.year?.let { context.getString(R.string.info_year, it) },
            )
            if (detail.isNotEmpty()) append(". ").append(detail.joinToString(", ").replaceFirstChar { it.uppercase() })
            append(". ")
        }
        val body = listOfNotNull(facts.song, facts.artist).joinToString(" ")
        val sentences = Regex("(?<=[.!?…])\\s+").split(body).filter { it.isNotBlank() }
        return head + sentences.take(length.sentences).joinToString(" ")
    }

    // ---- Cache ----

    private fun cacheDir(context: Context) = File(context.filesDir, "trackinfo").apply { mkdirs() }

    private fun trimCache(context: Context) {
        val files = cacheDir(context).listFiles() ?: return
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
        "групп", "певец", "певица", "музыкант", "рэпер", "исполнител", "композитор", "коллектив", "дуэт", "автор-исполнитель",
    )

    private const val FETCH_TIMEOUT_MS = 9_000L
    private const val MAX_FACT_CHARS = 1_200
    private const val TOKENS_PER_SENTENCE = 45
    private const val MIN_ANSWER_CHARS = 20
    private const val MIN_SCORE = 90
    private const val MAX_CACHED = 200
}
