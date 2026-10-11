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
 *  - MusicBrainz: where the artist is from, when a band was formed, genres.
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
    )

    suspend fun gather(context: Context, title: String, artist: String?, album: String?, lang: String): Found = coroutineScope {
        if (artist == null) return@coroutineScope Found(emptyList(), emptyList(), emptyList(), null, null)
        val res = TrackInfo.localized(context, lang)
        val audioDbArtist = async { runCatching { audioDbArtist(res, artist, lang) }.getOrNull() }
        val audioDbTrack = async { runCatching { audioDbTrack(artist, title, lang) }.getOrNull() }
        val deezer = async { runCatching { deezer(res, artist, title, lang) }.getOrNull() }
        val brainz = async { runCatching { brainzArtist(res, artist, lang) }.getOrNull() }
        val d = deezer.await()
        // The album's description needs its name: the player's, else the one Deezer found.
        val albumName = album ?: d?.albumName
        val audioDbAlbum = albumName?.let { runCatching { audioDbAlbum(artist, it, lang) }.getOrNull() }
        Found(
            song = listOfNotNull(audioDbTrack.await()),
            album = listOfNotNull(audioDbAlbum, d?.album),
            artist = listOfNotNull(audioDbArtist.await(), d?.artist, brainz.await()),
            albumName = albumName,
            year = d?.year,
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

    // ---- MusicBrainz ----

    private fun brainzArtist(res: Context, artist: String, lang: String): TrackInfo.Text? {
        val q = "artist:\"${artist.replace("\"", "")}\""
        val a = TrackInfo.getJson("https://musicbrainz.org/ws/2/artist/?fmt=json&limit=1&query=${enc(q)}")
            ?.optJSONArray("artists")?.optJSONObject(0) ?: return null
        if (a.optInt("score") < MIN_SCORE || !sameName(a.optString("name"), artist)) return null
        val facts = ArrayList<String>()
        val group = a.optString("type") == "Group"
        val begin = a.optJSONObject("life-span")?.optString("begin")?.take(4)?.takeIf { it.length == 4 }
        if (group && begin != null) facts += res.getString(R.string.src_formed, begin)
        val place = a.optJSONObject("begin-area")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: a.optJSONObject("area")?.optString("name")?.takeIf { it.isNotBlank() }
        place?.let { facts += res.getString(if (group) R.string.src_band_from else R.string.src_artist_from, it) }
        a.optJSONArray("tags")?.let { tags ->
            val names = (0 until tags.length()).mapNotNull { tags.optJSONObject(it) }
                .sortedByDescending { it.optInt("count") }.map { it.optString("name") }.filter { it.isNotBlank() }.take(3)
            if (names.isNotEmpty()) facts += res.getString(R.string.src_genres, names.joinToString(", "))
        }
        return facts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) }
    }

    // ---- Helpers ----

    private const val MIN_SCORE = 90

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The source found this artist, not one with a similar name. */
    private fun sameName(found: String, wanted: String): Boolean {
        fun n(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")
        val a = n(found)
        val b = n(wanted)
        return a.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
    }

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
