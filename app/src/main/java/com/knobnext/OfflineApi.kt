package com.knobnext

/** A file the app downloads to work offline: a language model or a speech recognition model. */
data class OfflineFile(val state: State, val downloadedMb: Int = 0, val totalMb: Int = 0, val error: String? = null) {
    enum class State { MISSING, DOWNLOADING, UNPACKING, READY, FAILED }
}

/** Listens for one phrase without the system recognizer (Vosk). */
interface OfflineSpeech {
    fun listen(onResult: (VoiceListener.Result) -> Unit)
    fun cancel()
}
