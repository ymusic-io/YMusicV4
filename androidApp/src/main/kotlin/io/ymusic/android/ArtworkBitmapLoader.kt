package io.ymusic.android

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.decodeScaled
import io.ymusic.ui.artImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * media3 [BitmapLoader] over the shared app [ImageLoader]: session + notification artwork resolves
 * through the same fetchers as the UI (https thumbs, `art://` ffmpeg extraction) and its source +
 * scaled disk caches. The memory LRU is deliberately NOT written (`cacheInMemory = false`): media3
 * holds/parcels its own bitmap, and the UI never peeks this size bucket, so an LRU entry would
 * only evict thumbs the UI does reuse — wrap in media3's `CacheBitmapLoader` instead, which
 * memoizes the one artwork the session republishes.
 *
 * [ArtworkProvider]-minted `content://` uris (what [MediaMetadata.artworkUri] carries so Android
 * Auto can resolve queue icons) are unwrapped back to their target url IN process — same
 * [ImageLoader] cache key as the raw url, no binder round-trip through the provider.
 */
class ImageLoaderBitmapLoader(
    private val imageLoader: ImageLoader,
    private val scope: CoroutineScope,
    private val ownAuthority: String,
    /** The largest artwork the platform session keeps unscaled. */
    sessionBitmapLimit: Int,
) : BitmapLoader {
    // Never over the session limit: some custom ROMs' MediaMetadata.Builder.scaleBitmap recycles its source,
    // and media3 publishes one bitmap under two keys, so the second key's rescale throws on the recycled bitmap.
    private val maxPx = minOf(ARTWORK_MAX_PX, sessionBitmapLimit)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = future {
        // Bounded like every other artwork path: a controller can hand arbitrary artworkData, and
        // a full-size decode of it is an unowned allocation (decodeScaled subsamples during decode).
        decodeScaled(data, IntSize(maxPx, maxPx)).bitmap.asAndroidBitmap().fitWithin(maxPx)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = future {
        val target = if (uri.scheme == "content" && uri.authority == ownAuthority) {
            uri.getQueryParameter(ARTWORK_URI_PARAM)
                ?: throw IllegalArgumentException("artwork content uri without a target: $uri")
        } else {
            uri.toString()
        }
        imageLoader.load(artImageRequest(target, maxPx, maxPx), cacheInMemory = false).asAndroidBitmap().fitWithin(maxPx)
    }

    /** Subsampled decodes land up to 2x the request; finish the downscale exactly. */
    private fun Bitmap.fitWithin(maxPx: Int): Bitmap {
        if (width <= maxPx && height <= maxPx) return this
        val ratio = maxPx.toFloat() / maxOf(width, height)
        return Bitmap.createScaledBitmap(this, maxOf(1, (width * ratio).roundToInt()), maxOf(1, (height * ratio).roundToInt()), true)
    }

    private fun <T : Any> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = scope.launch(Dispatchers.Default) {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }
}
