package com.knobnext

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updating from inside the app (GitHub version only): downloads the release APK, checks that it
 * really is a newer KnobNext signed with the same key, and hands it to the system installer. The
 * app is replaced in place, settings and the enabled accessibility service are kept.
 */
object SelfUpdate {
    sealed interface State {
        data object Idle : State

        /** [progress] is 0..1, or -1 while the size is unknown. */
        data class Downloading(val progress: Float) : State

        /** The system's "install unknown apps" switch for KnobNext is off. */
        data object NeedsPermission : State

        /** Waiting for the user in the system's install dialog. */
        data object Installing : State

        data class Failed(val reason: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun start(context: Context, apkUrl: String) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        if (!canInstall(app)) {
            mutableState.value = State.NeedsPermission
            return
        }
        job = scope.launch {
            mutableState.value = State.Downloading(-1f)
            val file = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { download(app, apkUrl) }
            } catch (_: Exception) {
                return@launch fail(app.getString(R.string.update_err_network))
            }
            verify(app, file)?.let { return@launch fail(it) }
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { install(app, file) }
                mutableState.value = State.Installing
            } catch (_: Exception) {
                fail(app.getString(R.string.update_err_install))
            }
        }
    }

    /** Called by [UpdateReceiver] when the installer gives up or the user cancels. */
    internal fun fail(reason: String) {
        mutableState.value = State.Failed(reason)
    }

    internal fun reset() {
        mutableState.value = State.Idle
    }

    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "KnobNext.apk")
        file.delete()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "KnobNext")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var shown = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                mutableState.value = State.Downloading(percent / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** A reason not to install [file], or null when it is a newer KnobNext with the same signature. */
    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return context.getString(R.string.update_err_broken)
        if (archive.packageName != context.packageName) return context.getString(R.string.update_err_other_app)
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (versionCode(archive) <= versionCode(installed)) return context.getString(R.string.update_err_not_newer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val theirs = archive.signingInfo?.apkContentsSigners?.toSet()
            val ours = installed.signingInfo?.apkContentsSigners?.toSet()
            // Android refuses to update an app with an APK signed by another key; say so plainly.
            if (theirs != null && ours != null && theirs != ours) return context.getString(R.string.update_err_signature)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // An app updating itself may skip the confirmation on Android 12+; the system decides.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java),
                // The installer adds the result to this intent, so it has to be mutable.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }
}

/** Results from the system installer: show its confirmation dialog, or report why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                try {
                    confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } catch (_: Exception) {
                    SelfUpdate.fail(context.getString(R.string.update_err_install))
                }
            }
            // The new version replaces this process; nothing to do.
            PackageInstaller.STATUS_SUCCESS -> SelfUpdate.reset()
            PackageInstaller.STATUS_FAILURE_ABORTED -> SelfUpdate.reset()
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                SelfUpdate.fail(context.getString(R.string.update_err_signature))
            else -> SelfUpdate.fail(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: context.getString(R.string.update_err_install),
            )
        }
    }
}
