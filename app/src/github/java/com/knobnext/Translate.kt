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

    /** Where the translations are kept between runs of the app: a biography is translated once. */
    @Volatile private var store: java.io.File? = null

    fun attach(dir: java.io.File) {
        if (store != null) return
        val f = java.io.File(dir, "translations.json")
        store = f
        runCatching {
            if (!f.exists()) return
            val o = org.json.JSONObject(f.readText())
            o.keys().forEach { k -> done.putIfAbsent(k, o.optString(k)) }
        }
    }

    @Synchronized private fun save() {
        val f = store ?: return
        runCatching {
            val o = org.json.JSONObject()
            synchronized(done) { done.forEach { (k, v) -> o.put(k, v) } }
            f.writeText(o.toString())
        }
    }

    /** [sentences] already translated from [from] into [to], or null. Never waits. */
    fun known(sentences: List<String>, from: String, to: String): List<String>? {
        if (from == to) return sentences
        return sentences.map { done["$from>$to:$it"] ?: return null }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Language pairs whose model is on the phone: translating with it takes a moment, not a download. */
    private val ready = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * [sentences] translated in the background, for [known] to give next time: a story never waits for
     * a download of the model (it used to, seconds on every telling, without Wi-Fi to the end of the wait).
     * Without the language pair's model, it is fetched on Wi-Fi, once.
     */
    fun later(sentences: List<String>, from: String, to: String): kotlinx.coroutines.Job? {
        if (from == to || known(sentences, from, to) != null) return null
        val key = "$from>$to:${sentences.hashCode()}"
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            try {
                translate(sentences, from, to)
            } finally {
                jobs.remove(key)
            }
        }.also { jobs[key] = it }
    }

    /**
     * [sentences] in [to] if the model is already on the phone and translates them within [ms]
     * (a moment); otherwise null at once, and they are translated in the background for next time.
     */
    suspend fun soon(sentences: List<String>, from: String, to: String, ms: Long): List<String>? {
        known(sentences, from, to)?.let { return it }
        val job = later(sentences, from, to) ?: return known(sentences, from, to)
        if (ms <= 0 || "$from>$to" !in ready) return null
        withTimeoutOrNull(ms) { job.join() }
        return known(sentences, from, to)
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
            this.ready += "$from>$to"
            var added = false
            for (s in sentences) {
                val key = "$from>$to:$s"
                if (done[key] == null) translator.translate(s).outcome().getOrNull()?.let { done[key] = it; added = true }
            }
            if (added) save()
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
