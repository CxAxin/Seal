package com.junkfood.seal.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
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
    private const val OVERALL_TIMEOUT_MS = 45_000L
    private const val VERIFICATION_GRACE_MS = 300_000L
    private const val INLINE_FALLBACK_DELAY_MS = 3_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val CHROME_VERSION_PATTERN = Regex("Chrome/([0-9.]+)", RegexOption.IGNORE_CASE)

    /**
     * Douyin only asks for `/aweme/v1/web/aweme/detail/` on the desktop site, so a phone user
     * agent loads a page that never requests it and there is nothing to capture. The Chrome
     * version is carried over from the real one so the string cannot contradict itself.
     */
    fun desktopUserAgent(defaultUserAgent: String?): String {
        val version =
            defaultUserAgent?.let { CHROME_VERSION_PATTERN.find(it)?.groupValues?.getOrNull(1) }
                ?: "140.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$version Safari/537.36"
    }

    /** A visible page can be asked to solve a captcha; a hidden one cannot, so it is told apart. */
    interface Session {
        fun cancel()
    }

    /** Headless pass. Only useful while Douyin does not challenge the session. */
    fun resolve(
        context: Context,
        url: String,
        onResult: (Result<List<DouyinMedia>>) -> Unit,
    ): Session {
        val controller = Controller(url, onStep = {}, onResult = onResult)
        mainHandler.post {
            val view = WebView(context.applicationContext)
            configure(view)
            controller.attach(view, ownsView = true)
        }
        return controller
    }

    /**
     * Drives a WebView the caller already shows on screen, which is the only way through when
     * Douyin answers with a captcha: the challenge is solved by the person watching it.
     */
    fun attach(
        view: WebView,
        url: String,
        onStep: (String) -> Unit,
        onResult: (Result<List<DouyinMedia>>) -> Unit,
    ): Session {
        val controller = Controller(url, onStep = onStep, onResult = onResult)
        configure(view)
        controller.attach(view, ownsView = false)
        return controller
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.run {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            // A desktop page in a phone sized view: let it lay out wide and fit the screen.
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = desktopUserAgent(userAgentString)
        }
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, true) }
    }

    private val MUTE_MEDIA_SCRIPT =
        """
        (function(){
          function mute(items){for(var i=0;i<items.length;i++){try{items[i].muted=true;items[i].volume=0;}catch(e){}}}
          mute(document.querySelectorAll('audio,video'));
          if(!window.__sealMuteInstalled){
            window.__sealMuteInstalled=true;
            new MutationObserver(function(){mute(document.querySelectorAll('audio,video'));})
              .observe(document.documentElement,{childList:true,subtree:true});
          }
          return true;
        })()
        """.trimIndent()

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

    private fun isVerificationUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("captcha") ||
            lower.contains("verify") ||
            lower.contains("/login") ||
            lower.contains("passport")
    }

    private class Controller(
        private val startUrl: String,
        private val onStep: (String) -> Unit,
        private val onResult: (Result<List<DouyinMedia>>) -> Unit,
    ) : Session {
        @Volatile private var settled = false
        private var ownsView = false
        private var view: WebView? = null
        private var awemeId: String? = extractAwemeId(startUrl)
        private var startedAt = 0L

        @Volatile private var verificationSeen = false

        private val timeout = Runnable { onTimeout() }

        fun attach(webView: WebView, ownsView: Boolean) {
            this.view = webView
            this.ownsView = ownsView
            this.startedAt = SystemClock.elapsedRealtime()
            webView.webViewClient = client
            mainHandler.postDelayed(timeout, OVERALL_TIMEOUT_MS)
            webView.loadUrl(startUrl)
        }

        private fun postStep(text: String) {
            mainHandler.post { onStep(text) }
        }

        private fun settle(result: Result<List<DouyinMedia>>) {
            if (settled) return
            settled = true
            mainHandler.post {
                mainHandler.removeCallbacks(timeout)
                runCatching { view?.stopLoading() }
                // Keep the cookies for the next work: they are what makes the challenge pass.
                if (result.isSuccess) runCatching { CookieManager.getInstance().flush() }
                if (ownsView) runCatching { view?.destroy() }
                onResult(result)
            }
        }

        override fun cancel() {
            if (settled) return
            settled = true
            mainHandler.post {
                mainHandler.removeCallbacks(timeout)
                runCatching { view?.stopLoading() }
                if (ownsView) runCatching { view?.destroy() }
            }
        }

        private fun onTimeout() {
            if (settled) return
            val waited = SystemClock.elapsedRealtime() - startedAt
            if (verificationSeen && waited < VERIFICATION_GRACE_MS) {
                postStep("Still waiting for Douyin to hand over the media.")
                mainHandler.postDelayed(timeout, 60_000L)
                return
            }
            settle(
                Result.failure(
                    IllegalStateException(
                        if (verificationSeen) "Douyin keeps asking for a captcha or a login"
                        else "Could not read this Douyin work"
                    )
                )
            )
        }

        private val client =
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
                        executor.execute { replay(detailUrl, headers, cookie) }
                    }
                    return null
                }

                // Every hop has to stay inside this WebView, otherwise the short link would leave.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    extractAwemeId(url)?.let { awemeId = it }
                    if (isVerificationUrl(url)) {
                        verificationSeen = true
                        postStep(
                            "Douyin wants a captcha or a login. Finish it on this page and the " +
                                "rest happens on its own."
                        )
                    }
                    runCatching { view.evaluateJavascript(MUTE_MEDIA_SCRIPT, null) }
                    // The challenge fires after load, so give the request a moment before
                    // falling back to whatever the page left inlined.
                    mainHandler.postDelayed({ readInline(view) }, INLINE_FALLBACK_DELAY_MS)
                }
            }

        private fun readInline(view: WebView) {
            if (settled) return
            view.evaluateJavascript(INLINE_DATA_SCRIPT) { value ->
                if (settled) return@evaluateJavascript
                val json = unwrapJsString(value)
                if (json.length < 2) return@evaluateJavascript
                val media = parseDouyinDetail(json, awemeId)
                if (media.isNotEmpty()) settle(Result.success(media))
            }
        }

        private fun replay(url: String, headers: Map<String, String>, cookie: String?) {
            if (settled) return
            runCatching {
                    val builder = Request.Builder().url(url).get()
                    headers.forEach { (key, value) ->
                        // Let OkHttp negotiate encoding instead of inheriting one it cannot honour.
                        if (!key.equals("Accept-Encoding", ignoreCase = true))
                            builder.header(key, value)
                    }
                    if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
                    httpClient.newCall(builder.build()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful || body.isBlank()) {
                            postStep("Douyin refused the request (HTTP ${response.code}).")
                            return
                        }
                        val status =
                            runCatching { JSONObject(body).optInt("status_code", -1) }
                                .getOrDefault(-1)
                        val media = parseDouyinDetail(body, awemeId)
                        // A mismatched or filtered response is not a dead end: another request may
                        // still carry this work, so only the timeout ends the session.
                        if (media.isEmpty()) {
                            postStep(
                                if (status > 0) "Douyin answered with status $status."
                                else "That response carried no playable media."
                            )
                            return
                        }
                        settle(Result.success(media))
                    }
                }
                .onFailure { postStep("Could not reach Douyin: ${it.message}") }
        }
    }
}

/** `evaluateJavascript` hands back a JSON string literal; unwrap it without evaluating anything. */
private fun unwrapJsString(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val trimmed = value.trim()
    if (!trimmed.startsWith("\"")) return trimmed
    return runCatching { JSONObject("{\"v\":$trimmed}").getString("v") }.getOrDefault("")
}
