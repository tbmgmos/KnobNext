package com.knobnext

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Facts from Wikidata, the structured side of Wikipedia: no key, names in the story's language. The
 * item is the one behind the Wikipedia article already found for the song, album or artist, so a
 * namesake can't slip in. Each fact becomes a short sentence: release date, producer, studio, label,
 * awards, the albums before and after, who wrote the music and the words, when and where a band began,
 * who is in it, when and where an artist was born, genres.
 */
object Wikidata {
    /** [artistIntro]: who the artist is in a few sentences (when and where they began, genres). */
    class Found(val song: TrackInfo.Text?, val album: TrackInfo.Text?, val artist: TrackInfo.Text?, val artistIntro: List<String> = emptyList())

    /** A Wikipedia article: its language and title. */
    class Page(val lang: String, val title: String)

    /**
     * [deadline] (wall clock, ms): no request starts after it, so a slow Wikidata never holds up the
     * facts the other sources found.
     */
    fun gather(
        context: Context,
        lang: String,
        song: Page?,
        album: Page?,
        artist: Page?,
        deadline: Long,
        /** What the articles already say: a fact found there is not said twice. */
        said: String = "",
        /** The player names the album: "the song is on the album …" would only repeat it. */
        albumKnown: Boolean = false,
    ): Found {
        fun late() = System.currentTimeMillis() > deadline
        val none = Found(null, null, null)
        val ids = listOf(song, album, artist).map { page -> page?.takeIf { !late() }?.let(::itemOf) }
        if (late()) return none
        val items = ids.filterNotNull().takeIf { it.isNotEmpty() }?.let(::claims).orEmpty()
        if (items.isEmpty() || late()) return none
        // Everything the facts point to (a genre, a label, a city) needs its name: all in one request.
        val wanted = items.values.flatMap { claims -> PROPERTIES.flatMap { p -> itemValues(claims, p) } }.distinct()
        val names = labels(wanted.take(MAX_IDS), lang)
        val res = TrackInfo.localized(context, lang)
        val known = said.lowercase(Locale.ROOT)
        fun facts(id: String?, build: Facts.() -> Unit): Facts? =
            id?.let { items[it] }?.let { Facts(res, it, names, lang, known, albumKnown).apply(build) }
        fun Facts?.text() = this?.out?.takeIf { it.isNotEmpty() }?.let { TrackInfo.Text(it, lang) }
        val artistFacts = facts(ids[2]) { artist() }
        return Found(
            song = facts(ids[0]) { song() }.text(),
            album = facts(ids[1]) { album() }.text(),
            artist = artistFacts.text(),
            artistIntro = artistFacts?.let { it.out.take(it.introEnd) }.orEmpty(),
        )
    }

    /** One item's claims turned into sentences. */
    private class Facts(
        val res: Context,
        val claims: JSONObject,
        val names: Map<String, String>,
        val lang: String,
        /** The articles' text, lowercased. */
        val said: String,
        val albumKnown: Boolean,
    ) {
        val out = ArrayList<String>()

        /** The first [introEnd] sentences of an artist say who they are. */
        var introEnd = 0

        fun names(property: String, max: Int = MAX_NAMES) = itemValues(claims, property).mapNotNull { names[it] }.take(max)

        fun list(property: String, string: Int, max: Int = MAX_NAMES, quote: Boolean = false) {
            val list = names(property, max)
            // Every name already in the articles: they said it, and better.
            if (list.isEmpty() || list.all { said.contains(it.lowercase(Locale.ROOT)) }) return
            out += res.getString(string, list.joinToString(", ") { if (quote) quoted(it) else it })
        }

        fun date(property: String, string: Int) {
            val time = timeValue(claims, property) ?: return
            if (said.contains(time.core(lang))) return
            out += res.getString(string, time.words(lang))
        }

        fun quoted(name: String) = if (lang == "ru") "«$name»" else "\"$name\""

        fun song() {
            date("P577", R.string.src_wd_song_out)
            list("P86", R.string.src_wd_composer)
            list("P676", R.string.src_wd_lyrics)
            list("P162", R.string.src_wd_song_producer)
            if (!albumKnown) list("P361", R.string.src_wd_part_of, max = 1, quote = true)
            list("P166", R.string.src_wd_awards)
        }

        fun album() {
            date("P577", R.string.src_wd_album_out)
            list("P162", R.string.src_wd_album_producer)
            list("P483", R.string.src_wd_studio)
            list("P264", R.string.src_wd_album_label)
            list("P166", R.string.src_wd_awards)
            list("P155", R.string.src_wd_previous, max = 1, quote = true)
            list("P156", R.string.src_wd_next, max = 1, quote = true)
        }

        fun artist() {
            val person = itemValues(claims, "P31").contains(HUMAN)
            if (person) {
                val female = itemValues(claims, "P21").contains(FEMALE)
                date("P569", if (female) R.string.src_wd_born_f else R.string.src_wd_born_m)
                list("P19", R.string.src_artist_from, max = 1)
            } else {
                timeValue(claims, "P571")?.let { out += res.getString(R.string.src_formed, it.year.toString()) }
                list("P740", R.string.src_band_from, max = 1)
            }
            list("P136", R.string.src_genres)
            introEnd = out.size
            if (person) list("P463", R.string.src_wd_member_of) else list("P527", R.string.src_wd_members, max = MAX_MEMBERS)
            list("P264", R.string.src_wd_labels)
            list("P166", R.string.src_wd_awards)
        }
    }

    // ---- Requests ----

    /** The Wikidata item behind a Wikipedia article. */
    private fun itemOf(page: Page): String? {
        val url = "https://${page.lang}.wikipedia.org/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item" +
            "&redirects=1&format=json&titles=" + URLEncoder.encode(page.title, "UTF-8")
        val pages = TrackInfo.getJson(url)?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        return pages.keys().asSequence()
            .mapNotNull { pages.getJSONObject(it).optJSONObject("pageprops")?.optString("wikibase_item")?.takeIf { id -> id.isNotBlank() } }
            .firstOrNull()
    }

    private fun claims(ids: List<String>): Map<String, JSONObject> {
        val url = "$API?action=wbgetentities&props=claims&format=json&ids=" + URLEncoder.encode(ids.distinct().joinToString("|"), "UTF-8")
        val entities = TrackInfo.getJson(url)?.optJSONObject("entities") ?: return emptyMap()
        return entities.keys().asSequence().mapNotNull { id -> entities.getJSONObject(id).optJSONObject("claims")?.let { id to it } }.toMap()
    }

    /** Names in [lang], else in English; a name only in some other script is no use to say. */
    private fun labels(ids: List<String>, lang: String): Map<String, String> {
        val out = HashMap<String, String>()
        ids.chunked(MAX_IDS).take(1).forEach { chunk ->
            val url = "$API?action=wbgetentities&props=labels&format=json&languages=" + URLEncoder.encode("$lang|en", "UTF-8") +
                "&ids=" + URLEncoder.encode(chunk.joinToString("|"), "UTF-8")
            val entities = TrackInfo.getJson(url)?.optJSONObject("entities") ?: return@forEach
            for (id in entities.keys()) {
                val labels = entities.getJSONObject(id).optJSONObject("labels") ?: continue
                val name = (labels.optJSONObject(lang) ?: labels.optJSONObject("en"))?.optString("value")
                if (!name.isNullOrBlank()) out[id] = name
            }
        }
        return out
    }

    // ---- Claims ----

    /** The statements worth taking: preferred ones when there are any, never deprecated ones. */
    private fun statements(claims: JSONObject, property: String): List<JSONObject> {
        val arr = claims.optJSONArray(property) ?: return emptyList()
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("rank") != "deprecated" }
        return all.filter { it.optString("rank") == "preferred" }.ifEmpty { all }
    }

    private fun itemValues(claims: JSONObject, property: String): List<String> =
        statements(claims, property).mapNotNull {
            it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")?.takeIf { id -> id.startsWith("Q") }
        }

    private class Time(val year: Int, val month: Int, val day: Int) {
        /** What an article would say for this date, lowercased: "10 марта 2017", "march 10, 2017", or the year. */
        fun core(lang: String): String =
            if (month == 0 || day == 0) year.toString() else words(lang).removeSuffix(" года").removePrefix("on ").lowercase(Locale.ROOT)

        /** "15 марта 1987 года" / "on March 15, 1987"; just the year when that is all that is known. */
        fun words(lang: String): String {
            if (month == 0 || day == 0) return if (lang == "ru") "в $year году" else "in $year"
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month - 1, day) }
            return if (lang == "ru") {
                SimpleDateFormat("d MMMM yyyy 'года'", Locale("ru")).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(cal.time)
            } else {
                "on " + DateFormat.getDateInstance(DateFormat.LONG, Locale(lang)).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(cal.time)
            }
        }
    }

    private fun timeValue(claims: JSONObject, property: String): Time? {
        val value = statements(claims, property).firstNotNullOfOrNull {
            it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")
        } ?: return null
        // "+1987-03-15T00:00:00Z"; precision 11 is a day, 10 a month, 9 a year.
        val m = Regex("""^\+(\d{1,4})-(\d{2})-(\d{2})""").find(value.optString("time")) ?: return null
        val precision = value.optInt("precision")
        if (precision < 9) return null
        val (y, mo, d) = m.destructured
        return Time(y.toInt(), if (precision >= 11) mo.toInt() else 0, if (precision >= 11) d.toInt() else 0)
    }

    private const val API = "https://www.wikidata.org/w/api.php"
    private const val HUMAN = "Q5"
    private const val FEMALE = "Q6581072"
    private const val MAX_NAMES = 3
    private const val MAX_MEMBERS = 5
    private const val MAX_IDS = 50

    /** The properties whose values are items that need a name. */
    private val PROPERTIES = listOf(
        "P86", "P676", "P162", "P361", "P166", "P483", "P264", "P155", "P156",
        "P19", "P463", "P740", "P527", "P136",
    )
}
