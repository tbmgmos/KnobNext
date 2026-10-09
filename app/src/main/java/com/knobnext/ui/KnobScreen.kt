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
)

data class SetupState(
    val restricted: com.knobnext.Restricted,
    val serviceEnabled: Boolean,
    val playerAccess: Boolean,
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
            if (notificationAccess) {
                nowPlaying?.let { now -> item { NowPlayingCard(now, media) } }
            }

            item { GesturesSection(s) { editing = it } }
            item { SettingsSection(s, prefs) }
            item { PlayersSection(s, prefs, notificationAccess) }
            item { AboutSection(s, prefs, versionName, updatesSupported) }
        }
    }
}

@Composable
private fun GesturesSection(s: com.knobnext.Settings, onEdit: (Gesture) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shapes = listOf(
        MaterialShapes.Sunny,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Cookie7Sided,
    )
    Column {
        SectionTitle(stringResource(R.string.gestures_title))
        Group(Gesture.entries.size + 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Gesture.entries.forEachIndexed { i, g ->
                    val action = s.actions.getValue(g)
                    ListRow(
                        shape = shape(i),
                        title = stringResource(gestureLabel(g)),
                        subtitle = stringResource(actionLabel(action)),
                        enabled = !g.double || s.doubleMode != DoubleMode.OFF,
                        onClick = { onEdit(g) },
                        leading = {
                            ShapeBadge(
                                shapes[i], actionIcon(action),
                                if (g.double) scheme.tertiaryContainer else scheme.secondaryContainer,
                                if (g.double) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
                            )
                        },
                    )
                }
                ListRow(
                    shape = shape(Gesture.entries.size),
                    title = stringResource(R.string.gesture_tap),
                    subtitle = stringResource(R.string.action_volume),
                    leading = {
                        ShapeBadge(MaterialShapes.Circle, KnobIcons.VolumeUp, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.gestures_hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun SettingsSection(s: com.knobnext.Settings, prefs: Prefs) {
    Column {
        SectionTitle(stringResource(R.string.settings_title))
        Group(7) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(shape(0), stringResource(R.string.pref_enabled), s.enabled, { prefs.enabled = it }, highlighted = true)
                SwitchRow(
                    shape(1), stringResource(R.string.pref_only_when_playing), s.onlyWhenPlaying, { prefs.onlyWhenPlaying = it },
                    subtitle = stringResource(R.string.pref_only_when_playing_hint), enabled = s.enabled,
                )
                DoubleModeRow(shape(2), s.doubleMode, { prefs.doubleMode = it }, s.lastEvent, s.enabled)
                SwitchRow(
                    shape(3), stringResource(R.string.pref_headphones), s.headphonesOnly, { prefs.headphonesOnly = it },
                    subtitle = stringResource(R.string.pref_headphones_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(4), stringResource(R.string.pref_pocket), s.pocketGuard, { prefs.pocketGuard = it },
                    subtitle = stringResource(R.string.pref_pocket_hint), enabled = s.enabled,
                )
                SwitchRow(
                    shape(5), stringResource(R.string.pref_vibrate), s.vibrate, { prefs.vibrate = it },
                    subtitle = stringResource(R.string.pref_vibrate_hint), enabled = s.enabled,
                )
                LongPressRow(shape(6), s.longPressMs, { prefs.longPressMs = it }, s.enabled)
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
