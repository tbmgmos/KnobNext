package com.knobnext

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

// Small helpers several sources share: one copy each.

/** A file name or key from any text. */
internal fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Text for a URL's query. */
internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** A name reduced to its letters and digits, lowercase, "ё" as "е": "Blink-182" and "blink 182" are one. */
internal fun norm(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")

/** The source found this artist (or title), not one with a different name: one within the other. */
internal fun sameName(found: String, wanted: String): Boolean {
    val a = norm(found)
    val b = norm(wanted)
    return a.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
}

/** A live bootleg named by its date and place: "1991-05-29: Jabberjaw, Los Angeles". */
internal val DATED_BOOTLEG = Regex("^\\d{4}[-‐]\\d\\d[-‐]\\d\\d:")
