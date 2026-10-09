package com.knobnext

import android.content.Context
import android.content.SharedPreferences

/** VOLUME_* on a held gesture keeps changing the volume until release; elsewhere it is one step. */
enum class Action {
    NONE, NEXT, PREVIOUS, PLAY_PAUSE, SEEK_FORWARD, SEEK_BACK, VOLUME_UP, VOLUME_DOWN,
    ANNOUNCE, LIKE, SHUFFLE, REPEAT, SLEEP_TIMER, OPEN_PLAYER;

    /** Actions that make sense when nothing is playing (they start or prepare playback). */
    val worksWhenIdle: Boolean get() = this == PLAY_PAUSE || this == OPEN_PLAYER || this == SLEEP_TIMER
}

enum class GestureGroup { KEYS, SEQUENCE, MOTION }

enum class Gesture(val key: String, val group: GestureGroup, val default: Action) {
    HOLD_UP("action_hold_up", GestureGroup.KEYS, Action.NEXT),
    HOLD_DOWN("action_hold_down", GestureGroup.KEYS, Action.PREVIOUS),
    LONG_HOLD_UP("action_long_hold_up", GestureGroup.KEYS, Action.NONE),
    LONG_HOLD_DOWN("action_long_hold_down", GestureGroup.KEYS, Action.NONE),
    TAP_HOLD_UP("action_tap_hold_up", GestureGroup.KEYS, Action.VOLUME_UP),
    TAP_HOLD_DOWN("action_tap_hold_down", GestureGroup.KEYS, Action.VOLUME_DOWN),
    BOTH("action_both", GestureGroup.KEYS, Action.PLAY_PAUSE),
    BOTH_HOLD("action_both_hold", GestureGroup.KEYS, Action.LIKE),
    DOUBLE_UP("action_double_up_off", GestureGroup.SEQUENCE, Action.NEXT),
    DOUBLE_DOWN("action_double_down_off", GestureGroup.SEQUENCE, Action.PREVIOUS),
    TRIPLE_UP("action_triple_up", GestureGroup.SEQUENCE, Action.SEEK_FORWARD),
    TRIPLE_DOWN("action_triple_down", GestureGroup.SEQUENCE, Action.SEEK_BACK),
    UP_DOWN("action_up_down", GestureGroup.SEQUENCE, Action.PLAY_PAUSE),
    DOWN_UP("action_down_up", GestureGroup.SEQUENCE, Action.LIKE),
    BACK_TAP_2("action_back_tap_2", GestureGroup.MOTION, Action.NEXT),
    BACK_TAP_3("action_back_tap_3", GestureGroup.MOTION, Action.PREVIOUS),
    TWIST("action_twist", GestureGroup.MOTION, Action.NEXT),
    WAVE("action_wave", GestureGroup.MOTION, Action.NEXT),
}

/** How hard the back has to be tapped; values are high-pass acceleration thresholds in m/s². */
enum class TapSensitivity(val threshold: Float) { LOW(5.0f), MEDIUM(3.5f), HIGH(2.4f) }

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
    val backTap: Boolean,
    val tapSensitivity: TapSensitivity,
    val flip: Boolean,
    val twist: Boolean,
    val wave: Boolean,
    val waveCount: Int,
    val sleepMinutes: Int,
    val sleepUntil: Long,
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

    var backTap: Boolean
        get() = sp.getBoolean("back_tap", false)
        set(v) = sp.edit().putBoolean("back_tap", v).apply()

    var tapSensitivity: TapSensitivity
        get() = TapSensitivity.entries.firstOrNull { it.name == sp.getString("tap_sensitivity", null) }
            ?: TapSensitivity.MEDIUM
        set(v) = sp.edit().putString("tap_sensitivity", v.name).apply()

    var flip: Boolean
        get() = sp.getBoolean("flip", false)
        set(v) = sp.edit().putBoolean("flip", v).apply()

    var twist: Boolean
        get() = sp.getBoolean("twist", false)
        set(v) = sp.edit().putBoolean("twist", v).apply()

    var wave: Boolean
        get() = sp.getBoolean("wave", false)
        set(v) = sp.edit().putBoolean("wave", v).apply()

    /** How many passes of the hand make the wave gesture: 1 or 2. */
    var waveCount: Int
        get() = sp.getInt("wave_count", 2).coerceIn(1, 2)
        set(v) = sp.edit().putInt("wave_count", v).apply()

    var sleepMinutes: Int
        get() = sp.getInt("sleep_minutes", 30)
        set(v) = sp.edit().putInt("sleep_minutes", v).apply()

    /** Wall-clock time (ms) when the sleep timer pauses playback, or 0. */
    var sleepUntil: Long
        get() = sp.getLong("sleep_until", 0L)
        set(v) = sp.edit().putLong("sleep_until", v).apply()

    /** Most recent player package, for "open player" when nothing is playing. */
    var lastPlayer: String?
        get() = sp.getString("last_player", null)
        set(v) = sp.edit().putString("last_player", v).apply()

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
            else -> g.default
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
        backTap = backTap,
        tapSensitivity = tapSensitivity,
        flip = flip,
        twist = twist,
        wave = wave,
        waveCount = waveCount,
        sleepMinutes = sleepMinutes,
        sleepUntil = sleepUntil,
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
