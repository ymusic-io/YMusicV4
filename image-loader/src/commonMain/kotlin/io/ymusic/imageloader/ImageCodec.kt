package io.ymusic.imageloader

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** A decoded image and the pixel size of the source it came from (after any crop). */
class DecodedImage(
    val bitmap: ImageBitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val retention: Retention = Retention.Persistent,
) {
    /** The decode is smaller than its source: a scaled copy saves this work next time. */
    val downscaled: Boolean get() = bitmap.width < sourceWidth || bitmap.height < sourceHeight
}

/** How far the loader keeps a fetched image. Only [Persistent] reaches a disk cache or the [VariantIndex]. */
enum class Retention {
    Persistent,

    /** A stand-in the fetcher drew for an image it cannot have: kept in the memory LRU for this process only. */
    Memory,

    /** A stand-in for a failure that may pass (offline): never cached, so the next load asks again. */
    None,
}

/**
 * Decode [bytes] into a bitmap covering (or, with [ImageScale.Fit], fitting) [size], aspect kept,
 * never upscaled — the building block for [ImageFetcher] implementations whose data is in memory.
 * Platform decoders scale during/right after decode so the full-size image never stays resident.
 * Throws on undecodable bytes.
 */
suspend fun decodeScaled(bytes: ByteArray, size: IntSize, scale: ImageScale = ImageScale.Cover): DecodedImage =
    withContext(Dispatchers.Default) { decodeScaled(bytes, size.width, size.height, scale == ImageScale.Fit) }

/**
 * [decodeScaled] straight from a file — for fetchers whose data lives on disk: same clamp
 * semantics, but the compressed data never enters the heap whole on Android ([fileSystem] must be
 * the real one there for the two-pass `BitmapFactory.decodeFile`; any other — e.g. a test fake —
 * falls back to read-bytes + decode). Skia's codec API is bytes-only, so skiko reads the file into
 * memory first.
 */
suspend fun decodeScaledFile(
    fileSystem: okio.FileSystem,
    path: okio.Path,
    size: IntSize,
    scale: ImageScale = ImageScale.Cover,
): DecodedImage = withContext(Dispatchers.IO) {
    decodeScaledFile(fileSystem, path, size.width, size.height, scale == ImageScale.Fit)
}

/** This image with [crop] applied; [DecodedImage.sourceWidth]/[DecodedImage.sourceHeight] follow the crop. */
fun DecodedImage.cropped(crop: SourceCrop): DecodedImage = when (crop) {
    SourceCrop.None -> this
    SourceCrop.Letterbox16x9 -> {
        val width = bitmap.width
        val height = width * 9 / 16
        if (height <= 0 || height >= bitmap.height) {
            this
        } else {
            val band = ImageBitmap(width, height)
            Canvas(band).drawImageRect(
                bitmap,
                srcOffset = IntOffset(0, (bitmap.height - height) / 2),
                srcSize = IntSize(width, height),
                dstSize = IntSize(width, height),
                paint = Paint(),
            )
            DecodedImage(band, sourceWidth, sourceWidth * 9 / 16, retention)
        }
    }
}

internal expect fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int, fit: Boolean): DecodedImage

internal expect fun decodeScaledFile(
    fileSystem: okio.FileSystem,
    path: okio.Path,
    maxWidth: Int,
    maxHeight: Int,
    fit: Boolean,
): DecodedImage

/**
 * Encode an already-scaled bitmap as lossy WebP into [sink] (the scaled-image cache's storage
 * format: a fraction of PNG's size for photos, and it keeps alpha). Android compresses straight
 * into the stream; skiko encodes in memory first (Skia limitation).
 */
internal expect fun encodeWebpToSink(bitmap: ImageBitmap, sink: okio.Sink)

internal const val SCALED_WEBP_QUALITY = 85
