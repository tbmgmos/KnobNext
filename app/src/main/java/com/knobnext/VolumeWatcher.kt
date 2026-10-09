package com.knobnext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Reports music volume values from two sources:
 *  - the VOLUME_CHANGED_ACTION broadcast. Recent Android versions deliver only the most recent one
 *    when several are pending, so quick presses can arrive merged (23→25);
 *  - polling while the screen is off, music is playing and [shouldPoll] allows it. This sees every
 *    step separately no matter how broadcasts are delivered.
 * Callers de-duplicate by value, so the same step reported by both sources counts once.
 */
class VolumeWatcher(
    private val context: Context,
    private val handler: Handler,
    private val shouldPoll: () -> Boolean,
    private val onVolume: (value: Int, source: Source) -> Unit,
) {
    /** SYNC = baseline taken when polling starts; it must not count as a press. */
    enum class Source { BROADCAST, POLL, SYNC }

    private val audio = context.getSystemService(AudioManager::class.java)
    private var polling = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            if (value >= 0) onVolume(value, Source.BROADCAST)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) startPolling() else stopPolling()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            val active = audio.isMusicActive && shouldPoll()
            if (active) onVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), Source.POLL)
            handler.postDelayed(this, if (active) POLL_MS else IDLE_POLL_MS)
        }
    }

    fun start() {
        register(volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION))
        register(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
    }

    fun stop() {
        stopPolling()
        context.unregisterReceiver(volumeReceiver)
        context.unregisterReceiver(screenReceiver)
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        // Broadcasts may have been merged or missed while the screen was on: re-baseline silently,
        // otherwise the first poll would see a multi-step "jump" and replay it as presses.
        onVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), Source.SYNC)
        handler.postDelayed(poll, POLL_MS)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(poll)
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"

        /** Fast enough to see each press of a double press separately. */
        const val POLL_MS = 80L

        /** While nothing plays: just check now and then whether playback started. */
        const val IDLE_POLL_MS = 1_000L
    }
}
