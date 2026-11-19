package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.imageloader.AsyncImage
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.ImageSource

/** Provided at the App root from [AppGraph.imageLoader]; null keeps every thumb a placeholder box. */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** The largest variant: by declared area when sizes are known, else the last (source order ascends). */
internal fun List<YtThumb>.largestThumbUrl(): String? =
    maxByOrNull { (it.w ?: 0).toLong() * (it.h ?: 0) }?.takeIf { (it.w ?: 0) > 0 }?.url ?: lastOrNull()?.url

/**
 * Gray fill + icon shown only while a thumb is loading or failed. Must be passed as
 * [ArtImage]/[ThumbImage] `placeholder` so a loaded bitmap is not drawn over it (GPU overdraw).
 */
@Composable
internal fun BoxScope.ThumbPlaceholder(
    icon: ImageVector,
    iconSize: Dp,
    color: Color = DesignColors.Placeholder,
) {
    Box(Modifier.matchParentSize().background(color))
    Icon(icon, null, Modifier.size(iconSize), tint = DesignColors.PlaceholderIcon)
}

/**
 * The image layer of a thumbnail box: fills the parent, clipped to [shape].
 * [placeholder] draws only until the bitmap lands (or on failure). The loader picks the variant
 * that covers the measured box (see [thumbRequestSources]) — a video's thumbs share its identity.
 */
@Composable
internal fun BoxScope.ThumbImage(
    thumbs: List<YtThumb>,
    shape: Shape,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
) {
    val request = thumbRequestSources(thumbs)
    ImageLayer(request?.first, request?.second.orEmpty(), shape, placeholder)
}

/** One url into the thumbnail box; a YouTube thumbnail url joins its video's identity ([artRequestSources]). */
@Composable
internal fun BoxScope.ArtImage(
    url: String?,
    shape: Shape,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
) {
    val request = url?.let(::artRequestSources)
    ImageLayer(request?.first, request?.second.orEmpty(), shape, placeholder)
}

@Composable
private fun BoxScope.ImageLayer(
    key: String?,
    sources: List<ImageSource>,
    shape: Shape,
    placeholder: (@Composable BoxScope.() -> Unit)?,
) {
    val loader = LocalImageLoader.current
    if (key == null || sources.isEmpty() || loader == null) {
        if (placeholder != null) {
            Box(Modifier.matchParentSize().clip(shape), contentAlignment = Alignment.Center) {
                placeholder()
            }
        }
        return
    }
    AsyncImage(
        loader,
        key,
        sources,
        contentDescription = null,
        modifier = Modifier.matchParentSize().clip(shape),
        contentScale = ContentScale.Crop,
        placeholder = placeholder,
    )
}
