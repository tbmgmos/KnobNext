package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** When the round cover appears by itself as a new track starts. */
enum class BubbleAuto {
    OFF,
    EVERY,

    /** Only when there is something to find out: an artist met for the first time, a story about the song. */
    NEWS,
}

/** Where it appears by itself: anywhere, or only over the lock screen. */
enum class BubbleWhere { ANYWHERE, LOCKED }

enum class BubbleSide { RIGHT, LEFT }

/** Why a track is worth the round cover by itself: said by a mark on it. */
enum class BubbleNews { NEW_ARTIST, STORY }

/**
 * The track story without sound: a round cover at the edge of the screen, above every app and the lock
 * screen, with a ring for how far the track has played. On the cover itself: a swipe changes the track, a
 * double tap pauses, a long press likes; a tap opens a card. The card: the track, a note about it (the
 * same the voice would tell), the artists of its circle. A name opens that artist's own card, with their
 * circle, and so on down the line (back with "Back"); the play icon plays them in the player. An accessibility service
 * may draw such a window without any other permission.
 */
class TrackBubble(
    private val service: AccessibilityService,
    private val prefs: Prefs,
    private val log: (String) -> Unit,
    /** Done the way a gesture would do it: next, previous, pause, like. */
    private val act: (Action, MediaController?) -> Outcome,
    /** Reads the text aloud, or (null) stops reading: whether it reads now. */
    private val read: (String?) -> Boolean,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var noteView: TextView? = null
    private var statusView: TextView? = null
    private var sourcesView: TextView? = null
    private var subView: TextView? = null
    private var askJob: Job? = null

    /** The cloud model's answers this time, by card and question: asked again, shown at once. */
    private val answers = HashMap<String, CloudBrain.Answer>()
    private var chipsRow: LinearLayout? = null
    private var circleBox: View? = null
    private var readButton: ImageView? = null
    private var reading = false
    private var ring: View? = null
    private var badgeView: TextView? = null
    private var track: TrackInfo.Track? = null
    private var art: Bitmap? = null
    private var palette = DEFAULT
    private var controller: MediaController? = null
    private var news: BubbleNews? = null
    private var expanded = false
    private var noteJob: Job? = null
    private var circleJob: Job? = null

    /** The note the track's card shows; null while it is being looked for. */
    private var note: String? = null
    private var trackFacts: TrackInfo.Facts? = null
    private var trackRecord: Brainz.Record? = null
    private var recordJob: Job? = null
    private var recordBox: LinearLayout? = null

    /** Down the circle: the artists whose cards were opened, each with why they are in the one before. */
    private val path = ArrayList<String>()
    private val reasons = ArrayList<String?>()
    private val artists = HashMap<String, TrackInfo.Facts?>()
    private var page = 0

    /** The trail down the circle this time the card is open: when it began, and who was opened. */
    private var trailAt = 0L
    private val trailSteps = ArrayList<String>()

    private val hide = Runnable { dismiss() }
    private val tick = object : Runnable {
        override fun run() {
            ring?.invalidate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // The player changes track (a swipe on the cover, or by itself): the cover follows.
    private var watched: MediaController? = null
    private val playerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val m = metadata ?: return
            if (root == null) return
            val t = TrackInfo.trackOf(m) ?: return
            val cover = TrackInfo.coverOf(m)
            if (same(t)) {
                // A player (Spotify) gives the title first and the album, year and cover a moment later:
                // taken as they come, the open card included.
                val was = track ?: return
                if ((was.album != null || t.album == null) && (was.year != null || t.year == null) && (art != null || cover == null)) return
                setTrack(t.copy(album = t.album ?: was.album, year = t.year ?: was.year), cover ?: art)
                if (expanded) subView?.text = subtitle(track ?: return, trackFacts) else collapse()
                return
            }
            if (expanded) return
            setTrack(t, cover)
            news = null
            note = null
            trackFacts = null
            trackRecord = null
            recordJob?.cancel()
            collapse()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            ring?.invalidate()
        }
    }

    val shown: Boolean get() = root != null

    /** The card is open (not just the round cover). */
    val open: Boolean get() = root != null && expanded

    /** The card about [t] is open now: the same gesture again closes it. */
    fun openFor(t: TrackInfo.Track): Boolean = root != null && expanded && same(t)

    /**
     * Shows [t]: just the round cover ([expand] false, gone again in a few seconds unless touched), or the
     * card. [fetchNote]: the card looks the note up itself; otherwise it waits for [setNote]. [mark]: why
     * it came by itself.
     */
    fun show(
        t: TrackInfo.Track,
        cover: Bitmap?,
        player: MediaController?,
        expand: Boolean,
        fetchNote: Boolean = true,
        mark: BubbleNews? = null,
    ) {
        val sameTrack = same(t)
        // What is already known of this track is kept when the new word on it says less.
        val known = track.takeIf { sameTrack }
        setTrack(t.copy(album = t.album ?: known?.album, year = t.year ?: known?.year), cover ?: art.takeIf { sameTrack })
        news = mark ?: news.takeIf { sameTrack }
        if (!sameTrack) {
            note = null
            trackFacts = null
            trackRecord = null
            recordJob?.cancel()
        }
        controller = player
        watch(player)
        if (root == null && !attach()) return
        if (expand) expand(fetchNote) else collapse()
    }

    /** The mark found after the cover was shown: put on it, if it still shows that track. */
    fun mark(t: TrackInfo.Track, mark: BubbleNews) {
        if (root == null || !same(t)) return
        news = mark
        if (!expanded) collapse()
    }

    /** The note to show in the card (null: nothing was found). */
    fun setNote(text: String?) {
        note = text ?: service.getString(R.string.speak_info_none)
        if (path.isEmpty()) {
            noteView?.text = note
            showSources(if (text != null) TrackInfo.lastSources else emptyList())
        }
        keepOpen()
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        handler.removeCallbacks(tick)
        noteJob?.cancel()
        circleJob?.cancel()
        watch(null)
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        ring = null
        badgeView = null
        expanded = false
        path.clear()
        reasons.clear()
        trailAt = 0L
        trailSteps.clear()
    }

    fun release() {
        dismiss()
        scope.cancel()
    }

    private fun same(t: TrackInfo.Track) = track?.title == t.title && track?.artist == t.artist

    private fun setTrack(t: TrackInfo.Track, cover: Bitmap?) {
        track = t
        art = cover
        palette = paletteOf(cover)
    }

    private fun watch(c: MediaController?) {
        if (c === watched) return
        watched?.let { runCatching { it.unregisterCallback(playerCallback) } }
        watched = c
        c?.let { runCatching { it.registerCallback(playerCallback, handler) } }
    }

    // ---- States ----

    private fun collapse() {
        expanded = false
        noteJob?.cancel()
        circleJob?.cancel()
        path.clear()
        reasons.clear()
        trailAt = 0L
        trailSteps.clear()
        val r = root ?: return
        r.removeAllViews()
        val b = bubbleView()
        remote(b)
        r.addView(b)
        params?.let { it.flags = it.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv(); update(it) }
        handler.removeCallbacks(tick)
        handler.post(tick)
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, COLLAPSED_MS)
    }

    private fun expand(fetchNote: Boolean) {
        if (root == null || track == null) return
        expanded = true
        handler.removeCallbacks(tick)
        ring = null
        badgeView = null
        // A tap elsewhere folds it back.
        params?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH; update(it) }
        if (fetchNote) note = null
        render(fetchNote)
    }

    /** The card for where the person is: the track, or the artist they went down the circle to. */
    private fun render(fetchNote: Boolean = false) {
        val r = root ?: return
        val t = track ?: return
        noteJob?.cancel()
        circleJob?.cancel()
        r.removeAllViews()
        reading = false
        r.addView(if (path.isEmpty()) trackCard(t, fetchNote) else artistCard(path.last()))
        keepOpen()
    }

    private fun keepOpen() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, if (expanded) EXPANDED_MS else COLLAPSED_MS)
    }

    // ---- The track's card ----

    private fun trackCard(t: TrackInfo.Track, fetchNote: Boolean): View {
        val box = cardBox()
        val names = header(box, coverView(COVER_DP, round = false, picture = art, letter = t.artist ?: t.title))
        names.addView(text(Speech.cleanTitle(t.title), 16f, TEXT, bold = true, lines = 2))
        val sub = text(subtitle(t, trackFacts), 13f, MUTED, lines = 2)
        subView = sub
        names.addView(sub)
        news?.let { names.addView(text(service.getString(newsLabel(it)), 12f, palette.accent, bold = true)) }
        noteSection(box)
        askSection(box, TRACK_QUESTIONS)
        circleSection(box)
        recordSection(box)
        statusSection(box)

        val tools = row(Gravity.START)
        readButton = tool(R.drawable.ic_bubble_read, R.string.bubble_read) { readAloud(noteView?.text?.toString()) }.also { tools.addView(it) }
        tools.addView(tool(R.drawable.ic_bubble_like, R.string.bubble_like) { like() })
        tools.addView(tool(R.drawable.ic_bubble_later, R.string.bubble_later) { t.artist?.let { later(it, t.title, null) } })
        tools.addView(
            tool(R.drawable.ic_bubble_share, R.string.bubble_share) {
                share(listOfNotNull(listOfNotNull(t.artist?.let(Speech::cleanArtist), Speech.cleanTitle(t.title)).joinToString(" — "), note))
            },
        )
        tools.addView(tool(R.drawable.ic_bubble_wiki, R.string.bubble_wiki) { wiki(t.artist ?: t.title, trackFacts?.artistId) })
        box.addView(tools)

        val buttons = row(Gravity.END)
        buttons.addView(button(service.getString(R.string.bubble_more)) { fetchNote() })
        buttons.addView(button(service.getString(R.string.bubble_fold)) { collapse() })
        buttons.addView(button(service.getString(R.string.bubble_close)) { dismiss() })
        box.addView(buttons)

        when {
            note != null -> noteView?.text = note
            fetchNote -> fetchNote()
            else -> noteView?.setText(R.string.bubble_loading)
        }
        loadTrackCircle(t)
        return framed(box)
    }

    /** "Artist · Album · Year": the player's, filled in from what the sources found where it said nothing. */
    private fun subtitle(t: TrackInfo.Track, facts: TrackInfo.Facts?): String = listOfNotNull(
        t.artist?.let(Speech::cleanArtist),
        (t.album ?: facts?.album)?.let(Speech::cleanTitle)?.takeIf { it.isNotBlank() },
        t.year ?: facts?.year,
    ).joinToString(" · ")

    /** The next note about the track: the same the voice would tell, and kept in the history the same way. */
    private fun fetchNote() {
        val t = track ?: return
        noteJob?.cancel()
        note = null
        noteView?.setText(R.string.bubble_loading)
        noteJob = scope.launch {
            val told = runCatching {
                TrackInfo.story(service, t, prefs.infoLength, prefs.infoLanguage.resolve(), prefs.infoSource) { log(it) }
            }.getOrNull()
            setNote(told)
        }
    }

    /** The artists around the track's artist. */
    private fun loadTrackCircle(t: TrackInfo.Track) {
        trackFacts?.let {
            showCircle(it.related, t.artist)
            return loadRecord(t, it)
        }
        circleJob = scope.launch {
            val facts = runCatching { TrackInfo.facts(service, t, prefs.infoLanguage.resolve()) }.getOrNull()
            trackFacts = facts
            if (path.isEmpty()) {
                showCircle(facts?.related.orEmpty(), t.artist)
                // The player did not say the album or the year: the sources often know them.
                subView?.text = subtitle(t, facts)
            }
            loadRecord(t, facts)
        }
    }

    /** The recording itself: a few seconds later (MusicBrainz takes a request a second), once per track. */
    private fun loadRecord(t: TrackInfo.Track, facts: TrackInfo.Facts?) {
        trackRecord?.let { return showRecord(it) }
        if (recordJob?.isActive == true) return
        recordJob = scope.launch {
            val r = runCatching { TrackInfo.record(service, t, prefs.infoLanguage.resolve(), facts?.mbid) }.getOrNull()
            if (!same(t)) return@launch
            trackRecord = r
            if (r != null && path.isEmpty()) showRecord(r)
        }
    }

    private fun showRecord(r: Brainz.Record) {
        val box = recordBox ?: return
        box.removeAllViews()
        if (r.empty) return
        box.visibility = View.VISIBLE
        box.addView(text(service.getString(R.string.bubble_record), 12f, palette.accent, bold = true))
        val lines = listOfNotNull(
            r.producers.takeIf { it.isNotEmpty() }?.let { service.getString(R.string.bubble_record_producer, it.joinToString(", ")) },
            r.studio?.let { service.getString(R.string.bubble_record_studio, it) },
            // Who wrote it, when that is news: someone else's song, or written by others than the band.
            r.writers.takeIf { it.isNotEmpty() && (r.cover || it.none { w -> Journal.key(w) == Journal.key(track?.artist ?: "") }) }
                ?.let { service.getString(R.string.bubble_record_writers, it.joinToString(", ")) },
        )
        if (lines.isNotEmpty()) box.addView(text(lines.joinToString(" · "), 13f, TEXT, lines = 3).apply { setPadding(0, dp(4), 0, 0) })
        if (r.players.isNotEmpty()) {
            songRow(box, service.getString(R.string.bubble_record_players), r.players.map { (who, what) ->
                personChip(who, what.joinToString(", "))
            })
        }
        r.original?.let { songRow(box, service.getString(R.string.bubble_record_original), listOf(songChip(it))) }
        if (r.samples.isNotEmpty()) songRow(box, service.getString(R.string.bubble_record_samples), r.samples.map(::songChip))
        if (r.sampledBy.isNotEmpty()) songRow(box, service.getString(R.string.bubble_record_sampled), r.sampledBy.map(::songChip))
        if (r.covers.isNotEmpty()) {
            songRow(box, service.getString(R.string.bubble_record_covers, r.coverCount), r.covers.map(::songChip))
        }
    }

    private fun songRow(box: LinearLayout, label: String, chips: List<View>) {
        box.addView(text(label, 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        chips.forEach { row.addView(it) }
        box.addView(HorizontalScrollView(service).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    /** One who plays on the recording: their name opens their card. */
    private fun personChip(name: String, plays: String) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.chip, 16)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        addView(text(name, 13f, TEXT))
        if (plays.isNotEmpty()) addView(text(plays, 11f, MUTED))
        setOnClickListener { dive(name) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    /** A song: who plays it opens their card, the play icon puts that very song on. */
    private fun songChip(song: Brainz.Song) = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(palette.chip, 16)
        addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(5), dp(8), dp(5))
                addView(text(song.title, 13f, TEXT))
                addView(text(listOfNotNull(song.artist, song.year).joinToString(" · "), 11f, MUTED))
                setOnClickListener { dive(song.artist) }
            },
        )
        addView(View(service).apply { setBackgroundColor(palette.card) }, LinearLayout.LayoutParams(dp(1), dp(24)))
        addView(
            ImageView(service).apply {
                setImageDrawable(icon(R.drawable.ic_bubble_play, palette.accent, CHIP_ICON_DP))
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(8), dp(6), dp(10), dp(6))
                contentDescription = service.getString(R.string.bubble_play_who, "${song.artist} — ${song.title}")
                setOnClickListener { play(song.artist, song.title) }
            },
        )
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    // ---- An artist's card, down the circle ----

    private fun artistCard(name: String): View {
        recordBox = null
        val box = cardBox()
        val picture = runCatching {
            Journal.latestCover(service, Journal.key(name))?.let { BitmapFactory.decodeFile(it.path) }
        }.getOrNull()
        val names = header(box, coverView(COVER_DP, round = true, picture = picture, letter = name))
        names.addView(text(name, 16f, TEXT, bold = true, lines = 2))
        val from = path.getOrNull(path.size - 2) ?: track?.artist?.let(Speech::cleanArtist)
        from?.let { names.addView(text(service.getString(R.string.bubble_from_circle, it), 13f, MUTED, lines = 1)) }
        reasons.lastOrNull()?.let { why ->
            box.addView(text(why, 13f, palette.accent, lines = 3).apply { setPadding(0, dp(10), 0, 0); setTypeface(typeface, Typeface.ITALIC) })
        }
        noteSection(box)
        askSection(box, ARTIST_QUESTIONS)
        circleSection(box)
        statusSection(box)

        val tools = row(Gravity.START)
        tools.addView(playButton { play(name) })
        readButton = tool(R.drawable.ic_bubble_read, R.string.bubble_read) { readAloud(noteView?.text?.toString()) }.also { tools.addView(it) }
        tools.addView(tool(R.drawable.ic_bubble_later, R.string.bubble_later) { later(name, null, from) })
        tools.addView(tool(R.drawable.ic_bubble_share, R.string.bubble_share) { share(listOfNotNull(name, reasons.lastOrNull(), noteView?.text?.toString())) })
        tools.addView(tool(R.drawable.ic_bubble_wiki, R.string.bubble_wiki) { wiki(name, artists[name]?.artistId) })
        box.addView(tools)

        val buttons = row(Gravity.END)
        buttons.addView(button(service.getString(R.string.bubble_back), R.drawable.ic_bubble_back) { back() })
        buttons.addView(button(service.getString(R.string.bubble_more)) { turnPage() })
        buttons.addView(button(service.getString(R.string.bubble_close)) { dismiss() })
        box.addView(buttons)

        page = 0
        if (artists.containsKey(name)) {
            showArtist(name, artists[name])
        } else {
            noteView?.setText(R.string.bubble_loading)
            circleJob = scope.launch {
                val facts = runCatching { TrackInfo.aboutArtist(service, name, prefs.infoLanguage.resolve()) }.getOrNull()
                if (artists.size >= MAX_KEPT_ARTISTS) artists.clear()
                artists[name] = facts
                if (path.lastOrNull() == name) showArtist(name, facts)
            }
        }
        return framed(box)
    }

    private fun showArtist(name: String, facts: TrackInfo.Facts?) {
        showPage(facts)
        // Not back to where the person came from: the circle goes on.
        val been = (path + listOfNotNull(track?.artist)).map(Journal::key).toSet()
        showCircle(facts?.related.orEmpty().filter { Journal.key(it) !in been }, name)
    }

    private fun showPage(facts: TrackInfo.Facts?) {
        val all = facts?.let { TrackInfo.aboutLines(it, prefs.infoLanguage.resolve()) }.orEmpty()
        noteView?.text = if (all.isEmpty()) {
            service.getString(R.string.bubble_artist_none)
        } else {
            val pages = (all.size + LINES_PER_PAGE - 1) / LINES_PER_PAGE
            all.drop((page % pages) * LINES_PER_PAGE).take(LINES_PER_PAGE).joinToString(" ")
        }
    }

    private fun turnPage() {
        val name = path.lastOrNull() ?: return
        if (!artists.containsKey(name)) return
        page++
        showPage(artists[name])
        keepOpen()
    }

    /** One step down the circle: [name]'s own card, and why they are in the one before. */
    private fun dive(name: String) {
        if (path.size >= MAX_DEPTH) {
            path.removeAt(0)
            reasons.removeAt(0)
        }
        val before = path.lastOrNull()?.let { artists[it] } ?: trackFacts
        path += name
        reasons += before?.let { TrackInfo.why(it.circle, name) }
        step(name)
        render()
    }

    /** [name] joins this time's trail (kept in the Journal); [played]: and was put on. */
    private fun step(name: String, played: Boolean = false) {
        val from = track?.artist?.let(Speech::cleanArtist) ?: return
        if (trailAt == 0L) {
            trailAt = System.currentTimeMillis()
            trailSteps += from
        }
        if (trailSteps.none { Journal.key(it) == Journal.key(name) }) trailSteps += name
        val at = trailAt
        val steps = trailSteps.toList()
        scope.launch(Dispatchers.IO) { Journal.keepTrail(service, at, steps, name.takeIf { played }) }
    }

    private fun back() {
        if (path.isEmpty()) return collapse()
        path.removeAt(path.size - 1)
        reasons.removeAt(reasons.size - 1)
        render()
    }

    // ---- What the buttons do ----

    private fun showCircle(related: List<String>, self: String?) {
        val me = self?.let(Journal::key)
        val names = related.distinctBy { Journal.key(it) }.filter { Journal.key(it) != me }.take(MAX_CHIPS)
        val row = chipsRow ?: return
        row.removeAllViews()
        names.forEach { name -> row.addView(chip(name)) }
        circleBox?.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun play(name: String, title: String? = null) {
        step(name, played = true)
        val what = if (title != null) "$name — $title" else name
        status(service.getString(R.string.bubble_circle_trying, what))
        scope.launch {
            val r = CirclePlay.play(service, controller, prefs.lastPlayer, name, title)
            val player = r.player?.let { appLabel(service, it) }
            val text = when {
                player == null -> service.getString(R.string.log_no_player)
                r.ok && r.opened -> service.getString(R.string.log_circle_opened, what, player)
                r.ok -> service.getString(R.string.log_circle_play, what, player)
                else -> service.getString(R.string.log_circle_cannot, player, what)
            }
            log(text)
            // The player came to the front: the card folds out of its way.
            if (r.opened) collapse() else status(text)
        }
    }

    private fun like() {
        val outcome = act(Action.LIKE, controller)
        status(
            when (outcome.result) {
                Outcome.Result.FAILED -> outcome.message ?: service.getString(R.string.log_no_track)
                Outcome.Result.UNDONE -> service.getString(R.string.bubble_unliked)
                else -> service.getString(R.string.bubble_liked)
            },
        )
    }

    private fun later(artist: String, title: String?, via: String?) {
        scope.launch {
            val aside = withContext(Dispatchers.IO) { Journal.toggleLater(service, artist, title, via) }
            val what = if (title != null) "${Speech.cleanArtist(artist)} — ${Speech.cleanTitle(title)}" else Speech.cleanArtist(artist)
            status(service.getString(if (aside) R.string.bubble_later_added else R.string.bubble_later_removed, what))
        }
    }

    /** The speaker reads the note aloud; the same button (a square then) stops it. */
    private fun readAloud(text: String?) {
        reading = read(text?.takeIf { !reading })
        readButton?.setImageDrawable(icon(if (reading) R.drawable.ic_bubble_stop else R.drawable.ic_bubble_read, TEXT, TOOL_ICON_DP))
        keepOpen()
    }

    private fun share(parts: List<String>) {
        val text = parts.filter { it.isNotBlank() }.joinToString("\n\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, null))
    }

    /** The artist's article: through their Wikidata item when known (the right one), else a search. */
    private fun wiki(name: String, id: String?) {
        val lang = prefs.infoLanguage.resolve()
        val url = if (id != null) {
            "https://www.wikidata.org/wiki/Special:GoToLinkedPage/${lang}wiki/$id"
        } else {
            "https://$lang.wikipedia.org/w/index.php?search=" + Uri.encode(Speech.cleanArtist(name))
        }
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Opens another app: the card folds away so it can be seen (on the lock screen, once unlocked). */
    private fun launch(intent: Intent) {
        try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            collapse()
        } catch (_: Exception) {
            status(service.getString(R.string.bubble_no_app))
        }
    }

    private fun status(text: String) {
        statusView?.apply {
            this.text = text.replaceFirstChar { it.uppercase() }
            visibility = View.VISIBLE
        }
        keepOpen()
    }

    // ---- The round cover: a small remote ----

    /** The cover in its ring, and the mark that says why it came by itself. */
    private fun bubbleView(): View {
        val size = BUBBLE_DP + 2 * RING_GAP_DP
        val face = FrameLayout(service)
        val r = Ring()
        ring = r
        face.addView(r, FrameLayout.LayoutParams(dp(size), dp(size)))
        face.addView(
            coverView(BUBBLE_DP, round = true, picture = art, letter = track?.artist ?: track?.title).apply {
                elevation = dp(6).toFloat()
            },
            FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP), Gravity.CENTER),
        )
        val badge = TextView(service).apply {
            setTextColor(palette.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(palette.accent, 8)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            elevation = dp(7).toFloat()
            text = news?.let { service.getString(badgeLabel(it)) }
            visibility = if (news == null) View.GONE else View.VISIBLE
        }
        badgeView = badge
        face.addView(
            badge,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )
        face.contentDescription = track?.let { listOfNotNull(it.artist, it.title).joinToString(" — ") }
        return FrameLayout(service).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(face, FrameLayout.LayoutParams(dp(size), dp(size)))
        }
    }

    /** How far the track has played, around the cover. */
    private inner class Ring : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(RING_DP).toFloat()
        }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val p = progress() ?: return
            val inset = paint.strokeWidth / 2
            box.set(inset, inset, width - inset, height - inset)
            paint.color = palette.ringTrack
            canvas.drawArc(box, 0f, 360f, false, paint)
            paint.color = palette.accent
            canvas.drawArc(box, -90f, 360f * p, false, paint)
        }
    }

    private fun progress(): Float? {
        val c = controller ?: return null
        val state = c.playbackState ?: return null
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: return null
        var at = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            at += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        return (at.toFloat() / duration).coerceIn(0f, 1f)
    }

    /**
     * On the cover: up and down moves it along the edge (the place is kept), a swipe across changes the
     * track (to the left: the next one), a double tap pauses or plays, a long press likes, a tap opens the
     * card.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun remote(v: View) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var startParamY = 0
        var moving = Move.NONE
        val taps = GestureDetector(
            service,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    expand(fetchNote = true)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    act(Action.PLAY_PAUSE, controller)
                    ring?.invalidate()
                    keepOpen()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val outcome = act(Action.LIKE, controller)
                    flashBadge(if (outcome.result == Outcome.Result.UNDONE) R.drawable.ic_bubble_unlike else R.drawable.ic_bubble_like)
                    keepOpen()
                }
            },
            handler,
        )
        v.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                startX = e.rawX
                startY = e.rawY
                startParamY = p.y
                moving = Move.NONE
                handler.removeCallbacks(hide)
            }
            if (moving == Move.NONE) taps.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (moving == Move.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        moving = if (abs(dy) >= abs(dx)) Move.ALONG else Move.ACROSS
                        // Not a tap nor a long press any more.
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        taps.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (moving == Move.ALONG) {
                        p.y = (startParamY + dy).toInt().coerceAtLeast(0)
                        update(p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    when (moving) {
                        Move.ALONG -> prefs.bubbleY = p.y
                        Move.ACROSS -> {
                            val dx = e.rawX - startX
                            if (abs(dx) >= dp(SWIPE_DP)) {
                                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                act(if (dx < 0) Action.NEXT else Action.PREVIOUS, controller)
                            }
                        }
                        Move.NONE -> Unit
                    }
                    keepOpen()
                }
                MotionEvent.ACTION_CANCEL -> keepOpen()
            }
            true
        }
    }

    private enum class Move { NONE, ALONG, ACROSS }

    /** A sign on the cover for a moment (a heart for a like), then its mark again. */
    private fun flashBadge(sign: Int) {
        val b = badgeView ?: return
        b.text = null
        b.setCompoundDrawablesRelative(icon(sign, palette.onAccent, BADGE_ICON_DP), null, null, null)
        b.visibility = View.VISIBLE
        handler.postDelayed({
            if (badgeView !== b) return@postDelayed
            b.setCompoundDrawablesRelative(null, null, null, null)
            b.text = news?.let { service.getString(badgeLabel(it)) }
            b.visibility = if (news == null) View.GONE else View.VISIBLE
        }, FLASH_MS)
    }

    // ---- Window ----

    private fun attach(): Boolean {
        val r = FrameLayout(service)
        @Suppress("DEPRECATION")
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never takes the keyboard or the back key; touches beside it go to the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or if (prefs.bubbleSide == BubbleSide.LEFT) Gravity.START else Gravity.END
            x = dp(EDGE_DP)
            y = prefs.bubbleY.takeIf { it >= 0 } ?: (service.resources.displayMetrics.heightPixels * DEFAULT_Y).toInt()
        }
        r.setOnTouchListener { _, e ->
            // A tap outside the open card folds it back to the cover.
            if (e.action == MotionEvent.ACTION_OUTSIDE && expanded) collapse()
            false
        }
        return try {
            windows.addView(r, p)
            root = r
            params = p
            true
        } catch (e: Exception) {
            log(service.getString(R.string.log_bubble_failed, e.javaClass.simpleName))
            false
        }
    }

    private fun update(p: WindowManager.LayoutParams) {
        root?.let { runCatching { windows.updateViewLayout(it, p) } }
    }

    // ---- Views ----

    @SuppressLint("ClickableViewAccessibility")
    private fun cardBox() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(6))
        // Any touch inside keeps it open.
        setOnTouchListener { _, _ -> keepOpen(); false }
    }

    /** The card on its background; taller than most of the screen, it scrolls. */
    private fun framed(box: View) = FrameLayout(service).apply {
        val scroll = Capped().apply {
            isVerticalScrollBarEnabled = false
            background = rounded(palette.card, 24)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(8).toFloat()
            addView(box)
        }
        addView(scroll, FrameLayout.LayoutParams(dp(CARD_DP), FrameLayout.LayoutParams.WRAP_CONTENT))
        setPadding(dp(4), 0, dp(4), dp(8))
    }

    private inner class Capped : android.widget.ScrollView(service) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val most = (resources.displayMetrics.heightPixels * MAX_CARD_HEIGHT).toInt()
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(most, View.MeasureSpec.AT_MOST))
        }
    }

    /** The picture and, beside it, a column for the names: returned to be filled. */
    private fun header(box: LinearLayout, picture: View): LinearLayout {
        val head = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(picture)
        val names = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        head.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)
        return names
    }

    private fun noteSection(box: LinearLayout) {
        // Read whole: the card scrolls when the note is long.
        val n = text(service.getString(R.string.bubble_loading), 14f, TEXT, lines = Int.MAX_VALUE).apply {
            ellipsize = null
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        noteView = n
        box.addView(n)
        val src = text("", 11f, MUTED, lines = 2).apply {
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        sourcesView = src
        box.addView(src)
    }

    /** Where the cloud model found what it said: their names; a tap opens the first. */
    private fun showSources(sources: List<CloudBrain.Source>) {
        val v = sourcesView ?: return
        if (sources.isEmpty()) {
            v.visibility = View.GONE
            return
        }
        v.text = service.getString(R.string.bubble_sources, sources.take(MAX_SOURCES).joinToString(", ") { it.title })
        v.visibility = View.VISIBLE
        v.setOnClickListener { launch(Intent(Intent.ACTION_VIEW, Uri.parse(sources.first().url))) }
    }

    /** Questions to the cloud model, when it has a key: the answer takes the note's place. */
    private fun askSection(box: LinearLayout, questions: List<Pair<TrackInfo.Question, Int>>) {
        if (CloudBrain.configured(prefs) == null) return
        val section = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        section.addView(text(service.getString(R.string.bubble_ask), 12f, palette.accent, bold = true))
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        questions.forEach { (q, label) ->
            row.addView(
                text(service.getString(label), 13f, TEXT).apply {
                    background = rounded(palette.chip, 16)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setOnClickListener { ask(q) }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(8)
                    }
                },
            )
        }
        section.addView(HorizontalScrollView(service).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        box.addView(section)
    }

    /** [q] about the track, or about the artist whose card is open. */
    private fun ask(q: TrackInfo.Question) {
        val t = track ?: return
        val artist = path.lastOrNull()
        val key = "${artist ?: "${t.artist}\u0000${t.title}"}\u0000${q.name}"
        val cached = answers[key]
        if (cached != null) return showAnswer(q, cached, artist)
        askJob?.cancel()
        noteView?.setText(R.string.bubble_asking)
        showSources(emptyList())
        keepOpen()
        askJob = scope.launch {
            val facts = if (artist != null) artists[artist] else trackFacts
            val a = runCatching { TrackInfo.ask(service, t.takeIf { artist == null }, artist, q, prefs.infoLanguage.resolve(), facts) }.getOrNull()
            if (a == null) {
                noteView?.text = service.getString(R.string.bubble_ask_failed, CloudBrain.lastError ?: "-")
                log(service.getString(R.string.log_bubble_ask_failed, listOfNotNull(CloudBrain.lastError, CloudBrain.lastDetail).joinToString(" — ")))
                return@launch
            }
            answers[key] = a
            if (path.lastOrNull() == artist) showAnswer(q, a, artist)
        }
    }

    private fun showAnswer(q: TrackInfo.Question, a: CloudBrain.Answer, artist: String?) {
        noteView?.text = a.text
        showSources(a.sources)
        // "What next": the names it gave join the circle, to open and play like any other.
        if (q == TrackInfo.Question.NEXT) {
            val names = a.text.lines().mapNotNull { line ->
                line.split(" — ", " – ", " - ").firstOrNull()?.trim()?.trim('«', '»', '"', '*')?.takeIf { it.length in 2..MAX_NAME_CHARS }
            }
            if (names.isNotEmpty()) showCircle(names, artist ?: track?.artist)
        }
        keepOpen()
    }

    /** "About the recording", filled once MusicBrainz has answered. */
    private fun recordSection(box: LinearLayout) {
        val r = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        recordBox = r
        box.addView(r)
    }

    private fun circleSection(box: LinearLayout) {
        val circle = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        circle.addView(text(service.getString(R.string.bubble_circle), 12f, palette.accent, bold = true))
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        chipsRow = row
        circle.addView(HorizontalScrollView(service).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        circleBox = circle
        box.addView(circle)
    }

    private fun statusSection(box: LinearLayout) {
        val s = text("", 12f, MUTED, lines = 2).apply { visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        statusView = s
        box.addView(s)
    }

    private fun row(gravity: Int) = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity or Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, 0)
    }

    /** A cover: round as the bubble, rounded in the card; the initial when there is no picture. */
    private fun coverView(sizeDp: Int, round: Boolean, picture: Bitmap?, letter: String?): View {
        val frame = GradientDrawable().apply {
            if (round) shape = GradientDrawable.OVAL else cornerRadius = dp(12).toFloat()
            setColor(palette.accentDark)
        }
        val v: View = if (picture != null) {
            ImageView(service).apply {
                setImageBitmap(picture)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        } else {
            val initial = letter?.trim()?.take(1)?.uppercase()?.takeIf { it.isNotEmpty() }
            if (initial == null) {
                ImageView(service).apply {
                    setImageDrawable(icon(R.drawable.ic_bubble_music, TEXT, sizeDp / 2))
                    scaleType = ImageView.ScaleType.CENTER
                }
            } else {
                TextView(service).apply {
                    text = initial
                    setTextColor(TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
            }
        }
        v.background = frame
        v.outlineProvider = ViewOutlineProvider.BACKGROUND
        v.clipToOutline = true
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    /** An artist of the circle: the name opens their card, the play icon plays them. */
    private fun chip(name: String) = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(palette.chip, 16)
        addView(
            text(name, 13f, TEXT).apply {
                setPadding(dp(12), dp(7), dp(8), dp(7))
                setOnClickListener { dive(name) }
            },
        )
        addView(View(service).apply { setBackgroundColor(palette.card) }, LinearLayout.LayoutParams(dp(1), dp(18)))
        addView(
            ImageView(service).apply {
                setImageDrawable(icon(R.drawable.ic_bubble_play, palette.accent, CHIP_ICON_DP))
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(8), dp(6), dp(10), dp(6))
                contentDescription = service.getString(R.string.bubble_play_who, name)
                setOnClickListener { play(name) }
            },
        )
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    /** A button that is only an icon, named for TalkBack. */
    private fun tool(res: Int, label: Int, onClick: () -> Unit) = ImageView(service).apply {
        setImageDrawable(icon(res, TEXT, TOOL_ICON_DP))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = service.getString(label)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.chip) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(TOOL_DP), dp(TOOL_DP)).apply { marginEnd = dp(8) }
    }

    /** "Play" on an artist's card: the one button with a word, it is what the card leads to. */
    private fun playButton(onClick: () -> Unit) = TextView(service).apply {
        text = service.getString(R.string.bubble_play)
        setTextColor(palette.onAccent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(palette.accent, TOOL_DP / 2)
        setCompoundDrawablesRelative(icon(R.drawable.ic_bubble_play, palette.onAccent, CHIP_ICON_DP), null, null, null)
        compoundDrawablePadding = dp(4)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(TOOL_DP)).apply { marginEnd = dp(8) }
    }

    private fun button(label: String, iconRes: Int? = null, onClick: () -> Unit) = TextView(service).apply {
        text = label
        setTextColor(palette.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        iconRes?.let {
            setCompoundDrawablesRelative(icon(it, palette.accent, CHIP_ICON_DP), null, null, null)
            compoundDrawablePadding = dp(4)
        }
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    /** One of the app's own icons, in [color], [sizeDp] square. */
    private fun icon(res: Int, color: Int, sizeDp: Int): Drawable? =
        service.getDrawable(res)?.mutate()?.apply {
            setTint(color)
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(service).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private fun newsLabel(n: BubbleNews) = when (n) {
        BubbleNews.NEW_ARTIST -> R.string.bubble_news_artist
        BubbleNews.STORY -> R.string.bubble_news_story
    }

    private fun badgeLabel(n: BubbleNews) = when (n) {
        BubbleNews.NEW_ARTIST -> R.string.bubble_badge_new
        BubbleNews.STORY -> R.string.bubble_badge_story
    }

    // ---- Colours: the cover's own, dark enough for light text above any app ----

    private class Palette(val card: Int, val chip: Int, val accent: Int, val accentDark: Int, val onAccent: Int, val ringTrack: Int)

    private fun paletteOf(cover: Bitmap?): Palette {
        val base = cover?.let(::averageColor) ?: return DEFAULT
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        // A grey cover gives no colour of its own: the usual one.
        if (hsv[1] < MIN_SATURATION) return DEFAULT
        val hue = hsv[0]
        val s = hsv[1].coerceAtMost(MAX_SATURATION)
        fun c(sat: Float, value: Float, alpha: Int = 0xFF) = Color.HSVToColor(alpha, floatArrayOf(hue, sat, value))
        return Palette(
            card = c(s, 0.17f, 0xF2),
            chip = c(s, 0.28f),
            accent = c(s.coerceAtMost(0.45f), 0.9f),
            accentDark = c(s, 0.35f),
            onAccent = c(s, 0.15f),
            ringTrack = c(s * 0.5f, 0.6f, 0x66),
        )
    }

    private fun averageColor(b: Bitmap): Int? = runCatching {
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val small = Bitmap.createScaledBitmap(soft, SAMPLE_PX, SAMPLE_PX, true)
        var r = 0L
        var g = 0L
        var bl = 0L
        for (x in 0 until SAMPLE_PX) {
            for (y in 0 until SAMPLE_PX) {
                val px = small.getPixel(x, y)
                r += Color.red(px)
                g += Color.green(px)
                bl += Color.blue(px)
            }
        }
        val n = SAMPLE_PX * SAMPLE_PX
        Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }.getOrNull()

    private companion object {
        const val BUBBLE_DP = 56
        const val RING_GAP_DP = 4
        const val RING_DP = 3
        const val COVER_DP = 56
        const val CARD_DP = 320
        const val EDGE_DP = 8
        const val MAX_CHIPS = 8
        const val MAX_SOURCES = 3
        const val MAX_NAME_CHARS = 40

        val TRACK_QUESTIONS = listOf(
            TrackInfo.Question.SONG to R.string.ask_song,
            TrackInfo.Question.MAKING to R.string.ask_making,
            TrackInfo.Question.NEXT to R.string.ask_next,
            TrackInfo.Question.START to R.string.ask_start,
            TrackInfo.Question.ROOTS to R.string.ask_roots,
        )
        val ARTIST_QUESTIONS = listOf(
            TrackInfo.Question.WHO to R.string.ask_who,
            TrackInfo.Question.START to R.string.ask_start,
            TrackInfo.Question.NEXT to R.string.ask_next,
            TrackInfo.Question.ROOTS to R.string.ask_roots,
        )
        const val DEFAULT_Y = 0.3

        /** The open card's height at most, of the screen's: more scrolls. */
        const val MAX_CARD_HEIGHT = 0.75f

        /** A swipe across the cover this long changes the track. */
        const val SWIPE_DP = 36

        /** How deep down the circle: the first steps are let go after this. */
        const val MAX_DEPTH = 12
        const val MAX_KEPT_ARTISTS = 40
        const val LINES_PER_PAGE = 2

        /** The bare cover goes away by itself after this; the open card after this much quiet. */
        const val COLLAPSED_MS = 8_000L
        const val EXPANDED_MS = 45_000L
        const val TICK_MS = 1_000L
        const val FLASH_MS = 1_500L

        const val SAMPLE_PX = 12
        const val MIN_SATURATION = 0.12f
        const val MAX_SATURATION = 0.55f

        const val TOOL_DP = 36
        const val TOOL_ICON_DP = 20
        const val CHIP_ICON_DP = 16
        const val BADGE_ICON_DP = 12

        // Dark in any theme: it sits above whatever app is open.
        const val TEXT = 0xFFEDEFE8.toInt()
        const val MUTED = 0xFFB7BCB0.toInt()
        val DEFAULT = Palette(
            card = 0xF2202420.toInt(),
            chip = 0xFF333A31.toInt(),
            accent = 0xFFB6D097.toInt(),
            accentDark = 0xFF3B4A2E.toInt(),
            onAccent = 0xFF1B2614.toInt(),
            ringTrack = 0x66B7BCB0,
        )
    }
}
