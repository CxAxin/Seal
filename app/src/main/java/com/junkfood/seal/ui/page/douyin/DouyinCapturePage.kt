package com.junkfood.seal.ui.page.douyin

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.junkfood.seal.R
import com.junkfood.seal.util.DouyinMedia
import com.junkfood.seal.util.DouyinResolver

/**
 * A Douyin work can only be read by a browser that runs the site's own challenge, and that
 * challenge sometimes turns into a captcha nobody can solve on the user's behalf. So the page is
 * shown instead of hidden: it reads the work on its own, and when Douyin asks for a captcha the
 * person watching can just finish it.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DouyinCapturePage(
    url: String,
    onMedia: (List<DouyinMedia>) -> Unit,
    onDismiss: () -> Unit,
) {
    var status by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<DouyinResolver.Session?>(null) }
    var finished by remember { mutableStateOf(false) }

    fun leave() {
        session?.cancel()
        onDismiss()
    }

    BackHandler { leave() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.douyin_capture_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (!finished) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text =
                    status.ifBlank {
                        stringResource(id = R.string.douyin_capture_opening)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            AndroidView(
                modifier = Modifier.fillMaxSize().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        session =
                            DouyinResolver.attach(
                                view = this,
                                url = url,
                                onStep = { status = it },
                                onResult = { result ->
                                    result
                                        .onSuccess { media ->
                                            if (media.isEmpty()) {
                                                status =
                                                    context.getString(
                                                        R.string.douyin_capture_no_media
                                                    )
                                                return@onSuccess
                                            }
                                            finished = true
                                            status =
                                                context.getString(
                                                    R.string.douyin_capture_done,
                                                    media.size,
                                                )
                                            onMedia(media)
                                            onDismiss()
                                        }
                                        .onFailure { throwable ->
                                            status =
                                                throwable.message
                                                    ?: context.getString(
                                                        R.string.douyin_capture_failed
                                                    )
                                        }
                                },
                            )
                    }
                },
                onRelease = { view -> runCatching { view.destroy() } },
            )
        }
    }
}
