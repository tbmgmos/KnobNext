package com.knobnext

/**
 * The RuStore build has no network: no translation model can be downloaded, and the translation library
 * (some 28 MB) is left out of it. Facts in another language stay as they are.
 */
object Translate {
    fun known(sentences: List<String>, from: String, to: String): List<String>? = if (from == to) sentences else null

    @Suppress("UNUSED_PARAMETER")
    fun later(sentences: List<String>, from: String, to: String) = Unit
}
