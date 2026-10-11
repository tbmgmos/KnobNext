package com.knobnext

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What an action did: [message] goes to the event log, [result] picks the sound cue. */
class Outcome(val message: String?, val result: Result) {
    enum class Result { DONE, UNCHANGED, UNDONE, FAILED }

    companion object {
        val DONE = Outcome(null, Result.DONE)

        fun done(message: String) = Outcome(message, Result.DONE)

        /** Nothing needed doing (already liked, no other player) or it was undone (timer cancelled). */
        fun unchanged(message: String) = Outcome(message, Result.UNCHANGED)

        fun failed(message: String) = Outcome(message, Result.FAILED)

        /** The opposite happened on purpose: a like removed, a timer cancelled. */
        fun undone(message: String) = Outcome(message, Result.UNDONE)

        /** For actions whose only message is why they failed. */
        fun of(problem: String?) = if (problem == null) DONE else failed(problem)
    }
}

/**
 * Actions that need more than transport controls: speech, alarms, launching apps, player-specific
 * commands. Each returns a short message for the event log when it could not do what was asked.
 */
class Extras(private val context: Context, private val media: Media, private val prefs: Prefs) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val speaker = Speaker(context, prefs)
    private val scope = kotlinx.coroutines.MainScope()

    // Model-cleaned names, so a track is sent to the model once.
    private val smartTitles = object : LinkedHashMap<String, Pair<String, String?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String?>>?) = size > SMART_TITLE_CACHE
    }

    // Players don't report shuffle/repeat state through the framework API, so we remember what we set.
    private val shuffleOn = HashMap<String, Boolean>()
    private val repeatMode = HashMap<String, Int>()

    private val torch = Torch(context, handler)

    /** Called when a player that normally shows a liked track didn't, a moment after "like". */
    var onLikeNotConfirmed: ((pkg: String) -> Unit)? = null

    fun perform(action: Action, controller: MediaController?): Outcome = when (action) {
        Action.ANNOUNCE -> Outcome.of(announce(controller))
        Action.LIKE -> like(controller)
        Action.SHUFFLE -> Outcome.of(shuffle(controller))
        Action.REPEAT -> Outcome.of(repeat(controller))
        Action.SLEEP_TIMER -> toggleSleepTimer(controller)
        Action.TIME_LEFT -> timeLeft(controller)
        Action.OPEN_PLAYER -> Outcome.of(openPlayer(controller))
        Action.RESTART -> Outcome.of(restart(controller))
        Action.SWITCH_PLAYER -> switchPlayer(controller)
        Action.FLASHLIGHT -> Outcome.of(torch.toggle())
        Action.RINGER -> toggleRinger()
        Action.ASK_GEMINI -> Outcome.of(askAbout(controller))
        Action.TRACK_INFO -> trackInfo(controller)
        else -> Outcome.DONE
    }

    /** Speaks [text] the same way as the announcements. */
    fun say(text: String) = speaker.say(text)

    fun release() {
        scope.cancel()
        speaker.release()
        torch.release()
    }

    /** Back to the start of the track; players that can't seek usually restart on "previous". */
    private fun restart(controller: MediaController?): String? {
        val c = controller ?: return context.getString(R.string.log_needs_access)
        if (c.playbackState?.actions?.and(PlaybackState.ACTION_SEEK_TO) != 0L) {
            c.transportControls.seekTo(0)
        } else {
            c.transportControls.skipToPrevious()
        }
        return null
    }

    /** Pauses this player and resumes the next one that has a session, in a stable order. */
    private fun switchPlayer(controller: MediaController?): Outcome {
        val filtering = prefs.appFilter
        val players = media.controllers()
            .filter { !filtering || it.packageName in prefs.allowedApps }
            .distinctBy { it.packageName }
            .sortedBy { it.packageName }
        if (players.isEmpty()) return Outcome.failed(context.getString(R.string.log_needs_access))
        val current = players.indexOfFirst { it.packageName == controller?.packageName }
        val next = players[(current + 1) % players.size]
        if (next.packageName == controller?.packageName) return Outcome.unchanged(context.getString(R.string.log_no_other_player))
        controller?.transportControls?.pause()
        next.transportControls.play()
        prefs.lastPlayer = next.packageName
        return Outcome.done(context.getString(R.string.log_switched_to, appLabel(context, next.packageName)))
    }

    /** Sound ↔ vibrate. Leaving "silent" may need Do Not Disturb access, which the app doesn't ask for. */
    private fun toggleRinger(): Outcome =
        try {
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                Outcome.done(context.getString(R.string.log_ringer_vibrate))
            } else {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                Outcome.done(context.getString(R.string.log_ringer_normal))
            }
        } catch (_: SecurityException) {
            Outcome.failed(context.getString(R.string.log_ringer_dnd))
        }

    private fun announce(controller: MediaController?): String? {
        val meta = controller?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        if (title == null) {
            speaker.say(context.getString(R.string.speak_unknown))
            return context.getString(R.string.log_no_metadata)
        }
        if (!prefs.smartTitles || !prefs.cleanTitles) {
            speaker.say(Speech.announcement(title, artist, prefs.cleanTitles))
            return null
        }
        // A model, when the phone has one, catches what the rules miss; waiting is capped so the
        // announcement never lags much behind the gesture.
        val key = "$title\u0000$artist"
        smartTitles[key]?.let { (t, a) ->
            speaker.say(Speech.announcement(t, a, clean = true))
            return null
        }
        scope.launch {
            val cleaned = kotlinx.coroutines.withTimeoutOrNull(SMART_TITLE_WAIT_MS) {
                Brains.ask(context, TitleBrain.prompt(title, artist), SMART_TITLE_TOKENS)?.second?.let(TitleBrain::parse)
            }
            if (cleaned != null) smartTitles[key] = cleaned
            val (t, a) = cleaned ?: (title to artist)
            speaker.say(Speech.announcement(t, a, clean = true))
        }
        return null
    }

    private fun isLike(text: String) = LIKE.any { it.containsMatchIn(text) } && UNLIKE.none { it.containsMatchIn(text) }
    private fun isUnlike(text: String) = LIKE.any { it.containsMatchIn(text) } && UNLIKE.any { it.containsMatchIn(text) }

    /** "Remove from Liked Songs" — not "dislike", which is a different button. */
    private fun isRemoveLike(text: String) = LIKE.any { it.containsMatchIn(text) } && REMOVE.any { it.containsMatchIn(text) }

    /**
     * A moment after pressing "like", look at the player's notification again. Players that show the
     * liked state change their buttons; once a player has been seen doing that, unchanged buttons mean
     * the like didn't go through. Players that never change them are never reported.
     */
    private fun verifyLike(pkg: String, before: List<String>) {
        handler.postDelayed({
            val after = notificationButtons(pkg).map { it.first }
            when {
                after.isEmpty() -> Unit
                after != before -> prefs.markLikeReflecting(pkg)
                pkg in prefs.likeReflectingPlayers -> onLikeNotConfirmed?.invoke(pkg)
            }
        }, LIKE_CHECK_MS)
    }

    /** Speaks how much of the track is left. */
    private fun timeLeft(controller: MediaController?): Outcome {
        val (position, duration) = media.progress(controller)
            ?: return Outcome.failed(context.getString(R.string.log_no_duration))
        val left = ((duration - position) / 1000).toInt()
        val minutes = left / 60
        val seconds = left % 60
        val res = context.resources
        val phrase = buildString {
            append(context.getString(R.string.speak_time_left))
            if (minutes > 0) append(' ').append(res.getQuantityString(R.plurals.minutes, minutes, minutes))
            if (seconds > 0 || minutes == 0) append(' ').append(res.getQuantityString(R.plurals.seconds, seconds, seconds))
        }
        speaker.say(phrase)
        return Outcome.done(context.getString(R.string.log_time_left, minutes, seconds))
    }

    /** Buttons of the player's own notification, as (lower-cased title, action). */
    private fun notificationButtons(pkg: String) =
        KnobNotificationListener.instance?.playerActions(pkg).orEmpty()
            .map { (it.title?.toString().orEmpty().lowercase()) to it }

    private fun press(action: android.app.Notification.Action): Boolean =
        try {
            action.actionIntent?.send()
            action.actionIntent != null
        } catch (_: Exception) {
            false
        }

    /**
     * Like the current track. Tried in order:
     *  1. the heart / thumbs-up button of the player's notification — the same button a user taps,
     *     and the only way Spotify accepts it from another app;
     *  2. the session's custom action (Spotify "ADD_TO_COLLECTION", thumbs-up, heart, favourite…);
     *  3. the rating API.
     */
    private fun like(controller: MediaController?): Outcome {
        val c = controller ?: return Outcome.failed(context.getString(R.string.log_needs_access))
        val pkg = c.packageName
        val buttons = notificationButtons(pkg)
        buttons.firstOrNull { (title, _) -> isLike(title) }?.let { (_, action) ->
            if (press(action)) {
                verifyLike(pkg, buttons.map { it.first })
                return Outcome.DONE
            }
        }
        // "Remove from liked" is there: the track is liked already; with the toggle on, unlike it.
        buttons.firstOrNull { (title, _) -> isRemoveLike(title) }?.let { (_, action) ->
            if (prefs.likeToggle && press(action)) return Outcome.undone(context.getString(R.string.log_unliked))
            return Outcome.unchanged(context.getString(R.string.log_already_liked))
        }
        if (buttons.any { (title, _) -> isUnlike(title) }) return Outcome.unchanged(context.getString(R.string.log_already_liked))

        val custom = c.playbackState?.customActions.orEmpty()
        fun text(a: PlaybackState.CustomAction) = "${a.action} ${a.name}".lowercase()
        custom.firstOrNull { isLike(text(it)) }?.let {
            c.transportControls.sendCustomAction(it, null)
            return Outcome.DONE
        }
        if (prefs.likeToggle) {
            custom.firstOrNull { isRemoveLike(text(it)) }?.let {
                c.transportControls.sendCustomAction(it, null)
                return Outcome.undone(context.getString(R.string.log_unliked))
            }
        }
        when (c.ratingType) {
            Rating.RATING_THUMB_UP_DOWN -> c.transportControls.setRating(Rating.newThumbRating(true))
            Rating.RATING_HEART -> c.transportControls.setRating(Rating.newHeartRating(true))
            else -> {
                if (custom.any { isUnlike(text(it)) }) return Outcome.unchanged(context.getString(R.string.log_already_liked))
                val offered = (buttons.map { it.first } + custom.map { it.name.toString() })
                    .filter { it.isNotBlank() }.distinct().joinToString().ifEmpty { "—" }
                return Outcome.failed(context.getString(R.string.log_unsupported_actions, offered))
            }
        }
        return Outcome.DONE
    }

    /** Notification button first, then the session's custom action, then the generic compat command. */
    private fun toggleMode(c: MediaController, words: List<Regex>): Boolean {
        notificationButtons(c.packageName).firstOrNull { (title, _) -> words.any { it.containsMatchIn(title) } }
            ?.let { (_, action) -> if (press(action)) return true }
        c.playbackState?.customActions.orEmpty()
            .firstOrNull { a -> words.any { it.containsMatchIn("${a.action} ${a.name}".lowercase()) } }
            ?.let {
                c.transportControls.sendCustomAction(it, null)
                return true
            }
        return false
    }

    private fun shuffle(controller: MediaController?): String? {
        val c = controller ?: return context.getString(R.string.log_needs_access)
        if (toggleMode(c, SHUFFLE)) return null
        val on = !(shuffleOn[c.packageName] ?: false)
        shuffleOn[c.packageName] = on
        c.transportControls.sendCustomAction(
            COMPAT_SET_SHUFFLE,
            Bundle().apply { putInt(COMPAT_SHUFFLE_ARG, if (on) 1 else 0) },
        )
        return null
    }

    private fun repeat(controller: MediaController?): String? {
        val c = controller ?: return context.getString(R.string.log_needs_access)
        if (toggleMode(c, REPEAT)) return null
        // none → all → one → none
        val next = when (repeatMode[c.packageName] ?: 0) {
            0 -> 2
            2 -> 1
            else -> 0
        }
        repeatMode[c.packageName] = next
        c.transportControls.sendCustomAction(COMPAT_SET_REPEAT, Bundle().apply { putInt(COMPAT_REPEAT_ARG, next) })
        return null
    }

    private fun toggleSleepTimer(controller: MediaController?): Outcome {
        if (SleepTimer.active(prefs)) {
            if (prefs.sleepExtend) {
                SleepTimer.extend(context, prefs, SleepTimer.EXTEND_MINUTES)
                speaker.say(context.getString(R.string.speak_sleep_extended, SleepTimer.EXTEND_MINUTES))
                return Outcome.done(context.getString(R.string.log_sleep_extended, SleepTimer.EXTEND_MINUTES, clock(prefs.sleepUntil)))
            }
            SleepTimer.cancel(context, prefs)
            speaker.say(context.getString(R.string.speak_sleep_cancelled))
            return Outcome.undone(context.getString(R.string.log_sleep_cancelled))
        }
        val minutes = prefs.sleepMinutes
        if (minutes == Prefs.SLEEP_TO_TRACK_END) {
            val progress = media.progress(controller)
            if (progress != null) {
                SleepTimer.startIn(context, prefs, progress.second - progress.first)
                speaker.say(context.getString(R.string.speak_sleep_track_end))
                return Outcome.done(context.getString(R.string.log_sleep_track_end, clock(prefs.sleepUntil)))
            }
            // The player doesn't say how long the track is: fall back to a fixed time.
            SleepTimer.start(context, prefs, SleepTimer.FALLBACK_MINUTES)
            speaker.say(context.getString(R.string.speak_sleep_set, SleepTimer.FALLBACK_MINUTES))
            return Outcome.done(context.getString(R.string.log_sleep_no_length, SleepTimer.FALLBACK_MINUTES))
        }
        SleepTimer.start(context, prefs, minutes)
        speaker.say(context.getString(R.string.speak_sleep_set, minutes))
        return Outcome.done(context.getString(R.string.log_sleep_set, minutes))
    }

    private fun clock(wallMs: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(wallMs))

    private fun openPlayer(controller: MediaController?): String? =
        openApp(controller?.packageName ?: prefs.lastPlayer ?: return context.getString(R.string.log_no_player))

    private var infoJob: kotlinx.coroutines.Job? = null

    /**
     * Set by the service: keeps the volume keys working while the music is paused for a story (with
     * the screen off Android ignores them once nothing plays), so the same gesture can stop it.
     */
    var keepKeysAlive: ((Boolean) -> Unit)? = null

    /** Set by the service: a line for the event log. */
    var log: ((String) -> Unit)? = null

    /** Ends the story under way (gives the music back); null when none is. */
    private var storyEnd: (() -> Unit)? = null

    /**
     * A few spoken sentences about the playing track, in the background: no app opens, the music only
     * gets quieter (or pauses, if so set). The same gesture again stops it.
     */
    private fun trackInfo(controller: MediaController?): Outcome {
        if (infoJob?.isActive == true || speaker.speaking || storyEnd != null) {
            infoJob?.cancel()
            speaker.stop()
            // Stopped at any point (even during the opening line): the music comes back.
            storyEnd?.invoke()
            return Outcome.undone(context.getString(R.string.log_info_stopped))
        }
        val c = controller ?: return Outcome.failed(context.getString(R.string.log_no_track))
        val meta = c.metadata ?: return Outcome.failed(context.getString(R.string.log_no_track))
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return Outcome.failed(context.getString(R.string.log_no_track))
        val track = TrackInfo.Track(
            title = title,
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
            year = meta.getLong(MediaMetadata.METADATA_KEY_YEAR).takeIf { it > 0 }?.toString(),
        )
        val lang = prefs.infoLanguage.resolve()
        val length = prefs.infoLength
        val source = prefs.infoSource
        val pause = prefs.infoPause && c.isPlaying()
        var paused = false
        var ended = false
        val pauseMusic = {
            if (pause && !paused) {
                paused = true
                c.transportControls.pause()
            }
        }
        // Runs once, whichever way the story ends: told, failed, or stopped by the gesture.
        val resume = {
            if (!ended) {
                ended = true
                storyEnd = null
                if (pause) keepKeysAlive?.invoke(false)
                if (paused) c.transportControls.play()
            }
        }
        storyEnd = resume
        if (pause) keepKeysAlive?.invoke(true)
        // The opening line at once, from the player's own data; the story follows when it is ready.
        pauseMusic()
        speaker.say(TrackInfo.header(context, track, lang))
        infoJob = scope.launch {
            val told = TrackInfo.story(context, track, length, lang, source) { log?.invoke(it) }
            if (told == null) {
                speaker.say(context.getString(R.string.speak_info_none), resume, queue = true)
                return@launch
            }
            speaker.say(told, resume, queue = true)
        }
        return Outcome.done(context.getString(R.string.log_info_started))
    }

    /**
     * Opens Gemini with a question about the playing track (who, the story of the song, similar
     * music); without Gemini, a Google search for it.
     */
    private fun askAbout(controller: MediaController?): String? {
        val meta = controller?.metadata ?: return context.getString(R.string.log_no_track)
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return context.getString(R.string.log_no_track)
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val track = if (artist.isNullOrBlank()) "«${Speech.cleanTitle(title)}»" else "«${Speech.cleanTitle(title)}» — ${Speech.cleanArtist(artist)}"
        val question = context.getString(R.string.gemini_question, track)
        val tries = listOf(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, question).setPackage(GEMINI),
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, question).setPackage(GOOGLE),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(question))),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return null
            } catch (_: Exception) {
            }
        }
        return context.getString(R.string.log_no_gemini)
    }

    /** Opens a player and starts it playing; a message when that failed. */
    fun openApp(pkg: String): String? {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return context.getString(R.string.log_no_player)
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Give the player a moment to come up, then resume if it isn't playing on its own.
            handler.postDelayed({ if (!audio.isMusicActive) media.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, OPEN_PLAY_DELAY_MS)
            null
        } catch (_: Exception) {
            context.getString(R.string.log_no_player)
        }
    }

    companion object {
        private const val SMART_TITLE_CACHE = 50
        private const val SMART_TITLE_WAIT_MS = 2_500L
        private const val SMART_TITLE_TOKENS = 64
        private const val GEMINI = "com.google.android.apps.bard"
        private const val GOOGLE = "com.google.android.googlequicksearchbox"
        private val LIKE = listOf(
            Regex("like"), Regex("thumbs?_?up"), Regex("thumb up"), Regex("favou?rite"), Regex("heart"), Regex("love"),
            Regex("collection"), Regex("library"), Regex("save"), Regex("нрав"), Regex("любим"), Regex("избранн"),
            Regex("медиатек"), Regex("сохран"),
        )
        /** Words of a "remove from liked" button. */
        private val REMOVE = listOf(Regex("remove"), Regex("unlike"), Regex("удал"), Regex("убрать"))
        private const val LIKE_CHECK_MS = 1_500L
        private val SHUFFLE = listOf(Regex("shuffle"), Regex("перемеш"), Regex("случайн"))
        private val REPEAT = listOf(Regex("repeat"), Regex("повтор"))

        /** "Remove from liked", "dislike", "Не нравится" — but not "мне нравится". */
        private val UNLIKE = listOf(
            Regex("dislike"), Regex("unlike"), Regex("remove"), Regex("thumbs?_?down"), Regex("thumb down"),
            Regex("(^|[^а-яё])не нрав"), Regex("удал"), Regex("убрать"),
            // "Save to playlist" / "add to queue" are not likes.
            Regex("playlist"), Regex("плейлист"), Regex("queue"), Regex("очеред"),
        )
        private const val COMPAT_SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
        private const val COMPAT_SHUFFLE_ARG = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
        private const val COMPAT_SET_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE"
        private const val COMPAT_REPEAT_ARG = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"
        private const val OPEN_PLAY_DELAY_MS = 1_500L
    }
}

/** The sleep timer's alarm, shared by the gesture and the "cancel" button in settings. */
object SleepTimer {
    fun active(prefs: Prefs) = prefs.sleepUntil > System.currentTimeMillis()

    /** The music fades out over this long before the pause. */
    const val FADE_MS = 30_000L

    /** Called when the timer fires; the service sets it to fade out instead of cutting off. */
    @Volatile
    var fader: (() -> Unit)? = null

    /** Added by a repeated gesture (or a shake while the music fades out). */
    const val EXTEND_MINUTES = 15

    /** "Until the end of the track" when the player doesn't tell the track's length. */
    const val FALLBACK_MINUTES = 30

    fun start(context: Context, prefs: Prefs, minutes: Int) = startIn(context, prefs, minutes * 60_000L)

    fun startIn(context: Context, prefs: Prefs, delayMs: Long) {
        val delay = delayMs.coerceAtLeast(0L)
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context, ACTION_PAUSE))
        // Fires when the fade has to begin, so the music is paused right at the time shown.
        setAlarm(context, ACTION_FADE, (delay - FADE_MS).coerceAtLeast(0L))
        prefs.sleepUntil = System.currentTimeMillis() + delay
    }

    /** Moves the pause [minutes] later (from now, if the timer has already gone off). */
    fun extend(context: Context, prefs: Prefs, minutes: Int) {
        val now = System.currentTimeMillis()
        startIn(context, prefs, maxOf(prefs.sleepUntil, now) - now + minutes * 60_000L)
    }

    fun cancel(context: Context, prefs: Prefs) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        // null: an alarm set by a version before the fade existed.
        listOf(ACTION_FADE, ACTION_PAUSE, null).forEach { alarms.cancel(alarm(context, it)) }
        prefs.sleepUntil = 0L
    }

    /** No service to fade out: pause plainly at the time shown instead. */
    internal fun pauseLater(context: Context) = setAlarm(context, ACTION_PAUSE, FADE_MS)

    private fun setAlarm(context: Context, action: String, inMs: Long) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + inMs, alarm(context, action),
        )
    }

    private fun alarm(context: Context, action: String?) = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    internal const val ACTION_FADE = "com.knobnext.action.SLEEP_FADE"
    internal const val ACTION_PAUSE = "com.knobnext.action.SLEEP_PAUSE"
}

/** Fades out and pauses when the sleep timer fires (or pauses at the end if the service isn't running). */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (intent.action != SleepTimer.ACTION_PAUSE) {
            SleepTimer.fader?.let {
                prefs.sleepUntil = 0L
                return it()
            }
            return SleepTimer.pauseLater(context)
        }
        prefs.sleepUntil = 0L
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }
}

/** The camera flash as a torch. Needs no permission; fails only while another app uses the camera. */
private class Torch(private val context: Context, handler: Handler) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private var on = false

    private val id: String? by lazy {
        try {
            val withFlash = cameras?.cameraIdList.orEmpty().filter {
                cameras?.getCameraCharacteristics(it)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull {
                cameras?.getCameraCharacteristics(it)?.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    // The torch can also be switched from quick settings; follow it so the toggle stays right.
    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == id) on = enabled
        }
    }

    init {
        try {
            cameras?.registerTorchCallback(callback, handler)
        } catch (_: Exception) {
        }
    }

    fun toggle(): String? {
        val camera = id ?: return context.getString(R.string.log_no_torch)
        return try {
            cameras?.setTorchMode(camera, !on)
            on = !on
            null
        } catch (_: Exception) {
            context.getString(R.string.log_torch_busy)
        }
    }

    fun release() {
        try {
            cameras?.unregisterTorchCallback(callback)
        } catch (_: Exception) {
        }
    }
}
