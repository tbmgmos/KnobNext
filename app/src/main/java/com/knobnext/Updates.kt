package com.knobnext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val pageUrl: String, val apkUrl: String?)

object Updates {
    private const val LATEST = "https://api.github.com/repos/tbmgmos/KnobNext/releases/latest"

    /** Latest published release, or null when offline / no releases / repository not public. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0))
                    .map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
                Release(
                    version = json.getString("tag_name").removePrefix("v"),
                    pageUrl = json.getString("html_url"),
                    apkUrl = apk,
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Semver-ish: numeric parts compared in order; "1.2.0-dev.x" is older than "1.2.0". */
    fun isNewer(remote: String, local: String): Boolean {
        fun parse(v: String): Pair<List<Int>, Boolean> {
            val parts = v.split('-', limit = 2)
            return parts[0].split('.').map { it.toIntOrNull() ?: 0 } to (parts.size > 1)
        }
        val (r, rPre) = parse(remote)
        val (l, lPre) = parse(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return lPre && !rPre
    }
}
