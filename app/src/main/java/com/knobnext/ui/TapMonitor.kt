package com.knobnext.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.knobnext.R
import com.knobnext.ScreenOffService
import com.knobnext.TapStats
import kotlinx.coroutines.delay

/** One paragraph on what the back-tap detector is doing and what became of the taps. */
fun tapSummary(context: Context): String {
    if (!TapStats.running) {
        // Why it is off: "only while music plays" is wrong when it is off for another reason.
        val prefs = com.knobnext.Prefs(context)
        return context.getString(
            when {
                !prefs.backTap -> R.string.tap_stats_off_disabled
                // The screen is on while this is read.
                prefs.backTapScreenOff -> R.string.tap_stats_off_screen
                else -> R.string.tap_stats_off
            },
        )
    }
    val now = SystemClock.elapsedRealtime()
    val silentSec = ((now - TapStats.lastEventAt) / 1000).toInt()
    val sensor = if (silentSec >= 2) {
        context.getString(R.string.tap_stats_silent, silentSec)
    } else {
        context.getString(R.string.tap_stats_sensor, TapStats.rateHz, TapStats.noisePercent, TapStats.rotationTenths / 10.0)
    }
    val counts = context.getString(
        R.string.tap_stats_counts,
        TapStats.count(TapStats.Verdict.TAPS),
        TapStats.count(TapStats.Verdict.VIBRATION),
        TapStats.count(TapStats.Verdict.TURN),
        TapStats.count(TapStats.Verdict.BUMP),
        TapStats.count(TapStats.Verdict.SINGLE),
        TapStats.count(TapStats.Verdict.POCKET),
        TapStats.count(TapStats.Verdict.NOT_PLAYING),
        TapStats.count(TapStats.Verdict.RESTARTED),
    )
    val last = TapStats.last?.let { v ->
        val ago = ((now - TapStats.lastAt) / 1000).toInt()
        context.getString(R.string.tap_stats_last, context.getString(verdictLabel(v)), ago)
    }
    val background = if (ScreenOffService.running) null else context.getString(R.string.tap_stats_no_background)
    return listOfNotNull(background, sensor, counts, last).joinToString("\n")
}

fun verdictLabel(v: TapStats.Verdict) = when (v) {
    TapStats.Verdict.TAPS -> R.string.tap_verdict_taps
    TapStats.Verdict.VIBRATION -> R.string.tap_verdict_vibration
    TapStats.Verdict.BUMP -> R.string.tap_verdict_bump
    TapStats.Verdict.SINGLE -> R.string.tap_verdict_single
    TapStats.Verdict.POCKET -> R.string.tap_verdict_pocket
    TapStats.Verdict.NOT_PLAYING -> R.string.tap_verdict_not_playing
    TapStats.Verdict.RESTARTED -> R.string.tap_verdict_restarted
    TapStats.Verdict.TURN -> R.string.tap_verdict_turn
}

/** [tapSummary], refreshed twice a second while shown. */
@Composable
fun rememberTapSummary(): String {
    val context = LocalContext.current
    var text by remember { mutableStateOf(tapSummary(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            text = tapSummary(context)
            delay(500)
        }
    }
    return text
}
