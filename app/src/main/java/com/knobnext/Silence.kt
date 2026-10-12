package com.knobnext

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays inaudible silence. With the screen off, Android hands the volume keys to music only while
 * music is playing; after a gesture paused it, the keys would do nothing at all, and the same gesture
 * could not resume. While this plays, the keys keep changing the music volume, so the press
 * detection still sees them. Used only for the resume window after a gesture pause; it takes no audio
 * focus, so players are not disturbed.
 */
class Silence {
    private var track: AudioTrack? = null

    val playing: Boolean get() = track != null

    fun start(): Boolean {
        if (track != null) return true
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(FRAMES * 2)
                .build()
            t.write(ShortArray(FRAMES), 0, FRAMES)
            // Loops the same half second of zeros for as long as needed, no thread to feed it.
            t.setLoopPoints(0, FRAMES, -1)
            t.play()
            track = t
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 8_000
        const val FRAMES = SAMPLE_RATE / 2
    }
}
