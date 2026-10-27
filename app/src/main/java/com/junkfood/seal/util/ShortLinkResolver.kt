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
 * v.douyin.com, b23.tv, ...). Two things go wrong when such a link is passed to
 * yt-dlp directly:
 *
 * 1. Some services answer a short link with a login redirect when the request
 *    does not look like a browser. yt-dlp then ends up on
 *    `.../login?redirectPath=...`, which matches no extractor, and the download
 *    fails with "Unsupported URL" even though the site is supported.
 * 2. Even without the redirect, the expanded URL carries the parameters the
 *    extractor needs (xsec_token on Xiaohongshu, for instance).
 *
 * Following the redirect ourselves with a desktop user agent avoids both, and
 * [unwrapLoginRedirect] recovers the real target when a login page is served
 * anyway.
 *
 * Only known short-link hosts are touched, so ordinary URLs are never delayed
 * by an extra request.
 */
object ShortLinkResolver {

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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
            .followRedirects(true)
            .followSslRedirects(true)
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
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return url
        if (!host.isShortLinkHost()) return url
        return runCatching { followRedirects(url) }.getOrDefault(url)
    }

    private fun String.isShortLinkHost(): Boolean =
        SHORT_LINK_HOSTS.any { this == it || this.endsWith(".$it") }

    private fun followRedirects(url: String): String {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                .build()
        client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            return unwrapLoginRedirect(finalUrl) ?: finalUrl
        }
    }

    /**
     * Xiaohongshu redirects unauthenticated requests to
     * `/login?redirectPath=<real url>`. The target is still reachable if we pull
     * it back out of the query.
     */
    private fun unwrapLoginRedirect(url: String): String? {
        if (!url.contains("/login")) return null
        val uri = Uri.parse(url)
        val target = uri.getQueryParameter("redirectPath") ?: uri.getQueryParameter("redirect")
        if (target.isNullOrBlank() || !target.startsWith("http")) return null
        return target
    }
}
