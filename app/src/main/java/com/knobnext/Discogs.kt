package com.knobnext

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Discogs: the record collectors' database. What the others lack: the artist's biography as collectors
 * write it, who played in the band, the labels, and a year for nearly every record ever pressed.
 * Without a token it allows 25 requests a minute, enough for the base gathered at night; a token (free,
 * in Discogs' settings) allows 60 and its search, for artists MusicBrainz and Wikidata do not link.
 */
object Discogs {
    class Artist(
        val id: Long,
        val name: String,
        val profile: List<String>,
        /** Members, the present ones first. */
        val members: List<String>,
        val realName: String?,
    )

    /** One of the artist's own records: a "master" (all its pressings) or a single release. */
    class Record(val title: String, val year: String?, val label: String?, val master: Boolean)

    suspend fun artist(id: Long, token: String): Artist? {
        val a = get("$API/artists/$id", token) ?: return null
        val members = a.optJSONArray("members")?.let { m ->
            (0 until m.length()).mapNotNull { m.optJSONObject(it) }
                .sortedByDescending { it.optBoolean("active") }
                .map { plainName(it.optString("name")) }.filter { it.isNotBlank() }
        }.orEmpty()
        return Artist(
            id = id,
            name = plainName(a.optString("name")),
            profile = profile(a.optString("profile")),
            members = members.distinct().take(MAX_MEMBERS),
            realName = a.optString("realname").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** The artist's own records (not appearances on others'), the earliest first, a few pages of them. */
    suspend fun records(id: Long, token: String): List<Record> {
        val out = ArrayList<Record>()
        for (page in 1..MAX_PAGES) {
            val o = get("$API/artists/$id/releases?sort=year&sort_order=asc&per_page=$PER_PAGE&page=$page", token) ?: break
            val list = o.optJSONArray("releases") ?: break
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("role") == "Main" }
                .forEach { r ->
                    out += Record(
                        title = r.optString("title"),
                        year = r.optInt("year").takeIf { it in FIRST_YEAR..LAST_YEAR }?.toString(),
                        label = r.optString("label").takeIf { it.isNotBlank() && it != "null" && !it.startsWith("Not On Label") },
                        master = r.optString("type") == "master",
                    )
                }
            if (page >= (o.optJSONObject("pagination")?.optInt("pages") ?: 1)) break
        }
        return out
    }

    /** By name: only with a token (Discogs' search needs one). */
    suspend fun search(name: String, token: String): Long? {
        if (token.isBlank()) return null
        val results = get("$API/database/search?type=artist&per_page=5&q=${URLEncoder.encode(name, "UTF-8")}", token)
            ?.optJSONArray("results") ?: return null
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .firstOrNull { same(plainName(it.optString("title")), name) }?.optLong("id")?.takeIf { it > 0 }
    }

    /** "https://www.discogs.com/artist/125246-Nirvana" → 125246. */
    fun idOf(url: String?): Long? = url?.let { Regex("discogs\\.com/(?:[a-z]{2}/)?artist/(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** Whether the token opens Discogs: who it belongs to. */
    fun check(token: String): KeyCheck =
        KeyCheck.ask("$API/oauth/identity", mapOf("Authorization" to "Discogs token=${token.trim()}")) { it.has("username") }

    /**
     * The biography without Discogs' markup: "[a=Kurt Cobain]" is the name, "[l=Sub Pop]" the label,
     * "[url=…]text[/url]" the text; links to items by number and formatting go.
     */
    internal fun profile(text: String): List<String> {
        if (text.isBlank() || text == "null") return emptyList()
        val plain = text
            .replace(Regex("\\[[almr]=([^\\]]+)]")) { plainName(it.groupValues[1]) }
            .replace(Regex("\\[url=[^\\]]*]([^\\[]*)\\[/url]"), "$1")
            .replace(Regex("\\[[almr]\\d+]"), "")
            .replace(Regex("\\[/?[a-z]+]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Regex("(?<=[.!?])\\s+(?=[A-ZА-ЯЁ\"«])").split(plain).map { it.trim() }
            .filter { it.length in MIN_SENTENCE..MAX_SENTENCE }.take(MAX_SENTENCES)
    }

    /** "Dave Grohl (2)": Discogs' number for one of several of that name goes. */
    internal fun plainName(name: String) = name.replace(Regex("\\s*\\(\\d+\\)$"), "").trim()

    private fun same(a: String, b: String): Boolean {
        fun n(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")
        return n(a).isNotEmpty() && n(a) == n(b)
    }

    // ---- Requests: spaced as Discogs allows ----

    private val gate = Mutex()
    private var last = 0L

    private suspend fun get(url: String, token: String): JSONObject? = gate.withLock {
        val wait = last + (if (token.isBlank()) SPACING_MS else SPACING_TOKEN_MS) - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            fetch(url, token)
        } finally {
            last = System.currentTimeMillis()
        }
    }

    private fun fetch(url: String, token: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            // Discogs refuses requests that do not say who sends them.
            conn.setRequestProperty("User-Agent", TrackInfo.USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Discogs token=${token.trim()}")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private const val API = "https://api.discogs.com"
    private const val TIMEOUT_MS = 10_000

    /** 25 requests a minute without a token, 60 with one. */
    private const val SPACING_MS = 2_500L
    private const val SPACING_TOKEN_MS = 1_100L
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 3
    private const val MAX_MEMBERS = 12
    private const val MAX_SENTENCES = 8
    private const val MIN_SENTENCE = 25
    private const val MAX_SENTENCE = 400
    private const val FIRST_YEAR = 1890
    private const val LAST_YEAR = 2100
}
