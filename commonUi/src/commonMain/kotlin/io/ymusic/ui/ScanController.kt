package io.ymusic.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.library_scan_failed
import io.ymusic.ui.resources.library_scan_ffmpeg_missing
import io.ymusic.ui.resources.library_scan_no_folders
import io.ymusic.ui.resources.library_scan_summary
import io.ymusic.ui.resources.library_scan_summary_failed
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import projectcommon.error.errorCode

/**
 * App-level owner of a library scan/rescan, so it outlives the Library screen: a scan started from
 * the Folders tab keeps running (and its result still reaches the user) after navigating to Discover.
 * Owned by [AppGraph.scans] on the process scope; [AppRoot] pipes its [summaries] to the app snackbar, so the completion
 * message shows on whatever screen is open — a notification, not a Library-only banner.
 */
internal class ScanController(private val scope: CoroutineScope, private val log: Logger? = null) {
    /** True while a scan runs; [scanned] is its running file count (null before the first tick). */
    var running by mutableStateOf(false)
        private set
    var scanned by mutableStateOf<Int?>(null)
        private set

    private val _summaries = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val summaries: SharedFlow<String> = _summaries.asSharedFlow()

    private val queued = mutableListOf<ScanAction>()

    /** Start [action], or queue it behind the running scan; each one's summary (or error) surfaces on [summaries]. */
    fun start(action: ScanAction) {
        if (running) {
            queued += action
            return
        }
        running = true
        scope.launch {
            try {
                var next: ScanAction? = action
                while (next != null) {
                    run(next)
                    next = if (queued.isEmpty()) null else queued.removeAt(0)
                }
            } finally {
                queued.clear()
                running = false
                scanned = null
            }
        }
    }

    private suspend fun run(action: ScanAction) {
        scanned = null
        try {
            action { count -> scanned = count }?.let { _summaries.tryEmit(summaryOf(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.captureError(e, "library scan failed")
            _summaries.tryEmit(getString(Res.string.library_scan_failed, e.errorCode() ?: e.message ?: "$e"))
        }
    }
}

private suspend fun summaryOf(result: ScanResult): String = when (result) {
    is ScanResult.Done -> if (result.failed > 0) {
        getString(Res.string.library_scan_summary_failed, result.scanned, result.added, result.updated, result.removed, result.failed)
    } else {
        getString(Res.string.library_scan_summary, result.scanned, result.added, result.updated, result.removed)
    }
    ScanResult.NoFolders -> getString(Res.string.library_scan_no_folders)
    ScanResult.ExtractorMissing -> getString(Res.string.library_scan_ffmpeg_missing)
}

/** The app-level [ScanController], provided by [AppRoot]. */
internal val LocalScanController = compositionLocalOf<ScanController?> { null }
