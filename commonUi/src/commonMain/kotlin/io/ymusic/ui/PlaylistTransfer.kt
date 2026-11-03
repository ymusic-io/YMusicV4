package io.ymusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.mediastore.model.PlaylistSnapshot
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.dialog_ok
import io.ymusic.ui.resources.n_playlists
import io.ymusic.ui.resources.n_songs
import io.ymusic.ui.resources.playlist_backup_result
import io.ymusic.ui.resources.playlist_import_confirm
import io.ymusic.ui.resources.playlist_import_confirm_count
import io.ymusic.ui.resources.playlist_import_desc
import io.ymusic.ui.resources.playlist_import_empty
import io.ymusic.ui.resources.playlist_import_hint
import io.ymusic.ui.resources.playlist_import_none_matched
import io.ymusic.ui.resources.playlist_import_permission
import io.ymusic.ui.resources.playlist_import_result
import io.ymusic.ui.resources.playlist_import_select_all
import io.ymusic.ui.resources.playlist_import_title
import io.ymusic.ui.resources.playlist_menu_backup
import io.ymusic.ui.resources.playlist_menu_import_device
import io.ymusic.ui.resources.playlist_menu_restore
import io.ymusic.ui.resources.playlist_restore_confirm
import io.ymusic.ui.resources.playlist_restore_failed_title
import io.ymusic.ui.resources.playlist_restore_merge
import io.ymusic.ui.resources.playlist_restore_merge_desc
import io.ymusic.ui.resources.playlist_restore_replace
import io.ymusic.ui.resources.playlist_restore_replace_desc
import io.ymusic.ui.resources.playlist_restore_replace_desc_none
import io.ymusic.ui.resources.playlist_restore_result
import io.ymusic.ui.resources.playlist_restore_title
import io.ymusic.ui.resources.playlists_empty_desc
import io.ymusic.ui.resources.playlists_empty_restore
import io.ymusic.ui.resources.playlists_empty_title
import kmpcommon.io.asAsyncSource
import kmpcommon.io.buffer
import kmphttp.json.JsonReader
import kmphttp.json.JsonSyntaxException
import kmphttp.json.JsonWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.EOFException
import okio.Sink
import okio.Source
import okio.buffer
import okio.use
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.CodedError
import projectcommon.error.TechnicalException
import projectcommon.error.codedError
import projectcommon.error.runSuspendCatching
import projectcommon.json.intOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** A playlist another app registered with the OS. */
data class DevicePlaylist(val id: Long, val name: String, val songCount: Int)

/** A device playlist's members matched against the Library: the matched items' playlist codes in
 *  playlist order, out of [total] members. */
data class DevicePlaylistMatch(val codes: List<String>, val total: Int)

/** Playlists other apps registered with the OS (Android's MediaStore). */
interface DevicePlaylistSource {
    /** Asks for access first when needed; null when the user refuses it. */
    suspend fun playlists(): List<DevicePlaylist>?

    suspend fun match(playlistId: Long, library: List<MediaStoreItem>): DevicePlaylistMatch
}

internal const val PLAYLIST_BACKUP_INVALID_CODE = "playlist_backup_invalid"
internal const val PLAYLIST_BACKUP_NEWER_CODE = "playlist_backup_newer"

private const val BACKUP_MIME_TYPE = "application/json"
private const val BACKUP_FORMAT = "ymusic.playlists"
private const val BACKUP_VERSION = 1

/** A file the user picked is not a backup this app can read: a category the user acts on, not a crash. */
private class PlaylistBackupException(override val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause),
    CodedError {
    override val userMessage: String? = null
}

internal class PlaylistBackup(val exportedAt: Long, val playlists: List<PlaylistSnapshot>) {
    val songCount: Int get() = playlists.sumOf { it.items.size }
}

/** Writes every user playlist, members that don't resolve on this device included, and closes [sink].
 *  Title/artist ride along only so the file reads meaningfully; restore keys on `code`. */
internal suspend fun writePlaylistBackup(store: BetterMediaStore, sink: Sink, appVersion: String?): Int {
    val playlists = store.playlists()
    val contents = playlists.map { playlist ->
        store.playlistItems(playlist.id) to store.playlistItemDetails(playlist.id).associate { it.code to it.mediaItem }
    }
    val exportedAt = Clock.System.now().toEpochMilliseconds()
    withContext(Dispatchers.IO) {
        sink.buffer().use { out ->
            val writer = JsonWriter.Sync { out.write(it.buffer, it.buffer.size) }
            writer.beginObject()
            writer.name("format").value(BACKUP_FORMAT)
            writer.name("version").value(BACKUP_VERSION)
            writer.name("exportedAt").value(exportedAt)
            writer.name("app").value(appVersion)
            writer.name("playlists").beginArray()
            playlists.forEachIndexed { index, playlist ->
                val (items, resolved) = contents[index]
                writer.beginObject()
                writer.name("name").value(playlist.name)
                writer.name("createdAt").value(playlist.createdAt)
                writer.name("items").beginArray()
                for (item in items) {
                    val media = resolved[item.code]
                    writer.beginObject()
                    writer.name("code").value(item.code)
                    writer.name("addedAt").value(item.addedAt)
                    if (media != null) {
                        writer.name("title").value(media.title)
                        media.artist?.let { writer.name("artist").value(it) }
                    }
                    writer.endObject()
                }
                writer.endArray()
                writer.endObject()
                writer.flush()
            }
            writer.endArray()
            writer.endObject()
            writer.flush()
        }
    }
    return playlists.size
}

/** Reads and closes [source]. Throws [PlaylistBackupException] for a file that isn't a backup or is
 *  from a newer format. */
internal suspend fun readPlaylistBackup(source: Source): PlaylistBackup = withContext(Dispatchers.IO) {
    val root = source.use {
        try {
            JsonReader(it.asAsyncSource().buffer()).readObject()
        } catch (e: JsonSyntaxException) {
            throw PlaylistBackupException(PLAYLIST_BACKUP_INVALID_CODE, "not JSON", e)
        } catch (e: IllegalStateException) {
            throw PlaylistBackupException(PLAYLIST_BACKUP_INVALID_CODE, "root is not an object", e)
        } catch (e: EOFException) {
            throw PlaylistBackupException(PLAYLIST_BACKUP_INVALID_CODE, "truncated", e)
        }
    }
    fun invalid(message: String) = PlaylistBackupException(PLAYLIST_BACKUP_INVALID_CODE, message)
    if (root.stringOrNull("format") != BACKUP_FORMAT) throw invalid("format is not $BACKUP_FORMAT")
    val version = root.intOrNull("version") ?: throw invalid("no version")
    if (version > BACKUP_VERSION) throw PlaylistBackupException(PLAYLIST_BACKUP_NEWER_CODE, "backup version $version")
    val exportedAt = root.longOrNull("exportedAt") ?: 0L
    val playlists = root.objects("playlists").map { playlist ->
        PlaylistSnapshot(
            name = playlist.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw invalid("playlist without a name"),
            createdAt = playlist.longOrNull("createdAt") ?: exportedAt,
            items = playlist.objects("items").mapNotNull { item ->
                val code = item.stringOrNull("code")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PlaylistSnapshot.Item(code, item.longOrNull("addedAt") ?: exportedAt)
            },
        )
    }
    PlaylistBackup(exportedAt, playlists)
}

private class DeviceImportResult(val playlists: Int, val matched: Int, val total: Int)

/** Merges each picked device playlist's Library matches into the same-named playlist (or a new one);
 *  a playlist with no match is skipped. */
private suspend fun importDevicePlaylists(
    source: DevicePlaylistSource,
    store: BetterMediaStore,
    picked: List<DevicePlaylist>,
): DeviceImportResult {
    val library = store.allItems()
    val now = Clock.System.now().toEpochMilliseconds()
    var total = 0
    val snapshots = picked.mapNotNull { playlist ->
        val match = source.match(playlist.id, library)
        total += match.total
        if (match.codes.isEmpty()) return@mapNotNull null
        PlaylistSnapshot(playlist.name, now, match.codes.map { PlaylistSnapshot.Item(it, now) })
    }
    store.restorePlaylists(snapshots, replaceAll = false)
    return DeviceImportResult(snapshots.size, snapshots.sumOf { it.items.size }, total)
}

/** Import from device, backup and restore for the Playlists tab; [PlaylistTransferDialogs] renders its dialogs. */
@Stable
internal class PlaylistTransferState(
    private val store: BetterMediaStore,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val snackbar: (String) -> Unit,
    private val onChanged: () -> Unit,
) {
    var devicePlaylists by mutableStateOf<List<DevicePlaylist>?>(null)
        private set
    var restorePreview by mutableStateOf<PlaylistBackup?>(null)
        private set
    var restoreFailure by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    val canImport: Boolean get() = graph.devicePlaylists != null
    val canBackup: Boolean get() = graph.createDocument != null
    val canRestore: Boolean get() = graph.openDocument != null

    fun openImport() = runJob("E50003", "device playlists failed to load") {
        val source = graph.devicePlaylists ?: return@runJob
        val lists = source.playlists()
        if (lists == null) snackbar(getString(Res.string.playlist_import_permission)) else devicePlaylists = lists
    }

    fun dismissImport() {
        devicePlaylists = null
    }

    fun import(picked: List<DevicePlaylist>) = runJob("E50004", "device playlist import failed") {
        val source = graph.devicePlaylists ?: return@runJob
        val result = importDevicePlaylists(source, store, picked)
        devicePlaylists = null
        onChanged()
        snackbar(
            if (result.playlists == 0) {
                getString(Res.string.playlist_import_none_matched)
            } else {
                getPluralString(Res.plurals.playlist_import_result, result.playlists, result.playlists, result.matched, result.total)
            },
        )
    }

    fun backup() = runJob("E50001", "playlist backup failed") {
        val create = graph.createDocument ?: return@runJob
        val sink = create(backupFileName(), BACKUP_MIME_TYPE) ?: return@runJob
        val count = writePlaylistBackup(store, sink, graph.appVersionName)
        snackbar(getPluralString(Res.plurals.playlist_backup_result, count, count))
    }

    fun openRestore() = scope.launch {
        val open = graph.openDocument ?: return@launch
        runSuspendCatching {
            val source = open(listOf(BACKUP_MIME_TYPE, "application/octet-stream", "text/plain")) ?: return@launch
            restorePreview = readPlaylistBackup(source)
        }.onFailure { restoreFailure = classify(it, "E50002", "playlist backup failed to read") }
    }

    fun restore(backup: PlaylistBackup, replaceAll: Boolean) = runJob("E50005", "playlist restore failed") {
        store.restorePlaylists(backup.playlists, replaceAll)
        restorePreview = null
        onChanged()
        snackbar(getPluralString(Res.plurals.playlist_restore_result, backup.playlists.size, backup.playlists.size))
    }

    fun dismissRestore() {
        restorePreview = null
        restoreFailure = null
    }

    private fun runJob(siteCode: String, what: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                runSuspendCatching { block() }.onFailure { snackbar(errorText(classify(it, siteCode, what)).load()) }
            } finally {
                busy = false
            }
        }
    }

    /** The code to show for [error]; a technical failure is wrapped in [siteCode] and recorded once. */
    private fun classify(error: Throwable, siteCode: String, what: String): String {
        if (error is CancellationException) throw error
        if (error is PlaylistBackupException) return error.code
        val wrapped = TechnicalException(siteCode, what, cause = error)
        graph.logger?.fork("playlist-transfer")?.captureError(wrapped)
        return wrapped.codedError()?.code ?: siteCode
    }

    private fun backupFileName(): String {
        val date = Clock.System.now().toString().substringBefore('T')
        return "YMusic-playlists-$date.json"
    }
}

@Composable
internal fun rememberPlaylistTransferState(store: BetterMediaStore, graph: AppGraph, onChanged: () -> Unit): PlaylistTransferState {
    val snackbar = LocalSnackbar.current
    // The process scope: leaving the Library must not cancel a restore halfway through its writes.
    return remember(store, graph) { PlaylistTransferState(store, graph, graph.processScope, snackbar, onChanged) }
}

@Composable
internal fun PlaylistTransferDialogs(state: PlaylistTransferState, userPlaylistCount: Int) {
    state.devicePlaylists?.let { lists ->
        ImportDevicePlaylistsDialog(lists, busy = state.busy, onDismiss = state::dismissImport, onImport = state::import)
    }
    state.restorePreview?.let { backup ->
        RestorePlaylistsDialog(
            backup,
            userPlaylistCount = userPlaylistCount,
            busy = state.busy,
            onDismiss = state::dismissRestore,
            onRestore = { replaceAll -> state.restore(backup, replaceAll) },
        )
    }
    state.restoreFailure?.let { code -> RestoreFailedDialog(errorMessage(code), onDismiss = state::dismissRestore) }
}

@Composable
private fun ImportDevicePlaylistsDialog(
    lists: List<DevicePlaylist>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: (List<DevicePlaylist>) -> Unit,
) {
    var picked by remember(lists) { mutableStateOf(emptySet<Long>()) }
    val listState = rememberLazyListState()
    val dismiss = { if (!busy) onDismiss() }
    ElevatedBarDialog(
        title = stringResource(Res.string.playlist_import_title),
        onDismiss = dismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        buttons = {
            if (lists.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_ok), color = DesignColors.Accent) }
            } else {
                DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = !busy, onClick = dismiss)
                TextButton(
                    enabled = !busy && picked.isNotEmpty(),
                    onClick = { onImport(lists.filter { it.id in picked }) },
                ) {
                    Text(
                        if (picked.isEmpty()) {
                            stringResource(Res.string.playlist_import_confirm)
                        } else {
                            stringResource(Res.string.playlist_import_confirm_count, picked.size)
                        },
                        color = if (!busy && picked.isNotEmpty()) DesignColors.Accent else DesignColors.InkFaint,
                    )
                }
            }
        },
    ) {
        if (lists.isEmpty()) {
            DialogText(stringResource(Res.string.playlist_import_empty))
            return@ElevatedBarDialog
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item("desc") {
                Text(
                    stringResource(Res.string.playlist_import_desc),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(start = DesignSpacing.Space24, end = DesignSpacing.Space24, top = DesignSpacing.Space16),
                )
            }
            item("all") {
                val all = picked.size == lists.size
                CheckRow(
                    checked = all,
                    enabled = !busy,
                    onToggle = { picked = if (all) emptySet() else lists.mapTo(mutableSetOf()) { it.id } },
                ) {
                    Text(stringResource(Res.string.playlist_import_select_all), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DesignColors.Ink))
                }
                HorizontalDivider(color = DesignColors.Divider)
            }
            items(lists, key = { it.id }) { playlist ->
                CheckRow(
                    checked = playlist.id in picked,
                    enabled = !busy,
                    onToggle = { picked = if (playlist.id in picked) picked - playlist.id else picked + playlist.id },
                ) {
                    Column {
                        Text(playlist.name, style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(Res.plurals.n_songs, playlist.songCount, playlist.songCount),
                            style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                        )
                    }
                }
            }
            item("hint") {
                Text(
                    stringResource(Res.string.playlist_import_hint),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
                )
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = DesignColors.Accent))
        content()
    }
}

@Composable
private fun RestorePlaylistsDialog(
    backup: PlaylistBackup,
    userPlaylistCount: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRestore: (replaceAll: Boolean) -> Unit,
) {
    var replaceAll by remember(backup) { mutableStateOf(false) }
    val dismiss = { if (!busy) onDismiss() }
    val error = MaterialTheme.colorScheme.error
    ElevatedBarDialog(
        title = stringResource(Res.string.playlist_restore_title),
        onDismiss = dismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = !busy, onClick = dismiss)
            TextButton(enabled = !busy, onClick = { onRestore(replaceAll) }) {
                Text(
                    stringResource(if (replaceAll) Res.string.playlist_restore_replace else Res.string.playlist_restore_confirm),
                    color = if (replaceAll) error else DesignColors.Accent,
                )
            }
        },
    ) {
        Column(Modifier.padding(vertical = DesignSpacing.Space8)) {
            val summary = listOfNotNull(
                pluralStringResource(Res.plurals.n_playlists, backup.playlists.size, backup.playlists.size),
                pluralStringResource(Res.plurals.n_songs, backup.songCount, backup.songCount),
                backup.exportedAt.takeIf { it > 0 }?.let { formatShortDate(it) },
            ).joinToString(" · ")
            Text(
                summary,
                style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink),
                modifier = Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space8),
            )
            Column(Modifier.selectableGroup()) {
                RestoreModeRow(
                    selected = !replaceAll,
                    enabled = !busy,
                    title = stringResource(Res.string.playlist_restore_merge),
                    description = stringResource(Res.string.playlist_restore_merge_desc),
                    accent = DesignColors.Accent,
                    onSelect = { replaceAll = false },
                )
                RestoreModeRow(
                    selected = replaceAll,
                    enabled = !busy,
                    title = stringResource(Res.string.playlist_restore_replace),
                    description = if (userPlaylistCount > 0) {
                        pluralStringResource(Res.plurals.playlist_restore_replace_desc, userPlaylistCount, userPlaylistCount)
                    } else {
                        stringResource(Res.string.playlist_restore_replace_desc_none)
                    },
                    accent = error,
                    onSelect = { replaceAll = true },
                )
            }
        }
    }
}

@Composable
private fun RestoreModeRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    accent: Color,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = accent))
        Column(Modifier.weight(1f).padding(top = DesignSpacing.Space2)) {
            Text(title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) accent else DesignColors.Ink))
            Text(description, style = TextStyle(fontSize = 12.5.sp, color = DesignColors.InkSecondary))
        }
    }
}

@Composable
private fun RestoreFailedDialog(message: String, onDismiss: () -> Unit) {
    ElevatedBarDialog(
        title = stringResource(Res.string.playlist_restore_failed_title),
        onDismiss = onDismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_ok), color = DesignColors.Accent) } },
    ) {
        DialogText(message)
    }
}

@Composable
private fun DialogText(text: String) {
    Text(
        text,
        style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink),
        modifier = Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
    )
}

/** The Playlists toolbar's ⋮ entries; null when the shell offers none. Backup needs a playlist to write. */
@Composable
internal fun playlistTransferMenu(state: PlaylistTransferState, hasUserPlaylists: Boolean): ItemActions? {
    val entries = buildList {
        if (state.canImport) add(stringResource(Res.string.playlist_menu_import_device) to state::openImport)
        if (state.canBackup && hasUserPlaylists) add(stringResource(Res.string.playlist_menu_backup) to state::backup)
        if (state.canRestore) add(stringResource(Res.string.playlist_menu_restore) to { state.openRestore(); Unit })
    }
    return entries.takeIf { it.isNotEmpty() }?.let(::ItemActions)
}

/** Under the system playlists while the user has none of their own: the ways to bring some in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoPlaylistsHint(state: PlaylistTransferState) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Text(stringResource(Res.string.playlists_empty_title), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = DesignColors.Ink))
        Text(
            stringResource(Res.string.playlists_empty_desc),
            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary, textAlign = TextAlign.Center),
        )
        FlowRow(
            Modifier.padding(top = DesignSpacing.Space8),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            if (state.canImport) {
                Button(onClick = state::openImport, enabled = !state.busy) { Text(stringResource(Res.string.playlist_menu_import_device)) }
            }
            if (state.canRestore) {
                OutlinedButton(
                    onClick = { state.openRestore() },
                    border = BorderStroke(1.dp, DesignColors.Outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignColors.Ink),
                ) { Text(stringResource(Res.string.playlists_empty_restore)) }
            }
        }
    }
}
