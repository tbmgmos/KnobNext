@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.knobnext.Action
import com.knobnext.DoubleMode
import com.knobnext.Gesture
import com.knobnext.GestureGroup
import com.knobnext.Media
import com.knobnext.NowPlaying
import com.knobnext.NowPlayingTracker
import com.knobnext.Prefs
import com.knobnext.R
import com.knobnext.Release
import com.knobnext.TapSensitivity
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

private enum class Page(val title: Int) {
    HOME(R.string.app_name),
    KEYS(R.string.section_keys),
    SEQUENCES(R.string.section_double),
    MOTION(R.string.section_motion),
    PLAYERS(R.string.players_title),
    SETTINGS(R.string.settings_title),
}

/** Everything a page needs, so page builders stay small. */
private class PageContext(
    val s: com.knobnext.Settings,
    val prefs: Prefs,
    val setup: SetupState,
    val actions: ScreenActions,
    val versionName: String,
    val updatesSupported: Boolean,
    val edit: (Gesture) -> Unit,
    val open: (Page) -> Unit,
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
    val updatesSupported = remember { context.resources.getBoolean(R.bool.update_check) }

    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val stop = prefs.observe { revision++ }
        onDispose { stop() }
    }
    val s = remember(revision) { prefs.snapshot() }

    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(setup.playerAccess) {
        val tracker = if (setup.playerAccess) NowPlayingTracker(context, prefs) { nowPlaying = it } else null
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

    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
    val ctx = PageContext(s, prefs, setup, actions, versionName, updatesSupported, { editing = it }, { page = it })

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(page.title)) },
                subtitle = if (page == Page.HOME) {
                    { Text(stringResource(R.string.tagline)) }
                } else {
                    null
                },
                navigationIcon = {
                    if (page != Page.HOME) {
                        IconButton(onClick = { page = Page.HOME }) {
                            Icon(KnobIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn())
                    .togetherWith(slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "page",
        ) { current ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (current) {
                    Page.HOME -> homePage(ctx, update, nowPlaying, media)
                    Page.KEYS -> keysPage(ctx)
                    Page.SEQUENCES -> sequencesPage(ctx)
                    Page.MOTION -> motionPage(ctx)
                    Page.PLAYERS -> playersPage(ctx)
                    Page.SETTINGS -> settingsPage(ctx)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Home

private fun LazyListScope.homePage(ctx: PageContext, update: Release?, nowPlaying: NowPlaying?, media: Media) {
    val s = ctx.s
    val setup = ctx.setup
    update?.let { release ->
        item { UpdateCard(release) { ctx.actions.openUrl(release.apkUrl ?: release.pageUrl) } }
    }
    if (!setup.serviceEnabled) {
        item { SetupCard(setup, ctx.actions) }
    } else {
        item { StatusHero(s.enabled) { ctx.prefs.enabled = it } }
        if (!setup.serviceRunning) {
            item {
                InfoCard(
                    title = stringResource(R.string.not_running_title),
                    body = stringResource(R.string.not_running_body),
                    button = stringResource(R.string.open_accessibility),
                    onClick = ctx.actions.openAccessibility,
                ) { Badge(MaterialShapes.Pentagon, KnobIcons.Close, error = true) }
            }
        }
        if (s.doubleMode != DoubleMode.OFF && !setup.batteryUnrestricted) {
            item {
                InfoCard(
                    title = stringResource(R.string.battery_title),
                    body = stringResource(
                        if (ctx.actions.openAutostart != null) R.string.battery_body_xiaomi else R.string.battery_body,
                    ),
                    button = stringResource(R.string.battery_button),
                    onClick = ctx.actions.openBattery,
                    secondary = ctx.actions.openAutostart?.let { stringResource(R.string.autostart_button) to it },
                ) { Badge(MaterialShapes.Arch, KnobIcons.Lock) }
            }
        }
        if (!setup.playerAccess) {
            item {
                InfoCard(
                    title = stringResource(R.string.access_title),
                    body = stringResource(R.string.access_body),
                    button = stringResource(R.string.access_button),
                    onClick = ctx.actions.openNotificationAccess,
                ) { Badge(MaterialShapes.Flower, KnobIcons.MusicNote) }
            }
        }
    }
    nowPlaying?.let { now -> item { NowPlayingCard(now, media) } }

    item {
        Column {
            SectionTitle(stringResource(R.string.gestures_title))
            Group(3) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PageTile(
                        0, MaterialShapes.Sunny, KnobIcons.VolumeUp, R.string.section_keys,
                        stringResource(R.string.tile_keys, assigned(s, GestureGroup.KEYS)),
                    ) { ctx.open(Page.KEYS) }
                    PageTile(
                        1, MaterialShapes.Pill, KnobIcons.SkipNext, R.string.section_double,
                        if (s.doubleMode == DoubleMode.OFF) {
                            stringResource(R.string.tile_off)
                        } else {
                            stringResource(
                                R.string.tile_sequences,
                                stringResource(if (s.doubleMode == DoubleMode.ALWAYS) R.string.mode_always_long else R.string.mode_screen_off_long),
                            )
                        },
                    ) { ctx.open(Page.SEQUENCES) }
                    val motionOn = listOf(s.backTap, s.flip, s.twist, s.wave).count { it }
                    PageTile(
                        2, MaterialShapes.SoftBurst, KnobIcons.TouchApp, R.string.section_motion,
                        if (motionOn == 0) stringResource(R.string.tile_off) else stringResource(R.string.tile_motion, motionOn),
                    ) { ctx.open(Page.MOTION) }
                }
            }
        }
    }
    item {
        Group(2) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PageTile(
                    0, MaterialShapes.Cookie12Sided, KnobIcons.MusicNote, R.string.players_title,
                    if (s.appFilter && setup.playerAccess) {
                        stringResource(R.string.tile_players_some, s.allowedApps.size)
                    } else {
                        stringResource(R.string.tile_players_all)
                    },
                ) { ctx.open(Page.PLAYERS) }
                PageTile(
                    1, MaterialShapes.Cookie9Sided, KnobIcons.Settings, R.string.settings_title,
                    stringResource(R.string.tile_settings),
                ) { ctx.open(Page.SETTINGS) }
            }
        }
    }
}

private fun assigned(s: com.knobnext.Settings, group: GestureGroup) =
    s.actions.count { (g, a) -> g.group == group && a != Action.NONE }

// ---------------------------------------------------------------- Keys

private fun LazyListScope.keysPage(ctx: PageContext) {
    val s = ctx.s
    item {
        GestureGroupCard(
            ctx,
            listOf(
                Gesture.HOLD_UP to MaterialShapes.Sunny,
                Gesture.HOLD_DOWN to MaterialShapes.Cookie4Sided,
                Gesture.BOTH to MaterialShapes.Clover4Leaf,
            ),
            accent = false,
            footer = true,
        )
    }
    item {
        MoreGestures {
            GestureGroupCard(
                ctx,
                listOf(
                    Gesture.TAP_HOLD_UP to MaterialShapes.Cookie6Sided,
                    Gesture.TAP_HOLD_DOWN to MaterialShapes.Cookie7Sided,
                    Gesture.LONG_HOLD_UP to MaterialShapes.VerySunny,
                    Gesture.LONG_HOLD_DOWN to MaterialShapes.Cookie9Sided,
                    Gesture.BOTH_HOLD to MaterialShapes.Clover8Leaf,
                ),
                accent = false,
            )
        }
    }
    item { Group(1) { LongPressRow(shape(0), s.longPressMs, { ctx.prefs.longPressMs = it }, s.enabled) } }
    item { Hint(stringResource(R.string.section_keys_hint)) }
}

// ---------------------------------------------------------------- Sequences

private fun LazyListScope.sequencesPage(ctx: PageContext) {
    val s = ctx.s
    val on = s.doubleMode != DoubleMode.OFF
    item {
        Group(1) {
            ControlRow(shape(0), stringResource(R.string.double_when), enabled = s.enabled) {
                ConnectedChoice(
                    options = listOf(
                        DoubleMode.OFF to R.string.double_mode_off,
                        DoubleMode.SCREEN_OFF to R.string.double_mode_screen_off,
                        DoubleMode.ALWAYS to R.string.double_mode_always,
                    ),
                    selected = s.doubleMode,
                    onSelect = { ctx.prefs.doubleMode = it },
                    enabled = s.enabled,
                )
            }
        }
    }
    item {
        GestureGroupCard(
            ctx,
            listOf(
                Gesture.DOUBLE_UP to MaterialShapes.Pill,
                Gesture.DOUBLE_DOWN to MaterialShapes.Arch,
                Gesture.UP_DOWN to MaterialShapes.Gem,
            ),
            accent = true,
            enabled = on,
        )
    }
    item {
        MoreGestures {
            GestureGroupCard(
                ctx,
                listOf(
                    Gesture.TRIPLE_UP to MaterialShapes.Triangle,
                    Gesture.TRIPLE_DOWN to MaterialShapes.Diamond,
                    Gesture.DOWN_UP to MaterialShapes.Bun,
                ),
                accent = true,
                enabled = on,
            )
        }
    }
    item { Hint(stringResource(R.string.section_double_hint)) }
}

// ---------------------------------------------------------------- Motion

private fun LazyListScope.motionPage(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(if (s.backTap) 4 else 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.back_tap_title), s.backTap, { prefs.backTap = it },
                    subtitle = stringResource(R.string.back_tap_subtitle), enabled = s.enabled,
                    leading = { Badge(MaterialShapes.SoftBurst, KnobIcons.TouchApp, primary = true) },
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
                        GestureRow(2, Gesture.BACK_TAP_2, s, MaterialShapes.Puffy, accent = true, onEdit = ctx.edit)
                        GestureRow(3, Gesture.BACK_TAP_3, s, MaterialShapes.Flower, accent = true, onEdit = ctx.edit)
                    }
                }
            }
        }
    }
    item {
        Group(1) {
            SwitchRow(
                shape(0), stringResource(R.string.flip_title), s.flip, { prefs.flip = it },
                subtitle = stringResource(R.string.flip_subtitle), enabled = s.enabled,
                leading = { Badge(MaterialShapes.Square, KnobIcons.FlipDown, primary = true) },
            )
        }
    }
    item { MotionToggle(ctx, R.string.twist_title, R.string.twist_subtitle, s.twist, { prefs.twist = it }, Gesture.TWIST, MaterialShapes.Burst, KnobIcons.ScreenRotation) }
    item { MotionToggle(ctx, R.string.wave_title, R.string.wave_subtitle, s.wave, { prefs.wave = it }, Gesture.WAVE, MaterialShapes.Ghostish, KnobIcons.PanTool) }
    item { Hint(stringResource(R.string.motion_hint)) }
}

@Composable
private fun MotionToggle(
    ctx: PageContext,
    title: Int,
    subtitle: Int,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    gesture: Gesture,
    polygon: RoundedPolygon,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Group(if (on) 2 else 1) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SwitchRow(
                shape(0), stringResource(title), on, onChange,
                subtitle = stringResource(subtitle), enabled = ctx.s.enabled,
                leading = { Badge(polygon, icon, primary = true) },
            )
            AnimatedVisibility(on) {
                GestureRow(1, gesture, ctx.s, polygon, accent = true, onEdit = ctx.edit)
            }
        }
    }
}

// ---------------------------------------------------------------- Players

private fun LazyListScope.playersPage(ctx: PageContext) {
    item { PlayersList(ctx) }
    item { Hint(stringResource(R.string.players_hint)) }
}

@Composable
private fun PlayersList(ctx: PageContext) {
    val s = ctx.s
    val access = ctx.setup.playerAccess
    val context = LocalContext.current
    val players = remember(s.seenPlayers) {
        s.seenPlayers
            .map { pkg -> Triple(pkg, appLabel(context, pkg), appIcon(context, pkg)?.asImageBitmap()) }
            .sortedBy { it.second.lowercase() }
    }
    val showList = access && s.appFilter
    Group(1 + if (showList) maxOf(players.size, 1) else 0) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SwitchRow(
                shape(0), stringResource(R.string.pref_app_filter), s.appFilter && access, { ctx.prefs.appFilter = it },
                subtitle = stringResource(if (access) R.string.pref_app_filter_hint else R.string.pref_app_filter_no_access),
                enabled = access && s.enabled,
            )
            if (showList) {
                if (players.isEmpty()) ListRow(shape(1), stringResource(R.string.players_empty), enabled = false)
                players.forEachIndexed { i, (pkg, label, icon) ->
                    val checked = pkg in s.allowedApps
                    val toggle = { on: Boolean -> ctx.prefs.allowedApps = if (on) s.allowedApps + pkg else s.allowedApps - pkg }
                    ListRow(
                        shape = shape(i + 1),
                        title = label,
                        onClick = { toggle(!checked) },
                        leading = { AppIcon(icon) },
                        trailing = { Switch(checked = checked, onCheckedChange = toggle) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

private fun LazyListScope.settingsPage(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(4) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.pref_only_when_playing), s.onlyWhenPlaying, { prefs.onlyWhenPlaying = it },
                    subtitle = stringResource(R.string.pref_only_when_playing_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(1), stringResource(R.string.pref_headphones), s.headphonesOnly, { prefs.headphonesOnly = it },
                    subtitle = stringResource(R.string.pref_headphones_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(2), stringResource(R.string.pref_pocket), s.pocketGuard, { prefs.pocketGuard = it },
                    subtitle = stringResource(R.string.pref_pocket_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(3), stringResource(R.string.pref_vibrate), s.vibrate, { prefs.vibrate = it },
                    subtitle = stringResource(R.string.pref_vibrate_hint), enabled = s.enabled,
                )
            }
        }
    }
    item {
        val sleepActive = s.sleepUntil > System.currentTimeMillis()
        Group(1) {
            ControlRow(
                shape(0),
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
    item {
        Group(2) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ListRow(
                    shape(0), stringResource(R.string.open_accessibility),
                    subtitle = stringResource(R.string.open_accessibility_hint),
                    onClick = ctx.actions.openAccessibility,
                    trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                )
                if (ctx.updatesSupported) {
                    SwitchRow(
                        shape(1), stringResource(R.string.pref_check_updates), s.checkUpdates, { prefs.checkUpdates = it },
                        subtitle = stringResource(R.string.version_value, ctx.versionName),
                    )
                } else {
                    ListRow(shape(1), stringResource(R.string.app_name), stringResource(R.string.version_value, ctx.versionName))
                }
            }
        }
    }
    item { EventLog(s.lastEvent) }
}

// ---------------------------------------------------------------- Building blocks

@Composable
private fun Badge(polygon: RoundedPolygon, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean = false, error: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    when {
        error -> ShapeBadge(polygon, icon, scheme.error, scheme.onError, size = 40)
        primary -> ShapeBadge(polygon, icon, scheme.primaryContainer, scheme.onPrimaryContainer)
        else -> ShapeBadge(polygon, icon, scheme.tertiary, scheme.onTertiary, size = 40)
    }
}

@Composable
private fun GroupScope.PageTile(
    index: Int,
    polygon: RoundedPolygon,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    subtitle: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ListRow(
        shape = shape(index),
        title = stringResource(title),
        subtitle = subtitle,
        onClick = onClick,
        leading = { ShapeBadge(polygon, icon, scheme.secondaryContainer, scheme.onSecondaryContainer, size = 52) },
        trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant) },
    )
}

/** One gesture → action row; tapping it opens the action picker. */
@Composable
private fun GroupScope.GestureRow(
    index: Int,
    gesture: Gesture,
    s: com.knobnext.Settings,
    badge: RoundedPolygon,
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
        enabled = enabled && s.enabled,
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
private fun GestureGroupCard(
    ctx: PageContext,
    rows: List<Pair<Gesture, RoundedPolygon>>,
    accent: Boolean,
    enabled: Boolean = true,
    footer: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Group(rows.size + if (footer) 1 else 0) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { i, (g, polygon) ->
                GestureRow(i, g, ctx.s, polygon, accent, enabled, ctx.edit)
            }
            if (footer) {
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
    }
}

/** Less common gestures stay folded so the page isn't a wall of options. */
@Composable
private fun MoreGestures(content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(stringResource(if (open) R.string.more_gestures_hide else R.string.more_gestures))
            Icon(
                if (open) KnobIcons.ExpandLess else KnobIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
        AnimatedVisibility(open) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
