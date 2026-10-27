package com.junkfood.seal.util

import android.util.Log
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.FileOutputStream

/**
 * Installs the yt-dlp build that ships inside the APK.
 *
 * `youtubedl-android` bundles yt-dlp 2024.09.27, which predates the EJS mechanism and therefore
 * cannot use the bundled QuickJS runtime at all. Two details make it impossible to swap that copy
 * out from the app side:
 *
 * - the library reads it through its own `R.raw.ytdlp`, and Android namespaces resources by
 *   package, so an app-level `res/raw/ytdlp` is a different resource and does not override it;
 * - it only unpacks its copy when no binary exists yet, so existing installs are never refreshed.
 *
 * CI instead downloads the latest stable yt-dlp into `assets/ytdlp` plus `assets/ytdlp.version`,
 * and this copies it over the library's binary whenever the installed one is older. The app can
 * then solve YouTube challenges on first launch instead of depending on a network update, and
 * the recorded version lets [JsRuntime] pass `--js-runtimes` right away.
 */
object BundledYtDlp {

    private const val TAG = "BundledYtDlp"

    private const val ASSET = "ytdlp"

    private const val VERSION_ASSET = "ytdlp.version"

    /** Turns "2025.11.12" (or a nightly "2026.08.30.232658") into a comparable number. */
    private fun sortableVersion(raw: String): Long {
        if (raw.isBlank()) return 0L
        return raw.filter(Char::isDigit).take(8).toLongOrNull() ?: 0L
    }

    fun applyIfNewer() {
        val bundled =
            runCatching {
                    context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText() }.trim()
                }
                .getOrNull()
        if (bundled.isNullOrBlank()) {
            Log.d(TAG, "No yt-dlp bundled into this build")
            return
        }

        val installed = YT_DLP_VERSION.getString()
        if (sortableVersion(installed) >= sortableVersion(bundled)) {
            Log.d(TAG, "Installed yt-dlp $installed already covers bundled $bundled")
            return
        }

        val target =
            File(context.noBackupFilesDir, YoutubeDL.baseName)
                .resolve(YoutubeDL.ytdlpDirName)
                .resolve(YoutubeDL.ytdlpBin)

        runCatching {
                target.parentFile?.mkdirs()
                context.assets.open(ASSET).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                target.setExecutable(true)
            }
            .onFailure {
                Log.w(TAG, "Failed to install bundled yt-dlp $bundled", it)
                return
            }

        YT_DLP_VERSION.updateString(bundled)
        Log.i(TAG, "Installed bundled yt-dlp $bundled (was $installed) at ${target.absolutePath}")
    }
}
