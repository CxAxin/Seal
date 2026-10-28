package com.junkfood.seal.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers for cookies coming from outside the app (browser extensions, text shared by the user).
 *
 * yt-dlp only understands the Netscape cookie file layout, while most "get cookies.txt" browser
 * extensions can also export raw JSON. We normalize everything to Netscape before it is stored, so
 * an import never silently produces a file yt-dlp refuses to read.
 */
private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"

private fun JSONObject.optStringOrNull(key: String): String? =
    opt(key)?.takeIf { it !== JSONObject.NULL }?.toString()

/**
 * Converts a browser extension JSON dump into a Netscape cookie file.
 * Returns null when [raw] does not look like a cookie array at all.
 */
private fun jsonToNetscape(raw: String): String? {
    val array =
        runCatching { JSONArray(raw) }
            .getOrNull()
            ?.takeIf { it.length() > 0 }
            ?: return null

    val lines = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val name = entry.optStringOrNull("name")?.trim().orEmpty()
        if (name.isEmpty()) continue
        val value = entry.optStringOrNull("value").orEmpty()
        var domain = entry.optStringOrNull("domain")?.trim().orEmpty()
        if (domain.isEmpty()) continue
        // Netscape files mark domain-wide cookies with a leading dot.
        val hostOnly = entry.optBoolean("hostOnly", false)
        if (!hostOnly && !domain.startsWith(".")) domain = ".$domain"

        val path = entry.optStringOrNull("path")?.takeIf { it.isNotBlank() } ?: "/"
        val secure = if (entry.optBoolean("secure", false)) "TRUE" else "FALSE"
        val expiry =
            entry.opt("expirationDate").let {
                (it as? Number)?.toLong() ?: it?.toString()?.toDoubleOrNull()?.toLong()
            } ?: 0L
        lines.add(listOf(domain, "TRUE", path, secure, expiry.toString(), name, value).joinToString("\t"))
    }
    if (lines.isEmpty()) return null

    return buildString {
        appendLine(NETSCAPE_HEADER)
        lines.forEach { appendLine(it) }
    }
}

/**
 * Returns cookie file content yt-dlp can parse: Netscape files are passed through untouched, JSON
 * dumps from browser extensions are converted, anything else is returned unchanged so the user still
 * gets a clear error message from yt-dlp instead of an empty file.
 */
fun normalizeCookieFileContent(raw: String): String {
    val trimmed = raw.trimStart('\uFEFF').trim()
    if (trimmed.isEmpty()) return raw
    if (trimmed.startsWith("#") || trimmed.startsWith("[") || trimmed.startsWith("{")) {
        if (trimmed.startsWith("#")) return trimmed
        if (trimmed.startsWith("[")) jsonToNetscape(trimmed)?.let { return it }
        // Some extensions wrap the array in an object.
        val inner =
            runCatching { JSONObject(trimmed) }
                .getOrNull()
                ?.let { obj ->
                    obj.optJSONArray("cookies")
                        ?: obj.optJSONArray("data")
                        ?: obj.keys()
                            .asSequence()
                            .mapNotNull { obj.optJSONArray(it) }
                            .firstOrNull()
                }
        if (inner != null) jsonToNetscape(inner.toString())?.let { return it }
    }
    return raw
}
