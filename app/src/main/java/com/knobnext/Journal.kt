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
    enum class Topic { INTRO, SONG, ALBUM, ARTIST }

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
        /** How much of the artist's story has been told, in percent; null when unknown. */
        val progress: Int?,
        /** Nothing at all was found about them. */
        val unknown: Boolean,
        /** Unknown until a recheck found something: worth asking about again. */
        val foundNow: Boolean,
        /** When nothing was last found about them (an unknown artist is looked for again now and then). */
        val checkedAt: Long,
        /** The last track asked about, for "tell more" and the recheck. */
        val lastTrack: String?,
        val lastAlbum: String?,
        /** The person's own note: never said aloud, never leaves the phone. */
        val own: String,
    )

    fun key(artist: String) = artist.trim().lowercase(Locale.ROOT)

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
        progress: Int?,
        found: Boolean = text != null,
    ) {
        val root = read(context)
        val artists = root.optJSONObject("artists") ?: JSONObject().also { root.put("artists", it) }
        val k = key(artist)
        val now = System.currentTimeMillis()
        val e = artists.optJSONObject(k) ?: JSONObject().put("name", artist).put("first", now).also { artists.put(k, it) }
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
        progress?.let { e.put("progress", it) }
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
        covers(context).deleteRecursively()
        Prefs(context).journalVersion++
    }

    // ---- Covers ----

    /**
     * Keeps the player's cover of [album] (or of the track, when there is no album) small, once: the
     * history shows it next to the artist. Covers come from the player, so they work for any artist,
     * however unknown, and nothing is downloaded.
     */
    fun saveCover(context: Context, artist: String, album: String?, art: Bitmap?) {
        if (art == null || art.isRecycled || art.width <= 0 || art.height <= 0) return
        val file = coverFile(context, key(artist), album)
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
        File(covers(context).apply { mkdirs() }, prefix(key) + sha1(album?.let(Speech::cleanTitle)?.lowercase(Locale.ROOT) ?: "") + ".jpg")

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
            progress = if (o.has("progress")) o.optInt("progress") else null,
            unknown = o.optBoolean("unknown"),
            foundNow = o.optBoolean("foundNow"),
            checkedAt = o.optLong("checked", o.optLong("last")),
            lastTrack = o.optString("lastTitle").takeIf { it.isNotEmpty() } ?: notes.lastOrNull()?.track,
            lastAlbum = o.optString("lastAlbum").takeIf { it.isNotEmpty() } ?: notes.lastOrNull()?.album,
            own = o.optString("own"),
        )
    }

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
    private const val MAX_ARTISTS = 500
    private const val COVER_PX = 256
    private const val COVER_QUALITY = 85
    private const val PREFIX_CHARS = 12

    /** An unknown artist is looked for again no sooner than this. */
    private const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000
}
