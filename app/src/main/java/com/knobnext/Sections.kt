package com.knobnext

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What the round card says about a track, in parts that stay: who the artist is in a line or two
 * ([Kind.GENERAL]), then the artist, the album and the track on tabs of their own. Kept on the phone
 * by what they are about: the artist's parts for all their tracks, the album's for all of its own, so
 * the next track of an album opens with them at once and only its own part is asked for. Written by the
 * cloud model, or put together from the open sources' facts when there is none; kept until reset.
 */
object Sections {
    enum class Kind { GENERAL, ARTIST, ALBUM, TRACK }

    /** [by]: who wrote it ("Claude · claude-sonnet-5-5", or null for the facts as found); [sources]: pages found. */
    class Section(val text: String, val by: String?, val at: Long, val sources: List<CloudBrain.Source> = emptyList()) {
        /** Put together from the facts, not by a model: written anew once a model can. */
        val plain get() = by == null

        fun toJson(): JSONObject = JSONObject().put("text", text).put("by", by ?: "").put("at", at)
            .put("sources", JSONArray(sources.map { JSONObject().put("t", it.title).put("u", it.url) }))

        companion object {
            fun fromJson(o: JSONObject) = Section(
                o.optString("text"),
                o.optString("by").ifBlank { null },
                o.optLong("at"),
                o.optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                    .map { CloudBrain.Source(it.optString("t"), it.optString("u")) },
            )
        }
    }

    /** What [kind] of [track] is kept under; null when it cannot be told (no artist, no album). */
    fun key(kind: Kind, track: TrackInfo.Track, lang: String): String? {
        val artist = track.artist?.takeIf { it.isNotBlank() }?.let(Journal::key) ?: return null
        return when (kind) {
            Kind.GENERAL -> "g\u0000$artist\u0000$lang"
            Kind.ARTIST -> "a\u0000$artist\u0000$lang"
            Kind.ALBUM -> track.album?.let(Speech::cleanTitle)?.takeIf { it.isNotBlank() }
                ?.let { "b\u0000$artist\u0000${it.lowercase(Locale.ROOT)}\u0000$lang" }
            Kind.TRACK -> "t\u0000$artist\u0000${Speech.cleanTitle(track.title).lowercase(Locale.ROOT)}\u0000$lang"
        }
    }

    fun get(context: Context, key: String): Section? {
        synchronized(memory) { memory[key] }?.let { return it }
        val f = file(context, key)
        val s = f.takeIf { it.exists() }?.let { runCatching { Section.fromJson(JSONObject(it.readText())) }.getOrNull() } ?: return null
        synchronized(memory) { memory[key] = s }
        return s
    }

    fun put(context: Context, key: String, section: Section) {
        synchronized(memory) { memory[key] = section }
        runCatching { file(context, key).writeText(section.toJson().toString()) }
    }

    /** "Refresh" on a tab: that part is written anew. */
    fun forget(context: Context, key: String) {
        synchronized(memory) { memory.remove(key) }
        file(context, key).delete()
    }

    /** Everything kept goes: every part is written anew when next shown. */
    fun clear(context: Context) {
        synchronized(memory) { memory.clear() }
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun count(context: Context): Int = dir(context).listFiles()?.size ?: 0

    private val memory = object : LinkedHashMap<String, Section>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Section>) = size > MAX_IN_MEMORY
    }

    private fun dir(context: Context) = File(context.filesDir, "sections").apply { mkdirs() }

    private fun file(context: Context, key: String) = File(dir(context), sha1(key) + ".json")

    private const val MAX_IN_MEMORY = 200
}
