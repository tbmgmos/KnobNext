@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.knobnext.DoubleMode
import com.knobnext.Gesture
import com.knobnext.TapSensitivity
import androidx.compose.animation.AnimatedVisibility
import com.knobnext.Media
import com.knobnext.NowPlaying
import com.knobnext.NowPlayingTracker
import com.knobnext.Prefs
import com.knobnext.R
import com.knobnext.Release
import com.knobnext.Updates
import com.knobnext.appIcon
import com.knobnext.appLabel

class ScreenActions(
    val openAccessibility: () -> Unit,
    val openAppInfo: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val openUrl: (String) -> Unit,
    val openBattery: () -> Unit,
    /** Only on phones that need it (Xiaomi family). */
    val openAutostart: (() -> Unit)?,
)

data class SetupState(
    val restricted: com.knobnext.Restricted,
    val serviceEnabled: Boolean,
    val playerAccess: Boolean,
    val batteryUnrestricted: Boolean,
    /** Enabled in settings but not bound: the service keeps crashing or the system dropped it. */
    val serviceRunning: Boolean,
)

@Composable
fun KnobScreen(
    setup: SetupState,
    versionName: String,
    prefs: Prefs,
    actions: ScreenActions,
) {
    val context = LocalContext.current
    val media = remember { Media(context) }
    val notificationAccess = setup.playerAccess
    val updatesSupported = remember { context.resources.getBoolean(R.bool.update_check) }

    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val stop = prefs.observe { revision++ }
        onDispose { stop() }
    }
    val s = remember(revision) { prefs.snapshot() }

    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(notificationAccess) {
        val tracker = if (notificationAccess) NowPlayingTracker(context, prefs) { nowPlaying = it } else null
        if (tracker == null) nowPlaying = null else tracker.start()
        onDispose { tracker?.stop() }
    }

    var update by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(s.checkUpdates) {
        update = if (updatesSupported && s.checkUpdates) {
            Updates.latest()?.takeIf { Updates.isNewer(it.version, versionName) }
        } else {
            null
        }
    }

    var editing by remember { mutableStateOf<Gesture?>(null) }
    editing?.let { g ->
        ActionDialog(
            gesture = g,
            current = s.actions.getValue(g),
            onPick = {
                prefs.setAction(g, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            update?.let { release ->
                item { UpdateCard(release) { actions.openUrl(release.apkUrl ?: release.pageUrl) } }
            }
            if (!setup.serviceEnabled) {
                item { SetupCard(setup, actions) }
            } else {
                item { StatusHero(true, actions.openAccessibility) }
                if (!setup.serviceRunning) {
                    item {
                        InfoCard(
                            title = stringResource(R.string.not_running_title),
                            body = stringResource(R.string.not_running_body),
                            button = stringResource(R.string.open_accessibility),
                            onClick = actions.openAccessibility,
                        ) {
                            ShapeBadge(
                                MaterialShapes.Pentagon, KnobIcons.Close,
                                MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, size = 40,
                            )
                        }
                    }
                }
                if (!notificationAccess) {
                    item {
                        InfoCard(
                            title = stringResource(R.string.access_title),
                            body = stringResource(R.string.access_body),
                            button = stringResource(R.string.access_button),
                            onClick = actions.openNotificationAccess,
                        ) {
                            ShapeBadge(
                                MaterialShapes.Flower, KnobIcons.MusicNote,
                                MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, size = 40,
                            )
                        }
                    }
                }
            }
            if (setup.serviceEnabled && s.doubleMode != DoubleMode.OFF && !setup.batteryUnrestricted) {
                item {
                    InfoCard(
                        title = stringResource(R.string.battery_title),
                        body = stringResource(
                            if (actions.openAutostart != null) R.string.battery_body_xiaomi else R.string.battery_body,
                        ),
                        button = stringResource(R.string.battery_button),
                        onClick = actions.openBattery,
                        secondary = actions.openAutostart?.let { stringResource(R.string.autostart_button) to it },
                    ) {
                        ShapeBadge(
                            MaterialShapes.Arch, KnobIcons.Lock,
                            MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, size = 40,
                        )
                    }
                }
            }
            if (notificationAccess) {
                nowPlaying?.let { now -> item { NowPlayingCard(now, media) } }
            }

            item { VolumeKeysSection(s) { editing = it } }
            item { SequenceSection(s, prefs) { editing = it } }
            item { MotionSection(s, prefs) { editing = it } }
            item { SettingsSection(s, prefs) }
            item { PlayersSection(s, prefs, notificationAccess) }
            item { AboutSection(s, prefs, versionName, updatesSupported) }
        }
    }
}

/** One gesture → action row; tapping it opens the action picker. */
@Composable
private fun GroupScope.GestureRow(
    index: Int,
    gesture: Gesture,
    s: com.knobnext.Settings,
    badge: androidx.graphics.shapes.RoundedPolygon,
    accent: Boolean,
    enabled: Boolean = true,
    onEdit: (Gesture) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val action = s.actions.getValue(gesture)
    ListRow(
        shape = shape(index),
        title = stringResource(gestureLabel(gesture)),
        subtitle = stringResource(actionLabel(action)),
        enabled = enabled,
        onClick = { onEdit(gesture) },
        leading = {
            ShapeBadge(
                badge, actionIcon(action),
                if (accent) scheme.tertiaryContainer else scheme.secondaryContainer,
                if (accent) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
            )
        },
    )
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}

@Composable
private fun VolumeKeysSection(s: com.knobnext.Settings, onEdit: (Gesture) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rows = listOf(
        Gesture.HOLD_UP to MaterialShapes.Sunny,
        Gesture.HOLD_DOWN to MaterialShapes.Cookie4Sided,
        Gesture.LONG_HOLD_UP to MaterialShapes.VerySunny,
        Gesture.LONG_HOLD_DOWN to MaterialShapes.Cookie9Sided,
        Gesture.TAP_HOLD_UP to MaterialShapes.Cookie6Sided,
        Gesture.TAP_HOLD_DOWN to MaterialShapes.Cookie7Sided,
        Gesture.BOTH to MaterialShapes.Clover4Leaf,
        Gesture.BOTH_HOLD to MaterialShapes.Clover8Leaf,
    )
    Column {
        SectionTitle(stringResource(R.string.section_keys))
        Group(rows.size + 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEachIndexed { i, (g, shape) -> GestureRow(i, g, s, shape, accent = false, onEdit = onEdit) }
                ListRow(
                    shape = shape(rows.size),
                    title = stringResource(R.string.gesture_tap),
                    subtitle = stringResource(R.string.action_volume),
                    leading = {
                        ShapeBadge(MaterialShapes.Circle, KnobIcons.VolumeUp, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
                    },
                )
            }
        }
        SectionHint(stringResource(R.string.section_keys_hint))
    }
}

@Composable
private fun SequenceSection(s: com.knobnext.Settings, prefs: Prefs, onEdit: (Gesture) -> Unit) {
    val on = s.doubleMode != DoubleMode.OFF
    val rows = listOf(
        Gesture.DOUBLE_UP to MaterialShapes.Pill,
        Gesture.DOUBLE_DOWN to MaterialShapes.Arch,
        Gesture.TRIPLE_UP to MaterialShapes.Triangle,
        Gesture.TRIPLE_DOWN to MaterialShapes.Diamond,
        Gesture.UP_DOWN to MaterialShapes.Gem,
        Gesture.DOWN_UP to MaterialShapes.Bun,
    )
    Column {
        SectionTitle(stringResource(R.string.section_double))
        Group(rows.size + 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ControlRow(shape(0), stringResource(R.string.double_when), enabled = s.enabled) {
                    ConnectedChoice(
                        options = listOf(
                            DoubleMode.OFF to R.string.double_mode_off,
                            DoubleMode.SCREEN_OFF to R.string.double_mode_screen_off,
                            DoubleMode.ALWAYS to R.string.double_mode_always,
                        ),
                        selected = s.doubleMode,
                        onSelect = { prefs.doubleMode = it },
                        enabled = s.enabled,
                    )
                }
                rows.forEachIndexed { i, (g, shape) ->
                    GestureRow(i + 1, g, s, shape, accent = true, enabled = on, onEdit = onEdit)
                }
            }
        }
        SectionHint(stringResource(R.string.section_double_hint))
        if (on) EventLog(s.lastEvent)
    }
}

@Composable
private fun MotionSection(s: com.knobnext.Settings, prefs: Prefs, onEdit: (Gesture) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.section_motion))

        Group(if (s.backTap) 4 else 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.back_tap_title), s.backTap, { prefs.backTap = it },
                    subtitle = stringResource(R.string.back_tap_subtitle),
                    enabled = s.enabled,
                    leading = {
                        ShapeBadge(MaterialShapes.SoftBurst, KnobIcons.TouchApp, scheme.primaryContainer, scheme.onPrimaryContainer)
                    },
                )
                AnimatedVisibility(s.backTap) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ControlRow(shape(1), stringResource(R.string.back_tap_sensitivity), enabled = s.enabled) {
                            ConnectedChoice(
                                options = listOf(
                                    TapSensitivity.LOW to R.string.sensitivity_low,
                                    TapSensitivity.MEDIUM to R.string.sensitivity_medium,
                                    TapSensitivity.HIGH to R.string.sensitivity_high,
                                ),
                                selected = s.tapSensitivity,
                                onSelect = { prefs.tapSensitivity = it },
                                enabled = s.enabled,
                            )
                        }
                        GestureRow(2, Gesture.BACK_TAP_2, s, MaterialShapes.Puffy, accent = true, onEdit = onEdit)
                        GestureRow(3, Gesture.BACK_TAP_3, s, MaterialShapes.Flower, accent = true, onEdit = onEdit)
                    }
                }
            }
        }

        Group(1) {
            SwitchRow(
                shape(0), stringResource(R.string.flip_title), s.flip, { prefs.flip = it },
                subtitle = stringResource(R.string.flip_subtitle),
                enabled = s.enabled,
                leading = {
                    ShapeBadge(MaterialShapes.Square, KnobIcons.FlipDown, scheme.primaryContainer, scheme.onPrimaryContainer)
                },
            )
        }

        Group(if (s.twist) 2 else 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.twist_title), s.twist, { prefs.twist = it },
                    subtitle = stringResource(R.string.twist_subtitle),
                    enabled = s.enabled,
                    leading = {
                        ShapeBadge(MaterialShapes.Burst, KnobIcons.ScreenRotation, scheme.primaryContainer, scheme.onPrimaryContainer)
                    },
                )
                AnimatedVisibility(s.twist) {
                    GestureRow(1, Gesture.TWIST, s, MaterialShapes.Burst, accent = true, onEdit = onEdit)
                }
            }
        }

        Group(if (s.wave) 2 else 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.wave_title), s.wave, { prefs.wave = it },
                    subtitle = stringResource(R.string.wave_subtitle),
                    enabled = s.enabled,
                    leading = {
                        ShapeBadge(MaterialShapes.Ghostish, KnobIcons.PanTool, scheme.primaryContainer, scheme.onPrimaryContainer)
                    },
                )
                AnimatedVisibility(s.wave) {
                    GestureRow(1, Gesture.WAVE, s, MaterialShapes.Ghostish, accent = true, onEdit = onEdit)
                }
            }
        }
        SectionHint(stringResource(R.string.motion_hint))
    }
}

@Composable
private fun SettingsSection(s: com.knobnext.Settings, prefs: Prefs) {
    val sleepActive = s.sleepUntil > System.currentTimeMillis()
    Column {
        SectionTitle(stringResource(R.string.settings_title))
        Group(7) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(shape(0), stringResource(R.string.pref_enabled), s.enabled, { prefs.enabled = it }, highlighted = true)
                SwitchRow(
                    shape(1), stringResource(R.string.pref_only_when_playing), s.onlyWhenPlaying, { prefs.onlyWhenPlaying = it },
                    subtitle = stringResource(R.string.pref_only_when_playing_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(2), stringResource(R.string.pref_headphones), s.headphonesOnly, { prefs.headphonesOnly = it },
                    subtitle = stringResource(R.string.pref_headphones_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(3), stringResource(R.string.pref_pocket), s.pocketGuard, { prefs.pocketGuard = it },
                    subtitle = stringResource(R.string.pref_pocket_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(4), stringResource(R.string.pref_vibrate), s.vibrate, { prefs.vibrate = it },
                    subtitle = stringResource(R.string.pref_vibrate_hint), enabled = s.enabled,
                )
                LongPressRow(shape(5), s.longPressMs, { prefs.longPressMs = it }, s.enabled)
                ControlRow(
                    shape(6),
                    stringResource(R.string.pref_sleep),
                    caption = if (sleepActive) {
                        stringResource(
                            R.string.pref_sleep_active,
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.sleepUntil)),
                        )
                    } else {
                        stringResource(R.string.pref_sleep_hint)
                    },
                ) {
                    ConnectedChoice(
                        options = listOf(15 to R.string.minutes_15, 30 to R.string.minutes_30, 60 to R.string.minutes_60),
                        selected = s.sleepMinutes,
                        onSelect = { prefs.sleepMinutes = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayersSection(s: com.knobnext.Settings, prefs: Prefs, access: Boolean) {
    val context = LocalContext.current
    val players = remember(s.seenPlayers) {
        s.seenPlayers
            .map { pkg -> Triple(pkg, appLabel(context, pkg), appIcon(context, pkg)?.asImageBitmap()) }
            .sortedBy { it.second.lowercase() }
    }
    val showList = access && s.appFilter
    Column {
        SectionTitle(stringResource(R.string.players_title))
        val count = 1 + if (showList) maxOf(players.size, 1) else 0
        Group(count) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.pref_app_filter), s.appFilter && access, { prefs.appFilter = it },
                    subtitle = stringResource(if (access) R.string.pref_app_filter_hint else R.string.pref_app_filter_no_access),
                    enabled = access && s.enabled,
                )
                if (showList) {
                    if (players.isEmpty()) {
                        ListRow(shape(1), stringResource(R.string.players_empty), enabled = false)
                    }
                    players.forEachIndexed { i, (pkg, label, icon) ->
                        val checked = pkg in s.allowedApps
                        ListRow(
                            shape = shape(i + 1),
                            title = label,
                            onClick = { prefs.allowedApps = if (checked) s.allowedApps - pkg else s.allowedApps + pkg },
                            leading = { AppIcon(icon) },
                            trailing = {
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        prefs.allowedApps = if (on) s.allowedApps + pkg else s.allowedApps - pkg
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(s: com.knobnext.Settings, prefs: Prefs, versionName: String, updatesSupported: Boolean) {
    Column {
        SectionTitle(stringResource(R.string.about_title))
        Group(1) {
            val leading: @Composable () -> Unit = {
                Icon(KnobIcons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            if (updatesSupported) {
                SwitchRow(
                    shape(0), stringResource(R.string.pref_check_updates), s.checkUpdates, { prefs.checkUpdates = it },
                    subtitle = stringResource(R.string.version_value, versionName),
                    leading = leading,
                )
            } else {
                ListRow(shape(0), stringResource(R.string.app_name), stringResource(R.string.version_value, versionName))
            }
        }
    }
}
