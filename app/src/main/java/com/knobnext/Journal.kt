package com.knobnext

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The history of acquaintances: the artists KnobNext told about, and what it told. Kept only on the
 * phone, only for the tracks a story was asked about (not everything played), and only while the
 * setting is on. The screen reads it again whenever [Prefs.journalVersion] changes.
 */
object Journal {
    class Note(val at: Long, val track: String, val text: String, val intro: Boolean)

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
    )

    fun key(artist: String) = artist.trim().lowercase(Locale.ROOT)

    /** A story told about [artist] ([text] null: nothing was found). */
    @Synchronized
    fun record(context: Context, artist: String, track: String, text: String?, intro: Boolean, progress: Int?) {
        val root = read(context)
        val artists = root.optJSONObject("artists") ?: JSONObject().also { root.put("artists", it) }
        val k = key(artist)
        val now = System.currentTimeMillis()
        val e = artists.optJSONObject(k) ?: JSONObject().put("name", artist).put("first", now).also { artists.put(k, it) }
        e.put("last", now)
        e.put("asked", e.optInt("asked") + 1)
        if (text == null) {
            if ((e.optJSONArray("notes")?.length() ?: 0) == 0) e.put("unknown", true)
        } else {
            e.put("unknown", false)
            val notes = e.optJSONArray("notes") ?: JSONArray().also { e.put("notes", it) }
            notes.put(JSONObject().put("at", now).put("track", track).put("text", text).put("intro", intro))
            while (notes.length() > MAX_NOTES) notes.remove(0)
        }
        progress?.let { e.put("progress", it) }
        // The artists not asked about for the longest go first when there are too many.
        if (artists.length() > MAX_ARTISTS) {
            artists.keys().asSequence().toList()
                .sortedBy { artists.getJSONObject(it).optLong("last") }
                .take(artists.length() - MAX_ARTISTS)
                .forEach { artists.remove(it) }
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

    @Synchronized
    fun forget(context: Context, key: String) {
        val root = read(context)
        root.optJSONObject("artists")?.remove(key)
        write(context, root)
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        Prefs(context).journalVersion++
    }

    private fun entry(key: String, o: JSONObject): Entry {
        val arr = o.optJSONArray("notes") ?: JSONArray()
        val notes = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Note(it.optLong("at"), it.optString("track"), it.optString("text"), it.optBoolean("intro"))
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
        )
    }

    private fun file(context: Context) = File(File(context.filesDir, "trackinfo").apply { mkdirs() }, "journal.json")

    private fun read(context: Context): JSONObject =
        runCatching { JSONObject(file(context).readText()) }.getOrElse { JSONObject() }

    private fun write(context: Context, root: JSONObject) {
        runCatching { file(context).writeText(root.toString()) }
        Prefs(context).journalVersion++
    }

    private const val MAX_NOTES = 50
    private const val MAX_ARTISTS = 500
}
