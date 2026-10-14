package com.knobnext

import android.content.Context
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * ListenBrainz, MusicBrainz's sister for listening: open and free.
 *  - It recognises a track from "artist + title" however a player writes them, and says which recording
 *    and album of MusicBrainz it is: an album for players that give none (YouTube, SoundCloud).
 *  - With the person's token, KnobNext writes down what they listen to in any player on the phone.
 *  - Their most listened artists come first when the artist base is gathered.
 */
object ListenBrainz {
    /** What a track is, as MusicBrainz knows it. */
    class Match(
        val artist: String,
        val title: String,
        val album: String?,
        val artistMbid: String?,
        val recordingMbid: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().put("artist", artist).put("title", title).put("album", album ?: "")
            .put("artistMbid", artistMbid ?: "").put("recordingMbid", recordingMbid ?: "")

        companion object {
            fun fromJson(o: JSONObject) = Match(
                o.optString("artist"), o.optString("title"), o.optString("album").ifBlank { null },
                o.optString("artistMbid").ifBlank { null }, o.optString("recordingMbid").ifBlank { null },
            )
        }
    }

    // ---- Recognising ----

    private val matches = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Match?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Match?>) = size > MAX_IN_MEMORY
    })

    /** [artist]'s [title] as MusicBrainz knows it, or null; kept on the phone either way. */
    fun lookup(context: Context, artist: String?, title: String): Match? {
        val a = artist?.let(Speech::cleanArtist)?.takeIf { it.isNotBlank() } ?: return null
        val t = Speech.cleanTitle(title).takeIf { it.isNotBlank() } ?: return null
        val key = "${a.lowercase(Locale.ROOT)}\u0000${t.lowercase(Locale.ROOT)}"
        if (matches.containsKey(key)) return matches[key]
        val file = File(dir(context), "match-" + sha1(key))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < MATCH_FRESH_MS) {
            val kept = runCatching { file.readText().takeIf { it.isNotBlank() }?.let { Match.fromJson(JSONObject(it)) } }
            if (kept.isSuccess) return kept.getOrNull().also { matches[key] = it }
        }
        val reply = get("$API/1/metadata/lookup/?artist_name=${enc(a)}&recording_name=${enc(t)}", Prefs(context).lbToken)
        // No answer at all (offline): not kept, asked again next time.
        if (reply == null) return null
        val found = reply.optString("recording_name").takeIf { it.isNotBlank() }?.let { name ->
            Match(
                artist = reply.optString("artist_credit_name").ifBlank { a },
                title = name,
                album = reply.optString("release_name").takeIf { it.isNotBlank() && it != "null" },
                artistMbid = reply.optJSONArray("artist_mbids")?.optString(0)?.takeIf { it.isNotBlank() },
                recordingMbid = reply.optString("recording_mbid").takeIf { it.isNotBlank() },
            )
        }?.takeIf { same(it, a, t) }
        matches[key] = found
        runCatching { file.writeText(found?.toJson()?.toString().orEmpty()) }
        trim(context)
        return found
    }

    /**
     * The lookup is loose by design; a match is taken only when it is the same song: its title within the
     * one asked (or the other way round) and the artist sharing a word, or the title the same exactly.
     */
    private fun same(m: Match, artist: String, title: String): Boolean {
        val t1 = norm(m.title)
        val t2 = norm(title)
        if (t1.isEmpty() || t2.isEmpty()) return false
        val titleOk = t1 == t2 || t1.contains(t2) || t2.contains(t1)
        val words = words(artist)
        val artistOk = words(m.artist).any { it in words } || norm(m.artist) == norm(artist)
        return titleOk && (artistOk || t1 == t2)
    }

    // ---- Writing listens down ----

    /** Whether the token works, and whose it is (kept for their statistics). */
    fun check(context: Context, token: String): KeyCheck {
        val result = KeyCheck.ask("$API/1/validate-token", mapOf("Authorization" to "Token ${token.trim()}")) { it.optBoolean("valid") }
        if (result is KeyCheck.Works) {
            get("$API/1/validate-token", token)?.optString("user_name")?.takeIf { it.isNotBlank() }?.let { Prefs(context).lbUser = it }
        }
        return result
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()

    /** The listen, sent now, or kept for later when it cannot be (offline): the queue goes with the next. */
    fun submit(context: Context, listen: JSONObject) {
        val app = context.applicationContext
        scope.launch {
            sending.withLock {
                val queue = File(dir(app), QUEUE)
                val waiting = runCatching { queue.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList()) + listen.toString()
                val prefs = Prefs(app)
                val batch = waiting.takeLast(MAX_QUEUE)
                val sent = post(prefs.lbToken, if (batch.size == 1) "single" else "import", batch.map { JSONObject(it) })
                if (sent) {
                    queue.delete()
                    prefs.lbSent = prefs.lbSent + batch.size
                    prefs.lbQueued = 0
                } else {
                    runCatching { queue.writeText(batch.joinToString("\n")) }
                    prefs.lbQueued = batch.size
                }
            }
        }
    }

    /** "Playing now": shown on their ListenBrainz page while it plays, not kept. */
    fun playingNow(context: Context, metadata: JSONObject) {
        val token = Prefs(context).lbToken
        scope.launch { post(token, "playing_now", listOf(JSONObject().put("track_metadata", metadata))) }
    }

    private fun post(token: String, type: String, listens: List<JSONObject>): Boolean {
        if (token.isBlank()) return false
        val body = JSONObject().put("listen_type", type).put("payload", JSONArray(listens))
        return runCatching {
            val conn = URL("$API/1/submit-listens").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", TrackInfo.USER_AGENT)
                conn.setRequestProperty("Authorization", "Token ${token.trim()}")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                // A listen it refuses (400: a malformed one) is not kept either: it would block the queue.
                conn.responseCode in 200..299 || conn.responseCode == HttpURLConnection.HTTP_BAD_REQUEST
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Follows what plays, in any player, and writes a listen down as ListenBrainz asks: once half the
     * track (or four minutes) has been heard; tracks under 30 seconds are not listens.
     */
    class Scrobbler(private val context: Context) {
        private val handler = Handler(Looper.getMainLooper())
        private var key: String? = null
        private var metadata: JSONObject? = null
        private var startedAt = 0L
        private var duration = 0L
        private var heard = 0L
        private var playingSince = 0L
        private var done = false
        private val check = Runnable { maybeSubmit() }

        fun onTrack(now: NowPlaying?) {
            val prefs = Prefs(context)
            if (!prefs.lbScrobble || prefs.lbToken.isBlank()) return reset()
            val c = now?.controller ?: return pause()
            val meta = c.metadata ?: return pause()
            val track = TrackInfo.trackOf(meta, c.packageName)
            val artist = track.artist?.takeIf { it.isNotBlank() } ?: return pause()
            val k = "${c.packageName}/$artist/${track.title}"
            if (k != key) {
                pause()
                key = k
                done = false
                heard = 0L
                playingSince = 0L
                startedAt = System.currentTimeMillis() / 1000
                duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
                metadata = JSONObject()
                    .put("artist_name", artist)
                    .put("track_name", track.title)
                    .apply { track.album?.let { put("release_name", it) } }
                    .put(
                        "additional_info",
                        JSONObject()
                            .put("media_player", appLabel(context, c.packageName))
                            .put("submission_client", "KnobNext")
                            .put("submission_client_version", BuildConfig.VERSION_NAME)
                            .apply {
                                if (duration > 0) put("duration_ms", duration)
                                SERVICES[c.packageName]?.let { put("music_service", it) }
                            },
                    )
                if (now.playing && (duration == 0L || duration >= MIN_TRACK_MS)) playingNow(context, metadata!!)
            }
            if (now.playing) play() else pause()
        }

        private fun play() {
            if (playingSince == 0L) playingSince = SystemClock.elapsedRealtime()
            handler.removeCallbacks(check)
            val left = needed() - heardNow()
            handler.postDelayed(check, left.coerceAtLeast(0L) + CHECK_SLACK_MS)
        }

        private fun pause() {
            if (playingSince > 0L) heard += SystemClock.elapsedRealtime() - playingSince
            playingSince = 0L
            handler.removeCallbacks(check)
            maybeSubmit()
        }

        private fun reset() {
            handler.removeCallbacks(check)
            key = null
            metadata = null
        }

        private fun heardNow() = heard + if (playingSince > 0L) SystemClock.elapsedRealtime() - playingSince else 0L

        private fun needed() = if (duration > 0) minOf(duration / 2, MAX_NEEDED_MS) else MAX_NEEDED_MS

        private fun maybeSubmit() {
            val m = metadata ?: return
            if (done || (duration in 1 until MIN_TRACK_MS) || heardNow() < needed()) return
            done = true
            submit(context, JSONObject().put("listened_at", startedAt).put("track_metadata", m))
        }
    }

    // ---- Their statistics ----

    /** The artists they listened to most these three months, the most first (with their token's name). */
    fun topArtists(context: Context): List<String> {
        val user = Prefs(context).lbUser.takeIf { it.isNotBlank() } ?: return emptyList()
        val o = get("$API/1/stats/user/${enc(user)}/artists?range=quarter&count=$TOP_ARTISTS", "") ?: return emptyList()
        val list = o.optJSONObject("payload")?.optJSONArray("artists") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("artist_name")?.takeIf { n -> n.isNotBlank() } }
    }

    // ---- Requests ----

    private fun get(url: String, token: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", TrackInfo.USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Token ${token.trim()}")
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() }.ifBlank { "{}" })
                // Asked and answered "nothing like it": an empty answer, not a failure.
                HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND -> JSONObject()
                else -> null
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun dir(context: Context) = File(context.filesDir, "listenbrainz").apply { mkdirs() }

    /** Only the latest matches are kept. */
    private fun trim(context: Context) {
        val files = dir(context).listFiles { f -> f.name.startsWith("match-") } ?: return
        if (files.size <= MAX_KEPT) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_KEPT).forEach { it.delete() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun words(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Where a player's music comes from, as ListenBrainz names services. */
    private val SERVICES = mapOf(
        "com.spotify.music" to "spotify.com",
        "com.google.android.apps.youtube.music" to "music.youtube.com",
        "com.google.android.youtube" to "youtube.com",
        "com.soundcloud.android" to "soundcloud.com",
        "deezer.android.app" to "deezer.com",
        "ru.yandex.music" to "music.yandex.ru",
        "com.apple.android.music" to "music.apple.com",
        "com.aspiro.tidal" to "tidal.com",
        "com.bandcamp.android" to "bandcamp.com",
    )

    private const val API = "https://api.listenbrainz.org"
    private const val TIMEOUT_MS = 6_000
    private const val QUEUE = "queue.jsonl"
    private const val MAX_QUEUE = 500
    private const val MAX_IN_MEMORY = 100
    private const val MAX_KEPT = 1_000
    private const val MATCH_FRESH_MS = 30L * 24 * 60 * 60 * 1000
    private const val MIN_TRACK_MS = 30_000L
    private const val MAX_NEEDED_MS = 4L * 60 * 1000
    private const val CHECK_SLACK_MS = 1_000L
    private const val TOP_ARTISTS = 50
}
