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

    private fun like(controller: MediaController?): String? {
        val c = controller ?: return context.getString(R.string.log_needs_access)
        findCustomAction(c, LIKE_WORDS, exclude = listOf("dislike", "unlike", "thumbs_down", "thumb_down"))?.let {
            c.transportControls.sendCustomAction(it, null)
            return null
        }
        when (c.ratingType) {
            Rating.RATING_THUMB_UP_DOWN -> c.transportControls.setRating(Rating.newThumbRating(true))
            Rating.RATING_HEART -> c.transportControls.setRating(Rating.newHeartRating(true))
            else -> return context.getString(R.string.log_unsupported)
        }
        return null
    }

    private fun shuffle(controller: MediaController?): String? {
        val c = controller ?: return context.getString(R.string.log_needs_access)
        findCustomAction(c, listOf("shuffle", "перемеш"))?.let {
            c.transportControls.sendCustomAction(it, null)
            return null
        }
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
        findCustomAction(c, listOf("repeat", "повтор"))?.let {
            c.transportControls.sendCustomAction(it, null)
            return null
        }
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
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        if (prefs.sleepUntil > System.currentTimeMillis()) {
            alarms.cancel(pending)
            prefs.sleepUntil = 0L
            speaker.say(context.getString(R.string.speak_sleep_cancelled))
            return context.getString(R.string.log_sleep_cancelled)
        }
        val minutes = prefs.sleepMinutes
        val delay = minutes * 60_000L
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pending)
        prefs.sleepUntil = System.currentTimeMillis() + delay
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

    private fun findCustomAction(
        c: MediaController,
        words: List<String>,
        exclude: List<String> = emptyList(),
    ): PlaybackState.CustomAction? =
        c.playbackState?.customActions?.firstOrNull { a ->
            val text = "${a.action} ${a.name}".lowercase()
            words.any { it in text } && exclude.none { it in text }
        }

    companion object {
        private val LIKE_WORDS = listOf("like", "thumb", "favorite", "favourite", "heart", "love", "нрав", "избран", "сохран")
        private const val COMPAT_SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
        private const val COMPAT_SHUFFLE_ARG = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
        private const val COMPAT_SET_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE"
        private const val COMPAT_REPEAT_ARG = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"
        private const val OPEN_PLAY_DELAY_MS = 1_500L
    }
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
