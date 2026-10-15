package com.knobnext

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Facts beyond Wikipedia, all without keys, each source on its own (one failing or unreachable doesn't
 * hold up the rest):
 *  - TheAudioDB: artist biographies (often in Russian too), album and track descriptions;
 *  - Deezer: the album's label, release date and length, the artist's fans and albums;
 *  - MusicBrainz: where the artist is from, when a band was formed, genres (more of it, slower, in [Brainz]);
 *  - Last.fm (with the app's key): biographies and descriptions written by listeners, often of artists
 *    Wikipedia has no article on, how many listen to them, and whom listeners pair them with.
 * Descriptions come as they are (in their language); numbers and dates become sentences in the story's
 * language, ready to be said even without a model.
 */
object MusicSources {
    class Found(
        val song: List<TrackInfo.Text>,
        val album: List<TrackInfo.Text>,
        val artist: List<TrackInfo.Text>,
        val albumName: String?,
        val year: String?,
        /** Last.fm's texts: for when Wikipedia has no article (they are often copied from it). */
        val lastFm: LastFm? = null,
        /** When and where the artist began, by MusicBrainz: an introduction when nothing else has one. */
        val basics: List<String> = emptyList(),
    )

    class LastFm(
        val artist: TrackInfo.Text?,
        val song: TrackInfo.Text?,
        val album: TrackInfo.Text?,
        /** How many listen, in the story's language: always new, so not held back like the texts. */
        val artistNumbers: List<String>,
        val songNumbers: List<String>,
        /** Whom listeners pair the artist with: a sentence, and the names. */
        val similar: String?,
        val similarNames: List<String>,
    )

    suspend fun gather(context: Context, title: String, artist: String?, album: String?, lang: String): Found = coroutineScope {
        if (artist == null) return@coroutineScope Found(emptyList(), emptyList(), emptyList(), null, null)
        val res = TrackInfo.localized(context, lang)
        val audioDbArtist = async { runCatching { audioDbArtist(res, artist, lang) }.getOrNull() }
        val audioDbTrack = async { runCatching { audioDbTrack(artist, title, lang) }.getOrNull() }
        val deezer = async { runCatching { deezer(res, artist, title, lang) }.getOrNull() }
        val brainz = async { runCatching { brainzArtist(res, artist, lang) }.getOrNull() }
        val lastFm = async {
            val prefs = Prefs(context)
            val agent = if (prefs.lastFmPlainAgent) TrackInfo.PLAIN_AGENT else TrackInfo.USER_AGENT
            runCatching { lastFm(res, prefs.lastFmKeyInUse, agent, artist, title, album, lang) }.getOrNull()
        }
        val d = deezer.await()
        // The album's description needs its name: the player's, else the one Deezer found.
        val albumName = album ?: d?.albumName
        val audioDbAlbum = albumName?.let { runCatching { audioDbAlbum(artist, it, lang) }.getOrNull() }
        Found(
            song = listOfNotNull(audioDbTrack.await()),
            album = listOfNotNull(audioDbAlbum, d?.album),
            artist = listOfNotNull(audioDbArtist.await(), d?.artist, brainz.await()?.let { TrackInfo.Text(it, lang) }),
            albumName = albumName,
            year = d?.year,
            lastFm = lastFm.await(),
            basics = brainz.await().orEmpty(),
        )
    }

    // ---- TheAudioDB ----

    private const val AUDIODB = "https://www.theaudiodb.com/api/v1/json/123"

    private fun audioDbArtist(res: Context, artist: String, lang: String): TrackInfo.Text? {
        val a = TrackInfo.getJson("$AUDIODB/search.php?s=${enc(artist)}")?.optJSONArray("artists")?.optJSONObject(0) ?: return null
        if (!sameName(a.optString("strArtist"), artist)) return null
        val (bio, bioLang) = described(a, "strBiography", lang) ?: (null to lang)
        val facts = ArrayList<String>()
        bio?.let { facts += TrackInfo.splitFacts(it) }
        if (bioLang == lang) {
            a.optString("intFormedYear").takeIf { it.length == 4 && it != "0000" }?.let { facts += res.getString(R.string.src_formed, it) }
            a.optString("intMembers").toIntOrNull()?.takeIf { it > 1 }?.let {
                facts += res.resources.getQuantityString(R.plurals.src_members, it, it)
            }
            a.optString("strStyle").takeIf { it.isNotBlank() && it != "null" }?.let { facts += res.getString(R.string.src_style, it) }
        }
        return facts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, bioLang) }
    }

    private fun audioDbAlbum(artist: String, album: String, lang: String): TrackInfo.Text? {
        val a = TrackInfo.getJson("$AUDIODB/searchalbum.php?s=${enc(artist)}&a=${enc(Speech.cleanTitle(album))}")
            ?.optJSONArray("album")?.optJSONObject(0) ?: return null
        val (text, textLang) = described(a, "strDescription", lang) ?: return null
        return TrackInfo.Text(TrackInfo.splitFacts(text), textLang)
    }

    private fun audioDbTrack(artist: String, title: String, lang: String): TrackInfo.Text? {
        val t = TrackInfo.getJson("$AUDIODB/searchtrack.php?s=${enc(artist)}&t=${enc(title)}")
            ?.optJSONArray("track")?.optJSONObject(0) ?: return null
        val (text, textLang) = described(t, "strDescription", lang) ?: return null
        return TrackInfo.Text(TrackInfo.splitFacts(text), textLang)
    }

    /** A description in the story's language if the source has one, else in English. */
    private fun described(o: JSONObject, field: String, lang: String): Pair<String, String>? {
        val wanted = o.optString(field + lang.uppercase(Locale.ROOT)).takeIf { it.isNotBlank() && it != "null" }
        if (wanted != null) return wanted to lang
        val english = o.optString(field + "EN").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return english to "en"
    }

    // ---- Deezer ----

    private class DeezerFacts(val album: TrackInfo.Text?, val artist: TrackInfo.Text?, val albumName: String?, val year: String?)

    private fun deezer(res: Context, artist: String, title: String, lang: String): DeezerFacts? {
        val q = "artist:\"${artist.replace("\"", "")}\" track:\"${title.replace("\"", "")}\""
        val hit = TrackInfo.getJson("https://api.deezer.com/search?limit=1&q=${enc(q)}")?.optJSONArray("data")?.optJSONObject(0)
            ?: return null
        if (!sameName(hit.optJSONObject("artist")?.optString("name").orEmpty(), artist)) return null
        val albumId = hit.optJSONObject("album")?.optLong("id") ?: 0L
        val artistId = hit.optJSONObject("artist")?.optLong("id") ?: 0L

        val albumFacts = ArrayList<String>()
        var albumName: String? = null
        var year: String? = null
        if (albumId > 0) TrackInfo.getJson("https://api.deezer.com/album/$albumId")?.let { a ->
            albumName = a.optString("title").takeIf { it.isNotBlank() }
            val date = a.optString("release_date")
            year = date.take(4).takeIf { it.length == 4 }
            dateWords(date, lang)?.let { albumFacts += res.getString(R.string.src_album_date, it) }
            a.optString("label").takeIf { it.isNotBlank() }?.let { albumFacts += res.getString(R.string.src_label, it) }
            a.optInt("nb_tracks").takeIf { it > 1 }?.let { albumFacts += res.resources.getQuantityString(R.plurals.src_tracks, it, it) }
            a.optJSONObject("genres")?.optJSONArray("data")?.let { g ->
                val names = (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                if (names.isNotEmpty()) albumFacts += res.getString(R.string.src_genres, names.joinToString(", "))
            }
        }
        val artistFacts = ArrayList<String>()
        if (artistId > 0) TrackInfo.getJson("https://api.deezer.com/artist/$artistId")?.let { a ->
            a.optInt("nb_fan").takeIf { it > 0 }?.let { artistFacts += res.resources.getQuantityString(R.plurals.src_fans, it, number(it, lang)) }
            a.optInt("nb_album").takeIf { it > 1 }?.let { artistFacts += res.resources.getQuantityString(R.plurals.src_albums, it, it) }
        }
        return DeezerFacts(
            albumFacts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) },
            artistFacts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) },
            albumName,
            year,
        )
    }

    /** The album's cover at Deezer (no key): for the history, when the Cover Art Archive has none. */
    fun deezerCover(artist: String, album: String): String? {
        val name = Speech.cleanTitle(album)
        val q = "artist:\"${Speech.cleanArtist(artist).replace("\"", "")}\" album:\"${name.replace("\"", "")}\""
        val data = TrackInfo.getJson("https://api.deezer.com/search/album?limit=5&q=${enc(q)}")?.optJSONArray("data") ?: return null
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .firstOrNull { sameName(it.optString("title"), name) && sameName(it.optJSONObject("artist")?.optString("name").orEmpty(), Speech.cleanArtist(artist)) }
            ?.optString("cover_medium")?.takeIf { it.startsWith("https://") }
    }

    // ---- MusicBrainz ----

    private suspend fun brainzArtist(res: Context, artist: String, lang: String): List<String>? {
        val q = "artist:\"${artist.replace("\"", "")}\""
        val a = Brainz.get("https://musicbrainz.org/ws/2/artist/?fmt=json&limit=1&query=${enc(q)}")
            ?.optJSONArray("artists")?.optJSONObject(0) ?: return null
        if (a.optInt("score") < MIN_SCORE || !sameName(a.optString("name"), artist)) return null
        val facts = ArrayList<String>()
        val group = a.optString("type") == "Group"
        val begin = a.optJSONObject("life-span")?.optString("begin")?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        if (group && begin != null) facts += res.getString(R.string.src_formed, begin)
        val place = a.optJSONObject("begin-area")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: a.optJSONObject("area")?.optString("name")?.takeIf { it.isNotBlank() }
        // MusicBrainz's places and tags are in English: in a Russian story they would jar.
        place?.takeIf { lang != "ru" || cyrillic(it) }?.let { facts += res.getString(if (group) R.string.src_band_from else R.string.src_artist_from, it) }
        if (lang == "en") a.optJSONArray("tags")?.let { tags ->
            val names = (0 until tags.length()).mapNotNull { tags.optJSONObject(it) }
                .sortedByDescending { it.optInt("count") }.map { it.optString("name") }.filter { it.isNotBlank() }.take(3)
            if (names.isNotEmpty()) facts += res.getString(R.string.src_genres, names.joinToString(", "))
        }
        return facts.takeIf { it.isNotEmpty() }
    }

    // ---- Last.fm ----

    /**
     * What Last.fm says to [key]: a known artist must come back. A refusal that is not "Invalid API key"
     * may be about the way the app asks rather than the key, so the same request is tried again with a
     * plain signature and as a browser sends it; the answers say which it is. When the plain signature
     * works, the stories use it from then on ([Prefs.lastFmPlainAgent]). The browser's is only a clue:
     * Last.fm asks apps to say who they are.
     */
    fun checkLastFm(context: Context, key: String): KeyCheck {
        val url = "$LASTFM?method=artist.getinfo&artist=Radiohead&format=json&api_key=${enc(key.trim())}"
        val accepts: (JSONObject) -> Boolean = { it.optJSONObject("artist") != null }
        val prefs = Prefs(context)
        val first = KeyCheck.ask(url, mapOf("User-Agent" to TrackInfo.USER_AGENT), accepts)
        if (first is KeyCheck.Works) prefs.lastFmPlainAgent = false
        if (first !is KeyCheck.Rejected || first.error == LASTFM_INVALID_KEY || !first.fromApi) return first
        val plain = KeyCheck.ask(url, mapOf("User-Agent" to TrackInfo.PLAIN_AGENT), accepts)
        if (plain is KeyCheck.Works) {
            prefs.lastFmPlainAgent = true
            return KeyCheck.Works
        }
        val browser = KeyCheck.ask(url, mapOf("User-Agent" to BROWSER_AGENT, "Accept" to "*/*"), accepts)
        return KeyCheck.Rejected(first.http, first.error, first.message, first.fromApi, plain = plain, browser = browser)
    }

    private fun lastFm(res: Context, appKey: String, agent: String, artist: String, title: String, album: String?, lang: String): LastFm? {
        val key = appKey.takeIf { it.isNotBlank() } ?: return null
        fun call(method: String, params: String) =
            TrackInfo.getJson("$LASTFM?method=$method&api_key=$key&format=json&autocorrect=1&lang=$lang$params", agent)
        val a = call("artist.getinfo", "&artist=${enc(artist)}")?.optJSONObject("artist") ?: return null
        if (!sameName(a.optString("name"), artist)) return null
        val t = call("track.getinfo", "&artist=${enc(artist)}&track=${enc(Speech.cleanTitle(title))}")?.optJSONObject("track")
        val al = album?.let { call("album.getinfo", "&artist=${enc(artist)}&album=${enc(Speech.cleanTitle(it))}")?.optJSONObject("album") }

        val artistNumbers = ArrayList<String>()
        a.optJSONObject("stats")?.optString("listeners")?.toIntOrNull()?.takeIf { it >= MIN_LISTENERS }
            ?.let { artistNumbers += res.getString(R.string.src_lastfm_listeners, number(it, lang)) }
        val songNumbers = ArrayList<String>()
        t?.optString("listeners")?.toIntOrNull()?.takeIf { it >= MIN_LISTENERS }
            ?.let { songNumbers += res.getString(R.string.src_lastfm_song_listeners, number(it, lang)) }
        val similar = a.optJSONObject("similar")?.optJSONArray("artist")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty().take(MAX_SIMILAR)
        return LastFm(
            artist = written(a.optJSONObject("bio")?.optString("content")),
            song = written(t?.optJSONObject("wiki")?.optString("content")),
            album = written(al?.optJSONObject("wiki")?.optString("content")),
            artistNumbers = artistNumbers,
            songNumbers = songNumbers,
            similar = similar.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.src_lastfm_similar, it.joinToString(", ")) },
            similarNames = similar,
        )
    }

    /**
     * A text written by Last.fm's listeners, without the link and licence line it ends with, marked with
     * the language it turned out to be in (Last.fm gives English when it has nothing in the one asked).
     */
    private fun written(content: String?): TrackInfo.Text? {
        val text = content?.substringBefore("<a ")?.replace(Regex("<[^>]+>"), " ")?.replace("&quot;", "\"")?.replace("&amp;", "&")
            ?.trim()?.takeIf { it.length >= MIN_TEXT_CHARS } ?: return null
        val letters = text.filter { it.isLetter() }
        val cyrillic = letters.count { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
        val textLang = if (cyrillic * 10 >= letters.length * 6) "ru" else "en"
        return TrackInfo.splitFacts(text).takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, textLang) }
    }

    // ---- Helpers ----

    private const val MIN_SCORE = 90
    private const val LASTFM = "https://ws.audioscrobbler.com/2.0/"
    private const val LASTFM_INVALID_KEY = 10

    /** A phone browser's signature: only to tell whether Last.fm refuses the app's way of asking. */
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    private const val MIN_LISTENERS = 1_000
    private const val MAX_SIMILAR = 4
    private const val MIN_TEXT_CHARS = 60


    private fun cyrillic(s: String) = s.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }


    /** "1988-01-05" → "5 января 1988 года" / "January 5, 1988". */
    private fun dateWords(date: String, lang: String): String? = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date) ?: return null
        val locale = Locale(lang)
        if (lang == "ru") SimpleDateFormat("d MMMM yyyy 'года'", locale).format(parsed)
        else DateFormat.getDateInstance(DateFormat.LONG, locale).format(parsed)
    }.getOrNull()

    /** Big counts the way they are said: "1,2 миллиона", "350 тысяч". */
    private fun number(n: Int, lang: String): String = when {
        n >= 1_000_000 -> String.format(Locale(lang), "%.1f", n / 1_000_000.0).removeSuffix(",0").removeSuffix(".0") +
            if (lang == "ru") " млн" else " million"
        n >= 10_000 -> "${n / 1000}" + if (lang == "ru") " тыс." else " thousand"
        else -> n.toString()
    }
}
