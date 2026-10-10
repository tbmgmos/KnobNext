package com.knobnext

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.knobnext.ui.KnobScreen
import com.knobnext.ui.KnobTheme
import com.knobnext.ui.ScreenActions
import com.knobnext.ui.SetupState

class MainActivity : ComponentActivity() {

    private var setup by mutableStateOf(SetupState(restricted = Restricted.NOT_APPLICABLE, serviceEnabled = false, playerAccess = false, batteryUnrestricted = true, serviceRunning = true))

    private var stopObserving: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val actions = ScreenActions(
            openAccessibility = {
                Setup.requestReturn(prefs)
                openFirst(Setup.accessibilityIntents(this))
            },
            openAppInfo = { openFirst(listOf(Setup.appInfoIntent(this))) },
            openNotificationAccess = {
                Setup.requestReturn(prefs)
                openFirst(Setup.notificationAccessIntents(this))
            },
            openUrl = { openFirst(listOf(Intent(Intent.ACTION_VIEW, Uri.parse(it)))) },
            openBattery = { openFirst(Setup.batteryIntents(this)) },
            openAutostart = Setup.autostartIntents().takeIf { it.isNotEmpty() }?.let { intents -> { openFirst(intents) } },
        )
        var shouldRun = ScreenOffService.shouldRun(this)
        stopObserving = prefs.observe {
            val now = ScreenOffService.shouldRun(this)
            if (now != shouldRun) {
                shouldRun = now
                ScreenOffService.sync(this, inForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
        }
        setContent {
            KnobTheme {
                KnobScreen(setup = setup, versionName = version, prefs = prefs, actions = actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setup = SetupState(
            restricted = Setup.restrictedState(this),
            serviceEnabled = Setup.accessibilityEnabled(this) || isServiceRunning(),
            serviceRunning = isServiceRunning(),
            playerAccess = Media(this).hasAccess(),
            batteryUnrestricted = Setup.batteryUnrestricted(this),
        )
        ScreenOffService.sync(this, inForeground = true)
    }

    override fun onStart() {
        super.onStart()
        GestureTest.setVisible(true)
    }

    override fun onStop() {
        // By the time the screen-off stop arrives, the device is already non-interactive.
        val screenOff = !getSystemService(android.os.PowerManager::class.java).isInteractive
        GestureTest.setVisible(false, screenOff)
        super.onStop()
    }

    override fun onDestroy() {
        stopObserving()
        super.onDestroy()
    }

    private fun openFirst(intents: List<Intent>) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun isServiceRunning(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == KnobService::class.java.name
            }
}
