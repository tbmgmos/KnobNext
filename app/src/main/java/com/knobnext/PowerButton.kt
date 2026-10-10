package com.knobnext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * The power button is never passed to apps, accessibility services included: the system handles it
 * first. What is seen is its effect, the screen turning off or on. Two such turns in quick succession
 * are a double press. The system's own double press (camera, wallet) wins over that, so it has to be
 * turned off for the gesture to be free.
 */
object PowerButton {
    /** Whether the system's double press (camera or wallet) is on; null when that can't be read. */
    fun systemDoublePressOn(context: Context): Boolean? {
        val resolver = context.contentResolver
        return try {
            // Newer Android: one switch for camera or wallet.
            Settings.Secure.getString(resolver, NEW_KEY)?.toIntOrNull()?.let { return it == 1 }
            Settings.Secure.getString(resolver, CAMERA_DISABLED_KEY)?.toIntOrNull()?.let { return it == 0 }
            null
        } catch (_: Exception) {
            // Hidden settings may not be readable by apps.
            null
        }
    }

    /** The system gesture settings, where the double press is turned off; general settings if not found. */
    fun openGestureSettings(context: Context) {
        val tries = listOf(
            Intent(ACTION_GESTURE_SETTINGS),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$GestureSettingsActivity")),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    private const val NEW_KEY = "double_tap_power_button_gesture_enabled"
    private const val CAMERA_DISABLED_KEY = "camera_double_tap_power_gesture_disabled"
    private const val ACTION_GESTURE_SETTINGS = "android.settings.GESTURE_SETTINGS"
}
