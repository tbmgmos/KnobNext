package com.knobnext

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The track story without sound: a round cover at the edge of the screen, above every app and the lock
 * screen. A tap opens a card: the track, a note about it (the same the voice would tell), the artists of
 * its circle to play in the player, "More" for the next note. For those who would rather read than be
 * talked over. An accessibility service may draw such a window without any other permission.
 */
class TrackBubble(
    private val service: AccessibilityService,
    private val prefs: Prefs,
    private val log: (String) -> Unit,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubble: View? = null
    private var card: View? = null
    private var noteView: TextView? = null
    private var statusView: TextView? = null
    private var chipsRow: LinearLayout? = null
    private var circleBox: View? = null
    private var track: TrackInfo.Track? = null
    private var art: Bitmap? = null
    private var controller: MediaController? = null
    private var expanded = false
    private var noteJob: Job? = null
    private var circleJob: Job? = null
    private val hide = Runnable { dismiss() }

    val shown: Boolean get() = root != null

    /** The card is open (not just the round cover). */
    val open: Boolean get() = root != null && expanded

    /** The card about [t] is open now: the same gesture again closes it. */
    fun openFor(t: TrackInfo.Track): Boolean = root != null && expanded && track?.title == t.title && track?.artist == t.artist

    /**
     * Shows [t]: just the round cover ([expand] false, gone again in a few seconds unless touched), or the
     * card. [fetchNote]: the card looks the note up itself; otherwise it waits for [setNote].
     */
    fun show(t: TrackInfo.Track, cover: Bitmap?, player: MediaController?, expand: Boolean, fetchNote: Boolean = true) {
        val sameTrack = track?.title == t.title && track?.artist == t.artist
        track = t
        art = cover ?: art.takeIf { sameTrack }
        controller = player
        if (root == null && !attach()) return
        if (expand) expand(fetchNote) else collapse()
    }

    /** The note to show in the card (null: nothing was found). */
    fun setNote(text: String?) {
        noteView?.text = text ?: service.getString(R.string.speak_info_none)
        keepOpen()
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        noteJob?.cancel()
        circleJob?.cancel()
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        bubble = null
        card = null
        expanded = false
    }

    fun release() {
        dismiss()
        scope.cancel()
    }

    // ---- States ----

    private fun collapse() {
        expanded = false
        noteJob?.cancel()
        circleJob?.cancel()
        val r = root ?: return
        r.removeAllViews()
        val b = coverView(BUBBLE_DP, round = true)
        dragAndTap(b)
        r.addView(b)
        bubble = b
        card = null
        params?.let { it.flags = it.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv(); update(it) }
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, COLLAPSED_MS)
    }

    private fun expand(fetchNote: Boolean) {
        val r = root ?: return
        val t = track ?: return
        expanded = true
        r.removeAllViews()
        val c = buildCard(t)
        r.addView(c)
        card = c
        bubble = null
        // A tap elsewhere folds it back.
        params?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH; update(it) }
        if (fetchNote) fetchNote() else noteView?.setText(R.string.bubble_loading)
        loadCircle(t)
        keepOpen()
    }

    private fun keepOpen() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, if (expanded) EXPANDED_MS else COLLAPSED_MS)
    }

    // ---- What the card shows ----

    /** The next note about the track: the same the voice would tell, and kept in the history the same way. */
    private fun fetchNote() {
        val t = track ?: return
        noteJob?.cancel()
        noteView?.setText(R.string.bubble_loading)
        noteJob = scope.launch {
            val told = runCatching {
                TrackInfo.story(service, t, prefs.infoLength, prefs.infoLanguage.resolve(), prefs.infoSource) { log(it) }
            }.getOrNull()
            setNote(told)
        }
    }

    /** The artists around this one, each a button that plays them in the player. */
    private fun loadCircle(t: TrackInfo.Track) {
        circleJob?.cancel()
        circleJob = scope.launch {
            val facts = runCatching { TrackInfo.facts(service, t, prefs.infoLanguage.resolve()) }.getOrNull()
            val self = t.artist?.let(Journal::key)
            val names = facts?.related.orEmpty().distinctBy { Journal.key(it) }.filter { Journal.key(it) != self }.take(MAX_CHIPS)
            val row = chipsRow ?: return@launch
            row.removeAllViews()
            names.forEach { name -> row.addView(chip(name)) }
            circleBox?.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun play(name: String) {
        val r = CirclePlay.play(service, controller, prefs.lastPlayer, name)
        val player = r.player?.let { appLabel(service, it) }
        val text = when {
            player == null -> service.getString(R.string.log_no_player)
            r.ok -> service.getString(R.string.log_circle_play, name, player)
            else -> service.getString(R.string.log_circle_cannot, player, name)
        }
        log(text)
        statusView?.apply {
            this.text = text.replaceFirstChar { it.uppercase() }
            visibility = View.VISIBLE
        }
        keepOpen()
    }

    // ---- Views ----

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
            gravity = Gravity.TOP or Gravity.END
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

    private fun buildCard(t: TrackInfo.Track): View {
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(24).toFloat() }
            setPadding(dp(16), dp(16), dp(16), dp(10))
            elevation = dp(8).toFloat()
        }
        val head = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(coverView(COVER_DP, round = false))
        val names = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        names.addView(text(Speech.cleanTitle(t.title), 16f, TEXT, bold = true, lines = 2))
        val sub = listOfNotNull(t.artist?.let(Speech::cleanArtist), t.album?.let(Speech::cleanTitle), t.year).joinToString(" · ")
        if (sub.isNotEmpty()) names.addView(text(sub, 13f, MUTED, lines = 2))
        head.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)

        val note = text(service.getString(R.string.bubble_loading), 14f, TEXT, lines = MAX_NOTE_LINES).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        noteView = note
        box.addView(note)

        val circle = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        circle.addView(text(service.getString(R.string.bubble_circle), 12f, ACCENT, bold = true))
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        chipsRow = row
        circle.addView(HorizontalScrollView(service).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        circleBox = circle
        box.addView(circle)

        val status = text("", 12f, MUTED, lines = 2).apply { visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        statusView = status
        box.addView(status)

        val buttons = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0) }
        buttons.addView(button(service.getString(R.string.bubble_more)) { fetchNote() })
        buttons.addView(button(service.getString(R.string.bubble_fold)) { collapse() })
        buttons.addView(button(service.getString(R.string.bubble_close)) { dismiss() })
        box.addView(buttons)
        // Any touch inside keeps it open.
        box.setOnTouchListener { _, _ -> keepOpen(); false }
        return FrameLayout(service).apply {
            addView(box, FrameLayout.LayoutParams(dp(CARD_DP), FrameLayout.LayoutParams.WRAP_CONTENT))
            setPadding(0, 0, 0, dp(8))
        }
    }

    /** The cover: round as the bubble, rounded in the card; the artist's initial when there is no picture. */
    private fun coverView(sizeDp: Int, round: Boolean): View {
        val frame = GradientDrawable().apply {
            if (round) shape = GradientDrawable.OVAL else cornerRadius = dp(12).toFloat()
            setColor(ACCENT_DARK)
        }
        val picture = art
        val v: View = if (picture != null) {
            ImageView(service).apply {
                setImageBitmap(picture)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        } else {
            TextView(service).apply {
                text = (track?.artist ?: track?.title)?.trim()?.take(1)?.uppercase() ?: "♪"
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (round) 22f else 24f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        }
        v.background = frame
        v.outlineProvider = ViewOutlineProvider.BACKGROUND
        v.clipToOutline = true
        v.elevation = if (round) dp(6).toFloat() else 0f
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        if (round) v.contentDescription = track?.let { listOfNotNull(it.artist, it.title).joinToString(" — ") }
        return FrameLayout(service).apply {
            // Room for the shadow around the round one.
            if (round) setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(v, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        }
    }

    /** Drags the bubble up and down its edge (the place is kept); a tap opens the card. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragAndTap(v: View) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var startY = 0f
        var startParamY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    startParamY = p.y
                    moved = false
                    handler.removeCallbacks(hide)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (abs(dy) > slop) moved = true
                    if (moved) {
                        p.y = (startParamY + dy).toInt().coerceAtLeast(0)
                        update(p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.bubbleY = p.y
                        keepOpen()
                    } else {
                        expand(fetchNote = true)
                    }
                }
                MotionEvent.ACTION_CANCEL -> keepOpen()
            }
            true
        }
    }

    private fun chip(name: String) = TextView(service).apply {
        text = "▶ $name"
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(16).toFloat() }
        setPadding(dp(12), dp(6), dp(12), dp(6))
        maxLines = 1
        setOnClickListener { play(name) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(service).apply {
        text = label
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(service).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val BUBBLE_DP = 56
        const val COVER_DP = 56
        const val CARD_DP = 320
        const val EDGE_DP = 8
        const val MAX_NOTE_LINES = 9
        const val MAX_CHIPS = 8
        const val DEFAULT_Y = 0.3

        /** The bare cover goes away by itself after this; the open card after this much quiet. */
        const val COLLAPSED_MS = 8_000L
        const val EXPANDED_MS = 45_000L

        // Dark in any theme: it sits above whatever app is open.
        const val CARD = 0xF2202420.toInt()
        const val CHIP = 0xFF333A31.toInt()
        const val TEXT = 0xFFEDEFE8.toInt()
        const val MUTED = 0xFFB7BCB0.toInt()
        const val ACCENT = 0xFFB6D097.toInt()
        const val ACCENT_DARK = 0xFF3B4A2E.toInt()
    }
}
