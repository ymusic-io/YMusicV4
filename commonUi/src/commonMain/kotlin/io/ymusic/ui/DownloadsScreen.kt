package io.ymusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ymusic.download.DownloadFolderAccessException
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadService
import io.ymusic.download.DownloadState
import io.ymusic.download.DownloadTask
import io.ymusic.download.DownloadTasksAggregator
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.a11y_x_of_y
import io.ymusic.ui.resources.cd_share
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.download_other_format
import io.ymusic.ui.resources.download_size_kb
import io.ymusic.ui.resources.download_size_mb
import io.ymusic.ui.resources.downloads_action_pause
import io.ymusic.ui.resources.downloads_action_resume
import io.ymusic.ui.resources.downloads_action_reveal_finder
import io.ymusic.ui.resources.downloads_action_reveal_library
import io.ymusic.ui.resources.downloads_completed_date
import io.ymusic.ui.resources.downloads_empty_all
import io.ymusic.ui.resources.downloads_empty_completed
import io.ymusic.ui.resources.downloads_empty_error
import io.ymusic.ui.resources.downloads_empty_paused
import io.ymusic.ui.resources.downloads_empty_running
import io.ymusic.ui.resources.downloads_empty_waiting
import io.ymusic.ui.resources.downloads_error_folder_access
import io.ymusic.ui.resources.downloads_failed
import io.ymusic.ui.resources.downloads_filter_all
import io.ymusic.ui.resources.downloads_filter_completed
import io.ymusic.ui.resources.downloads_filter_error
import io.ymusic.ui.resources.downloads_filter_paused
import io.ymusic.ui.resources.downloads_filter_running
import io.ymusic.ui.resources.downloads_filter_waiting
import io.ymusic.ui.resources.downloads_none_message
import io.ymusic.ui.resources.downloads_none_title
import io.ymusic.ui.resources.downloads_pause_all
import io.ymusic.ui.resources.downloads_resume_all
import io.ymusic.ui.resources.downloads_state_canceled
import io.ymusic.ui.resources.downloads_state_done
import io.ymusic.ui.resources.downloads_state_downloading
import io.ymusic.ui.resources.downloads_state_failed
import io.ymusic.ui.resources.downloads_state_preparing
import io.ymusic.ui.resources.downloads_state_processing
import io.ymusic.ui.resources.downloads_state_queued
import io.ymusic.ui.resources.downloads_unavailable
import io.ymusic.ui.resources.menu_add_to_playlist
import io.ymusic.ui.resources.menu_play
import io.ymusic.ui.resources.menu_remove
import io.ymusic.ui.resources.menu_remove_from_disk
import io.ymusic.ui.resources.menu_rename
import io.ymusic.ui.resources.month_abbr
import io.ymusic.ui.resources.playback_retry_later
import io.ymusic.ui.resources.playback_sign_in_required
import io.ymusic.ui.resources.playback_unavailable
import io.ymusic.ui.resources.retry
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.Playability
import projectcommon.error.errorCode
import projectcommon.error.playabilityOf

/**
 * The Download Manager: a status filter chip row over one flat adaptive grid (one column on a phone,
 * more as width allows), with a Pause-all / Resume-all FAB bottom-right. Each card: art + title + a status
 * pill, the progress bar and byte counts aligned under the text, a per-state overflow menu, and an inline
 * Retry on failures.
 */
@Composable
fun DownloadsScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val service = graph.downloads
    if (service == null) {
        CenteredBox { Text(stringResource(Res.string.downloads_unavailable)) }
        return
    }
    // Own the aggregator for as long as this screen is composed: it seeds once and folds change events,
    // and is dropped (its DB subscription cancelled) when the user leaves.
    val aggregator = remember(service) { DownloadTasksAggregator(service::snapshot, service.changes) }
    val tasks by aggregator.tasks.collectAsState(emptyList())

    val visible = tasks.filter { it.state != DownloadState.CANCELED }
    if (visible.isEmpty()) {
        CenteredBox {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.downloads_none_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.downloads_none_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    // Added order only, never state: a task changing state must keep its slot.
    val ordered = visible.sortedByDescending { it.createdAt }
    val shown = ordered.filter { filter.matches(it.state) }
    val running = visible.any { it.state.isActive }
    val paused = visible.any { it.state == DownloadState.PAUSED }

    // Report scroll into the shell's ScrollHost so the shared app bar lifts on scroll, like Library/Discover.
    val gridState = rememberLazyGridState()
    TrackScroll { gridState.canScrollBackward }

    val navigate = LocalNavigateToSection.current
    val folderReveal = LocalLibraryFolderReveal.current
    val downloadLauncher = LocalDownloadLauncher.current
    val store = graph.mediaStore
    val scope = rememberCoroutineScope()
    val signIn = LocalSignInResolver.current
    val snackbar = LocalSnackbar.current
    val scans = LocalScanController.current
    var retryBlock by remember { mutableStateOf<RetryBlock?>(null) }
    var renaming by remember { mutableStateOf<DownloadTask?>(null) }
    renaming?.let { task ->
        graph.fileRenaming?.let { renamer -> RenameDownloadDialog(graph, renamer, service, task, onDismiss = { renaming = null }) }
    }
    retryBlock?.let { block ->
        val dismiss = { retryBlock = null }
        when (block.verdict) {
            Playability.SignInRequired -> DownloadSignInDialog(
                onSignIn = { signIn?.signIn { service.retry(block.taskId) } },
                onDismiss = dismiss,
            )
            Playability.Unavailable -> DownloadUnavailableDialog(onRemove = { service.remove(block.taskId) }, onDismiss = dismiss)
            Playability.RetryLater -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(DownloadCardMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesignSpacing.MarginCompact,
                end = DesignSpacing.MarginCompact,
                top = DesignSpacing.Space8,
                bottom = FabClearance + LocalMiniPlayerInset.current, // so the last card isn't hidden behind the FAB
            ),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Gutter),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    DownloadFilter.entries.forEach { entry ->
                        val label = stringResource(entry.label)
                        LibraryChip(
                            if (entry == DownloadFilter.ALL) label else "$label ${visible.count { entry.matches(it.state) }}",
                            selected = entry == filter,
                        ) { filter = entry }
                    }
                }
            }
            items(shown, key = { it.id }) { task ->
                DownloadCard(
                    task, service,
                    modifier = Modifier.animateItem(),
                    onRetry = {
                        val playability = playabilityOf(task.errorCode)
                        when {
                            // The folder lost its write grant: only a fresh pick restores it, so Retry re-picks first.
                            task.errorCode == DownloadFolderAccessException.CODE && graph.libraryFolderPicker != null ->
                                scope.launch { if (pickDownloadFolder(graph, snackbar, scans)) service.retry(task.id) }
                            // Signed out only: once signed in, the stored verdict may predate the sign-in, so it re-runs.
                            playability == Playability.SignInRequired && signIn != null && !signIn.signedIn ->
                                retryBlock = RetryBlock(task.id, playability)
                            playability == Playability.Unavailable -> retryBlock = RetryBlock(task.id, playability)
                            else -> service.retry(task.id)
                        }
                    },
                    onPlay = {
                        task.fileUri?.let { uri ->
                            scope.launch { coordinator.playDownloaded(uri, task.videoId, task.title, task.artist) }
                        }
                    },
                    onRevealLibrary = if (store != null) ({
                        scope.launch {
                            // Resolve the folder from the file itself, not the current download-location
                            // setting (which may have changed since): the item's registered bucket, else
                            // the file's parent folder as a Library bucket, else the download location.
                            val uri = task.fileUri
                            folderReveal?.value = libraryItemOf(store, task)?.bucketId
                                ?: uri?.parentFolderUri()?.let { store.findBucketByUri(Uri.parse(it)) }?.id
                                ?: graph.settings.downloadLocationFolder()?.let { store.findBucketByUri(Uri.parse(it)) }?.id
                            navigate(AppSection.Library)
                        }
                        Unit
                    }) else null,
                    onRevealFolder = graph.revealInFolder?.let { hook -> { task.fileUri?.let { uri -> scope.launch { hook(uri) } }; Unit } },
                    fileActions = completedFileActions(graph, task, service, onRename = { renaming = task }),
                    onOtherFormat = downloadLauncher?.let { launcher -> { launcher.launch(task.toFeedItem()) } },
                )
            }
        }

        if (shown.isEmpty()) {
            CenteredBox(Modifier.fillMaxSize()) {
                Text(
                    stringResource(filter.empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (running || paused) {
            ExtendedFloatingActionButton(
                onClick = { if (running) service.pauseAll() else service.resumeAll() },
                text = { Text(stringResource(if (running) Res.string.downloads_pause_all else Res.string.downloads_resume_all)) },
                icon = { Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = LocalMiniPlayerInset.current).padding(DesignSpacing.Space16),
            )
        }
    }
}

/** A Retry that a playability [verdict] answers before any re-run: its resolve dialog is up. */
private class RetryBlock(val taskId: String, val verdict: Playability)

/** The filter chips, in display order. CANCELED matches none (it's filtered out of the screen). */
private enum class DownloadFilter(val label: StringResource, val empty: StringResource) {
    ALL(Res.string.downloads_filter_all, Res.string.downloads_empty_all),
    RUNNING(Res.string.downloads_filter_running, Res.string.downloads_empty_running),
    WAITING(Res.string.downloads_filter_waiting, Res.string.downloads_empty_waiting),
    PAUSED(Res.string.downloads_filter_paused, Res.string.downloads_empty_paused),
    COMPLETED(Res.string.downloads_filter_completed, Res.string.downloads_empty_completed),
    ERROR(Res.string.downloads_filter_error, Res.string.downloads_empty_error),
    ;

    fun matches(state: DownloadState): Boolean = when (this) {
        ALL -> state != DownloadState.CANCELED
        RUNNING -> state == DownloadState.PREPARING || state == DownloadState.DOWNLOADING || state == DownloadState.PROCESSING
        WAITING -> state == DownloadState.QUEUED
        PAUSED -> state == DownloadState.PAUSED
        COMPLETED -> state == DownloadState.COMPLETED
        ERROR -> state == DownloadState.FAILED
    }
}

/** One download as a Media Card: bordered surface, art + title + status pill + overflow up top; the
 *  progress bar and byte counts indented under the text; an inline Retry on failures. Matches the chosen
 *  "Media Cards" mockup. */
@Composable
private fun DownloadCard(
    task: DownloadTask,
    service: DownloadService,
    modifier: Modifier,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onRevealLibrary: (() -> Unit)?,
    onRevealFolder: (() -> Unit)?,
    fileActions: CompletedFileActions,
    onOtherFormat: (() -> Unit)?,
) {
    val c = downloadColors()
    val failed = task.state == DownloadState.FAILED
    Surface(
        shape = RoundedCornerShape(MediaCardCorner),
        color = if (failed) c.dangerSoft else c.surface,
        border = BorderStroke(1.dp, if (failed) c.danger.copy(alpha = 0.4f) else c.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                LibraryThumb(icon = Icons.Outlined.FileDownload, badge = null, artUrl = task.thumbnailUrl, corner = MediaCardCorner)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                        StatusPill(task, c)
                        if (task.state == DownloadState.DOWNLOADING || task.state == DownloadState.PROCESSING) {
                            task.progress?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = c.inkMuted, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
                MoreMenu(actionsFor(task, service, onRetry, onPlay, onRevealLibrary, onRevealFolder, fileActions, onOtherFormat))
            }
            if (task.showsBar()) {
                Box(Modifier.fillMaxWidth().padding(start = ContentIndent).a11ySilent()) { DownloadBar(task, c) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                Box(Modifier.width(ThumbSize), contentAlignment = Alignment.Center) { FormatBadge(task.format) }
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    when (task.state) {
                        DownloadState.DOWNLOADING, DownloadState.PAUSED -> {
                            val done = humanSize(task.downloadedBytes)
                            val total = humanSize(task.totalBytes)
                            Text(
                                "$done / $total",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.inkMuted,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.spokenAs(stringResource(Res.string.a11y_x_of_y, done, total)),
                            )
                        }
                        DownloadState.COMPLETED -> {
                            Text(humanSize(task.totalBytes), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, fontFamily = FontFamily.Monospace)
                            Text(formatShortDate(task.updatedAt), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
                        }
                        DownloadState.FAILED -> {
                            Text(downloadErrorText(task), style = MaterialTheme.typography.bodySmall, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Button(
                                onClick = onRetry,
                                shape = RoundedCornerShape(percent = 50),
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                contentPadding = PaddingValues(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space4),
                            ) { Text(stringResource(Res.string.retry), style = MaterialTheme.typography.labelMedium) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun downloadErrorText(task: DownloadTask): String = downloadErrorUiText(task).resolve()

/** A failed task's message off composition — the shell's download notification shows the card's text. */
suspend fun downloadErrorMessage(task: DownloadTask): String = downloadErrorUiText(task).load()

/** A failed task's message: a category the user acts on as its own text, else the origin-minted
 *  userMessage with its code, else "Something went wrong (code)". */
private fun downloadErrorUiText(task: DownloadTask): UiText {
    val code = task.errorCode ?: return task.errorMessage?.let(UiText::Plain) ?: UiText.Resource(Res.string.downloads_failed)
    return when {
        code == DownloadFolderAccessException.CODE -> UiText.Resource(Res.string.downloads_error_folder_access)
        playabilityOf(code) == Playability.SignInRequired -> UiText.Resource(Res.string.playback_sign_in_required)
        playabilityOf(code) == Playability.Unavailable -> UiText.Resource(Res.string.playback_unavailable)
        playabilityOf(code) == Playability.RetryLater -> UiText.Resource(Res.string.playback_retry_later)
        task.errorMessage != null -> UiText.Plain("${task.errorMessage} ($code)")
        else -> errorText(code)
    }
}

/** Which states show a bar: preparing (indeterminate), and downloading/processing/paused with a known
 *  fraction (determinate). Queued/completed/failed show none. */
private fun DownloadTask.showsBar(): Boolean = state == DownloadState.PREPARING ||
    ((state == DownloadState.DOWNLOADING || state == DownloadState.PROCESSING || state == DownloadState.PAUSED) && progress != null)

/** A download's file and link actions, each null when it doesn't apply (no Library row, no rename hook…). */
private class CompletedFileActions(
    val addToPlaylist: (() -> Unit)?,
    val share: (() -> Unit)?,
    val rename: (() -> Unit)?,
    val deleteFile: (() -> Unit)?,
)

@Composable
private fun completedFileActions(graph: AppGraph, task: DownloadTask, service: DownloadService, onRename: () -> Unit): CompletedFileActions {
    val store = graph.mediaStore
    val songActions = LocalSongActions.current
    val libraryItem by produceState<MediaStoreItem?>(null, store, task.state, task.fileUri) {
        value = if (store != null && task.state == DownloadState.COMPLETED) libraryItemOf(store, task) else null
    }
    val item = libraryItem
    val fileUri = task.fileUri
    return CompletedFileActions(
        addToPlaylist = if (item != null && songActions != null) ({ songActions.addToPlaylist(item) }) else null,
        share = LocalShareYoutube.current?.let { share -> { share(YtFeedItem.KIND_VIDEO, task.videoId) } },
        rename = if (graph.fileRenaming != null && fileUri != null && task.state == DownloadState.COMPLETED) onRename else null,
        deleteFile = if (item != null && songActions?.canDelete == true) {
            { songActions.requestDelete(item) { service.remove(task.id) } }
        } else {
            null
        },
    )
}

/** Renames a completed download's file in place, then re-points its task and its Library row at the new uri. */
@Composable
private fun RenameDownloadDialog(
    graph: AppGraph,
    renamer: FileRenaming,
    service: DownloadService,
    task: DownloadTask,
    onDismiss: () -> Unit,
) {
    val uri = task.fileUri ?: return
    val snackbar = LocalSnackbar.current
    val currentName by produceState<String?>(null, uri) {
        try {
            value = withContext(Dispatchers.IO) { renamer.nameOf(uri) } ?: ""
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            graph.logger?.fork("download-rename", mapOf("taskId" to task.id))
                ?.log(Logger.Level.WARN, "reading a downloaded file's name failed", failure)
            snackbar(errorText(failure.errorCode() ?: "E70071").load())
            onDismiss()
        }
    }
    val name = currentName ?: return
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val extension = name.substring(dot)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FileNameDialog(name.substring(0, dot), extension, error = error, busy = busy, onDismiss = onDismiss) { stem ->
        busy = true
        error = null
        scope.launch {
            val log = graph.logger?.fork("download-rename", mapOf("taskId" to task.id))
            try {
                val store = graph.mediaStore
                val libraryItem = store?.let { libraryItemOf(it, task) }
                val newUri = withContext(Dispatchers.IO) { renamer.rename(uri, stem + extension) }
                service.relocate(task.id, newUri)
                if (store != null && libraryItem != null) store.relocateItem(libraryItem.id, Uri.parse(newUri))
                onDismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                log?.log(Logger.Level.WARN, "renaming a downloaded file failed", failure)
                error = errorText(failure.errorCode() ?: "E70070").load()
            } finally {
                busy = false
            }
        }
    }
}

/** The Library row of a completed download, by its exact file uri. */
private suspend fun libraryItemOf(store: BetterMediaStore, task: DownloadTask): MediaStoreItem? =
    task.fileUri?.let { uri -> store.findItemByUri(Uri.parse(uri)) }

private fun DownloadTask.toFeedItem() = YtFeedItem(
    kind = YtFeedItem.KIND_VIDEO,
    id = videoId,
    title = title,
    subtitleLine1 = artist,
    channelTitle = artist,
    thumbs = thumbnailUrl?.let { listOf(YtThumb(it)) } ?: emptyList(),
)

/** The containing folder of a `file://` uri, as a bucket uri; null for opaque uris (Android SAF
 *  `content://` documents, whose parent isn't derivable by string ops — those resolve via findItemByUri). */
private fun String.parentFolderUri(): String? =
    if (startsWith("file://")) substringBeforeLast('/').takeIf { it.length > "file://".length } else null

@Composable
private fun DownloadBar(task: DownloadTask, c: DownloadColors) {
    if (task.state == DownloadState.PREPARING || task.progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.track)
    } else {
        LinearProgressIndicator(
            progress = { task.progress!! },
            modifier = Modifier.fillMaxWidth(),
            color = if (task.state == DownloadState.PAUSED) c.inkMuted else c.accent,
            trackColor = c.track,
            gapSize = 0.dp, // solid bar, no M3 track gap or stop-indicator dot
            drawStopIndicator = {},
        )
    }
}

/** Colored state chip; the queued one is neutral, the rest semantic (teal active / amber paused / green
 *  done / red failed) — matching the mockup's pill roles. */
@Composable
private fun StatusPill(task: DownloadTask, c: DownloadColors) {
    val (label, bg, fg) = when (task.state) {
        DownloadState.QUEUED -> Triple(Res.string.downloads_state_queued, c.neutral, c.inkMuted)
        DownloadState.PREPARING -> Triple(Res.string.downloads_state_preparing, c.accentSoft, c.accent)
        DownloadState.DOWNLOADING -> Triple(Res.string.downloads_state_downloading, c.accentSoft, c.accent)
        DownloadState.PROCESSING -> Triple(Res.string.downloads_state_processing, c.accentSoft, c.accent)
        DownloadState.PAUSED -> Triple(Res.string.downloads_filter_paused, c.warningSoft, c.warning)
        DownloadState.FAILED -> Triple(Res.string.downloads_state_failed, c.dangerSoft, c.danger)
        DownloadState.COMPLETED -> Triple(Res.string.downloads_state_done, c.successSoft, c.success)
        DownloadState.CANCELED -> Triple(Res.string.downloads_state_canceled, c.neutral, c.inkMuted)
    }
    StatusChip(stringResource(label), bg, fg)
}

@Composable
private fun StatusChip(label: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(percent = 50), color = container) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space2),
        )
    }
}

@Composable
private fun actionsFor(
    task: DownloadTask,
    service: DownloadService,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onRevealLibrary: (() -> Unit)?,
    onRevealFolder: (() -> Unit)?,
    fileActions: CompletedFileActions,
    onOtherFormat: (() -> Unit)?,
): ItemActions {
    val otherFormat = stringResource(Res.string.download_other_format)
    val entries = buildList<Pair<String, () -> Unit>> {
        when (task.state) {
            DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING -> {
                add(stringResource(Res.string.downloads_action_pause) to { service.pause(task.id) })
                add(stringResource(Res.string.dialog_cancel) to { service.cancel(task.id) })
            }
            DownloadState.PAUSED -> {
                add(stringResource(Res.string.downloads_action_resume) to { service.resume(task.id) })
                add(stringResource(Res.string.dialog_cancel) to { service.cancel(task.id) })
            }
            DownloadState.FAILED -> {
                add(stringResource(Res.string.retry) to onRetry)
                onOtherFormat?.let { add(otherFormat to it) }
                fileActions.share?.let { add(stringResource(Res.string.cd_share) to it) }
                add(stringResource(Res.string.menu_remove) to { service.remove(task.id) })
            }
            DownloadState.COMPLETED -> {
                add(stringResource(Res.string.menu_play) to onPlay)
                fileActions.addToPlaylist?.let { add(stringResource(Res.string.menu_add_to_playlist) to it) }
                onOtherFormat?.let { add(otherFormat to it) }
                onRevealLibrary?.let { add(stringResource(Res.string.downloads_action_reveal_library) to it) }
                onRevealFolder?.let { add(stringResource(Res.string.downloads_action_reveal_finder) to it) }
                fileActions.rename?.let { add(stringResource(Res.string.menu_rename) to it) }
                fileActions.share?.let { add(stringResource(Res.string.cd_share) to it) }
                add(stringResource(Res.string.menu_remove) to { service.remove(task.id) })
                fileActions.deleteFile?.let { add(stringResource(Res.string.menu_remove_from_disk) to it) }
            }
            DownloadState.CANCELED -> {
                onOtherFormat?.let { add(otherFormat to it) }
                fileActions.share?.let { add(stringResource(Res.string.cd_share) to it) }
                add(stringResource(Res.string.menu_remove) to { service.remove(task.id) })
            }
        }
    }
    return ItemActions(entries)
}

@Composable
private fun FormatBadge(format: DownloadFormat) {
    Text(
        format.badge(),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(format.badgeColor(), RoundedCornerShape(BadgeCorner))
            .padding(horizontal = DesignSpacing.Space4, vertical = DesignSpacing.Space2),
    )
}

private fun DownloadFormat.badge(): String = when (this) {
    DownloadFormat.MP3 -> "MP3"
    DownloadFormat.M4A -> "M4A"
    DownloadFormat.AUDIO_WEBM -> "OPUS"
    DownloadFormat.VIDEO_WEBM -> "WEBM"
    DownloadFormat.VIDEO_MP4 -> "MP4"
}

/** A distinct badge tint per format so the type reads at a glance (white text sits on all of them). */
private fun DownloadFormat.badgeColor(): Color = when (this) {
    DownloadFormat.MP3 -> Color(0xFF2E7D32) // green
    DownloadFormat.M4A -> Color(0xFF1565C0) // blue
    DownloadFormat.AUDIO_WEBM -> Color(0xFF00695C) // teal
    DownloadFormat.VIDEO_MP4 -> Color(0xFF6A1B9A) // purple
    DownloadFormat.VIDEO_WEBM -> Color(0xFFB23C17) // rust
}

/** An epoch-millis instant as e.g. "Jul 31, 2026", in the device's time zone. */
@Composable
internal fun formatShortDate(epochMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = stringArrayResource(Res.array.month_abbr)[dt.monthNumber - 1]
    return stringResource(Res.string.downloads_completed_date, month, dt.dayOfMonth, dt.year)
}

@Composable
private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return stringResource(Res.string.download_size_mb, "0")
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) {
        stringResource(Res.string.download_size_mb, "${(mb * 10).toLong() / 10.0}")
    } else {
        stringResource(Res.string.download_size_kb, "${bytes / 1024}")
    }
}

/** The download screen's semantic palette — the app's surfaces/ink/accent plus amber/green for the
 *  paused/done states (M3 has no warning/success role), matching the "Media Cards" mockup. */
private class DownloadColors(
    val surface: Color,
    val border: Color,
    val track: Color,
    val neutral: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val warning: Color,
    val warningSoft: Color,
    val success: Color,
    val successSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
)

@Composable
private fun downloadColors(): DownloadColors {
    val d = DesignColors
    val amber = if (d.isDark) Color(0xFFE3A63E) else Color(0xFFB5720F)
    val green = if (d.isDark) Color(0xFF57C983) else Color(0xFF288A50)
    return DownloadColors(
        surface = d.SurfaceDim, // subtle lift from the screen surface — not the high-contrast elevated tone
        border = d.Divider,
        track = d.Placeholder,
        neutral = d.Placeholder,
        ink = d.Ink,
        inkMuted = d.InkMuted,
        accent = d.Accent,
        accentSoft = d.Accent.copy(alpha = 0.16f),
        onAccent = d.OnAccent,
        warning = amber,
        warningSoft = amber.copy(alpha = if (d.isDark) 0.16f else 0.14f),
        success = green,
        successSoft = green.copy(alpha = if (d.isDark) 0.16f else 0.14f),
        danger = MaterialTheme.colorScheme.error,
        dangerSoft = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
    )
}

// --- layout tokens (match the mockup's media-card metrics) ---------------------------------------
private val BadgeCorner = 3.dp
internal val MediaCardCorner = 6.dp // match the video thumbnail radius (RectThumb)
private val CardPadding = 14.dp
private val CardGap = 12.dp // thumb ↔ text
private val ThumbSize = 56.dp // LibraryThumb's size
private val ContentIndent = ThumbSize + CardGap // aligns the bar under the text
private val DownloadCardMinWidth = 340.dp // adaptive grid: 1 column on a phone, more as width allows
private val FabClearance = 88.dp // bottom inset so the FAB doesn't cover the last card
