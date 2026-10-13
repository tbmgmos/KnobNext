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
        )
    }

    // ---- The recording itself, for the round card ----

    /** A song as the card shows it: its title, who plays it, and the year it first came out. */
    class Song(val title: String, val artist: String, val year: String?)

    /**
     * What the card says of the recording: who plays on it and what, who produced it and where; who
     * wrote the song; whether it is someone else's song and whose first; what it samples and who sampled
     * it; who else recorded the song.
     */
    class Record(
        /** Each player and what they play or sing, in the order MusicBrainz lists them. */
        val players: List<Pair<String, List<String>>>,
        val producers: List<String>,
        val studio: String?,
        val writers: List<String>,
        /** The artist plays someone else's song. */
        val cover: Boolean,
        /** The song's first recording by another artist: the original, when this is a cover. */
        val original: Song?,
        val samples: List<Song>,
        val sampledBy: List<Song>,
        /** Others' recordings of the song, the earliest first, one each; [coverCount]: how many artists in all. */
        val covers: List<Song>,
        val coverCount: Int,
    ) {
        val empty get() = players.isEmpty() && producers.isEmpty() && studio == null && writers.isEmpty() &&
            original == null && samples.isEmpty() && sampledBy.isEmpty() && covers.isEmpty()

        fun toJson(): JSONObject {
            fun songs(list: List<Song>) = org.json.JSONArray(list.map { song(it) })
            return JSONObject()
                .put("players", org.json.JSONArray(players.map { (who, what) -> JSONObject().put("name", who).put("plays", org.json.JSONArray(what)) }))
                .put("producers", org.json.JSONArray(producers))
                .put("studio", studio ?: "")
                .put("writers", org.json.JSONArray(writers))
                .put("cover", cover)
                .put("original", original?.let(::song) ?: JSONObject())
                .put("samples", songs(samples))
                .put("sampledBy", songs(sampledBy))
                .put("covers", songs(covers))
                .put("coverCount", coverCount)
        }

        companion object {
            private fun song(s: Song) = JSONObject().put("title", s.title).put("artist", s.artist).put("year", s.year ?: "")

            private fun songOf(o: JSONObject?): Song? =
                o?.optString("title")?.takeIf { it.isNotBlank() }?.let { Song(it, o.optString("artist"), o.optString("year").ifBlank { null }) }

            fun fromJson(o: JSONObject): Record {
                fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                fun songs(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { songOf(a.optJSONObject(it)) } }.orEmpty()
                val players = o.optJSONArray("players")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
                        p.optString("name") to (p.optJSONArray("plays")?.let { x -> (0 until x.length()).map { x.getString(it) } }.orEmpty())
                    }
                }.orEmpty()
                return Record(
                    players, strings("producers"), o.optString("studio").ifBlank { null }, strings("writers"), o.optBoolean("cover"),
                    songOf(o.optJSONObject("original")), songs("samples"), songs("sampledBy"), songs("covers"), o.optInt("coverCount"),
                )
            }
        }
    }

    /** The recording of [title] by [artist] as the card shows it; [mbid]: the artist's id, the right one. */
    suspend fun record(lang: String, title: String, artist: String, album: String?, mbid: String?): Record? {
        val name = clean(Speech.cleanTitle(title))
        // By the artist's id when known (the right one), else, or when that finds nothing, by name.
        var found = candidates(name, artistQuery(artist, mbid), artist, mbid)
        if (found.isEmpty() && mbid != null) found = candidates(name, artistQuery(artist, null), artist, null)
        if (found.isEmpty()) return null
        // The search finds live takes, edits and DJ mixes as readily as the record: the one on the album
        // first, then plain ones. Credits are often kept on one of several versions: a few are looked at,
        // the one that says most is taken.
        val album0 = album?.let(Speech::cleanTitle)
        val ranked = found.sortedWith(
            compareByDescending<JSONObject> { r -> album0 != null && releaseTitles(r).any { sameName(it, album0) } }
                .thenByDescending { it.optString("disambiguation").isBlank() && !it.optBoolean("video") }
                .thenByDescending { !LIVE.containsMatchIn(it.optString("disambiguation")) },
        )
        var r = ranked.first()
        var rels = emptyList<JSONObject>()
        for (candidate in ranked.take(LOOKED_AT)) {
            val rec = get("$API/recording/${candidate.optString("id")}?inc=artist-rels+place-rels+recording-rels+work-rels&fmt=json") ?: continue
            val these = rec.optJSONArray("relations")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            if (these.size > rels.size) {
                r = candidate
                rels = these
            }
            // Who plays on it and whose song it is: enough to stop looking.
            if (rels.any { it.optString("type") in PLAYER_TYPES } && rels.any { it.optString("target-type") == "work" }) break
        }
        val selfId = mbid ?: r.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist")?.optString("id")

        val players = LinkedHashMap<String, MutableList<String>>()
        rels.filter { it.optString("type") in PLAYER_TYPES }.forEach { rel ->
            val name = name(rel, "artist") ?: return@forEach
            val what = attributes(rel).ifEmpty { if (rel.optString("type") == "vocal") listOf("vocals") else emptyList() }
                .map { instrument(it, lang) ?: it.lowercase(Locale.ROOT) }
            players.getOrPut(name) { ArrayList() }.addAll(what.filter { it !in players[name].orEmpty() })
        }
        val producers = rels.filter { it.optString("type") == "producer" }.mapNotNull { name(it, "artist") }.distinct().take(MAX_NAMES)
        val studio = rels.firstOrNull { it.optString("type") == "recorded at" }?.let { name(it, "place") }

        // Samples: forward, this recording takes from the other; backward, the other takes from this one.
        suspend fun songs(direction: String): List<Song> =
            rels.filter { it.optString("type") == "samples material" && it.optString("direction") == direction }
                .mapNotNull { it.optJSONObject("recording")?.optString("id") }.distinct().take(MAX_SAMPLES)
                .mapNotNull { id -> get("$API/recording/$id?inc=artist-credits&fmt=json")?.let(::songOf) }
        val samples = songs("forward")
        val sampledBy = songs("backward")

        val performance = rels.firstOrNull { it.optString("target-type") == "work" && it.optString("type") == "performance" }
        val cover = performance?.let { "cover" in attributes(it) } == true
        val workId = performance?.optJSONObject("work")?.optString("id")?.takeIf { it.isNotBlank() }
        var writers = emptyList<String>()
        var original: Song? = null
        var covers = emptyList<Song>()
        var coverCount = 0
        if (workId != null) {
            val work = get("$API/work/$workId?inc=artist-rels&fmt=json")
            writers = work?.optJSONArray("relations")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                .filter { it.optString("type") in WRITER_TYPES }.mapNotNull { name(it, "artist") }.distinct().take(MAX_NAMES)
            // Everyone who recorded the song: one song each, the earliest first.
            val all = get("$API/recording?work=$workId&inc=artist-credits&limit=100&fmt=json")?.optJSONArray("recordings")
            fun firstArtist(o: JSONObject) = o.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist")?.optString("id")
            // One song per artist ("Lulu" and "Lulu & David Bowie" are one), their earliest.
            val others = all?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                .filter { o -> firstArtist(o) != selfId }
                .mapNotNull { o -> songOf(o)?.let { (firstArtist(o) ?: Journal.key(it.artist)) to it } }
                // Not the artist themselves under another credit ("Eminem & The Prodigy"), nor the song's
                // own writer singing it alone (Виктор Цой for «Кино»): those are not covers.
                .filter { (_, s) ->
                    val credit = Journal.key(s.artist)
                    !credit.contains(Journal.key(artist)) && writers.none { w -> Journal.key(w) == credit }
                }
                .sortedBy { (_, s) -> s.year ?: "9999" }
                .distinctBy { (who, _) -> who }
                .map { (_, s) -> s }
            if (cover) original = others.firstOrNull()
            covers = others.filter { it !== original }.take(MAX_COVERS)
            coverCount = others.size - (if (original != null) 1 else 0)
        }
        return Record(players.entries.map { it.key to it.value }.take(MAX_PLAYERS), producers, studio, writers, cover, original, samples, sampledBy, covers, coverCount)
    }

    private suspend fun candidates(name: String, who: String, artist: String, mbid: String?): List<JSONObject> {
        val list = get("$API/recording/?fmt=json&limit=25&query=${enc("recording:\"$name\" AND $who")}")?.optJSONArray("recordings")
            ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optInt("score") >= MIN_SCORE && credited(it, artist, mbid) }
    }

    private fun releaseTitles(r: JSONObject): List<String> =
        r.optJSONArray("releases")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("title") } }.orEmpty()

    private fun artistQuery(artist: String, mbid: String?) = if (mbid != null) "arid:$mbid" else "artist:\"${clean(Speech.cleanArtist(artist))}\""

    private fun credited(r: JSONObject, artist: String, mbid: String?): Boolean {
        val a = r.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist") ?: return false
        return if (mbid != null) a.optString("id") == mbid else sameName(a.optString("name"), Speech.cleanArtist(artist))
    }

    private fun songOf(r: JSONObject): Song? {
        val title = r.optString("title").takeIf { it.isNotBlank() } ?: return null
        val credit = r.optJSONArray("artist-credit")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.joinToString("") { it.optString("name") + it.optString("joinphrase") }
        }?.takeIf { it.isNotBlank() } ?: return null
        return Song(title, credit, year(r, "first-release-date"))
    }

    /**
     * The cover of [album] by [artist] in the Cover Art Archive, found by the album's name: for the
     * history, when the player showed none and no story has found one yet.
     */
    suspend fun coverOf(artist: String, album: String): String? {
        val name = Speech.cleanTitle(album)
        val who = clean(Speech.cleanArtist(artist))
        // The exact title first; then its words, for a title the player writes without its commas.
        val words = name.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().split(Regex("\\s+")).joinToString(" AND ")
        for (q in listOf("releasegroup:\"${clean(name)}\" AND artist:\"$who\"", "releasegroup:($words) AND artist:\"$who\"")) {
            val groups = get("$API/release-group/?fmt=json&limit=5&query=${enc(q)}")?.optJSONArray("release-groups") ?: continue
            (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }
                .firstOrNull { it.optInt("score") >= COVER_SCORE && sameName(it.optString("title"), name) }
                ?.optString("id")?.takeIf { it.isNotBlank() }
                ?.let { return "https://coverartarchive.org/release-group/$it/front-250" }
        }
        return null
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

    /** A cover is checked by its title too, so a lower search score will do. */
    private const val COVER_SCORE = 70
    private const val MAX_MEMBERS = 6
    private const val MAX_NAMES = 3
    private const val MAX_PLAYERS = 8
    private const val MAX_SAMPLES = 3
    private const val MAX_COVERS = 8

    /** Versions of a recording looked at for its credits: a request each. */
    private const val LOOKED_AT = 3
    private val PLAYER_TYPES = setOf("instrument", "vocal", "performer")
    private val WRITER_TYPES = setOf("composer", "lyricist", "writer")
    private val LIVE = Regex("(?i)live|mix|demo|remix|edit|instrumental|karaoke")
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
