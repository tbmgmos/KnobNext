package com.knobnext

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import java.util.Locale

/**
 * Actions that need more than transport controls: speech, alarms, launching apps, player-specific
 * commands. Each returns a short message for the event log when it could not do what was asked.
 */
class Extras(private val context: Context, private val media: Media, private val prefs: Prefs) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val speaker = Speaker(context)

    // Players don't report shuffle/repeat state through the framework API, so we remember what we set.
    private val shuffleOn = HashMap<String, Boolean>()
    private val repeatMode = HashMap<String, Int>()

    fun perform(action: Action, controller: MediaController?): String? = when (action) {
        Action.ANNOUNCE -> announce(controller)
        Action.LIKE -> like(controller)
        Action.SHUFFLE -> shuffle(controller)
        Action.REPEAT -> repeat(controller)
        Action.SLEEP_TIMER -> toggleSleepTimer()
        Action.OPEN_PLAYER -> openPlayer(controller)
        else -> null
    }

    fun release() = speaker.release()

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
        speaker.say(listOfNotNull(title, artist).joinToString(". "))
        return null
    }

    private fun isLike(text: String) = LIKE.any { it.containsMatchIn(text) } && UNLIKE.none { it.containsMatchIn(text) }
    private fun isUnlike(text: String) = LIKE.any { it.containsMatchIn(text) } && UNLIKE.any { it.containsMatchIn(text) }

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
    private fun like(controller: MediaController?): String? {
        val c = controller ?: return context.getString(R.string.log_needs_access)
        val buttons = notificationButtons(c.packageName)
        buttons.firstOrNull { (title, _) -> isLike(title) }?.let { (_, action) -> if (press(action)) return null }
        if (buttons.any { (title, _) -> isUnlike(title) }) return context.getString(R.string.log_already_liked)

        val custom = c.playbackState?.customActions.orEmpty()
        fun text(a: PlaybackState.CustomAction) = "${a.action} ${a.name}".lowercase()
        custom.firstOrNull { isLike(text(it)) }?.let {
            c.transportControls.sendCustomAction(it, null)
            return null
        }
        when (c.ratingType) {
            Rating.RATING_THUMB_UP_DOWN -> c.transportControls.setRating(Rating.newThumbRating(true))
            Rating.RATING_HEART -> c.transportControls.setRating(Rating.newHeartRating(true))
            else -> {
                if (custom.any { isUnlike(text(it)) }) return context.getString(R.string.log_already_liked)
                val offered = (buttons.map { it.first } + custom.map { it.name.toString() })
                    .filter { it.isNotBlank() }.distinct().joinToString().ifEmpty { "—" }
                return context.getString(R.string.log_unsupported_actions, offered)
            }
        }
        return null
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

    private fun toggleSleepTimer(): String? {
        if (SleepTimer.active(prefs)) {
            SleepTimer.cancel(context, prefs)
            speaker.say(context.getString(R.string.speak_sleep_cancelled))
            return context.getString(R.string.log_sleep_cancelled)
        }
        val minutes = prefs.sleepMinutes
        SleepTimer.start(context, prefs, minutes)
        speaker.say(context.getString(R.string.speak_sleep_set, minutes))
        return context.getString(R.string.log_sleep_set, minutes)
    }

    private fun openPlayer(controller: MediaController?): String? {
        val pkg = controller?.packageName ?: prefs.lastPlayer ?: return context.getString(R.string.log_no_player)
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
        private val LIKE = listOf(
            Regex("like"), Regex("thumbs?_?up"), Regex("thumb up"), Regex("favou?rite"), Regex("heart"), Regex("love"),
            Regex("collection"), Regex("library"), Regex("save"), Regex("нрав"), Regex("любим"), Regex("избранн"),
            Regex("медиатек"), Regex("сохран"),
        )
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

    fun start(context: Context, prefs: Prefs, minutes: Int) {
        val delay = minutes * 60_000L
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, alarm(context))
        prefs.sleepUntil = System.currentTimeMillis() + delay
    }

    fun cancel(context: Context, prefs: Prefs) {
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context))
        prefs.sleepUntil = 0L
    }

    private fun alarm(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Pauses playback when the sleep timer fires. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs(context).sleepUntil = 0L
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }
}

/** Text-to-speech that briefly ducks the music while it talks. */
private class Speaker(private val context: Context) : TextToSpeech.OnInitListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(text: String) {
        if (tts == null) {
            pending = text
            tts = TextToSpeech(context, this)
            return
        }
        if (ready) speak(text) else pending = text
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        engine.language = Locale.getDefault()
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                audio.abandonAudioFocusRequest(focus)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audio.abandonAudioFocusRequest(focus)
            }
        })
        pending?.let(::speak)
        pending = null
    }

    private fun speak(text: String) {
        audio.requestAudioFocus(focus)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "knob")
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }
}
