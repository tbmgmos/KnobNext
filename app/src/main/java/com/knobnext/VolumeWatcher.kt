package com.knobnext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock

/**
 * Reports music volume values from two sources:
 *  - the VOLUME_CHANGED_ACTION broadcast. Recent Android versions deliver only the most recent one
 *    when several are pending, so quick presses can arrive merged (23→25);
 *  - polling while the screen is off, music is playing and [shouldPoll] allows it. This sees every
 *    step separately no matter how broadcasts are delivered.
 * Callers de-duplicate by value, so the same step reported by both sources counts once.
 *
 * Changes that are not presses are reported as [Source.SYNC]: the baseline when polling starts or
 * resumes, and the volume jump when headphones are connected or disconnected (each output device has
 * its own volume, so switching can move it by several steps at once).
 */
class VolumeWatcher(
    private val context: Context,
    private val handler: Handler,
    private val shouldPoll: () -> Boolean,
    /** The screen turned off (false) or on (true). */
    private val onScreen: (on: Boolean) -> Unit,
    private val onVolume: (value: Int, source: Source) -> Unit,
) {
    /** SYNC = a new baseline; it must not count as a press. KEY = a tap seen directly (gesture check). */
    enum class Source { BROADCAST, POLL, SYNC, KEY }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var polling = false
    private var pollActive = false
    private var musicSeenAt = 0L
    private var outputsChangedAt = 0L

    // What the poll saw last, to tell a step it missed from one it already reported.
    private var polled = -1
    private var polledFrom = -1
    private var polledChangeAt = 0L

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            if (value < 0) return
            // While polling, the poll is authoritative; a late broadcast carries a stale value.
            if (!polling) return report(value, Source.BROADCAST)
            // Except for one thing: both keys pressed at once raise and lower the volume between two
            // polls, so the poll sees no change at all. The broadcast of the second step still tells
            // where the volume was a moment ago: replay that step and its undoing.
            val prev = intent.getIntExtra(EXTRA_PREV_VALUE, -1)
            val seen = prev == polledFrom && SystemClock.elapsedRealtime() - polledChangeAt < SEEN_MS
            if (pollActive && prev >= 0 && prev != value && value == polled && !seen) {
                polledFrom = prev
                polledChangeAt = SystemClock.elapsedRealtime()
                report(prev, Source.POLL)
                report(value, Source.POLL)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                onScreen(false)
                startPolling()
            } else {
                stopPolling()
                onScreen(true)
            }
        }
    }

    private val outputs = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = outputsChanged(added)
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = outputsChanged(removed)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            val now = SystemClock.elapsedRealtime()
            // Players go quiet for a moment between tracks; keep watching through that gap.
            if (audio.isMusicActive) musicSeenAt = now
            val active = now - musicSeenAt < MUSIC_GAP_MS && shouldPoll()
            if (active) {
                // Coming back from idle, the last known value may be old: start from a fresh baseline.
                val value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (value != polled) {
                    polledFrom = polled
                    polled = value
                    polledChangeAt = now
                }
                report(value, if (pollActive) Source.POLL else Source.SYNC)
            }
            pollActive = active
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
        audio.registerAudioDeviceCallback(outputs, handler)
        // Started (or restarted by the system) while the screen is already off: no SCREEN_OFF will come.
        if (!power.isInteractive) startPolling()
    }

    fun stop() {
        stopPolling()
        audio.unregisterAudioDeviceCallback(outputs)
        context.unregisterReceiver(volumeReceiver)
        context.unregisterReceiver(screenReceiver)
    }

    private fun report(value: Int, source: Source) {
        val settling = SystemClock.elapsedRealtime() - outputsChangedAt < OUTPUT_SETTLE_MS
        onVolume(value, if (settling) Source.SYNC else source)
    }

    private fun outputsChanged(devices: Array<out AudioDeviceInfo>) {
        if (devices.none { it.isSink }) return
        outputsChangedAt = SystemClock.elapsedRealtime()
        report(audio.getStreamVolume(AudioManager.STREAM_MUSIC), Source.SYNC)
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollActive = false
        // Broadcasts may have been merged or missed while the screen was on: re-baseline silently,
        // otherwise the first poll would see a multi-step "jump" and replay it as presses.
        polled = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        polledFrom = -1
        report(polled, Source.SYNC)
        handler.postDelayed(poll, POLL_MS)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(poll)
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        // Both broadcasts come from the system, which reaches non-exported receivers too; other apps
        // must not be able to fake volume changes (they would fire gestures).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        /** A broadcast this much later than the poll's change describes that same change. */
        const val SEEN_MS = 400L

        /** Fast enough to see each press of a double press separately. */
        const val POLL_MS = 80L

        /** While nothing plays: just check now and then whether playback started. */
        const val IDLE_POLL_MS = 1_000L

        /** Silence between tracks shorter than this still counts as playing. */
        const val MUSIC_GAP_MS = 3_000L

        /** After headphones come or go, volume changes are the device switch, not presses. */
        const val OUTPUT_SETTLE_MS = 1_500L
    }
}
