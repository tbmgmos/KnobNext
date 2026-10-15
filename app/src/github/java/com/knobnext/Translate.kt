package com.knobnext

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Facts found only in another language (Last.fm's and Discogs' biographies, an English article),
 * translated on the phone: the story and the card are in one language, with a model or without. The
 * language pair's model (some 30 MB) is downloaded once, on Wi-Fi; until then the facts stay as they are.
 */
object Translate {
    private val done = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_KEPT
    })

    /** [sentences] already translated from [from] into [to] (this run of the app), or null. Never waits. */
    fun known(sentences: List<String>, from: String, to: String): List<String>? {
        if (from == to) return sentences
        return sentences.map { done["$from>$to:$it"] ?: return null }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * [sentences] translated in the background, for [known] to give next time: a story never waits for
     * the translation (it used to wait for the model on every telling, seconds each, without Wi-Fi forever).
     * Without the language pair's model, it is fetched on Wi-Fi, once.
     */
    fun later(sentences: List<String>, from: String, to: String) {
        if (from == to || known(sentences, from, to) != null) return
        val job = "$from>$to:${sentences.hashCode()}"
        if (!asked.add(job)) return
        scope.launch {
            try {
                translate(sentences, from, to)
            } finally {
                asked.remove(job)
            }
        }
    }

    private suspend fun translate(sentences: List<String>, from: String, to: String) {
        val source = TranslateLanguage.fromLanguageTag(from) ?: return
        val target = TranslateLanguage.fromLanguageTag(to) ?: return
        // Not available at all (no Google Play services, a test): left as it is.
        val translator = runCatching {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }.getOrNull() ?: return
        try {
            // The model: there already, or fetched on Wi-Fi (waited for here, in the background only).
            val ready = withTimeoutOrNull(MODEL_WAIT_MS) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).outcome().isSuccess
            } == true
            if (!ready) return
            for (s in sentences) {
                val key = "$from>$to:$s"
                if (done[key] == null) translator.translate(s).outcome().getOrNull()?.let { done[key] = it }
            }
        } catch (_: Exception) {
        } finally {
            translator.close()
        }
    }

    /** How a Play services task ended: its result (null for a task with none), or why it failed. */
    private suspend fun <T> Task<T>.outcome(): Result<T?> = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(Result.success(it)) }
        addOnFailureListener { if (cont.isActive) cont.resume(Result.failure(it)) }
        addOnCanceledListener { if (cont.isActive) cont.resume(Result.failure(IllegalStateException("cancelled"))) }
    }

    /** A model being downloaded on Wi-Fi is waited for this long in the background. */
    private const val MODEL_WAIT_MS = 120_000L
    private const val MAX_KEPT = 1_000
}
