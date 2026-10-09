package com.knobnext

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The gesture check: while its page is open, the service reports recognized gestures here instead of
 * running their actions, intercepts keys even when nothing plays, and runs the motion sensors.
 * The page and the service live in the same process, so a plain object is enough.
 *
 * It stays active for a couple of minutes after the app goes to the background, so screen-off
 * gestures can be tried too: turn the screen off, do the gesture, come back and see it ticked.
 */
object GestureTest {
    sealed interface Event {
        data class Recognized(val gesture: Gesture, val action: Action) : Event

        /** Something happened that is not a gesture (a short tap, a flip) or a gesture was rejected. */
        data class Note(val text: String) : Event
    }

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((Event) -> Unit)? = null
    private var visible = true
    private var hiddenAt = 0L

    /** Set by the service: called when [active] may have changed, so sensors can follow at once. */
    var onChange: (() -> Unit)? = null

    val active: Boolean
        get() = listener != null && (visible || SystemClock.elapsedRealtime() - hiddenAt < BACKGROUND_GRACE_MS)

    fun start(onEvent: (Event) -> Unit) {
        listener = onEvent
        onChange?.invoke()
    }

    fun stop() {
        listener = null
        onChange?.invoke()
    }

    /** Called by the activity when it is shown or hidden. */
    fun setVisible(shown: Boolean) {
        visible = shown
        if (!shown) {
            hiddenAt = SystemClock.elapsedRealtime()
            // Re-check once the grace period is over, so sensors don't keep running for nothing.
            main.postDelayed({ onChange?.invoke() }, BACKGROUND_GRACE_MS + 100)
        }
        onChange?.invoke()
    }

    fun report(event: Event) {
        val l = listener ?: return
        main.post { l(event) }
    }

    private const val BACKGROUND_GRACE_MS = 2 * 60 * 1000L
}
