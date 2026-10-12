package com.knobnext

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * MusicBrainz, deeper: who plays in a band and since when, on what; who produced and engineered the
 * recording and where it was made; the bands a singer plays in; the artist's own pages (site,
 * Bandcamp). Anyone can add to MusicBrainz, musicians themselves too, so this is often all there is
 * about a small artist. MusicBrainz asks for no more than one request a second, so these come after
 * the quick facts, in the background, and join them for the next telling.
 */
object Brainz {
    class Found(
        val song: TrackInfo.Text?,
        val artist: TrackInfo.Text?,
        /** Who the artist is, from MusicBrainz alone: when and where they began. */
        val basics: List<String>,
        /** The bands a singer plays in, a sentence each, and their names. */
        val circle: List<String>,
        val related: List<String>,
        /** The cover of the album the recording is on (Cover Art Archive), when there is one. */
        val coverUrl: String?,
        /** The artist's own pages: kind ("site", "bandcamp", "soundcloud") to address. */
        val pages: Map<String, String>,
        /** The artist's MusicBrainz id: setlist.fm knows them by it. */
        val artistMbid: String,
    )

    /**
     * [said]: everything already found, lowercased; a fact whose names are all in it is not said twice.
     * [mbid]: the artist's MusicBrainz id from Wikidata. Without it, a name search ("Kino") may find a
     * namesake, so it is used only when nothing else was found about the artist.
     */
    suspend fun gather(context: Context, lang: String, title: String, artist: String, album: String?, said: String, mbid: String? = null): Found? {
        val res = TrackInfo.localized(context, lang)
        val recording = recording(title, artist, album)
        val artistId = mbid ?: recording?.artistId ?: (if (said.isBlank()) artistId(artist) else null) ?: return null
        val a = get("$API/artist/$artistId?inc=artist-rels+url-rels&fmt=json") ?: return null
        val rec = recording?.id?.let { get("$API/recording/$it?inc=artist-rels+place-rels&fmt=json") }
        val known = said.lowercase(Locale.ROOT)
        fun new(vararg names: String) = names.any { !known.contains(it.lowercase(Locale.ROOT)) }
        // MusicBrainz writes names as the artists do: "Kurt Cobain" in a Russian story about a band the
        // articles already present in Russian would only jar. For a small artist it is all there is.
        fun sayable(name: String) = lang != "ru" || said.isBlank() || name.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }

        val group = a.optString("type") == "Group"
        val relations = a.optJSONArray("relations")?.let { r -> (0 until r.length()).mapNotNull { r.optJSONObject(it) } }.orEmpty()
        val artistFacts = ArrayList<String>()
        val circle = ArrayList<String>()
        val related = ArrayList<String>()
        val members = relations.filter { it.optString("type") == "member of band" }
        if (group) {
            val band = members.filter { it.optString("direction") == "backward" }
            val first = band.filter { "original" in attributes(it) }.mapNotNull { name(it, "artist") }.distinct()
            if (first.size >= 2 && first.all(::sayable) && new(*first.toTypedArray())) {
                artistFacts += res.getString(R.string.src_mb_first_lineup, first.joinToString(", "))
            }
            // MusicBrainz keeps one relation per instrument: one sentence per person, all they play.
            band.groupBy { name(it, "artist") }.entries.filter { (who, _) -> who != null && sayable(who) }.take(MAX_MEMBERS).forEach { (who, rels) ->
                val since = rels.mapNotNull { year(it, "begin") }.minOrNull() ?: return@forEach
                val ended = rels.all { year(it, "end") != null }
                val until = if (ended) rels.mapNotNull { year(it, "end") }.maxOrNull() else null
                val plays = rels.flatMap { attributes(it) }.mapNotNull { instrument(it, lang) }.distinct().take(2)
                    .joinToString(if (lang == "ru") " и " else " and ")
                artistFacts += when {
                    until == since && plays.isNotEmpty() -> res.getString(R.string.src_mb_member_plays_year, who, plays, since)
                    until == since -> res.getString(R.string.src_mb_member_year, who, since)
                    until != null && plays.isNotEmpty() -> res.getString(R.string.src_mb_member_plays_span, who, plays, since, until)
                    until != null -> res.getString(R.string.src_mb_member_span, who, since, until)
                    plays.isNotEmpty() -> res.getString(R.string.src_mb_member_plays_since, who, plays, since)
                    else -> res.getString(R.string.src_mb_member_since, who, since)
                }
            }
        } else {
            // A singer's bands are the artists around them.
            members.filter { it.optString("direction") == "forward" }.take(MAX_MEMBERS).forEach { m ->
                // Already said (Wikidata's "member of"): not again.
                val bandName = name(m, "artist")?.takeIf { new(it) && sayable(it) } ?: return@forEach
                val since = year(m, "begin")
                val until = year(m, "end")
                circle += when {
                    since != null && until != null -> res.getString(R.string.src_mb_in_band_span, bandName, since, until)
                    since != null -> res.getString(R.string.src_mb_in_band_since, bandName, since)
                    else -> res.getString(R.string.src_mb_in_band, bandName)
                }
                related += bandName
            }
        }

        val songFacts = ArrayList<String>()
        val recRelations = rec?.optJSONArray("relations")?.let { r -> (0 until r.length()).mapNotNull { r.optJSONObject(it) } }.orEmpty()
        fun people(vararg types: String) = recRelations.filter { it.optString("type") in types }.mapNotNull { name(it, "artist") }.distinct().take(MAX_NAMES)
        people("producer").takeIf { it.isNotEmpty() && it.all(::sayable) && new(*it.toTypedArray()) }?.let { songFacts += res.getString(R.string.src_mb_producer, it.joinToString(", ")) }
        people("engineer", "recording").takeIf { it.isNotEmpty() && it.all(::sayable) && new(*it.toTypedArray()) }?.let { songFacts += res.getString(R.string.src_mb_engineer, it.joinToString(", ")) }
        recRelations.filter { it.optString("type") == "recorded at" }.mapNotNull { name(it, "place") }.firstOrNull()
            ?.takeIf { new(it) && sayable(it) }?.let { songFacts += res.getString(R.string.src_mb_studio, it) }

        val pages = LinkedHashMap<String, String>()
        relations.forEach { r ->
            val url = r.optJSONObject("url")?.optString("resource")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@forEach
            when (r.optString("type")) {
                "official homepage" -> pages.putIfAbsent("site", url)
                "bandcamp" -> pages.putIfAbsent("bandcamp", url)
                "soundcloud" -> pages.putIfAbsent("soundcloud", url)
            }
        }

        val basics = ArrayList<String>()
        val begin = a.optJSONObject("life-span")?.let { year(it, "begin") }
        if (group && begin != null) basics += res.getString(R.string.src_formed, begin)
        // Places come in English ("Sankt-Peterburg"): said in a Russian story only for a small artist.
        (a.optJSONObject("begin-area") ?: a.optJSONObject("area"))?.optString("name")?.takeIf { it.isNotBlank() && sayable(it) }
            ?.let { basics += res.getString(if (group) R.string.src_band_from else R.string.src_artist_from, it) }

        return Found(
            song = songFacts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) },
            artist = artistFacts.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) },
            basics = basics,
            circle = circle,
            related = related,
            coverUrl = recording?.releaseGroup?.let { "https://coverartarchive.org/release-group/$it/front-250" },
            pages = pages,
            artistMbid = artistId,
        )
    }

    private class Recording(val id: String, val artistId: String?, val releaseGroup: String?)

    /** The recording, its artist, and the album it is on: the player's album when it names one. */
    private suspend fun recording(title: String, artist: String, album: String?): Recording? {
        val q = "recording:\"${clean(Speech.cleanTitle(title))}\" AND artist:\"${clean(artist)}\""
        val list = get("$API/recording/?fmt=json&limit=3&query=${enc(q)}")?.optJSONArray("recordings") ?: return null
        val r = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optInt("score") >= MIN_SCORE } ?: return null
        val credit = r.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist")
        if (credit == null || !sameName(credit.optString("name"), artist)) return null
        val releases = r.optJSONArray("releases")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val release = album?.let { name -> releases.firstOrNull { sameName(it.optString("title"), Speech.cleanTitle(name)) } }
            ?: releases.firstOrNull { it.optJSONObject("release-group")?.optString("primary-type") == "Album" }
            ?: releases.firstOrNull()
        return Recording(r.optString("id"), credit.optString("id").takeIf { it.isNotBlank() }, release?.optJSONObject("release-group")?.optString("id")?.takeIf { it.isNotBlank() })
    }

    private suspend fun artistId(artist: String): String? {
        val a = get("$API/artist/?fmt=json&limit=1&query=${enc("artist:\"${clean(artist)}\"")}")
            ?.optJSONArray("artists")?.optJSONObject(0) ?: return null
        if (a.optInt("score") < MIN_SCORE || !sameName(a.optString("name"), artist)) return null
        return a.optString("id").takeIf { it.isNotBlank() }
    }

    private fun attributes(r: JSONObject): List<String> =
        r.optJSONArray("attributes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    private fun name(r: JSONObject, target: String) = r.optJSONObject(target)?.optString("name")?.takeIf { it.isNotBlank() }

    // optString gives "null" for a JSON null: only four digits count.
    private fun year(r: JSONObject, field: String) = r.optString(field).take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }

    /** An instrument's name to say; one not in the list is left out rather than said in English to a Russian ear. */
    private fun instrument(attribute: String, lang: String): String? {
        val ru = INSTRUMENTS[attribute.lowercase(Locale.ROOT)] ?: return null
        return if (lang == "ru") ru else attribute.lowercase(Locale.ROOT).removeSuffix(" (drum set)")
    }

    // ---- Requests: one a second, as MusicBrainz asks ----

    private val gate = Mutex()
    private var last = 0L

    private suspend fun get(url: String): JSONObject? = gate.withLock {
        val wait = last + SPACING_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            TrackInfo.getJson(url)
        } finally {
            last = System.currentTimeMillis()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun clean(s: String) = s.replace("\"", "")

    private fun sameName(found: String, wanted: String): Boolean {
        fun n(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")
        val a = n(found)
        val b = n(wanted)
        return a.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
    }

    private const val API = "https://musicbrainz.org/ws/2"
    private const val MIN_SCORE = 90
    private const val MAX_MEMBERS = 6
    private const val MAX_NAMES = 3
    private const val SPACING_MS = 1_100L

    private val INSTRUMENTS = mapOf(
        "guitar" to "гитара", "electric guitar" to "электрогитара", "acoustic guitar" to "акустическая гитара",
        "bass guitar" to "бас-гитара", "bass" to "бас", "double bass" to "контрабас",
        "drums (drum set)" to "ударные", "drums" to "ударные", "percussion" to "перкуссия",
        "keyboard" to "клавишные", "synthesizer" to "синтезатор", "piano" to "фортепиано", "organ" to "орган",
        "violin" to "скрипка", "cello" to "виолончель", "saxophone" to "саксофон", "trumpet" to "труба",
        "flute" to "флейта", "accordion" to "аккордеон", "harmonica" to "губная гармоника",
        "lead vocals" to "вокал", "vocals" to "вокал", "background vocals" to "бэк-вокал",
        "turntables" to "вертушки", "programming" to "программирование",
    )
}
