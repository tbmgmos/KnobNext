package com.knobnext

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The phone's own base of the artists the person listens to, gathered ahead of time: at night, on Wi-Fi,
 * while charging. For each: the dates (MusicBrainz), every record with its year (MusicBrainz and
 * Discogs), the band's members with their years, the labels, Discogs' biography and Wikipedia's facts.
 * A story then begins at once, works without the network, and gives the cloud model dates it need not guess.
 */
object ArtistBase {
    class Member(val name: String, val years: String?, val roles: List<String>)

    /** A record: [kind] "Album", "EP" or null (Discogs does not say), [studio] a studio album. */
    class Album(val title: String, val date: String?, val kind: String?, val studio: Boolean)

    class Card(
        val name: String,
        val at: Long,
        val lang: String,
        val mbid: String?,
        val discogsId: Long?,
        val wikidata: String?,
        val band: Boolean,
        val began: String?,
        val ended: String?,
        val members: List<Member>,
        val albums: List<Album>,
        val labels: List<String>,
        /** Discogs' biography, in English. */
        val profile: List<String>,
        /** Who they are and the rest of Wikipedia's facts, in [lang]. */
        val intro: List<String>,
        val about: List<String>,
        val related: List<String>,
        val relatedIds: List<String>,
        /** Their genres, in English as MusicBrainz names them. */
        val genres: List<String> = emptyList(),
        /** Every source answered: one that did not (MusicBrainz busy, say) is asked again the next night. */
        val complete: Boolean = true,
    ) {
        val empty get() = mbid == null && discogsId == null && intro.isEmpty() && about.isEmpty()

        fun toJson(): JSONObject = JSONObject()
            .put("name", name).put("at", at).put("lang", lang)
            .put("mbid", mbid ?: "").put("discogs", discogsId ?: 0L).put("wikidata", wikidata ?: "")
            .put("band", band).put("began", began ?: "").put("ended", ended ?: "")
            .put("members", JSONArray(members.map { JSONObject().put("n", it.name).put("y", it.years ?: "").put("r", JSONArray(it.roles)) }))
            .put("albums", JSONArray(albums.map { JSONObject().put("t", it.title).put("d", it.date ?: "").put("k", it.kind ?: "").put("s", it.studio) }))
            .put("labels", JSONArray(labels)).put("profile", JSONArray(profile))
            .put("intro", JSONArray(intro)).put("about", JSONArray(about))
            .put("related", JSONArray(related)).put("relatedIds", JSONArray(relatedIds))
            .put("complete", complete)
            .put("genres", JSONArray(genres))

        companion object {
            fun fromJson(o: JSONObject): Card {
                fun strings(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                fun objects(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                return Card(
                    name = o.optString("name"),
                    at = o.optLong("at"),
                    lang = o.optString("lang"),
                    mbid = o.optString("mbid").ifBlank { null },
                    discogsId = o.optLong("discogs").takeIf { it > 0 },
                    wikidata = o.optString("wikidata").ifBlank { null },
                    band = o.optBoolean("band"),
                    began = o.optString("began").ifBlank { null },
                    ended = o.optString("ended").ifBlank { null },
                    members = objects("members").map { m ->
                        Member(m.optString("n"), m.optString("y").ifBlank { null }, m.optJSONArray("r")?.let { r -> (0 until r.length()).map { r.optString(it) } }.orEmpty())
                    },
                    albums = objects("albums").map { Album(it.optString("t"), it.optString("d").ifBlank { null }, it.optString("k").ifBlank { null }, it.optBoolean("s")) },
                    labels = strings("labels"),
                    profile = strings("profile"),
                    intro = strings("intro"),
                    about = strings("about"),
                    related = strings("related"),
                    relatedIds = strings("relatedIds"),
                    complete = o.optBoolean("complete", true),
                    genres = strings("genres"),
                )
            }
        }
    }

    // ---- Reading ----

    private val cards = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Card?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Card?>) = size > MAX_IN_MEMORY
    })

    /** [artist]'s card, if the base has one (empty cards, of artists nothing was found about, are not given). */
    fun card(context: Context, artist: String?): Card? {
        if (artist.isNullOrBlank() || !Prefs(context).baseOn) return null
        val key = Journal.key(artist)
        if (cards.containsKey(key)) return cards[key]
        val card = file(context, key).takeIf { it.exists() }
            ?.let { f -> runCatching { Card.fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.takeUnless { it.empty }
        cards[key] = card
        return card
    }

    /** How many artists the base knows. */
    fun count(context: Context): Int = dir(context).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    /** The dates of the Reference from the card: the album's own when the card has it. */
    fun dates(card: Card, album: String?): Brainz.Dates {
        val found = album?.let { albumOf(card, it) }
        val studio = card.albums.filter { it.studio && it.date != null }
        return Brainz.Dates(
            began = card.began,
            ended = card.ended,
            album = found?.title ?: album?.let(Speech::cleanTitle),
            albumDate = found?.date,
            albums = studio.map { it.title to it.date!!.take(4) }.distinctBy { it.first.lowercase(Locale.ROOT) }.take(MAX_STUDIO),
        )
    }

    /** [album] among the card's records, by its title cleaned of "(Remastered)" and the like. */
    fun albumOf(card: Card, album: String): Album? {
        val wanted = norm(Speech.cleanTitle(album)).takeIf { it.isNotEmpty() } ?: return null
        return card.albums.firstOrNull { norm(it.title) == wanted && it.date != null }
            ?: card.albums.firstOrNull { norm(Speech.cleanTitle(it.title)) == wanted && it.date != null }
    }

    /**
     * The facts found for the track with the card's added: the members, the labels, Discogs' biography;
     * when nothing was found (offline, slow), the card's own. The album's year from the card when the
     * player and the sources had none.
     */
    fun enrich(context: Context, track: TrackInfo.Track, lang: String, facts: TrackInfo.Facts?): TrackInfo.Facts? {
        val card = card(context, track.artist) ?: return facts
        val extra = texts(context, card, lang)
        val album = facts?.album ?: track.album
        val year = facts?.year ?: track.year ?: album?.let { albumOf(card, it)?.date?.take(4) }
        val own = listOfNotNull(card.about.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, card.lang, TrackInfo.BASE) })
        if (facts == null || (facts.empty && facts.intro.isEmpty())) {
            return TrackInfo.Facts(
                song = facts?.song.orEmpty(),
                artist = own + extra,
                album = album,
                year = year,
                albumText = facts?.albumText.orEmpty(),
                intro = card.intro.takeIf { card.lang == lang }.orEmpty(),
                related = card.related,
                relatedIds = card.relatedIds,
                artistId = card.wikidata,
                mbid = card.mbid,
            )
        }
        return TrackInfo.Facts(
            song = facts.song,
            // The article first (the story follows it), the card's additions after.
            artist = facts.artist.ifEmpty { own } + extra,
            album = album,
            year = year,
            albumText = facts.albumText,
            intro = facts.intro.ifEmpty { card.intro.takeIf { card.lang == lang }.orEmpty() },
            circle = facts.circle,
            related = facts.related.ifEmpty { card.related },
            relatedIds = facts.relatedIds.ifEmpty { card.relatedIds },
            artistId = facts.artistId ?: card.wikidata,
            coverUrl = facts.coverUrl,
            pages = facts.pages,
            deep = facts.deep,
            mbid = facts.mbid ?: card.mbid,
        )
    }

    /** What the card adds, said in [lang]: the members and their years, the labels; Discogs' biography as it is. */
    private fun texts(context: Context, card: Card, lang: String): List<TrackInfo.Text> {
        val res = TrackInfo.localized(context, lang)
        val said = ArrayList<String>()
        if (card.band && card.members.isNotEmpty()) {
            // "1987–": still in the band, "since 1987".
            fun years(y: String) = if (y.endsWith("–")) res.getString(R.string.base_since, y.dropLast(1)) else y
            // "Kurt Cobain (вокал, гитара; 1987–1994)": what they played, when MusicBrainz says.
            val names = card.members.take(MAX_MEMBERS_SAID).joinToString(", ") { m ->
                val about = listOfNotNull(
                    m.roles.mapNotNull { Brainz.instrument(it, lang) }.distinct().take(MAX_ROLES).joinToString(", ").ifBlank { null },
                    m.years?.let { years(it) },
                ).joinToString("; ")
                m.name + if (about.isNotBlank()) " ($about)" else ""
            }
            said += res.getString(R.string.base_members, names)
        }
        if (card.labels.isNotEmpty()) said += res.getString(R.string.base_labels, card.labels.joinToString(", "))
        return listOfNotNull(
            said.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang, if (card.discogsId != null) TrackInfo.DISCOGS else TrackInfo.MUSICBRAINZ) },
            card.profile.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, "en", TrackInfo.DISCOGS) },
        )
    }

    // ---- Gathering ----

    /**
     * Who to gather: [first] (their most listened, by ListenBrainz), everyone in the history (the latest
     * first), those put aside for later, their circle.
     */
    fun wanted(context: Context, first: List<String> = emptyList()): List<String> {
        val entries = Journal.entries(context)
        val names = LinkedHashMap<String, String>()
        fun add(name: String?) {
            val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
            names.putIfAbsent(Journal.key(n), n)
        }
        first.forEach { add(it) }
        entries.forEach { add(it.name) }
        Journal.later(context).forEach { add(it.artist) }
        entries.take(CIRCLE_OF).forEach { e -> e.links.take(LINKS_EACH).forEach { add(it) } }
        return names.values.toList()
    }

    /** The card of [name], from every source; one that found nothing is kept too, and asked again later. */
    suspend fun build(context: Context, name: String, lang: String): Card {
        val token = Prefs(context).discogsToken
        val facts = runCatching { TrackInfo.aboutArtist(context, name, lang) }.getOrNull()
        // MusicBrainz turns away a burst of requests ("busy"): once more, a few seconds later.
        val mb = runCatching { Brainz.artistInfo(name, facts?.mbid) }.getOrNull()
            ?: run { kotlinx.coroutines.delay(BUSY_RETRY_MS); runCatching { Brainz.artistInfo(name, facts?.mbid) }.getOrNull() }
        val discogsId = Discogs.idOf(mb?.discogs)
            ?: facts?.artistId?.let { runCatching { discogsOfWikidata(it) }.getOrNull() }
            ?: runCatching { Discogs.search(name, token) }.getOrNull()
        val dArtist = discogsId?.let { runCatching { Discogs.artist(it, token) }.getOrNull() }
        val dRecords = discogsId?.let { runCatching { Discogs.records(it, token) }.getOrNull() }.orEmpty()

        // MusicBrainz's records first (it knows what each is and the exact day); Discogs adds what it lacks.
        val albums = ArrayList<Album>()
        // Not the bootlegs named by their date and place ("1990-05-13: Duffy's Tavern, Lincoln, NE"): they
        // would crowd out the records themselves.
        mb?.groups?.filterNot { DATED_BOOTLEG.containsMatchIn(it.title) }?.forEach { g -> albums += Album(g.title, g.date, g.kind, g.studio) }
        val known = albums.map { norm(it.title) }.toMutableSet()
        dRecords.filter { it.master && it.year != null }.forEach { r ->
            val n = norm(r.title)
            if (n.isNotEmpty() && known.add(n)) albums += Album(r.title, r.year, null, false)
        }
        // A MusicBrainz record without a date, dated by Discogs.
        val discogsYears = dRecords.filter { it.year != null }.associate { norm(it.title) to it.year!! }
        val dated = albums.map { a -> if (a.date == null) discogsYears[norm(a.title)]?.let { Album(a.title, it, a.kind, a.studio) } ?: a else a }

        val members = mb?.members.orEmpty().map { m ->
            val years = when {
                m.began != null && m.ended != null -> if (m.began == m.ended) m.began else "${m.began}–${m.ended}"
                m.began != null -> "${m.began}–"
                else -> m.ended?.let { "–$it" }
            }
            Member(m.name, years, m.roles)
        }.ifEmpty { dArtist?.members.orEmpty().map { Member(it, null, emptyList()) } }
        val labels = dRecords.mapNotNull { it.label?.substringBefore(",")?.trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(MAX_LABELS)

        val intro = facts?.intro.orEmpty()
        val about = facts?.let { f -> TrackInfo.aboutLines(f, lang).filterNot { it in intro } }.orEmpty()
        return Card(
            name = mb?.name?.takeIf { it.isNotBlank() } ?: dArtist?.name ?: name,
            at = System.currentTimeMillis(),
            lang = lang,
            mbid = mb?.id ?: facts?.mbid,
            discogsId = discogsId,
            wikidata = facts?.artistId,
            band = mb?.band ?: (dArtist?.members?.isNotEmpty() == true),
            began = mb?.began,
            ended = mb?.ended,
            members = members.take(MAX_MEMBERS),
            albums = dated.sortedBy { it.date ?: "9999" }.take(MAX_ALBUMS),
            labels = labels,
            profile = dArtist?.profile.orEmpty(),
            intro = intro,
            about = about.take(MAX_ABOUT),
            related = facts?.related.orEmpty(),
            relatedIds = facts?.relatedIds.orEmpty(),
            genres = mb?.genres.orEmpty(),
            complete = facts != null && mb != null && mb.groups.isNotEmpty() && (discogsId == null || dArtist != null),
        )
    }

    /** Discogs' artist number as Wikidata keeps it (P1953). */
    private fun discogsOfWikidata(item: String): Long? {
        val o = TrackInfo.getJson("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=P1953&entity=$item")
        val value = o?.optJSONObject("claims")?.optJSONArray("P1953")?.optJSONObject(0)
            ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
        return value?.toLongOrNull()
    }

    /** Where the gathering is: how many of how many, and whether it runs. */
    class Progress(val running: Boolean, val done: Int, val total: Int, val current: String?)

    private val _progress = MutableStateFlow(Progress(false, 0, 0, null))
    val progress: StateFlow<Progress> = _progress

    private val running = Mutex()

    /**
     * Cards for those [wanted] who have none or an old one, until [until] (a job has ten minutes) or
     * [max] of them. The number gathered.
     */
    suspend fun fill(context: Context, until: Long, max: Int = MAX_PER_RUN): Int {
        if (!running.tryLock()) return 0
        try {
            val prefs = Prefs(context)
            val lang = prefs.infoLanguage.resolve()
            val now = System.currentTimeMillis()
            val top = runCatching { ListenBrainz.topArtists(context) }.getOrDefault(emptyList())
            val due = wanted(context, top).filter { name ->
                val f = file(context, Journal.key(name))
                if (!f.exists()) return@filter true
                val card = runCatching { Card.fromJson(JSONObject(f.readText())) }.getOrNull() ?: return@filter true
                val fresh = when {
                    card.empty -> EMPTY_FRESH_MS
                    !card.complete -> DAY_MS
                    else -> FRESH_MS
                }
                now - card.at > fresh || card.lang != lang
            }.take(max)
            var done = 0
            for (name in due) {
                if (System.currentTimeMillis() > until) break
                _progress.value = Progress(true, done, due.size, name)
                keep(context, name, build(context, name, lang))
                done++
            }
            prefs.baseFilledAt = System.currentTimeMillis()
            return done
        } finally {
            _progress.value = Progress(false, 0, 0, null)
            running.unlock()
        }
    }

    private val building = HashMap<String, kotlinx.coroutines.Deferred<Card?>>()

    /**
     * [name]'s card gathered now, in the background (an artist the open sources say little about), unless
     * the base is off, there is no network in this build, or it was gathered lately (found or not).
     * Null when nothing is to be gathered.
     */
    fun buildSoon(context: Context, name: String, lang: String): kotlinx.coroutines.Deferred<Card?>? {
        val app = context.applicationContext
        if (!Prefs(app).baseOn || !app.resources.getBoolean(R.bool.network)) return null
        val key = Journal.key(name)
        val f = file(app, key)
        if (f.exists()) {
            val card = runCatching { Card.fromJson(JSONObject(f.readText())) }.getOrNull()
            // A card from before genres were kept is gathered again for them.
            val old = card != null && !card.empty && card.genres.isEmpty() && card.mbid != null
            val fresh = if (card == null || card.empty) EMPTY_FRESH_MS else DAY_MS
            if (card != null && !old && System.currentTimeMillis() - card.at < fresh) return null
        }
        return synchronized(building) {
            building[key]?.takeIf { it.isActive } ?: scope.async {
                runCatching { build(app, name, lang).also { keep(app, name, it) } }.getOrNull()
            }.also { building[key] = it }
        }
    }

    internal fun keep(context: Context, name: String, card: Card) {
        val key = Journal.key(name)
        runCatching { file(context, key).writeText(card.toJson().toString()) }
        cards.remove(key)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** "Gather now" in the settings: in the background, so leaving the screen does not stop it. */
    fun fillNow(context: Context) {
        val app = context.applicationContext
        scope.launch { fill(app, System.currentTimeMillis() + NOW_BUDGET_MS) }
    }

    /** The base gone (the history cleared, or asked for): it is gathered anew. */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        cards.clear()
    }

    /** Keeps the nightly gathering scheduled while the base is on. */
    fun sync(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!Prefs(context).baseOn) return scheduler.cancel(JOB_ID)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BaseJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresCharging(true)
            .setPeriodic(DAY_MS)
        try {
            scheduler.schedule(job.setPersisted(true).build())
        } catch (_: Exception) {
            // Not declared in this build (no network in it), or no right to outlive a restart.
            runCatching { scheduler.schedule(job.setPersisted(false).build()) }
        }
    }

    private fun dir(context: Context) = File(context.filesDir, "base").apply { mkdirs() }

    private fun file(context: Context, key: String) = File(dir(context), sha1(key) + ".json")



    private const val JOB_ID = 43
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A card is gathered again after a month; one that found nothing, after a week. */
    private const val FRESH_MS = 30 * DAY_MS
    private const val EMPTY_FRESH_MS = 7 * DAY_MS
    private const val MAX_PER_RUN = 60
    private const val NOW_BUDGET_MS = 30L * 60 * 1000
    private const val MAX_IN_MEMORY = 50

    /** The circle of the most recent artists, a few names each. */
    private const val CIRCLE_OF = 20
    private const val LINKS_EACH = 3
    private const val MAX_MEMBERS = 15
    private const val MAX_MEMBERS_SAID = 6
    private const val MAX_ROLES = 2
    private const val MAX_LABELS = 4
    private const val MAX_ALBUMS = 200
    private const val BUSY_RETRY_MS = 3_000L
    private const val MAX_STUDIO = 20
    private const val MAX_ABOUT = 30
}

/** The nightly gathering: on Wi-Fi, while charging, for the ten minutes a job is given. */
class BaseJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            runCatching { ArtistBase.fill(this@BaseJob, System.currentTimeMillis() + BUDGET_MS) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private companion object {
        /** Under the ten minutes Android gives a job. */
        const val BUDGET_MS = 8L * 60 * 1000
    }
}
