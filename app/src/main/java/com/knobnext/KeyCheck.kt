package com.knobnext

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What a service said to a key typed into the settings. "The key is wrong" and "the service could not be
 * reached" need different answers, so they are kept apart (the first version showed one message for both).
 */
sealed interface KeyCheck {
    object Works : KeyCheck

    /** The service answered and did not take the key: its HTTP status and, when it gave one, its own error code. */
    class Rejected(val http: Int, val error: Int?) : KeyCheck

    /** No answer at all: [kind] says what went wrong, [detail] names the exception. */
    class NoAnswer(val kind: Kind, val detail: String) : KeyCheck

    enum class Kind { TIMEOUT, NO_HOST, TLS, OTHER }

    companion object {
        /** Asks [url] (with [headers]); [accepts] says whether the answer shows the key works. */
        fun ask(url: String, headers: Map<String, String> = emptyMap(), accepts: (JSONObject) -> Boolean): KeyCheck =
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.setRequestProperty("User-Agent", "KnobNext (https://github.com/tbmgmos/KnobNext)")
                    conn.setRequestProperty("Accept", "application/json")
                    headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                    val http = conn.responseCode
                    // A refusal comes with a body of its own (Last.fm: {"error":10,"message":"Invalid API key"}).
                    val body = (if (http in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (http == HttpURLConnection.HTTP_OK && json != null && accepts(json)) {
                        Works
                    } else {
                        Rejected(http, json?.optInt("error")?.takeIf { it != 0 })
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: SocketTimeoutException) {
                NoAnswer(Kind.TIMEOUT, e.javaClass.simpleName)
            } catch (e: UnknownHostException) {
                NoAnswer(Kind.NO_HOST, e.javaClass.simpleName)
            } catch (e: SSLException) {
                NoAnswer(Kind.TLS, e.javaClass.simpleName)
            } catch (e: IOException) {
                NoAnswer(Kind.OTHER, e.javaClass.simpleName + (e.message?.let { ": " + it.take(MAX_DETAIL_CHARS) } ?: ""))
            } catch (e: Exception) {
                NoAnswer(Kind.OTHER, e.javaClass.simpleName)
            }

        /** Last.fm's keys are 32 hexadecimal characters. */
        val LASTFM_KEY = Regex("[0-9a-fA-F]{32}")

        private const val TIMEOUT_MS = 8_000
        private const val MAX_DETAIL_CHARS = 60
    }
}
