package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kmphttp.cache.DiskLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import kotlin.random.Random

/**
 * Optional persistent cache of already-scaled images, so re-showing a thumbnail skips both the
 * network fetch and the full-size decode. The cache owns the storage codec — callers hand it
 * bitmaps, never bytes. Keys are opaque (the loader passes a hash of url + target size).
 * Implementations own their bounds/eviction.
 */
interface ScaledImageCache {
    /** The cached bitmap, or null on a miss. Throws on an undecodable (corrupt) entry. */
    suspend fun get(key: String): ImageBitmap?

    suspend fun put(key: String, bitmap: ImageBitmap)

    /** The committed entry's backing FILE (LRU-refreshed), or null on a miss — for consumers that
     *  serve bytes across a process boundary instead of drawing (see [ImageLoader.loadFile]).
     *  Null also when the implementation has no file-backed storage. */
    suspend fun filePath(key: String): Path? = null

    /** [bitmap] as a file outside the cache, for [ImageLoader.loadFile] of an image that must not be cached; it
     *  replaces the last one under [key]. Null when the implementation has no file-backed storage. */
    suspend fun transientFile(key: String, bitmap: ImageBitmap): Path? = null
}

/**
 * [ScaledImageCache] on kmpHttp's journaled [DiskLruCache]: true LRU (a get refreshes the entry),
 * incremental size accounting (no directory scans), crash-safe journaled writes, bounded to
 * [maxBytes]. Entries are stored as lossy WebP; en/decoding streams through okio [Buffer]s (Android
 * decodes straight off the stream — no intermediate ByteArray; Skia's codec API is bytes-only).
 */
class DiskScaledImageCache(
    private val fileSystem: FileSystem,
    directory: Path,
    maxBytes: Long = 64L * 1024 * 1024,
) : ScaledImageCache {
    private val cache = DiskLruCache(
        fileSystem = fileSystem,
        directory = directory,
        valueCount = 1,
        maxSize = maxBytes,
    )

    private val transientDirectory = "$directory-transient".toPath()

    /** Last process's transient files go before this one writes its first. */
    private val transientReady = lazy {
        fileSystem.deleteRecursively(transientDirectory)
        fileSystem.createDirectories(transientDirectory)
    }

    /** Bytes currently accounted by the journal. */
    val sizeBytes: Long get() = cache.currentSize

    /** Evict down to the budget now instead of waiting for the throttled background cleanup. */
    suspend fun trimNow() = withContext(Dispatchers.IO) { cache.trimNow() }

    /** Flush the journal and release the cache; the directory stays valid for a new instance. */
    suspend fun close() = withContext(Dispatchers.IO) { cache.close() }

    override suspend fun get(key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key)?.use { snapshot ->
            // Entries are pre-scaled: MAX/MAX clamps to nothing, single effective pass.
            decodeScaledFile(fileSystem, snapshot.getPath(0), Int.MAX_VALUE, Int.MAX_VALUE, fit = true).bitmap
        }
    }

    override suspend fun filePath(key: String): Path? = withContext(Dispatchers.IO) {
        cache.get(key)?.use { it.getPath(0) }
    }

    override suspend fun transientFile(key: String, bitmap: ImageBitmap): Path = withContext(Dispatchers.IO) {
        transientReady.value
        val file = transientDirectory / key
        val partial = transientDirectory / "$key.${Random.nextLong().toULong()}.tmp"
        fileSystem.sink(partial).use { encodeWebpToSink(bitmap, it) }
        fileSystem.atomicMove(partial, file)
        file
    }

    override suspend fun put(key: String, bitmap: ImageBitmap): Unit = withContext(Dispatchers.IO) {
        val editor = cache.edit(key) ?: return@withContext
        try {
            editor.newSink(0).use { sink ->
                encodeWebpToSink(bitmap, sink)
                sink.flush()
            }
            editor.commit()
        } catch (t: Throwable) {
            editor.abortSuppressing(t)
            throw t
        }
    }
}
