package com.knobnext

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * setlist.fm: the artist on stage. From their latest concerts (one request): when and where they last
 * played, the tour, and whether this song is still played live, and where last. Nobody else knows this.
 * Concerts to come are left out: the facts are kept for weeks, and "the next concert" would go stale.
 * Needs the app's key (the SETLISTFM_API_KEY secret); without it there is nothing.
 */
object Setlist {
    class Found(val song: TrackInfo.Text?, val artist: TrackInfo.Text?)

    /** [mbid]: the artist's MusicBrainz id, which is how setlist.fm knows them. */
    fun gather(context: Context, lang: String, mbid: String, title: String): Found? {
        val key = Prefs(context).setlistKeyInUse.takeIf { it.isNotBlank() } ?: return null
        val list = get("$API/artist/$mbid/setlists?p=1", key)?.optJSONArray("setlist") ?: return null
        val res = TrackInfo.localized(context, lang)
        val today = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val shows = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { Show.of(it) }
        val played = shows.filter { it.date <= today && it.songs.isNotEmpty() }

        val artistFacts = ArrayList<String>()
        played.firstOrNull()?.let { last ->
            artistFacts += res.getString(R.string.src_setlist_last, words(last.date, lang), last.place)
            last.tour?.let { artistFacts += res.getString(R.string.src_setlist_tour, quoted(it, lang)) }
        }

        val songFacts = ArrayList<String>()
        val wanted = normalize(Speech.cleanTitle(title))
        if (wanted.isNotEmpty() && played.size >= MIN_SHOWS) {
            val with = played.filter { show -> show.songs.any { normalize(it) == wanted } }
            if (with.isNotEmpty()) {
                songFacts += res.getString(R.string.src_setlist_song_count, with.size.toString(), played.size.toString())
                val last = with.first()
                songFacts += res.getString(R.string.src_setlist_song_last, words(last.date, lang), last.place)
            } else if (played.size >= MANY_SHOWS) {
                songFacts += res.getString(R.string.src_setlist_song_none, played.size.toString())
            }
        }
        return Found(
            song = songFacts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) },
            artist = artistFacts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) },
        )
    }

    /** What setlist.fm says to [key]: a known artist's concerts must come back (Radiohead's MusicBrainz id). */
    fun check(key: String): KeyCheck =
        KeyCheck.ask("$API/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711/setlists?p=1", mapOf("x-api-key" to key.trim())) { it.has("setlist") }

    /** A concert: its day (UTC midnight), where ("venue, city"), its tour, the songs played. */
    private class Show(val date: Long, val place: String, val tour: String?, val songs: List<String>) {
        companion object {
            fun of(o: JSONObject): Show? {
                val date = runCatching {
                    SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(o.optString("eventDate"))?.time
                }.getOrNull() ?: return null
                val venue = o.optJSONObject("venue")
                val place = listOfNotNull(
                    venue?.optString("name")?.takeIf { it.isNotBlank() },
                    venue?.optJSONObject("city")?.optString("name")?.takeIf { it.isNotBlank() },
                ).joinToString(", ").ifEmpty { return null }
                val sets = o.optJSONObject("sets")?.optJSONArray("set")
                val songs = sets?.let { s ->
                    (0 until s.length()).flatMap { i ->
                        val songs = s.optJSONObject(i)?.optJSONArray("song") ?: return@flatMap emptyList()
                        (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                    }
                }.orEmpty()
                return Show(date, place, o.optJSONObject("tour")?.optString("name")?.takeIf { it.isNotBlank() }, songs)
            }
        }
    }

    private fun get(url: String, key: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "KnobNext (https://github.com/tbmgmos/KnobNext)")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** "1 марта 1994 года" / "March 1, 1994". */
    private fun words(date: Long, lang: String): String {
        val format = if (lang == "ru") SimpleDateFormat("d MMMM yyyy 'года'", Locale("ru")) else SimpleDateFormat("MMMM d, yyyy", Locale(lang))
        return format.apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
    }

    private fun quoted(name: String, lang: String) = if (lang == "ru") "«$name»" else "\"$name\""

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")

    private const val API = "https://api.setlist.fm/rest/1.0"
    private const val TIMEOUT_MS = 4_000

    /** Fewer known setlists than this say nothing about a song. */
    private const val MIN_SHOWS = 5

    /** "Not played at the last N concerts" only when N says something. */
    private const val MANY_SHOWS = 10
}
