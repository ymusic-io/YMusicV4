package io.ymusic.ui

import androidx.compose.ui.unit.IntSize
import io.ymusic.imageloader.DecodedImage
import io.ymusic.imageloader.ImageFetcher
import io.ymusic.imageloader.ImageLoadException
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.ImageRequest
import io.ymusic.imageloader.ImageScale
import io.ymusic.imageloader.ImageSourceCache
import io.ymusic.imageloader.decodeChecked
import io.ymusic.imageloader.decodeScaled
import io.ymusic.imageloader.decodeScaledFile
import io.ymusic.imageloader.sourceCacheKey
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.MediaStoreItem
import kmpcommon.WorkerThread
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmpffmpeg.metadata.FfmpegThumbnailException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Source
import okio.use

/**
 * `art://pic/<media-id>` — a local media file's artwork, the ONE url every consumer (Library,
 * NowPlaying, media session/Auto) carries for a library item. Resolved from the store row at fetch
 * time by [mediaStoreArtFetcher]: the embedded cover (attached_pic) when the file carries one,
 * else the YouTube thumbnail when the file is tagged with a video id, else a representative
 * decoded frame for a video. Keyed by the store id, which survives file moves (the store preserves
 * it), so the image caches stay warm across a rescan — and because the caches key on this single
 * url, the source choice runs once per cold miss, never per view. [localArtUrl] mints the url only
 * when the store knows an artwork source exists, so art-less files never reach the fetcher.
 */
fun localArtUrl(item: MediaStoreItem): String? =
    if (item.thumbnailStreamIndex != null || item.videoCodec != null || item.youtubeVid != null) {
        UriBuilder().scheme("art").authority("pic").appendPathSegment(item.id.toString()).buildString()
    } else {
        null
    }

/** The YouTube thumbnail for [videoId] — mqdefault (320×180): the one variant every video has,
 *  and its url carries no rotating query params, so it is stable enough for long-lived uris. */
fun youtubeThumbUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

/** `art://yt/<videoId>` — a video's best thumbnail at the view size, for a file not in the store. */
fun youtubeArtUrl(videoId: String): String =
    UriBuilder().scheme("art").authority("yt").appendPathSegment(videoId).buildString()

/** `art://file?kind=…&uri=…` — art for a file not in the store: its embedded cover, else its video's
 *  thumbnail, else a decoded frame. Null when it has none of those. */
fun fileArtUrl(uri: String, hasCover: Boolean, youtubeVid: String?, hasVideo: Boolean): String? = when {
    hasCover -> fileArtUrl(uri, ART_KIND_COVER)
    youtubeVid != null -> youtubeArtUrl(youtubeVid)
    hasVideo -> fileArtUrl(uri, ART_KIND_FRAME)
    else -> null
}

private fun fileArtUrl(uri: String, kind: String): String =
    UriBuilder().scheme("art").authority("file").appendQueryParam("kind", kind).appendQueryParam("uri", uri).buildString()

private const val ART_KIND_COVER = "cover"
private const val ART_KIND_FRAME = "frame"

/** A video's thumbnail at a size — the art fetcher's fallback for files tagged with a video id. */
fun interface YoutubeArt {
    suspend fun load(videoId: String, size: IntSize, scale: ImageScale): DecodedImage
}

/**
 * A platform decoder's JPEG of one representative frame of a video, fitted within a 1280 px box and
 * never scaled up — what [FfmpegMetadataGetter.extractThumbnail] yields. The art fetcher's fallback
 * for a video the bundled ffmpeg cannot decode (Android's build has no AV1 decoder).
 */
fun interface VideoFrameExtractor {
    @WorkerThread
    fun extract(uri: Uri): Source
}

/**
 * [YoutubeArt] through [loader]'s identity request for the video ([youtubeImageKey] with
 * [youtubeThumbnailSources]): shares its caches and variant index with every other view of the
 * video. [loader] is a provider because the art fetcher is built into the loader it calls; the
 * outer `art:` load holds a local slot and this one a network slot, so the nesting cannot starve.
 */
fun loaderYoutubeArt(loader: () -> ImageLoader): YoutubeArt = YoutubeArt { videoId, size, scale ->
    val bitmap = loader().load(ImageRequest(youtubeImageKey(videoId), youtubeThumbnailSources(videoId), size, scale))
    DecodedImage(bitmap, bitmap.width, bitmap.height)
}

/**
 * The `art:` resolver: media id → [store] item → artwork, decoded to the view size. Embedded
 * covers and video frames are ffmpeg-extracted via [getter] (every such fetch spawns one ffmpeg);
 * a file with no embedded cover but a YouTube video id — and `art://yt/<id>` — goes to
 * [youtubeArt]. That branch sits before the video-frame one: tagged videos show the real poster
 * rather than an arbitrary frame. `youtubeVid` is scan-validated to the 11-char id alphabet.
 *
 * With a [coverCache], an extracted cover or frame is kept on disk, keyed by the file's uri, size
 * and mtime (a changed file re-extracts): every view size then decodes from that file instead of
 * spawning ffmpeg again.
 *
 * A video frame ffmpeg cannot produce goes to [frameFallback] when there is one; if that fails too,
 * ffmpeg's exception is thrown with the fallback's failure suppressed on it.
 */
fun mediaStoreArtFetcher(
    getter: FfmpegMetadataGetter,
    store: BetterMediaStore,
    youtubeArt: YoutubeArt,
    coverCache: ImageSourceCache? = null,
    maxImageBytes: Long = 32L * 1024 * 1024,
    frameFallback: VideoFrameExtractor? = null,
    logger: Logger? = null,
): ImageFetcher = ImageFetcher { uri, size, scale ->
    suspend fun extractFrame(file: Uri): Source = try {
        getter.extractThumbnail(file)
    } catch (ffmpegError: FfmpegThumbnailException) {
        val fallback = frameFallback ?: throw ffmpegError
        try {
            withContext(Dispatchers.IO) { fallback.extract(file) }.also {
                logger?.log(Logger.Level.INFO, "ffmpeg gave no video frame, platform decoder did: $file", ffmpegError)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (fallbackError: Exception) {
            ffmpegError.addSuppressed(fallbackError)
            throw ffmpegError
        }
    }

    if (uri.scheme?.toString() != "art") return@ImageFetcher null
    val url = uri.toString()
    if (uri.authority?.toString() == "yt") {
        val videoId = uri.path.toString().substringAfterLast('/').takeIf { it.isNotEmpty() }
            ?: throw ImageLoadException(ImageLoadException.Reason.Unsupported, "malformed art uri: $url")
        return@ImageFetcher youtubeArt.load(videoId, size, scale)
    }
    if (uri.authority?.toString() == "file") {
        val file = uri.queryParamValue("uri")?.let { Uri.parse(it) }
            ?: throw ImageLoadException(ImageLoadException.Reason.Unsupported, "malformed art uri: $url")
        val source = if (uri.queryParamValue("kind")?.toString() == ART_KIND_COVER) getter.getAttachedPic(file) else extractFrame(file)
        val bytes = readCapped(source, maxImageBytes, url)
        return@ImageFetcher decodeChecked(url) { decodeScaled(bytes, size, scale) }
    }
    if (uri.authority?.toString() != "pic") return@ImageFetcher null
    val id = uri.path.toString().substringAfterLast('/').toLongOrNull()
        ?: throw ImageLoadException(ImageLoadException.Reason.Unsupported, "malformed art uri: $url")
    val item = store.findItem(id)
        ?: throw ImageLoadException(ImageLoadException.Reason.NotFound, "no media item for art uri: $url")
    val youtubeVid = item.youtubeVid
    val kind = when {
        item.thumbnailStreamIndex != null -> "cover"
        youtubeVid != null -> return@ImageFetcher youtubeArt.load(youtubeVid, size, scale)
        item.videoCodec != null -> "frame"
        else -> throw ImageLoadException(ImageLoadException.Reason.NotFound, "media item $id has no artwork source: $url")
    }
    suspend fun extract(): Source = if (kind == "cover") getter.getAttachedPic(item.uri) else extractFrame(item.uri)
    if (coverCache != null) {
        val key = sourceCacheKey("$kind:${item.uri}:${item.fileSize}:${item.lastModified}")
        val file = coverCache.filePath(key) ?: extract().use { coverCache.write(key, it, maxImageBytes, url) }
        return@ImageFetcher decodeChecked(url) { decodeScaledFile(coverCache.fileSystem, file, size, scale) }
    }
    // ByteArray: the in-memory platform decoders behind decodeScaled are bytes-only (Skia codec),
    // and embedded art is small — the cap guards a mislabeled stream.
    val bytes = readCapped(extract(), maxImageBytes, url)
    decodeChecked(url) { decodeScaled(bytes, size, scale) }
}

private suspend fun readCapped(source: Source, maxBytes: Long, url: String): ByteArray =
    withContext(Dispatchers.IO) {
        source.use { src ->
            val buffer = Buffer()
            while (true) {
                if (buffer.size > maxBytes) {
                    throw ImageLoadException(ImageLoadException.Reason.Undecodable, "embedded art exceeds the $maxBytes-byte cap: $url")
                }
                if (src.read(buffer, 64L * 1024) == -1L) break
            }
            buffer.readByteArray()
        }
    }
