package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.PlaylistItemDetail
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.arrange_songs
import io.ymusic.ui.resources.arrange_stacking
import io.ymusic.ui.resources.cd_drag_to_reorder
import io.ymusic.ui.resources.cd_move_to_top
import io.ymusic.ui.resources.cd_reset_stacking
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.dialog_save
import io.ymusic.ui.resources.dialog_undo
import io.ymusic.ui.resources.menu_remove_from_playlist
import kmpcommon.log.Logger
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.TechnicalException
import projectcommon.error.codedError
import kotlin.coroutines.cancellation.CancellationException

/**
 * The working copy behind [ArrangePlaylistDialog]. The top [stackSize] rows are the stack: Move to top
 * lands a song right under it, and a drag or removal that crosses its edge grows or shrinks it, so the
 * stacked songs are always exactly the first [stackSize] rows. Every edit can be undone one at a time.
 */
internal class ArrangeState(private val original: List<PlaylistItemDetail>) {
    private class Snapshot(val items: List<PlaylistItemDetail>, val stackSize: Int)

    var items by mutableStateOf(original)
        private set
    var stackSize by mutableIntStateOf(0)
        private set
    private val history = mutableStateListOf<Snapshot>()
    private var dragStart: Snapshot? = null

    val canUndo: Boolean get() = history.isNotEmpty()
    val removedCodes: List<String> get() = (original.map { it.code }.toSet() - items.map { it.code }.toSet()).toList()

    fun moveToTop(index: Int) {
        if (index < stackSize || index !in items.indices) return
        record()
        items = items.moved(index, stackSize)
        stackSize++
    }

    fun remove(index: Int) {
        if (index !in items.indices) return
        record()
        items = items.toMutableList().also { it.removeAt(index) }
        if (index < stackSize) stackSize--
    }

    fun resetStacking() {
        stackSize = 0
    }

    fun undo() {
        val last = history.removeAt(history.lastIndex)
        items = last.items
        stackSize = last.stackSize
    }

    fun dragStarted() {
        dragStart = Snapshot(items, stackSize)
    }

    fun dragMoved(from: Int, to: Int) {
        items = items.moved(from, to)
    }

    /** A drop below the stack's edge leaves it, one above joins it — judged against the size before the drag. */
    fun dragEnded(from: Int, to: Int) {
        val start = dragStart ?: return
        dragStart = null
        if (from == to) return
        history += start
        if (from < stackSize && to >= stackSize) stackSize-- else if (from >= stackSize && to < stackSize) stackSize++
    }

    private fun record() {
        history += Snapshot(items, stackSize)
    }

    private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().also { it.add(to, it.removeAt(from)) }
}

/** Reorder and remove a user playlist's songs; nothing is written until Save. */
@Composable
internal fun ArrangePlaylistDialog(
    store: BetterMediaStore,
    playlistId: Long,
    items: List<PlaylistItemDetail>,
    logger: Logger?,
    onDismiss: () -> Unit,
) {
    val arrange = remember { ArrangeState(items) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val dragState = rememberReorderDragState(listState, indexOffset = 0) { from, to -> arrange.dragMoved(from, to) }
    val dismiss = { if (!busy) onDismiss() }
    ElevatedBarDialog(
        title = stringResource(Res.string.arrange_songs),
        onDismiss = dismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        dismissOnClickOutside = false,
        titleAction = if (arrange.stackSize > 0) ({ StackingChip(arrange.stackSize, arrange::resetStacking) }) else null,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_undo), enabled = !busy && arrange.canUndo, onClick = arrange::undo)
            Spacer(Modifier.weight(1f))
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = !busy, onClick = dismiss)
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            saveArrangement(store, playlistId, arrange)
                            onDismiss()
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            val wrapped = TechnicalException("E50006", "saving the playlist arrangement failed", cause = e)
                            logger?.fork("arrange-playlist", mapOf("playlist" to playlistId.toString()))?.captureError(wrapped)
                            snackbar(errorText(wrapped.codedError()?.code ?: "E50006").load())
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(stringResource(Res.string.dialog_save), color = if (busy) DesignColors.InkFaint else DesignColors.Accent)
            }
        },
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(arrange.items, key = { _, item -> item.code }) { index, item ->
                ArrangeRow(item, index, stacked = index < arrange.stackSize, arrange, dragState)
            }
        }
    }
}

/** Songs the playlist holds but the library can't resolve right now aren't shown; they keep their place after the arranged ones. */
private suspend fun saveArrangement(store: BetterMediaStore, playlistId: Long, arrange: ArrangeState) {
    arrange.removedCodes.forEach { store.removeFromPlaylist(playlistId, it) }
    val arranged = arrange.items.map { it.code }
    val shown = arranged.toSet()
    val unresolved = store.playlistItems(playlistId).map { it.code }.filter { it !in shown }
    store.reorderPlaylist(playlistId, arranged + unresolved)
}

@Composable
private fun StackingChip(size: Int, onReset: () -> Unit) {
    val label = stringResource(Res.string.arrange_stacking, size)
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, DesignColors.Accent, RoundedCornerShape(999.dp))
            .background(DesignColors.Accent.copy(alpha = 0.14f))
            .clickable(onClick = onReset)
            .a11yActionLabel("$label, ${stringResource(Res.string.cd_reset_stacking)}")
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DesignColors.Accent), modifier = Modifier.a11ySilent())
        Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = DesignColors.Accent)
    }
}

@Composable
private fun ArrangeRow(
    item: PlaylistItemDetail,
    index: Int,
    stacked: Boolean,
    arrange: ArrangeState,
    dragState: ReorderDragState,
) {
    val dragging = dragState.draggingIndex == index
    val currentIndex by rememberUpdatedState(index)
    var dragFrom by remember { mutableIntStateOf(-1) }
    val song = item.mediaItem
    Row(
        Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) dragState.dragOffset else 0f }
            .then(
                when {
                    dragging -> Modifier.shadow(4.dp).background(DesignColors.SurfaceElevated)
                    stacked -> Modifier.background(DesignColors.Accent.copy(alpha = 0.08f))
                    else -> Modifier
                },
            )
            .padding(start = DesignSpacing.Space4, end = DesignSpacing.Space4, top = DesignSpacing.Space4, bottom = DesignSpacing.Space4),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                // Not keyed on the index: each swap moves this row to a new index mid-drag.
                .pointerInput(dragState) {
                    detectDragGestures(
                        onDragStart = {
                            dragFrom = currentIndex
                            arrange.dragStarted()
                            dragState.start(currentIndex)
                        },
                        onDragEnd = {
                            val to = dragState.draggingIndex ?: dragFrom
                            dragState.end()
                            arrange.dragEnded(dragFrom, to)
                        },
                        onDragCancel = {
                            val to = dragState.draggingIndex ?: dragFrom
                            dragState.end()
                            arrange.dragEnded(dragFrom, to)
                        },
                    ) { change, amount ->
                        change.consume()
                        dragState.drag(amount.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.DragHandle, stringResource(Res.string.cd_drag_to_reorder), Modifier.size(20.dp), tint = DesignColors.Outline)
        }
        Box {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                ArtImage(localArtUrl(song), RoundedCornerShape(6.dp)) { ThumbPlaceholder(Icons.Default.MusicNote, 18.dp) }
            }
            if (stacked) {
                Box(
                    Modifier.offset((-4).dp, (-4).dp).size(18.dp).clip(CircleShape).background(DesignColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DesignColors.OnAccent))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = DesignSpacing.Space8)) {
            Text(song.title, style = ListTitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(song.artist, song.album).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = ListSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = DesignSpacing.Space2))
            }
        }
        ActionIcon(
            Icons.Default.VerticalAlignTop,
            stringResource(Res.string.cd_move_to_top),
            iconSize = 22.dp,
            tint = if (stacked) DesignColors.Outline else DesignColors.InkSoft,
            enabled = !stacked,
        ) { arrange.moveToTop(currentIndex) }
        ActionIcon(
            Icons.Outlined.RemoveCircleOutline,
            stringResource(Res.string.menu_remove_from_playlist),
            iconSize = 22.dp,
            tint = DesignColors.InkSoft,
        ) { arrange.remove(currentIndex) }
    }
}
