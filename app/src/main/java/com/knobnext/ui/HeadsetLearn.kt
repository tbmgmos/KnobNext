package com.knobnext.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.knobnext.Gesture
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
