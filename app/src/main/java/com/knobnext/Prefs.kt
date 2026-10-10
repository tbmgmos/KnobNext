package com.knobnext

import android.content.Context
import android.content.SharedPreferences

/** VOLUME_* on a held gesture keeps changing the volume until release; elsewhere it is one step. */
enum class Action {
    NONE, NEXT, PREVIOUS, RESTART, PLAY_PAUSE, SEEK_FORWARD, SEEK_BACK, VOLUME_UP, VOLUME_DOWN,
    ANNOUNCE, TIME_LEFT, LIKE, SHUFFLE, REPEAT, SWITCH_PLAYER, SLEEP_TIMER, OPEN_PLAYER,

    // Not about music: the volume keys as shortcuts.
    FLASHLIGHT, RINGER;

    /** Actions that make sense when nothing is playing (they start playback or aren't about music). */
    val worksWhenIdle: Boolean
        get() = this == PLAY_PAUSE || this == OPEN_PLAYER || this == SLEEP_TIMER || this == FLASHLIGHT || this == RINGER

    val notMusic: Boolean get() = this == FLASHLIGHT || this == RINGER
}

/** Starting points for an app's own gesture set. */
enum class ProfilePreset {
    /** A copy of the common gestures. */
    COMMON,

    /** Video and podcasts: holds and double presses seek, long holds and triple presses switch. */
    SEEK,
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
    // Off by default: "+ then −" or three presses is also how people simply correct the volume.
    TRIPLE_UP("action_triple_up", GestureGroup.SEQUENCE, Action.NONE),
    TRIPLE_DOWN("action_triple_down", GestureGroup.SEQUENCE, Action.NONE),
    UP_DOWN("action_up_down", GestureGroup.SEQUENCE, Action.NONE),
    DOWN_UP("action_down_up", GestureGroup.SEQUENCE, Action.NONE),
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
    val resumeMinutes: Int,
    val announceSkips: Boolean,
    val cueStyle: CueStyle,
    val cueVolume: CueVolume,
    val cueScreenOffOnly: Boolean,
    val cueGroups: Set<CueGroup>,
    val ttsMultilang: Boolean,
    val cleanTitles: Boolean,
    val ttsEngine: String?,
    val ttsRate: Float,
    val seekStepSec: Int,
    val seekSmooth: Boolean,
    val likeToggle: Boolean,
    val sleepExtend: Boolean,
    val announceAllTracks: Boolean,
    val backTapEco: Boolean,
    val dismissedSuggestions: Set<String>,
    /** Apps with their own seek step → seconds. */
    val profileSeekSteps: Map<String, Int>,
    /** Apps whose sound cues are off. */
    val cuesOffApps: Set<String>,
    val actions: Map<Gesture, Action>,
    /** Apps with their own gesture set → that set. */
    val profiles: Map<String, Map<Gesture, Action>>,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("knob", Context.MODE_PRIVATE)

    // The event log changes on every volume step; kept apart so that settings observers don't wake up for it.
    private val logSp = context.getSharedPreferences("knob_log", Context.MODE_PRIVATE)

    // How long short taps last, to suggest a hold time that suits this person. Written on every tap,
    // so it lives apart from the settings too.
    private val statsSp = context.getSharedPreferences("knob_stats", Context.MODE_PRIVATE)

    /** Recent short-tap durations in ms, oldest first. */
    val tapDurations: List<Int>
        get() = statsSp.getString("taps", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    fun recordTap(ms: Int) {
        val kept = (tapDurations + ms).takeLast(TAP_SAMPLES)
        statsSp.edit().putString("taps", kept.joinToString(",")).apply()
    }

    /**
     * How long after the hold time recent holds were released. A deliberate hold is let go only after the
     * vibration is felt; one released almost at once was probably a tap that ran a little long.
     */
    val holdOvershoots: List<Int>
        get() = statsSp.getString("holds", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    fun recordHoldOvershoot(ms: Int) {
        val kept = (holdOvershoots + ms).takeLast(TAP_SAMPLES)
        statsSp.edit().putString("holds", kept.joinToString(",")).apply()
    }

    /** Volume to put back after a sleep-timer fade that didn't finish, or -1. */
    var fadeRestoreVolume: Int
        get() = statsSp.getInt("fade_restore", -1)
        set(v) = statsSp.edit().putInt("fade_restore", v).apply()

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
        get() = logSp.getString("last_event", null)
        set(v) = logSp.edit().putString("last_event", v).apply()

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
        set(v) {
            sp.edit().putInt("long_press_ms", v).apply()
            // Overshoots are measured from the old hold time and say nothing about the new one.
            statsSp.edit().remove("holds").apply()
        }

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

    /** Sleep-timer length in minutes; [SLEEP_TO_TRACK_END] pauses when the current track ends. */
    var sleepMinutes: Int
        get() = sp.getInt("sleep_minutes", 30)
        set(v) = sp.edit().putInt("sleep_minutes", v).apply()

    /**
     * For how long after a gesture paused the music a gesture assigned to play/pause can resume it,
     * in minutes; 0 turns this off. See KnobService.resumeWindowOpen.
     */
    var resumeMinutes: Int
        get() = sp.getInt("resume_minutes", 30)
        set(v) = sp.edit().putInt("resume_minutes", v).apply()

    /** With the screen off, say the new track's name after switching. */
    var announceSkips: Boolean
        get() = sp.getBoolean("announce_skips", false)
        set(v) = sp.edit().putBoolean("announce_skips", v).apply()

    /** Timbre of the sound cues after actions; OFF for none. */
    var cueStyle: CueStyle
        get() = CueStyle.entries.firstOrNull { it.name == sp.getString("cue_style", null) } ?: CueStyle.OFF
        set(v) = sp.edit().putString("cue_style", v.name).apply()

    var cueVolume: CueVolume
        get() = CueVolume.entries.firstOrNull { it.name == sp.getString("cue_volume", null) } ?: CueVolume.LOW
        set(v) = sp.edit().putString("cue_volume", v.name).apply()

    /** Which kinds of actions make a sound. All by default; the earlier "tracks only" choice carries over. */
    var cueGroups: Set<CueGroup>
        get() {
            sp.getStringSet("cue_groups", null)?.let { names -> return CueGroup.entries.filter { it.name in names }.toSet() }
            return if (sp.getString("cue_scope", null) == "TRACKS") setOf(CueGroup.TRACKS) else CueGroup.entries.toSet()
        }
        set(v) = sp.edit().putStringSet("cue_groups", v.map { it.name }.toSet()).apply()

    /** With the screen on the change is visible anyway, so by default the cue is only for the screen off. */
    var cueScreenOffOnly: Boolean
        get() = sp.getBoolean("cue_screen_off_only", true)
        set(v) = sp.edit().putBoolean("cue_screen_off_only", v).apply()

    /** Read pieces in another alphabet with a voice of their language. */
    var ttsMultilang: Boolean
        get() = sp.getBoolean("tts_multilang", true)
        set(v) = sp.edit().putBoolean("tts_multilang", v).apply()

    /** Drop "(Remastered 2011)", "[Official Video]", emoji and the like before reading a title. */
    var cleanTitles: Boolean
        get() = sp.getBoolean("clean_titles", true)
        set(v) = sp.edit().putBoolean("clean_titles", v).apply()

    /** Package of the text-to-speech engine, or null for the system default. */
    var ttsEngine: String?
        get() = sp.getString("tts_engine", null)
        set(v) = sp.edit().putString("tts_engine", v).apply()

    var ttsRate: Float
        get() = sp.getFloat("tts_rate", 1.0f)
        set(v) = sp.edit().putFloat("tts_rate", v).apply()

    /** How far one seek jumps, in seconds. */
    var seekStepSec: Int
        get() = sp.getInt("seek_step_sec", 10)
        set(v) = sp.edit().putInt("seek_step_sec", v).apply()

    /** Held seeking glides with notches felt by vibration; off = jumps every half second. */
    var seekSmooth: Boolean
        get() = sp.getBoolean("seek_smooth", true)
        set(v) = sp.edit().putBoolean("seek_smooth", v).apply()

    /** The seek step while [pkg] plays: its own if set, otherwise the common one. */
    fun seekStepMs(pkg: String?): Long {
        val own = pkg?.let { sp.getInt("seek_step_sec@$it", 0) } ?: 0
        return (if (own > 0) own else seekStepSec) * 1000L
    }

    /** 0 = use the common step. */
    fun setSeekStep(pkg: String, sec: Int) = sp.edit().putInt("seek_step_sec@$pkg", sec).apply()

    /** Sound cues are off while [pkg] plays. */
    fun cuesOff(pkg: String?): Boolean = pkg != null && sp.getBoolean("cues_off@$pkg", false)

    fun setCuesOff(pkg: String, off: Boolean) = sp.edit().putBoolean("cues_off@$pkg", off).apply()

    /** A repeated "like" removes the like. */
    var likeToggle: Boolean
        get() = sp.getBoolean("like_toggle", false)
        set(v) = sp.edit().putBoolean("like_toggle", v).apply()

    /** Repeating the sleep-timer gesture adds time instead of cancelling. */
    var sleepExtend: Boolean
        get() = sp.getBoolean("sleep_extend", true)
        set(v) = sp.edit().putBoolean("sleep_extend", v).apply()

    /** With the screen off, say the name of every new track, not only after a gesture. */
    var announceAllTracks: Boolean
        get() = sp.getBoolean("announce_all_tracks", false)
        set(v) = sp.edit().putBoolean("announce_all_tracks", v).apply()

    /** With the screen off, read the accelerometer for back taps less often (less battery, less sensitive). */
    var backTapEco: Boolean
        get() = sp.getBoolean("back_tap_eco", false)
        set(v) = sp.edit().putBoolean("back_tap_eco", v).apply()

    /** Apps for which the "make a profile" suggestion was dismissed. */
    var dismissedSuggestions: Set<String>
        get() = sp.getStringSet("dismissed_suggestions", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("dismissed_suggestions", v).apply()

    /** Gaps between presses of recent recognized sequences, ms. */
    val sequenceGaps: List<Int>
        get() = statsSp.getString("sequence_gaps", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    fun recordSequenceGaps(gaps: List<Int>) {
        if (gaps.isEmpty()) return
        val kept = (sequenceGaps + gaps).takeLast(TAP_SAMPLES)
        statsSp.edit().putString("sequence_gaps", kept.joinToString(",")).apply()
    }

    /** Players whose notification shows whether the track is liked (seen flipping after a like). */
    val likeReflectingPlayers: Set<String>
        get() = statsSp.getStringSet("like_reflecting", null)?.toSet() ?: emptySet()

    fun markLikeReflecting(pkg: String) {
        if (pkg !in likeReflectingPlayers) statsSp.edit().putStringSet("like_reflecting", likeReflectingPlayers + pkg).apply()
    }

    /** The newest release a notification was already shown for. */
    var updateNotified: String?
        get() = statsSp.getString("update_notified", null)
        set(v) = statsSp.edit().putString("update_notified", v).apply()

    /** Whether the notification permission was already asked for (it is asked once). */
    var notificationsAsked: Boolean
        get() = statsSp.getBoolean("notifications_asked", false)
        set(v) = statsSp.edit().putBoolean("notifications_asked", v).apply()

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

    // ---- Per-app gesture sets ----

    val profileApps: Set<String>
        get() = sp.getStringSet("profile_apps", null)?.toSet() ?: emptySet()

    private fun profileKey(g: Gesture, pkg: String) = "${g.key}@$pkg"

    private fun stored(key: String): Action? =
        sp.getString(key, null)?.let { stored -> Action.entries.firstOrNull { it.name == stored } ?: Action.NONE }

    /** The action for [g] while [pkg] is the player: its own set if it has one, otherwise the common one. */
    fun action(g: Gesture, pkg: String?): Action =
        if (pkg != null && pkg in profileApps) stored(profileKey(g, pkg)) ?: action(g) else action(g)

    fun setAction(g: Gesture, a: Action, pkg: String?) {
        if (pkg == null) setAction(g, a) else sp.edit().putString(profileKey(g, pkg), a.name).apply()
    }

    fun createProfile(pkg: String, preset: ProfilePreset) {
        val edit = sp.edit()
        Gesture.entries.forEach { g ->
            val a = if (preset == ProfilePreset.SEEK) SEEK_PRESET[g] ?: action(g) else action(g)
            edit.putString(profileKey(g, pkg), a.name)
        }
        edit.putStringSet("profile_apps", profileApps + pkg).apply()
    }

    fun deleteProfile(pkg: String) {
        val edit = sp.edit()
        Gesture.entries.forEach { edit.remove(profileKey(it, pkg)) }
        edit.remove("seek_step_sec@$pkg").remove("cues_off@$pkg")
        edit.putStringSet("profile_apps", profileApps - pkg).apply()
    }

    fun snapshot() = Settings(
        enabled = enabled,
        onlyWhenPlaying = onlyWhenPlaying,
        doubleMode = doubleMode,
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
        resumeMinutes = resumeMinutes,
        announceSkips = announceSkips,
        cueStyle = cueStyle,
        cueVolume = cueVolume,
        cueScreenOffOnly = cueScreenOffOnly,
        cueGroups = cueGroups,
        ttsMultilang = ttsMultilang,
        cleanTitles = cleanTitles,
        ttsEngine = ttsEngine,
        ttsRate = ttsRate,
        seekStepSec = seekStepSec,
        seekSmooth = seekSmooth,
        likeToggle = likeToggle,
        sleepExtend = sleepExtend,
        announceAllTracks = announceAllTracks,
        backTapEco = backTapEco,
        dismissedSuggestions = dismissedSuggestions,
        profileSeekSteps = profileApps.associateWith { sp.getInt("seek_step_sec@$it", 0) }.filterValues { it > 0 },
        cuesOffApps = profileApps.filter { cuesOff(it) }.toSet(),
        actions = Gesture.entries.associateWith { action(it) },
        profiles = profileApps.associateWith { pkg -> Gesture.entries.associateWith { action(it, pkg) } },
    )

    /** Returns an unregister function. The listener object must stay referenced, so it lives in the closure. */
    fun observe(onChange: () -> Unit): () -> Unit = observe(sp, onChange)

    /** Same as [observe], for the event log. */
    fun observeLog(onChange: () -> Unit): () -> Unit = observe(logSp, onChange)

    private fun observe(prefs: SharedPreferences, onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private val SEEK_PRESET = mapOf(
            Gesture.HOLD_UP to Action.SEEK_FORWARD,
            Gesture.HOLD_DOWN to Action.SEEK_BACK,
            Gesture.LONG_HOLD_UP to Action.NEXT,
            Gesture.LONG_HOLD_DOWN to Action.PREVIOUS,
            Gesture.DOUBLE_UP to Action.SEEK_FORWARD,
            Gesture.DOUBLE_DOWN to Action.SEEK_BACK,
            Gesture.TRIPLE_UP to Action.NEXT,
            Gesture.TRIPLE_DOWN to Action.PREVIOUS,
        )

        const val TAP_SAMPLES = 30
        const val SLEEP_TO_TRACK_END = -1
        const val LONG_PRESS_MIN = 250
        const val LONG_PRESS_MAX = 1500
        const val LONG_PRESS_STEP = 50
    }
}
