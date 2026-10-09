package com.knobnext

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

object Setup {
    private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"
    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val RETURN_WINDOW_MS = 3 * 60 * 1000L

    /**
     * Android 13+ blocks accessibility / notification access for apps installed from a file
     * (browser download, file manager) until the user allows "restricted settings".
     * Apps installed by a store are not affected, so this step is shown only when it is really needed.
     */
    fun installedFromFile(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val source = try {
            context.packageManager.getInstallSourceInfo(context.packageName).packageSource
        } catch (_: Exception) {
            return false
        }
        return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
    }

    fun restrictedSettingsBlocked(context: Context): Boolean {
        if (!installedFromFile(context)) return false
        return try {
            context.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(OP_RESTRICTED_SETTINGS, Process.myUid(), context.packageName) !=
                AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            true
        }
    }

    /** The page with KnobNext's own switch, or the accessibility list with KnobNext highlighted. */
    fun accessibilityIntents(context: Context): List<Intent> {
        val component = ComponentName(context, KnobService::class.java).flattenToString()
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS).putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).highlight(component)
        return listOf(details, list)
    }

    fun notificationAccessIntents(context: Context): List<Intent> {
        val component = ComponentName(context, KnobNotificationListener::class.java).flattenToString()
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).highlight(component)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return listOf(list)
        val details = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        return listOf(details, list)
    }

    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))

    /** Called before sending the user to system settings, so the app can bring them back afterwards. */
    fun requestReturn(prefs: Prefs) {
        prefs.returnRequestedAt = SystemClock.elapsedRealtime()
    }

    /** Called when a service gets enabled: reopens the app if the user came from it a moment ago. */
    fun returnToAppIfRequested(context: Context) {
        val prefs = Prefs(context)
        val at = prefs.returnRequestedAt
        if (at == 0L) return
        prefs.returnRequestedAt = 0L
        val elapsed = SystemClock.elapsedRealtime() - at
        if (elapsed !in 0..RETURN_WINDOW_MS) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (_: Exception) {
        }
    }

    private fun Intent.highlight(component: String): Intent =
        putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
}
