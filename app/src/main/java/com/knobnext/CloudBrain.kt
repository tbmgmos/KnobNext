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
    /** How its keys begin: a key that begins otherwise is likely the wrong one (or not all of it). */
    val keyPrefix: List<String>,
) {
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-flash-latest", "https://aistudio.google.com/apikey", true, listOf("AIza")),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto", "https://openrouter.ai/keys", false, listOf("sk-or-")),
    GROQ("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "https://console.groq.com/keys", false, listOf("gsk_")),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "https://platform.deepseek.com/api_keys", false, listOf("sk-")),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys", false, listOf("sk-")),
}

object CloudBrain {
    /** An answer and the pages it was found on (Gemini's search), for the card to name. */
    /** [by]: what answered ("Groq · openai/gpt-oss-120b"), this answer's own, whatever else was asked meanwhile. */
    class Answer(val text: String, val sources: List<Source>, val by: String? = null)

    class Source(val title: String, val url: String)

    /** Why the last request gave nothing, in a sentence the card can show. */
    @Volatile var lastError: String? = null

    /** The service's own words for it, for the log. */
    @Volatile var lastDetail: String? = null

    /** What answered last: "Gemini · gemini-2.5-flash · с поиском". */
    @Volatile var lastUsed: String? = null

    /** The service, key and model set in the settings, or null when there is no key. */
    fun configured(prefs: Prefs): Triple<CloudService, String, String>? {
        val service = order(prefs).firstOrNull() ?: return null
        return Triple(service, prefs.cloudKey(service), prefs.cloudModel(service).ifBlank { service.defaultModel })
    }

    /**
     * The services to ask, in turn: the chosen one, then (when switching is on) every other with a key,
     * in the order of the list, so that one down, out of quota or overloaded does not leave the card silent.
     */
    fun order(prefs: Prefs): List<CloudService> {
        // An experiment for development: nothing is asked outside developer mode.
        if (!prefs.devMode) return emptyList()
        val chosen = prefs.cloudService
        val all = listOf(chosen) + if (prefs.cloudFallback) CloudService.entries.filter { it != chosen } else emptyList()
        return all.filter { prefs.cloudKey(it).isNotBlank() }
    }

    /**
     * [prompt] to the cloud model; [system]: who it is and how to answer. Null when no service could answer.
     * The same question asked twice at once (the spoken story and the round card's, for one track) is asked
     * once: free plans count tokens by the minute, and a second request would only use them up.
     */
    fun ask(prefs: Prefs, system: String, prompt: String, maxTokens: Int = MAX_TOKENS): Answer? {
        val key = "${order(prefs).joinToString { it.name }}:${system.hashCode()}:${prompt.hashCode()}:${prompt.length}:$maxTokens"
        val lock = synchronized(asking) {
            if (asking.size > MAX_REMEMBERED) asking.clear()
            asking.getOrPut(key) { Any() }
        }
        synchronized(lock) {
            synchronized(answered) { answered[key] }?.takeIf { System.currentTimeMillis() - it.first < SAME_ANSWER_MS }?.let { return it.second }
            val answer = askNow(prefs, system, prompt, maxTokens) ?: return null
            synchronized(answered) {
                if (answered.size > MAX_REMEMBERED) answered.clear()
                answered[key] = System.currentTimeMillis() to answer
            }
            return answer
        }
    }

    private val asking = HashMap<String, Any>()
    private val answered = HashMap<String, Pair<Long, Answer>>()

    private fun askNow(prefs: Prefs, system: String, prompt: String, maxTokens: Int): Answer? {
        usedHere.set(null)
        lastCode = 0
        val services = order(prefs)
        if (services.isEmpty()) return null.also { lastError = "нет ключа" }
        val why = ArrayList<String>()
        for (service in services) {
            val key = prefs.cloudKey(service)
            val model = prefs.cloudModel(service).ifBlank { service.defaultModel }
            val answer = try {
                when {
                    service == CloudService.GEMINI -> gemini(prefs, key, model, system, prompt, maxTokens)
                    // No model named: one of OpenRouter's free ones, found and kept. A named one that is no
                    // chat model (an embedding model, a model gone) gives way to them too.
                    service == CloudService.OPENROUTER && prefs.cloudModel(service).isBlank() -> openRouterFree(prefs, key, system, prompt, maxTokens)
                    service == CloudService.OPENROUTER -> chat(service, key, model, system, prompt, maxTokens)
                        ?: if (lastCode == 400 || lastCode == 404) openRouterFree(prefs, key, system, prompt, maxTokens) else null
                    // Groq: with its search (Compound) when asked for, else the model that knows most of those it has.
                    service == CloudService.GROQ -> groq(prefs, key, system, prompt, maxTokens)
                    else -> chat(service, key, model, system, prompt, maxTokens)
                }
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
                lastDetail = e.message?.take(MAX_ERROR_CHARS)
                null
            }
            if (answer != null) {
                // For the settings: what answered last (the service it switched to, if it did).
                val by = usedHere.get()
                prefs.setCloudLast(by.orEmpty(), ok = true)
                return Answer(answer.text, answer.sources, by)
            }
            why += "${service.title}: ${lastError ?: "нет ответа"}"
        }
        lastError = why.joinToString("; ")
        prefs.setCloudLast(lastError.orEmpty(), ok = false)
        return null
    }

    private fun used(service: CloudService, model: String, search: Boolean?) {
        usedHere.set(listOfNotNull(
            service.title,
            model,
            when (search) {
                true -> "с поиском"
                false -> "без поиска"
                null -> null
            },
        ).joinToString(" · "))
        lastUsed = usedHere.get()
    }

    /** Whether the key opens the service: the list of its models, which costs nothing. */
    fun check(service: CloudService, key: String): KeyCheck {
        val k = key.trim()
        return if (service == CloudService.GEMINI) {
            KeyCheck.ask("${service.base}/models?pageSize=1", mapOf("x-goog-api-key" to k)) { it.has("models") }
        } else if (service == CloudService.OPENROUTER) {
            // OpenRouter's model list is open to anyone: only its key endpoint says whether the key is good.
            KeyCheck.ask("${service.base}/key", mapOf("Authorization" to "Bearer $k")) { it.has("data") }
        } else {
            KeyCheck.ask("${service.base}/models", mapOf("Authorization" to "Bearer $k")) { it.has("data") }
        }
    }

    // ---- Gemini ----

    /**
     * Gemini with its Google Search, and when the key's free quota does not cover that (a model or the
     * search can have none at all): the same model without the search, then the key's other Flash
     * models. What worked is kept, so the next question goes straight there.
     */
    private fun gemini(prefs: Prefs, key: String, model: String, system: String, prompt: String, maxTokens: Int): Answer? {
        val tries = LinkedHashSet<Pair<String, Boolean>>()
        prefs.cloudWorking(CloudService.GEMINI)?.let { tries += it }
        tries += model to true
        tries += model to false
        var reply: Reply? = null
        fun attempt(m: String, search: Boolean): Answer? {
            var r = geminiOnce(key, m, search, system, prompt, maxTokens)
            // A per-minute limit says when to come back: a short wait is worth it.
            if (r.code == HTTP_QUOTA && r.retryAfterS in 1..MAX_RETRY_WAIT_S) {
                Thread.sleep(r.retryAfterS * 1000L)
                r = geminiOnce(key, m, search, system, prompt, maxTokens)
            }
            // "The model is overloaded" (503) and the like pass in a moment: once more, then another model.
            if (r.code in BUSY) {
                Thread.sleep(BUSY_WAIT_MS)
                r = geminiOnce(key, m, search, system, prompt, maxTokens)
            }
            reply = r
            r.answer?.let {
                prefs.setCloudWorking(CloudService.GEMINI, m, search)
                used(CloudService.GEMINI, m, search)
            }
            return r.answer
        }
        for ((m, search) in tries) {
            attempt(m, search)?.let { return it }
            // A wrong key or no network: another model would not help.
            if ((reply?.code ?: 0) !in RETRYABLE) return fail(reply, model)
        }
        for (m in flashModels(key).filter { it != model }.take(MAX_OTHER_MODELS)) {
            for (search in listOf(true, false)) {
                attempt(m, search)?.let { return it }
                if ((reply?.code ?: 0) !in RETRYABLE) return fail(reply, model)
            }
        }
        return fail(reply, model)
    }

    private fun geminiOnce(key: String, model: String, search: Boolean, system: String, prompt: String, maxTokens: Int): Reply {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxOf(maxTokens, GEMINI_MIN_TOKENS)).put("temperature", TEMPERATURE))
        // It looks the track up in Google itself, and says where it found what it tells.
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val r = post("${CloudService.GEMINI.base}/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
        val json = r.json ?: return r
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return r.copy(said = json.toString().take(MAX_ERROR_CHARS))
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = (0 until (parts?.length() ?: 0)).mapNotNull { parts!!.optJSONObject(it) }
            // A thinking model's thoughts are not the answer.
            .filter { !it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }.trim()
        // A thinking model can spend the whole allowance thinking (finishReason MAX_TOKENS), or a filter
        // can stop it: tried another way, not given up on.
        if (text.isEmpty()) return r.copy(code = EMPTY, said = candidate.optString("finishReason").ifBlank { "без причины" })
        val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        val sources = (0 until (chunks?.length() ?: 0)).mapNotNull { chunks!!.optJSONObject(it)?.optJSONObject("web") }
            .mapNotNull { w -> w.optString("uri").takeIf { it.isNotBlank() }?.let { Source(w.optString("title").ifBlank { it }, it) } }
            .distinctBy { it.title }
        return r.copy(answer = Answer(text, sources))
    }

    /** The key's Flash models that can answer, the stable ones first. */
    private fun flashModels(key: String): List<String> {
        val r = get("${CloudService.GEMINI.base}/models?pageSize=200", mapOf("x-goog-api-key" to key))
        val list = r.json?.optJSONArray("models") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                (0 until (methods?.length() ?: 0)).any { methods!!.optString(it) == "generateContent" }
            }
            .map { it.optString("name").removePrefix("models/") }
            .filter { n -> "flash" in n && UNFIT.none { it in n } }
            .sortedWith(compareBy<String>({ "preview" in it || "exp" in it }, { "lite" in it }))
    }

    /** Why nothing came, said plainly: the card shows it, the log gets the service's own words. */
    private fun fail(r: Reply?, model: String): Answer? {
        val said = r?.said.orEmpty()
        lastCode = r?.code ?: 0
        lastDetail = r?.let { "HTTP ${it.code}: ${said.take(MAX_ERROR_CHARS)}" }
        // The service's own words after ours: what it really said is often the whole answer.
        val quoted = said.takeIf { it.isNotBlank() }?.let { " — «${it.take(MAX_QUOTE_CHARS)}»" }.orEmpty()
        lastError = when {
            r == null -> "нет ответа"
            r.code == 0 -> said.ifBlank { "нет связи" }
            said.contains("location is not supported", ignoreCase = true) -> "Gemini недоступен из вашей страны — нужен VPN"
            r.code == EMPTY -> "модель вернула пустой ответ (${said.take(MAX_REASON_CHARS)})"
            r.code in 200..299 -> "модель вернула пустой ответ"
            r.code in BUSY -> "сервис модели сейчас перегружен — попробуйте чуть позже"
            // DeepSeek has no free quota: "Insufficient Balance".
            r.code == HTTP_NO_MONEY -> "на счёте сервиса нет средств — пополните баланс (бесплатного лимита у него нет)"
            r.code == HTTP_QUOTA -> "у ключа закончился бесплатный лимит Gemini (или его нет для этих моделей). Попробуйте позже или включите оплату в Google AI Studio"
            r.code == 401 || r.code == 403 -> "ключ не принят$quoted"
            r.code == 404 -> "модель «$model» не найдена или не умеет отвечать текстом$quoted"
            r.code == 400 -> "сервис отклонил запрос к «$model»$quoted"
            else -> "ошибка ${r.code}$quoted"
        }
        return null
    }

    // ---- OpenAI's format ----

    private fun chat(service: CloudService, key: String, model: String, system: String, prompt: String, maxTokens: Int): Answer? {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", prompt)),
            )
            // Thinking models (gpt-oss, DeepSeek R1, o-series) spend part of it thinking: room for the answer too.
            // Groq counts what may be written against its tokens per minute (8000 for gpt-oss-120b, free): less there.
            .put("max_tokens", maxOf(maxTokens, if (service == CloudService.GROQ) GROQ_MIN_TOKENS else CHAT_MIN_TOKENS))
            .put("temperature", TEMPERATURE)
        // gpt-oss on Groq: brief thinking is enough for a few sentences, and quicker (the voice is waiting).
        if (service == CloudService.GROQ && "gpt-oss" in model) body.put("reasoning_effort", "low")
        val headers = mutableMapOf("Authorization" to "Bearer $key")
        // OpenRouter asks apps to say who they are.
        if (service == CloudService.OPENROUTER) {
            headers["HTTP-Referer"] = "https://github.com/tbmgmos/KnobNext"
            headers["X-Title"] = "KnobNext"
        }
        var r = post("${service.base}/chat/completions", headers, body)
        // Should the service no longer take the thinking setting, the same request without it.
        if (r.code == 400 && body.has("reasoning_effort") && "reasoning" in r.said.lowercase()) {
            body.remove("reasoning_effort")
            r = post("${service.base}/chat/completions", headers, body)
        }
        val message = r.json?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val text = message?.optString("content")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return fail(r, model).also {
            if (r.code == HTTP_QUOTA || r.code == HTTP_TOO_LARGE) lastError = limitSaid(service, r.code, r.said)
        }
        // Groq Compound tells what its search found: the pages, as Gemini's are.
        val sources = message?.optJSONArray("executed_tools")?.let { tools ->
            (0 until tools.length()).mapNotNull { tools.optJSONObject(it) }.flatMap { tool ->
                val found = tool.optJSONObject("search_results")?.optJSONArray("results") ?: tool.optJSONArray("search_results")
                (0 until (found?.length() ?: 0)).mapNotNull { found!!.optJSONObject(it) }
                    .mapNotNull { f -> f.optString("url").takeIf { it.startsWith("http") }?.let { Source(f.optString("title").ifBlank { it }, it) } }
            }
        }.orEmpty().distinctBy { it.url }
        used(service, model, if (model == GROQ_SEARCH_MODEL) true else null)
        return Answer(text, sources)
    }

    /** Which limit ran out, said plainly: by the minute it comes back by itself, by the day only tomorrow. */
    private fun limitSaid(service: CloudService, code: Int, said: String): String {
        val words = said.lowercase()
        val quoted = said.takeIf { it.isNotBlank() }?.let { " — «${it.take(MAX_QUOTE_CHARS)}»" }.orEmpty()
        return when {
            code == HTTP_TOO_LARGE -> "запрос больше поминутного лимита ${service.title}$quoted"
            "per minute" in words -> "поминутный лимит ${service.title} исчерпан — через минуту снова заработает$quoted"
            "per day" in words -> "дневной лимит ${service.title} исчерпан — заработает завтра$quoted"
            else -> "у ключа ${service.title} закончился лимит или нет оплаты$quoted"
        }
    }

    /**
     * OpenRouter with no model named: its free models (":free", priced at nothing), the one that answered
     * last first. One key is all it needs; a free model that is busy or gone gives way to the next.
     */
    private fun openRouterFree(prefs: Prefs, key: String, system: String, prompt: String, maxTokens: Int): Answer? {
        val service = CloudService.OPENROUTER
        val tries = LinkedHashSet<String>()
        prefs.cloudWorking(service)?.first?.let { tries += it }
        tries += freeModels(key).take(MAX_FREE_TRIED)
        if (tries.isEmpty()) {
            lastError = "у OpenRouter не нашлось бесплатных моделей — впишите модель в настройках"
            return null
        }
        for (m in tries) {
            chat(service, key, m, system, prompt, maxTokens)?.let {
                prefs.setCloudWorking(service, m, false)
                return it
            }
        }
        return null
    }

    /**
     * Groq: Compound (its models with a web search, paid per search) when switched on; else the model in
     * the settings; else the one that knows most of what it serves (gpt-oss-120b, then Kimi K2, Qwen 3,
     * Llama), the one that answered last first. Each model has its own limits by the minute: one out of
     * them, retired or refusing gives way to the next. Only a key refused stops it.
     */
    private fun groq(prefs: Prefs, key: String, system: String, prompt: String, maxTokens: Int): Answer? {
        val service = CloudService.GROQ
        if (prefs.groqSearch) chat(service, key, GROQ_SEARCH_MODEL, system, prompt, maxTokens)?.let { return it }
        val named = prefs.cloudModel(service)
        if (named.isNotBlank()) {
            chat(service, key, named, system, prompt, maxTokens)?.let { return it }
            if (lastCode in KEY_REFUSED) return null
        }
        val tries = LinkedHashSet<String>()
        prefs.cloudWorking(service)?.first?.takeIf { named.isBlank() }?.let { tries += it }
        tries += groqModels(key).take(MAX_FREE_TRIED)
        tries += service.defaultModel
        tries -= named
        // Kept, so that the card says why the first one failed rather than the last.
        var first: Pair<String?, String?>? = null
        for (m in tries) {
            chat(service, key, m, system, prompt, maxTokens)?.let {
                prefs.setCloudWorking(service, m, false)
                return it
            }
            if (first == null) first = lastError to lastDetail
            if (lastCode in KEY_REFUSED) break
        }
        first?.let { (e, d) -> lastError = e; lastDetail = d }
        return null
    }

    /** Groq's chat models, the ones that know the world best first (the list kept for an hour). */
    private fun groqModels(key: String): List<String> {
        groqList?.takeIf { it.first == key.hashCode() && System.currentTimeMillis() - it.second < MODELS_KEPT_MS }?.let { return it.third }
        val list = get("${CloudService.GROQ.base}/models", mapOf("Authorization" to "Bearer $key")).json?.optJSONArray("data") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id") }
            .filter { id -> NOT_CHAT.none { it in id.lowercase() } && GROQ_NOT_CHAT.none { it in id.lowercase() } }
            .filter { id -> GROQ_PREFERRED.any { it in id } }
            .sortedBy { id -> GROQ_PREFERRED.indexOfFirst { it in id } }
            .also { groqList = Triple(key.hashCode(), System.currentTimeMillis(), it) }
    }

    @Volatile private var groqList: Triple<Int, Long, List<String>>? = null

    /** OpenRouter's free text models, the better-known families first. */
    private fun freeModels(key: String): List<String> {
        // The whole list is large: fetched once an hour, not before every answer.
        freeList?.takeIf { System.currentTimeMillis() - it.first < MODELS_KEPT_MS }?.let { return it.second }
        return fetchFreeModels(key).also { if (it.isNotEmpty()) freeList = System.currentTimeMillis() to it }
    }

    @Volatile private var freeList: Pair<Long, List<String>>? = null

    private fun fetchFreeModels(key: String): List<String> {
        val list = get("${CloudService.OPENROUTER.base}/models", mapOf("Authorization" to "Bearer $key")).json?.optJSONArray("data") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { m ->
                val id = m.optString("id")
                val price = m.optJSONObject("pricing")
                val free = id.endsWith(":free") || (price?.optString("prompt") == "0" && price?.optString("completion") == "0")
                // A chat model, writing text: not embeddings, rerankers, speech or pictures.
                val out = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
                val writes = out == null || (0 until out.length()).any { out.optString(it) == "text" } &&
                    (0 until out.length()).none { out.optString(it) == "image" }
                free && writes && NOT_CHAT.none { it in id.lowercase() } && id != "openrouter/auto"
            }
            .map { it.optString("id") }
            .sortedBy { id -> FREE_PREFERRED.indexOfFirst { it in id }.let { if (it < 0) FREE_PREFERRED.size else it } }
    }

    // ---- Requests ----

    /** What a request brought: the code (0: no answer at all), the JSON, the service's words, when to retry. */
    private data class Reply(
        val code: Int,
        val json: JSONObject?,
        val said: String = "",
        val retryAfterS: Int = 0,
        val answer: Answer? = null,
    )

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Reply = request(url, headers, body)

    private fun get(url: String, headers: Map<String, String>): Reply = request(url, headers, null)

    private fun request(url: String, headers: Map<String, String>, body: JSONObject?): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.setRequestProperty("User-Agent", TrackInfo.USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299) return Reply(code, json)
            // The service's own words: "API key not valid", "model not found", "quota exceeded".
            val error = json?.optJSONObject("error")
            val said = (error?.optString("message") ?: json?.optString("message"))?.takeIf { it.isNotBlank() } ?: text.take(MAX_ERROR_CHARS)
            // Google says when a per-minute limit lets go: {"@type": "...RetryInfo", "retryDelay": "7s"}.
            val details = error?.optJSONArray("details")
            val retry = (0 until (details?.length() ?: 0)).mapNotNull { details!!.optJSONObject(it)?.optString("retryDelay") }
                .firstOrNull { it.isNotBlank() }?.removeSuffix("s")?.toDoubleOrNull()?.toInt() ?: 0
            // "limit: 0": none at all for this model, waiting would not help.
            return Reply(code, null, said, if (said.contains("limit: 0")) 0 else retry)
        } catch (e: SocketTimeoutException) {
            return Reply(0, null, "не ответил вовремя")
        } catch (e: UnknownHostException) {
            return Reply(0, null, "нет сети")
        } catch (e: SSLException) {
            return Reply(0, null, "ошибка защищённого соединения")
        } catch (e: IOException) {
            return Reply(0, null, e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private const val CONNECT_MS = 8_000
    // A search and an answer: Gemini takes a few seconds more than a plain answer.
    private const val READ_MS = 30_000
    private const val MAX_TOKENS = 1_024
    private const val TEMPERATURE = 0.6
    private const val MAX_ERROR_CHARS = 200
    private const val HTTP_QUOTA = 429
    private const val HTTP_NO_MONEY = 402
    private const val HTTP_TOO_LARGE = 413
    private val KEY_REFUSED = setOf(401, 403)

    /** An answer to the same question within this long is the same answer. */
    private const val SAME_ANSWER_MS = 60_000L
    private const val MAX_REMEMBERED = 50
    private const val MODELS_KEPT_MS = 60L * 60 * 1000

    /** Answers worth trying another way: no quota, no such model, a feature not offered (the search). */
    private val RETRYABLE = setOf(HTTP_QUOTA, HTTP_NO_MONEY, HTTP_TOO_LARGE, EMPTY, 404, 400, 500, 502, 503, 504)

    /** Answered, but with no text (all of it spent thinking, or stopped by a filter). */
    private const val EMPTY = -1
    private const val MAX_REASON_CHARS = 40

    /**
     * Gemini's newer models think before they answer, and the thinking counts against the allowance: a
     * small one left nothing for the answer. Only what is written is paid for; the prompt keeps it short.
     */
    private const val GEMINI_MIN_TOKENS = 4_096
    private const val CHAT_MIN_TOKENS = 2_048
    private const val GROQ_MIN_TOKENS = 1_536

    /** The service is busy or briefly down: worth one more try a little later. */
    private val BUSY = setOf(500, 502, 503, 504)
    private const val BUSY_WAIT_MS = 2_000L
    private const val MAX_RETRY_WAIT_S = 10
    private const val MAX_OTHER_MODELS = 3
    private const val MAX_FREE_TRIED = 4

    /** Groq's models with a web search (one search per answer: quick, the cheapest). */
    private const val GROQ_SEARCH_MODEL = "groq/compound-mini"
    // Kimi K2 and Llama 3.3 70B were retired from Groq in 2026; should they (or newer ones) return, they are taken.
    private val GROQ_PREFERRED = listOf("gpt-oss-120b", "kimi-k2", "qwen3", "llama-4-maverick", "llama-3.3-70b", "llama-4", "gpt-oss-20b")
    private val GROQ_NOT_CHAT = listOf("compound", "orpheus", "playai", "distil")
    private const val MAX_QUOTE_CHARS = 120

    /** Models by these names do not chat: embeddings, rerankers, speech, guards. */
    private val NOT_CHAT = listOf("embed", "rerank", "whisper", "tts", "guard", "moderation")

    /**
     * The last failed request's code, for the next step to decide on; and what answered. Per thread: a
     * question from the card may be asked while a story's request is still running, each on its own
     * thread from start to end (the requests block), and neither must steer the other.
     */
    private val codeHere = ThreadLocal<Int>()
    private val usedHere = ThreadLocal<String?>()
    private var lastCode: Int
        get() = codeHere.get() ?: 0
        set(v) = codeHere.set(v)

    /** Free models from these families are tried first: they know music and Russian best. */
    private val FREE_PREFERRED = listOf("deepseek", "gemini", "llama-3.3", "qwen", "mistral", "llama")

    /** Flash models that are not for text: pictures, speech, live audio, embeddings. */
    private val UNFIT = listOf("image", "tts", "audio", "live", "embedding", "native", "robotics", "computer-use")
}
