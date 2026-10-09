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
import com.knobnext.Release

@Composable
fun StatusHero(enabled: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(
        if (enabled) scheme.primaryContainer else scheme.errorContainer, motion.defaultEffectsSpec(), label = "c",
    )
    val content by animateColorAsState(
        if (enabled) scheme.onPrimaryContainer else scheme.onErrorContainer, motion.defaultEffectsSpec(), label = "t",
    )
    val badge by animateColorAsState(
        if (enabled) scheme.primary else scheme.error, motion.defaultEffectsSpec(), label = "b",
    )
    val onBadge = if (enabled) scheme.onPrimary else scheme.onError
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
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = enabled,
                    transitionSpec = { (scaleIn(popIn) + fadeIn()).togetherWith(scaleOut() + fadeOut()) },
                    label = "heroShape",
                ) { on ->
                    Box(
                        Modifier
                            .size(96.dp)
                            .graphicsLayer { rotationZ = if (on) rotation else 0f }
                            .clip((if (on) MaterialShapes.Cookie9Sided else MaterialShapes.Pentagon).toShape())
                            .background(badge),
                    )
                }
                Icon(
                    if (enabled) KnobIcons.Check else KnobIcons.Close,
                    contentDescription = null,
                    tint = onBadge,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(if (enabled) R.string.status_on_title else R.string.status_off_title),
                style = MaterialTheme.typography.displaySmallEmphasized,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (enabled) R.string.status_on_body else R.string.status_off_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(20.dp))
            if (enabled) {
                FilledTonalButton(onClick = onOpen, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.open_accessibility))
                }
            } else {
                Button(
                    onClick = onOpen,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.enable_service), style = MaterialTheme.typography.titleMediumEmphasized)
                    Spacer(Modifier.width(8.dp))
                    Icon(KnobIcons.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun UpdateCard(release: Release, onDownload: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.primary,
        contentColor = scheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeBadge(MaterialShapes.SoftBurst, KnobIcons.Download, scheme.onPrimary, scheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(stringResource(R.string.update_body, release.version), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onDownload,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.onPrimary, contentColor = scheme.primary),
            ) {
                Text(stringResource(R.string.update_download))
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
            FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(button) }
        }
    }
}

@Composable
fun NowPlayingCard(now: NowPlaying, media: Media) {
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
                    OneLine(now.appLabel, MaterialTheme.typography.labelLarge, scheme.onSecondaryContainer.copy(alpha = 0.75f))
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
            Column {
                Action.entries.forEach { a ->
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
}

fun actionIcon(a: Action) = when (a) {
    Action.NONE -> KnobIcons.Block
    Action.NEXT -> KnobIcons.SkipNext
    Action.PREVIOUS -> KnobIcons.SkipPrevious
    Action.PLAY_PAUSE -> KnobIcons.PlayPause
    Action.SEEK_FORWARD -> KnobIcons.FastForward
    Action.SEEK_BACK -> KnobIcons.FastRewind
}

fun gestureLabel(g: Gesture) = when (g) {
    Gesture.HOLD_UP -> R.string.gesture_hold_up
    Gesture.HOLD_DOWN -> R.string.gesture_hold_down
    Gesture.BOTH -> R.string.gesture_both
    Gesture.DOUBLE_UP_OFF -> R.string.gesture_double_up_off
    Gesture.DOUBLE_DOWN_OFF -> R.string.gesture_double_down_off
}
