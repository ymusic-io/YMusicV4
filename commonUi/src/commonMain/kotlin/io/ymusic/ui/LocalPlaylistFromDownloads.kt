package io.ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.local_playlist_create
import io.ymusic.ui.resources.local_playlist_created
import io.ymusic.ui.resources.local_playlist_desc
import io.ymusic.ui.resources.local_playlist_downloaded
import io.ymusic.ui.resources.local_playlist_empty
import io.ymusic.ui.resources.playlist_create_confirm
import io.ymusic.ui.resources.playlist_load_more
import io.ymusic.ui.resources.playlist_name_exists
import io.ymusic.ui.resources.playlist_name_hint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Create local playlist" from a YouTube playlist page: a Library playlist of the page's videos the
 * Library already holds, in playlist order — nothing is downloaded. Shares the page's [pager], so
 * "Load more" extends both.
 */
@Composable
internal fun LocalPlaylistFromDownloadsDialog(store: BetterMediaStore, title: String, pager: Pager, onDismiss: () -> Unit) {
    val state by pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    val offline = LocalAvailableOffline.current
    val videos = ready?.sections.orEmpty().flatMap { it.items }
        .filter { it.kind == YtFeedItem.KIND_VIDEO && it.id in offline }
        .distinctBy { it.id }
    val existingNames by produceState(emptyList<String>(), store) { value = store.playlists().map { it.name } }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var name by remember { mutableStateOf(title) }
    var busy by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val duplicate = trimmed.isNotEmpty() && existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canSubmit = !busy && trimmed.isNotEmpty() && !duplicate && videos.isNotEmpty()
    val dismiss = { if (!busy) onDismiss() }
    val submit = {
        if (canSubmit) {
            busy = true
            val codes = videos.map { it.id }
            scope.launch {
                try {
                    val playlist = store.createPlaylist(trimmed)
                    store.addCodesToPlaylist(playlist.id, codes)
                    snackbar(getPluralString(Res.plurals.local_playlist_created, codes.size, trimmed, codes.size))
                    onDismiss()
                } finally {
                    busy = false
                }
            }
        }
        Unit
    }
    val listState = rememberLazyListState()
    ElevatedBarDialog(
        title = stringResource(Res.string.local_playlist_create),
        onDismiss = dismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        dismissOnClickOutside = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = !busy, onClick = dismiss)
            TextButton(enabled = canSubmit, onClick = submit) {
                Text(stringResource(Res.string.playlist_create_confirm), color = DesignColors.Accent)
            }
        },
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    stringResource(Res.string.local_playlist_desc),
                    style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(start = DesignSpacing.Space24, end = DesignSpacing.Space24, top = DesignSpacing.Space16),
                )
            }
            item {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(Res.string.playlist_name_hint),
                    isError = duplicate,
                    supportingText = if (duplicate) stringResource(Res.string.playlist_name_exists) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space8),
                )
            }
            item {
                Row(
                    Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    FeedChip(pluralStringResource(Res.plurals.local_playlist_downloaded, videos.size, videos.size), selected = false, onClick = null)
                    when {
                        ready?.loadingMore == true -> CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.Accent, strokeWidth = 2.dp)
                        ready?.continuation != null -> FeedChip(stringResource(Res.string.playlist_load_more), selected = true, choice = false) { pager.loadMore() }
                    }
                }
            }
            if (videos.isEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.local_playlist_empty),
                        style = TextStyle(fontSize = 14.sp, color = DesignColors.InkFaint),
                        modifier = Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
                    )
                }
            }
            items(videos, key = { it.id }) { CompactVideoRow(it) }
            item { Box(Modifier.size(DesignSpacing.Space8)) }
        }
    }
}

@Composable
private fun CompactVideoRow(video: YtFeedItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
    ) {
        Box(Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            ThumbImage(video.thumbs, RoundedCornerShape(4.dp)) { ThumbPlaceholder(Icons.Outlined.Image, 16.dp) }
        }
        Column(Modifier.weight(1f)) {
            Text(video.title, style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            video.subtitleLine1?.let {
                Text(it, style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
