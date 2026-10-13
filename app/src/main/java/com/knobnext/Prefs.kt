package com.knobnext

import android.content.Context
import android.content.SharedPreferences

/** VOLUME_* on a held gesture keeps changing the volume until release; elsewhere it is one step. */
enum class Action {
    NONE, NEXT, PREVIOUS, RESTART, PLAY_PAUSE, SEEK_FORWARD, SEEK_BACK, VOLUME_UP, VOLUME_DOWN,
    ANNOUNCE, TIME_LEFT, LIKE, SHUFFLE, REPEAT, SWITCH_PLAYER, SLEEP_TIMER, OPEN_PLAYER, POCKET, VOICE, TRACK_INFO, ASK_GEMINI,

    // An artist the track story's circle named, played in the person's own player.
    PLAY_CIRCLE,

    // The round card about the playing track, on its own gesture whatever the story's delivery.
    SHOW_BUBBLE,

    // Not about music: the volume keys as shortcuts.
    FLASHLIGHT, RINGER;

    /** Actions that make sense when nothing is playing (they start playback or aren't about music). */
    val worksWhenIdle: Boolean
        get() = this == PLAY_PAUSE || this == OPEN_PLAYER || this == SLEEP_TIMER || this == FLASHLIGHT || this == RINGER ||
            // The story pauses the music if so set; the same gesture must still be able to stop it.
            this == VOICE || this == TRACK_INFO || this == PLAY_CIRCLE || this == SHOW_BUBBLE

    val notMusic: Boolean get() = this == FLASHLIGHT || this == RINGER
}

/** Starting points for an app's own gesture set. */
enum class ProfilePreset {
    /** A copy of the common gestures. */
    COMMON,

    /** Video and podcasts: holds and double presses seek, long holds and triple presses switch. */
    SEEK,
}

enum class GestureGroup { KEYS, SEQUENCE, MOTION, HEADSET }

enum class Gesture(val key: String, val group: GestureGroup, val default: Action) {
    HOLD_UP("action_hold_up", GestureGroup.KEYS, Action.NEXT),
    HOLD_DOWN("action_hold_down", GestureGroup.KEYS, Action.PREVIOUS),
    LONG_HOLD_UP("action_long_hold_up", GestureGroup.KEYS, Action.NONE),
    LONG_HOLD_DOWN("action_long_hold_down", GestureGroup.KEYS, Action.NONE),
    TAP_HOLD_UP("action_tap_hold_up", GestureGroup.KEYS, Action.VOLUME_UP),
    TAP_HOLD_DOWN("action_tap_hold_down", GestureGroup.KEYS, Action.VOLUME_DOWN),
    BOTH("action_both", GestureGroup.KEYS, Action.PLAY_PAUSE),
    BOTH_HOLD("action_both_hold", GestureGroup.KEYS, Action.LIKE),
    // Off by default: the system's own double press (camera) has to be turned off first.
    POWER_DOUBLE("action_power_double", GestureGroup.KEYS, Action.NONE),
    // Works only once KnobNext is chosen as the digital assistant app.
    POWER_HOLD("action_power_hold", GestureGroup.KEYS, Action.LIKE),
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

    // Pixel's own Quick Tap (a double tap on the back), set to "play/pause media": KnobNext catches that
    // key and runs this instead. Off until turned on and assigned.
    PIXEL_TAP("action_pixel_tap", GestureGroup.MOTION, Action.NONE),

    // Buttons of headphones and speakers. What they send is up to the device: a tap, two or three taps
    // usually arrive as play/pause, next and previous. Each is the same as before until reassigned.
    HS_PLAY_PAUSE("action_hs_play_pause", GestureGroup.HEADSET, Action.PLAY_PAUSE),
    HS_NEXT("action_hs_next", GestureGroup.HEADSET, Action.NEXT),
    HS_PREVIOUS("action_hs_previous", GestureGroup.HEADSET, Action.PREVIOUS),
    HS_FORWARD("action_hs_forward", GestureGroup.HEADSET, Action.SEEK_FORWARD),
    HS_REWIND("action_hs_rewind", GestureGroup.HEADSET, Action.SEEK_BACK),

    // For devices that send every tap as a plain play/pause: KnobNext counts the presses itself.
    HS_PLAY_PAUSE_DOUBLE("action_hs_play_pause_double", GestureGroup.HEADSET, Action.NONE),
    HS_PLAY_PAUSE_TRIPLE("action_hs_play_pause_triple", GestureGroup.HEADSET, Action.NONE),
    HS_PLAY_PAUSE_HOLD("action_hs_play_pause_hold", GestureGroup.HEADSET, Action.NONE),

    // The headset's "voice assistant" button; works once it is passed to KnobNext (a setting).
    HS_ASSIST("action_hs_assist", GestureGroup.HEADSET, Action.NONE),
}

/**
 * What the finger does on the headphones. KnobNext only sees the command that arrives; which gesture
 * sends it is set in the headphones' own app, so the person tells it once, per headphones.
 */
enum class HeadsetMotion { TAP, DOUBLE_TAP, TRIPLE_TAP, HOLD, DOUBLE_TAP_HOLD, SWIPE_FORWARD, SWIPE_BACK, BUTTON }

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
    val scrubDuck: Boolean,
    val scrubSpeak: Boolean,
    val likeToggle: Boolean,
    val sleepExtend: Boolean,
    val announceAllTracks: Boolean,
    val backTapEco: Boolean,
    val backTapScreenOff: Boolean,
    val pocketAuto: Boolean,
    val powerHoldFallback: Boolean,
    val headsetButtons: Boolean,
    val pixelTap: Boolean,
    val headsetHold: Boolean,
    /** The headphones connected last, and the gestures their commands were named after there. */
    val headsetDevice: String?,
    val headsetMotions: Map<Gesture, HeadsetMotion>,
    val smartVoice: Boolean,
    val blindGestures: Boolean,
    val infoLength: InfoLength,
    val infoPause: Boolean,
    val infoDelivery: InfoDelivery,
    val bubbleAuto: BubbleAuto,
    val bubbleWhere: BubbleWhere,
    val bubbleSide: BubbleSide,
    val infoLanguage: InfoLanguage,
    val infoSource: InfoSource,
    val cloudService: CloudService,
    /** A key is set for the cloud model's service. */
    val cloudReady: Boolean,
    val keysOnPause: Boolean,
    val infoHistory: Boolean,
    val journalVersion: Int,
    val smartTitles: Boolean,
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
    /**
     * KnobNext remembers its stories: who it has introduced (the introduction is said once) and the
     * history of acquaintances. Off: nothing is written, and every story starts with the introduction.
     */
    var infoHistory: Boolean
        get() = sp.getBoolean("info_history", true)
        set(v) = sp.edit().putBoolean("info_history", v).apply()

    /**
     * The person's own Last.fm key, for the track story. Empty: the app's own key, if the build has one.
     */
    var lastFmKey: String
        get() = sp.getString("lastfm_key", null).orEmpty()
        set(v) = sp.edit().putString("lastfm_key", v.trim()).apply()

    val lastFmKeyInUse: String get() = lastFmKey.ifBlank { BuildConfig.LASTFM_KEY }

    /** The cloud model's service, and per service the person's key and the model (blank: its usual one). */
    var cloudService: CloudService
        get() = CloudService.entries.firstOrNull { it.name == sp.getString("cloud_service", null) } ?: CloudService.GEMINI
        set(v) = sp.edit().putString("cloud_service", v.name).apply()

    fun cloudKey(service: CloudService): String = sp.getString("cloud_key_" + service.name, null).orEmpty()

    fun setCloudKey(service: CloudService, key: String) = sp.edit().putString("cloud_key_" + service.name, key.trim()).apply()

    fun cloudModel(service: CloudService): String = sp.getString("cloud_model_" + service.name, null).orEmpty()

    fun setCloudModel(service: CloudService, model: String) = sp.edit().putString("cloud_model_" + service.name, model.trim()).apply()

    /**
     * The artists the last "widening the circle" note named, for [Action.PLAY_CIRCLE]; [circlePick]
     * counts the plays, so each takes the next one.
     */
    var circleNames: List<String>
        get() = sp.getString("circle_names", null)?.split('\u0000')?.filter { it.isNotBlank() }.orEmpty()
        set(v) = sp.edit().putString("circle_names", v.joinToString("\u0000")).putInt("circle_pick", 0)
            .putLong("circle_at", System.currentTimeMillis()).apply()

    /** When [circleNames] were named: an hour on, they are no longer what the person just heard. */
    val circleAt: Long get() = sp.getLong("circle_at", 0L)

    var circlePick: Int
        get() = sp.getInt("circle_pick", 0)
        set(v) = sp.edit().putInt("circle_pick", v).apply()

    /** Players that take a search through their session and do nothing (Spotify): opened instead. */
    var searchDeaf: Set<String>
        get() = sp.getStringSet("search_deaf", null).orEmpty().toSet()
        set(v) = sp.edit().putStringSet("search_deaf", v).apply()

    /** Last.fm refused the app's usual signature and took a plain one: the stories sign that way. */
    var lastFmPlainAgent: Boolean
        get() = sp.getBoolean("lastfm_plain_agent", false)
        set(v) = sp.edit().putBoolean("lastfm_plain_agent", v).apply()

    /** Bumped on every change of the history of acquaintances, so the screen reads it again. */
    var journalVersion: Int
        get() = sp.getInt("journal_version", 0)
        set(v) = sp.edit().putInt("journal_version", v).apply()

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
        get() = sp.getBoolean("tts_multilang", false)
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

    /** Smooth seeking turns the music down while it runs, so the chopped sound isn't harsh. */
    var scrubDuck: Boolean
        get() = sp.getBoolean("scrub_duck", true)
        set(v) = sp.edit().putBoolean("scrub_duck", v).apply()

    /** After smooth seeking, say where it stopped. */
    var scrubSpeak: Boolean
        get() = sp.getBoolean("scrub_speak", false)
        set(v) = sp.edit().putBoolean("scrub_speak", v).apply()

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

    /** Back taps only with the screen off: with it on, Pixel's own Quick Tap does the job. */
    var backTapScreenOff: Boolean
        get() = sp.getBoolean("back_tap_screen_off", false)
        set(v) = sp.edit().putBoolean("back_tap_screen_off", v).apply()

    /** While music plays, the power button turns on pocket mode first; a second press turns the screen off. */
    var pocketAuto: Boolean
        get() = sp.getBoolean("pocket_auto", false)
        set(v) = sp.edit().putBoolean("pocket_auto", v).apply()

    /** Holding power with nothing to do opens Gemini (or the Google assistant), as it would without us. */
    /**
     * Experimental: Pixel's Quick Tap, set to "play/pause media", sends that key; without headphones
     * connected KnobNext takes it as the "Pixel Quick Tap" gesture.
     */
    var pixelTap: Boolean
        get() = sp.getBoolean("pixel_tap", false)
        set(v) = sp.edit().putBoolean("pixel_tap", v).apply()

    /** Buttons of Bluetooth and wired headphones go through KnobNext, so they can be reassigned. */
    var headsetButtons: Boolean
        get() = sp.getBoolean("headset_buttons", false)
        set(v) = sp.edit().putBoolean("headset_buttons", v).apply()

    /** The headset's assistant button opens KnobNext's action instead of the assistant. */
    var headsetHold: Boolean
        get() = sp.getBoolean("headset_hold", false)
        set(v) = sp.edit().putBoolean("headset_hold", v).apply()

    /** The headphones whose buttons came through KnobNext last. */
    var lastHeadset: String?
        get() = sp.getString("last_headset", null)
        set(v) = sp.edit().putString("last_headset", v).apply()

    /** Which gesture on [device] sends the command behind [g], as the person said; null: not said. */
    fun headsetMotion(device: String, g: Gesture): HeadsetMotion? =
        sp.getString("hs_motion@$device@${g.key}", null)?.let { name -> HeadsetMotion.entries.firstOrNull { it.name == name } }

    fun setHeadsetMotion(device: String, g: Gesture, m: HeadsetMotion?) {
        val key = "hs_motion@$device@${g.key}"
        if (m == null) sp.edit().remove(key).apply() else sp.edit().putString(key, m.name).apply()
    }

    var powerHoldFallback: Boolean
        get() = sp.getBoolean("power_hold_fallback", true)
        set(v) = sp.edit().putBoolean("power_hold_fallback", v).apply()

    /** The player a gesture paused last: play goes back to it (not a stored setting, just memory). */
    var pausedPlayer: String?
        get() = sp.getString("paused_player", null)
        set(v) = sp.edit().putString("paused_player", v).apply()

    /**
     * With the screen off: a held key (system-repeated steps) and both keys together. Off by default:
     * Bluetooth headphones can echo a volume step back, which looks just like both keys pressed.
     */
    var blindGestures: Boolean
        get() = sp.getBoolean("blind_gestures", false)
        set(v) = sp.edit().putBoolean("blind_gestures", v).apply()

    /** How long the spoken story about a track is. */
    var infoLength: InfoLength
        get() = InfoLength.entries.firstOrNull { it.name == sp.getString("info_length", null) } ?: InfoLength.NORMAL
        set(v) = sp.edit().putString("info_length", v.name).apply()

    /** Where the story about a track comes from. */
    var infoSource: InfoSource
        get() = InfoSource.entries.firstOrNull { it.name == sp.getString("info_source", null) } ?: InfoSource.GEMINI
        set(v) = sp.edit().putString("info_source", v.name).apply()

    /** The language of the story about a track. */
    var infoLanguage: InfoLanguage
        get() = InfoLanguage.entries.firstOrNull { it.name == sp.getString("info_language", null) } ?: InfoLanguage.RU
        set(v) = sp.edit().putString("info_language", v.name).apply()

    /** Whether the story is spoken, shown in the round card on screen, or both. */
    var infoDelivery: InfoDelivery
        get() = InfoDelivery.entries.firstOrNull { it.name == sp.getString("info_delivery", null) } ?: InfoDelivery.VOICE
        set(v) = sp.edit().putString("info_delivery", v.name).apply()

    /** When the round cover appears by itself as a new track starts (it was once a switch: on is "every"). */
    var bubbleAuto: BubbleAuto
        get() = BubbleAuto.entries.firstOrNull { it.name == sp.getString("bubble_auto", null) }
            ?: if (sp.getBoolean("bubble_on_track", false)) BubbleAuto.EVERY else BubbleAuto.OFF
        set(v) = sp.edit().putString("bubble_auto", v.name).apply()

    /** Where it appears by itself: anywhere, or only over the lock screen. */
    var bubbleWhere: BubbleWhere
        get() = BubbleWhere.entries.firstOrNull { it.name == sp.getString("bubble_where", null) } ?: BubbleWhere.ANYWHERE
        set(v) = sp.edit().putString("bubble_where", v.name).apply()

    /** The edge of the screen it sits at. */
    var bubbleSide: BubbleSide
        get() = BubbleSide.entries.firstOrNull { it.name == sp.getString("bubble_side", null) } ?: BubbleSide.RIGHT
        set(v) = sp.edit().putString("bubble_side", v.name).apply()

    /** Where the round cover was dragged to, from the top of the screen; -1 before it ever was. */
    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    /** Pause the music for the story about a track (otherwise it only gets quieter). */
    var infoPause: Boolean
        get() = sp.getBoolean("info_pause", false)
        set(v) = sp.edit().putBoolean("info_pause", v).apply()

    /** After a gesture pause, play silence for the resume window so the volume keys keep working. */
    var keysOnPause: Boolean
        get() = sp.getBoolean("keys_on_pause", true)
        set(v) = sp.edit().putBoolean("keys_on_pause", v).apply()

    /** Phrases the rules don't know go to the on-phone model, when there is one. */
    var smartVoice: Boolean
        get() = sp.getBoolean("smart_voice", true)
        set(v) = sp.edit().putBoolean("smart_voice", v).apply()

    /** Track names are tidied for reading aloud by the on-phone model, when there is one. */
    var smartTitles: Boolean
        get() = sp.getBoolean("smart_titles", true)
        set(v) = sp.edit().putBoolean("smart_titles", v).apply()

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

    /** Takes [packageName] out of everything that remembers players (KnobNext's own session was once taken for one). */
    fun forgetPlayer(packageName: String) {
        val edit = sp.edit()
        seenPlayers.takeIf { packageName in it }?.let { edit.putStringSet("seen_players", it - packageName) }
        if (lastPlayer == packageName) edit.remove("last_player")
        if (pausedPlayer == packageName) edit.remove("paused_player")
        edit.apply()
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

    /** One-time fixes of settings saved by older versions. */
    fun migrate() {
        val level = sp.getInt("migrated", 0)
        if (level >= 2) return
        val edit = sp.edit()
        // One voice for a whole phrase sounds more natural than switching voices mid-sentence.
        if (level < 2) edit.putBoolean("tts_multilang", false)
        if (level >= 1) {
            edit.putInt("migrated", 2).apply()
            return
        }
        // Profiles made from the first "seek" preset had next/previous on the long hold, which a seeking
        // hold makes unreachable (and which used to stop the hold from seeking smoothly).
        profileApps.forEach { pkg ->
            listOf(
                Gesture.HOLD_UP to Gesture.LONG_HOLD_UP,
                Gesture.HOLD_DOWN to Gesture.LONG_HOLD_DOWN,
            ).forEach { (hold, long) ->
                val holdAction = action(hold, pkg)
                val longAction = action(long, pkg)
                val seeks = holdAction == Action.SEEK_FORWARD || holdAction == Action.SEEK_BACK
                if (seeks && (longAction == Action.NEXT || longAction == Action.PREVIOUS)) {
                    edit.putString(profileKey(long, pkg), Action.NONE.name)
                }
            }
        }
        edit.putInt("migrated", 2).apply()
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
        scrubDuck = scrubDuck,
        scrubSpeak = scrubSpeak,
        likeToggle = likeToggle,
        sleepExtend = sleepExtend,
        announceAllTracks = announceAllTracks,
        backTapEco = backTapEco,
        backTapScreenOff = backTapScreenOff,
        pocketAuto = pocketAuto,
        powerHoldFallback = powerHoldFallback,
        headsetButtons = headsetButtons,
        pixelTap = pixelTap,
        headsetHold = headsetHold,
        headsetDevice = lastHeadset,
        headsetMotions = lastHeadset?.let { device ->
            Gesture.entries.filter { it.group == GestureGroup.HEADSET }
                .mapNotNull { g -> headsetMotion(device, g)?.let { g to it } }.toMap()
        }.orEmpty(),
        smartVoice = smartVoice,
        blindGestures = blindGestures,
        infoLength = infoLength,
        infoPause = infoPause,
        infoDelivery = infoDelivery,
        bubbleAuto = bubbleAuto,
        bubbleWhere = bubbleWhere,
        bubbleSide = bubbleSide,
        infoLanguage = infoLanguage,
        infoSource = infoSource,
        cloudService = cloudService,
        cloudReady = cloudKey(cloudService).isNotBlank(),
        keysOnPause = keysOnPause,
        infoHistory = infoHistory,
        journalVersion = journalVersion,
        smartTitles = smartTitles,
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
            // Holding seeks for as long as it is held, so a long hold would never be reached.
            Gesture.LONG_HOLD_UP to Action.NONE,
            Gesture.LONG_HOLD_DOWN to Action.NONE,
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
