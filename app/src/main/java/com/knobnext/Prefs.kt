package com.knobnext

import android.content.Context
import android.content.SharedPreferences

enum class Action { NONE, NEXT, PREVIOUS, PLAY_PAUSE, SEEK_FORWARD, SEEK_BACK }

enum class Gesture(val key: String, val double: Boolean) {
    HOLD_UP("action_hold_up", false),
    HOLD_DOWN("action_hold_down", false),
    BOTH("action_both", false),
    DOUBLE_UP("action_double_up_off", true),
    DOUBLE_DOWN("action_double_down_off", true),
}

/** When a quick double press of a volume key counts as a gesture. */
enum class DoubleMode { OFF, SCREEN_OFF, ALWAYS }

data class Settings(
    val enabled: Boolean,
    val onlyWhenPlaying: Boolean,
    val doubleMode: DoubleMode,
    val lastEvent: String?,
    val headphonesOnly: Boolean,
    val pocketGuard: Boolean,
    val vibrate: Boolean,
    val longPressMs: Int,
    val appFilter: Boolean,
    val allowedApps: Set<String>,
    val seenPlayers: Set<String>,
    val checkUpdates: Boolean,
    val actions: Map<Gesture, Action>,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("knob", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var onlyWhenPlaying: Boolean
        get() = sp.getBoolean("only_when_playing", true)
        set(v) = sp.edit().putBoolean("only_when_playing", v).apply()

    var doubleMode: DoubleMode
        get() {
            val stored = sp.getString("double_mode", null)
            DoubleMode.entries.firstOrNull { it.name == stored }?.let { return it }
            // Version 3.0 had a single "screen off" switch.
            return if (sp.getBoolean("screen_off", true)) DoubleMode.SCREEN_OFF else DoubleMode.OFF
        }
        set(v) = sp.edit().putString("double_mode", v.name).apply()

    /** Human-readable trace of the last double-press detection, shown in settings for troubleshooting. */
    var lastEvent: String?
        get() = sp.getString("last_event", null)
        set(v) = sp.edit().putString("last_event", v).apply()

    var headphonesOnly: Boolean
        get() = sp.getBoolean("headphones_only", false)
        set(v) = sp.edit().putBoolean("headphones_only", v).apply()

    var pocketGuard: Boolean
        get() = sp.getBoolean("pocket_guard", false)
        set(v) = sp.edit().putBoolean("pocket_guard", v).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var longPressMs: Int
        get() = sp.getInt("long_press_ms", 500).coerceIn(LONG_PRESS_MIN, LONG_PRESS_MAX)
        set(v) = sp.edit().putInt("long_press_ms", v).apply()

    var appFilter: Boolean
        get() = sp.getBoolean("app_filter", false)
        set(v) = sp.edit().putBoolean("app_filter", v).apply()

    var allowedApps: Set<String>
        get() = sp.getStringSet("allowed_apps", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("allowed_apps", v).apply()

    val seenPlayers: Set<String>
        get() = sp.getStringSet("seen_players", null)?.toSet() ?: emptySet()

    var checkUpdates: Boolean
        get() = sp.getBoolean("check_updates", true)
        set(v) = sp.edit().putBoolean("check_updates", v).apply()

    var returnRequestedAt: Long
        get() = sp.getLong("return_requested_at", 0L)
        set(v) = sp.edit().putLong("return_requested_at", v).apply()

    fun rememberPlayer(packageName: String) {
        val seen = seenPlayers
        if (packageName !in seen) sp.edit().putStringSet("seen_players", seen + packageName).apply()
    }

    fun action(g: Gesture): Action {
        sp.getString(g.key, null)?.let { stored ->
            return Action.entries.firstOrNull { it.name == stored } ?: Action.NONE
        }
        // Defaults, honoring the "swap" / "combo" switches of version 2.
        val swap = sp.getBoolean("swap", false)
        return when (g) {
            Gesture.HOLD_UP, Gesture.DOUBLE_UP -> if (swap) Action.PREVIOUS else Action.NEXT
            Gesture.HOLD_DOWN, Gesture.DOUBLE_DOWN -> if (swap) Action.NEXT else Action.PREVIOUS
            Gesture.BOTH -> if (sp.getBoolean("combo_play_pause", true)) Action.PLAY_PAUSE else Action.NONE
        }
    }

    fun setAction(g: Gesture, a: Action) = sp.edit().putString(g.key, a.name).apply()

    fun snapshot() = Settings(
        enabled = enabled,
        onlyWhenPlaying = onlyWhenPlaying,
        doubleMode = doubleMode,
        lastEvent = lastEvent,
        headphonesOnly = headphonesOnly,
        pocketGuard = pocketGuard,
        vibrate = vibrate,
        longPressMs = longPressMs,
        appFilter = appFilter,
        allowedApps = allowedApps,
        seenPlayers = seenPlayers,
        checkUpdates = checkUpdates,
        actions = Gesture.entries.associateWith(::action),
    )

    /** Returns an unregister function. The listener object must stay referenced, so it lives in the closure. */
    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val LONG_PRESS_MIN = 250
        const val LONG_PRESS_MAX = 1500
        const val LONG_PRESS_STEP = 50
    }
}
