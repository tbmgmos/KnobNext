package com.knobnext

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * What phones without Gemini Nano or an offline recognizer can download (GitHub build only):
 *  - a small language model (Qwen2.5 1.5B Instruct for MediaPipe), the same role as Gemini Nano;
 *  - a Vosk speech model (~45 MB) for recognizing commands without the internet.
 * Both go through the system's download manager into the app's own storage and can be deleted.
 */
object Offline {
    const val supported = true

    private const val LLM_REPO = "litert-community/Qwen2.5-1.5B-Instruct"
    private const val LLM_DIR = "llm"
    private const val VOSK_DIR = "vosk"
    private const val VOSK_RU = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val VOSK_EN = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val ENGINE_IDLE_MS = 3 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var unpacking = false
    @Volatile private var lastError: String? = null

    private fun sp(context: Context) = context.getSharedPreferences("offline", Context.MODE_PRIVATE)
    private fun dir(context: Context, name: String) = File(context.getExternalFilesDir(null) ?: context.filesDir, name)

    // ---- Language model ----

    fun llmFile(context: Context): File? =
        dir(context, LLM_DIR).listFiles()?.firstOrNull { it.name.endsWith(".task") && sp(context).getLong("llm_id", -1L) == -1L }

    fun llm(context: Context): OfflineFile {
        llmFile(context)?.let { return OfflineFile(OfflineFile.State.READY, totalMb = (it.length() shr 20).toInt()) }
        return downloadState(context, "llm_id") ?: lastError?.let { OfflineFile(OfflineFile.State.FAILED, error = it) }
            ?: OfflineFile(OfflineFile.State.MISSING)
    }

    /** Finds the smallest MediaPipe bundle of the model on Hugging Face and downloads it (Wi-Fi only). */
    suspend fun downloadLlm(context: Context) {
        lastError = null
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL("https://huggingface.co/api/models/$LLM_REPO?blobs=true").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                try {
                    val siblings: JSONArray = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("siblings")
                    (0 until siblings.length()).map { siblings.getJSONObject(it) }
                        .filter { it.getString("rfilename").endsWith(".task") }
                        .minByOrNull { it.optLong("size", Long.MAX_VALUE) }
                        ?.getString("rfilename")
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
        if (file == null) {
            lastError = context.getString(R.string.offline_err_list)
            return
        }
        dir(context, LLM_DIR).deleteRecursively()
        enqueue(context, "llm_id", "https://huggingface.co/$LLM_REPO/resolve/main/$file", LLM_DIR, file, wifiOnly = true)
    }

    fun deleteLlm(context: Context) {
        cancel(context, "llm_id")
        closeEngine()
        dir(context, LLM_DIR).deleteRecursively()
        lastError = null
    }

    private var engine: LlmInference? = null
    private val closeIdle = Runnable { closeEngine() }

    private fun closeEngine() {
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }

    /** The downloaded model as a [Brain]; it stays loaded for a few minutes after each use. */
    fun brain(context: Context): Brain? {
        llm(context)
        val file = llmFile(context) ?: return null
        return object : Brain {
            override val name = context.getString(R.string.offline_llm_name)

            override suspend fun complete(prompt: String, maxTokens: Int): String? = withContext(Dispatchers.Default) {
                main.removeCallbacks(closeIdle)
                try {
                    val llm = synchronized(this@Offline) {
                        engine ?: LlmInference.createFromOptions(
                            context.applicationContext,
                            LlmInference.LlmInferenceOptions.builder()
                                .setModelPath(file.path)
                                .setMaxTokens(1024)
                                .build(),
                        ).also { engine = it }
                    }
                    val session = LlmInferenceSession.createFromOptions(
                        llm,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build(),
                    )
                    try {
                        session.addQueryChunk(prompt)
                        session.generateResponse()
                    } finally {
                        session.close()
                    }
                } catch (e: Throwable) {
                    lastError = e.message ?: e.javaClass.simpleName
                    null
                } finally {
                    main.postDelayed(closeIdle, ENGINE_IDLE_MS)
                }
            }
        }
    }

    // ---- Vosk ----

    private fun voskModelDir(context: Context): File? =
        dir(context, VOSK_DIR).listFiles()?.firstOrNull { File(it, "am").exists() || File(it, "conf").exists() }

    fun vosk(context: Context): OfflineFile {
        if (unpacking) return OfflineFile(OfflineFile.State.UNPACKING)
        voskModelDir(context)?.let { return OfflineFile(OfflineFile.State.READY) }
        val state = downloadState(context, "vosk_id")
        if (state?.state == OfflineFile.State.READY) {
            unpack(context)
            return OfflineFile(OfflineFile.State.UNPACKING)
        }
        return state ?: OfflineFile(OfflineFile.State.MISSING)
    }

    fun downloadVosk(context: Context) {
        dir(context, VOSK_DIR).deleteRecursively()
        val url = if (Locale.getDefault().language == "ru") VOSK_RU else VOSK_EN
        enqueue(context, "vosk_id", url, VOSK_DIR, "model.zip", wifiOnly = false)
    }

    fun deleteVosk(context: Context) {
        cancel(context, "vosk_id")
        dir(context, VOSK_DIR).deleteRecursively()
    }

    private fun unpack(context: Context) {
        if (unpacking) return
        unpacking = true
        Thread {
            val folder = dir(context, VOSK_DIR)
            val zip = File(folder, "model.zip")
            try {
                ZipInputStream(zip.inputStream().buffered()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val out = File(folder, entry.name).canonicalFile
                        if (!out.path.startsWith(folder.canonicalPath + File.separator)) continue
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                zip.delete()
                sp(context).edit().remove("vosk_id").apply()
            } catch (_: Exception) {
                folder.deleteRecursively()
                sp(context).edit().remove("vosk_id").apply()
            } finally {
                unpacking = false
            }
        }.start()
    }

    /** One phrase through Vosk, when its model is downloaded. */
    fun speech(context: Context): OfflineSpeech? {
        if (vosk(context).state != OfflineFile.State.READY) return null
        val modelDir = voskModelDir(context) ?: return null
        return object : OfflineSpeech {
            private var service: SpeechService? = null
            private var done: ((VoiceListener.Result) -> Unit)? = null

            override fun listen(onResult: (VoiceListener.Result) -> Unit) {
                cancel()
                done = onResult
                try {
                    val model = voskModel(modelDir)
                    val s = SpeechService(Recognizer(model, SAMPLE_RATE), SAMPLE_RATE)
                    service = s
                    s.startListening(
                        object : RecognitionListener {
                            override fun onResult(hypothesis: String?) = heard(hypothesis)
                            override fun onFinalResult(hypothesis: String?) = heard(hypothesis)
                            override fun onPartialResult(hypothesis: String?) = Unit
                            override fun onError(exception: Exception?) =
                                finish(VoiceListener.Result.Failed(context.getString(R.string.voice_err_audio)))
                            override fun onTimeout() =
                                finish(VoiceListener.Result.Failed(context.getString(R.string.voice_err_timeout)))
                        },
                        LISTEN_TIMEOUT_MS,
                    )
                } catch (e: Exception) {
                    finish(VoiceListener.Result.Failed(context.getString(R.string.voice_err_unavailable)))
                }
            }

            private fun heard(json: String?) {
                val text = runCatching { JSONObject(json ?: "").optString("text") }.getOrNull().orEmpty().trim()
                if (text.isNotEmpty()) finish(VoiceListener.Result.Heard(listOf(text)))
            }

            private fun finish(result: VoiceListener.Result) {
                val callback = done ?: return
                cancel()
                main.post { callback(result) }
            }

            override fun cancel() {
                done = null
                service?.let {
                    it.stop()
                    it.shutdown()
                }
                service = null
            }
        }
    }

    private var loadedVosk: Pair<File, Model>? = null

    private fun voskModel(dir: File): Model = synchronized(this) {
        loadedVosk?.takeIf { it.first == dir }?.second ?: Model(dir.path).also { loadedVosk = dir to it }
    }

    private const val SAMPLE_RATE = 16_000f
    private const val LISTEN_TIMEOUT_MS = 8_000

    // ---- Downloads ----

    private fun enqueue(context: Context, key: String, url: String, folder: String, name: String, wifiOnly: Boolean) {
        val dm = context.getSystemService(DownloadManager::class.java)
        cancel(context, key)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(context.getString(R.string.offline_download_title))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "$folder/$name")
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        sp(context).edit().putLong(key, dm.enqueue(request)).apply()
    }

    private fun cancel(context: Context, key: String) {
        val id = sp(context).getLong(key, -1L)
        if (id != -1L) context.getSystemService(DownloadManager::class.java).remove(id)
        sp(context).edit().remove(key).apply()
    }

    /** The state of the download under [key], or null when there is none. READY = downloaded, not yet used. */
    private fun downloadState(context: Context, key: String): OfflineFile? {
        val id = sp(context).getLong(key, -1L)
        if (id == -1L) return null
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) {
                sp(context).edit().remove(key).apply()
                return null
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // The language model is used as it is: the download is over.
                    if (key == "llm_id") sp(context).edit().remove(key).apply()
                    OfflineFile(OfflineFile.State.READY)
                }
                DownloadManager.STATUS_FAILED -> {
                    sp(context).edit().remove(key).apply()
                    OfflineFile(OfflineFile.State.FAILED, error = context.getString(R.string.offline_err_download))
                }
                else -> OfflineFile(OfflineFile.State.DOWNLOADING, (done shr 20).toInt(), (total.coerceAtLeast(0) shr 20).toInt())
            }
        }
        return null
    }
}
