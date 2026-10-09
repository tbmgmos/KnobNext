@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.knobnext.Prefs
import com.knobnext.R

private class PrefState<T>(initial: T, private val save: (T) -> Unit) {
    var value by mutableStateOf(initial)
        private set

    fun update(v: T) {
        value = v
        save(v)
    }
}

@Composable
fun KnobScreen(
    serviceEnabled: Boolean,
    prefs: Prefs,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val enabled = remember { PrefState(prefs.enabled) { prefs.enabled = it } }
    val onlyWhenPlaying = remember { PrefState(prefs.onlyWhenPlaying) { prefs.onlyWhenPlaying = it } }
    val swap = remember { PrefState(prefs.swap) { prefs.swap = it } }
    val combo = remember { PrefState(prefs.comboPlayPause) { prefs.comboPlayPause = it } }
    val vibrate = remember { PrefState(prefs.vibrate) { prefs.vibrate = it } }
    val longPress = remember { PrefState(prefs.longPressMs) { prefs.longPressMs = it } }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = { Text(stringResource(R.string.tagline)) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { StatusHero(serviceEnabled, onOpenAccessibility) }
            if (!serviceEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        RestrictedCard(onOpenAppInfo)
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.gestures_title)) }
            val gestures = listOf(
                Gesture(
                    MaterialShapes.Sunny,
                    if (swap.value) KnobIcons.SkipPrevious else KnobIcons.SkipNext,
                    R.string.gesture_hold_up,
                    if (swap.value) R.string.action_prev else R.string.action_next,
                ),
                Gesture(
                    MaterialShapes.Cookie4Sided,
                    if (swap.value) KnobIcons.SkipNext else KnobIcons.SkipPrevious,
                    R.string.gesture_hold_down,
                    if (swap.value) R.string.action_next else R.string.action_prev,
                ),
                Gesture(MaterialShapes.Clover4Leaf, KnobIcons.PlayPause, R.string.gesture_both, R.string.action_play_pause),
                Gesture(MaterialShapes.Circle, KnobIcons.VolumeUp, R.string.gesture_tap, R.string.action_volume),
            )
            gestures.forEachIndexed { i, g ->
                item { GestureRow(g, groupShape(i, gestures.size), dimmed = i == 2 && !combo.value) }
            }

            item { SectionTitle(stringResource(R.string.settings_title)) }
            item {
                SwitchRow(
                    stringResource(R.string.pref_enabled), null, enabled.value, enabled::update,
                    groupShape(0, 6), emphasized = true,
                )
            }
            item {
                SwitchRow(
                    stringResource(R.string.pref_only_when_playing),
                    stringResource(R.string.pref_only_when_playing_hint),
                    onlyWhenPlaying.value, onlyWhenPlaying::update, groupShape(1, 6), enabled = enabled.value,
                )
            }
            item {
                SwitchRow(
                    stringResource(R.string.pref_swap), stringResource(R.string.pref_swap_hint),
                    swap.value, swap::update, groupShape(2, 6), enabled = enabled.value,
                )
            }
            item {
                SwitchRow(
                    stringResource(R.string.pref_combo), null,
                    combo.value, combo::update, groupShape(3, 6), enabled = enabled.value,
                )
            }
            item {
                SwitchRow(
                    stringResource(R.string.pref_vibrate), null,
                    vibrate.value, vibrate::update, groupShape(4, 6), enabled = enabled.value,
                )
            }
            item { LongPressRow(longPress.value, longPress::update, groupShape(5, 6), enabled.value) }
        }
    }
}

@Composable
private fun StatusHero(enabled: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (enabled) scheme.primaryContainer else scheme.errorContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "heroContainer",
    )
    val content by animateColorAsState(
        if (enabled) scheme.onPrimaryContainer else scheme.onErrorContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "heroContent",
    )
    val badge by animateColorAsState(
        if (enabled) scheme.primary else scheme.error,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "heroBadge",
    )
    val onBadge = if (enabled) scheme.onPrimary else scheme.onError

    val spin = rememberInfiniteTransition(label = "spin")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )

    Surface(
        shape = RoundedCornerShape(36.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val popIn = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
        Column(Modifier.padding(24.dp)) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = enabled,
                    transitionSpec = {
                        (scaleIn(popIn) + fadeIn())
                            .togetherWith(scaleOut() + fadeOut())
                    },
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
private fun RestrictedCard(onOpenAppInfo: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(
                    MaterialShapes.Gem,
                    KnobIcons.Lock,
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.onTertiary,
                    size = 40,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.restricted_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.restricted_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onOpenAppInfo, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.open_app_info))
            }
        }
    }
}

private data class Gesture(val shape: RoundedPolygon, val icon: ImageVector, val trigger: Int, val action: Int)

@Composable
private fun GestureRow(g: Gesture, shape: Shape, dimmed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = scheme.surfaceBright,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (dimmed) 0.45f else 1f },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeBadge(g.shape, g.icon, scheme.secondaryContainer, scheme.onSecondaryContainer)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(g.trigger), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    stringResource(g.action),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShapeBadge(polygon: RoundedPolygon, icon: ImageVector, bg: Color, fg: Color, size: Int = 48) {
    Box(
        Modifier
            .size(size.dp)
            .clip(polygon.toShape())
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    shape: Shape,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { onChange(!checked) },
        enabled = enabled,
        shape = shape,
        color = if (emphasized && checked) scheme.primaryContainer else scheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                thumbContent = {
                    Icon(
                        if (checked) KnobIcons.Check else KnobIcons.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun LongPressRow(ms: Int, onChange: (Int) -> Unit, shape: Shape, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = shape, color = scheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.pref_long_press),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.ms_value, ms),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = scheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = ms.toFloat(),
                onValueChange = {
                    val stepped = (Math.round(it / Prefs.LONG_PRESS_STEP) * Prefs.LONG_PRESS_STEP)
                        .coerceIn(Prefs.LONG_PRESS_MIN, Prefs.LONG_PRESS_MAX)
                    if (stepped != ms) onChange(stepped)
                },
                valueRange = Prefs.LONG_PRESS_MIN.toFloat()..Prefs.LONG_PRESS_MAX.toFloat(),
                enabled = enabled,
            )
            Text(
                stringResource(R.string.pref_long_press_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp),
    )
}

private fun groupShape(index: Int, count: Int): Shape {
    val outer = 28.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}
