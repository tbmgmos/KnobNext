package com.knobnext

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Claude through Anthropic's own SDK: at Anthropic, or at [base] when the person named a server that
 * passes requests on to Claude. Blocking: called on a background thread, like the other services.
 */
object ClaudeApi {
    /** The text (null: none), the model that answered, the HTTP status (0: no answer at all), what was said. */
    class Reply(val text: String?, val model: String?, val code: Int, val said: String)

    /** Not an HTTP status: Claude declined to answer (its safeguards). */
    const val REFUSED = -2

    fun ask(key: String, base: String, model: String, system: String, prompt: String, maxTokens: Int): Reply {
        val official = base.isBlank()
        fun params(effort: Boolean) = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens.toLong())
            .system(system)
            .addUserMessage(prompt)
            .apply {
                // A few sentences: brief thinking is enough, and quicker (the voice is waiting).
                if (effort) outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                // Declined by Claude's safeguards: Anthropic answers with the model its category calls for
                // instead. Only at Anthropic itself: a server in between may not know the parameter.
                if (official && FALLBACK_MODELS.any { model.startsWith(it) }) {
                    addBeta(FALLBACK_BETA)
                    fallbacksDefault()
                }
            }
            .build()
        return try {
            val m = try {
                client(key, base).beta().messages().create(params(effort = true))
            } catch (e: AnthropicServiceException) {
                // An older model, or a server in between, that does not take the effort: once more without it.
                if (e.statusCode() == 400 && "effort" in e.message.orEmpty().lowercase()) {
                    client(key, base).beta().messages().create(params(effort = false))
                } else {
                    throw e
                }
            }
            if (m.stopReason().orElse(null) == BetaStopReason.REFUSAL) return Reply(null, null, REFUSED, "")
            val text = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            Reply(text.ifBlank { null }, m.model().toString(), 200, "")
        } catch (e: AnthropicServiceException) {
            Reply(null, null, e.statusCode(), e.message.orEmpty())
        } catch (e: Exception) {
            Reply(null, null, 0, listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": "))
        }
    }

    /**
     * Whether the key opens Claude: Anthropic's list of models (costs nothing). A server in between may
     * not have that list: then the shortest of questions, a few tokens.
     */
    fun check(key: String, base: String, model: String): KeyCheck = try {
        try {
            client(key, base).models().list()
        } catch (e: AnthropicServiceException) {
            if (base.isBlank() || e.statusCode() != 404) throw e
            client(key, base).beta().messages().create(
                MessageCreateParams.builder().model(model).maxTokens(CHECK_TOKENS).addUserMessage("ok").build(),
            )
        }
        KeyCheck.Works
    } catch (e: AnthropicServiceException) {
        KeyCheck.Rejected(e.statusCode(), null, e.message, fromApi = true)
    } catch (e: Exception) {
        KeyCheck.NoAnswer(KeyCheck.Kind.OTHER, listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": "))
    }

    /** One client per key and server: it keeps its connections for the next question. */
    private fun client(key: String, base: String): AnthropicClient = synchronized(this) {
        val id = "$key\u0000$base"
        current?.takeIf { it.first == id }?.second ?: AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { if (base.isNotBlank()) baseUrl(base.trim().trimEnd('/')) }
            .timeout(Duration.ofSeconds(TIMEOUT_S))
            .maxRetries(1)
            .build()
            .also { current = id to it }
    }

    private var current: Pair<String, AnthropicClient>? = null

    /** A story waits a few seconds and takes a late answer next time; a request is not kept open longer. */
    private const val TIMEOUT_S = 60L
    private const val CHECK_TOKENS = 16L
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private val FALLBACK_MODELS = listOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")
}
