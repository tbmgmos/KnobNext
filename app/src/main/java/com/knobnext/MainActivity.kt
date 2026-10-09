package com.knobnext

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.reflect.KMutableProperty0

class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }

        bindSwitch(R.id.pref_enabled, prefs::enabled)
        bindSwitch(R.id.pref_only_when_playing, prefs::onlyWhenPlaying)
        bindSwitch(R.id.pref_swap, prefs::swap)
        bindSwitch(R.id.pref_combo, prefs::comboPlayPause)
        bindSwitch(R.id.pref_vibrate, prefs::vibrate)
        bindLongPressSeekBar()
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        findViewById<TextView>(R.id.status).apply {
            setText(if (enabled) R.string.status_on else R.string.status_off)
            setTextColor(getColor(if (enabled) R.color.status_ok else R.color.status_bad))
        }
        findViewById<View>(R.id.restricted_block).visibility =
            if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
    }

    private fun isServiceEnabled(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == KnobService::class.java.name
            }

    private fun bindSwitch(id: Int, pref: KMutableProperty0<Boolean>) {
        findViewById<Switch>(id).apply {
            isChecked = pref.get()
            setOnCheckedChangeListener { _, checked -> pref.set(checked) }
        }
    }

    private fun bindLongPressSeekBar() {
        val label = findViewById<TextView>(R.id.long_press_label)
        val seek = findViewById<SeekBar>(R.id.long_press_seek)
        fun show(ms: Int) {
            label.text = getString(R.string.pref_long_press, ms)
        }

        seek.max = (Prefs.LONG_PRESS_MAX - Prefs.LONG_PRESS_MIN) / Prefs.LONG_PRESS_STEP
        seek.progress = (prefs.longPressMs - Prefs.LONG_PRESS_MIN) / Prefs.LONG_PRESS_STEP
        show(prefs.longPressMs)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = Prefs.LONG_PRESS_MIN + progress * Prefs.LONG_PRESS_STEP
                prefs.longPressMs = ms
                show(ms)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }
}
