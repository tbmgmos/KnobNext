package com.knobnext

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Claude through Anthropic's own SDK: at Anthropic, or at [base] when the person named a server that
 * passes requests on to Claude. Blocking: called on a background thread, like the other services.
 */
object ClaudeApi {
    /**
     * The text (null: none), the model that answered, the HTTP status (0: no answer at all), what was
     * said, and the pages its web search found (title, address).
     */
    class Reply(val text: String?, val model: String?, val code: Int, val said: String, val sources: List<Pair<String, String>> = emptyList())

    /** Not an HTTP status: Claude declined to answer (its safeguards). */
    const val REFUSED = -2

    /** [search]: Claude may look things up on the web (a few searches, paid apart from the tokens). */
    fun ask(key: String, base: String, model: String, system: String, prompt: String, maxTokens: Int, search: Boolean): Reply {
        val official = base.isBlank()
        fun params(effort: Boolean, search: Boolean, before: List<BetaMessage>) = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens.toLong())
            .system(system)
            .addUserMessage(prompt)
            .apply {
                // A long search pauses the turn: what was done so far goes back, and Claude goes on from there.
                before.forEach { addMessage(it) }
                // A few sentences: brief thinking is enough, and quicker (the voice is waiting).
                if (effort) outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                if (search) {
                    // The newer search (it filters what it finds) on the models that have it.
                    if (NEW_SEARCH_MODELS.any { model.startsWith(it) }) {
                        addTool(BetaWebSearchTool20260209.builder().maxUses(MAX_SEARCHES).build())
                    } else {
                        addTool(BetaWebSearchTool20250305.builder().maxUses(MAX_SEARCHES).build())
                    }
                }
                // Declined by Claude's safeguards: Anthropic answers with the model its category calls for
                // instead. Only at Anthropic itself: a server in between may not know the parameter.
                if (official && FALLBACK_MODELS.any { model.startsWith(it) }) {
                    addBeta(FALLBACK_BETA)
                    fallbacksDefault()
                }
            }
            .build()
        return try {
            var effort = true
            var searching = search
            val turns = ArrayList<BetaMessage>()
            var m: BetaMessage
            while (true) {
                m = try {
                    client(key, base).beta().messages().create(params(effort, searching, turns))
                } catch (e: AnthropicServiceException) {
                    val said = e.message.orEmpty().lowercase()
                    // An older model, or a server in between, that does not take the effort or the search:
                    // once more without what it did not take.
                    when {
                        e.statusCode() != 400 || turns.isNotEmpty() -> throw e
                        effort && "effort" in said -> { effort = false; continue }
                        searching && ("web_search" in said || "tool" in said) -> { searching = false; continue }
                        else -> throw e
                    }
                }
                if (m.stopReason().orElse(null) != BetaStopReason.PAUSE_TURN || turns.size >= MAX_PAUSES) break
                turns += m
            }
            if (m.stopReason().orElse(null) == BetaStopReason.REFUSAL) return Reply(null, null, REFUSED, "")
            val all = turns + m
            val text = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            // The pages the search found: the card names them.
            val sources = all.flatMap { it.content() }.mapNotNull { it.webSearchToolResult().orElse(null) }
                .flatMap { it.content().resultBlocks().orElse(emptyList()) }
                .map { it.title() to it.url() }.distinctBy { it.second }.take(MAX_SOURCES)
            Reply(text.ifBlank { null }, m.model().toString(), 200, "", sources)
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
    private const val TIMEOUT_S = 45L
    private const val CHECK_TOKENS = 16L

    /** A few searches are enough for a few sentences, and each one is paid for and waited for. */
    private const val MAX_SEARCHES = 3L
    private const val MAX_PAUSES = 2
    private const val MAX_SOURCES = 5
    private val NEW_SEARCH_MODELS = listOf("claude-opus-5", "claude-sonnet-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6")
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private val FALLBACK_MODELS = listOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")
}
