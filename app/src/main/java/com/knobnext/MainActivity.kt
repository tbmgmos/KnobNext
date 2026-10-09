package com.knobnext

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)
    private var notificationAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val media = Media(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val actions = ScreenActions(
            openAccessibility = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            openAppInfo = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            openNotificationAccess = {
                val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        media.listenerComponent.flattenToString(),
                    )
                } else {
                    null
                }
                if (detail == null || !open(detail)) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            openUrl = { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
        )
        setContent {
            KnobTheme {
                KnobScreen(
                    serviceEnabled = serviceEnabled,
                    notificationAccess = notificationAccess,
                    versionName = version,
                    prefs = prefs,
                    actions = actions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
        notificationAccess = Media(this).hasAccess()
    }

    private fun open(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private fun isServiceEnabled(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == KnobService::class.java.name
            }
}
