package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.util.DouyinMedia
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.isDouyinUrl
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DownloadDialogViewModel"

class DownloadDialogViewModel(private val downloader: DownloaderV2) : ViewModel() {

    sealed interface SelectionState {
        data object Idle : SelectionState

        data class PlaylistSelection(val result: PlaylistResult) : SelectionState

        data class FormatSelection(val info: VideoInfo) : SelectionState
    }

    sealed interface SheetState {
        data object InputUrl : SheetState

        data class Configure(val urlList: List<String>) : SheetState

        data class Loading(val taskKey: String, val job: Job) : SheetState

        data class Error(val action: Action, val throwable: Throwable) : SheetState

        /**
         * Douyin only gives up its media to a real browser, and that browser sometimes has to be
         * watched (a captcha can show up), so the work is handed to a page instead of the queue.
         * The nonce keeps two identical links from looking like the same request.
         */
        data class DouyinCapture(val url: String, val nonce: Int = 0) : SheetState
    }

    sealed interface SheetValue {
        data object Expanded : SheetValue

        data object Hidden : SheetValue
    }

    sealed interface Action {
        data object HideSheet : Action

        data class ShowSheet(val urlList: List<String>? = null) : Action

        data class ProceedWithURLs(val urlList: List<String>) : Action

        data object Reset : Action

        data class FetchPlaylist(
            val url: String,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class FetchFormats(
            val url: String,
            val audioOnly: Boolean,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class DownloadWithPreset(
            val urlList: List<String>,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class RunCommand(
            val url: String,
            val template: CommandTemplate,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data object Cancel : Action
    }

    private val mSelectionStateFlow: MutableStateFlow<SelectionState> =
        MutableStateFlow(SelectionState.Idle)
    private val mSheetStateFlow: MutableStateFlow<SheetState> =
        MutableStateFlow(SheetState.InputUrl)
    private val mSheetValueFlow: MutableStateFlow<SheetValue> = MutableStateFlow(SheetValue.Hidden)

    private val pendingDouyinUrls = ArrayDeque<String>()
    private var pendingDouyinPreferences: DownloadUtil.DownloadPreferences? = null
    private var douyinNonce = 0

    val selectionStateFlow = mSelectionStateFlow.asStateFlow()
    val sheetStateFlow = mSheetStateFlow.asStateFlow()
    val sheetValueFlow = mSheetValueFlow.asStateFlow()

    private val sheetState
        get() = sheetStateFlow.value

    fun postAction(action: Action) {
        with(action) {
            when (this) {
                is Action.ProceedWithURLs -> proceedWithUrls(this)
                is Action.FetchFormats -> fetchFormat(this)
                is Action.FetchPlaylist -> fetchPlaylist(this)
                is Action.DownloadWithPreset -> downloadWithPreset(urlList, preferences)
                is Action.RunCommand -> runCommand(url, template, preferences)
                Action.HideSheet -> hideDialog()
                is Action.ShowSheet -> showDialog(this)
                Action.Cancel -> cancel()
                Action.Reset -> resetSelectionState()
            }
        }
    }

    private fun proceedWithUrls(action: Action.ProceedWithURLs) {
        mSheetStateFlow.update { SheetState.Configure(action.urlList) }
    }

    private fun fetchPlaylist(action: Action.FetchPlaylist) {
        val (url, preferences) = action
        // Same key has to reach yt-dlp, otherwise cancelling only cancels the coroutine and the
        // process keeps running until it decides to stop on its own.
        val taskKey = "FetchPlaylist_$url"

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                DownloadUtil.getPlaylistOrVideoInfo(
                        playlistURL = url,
                        downloadPreferences = preferences,
                        taskKey = taskKey,
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            when (info) {
                                is PlaylistResult -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.PlaylistSelection(result = info)
                                    }
                                }

                                is VideoInfo -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.FormatSelection(info = info)
                                    }
                                }
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        if (th is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        th.printStackTrace()
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update { SheetState.Error(action = action, throwable = th) }
                        }
                    }
            }
        mSheetStateFlow.update { SheetState.Loading(taskKey = taskKey, job = job) }
    }

    private fun fetchFormat(action: Action.FetchFormats) {
        val (url, audioOnly, preferences) = action

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        preferences = preferences.copy(extractAudio = audioOnly),
                        taskKey = "FetchFormat_$url",
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            mSelectionStateFlow.update {
                                SelectionState.FormatSelection(info = info)
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        if (th is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        th.printStackTrace()
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update { SheetState.Error(action, throwable = th) }
                        }
                    }
            }

        mSheetStateFlow.update { SheetState.Loading(taskKey = "FetchFormat_$url", job = job) }
    }

    private fun downloadWithPreset(
        urlList: List<String>,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        urlList.filterNot { isDouyinUrl(it) }.forEach {
            downloader.enqueue(Task(url = it, preferences = preferences))
        }

        val douyinUrls = urlList.filter { isDouyinUrl(it) }
        if (douyinUrls.isEmpty()) {
            hideDialog()
            return
        }

        // Douyin links cannot be handed to yt-dlp: they have to be turned into direct media URLs
        // first, which needs a real browser on screen. Everything else was already queued above.
        pendingDouyinUrls.clear()
        pendingDouyinUrls.addAll(douyinUrls)
        pendingDouyinPreferences = preferences
        hideDialog()
        advanceDouyinQueue()
    }

    private fun advanceDouyinQueue() {
        val next = pendingDouyinUrls.firstOrNull()
        if (next == null) {
            pendingDouyinPreferences = null
            mSheetStateFlow.update { SheetState.InputUrl }
            return
        }
        mSheetStateFlow.update { SheetState.DouyinCapture(next, ++douyinNonce) }
    }

    /** Called by the Douyin page once it read the work; the download itself is the usual queue. */
    fun onDouyinMedia(media: List<DouyinMedia>) {
        val preferences = pendingDouyinPreferences ?: return
        media.forEach { item ->
            downloader.enqueue(
                Task(url = item.url, type = Task.TypeInfo.Douyin, preferences = preferences)
            )
        }
        pendingDouyinUrls.removeFirstOrNull()
        advanceDouyinQueue()
    }

    /** The person left the page: drop every Douyin link still waiting, the rest is already queued. */
    fun onDouyinCancelled() {
        pendingDouyinUrls.clear()
        advanceDouyinQueue()
    }

    private fun runCommand(
        url: String,
        template: CommandTemplate,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        val task =
            Task(
                url = url,
                type = Task.TypeInfo.CustomCommand(template = template),
                preferences = preferences,
            )
        downloader.enqueue(task)
    }

    private fun hideDialog() {
        mSheetValueFlow.update { SheetValue.Hidden }
        when (sheetState) {
            is SheetState.Loading -> {
                cancel()
            }

            else -> {}
        }
    }

    private fun showDialog(action: Action.ShowSheet) {
        val urlList = action.urlList
        if (!urlList.isNullOrEmpty()) {
            mSheetStateFlow.update { SheetState.Configure(urlList) }
        } else {
            mSheetStateFlow.update { SheetState.InputUrl }
        }
        mSheetValueFlow.update { SheetValue.Expanded }
    }

    private fun cancel(): Boolean {
        return when (val state = sheetState) {
            is SheetState.Loading -> {
                val res = YoutubeDL.destroyProcessById(id = state.taskKey)
                if (res) {
                    state.job.cancel()
                }
                return res
            }
            else -> false
        }
    }

    private fun resetSelectionState() {
        mSelectionStateFlow.update { SelectionState.Idle }
    }
}
