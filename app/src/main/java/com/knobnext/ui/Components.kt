@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.knobnext.Prefs
import com.knobnext.R

@Composable
fun ShapeBadge(polygon: RoundedPolygon, icon: ImageVector, bg: Color, fg: Color, size: Int = 48) {
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
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp),
    )
}

/** Android 16 style grouped list: big outer corners, small inner ones. */
@Composable
fun Group(count: Int, content: @Composable GroupScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        GroupScope(count).content()
    }
}

class GroupScope(private val count: Int) {
    fun shape(index: Int): Shape {
        val outer = 28.dp
        val inner = 6.dp
        return RoundedCornerShape(
            topStart = if (index == 0) outer else inner,
            topEnd = if (index == 0) outer else inner,
            bottomStart = if (index == count - 1) outer else inner,
            bottomEnd = if (index == count - 1) outer else inner,
        )
    }
}

@Composable
fun ListRow(
    shape: Shape,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val color = if (highlighted) scheme.primaryContainer else scheme.surfaceBright
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (highlighted) MaterialTheme.typography.titleLargeEmphasized
                    else MaterialTheme.typography.titleMedium,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, shape = shape, color = color, modifier = Modifier.fillMaxWidth()) {
            body()
        }
    } else {
        Surface(shape = shape, color = color, modifier = Modifier.fillMaxWidth()) { body() }
    }
}

@Composable
fun SwitchRow(
    shape: Shape,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    ListRow(
        shape = shape,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        highlighted = highlighted && checked,
        onClick = { onChange(!checked) },
        leading = leading,
        trailing = {
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
        },
    )
}

@Composable
fun LongPressRow(shape: Shape, ms: Int, onChange: (Int) -> Unit, enabled: Boolean) {
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
fun AppIcon(icon: ImageBitmap?, size: Int = 40) {
    if (icon != null) {
        Image(
            icon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(KnobIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
fun OneLine(text: String, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified) {
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** M3 Expressive connected button group used as a single-choice selector. */
@Composable
fun <T> ConnectedChoice(
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                enabled = enabled,
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
            ) {
                if (value == selected) {
                    Icon(KnobIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                OneLine(stringResource(label), MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A titled block inside a group: header text, optional caption and free content (choices, sliders). */
@Composable
fun ControlRow(
    shape: Shape,
    title: String,
    caption: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = shape, color = scheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Collapsed by default: the trace is for troubleshooting, not for everyday use. */
@Composable
fun EventLog(prefs: Prefs) {
    var open by remember { mutableStateOf(false) }
    var lastEvent by remember { mutableStateOf(prefs.lastEvent) }
    DisposableEffect(prefs) {
        lastEvent = prefs.lastEvent
        val stop = prefs.observeLog { lastEvent = prefs.lastEvent }
        onDispose { stop() }
    }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(stringResource(if (open) R.string.log_hide else R.string.log_show))
        }
        AnimatedVisibility(open) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    lastEvent ?: stringResource(R.string.last_event_none),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * Listens to the proximity sensor while visible and shows what it sees, plus how many wave gestures
 * the same detector the service uses has recognized.
 */
@Composable
fun WaveTester(shape: Shape, required: Int) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var near by remember { mutableStateOf(false) }
    var recognized by remember { mutableIntStateOf(0) }
    var available by remember { mutableStateOf(true) }
    val currentRequired by rememberUpdatedState(required)
    DisposableEffect(Unit) {
        val detector = com.knobnext.WaveDetector(
            context,
            required = { currentRequired },
            onWave = { recognized++ },
            onState = { isNear, _ -> near = isNear },
        )
        available = detector.available
        detector.start()
        onDispose { detector.stop() }
    }
    val badge by animateColorAsState(
        if (near) scheme.primary else scheme.surfaceContainerHighest,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "near",
    )
    val pop by animateFloatAsState(
        if (near) 1.12f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pop",
    )
    Surface(shape = shape, color = scheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = pop
                        scaleY = pop
                    }
                    .clip((if (near) MaterialShapes.Cookie4Sided else MaterialShapes.Circle).toShape())
                    .background(badge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    KnobIcons.PanTool,
                    contentDescription = null,
                    tint = if (near) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wave_test_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when {
                            !available -> R.string.wave_unavailable
                            near -> R.string.wave_near
                            else -> R.string.wave_far
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (near) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            Text(
                "$recognized",
                style = MaterialTheme.typography.headlineMediumEmphasized,
                color = scheme.primary,
            )
        }
    }
}
