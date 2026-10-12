package com.knobnext

import android.app.Activity
import android.os.Build
import android.os.Bundle

/**
 * What Pixel's own "Quick Tap" (a double tap on the back, recognized by the phone's sensor chip, far
 * better than anything an app can do with the raw accelerometer) starts when it is told to open this
 * shortcut. Shows nothing: the service runs the assigned action, and this closes at once.
 */
class QuickTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        QuickTap.handler?.invoke()
        // Theme.NoDisplay: must finish before it would be shown.
        finish()
    }
}

object QuickTap {
    /** Set by the service while it runs. */
    @Volatile var handler: (() -> Unit)? = null
}
