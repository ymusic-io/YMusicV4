package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_added
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Opens the download-format picker for a video. Provided app-wide by [DownloadDialogHost] (null when the
 * shell has no download service), so any video menu — feed rows, Now Playing — can offer "Download"
 * without threading the service through every call site.
 */
interface DownloadLauncher {
    fun launch(video: YtFeedItem)

    /** The Quick download preset. */
    val quickPreset: StateFlow<QuickDownloadPreset>

    /** Download [video] now with [quickPreset] — no options dialog. */
    fun quickLaunch(video: YtFeedItem)
}

/** Null when downloads are unavailable (iOS, headless graphs) — the menus then omit the Download action. */
val LocalDownloadLauncher = staticCompositionLocalOf<DownloadLauncher?> { null }

/**
 * Hosts the download options dialog for the currently-[pending] video — the bottom sheet in a phone-sized
 * window, the two-pane dialog in a larger one, whatever the platform — and enqueues the pick into
 * `graph.downloads`. Rendered once inside the app's CompositionLocal scope so it can reach
 * [LocalSnackbar]; a no-op when nothing is pending. MP3 and video (mux) options are hidden where ffmpeg
 * is absent (iOS) — only the raw-stream M4A remains.
 */
/** The window the two-pane dialog needs; anything smaller (a phone, in either orientation) gets the
 *  scrolling bottom sheet. The height covers the dialog's header, 360dp panes, footer and margins. */
private val DIALOG_MIN_WINDOW_WIDTH = 750.dp
private val DIALOG_MIN_WINDOW_HEIGHT = 600.dp

@Composable
internal fun DownloadDialogHost(graph: AppGraph, pending: YtFeedItem?, onDismiss: () -> Unit) {
    val downloads = graph.downloads ?: return
    val video = pending ?: return
    val snackbar = LocalSnackbar.current
    val scans = LocalScanController.current

    // Gate: the saved download location must still point at a Library folder. If it's unset — or its
    // folder was removed/excluded — go straight to the OS folder picker; a pick sets the location (this
    // recomposes into the format dialog), a cancel cancels the download. Validated only here, at start
    // time, so nothing has to watch the store. (No gate when the shell can't pick a folder.)
    val location by remember(graph) { graph.settings.flow(AppSettings.downloadLocation) }.collectAsState()
    if (graph.mediaStore != null && graph.libraryFolderPicker != null) {
        val usable by produceState<Boolean?>(null, video, location) { value = hasUsableDownloadLocation(graph) }
        when (usable) {
            null -> return // validating — render nothing yet
            false -> {
                LaunchedEffect(video) { if (!pickDownloadFolder(graph, snackbar, scans)) onDismiss() }
                return
            }
            true -> Unit // fall through to the format dialog
        }
    }

    val scope = rememberCoroutineScope()
    val log = graph.logger
    val state = remember(video.id) {
        DownloadOptionsState(
            video = video,
            downloads = downloads,
            preferredCaptionLanguage = graph.quickDownload.value.captionLanguage,
        )
    }
    LaunchedEffect(state) { state.load(log) }
    val locationName = rememberDownloadLocationName(graph)
    val added = stringResource(Res.string.download_added)
    val start = {
        if (state.saveAsQuick) {
            val preset = state.quickPreset()
            scope.launch { graph.saveQuickDownload(preset) }
        }
        downloads.enqueue(state.request())
        snackbar(added)
        onDismiss()
    }
    val alreadyDownloaded = video.id in LocalAvailableOffline.current
    var confirming by remember(video.id) { mutableStateOf(false) }
    val onDownload = { if (alreadyDownloaded) confirming = true else start() }
    val onPickLocation: () -> Unit = { scope.launch { pickDownloadFolder(graph, snackbar, scans) } }
    val onRetry: () -> Unit = { scope.launch { state.load(log) } }
    // Signed out: sign in, then reopen these options for a fresh probe under the account.
    val signIn = LocalSignInResolver.current?.takeIf { !it.signedIn }
    val launcher = LocalDownloadLauncher.current
    val onSignIn: (() -> Unit)? = signIn?.let { resolver ->
        {
            onDismiss()
            resolver.signIn { launcher?.launch(video) }
        }
    }
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    if (window.width < DIALOG_MIN_WINDOW_WIDTH || window.height < DIALOG_MIN_WINDOW_HEIGHT) {
        DownloadSheet(state, locationName, onPickLocation, onRetry, onSignIn, onDownload, onDismiss)
    } else {
        DownloadDialogDesktop(state, locationName, onPickLocation, onRetry, onSignIn, onDownload, onDismiss)
    }
    if (confirming) RedownloadDialog(video.title, onConfirm = start, onDismiss = { confirming = false })
}
