package io.ymusic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.player.LoopMode
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackState
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.Player
import io.ymusic.player.Progress
import io.ymusic.player.QueueMore
import io.ymusic.player.QueueState
import io.ymusic.player.VideoQualityOption
import io.ymusic.player.VideoQualityState
import io.ymusic.player.isEnded
import io.ymusic.player.isPlaying
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.audio_only
import io.ymusic.ui.resources.badge_live
import io.ymusic.ui.resources.captions_auto_translate
import io.ymusic.ui.resources.captions_off
import io.ymusic.ui.resources.cd_captions
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.cd_download
import io.ymusic.ui.resources.cd_drag_to_reorder
import io.ymusic.ui.resources.cd_exit_fullscreen
import io.ymusic.ui.resources.cd_fill_video
import io.ymusic.ui.resources.cd_fit_video
import io.ymusic.ui.resources.cd_forward10
import io.ymusic.ui.resources.cd_fullscreen
import io.ymusic.ui.resources.cd_lock_rotation
import io.ymusic.ui.resources.cd_more
import io.ymusic.ui.resources.cd_next
import io.ymusic.ui.resources.cd_pause
import io.ymusic.ui.resources.cd_play
import io.ymusic.ui.resources.cd_previous
import io.ymusic.ui.resources.cd_repeat_all
import io.ymusic.ui.resources.cd_repeat_off
import io.ymusic.ui.resources.cd_repeat_one
import io.ymusic.ui.resources.cd_rewind10
import io.ymusic.ui.resources.cd_scroll_to_now_playing
import io.ymusic.ui.resources.cd_share
import io.ymusic.ui.resources.cd_shuffle
import io.ymusic.ui.resources.cd_unlock_rotation
import io.ymusic.ui.resources.download_audio_track
import io.ymusic.ui.resources.download_quality
import io.ymusic.ui.resources.error_captcha
import io.ymusic.ui.resources.error_network
import io.ymusic.ui.resources.error_supervised_account
import io.ymusic.ui.resources.ic_sponsor_block
import io.ymusic.ui.resources.menu_add_to_playlist
import io.ymusic.ui.resources.menu_details_comments
import io.ymusic.ui.resources.menu_go_to_album
import io.ymusic.ui.resources.menu_go_to_artist
import io.ymusic.ui.resources.menu_reload_player
import io.ymusic.ui.resources.menu_search_lyrics
import io.ymusic.ui.resources.menu_speed_pitch
import io.ymusic.ui.resources.menu_start_radio
import io.ymusic.ui.resources.playback_add_folder_again
import io.ymusic.ui.resources.playback_botguard_failed
import io.ymusic.ui.resources.playback_failed
import io.ymusic.ui.resources.playback_file_missing
import io.ymusic.ui.resources.playback_folder_access_lost
import io.ymusic.ui.resources.playback_retry_later
import io.ymusic.ui.resources.playback_sign_in_required
import io.ymusic.ui.resources.playback_unavailable
import io.ymusic.ui.resources.playback_video_unsupported
import io.ymusic.ui.resources.queue_end
import io.ymusic.ui.resources.queue_load_failed
import io.ymusic.ui.resources.queue_load_more
import io.ymusic.ui.resources.queue_loading_more
import io.ymusic.ui.resources.queue_next
import io.ymusic.ui.resources.queue_save_as_yt_playlist
import io.ymusic.ui.resources.remove_from_queue
import io.ymusic.ui.resources.retry
import io.ymusic.ui.resources.signin_button
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sponsor_block_excluded
import io.ymusic.ui.resources.sponsor_block_included
import io.ymusic.ui.resources.up_next
import io.ymusic.ui.resources.video_quality_auto
import io.ymusic.ui.resources.yt_playlist_queue_empty
import io.ymusic.ui.sponsorblock.SponsorBlockState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.Playability
import projectcommon.error.errorCode
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal fun formatMillis(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

/** True when the portrait title/seek/transport block (list item 0) has left the viewport. */
private fun inlineControlsOffscreen(listState: LazyListState): Boolean {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return false
    if (listState.firstVisibleItemIndex >= 1) return true
    val item0 = visible.find { it.index == 0 }
    return item0 == null || item0.offset + item0.size <= 0
}

/**
 * The docked mini-player (design/final/library.html `.mini`): tap to expand to Now Playing.
 *
 * [surface] is the shared video container rendered as the 36dp art tile — only passed on platforms
 * whose surface tolerates thumbnail size ([AppGraph.surfaceScalesDown]); it keeps picture playing
 * while Now Playing is closed.
 */
@Composable
fun MiniPlayer(
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)? = null,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by coordinator.player.status.collectAsState()
    val progress by coordinator.player.progress.collectAsState()
    val queue by coordinator.player.queue.collectAsState()
    val item = queue.currentItem ?: return

    val duration = progress.durationMs
    val density = LocalDensity.current
    var colH by remember { mutableIntStateOf(0) }
    val tileH = with(density) { if (colH > 0) colH.toDp() else 48.dp }
    SwipeDownDismissBox(
        onDismiss = {
            coordinator.player.stop()
            coordinator.player.clearQueue()
        },
        modifier = modifier.fillMaxWidth(),
        commitFractionOfHeight = 1f / 3f,
        restingShadow = true,
        enabled = !desktopLayoutHole,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .height(tileH)
                    .aspectRatio(16f / 9f)
                    .then(if (desktopLayoutHole) Modifier else Modifier.background(Color.Black)),
                contentAlignment = Alignment.Center,
            ) {
                val audioOnly = showsAudioOnly(coordinator.player)
                if (surface != null) {
                    VideoHole(coordinator.player, surface, Modifier.fillMaxSize())
                }
                if (audioOnly || surface == null) {
                    AudioOnlyCover(item.displayThumbnailUrl, Modifier.fillMaxSize())
                }
                BufferingIndicator(status, size = 22.dp, strokeWidth = 2.dp)
                Box(Modifier.matchParentSize().clickable(onClick = onExpand))
            }
            Column(
                Modifier.weight(1f)
                    .background(DesignColors.SurfaceDim)
                    .onSizeChanged { colH = it.height },
            ) {
                LinearProgressIndicator(
                    progress = { if (duration > 0) progress.positionMs.toFloat() / duration else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = DesignColors.Accent,
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Butt,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.displayTitle,
                            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        artistText(item, status)?.let {
                            Text(
                                it,
                                style = TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { coordinator.player.togglePlay() }) {
                        Icon(
                            if (status.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                            stringResource(if (status.playWhenReady) Res.string.cd_pause else Res.string.cd_play),
                            tint = DesignColors.InkMuted,
                        )
                    }
                    IconButton(onClick = { coordinator.player.skipNext() }) {
                        Icon(Icons.Default.SkipNext, stringResource(Res.string.cd_next), tint = DesignColors.InkMuted)
                    }
                }
            }
        }
    }
}

/**
 * Full Now Playing (design/final/now-playing.html). Layout follows the window's orientation:
 * portrait/tablet = the top-bar · video · meta · seek · transport · queue column; landscape
 * (width > height) = the split — video fills the left with the controls overlaid on it, and a fixed
 * right pane carries the top bar and the queue. Full screen overrides both: video only, edge to
 * edge, with the auto-hiding overlay.
 */
private val LocalOpenChannel = staticCompositionLocalOf<(url: String, title: String) -> Unit> { { _, _ -> } }

private val LocalOpenLogin = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun NowPlayingScreen(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    onClose: () -> Unit,
    onPeek: (Float) -> Unit = {},
    onOpenChannel: (url: String, title: String) -> Unit = { _, _ -> },
    onLogin: () -> Unit = {},
) {
    var fullscreen by remember { mutableStateOf(false) }
    DisposableEffect(fullscreen) {
        graph.pip.videoFullscreen.value = fullscreen
        if (!fullscreen) graph.videoFillScreen.value = false
        onDispose {
            graph.pip.videoFullscreen.value = false
            graph.videoFillScreen.value = false
        }
    }
    val status by coordinator.player.status.collectAsState()
    val playWhenReady = status.playWhenReady
    val audioOnly = showsAudioOnly(coordinator.player)
    KeepScreenOn(status.state.isPlaying() && !status.state.isEnded() && !audioOnly)
    val armed = VideoOverlayNative.armed
    val overlayVisible = VideoOverlayNative.visible
    val showGen = VideoOverlayNative.showGen
    val scrubbing = VideoOverlayNative.scrubbing
    val menusOpen = VideoOverlayNative.menusOpen
    LaunchedEffect(playWhenReady, armed, overlayVisible, showGen, scrubbing, menusOpen) {
        if (armed && overlayVisible && playWhenReady && !scrubbing && menusOpen == 0) {
            delay(OVERLAY_HIDE_DELAY_MS)
            VideoOverlayNative.visible = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { VideoOverlayNative.armed = false }
    }
    val shell = @Composable {
        BoxWithConstraints {
            when {
                fullscreen -> FullscreenVideo(graph, coordinator, onExit = { fullscreen = false })
                maxWidth > maxHeight -> LandscapeNowPlaying(graph, coordinator, onClose, onFullscreen = { fullscreen = true })
                else -> PortraitNowPlaying(graph, coordinator, onClose, onFullscreen = { fullscreen = true })
            }
        }
    }
    SwipeDownDismissBox(
        onDismiss = onClose,
        modifier = Modifier.then(if (fullscreen) Modifier else Modifier.statusBarsPadding()).fillMaxSize(),
        onPeek = onPeek,
        enabled = !desktopLayoutHole,
    ) {
        // No full-screen fill: the video hole is opaque (TextureView) and the chrome paints
        // Surface itself. A wrapping Surface under the picture is 1× overdraw on every pixel.
        CompositionLocalProvider(LocalOpenChannel provides onOpenChannel, LocalOpenLogin provides onLogin) {
            Box(Modifier.fillMaxSize(), content = { shell() })
        }
    }
}

/** The item's artist, or for a YouTube video queued without one, the channel its player response names. */
private fun artistText(current: PlayableItem?, status: PlaybackStatus): String? =
    current?.displaySubtitle ?: currentDetails(current as? PlayableItem.YtMedia, status)?.channelTitle

private fun currentDetails(video: PlayableItem.YtMedia?, status: PlaybackStatus) =
    status.details?.takeIf { video != null && it.videoId == video.videoId }

/** The current item's artist line. A YouTube video's opens its channel — from the queue item, or
 *  once the player response names it. */
@Composable
private fun ArtistLine(current: PlayableItem?, status: PlaybackStatus, style: TextStyle, modifier: Modifier) {
    val text = artistText(current, status) ?: return
    val video = current as? PlayableItem.YtMedia
    val url = video?.channelUrl ?: currentDetails(video, status)?.channelUrl
    val openChannel = LocalOpenChannel.current
    Text(
        text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(if (url != null) Modifier.clickable { openChannel(url, text) } else Modifier),
    )
}

/** The video alone, edge to edge, with the auto-hiding overlay; its full-screen button exits. */
@Composable
private fun FullscreenVideo(graph: AppGraph, coordinator: PlaybackCoordinator, onExit: () -> Unit) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val current = queue.currentItem

    SideEffect { VideoOverlayNative.armed = true }
    LaunchedEffect(Unit) { VideoOverlayNative.show() }
    SyncImmersiveSystemBars(immersive = true)
    val rotationLocked by graph.videoRotationLocked
    SyncSensorRotation(locked = rotationLocked)
    val fillScreen by graph.videoFillScreen
    Box(Modifier.fillMaxSize()) {
        PlayerSurfaceHole(
            player,
            graph.playerSurface,
            current?.displayThumbnailUrl,
            Modifier.fillMaxSize(),
            fill = fillScreen,
            settings = graph.settings,
        )
        VideoOverlayHost(Modifier.fillMaxSize()) {
            BufferingIndicator(status)
            AutoHidingOverlay {
                VideoOverlayControls(
                    coordinator, status, progress, current,
                    isFullscreen = true,
                    fillScreen = fillScreen,
                    onToggleFill = { graph.videoFillScreen.value = !graph.videoFillScreen.value },
                    rotationLocked = rotationLocked,
                    onToggleRotationLock = if (rotationLockSupported) {
                        { graph.videoRotationLocked.value = !graph.videoRotationLocked.value }
                    } else {
                        null
                    },
                    onToggleFullscreen = onExit,
                )
            }
            status.error?.let { PlaybackErrorText(graph, it, VideoOverlayNative.showing) }
            PlayerCaptionOverlay(graph.player, VideoOverlayNative.showing, fill = fillScreen)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortraitNowPlaying(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val sleepMs by player.sleepTimerMs.collectAsState()
    val current = queue.currentItem

    // Everything under the video scrolls as one list; once the meta/seek/transport block (item 0)
    // scrolls away, those controls re-appear overlaid on the video and the queue header pins.
    val listState = rememberLazyListState()
    val controlsOnVideo by remember {
        derivedStateOf { inlineControlsOffscreen(listState) }
    }
    // Do not onDispose armed=false here: Compose disposes Portrait after Landscape
    // has set armed=true, which left clicks ignored (poke requires armed).
    LaunchedEffect(listState) {
        snapshotFlow { inlineControlsOffscreen(listState) }.collect { off ->
            val becameArmed = off && !VideoOverlayNative.armed
            VideoOverlayNative.armed = off
            // Only when item 0 first leaves the viewport — not on later queue scrolls.
            if (becameArmed) VideoOverlayNative.show()
        }
    }
    // Queue rows sit after the controls item and the sticky header in this list.
    val dragState = rememberReorderDragState(listState, indexOffset = 2) { from, to -> player.move(from, to) }
    val locateCurrent = rememberLocateCurrent(listState, queue.currentIndex, indexOffset = 2)

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .then(if (desktopLayoutHole) Modifier.background(Color.Transparent) else Modifier),
    ) {
        PlayerTopBar(coordinator, sleepMs, onClose)

        // Video surface — the shell's shared container; each engine shows/hides its own picture.
        // Black: HW overlay letterbox is transparent, so this fill is the bars.
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .then(if (desktopLayoutHole) Modifier else Modifier.background(Color.Black)),
        ) {
            PlayerSurfaceHole(
                player,
                graph.playerSurface,
                current?.displayThumbnailUrl,
                Modifier.fillMaxSize(),
                settings = graph.settings,
            )
            VideoOverlayHost(Modifier.fillMaxSize()) {
                // Stay composed: remounting after scroll-up missed the next scroll-down
                // (chrome window kept a stale content lambda).
                BufferingIndicator(status)
                AutoHidingOverlay {
                    VideoOverlayControls(
                        coordinator, status, progress, current,
                        onToggleFullscreen = onFullscreen,
                    )
                }
                status.error?.let { PlaybackErrorText(graph, it, VideoOverlayNative.showing) }
                val overlayOn = VideoOverlayNative.showing
                PlayerCaptionOverlay(player, overlayVisible = overlayOn)
                if (!overlayOn) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CaptionToggle(player, offTint = Color(0xFFDCDCDC))
                        ActionIcon(
                            Icons.Default.Fullscreen,
                            stringResource(Res.string.cd_fullscreen),
                            tint = Color(0xFFDCDCDC),
                            onClick = onFullscreen,
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).background(DesignColors.Surface),
            overscrollEffect = null,
        ) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            current?.displayTitle.orEmpty(),
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkStrong),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        ArtistLine(
                            current,
                            status,
                            TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                            Modifier.padding(top = 5.dp),
                        )
                    }
                    SeekBar(player, progress)
                    TransportRow(coordinator, status.playWhenReady)
                }
            }
            stickyHeader {
                // Pinned (= item 0 scrolled away, the same condition that overlays the controls):
                // the header lifts off the scrolling queue with a shadow and an elevated fill.
                val pinned = controlsOnVideo
                val headerShadow by animateDpAsState(if (pinned) 4.dp else 0.dp)
                val headerColor by animateColorAsState(
                    if (pinned) DesignColors.SurfaceElevated else DesignColors.Surface,
                )
                Column(
                    Modifier.fillMaxWidth().then(
                        if (pinned) Modifier.shadow(headerShadow).background(headerColor)
                        else Modifier,
                    ),
                ) {
                    QueueHeader(coordinator, queue.currentIndex, queue.displayTotal, queue.shuffle, queue.loopMode, locateCurrent)
                    HorizontalDivider(color = DesignColors.Divider)
                }
            }
            itemsIndexed(queue.items) { index, item ->
                QueueRow(coordinator, index, item, current = index == queue.currentIndex, dragState)
            }
            queue.more?.let { more -> item { QueueMoreRow(player, more) } }
        }
    }
}

/** Landscape split (mockup `.ls`): video left with overlaid controls; the queue pane on the right,
 *  [queuePaneWidth] wide. */
@Composable
private fun LandscapeNowPlaying(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val sleepMs by player.sleepTimerMs.collectAsState()
    val current = queue.currentItem
    SideEffect { VideoOverlayNative.armed = true }
    LaunchedEffect(Unit) { VideoOverlayNative.show() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = queuePaneWidth(maxWidth)
        val largeRows = paneWidth >= QueueLargeRowsFrom
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                PlayerSurfaceHole(
                    player,
                    graph.playerSurface,
                    current?.displayThumbnailUrl,
                    Modifier.fillMaxSize(),
                    settings = graph.settings,
                )
                VideoOverlayHost(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    BufferingIndicator(status)
                    AutoHidingOverlay {
                        VideoOverlayControls(coordinator, status, progress, current, onToggleFullscreen = onFullscreen)
                    }
                    status.error?.let { PlaybackErrorText(graph, it, VideoOverlayNative.showing) }
                    PlayerCaptionOverlay(player, VideoOverlayNative.showing)
                }
            }
            VerticalDivider(color = DesignColors.Divider)
            Column(
                Modifier.width(paneWidth).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .background(DesignColors.Surface),
            ) {
                PlayerTopBar(coordinator, sleepMs, onClose)
                val queueListState = rememberLazyListState()
                val locateCurrent = rememberLocateCurrent(queueListState, queue.currentIndex, indexOffset = 0)
                QueueHeader(coordinator, queue.currentIndex, queue.displayTotal, queue.shuffle, queue.loopMode, locateCurrent)
                HorizontalDivider(color = DesignColors.Divider)
                val dragState = rememberReorderDragState(queueListState, indexOffset = 0) { from, to -> player.move(from, to) }
                FollowCurrentQueueItem(queueListState, queue.currentIndex)
                LazyColumn(state = queueListState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(queue.items) { index, item ->
                        QueueRow(coordinator, index, item, current = index == queue.currentIndex, dragState, large = largeRows)
                    }
                    queue.more?.let { more -> item { QueueMoreRow(player, more) } }
                }
            }
        }
    }
}

/**
 * Brings the playing row back into view when the song changes: only if it left the view or sits more
 * than 3 rows below the middle, and never within 5s of the user scrolling the list themselves; it
 * lands around the middle. List indices must equal queue indices.
 */
@Composable
private fun FollowCurrentQueueItem(listState: LazyListState, currentIndex: Int) {
    var lastUserScroll by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !following) lastUserScroll = TimeSource.Monotonic.markNow()
        }
    }
    var previous by remember { mutableIntStateOf(-1) }
    LaunchedEffect(currentIndex) {
        val before = previous
        previous = currentIndex
        if (currentIndex < 0 || before < 0 || before == currentIndex) return@LaunchedEffect
        if ((lastUserScroll?.elapsedNow() ?: Duration.INFINITE) < 5.seconds) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        if (currentIndex >= last || currentIndex < first || currentIndex >= (first + last) / 2 + 3) {
            following = true
            try {
                listState.animateScrollToItem(maxOf(currentIndex - (last - first) / 2, 0))
            } finally {
                following = false
            }
        }
    }
}

/**
 * Scrolls the playing row (list index [indexOffset] + [currentIndex]) to the middle of [listState];
 * null while any part of that row is on screen.
 */
@Composable
private fun rememberLocateCurrent(listState: LazyListState, currentIndex: Int, indexOffset: Int): (() -> Unit)? {
    val target = indexOffset + currentIndex
    val onScreen by remember(listState, target) {
        derivedStateOf { currentIndex < 0 || listState.layoutInfo.visibleItemsInfo.any { it.index == target } }
    }
    val scope = rememberCoroutineScope()
    if (onScreen) return null
    return {
        scope.launch {
            val rows = listState.layoutInfo.visibleItemsInfo.size
            listState.animateScrollToItem(maxOf(target - rows / 2, 0))
        }
    }
}

/** The landscape queue pane: 30% of the window, kept between 320dp (phones) and 480dp (desktops). */
private fun queuePaneWidth(windowWidth: Dp): Dp = (windowWidth * 0.3f).coerceIn(320.dp, 480.dp)

/** Pane width from which queue rows take the larger thumb and text. */
private val QueueLargeRowsFrom = 380.dp

/**
 * Hosts video-overlay [content] with player-style auto-hide: it fades out after a few seconds of
 * playback, and a tap/click on the video toggles it. Dragging the seek bar holds it visible.
 * While paused nothing auto-hides.
 */
@Composable
private fun BoxScope.AutoHidingOverlay(
    content: @Composable BoxScope.() -> Unit,
) {
    val armed = VideoOverlayNative.armed
    val visible = VideoOverlayNative.visible
    val show = armed && visible
    if (armed) {
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { VideoOverlayNative.poke() }
                },
        )
    }
    if (show) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

private const val OVERLAY_HIDE_DELAY_MS = 3_000L

private fun PlaybackStatus.isBufferingOrLoading(): Boolean = when (val s = state) {
    is PlaybackState.Prepared -> s.buffering
    is PlaybackState.Uninitialized -> s.isInitializing
    PlaybackState.Idle -> playWhenReady
    is PlaybackState.Error -> false
}

/** Stays on the video while overlay chrome auto-hides; transport play/pause stays tappable. */
@Composable
private fun BoxScope.BufferingIndicator(
    status: PlaybackStatus,
    size: Dp = 72.dp,
    strokeWidth: Dp = 3.dp,
) {
    if (!status.isBufferingOrLoading()) return
    CircularProgressIndicator(
        Modifier.align(Alignment.Center).size(size),
        color = Color.White,
        strokeWidth = strokeWidth,
    )
}

/** The controls drawn over the video: landscape always, portrait once the inline controls scroll away. */
@Composable
private fun BoxScope.VideoOverlayControls(
    coordinator: PlaybackCoordinator,
    status: PlaybackStatus,
    progress: io.ymusic.player.Progress,
    current: PlayableItem?,
    isFullscreen: Boolean = false,
    fillScreen: Boolean = false,
    onToggleFill: (() -> Unit)? = null,
    rotationLocked: Boolean = false,
    onToggleRotationLock: (() -> Unit)? = null,
    onToggleFullscreen: () -> Unit,
) {
    val player = coordinator.player
    val scale = LocalVideoOverlayScale.current
    val iconSize = 24.dp * scale
    val hitSize = 40.dp * scale
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.24f)))

    Column(
        Modifier.align(Alignment.TopStart)
            .then(if (isFullscreen) Modifier.keepBelowStatusBar() else Modifier)
            .padding(start = 16.dp * scale, end = 16.dp * scale, top = 11.dp * scale),
    ) {
        Text(
            current?.displayTitle.orEmpty(),
            style = TextStyle(fontSize = 13.5.sp * scale, fontWeight = FontWeight.Bold, color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ArtistLine(
            current,
            status,
            TextStyle(fontSize = 11.sp * scale, color = Color(0xFFDADADA)),
            Modifier.padding(top = 3.dp * scale),
        )
    }

    Box(Modifier.align(Alignment.Center)) {
        TransportRow(
            coordinator,
            status.playWhenReady,
            iconTint = Color.White,
            playBackground = Color.Black.copy(alpha = 0.42f),
            playForeground = Color.White,
            playSize = 58.dp * scale,
            scale = scale,
        )
    }

    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val labelProgress = scrubMs?.let { progress.copy(positionMs = it) } ?: progress
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 11.dp * scale, end = 11.dp * scale, bottom = 8.dp * scale),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val overlayStyle = TextStyle(fontSize = 10.sp * scale, fontWeight = FontWeight.Bold, color = Color(0xFFEEEEEE))
            labelProgress.progressLabel?.let {
                Text(it, style = overlayStyle)
                Text(" / ", style = overlayStyle)
            }
            DurationLabel(player, progress, overlayStyle)
            Box(Modifier.weight(1f))
            CaptionToggle(player, offTint = Color(0xFFEEEEEE), iconSize = iconSize, hitSize = hitSize)
            if (onToggleRotationLock != null) {
                ActionIcon(
                    if (rotationLocked) Icons.Default.ScreenLockRotation else Icons.Default.ScreenRotation,
                    stringResource(if (rotationLocked) Res.string.cd_unlock_rotation else Res.string.cd_lock_rotation),
                    iconSize = iconSize,
                    hitSize = hitSize,
                    tint = Color(0xFFEEEEEE),
                    onClick = onToggleRotationLock,
                )
            }
            if (onToggleFill != null) {
                ActionIcon(
                    if (fillScreen) Icons.Default.FitScreen else Icons.Default.CropFree,
                    stringResource(if (fillScreen) Res.string.cd_fit_video else Res.string.cd_fill_video),
                    iconSize = iconSize,
                    hitSize = hitSize,
                    tint = Color(0xFFEEEEEE),
                    onClick = onToggleFill,
                )
            }
            ActionIcon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                stringResource(if (isFullscreen) Res.string.cd_exit_fullscreen else Res.string.cd_fullscreen),
                iconSize = iconSize,
                hitSize = hitSize,
                tint = Color(0xFFEEEEEE),
                onClick = onToggleFullscreen,
            )
        }
        SeekSlider(
            player,
            progress,
            trackColor = Color.White.copy(alpha = 0.32f),
            bufferedColor = Color.White.copy(alpha = 0.55f),
            onScrubMs = { scrubMs = it },
        )
    }
}

/** Height of the video controls' bottom strip at 1× overlay scale; captions and errors stay above it while it shows. */
private val VideoControlsStrip = 72.dp

/** How much the video overlay chrome scales: 1× up to a 400dp short side, growing with the video area to 1.3×. */
private val LocalVideoOverlayScale = compositionLocalOf { 1f }

/** The box over the video picture: sets [LocalVideoOverlayScale] from its own size for the overlay, captions and errors. */
@Composable
private fun VideoOverlayHost(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val scale = (minOf(maxWidth, maxHeight) / 400.dp).coerceIn(1f, 1.3f)
        CompositionLocalProvider(LocalVideoOverlayScale provides scale) { content() }
    }
}

/**
 * Captions centered on the picture (the video's contain-fit rect, as [VideoHole] lays it out): in the bar
 * below it when that bar fits two rows clear of the controls strip, otherwise over the picture's bottom.
 */
@Composable
private fun BoxScope.PlayerCaptionOverlay(
    player: io.ymusic.player.Player,
    overlayVisible: Boolean,
    fill: Boolean = false,
) {
    val lines by player.captionLines.collectAsState()
    if (lines.isEmpty()) return
    val status by player.status.collectAsState()
    val audioOnly = showsAudioOnly(player)
    val strip = VideoControlsStrip * LocalVideoOverlayScale.current
    val controlsInset by animateDpAsState(if (overlayVisible) strip else 12.dp)
    val twoRows = captionTwoRowHeight()
    BoxWithConstraints(Modifier.matchParentSize()) {
        val ratio = status.videoSize?.aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
        val pictureWidth: Dp
        val pictureHeight: Dp
        if (fill || audioOnly || maxWidth / maxHeight <= ratio) {
            pictureWidth = maxWidth
            pictureHeight = if (fill || audioOnly) maxHeight else maxWidth / ratio
        } else {
            pictureWidth = maxHeight * ratio
            pictureHeight = maxHeight
        }
        val bar = (maxHeight - pictureHeight) / 2
        if (bar >= twoRows + strip + DesignSpacing.Space8) {
            CaptionOverlay(
                lines,
                Modifier.align(Alignment.TopCenter)
                    .padding(top = bar + pictureHeight + DesignSpacing.Space8)
                    .width(pictureWidth),
            )
        } else {
            CaptionOverlay(
                lines,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = maxOf(bar + 12.dp, controlsInset))
                    .width(pictureWidth),
            )
        }
    }
}

/**
 * The video CC control: hidden when the source has no caption tracks. Tapping opens a menu of
 * Off + every available language (checkmark on the active one); the glyph is accent-filled while
 * captions are on. Picking a language turns captions on and selects it; Off hides them.
 */
@Composable
private fun CaptionToggle(player: io.ymusic.player.Player, offTint: Color, iconSize: Dp = 24.dp, hitSize: Dp = 40.dp) {
    val captions by player.captions.collectAsState()
    val config by player.config.collectAsState()
    if (captions.tracks.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var translateOpen by remember { mutableStateOf(false) }
    HoldVideoOverlay(open || translateOpen)
    val (translations, tracks) = captions.tracks.partition { it.kind == AUTO_TRANSLATE_KIND }
    fun isOn(track: io.ymusic.player.CaptionTrack) = config.captionVisible &&
        (track.id == captions.selectedLanguage || track.languageCode == captions.selectedLanguage)
    fun pick(track: io.ymusic.player.CaptionTrack) {
        player.selectCaption(track.id)
        player.setCaptionVisible(true)
        open = false
        translateOpen = false
    }
    Box {
        ActionIcon(
            if (config.captionVisible) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaptionOff,
            stringResource(Res.string.cd_captions),
            iconSize = iconSize,
            hitSize = hitSize,
            tint = if (config.captionVisible) DesignColors.Accent else offTint,
            onClick = { open = true },
        )
        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                val off = !config.captionVisible
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.captions_off)) },
                    onClick = { player.setCaptionVisible(false); open = false },
                    modifier = Modifier.menuChoice(off),
                    trailingIcon = selectedCheck(off),
                )
                tracks.forEach { track ->
                    DropdownMenuItem(
                        text = { Text(track.displayName) },
                        onClick = { pick(track) },
                        modifier = Modifier.menuChoice(isOn(track)),
                        trailingIcon = selectedCheck(isOn(track)),
                    )
                }
                if (translations.isNotEmpty()) {
                    val label = stringResource(Res.string.captions_auto_translate)
                    val current = translations.firstOrNull { isOn(it) }
                    DropdownMenuItem(
                        text = { Text(if (current != null) "$label · ${current.displayName}" else "$label ›") },
                        onClick = {
                            open = false
                            translateOpen = true
                        },
                        modifier = Modifier.menuChoice(current != null),
                        trailingIcon = selectedCheck(current != null),
                    )
                }
            }
        }
        // ~150 languages: a lazy list, so opening it does not compose every row.
        if (translateOpen) {
            DropdownMenu(expanded = true, onDismissRequest = { translateOpen = false }) {
                LazyColumn(Modifier.size(width = 240.dp, height = 360.dp)) {
                    items(translations, key = { it.id }) { track ->
                        DropdownMenuItem(
                            text = { Text(track.displayName) },
                            onClick = { pick(track) },
                            modifier = Modifier.menuChoice(isOn(track)),
                            trailingIcon = selectedCheck(isOn(track)),
                        )
                    }
                }
            }
        }
    }
}

/** Engine track kind for YouTube auto-translate languages (clones of a source track, one per language). */
private const val AUTO_TRANSLATE_KIND = "asrt"

/** A playback failure for the user: a known category, the text the source gave for it (YouTube's own words
 *  for a playability verdict), else our message for the verdict, else "Playback failed (code)". */
internal fun playerErrorText(error: io.ymusic.player.PlayerError): UiText = when {
    error.code == CAPTCHA_CODE -> UiText.Resource(Res.string.error_captcha)
    error.code == "network" -> UiText.Resource(Res.string.error_network)
    error.code == SUPERVISED_ACCOUNT_CODE -> UiText.Resource(Res.string.error_supervised_account)
    error.code == io.ymusic.player.PlayerError.FILE_MISSING -> UiText.Resource(Res.string.playback_file_missing)
    error.code == io.ymusic.player.PlayerError.FOLDER_ACCESS_LOST -> UiText.Resource(Res.string.playback_folder_access_lost)
    error.code == io.ymusic.player.PlayerError.VIDEO_DECODER_FAILED -> UiText.Resource(Res.string.playback_video_unsupported)
    error.code == io.ymusic.player.PlayerError.PO_TOKEN_REJECTED -> UiText.Resource(Res.string.playback_botguard_failed)
    else -> error.userMessage?.let(UiText::Plain) ?: when (error.playability()) {
        Playability.SignInRequired -> UiText.Resource(Res.string.playback_sign_in_required)
        Playability.Unavailable -> UiText.Resource(Res.string.playback_unavailable)
        Playability.RetryLater -> UiText.Resource(Res.string.playback_retry_later)
        null -> UiText.Resource(Res.string.playback_failed, error.code)
    }
}

/** [playerErrorText] resolved off composition, for surfaces outside the app UI (Android Auto). */
suspend fun playerErrorMessage(error: io.ymusic.player.PlayerError): String = playerErrorText(error).load()

/**
 * The playback error on a dark panel at the bottom of the picture, above the overlay chrome so its actions
 * stay tappable, and lifted over the controls strip while that shows: up to two lines of message (a playability verdict, captcha or decoder verdict, else
 * "Failed (code)"), then its actions as text.
 */
@Composable
private fun BoxScope.PlaybackErrorText(
    graph: AppGraph,
    error: io.ymusic.player.PlayerError,
    overlayVisible: Boolean,
) {
    val player = graph.player
    val controlsInset by animateDpAsState(if (overlayVisible) VideoControlsStrip * LocalVideoOverlayScale.current else 44.dp)
    val captcha = error.code == CAPTCHA_CODE
    val signIn = error.playability() == Playability.SignInRequired
    val resolver = LocalCaptchaResolver.current
    val openLogin = LocalOpenLogin.current
    val scanController = LocalScanController.current
    val scanFolder = graph.scanLibraryFolder
    val actions = buildList<Pair<String, () -> Unit>> {
        if (signIn) add(stringResource(Res.string.signin_button) to openLogin)
        if (error.code == io.ymusic.player.PlayerError.FOLDER_ACCESS_LOST && scanController != null && scanFolder != null) {
            add(stringResource(Res.string.playback_add_folder_again) to { scanController.start(scanFolder) })
        }
        if (captcha && resolver != null) add(stringResource(resolver.label) to { resolver.resolve { player.play() } })
        // play() reloads the current item after a retryable error. A stream the device cannot decode
        // would fail again, so that retry plays audio only; picking a quality turns video back on.
        if (error.canRetry) {
            add(
                stringResource(Res.string.retry) to {
                    if (error.code == io.ymusic.player.PlayerError.VIDEO_DECODER_FAILED) player.setAudioOnly(true)
                    player.play()
                },
            )
        }
    }
    Column(
        Modifier.align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = controlsInset)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            playerErrorText(error).resolve(),
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (actions.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { index, (label, onClick) ->
                    if (index > 0) Text("·", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    Text(
                        label,
                        color = DesignColors.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** chevron-down · [right:] download · sponsorblock · sleep timer · quality chip · ⋮ (reload + tracks). */
@Composable
private fun PlayerTopBar(coordinator: PlaybackCoordinator, sleepMs: Long?, onClose: () -> Unit) {
    val player = coordinator.player
    val quality by player.videoQuality.collectAsState()
    val audioOnlyLabel = stringResource(Res.string.audio_only)
    val autoQualityLabel = stringResource(Res.string.video_quality_auto)
    fun qualityLabel(option: VideoQualityOption): String = when (option.quality) {
        VideoQualityState.AUDIO_ONLY -> audioOnlyLabel
        VideoQualityState.AUTO -> autoQualityLabel
        else -> option.label
    }

    val queue by player.queue.collectAsState()
    val downloadLauncher = LocalDownloadLauncher.current
    val downloadable = coordinator.downloadable(queue.currentItem)
    val videoId = (queue.currentItem as? PlayableItem.YtMedia)?.videoId
    val sponsorBlock = coordinator.graph.sponsorBlock
    val sbChanges by sponsorBlock.changes.collectAsState()
    val sbEnabled by remember(coordinator.graph) {
        coordinator.graph.settings.flow(AppSettings.sponsorBlockEnabled)
    }.collectAsState()
    var sbState by remember { mutableStateOf(SponsorBlockState.Unavailable) }
    LaunchedEffect(videoId, sbChanges, sbEnabled) {
        sbState = if (videoId == null) {
            SponsorBlockState.Unavailable
        } else {
            sponsorBlock.load(videoId).state
        }
    }
    val scope = rememberCoroutineScope()
    val showSnackbar = LocalSnackbar.current
    val includedMsg = stringResource(Res.string.sponsor_block_included)
    val excludedMsg = stringResource(Res.string.sponsor_block_excluded)

    Row(
        // Paddings/gaps shave the ActionIcon hit-inset (8dp) so the glyphs keep the bar's metrics.
        Modifier.fillMaxWidth()
            .background(DesignColors.Surface)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.cd_close), onClick = onClose)
        Box(Modifier.weight(1f))
        // Only for a downloadable item (a non-live YouTube video) where there is a download service.
        if (downloadLauncher != null && downloadable != null) {
            ActionIcon(
                Icons.Outlined.FileDownload,
                stringResource(Res.string.cd_download),
                onClick = { downloadLauncher.launch(downloadable) },
            )
        }
        if (sbState != SponsorBlockState.Unavailable) {
            val included = sbState == SponsorBlockState.Available
            ActionIcon(
                painterResource(Res.drawable.ic_sponsor_block),
                if (included) includedMsg else excludedMsg,
                tint = if (included) DesignColors.Accent else DesignColors.InkMuted,
                onClick = {
                    val id = videoId ?: return@ActionIcon
                    showSnackbar(if (included) excludedMsg else includedMsg)
                    scope.launch {
                        if (included) sponsorBlock.addToWhitelist(id) else sponsorBlock.removeFromWhitelist(id)
                    }
                },
            )
        }
        val sleepAfterCurrent by player.sleepAfterCurrent.collectAsState()
        val settings = coordinator.graph.settings
        val lastSleepMinutes by remember(settings) { settings.flow(AppSettings.sleepTimerMinutes) }.collectAsState()
        SleepTimerButton(
            sleepMs = sleepMs,
            afterCurrent = sleepAfterCurrent,
            lastMinutes = lastSleepMinutes,
        ) { choice ->
            when (choice) {
                is SleepChoice.Timer -> {
                    player.setSleepTimer(choice.minutes * 60_000L)
                    scope.launch { settings.put(AppSettings.sleepTimerMinutes, choice.minutes) }
                }
                SleepChoice.EndOfTrack -> player.setSleepAfterCurrent(true)
                SleepChoice.Off -> player.setSleepTimer(null)
            }
        }
        if (quality.options.isNotEmpty()) {
            QualityChip(
                label = quality.options.firstOrNull { it.quality == quality.actualQuality }?.let(::qualityLabel)
                    ?: when (quality.actualQuality) {
                        VideoQualityState.AUTO -> autoQualityLabel
                        VideoQualityState.AUDIO_ONLY -> audioOnlyLabel
                        else -> quality.actualQuality
                    },
                options = quality.options.map { it.quality to qualityLabel(it) },
                selectedId = quality.preferredQuality,
                onSelect = { player.selectVideoQuality(it) },
            )
        }
        PlayerMoreMenu(coordinator)
    }
}

@Composable
internal fun QualityChip(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String? = null,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HoldVideoOverlay(open)
    val qualityDescription = stringResource(Res.string.download_quality)
    Box {
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkMuted),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(6.dp))
                .a11yActionLabel(qualityDescription)
                .semantics {
                    stateDescription = label
                    role = Role.DropdownList
                }
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                val selected = id == selectedId
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            color = if (selected) DesignColors.Accent else DesignColors.Ink,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = selectedCheck(selected),
                    onClick = { onSelect(id); open = false },
                    modifier = Modifier.menuChoice(selected),
                )
            }
        }
    }
}

private fun Modifier.menuChoice(selected: Boolean) = semantics {
    this.selected = selected
    role = Role.RadioButton
}

private fun selectedCheck(selected: Boolean): (@Composable () -> Unit)? = if (selected) {
    { Icon(Icons.Default.Check, contentDescription = null, tint = DesignColors.Accent) }
} else {
    null
}

/** Material's menu-item width cap; fixed so labels wrap instead of setting the menu's width. */
private val MoreMenuWidth = 280.dp

@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    text: String,
    value: String? = null,
    submenu: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = DesignColors.InkMuted) },
        trailingIcon = if (value == null && !submenu) {
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value != null) {
                        Text(
                            value,
                            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp),
                        )
                    }
                    if (submenu) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            Modifier.size(18.dp),
                            tint = DesignColors.InkSecondary,
                        )
                    }
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun MoreMenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = DesignSpacing.Space4), color = DesignColors.Divider)
}

/** The ⋮ menu, grouped: this video + queue · sound · player. Captions live on the video CC control;
 *  audio-only is a quality-chip option, not a toggle here. */
@Composable
private fun PlayerMoreMenu(coordinator: PlaybackCoordinator) {
    val player = coordinator.player
    val audioTracks by player.audioTracks.collectAsState()
    var open by remember { mutableStateOf(false) }
    var playbackOpen by remember { mutableStateOf(false) }
    var eqOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val current = player.queue.collectAsState().value.currentItem
    val video = current as? PlayableItem.YtMedia
    val shareVideoId by produceState<String?>(null, current) {
        value = current?.let { queueVideoIds(coordinator.graph, listOf(it)).firstOrNull() }
    }
    val uriHandler = LocalUriHandler.current
    val ytPlaylists = LocalYtPlaylistActions.current
    val shareYoutube = LocalShareYoutube.current
    val snackbar = LocalSnackbar.current
    val noVideos = stringResource(Res.string.yt_playlist_queue_empty)
    val scope = rememberCoroutineScope()
    var tracksOpen by remember { mutableStateOf(false) }
    val config by player.config.collectAsState()
    HoldVideoOverlay(open || tracksOpen || playbackOpen || eqOpen || detailsOpen)

    Box {
        ActionIcon(Icons.Default.MoreVert, stringResource(Res.string.cd_more), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(MoreMenuWidth)) {
            if (video != null) {
                MoreMenuItem(Icons.Outlined.Info, stringResource(Res.string.menu_details_comments)) {
                    detailsOpen = true
                    open = false
                }
                if (ytPlaylists != null) {
                    MoreMenuItem(Icons.AutoMirrored.Outlined.PlaylistAdd, stringResource(Res.string.menu_add_to_playlist)) {
                        ytPlaylists.addToPlaylist(video.videoId)
                        open = false
                    }
                }
            }
            val shareId = shareVideoId
            if (shareYoutube != null && shareId != null) {
                MoreMenuItem(Icons.Outlined.Share, stringResource(Res.string.cd_share)) {
                    shareYoutube(YtFeedItem.KIND_VIDEO, shareId)
                    open = false
                }
            }
            if (ytPlaylists != null) {
                MoreMenuItem(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(Res.string.queue_save_as_yt_playlist)) {
                    open = false
                    scope.launch {
                        val videoIds = queueVideoIds(coordinator.graph, player.queue.value.items)
                        if (videoIds.isEmpty()) snackbar(noVideos) else ytPlaylists.saveAsPlaylist(videoIds)
                    }
                }
            }
            if (current != null) {
                MoreMenuItem(Icons.Outlined.Lyrics, stringResource(Res.string.menu_search_lyrics)) {
                    uriHandler.openUri(lyricsSearchUrl(current))
                    open = false
                }
            }
            if (ytPlaylists != null || current != null) MoreMenuDivider()
            MoreMenuItem(
                Icons.Outlined.Speed,
                stringResource(Res.string.menu_speed_pitch),
                value = "${trimFloat(config.speed)}×",
            ) {
                playbackOpen = true
                open = false
            }
            MoreMenuItem(
                Icons.Outlined.Equalizer,
                stringResource(Res.string.sound_eq),
                value = if (config.inAppAudioFx) stringResource(eqPresetLabel(config.eqBands)) else null,
            ) {
                eqOpen = true
                open = false
            }
            if (audioTracks.tracks.size > 1) {
                MoreMenuItem(
                    Icons.Outlined.GraphicEq,
                    stringResource(Res.string.download_audio_track),
                    value = audioTracks.tracks.firstOrNull { it.id == audioTracks.selectedId }?.displayName,
                    submenu = true,
                ) {
                    tracksOpen = true
                    open = false
                }
            }
            MoreMenuDivider()
            MoreMenuItem(Icons.Outlined.Refresh, stringResource(Res.string.menu_reload_player)) {
                player.reload()
                open = false
            }
        }
        DropdownMenu(expanded = tracksOpen, onDismissRequest = { tracksOpen = false }, modifier = Modifier.width(MoreMenuWidth)) {
            audioTracks.tracks.forEach { track ->
                val selected = track.id == audioTracks.selectedId
                DropdownMenuItem(
                    text = {
                        Text(
                            track.displayName,
                            color = if (selected) DesignColors.Accent else DesignColors.Ink,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = selectedCheck(selected),
                    onClick = { player.selectAudioTrack(track.id); tracksOpen = false },
                    modifier = Modifier.menuChoice(selected),
                )
            }
        }
    }
    if (playbackOpen) {
        PlaybackFxSheet(player, onDismiss = { playbackOpen = false })
    }
    if (detailsOpen && video != null) {
        val openChannel = LocalOpenChannel.current
        VideoDetailsDialog(
            coordinator.graph,
            video.videoId,
            onOpenChannel = { url, title -> detailsOpen = false; openChannel(url, title) },
            onDismiss = { detailsOpen = false },
        )
    }
    if (eqOpen) {
        EqualizerFxSheet(coordinator.graph, onDismiss = { eqOpen = false })
    }
}

/** A YouTube item's artist is the uploading channel, which only muddies the search. */
private fun lyricsSearchUrl(item: PlayableItem): String {
    val artist = (item as? PlayableItem.LocalMedia)?.artist
    val query = listOfNotNull(artist, item.displayTitle, "lyrics").joinToString(" ")
    return "https://www.google.com/search?q=${percentEncode(query)}"
}

/** 3dp track, orange fill, 13dp orange thumb; the m:ss / h:mm:ss labels underneath. */
@Composable
private fun SeekBar(player: io.ymusic.player.Player, progress: Progress) {
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val labelProgress = scrubMs?.let { progress.copy(positionMs = it) } ?: progress
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp)) {
        SeekSlider(player, progress, trackColor = DesignColors.OutlineSoft, onScrubMs = { scrubMs = it })
        MediaLtr {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val timeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary)
                Text(labelProgress.progressLabel.orEmpty(), style = timeStyle)
                Spacer(Modifier.weight(1f))
                DurationLabel(player, progress, timeStyle)
            }
        }
    }
}

@Composable
private fun DurationLabel(
    player: io.ymusic.player.Player,
    progress: Progress,
    style: TextStyle,
) {
    val text = if (progress.durationIsLive) {
        stringResource(Res.string.badge_live)
    } else {
        progress.durationLabel ?: "--:--"
    }
    Text(
        text,
        style = if (progress.highlightDuration) style.copy(color = DesignColors.Accent) else style,
        modifier = if (progress.durationIsLive && progress.seekable) {
            Modifier.clickable { player.seekTo(progress.durationMs.coerceAtLeast(0L)) }
        } else {
            Modifier
        },
    )
}

/** The bare slider (3dp [trackColor] track, orange fill + thumb) — SeekBar's core, reused by the
 *  landscape video overlay. */
@Composable
private fun SeekSlider(
    player: io.ymusic.player.Player,
    progress: Progress,
    trackColor: Color,
    bufferedColor: Color = DesignColors.Accent.copy(alpha = 0.35f),
    onScrubMs: ((Long?) -> Unit)? = null,
) {
    val durationMs = progress.durationMs
    val skipSegments by player.skipSegments.collectAsState()
    val queue by player.queue.collectAsState()
    val streamed = queue.currentItem !is PlayableItem.LocalMedia
    MediaLtr {
        SlimSeekBar(
            fraction = if (durationMs > 0) progress.positionMs.toFloat() / durationMs else 0f,
            bufferedFraction = if (streamed && durationMs > 0) progress.bufferedPositionMs.toFloat() / durationMs else 0f,
            bufferedColor = bufferedColor,
            enabled = progress.seekable && durationMs > 0,
            onSeek = { f -> player.seekTo((f * durationMs).toLong()) },
            onScrub = { f -> onScrubMs?.invoke(if (f == null || durationMs <= 0) null else (f * durationMs).toLong()) },
            trackColor = trackColor,
            skipFractions = skipSegmentFractions(skipSegments, durationMs),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** rewind · prev · [play circle] · next · forward. Dark-on-white by default; the video overlay
 *  passes white glyphs on a translucent play circle. */
@Composable
private fun TransportRow(
    coordinator: PlaybackCoordinator,
    playing: Boolean,
    iconTint: Color = DesignColors.InkStrong,
    playBackground: Color = DesignColors.InkStrong,
    playForeground: Color = DesignColors.OnInk,
    playSize: Dp = 64.dp,
    scale: Float = 1f,
) {
    val player = coordinator.player
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        MediaLtr { TransportButtons(player, playing, iconTint, playBackground, playForeground, playSize, scale) }
    }
}

/** [this] at an even pixel count, so nested centred boxes split their difference exactly — an odd
 *  difference rounds half a pixel down at every level. */
@Composable
private fun Dp.evenPx(): Dp {
    val density = LocalDensity.current.density
    return ((value * density / 2).roundToInt() * 2 / density).dp
}

/** Media controls and the seek bar stay left-to-right in RTL locales: time runs left to right. */
@Composable
private fun MediaLtr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@Composable
private fun TransportButtons(
    player: Player,
    playing: Boolean,
    iconTint: Color,
    playBackground: Color,
    playForeground: Color,
    playSize: Dp,
    scale: Float,
) {
    val iconSize = (24.dp * scale).evenPx()
    val hitSize = (40.dp * scale).evenPx()
    val circle = playSize.evenPx()
    Row(
        // SpaceEvenly inside a capped width: the gaps grow with the window (≈ the mockup's 26dp
        // at phone width) up to the cap, instead of a fixed cluster.
        Modifier
            .widthIn(max = 480.dp * scale)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(
            Icons.Default.FastRewind,
            stringResource(Res.string.cd_rewind10),
            iconSize = iconSize,
            hitSize = hitSize,
            tint = iconTint,
            onClick = { player.seekTo((player.progress.value.positionMs - 10_000).coerceAtLeast(0)) },
        )
        ActionIcon(
            Icons.Default.SkipPrevious,
            stringResource(Res.string.cd_previous),
            iconSize = iconSize,
            hitSize = hitSize,
            tint = iconTint,
            onClick = { player.skipPrev() },
        )
        Box(
            Modifier.size(circle).clip(CircleShape).background(playBackground).clickable(role = Role.Button) { player.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (playing) Res.string.cd_pause else Res.string.cd_play),
                Modifier.size((26.dp * scale).evenPx()),
                tint = playForeground,
            )
        }
        ActionIcon(
            Icons.Default.SkipNext,
            stringResource(Res.string.cd_next),
            iconSize = iconSize,
            hitSize = hitSize,
            tint = iconTint,
            onClick = { player.skipNext() },
        )
        ActionIcon(
            Icons.Default.FastForward,
            stringResource(Res.string.cd_forward10),
            iconSize = iconSize,
            hitSize = hitSize,
            tint = iconTint,
            onClick = { player.seekTo(player.progress.value.positionMs + 10_000) },
        )
    }
}

/** "Up next" · "n / total" · [right:] repeat · shuffle. */
/** The playlist's length while its queue is still loading, else the queue's own size. */
private val QueueState.displayTotal: Int
    get() = more?.totalCount?.coerceAtLeast(items.size) ?: items.size

/** The end of a growing queue: Load more, its progress, a failure with Retry, or the list's end. */
@Composable
private fun QueueMoreRow(player: Player, more: QueueMore) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space16),
        contentAlignment = Alignment.Center,
    ) {
        val noteStyle = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary)
        val error = more.error
        when {
            more.loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.queue_loading_more), style = noteStyle)
            }
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
            ) {
                Text(stringResource(Res.string.queue_load_failed), style = noteStyle)
                Text(
                    errorMessage(error.errorCode() ?: "E60063"),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkFaint),
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.padding(top = DesignSpacing.Space4)) {
                    PlayPill(Icons.Default.Refresh, stringResource(Res.string.retry), filled = false, onClick = player::loadMore)
                }
            }
            more.hasMore -> PlayPill(
                Icons.Default.ExpandMore,
                stringResource(Res.string.queue_load_more),
                filled = false,
                onClick = player::loadMore,
            )
            else -> Text(stringResource(Res.string.queue_end), style = noteStyle)
        }
    }
}

@Composable
private fun QueueHeader(
    coordinator: PlaybackCoordinator,
    currentIndex: Int,
    total: Int,
    shuffle: Boolean,
    loopMode: LoopMode,
    onLocateCurrent: (() -> Unit)?,
) {
    val player = coordinator.player
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.up_next),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
        )
        Text(
            "${currentIndex + 1} / $total",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkFaint),
        )
        if (onLocateCurrent != null) {
            ActionIcon(
                Icons.Default.GpsFixed,
                stringResource(Res.string.cd_scroll_to_now_playing),
                tint = DesignColors.InkSoft,
                onClick = onLocateCurrent,
            )
        }
        Box(Modifier.weight(1f))
        ActionIcon(
            if (loopMode == LoopMode.One) Icons.Default.RepeatOne else Icons.Default.Repeat,
            stringResource(
                when (loopMode) {
                    LoopMode.None -> Res.string.cd_repeat_off
                    LoopMode.All -> Res.string.cd_repeat_all
                    LoopMode.One -> Res.string.cd_repeat_one
                },
            ),
            tint = if (loopMode != LoopMode.None) DesignColors.Accent else DesignColors.InkSoft,
            onClick = {
                player.setLoopMode(
                    when (loopMode) {
                        LoopMode.None -> LoopMode.All
                        LoopMode.All -> LoopMode.One
                        LoopMode.One -> LoopMode.None
                    },
                )
            },
        )
        ToggleActionIcon(
            Icons.Default.Shuffle,
            stringResource(Res.string.cd_shuffle),
            checked = shuffle,
            tint = if (shuffle) DesignColors.Accent else DesignColors.InkSoft,
            onCheckedChange = { player.setShuffle(it) },
        )
    }
}

/** The queue's YouTube video ids in order, deduplicated: YouTube items, plus local files the library
 *  knows came from a YouTube video (downloads). */
private suspend fun queueVideoIds(graph: AppGraph, items: List<PlayableItem>): List<String> {
    val store = graph.mediaStore
    return items.mapNotNull { item ->
        when (item) {
            is PlayableItem.YtMedia -> item.videoId
            is PlayableItem.LocalMedia -> item.mediaId?.let { store?.findItem(it)?.youtubeVid }
        }
    }.distinct()
}

@Composable
private fun QueueRow(
    coordinator: PlaybackCoordinator,
    index: Int,
    item: io.ymusic.player.PlayableItem,
    current: Boolean,
    dragState: ReorderDragState,
    large: Boolean = false,
) {
    val dragging = dragState.draggingIndex == index
    val swipeState = rememberSwipeToDismissBoxState()
    val currentIndex by rememberUpdatedState(index)
    // Rows are keyed by position: once the swiped row is gone its slot shows the next item, so the
    // state must be back at Settled in the same frame.
    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
            coordinator.player.removeAt(currentIndex)
            swipeState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        swipeState,
        backgroundContent = {
            if (swipeState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(
                    Modifier.fillMaxSize().background(DesignColors.SurfaceDim).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(20.dp), tint = DesignColors.InkSecondary)
                }
            }
        },
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) dragState.dragOffset else 0f },
        enableDismissFromEndToStart = false,
        gesturesEnabled = dragState.draggingIndex == null,
    ) {
        val swiping = swipeState.dismissDirection != SwipeToDismissBoxValue.Settled
        QueueRowContent(coordinator, index, item, current, dragState, large, dragging, swiping)
    }
}

@Composable
private fun QueueRowContent(
    coordinator: PlaybackCoordinator,
    index: Int,
    item: io.ymusic.player.PlayableItem,
    current: Boolean,
    dragState: ReorderDragState,
    large: Boolean,
    dragging: Boolean,
    swiping: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val sub = listOfNotNull(item.displayDuration, item.displaySubtitle).joinToString(" · ")
    val rowLabel = if (sub.isNotEmpty()) "${item.displayTitle}, $sub" else item.displayTitle
    val playLabel = stringResource(Res.string.cd_play)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                when {
                    dragging -> Modifier.shadow(4.dp).background(DesignColors.SurfaceElevated)
                    swiping -> Modifier.background(DesignColors.Surface)
                    else -> Modifier
                },
            )
            .then(if (current) Modifier.background(DesignColors.Accent.copy(alpha = 0.1f)) else Modifier)
            .a11yActionLabel(rowLabel)
            .semantics(mergeDescendants = true) {
                if (current) selected = true
            }
            .clickable(role = Role.Button, onClickLabel = playLabel) { coordinator.player.playAt(index) }
            .padding(horizontal = 16.dp, vertical = if (large) 10.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.DragHandle,
            null,
            Modifier
                .align(Alignment.CenterVertically)
                .size(18.dp)
                .pointerInput(dragState, index) {
                    detectDragGestures(
                        onDragStart = { dragState.start(index) },
                        onDragEnd = dragState::end,
                        onDragCancel = dragState::end,
                    ) { change, amount ->
                        change.consume()
                        dragState.drag(amount.y)
                    }
                },
            tint = if (current) DesignColors.Accent else DesignColors.Outline,
        )
        Box(
            Modifier.size(if (large) 60.dp else 48.dp).clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ArtImage(item.displayThumbnailUrl, RoundedCornerShape(6.dp)) {
                ThumbPlaceholder(Icons.Outlined.Image, 18.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.displayTitle,
                style = TextStyle(
                    fontSize = if (large) 14.sp else 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (current) DesignColors.Accent else DesignColors.Ink,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = TextStyle(fontSize = if (large) 12.5.sp else 11.sp, color = DesignColors.InkSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (large) 4.dp else 2.dp),
                )
            }
        }
        Box {
            ActionIcon(
                Icons.Default.MoreVert,
                stringResource(Res.string.cd_more),
                tint = DesignColors.IconDots,
                onClick = { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                queueItemActions(coordinator, index, item, current).entries.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

/** A queue row's menu (the legacy app's queue item menu): a local file gets the song actions, a YouTube
 *  video the video ones; both can jump next in line or leave the queue. */
@Composable
private fun queueItemActions(coordinator: PlaybackCoordinator, index: Int, item: PlayableItem, current: Boolean): ItemActions {
    val player = coordinator.player
    val playNext = stringResource(Res.string.queue_next)
    val removeFromQueue = stringResource(Res.string.remove_from_queue)
    val addToPlaylistLabel = stringResource(Res.string.menu_add_to_playlist)
    val shareLabel = stringResource(Res.string.cd_share)
    val song by produceState<MediaStoreItem?>(null, item) {
        val mediaId = (item as? PlayableItem.LocalMedia)?.mediaId ?: return@produceState
        value = coordinator.graph.mediaStore?.findItem(mediaId)
    }
    val addSongToPlaylist = LocalAddToPlaylist.current
    val goToGroup = LocalGoToLibraryGroup.current
    val goToArtist = stringResource(Res.string.menu_go_to_artist)
    val goToAlbum = stringResource(Res.string.menu_go_to_album)
    val shareYoutube = LocalShareYoutube.current
    val ytPlaylists = LocalYtPlaylistActions.current
    val downloadLauncher = LocalDownloadLauncher.current
    val startRadio = stringResource(Res.string.menu_start_radio)
    val download = stringResource(Res.string.cd_download)
    return ItemActions(
        buildList {
            val video = coordinator.downloadable(item)
            if (video != null) add(startRadio to { coordinator.playNow(video) })
            if (!current) {
                add(
                    playNext to {
                        val currentIndex = player.queue.value.currentIndex
                        if (currentIndex >= 0) player.move(index, if (index > currentIndex) currentIndex + 1 else currentIndex)
                    },
                )
            }
            when (item) {
                is PlayableItem.LocalMedia -> song?.let { local ->
                    if (addSongToPlaylist != null) add(addToPlaylistLabel to { addSongToPlaylist(local) })
                    if (goToGroup != null) {
                        add(goToArtist to { goToGroup(LibraryGroupKind.Artist, local) })
                        add(goToAlbum to { goToGroup(LibraryGroupKind.Album, local) })
                    }
                }
                is PlayableItem.YtMedia -> {
                    if (ytPlaylists != null) add(addToPlaylistLabel to { ytPlaylists.addToPlaylist(item.videoId) })
                    if (video != null && downloadLauncher != null) add(download to { downloadLauncher.launch(video) })
                    if (shareYoutube != null) add(shareLabel to { shareYoutube(YtFeedItem.KIND_VIDEO, item.videoId) })
                }
            }
            add(removeFromQueue to { player.removeAt(index) })
        },
    )
}
