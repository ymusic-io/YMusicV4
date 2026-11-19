package io.ymusic.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import io.ymusic.ui.VideoFrameExtractor
import io.ymusic.ui.VideoFrameUnavailableException
import kmpcommon.WorkerThread
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import okio.Buffer
import okio.IOException
import okio.Source

/**
 * [VideoFrameExtractor] over [MediaMetadataRetriever]: the platform's own decoders (hardware, or
 * Android's software AV1 since 10) for a video the bundled ffmpeg cannot decode. Opens [uri] (a SAF
 * document under the app's persisted tree grant) through the ContentResolver, as the ffmpeg path does.
 *
 * The frame is the retriever's representative one (a negative time): the extractor's thumbnail
 * time, which MPEG-4 and Matroska set to the largest of the first sync samples — the busiest early
 * key frame, so not the black lead-in.
 */
class PlatformVideoFrameExtractor(private val context: Context, private val logger: Logger) : VideoFrameExtractor {
    @WorkerThread
    override fun extract(uri: Uri): Source {
        val document = context.contentResolver.openFileDescriptor(android.net.Uri.parse(uri.toString()), "r")
            ?: throw IOException("ContentResolver: couldn't open $uri")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(document.fileDescriptor)
            val frame = frame(retriever) ?: throw VideoFrameUnavailableException("platform decoder produced no frame: $uri")
            val jpeg = Buffer()
            try {
                if (!frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg.outputStream())) {
                    throw VideoFrameUnavailableException("JPEG encode failed: $uri")
                }
            } finally {
                frame.recycle()
            }
            return jpeg
        } catch (e: RuntimeException) {
            // setDataSource and the frame getters report an undecodable source as IllegalArgument/StateException.
            throw VideoFrameUnavailableException("platform decoder failed: $uri", e)
        } finally {
            releaseLogged(uri, "retriever") { retriever.release() }
            releaseLogged(uri, "document") { document.close() }
        }
    }

    /** Logged, never thrown: a failed release must not replace the frame or the real error. */
    private inline fun releaseLogged(uri: Uri, what: String, release: () -> Unit) {
        try {
            release()
        } catch (e: IOException) {
            logger.log(Logger.Level.WARN, "$what release failed: $uri", e)
        }
    }

    private fun frame(retriever: MediaMetadataRetriever): Bitmap? {
        val box = frameBox(retriever)
        if (box != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(REPRESENTATIVE_FRAME, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box.first, box.second)
        }
        val full = retriever.getFrameAtTime(REPRESENTATIVE_FRAME, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = minOf(1f, MAX_EDGE.toFloat() / full.width, MAX_EDGE.toFloat() / full.height)
        if (scale >= 1f) return full
        val scaled = Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    /** The displayed size capped per edge at [MAX_EDGE]; the retriever fits the frame inside it, keeping the aspect. */
    private fun frameBox(retriever: MediaMetadataRetriever): Pair<Int, Int>? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val sideways = rotation % 180 != 0
        val displayWidth = if (sideways) height else width
        val displayHeight = if (sideways) width else height
        return minOf(MAX_EDGE, displayWidth) to minOf(MAX_EDGE, displayHeight)
    }

    private companion object {
        const val MAX_EDGE = 1280
        const val JPEG_QUALITY = 90
        const val REPRESENTATIVE_FRAME = -1L
    }
}
