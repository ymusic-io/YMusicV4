package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A deliberately simple image loader for Compose: resolve an [ImageRequest] via [fetchers] → decode
 * scaled to the view size → cache. Everything is a fetcher — http is [networkFetcher], local files
 * are [fileFetcher], app schemes (embedded album art, content providers) are their own.
 *
 * - **Identity**: every cache layer is keyed by [ImageRequest.key] plus the 64px size bucket (and
 *   the scale), never by a url — the url-only overloads use [imageCacheKey] of the url.
 * - **Memory**: images decode at (or just above) the requested display size — the full-resolution
 *   original is never kept. Decoded bitmaps live in a bounded in-memory LRU.
 * - **[fetchers]** are asked in order per source; the first non-null result wins. Each fetcher
 *   fetches AND decodes — guarding (byte caps, retries) is the fetcher's own. A request with several
 *   sources tries the smallest that covers the size first and falls back across the rest.
 * - **[scaledCache]** (optional) persists the *scaled* image when it is smaller than its source, so
 *   a warm start skips both the fetch and the full-size decode.
 * - **Priority is LIFO** over two pools — [networkParallelism] slots for http(s) sources and
 *   [localParallelism] for everything else, so a slow download never holds up a local cover. The
 *   newest request wins, and a load whose every caller cancelled (a list item scrolled away, its
 *   composition gone) frees its slot immediately — see [LifoGate].
 * - **One load per image**: concurrent requests for the same key and size share one fetch.
 * - **Misses are remembered**: a source that answered NotFound is not asked again for
 *   [NOT_FOUND_TTL], within this process.
 */
class ImageLoader(
    private val fetchers: List<ImageFetcher>,
    private val scaledCache: ScaledImageCache? = null,
    logger: Logger? = null,
    memoryCacheBytes: Long = 32L * 1024 * 1024,
    networkParallelism: Int = 4,
    localParallelism: Int = 2,
    private val variantIndex: VariantIndex = MemoryVariantIndex(),
) {
    internal val logger = logger?.fork("image-loader")
    private val memory = MemoryImageCache(memoryCacheBytes)
    private val networkGate = LifoGate(networkParallelism)
    private val localGate = LifoGate(localParallelism)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val inFlight = HashMap<String, Flight>()
    private val notFound = HashMap<String, TimeMark>()

    private class Flight(val job: Deferred<ImageBitmap>) {
        var waiters = 0
    }

    @Volatile private var memoryBudget = memoryCacheBytes

    /** New steady-state memory-cache budget (device class changed, user setting); evicts to it now. */
    fun setMemoryCacheBudget(bytes: Long) {
        memoryBudget = bytes
        memory.setBudget(bytes)
    }

    /**
     * Memory pressure (Android `onTrimMemory`, backgrounding): evict now down to [keepFraction] of
     * the budget — 0 clears — without changing the steady-state budget, so the cache regrows
     * normally once pressure passes.
     */
    fun trimMemoryCache(keepFraction: Float = 0f) {
        memory.trimTo((memoryBudget * keepFraction.coerceIn(0f, 1f)).toLong())
    }

    /**
     * The memory-cached bitmap for [request], or null — non-suspending, cheap enough for
     * composition, so an already-cached image draws on its first frame with no placeholder flash
     * (see `AsyncImage`).
     */
    fun peek(request: ImageRequest): ImageBitmap? = memory.peek(memoryKey(request))

    fun peek(url: String, maxWidth: Int, maxHeight: Int): ImageBitmap? = peek(ImageRequest.of(url, maxWidth, maxHeight))

    /** [load], or null on a failed fetch/decode — logged here, for callers that keep showing what they had. */
    suspend fun loadOrNull(request: ImageRequest): ImageBitmap? =
        try {
            load(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger?.log(loadFailureLevel(e), "load failed: ${request.key}", e)
            null
        }

    suspend fun loadOrNull(url: String, maxWidth: Int, maxHeight: Int): ImageBitmap? =
        loadOrNull(ImageRequest.of(url, maxWidth, maxHeight))

    /**
     * The image for [request], scaled down to cover (or fit) its size, aspect kept, never upscaled.
     * Suspends until loaded; throws [ImageLoadException] (the last source's reason, NotFound only
     * when every source said so) — callers showing a placeholder catch and log (see `AsyncImage`).
     *
     * [cacheInMemory] false keeps the result OUT of the memory LRU (an existing entry is still
     * used) — for callers that hand the bitmap off once at their own size bucket (media3 session
     * artwork) instead of drawing it repeatedly, so the UI's entries aren't evicted for buckets
     * nothing will ever peek.
     */
    suspend fun load(request: ImageRequest, cacheInMemory: Boolean = true): ImageBitmap {
        val key = memoryKey(request)
        memory.peek(key)?.let { return it }
        peekCovering(request)?.let { return it }
        val flight = synchronized(lock) {
            inFlight.getOrPut(key) {
                Flight(scope.async(start = CoroutineStart.LAZY) { loadUncached(request, key, cacheInMemory) })
            }.also { it.waiters++ }
        }
        flight.job.start()
        try {
            return flight.job.await()
        } finally {
            synchronized(lock) {
                flight.waiters--
                // The last caller gone (cancelled, or the load settled) retires the shared load; an
                // unfinished one is cancelled so it frees its slot.
                if (flight.waiters == 0) {
                    if (inFlight[key] === flight) inFlight.remove(key)
                    if (!flight.job.isCompleted) flight.job.cancel()
                }
            }
        }
    }

    private suspend fun loadUncached(request: ImageRequest, key: String, cacheInMemory: Boolean): ImageBitmap =
        gateFor(request).withPermit {
            memory.peek(key)?.let { return@withPermit it } // a load that finished while this one waited
            val bitmap = fromScaledCache(key) ?: try {
                fetchAndDecode(request, key)
            } catch (e: ImageLoadException) {
                // Offline or every variant gone: any size of this identity beats nothing — returned,
                // not cached under this size, so a sharp decode replaces it once a source answers.
                memory.peekLargest(request.key)?.let { stand -> return@withPermit stand }
                throw e
            }
            if (cacheInMemory) memory.put(key, bitmap)
            bitmap
        }

    private fun peekCovering(request: ImageRequest): ImageBitmap? {
        val size = requestBucket(request)
        return memory.peekCovering(request.key, size.width, size.height, scaleSuffix(request.scale))
    }

    private fun gateFor(request: ImageRequest): LifoGate =
        if (request.sources.any { it.url.startsWith("http://") || it.url.startsWith("https://") }) networkGate else localGate

    /**
     * Load [request] in the background so a later [load] of it finds it in memory — e.g. the next
     * queue item's art before the song changes. Best effort: a failure is logged, as [loadOrNull].
     */
    fun prefetch(request: ImageRequest) {
        if (memory.peek(memoryKey(request)) != null) return
        scope.launch { loadOrNull(request) }
    }

    suspend fun load(url: String, maxWidth: Int, maxHeight: Int, cacheInMemory: Boolean = true): ImageBitmap =
        load(ImageRequest.of(url, maxWidth, maxHeight), cacheInMemory)

    /**
     * The scaled image as a committed FILE in the scaled disk cache, or null when the loader has
     * no [scaledCache]. For consumers that serve bytes across a process boundary (the Android
     * artwork ContentProvider) and never draw: the memory LRU is untouched, and — unlike [load] —
     * the entry is persisted even when the source already fit the request, because the caller
     * needs a file and some schemes (`art://`) have no other on-disk form. The transient decode is
     * dropped after encoding. The path is a live LRU entry that can be evicted later — re-call on a
     * missing file rather than holding the path.
     */
    suspend fun loadFile(request: ImageRequest): okio.Path? {
        val cache = scaledCache ?: return null
        val key = memoryKey(request)
        val disk = diskKey(key)
        cache.filePath(disk)?.let { return it }
        return gateFor(request).withPermit {
            cache.filePath(disk)?.let { return@withPermit it } // raced with an identical load
            // A bitmap the UI already decoded at this bucket is persisted as-is; otherwise fetch,
            // bypassing fetchAndDecode so its downscaled-only persist policy doesn't double-write.
            val bitmap = memory.peek(key) ?: fetchFromSources(request).bitmap
            cache.put(disk, bitmap)
            cache.filePath(disk)
                ?: throw ImageLoadException(ImageLoadException.Reason.Undecodable, "scaled entry vanished after write: ${request.key}")
        }
    }

    suspend fun loadFile(url: String, maxWidth: Int, maxHeight: Int): okio.Path? = loadFile(ImageRequest.of(url, maxWidth, maxHeight))

    private suspend fun fromScaledCache(key: String): ImageBitmap? {
        val cache = scaledCache ?: return null
        return try {
            cache.get(diskKey(key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A corrupt or unreadable entry falls through to a fresh fetch (which overwrites it).
            logger?.log(Logger.Level.WARN, "scaled cache read failed: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchAndDecode(request: ImageRequest, key: String): ImageBitmap {
        val decoded = fetchFromSources(request)
        // Only a genuinely downscaled result is worth persisting: an un-shrunk image re-encoded is
        // often BIGGER than the original its fetcher already holds, and saves no decode.
        if (decoded.downscaled) {
            scaledCache?.let { cache ->
                try {
                    cache.put(diskKey(key), decoded.bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger?.log(Logger.Level.WARN, "scaled cache write failed: ${e.message}", e)
                }
            }
        }
        return decoded.bitmap
    }

    /**
     * The request's sources in [orderSources] order until one decodes; failures fall through. What
     * each attempt learns (the measured size, or NotFound) goes to the [variantIndex].
     */
    private suspend fun fetchFromSources(request: ImageRequest): DecodedImage {
        val size = requestBucket(request)
        val record = variantIndex.get(request.key)
        val failures = mutableListOf<ImageLoadException>()
        for (source in orderSources(request.sources, size, request.scale, record)) {
            val sourceKey = imageCacheKey(source.url)
            if (sourceKey in record.missing) {
                failures += ImageLoadException(ImageLoadException.Reason.NotFound, "known missing: ${source.url}")
                continue
            }
            try {
                val decoded = fetchSource(source, size, request.scale)
                variantIndex.recordFetched(request.key, sourceKey, decoded.sourceWidth, decoded.sourceHeight)
                return decoded
            } catch (e: ImageLoadException) {
                if (e.reason == ImageLoadException.Reason.NotFound) variantIndex.recordMissing(request.key, sourceKey)
                logger?.log(Logger.Level.DEBUG, "source failed (${e.reason}): ${source.url}", e)
                failures += e
            }
        }
        // The most telling failure: anything beats NotFound, which only wins if unanimous.
        val failure = failures.firstOrNull { it.reason != ImageLoadException.Reason.NotFound }
            ?: failures.firstOrNull()
            ?: throw ImageLoadException(ImageLoadException.Reason.Unsupported, "no sources: ${request.key}")
        failures.forEach { if (it !== failure) failure.addSuppressed(it) }
        throw failure
    }

    private suspend fun fetchSource(source: ImageSource, size: IntSize, scale: ImageScale): DecodedImage {
        val sourceKey = imageCacheKey(source.url)
        val missingSince = synchronized(lock) { notFound[sourceKey] }
        if (missingSince != null) {
            if (missingSince.elapsedNow() < NOT_FOUND_TTL) {
                throw ImageLoadException(ImageLoadException.Reason.NotFound, "known missing: ${source.url}")
            }
            synchronized(lock) { notFound.remove(sourceKey) }
        }
        val target = Uri.parse(source.url)
        val decoded = try {
            fetchers.firstNotNullOfOrNull { it.fetch(target, size, scale) }
                ?: throw ImageLoadException(ImageLoadException.Reason.Unsupported, "no fetcher handles: ${source.url}")
        } catch (e: ImageLoadException) {
            if (e.reason == ImageLoadException.Reason.NotFound) {
                synchronized(lock) { notFound[sourceKey] = TimeSource.Monotonic.markNow() }
            }
            throw e
        }
        return decoded.cropped(source.crop)
    }

    /** Bump [SCALED_CACHE_VERSION] when the scaling rule changes: entries scaled the old way must miss. */
    private fun diskKey(key: String): String = "$SCALED_CACHE_VERSION:$key".encodeUtf8().sha256().hex()
}

private const val SCALED_CACHE_VERSION = 3

/** A failure the fetcher already logged when it first happened repeats at DEBUG, not WARN. */
internal fun loadFailureLevel(error: Throwable): Logger.Level =
    if ((error as? ImageLoadException)?.reason == ImageLoadException.Reason.RecentlyFailed) Logger.Level.DEBUG else Logger.Level.WARN

private val NOT_FOUND_TTL = 10.minutes

/** Round the target up to a 64px step: stable cache keys across ±layout-pixel jitter. */
internal fun bucketPx(px: Int): Int = (((px.coerceIn(1, 1 shl 20) - 1) / 64) + 1) * 64

internal fun requestBucket(request: ImageRequest): IntSize =
    IntSize(bucketPx(request.size.width), bucketPx(request.size.height))

/** `key@WxH`, plus `~fit` for a fit decode (smaller than the cover decode of the same box). */
internal fun memoryKey(request: ImageRequest): String {
    val size = requestBucket(request)
    return "${request.key}@${size.width}x${size.height}${scaleSuffix(request.scale)}"
}

internal fun scaleSuffix(scale: ImageScale): String = if (scale == ImageScale.Fit) "~fit" else ""

/**
 * Sources to try for a [size] box. A source's size is what [record] measured when it was fetched,
 * else its declared size after crop. Order: those that cover the box, smallest first (already
 * fetched ones before untried ones — likely on disk); then those of unknown size, as given; then
 * the smaller ones — the best available when nothing covers: a fetched one that covers at least half
 * the box first (close enough to skip a download), then largest first.
 */
internal fun orderSources(
    sources: List<ImageSource>,
    size: IntSize,
    scale: ImageScale,
    record: VariantRecord = VariantRecord.EMPTY,
): List<ImageSource> {
    if (sources.size < 2) return sources
    val measured = sources.associateWith { record.fetched[imageCacheKey(it.url)] ?: declaredSize(it) }
    val fetched = sources.filterTo(HashSet()) { imageCacheKey(it.url) in record.fetched }
    val (known, unknown) = sources.partition { measured.getValue(it) != null }
    val (covering, smaller) = known.partition { covers(measured.getValue(it)!!, size, scale) }
    val half = IntSize((size.width + 1) / 2, (size.height + 1) / 2)
    return covering.sortedWith(compareBy<ImageSource> { it !in fetched }.thenBy { measured.getValue(it)!!.first }) +
        unknown +
        smaller.sortedWith(
            compareBy<ImageSource> { !(it in fetched && covers(measured.getValue(it)!!, half, scale)) }
                .thenByDescending { measured.getValue(it)!!.first },
        )
}

/** The declared size after crop, or null when not declared. */
internal fun declaredSize(source: ImageSource): Pair<Int, Int>? {
    if (source.width <= 0 || source.height <= 0) return null
    val height = when (source.crop) {
        SourceCrop.None -> source.height
        SourceCrop.Letterbox16x9 -> minOf(source.height, source.width * 9 / 16)
    }
    return source.width to height
}

/** An image of [sourceSize] decoded for [size] needs no upscale. */
internal fun covers(sourceSize: Pair<Int, Int>, size: IntSize, scale: ImageScale): Boolean {
    val (w, h) = sourceSize
    if (w <= 0 || h <= 0) return false
    return when (scale) {
        ImageScale.Cover -> w >= size.width && h >= size.height
        ImageScale.Fit -> w >= size.width || h >= size.height
    }
}
