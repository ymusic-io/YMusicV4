package io.ymusic.imageloader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * An image from [url], loaded through [loader] at this composable's own measured pixel size (so
 * the decode matches the display — no full-size bitmaps for thumbnails). Its identity is
 * [imageCacheKey] of the url.
 */
@Composable
fun AsyncImage(
    loader: ImageLoader,
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
    error: (@Composable BoxScope.() -> Unit)? = null,
    crossfadeMs: Int = 0,
) {
    AsyncImage(
        loader = loader,
        key = url?.let(::imageCacheKey),
        sources = url?.let { listOf(ImageSource(it)) }.orEmpty(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholder,
        error = error,
        crossfadeMs = crossfadeMs,
    )
}

/**
 * An image with identity [key] and several [sources] (sizes of one picture), loaded through
 * [loader] at this composable's own measured pixel size: the loader picks the smallest source that
 * covers it (see [ImageRequest]). A null [key] or empty [sources] shows [placeholder].
 *
 * Shows [placeholder] while loading and [error] (falling back to [placeholder]) when the load
 * fails; the failure is logged, never thrown into composition. A memory-cached image draws on the
 * FIRST frame (synchronous peek), so scrolling back never flashes the placeholder. On resize the
 * already-shown bitmap stays; only a growth of at least [RELOAD_GROWTH_PX] in a dimension warrants
 * a sharper decode (shrinking just downscales), and the reload waits [RESIZE_SETTLE_MS] for the
 * resize to settle — every further size change restarts the wait, so a live window drag triggers
 * one reload at the end, not a stream of them. [contentScale] Fit/Inside decodes to fit the box;
 * everything else decodes to cover it. [crossfadeMs] > 0 fades an image in over the placeholder
 * when it arrives after the first frame; a memory hit still draws at once.
 */
@Composable
fun AsyncImage(
    loader: ImageLoader,
    key: String?,
    sources: List<ImageSource>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
    error: (@Composable BoxScope.() -> Unit)? = null,
    crossfadeMs: Int = 0,
) {
    val alpha = remember(key) { Animatable(1f) }
    val scale = if (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside) ImageScale.Fit else ImageScale.Cover
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var loaded by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    // The size the showing bitmap was decoded for — the growth-threshold baseline.
    var loadedFor by remember(key) { mutableStateOf(IntSize.Zero) }
    val request = if (key != null && sources.isNotEmpty() && sizePx != IntSize.Zero) {
        ImageRequest(key, sources, sizePx, scale)
    } else {
        null
    }

    Box(modifier.onSizeChanged { sizePx = it }, contentAlignment = Alignment.Center) {
        // Synchronous memory-cache peek: an already-cached image draws on its FIRST frame — routing
        // the hit through the effect would flash the placeholder for a frame (list scroll jitter).
        val bitmap = loaded ?: request?.let(loader::peek)
        when {
            bitmap != null -> {
                if (alpha.value < 1f) placeholder?.invoke(this)
                Image(
                    bitmap,
                    contentDescription,
                    modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = alpha.value },
                    contentScale = contentScale,
                )
            }
            failed -> (error ?: placeholder)?.invoke(this)
            else -> placeholder?.invoke(this)
        }
    }

    if (request != null) {
        LaunchedEffect(key, sizePx) {
            if (loaded != null) {
                val growsW = sizePx.width - loadedFor.width >= RELOAD_GROWTH_PX
                val growsH = sizePx.height - loadedFor.height >= RELOAD_GROWTH_PX
                if (!growsW && !growsH) return@LaunchedEffect
                delay(RESIZE_SETTLE_MS)
            }
            loadedFor = sizePx
            val arrivesLate = crossfadeMs > 0 && loaded == null && loader.peek(request) == null
            try {
                val bitmap = loader.load(request)
                if (arrivesLate) alpha.snapTo(0f)
                loaded = bitmap
                failed = false
                if (arrivesLate) alpha.animateTo(1f, tween(crossfadeMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                loader.logger?.log(loadFailureLevel(e), "load failed: $key", e)
                // A failed sharper re-decode keeps the bitmap already showing.
                failed = loaded == null
            }
        }
    }
}

private const val RELOAD_GROWTH_PX = 100
private const val RESIZE_SETTLE_MS = 400L
