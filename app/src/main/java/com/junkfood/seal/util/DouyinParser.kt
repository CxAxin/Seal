package com.junkfood.seal.util

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Douyin is the one site yt-dlp cannot handle: its web detail endpoint expects an `__ac_signature`
 * that is only produced by the page's own JavaScript challenge, so yt-dlp always answers
 * "Fresh cookies ... are needed". The only way through is a real browser, which is what
 * [com.junkfood.seal.util.DouyinResolver] does. This file holds the parts that need no Android
 * runtime: telling Douyin links apart, and turning the detail JSON into playable media.
 */

private val DOUYIN_HOST_SUFFIXES = listOf("douyin.com", "iesdouyin.com")

fun isDouyinHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val normalized = host.lowercase(Locale.US)
    return DOUYIN_HOST_SUFFIXES.any { normalized == it || normalized.endsWith(".$it") }
}

/** Short links such as v.douyin.com, and the ordinary work pages. */
fun isDouyinUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    return isDouyinHost(host)
}

fun isWebDetailEndpoint(uri: Uri?): Boolean {
    if (uri == null) return false
    if (!"https".equals(uri.scheme, ignoreCase = true)) return false
    if (!isDouyinHost(uri.host)) return false
    return uri.path == "/aweme/v1/web/aweme/detail/"
}

private val PATH_AWEME_PATTERN = Regex("/(?:video|note|slides)/(\\d{8,})", RegexOption.IGNORE_CASE)
private val JSON_AWEME_PATTERN = Regex("[\"']aweme_id[\"']\\s*[:=]\\s*[\"']?(\\d{8,})")

fun extractAwemeId(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    runCatching {
        val uri = Uri.parse(value)
        uri.getQueryParameter("aweme_id")?.takeIf { it.all(Char::isDigit) && it.length >= 8 }?.let {
            return it
        }
        PATH_AWEME_PATTERN.find(uri.path.orEmpty())?.groupValues?.get(1)?.let { return it }
    }
    return JSON_AWEME_PATTERN.find(value)?.groupValues?.get(1)
}

/**
 * Hosts that serve Douyin media. Their links are signed, but some of them still refuse a request
 * that arrives without a Douyin referer.
 */
private val DOUYIN_MEDIA_HOST_SUFFIXES =
    listOf("douyinvod.com", "amemv.com", "snssdk.com", "iesdouyin.com", "douyin.com")

fun isDouyinMediaHost(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase(Locale.US) ?: return false
    return DOUYIN_MEDIA_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

/** Log the path only: the signed query is a credential, and so are the cookies. */
fun pathOnly(url: String): String =
    runCatching { Uri.parse(url).encodedPath }.getOrNull().takeUnless { it.isNullOrEmpty() } ?: "/"

enum class DouyinMediaKind {
    VIDEO,
    IMAGE,
    LIVE_MOTION,
}

data class DouyinMedia(
    val url: String,
    val title: String,
    val author: String,
    val kind: DouyinMediaKind,
    val index: Int = 0,
    val extension: String = "mp4",
)

// Ordered by quality, and both spellings are accepted because the web page and the detail API
// disagree on whether they use snake_case.
private val PLAY_ADDR_KEYS =
    listOf(
        "play_addr_h264",
        "playAddrH264",
        "play_addr_265",
        "playAddrH265",
        "play_addr_256",
        "playAddr256",
        "play_addr",
        "playAddr",
    )

// A URL carrying any of these renders the Douyin watermark into the pixels.
private val WATERMARK_HINTS =
    listOf("tplv-dy-water", "dy-water", "owner_watermark", "watermark_image", "watermark=1", "playwm")

private fun isWatermarked(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    return WATERMARK_HINTS.any { lower.contains(it) }
}

private fun JSONObject.optFirstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> opt(key)?.takeIf { it !== JSONObject.NULL }?.toString() }

private fun urlList(source: Any?): List<String> {
    val urls = mutableListOf<String>()
    when (source) {
        is JSONArray ->
            for (i in 0 until source.length()) {
                val item = source.opt(i)
                if (item is String) urls.add(item)
                else if (item is JSONObject)
                    item.optFirstString("url", "src", "uri")?.let { urls.add(it) }
            }
        is JSONObject ->
            source.optJSONArray("url_list")?.let { urls.addAll(urlList(it)) }
                ?: source.optFirstString("url", "uri")?.let { urls.add(it) }
        is String -> urls.add(source)
    }
    return urls
}

/** Best non-watermarked candidate, falling back to the best watermarked one if that is all there is. */
private fun pickUrl(candidates: List<String>): String? {
    val clean = candidates.filter { it.startsWith("http") && !isWatermarked(it) }
    if (clean.isNotEmpty()) return clean.first()
    return candidates.firstOrNull { it.startsWith("http") }
}

private fun imageExtension(url: String): String {
    val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty().lowercase(Locale.US)
    return when {
        path.endsWith(".png") -> "png"
        path.endsWith(".webp") -> "webp"
        path.endsWith(".gif") -> "gif"
        else -> "jpeg"
    }
}

/**
 * Reads the media out of a detail response. Only known media fields are considered, so covers and
 * avatars never end up being downloaded.
 */
fun parseDouyinDetail(json: String, expectedAwemeId: String? = null): List<DouyinMedia> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val detail =
        root.optJSONObject("aweme_detail") ?: root.optJSONArray("aweme_list")?.optJSONObject(0)
            ?: root
    val actualId = detail.optFirstString("aweme_id", "awemeId")
    if (!expectedAwemeId.isNullOrEmpty() && !actualId.isNullOrEmpty() && actualId != expectedAwemeId) {
        return emptyList()
    }

    val title = detail.optFirstString("desc", "item_title", "title").orEmpty().ifBlank { "douyin" }
    val author = detail.optJSONObject("author")?.optFirstString("nickname", "name").orEmpty()

    val video = detail.optJSONObject("video")
    val images = detail.optJSONArray("images")
    val result = mutableListOf<DouyinMedia>()

    val videoUrl =
        video?.let { v ->
            val direct = mutableListOf<String>()
            PLAY_ADDR_KEYS.forEach { key -> v.opt(key)?.let { direct.addAll(urlList(it)) } }
            // The newer field is watermark free by name, so it wins outright.
            v.optJSONArray("watermark_free_download_url_list")?.let {
                direct.addAll(0, urlList(it))
            }
            pickUrl(direct)
        }

    if (images != null && images.length() > 0) {
        for (i in 0 until images.length()) {
            val item = images.optJSONObject(i) ?: continue
            val stills = mutableListOf<String>()
            item.optJSONArray("watermark_free_download_url_list")?.let { stills.addAll(urlList(it)) }
            item.optJSONArray("url_list")?.let { stills.addAll(urlList(it)) }
            val still = pickUrl(stills) ?: continue
            val ext = imageExtension(still)
            // A live photo ships a still plus a short motion clip; both are worth keeping.
            val motion =
                item.optJSONObject("video")?.let { motionVideo ->
                    val urls = mutableListOf<String>()
                    PLAY_ADDR_KEYS.forEach { key ->
                        motionVideo.opt(key)?.let { urls.addAll(urlList(it)) }
                    }
                    pickUrl(urls)
                }
            result.add(
                DouyinMedia(
                    url = still,
                    title = title,
                    author = author,
                    kind = if (motion != null) DouyinMediaKind.LIVE_MOTION else DouyinMediaKind.IMAGE,
                    index = i,
                    extension = ext,
                )
            )
            if (motion != null) {
                result.add(
                    DouyinMedia(
                        url = motion,
                        title = title,
                        author = author,
                        kind = DouyinMediaKind.LIVE_MOTION,
                        index = i,
                        extension = "mp4",
                    )
                )
            }
        }
        if (result.isNotEmpty()) return result
    }

    if (videoUrl != null) {
        result.add(
            DouyinMedia(
                url = videoUrl,
                title = title,
                author = author,
                kind = DouyinMediaKind.VIDEO,
            )
        )
    }
    return result
}
