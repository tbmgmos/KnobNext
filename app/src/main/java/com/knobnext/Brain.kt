package com.knobnext

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** A small language model on the phone, used for what plain rules can't do. */
interface Brain {
    val name: String

    /**
     * The model's answer to [prompt], or null when it failed or took too long. [creative]: some
     * randomness, for stories that should differ each time; otherwise the single likeliest answer.
     */
    suspend fun complete(prompt: String, maxTokens: Int, creative: Boolean = false): String?
}

/** Gemini Nano through ML Kit: built into the system on phones that have it, nothing to download here. */
class NanoBrain private constructor(private val model: GenerativeModel) : Brain {
    override val name = "Gemini Nano"

    override suspend fun complete(prompt: String, maxTokens: Int, creative: Boolean): String? = try {
        withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = if (creative) CREATIVE_TEMPERATURE else 0f
                topK = if (creative) CREATIVE_TOP_K else 1
                maxOutputTokens = maxTokens
            }
            model.generateContent(request).candidates.firstOrNull()?.text
        }
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        null
    }

    enum class State { READY, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    companion object {
        private const val ANSWER_TIMEOUT_MS = 8_000L
        const val CREATIVE_TEMPERATURE = 0.8f
        const val CREATIVE_TOP_K = 40

        /** The last failure, for the event log (e.g. the system refusing use in the background). */
        @Volatile var lastError: String? = null

        /** Why the last check found it unavailable, as the system put it; null when it gave no reason. */
        @Volatile var unavailableReason: String? = null

        private const val AICORE = "com.google.android.aicore"

        private var clientError: String? = null
        private val client: GenerativeModel? by lazy {
            try {
                Generation.getClient()
            } catch (e: Throwable) {
                clientError = describe(e)
                null
            }
        }

        private fun describe(e: Throwable): String {
            // GenAiException carries a numeric code the message doesn't always repeat.
            val code = runCatching { e.javaClass.getMethod("getErrorCode").invoke(e) }.getOrNull()
            // Where it happened, for failures without a message (a NullPointerException says nothing).
            val where = e.stackTrace.firstOrNull()?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            val cause = e.cause?.let { it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") }
            return listOfNotNull(
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
                code?.let { "код $it" },
                where?.let { "в $it" },
                cause?.let { "причина: $it" },
            ).joinToString(", ")
        }

        suspend fun state(): State {
            unavailableReason = null
            val model = client ?: return State.UNAVAILABLE.also { unavailableReason = clientError }
            return try {
                when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> State.READY
                    FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
                    FeatureStatus.DOWNLOADING -> State.DOWNLOADING
                    else -> State.UNAVAILABLE
                }
            } catch (e: Throwable) {
                unavailableReason = describe(e)
                State.UNAVAILABLE
            }
        }

        /** The system's AICore service (it holds Gemini Nano): its version, or null when it isn't installed or is off. */
        fun aicoreVersion(context: Context): String? = try {
            val info = context.packageManager.getPackageInfo(AICORE, 0)
            if (info.applicationInfo?.enabled == false) null else info.versionName ?: "?"
        } catch (_: Exception) {
            null
        }

        fun openAicoreInStore(context: Context) {
            val tries = listOf(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$AICORE")),
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$AICORE")),
            )
            for (intent in tries) {
                try {
                    context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (_: Exception) {
                }
            }
        }

        /** Asks the system to fetch the model; emits the download's progress as "started", "done" etc. */
        fun download(): Flow<String>? = client?.download()?.map { status ->
            when (status) {
                is DownloadStatus.DownloadCompleted -> "done"
                is DownloadStatus.DownloadFailed -> "failed"
                else -> "progress"
            }
        }

        suspend fun ready(): NanoBrain? = client?.takeIf { state() == State.READY }?.let(::NanoBrain)
    }
}

object Brains {
    /** The models this phone has right now, best first; empty: only the rules work. */
    suspend fun all(context: Context): List<Brain> = listOfNotNull(NanoBrain.ready(), Offline.brain(context))

    suspend fun best(context: Context): Brain? = all(context).firstOrNull()

    /**
     * Asks the models in turn until one answers: Gemini Nano may refuse (the system can block its use
     * from the background), and then the downloaded model still can.
     */
    suspend fun ask(context: Context, prompt: String, maxTokens: Int, creative: Boolean = false): Pair<Brain, String>? {
        for (brain in all(context)) {
            brain.complete(prompt, maxTokens, creative)?.let { return brain to it }
        }
        return null
    }

    /** The models that work with the app in the background: the system keeps Gemini Nano to the app on screen. */
    fun background(context: Context): Brain? = Offline.brain(context)
}

/**
 * Free-form phrases → [Command] through a model: it answers with one line of a tiny command language
 * that [parse] reads back, so a wrong answer can't do anything but nothing.
 */
object CommandBrain {
    fun prompt(phrase: String, apps: Collection<String>): String = """
        You control a music player by voice. Turn the user's phrase into exactly one command and output only that command.
        Commands:
        NEXT | PREVIOUS | PLAY | PAUSE | RESTART
        SEEK <seconds, negative = back> | SEEKTO <seconds from the start>
        VOLUME <+steps or -steps> | VOLUMETO <percent>
        LIKE | SHUFFLE | REPEAT | ANNOUNCE (say what is playing) | TIMELEFT | INFO (tell about the track or artist) | ASK (open Gemini)
        SLEEP <minutes> | SLEEP END (pause at the end of the track) | SLEEP OFF
        OPEN <app> (one of: ${apps.joinToString(", ").ifEmpty { "-" }})
        POCKET (pocket mode) | FLASHLIGHT | NONE (not a player command)
        Examples:
        "перемотай немного назад" -> SEEK -15
        "сделай потише" -> VOLUME -2
        "выключи музыку через полчаса" -> SLEEP 30
        "включи что-нибудь другое" -> NEXT
        "верни прошлую песню" -> PREVIOUS
        "что это играет" -> ANNOUNCE
        "а что это за группа" -> INFO
        "хочу послушать спотифай" -> OPEN spotify
        "какая завтра погода" -> NONE
        Phrase: "${phrase.replace("\"", "")}"
        Command:
    """.trimIndent()

    fun parse(answer: String, players: Map<String, String>): Command? {
        val line = answer.lineSequence().map { it.trim().trim('`', '"', '.', '*') }.firstOrNull { it.isNotEmpty() } ?: return null
        val words = line.split(Regex("\\s+"))
        val head = words.first().uppercase()
        val arg = words.getOrNull(1)
        val number = arg?.replace("+", "")?.toDoubleOrNull()
        return when (head) {
            "NEXT" -> Command.Run(Action.NEXT)
            "PREVIOUS" -> Command.Run(Action.PREVIOUS)
            "PLAY" -> Command.Play
            "PAUSE" -> Command.Pause
            "RESTART" -> Command.Run(Action.RESTART)
            "SEEK" -> number?.let { Command.SeekBy((it * 1000).toLong()) }
            "SEEKTO" -> number?.let { Command.SeekTo((it * 1000).toLong().coerceAtLeast(0)) }
            "VOLUME" -> number?.let { Command.VolumeBy(it.toInt()) }
            "VOLUMETO" -> number?.let { Command.VolumeTo(it.toInt().coerceIn(0, 100)) }
            "LIKE" -> Command.Run(Action.LIKE)
            "SHUFFLE" -> Command.Run(Action.SHUFFLE)
            "REPEAT" -> Command.Run(Action.REPEAT)
            "ANNOUNCE" -> Command.Run(Action.ANNOUNCE)
            "TIMELEFT" -> Command.Run(Action.TIME_LEFT)
            "ASK" -> Command.Run(Action.ASK_GEMINI)
            "INFO" -> Command.Run(Action.TRACK_INFO)
            "SLEEP" -> when (arg?.uppercase()) {
                "END" -> Command.SleepToTrackEnd
                "OFF" -> Command.SleepCancel
                else -> number?.let { Command.Sleep(it.toInt().coerceIn(1, 600)) } ?: Command.Run(Action.SLEEP_TIMER)
            }
            "OPEN" -> {
                val name = words.drop(1).joinToString(" ").lowercase()
                players.entries.firstOrNull { (label, _) -> name.isNotEmpty() && (label.contains(name) || name.contains(label)) }
                    ?.let { Command.Open(it.value) }
            }
            "POCKET" -> Command.Run(Action.POCKET)
            "FLASHLIGHT" -> Command.Run(Action.FLASHLIGHT)
            else -> null
        }
    }
}

/** Track names made fit for reading aloud by a model, beyond what the clean-up rules catch. */
object TitleBrain {
    fun prompt(title: String, artist: String?): String = """
        Rewrite this music track for reading aloud by a text-to-speech voice.
        Remove everything that is not the song's name or the artist: video/audio/lyrics/remaster/HD/4K tags, years in brackets, channel suffixes like "- Topic" or "VEVO", hashtags, emoji.
        Keep the original language and spelling of the words. Do not translate. Do not add anything.
        Answer in exactly two lines: the title, then the artist (or "-" if unknown).
        Title: ${title.replace("\n", " ")}
        Artist: ${artist?.replace("\n", " ") ?: "-"}
    """.trimIndent()

    fun parse(answer: String): Pair<String, String?>? {
        val lines = answer.lines().map { it.trim().removePrefix("Title:").removePrefix("Artist:").trim() }.filter { it.isNotEmpty() }
        val title = lines.getOrNull(0)?.takeIf { it.length in 1..200 } ?: return null
        val artist = lines.getOrNull(1)?.takeIf { it != "-" && it.length in 1..200 }
        return title to artist
    }
}
