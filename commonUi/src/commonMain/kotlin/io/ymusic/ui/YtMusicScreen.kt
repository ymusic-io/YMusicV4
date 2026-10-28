package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.extractMusicBrowse
import io.ymusic.dynamicengine.yt.extractMusicCharts
import io.ymusic.dynamicengine.yt.extractMusicExplore
import io.ymusic.dynamicengine.yt.extractMusicHome
import io.ymusic.dynamicengine.yt.extractMusicLibrary
import io.ymusic.dynamicengine.yt.extractMusicNewReleases
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.detail_playlist
import io.ymusic.ui.resources.play_all
import io.ymusic.ui.resources.shuffle_all
import io.ymusic.ui.resources.ytmusic_explore_empty
import io.ymusic.ui.resources.ytmusic_home_empty
import io.ymusic.ui.resources.ytmusic_library_empty
import io.ymusic.ui.resources.ytmusic_tab_charts
import io.ymusic.ui.resources.ytmusic_tab_explore
import io.ymusic.ui.resources.ytmusic_tab_home
import io.ymusic.ui.resources.ytmusic_tab_library
import io.ymusic.ui.resources.ytmusic_tab_new_releases
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

/** The engine-composed music detail link prefix ("/ytmusic/browse?id=…", see musicParse.ts) — the
 *  app routes items/deeplinks carrying it to the music detail layer instead of the WEB pages. */
const val MusicBrowsePrefix = "/ytmusic/browse"

/** The YT Music top tabs. All but Library are anonymous WEB_REMIX feeds; Library needs sign-in. */
enum class YtMusicTab(val label: StringResource) {
    Home(Res.string.ytmusic_tab_home),
    Explore(Res.string.ytmusic_tab_explore),
    NewReleases(Res.string.ytmusic_tab_new_releases),
    Charts(Res.string.ytmusic_tab_charts),
    Library(Res.string.ytmusic_tab_library),
}

/**
 * YT Music — the music.youtube.com surface. Swipeable tabs, each backed by a cached
 * [FeedSession] over the `ytmusic/…` engine commands and drawn with the shared [FeedList]. Library
 * is the signed-in `ytmusic/library` feed — the sign-in preface when logged out; its filter chips
 * (Playlists / Songs / Albums / …) and sort menu ride the page's own chip/sort rows. Songs play on
 * tap (FeedList's default); album/artist/playlist cards navigate through [onOpen] (an
 * engine-composed "/ytmusic/browse?…" link). The section's search lives in the shell app bar.
 *
 * [sessions] is hoisted from the app root (like Discover's) so switching tabs — or returning from a
 * search/detail layer — shows the loaded feed at its scroll position instead of reloading; it is
 * cleared when the content locale or login changes.
 */
@Composable
fun YtMusicScreen(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    sessions: MutableMap<YtMusicTab, FeedSession>,
    localeEpoch: Int,
    scope: CoroutineScope,
    signedIn: Boolean = false,
    onLogin: () -> Unit = {},
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(YtMusicTab.Home) }
    val tabs = remember(graph) { YtMusicTab.entries.filter { !graph.guest || it != YtMusicTab.Library } }
    DisposableEffect(tabs) {
        val remove = GlobalShortcutActions.pushTabHandler { i ->
            tabs.getOrNull(i - 1)?.let { tab = it; true } ?: false
        }
        onDispose { remove() }
    }
    // Live swipe progress (−1..1) so the underline tracks the drag, not just the commit — same as
    // Discover / Library.
    var tabDrag by remember { mutableFloatStateOf(0f) }
    val openDrawer = LocalOpenNavDrawer.current
    Column(Modifier.fillMaxSize()) {
        // The tab row is pinned under the app bar and elevates with it (one continuous surface),
        // exactly like Library's — the shell app bar drops its own bottom shadow for this section.
        ElevatedBarColumn {
            YtMusicTabRow(tabs, tab, tabDrag) { tab = it }
            HorizontalDivider(color = DesignColors.Divider)
        }
        SwipeTabSwitcher(
            tab,
            tabs,
            onSelect = { tab = it },
            onDragProgress = { tabDrag = it },
            leadingEdgeReserve = NavDrawerEdgeReserve,
            onLeadingEdgeSwipe = openDrawer,
        ) { current ->
            if (current == YtMusicTab.Library && !signedIn) {
                LoginPreface(onLogin)
            } else {
                val session = remember(current, localeEpoch) {
                    sessions.getOrPut(current) {
                        val log = graph.logger?.fork("YtMusic" + current.name)
                        FeedSession(
                            scope,
                            log,
                            reloadFetch = if (current == YtMusicTab.Home) {
                                { continuation -> graph.engines.extractMusicHome(continuation, log, refresh = true) }
                            } else {
                                null
                            },
                        ) { continuation ->
                            when (current) {
                                YtMusicTab.Explore -> graph.engines.extractMusicExplore(continuation, log)
                                YtMusicTab.NewReleases -> graph.engines.extractMusicNewReleases(continuation, log)
                                YtMusicTab.Charts -> graph.engines.extractMusicCharts(continuation, log)
                                YtMusicTab.Library -> graph.engines.extractMusicLibrary(continuation, log)
                                else -> graph.engines.extractMusicHome(continuation, log)
                            }
                        }.also { it.pager.refresh() }
                    }
                }
                FeedList(
                    session.pager,
                    coordinator,
                    emptyMessage = stringResource(
                        when (current) {
                            YtMusicTab.Explore -> Res.string.ytmusic_explore_empty
                            YtMusicTab.Library -> Res.string.ytmusic_library_empty
                            else -> Res.string.ytmusic_home_empty
                        },
                    ),
                    onOpen = onOpen,
                    listState = session.listState,
                    onDeeplink = onDeeplink,
                )
            }
        }
    }
}

/** The music tab row — the shared [UnderlineTabRow] (2dp sliding underline) with text labels, the
 *  same treatment as Library's. */
@Composable
private fun YtMusicTabRow(tabs: List<YtMusicTab>, selected: YtMusicTab, dragProgress: Float, onSelect: (YtMusicTab) -> Unit) {
    val labels = tabs.associateWith { stringResource(it.label) }
    UnderlineTabRow(selected, tabs, dragProgress, onSelect, a11yLabel = { labels.getValue(it) }) { t, active ->
        TabLabel(labels.getValue(t), active)
    }
}

/**
 * A music detail page (album / artist / playlist) served by `ytmusic/browse`. [url] is the
 * engine-composed "/ytmusic/browse?id=<browseId>" link, round-tripped VERBATIM; [item] seeds the
 * header while it loads.
 */
class MusicBrowseSession(
    graph: AppGraph,
    scope: CoroutineScope,
    val url: String,
    val item: YtFeedItem,
    parentLog: Logger?,
) {
    internal val log = parentLog?.fork("MusicBrowse", mapOf("url" to url))
    val feed = FeedSession(scope, log) { continuation ->
        graph.engines.extractMusicBrowse(url, continuation = continuation, logger = log)
    }

    init {
        feed.pager.refresh()
    }

    fun close() {
        feed.close()
    }
}

/**
 * The music detail surface served by `ytmusic/browse`, in one of two shapes:
 * - an **album / playlist** reads like the WEB [PlaylistDetailScreen] — "Playlist" · a [PlaylistHeader]
 *   (title · meta · actions · shuffle FAB), and a song tap plays the page as the queue;
 * - a **category feed** (Explore's New releases / Charts / Moods & genres / a mood category, browseId
 *   `FEmusic_…`) is just its title over the shelves — no track-list header or shuffle, since it's a
 *   feed of collections, not a track list.
 * (Artists reuse [ChannelDetailScreen] instead — routed in App.kt.)
 */
@Composable
fun MusicDetailScreen(
    coordinator: PlaybackCoordinator,
    session: MusicBrowseSession,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
    onDownload: ((title: String, pager: Pager) -> Unit)? = null,
) {
    val state by session.feed.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    val videos = ready?.sections.orEmpty().flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }
    // A category/feed browse (New releases, Charts, Moods & genres, a mood) vs a track list.
    val isFeed = session.url.contains("id=FEmusic")

    val store = coordinator.graph.mediaStore
    var creatingLocal by remember { mutableStateOf<String?>(null) }
    if (store != null) {
        creatingLocal?.let { title -> LocalPlaylistFromDownloadsDialog(store, title, session.feed.pager) { creatingLocal = null } }
    }

    Column(Modifier.fillMaxSize()) {
        DetailAppBar(
            if (isFeed) (ready?.pageHeader?.title ?: session.item.title) else stringResource(Res.string.detail_playlist),
            onBack,
            onSearch,
        )
        FeedList(
            session.feed.pager,
            coordinator,
            onPlayItem = if (isFeed) {
                null
            } else {
                { tapped ->
                    coordinator.playAll(
                        videos,
                        startIndex = videos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0),
                        source = ready.queueSource(coordinator.graph.engines, session.url, videos, session.log),
                    )
                }
            },
            topContent = if (isFeed) {
                if (videos.isEmpty()) {
                    null
                } else {
                    {
                        PlayAllBar(
                            onPlayAll = { coordinator.playAll(videos, shuffle = false) },
                            onShuffleAll = { coordinator.playAll(videos, startIndex = videos.indices.random(), shuffle = true) },
                        )
                    }
                }
            } else {
                {
                    val title = ready?.pageHeader?.title ?: session.item.title
                    PlaylistHeader(
                        title = title,
                        meta = ready?.pageHeader?.subtitleLine1 ?: session.item.subtitleLine1,
                        onShare = sharePlaylist(session.item, ready?.pageHeader),
                        onShuffle = if (videos.isEmpty()) {
                            null
                        } else {
                            {
                                coordinator.playAll(
                                    videos,
                                    startIndex = videos.indices.random(),
                                    shuffle = true,
                                    source = ready.queueSource(coordinator.graph.engines, session.url, videos, session.log),
                                )
                            }
                        },
                        onDownload = onDownload?.let { open -> { open(title, session.feed.pager) } },
                        onCreateLocal = store?.let { { creatingLocal = title } },
                    )
                }
            },
            onOpen = onOpen,
            listState = session.feed.listState,
            onDeeplink = onDeeplink,
        )
    }
}

@Composable
internal fun PlayAllBar(onPlayAll: () -> Unit, onShuffleAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        PlayPill(Icons.Default.PlayArrow, stringResource(Res.string.play_all), filled = true, onClick = onPlayAll)
        PlayPill(Icons.Default.Shuffle, stringResource(Res.string.shuffle_all), filled = false, onClick = onShuffleAll)
    }
}

/** A pill action, filled (primary) or outlined. */
@Composable
internal fun PlayPill(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    val content = if (filled) DesignColors.OnInk else DesignColors.Ink
    Row(
        Modifier
            .clip(shape)
            .then(if (filled) Modifier.background(DesignColors.InkStrong) else Modifier.border(1.dp, DesignColors.OutlineSoft, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = content)
        Text(
            label,
            // The font's ascent outruns its descent, so a centered line box sits the caps low; lift a whole pixel.
            Modifier.offset { IntOffset(0, -max(1, 0.4.dp.roundToPx())) },
            maxLines = 1,
            softWrap = false,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = content),
        )
    }
}
