package com.knobnext

import android.service.notification.NotificationListenerService

/**
 * Never reads notifications. Being an enabled notification listener is what lets the app call
 * MediaSessionManager.getActiveSessions() to see and control the current player.
 */
class KnobNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        KnobService.instance?.onPlayerAccessGranted()
        Setup.returnToAppIfRequested(this)
    }
}
