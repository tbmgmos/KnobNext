package com.knobnext

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Preparing text for speech: tidying track titles and cutting mixed-language text into pieces,
 * so "Сейчас играет Bohemian Rhapsody" is read by a Russian voice and then an English one instead of
 * the English words being read with Russian rules.
 */
object Speech {
    /** A piece of text in one script; [locale] null means the system language. */
    data class Part(val text: String, val locale: Locale?)

    /** What "announce" says: cleaned title, then artist. */
    fun announcement(title: String, artist: String?, clean: Boolean): String {
        val t = if (clean) cleanTitle(title) else title
        val a = artist?.let { if (clean) cleanArtist(it) else it }?.takeIf { it.isNotBlank() && it != t }
        return listOfNotNull(t, a).joinToString(". ")
    }

    private val NOISE_WORDS =
        "remaster(?:ed)?|official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|explicit|clean|bonus|deluxe|mono|stereo|" +
            "version|edit|mix|prod\\.?|feat\\.?|ft\\.?|featuring|mv|clip|клип|премьера"
    private val BRACKETED_NOISE = Regex("""\s*[(\[{][^)\]}]*\b(?:$NOISE_WORDS)\b[^)\]}]*[)\]}]""", RegexOption.IGNORE_CASE)
    private val DASHED_NOISE = Regex("""\s+[-–—]\s+[^-–—]*\b(?:remaster(?:ed)?|version|edit|mix|mono|stereo|bonus)\b.*$""", RegexOption.IGNORE_CASE)
    private val FEATURING = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val CHANNEL_TAIL = Regex("""\s+\|\s+.*$""")
    private val HASHTAG = Regex("""#\S+""")
    private val SPACES = Regex("""\s{2,}""")
    private val TRAILING = Regex("""[\s\-–—:|,.]+$""")

    /** "Song (Remastered 2011) [Official Video] feat. X | Channel 🎵 #tag" → "Song". */
    fun cleanTitle(title: String): String {
        var t = withoutSymbols(title)
        t = BRACKETED_NOISE.replace(t, "")
        t = DASHED_NOISE.replace(t, "")
        t = FEATURING.replace(t, "")
        t = CHANNEL_TAIL.replace(t, "")
        t = HASHTAG.replace(t, "")
        t = SPACES.replace(t, " ")
        t = TRAILING.replace(t, "").trim()
        return t.ifBlank { title.trim() }
    }

    /** YouTube's "Queen - Topic" and "QueenVEVO" are the artist "Queen". */
    fun cleanArtist(artist: String): String {
        var a = withoutSymbols(artist)
        a = a.replace(Regex("""\s+-\s+Topic$"""), "")
        a = a.replace(Regex("""(?<=\S)VEVO$"""), "")
        a = FEATURING.replace(a, "")
        return a.trim().ifBlank { artist.trim() }
    }

    /** Emoji and other pictographs, which engines read out ("musical note") or stumble on. */
    private fun withoutSymbols(text: String): String = buildString {
        text.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            val drop = type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                cp == 0xFE0F || cp == 0x200D
            if (!drop) appendCodePoint(cp)
        }
    }

    private enum class Script { LATIN, CYRILLIC, CJK, HANGUL, ARABIC, HEBREW, GREEK, THAI, DEVANAGARI }

    private fun scriptOf(cp: Int): Script? {
        if (!Character.isLetter(cp)) return null
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN -> Script.LATIN
            Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.CJK
            Character.UnicodeScript.HANGUL -> Script.HANGUL
            Character.UnicodeScript.ARABIC -> Script.ARABIC
            Character.UnicodeScript.HEBREW -> Script.HEBREW
            Character.UnicodeScript.GREEK -> Script.GREEK
            Character.UnicodeScript.THAI -> Script.THAI
            Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
            else -> null
        }
    }

    /**
     * Cuts [text] where the alphabet changes. Spaces and punctuation stay with the piece before them,
     * so the pauses fall where they are written.
     */
    fun split(text: String): List<Part> {
        val parts = ArrayList<Part>()
        val piece = StringBuilder()
        var script: Script? = null
        fun flush() {
            val t = piece.toString().trim()
            if (t.isNotEmpty()) parts += Part(t, localeFor(script, t))
            piece.clear()
        }
        text.codePoints().forEach { cp ->
            val s = scriptOf(cp)
            if (s != null && script != null && s != script) {
                flush()
                script = s
            } else if (s != null && script == null) {
                script = s
            }
            piece.appendCodePoint(cp)
        }
        flush()
        return parts
    }

    private val CYRILLIC_LANGUAGES = setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "mn")

    private fun localeFor(script: Script?, text: String): Locale? {
        val system = Locale.getDefault()
        return when (script) {
            null -> null
            Script.CYRILLIC -> when {
                system.language in CYRILLIC_LANGUAGES -> null
                text.any { it in "іїєґІЇЄҐ" } -> Locale.forLanguageTag("uk-UA")
                else -> Locale.forLanguageTag("ru-RU")
            }
            Script.LATIN -> latinLocale(text) ?: if (system.language in CYRILLIC_LANGUAGES) Locale.US else null
            Script.CJK -> if (text.any { Character.UnicodeScript.of(it.code).let { s -> s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA } }) {
                Locale.JAPAN
            } else {
                Locale.CHINA
            }
            Script.HANGUL -> Locale.KOREA
            Script.ARABIC -> Locale.forLanguageTag("ar")
            Script.HEBREW -> Locale.forLanguageTag("he")
            Script.GREEK -> Locale.forLanguageTag("el-GR")
            Script.THAI -> Locale.forLanguageTag("th-TH")
            Script.DEVANAGARI -> Locale.forLanguageTag("hi-IN")
        }
    }

    /** A Latin-script language told by its own letters; null when nothing gives it away. */
    private fun latinLocale(text: String): Locale? {
        val t = text.lowercase()
        return when {
            t.any { it in "äöüß" } -> Locale.GERMANY
            t.any { it in "ñ¡¿" } -> Locale.forLanguageTag("es-ES")
            t.any { it in "ãõ" } -> Locale.forLanguageTag("pt-BR")
            t.any { it in "çéèêëàâîïôûùœ" } -> Locale.FRANCE
            t.any { it in "åæø" } -> Locale.forLanguageTag("sv-SE")
            t.any { it in "ąęłńśźż" } -> Locale.forLanguageTag("pl-PL")
            t.any { it in "čřšžěů" } -> Locale.forLanguageTag("cs-CZ")
            t.any { it in "ğış" } -> Locale.forLanguageTag("tr-TR")
            else -> null
        }
    }

    /** Installed text-to-speech engines as (package, label). */
    fun engines(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
    }

    /** The system's text-to-speech settings, where voices are downloaded. */
    fun settingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Text-to-speech that briefly ducks the music while it talks. With "pronounce foreign words" on,
 * each piece in another alphabet gets a voice of its own language.
 */
class Speaker(private val context: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private var tts: TextToSpeech? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Players that pause for an announcement resume only when the focus is given back. If the speech
    // engine never reports the end (it failed, was stopped or died), give it back anyway.
    private val giveBackFocus = Runnable { audio.abandonAudioFocusRequest(focus) }
    private var engineName: String? = null
    private var ready = false
    private var pending: String? = null
    private var serial = 0

    fun say(text: String) {
        val wanted = prefs.ttsEngine
        if (tts != null && wanted != engineName) release()
        if (tts == null) {
            pending = text
            engineName = wanted
            tts = if (wanted != null) TextToSpeech(context, this, wanted) else TextToSpeech(context, this)
            return
        }
        if (ready) speak(text) else pending = text
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        engine.language = Locale.getDefault()
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            // Focus is held for the whole phrase, which may be several pieces.
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.endsWith(LAST) == true) audio.abandonAudioFocusRequest(focus)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId?.endsWith(LAST) == true) audio.abandonAudioFocusRequest(focus)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audio.abandonAudioFocusRequest(focus)
            }
        })
        pending?.let(::speak)
        pending = null
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        val parts = if (prefs.ttsMultilang) Speech.split(text) else listOf(Speech.Part(text, null))
        if (parts.isEmpty()) return
        engine.setSpeechRate(prefs.ttsRate)
        audio.requestAudioFocus(focus)
        handler.removeCallbacks(giveBackFocus)
        handler.postDelayed(giveBackFocus, (FOCUS_BASE_MS + text.length * FOCUS_PER_CHAR_MS / prefs.ttsRate).toLong().coerceAtMost(FOCUS_MAX_MS))
        val id = ++serial
        parts.forEachIndexed { i, part ->
            // The voice is taken when the piece is queued, so each piece keeps its own.
            useLanguage(engine, part.locale ?: Locale.getDefault())
            val utterance = "knob-$id-$i" + if (i == parts.lastIndex) LAST else ""
            val queued = engine.speak(part.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utterance)
            if (queued != TextToSpeech.SUCCESS) {
                // Nothing will be said, and no callback will come to give the focus back.
                handler.removeCallbacks(giveBackFocus)
                audio.abandonAudioFocusRequest(focus)
                return
            }
        }
    }

    private fun useLanguage(engine: TextToSpeech, locale: Locale) {
        val voice = bestVoice(engine, locale)
        if (voice != null) {
            engine.voice = voice
        } else if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        } else {
            // This language isn't installed: the system voice reads it as best it can.
            engine.language = Locale.getDefault()
        }
    }

    /** An installed voice for the language, the same country and better quality first; on-device before network. */
    private fun bestVoice(engine: TextToSpeech, locale: Locale): Voice? =
        try {
            engine.voices.orEmpty()
                .filter { it.locale.language == locale.language && "notInstalled" !in it.features.orEmpty() }
                .sortedWith(
                    compareByDescending<Voice> { it.locale.country == locale.country }
                        .thenBy { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality },
                )
                .firstOrNull()
        } catch (_: Exception) {
            null
        }

    fun release() {
        handler.removeCallbacks(giveBackFocus)
        audio.abandonAudioFocusRequest(focus)
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val LAST = "-last"
        const val FOCUS_BASE_MS = 3_000f
        const val FOCUS_PER_CHAR_MS = 120f
        const val FOCUS_MAX_MS = 30_000L
    }
}
