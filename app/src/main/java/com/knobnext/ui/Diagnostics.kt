@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.knobnext.DoubleMode
import com.knobnext.NowPlaying
import com.knobnext.Prefs
import com.knobnext.R
import com.knobnext.Restricted
import com.knobnext.ScreenOffService
import com.knobnext.Settings

/** One line of the diagnostics: [ok] null means "just information". */
/** [fix] opens the place where a failing check is fixed. */
private class Check(val title: String, val value: String, val ok: Boolean?, val fix: (() -> Unit)? = null)

/** Everything that decides whether gestures work, in one list, plus a report to send. */
@Composable
fun DiagnosticsCard(
    s: Settings,
    prefs: Prefs,
    setup: SetupState,
    actions: ScreenActions,
    nowPlaying: NowPlaying?,
    versionName: String,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val backgroundRunning by ScreenOffService.runningFlow.collectAsState()
    val checks = buildChecks(context, s, prefs, setup, actions, nowPlaying, versionName, backgroundRunning)
    val firstFix = checks.firstOrNull { it.ok == false && it.fix != null }?.fix
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (firstFix != null) {
            Button(
                onClick = firstFix,
                shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diag_fix_all), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                stringResource(R.string.diag_fix_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Group(checks.size) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                checks.forEachIndexed { i, c ->
                    ListRow(
                        shape = shape(i),
                        title = c.title,
                        subtitle = c.value,
                        onClick = if (c.ok == false) c.fix else null,
                        leading = {
                            when (c.ok) {
                                true -> ShapeBadge(MaterialShapes.Circle, KnobIcons.Check, scheme.primary, scheme.onPrimary, size = 40)
                                false -> ShapeBadge(MaterialShapes.Pentagon, KnobIcons.Close, scheme.error, scheme.onError, size = 40)
                                null -> ShapeBadge(
                                    MaterialShapes.Cookie6Sided, KnobIcons.Settings,
                                    scheme.surfaceContainerHighest, scheme.onSurfaceVariant, size = 40,
                                )
                            }
                        },
                    )
                }
            }
        }
        Button(
            onClick = { share(context, report(context, checks, prefs)) },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(KnobIcons.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diag_share), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun buildChecks(
    context: Context,
    s: Settings,
    prefs: Prefs,
    setup: SetupState,
    actions: ScreenActions,
    nowPlaying: NowPlaying?,
    versionName: String,
    backgroundRunning: Boolean,
): List<Check> = buildList {
    add(
        Check(
            stringResource(R.string.diag_gestures),
            stringResource(if (s.enabled) R.string.diag_gestures_on else R.string.diag_gestures_off),
            s.enabled,
            fix = { prefs.enabled = true },
        ),
    )
    add(
        Check(
            stringResource(R.string.diag_service),
            stringResource(
                when {
                    setup.serviceEnabled && setup.serviceRunning -> R.string.diag_service_ok
                    setup.serviceEnabled -> R.string.diag_service_stuck
                    else -> R.string.diag_service_off
                },
            ),
            setup.serviceEnabled && setup.serviceRunning,
            fix = actions.openAccessibility,
        ),
    )
    if (setup.restricted == Restricted.BLOCKED && !setup.serviceEnabled) {
        add(Check(stringResource(R.string.diag_restricted), stringResource(R.string.diag_restricted_blocked), false, actions.openAppInfo))
    }
    val needsBackground = s.doubleMode != DoubleMode.OFF || s.backTap || s.flip || s.twist || s.wave
    add(
        if (!needsBackground) {
            Check(stringResource(R.string.diag_background), stringResource(R.string.diag_background_off), null)
        } else {
            Check(
                stringResource(R.string.diag_background),
                stringResource(if (backgroundRunning) R.string.diag_background_ok else R.string.diag_background_stopped),
                backgroundRunning,
                // Usually stopped because the system may not start it from the background.
                fix = if (setup.batteryUnrestricted) null else actions.openBattery,
            )
        },
    )
    // What became of the back taps: when they stop working, the reason is here.
    if (s.backTap) add(Check(stringResource(R.string.diag_back_tap), rememberTapSummary(), null))
    add(
        Check(
            stringResource(R.string.diag_battery),
            stringResource(if (setup.batteryUnrestricted) R.string.diag_battery_ok else R.string.diag_battery_limited),
            if (needsBackground) setup.batteryUnrestricted else null,
            fix = actions.openBattery,
        ),
    )
    add(
        Check(
            stringResource(R.string.diag_player_access),
            stringResource(if (setup.playerAccess) R.string.diag_player_ok else R.string.diag_player_missing),
            setup.playerAccess,
            fix = actions.openNotificationAccess,
        ),
    )
    add(
        Check(
            stringResource(R.string.diag_now),
            when {
                nowPlaying == null -> stringResource(R.string.diag_now_nothing)
                else -> {
                    val label = nowPlaying.appLabel +
                        if (nowPlaying.controller.packageName in s.profiles) " · " + stringResource(R.string.profile_badge) else ""
                    stringResource(if (nowPlaying.playing) R.string.diag_now_playing else R.string.diag_now_paused, label)
                }
            },
            null,
        ),
    )
    val sensors = context.getSystemService(SensorManager::class.java)
    val missing = listOf(
        Sensor.TYPE_ACCELEROMETER to R.string.diag_sensor_accel,
        Sensor.TYPE_GYROSCOPE to R.string.diag_sensor_gyro,
        Sensor.TYPE_PROXIMITY to R.string.diag_sensor_proximity,
    ).filter { (type, _) -> sensors?.getDefaultSensor(type) == null }.map { stringResource(it.second) }
    add(
        Check(
            stringResource(R.string.diag_sensors),
            if (missing.isEmpty()) {
                stringResource(R.string.diag_sensors_all)
            } else {
                stringResource(R.string.diag_sensors_missing, missing.joinToString())
            },
            null,
        ),
    )
    add(
        Check(
            stringResource(R.string.diag_device),
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "KnobNext $versionName",
            null,
        ),
    )
}

private fun report(context: Context, checks: List<Check>, prefs: Prefs): String = buildString {
    appendLine(context.getString(R.string.diag_title))
    checks.forEach { c ->
        val mark = when (c.ok) {
            true -> "✓"
            false -> "✗"
            null -> "·"
        }
        appendLine("$mark ${c.title}: ${c.value}")
    }
    appendLine()
    appendLine(context.getString(R.string.diag_report_log))
    appendLine(prefs.lastEvent ?: context.getString(R.string.last_event_none))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "KnobNext")
        .putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.diag_share)))
    } catch (_: Exception) {
    }
}

/** A suggested hold time; [longTap] is set when it comes from tap lengths, null when from hasty holds. */
class HoldAdvice(val ms: Int, val longTap: Int?)

/**
 * A hold time that suits this person, or null when the current one is fine. Two signals:
 *  - holds let go almost as soon as they fired (before the vibration could be felt) were probably
 *    taps that ran long: the hold time is too short. Such presses never show up among the taps;
 *  - otherwise, the length of short taps: the hold time should leave room above the longest ones
 *    without making holds wait longer than needed.
 */
fun suggestLongPress(taps: List<Int>, overshoots: List<Int>, current: Int): HoldAdvice? {
    fun round(ms: Double) = (Math.round(ms / Prefs.LONG_PRESS_STEP) * Prefs.LONG_PRESS_STEP).toInt()
        .coerceIn(MIN_SUGGESTED_MS, MAX_SUGGESTED_MS)
    if (overshoots.size >= MIN_HOLD_SAMPLES) {
        val hasty = overshoots.count { it < HASTY_RELEASE_MS }
        if (hasty * 10 >= overshoots.size * 4) {
            return HoldAdvice(round(current + 150.0), null).takeIf { it.ms > current }
        }
    }
    if (taps.size < MIN_TAP_SAMPLES) return null
    val longTap = longTapMs(taps)
    val ideal = round(longTap * 2.2)
    val ms = when {
        // Taps come close to the hold time: holds fire when a tap was meant.
        current < longTap * 1.6 -> ideal
        // Much slower than needed: every hold waits for nothing.
        current > ideal + 200 -> ideal
        else -> return null
    }
    return HoldAdvice(ms, longTap).takeIf { ms != current }
}

/** The longest of most taps (90th percentile), shown next to the suggestion. */
fun longTapMs(taps: List<Int>): Int = taps.sorted().let { it[((it.size - 1) * 0.9).toInt()] }

private const val MIN_TAP_SAMPLES = 10
private const val MIN_HOLD_SAMPLES = 5

/** Faster than anyone reacts to the vibration: the key was already on its way up. */
private const val HASTY_RELEASE_MS = 100
private const val MIN_SUGGESTED_MS = 300
private const val MAX_SUGGESTED_MS = 1_000
