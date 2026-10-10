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
import com.knobnext.Cue
import com.knobnext.CueGroup
import com.knobnext.CueStyle
import com.knobnext.CueVolume
import com.knobnext.Cues
import com.knobnext.Prefs
import com.knobnext.ProfilePreset
import com.knobnext.R
import com.knobnext.Release
import com.knobnext.SleepTimer
import com.knobnext.TapSensitivity
import com.knobnext.Updates
import com.knobnext.appIcon
import com.knobnext.appLabel
import kotlinx.coroutines.launch

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
    PROFILE(R.string.profile_title),
    SETTINGS(R.string.settings_title),
    TEST(R.string.test_title),
    DIAGNOSTICS(R.string.diag_title),
}

/** Where "back" leads from each page. */
private val Page.parent: Page
    get() = when (this) {
        Page.PROFILE -> Page.PLAYERS
        Page.DIAGNOSTICS -> Page.SETTINGS
        else -> Page.HOME
    }

/** Everything a page needs, so page builders stay small. */
private class PageContext(
    val s: com.knobnext.Settings,
    val prefs: Prefs,
    val setup: SetupState,
    val actions: ScreenActions,
    val versionName: String,
    val updatesSupported: Boolean,
    /** Edit a gesture's action: the common set when the package is null, otherwise that app's own set. */
    val editIn: (Gesture, String?) -> Unit,
    val open: (Page) -> Unit,
    val openProfile: (String) -> Unit,
) {
    val edit: (Gesture) -> Unit = { editIn(it, null) }
}

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

    var editing by remember { mutableStateOf<Pair<Gesture, String?>?>(null) }
    editing?.let { (g, pkg) ->
        ActionDialog(
            gesture = g,
            current = pkg?.let { s.profiles[it]?.get(g) } ?: s.actions.getValue(g),
            onPick = {
                prefs.setAction(g, it, pkg)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var profilePkg by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != Page.HOME) { page = page.parent }
    val ctx = PageContext(
        s, prefs, setup, actions, versionName, updatesSupported,
        editIn = { g, pkg -> editing = g to pkg },
        open = { page = it },
        openProfile = {
            profilePkg = it
            page = Page.PROFILE
        },
    )

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
                        IconButton(onClick = { page = page.parent }) {
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
            // Composed only while the check page is shown: that is what keeps the service in check mode.
            val check = if (current == Page.TEST) rememberGestureCheck() else null
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
                    Page.PROFILE -> profilePkg?.let { profilePage(ctx, it) } ?: playersPage(ctx)
                    Page.SETTINGS -> settingsPage(ctx)
                    Page.TEST -> testPage(ctx, check ?: CheckState())
                    Page.DIAGNOSTICS -> {
                        item { DiagnosticsCard(s, prefs, setup, actions, nowPlaying, versionName) }
                        item { Hint(stringResource(R.string.diag_hint)) }
                    }
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
        item { UpdateCard(release) { ctx.actions.openUrl(release.pageUrl) } }
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
        val needsBackground = s.doubleMode != DoubleMode.OFF || s.backTap || s.flip || s.twist || s.wave
        if (needsBackground && !setup.batteryUnrestricted) {
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
    nowPlaying?.let { now -> item { NowPlayingCard(now, media, ownGestures = now.controller.packageName in s.profiles) } }
    // A video or podcast app seen for the first time: offer seeking instead of track switching.
    val suggested = if (setup.serviceEnabled && setup.playerAccess) {
        SEEK_APPS.firstOrNull { it in s.seenPlayers && it !in s.profiles && it !in s.dismissedSuggestions }
    } else {
        null
    }
    suggested?.let { pkg ->
        item {
            val context = LocalContext.current
            InfoCard(
                title = stringResource(R.string.suggest_title, remember(pkg) { appLabel(context, pkg) }),
                body = stringResource(R.string.suggest_body),
                button = stringResource(R.string.suggest_yes),
                onClick = { ctx.prefs.createProfile(pkg, ProfilePreset.SEEK) },
                secondary = stringResource(R.string.suggest_no) to { ctx.prefs.dismissedSuggestions = s.dismissedSuggestions + pkg },
            ) { Badge(MaterialShapes.Pill, KnobIcons.FastForward) }
        }
    }

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
        Group(3) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PageTile(
                    0, MaterialShapes.Cookie7Sided, KnobIcons.Checklist, R.string.test_title,
                    stringResource(R.string.tile_test),
                ) { ctx.open(Page.TEST) }
                PageTile(
                    1, MaterialShapes.Cookie12Sided, KnobIcons.MusicNote, R.string.players_title,
                    when {
                        s.profiles.isNotEmpty() -> stringResource(R.string.tile_players_profiles, s.profiles.size)
                        s.appFilter && setup.playerAccess -> stringResource(R.string.tile_players_some, s.allowedApps.size)
                        else -> stringResource(R.string.tile_players_all)
                    },
                ) { ctx.open(Page.PLAYERS) }
                PageTile(
                    2, MaterialShapes.Cookie9Sided, KnobIcons.Settings, R.string.settings_title,
                    stringResource(R.string.tile_settings),
                ) { ctx.open(Page.SETTINGS) }
            }
        }
    }
}

/** Video and podcast apps, where seeking is usually more useful than switching tracks. */
private val SEEK_APPS = listOf(
    "com.google.android.youtube",
    "app.revanced.android.youtube",
    "org.schabi.newpipe",
    "com.vk.vkvideo",
    "ru.rutube.app",
    "au.com.shiftyjelly.pocketcasts",
    "de.danoeh.antennapod",
    "com.bambuna.podcastaddict",
    "fm.castbox.audiobook.radio.podcast",
    "com.audible.application",
)

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
    item { HoldAdvice(ctx) }
    item { Hint(stringResource(R.string.section_keys_hint)) }
}

/** Suggests a hold time from how long this person's short taps actually last. */
@Composable
private fun HoldAdvice(ctx: PageContext) {
    val current = ctx.s.longPressMs
    val advice = remember(current) { suggestLongPress(ctx.prefs.tapDurations, ctx.prefs.holdOvershoots, current) } ?: return
    InfoCard(
        title = stringResource(R.string.hold_advice_title),
        body = advice.longTap?.let { stringResource(R.string.hold_advice_body, it, advice.ms) }
            ?: stringResource(R.string.hold_advice_body_hasty, advice.ms),
        button = stringResource(R.string.hold_advice_button, advice.ms),
        onClick = { ctx.prefs.longPressMs = advice.ms },
    ) { Badge(MaterialShapes.Cookie4Sided, KnobIcons.TouchApp) }
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
    if (on) item { Hint(stringResource(R.string.section_double_pause_hint)) }
}

// ---------------------------------------------------------------- Motion

private fun LazyListScope.motionPage(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(if (s.backTap) 5 else 1) {
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
                        SwitchRow(
                            shape(4), stringResource(R.string.back_tap_eco), s.backTapEco, { prefs.backTapEco = it },
                            subtitle = stringResource(R.string.back_tap_eco_hint), enabled = s.enabled,
                        )
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
    item { WaveCard(ctx) }
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

/** Wave settings with a live view of the proximity sensor, to find the motion that works on this phone. */
@Composable
private fun WaveCard(ctx: PageContext) {
    val s = ctx.s
    Group(if (s.wave) 4 else 1) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SwitchRow(
                shape(0), stringResource(R.string.wave_title), s.wave, { ctx.prefs.wave = it },
                subtitle = stringResource(R.string.wave_subtitle), enabled = s.enabled,
                leading = { Badge(MaterialShapes.Ghostish, KnobIcons.PanTool, primary = true) },
            )
            AnimatedVisibility(s.wave) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ControlRow(shape(1), stringResource(R.string.wave_count_title), enabled = s.enabled) {
                        ConnectedChoice(
                            options = listOf(1 to R.string.wave_one, 2 to R.string.wave_two),
                            selected = s.waveCount,
                            onSelect = { ctx.prefs.waveCount = it },
                            enabled = s.enabled,
                        )
                    }
                    WaveTester(shape(2), s.waveCount)
                    GestureRow(3, Gesture.WAVE, s, MaterialShapes.Ghostish, accent = true, onEdit = ctx.edit)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Players

private fun LazyListScope.playersPage(ctx: PageContext) {
    item { ProfilesList(ctx) }
    item { Hint(stringResource(R.string.profiles_hint)) }
    item { PlayersList(ctx) }
    item { Hint(stringResource(R.string.players_hint)) }
}

/** Players seen so far, as (package, label, icon), sorted by label. */
@Composable
private fun rememberPlayers(seen: Set<String>): List<Triple<String, String, androidx.compose.ui.graphics.ImageBitmap?>> {
    val context = LocalContext.current
    return remember(seen) {
        seen.map { pkg -> Triple(pkg, appLabel(context, pkg), appIcon(context, pkg)?.asImageBitmap()) }
            .sortedBy { it.second.lowercase() }
    }
}

@Composable
private fun ProfilesList(ctx: PageContext) {
    val s = ctx.s
    val players = rememberPlayers(s.seenPlayers)
    Column {
        SectionTitle(stringResource(R.string.profiles_title))
        when {
            !ctx.setup.playerAccess -> Group(1) { ListRow(shape(0), stringResource(R.string.profiles_no_access), enabled = false) }
            players.isEmpty() -> Group(1) { ListRow(shape(0), stringResource(R.string.players_empty), enabled = false) }
            else -> Group(players.size) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    players.forEachIndexed { i, (pkg, label, icon) ->
                        ListRow(
                            shape = shape(i),
                            title = label,
                            subtitle = stringResource(if (pkg in s.profiles) R.string.profile_own else R.string.profile_common),
                            enabled = s.enabled,
                            onClick = { ctx.openProfile(pkg) },
                            leading = { AppIcon(icon) },
                            trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayersList(ctx: PageContext) {
    val s = ctx.s
    val access = ctx.setup.playerAccess
    val players = rememberPlayers(s.seenPlayers)
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

// ---------------------------------------------------------------- One app's gestures

private fun LazyListScope.profilePage(ctx: PageContext, pkg: String) {
    val own = ctx.s.profiles[pkg]
    item { ProfileHeader(ctx, pkg, own != null) }
    if (own == null) {
        item { ProfilePresets(ctx, pkg) }
    } else {
        item { ProfileOptions(ctx, pkg) }
        listOf(
            GestureGroup.KEYS to R.string.section_keys,
            GestureGroup.SEQUENCE to R.string.section_double,
            GestureGroup.MOTION to R.string.section_motion,
        ).forEach { (group, title) ->
            item {
                val gestures = Gesture.entries.filter { it.group == group }
                Column {
                    SectionTitle(stringResource(title))
                    Group(gestures.size) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            gestures.forEachIndexed { i, g ->
                                GestureRow(
                                    i, g, ctx.s, gestureShape(g), accent = group != GestureGroup.KEYS,
                                    actions = own, onEdit = { ctx.editIn(it, pkg) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    item { Hint(stringResource(R.string.profile_hint)) }
}

@Composable
private fun ProfileHeader(ctx: PageContext, pkg: String, own: Boolean) {
    val context = LocalContext.current
    val label = remember(pkg) { appLabel(context, pkg) }
    val icon = remember(pkg) { appIcon(context, pkg)?.asImageBitmap() }
    Group(1) {
        SwitchRow(
            shape(0), label, own,
            { on -> if (on) ctx.prefs.createProfile(pkg, ProfilePreset.COMMON) else ctx.prefs.deleteProfile(pkg) },
            subtitle = stringResource(if (own) R.string.profile_on_hint else R.string.profile_off_hint),
            enabled = ctx.s.enabled,
            leading = { AppIcon(icon, size = 48) },
        )
    }
}

/** Per-app settings beside the gestures: seek step and sound cues. */
@Composable
private fun ProfileOptions(ctx: PageContext, pkg: String) {
    val s = ctx.s
    Group(2) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ControlRow(shape(0), stringResource(R.string.profile_seek_step), enabled = s.enabled) {
                ConnectedChoice(
                    options = listOf(
                        0 to R.string.seek_common,
                        5 to R.string.seek_5,
                        10 to R.string.seek_10,
                        15 to R.string.seek_15,
                        30 to R.string.seek_30,
                    ),
                    selected = s.profileSeekSteps[pkg] ?: 0,
                    onSelect = { ctx.prefs.setSeekStep(pkg, it) },
                    enabled = s.enabled,
                )
            }
            SwitchRow(
                shape(1), stringResource(R.string.profile_cues_off), pkg in s.cuesOffApps, { ctx.prefs.setCuesOff(pkg, it) },
                subtitle = stringResource(R.string.profile_cues_off_hint), enabled = s.enabled,
            )
        }
    }
}

@Composable
private fun ProfilePresets(ctx: PageContext, pkg: String) {
    Column {
        SectionTitle(stringResource(R.string.profile_start))
        Group(2) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ListRow(
                    shape(0), stringResource(R.string.profile_preset_common),
                    subtitle = stringResource(R.string.profile_preset_common_hint),
                    enabled = ctx.s.enabled,
                    onClick = { ctx.prefs.createProfile(pkg, ProfilePreset.COMMON) },
                    leading = { Badge(MaterialShapes.Cookie6Sided, KnobIcons.Tune) },
                )
                ListRow(
                    shape(1), stringResource(R.string.profile_preset_seek),
                    subtitle = stringResource(R.string.profile_preset_seek_hint),
                    enabled = ctx.s.enabled,
                    onClick = { ctx.prefs.createProfile(pkg, ProfilePreset.SEEK) },
                    leading = { Badge(MaterialShapes.Pill, KnobIcons.FastForward) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Gesture check

private fun LazyListScope.testPage(ctx: PageContext, check: CheckState) {
    val s = ctx.s
    if (!ctx.setup.serviceEnabled || !ctx.setup.serviceRunning) {
        item {
            InfoCard(
                title = stringResource(R.string.not_running_title),
                body = stringResource(R.string.test_not_running),
                button = stringResource(R.string.open_accessibility),
                onClick = ctx.actions.openAccessibility,
            ) { Badge(MaterialShapes.Pentagon, KnobIcons.Close, error = true) }
        }
    }
    item { CheckHero(check) }
    // The same set the service uses: the playing app's own gestures when it has them.
    val actions = ctx.prefs.lastPlayer?.let { s.profiles[it] } ?: s.actions
    item { CheckList(stringResource(R.string.section_keys), Gesture.entries.filter { it.group == GestureGroup.KEYS }, actions, check) }
    if (s.doubleMode != DoubleMode.OFF) {
        item {
            CheckList(stringResource(R.string.section_double), Gesture.entries.filter { it.group == GestureGroup.SEQUENCE }, actions, check)
        }
        if (s.doubleMode == DoubleMode.SCREEN_OFF) item { Hint(stringResource(R.string.test_screen_off_mode)) }
    }
    val motion = buildList {
        if (s.backTap) addAll(listOf(Gesture.BACK_TAP_2, Gesture.BACK_TAP_3))
        if (s.twist) add(Gesture.TWIST)
        if (s.wave) add(Gesture.WAVE)
    }
    if (motion.isNotEmpty()) item { CheckList(stringResource(R.string.section_motion), motion, actions, check) }
    item { Hint(stringResource(R.string.test_hint)) }
}

// ---------------------------------------------------------------- Settings

private fun LazyListScope.settingsPage(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(5) {
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
                SwitchRow(
                    shape(4), stringResource(R.string.pref_announce_skips), s.announceSkips, { prefs.announceSkips = it },
                    subtitle = stringResource(R.string.pref_announce_skips_hint), enabled = s.enabled,
                )
            }
        }
    }
    item {
        Column {
            SectionTitle(stringResource(R.string.actions_title))
            Group(3) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ControlRow(shape(0), stringResource(R.string.pref_seek_step), caption = stringResource(R.string.pref_seek_step_hint), enabled = s.enabled) {
                        ConnectedChoice(
                            options = listOf(5 to R.string.seek_5, 10 to R.string.seek_10, 15 to R.string.seek_15, 30 to R.string.seek_30),
                            selected = s.seekStepSec,
                            onSelect = { prefs.seekStepSec = it },
                            enabled = s.enabled,
                        )
                    }
                    SwitchRow(
                        shape(1), stringResource(R.string.pref_like_toggle), s.likeToggle, { prefs.likeToggle = it },
                        subtitle = stringResource(R.string.pref_like_toggle_hint), enabled = s.enabled,
                    )
                    SwitchRow(
                        shape(2), stringResource(R.string.pref_announce_all), s.announceAllTracks, { prefs.announceAllTracks = it },
                        subtitle = stringResource(R.string.pref_announce_all_hint), enabled = s.enabled,
                    )
                }
            }
        }
    }
    item { SpeechCard(s, prefs) }
    item { CueCard(ctx) }
    item {
        Group(1) {
            ControlRow(shape(0), stringResource(R.string.pref_resume), caption = stringResource(R.string.pref_resume_hint), enabled = s.enabled) {
                ConnectedChoice(
                    options = listOf(
                        0 to R.string.double_mode_off,
                        15 to R.string.minutes_15,
                        30 to R.string.minutes_30,
                        60 to R.string.minutes_60,
                    ),
                    selected = s.resumeMinutes,
                    onSelect = { prefs.resumeMinutes = it },
                    enabled = s.enabled,
                )
            }
        }
    }
    // Only relevant once a gesture is assigned to the sleep timer (or one is running).
    val sleepActive = s.sleepUntil > System.currentTimeMillis()
    if (sleepActive || Action.SLEEP_TIMER in s.actions.values) item {
        val context = LocalContext.current
        Group(2) {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    options = listOf(
                        15 to R.string.minutes_15,
                        30 to R.string.minutes_30,
                        60 to R.string.minutes_60,
                        Prefs.SLEEP_TO_TRACK_END to R.string.minutes_track,
                    ),
                    selected = s.sleepMinutes,
                    onSelect = { prefs.sleepMinutes = it },
                )
                if (sleepActive) {
                    TextButton(onClick = { SleepTimer.cancel(context, prefs) }) {
                        Text(stringResource(R.string.sleep_cancel))
                    }
                }
            }
            SwitchRow(
                shape(1), stringResource(R.string.pref_sleep_extend), s.sleepExtend, { prefs.sleepExtend = it },
                subtitle = stringResource(R.string.pref_sleep_extend_hint),
            )
          }
        }
    }
    item {
        Group(3) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ListRow(
                    shape(0), stringResource(R.string.diag_title),
                    subtitle = stringResource(R.string.diag_open_hint),
                    onClick = { ctx.open(Page.DIAGNOSTICS) },
                    trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                )
                ListRow(
                    shape(1), stringResource(R.string.open_accessibility),
                    subtitle = stringResource(R.string.open_accessibility_hint),
                    onClick = ctx.actions.openAccessibility,
                    trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                )
                if (ctx.updatesSupported) {
                    SwitchRow(
                        shape(2), stringResource(R.string.pref_check_updates), s.checkUpdates, { prefs.checkUpdates = it },
                        subtitle = stringResource(R.string.version_value, ctx.versionName),
                    )
                } else {
                    ListRow(shape(2), stringResource(R.string.app_name), stringResource(R.string.version_value, ctx.versionName))
                }
            }
        }
    }
    item { EventLog(prefs) }
}

/** The optional sounds after actions: timbre, loudness, when, and a switch (with a preview) per kind of action. */
@Composable
private fun CueCard(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    val on = s.cueStyle != CueStyle.OFF
    val cues = remember { Cues() }
    DisposableEffect(cues) { onDispose { cues.release() } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val preview: (List<Cue>, CueStyle, CueVolume) -> Unit = { sample, style, volume ->
        scope.launch {
            sample.forEach {
                cues.play(it, style, volume)
                kotlinx.coroutines.delay(CUE_PREVIEW_GAP_MS)
            }
        }
    }
    val tryAll = { style: CueStyle, volume: CueVolume ->
        preview(listOf(Cue.FORWARD, Cue.LIKE, Cue.ERROR), style, volume)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Group(if (on) 3 else 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ControlRow(shape(0), stringResource(R.string.cue_title), caption = stringResource(R.string.cue_hint), enabled = s.enabled) {
                    ConnectedChoice(
                        options = listOf(
                            CueStyle.OFF to R.string.cue_off,
                            CueStyle.SOFT to R.string.cue_soft,
                            CueStyle.TWO_TONE to R.string.cue_two_tone,
                            CueStyle.CLICK to R.string.cue_click,
                        ),
                        selected = s.cueStyle,
                        onSelect = {
                            prefs.cueStyle = it
                            tryAll(it, s.cueVolume)
                        },
                        enabled = s.enabled,
                    )
                }
                AnimatedVisibility(on) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ControlRow(shape(1), stringResource(R.string.cue_volume), enabled = s.enabled) {
                            ConnectedChoice(
                                options = listOf(
                                    CueVolume.LOW to R.string.cue_volume_low,
                                    CueVolume.MEDIUM to R.string.cue_volume_medium,
                                    CueVolume.HIGH to R.string.cue_volume_high,
                                ),
                                selected = s.cueVolume,
                                onSelect = {
                                    prefs.cueVolume = it
                                    tryAll(s.cueStyle, it)
                                },
                                enabled = s.enabled,
                            )
                        }
                        SwitchRow(
                            shape(2), stringResource(R.string.cue_screen_off_only), s.cueScreenOffOnly, { prefs.cueScreenOffOnly = it },
                            subtitle = stringResource(R.string.cue_screen_off_only_hint), enabled = s.enabled,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(on) {
            Column {
                SectionTitle(stringResource(R.string.cue_groups_title))
                Group(CueGroup.entries.size) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CueGroup.entries.forEachIndexed { i, group ->
                            val checked = group in s.cueGroups
                            val toggle = { enable: Boolean ->
                                prefs.cueGroups = if (enable) s.cueGroups + group else s.cueGroups - group
                            }
                            // Tapping the row plays the sound; the switch turns it on or off.
                            ListRow(
                                shape = shape(i),
                                title = stringResource(cueGroupLabel(group)),
                                subtitle = stringResource(cueGroupSound(group)),
                                enabled = s.enabled,
                                onClick = { preview(group.sample.toList(), s.cueStyle, s.cueVolume) },
                                leading = { Badge(MaterialShapes.Cookie6Sided, cueGroupIcon(group)) },
                                trailing = { Switch(checked = checked, onCheckedChange = toggle, enabled = s.enabled) },
                            )
                        }
                    }
                }
                Hint(stringResource(R.string.cue_groups_hint))
            }
        }
    }
}

private fun cueGroupLabel(g: CueGroup) = when (g) {
    CueGroup.TRACKS -> R.string.cue_group_tracks
    CueGroup.PLAYBACK -> R.string.cue_group_playback
    CueGroup.SEEK -> R.string.cue_group_seek
    CueGroup.LIKE -> R.string.cue_group_like
    CueGroup.MODES -> R.string.cue_group_modes
    CueGroup.TIMER -> R.string.cue_group_timer
    CueGroup.OTHER -> R.string.cue_group_other
    CueGroup.ERRORS -> R.string.cue_group_errors
}

private fun cueGroupSound(g: CueGroup) = when (g) {
    CueGroup.TRACKS -> R.string.cue_sound_tracks
    CueGroup.PLAYBACK -> R.string.cue_sound_playback
    CueGroup.SEEK -> R.string.cue_sound_seek
    CueGroup.LIKE -> R.string.cue_sound_like
    CueGroup.MODES -> R.string.cue_sound_modes
    CueGroup.TIMER -> R.string.cue_sound_timer
    CueGroup.OTHER -> R.string.cue_sound_other
    CueGroup.ERRORS -> R.string.cue_sound_errors
}

private fun cueGroupIcon(g: CueGroup) = when (g) {
    CueGroup.TRACKS -> KnobIcons.SkipNext
    CueGroup.PLAYBACK -> KnobIcons.PlayPause
    CueGroup.SEEK -> KnobIcons.FastForward
    CueGroup.LIKE -> KnobIcons.Favorite
    CueGroup.MODES -> KnobIcons.Shuffle
    CueGroup.TIMER -> KnobIcons.Bedtime
    CueGroup.OTHER -> KnobIcons.Flashlight
    CueGroup.ERRORS -> KnobIcons.Close
}

private const val CUE_PREVIEW_GAP_MS = 550L

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
    actions: Map<Gesture, Action> = s.actions,
    onEdit: (Gesture) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val action = actions[gesture] ?: Action.NONE
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
                GestureRow(i, g, ctx.s, polygon, accent, enabled, onEdit = ctx.edit)
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
