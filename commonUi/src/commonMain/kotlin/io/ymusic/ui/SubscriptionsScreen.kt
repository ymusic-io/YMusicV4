package io.ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.menu_play_single
import io.ymusic.ui.resources.menu_unsubscribe
import io.ymusic.ui.resources.queue_last
import io.ymusic.ui.resources.queue_next
import io.ymusic.ui.resources.subscriptions_channels
import io.ymusic.ui.resources.subscriptions_copied
import io.ymusic.ui.resources.subscriptions_copy
import io.ymusic.ui.resources.subscriptions_empty
import io.ymusic.ui.resources.subscriptions_imported
import io.ymusic.ui.resources.subscriptions_latest
import io.ymusic.ui.resources.subscriptions_partial
import io.ymusic.ui.resources.subscriptions_paste
import io.ymusic.ui.subscriptions.LocalFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.TechnicalException

/** Account-free subscriptions: the newest uploads of the channels saved on this device, and the channel list itself. */
@Composable
fun SubscriptionsScreen(graph: AppGraph, coordinator: PlaybackCoordinator, onOpen: (YtFeedItem) -> Unit) {
    val store = graph.localSubscriptions
    val log = remember { graph.logger?.fork("local-subscriptions") }
    val scope = rememberCoroutineScope()
    val showSnackbar = LocalSnackbar.current
    var showChannels by remember { mutableStateOf(false) }
    var feed by remember { mutableStateOf<LocalFeed?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }

    if (store == null) {
        EmptyState(stringResource(Res.string.subscriptions_empty), onReload = {})
        return
    }
    val channels by store.channels.collectAsState()
    LaunchedEffect(store, channels, reloads) {
        if (channels.isEmpty()) {
            feed = LocalFeed(emptyList(), 0)
            errorCode = null
            return@LaunchedEffect
        }
        try {
            feed = store.latest()
            errorCode = null
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log?.captureError(TechnicalException("E60065", "local subscriptions feed failed to load", cause = e))
            errorCode = "E60065"
        }
    }
    val partial = stringResource(Res.string.subscriptions_partial)
    LaunchedEffect(feed) {
        if ((feed?.failedChannels ?: 0) > 0) showSnackbar(partial)
    }
    actionError?.let { code ->
        val message = errorMessage(code)
        LaunchedEffect(code, message) {
            showSnackbar(message)
            actionError = null
        }
    }

    val listState = rememberLazyListState()
    TrackScroll { listState.canScrollBackward }
    val code = errorCode
    val loaded = feed
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = DesignSpacing.MarginCompact, vertical = DesignSpacing.Space8),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeedChip(stringResource(Res.string.subscriptions_latest), selected = !showChannels) { showChannels = false }
            FeedChip(stringResource(Res.string.subscriptions_channels), selected = showChannels) { showChannels = true }
        }
        val copy = graph.copyToClipboard
        val read = graph.readClipboard
        if (copy != null && read != null) {
            Row(
                Modifier.padding(start = DesignSpacing.MarginCompact, end = DesignSpacing.MarginCompact, bottom = DesignSpacing.Space8),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (channels.isNotEmpty()) {
                    FeedChip(stringResource(Res.string.subscriptions_copy), selected = false) {
                        copy(store.export())
                        scope.launch { showSnackbar(getString(Res.string.subscriptions_copied)) }
                    }
                }
                FeedChip(stringResource(Res.string.subscriptions_paste), selected = false) {
                    scope.launch {
                        try {
                            val added = store.importFrom(read())
                            showSnackbar(getString(Res.string.subscriptions_imported, added))
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            log?.captureError(TechnicalException("E60068", "local subscriptions import failed", cause = e))
                            actionError = "E60068"
                        }
                    }
                }
            }
        }
        when {
            channels.isEmpty() -> EmptyState(stringResource(Res.string.subscriptions_empty), onReload = null)
            showChannels -> {
                val unsubscribe = stringResource(Res.string.menu_unsubscribe)
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(channels, key = { it.id }) { channel ->
                        val item = YtFeedItem(
                            kind = YtFeedItem.KIND_CHANNEL,
                            id = channel.id,
                            title = channel.title,
                            url = "/channel/${channel.id}",
                            thumbs = listOfNotNull(channel.thumb?.let { YtThumb(it) }),
                        )
                        FeedListItem(
                            item,
                            ItemActions(
                                listOf(
                                    unsubscribe to {
                                        scope.launch {
                                            try {
                                                store.unsubscribe(channel.id)
                                            } catch (c: CancellationException) {
                                                throw c
                                            } catch (e: Exception) {
                                                log?.captureError(TechnicalException("E60066", "local unsubscribe failed", cause = e))
                                                actionError = "E60066"
                                            }
                                        }
                                    },
                                ),
                            ),
                            onClick = { onOpen(item) },
                        )
                    }
                }
            }
            code != null -> ErrorState(code) { reloads++ }
            loaded == null -> CenteredBox { CircularProgressIndicator() }
            loaded.items.isEmpty() -> EmptyState(stringResource(Res.string.subscriptions_empty), onReload = { reloads++ })
            else -> {
                val playSingle = stringResource(Res.string.menu_play_single)
                val playNext = stringResource(Res.string.queue_next)
                val playLast = stringResource(Res.string.queue_last)
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(loaded.items, key = { it.id }) { item ->
                        FeedListItem(
                            item,
                            ItemActions(
                                listOf(
                                    playSingle to { coordinator.playSingle(item) },
                                    playNext to { coordinator.playNext(item) },
                                    playLast to { coordinator.playLater(item) },
                                ),
                            ),
                            onClick = { coordinator.playSingle(item) },
                            wide = true,
                        )
                    }
                }
            }
        }
    }
}
