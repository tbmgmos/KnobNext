package com.knobnext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.knobnext.Gesture
import com.knobnext.HeadsetMotion
import com.knobnext.R
import com.knobnext.HeadsetLive
import kotlinx.coroutines.delay

/** What the headphones page has seen: which headphones, and the last press. */
class HeadsetLearnState {
    var device by mutableStateOf<String?>(null)
    var last by mutableStateOf<HeadsetLive.Event.Pressed?>(null)

    /** The press just made, for a few seconds; the line it belongs to stands out meanwhile. */
    var fresh by mutableStateOf<Gesture?>(null)
    var serial by mutableIntStateOf(0)
}

/** While composed (the headphones page is open), presses on the headphones only show on the page. */
@Composable
fun rememberHeadsetLearn(): HeadsetLearnState {
    val state = remember { HeadsetLearnState().apply { device = HeadsetLive.device } }
    DisposableEffect(Unit) {
        HeadsetLive.start { event ->
            when (event) {
                is HeadsetLive.Event.Pressed -> {
                    state.last = event
                    state.fresh = event.gesture
                    state.serial++
                }
                is HeadsetLive.Event.Device -> state.device = event.name
            }
        }
        onDispose { HeadsetLive.stop() }
    }
    LaunchedEffect(state.serial) {
        if (state.serial == 0) return@LaunchedEffect
        delay(FRESH_MS)
        state.fresh = null
    }
    return state
}

private const val FRESH_MS = 4_000L

fun motionLabel(m: HeadsetMotion) = when (m) {
    HeadsetMotion.TAP -> R.string.motion_tap
    HeadsetMotion.DOUBLE_TAP -> R.string.motion_double_tap
    HeadsetMotion.TRIPLE_TAP -> R.string.motion_triple_tap
    HeadsetMotion.HOLD -> R.string.motion_hold
    HeadsetMotion.DOUBLE_TAP_HOLD -> R.string.motion_double_tap_hold
    HeadsetMotion.SWIPE_FORWARD -> R.string.motion_swipe_forward
    HeadsetMotion.SWIPE_BACK -> R.string.motion_swipe_back
    HeadsetMotion.BUTTON -> R.string.motion_button
}

/** The gesture that usually sends this command, until the person names theirs. */
fun typicalMotion(g: Gesture) = when (g) {
    Gesture.HS_PLAY_PAUSE -> R.string.typical_play_pause
    Gesture.HS_NEXT -> R.string.typical_next
    Gesture.HS_PREVIOUS -> R.string.typical_previous
    Gesture.HS_FORWARD, Gesture.HS_REWIND -> R.string.typical_seek
    Gesture.HS_ASSIST -> R.string.typical_assist
    else -> R.string.typical_counted
}

/** "Which gesture was that?": the answer names the line of [gesture] for these headphones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotionQuestion(device: String, current: HeadsetMotion?, onPick: (HeadsetMotion?) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.headset_which, device),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeadsetMotion.entries.forEach { m ->
                FilterChip(
                    selected = m == current,
                    onClick = { onPick(if (m == current) null else m) },
                    label = { Text(stringResource(motionLabel(m))) },
                )
            }
        }
    }
}
