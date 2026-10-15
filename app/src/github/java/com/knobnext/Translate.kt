package com.knobnext

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
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

    /** [sentences] from [from] into [to], or null when it cannot be done now (no model yet, an error). */
    suspend fun sentences(sentences: List<String>, from: String, to: String): List<String>? {
        if (from == to) return sentences
        val source = TranslateLanguage.fromLanguageTag(from) ?: return null
        val target = TranslateLanguage.fromLanguageTag(to) ?: return null
        // Not available at all (no Google Play services, a test): left as it is.
        val translator = runCatching {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }.getOrNull() ?: return null
        return try {
            // The model: there already, or fetched now on Wi-Fi; not waited for long (the next story has it).
            val ready = withTimeoutOrNull(MODEL_WAIT_MS) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).outcome().isSuccess
            } == true
            if (!ready) return null
            withTimeoutOrNull(TRANSLATE_WAIT_MS) {
                sentences.map { s ->
                    val key = "$from>$to:$s"
                    done[key] ?: translator.translate(s).outcome().getOrNull()?.also { done[key] = it } ?: s
                }
            }
        } catch (_: Exception) {
            null
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

    private const val MODEL_WAIT_MS = 3_000L
    private const val TRANSLATE_WAIT_MS = 6_000L
    private const val MAX_KEPT = 1_000
}
