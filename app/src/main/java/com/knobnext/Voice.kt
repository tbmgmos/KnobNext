package com.knobnext

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** What a spoken phrase asks for. */
sealed interface Command {
    /** One of the gesture actions, as if its gesture was made. */
    data class Run(val action: Action) : Command
    data object Play : Command
    data object Pause : Command
    data class SeekBy(val ms: Long) : Command
    data class SeekTo(val ms: Long) : Command
    data class VolumeBy(val steps: Int) : Command
    data class VolumeTo(val percent: Int) : Command
    data class Sleep(val minutes: Int) : Command
    data object SleepToTrackEnd : Command
    data object SleepCancel : Command
    data class Open(val packageName: String) : Command
}

/**
 * Turns a phrase into a [Command] with plain rules: Russian first, some English. Speech recognizers
 * write numbers both as digits and as words, so both are understood ("на 2 минуты", "на две минуты").
 */
object CommandRules {
    /** [players]: app label (lowercase) → package, for "открой Spotify". */
    fun parse(phrase: String, players: Map<String, String>): Command? {
        val text = normalize(phrase)
        if (text.isBlank()) return null
        fun has(vararg stems: String) = stems.any { text.contains(it) }
        val duration = durationMs(text)

        if (has("таймер", "усни", "sleep timer", "выключи через", "выключись через")) {
            return when {
                has("отмен", "убери", "выключи таймер", "останови таймер", "стоп таймер", "cancel") -> Command.SleepCancel
                has("конца трека", "конец трека", "конца песни", "конец песни", "end of track", "end of song") -> Command.SleepToTrackEnd
                duration != null -> Command.Sleep(((duration + 30_000) / 60_000).toInt().coerceAtLeast(1))
                else -> Command.Run(Action.SLEEP_TIMER)
            }
        }
        if (has("с начала", "сначала", "на начало", "заново", "from the start", "restart")) return Command.Run(Action.RESTART)
        val back = has("назад", "отмота", "верни", "back", "rewind")
        val forward = has("вперед", "перемота", "промота", "пропусти", "forward", "skip ahead")
        if (duration != null && (back || forward)) return Command.SeekBy(if (back) -duration else duration)
        // "Перейди на 1:30": a point in the track, not a step.
        clockMs(text)?.let { if (has("перейди", "перемотай", "на ", "go to", "jump")) return Command.SeekTo(it) }
        if (duration != null && has("перейди на", "go to")) return Command.SeekTo(duration)

        if (has("сколько осталось", "сколько еще", "time left")) return Command.Run(Action.TIME_LEFT)
        if (has("gemini", "джемини", "гемини", "расскажи о", "расскажи про", "спроси", "ask")) return Command.Run(Action.ASK_GEMINI)
        if (has("что играет", "что за песн", "что за трек", "что это", "какой трек", "какая песня", "кто поет", "what's playing", "what is playing")) {
            return Command.Run(Action.ANNOUNCE)
        }
        if (has("лайк", "нравится", "в избранное", "сохрани трек", "like")) return Command.Run(Action.LIKE)
        if (has("перемеша", "вперемешку", "шафл", "случайн", "shuffle")) return Command.Run(Action.SHUFFLE)
        if (has("повтор", "repeat")) return Command.Run(Action.REPEAT)
        if (has("карман", "pocket")) return Command.Run(Action.POCKET)
        if (has("фонар", "flashlight", "torch")) return Command.Run(Action.FLASHLIGHT)
        if (has("вибрац", "беззвуч", "звонок", "vibrate")) return Command.Run(Action.RINGER)
        if (has("другой плеер", "переключи плеер", "смени плеер", "switch player")) return Command.Run(Action.SWITCH_PLAYER)

        // An app by name: "открой Spotify", "включи ютуб".
        if (has("открой", "запусти", "включи", "open", "launch", "play")) {
            // Names are often said in Russian: "ютуб", "спотифай".
            val spoken = APP_ALIASES.entries.fold(text) { t, (said, written) -> t.replace(said, written) }
            players.entries.sortedByDescending { it.key.length }
                .firstOrNull { (label, _) -> label.length >= 2 && spoken.contains(label) }
                ?.let { return Command.Open(it.value) }
        }

        if (has("громче", "louder", "volume up")) return Command.VolumeBy(numberIn(text) ?: DEFAULT_VOLUME_STEPS)
        if (has("тише", "quieter", "volume down")) return Command.VolumeBy(-(numberIn(text) ?: DEFAULT_VOLUME_STEPS))
        if (has("громкость", "volume")) numberIn(text)?.let { return Command.VolumeTo(it.coerceIn(0, 100)) }

        if (has("следующ", "дальше", "пропусти", "скип", "next", "skip")) return Command.Run(Action.NEXT)
        if (has("предыдущ", "прошл", "назад", "previous", "back")) return Command.Run(Action.PREVIOUS)
        if (has("пауз", "стоп", "останови", "хватит", "выключи музыку", "pause", "stop")) return Command.Pause
        if (has("играй", "продолж", "включи", "возобнов", "воспроизв", "play", "resume")) return Command.Play
        return null
    }

    fun normalize(phrase: String): String =
        phrase.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}: ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    /** "2 минуты", "полторы минуты", "тридцать секунд", "минуту" (= 1), "1 час 5 минут". */
    fun durationMs(text: String): Long? {
        val words = numbersToDigits(text.replace("полминуты", "0.5 минуты").replace("полчаса", "0.5 часа"))
        var total = 0.0
        var found = false
        val unit = Regex("(\\d+(?:[.,]\\d+)?)?\\s*(час|минут|секунд|сек(?!\\p{L})|мин(?!\\p{L})|hour|minute|min(?!\\p{L})|second|sec(?!\\p{L}))")
        for (m in unit.findAll(words)) {
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
            val scale = when {
                m.groupValues[2].startsWith("час") || m.groupValues[2].startsWith("hour") -> 3_600_000.0
                m.groupValues[2].startsWith("мин") || m.groupValues[2].startsWith("min") -> 60_000.0
                else -> 1_000.0
            }
            total += n * scale
            found = true
        }
        return if (found && total > 0) total.toLong() else null
    }

    /** "1:30" → 90 s. */
    private fun clockMs(text: String): Long? {
        val m = Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(text) ?: return null
        return (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000
    }

    private fun numberIn(text: String): Int? =
        Regex("\\b(\\d+)\\b").find(numbersToDigits(text))?.groupValues?.get(1)?.toIntOrNull()

    /** Number words to digits: "двадцать пять" → "25", "полторы" → "1.5". */
    fun numbersToDigits(text: String): String {
        val out = ArrayList<String>()
        var pending: Int? = null
        fun flush() {
            pending?.let { out += it.toString() }
            pending = null
        }
        for (word in text.split(' ')) {
            val half = HALVES[word]
            if (half != null) {
                flush()
                out += half
                continue
            }
            val n = NUMBERS[word]
            if (n == null) {
                flush()
                out += word
                continue
            }
            val p = pending
            // "двадцать пять": tens followed by units add up; anything else starts a new number.
            pending = if (p != null && p % 10 == 0 && p in 20..90 && n < 10) p + n else {
                flush()
                n
            }
        }
        flush()
        return out.joinToString(" ")
    }

    private val HALVES = mapOf("полторы" to "1.5", "полтора" to "1.5", "пол" to "0.5")

    private val NUMBERS: Map<String, Int> = buildMap {
        listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
            "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
            "семнадцать", "восемнадцать", "девятнадцать").forEachIndexed { i, w -> put(w, i) }
        put("одну", 1); put("одна", 1); put("две", 2)
        listOf("двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
            .forEachIndexed { i, w -> put(w, (i + 2) * 10) }
        put("сто", 100)
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
            .forEachIndexed { i, w -> put(w, i) }
        put("fifteen", 15); put("twenty", 20); put("thirty", 30); put("forty", 40); put("fifty", 50)
    }

    private const val DEFAULT_VOLUME_STEPS = 2

    private val APP_ALIASES = linkedMapOf(
        "ютуб музык" to "youtube music",
        "ютьюб" to "youtube",
        "ютуб" to "youtube",
        "спотифай" to "spotify",
        "спотифи" to "spotify",
        "яндекс музык" to "яндекс музыка",
        "вк музык" to "vk музыка",
        "звук" to "звук",
        "подкаст" to "podcast",
        "саундклауд" to "soundcloud",
        "дизер" to "deezer",
        "апл музык" to "apple music",
    )
}

/**
 * Listens for one phrase with the system speech recognizer: the on-device one when the phone has it
 * (works offline, nothing leaves the phone), otherwise the regular one.
 */
class VoiceListener(private val context: Context, private val handler: Handler) {
    sealed interface Result {
        data class Heard(val phrases: List<String>) : Result
        data class Failed(val reason: String) : Result
    }

    private var recognizer: SpeechRecognizer? = null
    private var offline: OfflineSpeech? = null
    private var onResult: ((Result) -> Unit)? = null
    private val timeout = Runnable { finish(Result.Failed(context.getString(R.string.voice_err_timeout))) }

    val listening: Boolean get() = recognizer != null || offline != null

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun listen(onResult: (Result) -> Unit) {
        cancel()
        if (!hasMicPermission()) return onResult(Result.Failed(context.getString(R.string.voice_err_permission)))
        this.onResult = onResult
        // The system's on-device recognizer first, then a downloaded Vosk model, then the system's
        // regular (possibly online) one.
        when {
            availability(context) == Availability.ON_DEVICE -> start(onDevice = true)
            useOffline() -> Unit
            else -> start(onDevice = false)
        }
    }

    /** Hands the phrase to Vosk, when its model is downloaded. */
    private fun useOffline(): Boolean {
        val speech = Offline.speech(context) ?: return false
        offline = speech
        speech.listen { finish(it) }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
        offline?.cancel()
        offline = null
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
        onResult = null
    }

    private fun start(onDevice: Boolean) {
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (_: Exception) {
            return finish(Result.Failed(context.getString(R.string.voice_err_unavailable)))
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                finish(if (phrases.isEmpty()) Result.Failed(context.getString(R.string.voice_err_nothing)) else Result.Heard(phrases))
            }

            override fun onError(error: Int) {
                // The on-device recognizer may lack this language: try the regular one once.
                val languageMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                val network = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_SERVER
                if (onDevice && languageMissing || !onDevice && network) {
                    val callback = onResult
                    cancel()
                    onResult = callback
                    // No internet for the regular recognizer: Vosk, if it's there.
                    if (!onDevice && useOffline()) return
                    if (onDevice) return start(onDevice = false)
                }
                finish(Result.Failed(describe(error)))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        try {
            r.startListening(intent)
        } catch (_: Exception) {
            return finish(Result.Failed(context.getString(R.string.voice_err_unavailable)))
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, LISTEN_TIMEOUT_MS)
    }

    private fun finish(result: Result) {
        val callback = onResult
        cancel()
        callback?.invoke(result)
    }

    private fun describe(error: Int): String = context.getString(
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_err_nothing
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_err_permission
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.voice_err_network
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_err_busy
            SpeechRecognizer.ERROR_AUDIO -> R.string.voice_err_audio
            else -> R.string.voice_err_unavailable
        },
    ) + " ($error)"

    enum class Availability { ON_DEVICE, ONLINE, NONE }

    companion object {
        private const val MAX_RESULTS = 5
        private const val LISTEN_TIMEOUT_MS = 10_000L

        fun availability(context: Context): Availability = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                Availability.ON_DEVICE
            SpeechRecognizer.isRecognitionAvailable(context) -> Availability.ONLINE
            else -> Availability.NONE
        }

        /** Set by the service while it runs: listens for one command (from the app's "try it"). */
        @Volatile var trigger: (() -> Unit)? = null
    }
}
