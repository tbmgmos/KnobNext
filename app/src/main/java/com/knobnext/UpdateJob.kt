package com.knobnext

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Once a day, with any network, asks GitHub for a newer release and, if there is one, shows a
 * notification once per version. Tapping it opens the app, whose update card installs it.
 * GitHub version only (the service is declared in that flavor's manifest).
 */
class UpdateJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.Main).launch {
            val prefs = Prefs(this@UpdateJob)
            val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            val release = if (prefs.checkUpdates) Updates.latest() else null
            if (release != null && Updates.isNewer(release.version, installed) && prefs.updateNotified != release.version) {
                notify(release.version)
                prefs.updateNotified = release.version
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private fun notify(version: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.update_notification_title, version))
                .setContentText(getString(R.string.update_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val JOB_ID = 42
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 2
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Keeps the daily check scheduled while update checks are on, and removes it when they're off. */
        fun sync(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val wanted = context.resources.getBoolean(R.bool.update_check) && Prefs(context).checkUpdates
            if (!wanted) return scheduler.cancel(JOB_ID)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            try {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(DAY_MS)
                        .setPersisted(true)
                        .build(),
                )
            } catch (_: Exception) {
                // Not declared in this build (RuStore version) or not allowed: nothing to check then.
            }
        }
    }
}
