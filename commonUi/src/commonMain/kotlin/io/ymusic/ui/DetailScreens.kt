package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtPageHeader
import io.ymusic.dynamicengine.yt.YtPageTab
import io.ymusic.dynamicengine.yt.extractChannelDetail
import io.ymusic.dynamicengine.yt.extractPlaylistDetail
import io.ymusic.dynamicengine.yt.removeFromPlaylist
import io.ymusic.dynamicengine.yt.savePlaylist
import io.ymusic.dynamicengine.yt.subscribeChannel
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_download
import io.ymusic.ui.resources.cd_remove_playlist_from_library
import io.ymusic.ui.resources.cd_save_playlist
import io.ymusic.ui.resources.cd_share
import io.ymusic.ui.resources.cd_shuffle
import io.ymusic.ui.resources.channel_sign_in_to_subscribe
import io.ymusic.ui.resources.detail_playlist
import io.ymusic.ui.resources.local_playlist_create
import io.ymusic.ui.resources.playlist_removed
import io.ymusic.ui.resources.playlist_saved
import io.ymusic.ui.resources.playlist_sign_in_to_save
import io.ymusic.ui.resources.subscribe
import io.ymusic.ui.resources.subscribed
import io.ymusic.ui.resources.yt_playlist_removed_video
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.errorCode

/**
 * Playlist detail's retained state (feed + scroll), created when the page is pushed. [url] is the
 * engine-composed app link ("/playlist?…"), round-tripped VERBATIM — how it's served (VL browse,
 * Mix watch-panel, seed videos…) is entirely the engine's business. [item] only seeds the header
 * while the page loads.
 */
class PlaylistSession(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    val url: String,
    val item: YtFeedItem,
    parentLog: Logger?,
) {
    internal val log = parentLog?.fork("PlaylistDetail", mapOf("url" to url))

    /** Whether the playlist is in the viewer's library; null while unknown or signed out. Flips once
     *  the server confirms. */
    var saved by mutableStateOf<Boolean?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    /** A finished save/unsave: the new state on success, or the failure's code — for the screen to
     *  show once and clear. */
    var saveResult by mutableStateOf<Result<Boolean>?>(null)

    val feed = FeedSession(scope, log) { continuation ->
        graph.engines.extractPlaylistDetail(url, continuation = continuation, logger = log).also { page ->
            page.pageHeader?.takeIf { it.savePlaylistId != null }?.let { saved = it.saved }
        }
    }

    init {
        feed.pager.refresh()
    }

    /** A finished removal: the removed video on success, or the failure — shown once and cleared. */
    var removeResult by mutableStateOf<Result<YtFeedItem>?>(null)

    fun removeVideo(item: YtFeedItem) {
        val token = item.removeToken ?: return
        scope.launch {
            removeResult = try {
                graph.engines.removeFromPlaylist(token, log)
                feed.pager.removeItems { it.removeToken == token }
                Result.success(item)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "remove from playlist failed", error)
                Result.failure(error)
            }
        }
    }

    fun toggleSaved(playlistId: String) {
        val current = saved ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                graph.engines.savePlaylist(playlistId, !current, log)
                saved = !current
                saveResult = Result.success(!current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "save playlist failed", error)
                saveResult = Result.failure(error)
            } finally {
                saving = false
            }
        }
    }

    fun close() {
        feed.close()
    }
}

/**
 * Playlist detail (design/final/playlist-detail.html): back · "Playlist" · search app bar, then the
 * header (title + meta + actions row + shuffle FAB) scrolling above the video feed. A video tap
 * plays the playlist as the queue from that video; the FAB shuffles it.
 */
@Composable
fun PlaylistDetailScreen(
    coordinator: PlaybackCoordinator,
    session: PlaylistSession,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onDownload: ((title: String, pager: Pager) -> Unit)? = null,
) {
    val s = session
    val item = s.item
    val state by s.feed.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    val videos = ready?.sections.orEmpty().flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }
    s.saveResult?.let { result ->
        val snackbar = LocalSnackbar.current
        val message = when {
            result.isFailure -> errorMessage(result.exceptionOrNull()?.errorCode() ?: "E20104")
            result.getOrNull() == true -> stringResource(Res.string.playlist_saved)
            else -> stringResource(Res.string.playlist_removed)
        }
        LaunchedEffect(result, message) {
            snackbar(message)
            s.saveResult = null
        }
    }

    s.removeResult?.let { result ->
        val snackbar = LocalSnackbar.current
        val message = result.fold(
            onSuccess = { stringResource(Res.string.yt_playlist_removed_video, it.title) },
            onFailure = { errorMessage(it.errorCode() ?: "E20112") },
        )
        LaunchedEffect(result, message) {
            snackbar(message)
            s.removeResult = null
        }
    }

    val store = coordinator.graph.mediaStore
    var creatingLocal by remember { mutableStateOf<String?>(null) }
    if (store != null) {
        creatingLocal?.let { title -> LocalPlaylistFromDownloadsDialog(store, title, s.feed.pager) { creatingLocal = null } }
    }

    Column(Modifier.fillMaxSize()) {
        DetailAppBar(stringResource(Res.string.detail_playlist), onBack, onSearch)
        CompositionLocalProvider(LocalRemoveFromYtPlaylist provides { s.removeVideo(it) }) {
            FeedList(
                s.feed.pager,
                coordinator,
                onPlayItem = { tapped ->
                    coordinator.playAll(
                        videos,
                        startIndex = videos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0),
                        source = ready.queueSource(coordinator.graph.engines, s.url, videos, s.log),
                    )
                },
                topContent = {
                    val title = ready?.pageHeader?.title ?: item.title
                    PlaylistHeader(
                        title = title,
                        meta = ready?.pageHeader?.subtitleLine1 ?: item.subtitleLine1,
                        onShuffle = if (videos.isEmpty()) {
                            null
                        } else {
                            {
                                coordinator.playAll(
                                    videos,
                                    startIndex = videos.indices.random(),
                                    shuffle = true,
                                    source = ready.queueSource(coordinator.graph.engines, s.url, videos, s.log),
                                )
                            }
                        },
                        onDownload = onDownload?.let { open -> { open(title, s.feed.pager) } },
                        onCreateLocal = store?.let { { creatingLocal = title } },
                        save = ready?.pageHeader?.savePlaylistId?.let { playlistId ->
                            { SavePlaylistToggle(s.saved, s.saving) { s.toggleSaved(playlistId) } }
                        },
                        onShare = sharePlaylist(item, ready?.pageHeader),
                    )
                },
                listState = s.feed.listState,
            )
        }
    }
}

/** The playlist page's Share: its id from the page header, else from the row it was opened from (a
 *  deeplink-seeded page carries the link, not an id, until the header answers). */
@Composable
internal fun sharePlaylist(item: YtFeedItem, header: YtPageHeader?): (() -> Unit)? {
    val share = LocalShareYoutube.current ?: return null
    val playlistId = header?.savePlaylistId ?: item.id.takeIf { item.kind == YtFeedItem.KIND_PLAYLIST && it != item.url } ?: return null
    if (youtubeShareLink(YtFeedItem.KIND_PLAYLIST, playlistId) == null) return null
    return { share(YtFeedItem.KIND_PLAYLIST, playlistId) }
}

/** Title + meta, the actions row and the
 *  shuffle FAB, closed by the 16dp-inset divider. Shared by the WEB playlist detail and the YT Music
 *  album/playlist detail so both read identically. [save] (the save-to-library toggle) leads the
 *  row only when the playlist can be saved. */
@Composable
internal fun PlaylistHeader(
    title: String,
    meta: String?,
    onShuffle: (() -> Unit)?,
    onDownload: (() -> Unit)? = null,
    onCreateLocal: (() -> Unit)? = null,
    save: (@Composable () -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Text(
                title,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.Ink, lineHeight = 25.sp),
            )
            meta?.let {
                Text(
                    it,
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            save?.invoke()
            ActionIcon(Icons.Outlined.Share, stringResource(Res.string.cd_share), enabled = onShare != null, onClick = { onShare?.invoke() })
            ActionIcon(Icons.Outlined.FileDownload, stringResource(Res.string.cd_download), enabled = onDownload != null, onClick = { onDownload?.invoke() })
            if (onCreateLocal != null) ActionIcon(Icons.Outlined.LibraryAdd, stringResource(Res.string.local_playlist_create), onClick = onCreateLocal)
            Box(Modifier.weight(1f))
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(DesignColors.InkStrong)
                    .clickable(enabled = onShuffle != null, role = Role.Button, onClick = { onShuffle?.invoke() })
                    .a11yActionLabel(stringResource(Res.string.cd_shuffle)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Shuffle, null, Modifier.size(20.dp), tint = DesignColors.OnInk)
            }
        }
        // 8dp under the divider + the first row's own 8dp = the list's 16dp item rhythm; without
        // it the first video hugs the divider at half the spacing of every following row.
        HorizontalDivider(color = DesignColors.StackNear, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
}

/**
 * A generic feed page's retained state, created when pushed. [url] is an engine/app "/feed/…" link
 * (the account's watch history, its all-playlists grid) served through the same yt/channel command
 * — which decodes the path to its browseId — but rendered WITHOUT channel chrome (no header, no
 * Subscribe): just the app bar and the feed. [title] seeds the bar until the page header answers.
 */
class FeedDetailSession(
    graph: AppGraph,
    scope: CoroutineScope,
    val url: String,
    val title: String?,
    parentLog: Logger?,
) {
    internal val log = parentLog?.fork("FeedDetail", mapOf("url" to url))
    val feed = FeedSession(scope, log) { continuation ->
        graph.engines.extractChannelDetail(url, continuation = continuation, logger = log)
    }

    init {
        feed.pager.refresh()
    }

    fun close() {
        feed.close()
    }
}

/** A generic feed page: back · title · search app bar over the plain feed. Playlist/channel taps
 *  open through [onOpen]; video taps autoplay as usual. */
@Composable
fun FeedDetailScreen(
    coordinator: PlaybackCoordinator,
    session: FeedDetailSession,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    val state by session.feed.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    Column(Modifier.fillMaxSize()) {
        DetailAppBar(ready?.pageHeader?.title ?: session.title.orEmpty(), onBack, onSearch)
        FeedList(
            session.feed.pager,
            coordinator,
            onOpen = onOpen,
            listState = session.feed.listState,
            onDeeplink = onDeeplink,
        )
    }
}

/**
 * Channel detail's retained state (feed + scroll + the picked tab), created when pushed. [url] is
 * the engine-composed app link ("/channel/…", possibly pinned to a server tab), round-tripped
 * VERBATIM; a tab switch just swaps in that tab's own engine link and refetches. [item] only seeds
 * the header while the page loads.
 */
class ChannelSession(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    val item: YtFeedItem,
    initialUrl: String,
    parentLog: Logger?,
    // Defaults to the WEB channel page; a YT Music artist reuses this whole screen but fetches its
    // page via ytmusic/browse instead (it returns no pageTabs, so the tab row simply never shows).
    fetch: suspend (url: String, continuation: String?, log: Logger?) -> YtFeedPage = { url, continuation, log ->
        graph.engines.extractChannelDetail(url, continuation = continuation, logger = log)
    },
) {
    internal val log = parentLog?.fork("ChannelDetail", mapOf("url" to initialUrl))

    var url by mutableStateOf(initialUrl)

    /** The last non-empty tab row — survives a tab-switch reload (Loading has no page yet). */
    var tabs by mutableStateOf(listOf<YtPageTab>())
    // A channel tab's own videos carry no byline; they are this channel's, so they take its name as
    // the player's artist line. A deeplink seed's title is a shelf header, not the channel name.
    private var channelName: String? = item.title.takeIf { item.channelId == item.id }

    /** The viewer's subscription; null while unknown or signed out. Flips once the server confirms. */
    var subscribed by mutableStateOf<Boolean?>(null)
        private set
    var subscribing by mutableStateOf(false)
        private set

    /** A failed subscribe/unsubscribe, for the screen to show once and clear. */
    var subscribeErrorCode by mutableStateOf<String?>(null)

    val feed = FeedSession(scope, log) { continuation ->
        val page = fetch(url, continuation, log)
        page.pageHeader?.title?.let { channelName = it }
        page.pageHeader?.takeIf { it.channelId != null }?.let { subscribed = it.subscribed }
        val name = channelName ?: return@FeedSession page
        page.copy(
            sections = page.sections.map { section ->
                section.copy(
                    items = section.items.map {
                        if (it.kind == YtFeedItem.KIND_VIDEO && it.channelTitle == null) it.copy(channelTitle = name) else it
                    },
                )
            },
        )
    }

    init {
        feed.pager.refresh()
    }

    fun toggleSubscription(channelId: String) {
        val current = subscribed ?: return
        if (subscribing) return
        subscribing = true
        scope.launch {
            try {
                graph.engines.subscribeChannel(channelId, !current, log)
                subscribed = !current
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "subscribe failed", error)
                subscribeErrorCode = error.errorCode() ?: "E20103"
            } finally {
                subscribing = false
            }
        }
    }

    fun close() {
        feed.close()
    }
}

/**
 * Channel detail (design/final/channel-detail.html): back · name · search app bar, the pinned text
 * tab row (server tabs: Home / Videos / …), then banner + avatar header scrolling above the tab's
 * feed. Playlist taps open through [onOpen] (playlist detail); video taps autoplay as usual.
 */
@Composable
fun ChannelDetailScreen(
    coordinator: PlaybackCoordinator,
    session: ChannelSession,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    val s = session
    val item = s.item
    val state by s.feed.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready

    LaunchedEffect(ready?.pageTabs) {
        ready?.pageTabs?.takeIf { it.isNotEmpty() }?.let { s.tabs = it }
    }
    DisposableEffect(s.tabs) {
        val remove = GlobalShortcutActions.pushTabHandler { i ->
            s.tabs.getOrNull(i - 1)?.url?.let { url ->
                s.url = url
                s.feed.pager.refresh()
                true
            } ?: false
        }
        onDispose { remove() }
    }
    s.subscribeErrorCode?.let { code ->
        val snackbar = LocalSnackbar.current
        val message = errorMessage(code)
        LaunchedEffect(code, message) {
            snackbar(message)
            s.subscribeErrorCode = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailAppBar(ready?.pageHeader?.title ?: item.title, onBack, onSearch) {
            if (s.tabs.isNotEmpty()) {
                ChannelTabRow(s.tabs, activeUrl = s.url) { tab ->
                    tab.url?.let {
                        s.url = it
                        s.feed.pager.refresh()
                    }
                }
                HorizontalDivider(color = DesignColors.Divider)
            }
        }
        // The channel header (banner · avatar · Subscribe) belongs to the FRONT tab only — content
        // tabs (Videos / Shorts / …) start directly at their feed. Before tabs load, the page IS
        // the front tab.
        val onHomeTab = s.tabs.firstOrNull { it.url == s.url }?.home ?: true
        FeedList(
            s.feed.pager,
            coordinator,
            onOpen = onOpen,
            // Loading is a bare spinner — the header waits for the page instead of rendering from the tapped item.
            topContent = if (onHomeTab && ready != null) {
                { ChannelHeader(ready?.pageHeader, fallback = item, s.subscribed, s.subscribing) { s.toggleSubscription(it) } }
            } else {
                null
            },
            listState = s.feed.listState,
            onDeeplink = onDeeplink,
        )
    }
}

/** Banner strip (when the channel has one) + avatar · name/meta · the Subscribe pill — shown when the
 *  page names its channel; signed out ([subscribed] null), a tap explains that subscribing needs sign-in. */
@Composable
private fun ChannelHeader(
    header: YtPageHeader?,
    fallback: YtFeedItem,
    subscribed: Boolean?,
    subscribing: Boolean,
    onToggleSubscription: (channelId: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val banner = header?.banner.orEmpty()
        if (banner.isNotEmpty()) {
            Box(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ThumbImage(banner, RoundedCornerShape(12.dp)) {
                    ThumbPlaceholder(Icons.Outlined.Image, 26.dp)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ThumbImage(header?.thumbs ?: fallback.thumbs, CircleShape) {
                    ThumbPlaceholder(Icons.Outlined.Person, 26.dp, DesignColors.PlaceholderDim)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    header?.title ?: fallback.title,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = header?.subtitleLine2 ?: header?.subtitleLine1 ?: fallback.subtitleLine1
                meta?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            header?.channelId?.let { channelId ->
                SubscribePill(subscribed, subscribing) { onToggleSubscription(channelId) }
            }
        }
    }
}

/** Filled "Subscribe", or the quieter outlined "Subscribed" once subscribed; a spinner replaces the
 *  label (keeping the pill's width) while [subscribing]. */
@Composable
private fun SubscribePill(subscribed: Boolean?, subscribing: Boolean, onToggle: () -> Unit) {
    val snackbar = LocalSnackbar.current
    val signIn = stringResource(Res.string.channel_sign_in_to_subscribe)
    val shape = RoundedCornerShape(999.dp)
    val contentColor = if (subscribed == true) DesignColors.InkSecondary else DesignColors.OnInk
    Box(
        Modifier
            .clip(shape)
            .then(
                if (subscribed == true) {
                    Modifier.border(1.dp, DesignColors.OutlineSoft, shape)
                } else {
                    Modifier.background(DesignColors.InkStrong)
                },
            )
            .clickable(enabled = !subscribing) { if (subscribed == null) snackbar(signIn) else onToggle() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (subscribed == true) Res.string.subscribed else Res.string.subscribe),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = contentColor),
            modifier = Modifier.alpha(if (subscribing) 0f else 1f),
        )
        if (subscribing) {
            CircularProgressIndicator(Modifier.size(14.dp), color = contentColor, strokeWidth = 2.dp)
        }
    }
}

/** Save to / remove from the YouTube library: accent check when saved, a spinner while [saving];
 *  signed out ([saved] null), a tap explains that saving needs sign-in. */
@Composable
private fun SavePlaylistToggle(saved: Boolean?, saving: Boolean, onToggle: () -> Unit) {
    if (saving) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.InkMuted, strokeWidth = 2.dp)
        }
        return
    }
    val snackbar = LocalSnackbar.current
    val signIn = stringResource(Res.string.playlist_sign_in_to_save)
    ActionIcon(
        if (saved == true) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
        stringResource(if (saved == true) Res.string.cd_remove_playlist_from_library else Res.string.cd_save_playlist),
        tint = if (saved == true) DesignColors.Accent else DesignColors.InkMuted,
        onClick = { if (saved == null) snackbar(signIn) else onToggle() },
    )
}
