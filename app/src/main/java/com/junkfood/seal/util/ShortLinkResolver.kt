package com.junkfood.seal.util

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Expands short links into the URL they actually point at, before the URL is
 * handed to yt-dlp.
 *
 * Sharing from a Chinese app usually produces a short link (xhslink.cn,
 * v.douyin.com, b23.tv, ...). Xiaohongshu's behave like this:
 *
 *   1. 302 -> https://www.xiaohongshu.com/discovery/item/<id>?...  (the real note)
 *   2. 302 -> https://www.xiaohongshu.com/login?redirectPath=<the real note>
 *   3. 200    login page
 *
 * yt-dlp does have a Xiaohongshu extractor, but its pattern only matches
 * /explore/<id> or /discovery/item/<id>, so once the chain ends on /login the
 * URL matches nothing and the download fails with "Unsupported URL" - even
 * though the site is supported.
 *
 * Following every redirect lands on the login page, so [followRedirects] walks
 * the chain itself and stops at the first hop that leaves the short-link host:
 * that is the real note. [unwrapLoginRedirect] stays as a second line of
 * defence, and [preferHttps] upgrades the http:// URL Xiaohongshu encodes into
 * `redirectPath`.
 *
 * Only known short-link hosts are touched, so ordinary URLs are never delayed
 * by an extra request, and any failure falls back to the original URL.
 */
object ShortLinkResolver {

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val MAX_HOPS = 5

    private val SHORT_LINK_HOSTS =
        setOf(
            "xhslink.cn",
            "xhslink.com",
            "v.douyin.com",
            "iesdouyin.com",
            "b23.tv",
            "t.cn",
            "dwz.cn",
            "url.cn",
            "sourl.cn",
            "v.kuaishou.com",
        )

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns [url] unchanged unless it points at a known short-link service.
     * Any failure falls back to [url], so this can never make a working link
     * stop working.
     */
    fun resolve(url: String): String {
        if (!hostOf(url).isShortLinkHost()) return url
        return runCatching { preferHttps(followRedirects(url)) }.getOrDefault(url)
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()

    private fun String.isShortLinkHost(): Boolean =
        SHORT_LINK_HOSTS.any { this == it || this.endsWith(".$it") }

    /**
     * Walks the redirect chain one hop at a time and returns the first target
     * that is no longer a short link, which is the URL the link was sharing.
     */
    private fun followRedirects(url: String): String {
        var current = url
        repeat(MAX_HOPS) {
            val request =
                Request.Builder()
                    .url(current)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                    .build()
            client.newCall(request).execute().use { response ->
                val location = response.header("Location")
                val here = response.request.url.toString()
                if (location == null) return unwrapLoginRedirect(here) ?: here
                val next = resolveRelative(current, location)
                if (!hostOf(next).isShortLinkHost()) {
                    return unwrapLoginRedirect(next) ?: next
                }
                current = next
            }
        }
        return current
    }

    /**
     * Xiaohongshu encodes the target into `/login?redirectPath=<real url>`; pull
     * it back out rather than giving up on the login page.
     */
    private fun unwrapLoginRedirect(url: String): String? {
        if (!url.contains("/login")) return null
        val uri = Uri.parse(url)
        val target = uri.getQueryParameter("redirectPath") ?: uri.getQueryParameter("redirect")
        if (target.isNullOrBlank() || !target.startsWith("http")) return null
        return target
    }

    private fun resolveRelative(base: String, location: String): String =
        runCatching { java.net.URI.create(base).resolve(location).toString() }
            .getOrDefault(location)

    private fun preferHttps(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
}
