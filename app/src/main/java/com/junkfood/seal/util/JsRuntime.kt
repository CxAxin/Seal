package com.junkfood.seal.util

import android.util.Log
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.util.PreferenceUtil.getString
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Supplies a bundled JavaScript runtime (QuickJS) to yt-dlp.
 *
 * Since yt-dlp 2025.11.12 an external JS runtime is required to solve YouTube's JavaScript
 * challenges ("EJS"). Without one, YouTube extraction degrades: formats go missing, the `n`
 * parameter cannot be solved, and downloads fail with HTTP 403 / "Sign in to confirm you're not a
 * bot".
 *
 * The QuickJS binary is shipped as a native library (`libqjs.so` under `jniLibs/<abi>/`).
 * `youtubedl-android` already puts the app's `nativeLibraryDir` on the PATH, but the file is not
 * named `qjs`, so its path must be passed explicitly via `--js-runtimes`.
 *
 * See https://github.com/yt-dlp/yt-dlp/wiki/EJS
 */
object JsRuntime {

    private const val TAG = "JsRuntime"

    private const val QJS_LIB_NAME = "libqjs.so"

    /** First yt-dlp release that understands `--js-runtimes` (2025.11.12). */
    private const val MIN_EJS_VERSION = 20251112L

    private val qjsBinary: File? by lazy { locateQjs() }

    /** True when a QuickJS binary for the current ABI is bundled in this build. */
    val isAvailable: Boolean
        get() = qjsBinary != null

    private fun locateQjs(): File? =
        context.applicationInfo.nativeLibraryDir?.let { dir ->
            File(dir, QJS_LIB_NAME).takeIf { it.exists() }?.also { file ->
                if (!file.canExecute()) {
                    runCatching { file.setExecutable(true) }
                }
                Log.d(TAG, "QuickJS at ${file.absolutePath} (executable=${file.canExecute()})")
            }
        }

    private fun supportsJsRuntimes(): Boolean {
        // yt-dlp nightly reports e.g. 2026.08.30.232658, stable reports 2025.11.12
        val version = YT_DLP_VERSION.getString()
        if (version.isBlank()) {
            // The build ships yt-dlp 2024.09.27, which rejects `--js-runtimes` outright.
            // A blank preference means the in-app updater has not reported a version yet,
            // so assume the old bundled build and stay out of the way: injecting here
            // would break *every* download, not just YouTube, with an "unrecognized
            // argument" error.
            Log.w(TAG, "yt-dlp version unknown; skipping --js-runtimes")
            return false
        }
        val value = version.filter(Char::isDigit).take(8).toLongOrNull() ?: return false
        return value >= MIN_EJS_VERSION
    }

    fun YoutubeDLRequest.enableJsRuntime(): YoutubeDLRequest {
        val qjs = qjsBinary
        if (qjs == null) {
            Log.w(TAG, "No QuickJS bundled for this ABI; skipping --js-runtimes")
            return this
        }
        if (!supportsJsRuntimes()) {
            Log.w(TAG, "yt-dlp predates --js-runtimes; skipping")
            return this
        }
        Log.d(TAG, "Using JS runtime quickjs:${qjs.absolutePath}")
        return addOption("--js-runtimes", "quickjs:${qjs.absolutePath}")
    }
}
