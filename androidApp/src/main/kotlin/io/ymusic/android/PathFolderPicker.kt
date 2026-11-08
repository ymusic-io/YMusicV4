package io.ymusic.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.ymusic.mediascanner.StorageRoot
import io.ymusic.ui.DesignColors
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.DialogSecondaryButton
import io.ymusic.ui.ElevatedBarDialog
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.folder_picker_empty
import io.ymusic.ui.resources.folder_picker_title
import io.ymusic.ui.resources.folder_picker_use_folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * Browses [roots] by path and returns the chosen directory: the folder pick on devices without the system
 * tree picker (most TVs). Opens inside the only volume, or on the volume list when there are several.
 */
@Composable
internal fun PathFolderPickerDialog(roots: List<StorageRoot>, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(roots.singleOrNull()?.directory) }
    var navigated by remember { mutableStateOf(false) }
    val root = current?.let { dir -> roots.firstOrNull { dir.startsWith(it.directory) } }
    val folders by produceState<List<File>?>(null, current) {
        value = null
        val dir = current
        value = if (dir == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                dir.listFiles().orEmpty().filter { it.isDirectory && !it.isHidden }.sortedBy { it.name.lowercase() }
            }
        }
    }
    val parent = current?.let { dir -> if (dir == root?.directory) null else dir.parentFile }
    val canGoUp = current != null && (parent != null || roots.size > 1)
    val listState = key(current) { rememberLazyListState() }
    val firstRow = remember { FocusRequester() }
    fun open(dir: File?) {
        navigated = true
        current = dir
    }
    ElevatedBarDialog(
        title = stringResource(Res.string.folder_picker_title),
        onDismiss = onDismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onDismiss)
            TextButton(onClick = { current?.let(onPick) }, enabled = current != null) {
                Text(stringResource(Res.string.folder_picker_use_folder))
            }
        },
    ) {
        Column {
            if (current != null && root != null) {
                val relative = current!!.relativeTo(root.directory).path
                Text(
                    if (relative.isEmpty()) root.label else "${root.label}/$relative",
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.padding(
                        start = DesignSpacing.DialogRowHorizontal,
                        end = DesignSpacing.DialogRowHorizontal,
                        top = DesignSpacing.DialogRowVertical,
                    ),
                )
            }
            val loaded = folders
            LazyColumn(state = listState) {
                if (canGoUp) {
                    item(key = "up") {
                        FolderRow(Icons.Default.ArrowUpward, "..", Modifier.focusRequester(firstRow)) { open(parent) }
                    }
                }
                if (current == null) {
                    items(roots, key = { it.directory.path }) { volume ->
                        val focus = if (volume == roots.first()) Modifier.focusRequester(firstRow) else Modifier
                        FolderRow(Icons.Default.SdStorage, volume.label, focus) { open(volume.directory) }
                    }
                } else if (loaded != null) {
                    items(loaded, key = { it.path }) { dir ->
                        val focus = if (!canGoUp && dir == loaded.first()) Modifier.focusRequester(firstRow) else Modifier
                        FolderRow(Icons.Default.Folder, dir.name, focus) { open(dir) }
                    }
                    if (loaded.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(Res.string.folder_picker_empty),
                                style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary),
                                modifier = Modifier.padding(
                                    horizontal = DesignSpacing.DialogRowHorizontal,
                                    vertical = DesignSpacing.DialogRowVertical,
                                ),
                            )
                        }
                    }
                }
            }
            // The clicked row leaves with the listing it was in; on a D-pad focus must land on the new one.
            val hasRows = canGoUp || current == null || !loaded.isNullOrEmpty()
            LaunchedEffect(current, loaded != null) {
                if (navigated && hasRows && (current == null || loaded != null)) firstRow.requestFocus()
            }
        }
    }
}

@Composable
private fun FolderRow(icon: ImageVector, name: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.DialogRowVertical),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = DesignColors.InkSecondary)
        Text(
            name,
            style = TextStyle(fontSize = 15.sp, color = DesignColors.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
