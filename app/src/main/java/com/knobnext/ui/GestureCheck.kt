@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.knobnext.Action
import com.knobnext.Gesture
import com.knobnext.GestureTest
import com.knobnext.R

/** What the gesture check has seen so far. */
class CheckState {
    var last by mutableStateOf<GestureTest.Event.Recognized?>(null)

    /** Bumped on every recognized gesture, so the same gesture twice still animates. */
    var serial by mutableIntStateOf(0)
    var note by mutableStateOf<String?>(null)
    val seen = mutableStateMapOf<Gesture, Int>()
}

/** Puts the service into check mode for as long as this is composed. */
@Composable
fun rememberGestureCheck(): CheckState {
    val state = remember { CheckState() }
    DisposableEffect(Unit) {
        GestureTest.start { event ->
            when (event) {
                is GestureTest.Event.Recognized -> {
                    state.last = event
                    state.serial++
                    state.note = null
                    state.seen[event.gesture] = (state.seen[event.gesture] ?: 0) + 1
                }
                is GestureTest.Event.Note -> state.note = event.text
            }
        }
        onDispose { GestureTest.stop() }
    }
    return state
}

/** The big card on top: the last recognized gesture and what it would do. */
@Composable
fun CheckHero(state: CheckState) {
    val scheme = MaterialTheme.colorScheme
    val pop = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    Surface(
        shape = RoundedCornerShape(36.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = state.serial to state.last,
                transitionSpec = { (scaleIn(pop, initialScale = 0.5f) + fadeIn()).togetherWith(scaleOut() + fadeOut()) },
                label = "checkHero",
            ) { (_, last) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (last == null) {
                        ShapeBadge(MaterialShapes.Cookie9Sided, KnobIcons.TouchApp, scheme.primary, scheme.onPrimary, size = 96)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.test_idle_title),
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        ShapeBadge(gestureShape(last.gesture), actionIcon(last.action), scheme.primary, scheme.onPrimary, size = 96)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(gestureLabel(last.gesture)),
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            if (last.action == Action.NONE) {
                                stringResource(R.string.test_no_action)
                            } else {
                                "→ " + stringResource(actionLabel(last.action))
                            },
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            AnimatedVisibility(state.note != null) {
                Text(
                    state.note.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.test_idle_body),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One group of gestures to try; a tick appears on each one once it has been recognized. */
@Composable
fun CheckList(title: String, gestures: List<Gesture>, actions: Map<Gesture, Action>, state: CheckState) {
    val scheme = MaterialTheme.colorScheme
    Column {
        SectionTitle(title)
        Group(gestures.size) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                gestures.forEachIndexed { i, g ->
                    val count = state.seen[g] ?: 0
                    val action = actions[g] ?: Action.NONE
                    ListRow(
                        shape = shape(i),
                        title = stringResource(gestureLabel(g)),
                        subtitle = stringResource(if (action == Action.NONE) R.string.test_no_action else actionLabel(action)),
                        leading = {
                            if (count > 0) {
                                ShapeBadge(MaterialShapes.Circle, KnobIcons.Check, scheme.primary, scheme.onPrimary)
                            } else {
                                ShapeBadge(gestureShape(g), actionIcon(action), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
                            }
                        },
                        trailing = {
                            if (count > 1) Text("×$count", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                        },
                    )
                }
            }
        }
    }
}

/** A shape per gesture, the same everywhere a gesture is shown. */
fun gestureShape(g: Gesture): RoundedPolygon = when (g) {
    Gesture.HOLD_UP -> MaterialShapes.Sunny
    Gesture.HOLD_DOWN -> MaterialShapes.Cookie4Sided
    Gesture.BOTH -> MaterialShapes.Clover4Leaf
    Gesture.TAP_HOLD_UP -> MaterialShapes.Cookie6Sided
    Gesture.TAP_HOLD_DOWN -> MaterialShapes.Cookie7Sided
    Gesture.LONG_HOLD_UP -> MaterialShapes.VerySunny
    Gesture.LONG_HOLD_DOWN -> MaterialShapes.Cookie9Sided
    Gesture.BOTH_HOLD -> MaterialShapes.Clover8Leaf
    Gesture.POWER_DOUBLE -> MaterialShapes.SoftBurst
    Gesture.DOUBLE_UP -> MaterialShapes.Pill
    Gesture.DOUBLE_DOWN -> MaterialShapes.Arch
    Gesture.UP_DOWN -> MaterialShapes.Gem
    Gesture.TRIPLE_UP -> MaterialShapes.Triangle
    Gesture.TRIPLE_DOWN -> MaterialShapes.Diamond
    Gesture.DOWN_UP -> MaterialShapes.Bun
    Gesture.BACK_TAP_2 -> MaterialShapes.Puffy
    Gesture.BACK_TAP_3 -> MaterialShapes.Flower
    Gesture.TWIST -> MaterialShapes.Burst
    Gesture.WAVE -> MaterialShapes.Ghostish
}
