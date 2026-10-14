package com.knobnext

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The history of acquaintances: the artists KnobNext told about, and what it told. Kept only on the
 * phone, only for the tracks a story was asked about (not everything played), and only while the
 * setting is on. The screen reads it again whenever [Prefs.journalVersion] changes.
 */
object Journal {
    /** What a note was about. */
    enum class Topic { INTRO, SONG, ALBUM, ARTIST, CIRCLE }

    /** [topic] null: a note from before topics were kept, or a model's free story. */
    class Note(val at: Long, val track: String, val album: String?, val text: String, val topic: Topic?) {
        val intro get() = topic == Topic.INTRO
    }

    class Entry(
        val key: String,
        val name: String,
        val firstAt: Long,
        val lastAt: Long,
        /** How many times a story was asked about this artist. */
        val asked: Int,
        val notes: List<Note>,
        /** Nothing at all was found about them. */
        val unknown: Boolean,
        /** Unknown until a recheck found something: worth asking about again. */
        val foundNow: Boolean,
        /** When nothing was last found about them (an unknown artist is looked for again now and then). */
        val checkedAt: Long,
        /** The last track asked about, for the recheck. */
        val lastTrack: String?,
        val lastAlbum: String?,
        /** The person's own note: never said aloud, never sent anywhere, not in the cloud backup. */
        val own: String,
        /** The artists around them that stories named (members' other bands, influences). */
        val links: List<String>,
        /** The artist whose story named this one before the listener asked about them. */
        val via: String?,
        /** Their Wikidata item, when known. */
        val id: String?,
        /** Their own pages: "site", "bandcamp", "soundcloud" to address. */
        val pages: Map<String, String>,
        /** Album covers to fetch when the player had none: album (lowercased, cleaned) to address. */
        val coverUrls: Map<String, String>,
    ) {
        /** The albums the notes are about. */
        val albums get() = notes.mapNotNull { it.album?.let(Speech::cleanTitle)?.lowercase(Locale.ROOT) }.distinct().size
    }

    /** One artist however the player writes them: "Nirvana feat. …", "NIRVANA" and "Nirvana" are one. */
    fun key(artist: String) = Speech.cleanArtist(artist).trim().lowercase(Locale.ROOT).replace('ё', 'е')

    /**
     * A story told about [artist] ([text] null: nothing was found, unless [found]: then it was only a
     * reminder, nothing new to keep). [title] is the track's full title, kept to tell more later.
     */
    @Synchronized
    fun record(
        context: Context,
        artist: String,
        title: String,
        track: String,
        album: String?,
        text: String?,
        topic: Topic?,
        found: Boolean = text != null,
        /** The artist's Wikidata item: "Kino" and "Кино" are one artist. */
        id: String? = null,
        /** The artists around them the facts name, and their Wikidata items. */
        related: List<String> = emptyList(),
        relatedIds: List<String> = emptyList(),
        /** Where the album's cover is, for when the player had none. */
        coverUrl: String? = null,
        /** The artist's own pages. */
        pages: Map<String, String> = emptyMap(),
    ) {
        val root = read(context)
        val artists = root.optJSONObject("artists") ?: JSONObject().also { root.put("artists", it) }
        // Known under another spelling: the same Wikidata item.
        val k = key(artist).takeIf { artists.has(it) } ?: id?.let { byId(artists, it) } ?: key(artist)
        val now = System.currentTimeMillis()
        val e = artists.optJSONObject(k) ?: JSONObject().put("name", Speech.cleanArtist(artist)).put("first", now).also { fresh ->
            // Met through someone already in the history: a story about them named this artist first.
            artists.keys().asSequence().firstOrNull { other ->
                val o = artists.getJSONObject(other)
                links(o).any { key(it) == k } || id != null && strings(o, "linkIds").contains(id)
            }?.let { fresh.put("via", artists.getJSONObject(it).optString("name")) }
                // Or reached down the circle in the round card: the card before theirs.
                ?: cameBy(root, k)?.let { fresh.put("via", it) }
            artists.put(k, fresh)
        }
        id?.let { e.put("id", it) }
        if (pages.isNotEmpty()) e.put("pages", JSONObject(pages))
        if (coverUrl != null) {
            val covers = e.optJSONObject("coverUrls") ?: JSONObject().also { e.put("coverUrls", it) }
            covers.put(albumKey(album), coverUrl)
        }
        if (related.isNotEmpty()) {
            e.put("links", JSONArray((links(e) + related).distinctBy { key(it) }.filter { key(it) != k }.take(MAX_LINKS)))
        }
        if (relatedIds.isNotEmpty()) {
            e.put("linkIds", JSONArray((strings(e, "linkIds") + relatedIds).distinct().filter { it != id }.take(MAX_LINKS)))
        }
        e.put("last", now)
        e.put("asked", e.optInt("asked") + 1)
        e.put("lastTitle", title)
        if (album != null) e.put("lastAlbum", album) else e.remove("lastAlbum")
        e.remove("foundNow")
        if (text == null) {
            if (found) {
                // A reminder: nothing new to keep.
            } else if ((e.optJSONArray("notes")?.length() ?: 0) == 0) {
                e.put("unknown", true)
                e.put("checked", now)
            }
        } else {
            e.put("unknown", false)
            val notes = e.optJSONArray("notes") ?: JSONArray().also { e.put("notes", it) }
            notes.put(
                JSONObject().put("at", now).put("track", track).put("text", text)
                    .apply { album?.let { put("album", it) } }
                    .apply { topic?.let { put("topic", it.name) } },
            )
            while (notes.length() > MAX_NOTES) notes.remove(0)
        }
        // The artists not asked about for the longest go first when there are too many.
        if (artists.length() > MAX_ARTISTS) {
            artists.keys().asSequence().toList()
                .sortedBy { artists.getJSONObject(it).optLong("last") }
                .take(artists.length() - MAX_ARTISTS)
                .forEach { forgetFiles(context, it); artists.remove(it) }
        }
        write(context, root)
    }

    /** Everyone, the most recent first. */
    @Synchronized
    fun entries(context: Context): List<Entry> {
        val artists = read(context).optJSONObject("artists") ?: return emptyList()
        return artists.keys().asSequence().map { entry(it, artists.getJSONObject(it)) }.sortedByDescending { it.lastAt }.toList()
    }

    @Synchronized
    fun entry(context: Context, key: String): Entry? =
        read(context).optJSONObject("artists")?.optJSONObject(key)?.let { entry(key, it) }

    /**
     * What the slower sources found after the story was told (the album's cover, the artist's pages):
     * kept for an artist already in the history, so the card has them without another story.
     */
    @Synchronized
    fun learned(context: Context, artist: String, id: String?, album: String?, coverUrl: String?, pages: Map<String, String>) {
        val root = read(context)
        val artists = root.optJSONObject("artists") ?: return
        // Under another spelling ("Kino" for «Кино»): the same Wikidata item.
        val k = key(artist).takeIf { artists.has(it) } ?: id?.let { byId(artists, it) } ?: return
        val e = artists.getJSONObject(k)
        if (pages.isNotEmpty()) e.put("pages", JSONObject(map(e, "pages") + pages))
        if (coverUrl != null) {
            val covers = e.optJSONObject("coverUrls") ?: JSONObject().also { e.put("coverUrls", it) }
            covers.put(albumKey(album), coverUrl)
        }
        write(context, root)
    }

    /** Whether a story was already told about the artist behind this Wikidata item, under any spelling. */
    @Synchronized
    fun knows(context: Context, id: String): Boolean {
        val artists = read(context).optJSONObject("artists") ?: return false
        return byId(artists, id)?.let { (artists.getJSONObject(it).optJSONArray("notes")?.length() ?: 0) > 0 } == true
    }

    private fun byId(artists: JSONObject, id: String): String? =
        artists.keys().asSequence().firstOrNull { artists.getJSONObject(it).optString("id") == id }

    /** The unknown artists not looked for in a while, the longest ago first. */
    fun dueForRecheck(context: Context): List<Entry> {
        val now = System.currentTimeMillis()
        return entries(context)
            .filter { it.unknown && !it.foundNow && it.lastTrack != null && now - it.checkedAt > RECHECK_MS }
            .sortedBy { it.checkedAt }
    }

    /** The recheck's answer: [found] marks them as worth asking about again. */
    @Synchronized
    fun rechecked(context: Context, key: String, found: Boolean) {
        val root = read(context)
        val e = root.optJSONObject("artists")?.optJSONObject(key) ?: return
        e.put("checked", System.currentTimeMillis())
        if (found) e.put("foundNow", true)
        write(context, root)
    }

    @Synchronized
    fun setOwn(context: Context, key: String, text: String) {
        val root = read(context)
        val e = root.optJSONObject("artists")?.optJSONObject(key) ?: return
        if (e.optString("own") == text) return
        if (text.isBlank()) e.remove("own") else e.put("own", text)
        write(context, root)
    }

    @Synchronized
    fun forget(context: Context, key: String) {
        val root = read(context)
        root.optJSONObject("artists")?.remove(key)
        forgetFiles(context, key)
        write(context, root)
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        seenFile(context).delete()
        covers(context).deleteRecursively()
        // Gathered from the history: it goes with it.
        ArtistBase.clear(context)
        Prefs(context).journalVersion++
    }

    // ---- Listen later ----

    /** An artist (or one of their tracks, [title]) put aside from the round card; [via]: whose card named them. */
    class Later(val artist: String, val title: String?, val via: String?, val at: Long)

    /** What was put aside, the latest first. */
    @Synchronized
    fun later(context: Context): List<Later> {
        val list = read(context).optJSONArray("later") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.map { o ->
            Later(o.optString("artist"), o.optString("title").takeIf { it.isNotEmpty() }, o.optString("via").takeIf { it.isNotEmpty() }, o.optLong("at"))
        }.sortedByDescending { it.at }
    }

    @Synchronized
    fun isLater(context: Context, artist: String, title: String?): Boolean =
        later(context).any { same(it, artist, title) }

    /** Puts the artist (or track) aside, or takes it back when it already was: true when it is aside now. */
    @Synchronized
    fun toggleLater(context: Context, artist: String, title: String?, via: String?): Boolean {
        val root = read(context)
        val list = root.optJSONArray("later") ?: JSONArray()
        val kept = JSONArray()
        var had = false
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val l = Later(o.optString("artist"), o.optString("title").takeIf { it.isNotEmpty() }, null, 0)
            if (same(l, artist, title)) had = true else kept.put(o)
        }
        if (!had) {
            kept.put(
                JSONObject().put("artist", Speech.cleanArtist(artist)).put("at", System.currentTimeMillis())
                    .apply { title?.let { put("title", Speech.cleanTitle(it)) } }
                    .apply { via?.let { put("via", Speech.cleanArtist(it)) } },
            )
            while (kept.length() > MAX_LATER) kept.remove(0)
        }
        root.put("later", kept)
        write(context, root)
        return !had
    }

    private fun same(l: Later, artist: String, title: String?) =
        key(l.artist) == key(artist) && (l.title?.let(Speech::cleanTitle)?.lowercase(Locale.ROOT)) == title?.let(Speech::cleanTitle)?.lowercase(Locale.ROOT)

    // ---- Trails down the circle ----

    /**
     * A way down the circle in the round card: from the track's artist, card after card, in the order
     * they were first opened. [played]: the artists put on from it.
     */
    class Trail(val at: Long, val steps: List<String>, val played: List<String>)

    /** The latest first. */
    @Synchronized
    fun trails(context: Context): List<Trail> {
        val list = read(context).optJSONArray("trails") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
            .map { Trail(it.optLong("at"), strings(it, "steps"), strings(it, "played")) }
            .sortedByDescending { it.at }
    }

    /** The trail begun at [at] as it is now: [steps] so far, and [played] added to what was put on from it. */
    @Synchronized
    fun keepTrail(context: Context, at: Long, steps: List<String>, played: String? = null) {
        if (steps.size < 2) return
        val root = read(context)
        val list = root.optJSONArray("trails") ?: JSONArray().also { root.put("trails", it) }
        val o = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optLong("at") == at }
            ?: JSONObject().put("at", at).also { list.put(it) }
        o.put("steps", JSONArray(steps.map(Speech::cleanArtist)))
        if (played != null) o.put("played", JSONArray((strings(o, "played") + Speech.cleanArtist(played)).distinctBy(::key)))
        while (list.length() > MAX_TRAILS) list.remove(0)
        write(context, root)
    }

    /** The artist whose card led to [k]'s in a trail, if any did. */
    private fun cameBy(root: JSONObject, k: String): String? {
        val list = root.optJSONArray("trails") ?: return null
        for (i in list.length() - 1 downTo 0) {
            val steps = strings(list.getJSONObject(i), "steps")
            val at = steps.indexOfFirst { key(it) == k }
            if (at > 0) return steps[at - 1]
        }
        return null
    }

    // ---- Artists met ----

    /**
     * Whether [artist] is met for the first time: never in the history, never seen in the round card.
     * Each artist is new once; kept apart from the history so that it does not change with every track.
     */
    @Synchronized
    fun firstMeeting(context: Context, artist: String): Boolean {
        val k = key(artist)
        val f = seenFile(context)
        val seen = runCatching { f.readLines() }.getOrDefault(emptyList())
        if (k in seen) return false
        runCatching { f.writeText((seen.takeLast(MAX_SEEN - 1) + k).joinToString("\n")) }
        return read(context).optJSONObject("artists")?.has(k) != true
    }

    private fun seenFile(context: Context) = File(File(context.filesDir, "trackinfo").apply { mkdirs() }, "seen.txt")

    // ---- Covers ----

    /**
     * Keeps the player's cover of [album] (or of the track, when there is no album) small, once: the
     * history shows it next to the artist. Covers come from the player, so they work for any artist,
     * however unknown, and nothing is downloaded.
     */
    fun saveCover(context: Context, artist: String, album: String?, art: Bitmap?) = saveCoverOf(context, key(artist), album, art)

    /** The same, for an artist already in the history (a cover fetched for the card). */
    fun saveCoverOf(context: Context, key: String, album: String?, art: Bitmap?) {
        if (art == null || art.isRecycled || art.width <= 0 || art.height <= 0) return
        val file = coverFile(context, key, album)
        if (file.exists()) {
            // Touched, so the artist's latest album is the one shown.
            file.setLastModified(System.currentTimeMillis())
            return
        }
        runCatching {
            val scale = COVER_PX.toFloat() / maxOf(art.width, art.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true) else art
            file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it) }
            Prefs(context).journalVersion++
        }
    }

    /** The cover of [album], or null when none was kept. */
    fun cover(context: Context, key: String, album: String?): File? = coverFile(context, key, album).takeIf { it.exists() }

    /** The cover of the album heard most recently. */
    fun latestCover(context: Context, key: String): File? =
        covers(context).listFiles { f -> f.name.startsWith(prefix(key)) }?.maxByOrNull { it.lastModified() }

    private fun covers(context: Context) = File(File(context.filesDir, "trackinfo"), "covers")

    private fun prefix(key: String) = sha1(key).take(PREFIX_CHARS) + "-"

    private fun coverFile(context: Context, key: String, album: String?) =
        File(covers(context).apply { mkdirs() }, prefix(key) + sha1(albumKey(album)) + ".jpg")

    private fun forgetFiles(context: Context, key: String) {
        covers(context).listFiles { f -> f.name.startsWith(prefix(key)) }?.forEach { it.delete() }
    }

    // ---- Storage ----

    private fun entry(key: String, o: JSONObject): Entry {
        val arr = o.optJSONArray("notes") ?: JSONArray()
        val notes = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val topic = it.optString("topic").takeIf { t -> t.isNotEmpty() }?.let { t -> runCatching { Topic.valueOf(t) }.getOrNull() }
                // Notes from before topics were kept: only the introduction was marked.
                ?: Topic.INTRO.takeIf { _ -> it.optBoolean("intro") }
            Note(it.optLong("at"), it.optString("track"), it.optString("album").takeIf { a -> a.isNotEmpty() }, it.optString("text"), topic)
        }
        return Entry(
            key = key,
            name = o.optString("name", key),
            firstAt = o.optLong("first"),
            lastAt = o.optLong("last"),
            asked = o.optInt("asked"),
            notes = notes,
            unknown = o.optBoolean("unknown"),
            foundNow = o.optBoolean("foundNow"),
            checkedAt = o.optLong("checked", o.optLong("last")),
            lastTrack = o.optString("lastTitle").takeIf { it.isNotEmpty() } ?: notes.lastOrNull()?.track,
            lastAlbum = o.optString("lastAlbum").takeIf { it.isNotEmpty() } ?: notes.lastOrNull()?.album,
            own = o.optString("own"),
            links = links(o),
            via = o.optString("via").takeIf { it.isNotEmpty() },
            id = o.optString("id").takeIf { it.isNotEmpty() },
            pages = map(o, "pages"),
            coverUrls = map(o, "coverUrls"),
        )
    }

    private fun links(o: JSONObject): List<String> = strings(o, "links")

    private fun map(o: JSONObject, name: String): Map<String, String> =
        o.optJSONObject(name)?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } }.orEmpty()

    fun albumKey(album: String?) = album?.let(Speech::cleanTitle)?.lowercase(Locale.ROOT) ?: ""

    private fun strings(o: JSONObject, name: String): List<String> =
        o.optJSONArray(name)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()

    private fun file(context: Context) = File(File(context.filesDir, "trackinfo").apply { mkdirs() }, "journal.json")

    private fun read(context: Context): JSONObject =
        runCatching { JSONObject(file(context).readText()) }.getOrElse { JSONObject() }

    private fun write(context: Context, root: JSONObject) {
        runCatching { file(context).writeText(root.toString()) }
        Prefs(context).journalVersion++
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val MAX_NOTES = 50
    private const val MAX_LATER = 200
    private const val MAX_TRAILS = 50
    private const val MAX_SEEN = 3000
    private const val MAX_ARTISTS = 500
    private const val MAX_LINKS = 30
    private const val COVER_PX = 256
    private const val COVER_QUALITY = 85
    private const val PREFIX_CHARS = 12

    /** An unknown artist is looked for again no sooner than this. */
    private const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000
}
