package io.ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.ymusic.download.DownloadFormat
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtLinkTarget
import io.ymusic.dynamicengine.yt.YtSection
import io.ymusic.dynamicengine.yt.extractAccountLibrary
import io.ymusic.dynamicengine.yt.extractHomeFeed
import io.ymusic.dynamicengine.yt.extractMusicBrowse
import io.ymusic.dynamicengine.yt.extractTrendingMusicFeed
import io.ymusic.dynamicengine.yt.getRecommendedFeed
import io.ymusic.dynamicengine.yt.handleWebViewPlayNext
import io.ymusic.dynamicengine.yt.resolveLink
import io.ymusic.player.PlayableItem
import io.ymusic.player.VideoQualityState
import io.ymusic.player.showsVideo
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.captcha_verify
import io.ymusic.ui.resources.coming_soon
import io.ymusic.ui.resources.discover_needs_account
import io.ymusic.ui.resources.discover_no_anonymous_home
import io.ymusic.ui.resources.discover_no_recommendations
import io.ymusic.ui.resources.discover_no_top_tracks
import io.ymusic.ui.resources.discover_no_trending
import io.ymusic.ui.resources.error_captcha
import io.ymusic.ui.resources.error_generic
import io.ymusic.ui.resources.error_network
import io.ymusic.ui.resources.link_open_failed
import io.ymusic.ui.resources.link_queued_last
import io.ymusic.ui.resources.link_queued_next
import io.ymusic.ui.resources.link_unsupported
import io.ymusic.ui.resources.search_videos
import io.ymusic.ui.resources.section_catalogue
import io.ymusic.ui.resources.section_discover
import io.ymusic.ui.resources.section_donate
import io.ymusic.ui.resources.section_downloads
import io.ymusic.ui.resources.section_feedback
import io.ymusic.ui.resources.section_library
import io.ymusic.ui.resources.section_play_again
import io.ymusic.ui.resources.section_settings
import io.ymusic.ui.resources.section_telegram
import io.ymusic.ui.resources.section_yt_music
import io.ymusic.ui.resources.signin_button
import io.ymusic.ui.resources.tab_account_library
import io.ymusic.ui.resources.tab_home
import io.ymusic.ui.resources.tab_music
import io.ymusic.ui.resources.tab_recommended
import io.ymusic.ui.resources.tab_top_tracks
import io.ymusic.ui.resources.yt_playlist_sign_in
import io.ymusic.ui.sponsorblock.SponsorBlockCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.errorCode
import projectcommon.error.runSuspendCatching
import kotlin.math.abs
import kotlin.math.roundToInt

/** App-level sections — the drawer's three groups (design/final/drawer.html). */
/** [id] is persisted (the section a restart resumes): stable, never reused, independent of entry order. */
enum class AppSection(val id: Int, val label: StringResource, val icon: ImageVector, val group: Int) {
    Discover(1, Res.string.section_discover, Icons.Outlined.OndemandVideo, 0),
    YtMusic(2, Res.string.section_yt_music, Icons.Default.PlayCircleOutline, 0),
    Library(3, Res.string.section_library, Icons.Outlined.LibraryMusic, 0),
    PlayAgain(4, Res.string.section_play_again, Icons.Outlined.History, 1),
    Downloads(5, Res.string.section_downloads, Icons.Outlined.FileDownload, 1),
    Settings(6, Res.string.section_settings, Icons.Outlined.Settings, 1),
    Catalogue(7, Res.string.section_catalogue, Icons.Outlined.Palette, 1),
    Donate(8, Res.string.section_donate, Icons.Outlined.FavoriteBorder, 2),
    Telegram(9, Res.string.section_telegram, TelegramIcon, 2),
    Feedback(10, Res.string.section_feedback, Icons.AutoMirrored.Outlined.Chat, 2),
}

/** Switch the top-level section from within a screen (e.g. Downloads → "Reveal in Library"); no-op
 *  outside the app shell. */
val LocalNavigateToSection = androidx.compose.runtime.compositionLocalOf<(AppSection) -> Unit> { {} }

/** A pending "open this folder in the Library" request (a bucket id), stashed by one screen and
 *  consumed by the Library once it composes — so Downloads' "Reveal in Library" lands on the file's
 *  actual folder, not wherever the Library was left. Null host outside the shell. */
val LocalLibraryFolderReveal =
    androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.MutableState<Long?>?> { null }

/**
 * The app root: drawer navigation + the persistent mini-player + the Now Playing overlay. The shell
 * calls this with a ready [AppGraph] (engines started, player built, surface injected).
 *
 * The UI-language setting is applied here: the default locale is overridden and the whole tree is
 * rebuilt under `key(tag)` so every `stringResource` re-resolves, with the layout direction derived
 * from the effective locale (RTL scripts flip the UI). [section] lives above the `key` so a
 * language switch doesn't navigate away from Settings.
 */
@Composable
fun App(graph: AppGraph, catalogue: (@Composable () -> Unit)? = null) {
    App(graph, rememberStartSection(graph), catalogue)
}

private val QueueActions = setOf(LinkAction.QUEUE_NEXT, LinkAction.QUEUE_LAST)

private val DownloadActions = setOf(LinkAction.DOWNLOAD, LinkAction.DOWNLOAD_M4A, LinkAction.DOWNLOAD_MP3)

/** The sections a restart resumes; anything else (Settings, Downloads, …) resumes the last of these. */
private val ResumableSections = setOf(AppSection.Discover, AppSection.Library, AppSection.YtMusic)

/** The section state a shell hands to [App]: starts on the last resumable section the user had open. */
@Composable
fun rememberStartSection(graph: AppGraph): androidx.compose.runtime.MutableState<AppSection> = rememberSaveable {
    val saved = graph.settings.flow(AppSettings.lastSection).value
    mutableStateOf(ResumableSections.firstOrNull { it.id == saved } ?: AppSection.Discover)
}

/** [sectionState] hoisted for tests and for shells that deep-link from platform surfaces (a
 *  download notification tap lands on Downloads): flipping it is exactly what a drawer selection
 *  does (on touch platforms the drawer edge-swipes open over ANY screen, which a headless jvm rig
 *  can't drive). [catalogue] is the dev "UI catalogue" screen; shells pass it only in debug builds
 *  so a release binary never references [CatalogueScreen] and the shrinker drops it. */
@Composable
fun App(
    graph: AppGraph,
    sectionState: androidx.compose.runtime.MutableState<AppSection>,
    catalogue: (@Composable () -> Unit)? = null,
) {
    val uiLanguage by remember(graph) { graph.settings.flow(AppSettings.uiLanguage) }.collectAsState()
    // ONE language, decided ONCE: the shell seeded the setting before the engine started
    // (ensureAppLanguage), so this is a concrete tag — the blank fallback only covers unseeded
    // test graphs. The engine pulls the same key for its hl default; no push happens here.
    val tag = uiLanguage.ifBlank { defaultUiLanguageTag() }
    remember(tag) { applyUiLocale(tag) }
    val themePref by remember(graph) { graph.settings.flow(AppSettings.theme) }.collectAsState()
    val accentPref by remember(graph) { graph.settings.flow(AppSettings.themeAccent) }.collectAsState()
    val palette = designPaletteFor(themePref, androidx.compose.foundation.isSystemInDarkTheme(), accentPref)
    var section by sectionState
    LaunchedEffect(graph.settings) {
        snapshotFlow { sectionState.value }
            .filter { it in ResumableSections }
            .collect { graph.settings.put(AppSettings.lastSection, it.id) }
    }
    LaunchedEffect(graph.playerCache, graph.settings) {
        val cache = graph.playerCache ?: return@LaunchedEffect
        graph.settings.flow(AppSettings.playerCacheMaxMb).collect { mb ->
            withContext(Dispatchers.IO) { cache.blobs.setMaxSize(mb * 1024L * 1024L) }
        }
    }
    LaunchedEffect(graph.player, graph.settings) {
        val quality = graph.settings.string(PlaybackQualitySettingKey, VideoQualityState.AUTO).value
        if (quality != VideoQualityState.AUDIO_ONLY) graph.player.selectVideoQuality(quality)
        graph.player.setAudioOnly(graph.settings.string(PlaybackAudioOnlyKey, "1").value != "0")
        graph.player.volume = graph.settings.string(PlaybackVolumeKey, "1").value.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        graph.player.speed = graph.settings.string(PlaybackSpeedKey, "1").value.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
        graph.player.setPitch(graph.settings.string(PlaybackPitchKey, "1").value.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f)
        graph.settings.string(PlaybackEqKey, "").value.split(',').mapNotNull { it.toFloatOrNull() }.let { n ->
            if (n.size >= 12) {
                graph.player.setBassBoost(n[0])
                graph.player.setVirtualizer(n[1])
                graph.player.setEqBands(n.drop(2).take(10))
                n.getOrNull(12)?.let { graph.player.setPreamp(it) }
            }
        }
        launch {
            graph.player.config.map { it.preferVideoQuality.first }.distinctUntilChanged()
                .collect { graph.settings.putString(PlaybackQualitySettingKey, it) }
        }
        launch {
            combine(graph.player.config.map { it.audioOnly }, graph.pip.demotedToAudioOnly) { audioOnly, demoted ->
                audioOnly.takeUnless { demoted }
            }.filterNotNull().distinctUntilChanged()
                .collect { graph.settings.putString(PlaybackAudioOnlyKey, if (it) "1" else "0") }
        }
        launch {
            graph.player.config.map { "${it.volume}|${it.speed}|${it.pitchFactor}|${it.bassBoostDb}|${it.virtualizer}|${it.eqBands.joinToString(",")}|${it.preampDb}" }
                .distinctUntilChanged()
                .collect { blob ->
                    val p = blob.split('|')
                    if (p.size >= 7) {
                        graph.settings.putString(PlaybackVolumeKey, p[0])
                        graph.settings.putString(PlaybackSpeedKey, p[1])
                        graph.settings.putString(PlaybackPitchKey, p[2])
                        // bass, virtualizer, 10 bands, preamp — preamp last so 12-value saves still load.
                        graph.settings.putString(PlaybackEqKey, "${p[3]},${p[4]},${p[5]},${p[6]}")
                    }
                }
        }
    }
    key(tag) {
        val direction = if (isRtlLanguage(Locale.current.language)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            YMusicTheme(palette) {
                val platformUriHandler = LocalUriHandler.current
                val uriHandler = remember(platformUriHandler) { SafeUriHandler(platformUriHandler, graph.logger?.fork("uri-handler")) }
                CompositionLocalProvider(
                    LocalImageLoader provides graph.imageLoader,
                    LocalRefreshRequests provides graph.refreshRequests,
                    LocalUriHandler provides uriHandler,
                ) {
                    AppRoot(graph, section, catalogue, uriFailures = uriHandler.failures, onSection = { section = it })
                }
            }
        }
    }
}

/** App-wide reload requests (desktop F5/Cmd+R) — the composed feed surface collects and refetches.
 *  Null outside the app shell. */
internal val LocalRefreshRequests = compositionLocalOf<kotlinx.coroutines.flow.Flow<Unit>?> { null }

/** Show a transient message in the app's snackbar. No-op outside the app shell (tests, standalone). */
internal val LocalSnackbar = compositionLocalOf<(String) -> Unit> { {} }

/** The action that clears YouTube's "captcha" bot challenge: [label] names it, [resolve] runs it and
 *  calls its `retry` once the session is refreshed. */
internal class CaptchaResolver(val label: StringResource, val resolve: (retry: () -> Unit) -> Unit)

/** Null outside the app shell: a captcha error then shows its message with no action. */
internal val LocalCaptchaResolver = compositionLocalOf<CaptchaResolver?> { null }

/** Opens the YouTube sign-in; [signIn]'s `onSignedIn` runs once the gate passed and the session is
 *  re-scraped, never when the user backs out of the browser. */
internal class SignInResolver(val signedIn: Boolean, val signIn: (onSignedIn: () -> Unit) -> Unit)

/** Null outside the app shell: a sign-in-required failure then offers no sign-in action. */
internal val LocalSignInResolver = compositionLocalOf<SignInResolver?> { null }

/** Height of the mini player overlaid on the screens' bottom edge (0 when hidden or docked below them):
 *  scrolling content adds it as bottom padding so its last rows can scroll clear of the bar. */
internal val LocalMiniPlayerInset = compositionLocalOf { 0.dp }

/**
 * One pushed navigation layer. Each layer OWNS its screen state (loaded feed, scroll position,
 * query…) so lower layers survive intact under pushed ones — back always lands where the user
 * left, e.g. search results stay loaded while a result's detail page is open above them.
 */
private sealed interface AppLayer {
    class Search(val session: SearchSession) : AppLayer

    class LibrarySearch(val session: LibrarySearchSession) : AppLayer

    class Playlist(val session: PlaylistSession) : AppLayer

    class Channel(val session: ChannelSession) : AppLayer

    /** A generic "/feed/…" page (account history / all playlists) — plain feed, no detail chrome. */
    class Feed(val session: FeedDetailSession) : AppLayer

    class MusicBrowse(val session: MusicBrowseSession) : AppLayer

    /** Download a playlist's videos — shares the playlist page's pager, so it owns nothing to close. */
    class PlaylistDownload(val session: PlaylistDownloadSession) : AppLayer

    /** The YouTube sign-in browser. Holds its [LoginWebViewState] so the back gesture can walk the
     *  page's own history (canGoBack) before popping the layer. */
    /** [switchAccount]: the browser opens YouTube's channel switcher instead of sign-in — no cookie
     *  polling; CLOSING the layer is the completion signal (popLayer refreshes session+account). */
    class Login(val state: LoginWebViewState, val switchAccount: Boolean = false) : AppLayer

    /** The in-app browser on YouTube where the user clears a "captcha" bot challenge. No cookie gate:
     *  closing it is completion — popLayer refreshes the session, then runs every caller's [retries]. */
    class Verify(val state: LoginWebViewState, val retries: MutableList<() -> Unit>) : AppLayer
}

/** The layer's scope logger, the parent for anything opened on top of it (null = the root). */
private val AppLayer.log: kmpcommon.log.Logger?
    get() = when (this) {
        is AppLayer.Search -> session.log
        is AppLayer.Playlist -> session.log
        is AppLayer.Channel -> session.log
        is AppLayer.Feed -> session.log
        is AppLayer.MusicBrowse -> session.log
        is AppLayer.LibrarySearch, is AppLayer.Login, is AppLayer.Verify, is AppLayer.PlaylistDownload -> null
    }

@Composable
private fun AppRoot(
    graph: AppGraph,
    section: AppSection,
    catalogue: (@Composable () -> Unit)?,
    uriFailures: Flow<String>,
    onSection: (AppSection) -> Unit,
) {
    run {
        val scope = rememberCoroutineScope()
        val coordinator = graph.playback
        remember { SponsorBlockCoordinator(graph, scope) }
        val inPip by graph.pip.inPip.collectAsState()
        if (inPip) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoHole(graph.player, graph.playerSurface, Modifier.fillMaxSize())
            }
            return@run
        }
        // A folder-reveal target (bucket id) set by Downloads' "Reveal in Library" and consumed by the
        // Library once it composes. Lives here so it survives the section switch.
        val libraryReveal = remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
        val libraryGroupReveal = remember { androidx.compose.runtime.mutableStateOf<LibraryGroupReveal?>(null) }
        // Signed-in state, derived from the engine's cookie gate. Bumps loginEpoch on sign-in/out.
        val accountController = remember { AccountController(graph, scope) }
        val signedIn by accountController.signedIn.collectAsState()
        val account by accountController.account.collectAsState()
        val profileReady by accountController.profileReady.collectAsState()
        val loginEpoch by accountController.loginEpoch.collectAsState()
        // The Recommended feed's raw material: the playing page reports YouTube's related items for
        // a music video and the engine keeps them. Pure hand-off — the app parses nothing, and a
        // harvest that fails must never disturb playback.
        LaunchedEffect(graph) {
            graph.player.watchNextSeen.collect { seen ->
                runSuspendCatching { graph.engines.handleWebViewPlayNext(seen.videoId, seen.contentsJson) }
                    .onFailure { graph.logger?.fork("watch-next-harvest")?.log(kmpcommon.log.Logger.Level.WARN, "harvest hand-off failed", it) }
            }
        }
        // App-level so a scan started in Library keeps running (and still notifies) across sections.
        val scanController = remember { ScanController(scope, graph.logger?.fork("library-scan")) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        if (catalogue == null) {
            LaunchedEffect(section) {
                if (section == AppSection.Catalogue) onSection(AppSection.Discover)
            }
        }
        val discoverTabs = remember(graph) { discoverTabsFor(graph) }
        var discoverTab by rememberSaveable {
            val saved = graph.settings.flow(AppSettings.lastDiscoverTab).value
            // Trending music on a fresh install — Home stays empty until YouTube has watch history.
            mutableStateOf(discoverTabs.firstOrNull { it.id == saved } ?: DiscoverTab.Music)
        }
        LaunchedEffect(discoverTab) { graph.settings.put(AppSettings.lastDiscoverTab, discoverTab.id) }
        // Live swipe progress (−1..1) so the Discover underline tracks the drag, not just the commit.
        var discoverDrag by remember { mutableFloatStateOf(0f) }
        val nowPlayingOpen by graph.pip.nowPlayingOpen.collectAsState()
        var nowPlayingPeeking by remember { mutableStateOf(false) }
        val snackbarHostState = remember { SnackbarHostState() }
        // Snackbars sit above the mini player, which is drawn deep in the shell.
        var miniHeightPx by remember { mutableIntStateOf(0) }
        val showSnackbar: (String) -> Unit = { message ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
        LaunchedEffect(uriFailures) {
            uriFailures.collect { uri -> showSnackbar(getString(Res.string.link_open_failed, uri)) }
        }
        // Download entry points (any video menu, Now Playing) set these; the hosts below show the options
        // dialog / run a Quick download. Hoisted to the root so Now Playing — drawn over the shell — has
        // them too. Null launcher when there is no download service.
        var pendingDownload by remember { mutableStateOf<io.ymusic.dynamicengine.yt.YtFeedItem?>(null) }
        var pendingQuickDownload by remember { mutableStateOf<io.ymusic.dynamicengine.yt.YtFeedItem?>(null) }
        // A shared link's "M4A/MP3 download": a Quick download in that format.
        var pendingFormatDownload by remember { mutableStateOf<Pair<io.ymusic.dynamicengine.yt.YtFeedItem, DownloadFormat>?>(null) }
        val downloadLauncher = remember(graph) {
            graph.downloads?.let {
                object : DownloadLauncher {
                    override fun launch(video: io.ymusic.dynamicengine.yt.YtFeedItem) {
                        pendingDownload = video
                    }

                    override val quickPreset = graph.quickDownload

                    override fun quickLaunch(video: io.ymusic.dynamicengine.yt.YtFeedItem) {
                        pendingQuickDownload = video
                    }
                }
            }
        }
        val availableOffline = rememberAvailableOffline(graph)
        // Add-to-playlist / save-queue entry points (video menus, Now Playing) set this; the host below
        // shows the dialog. Hoisted to the root for the same reason as the download launcher.
        var ytPlaylistRequest by remember { mutableStateOf<YtPlaylistRequest?>(null) }
        val ytPlaylistSignIn = stringResource(Res.string.yt_playlist_sign_in)
        val ytPlaylistActions = remember(signedIn, ytPlaylistSignIn) {
            YtPlaylistActions(
                addToPlaylist = { videoId ->
                    if (signedIn) ytPlaylistRequest = YtPlaylistRequest.Add(videoId) else showSnackbar(ytPlaylistSignIn)
                },
                saveAsPlaylist = { videoIds ->
                    if (signedIn) {
                        ytPlaylistRequest = YtPlaylistRequest.Create(videoIds, fromQueue = true)
                    } else {
                        showSnackbar(ytPlaylistSignIn)
                    }
                },
            )
        }
        // Scan results are notification-class: shown on whatever screen is open — collected at the root,
        // since the shell (and anything inside it) is torn down while desktop Now Playing covers it.
        LaunchedEffect(scanController) {
            scanController.summaries.collect { showSnackbar(it) }
        }
        LaunchedEffect(coordinator) {
            coordinator.player.queue.collect {
                if (it.items.isEmpty()) {
                    graph.pip.nowPlayingOpen.value = false
                    nowPlayingPeeking = false
                }
            }
        }
        // Discover feeds cache per browseId — switching tab or navigating away and back must show
        // the already-loaded feed at its old scroll position, not reload it.
        val discoverSessions = remember { mutableMapOf<String, FeedSession>() }
        // YT Music's per-tab feed cache — same lifetime/locale rules as Discover's above.
        val ytMusicSessions = remember { mutableMapOf<YtMusicTab, FeedSession>() }
        // The content locale decides what every feed CONTAINS and the language of its
        // server-composed text — but it lives in the ENGINE, so the app only hears "it changed"
        // ([AppGraph.localeEpoch], bumped by the Settings pickers) and throws the cached pages
        // away. It lives here, not in DiscoverFeed: that composable is disposed while a search or
        // detail layer is open, and a remember there would re-clear the cache on every return.
        val localeEpoch by graph.localeEpoch.collectAsState()
        // A sign-in/out changes what every feed CONTAINS (personalized vs anonymous), same as a
        // locale change — so it clears the caches too, and the feeds refetch under the new identity.
        remember(localeEpoch, loginEpoch) {
            discoverSessions.values.forEach { it.close() }
            discoverSessions.clear()
            ytMusicSessions.values.forEach { it.close() }
            ytMusicSessions.clear()
        }
        // Each section retains its OWN layer stack: the drawer switches sections laterally — no
        // push, no clear — so Discover's search/results/detail pages survive a trip through
        // Library and are exactly where the user left them on return. The stacks never mix:
        // AppLayer.Search is YouTube search and only ever lives in Discover's stack (Library's
        // future search is a different, local-files flow).
        val sectionLayers = remember { mutableMapOf<AppSection, androidx.compose.runtime.snapshots.SnapshotStateList<AppLayer>>() }
        val layers = sectionLayers.getOrPut(section) { mutableStateListOf() }
        val sectionStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        // Navigation is url-driven: every openable target carries an engine-composed app link
        // (item.url / tab.url / a shelf CTA's deeplink) that the app routes on and round-trips
        // verbatim — the tapped item itself only seeds the header while the page loads.
        // The parent trail for a newly opened layer: the layer it was opened FROM, else the root.
        // Resolved per open (the stack top changes between opens), never captured once.
        fun parentLog() = layers.lastOrNull()?.log ?: graph.logger
        val openItem: (io.ymusic.dynamicengine.yt.YtFeedItem) -> Unit = { item ->
            val url = item.url
            if (url != null) {
                // Music album/artist/playlist cards carry an engine-composed "/ytmusic/browse?…" link
                // served by the WEB_REMIX music client — a different surface from the WEB
                // playlist/channel pages, so it routes to its own detail layer.
                if (url.startsWith(MusicBrowsePrefix)) {
                    // A music artist reuses the YouTube channel screen (no tab row — a music browse
                    // returns no pageTabs), fetching via ytmusic/browse; albums/playlists get the
                    // playlist-style music detail.
                    if (item.kind == io.ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL) {
                        layers.add(
                            AppLayer.Channel(
                                ChannelSession(graph, scope, item, initialUrl = url, parentLog = parentLog()) { u, c, l ->
                                    graph.engines.extractMusicBrowse(u, c, logger = l)
                                },
                            ),
                        )
                    } else {
                        layers.add(AppLayer.MusicBrowse(MusicBrowseSession(graph, scope, url, item, parentLog())))
                    }
                } else {
                    when (item.kind) {
                        io.ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST ->
                            layers.add(AppLayer.Playlist(PlaylistSession(graph, scope, url, item, parentLog())))
                        io.ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL ->
                            layers.add(AppLayer.Channel(ChannelSession(graph, scope, item, initialUrl = url, parentLog = parentLog())))
                    }
                }
            }
        }
        // Engine-composed deeplinks (a shelf's "View all"): the link decides the surface, the
        // header title seeds the page while it loads.
        fun openDeeplinkIn(stack: MutableList<AppLayer>, link: String, title: String?) {
            val seed = { kind: String ->
                io.ymusic.dynamicengine.yt.YtFeedItem(kind = kind, id = link, title = title.orEmpty(), url = link)
            }
            if (link.startsWith(MusicBrowsePrefix)) {
                stack.add(
                    AppLayer.MusicBrowse(
                        MusicBrowseSession(graph, scope, link, seed(io.ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST), parentLog()),
                    ),
                )
            } else when (val target = parseDeeplink(link)) {
                is Deeplink.Playlist -> stack.add(
                    AppLayer.Playlist(
                        PlaylistSession(graph, scope, target.url, seed(io.ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST), parentLog()),
                    ),
                )
                is Deeplink.Channel -> stack.add(
                    AppLayer.Channel(
                        ChannelSession(
                            graph,
                            scope,
                            seed(io.ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL),
                            initialUrl = target.url,
                            parentLog = parentLog(),
                        ),
                    ),
                )
                is Deeplink.Feed -> stack.add(
                    AppLayer.Feed(FeedDetailSession(graph, scope, target.url, title, parentLog())),
                )
                null -> Unit // an older app meeting a newer bundle's scheme — the CTA is inert
            }
        }
        val openDeeplink: (String, String?) -> Unit = { link, title -> openDeeplinkIn(layers, link, title) }
        // Search is single-instance (Android's SINGLE_TOP + BRING_TO_FRONT): reopening search from
        // anywhere raises the existing layer — with its query, results and scroll — to the top
        // instead of stacking a fresh one.
        val openSearch: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Search }
            val search: AppLayer.Search = when {
                existing >= 0 && existing == layers.lastIndex -> layers[existing] as AppLayer.Search
                existing >= 0 -> (layers.removeAt(existing) as AppLayer.Search).also { layers.add(it) }
                else -> AppLayer.Search(SearchSession.youtube(graph, scope)).also { layers.add(it) }
            }
            // Hotkey from anywhere: drop back to the input mode so the field re-focuses, even if the
            // user had already submitted a query (otherwise the results bar sits silent).
            search.session.editing = true
        }
        DisposableEffect(openSearch) {
            GlobalShortcutActions.openSearch = openSearch
            onDispose { GlobalShortcutActions.openSearch = {} }
        }
        DisposableEffect(drawer, scope) {
            GlobalShortcutActions.toggleNavDrawer = {
                scope.launch { if (drawer.isOpen) drawer.close() else drawer.open() }
            }
            GlobalShortcutActions.closeNavDrawerIfOpen = {
                if (drawer.isOpen) { scope.launch { drawer.close() }; true } else false
            }
            onDispose {
                GlobalShortcutActions.toggleNavDrawer = {}
                GlobalShortcutActions.closeNavDrawerIfOpen = { false }
            }
        }
        if (section == AppSection.Discover) {
            DisposableEffect(discoverTabs) {
                val remove = GlobalShortcutActions.pushTabHandler { i ->
                    discoverTabs.getOrNull(i - 1)?.let { discoverTab = it; true } ?: false
                }
                onDispose { remove() }
            }
        }
        // YT Music's search (its own stack): a music SearchSession over ytmusic/search, single-instance
        // the same way.
        val openMusicSearch: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Search }
            when {
                existing >= 0 && existing == layers.lastIndex -> Unit
                existing >= 0 -> layers.add(layers.removeAt(existing))
                else -> layers.add(AppLayer.Search(SearchSession.music(graph, scope)))
            }
        }
        // A playlist's download page, over its pager; null where there is no download service.
        val openPlaylistDownload: ((title: String, pager: Pager) -> Unit)? = graph.downloads?.let {
            { title, pager -> layers.add(AppLayer.PlaylistDownload(PlaylistDownloadSession(title, pager, graph.quickDownload.value))) }
        }
        // What a SignInResolver caller resumes after the sign-in browser's gate passes.
        var afterSignIn by remember { mutableStateOf<(() -> Unit)?>(null) }
        val popLayer: () -> Unit = popLayer@{
            val top = layers.removeLastOrNull() ?: return@popLayer
            when (top) {
                is AppLayer.Search -> top.session.close()
                is AppLayer.Playlist -> top.session.close()
                is AppLayer.Channel -> top.session.close()
                is AppLayer.Feed -> top.session.close()
                is AppLayer.MusicBrowse -> top.session.close()
                is AppLayer.LibrarySearch, is AppLayer.Login, is AppLayer.Verify, is AppLayer.PlaylistDownload -> Unit
            }
            if (top is AppLayer.Login) afterSignIn = null
            // Closing the account-switcher browser IS its completion (no cookie gate flips while
            // switching): refresh session + account + feeds under whatever identity was picked.
            if (top is AppLayer.Login && top.switchAccount) accountController.onLoginComplete()
            if (top is AppLayer.Verify) {
                val retries = top.retries.toList()
                accountController.onCaptchaVerified { retries.forEach { it() } }
            }
        }
        // The YouTube sign-in browser — single-instance like search: reopening (from the Account tab
        // preface or a future gated action like Subscribe) raises the existing layer.
        val openLogin: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Login }
            when {
                existing >= 0 && existing == layers.lastIndex -> Unit
                existing >= 0 -> layers.add(layers.removeAt(existing))
                else -> layers.add(AppLayer.Login(LoginWebViewState()))
            }
        }
        // The account switcher — same single Login slot (login and switcher never stack).
        val openSwitchAccount: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Login }
            when {
                existing >= 0 && existing == layers.lastIndex -> Unit
                existing >= 0 -> layers.add(layers.removeAt(existing))
                else -> layers.add(AppLayer.Login(LoginWebViewState(), switchAccount = true))
            }
        }
        val signInResolver = SignInResolver(signedIn) { onSignedIn ->
            graph.pip.nowPlayingOpen.value = false
            nowPlayingPeeking = false
            afterSignIn = onSignedIn
            openLogin()
        }
        // A "captcha" failure's action: the in-app browser where a shell has one, else sign-in (the
        // desktop has no WebView; onLoginComplete refreshes the session and retries what failed).
        val captchaResolver = if (graph.loginBrowser != null) {
            CaptchaResolver(Res.string.captcha_verify) { retry ->
                graph.pip.nowPlayingOpen.value = false
                nowPlayingPeeking = false
                val existing = layers.indexOfFirst { it is AppLayer.Verify }
                when {
                    existing >= 0 -> {
                        (layers[existing] as AppLayer.Verify).retries += retry
                        if (existing != layers.lastIndex) layers.add(layers.removeAt(existing))
                    }
                    else -> layers.add(AppLayer.Verify(LoginWebViewState(), mutableListOf(retry)))
                }
            }
        } else {
            CaptchaResolver(Res.string.signin_button) { _ ->
                graph.pip.nowPlayingOpen.value = false
                nowPlayingPeeking = false
                openLogin()
            }
        }
        // Library's own search flow (local files, filter-as-you-type) — a different surface from
        // YouTube search, single-instance within Library's stack the same way.
        val openLibrarySearch: () -> Unit = {
            val store = graph.mediaStore
            if (store != null) {
                val existing = layers.indexOfFirst { it is AppLayer.LibrarySearch }
                when {
                    existing >= 0 && existing == layers.lastIndex -> Unit
                    existing >= 0 -> layers.add(layers.removeAt(existing))
                    else -> layers.add(AppLayer.LibrarySearch(LibrarySearchSession(store)))
                }
            }
        }
        // The "Search "q" online" hand-off: jump to Discover and run q as a YouTube search there,
        // reusing Discover's single search layer (its stack, not Library's).
        val searchOnline: (String) -> Unit = { q ->
            onSection(AppSection.Discover)
            val discover = sectionLayers.getOrPut(AppSection.Discover) { mutableStateListOf() }
            val existing = discover.indexOfFirst { it is AppLayer.Search }
            val layer = if (existing >= 0) {
                discover.removeAt(existing) as AppLayer.Search
            } else {
                AppLayer.Search(SearchSession.youtube(graph, scope))
            }
            layer.session.apply {
                field = androidx.compose.ui.text.input.TextFieldValue(q)
                submitted = q
                editing = false
                filterParams = null
                pager.refresh()
            }
            discover.add(layer)
        }
        // Outside links (youtube.com / youtu.be / the app's own scheme): the engine says what each
        // opens; pages land on Discover's stack, a video starts playing in Now Playing.
        val linkUnsupported = stringResource(Res.string.link_unsupported)
        val linkQueuedNext = stringResource(Res.string.link_queued_next)
        val linkQueuedLast = stringResource(Res.string.link_queued_last)
        val latestOnSection by androidx.compose.runtime.rememberUpdatedState(onSection)
        fun openPage(link: String) {
            graph.pip.nowPlayingOpen.value = false
            latestOnSection(AppSection.Discover)
            openDeeplinkIn(sectionLayers.getOrPut(AppSection.Discover) { mutableStateListOf() }, link, null)
        }
        LaunchedEffect(graph, coordinator) {
            IncomingLinks.links.collect { (url, action) ->
                val log = graph.logger?.fork("IncomingLink", mapOf("action" to action.name))
                val target = try {
                    graph.engines.resolveLink(url, log)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    log?.log(kmpcommon.log.Logger.Level.WARN, "resolve link failed", e)
                    showSnackbar(
                        when (val code = e.errorCode() ?: "E20113") {
                            "network" -> getString(Res.string.error_network)
                            CAPTCHA_CODE -> getString(Res.string.error_captcha)
                            else -> getString(Res.string.error_generic, code)
                        },
                    )
                    return@collect
                }
                when (target) {
                    is YtLinkTarget.Video -> {
                        val video = target.item
                        val queueEmpty = coordinator.player.queue.value.items.isEmpty()
                        val downloads = graph.downloads
                        when {
                            action == LinkAction.PLAY_SINGLE || (queueEmpty && action in QueueActions) ->
                                coordinator.player.setQueue(listOf(video.toYtMedia()), startPositionMs = target.startMs ?: 0L)
                            action == LinkAction.QUEUE_NEXT -> {
                                coordinator.playNext(video)
                                showSnackbar(linkQueuedNext)
                            }
                            action == LinkAction.QUEUE_LAST -> {
                                coordinator.playLater(video)
                                showSnackbar(linkQueuedLast)
                            }
                            action == LinkAction.DOWNLOAD_M4A && downloads != null ->
                                pendingFormatDownload = video to DownloadFormat.M4A
                            action == LinkAction.DOWNLOAD_MP3 && downloads != null && downloads.ffmpegAvailable ->
                                pendingFormatDownload = video to DownloadFormat.MP3
                            action in DownloadActions && downloads != null -> pendingDownload = video
                            else -> {
                                val playlist = target.playlistUrl
                                coordinator.player.setQueue(
                                    listOf(video.toYtMedia()),
                                    startPositionMs = target.startMs ?: 0L,
                                    source = if (playlist != null) {
                                        NextQueueSource.playlist(graph.engines, playlist, listOf(video), null, log?.fork("queue-source"))
                                    } else {
                                        NextQueueSource.radio(graph.engines, video.id, log?.fork("queue-source"))
                                    },
                                )
                                graph.pip.nowPlayingOpen.value = true
                            }
                        }
                    }
                    is YtLinkTarget.Search -> {
                        graph.pip.nowPlayingOpen.value = false
                        searchOnline(target.query)
                    }
                    is YtLinkTarget.Playlist -> openPage(target.url)
                    is YtLinkTarget.Channel -> openPage(target.url)
                    is YtLinkTarget.MusicBrowse -> openPage(target.url)
                    null -> showSnackbar(linkUnsupported)
                }
            }
        }

        // "Check for update" from the drawer; a found update bumps [updateRecheck] so the prompt
        // shows it even after an earlier "Ask later".
        val uriHandler = LocalUriHandler.current
        val config by remember(graph) { graph.remoteConfig ?: emptyFlow() }.collectAsState(initial = null)
        var checkingUpdate by remember { mutableStateOf(false) }
        // Feedback sends the app logs on arrival, so with anonymous reports off it stays closed
        // until the user agrees in this dialog.
        var askFeedbackConsent by remember { mutableStateOf(false) }
        fun openSection(target: AppSection) {
            val needsConsent = target == AppSection.Feedback && graph.captureFeedbackReport != null &&
                !graph.settings.flow(AppSettings.crashReports).value
            if (needsConsent) askFeedbackConsent = true else onSection(target)
        }
        if (askFeedbackConsent) {
            ReportsOffDialog(
                onContinue = {
                    askFeedbackConsent = false
                    scope.launch {
                        graph.settings.put(AppSettings.crashReports, true)
                        onSection(AppSection.Feedback)
                    }
                },
                onCancel = { askFeedbackConsent = false },
            )
        }
        // The song menus' cross-screen jumps land on the Library's root, over Now Playing and any search layer.
        fun openLibraryRoot() {
            graph.pip.nowPlayingOpen.value = false
            nowPlayingPeeking = false
            sectionLayers[AppSection.Library]?.clear()
            openSection(AppSection.Library)
        }
        val songActions = rememberSongActions(
            graph,
            showSnackbar,
            reveal = { song ->
                libraryReveal.value = song.bucketId
                openLibraryRoot()
            },
            goToGroup = { kind, song ->
                val name = when (kind) {
                    LibraryGroupKind.Artist -> song.artist
                    LibraryGroupKind.Album -> song.album
                }
                libraryGroupReveal.value = LibraryGroupReveal(kind, name.orEmpty())
                openLibraryRoot()
            },
        )
        val premium by graph.premium.collectAsState()
        var updateRecheck by remember { mutableStateOf(0) }
        // The system back gesture/button walks the same path as the in-app back arrows: overlays
        // close first (drawer, Now Playing); the search input steps back to its results; then one
        // layer of the CURRENT section's stack pops. A lateral section with nothing left to unwind
        // steps back to Discover — which restores its own retained stack — and only from Discover's
        // root is back left to the OS to exit the app.
        PlatformBackHandler(
            enabled = drawer.isOpen || nowPlayingOpen || layers.isNotEmpty() || section != AppSection.Discover,
        ) {
            val top = layers.lastOrNull()
            when {
                drawer.isOpen -> scope.launch { drawer.close() }
                nowPlayingOpen -> graph.pip.nowPlayingOpen.value = false
                top is AppLayer.Search && top.session.editing && top.session.submitted != null ->
                    top.session.editing = false
                top is AppLayer.LibrarySearch && top.session.drill != null -> top.session.drill = null
                // In the sign-in browser, back walks the page's own history first, then leaves.
                top is AppLayer.Login && top.state.canGoBack -> top.state.goBack()
                top is AppLayer.Verify && top.state.canGoBack -> top.state.goBack()
                layers.isNotEmpty() -> popLayer()
                else -> onSection(AppSection.Discover)
            }
        }

        // Keep the shared video surface hosted while a YouTube item or a pictured local item is
        // current. Now Playing hosts it while open; otherwise the mini player's art tile does, so
        // playback keeps going (the same single container re-parents, which the engines tolerate).
        val queue by graph.player.queue.collectAsState()
        val playbackConfig by graph.player.config.collectAsState()
        val quality by graph.player.videoQuality.collectAsState()
        val needsSurface = queue.currentItem is PlayableItem.YtMedia ||
            (queue.currentItem != null && showsVideo(playbackConfig, quality))

        val holeOpen = desktopLayoutHole && nowPlayingOpen
        Box(
            Modifier.fillMaxSize()
                .then(if (desktopLayoutHole) Modifier else Modifier.background(DesignColors.Surface))
                // Every screen that shows the drawer's Menu button (nothing pushed) takes the edge swipe.
                .drawerEdgeGestureExclusion(!drawer.isOpen && !nowPlayingOpen && layers.isEmpty()),
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding(),
            ) {
                // design/final/home.html: landscape (or any window this wide) puts the tab row inline in the app bar.
                val wide = maxWidth > maxHeight && maxWidth >= 600.dp || maxWidth >= 650.dp
                // Desktop only: fully covering Now Playing is opaque, so skip composing the shell
                // underneath (4× overdraw savings + no stray semantics for hover hit-tests meant
                // for Now Playing). Mobile keeps the shell composed for peek-dismiss animation.
                val drawShell = !cullShellWhenNowPlayingCovers || !nowPlayingOpen || nowPlayingPeeking
                if (drawShell) Box(Modifier.fillMaxSize()) {
                    if (!holeOpen) AppNavigationDrawer(
                        drawerState = drawer,
                        drawerContent = {
                            AppDrawer(
                                section = section,
                                appVersion = graph.appVersion,
                                guest = graph.guest,
                                showCatalogue = catalogue != null,
                                onSelect = { s ->
                                    // Donate and Telegram are actions, not screens.
                                    when (s) {
                                        AppSection.Donate -> graph.openDonation?.invoke()
                                        AppSection.Telegram -> uriHandler.openUri(AppLinks(config).telegram)
                                        else -> openSection(s)
                                    }
                                    scope.launch { drawer.close() }
                                },
                                premium = premium,
                                showDonate = graph.openDonation != null,
                                websiteUrl = AppLinks(config).website,
                                updates = graph.updates,
                                onCheckUpdate = graph.updates?.let {
                                    {
                                        checkingUpdate = true
                                        scope.launch { drawer.close() }
                                    }
                                },
                            )
                        },
                    ) {
                        // The shell owns app-bar elevation: content reports its scroll via TrackScroll into
                        // this host, and the bar reads it. (Content never touches the bar directly.)
                        val scrollHost = remember { ScrollHost() }
                        // Touch platforms scroll the toolbar row away (enter-always) while the tab row
                        // stays pinned; with the tabs inline (wide) the bar carries them, so it stays put.
                        // Only the tabbed sections collapse: without a tab row below, a vanishing toolbar
                        // reads as lost context, not gained space.
                        val collapsible = platformUsesTouch && !wide &&
                            (section == AppSection.Discover || section == AppSection.YtMusic || section == AppSection.Library)
                        var toolbarHeightPx by remember { mutableStateOf(0) }
                        var toolbarOffsetPx by remember { mutableStateOf(0f) }
                        LaunchedEffect(section, discoverTab, layers.isEmpty(), collapsible) {
                            toolbarOffsetPx = 0f
                        }
                        val collapseConnection = remember(collapsible) {
                            object : NestedScrollConnection {
                                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                    if (!collapsible) return Offset.Zero
                                    val previous = toolbarOffsetPx
                                    toolbarOffsetPx = (previous + available.y).coerceIn(-toolbarHeightPx.toFloat(), 0f)
                                    return Offset(0f, toolbarOffsetPx - previous)
                                }
                            }
                        }
                        CompositionLocalProvider(
                            LocalScrollHost provides scrollHost,
                            LocalSnackbar provides showSnackbar,
                            LocalScanController provides scanController,
                            LocalOpenNavDrawer provides { scope.launch { drawer.open() } },
                            LocalDownloadLauncher provides downloadLauncher,
                            LocalYtPlaylistActions provides ytPlaylistActions,
                            LocalAvailableOffline provides availableOffline,
                            LocalNavigateToSection provides { openSection(it) },
                            LocalLibraryFolderReveal provides libraryReveal,
                            LocalLibraryGroupReveal provides libraryGroupReveal,
                            *songActions.provided(),
                            LocalCaptchaResolver provides captchaResolver,
                            LocalSignInResolver provides signInResolver,
                        ) {
                            DefaultDownloadLocationPrompt(graph)
                            Scaffold(
                                modifier = Modifier.nestedScroll(collapseConnection),
                                containerColor = Color.Transparent,
                                // The shell box above already pads safeDrawing; the Scaffold adds no insets.
                                contentWindowInsets = WindowInsets(0),
                                topBar = {
                                    // Search and the detail pages carry their own app bars.
                                    if (layers.isEmpty()) {
                                        // Library's tab row lives in its own body (it needs the loaded state),
                                        // so the shell bar drops its shadow there and lets that row cast it —
                                        // keeping bar + tabs one continuous elevated surface.
                                        ElevatedBarColumn(bottomShadow = section != AppSection.Library && section != AppSection.YtMusic) {
                                            Box(
                                                Modifier.clipToBounds().layout { measurable, constraints ->
                                                    val placeable = measurable.measure(constraints)
                                                    toolbarHeightPx = placeable.height
                                                    val height = (placeable.height + toolbarOffsetPx).roundToInt().coerceAtLeast(0)
                                                    layout(placeable.width, height) {
                                                        placeable.place(0, toolbarOffsetPx.roundToInt())
                                                    }
                                                },
                                            ) {
                                                DesignAppBar(
                                                    title = stringResource(
                                                        if (section == AppSection.Discover) discoverTab.label else section.label,
                                                    ),
                                                    onMenu = { scope.launch { drawer.open() } },
                                                    // Each section's own search flow: YouTube on Discover,
                                                    // local files on Library, none elsewhere.
                                                    onSearch = when {
                                                        section == AppSection.Discover -> openSearch
                                                        section == AppSection.YtMusic -> openMusicSearch
                                                        section == AppSection.Library && graph.mediaStore != null -> openLibrarySearch
                                                        else -> null
                                                    },
                                                    // Inline tabs sit right after the title, so the slot
                                                    // reserves the widest tab label — the tabs must not
                                                    // shift when switching tabs re-titles the bar.
                                                    titleReserveWidthOf = if (section == AppSection.Discover && wide) {
                                                        discoverTabs.map { stringResource(it.label) }
                                                    } else {
                                                        emptyList()
                                                    },
                                                    inlineContent = if (section == AppSection.Discover && wide) {
                                                        { DiscoverTabRow(discoverTab, inline = true, dragProgress = discoverDrag, tabs = discoverTabs) { discoverTab = it } }
                                                    } else {
                                                        null
                                                    },
                                                )
                                            }
                                            if (section == AppSection.Discover && !wide) {
                                                DiscoverTabRow(discoverTab, dragProgress = discoverDrag, tabs = discoverTabs) { discoverTab = it }
                                                HorizontalDivider(color = DesignColors.Divider)
                                            }
                                        }
                                    }
                                },
                            ) { padding ->
                                val screens = @Composable {
                                    when (val top = layers.lastOrNull()) {
                                        is AppLayer.Search -> key(top) {
                                            SearchScreen(
                                                graph,
                                                coordinator,
                                                onBack = popLayer,
                                                onOpen = openItem,
                                                session = top.session,
                                                onDeeplink = openDeeplink,
                                            )
                                        }
                                        is AppLayer.LibrarySearch -> key(top) {
                                            LibrarySearchScreen(
                                                top.session,
                                                coordinator,
                                                onBack = popLayer,
                                                onSearchOnline = searchOnline,
                                            )
                                        }
                                        is AppLayer.Playlist -> key(top) {
                                            PlaylistDetailScreen(
                                                coordinator,
                                                top.session,
                                                onBack = popLayer,
                                                onSearch = openSearch,
                                                onDownload = openPlaylistDownload,
                                            )
                                        }
                                        is AppLayer.PlaylistDownload -> key(top) {
                                            PlaylistDownloadScreen(graph, top.session, onBack = popLayer)
                                        }
                                        is AppLayer.Channel -> key(top) {
                                            ChannelDetailScreen(
                                                coordinator,
                                                top.session,
                                                onBack = popLayer,
                                                // A music artist page's search is music search, not YouTube.
                                                onSearch = if (top.session.url.startsWith(MusicBrowsePrefix)) openMusicSearch else openSearch,
                                                onOpen = openItem,
                                                onDeeplink = openDeeplink,
                                            )
                                        }
                                        is AppLayer.Feed -> key(top) {
                                            FeedDetailScreen(
                                                coordinator,
                                                top.session,
                                                onBack = popLayer,
                                                onSearch = openSearch,
                                                onOpen = openItem,
                                                onDeeplink = openDeeplink,
                                            )
                                        }
                                        is AppLayer.MusicBrowse -> key(top) {
                                            MusicDetailScreen(
                                                coordinator,
                                                top.session,
                                                onBack = popLayer,
                                                onSearch = openMusicSearch,
                                                onOpen = openItem,
                                                onDeeplink = openDeeplink,
                                                onDownload = openPlaylistDownload,
                                            )
                                        }
                                        is AppLayer.Login -> key(top) {
                                            YtLoginScreen(
                                                graph,
                                                top.state,
                                                onBack = popLayer,
                                                // Gate passed: drop the browser, then let the controller pull
                                                // the account card, reload feeds, and stale the player page.
                                                // (The switcher never fires this — popLayer is its signal.)
                                                onComplete = {
                                                    val resume = afterSignIn
                                                    popLayer()
                                                    accountController.onLoginComplete(resume)
                                                },
                                                switchAccount = top.switchAccount,
                                            )
                                        }
                                        is AppLayer.Verify -> key(top) {
                                            YtVerifyScreen(graph, top.state, onDone = popLayer)
                                        }
                                        // Saveable state (tab picks, folder crumbs, scroll positions) is
                                        // kept per section while its content is out of composition —
                                        // Library must come back on the tab and folder it was left on.
                                        null -> sectionStateHolder.SaveableStateProvider(section.name) {
                                            when (section) {
                                                AppSection.Discover -> SwipeTabSwitcher(
                                                    discoverTab,
                                                    discoverTabs,
                                                    onSelect = { discoverTab = it },
                                                    onDragProgress = { discoverDrag = it },
                                                    leadingEdgeReserve = NavDrawerEdgeReserve,
                                                    onLeadingEdgeSwipe = { scope.launch { drawer.open() } },
                                                ) { current ->
                                                    DiscoverFeed(
                                                        graph,
                                                        coordinator,
                                                        current,
                                                        localeEpoch,
                                                        loginEpoch,
                                                        discoverSessions,
                                                        scope,
                                                        signedIn = signedIn,
                                                        profileReady = profileReady,
                                                        account = account,
                                                        onLogin = openLogin,
                                                        onSignOut = { accountController.signOut() },
                                                        onSwitchAccount = openSwitchAccount,
                                                        onOpen = openItem,
                                                        onDeeplink = openDeeplink,
                                                        onSearch = openSearch,
                                                    )
                                                }
                                                AppSection.YtMusic -> YtMusicScreen(
                                                    graph,
                                                    coordinator,
                                                    ytMusicSessions,
                                                    localeEpoch + loginEpoch,
                                                    scope,
                                                    signedIn = signedIn,
                                                    onLogin = openLogin,
                                                    onOpen = openItem,
                                                    onDeeplink = openDeeplink,
                                                )
                                                AppSection.Library -> LibraryScreen(graph, coordinator)
                                                AppSection.PlayAgain -> PlayAgainScreen(graph, coordinator)
                                                AppSection.Downloads -> DownloadsScreen(graph, coordinator)
                                                AppSection.Settings -> SettingsScreen(
                                                    graph,
                                                    signedIn = signedIn,
                                                    onLogin = openLogin,
                                                )
                                                AppSection.Feedback -> FeedbackScreen(graph, onDismiss = { onSection(AppSection.Discover) })
                                                AppSection.Catalogue ->
                                                    if (catalogue != null) catalogue() else ComingSoon(section)
                                                else -> ComingSoon(section)
                                            }
                                        }
                                    }
                                }
                                val mini: @Composable (Modifier) -> Unit = { mod ->
                                    DisposableEffect(Unit) { onDispose { miniHeightPx = 0 } }
                                    MiniPlayer(
                                        coordinator,
                                        surface = if (needsSurface && !nowPlayingOpen) graph.playerSurface else null,
                                        onExpand = { graph.pip.nowPlayingOpen.value = true },
                                        modifier = mod.onSizeChanged { miniHeightPx = it.height },
                                    )
                                }
                                if (desktopLayoutHole) {
                                    Column(Modifier.fillMaxSize()) {
                                        Column(
                                            Modifier.weight(1f).fillMaxWidth().padding(padding)
                                                .background(DesignColors.Surface),
                                        ) { screens() }
                                        mini(Modifier.fillMaxWidth())
                                    }
                                } else {
                                    val miniVisible = coordinator.player.queue.collectAsState().value.currentItem != null
                                    val miniInset = if (miniVisible) with(LocalDensity.current) { miniHeightPx.toDp() } else 0.dp
                                    Box(Modifier.fillMaxSize()) {
                                        Column(Modifier.fillMaxSize().padding(padding)) {
                                            CompositionLocalProvider(LocalMiniPlayerInset provides miniInset) { screens() }
                                        }
                                        mini(
                                            Modifier.align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .navigationBarsPadding(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                UpdateDialog(graph, recheck = updateRecheck)
                UpgradeSnackbar(graph, snackbarHostState)
                if (checkingUpdate) {
                    CheckUpdateDialog(graph) { found ->
                        checkingUpdate = false
                        if (found) updateRecheck++
                    }
                }
            }
            CompositionLocalProvider(
                LocalSnackbar provides showSnackbar,
                LocalScanController provides scanController,
                LocalDownloadLauncher provides downloadLauncher,
                LocalAvailableOffline provides availableOffline,
                LocalYtPlaylistActions provides ytPlaylistActions,
                LocalCaptchaResolver provides captchaResolver,
                LocalSignInResolver provides signInResolver,
                *songActions.provided(),
            ) {
                if (nowPlayingOpen) {
                    NowPlayingScreen(
                        graph,
                        coordinator,
                        onClose = {
                            graph.pip.nowPlayingOpen.value = false
                            nowPlayingPeeking = false
                        },
                        onPeek = { nowPlayingPeeking = it > 0.5f },
                        onLogin = {
                            graph.pip.nowPlayingOpen.value = false
                            nowPlayingPeeking = false
                            openLogin()
                        },
                        onOpenChannel = { url, title ->
                            graph.pip.nowPlayingOpen.value = false
                            nowPlayingPeeking = false
                            openItem(
                                io.ymusic.dynamicengine.yt.YtFeedItem(
                                    kind = io.ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL,
                                    id = url,
                                    title = title,
                                    channelId = url,
                                    url = url,
                                ),
                            )
                        },
                    )
                }
                songActions.Dialogs()
                DownloadDialogHost(graph, pendingDownload) { pendingDownload = null }
                QuickDownloadHost(graph, pendingQuickDownload) { pendingQuickDownload = null }
                QuickDownloadHost(graph, pendingFormatDownload?.first, pendingFormatDownload?.second) { pendingFormatDownload = null }
                YtPlaylistHost(graph, ytPlaylistRequest) { ytPlaylistRequest = null }
            }
            // Last, so messages land over everything — the shell, Now Playing — whichever screen is up.
            Box(Modifier.fillMaxSize()) {
                val snackbarLift = if (nowPlayingOpen) 0.dp else with(LocalDensity.current) { miniHeightPx.toDp() }
                SnackbarHost(
                    snackbarHostState,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = snackbarLift),
                ) { data -> SwipeableSnackbar(data) }
            }
        }
    }
}

/** A snackbar the user can swipe away, either direction. */
@Composable
private fun SwipeableSnackbar(data: SnackbarData) {
    key(data) {
        val swipeState = rememberSwipeToDismissBoxState()
        LaunchedEffect(swipeState.currentValue) {
            if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss()
        }
        SwipeToDismissBox(swipeState, backgroundContent = {}) { Snackbar(data) }
    }
}

/** Discover's five feed tabs (design §1). Home and Music have anonymous backing feeds;
 *  Recommended and Top tracks are built from local play; Account is the signed-in library. [id] is
 *  persisted (the tab a restart resumes): stable, never reused, independent of entry order. */
enum class DiscoverTab(val id: Int, val label: StringResource, val icon: ImageVector, val hasFeed: Boolean) {
    Home(1, Res.string.tab_home, Icons.Outlined.Home, hasFeed = true),
    Music(2, Res.string.tab_music, Icons.Outlined.MusicNote, hasFeed = true),
    Recommended(3, Res.string.tab_recommended, Icons.Default.StarBorder, hasFeed = true),
    TopTracks(4, Res.string.tab_top_tracks, Icons.AutoMirrored.Outlined.TrendingUp, hasFeed = true),
    Account(5, Res.string.tab_account_library, Icons.Outlined.Person, hasFeed = false),
}

/** A guest has no account and keeps no YouTube play history: no Account, no Top tracks. */
internal fun discoverTabsFor(graph: AppGraph): List<DiscoverTab> =
    DiscoverTab.entries.filter { !graph.guest || (it != DiscoverTab.Account && it != DiscoverTab.TopTracks) }

/** Icon tabs with the 2dp underline indicator spanning the tab; [inline] is the app-bar form (capped-width
 *  icon tabs), otherwise the tabs split the row's width evenly. A single
 *  indicator slides between tabs (and leans toward the neighbour by [dragProgress], −1..1, from the
 *  content swipe) instead of the underline snapping tab-to-tab. */
@Composable
internal fun DiscoverTabRow(
    selected: DiscoverTab,
    inline: Boolean = false,
    dragProgress: Float = 0f,
    tabs: List<DiscoverTab> = DiscoverTab.entries,
    onSelect: (DiscoverTab) -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Each tab's (centre, width) in the row's space so the indicator can slide between them.
    val bounds = remember(rtl) { mutableStateMapOf<DiscoverTab, Pair<Dp, Dp>>() }
    BoxWithConstraints(if (inline) Modifier else Modifier.fillMaxWidth()) {
        // Inline: equal tabs tiling the row with no dead space, shrinking from the max when the bar is short.
        val inlineTabWidth = (maxWidth / tabs.size.coerceAtLeast(1)).coerceAtMost(DiscoverInlineTabMaxWidth)
        Column(if (inline) Modifier else Modifier.fillMaxWidth()) {
            Row(if (inline) Modifier else Modifier.fillMaxWidth()) {
                tabs.forEach { t ->
                    val active = t == selected
                    Box(
                        (if (inline) Modifier.width(inlineTabWidth) else Modifier.weight(1f))
                            .onGloballyPositioned { c ->
                                val parent = c.parentLayoutCoordinates
                                val center = (parent?.localPositionOf(c)?.x ?: 0f) + c.size.width / 2f
                                // Start-relative, as Modifier.offset mirrors in RTL.
                                val x = if (rtl && parent != null) parent.size.width - center else center
                                bounds[t] = with(density) { x.toDp() to c.size.width.toDp() }
                            }
                            .then(if (inline) Modifier.clip(RoundedCornerShape(10.dp)) else Modifier)
                            .clickable { onSelect(t) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            t.icon,
                            stringResource(t.label),
                            Modifier.size(24.dp),
                            tint = if (active) DesignColors.Accent else DesignColors.InkFaint,
                        )
                    }
                }
            }
            val cur = bounds[selected]
            val i = tabs.indexOf(selected)
            val neighbor = tabs.getOrNull((i + if (dragProgress > 0f) 1 else -1).coerceIn(0, tabs.lastIndex))?.let { bounds[it] }
            val frac = abs(dragProgress) * TabIndicatorDragTravel
            val targetCenter = if (cur != null && neighbor != null) lerp(cur.first, neighbor.first, frac) else cur?.first ?: 0.dp
            val center by animateDpAsState(targetCenter, label = "discoverIndicator")
            val indicatorWidth = cur?.second ?: 0.dp
            Box(
                Modifier
                    .offset(x = center - indicatorWidth / 2)
                    .width(indicatorWidth)
                    .height(2.dp)
                    .background(DesignColors.Accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private val DiscoverInlineTabMaxWidth = 88.dp

@Composable
private fun DiscoverFeed(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    tab: DiscoverTab,
    localeEpoch: Int,
    loginEpoch: Int,
    sessions: MutableMap<String, FeedSession>,
    scope: kotlinx.coroutines.CoroutineScope,
    signedIn: Boolean,
    profileReady: Boolean,
    account: io.ymusic.dynamicengine.yt.YtAccountInfo?,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    onOpen: ((io.ymusic.dynamicengine.yt.YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    if (tab == DiscoverTab.Account) {
        // The user/library tab: spinner until the profile probe finishes, then the sign-in
        // preface when logged out or the account library when in.
        if (!profileReady) {
            CenteredBox { CircularProgressIndicator() }
        } else if (signedIn) {
            // Cached like the other tabs (cleared with them on a locale/login epoch change).
            val session = remember(tab, localeEpoch, loginEpoch) {
                sessions.getOrPut(tab.name) {
                    val log = graph.logger?.fork("AccountLibrary")
                    FeedSession(scope, log) { continuation ->
                        graph.engines.extractAccountLibrary(continuation = continuation, logger = log)
                    }.also { it.pager.refresh() }
                }
            }
            AccountLibraryScreen(
                session,
                coordinator,
                account,
                onSignOut = onSignOut,
                onSwitchAccount = onSwitchAccount,
                onOpen = onOpen,
                onDeeplink = onDeeplink,
            )
        } else {
            LoginPreface(onLogin)
        }
    } else if (!tab.hasFeed) {
        CenteredBox {
            Text(
                stringResource(Res.string.discover_needs_account, stringResource(tab.label)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // Recommended and Top tracks cost no network and grow as the user plays, so they rebuild
        // on every visit on a scope that dies with the tab instead of freezing the first read.
        val localScope = rememberCoroutineScope()
        val localFeed = tab == DiscoverTab.Recommended || tab == DiscoverTab.TopTracks
        // Cached per tab (and on the app scope): coming back to the tab — or back from
        // search / a detail page — shows the feed as it was, no reload. Re-keyed on loginEpoch so a
        // sign-in/out rebuilds the feed under the new (personalized/anonymous) identity.
        val session = remember(tab, localeEpoch, loginEpoch) {
            if (localFeed) {
                FeedSession(localScope, graph.logger?.fork(tab.name)) {
                    when (tab) {
                        DiscoverTab.TopTracks -> YtFeedPage(
                            sections = listOf(
                                YtSection(items = graph.playHistory?.youtubeTopChart().orEmpty()),
                            ),
                        )
                        else -> graph.engines.getRecommendedFeed()
                    }
                }.also { it.pager.refresh() }
            } else {
                sessions.getOrPut(tab.name) {
                    val log = graph.logger?.fork(tab.name)
                    val home: suspend (String?, Boolean) -> YtFeedPage = { continuation, refresh ->
                        val page = graph.engines.extractHomeFeed(continuation = continuation, logger = log, refresh = refresh)
                        // Home is a music app's front door: music first (per section —
                        // reordering across sections would tear headers off their items).
                        page.copy(sections = page.sections.map { it.copy(items = rankMusicFirst(it.items)) })
                    }
                    FeedSession(
                        scope,
                        log,
                        reloadFetch = if (tab == DiscoverTab.Music) null else { continuation -> home(continuation, true) },
                    ) { continuation ->
                        when (tab) {
                            // The region's music charts — one page, no continuation ever arrives.
                            DiscoverTab.Music -> graph.engines.extractTrendingMusicFeed(logger = log)
                            else -> home(continuation, false)
                        }
                    }.also { it.pager.refresh() }
                }
            }
        }
        val removeFromTopTracks: ((YtFeedItem) -> Unit)? =
            if (tab == DiscoverTab.TopTracks) {
                { item ->
                    localScope.launch {
                        graph.playHistory?.deleteYoutube(item.id)
                        session.pager.reload()
                    }
                }
            } else {
                null
            }
        CompositionLocalProvider(LocalRemoveFromTopTracks provides removeFromTopTracks) {
            FeedList(
                session.pager,
                coordinator,
                emptyMessage = stringResource(
                    when (tab) {
                        DiscoverTab.Music -> Res.string.discover_no_trending
                        DiscoverTab.Recommended -> Res.string.discover_no_recommendations
                        DiscoverTab.TopTracks -> Res.string.discover_no_top_tracks
                        else -> Res.string.discover_no_anonymous_home
                    },
                ),
                emptyAction = if (tab == DiscoverTab.Home && onSearch != null) {
                    EmptyAction(stringResource(Res.string.search_videos), onSearch)
                } else {
                    null
                },
                onOpen = onOpen,
                onPlayItem = if (tab == DiscoverTab.TopTracks) {
                    { tapped ->
                        val videos = (session.pager.state.value as? Pager.State.Ready)
                            ?.sections.orEmpty()
                            .flatMap { it.items }
                            .filter { it.kind == YtFeedItem.KIND_VIDEO }
                        coordinator.playAll(
                            videos,
                            startIndex = videos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0),
                        )
                    }
                } else {
                    null
                },
                listState = session.listState,
                onDeeplink = onDeeplink,
            )
        }
    }
}

@Composable
private fun ComingSoon(section: AppSection) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(section.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(Res.string.coming_soon, stringResource(section.label)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
