package com.knobnext

import android.content.Context

/**
 * The RuStore build has no network access, so nothing can be downloaded here yet: Gemini Nano and the
 * system recognizer still work. (To do: import model files picked by hand.)
 */
object Offline {
    const val supported = false

    fun llm(context: Context) = OfflineFile(OfflineFile.State.MISSING)

    suspend fun downloadLlm(context: Context) = Unit

    fun deleteLlm(context: Context) = Unit

    fun brain(context: Context): Brain? = null

    fun vosk(context: Context) = OfflineFile(OfflineFile.State.MISSING)

    fun downloadVosk(context: Context) = Unit

    fun deleteVosk(context: Context) = Unit

    fun speech(context: Context): OfflineSpeech? = null
}
