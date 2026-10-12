package com.knobnext

import android.os.Handler
import android.os.Looper

/**
 * The headphones page, open on screen: presses are shown there instead of being acted on, so it is plain
 * which gesture on the headphones is which line on the page. Also tells the page which headphones
 * KnobNext catches the buttons of right now.
 */
object HeadsetLive {
    sealed interface Event {
        /** A press, recognized as [gesture]; [key] is the key the headphones sent (0: the voice button). */
        data class Pressed(val gesture: Gesture, val key: Int) : Event

        /** The headphones whose buttons come to KnobNext now; null when none. */
        data class Device(val name: String?) : Event
    }

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((Event) -> Unit)? = null
    private var visible = true

    /** Set by the service; read by the page when it opens. */
    @Volatile var device: String? = null
        private set

    /** The page is open and on screen: presses only show there. */
    val learning: Boolean get() = listener != null && visible

    fun start(onEvent: (Event) -> Unit) {
        listener = onEvent
    }

    fun stop() {
        listener = null
    }

    /** Called by the activity: leaving for another app hands the buttons back to the music at once. */
    fun setVisible(shown: Boolean) {
        visible = shown
    }

    fun report(gesture: Gesture, key: Int) {
        main.post { listener?.invoke(Event.Pressed(gesture, key)) }
    }

    fun setDevice(name: String?) {
        device = name
        main.post { listener?.invoke(Event.Device(name)) }
    }
}
