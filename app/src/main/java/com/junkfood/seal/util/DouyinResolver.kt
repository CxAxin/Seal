package com.junkfood.seal.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves a Douyin work into directly downloadable media URLs.
 *
 * yt-dlp cannot do this: the web detail endpoint needs an `__ac_signature` that only the page's own
 * JavaScript challenge produces. A real WebView does run that challenge, so the page is loaded for
 * real and the resulting signed request is captured. `shouldInterceptRequest` never exposes a
 * response body, so the same signed URL is replayed natively with the captured headers and cookies.
 *
 * Nothing lands on disk here. The signed URLs and cookies only ever live in memory, and log lines
 * carry the request path but never the query.
 */
object DouyinResolver {

    private const val TAG = "DouyinResolver"
    private const val OVERALL_TIMEOUT_MS = 40_000L
    private const val INLINE_FALLBACK_DELAY_MS = 3_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val INLINE_DATA_SCRIPT =
        """
        (function(){
          try{
            var renderData = document.getElementById('RENDER_DATA');
            if (renderData && renderData.textContent) return decodeURIComponent(renderData.textContent);
            if (window._ROUTER_DATA) return JSON.stringify(window._ROUTER_DATA);
            if (window.__INITIAL_STATE__) return JSON.stringify(window.__INITIAL_STATE__);
          }catch(e){}
          return '';
        })()
        """.trimIndent()

    /**
     * @param onResult always invoked exactly once, on the main thread
     */
    fun resolve(context: Context, url: String, onResult: (Result<List<DouyinMedia>>) -> Unit) {
        val awemeId = extractAwemeId(url)
        var settled = false
        var webView: WebView? = null

        fun settle(result: Result<List<DouyinMedia>>) {
            if (settled) return
            settled = true
            mainHandler.post {
                runCatching { webView?.stopLoading() }
                runCatching { webView?.destroy() }
                webView = null
                onResult(result)
            }
        }

        mainHandler.post {
            val view =
                WebView(context.applicationContext).apply {
                    settings.run {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        // Same trick as the cookie login page: drop only the embedded browser
                        // markers so the UA still matches what the browser reports about itself.
                        userAgentString =
                            userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                    }
                }
            webView = view
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, true) }

            view.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val uri = request.url
                        if (request.method.equals("GET", ignoreCase = true) && isWebDetailEndpoint(uri)) {
                            val detailUrl = uri.toString()
                            val headers = request.requestHeaders.toMap()
                            val cookie =
                                runCatching { CookieManager.getInstance().getCookie(detailUrl) }
                                    .getOrNull()
                            executor.execute {
                                replayDetailRequest(detailUrl, headers, cookie, awemeId, ::settle)
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // The challenge runs after load, so give the request a moment before
                        // falling back to whatever the page left inlined.
                        mainHandler.postDelayed(
                            {
                                if (settled) return@postDelayed
                                view.evaluateJavascript(INLINE_DATA_SCRIPT) { value ->
                                    val json =
                                        value
                                            .trim()
                                            .trim('"')
                                            .replace("\\\"", "\"")
                                            .replace("\\n", "")
                                    if (json.length < 2) return@evaluateJavascript
                                    val media = parseDouyinDetail(json, awemeId)
                                    if (media.isNotEmpty()) settle(Result.success(media))
                                }
                            },
                            INLINE_FALLBACK_DELAY_MS,
                        )
                    }
                }

            view.loadUrl(url)

            mainHandler.postDelayed(
                {
                    settle(
                        Result.failure(
                            IllegalStateException(
                                "Timed out waiting for the Douyin page. Open the work in a browser " +
                                    "once and try again."
                            )
                        )
                    )
                },
                OVERALL_TIMEOUT_MS,
            )
        }
    }

    private fun replayDetailRequest(
        url: String,
        headers: Map<String, String>,
        cookie: String?,
        awemeId: String?,
        settle: (Result<List<DouyinMedia>>) -> Unit,
    ) {
        runCatching {
                val builder = Request.Builder().url(url).get()
                headers.forEach { (key, value) ->
                    // Let OkHttp negotiate encoding instead of inheriting one it cannot honour.
                    if (!key.equals("Accept-Encoding", ignoreCase = true)) builder.header(key, value)
                }
                if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
                httpClient.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful || body.isBlank()) {
                        settle(Result.failure(IllegalStateException("Douyin refused the request")))
                        return
                    }
                    val media = parseDouyinDetail(body, awemeId)
                    settle(
                        if (media.isEmpty())
                            Result.failure(IllegalStateException("No media in the Douyin response"))
                        else Result.success(media)
                    )
                }
            }
            .onFailure { settle(Result.failure(it)) }
    }
}
