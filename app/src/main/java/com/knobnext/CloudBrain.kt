package com.knobnext

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A large model in the cloud, with the person's own key: Gemini, which looks things up in Google itself
 * and says where it found them, or any service that speaks OpenAI's format (OpenRouter, Groq, DeepSeek,
 * OpenAI). It knows far more about music than the model on the phone; what the open sources found is
 * given to it as well, so that it does not contradict them.
 */
enum class CloudService(
    val title: String,
    /** Where its API is. */
    val base: String,
    /** The model asked unless the person names another. */
    val defaultModel: String,
    /** Where a key is made. */
    val keyPage: String,
    /** It looks things up on the web itself (Gemini's Google Search). */
    val searches: Boolean,
) {
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-flash-latest", "https://aistudio.google.com/apikey", true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto", "https://openrouter.ai/keys", false),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "https://console.groq.com/keys", false),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "https://platform.deepseek.com/api_keys", false),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys", false),
}

object CloudBrain {
    /** An answer and the pages it was found on (Gemini's search), for the card to name. */
    class Answer(val text: String, val sources: List<Source>)

    class Source(val title: String, val url: String)

    /** Why the last request gave nothing, for the log. */
    @Volatile var lastError: String? = null

    /** The service, key and model set in the settings, or null when there is no key. */
    fun configured(prefs: Prefs): Triple<CloudService, String, String>? {
        val service = prefs.cloudService
        val key = prefs.cloudKey(service).takeIf { it.isNotBlank() } ?: return null
        return Triple(service, key, prefs.cloudModel(service).ifBlank { service.defaultModel })
    }

    /** [prompt] to the configured model; [system]: who it is and how to answer. Null when it could not answer. */
    fun ask(prefs: Prefs, system: String, prompt: String, maxTokens: Int = MAX_TOKENS): Answer? {
        val (service, key, model) = configured(prefs) ?: return null.also { lastError = "нет ключа" }
        return try {
            if (service == CloudService.GEMINI) gemini(service, key, model, system, prompt, maxTokens) else chat(service, key, model, system, prompt, maxTokens)
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(MAX_ERROR_CHARS) } ?: "")
            null
        }
    }

    /** Whether the key opens the service: the list of its models, which costs nothing. */
    fun check(service: CloudService, key: String): KeyCheck {
        val k = key.trim()
        return if (service == CloudService.GEMINI) {
            KeyCheck.ask("${service.base}/models?pageSize=1", mapOf("x-goog-api-key" to k)) { it.has("models") }
        } else {
            KeyCheck.ask("${service.base}/models", mapOf("Authorization" to "Bearer $k")) { it.has("data") }
        }
    }

    private fun gemini(service: CloudService, key: String, model: String, system: String, prompt: String, maxTokens: Int): Answer? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            // It looks the track up in Google itself, and says where it found what it tells.
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens).put("temperature", TEMPERATURE))
        val json = post("${service.base}/models/$model:generateContent", mapOf("x-goog-api-key" to key), body) ?: return null
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return null.also { lastError = json.toString().take(MAX_ERROR_CHARS) }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = (0 until (parts?.length() ?: 0)).mapNotNull { parts!!.optJSONObject(it) }
            // A thinking model's thoughts are not the answer.
            .filter { !it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }.trim()
        if (text.isEmpty()) return null.also { lastError = "пустой ответ: " + candidate.optString("finishReason") }
        val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        val sources = (0 until (chunks?.length() ?: 0)).mapNotNull { chunks!!.optJSONObject(it)?.optJSONObject("web") }
            .mapNotNull { w -> w.optString("uri").takeIf { it.isNotBlank() }?.let { Source(w.optString("title").ifBlank { it }, it) } }
            .distinctBy { it.title }
        return Answer(text, sources)
    }

    private fun chat(service: CloudService, key: String, model: String, system: String, prompt: String, maxTokens: Int): Answer? {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", prompt)),
            )
            .put("max_tokens", maxTokens)
            .put("temperature", TEMPERATURE)
        val headers = mutableMapOf("Authorization" to "Bearer $key")
        // OpenRouter asks apps to say who they are.
        if (service == CloudService.OPENROUTER) {
            headers["HTTP-Referer"] = "https://github.com/tbmgmos/KnobNext"
            headers["X-Title"] = "KnobNext"
        }
        val json = post("${service.base}/chat/completions", headers, body) ?: return null
        val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null.also { lastError = json.toString().take(MAX_ERROR_CHARS) }
        return Answer(text, emptyList())
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", TrackInfo.USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // The service's own words: "API key not valid", "model not found", "quota exceeded".
                val said = runCatching { JSONObject(text) }.getOrNull()?.let { j ->
                    j.optJSONObject("error")?.optString("message") ?: j.optString("message")
                }?.takeIf { it.isNotBlank() } ?: text.take(MAX_ERROR_CHARS)
                lastError = "HTTP $code: ${said.take(MAX_ERROR_CHARS)}"
                return null
            }
            lastError = null
            return JSONObject(text)
        } catch (e: SocketTimeoutException) {
            lastError = "не ответил вовремя"
            return null
        } catch (e: UnknownHostException) {
            lastError = "нет сети"
            return null
        } catch (e: SSLException) {
            lastError = "ошибка защищённого соединения"
            return null
        } catch (e: IOException) {
            lastError = e.javaClass.simpleName
            return null
        } finally {
            conn.disconnect()
        }
    }

    private const val CONNECT_MS = 8_000
    // A search and an answer: Gemini takes a few seconds more than a plain answer.
    private const val READ_MS = 30_000
    private const val MAX_TOKENS = 1_024
    private const val TEMPERATURE = 0.6
    private const val MAX_ERROR_CHARS = 160
}
