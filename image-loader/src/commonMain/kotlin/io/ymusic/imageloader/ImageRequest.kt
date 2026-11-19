package io.ymusic.imageloader

import androidx.compose.ui.unit.IntSize
import okio.IOException

/**
 * What to show and at what size. [key] is the image's identity — every cache layer is keyed by it,
 * never by a url — so one picture published at several sizes or under rotating signed urls is one
 * entry family. [sources] are the variants it can be fetched from; with more than one, the loader
 * picks the smallest that covers [size] (by each source's declared size, a hint) and falls back
 * across the rest. A plain single-url image is [ImageRequest.of].
 */
class ImageRequest(
    val key: String,
    val sources: List<ImageSource>,
    val size: IntSize,
    val scale: ImageScale = ImageScale.Cover,
) {
    init {
        require(sources.isNotEmpty()) { "an image request needs at least one source: $key" }
    }

    companion object {
        /** A single-url image; its identity is [imageCacheKey] of the url. */
        fun of(url: String, width: Int, height: Int, scale: ImageScale = ImageScale.Cover): ImageRequest =
            ImageRequest(imageCacheKey(url), listOf(ImageSource(url)), IntSize(width, height), scale)
    }
}

/**
 * One fetchable variant of an image. [width]/[height] are the declared pixel size — 0 when unknown
 * — used only to choose a variant; declared sizes can be wrong. [crop] runs on the decoded image,
 * so every variant of one identity shows the same picture.
 */
class ImageSource(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val crop: SourceCrop = SourceCrop.None,
)

/** How the decode relates to the requested box: [Cover] fills it (for cropped views), [Fit] fits inside. */
enum class ImageScale { Cover, Fit }

enum class SourceCrop {
    None,

    /** The middle 16:9 band of a 4:3 frame: the picture inside YouTube's letterboxed hqdefault/sddefault. */
    Letterbox16x9,
}

/** A failed load, classified so callers can tell a missing image from a transient failure. */
class ImageLoadException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason {
        /** The source says the image does not exist (HTTP 404/410, a file or embedded picture that is absent). */
        NotFound,

        /** Transport failure or a server error: worth retrying later. */
        Network,

        /** Bytes arrived but are not a decodable image (or exceed the size cap). */
        Undecodable,

        /** No fetcher handles the url's scheme. */
        Unsupported,

        /** The source failed this way recently and is not retried yet; that first failure was logged. */
        RecentlyFailed,
    }
}
