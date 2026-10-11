package com.knobnext

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Started by the system when the power button is held and KnobNext is the digital assistant app.
 * Shows nothing: the service runs the "hold power" action, or, when there is nothing to do (no music,
 * nothing assigned), the usual assistant opens instead.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        val handled = PowerHold.handler?.invoke() ?: false
        if (!handled && Prefs(this).powerHoldFallback) PowerHold.openOtherAssistant(this)
        // Theme.NoDisplay: must finish before it would be shown.
        finish()
    }
}

object PowerHold {
    /** Set by the service while it runs: true when the hold was used (the action ran or was checked). */
    @Volatile var handler: (() -> Boolean)? = null

    /** Whether KnobNext is the digital assistant app; null when that can't be told (before Android 10). */
    fun isAssistant(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        return try {
            if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Where the digital assistant app is chosen (apps can't ask for that role themselves). */
    fun openAssistantSettings(context: Context) {
        val tries = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    /** Gemini or the Google assistant, whichever is there: what the hold would have opened without us. */
    fun openOtherAssistant(context: Context) {
        val pm = context.packageManager
        val tries = listOfNotNull(
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage(GOOGLE),
            pm.getLaunchIntentForPackage(GEMINI),
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage(GEMINI),
            pm.getLaunchIntentForPackage(GOOGLE),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    private const val GOOGLE = "com.google.android.googlequicksearchbox"
    private const val GEMINI = "com.google.android.apps.bard"
}
