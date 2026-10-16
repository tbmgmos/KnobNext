package com.knobnext

/** The RuStore build has no network: no cloud model, and Anthropic's SDK is left out of it. */
object ClaudeApi {
    class Reply(val text: String?, val model: String?, val code: Int, val said: String, val sources: List<Pair<String, String>> = emptyList())

    const val REFUSED = -2

    @Suppress("UNUSED_PARAMETER")
    fun ask(key: String, base: String, model: String, system: String, prompt: String, maxTokens: Int, search: Boolean): Reply =
        Reply(null, null, 0, "нет в этой сборке")

    @Suppress("UNUSED_PARAMETER")
    fun check(key: String, base: String, model: String): KeyCheck = KeyCheck.NoAnswer(KeyCheck.Kind.OTHER, "нет в этой сборке")
}
