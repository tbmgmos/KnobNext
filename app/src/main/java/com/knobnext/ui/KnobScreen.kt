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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.knobnext.PocketScreen
import com.knobnext.PowerButton
import com.knobnext.PowerHold
import com.knobnext.VoiceListener
import com.knobnext.NanoBrain
import com.knobnext.Offline
import com.knobnext.OfflineFile
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
    HEADSET(R.string.section_headset),
    ACQUAINT(R.string.acq_title),
    ARTIST(R.string.acq_artist_title),
    PLAYERS(R.string.players_title),
    PROFILE(R.string.profile_title),
    SETTINGS(R.string.settings_title),
    SETTINGS_GENERAL(R.string.settings_general),
    SETTINGS_ACTIONS(R.string.settings_actions),
    SETTINGS_SOUNDS(R.string.settings_sounds),
    SETTINGS_SPEECH(R.string.settings_speech),
    SETTINGS_VOICE(R.string.settings_voice),
    SETTINGS_PAUSE(R.string.settings_pause),
    SETTINGS_POCKET(R.string.settings_pocket),
    SETTINGS_ABOUT(R.string.settings_about),
    TEST(R.string.test_title),
    DIAGNOSTICS(R.string.diag_title),
}

/** Where "back" leads from each page. */
private val Page.parent: Page
    get() = when (this) {
        Page.PROFILE -> Page.PLAYERS
        Page.ARTIST -> Page.ACQUAINT
        Page.SETTINGS_GENERAL, Page.SETTINGS_ACTIONS, Page.SETTINGS_SOUNDS, Page.SETTINGS_SPEECH,
        Page.SETTINGS_VOICE, Page.SETTINGS_PAUSE, Page.SETTINGS_POCKET, Page.SETTINGS_ABOUT -> Page.SETTINGS
        Page.DIAGNOSTICS -> Page.SETTINGS_ABOUT
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
    val openArtist: (String) -> Unit = {},
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
    var artistKey by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != Page.HOME) { page = page.parent }
    val ctx = PageContext(
        s, prefs, setup, actions, versionName, updatesSupported,
        editIn = { g, pkg -> editing = g to pkg },
        open = { page = it },
        openProfile = {
            profilePkg = it
            page = Page.PROFILE
        },
        openArtist = {
            artistKey = it
            page = Page.ARTIST
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
            // Likewise: while the headphones page is shown, presses on them only show there.
            val learn = if (current == Page.HEADSET) rememberHeadsetLearn() else null
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
                    Page.HEADSET -> headsetPage(ctx, learn ?: HeadsetLearnState())
                    Page.ACQUAINT -> acquaintPage(ctx)
                    Page.ARTIST -> artistKey?.let { key -> item { ArtistCard(key, ctx.s.journalVersion, ctx.openArtist) { ctx.open(Page.ACQUAINT) } } } ?: acquaintPage(ctx)
                    Page.PLAYERS -> playersPage(ctx)
                    Page.PROFILE -> profilePkg?.let { profilePage(ctx, it) } ?: playersPage(ctx)
                    Page.SETTINGS -> settingsPage(ctx)
                    Page.SETTINGS_GENERAL -> generalSettings(ctx)
                    Page.SETTINGS_ACTIONS -> actionSettings(ctx)
                    Page.SETTINGS_SOUNDS -> item { CueCard(ctx) }
                    Page.SETTINGS_SPEECH -> speechSettings(ctx)
                    Page.SETTINGS_VOICE -> {
                        item { VoiceCard(ctx) }
                        item { BrainCard(ctx) }
                    }
                    Page.SETTINGS_PAUSE -> pauseSettings(ctx)
                    Page.SETTINGS_POCKET -> pocketSettings(ctx)
                    Page.SETTINGS_ABOUT -> aboutSettings(ctx)
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
            Group(4) {
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
                    PageTile(
                        3, MaterialShapes.Cookie6Sided, KnobIcons.MusicNote, R.string.section_headset,
                        if (s.headsetButtons) stringResource(R.string.tile_headset_on) else stringResource(R.string.tile_off),
                    ) { ctx.open(Page.HEADSET) }
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
                val context = LocalContext.current
                val known = remember(s.journalVersion) { com.knobnext.Journal.entries(context).size }
                PageTile(
                    3, MaterialShapes.Cookie4Sided, KnobIcons.Info, R.string.acq_title,
                    if (s.infoHistory) stringResource(R.string.acq_tile, known) else stringResource(R.string.acq_tile_off),
                ) { ctx.open(Page.ACQUAINT) }
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
    item { PowerCard(ctx) }
    item { Hint(stringResource(R.string.section_keys_hint)) }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Group(1) {
                SwitchRow(
                    shape(0), stringResource(R.string.pref_blind), s.blindGestures, { ctx.prefs.blindGestures = it },
                    subtitle = stringResource(R.string.pref_blind_hint), enabled = s.enabled,
                )
            }
            Hint(stringResource(R.string.blind_keys_hint))
        }
    }
}

/** The power button pressed twice: its gesture, and whether the system's own double press is in the way. */
@Composable
private fun PowerCard(ctx: PageContext) {
    val context = LocalContext.current
    // Read again whenever the page comes back (after changing it in system settings).
    var systemOn by remember { mutableStateOf(PowerButton.systemDoublePressOn(context)) }
    var assistant by remember { mutableStateOf(PowerHold.isAssistant(context)) }
    DisposableEffect(context) {
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                systemOn = PowerButton.systemDoublePressOn(context)
                assistant = PowerHold.isAssistant(context)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.section_power))
        Group(2) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GestureRow(0, Gesture.POWER_DOUBLE, ctx.s, gestureShape(Gesture.POWER_DOUBLE), false, ctx.s.enabled, onEdit = ctx.edit)
                ListRow(
                    shape(1),
                    stringResource(R.string.power_system),
                    subtitle = stringResource(
                        when (systemOn) {
                            true -> R.string.power_system_on
                            false -> R.string.power_system_off
                            null -> R.string.power_system_unknown
                        },
                    ),
                    highlighted = systemOn == true,
                    onClick = { PowerButton.openGestureSettings(context) },
                    leading = {
                        if (systemOn == false) {
                            ShapeBadge(MaterialShapes.Circle, KnobIcons.Check, scheme.primary, scheme.onPrimary, size = 40)
                        } else {
                            ShapeBadge(MaterialShapes.Pentagon, KnobIcons.Close, scheme.error, scheme.onError, size = 40)
                        }
                    },
                    trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                )
            }
        }
        Hint(stringResource(R.string.power_hint))
        Group(3) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GestureRow(0, Gesture.POWER_HOLD, ctx.s, gestureShape(Gesture.POWER_HOLD), false, ctx.s.enabled, onEdit = ctx.edit)
                ListRow(
                    shape(1),
                    stringResource(R.string.power_assistant),
                    subtitle = stringResource(
                        when (assistant) {
                            true -> R.string.power_assistant_on
                            false -> R.string.power_assistant_off
                            null -> R.string.power_assistant_unknown
                        },
                    ),
                    highlighted = assistant == false,
                    onClick = { PowerHold.openAssistantSettings(context) },
                    leading = {
                        if (assistant == true) {
                            ShapeBadge(MaterialShapes.Circle, KnobIcons.Check, scheme.primary, scheme.onPrimary, size = 40)
                        } else {
                            ShapeBadge(MaterialShapes.Pentagon, KnobIcons.Close, scheme.error, scheme.onError, size = 40)
                        }
                    },
                    trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                )
                SwitchRow(
                    shape(2), stringResource(R.string.power_fallback), ctx.s.powerHoldFallback, { ctx.prefs.powerHoldFallback = it },
                    subtitle = stringResource(R.string.power_fallback_hint),
                )
            }
        }
        Hint(stringResource(R.string.power_hold_hint))
    }
}

/** Voice commands: microphone permission, which recognizer is there, a try-it button and examples. */
@Composable
private fun VoiceCard(ctx: PageContext) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val running = ctx.setup.serviceEnabled && ctx.setup.serviceRunning
    fun granted() = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var mic by remember { mutableStateOf(granted()) }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { mic = it || granted() }
    val engine = remember { VoiceListener.availability(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.voice_title))
        val vosk = Offline.supported && engine != VoiceListener.Availability.ON_DEVICE
        Group(if (vosk) 4 else 3) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ListRow(
                    shape(0), stringResource(R.string.voice_mic),
                    subtitle = stringResource(if (mic) R.string.voice_mic_on else R.string.voice_mic_off),
                    highlighted = !mic,
                    onClick = if (mic) null else ({ askMic.launch(android.Manifest.permission.RECORD_AUDIO) }),
                    leading = {
                        if (mic) {
                            ShapeBadge(MaterialShapes.Circle, KnobIcons.Check, scheme.primary, scheme.onPrimary, size = 40)
                        } else {
                            ShapeBadge(MaterialShapes.Pentagon, KnobIcons.Close, scheme.error, scheme.onError, size = 40)
                        }
                    },
                )
                ListRow(
                    shape(1), stringResource(R.string.voice_engine),
                    subtitle = stringResource(
                        when (engine) {
                            VoiceListener.Availability.ON_DEVICE -> R.string.voice_engine_on_device
                            VoiceListener.Availability.ONLINE -> R.string.voice_engine_online
                            VoiceListener.Availability.NONE -> R.string.voice_engine_none
                        },
                    ),
                )
                if (vosk) {
                    OfflineRow(
                        shape(2), stringResource(R.string.offline_vosk),
                        hint = stringResource(R.string.offline_vosk_hint),
                        state = { Offline.vosk(it) },
                        download = { Offline.downloadVosk(it) },
                        delete = { Offline.deleteVosk(it) },
                    )
                }
                ListRow(
                    shape(if (vosk) 3 else 2), stringResource(R.string.voice_try),
                    subtitle = stringResource(if (running) R.string.voice_try_hint else R.string.voice_need_service),
                    enabled = running && mic && engine != VoiceListener.Availability.NONE,
                    onClick = { VoiceListener.trigger?.invoke() },
                    trailing = { Icon(KnobIcons.Mic, contentDescription = null) },
                )
            }
        }
        Hint(stringResource(R.string.voice_hint))
    }
}

/** The on-phone model: whether Gemini Nano is there (and fetching it), and what it is used for. */
@Composable
private fun BrainCard(ctx: PageContext) {
    val s = ctx.s
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<NanoBrain.State?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state = NanoBrain.state() }
    val download: () -> Unit = {
        failed = false
        state = NanoBrain.State.DOWNLOADING
        scope.launch {
            try {
                NanoBrain.download()?.collect { if (it == "failed") failed = true }
            } catch (_: Exception) {
                failed = true
            }
            state = NanoBrain.state()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.brain_title))
        val local = Offline.supported
        Group(if (local) 4 else 3) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val context = LocalContext.current
                val aicore = remember(state) { NanoBrain.aicoreVersion(context) }
                val unavailable = state == NanoBrain.State.UNAVAILABLE
                val openStore: () -> Unit = { NanoBrain.openAicoreInStore(context) }
                val recheck: () -> Unit = {
                    state = null
                    scope.launch { state = NanoBrain.state() }
                }
                ListRow(
                    shape(0), stringResource(R.string.brain_nano),
                    subtitle = when {
                        failed -> stringResource(R.string.brain_nano_failed)
                        state == null -> stringResource(R.string.brain_nano_checking)
                        state == NanoBrain.State.READY -> stringResource(R.string.brain_nano_ready)
                        state == NanoBrain.State.DOWNLOADABLE -> stringResource(R.string.brain_nano_downloadable)
                        state == NanoBrain.State.DOWNLOADING -> stringResource(R.string.brain_nano_downloading)
                        // Say why, as precisely as the system lets us know.
                        aicore == null -> stringResource(R.string.brain_nano_no_aicore)
                        NanoBrain.unavailableReason != null ->
                            stringResource(R.string.brain_nano_error, NanoBrain.unavailableReason ?: "", aicore)
                        else -> stringResource(R.string.brain_nano_refused, aicore)
                    },
                    highlighted = state == NanoBrain.State.DOWNLOADABLE || failed || unavailable,
                    onClick = when {
                        state == NanoBrain.State.DOWNLOADABLE || failed -> download
                        unavailable && aicore == null -> openStore
                        unavailable -> recheck
                        else -> null
                    },
                    trailing = { Icon(KnobIcons.Sparkle, contentDescription = null) },
                )
                if (local) {
                    OfflineRow(
                        shape(1), stringResource(R.string.offline_llm),
                        hint = stringResource(R.string.offline_llm_hint),
                        state = { Offline.llm(it) },
                        download = { context -> scope.launch { Offline.downloadLlm(context) } },
                        delete = { Offline.deleteLlm(it) },
                    )
                }
                val n = if (local) 1 else 0
                SwitchRow(
                    shape(1 + n), stringResource(R.string.brain_voice), s.smartVoice, { ctx.prefs.smartVoice = it },
                    subtitle = stringResource(R.string.brain_voice_hint),
                )
                SwitchRow(
                    shape(2 + n), stringResource(R.string.brain_titles), s.smartTitles, { ctx.prefs.smartTitles = it },
                    subtitle = stringResource(R.string.brain_titles_hint),
                )
            }
        }
        Hint(stringResource(R.string.brain_hint))
    }
}

/** A downloadable model: its state (with progress while downloading), download and delete. */
@Composable
private fun GroupScope.OfflineRow(
    shape: androidx.compose.ui.graphics.Shape,
    title: String,
    hint: String,
    state: (android.content.Context) -> OfflineFile,
    download: (android.content.Context) -> Unit,
    delete: (android.content.Context) -> Unit,
) {
    val context = LocalContext.current
    var file by remember { mutableStateOf(state(context)) }
    var tick by remember { mutableIntStateOf(0) }
    // While something is going on, look again every second.
    LaunchedEffect(tick, file.state) {
        if (file.state == OfflineFile.State.DOWNLOADING || file.state == OfflineFile.State.UNPACKING || tick == 0) {
            kotlinx.coroutines.delay(1_000)
            file = state(context)
            tick++
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    ListRow(
        shape, title,
        subtitle = when (file.state) {
            OfflineFile.State.MISSING -> hint
            OfflineFile.State.DOWNLOADING -> if (file.totalMb > 0) {
                stringResource(R.string.offline_downloading_of, file.downloadedMb, file.totalMb)
            } else {
                stringResource(R.string.offline_downloading)
            }
            OfflineFile.State.UNPACKING -> stringResource(R.string.offline_unpacking)
            OfflineFile.State.READY -> if (file.totalMb > 0) {
                stringResource(R.string.offline_ready_size, file.totalMb)
            } else {
                stringResource(R.string.offline_ready)
            }
            OfflineFile.State.FAILED -> file.error ?: stringResource(R.string.offline_err_download)
        },
        highlighted = file.state == OfflineFile.State.FAILED,
        onClick = {
            when (file.state) {
                OfflineFile.State.MISSING, OfflineFile.State.FAILED -> {
                    download(context)
                    file = OfflineFile(OfflineFile.State.DOWNLOADING)
                    tick++
                }
                else -> confirmDelete = true
            }
        },
        trailing = {
            Icon(
                if (file.state == OfflineFile.State.MISSING || file.state == OfflineFile.State.FAILED) KnobIcons.Download else KnobIcons.Close,
                contentDescription = null,
            )
        },
    )
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(title) },
            text = { Text(stringResource(R.string.offline_delete_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    delete(context)
                    file = state(context)
                    confirmDelete = false
                }) { Text(stringResource(R.string.offline_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Pocket mode: turned on by the power button while music plays, or right now to try it. */
@Composable
private fun PocketCard(ctx: PageContext) {
    val s = ctx.s
    val running = ctx.setup.serviceEnabled && ctx.setup.serviceRunning
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group(2) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.pocket_auto), s.pocketAuto, { ctx.prefs.pocketAuto = it },
                    subtitle = stringResource(R.string.pocket_auto_hint), enabled = s.enabled,
                )
                ListRow(
                    shape(1), stringResource(R.string.pocket_try),
                    subtitle = stringResource(if (running) R.string.pocket_try_hint else R.string.pocket_need_service),
                    enabled = running,
                    onClick = { PocketScreen.toggle?.invoke() },
                    trailing = { Icon(KnobIcons.PhoneDark, contentDescription = null) },
                )
            }
        }
        Hint(stringResource(R.string.pocket_intro))
    }
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

private fun LazyListScope.headsetPage(ctx: PageContext, learn: HeadsetLearnState) {
    val s = ctx.s
    val prefs = ctx.prefs
    item { Hint(stringResource(R.string.headset_intro)) }
    item {
        Group(1) {
            SwitchRow(
                shape(0), stringResource(R.string.headset_title), s.headsetButtons, { prefs.headsetButtons = it },
                subtitle = stringResource(R.string.headset_subtitle), enabled = s.enabled,
                leading = { Badge(MaterialShapes.Cookie6Sided, KnobIcons.MusicNote, primary = true) },
            )
        }
    }
    if (!s.headsetButtons) return
    item { HeadsetLiveCard(ctx, learn) }
    item {
        HeadsetRows(
            ctx, learn,
            (learn.device ?: s.headsetDevice)?.let { stringResource(R.string.headset_gestures_of, it) }
                ?: stringResource(R.string.headset_commands),
            listOf(
                Gesture.HS_PLAY_PAUSE to MaterialShapes.Circle,
                Gesture.HS_NEXT to MaterialShapes.Pill,
                Gesture.HS_PREVIOUS to MaterialShapes.Arch,
                Gesture.HS_FORWARD to MaterialShapes.Triangle,
                Gesture.HS_REWIND to MaterialShapes.Diamond,
            ),
        )
    }
    item {
        HeadsetRows(
            ctx, learn, stringResource(R.string.headset_counted),
            listOf(
                Gesture.HS_PLAY_PAUSE_DOUBLE to MaterialShapes.Puffy,
                Gesture.HS_PLAY_PAUSE_TRIPLE to MaterialShapes.Flower,
                Gesture.HS_PLAY_PAUSE_HOLD to MaterialShapes.Sunny,
            ),
        )
    }
    item { Hint(stringResource(R.string.headset_counted_hint)) }
    item {
        Column {
            SectionTitle(stringResource(R.string.headset_hold))
            Group(if (s.headsetHold) 2 else 1) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SwitchRow(
                        shape(0), stringResource(R.string.headset_hold_switch), s.headsetHold, { prefs.headsetHold = it },
                        subtitle = stringResource(R.string.headset_hold_hint), enabled = s.enabled,
                    )
                    AnimatedVisibility(s.headsetHold) {
                        GestureRow(
                            1, Gesture.HS_ASSIST, s, MaterialShapes.SoftBoom, accent = true,
                            highlighted = learn.fresh == Gesture.HS_ASSIST, onEdit = ctx.edit,
                        )
                    }
                }
            }
        }
    }
    item { Hint(stringResource(R.string.headset_hint)) }
}

/** Which headphones are caught, what the last press on them was, and which gesture that was. */
@Composable
private fun HeadsetLiveCard(ctx: PageContext, learn: HeadsetLearnState) {
    val last = learn.last
    val device = learn.device?.takeIf { it != "?" }
    val motion = last?.let { ctx.s.headsetMotions[it.gesture] }?.takeIf { device != null && device == ctx.s.headsetDevice }
    val title: String
    val subtitle: String
    when {
        learn.device == null -> {
            title = stringResource(R.string.headset_live_none)
            subtitle = stringResource(R.string.headset_live_none_hint)
        }
        last == null -> {
            title = stringResource(R.string.headset_live_try)
            subtitle = stringResource(R.string.headset_live_try_hint, learn.device.orEmpty())
        }
        else -> {
            title = motion?.let { stringResource(R.string.headset_live_got_motion, stringResource(motionLabel(it)), stringResource(gestureLabel(last.gesture))) }
                ?: stringResource(R.string.headset_live_got, stringResource(gestureLabel(last.gesture)))
            subtitle = if (last.key == 0) {
                stringResource(R.string.headset_live_got_hint_voice)
            } else {
                stringResource(R.string.headset_live_got_hint, android.view.KeyEvent.keyCodeToString(last.key).removePrefix("KEYCODE_"))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Group(1) {
            ListRow(
                shape = shape(0),
                title = title,
                subtitle = subtitle,
                highlighted = learn.fresh != null,
                leading = { Badge(MaterialShapes.Cookie9Sided, KnobIcons.TouchApp) },
            )
        }
        if (last != null && device != null) {
            MotionQuestion(device, motion) { ctx.prefs.setHeadsetMotion(device, last.gesture, it) }
        }
    }
}

/**
 * One line per command, named after the gesture that sends it on these headphones once that is known
 * ("Double tap"); until then by the command, with the gesture that usually sends it.
 */
@Composable
private fun HeadsetRows(ctx: PageContext, learn: HeadsetLearnState, title: String, rows: List<Pair<Gesture, RoundedPolygon>>) {
    val s = ctx.s
    val scheme = MaterialTheme.colorScheme
    Column {
        SectionTitle(title)
        Group(rows.size) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEachIndexed { i, (g, polygon) ->
                    val action = s.actions[g] ?: Action.NONE
                    val motion = s.headsetMotions[g]
                    val command = stringResource(gestureLabel(g))
                    ListRow(
                        shape = shape(i),
                        title = motion?.let { stringResource(motionLabel(it)) } ?: command,
                        subtitle = stringResource(R.string.headset_row_action, stringResource(actionLabel(action))) + "\n" +
                            (if (motion != null) command else stringResource(typicalMotion(g))),
                        enabled = s.enabled,
                        highlighted = learn.fresh == g,
                        onClick = { ctx.edit(g) },
                        leading = { ShapeBadge(polygon, actionIcon(action), scheme.secondaryContainer, scheme.onSecondaryContainer) },
                    )
                }
            }
        }
    }
}

/** Pixel's own Quick Tap, caught as the "play/pause media" key it sends (experimental). */
@Composable
private fun PixelTapCard(ctx: PageContext) {
    val s = ctx.s
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group(if (s.pixelTap) 2 else 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.pixel_tap_title), s.pixelTap, { ctx.prefs.pixelTap = it },
                    subtitle = stringResource(R.string.pixel_tap_subtitle), enabled = s.enabled,
                    leading = { Badge(MaterialShapes.Cookie12Sided, KnobIcons.TouchApp, primary = true) },
                )
                AnimatedVisibility(s.pixelTap) {
                    GestureRow(1, Gesture.PIXEL_TAP, s, MaterialShapes.Cookie12Sided, accent = true, onEdit = ctx.edit)
                }
            }
        }
        AnimatedVisibility(s.pixelTap) { Hint(stringResource(R.string.pixel_tap_hint)) }
    }
}

private fun LazyListScope.motionPage(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item { PixelTapCard(ctx) }
    item {
        Group(if (s.backTap) 7 else 1) {
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
                        SwitchRow(
                            shape(5), stringResource(R.string.back_tap_screen_off), s.backTapScreenOff, { prefs.backTapScreenOff = it },
                            subtitle = stringResource(R.string.back_tap_screen_off_hint), enabled = s.enabled,
                        )
                        ListRow(
                            shape = shape(6),
                            title = stringResource(R.string.tap_stats_title),
                            subtitle = rememberTapSummary() + "\n" + stringResource(R.string.tap_stats_hint),
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
        if (s.pixelTap) add(Gesture.PIXEL_TAP)
        if (s.twist) add(Gesture.TWIST)
        if (s.wave) add(Gesture.WAVE)
    }
    if (motion.isNotEmpty()) item { CheckList(stringResource(R.string.section_motion), motion, actions, check) }
    if (s.headsetButtons) {
        item {
            CheckList(
                stringResource(R.string.section_headset),
                Gesture.entries.filter { it.group == GestureGroup.HEADSET && (s.headsetHold || it != Gesture.HS_ASSIST) },
                actions, check,
            )
        }
    }
    item { Hint(stringResource(R.string.test_hint)) }
}

// ---------------------------------------------------------------- Settings

private fun LazyListScope.settingsPage(ctx: PageContext) {
    item { SettingsHub(ctx) }
}

/** One line of the settings list: opens [page]; [summary] tells what is set there now. */
private class Category(val page: Page, val icon: androidx.compose.ui.graphics.vector.ImageVector, val summary: String)

/** The settings, Android style: a short list of sections, each telling what is set inside. */
@Composable
private fun SettingsHub(ctx: PageContext) {
    val s = ctx.s
    val context = LocalContext.current
    fun sentence(parts: List<String>) = parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    val mic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val groups = listOf(
        listOf(
            Category(
                Page.SETTINGS_GENERAL, KnobIcons.Tune,
                sentence(
                    buildList {
                        if (s.onlyWhenPlaying) add(stringResource(R.string.sum_when_playing))
                        if (s.headphonesOnly) add(stringResource(R.string.sum_headphones))
                        if (s.vibrate) add(stringResource(R.string.sum_vibrate))
                    }.ifEmpty { listOf(stringResource(R.string.sum_no_limits)) },
                ),
            ),
            Category(
                Page.SETTINGS_ACTIONS, KnobIcons.FastForward,
                sentence(
                    listOf(
                        stringResource(if (s.seekSmooth) R.string.sum_seek_smooth else R.string.sum_seek_steps),
                        stringResource(R.string.sum_seek_step, s.seekStepSec),
                    ),
                ),
            ),
            Category(
                Page.SETTINGS_SOUNDS, KnobIcons.VolumeUp,
                stringResource(if (s.cueStyle == CueStyle.OFF) R.string.sum_off else R.string.sum_cues_on),
            ),
            Category(
                Page.SETTINGS_SPEECH, KnobIcons.RecordVoice,
                stringResource(
                    when {
                        s.announceAllTracks -> R.string.sum_speech_all
                        s.announceSkips -> R.string.sum_speech_skips
                        else -> R.string.sum_speech_gesture
                    },
                ),
            ),
        ),
        listOf(
            Category(
                Page.SETTINGS_VOICE, KnobIcons.Mic,
                stringResource(if (mic) R.string.sum_voice_ready else R.string.sum_voice_mic),
            ),
            Category(
                Page.SETTINGS_PAUSE, KnobIcons.Bedtime,
                sentence(
                    buildList {
                        add(
                            if (s.resumeMinutes > 0) {
                                stringResource(R.string.sum_resume, s.resumeMinutes)
                            } else {
                                stringResource(R.string.sum_resume_off)
                            },
                        )
                        if (s.sleepUntil > System.currentTimeMillis()) {
                            add(
                                stringResource(
                                    R.string.sum_sleep_until,
                                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.sleepUntil)),
                                ),
                            )
                        }
                    },
                ),
            ),
            Category(
                Page.SETTINGS_POCKET, KnobIcons.PhoneDark,
                sentence(
                    buildList {
                        add(stringResource(if (s.pocketAuto) R.string.sum_pocket_power else R.string.sum_pocket_manual))
                        if (s.pocketGuard) add(stringResource(R.string.sum_pocket_guard))
                    },
                ),
            ),
        ),
        listOf(
            Category(Page.SETTINGS_ABOUT, KnobIcons.Info, stringResource(R.string.sum_about, ctx.versionName)),
        ),
    )
    val scheme = MaterialTheme.colorScheme
    // Each section its own colour, as in the system settings.
    val tints = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
    var n = 0
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        groups.forEach { group ->
            Group(group.size) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    group.forEachIndexed { i, c ->
                        val (bg, fg) = tints[n++ % tints.size]
                        ListRow(
                            shape(i), stringResource(c.page.title),
                            subtitle = c.summary,
                            onClick = { ctx.open(c.page) },
                            leading = { ShapeBadge(MaterialShapes.Circle, c.icon, bg, fg, size = 40) },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.generalSettings(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(3) {
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
                    shape(2), stringResource(R.string.pref_vibrate), s.vibrate, { prefs.vibrate = it },
                    subtitle = stringResource(R.string.pref_vibrate_hint), enabled = s.enabled,
                )
            }
        }
    }
}

private fun LazyListScope.actionSettings(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Column {
            // The two smooth-seeking options only show when smooth seeking is chosen.
            val extra = if (s.seekSmooth) 2 else 0
            Group(3 + extra) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ControlRow(shape(0), stringResource(R.string.pref_seek_hold), caption = stringResource(R.string.pref_seek_hold_hint), enabled = s.enabled) {
                        ConnectedChoice(
                            options = listOf(true to R.string.seek_hold_smooth, false to R.string.seek_hold_steps),
                            selected = s.seekSmooth,
                            onSelect = { prefs.seekSmooth = it },
                            enabled = s.enabled,
                        )
                    }
                    if (s.seekSmooth) {
                        SwitchRow(
                            shape(1), stringResource(R.string.pref_scrub_duck), s.scrubDuck, { prefs.scrubDuck = it },
                            subtitle = stringResource(R.string.pref_scrub_duck_hint), enabled = s.enabled,
                        )
                        SwitchRow(
                            shape(2), stringResource(R.string.pref_scrub_speak), s.scrubSpeak, { prefs.scrubSpeak = it },
                            subtitle = stringResource(R.string.pref_scrub_speak_hint), enabled = s.enabled,
                        )
                    }
                    ControlRow(shape(1 + extra), stringResource(R.string.pref_seek_step), caption = stringResource(R.string.pref_seek_step_hint), enabled = s.enabled) {
                        ConnectedChoice(
                            options = listOf(5 to R.string.seek_5, 10 to R.string.seek_10, 15 to R.string.seek_15, 30 to R.string.seek_30),
                            selected = s.seekStepSec,
                            onSelect = { prefs.seekStepSec = it },
                            enabled = s.enabled,
                        )
                    }
                    SwitchRow(
                        shape(2 + extra), stringResource(R.string.pref_like_toggle), s.likeToggle, { prefs.likeToggle = it },
                        subtitle = stringResource(R.string.pref_like_toggle_hint), enabled = s.enabled,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.speechSettings(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(2) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    shape(0), stringResource(R.string.pref_announce_skips), s.announceSkips, { prefs.announceSkips = it },
                    subtitle = stringResource(R.string.pref_announce_skips_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(1), stringResource(R.string.pref_announce_all), s.announceAllTracks, { prefs.announceAllTracks = it },
                    subtitle = stringResource(R.string.pref_announce_all_hint), enabled = s.enabled,
                )
            }
        }
    }
    item { SpeechCard(s, prefs) }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.info_title))
            Group(6) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ControlRow(shape(0), stringResource(R.string.info_delivery), caption = stringResource(R.string.info_delivery_hint)) {
                        ConnectedChoice(
                            options = listOf(
                                com.knobnext.InfoDelivery.VOICE to R.string.info_delivery_voice,
                                com.knobnext.InfoDelivery.BUBBLE to R.string.info_delivery_bubble,
                                com.knobnext.InfoDelivery.BOTH to R.string.info_delivery_both,
                            ),
                            selected = s.infoDelivery,
                            onSelect = { prefs.infoDelivery = it },
                        )
                    }
                    ControlRow(
                        shape(1), stringResource(R.string.info_source),
                        caption = stringResource(
                            when (s.infoSource) {
                                com.knobnext.InfoSource.GEMINI -> R.string.info_source_gemini_hint
                                com.knobnext.InfoSource.COMBINED -> R.string.info_source_combined_hint
                                com.knobnext.InfoSource.WIKI -> R.string.info_source_wiki_hint
                                com.knobnext.InfoSource.CLOUD -> if (s.cloudReady) R.string.info_source_cloud_hint else R.string.info_source_cloud_nokey
                            },
                        ),
                    ) {
                        ConnectedChoice(
                            options = listOf(
                                com.knobnext.InfoSource.GEMINI to R.string.info_source_gemini,
                                com.knobnext.InfoSource.COMBINED to R.string.info_source_combined,
                                com.knobnext.InfoSource.WIKI to R.string.info_source_wiki,
                            ) + if (s.devMode) listOf(com.knobnext.InfoSource.CLOUD to R.string.info_source_cloud) else emptyList(),
                            selected = s.infoSource,
                            onSelect = { prefs.infoSource = it },
                        )
                    }
                    ControlRow(shape(2), stringResource(R.string.info_language)) {
                        ConnectedChoice(
                            options = listOf(
                                com.knobnext.InfoLanguage.RU to R.string.info_language_ru,
                                com.knobnext.InfoLanguage.EN to R.string.info_language_en,
                                com.knobnext.InfoLanguage.SYSTEM to R.string.info_language_system,
                            ),
                            selected = s.infoLanguage,
                            onSelect = { prefs.infoLanguage = it },
                        )
                    }
                    ControlRow(shape(3), stringResource(R.string.info_length)) {
                        ConnectedChoice(
                            options = listOf(
                                com.knobnext.InfoLength.SHORT to R.string.info_length_short,
                                com.knobnext.InfoLength.NORMAL to R.string.info_length_normal,
                                com.knobnext.InfoLength.LONG to R.string.info_length_long,
                            ),
                            selected = s.infoLength,
                            onSelect = { prefs.infoLength = it },
                        )
                    }
                    ControlRow(shape(4), stringResource(R.string.info_music), caption = stringResource(R.string.info_music_hint)) {
                        ConnectedChoice(
                            options = listOf(false to R.string.info_music_duck, true to R.string.info_music_pause),
                            selected = s.infoPause,
                            onSelect = { prefs.infoPause = it },
                        )
                    }
                    SwitchRow(
                        shape(5), stringResource(R.string.acq_history), s.infoHistory, { prefs.infoHistory = it },
                        subtitle = stringResource(R.string.acq_history_hint),
                    )
                }
            }
            // Quietly, what told the last story: the model, the facts as they are, the cloud with its search.
            if (s.storyLast.isNotBlank()) Hint(stringResource(R.string.info_last, s.storyLast, clock(s.storyLastAt)))
            Hint(stringResource(R.string.info_hint))
            SectionTitle(stringResource(R.string.bubble_title))
            Group(3) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ControlRow(shape(0), stringResource(R.string.bubble_auto), caption = stringResource(R.string.bubble_auto_hint)) {
                        ConnectedChoice(
                            options = listOf(
                                com.knobnext.BubbleAuto.OFF to R.string.bubble_auto_off,
                                com.knobnext.BubbleAuto.EVERY to R.string.bubble_auto_every,
                                com.knobnext.BubbleAuto.NEWS to R.string.bubble_auto_news,
                            ),
                            selected = s.bubbleAuto,
                            onSelect = { prefs.bubbleAuto = it },
                        )
                    }
                    ControlRow(shape(1), stringResource(R.string.bubble_where)) {
                        ConnectedChoice(
                            options = listOf(
                                com.knobnext.BubbleWhere.ANYWHERE to R.string.bubble_where_any,
                                com.knobnext.BubbleWhere.LOCKED to R.string.bubble_where_locked,
                            ),
                            selected = s.bubbleWhere,
                            onSelect = { prefs.bubbleWhere = it },
                        )
                    }
                    ControlRow(shape(2), stringResource(R.string.bubble_side)) {
                        ConnectedChoice(
                            options = listOf(
                                com.knobnext.BubbleSide.RIGHT to R.string.bubble_side_right,
                                com.knobnext.BubbleSide.LEFT to R.string.bubble_side_left,
                            ),
                            selected = s.bubbleSide,
                            onSelect = { prefs.bubbleSide = it },
                        )
                    }
                }
            }
            Hint(stringResource(R.string.bubble_remote_hint))
            // Experimental, for development: shown in developer mode only.
            if (s.devMode) CloudKeys(s, prefs)
            SourceKeys(prefs)
        }
    }
}

// ---------------------------------------------------------------- Acquaintances

private fun LazyListScope.acquaintPage(ctx: PageContext) {
    if (!ctx.s.infoHistory) {
        item {
            Group(1) {
                SwitchRow(
                    shape(0), stringResource(R.string.acq_history), false, { ctx.prefs.infoHistory = it },
                    subtitle = stringResource(R.string.acq_history_off_hint),
                )
            }
        }
    }
    item { HistoryList(ctx.s.journalVersion, ctx.openArtist) }
}


private fun LazyListScope.pauseSettings(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(2) {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            SwitchRow(
                shape(1), stringResource(R.string.pref_keys_on_pause), s.keysOnPause, { prefs.keysOnPause = it },
                subtitle = stringResource(R.string.pref_keys_on_pause_hint), enabled = s.enabled && s.resumeMinutes > 0,
            )
          }
        }
    }
    val sleepActive = s.sleepUntil > System.currentTimeMillis()
    item {
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
}

private fun LazyListScope.pocketSettings(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item { PocketCard(ctx) }
    item {
        Group(1) {
            SwitchRow(
                shape(0), stringResource(R.string.pref_pocket), s.pocketGuard, { prefs.pocketGuard = it },
                subtitle = stringResource(R.string.pref_pocket_hint), enabled = s.enabled,
            )
        }
    }
}

private fun LazyListScope.aboutSettings(ctx: PageContext) {
    val s = ctx.s
    val prefs = ctx.prefs
    item {
        Group(4) {
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
                DevModeRow(shape(3), ctx.versionName, s.devMode, prefs)
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
    highlighted: Boolean = false,
    onEdit: (Gesture) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val action = actions[gesture] ?: Action.NONE
    ListRow(
        shape = shape(index),
        title = stringResource(gestureLabel(gesture)),
        subtitle = stringResource(actionLabel(action)),
        enabled = enabled && s.enabled,
        highlighted = highlighted,
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
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * The build, and developer mode behind it: seven taps turn it on, as in Android's own settings; once
 * on, the row is a switch that turns it off.
 */
@Composable
private fun GroupScope.DevModeRow(shape: androidx.compose.ui.graphics.Shape, version: String, on: Boolean, prefs: Prefs) {
    val context = LocalContext.current
    if (on) {
        SwitchRow(shape, stringResource(R.string.dev_mode), true, { prefs.devMode = it }, subtitle = stringResource(R.string.dev_mode_hint))
        return
    }
    var taps by remember { mutableIntStateOf(0) }
    ListRow(
        shape, stringResource(R.string.dev_build), stringResource(R.string.version_value, version),
        onClick = {
            taps++
            val left = DEV_TAPS - taps
            when {
                left <= 0 -> {
                    prefs.devMode = true
                    taps = 0
                    android.widget.Toast.makeText(context, context.getString(R.string.dev_mode_on), android.widget.Toast.LENGTH_SHORT).show()
                }
                left <= DEV_TAPS_SHOWN ->
                    android.widget.Toast.makeText(context, context.getString(R.string.dev_mode_left, left), android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    )
}

private const val DEV_TAPS = 7
private const val DEV_TAPS_SHOWN = 4
