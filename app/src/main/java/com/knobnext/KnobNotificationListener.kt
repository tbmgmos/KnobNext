package com.knobnext

import android.app.Notification
import android.service.notification.NotificationListenerService

/**
 * Being an enabled notification listener lets the app see and control media sessions. Notifications
 * are not read, except the action buttons of the current player's own notification: players such as
 * Spotify accept "like" only from their notification button, so that is what gets pressed.
 */
class KnobNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Setup.returnToAppIfRequested(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    /** Buttons of [packageName]'s notifications, the media notification first. */
    fun playerActions(packageName: String): List<Notification.Action> =
        try {
            activeNotifications
                .filter { it.packageName == packageName }
                .sortedByDescending { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
                .flatMap { it.notification.actions?.toList().orEmpty() }
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        @Volatile
        var instance: KnobNotificationListener? = null
            private set
    }
}
