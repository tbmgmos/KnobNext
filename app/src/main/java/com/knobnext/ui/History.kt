@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.knobnext.Brainz
import com.knobnext.Journal
import com.knobnext.KeyCheck
import com.knobnext.Prefs
import com.knobnext.R
import com.knobnext.Speech
import com.knobnext.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * The artists KnobNext told about: the most asked about on top, then everyone, then the ones nothing
 * is known about yet. While the list is open, unknown artists not looked for in a week are looked for
 * again, a few at a time.
 */
@Composable
fun HistoryList(version: Int, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val entries = remember(version) { Journal.entries(context) }
    LaunchedEffect(Unit) { recheckUnknown(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Later(context, version)
        Trails(context, version)
        if (entries.isEmpty()) {
            Hint(stringResource(R.string.acq_empty))
            return@Column
        }
        val (unknown, known) = entries.partition { it.unknown && !it.foundNow }
        val top = known.filter { it.asked >= 2 }.sortedByDescending { it.asked }.take(TOP_ARTISTS)
        if (top.isNotEmpty()) {
            SectionTitle(stringResource(R.string.acq_top))
            ArtistRows(context, top, version, onOpen) { stringResource(R.string.acq_asked, it.asked) }
        }
        if (known.isNotEmpty()) {
            SectionTitle(stringResource(R.string.acq_all))
            ArtistRows(context, known, version, onOpen) { summary(context, it) }
        }
        if (unknown.isNotEmpty()) {
            SectionTitle(stringResource(R.string.acq_unknown_title))
            Hint(stringResource(R.string.acq_unknown_list_hint))
            ArtistRows(context, unknown, version, onOpen) { summary(context, it) }
        }
        Hint(stringResource(R.string.acq_sources))
        TextButton(
            onClick = {
                // Everyone is a stranger again: the next story about anyone starts with an introduction.
                TrackInfo.forgetEveryone(context)
                Journal.clear(context)
            },
        ) { Text(stringResource(R.string.acq_clear)) }
    }
}

@Composable
private fun ArtistRows(
    context: Context,
    list: List<Journal.Entry>,
    version: Int,
    onOpen: (String) -> Unit,
    subtitle: @Composable (Journal.Entry) -> String,
) {
    Group(list.size) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.forEachIndexed { i, e ->
                ListRow(
                    shape(i), e.name,
                    subtitle = subtitle(e),
                    highlighted = e.foundNow,
                    onClick = { onOpen(e.key) },
                    leading = { Cover(context, e.key, album = null, version = version, size = LIST_COVER_DP) },
                )
            }
        }
    }
}

private fun summary(context: Context, e: Journal.Entry): String {
    if (e.foundNow) return context.getString(R.string.acq_found_now)
    if (e.unknown && e.notes.isEmpty()) return context.getString(R.string.acq_unknown)
    return listOfNotNull(
        context.getString(R.string.acq_notes, e.notes.size),
        e.albums.takeIf { it > 0 }?.let { context.getString(R.string.acq_albums, it) },
        day(e.lastAt),
    ).joinToString(" · ")
}

private fun day(at: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))

/** Unknown artists, looked for again: the cached facts are kept only when something was found. */
private suspend fun recheckUnknown(context: Context) {
    val lang = Prefs(context).infoLanguage.resolve()
    for (e in Journal.dueForRecheck(context).take(RECHECK_AT_ONCE)) {
        val track = TrackInfo.Track(e.lastTrack ?: continue, e.name, e.lastAlbum, null)
        val found = withContext(Dispatchers.IO) { runCatching { TrackInfo.facts(context, track, lang) }.getOrNull() }
        Journal.rechecked(context, e.key, found != null && !found.empty)
    }
}

/**
 * One artist: their cover, the person's own note, and what was told, grouped: about the
 * artist (the introduction and their story), then by album. Nothing known yet: where it can be added.
 */
@Composable
fun ArtistCard(key: String, version: Int, onOpen: (String) -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val e = remember(version, key) { Journal.entry(context, key) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (e == null) {
            Hint(stringResource(R.string.acq_missing))
            return@Column
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(context, key, album = null, version = version, size = CARD_COVER_DP)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.headlineSmall)
                Text(summary(context, e), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                e.via?.let {
                    Text(stringResource(R.string.acq_via, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (e.notes.isEmpty()) {
            Hint(stringResource(R.string.acq_unknown_hint))
            AddInfo(context, e.name)
        } else {
            NoteGroups(context, e, version)
        }
        if (e.links.isNotEmpty()) Links(context, e.links, version, onOpen)
        if (e.pages.isNotEmpty()) Pages(context, e.pages)
        OwnNote(context, e)
        TextButton(
            onClick = {
                TrackInfo.forgetArtist(context, e.name, e.id)
                Journal.forget(context, key)
                onGone()
            },
        ) { Text(stringResource(R.string.acq_restart)) }
        Hint(stringResource(R.string.acq_sources))
    }
}

/** The artist's own pages, from MusicBrainz: where to hear more of a small artist. */
@Composable
private fun Pages(context: Context, pages: Map<String, String>) {
    val shown = PAGE_KINDS.mapNotNull { (kind, label) -> pages[kind]?.let { label to it } }
    if (shown.isEmpty()) return
    SectionTitle(stringResource(R.string.acq_pages))
    Group(shown.size) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEachIndexed { i, (label, url) ->
                ListRow(shape(i), stringResource(label), subtitle = Uri.parse(url).host, onClick = { open(context, url) })
            }
        }
    }
}

private val PAGE_KINDS = listOf(
    "site" to R.string.acq_page_site,
    "bandcamp" to R.string.acq_page_bandcamp,
    "soundcloud" to R.string.acq_page_soundcloud,
)

@Composable
private fun NoteGroups(context: Context, e: Journal.Entry, version: Int) {
    // About the artist: the introduction and their story; the artists around them; the rest by album,
    // songs without one last.
    val (circle, notes) = e.notes.partition { it.topic == Journal.Topic.CIRCLE }
    val (artist, music) = notes.partition { it.topic == Journal.Topic.INTRO || it.topic == Journal.Topic.ARTIST }
    val albums = music.groupBy { it.album?.let(Speech::cleanTitle) }
    if (artist.isNotEmpty()) {
        SectionTitle(stringResource(R.string.acq_about_artist))
        Notes(artist, showTrack = false)
    }
    if (circle.isNotEmpty()) {
        SectionTitle(stringResource(R.string.acq_circle))
        Notes(circle, showTrack = false)
    }
    albums.entries.sortedBy { it.key == null }.forEach { (album, notes) ->
        if (album == null) {
            SectionTitle(stringResource(R.string.acq_other_songs))
        } else {
            Row(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(context, e.key, notes.last().album, version, size = ALBUM_COVER_DP)
                Text(
                    stringResource(R.string.acq_album, album),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Notes(notes, showTrack = true)
    }
}

@Composable
private fun Notes(notes: List<Journal.Note>, showTrack: Boolean) {
    Group(notes.size) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            notes.forEachIndexed { i, n ->
                val label = when {
                    n.intro -> stringResource(R.string.acq_intro)
                    showTrack -> n.track
                    else -> null
                }
                ListRow(shape(i), n.text, subtitle = listOfNotNull(label, day(n.at)).joinToString(" · "))
            }
        }
    }
}

/** The artists around this one: the ones already in the history open their card. */
@Composable
private fun Links(context: Context, links: List<String>, version: Int, onOpen: (String) -> Unit) {
    val known = remember(version) { Journal.entries(context).map { it.key }.toSet() }
    val media = remember { com.knobnext.Media(context) }
    val scope = rememberCoroutineScope()
    SectionTitle(stringResource(R.string.acq_links))
    Group(links.size) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            links.forEachIndexed { i, name ->
                val key = Journal.key(name)
                val inHistory = key in known
                ListRow(
                    shape(i), name,
                    subtitle = stringResource(if (inHistory) R.string.acq_link_known else R.string.acq_link_new),
                    onClick = if (inHistory) ({ onOpen(key) }) else null,
                    // Straight to the music: the artist in the player in use.
                    trailing = {
                        IconButton(onClick = { scope.launch { playInPlayer(context, media, name) } }) {
                            Icon(KnobIcons.PlayPause, contentDescription = stringResource(R.string.acq_link_play, name))
                        }
                    },
                )
            }
        }
    }
}

/** What was put aside from the round card: ▶ plays it in the player, ✕ takes it off the list. */
@Composable
private fun Later(context: Context, version: Int) {
    val list = remember(version) { Journal.later(context) }
    if (list.isEmpty()) return
    val media = remember { com.knobnext.Media(context) }
    val scope = rememberCoroutineScope()
    SectionTitle(stringResource(R.string.later_title))
    Hint(stringResource(R.string.later_hint))
    Group(list.size) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.forEachIndexed { i, l ->
                ListRow(
                    shape(i), l.title?.let { "${l.artist} — $it" } ?: l.artist,
                    subtitle = listOfNotNull(l.via?.let { stringResource(R.string.later_from, it) }, day(l.at)).joinToString(" · "),
                    trailing = {
                        Row {
                            IconButton(onClick = { scope.launch { playInPlayer(context, media, l.artist, l.title) } }) {
                                Icon(KnobIcons.Play, contentDescription = stringResource(R.string.acq_link_play, l.artist))
                            }
                            IconButton(onClick = { Journal.toggleLater(context, l.artist, l.title, null) }) {
                                Icon(KnobIcons.Close, contentDescription = stringResource(R.string.later_remove))
                            }
                        }
                    },
                )
            }
        }
    }
}

/** The ways taken down the circle in the round card: where each began and where it led. */
@Composable
private fun Trails(context: Context, version: Int) {
    val list = remember(version) { Journal.trails(context).take(MAX_TRAILS_SHOWN) }
    if (list.isEmpty()) return
    val media = remember { com.knobnext.Media(context) }
    val scope = rememberCoroutineScope()
    SectionTitle(stringResource(R.string.trails_title))
    Hint(stringResource(R.string.trails_hint))
    Group(list.size) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.forEachIndexed { i, t ->
                val last = t.steps.last()
                ListRow(
                    shape(i), t.steps.joinToString(" → "),
                    subtitle = listOfNotNull(
                        day(t.at),
                        t.played.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.trails_played, it.joinToString(", ")) },
                    ).joinToString(" · "),
                    trailing = {
                        IconButton(onClick = { scope.launch { playInPlayer(context, media, last) } }) {
                            Icon(KnobIcons.Play, contentDescription = stringResource(R.string.acq_link_play, last))
                        }
                    },
                )
            }
        }
    }
}

/** Plays [name] (or their track [title]) in the player in use (or the last one), and says what happened. */
private suspend fun playInPlayer(context: Context, media: com.knobnext.Media, name: String, title: String? = null) {
    val prefs = Prefs(context)
    val r = com.knobnext.CirclePlay.play(context, media.pick(media.controllers(), prefs.pausedPlayer), prefs.lastPlayer, name, title)
    val player = r.player?.let { com.knobnext.appLabel(context, it) }
    val text = when {
        player == null -> context.getString(R.string.log_no_player)
        r.ok -> context.getString(if (r.opened) R.string.log_circle_opened else R.string.log_circle_play, name, player)
        else -> context.getString(R.string.log_circle_cannot, player, name)
    }
    android.widget.Toast.makeText(context, text.replaceFirstChar { it.uppercase() }, android.widget.Toast.LENGTH_SHORT).show()
}

/** Nothing known: the open databases where the artist, or anyone who knows them, can add it. */
@Composable
private fun AddInfo(context: Context, name: String) {
    val q = Uri.encode(name)
    Group(2) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ListRow(
                shape(0), stringResource(R.string.acq_add_musicbrainz),
                subtitle = stringResource(R.string.acq_add_musicbrainz_hint),
                onClick = { open(context, "https://musicbrainz.org/search?type=artist&query=$q") },
            )
            ListRow(
                shape(1), stringResource(R.string.acq_add_wikidata),
                subtitle = stringResource(R.string.acq_add_wikidata_hint),
                onClick = { open(context, "https://www.wikidata.org/w/index.php?search=$q") },
            )
        }
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The person's own words about the artist, kept as they type. */
@Composable
private fun OwnNote(context: Context, e: Journal.Entry) {
    var text by remember(e.key) { mutableStateOf(e.own) }
    LaunchedEffect(e.key, text) {
        delay(SAVE_DELAY_MS)
        withContext(Dispatchers.IO) { Journal.setOwn(context, e.key, text.trim()) }
    }
    SectionTitle(stringResource(R.string.acq_own))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(MAX_OWN_CHARS) },
        placeholder = { Text(stringResource(R.string.acq_own_placeholder)) },
        supportingText = { Text(stringResource(R.string.acq_own_hint)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** A cover the player showed: the album's, or (null [album]) the latest album's; nothing when none was kept. */
@Composable
private fun Cover(context: Context, key: String, album: String?, version: Int, size: Int) {
    val image by produceState<ImageBitmap?>(null, key, album, version) {
        value = withContext(Dispatchers.IO) {
            val file: File? = if (album == null) Journal.latestCover(context, key) else Journal.cover(context, key, album)
            file?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
                ?: fetchCover(context, key, album)?.asImageBitmap()
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)),
        )
    } else if (size == LIST_COVER_DP) {
        ShapeBadge(
            MaterialShapes.Cookie4Sided, KnobIcons.Info,
            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, size,
        )
    }
}

/**
 * The player had no cover: the one MusicBrainz knows (Cover Art Archive), fetched once and kept like
 * the player's. [album] null: the album heard last, or any.
 */
private suspend fun fetchCover(context: Context, key: String, album: String?): Bitmap? {
    // Once per run: a saved cover redraws every card, and a failed one would be fetched again each time.
    if (!coverTried.add(key + "\u0000" + album)) return null
    val entry = Journal.entry(context, key) ?: return null
    // The album: the one asked for, else the one heard last, else any the notes are about.
    val which = album ?: entry.lastAlbum ?: entry.notes.lastOrNull { it.album != null }?.album
    val url = which?.let { entry.coverUrls[Journal.albumKey(it)] }
        // No story has found it yet (or it was told before covers were looked for): ask MusicBrainz.
        ?: which?.let { a ->
            runCatching { Brainz.coverOf(entry.name, a) }.getOrNull()
                ?: runCatching { com.knobnext.MusicSources.deezerCover(entry.name, a) }.getOrNull()
        }
            ?.also { Journal.learned(context, entry.name, entry.id, which, it, emptyMap()) }
        ?: entry.coverUrls.entries.firstOrNull()?.value
        ?: return null
    val bitmap = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = COVER_TIMEOUT_MS
            conn.readTimeout = COVER_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "KnobNext (https://github.com/tbmgmos/KnobNext)")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull() ?: return null
    Journal.saveCoverOf(context, key, which, bitmap)
    return bitmap
}

/**
 * The person's own Last.fm key: free for a non-commercial app, and with it the story has Last.fm's
 * facts. The key is checked against the service when asked.
 */
@Composable
fun SourceKeys(prefs: Prefs) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.keys_title))
    Hint(stringResource(R.string.keys_hint))
    KeyField(
        context, stringResource(R.string.keys_lastfm), prefs.lastFmKey, { prefs.lastFmKey = it },
        getUrl = "https://www.last.fm/api/account/create",
        // A wrong length or a stray character is told at once, without asking the service.
        format = { key ->
            when {
                key.length != LASTFM_KEY_CHARS -> stringResource(R.string.keys_lastfm_length, key.length)
                !KeyCheck.LASTFM_KEY.matches(key) -> stringResource(R.string.keys_lastfm_chars)
                else -> null
            }
        },
        check = { com.knobnext.MusicSources.checkLastFm(context, it) },
    )
}

/**
 * The cloud model: which service, its key (checked at once), and the model if not its usual one. Gemini
 * is first: its key is free, and it looks things up in Google itself.
 */
@Composable
fun CloudKeys(s: com.knobnext.Settings, prefs: Prefs) {
    val context = LocalContext.current
    val service = s.cloudService
    Hint(stringResource(R.string.cloud_hint))
    Column(Modifier.padding(horizontal = 16.dp)) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(com.knobnext.CloudService.entries.size) { i ->
                val option = com.knobnext.CloudService.entries[i]
                androidx.compose.material3.FilterChip(
                    selected = option == service,
                    onClick = { prefs.cloudService = option },
                    label = { Text(option.title) },
                    // A tick on the services that have a key: the ones switching can go to.
                    leadingIcon = if (option in s.cloudKeyed) {
                        { Icon(KnobIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
        Hint(stringResource(if (service.searches) R.string.cloud_searches else R.string.cloud_no_search))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cloud_fallback), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.cloud_fallback_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = s.cloudFallback, onCheckedChange = { prefs.cloudFallback = it })
        }
        // What answered last (the model it settled on, with the search or not), or why nothing did.
        if (s.cloudReady) {
            Hint(
                when {
                    s.cloudLast.isBlank() -> stringResource(R.string.cloud_last_none)
                    s.cloudLastOk -> stringResource(R.string.cloud_last_ok, s.cloudLast, clock(s.cloudLastAt))
                    else -> stringResource(R.string.cloud_last_failed, s.cloudLast, clock(s.cloudLastAt))
                },
            )
        }
    }
    androidx.compose.runtime.key(service) {
        KeyField(
            context, stringResource(R.string.cloud_key, service.title), prefs.cloudKey(service), { prefs.setCloudKey(service, it) },
            getUrl = service.keyPage,
            // A gentle word when the key does not begin the way this service's keys do (it is checked anyway).
            format = { key -> if (service.keyPrefix.none { key.startsWith(it) }) stringResource(R.string.cloud_key_prefix, service.title, service.keyPrefix.first()) else null },
            check = { com.knobnext.CloudBrain.check(service, it) },
            formatBlocks = false,
        )
        var model by remember { mutableStateOf(prefs.cloudModel(service)) }
        OutlinedTextField(
            value = model,
            onValueChange = {
                model = it.trim()
                prefs.setCloudModel(service, model)
            },
            label = { Text(stringResource(R.string.cloud_model)) },
            placeholder = { Text(if (service == com.knobnext.CloudService.OPENROUTER) "…:free" else service.defaultModel) },
            supportingText = {
                Text(
                    if (service == com.knobnext.CloudService.OPENROUTER) stringResource(R.string.cloud_model_hint_free)
                    else stringResource(R.string.cloud_model_hint, service.defaultModel),
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** A time of day as the phone shows it: "21:41". */
internal fun clock(at: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(at))

/** One more try, in a word or two: worked, refused (and what was said), or no answer. */
@Composable
private fun attempt(result: KeyCheck): String = when (result) {
    is KeyCheck.Works -> stringResource(R.string.keys_try_works)
    is KeyCheck.Rejected -> stringResource(R.string.keys_try_refused, result.message ?: "HTTP ${result.http}")
    is KeyCheck.NoAnswer -> stringResource(R.string.keys_try_no_answer)
}

/** What to tell about a key the service did not take, or could not be asked. */
@Composable
private fun keyProblem(result: KeyCheck): String = when (result) {
    is KeyCheck.Works -> stringResource(R.string.keys_works)
    is KeyCheck.Rejected -> {
        val reason = when {
            // Last.fm: error 26 is a suspended key, 29 a limit.
            result.error == KEY_SUSPENDED -> stringResource(R.string.keys_suspended)
            result.error == KEY_LIMIT || result.http == HTTP_TOO_MANY -> stringResource(R.string.keys_limit)
            // Not the API's answer (an HTML page): something on the way stopped the request, the key is not to blame.
            !result.fromApi -> stringResource(R.string.keys_blocked, result.http)
            // Last.fm: "Access Denied - You cannot access this service" is about where the request comes
            // from (a VPN's address, a country), not the key, which it calls "Invalid API key" (10).
            result.error != KEY_INVALID && result.message?.contains("access denied", ignoreCase = true) == true ->
                stringResource(R.string.keys_denied)
            result.error == KEY_INVALID || result.http == HTTP_UNAUTHORIZED || result.http == HTTP_FORBIDDEN ->
                stringResource(R.string.keys_rejected)
            else -> stringResource(R.string.keys_rejected_other, result.error ?: result.http)
        }
        // The service's own words, as it said them: the surest clue.
        val said = result.message?.let { reason + "\n" + stringResource(R.string.keys_service_said, it) } ?: reason
        // The same request signed plainly and as a browser: whether the way of asking is what is refused.
        val tries = listOfNotNull(
            result.plain?.let { stringResource(R.string.keys_try_plain, attempt(it)) },
            result.browser?.let { stringResource(R.string.keys_try_browser, attempt(it)) },
        )
        (listOf(said) + tries).joinToString("\n")
    }
    is KeyCheck.NoAnswer -> when (result.kind) {
        KeyCheck.Kind.TIMEOUT -> stringResource(R.string.keys_no_answer_timeout)
        KeyCheck.Kind.NO_HOST -> stringResource(R.string.keys_no_answer_host)
        KeyCheck.Kind.TLS -> stringResource(R.string.keys_no_answer_tls)
        KeyCheck.Kind.OTHER -> stringResource(R.string.keys_no_answer_other, result.detail)
    }
}

@Composable
private fun KeyField(
    context: Context,
    label: String,
    stored: String,
    save: (String) -> Unit,
    getUrl: String,
    format: @Composable (String) -> String?,
    check: (String) -> KeyCheck,
    /** A format problem stops the check (Last.fm's fixed length); false: only a warning, checked anyway. */
    formatBlocks: Boolean = true,
) {
    var text by remember { mutableStateOf(stored) }
    // null: not checked yet; else what the service said.
    var result by remember { mutableStateOf<KeyCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    // Bumped by "Check again": the same key asked once more (after the connection came back, say).
    var again by remember { mutableIntStateOf(0) }
    val formatProblem = if (text.isBlank()) null else format(text)
    LaunchedEffect(text, again) {
        result = null
        if (text.isBlank()) {
            save("")
            return@LaunchedEffect
        }
        delay(SAVE_DELAY_MS)
        // Kept as typed even when it looks wrong: a half-pasted key is theirs to finish.
        save(text)
        if (formatProblem != null && formatBlocks) return@LaunchedEffect
        checking = true
        try {
            result = withContext(Dispatchers.IO) {
                runCatching { check(text) }.getOrElse { KeyCheck.NoAnswer(KeyCheck.Kind.OTHER, it.javaClass.simpleName) }
            }
        } finally {
            checking = false
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim().take(MAX_KEY_CHARS) },
            label = { Text(label) },
            singleLine = true,
            supportingText = {
                val shown = result
                // The service's word wins over a guess from the format: a key it takes is fine however it looks.
                val works = shown is KeyCheck.Works
                val bad = !works && (formatProblem != null || shown != null)
                Text(
                    when {
                        text.isBlank() -> stringResource(R.string.keys_empty)
                        works -> keyProblem(shown!!)
                        formatProblem != null && (formatBlocks || shown == null) -> formatProblem
                        shown != null -> listOfNotNull(formatProblem?.substringBefore(" —"), keyProblem(shown)).joinToString(". ")
                        else -> stringResource(R.string.keys_checking)
                    },
                    color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = result !is KeyCheck.Works && (formatProblem != null || result != null),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { open(context, getUrl) }) { Text(stringResource(R.string.keys_get)) }
            if (text.isNotBlank() && !checking && (formatProblem == null || !formatBlocks)) {
                TextButton(onClick = { again++ }) { Text(stringResource(R.string.keys_check_again)) }
            }
        }
    }
}

private val coverTried: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

private const val TOP_ARTISTS = 3
private const val MAX_TRAILS_SHOWN = 10
private const val RECHECK_AT_ONCE = 3
private const val LIST_COVER_DP = 40
private const val CARD_COVER_DP = 96
private const val ALBUM_COVER_DP = 32
private const val SAVE_DELAY_MS = 600L
private const val MAX_OWN_CHARS = 500
private const val COVER_TIMEOUT_MS = 6_000
// Only a guard against pasting a page: an OpenAI project key is some 160 characters, OpenRouter's 73.
private const val MAX_KEY_CHARS = 512
private const val LASTFM_KEY_CHARS = 32

/** Last.fm's own error codes, and the HTTP statuses the services use for a key they refuse. */
private const val KEY_INVALID = 10
private const val KEY_SUSPENDED = 26
private const val KEY_LIMIT = 29
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY = 429
