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
import com.knobnext.ui.KnobScreen
import com.knobnext.ui.KnobTheme
import com.knobnext.ui.ScreenActions
import com.knobnext.ui.SetupState

class MainActivity : ComponentActivity() {

    private var setup by mutableStateOf(SetupState(restrictedApplies = false, restrictedBlocked = false, serviceEnabled = false, playerAccess = false))

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
        )
        setContent {
            KnobTheme {
                KnobScreen(setup = setup, versionName = version, prefs = prefs, actions = actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setup = SetupState(
            restrictedApplies = Setup.installedFromFile(this),
            restrictedBlocked = Setup.restrictedSettingsBlocked(this),
            serviceEnabled = isServiceEnabled(),
            playerAccess = Media(this).hasAccess(),
        )
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

    private fun isServiceEnabled(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == KnobService::class.java.name
            }
}
