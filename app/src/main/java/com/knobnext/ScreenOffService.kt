package com.knobnext

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Does no work of its own. While it runs, the app's process counts as foreground, so the system
 * doesn't freeze it with the screen off (a frozen process gets volume events late and merged) and
 * keeps delivering sensor events for motion gestures.
 */
class ScreenOffService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must always be called after startForegroundService, even if we stop right away.
        // It throws when a background start isn't allowed; that must never take the process
        // (and the accessibility service living in it) down.
        try {
            startForegroundWithNotification()
        } catch (_: Exception) {
            starting = false
            stopSelf()
            return START_NOT_STICKY
        }
        starting = false
        // Also how a stop requested while this start was pending takes effect, see [stop].
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.fgs_channel), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "screen_off"
        private const val NOTIFICATION_ID = 1

        /**
         * Needed for screen-off press sequences and for motion gestures: Android stops delivering
         * sensor events to apps that have been in the background for about a minute.
         */
        fun shouldRun(context: Context): Boolean {
            val prefs = Prefs(context)
            val needsBackground = prefs.doubleMode != DoubleMode.OFF ||
                prefs.backTap || prefs.flip || prefs.twist || prefs.wave
            return prefs.enabled && needsBackground && Setup.accessibilityEnabled(context)
        }

        // Between startForegroundService() and startForeground() in onStartCommand. Stopping the service
        // in that gap makes the system kill the whole process — the accessibility service included.
        @Volatile
        private var starting = false

        /**
         * Starts or stops the service to match the settings. Safe to call from anywhere. From the
         * background a start is only allowed when battery optimization is off; pass [inForeground]
         * when called from a visible activity.
         */
        fun sync(context: Context, inForeground: Boolean = false) {
            if (!shouldRun(context)) return stop(context)
            if (!inForeground && !Setup.batteryUnrestricted(context)) return
            try {
                starting = true
                context.startForegroundService(Intent(context, ScreenOffService::class.java))
            } catch (_: Exception) {
                // Not allowed right now; the app retries on next open.
                starting = false
            }
        }

        fun stop(context: Context) {
            // A pending start re-checks shouldRun in onStartCommand and stops itself there.
            if (starting) return
            try {
                context.stopService(Intent(context, ScreenOffService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
