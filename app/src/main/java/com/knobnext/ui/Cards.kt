@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.knobnext.SelfUpdate
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.knobnext.Action
import com.knobnext.Gesture
import com.knobnext.Media
import com.knobnext.NowPlaying
import com.knobnext.R
import com.knobnext.Restricted
import com.knobnext.Release

/** Main status card; its toggle is the master switch for all gestures. */
@Composable
fun StatusHero(on: Boolean, onToggle: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(
        if (on) scheme.primaryContainer else scheme.surfaceContainerHighest, motion.defaultEffectsSpec(), label = "c",
    )
    val content by animateColorAsState(
        if (on) scheme.onPrimaryContainer else scheme.onSurface, motion.defaultEffectsSpec(), label = "t",
    )
    val badge by animateColorAsState(
        if (on) scheme.primary else scheme.outline, motion.defaultEffectsSpec(), label = "b",
    )
    val onBadge = if (on) scheme.onPrimary else scheme.surface
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    val popIn = motion.fastSpatialSpec<Float>()

    Surface(
        shape = RoundedCornerShape(36.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = on,
                        transitionSpec = { (scaleIn(popIn) + fadeIn()).togetherWith(scaleOut() + fadeOut()) },
                        label = "heroShape",
                    ) { active ->
                        Box(
                            Modifier
                                .size(72.dp)
                                .graphicsLayer { rotationZ = if (active) rotation else 0f }
                                .clip((if (active) MaterialShapes.Cookie9Sided else MaterialShapes.Pentagon).toShape())
                                .background(badge),
                        )
                    }
                    Icon(
                        if (on) KnobIcons.Check else KnobIcons.Pause,
                        contentDescription = null,
                        tint = onBadge,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (on) R.string.status_on_title else R.string.status_paused_title),
                        style = MaterialTheme.typography.headlineLargeEmphasized,
                    )
                    Text(
                        stringResource(if (on) R.string.status_on_body else R.string.status_paused_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            ToggleButton(
                checked = on,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(if (on) KnobIcons.Check else KnobIcons.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (on) R.string.toggle_on else R.string.toggle_off),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            }
        }
    }
}

@Composable
fun UpdateCard(release: Release, onOpenPage: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by SelfUpdate.state.collectAsState()
    val update = {
        val apk = release.apkUrl
        when {
            apk == null -> onOpenPage()
            !SelfUpdate.canInstall(context) -> {
                SelfUpdate.start(context, apk)
                try {
                    context.startActivity(SelfUpdate.permissionIntent(context))
                } catch (_: Exception) {
                }
            }
            else -> SelfUpdate.start(context, apk)
        }
    }
    val onPrimaryButtons = ButtonDefaults.buttonColors(containerColor = scheme.onPrimary, contentColor = scheme.primary)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.primary,
        contentColor = scheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(MaterialShapes.SoftBurst, KnobIcons.Download, scheme.onPrimary, scheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        when (val st = state) {
                            is SelfUpdate.State.Downloading ->
                                if (st.progress < 0) {
                                    stringResource(R.string.update_downloading_start)
                                } else {
                                    stringResource(R.string.update_downloading, (st.progress * 100).toInt())
                                }
                            SelfUpdate.State.NeedsPermission -> stringResource(R.string.update_permission)
                            SelfUpdate.State.Installing -> stringResource(R.string.update_installing)
                            is SelfUpdate.State.Failed -> stringResource(R.string.update_failed, st.reason)
                            SelfUpdate.State.Idle -> stringResource(R.string.update_body, release.version)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            when (val st = state) {
                is SelfUpdate.State.Downloading -> {
                    Spacer(Modifier.height(16.dp))
                    if (st.progress < 0) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = scheme.onPrimary,
                            trackColor = scheme.onPrimary.copy(alpha = 0.3f),
                        )
                    } else {
                        LinearWavyProgressIndicator(
                            progress = { st.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = scheme.onPrimary,
                            trackColor = scheme.onPrimary.copy(alpha = 0.3f),
                        )
                    }
                }
                SelfUpdate.State.Installing -> Unit
                else -> {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = update, shapes = ButtonDefaults.shapes(), colors = onPrimaryButtons) {
                            Text(
                                stringResource(
                                    when (state) {
                                        SelfUpdate.State.NeedsPermission -> R.string.update_continue
                                        is SelfUpdate.State.Failed -> R.string.update_retry
                                        else -> R.string.update_install
                                    },
                                ),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onOpenPage) {
                            Text(stringResource(R.string.update_whats_new), color = scheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
    badge: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                badge()
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            }
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(button) }
                secondary?.let { (label, action) ->
                    TextButton(onClick = action) { Text(label) }
                }
            }
        }
    }
}

@Composable
fun NowPlayingCard(now: NowPlaying, media: Media, ownGestures: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val c = now.controller
    val playCorner by animateDpAsState(
        if (now.playing) 18.dp else 36.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "playCorner",
    )
    Surface(
        shape = RoundedCornerShape(36.dp),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(scheme.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    if (now.art != null) {
                        Image(
                            now.art.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(KnobIcons.MusicNote, contentDescription = null, tint = scheme.onTertiary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    OneLine(
                        if (ownGestures) now.appLabel + " · " + stringResource(R.string.profile_badge) else now.appLabel,
                        MaterialTheme.typography.labelLarge,
                        scheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                    OneLine(now.title ?: stringResource(R.string.now_playing_unknown), MaterialTheme.typography.titleLargeEmphasized)
                    now.artist?.let { OneLine(it, MaterialTheme.typography.bodyMedium) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { media.perform(Action.SEEK_BACK, c) }, modifier = Modifier.size(48.dp)) {
                    Icon(KnobIcons.FastRewind, contentDescription = stringResource(R.string.action_seek_back))
                }
                FilledTonalIconButton(onClick = { media.perform(Action.PREVIOUS, c) }, modifier = Modifier.size(56.dp)) {
                    Icon(KnobIcons.SkipPrevious, contentDescription = stringResource(R.string.action_prev))
                }
                FilledIconButton(
                    onClick = { media.perform(Action.PLAY_PAUSE, c) },
                    shape = RoundedCornerShape(playCorner),
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                ) {
                    Icon(
                        if (now.playing) KnobIcons.Pause else KnobIcons.Play,
                        contentDescription = stringResource(R.string.action_play_pause),
                        modifier = Modifier.size(32.dp),
                    )
                }
                FilledTonalIconButton(onClick = { media.perform(Action.NEXT, c) }, modifier = Modifier.size(56.dp)) {
                    Icon(KnobIcons.SkipNext, contentDescription = stringResource(R.string.action_next))
                }
                FilledTonalIconButton(onClick = { media.perform(Action.SEEK_FORWARD, c) }, modifier = Modifier.size(48.dp)) {
                    Icon(KnobIcons.FastForward, contentDescription = stringResource(R.string.action_seek_forward))
                }
            }
        }
    }
}

@Composable
fun ActionDialog(gesture: Gesture, current: Action, onPick: (Action) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(gestureLabel(gesture))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Action.entries.forEach { a ->
                    if (a == Action.entries.first { it.notMusic }) {
                        Text(
                            stringResource(R.string.actions_not_music),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPick(a) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = a == current, onClick = { onPick(a) })
                        Spacer(Modifier.width(8.dp))
                        Icon(actionIcon(a), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(actionLabel(a)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

fun actionLabel(a: Action) = when (a) {
    Action.NONE -> R.string.action_none
    Action.NEXT -> R.string.action_next
    Action.PREVIOUS -> R.string.action_prev
    Action.PLAY_PAUSE -> R.string.action_play_pause
    Action.SEEK_FORWARD -> R.string.action_seek_forward
    Action.SEEK_BACK -> R.string.action_seek_back
    Action.VOLUME_UP -> R.string.action_volume_up
    Action.VOLUME_DOWN -> R.string.action_volume_down
    Action.ANNOUNCE -> R.string.action_announce
    Action.LIKE -> R.string.action_like
    Action.SHUFFLE -> R.string.action_shuffle
    Action.REPEAT -> R.string.action_repeat
    Action.SLEEP_TIMER -> R.string.action_sleep
    Action.OPEN_PLAYER -> R.string.action_open_player
    Action.RESTART -> R.string.action_restart
    Action.SWITCH_PLAYER -> R.string.action_switch_player
    Action.FLASHLIGHT -> R.string.action_flashlight
    Action.RINGER -> R.string.action_ringer
    Action.TIME_LEFT -> R.string.action_time_left
    Action.POCKET -> R.string.action_pocket
}

fun actionIcon(a: Action) = when (a) {
    Action.NONE -> KnobIcons.Block
    Action.NEXT -> KnobIcons.SkipNext
    Action.PREVIOUS -> KnobIcons.SkipPrevious
    Action.PLAY_PAUSE -> KnobIcons.PlayPause
    Action.SEEK_FORWARD -> KnobIcons.FastForward
    Action.SEEK_BACK -> KnobIcons.FastRewind
    Action.VOLUME_UP -> KnobIcons.VolumeUp
    Action.VOLUME_DOWN -> KnobIcons.VolumeDown
    Action.ANNOUNCE -> KnobIcons.RecordVoice
    Action.LIKE -> KnobIcons.Favorite
    Action.SHUFFLE -> KnobIcons.Shuffle
    Action.REPEAT -> KnobIcons.Repeat
    Action.SLEEP_TIMER -> KnobIcons.Bedtime
    Action.OPEN_PLAYER -> KnobIcons.MusicNote
    Action.RESTART -> KnobIcons.Replay
    Action.SWITCH_PLAYER -> KnobIcons.SwapHoriz
    Action.FLASHLIGHT -> KnobIcons.Flashlight
    Action.RINGER -> KnobIcons.Vibration
    Action.TIME_LEFT -> KnobIcons.Schedule
    Action.POCKET -> KnobIcons.PhoneDark
}

fun gestureLabel(g: Gesture) = when (g) {
    Gesture.HOLD_UP -> R.string.gesture_hold_up
    Gesture.HOLD_DOWN -> R.string.gesture_hold_down
    Gesture.LONG_HOLD_UP -> R.string.gesture_long_hold_up
    Gesture.LONG_HOLD_DOWN -> R.string.gesture_long_hold_down
    Gesture.BOTH_HOLD -> R.string.gesture_both_hold
    Gesture.TRIPLE_UP -> R.string.gesture_triple_up
    Gesture.TRIPLE_DOWN -> R.string.gesture_triple_down
    Gesture.UP_DOWN -> R.string.gesture_up_down
    Gesture.DOWN_UP -> R.string.gesture_down_up
    Gesture.TWIST -> R.string.gesture_twist
    Gesture.WAVE -> R.string.gesture_wave
    Gesture.BOTH -> R.string.gesture_both
    Gesture.TAP_HOLD_UP -> R.string.gesture_tap_hold_up
    Gesture.TAP_HOLD_DOWN -> R.string.gesture_tap_hold_down
    Gesture.DOUBLE_UP -> R.string.gesture_double_up
    Gesture.DOUBLE_DOWN -> R.string.gesture_double_down
    Gesture.BACK_TAP_2 -> R.string.gesture_back_tap_2
    Gesture.BACK_TAP_3 -> R.string.gesture_back_tap_3
}

private class SetupStep(
    val title: Int,
    val body: Int,
    val button: Int,
    val done: Boolean,
    /** Not required to finish setup; [label] explains when it is needed. */
    val label: Int? = null,
    val onClick: () -> Unit,
)

@Composable
fun SetupCard(setup: SetupState, actions: ScreenActions) {
    val scheme = MaterialTheme.colorScheme
    val steps = buildList {
        when (setup.restricted) {
            Restricted.NOT_APPLICABLE -> Unit
            Restricted.ALLOWED, Restricted.BLOCKED -> add(
                SetupStep(
                    R.string.step_restricted_title, R.string.step_restricted_body, R.string.step_restricted_button,
                    done = setup.restricted == Restricted.ALLOWED, onClick = actions.openAppInfo,
                ),
            )
            // Can't tell on this Android version: show it as a hint that never blocks the next step.
            Restricted.UNKNOWN -> add(
                SetupStep(
                    R.string.step_restricted_title, R.string.step_restricted_body, R.string.step_restricted_button,
                    done = false, label = R.string.step_restricted_if_grey, onClick = actions.openAppInfo,
                ),
            )
        }
        add(
            SetupStep(
                R.string.step_service_title, R.string.step_service_body, R.string.step_service_button,
                done = setup.serviceEnabled, onClick = actions.openAccessibility,
            ),
        )
        add(
            SetupStep(
                R.string.step_player_title, R.string.step_player_body, R.string.step_player_button,
                done = setup.playerAccess, label = R.string.step_optional, onClick = actions.openNotificationAccess,
            ),
        )
    }
    val current = steps.indexOfFirst { !it.done && it.label == null }
        .takeIf { it >= 0 } ?: steps.indexOfFirst { !it.done }
    val left = steps.count { !it.done && it.label == null }
    val numberShapes = listOf(MaterialShapes.Cookie4Sided, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf)

    Surface(
        shape = RoundedCornerShape(36.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.displaySmallEmphasized)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.setup_steps_left, left), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            steps.forEachIndexed { i, step ->
                val active = i == current
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip((if (step.done) MaterialShapes.Circle else numberShapes[i % numberShapes.size]).toShape())
                            .background(if (step.done || active) scheme.primary else scheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (step.done) {
                            Icon(KnobIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
                        } else {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            stringResource(step.title) + (step.label?.let { " · " + stringResource(it) } ?: ""),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        if (!step.done) {
                            if (active || step.label != null && i < current) {
                                Spacer(Modifier.height(4.dp))
                                Text(stringResource(step.body), style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(10.dp))
                            if (active) {
                                Button(
                                    onClick = step.onClick,
                                    shapes = ButtonDefaults.shapes(),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                ) {
                                    Text(stringResource(step.button), style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(KnobIcons.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            } else {
                                FilledTonalButton(onClick = step.onClick, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(step.button))
                                }
                            }
                        }
                    }
                }
                if (i != steps.lastIndex) Spacer(Modifier.height(16.dp))
            }
        }
    }
}
