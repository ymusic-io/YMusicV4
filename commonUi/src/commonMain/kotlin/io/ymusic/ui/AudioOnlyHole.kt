package io.ymusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.ymusic.imageloader.ImageRequest
import io.ymusic.player.Player
import io.ymusic.player.VideoQualityState
import io.ymusic.player.showsVideo
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

private const val WAVE_BARS = 40

/** STARTED while the window is on screen, focused or not; drops when minimized/backgrounded. */
@Composable
private fun windowVisible(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Audio-only by the user's pick, or because the source has no video to show. Between items the
 * engine has not reported the new tracks yet, so it stays audio-only until it does — otherwise the
 * art view (and its song-change transition) would be torn down and rebuilt on every track.
 */
@Composable
internal fun showsAudioOnly(player: Player): Boolean {
    val config by player.config.collectAsState()
    val quality by player.videoQuality.collectAsState()
    val known = !showsVideo(config, quality)
    val tracksUnknown = quality.options.isEmpty() && quality.actualQuality == VideoQualityState.AUTO
    val last = remember { AudioOnlyMemo(known) }
    val shown = known || (last.value && tracksUnknown)
    SideEffect { last.value = shown }
    return shown
}

private class AudioOnlyMemo(var value: Boolean)

/** Cropped thumbnail filling the hole — mini player audio-only. */
@Composable
internal fun AudioOnlyCover(
    url: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.clip(RectangleShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ArtImage(url, RectangleShape)
    }
}

/**
 * 16:9 audio-only hole: blurred/darkened thumbnail fill, sharp square art on the left,
 * live PCM bars on the right. No badge.
 */
@Composable
internal fun AudioOnlyArtWave(
    player: Player,
    url: String?,
    modifier: Modifier = Modifier,
) {
    val status by player.status.collectAsState()
    val sampling = status.playWhenReady && windowVisible()
    val levels by produceState(FloatArray(WAVE_BARS), player, sampling) {
        val buf = FloatArray(WAVE_BARS)
        if (!sampling) {
            var cur = value
            var n = 0
            while (isActive && n < 12 && cur.any { it > 0.012f }) {
                cur = FloatArray(cur.size) { i -> cur[i] * 0.78f }
                value = cur
                n++
                delay(33)
            }
            if (isActive && cur.any { it > 0.012f }) value = FloatArray(WAVE_BARS)
            return@produceState
        }
        while (isActive) {
            // Native: waits on the audio threads' locks.
            val n = withContext(Dispatchers.Default) { player.pcmLevels(buf) }
            if (n > 0) {
                if (n < buf.size) buf.fill(0f, n, buf.size)
                value = buf.copyOf()
            }
            delay(33)
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val side = minOf(maxHeight * 0.86f, maxWidth * 0.32f)
        // The square, floored at the system artwork size; the blurred backdrop reuses this decode.
        val decodeSize = with(LocalDensity.current) {
            maxOf(side.roundToPx(), NOW_PLAYING_ART_PX).let { IntSize(it, it) }
        }
        val art = rememberArtTransition(url, decodeSize)
        PrefetchNextArt(player, decodeSize)
        Box(Modifier.matchParentSize().blur(28.dp)) {
            ArtBackdrop(art)
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.36f)))
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(side)
                    .shadow(10.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                ArtSquare(art)
            }
            Spacer(Modifier.width(14.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val n = levels.size.coerceAtLeast(1)
                val gap = size.width * 0.014f
                val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1.5f)
                val mid = size.height * 0.5f
                val maxH = minOf(size.height * 0.92f, side.toPx() * 2f)
                for (i in 0 until n) {
                    val h = (levels[i].coerceIn(0f, 1f) * maxH).coerceAtLeast(2f)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.92f),
                        topLeft = Offset(i * (barW + gap), mid - h * 0.5f),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW * 0.45f, barW * 0.45f),
                    )
                }
            }
        }
    }
}

private const val ART_FADE_OUT_MS = 260
private const val ART_FADE_IN_MS = 420
private const val ART_MIN_SHADE_MS = 220L
private const val ART_SHIMMER_MS = 1300
private const val ART_RESIZE_SETTLE_MS = 400L
private const val ART_BACKDROP_DIM = 0.3f

/**
 * Now Playing's art across a url change, the same every time: the square's [previous] picture
 * fades out to the shade while the backdrop keeps it, dimmed by [dim]; the shade holds at least
 * [ART_MIN_SHADE_MS] (longer while the load runs); then [current] fades in on both layers and the
 * dim lifts. A view that grows swaps in a sharper decode of the same picture, no transition.
 */
private class ArtTransition {
    var previous by mutableStateOf<ImageBitmap?>(null)
    var current by mutableStateOf<ImageBitmap?>(null)
    var url: String? = null
    var shading by mutableStateOf(false)
    val outAlpha = Animatable(1f)
    val inAlpha = Animatable(0f)
    val dim = Animatable(0f)
}

@Composable
private fun rememberArtTransition(url: String?, decodeSize: IntSize): ArtTransition {
    val loader = LocalImageLoader.current
    val state = remember { ArtTransition() }
    val size by rememberUpdatedState(decodeSize)
    val ready = decodeSize.width > 0 && decodeSize.height > 0

    LaunchedEffect(url, loader, ready) {
        if (!ready || (state.url == url && state.current != null)) return@LaunchedEffect
        coroutineScope {
            val next = async {
                if (url == null || loader == null) {
                    null
                } else {
                    val request = artRequest(url, size)
                    loader.peek(request) ?: loader.loadOrNull(request)
                }
            }
            val shadeFrom = TimeSource.Monotonic.markNow()
            // An interrupted fade-in becomes the outgoing picture from where it got to.
            state.current?.let { half ->
                state.outAlpha.snapTo(state.inAlpha.value)
                state.previous = half
                state.current = null
            }
            state.url = url
            state.shading = url != null
            listOf(
                launch { state.outAlpha.animateTo(0f, tween(ART_FADE_OUT_MS, easing = FastOutSlowInEasing)) },
                launch { state.dim.animateTo(ART_BACKDROP_DIM, tween(ART_FADE_OUT_MS, easing = FastOutSlowInEasing)) },
            ).joinAll()
            val bitmap = next.await()
            val held = shadeFrom.elapsedNow().inWholeMilliseconds
            if (held < ART_FADE_OUT_MS + ART_MIN_SHADE_MS) delay(ART_FADE_OUT_MS + ART_MIN_SHADE_MS - held)
            if (bitmap == null) {
                state.shading = false
                return@coroutineScope
            }
            state.inAlpha.snapTo(0f)
            state.current = bitmap
            listOf(
                launch { state.inAlpha.animateTo(1f, tween(ART_FADE_IN_MS, easing = FastOutSlowInEasing)) },
                launch { state.dim.animateTo(0f, tween(ART_FADE_IN_MS, easing = FastOutSlowInEasing)) },
            ).joinAll()
            state.previous = null
            state.shading = false
        }
    }

    LaunchedEffect(decodeSize) {
        val shownUrl = state.url ?: return@LaunchedEffect
        if (state.current == null || shownUrl != url || loader == null) return@LaunchedEffect
        delay(ART_RESIZE_SETTLE_MS)
        val request = artRequest(shownUrl, decodeSize)
        val sharper = loader.peek(request) ?: loader.loadOrNull(request)
        if (sharper != null && state.url == shownUrl && state.current != null) state.current = sharper
    }
    return state
}

/** Warms the next queue item's art at this view's size, so the song change finds it in memory. */
@Composable
private fun PrefetchNextArt(player: Player, decodeSize: IntSize) {
    val loader = LocalImageLoader.current ?: return
    val queue by player.queue.collectAsState()
    val nextUrl = queue.items.getOrNull(queue.currentIndex + 1)?.displayThumbnailUrl
    LaunchedEffect(nextUrl, decodeSize, loader) {
        if (nextUrl != null && decodeSize.width > 0 && decodeSize.height > 0) loader.prefetch(artRequest(nextUrl, decodeSize))
    }
}

private fun artRequest(url: String, size: IntSize): ImageRequest = artImageRequest(url, size.width, size.height)

@Composable
private fun BoxScope.ArtImageLayer(bitmap: ImageBitmap?, alpha: () -> Float) {
    if (bitmap == null) return
    Image(
        bitmap,
        contentDescription = null,
        modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = alpha() },
        contentScale = ContentScale.Crop,
    )
}

/** The backdrop keeps the outgoing picture (dimmed) under the incoming one's fade-in. */
@Composable
private fun BoxScope.ArtBackdrop(art: ArtTransition) {
    ArtImageLayer(art.previous) { 1f }
    ArtImageLayer(art.current) { art.inAlpha.value }
    Box(Modifier.matchParentSize().graphicsLayer { alpha = art.dim.value }.background(Color.Black))
}

/** The square fades the outgoing picture out over the shade, then the incoming one in. */
@Composable
private fun BoxScope.ArtSquare(art: ArtTransition) {
    if (art.shading) ArtShade()
    ArtImageLayer(art.previous) { art.outAlpha.value }
    ArtImageLayer(art.current) { art.inAlpha.value }
}

/** A soft light band sweeping a dark square while the next picture is on its way — opaque, so the
 *  dimmed backdrop behind doesn't tint it, and dark in every theme. */
@Composable
private fun BoxScope.ArtShade() {
    val sweep by rememberInfiniteTransition().animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(ART_SHIMMER_MS, easing = LinearEasing)),
    )
    Box(
        Modifier.matchParentSize().background(Color.Black).background(Color.White.copy(alpha = 0.07f)).drawWithCache {
            val band = size.width * 0.6f
            val x = sweep * size.width
            val brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                start = Offset(x - band, 0f),
                end = Offset(x, size.height),
            )
            onDrawBehind { drawRect(brush) }
        },
    )
}

/** Video hole plus the audio-only overlay when [showsAudioOnly]. */
@Composable
internal fun PlayerSurfaceHole(
    player: Player,
    surface: @Composable (Modifier) -> Unit,
    artUrl: String?,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    wave: Boolean = true,
    settings: SettingsStore? = null,
) {
    val audioOnly = showsAudioOnly(player)
    Box(modifier) {
        VideoHole(player, surface, Modifier.fillMaxSize(), fill = fill)
        if (audioOnly) {
            if (wave) AudioOnlyArtWave(player, artUrl, Modifier.fillMaxSize())
            else AudioOnlyCover(artUrl, Modifier.fillMaxSize())
        } else if (settings != null) {
            FpsBadge(player, settings, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
    }
}

@Composable
private fun FpsBadge(player: Player, settings: SettingsStore, modifier: Modifier = Modifier) {
    val enabled by settings.flow(AppSettings.showFps).collectAsState()
    if (!enabled) return
    FpsBadgeActive(player, modifier)
}

/** Pictures shown over the last second, dropped since the current item started, and the video decoder. */
private data class FrameRate(val shown: Int, val dropped: Int, val decoder: String? = null)

/** Dropped pictures summed over an item: the host's counter restarts with every load (a quality switch too). */
private class DroppedTotal(var last: Int) {
    var total = 0

    fun add(counter: Int) {
        total += if (counter >= last) counter - last else counter
        last = counter
    }
}

@Composable
private fun FpsBadgeActive(player: Player, modifier: Modifier = Modifier) {
    val status by player.status.collectAsState()
    val queue by player.queue.collectAsState()
    val item = queue.currentItem
    val dropped = remember(item) { DroppedTotal(player.droppedFrames()) }
    val sampling = status.playWhenReady && windowVisible()
    val rate by produceState(FrameRate(0, dropped.total), player, sampling, dropped) {
        if (!sampling) {
            value = FrameRate(0, dropped.total)
            return@produceState
        }
        var prevShown = player.presentedFrames()
        dropped.add(player.droppedFrames())
        while (isActive) {
            delay(1_000)
            val shown = player.presentedFrames()
            dropped.add(player.droppedFrames())
            val decoder = player.videoDecoder()
            value = FrameRate((shown - prevShown).coerceAtLeast(0), dropped.total, decoder)
            prevShown = shown
        }
    }
    if (!sampling || (rate.shown <= 0 && rate.dropped <= 0)) return
    Text(
        // A developer readout, not localized.
        listOfNotNull(
            "${rate.shown} fps",
            "${rate.dropped} dropped".takeIf { rate.dropped > 0 },
            rate.decoder,
        ).joinToString(" - "),
        style = TextStyle(fontSize = 13.sp, color = Color.White),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
