package io.ymusic.ytmedia.cache

import kmpcommon.WorkerThread
import okio.Buffer
import okio.BufferedSink
import okio.IOException
import okio.Source

/**
 * The byte-range substrate under the player cache: "give me / here are bytes `[position, position+length)`
 * of stream [key]". One [key] is a format stream (`formatCacheKey(itag, lmt)` = `"2_{itag}_{lmt}"`), and
 * because a format's segments are contiguous in byte space, the same stored bytes answer a progressive
 * `range=` read, a UMP GET, and a synthesized SABR segment alike (see `docs/player/sabr-cache-plan.md` §1).
 *
 * The store is deliberately positional (unlike `CacheStore`, which is keyed-blob with no seek): committed
 * bytes live in **immutable span files** `{root}/{key}/{start}_{len}.span`, so reads never see a torn write.
 * The interesting part is the concurrency model reproduced from media3's `SimpleCache` by hand — atomic
 * commit, per-block resume, single-writer dedupe, read pinning, plan-then-reacquire, LRU — documented on
 * [DiskMediaBlobCache]. The in-memory span index is guarded internally, so concurrent calls from multiple
 * worker threads are safe.
 */
@WorkerThread
interface MediaBlobCache {

    /**
     * Does the union of committed spans fully cover `[position, position+length)` **right now**? A
     * planning *hint* only — never a lock. A true here can become a miss by the time [read] runs (a race
     * with eviction), which is exactly why callers plan with this and then re-acquire with [read],
     * treating a null there as a network miss (plan-then-reacquire).
     */
    fun isCached(key: String, position: Long, length: Long): Boolean

    /**
     * How much of `[position, position+length)` is **contiguously** cached from [position] — mirrors
     * media3 `SimpleCache.getCachedLength`:
     * - a **positive** `n` ≤ [length]: `[position, position+n)` is cached (serve `n` from cache);
     * - a **negative** `-m`: [position] is a hole and `[position, position+m)` is uncached (the next
     *   cached byte is at `position+m`) — fetch that hole from the network;
     * - `0` only when [length] is 0.
     *
     * The split-serve primitive: `n = cachedLength(k, p, len)` → `n == len` is a full hit;
     * `0 < n < len` means serve `[p, p+n)` from cache (via [read]) and request `[p+n, p+len)` from the
     * network; `n < 0` is a plain miss for `[p, p-n)`.
     */
    fun cachedLength(key: String, position: Long, length: Long): Long

    /**
     * Pin the spans covering `[position, position+length)` and return an immutable, stitched [Source] over
     * exactly those bytes — or **null if not fully covered at this instant**. Pinned spans are never
     * evicted while the returned source is open; the caller **must close it** to unpin. A null result is
     * the signal to fall through to the network — including when a planned span's file was deleted behind the
     * index. Throws [MediaCacheStorageException] on any other storage failure opening or reading a span.
     */
    fun read(key: String, position: Long, length: Long): PinnedSource?

    /**
     * A writer for a region starting at [position]. Bytes stream to [BlobWriter.sink]; the caller ends
     * with [BlobWriter.commit] (persist the trailing partial block) or [BlobWriter.abort] (drop it). The
     * cache seals immutable span blocks as they fill, so [length] is only ever used for dedupe, never for
     * the writing itself:
     * - a **non-negative** [length] is the exact region `[position, position+length)` — returns a
     *   **no-op writer** when that region is already committed or already being written by another worker,
     *   so player readahead and a download fetching the same segment never double-write;
     * - a **negative** [length] means the total is **unknown** (a stream): the writer stores whatever it
     *   is given, block by block, until commit. No whole-region dedupe (only the per-block one on commit),
     *   so it never returns a no-op.
     *
     * With [publishOnCommit] no block becomes readable before [BlobWriter.commit], and [BlobWriter.abort]
     * drops every block — for bytes that are only trustworthy as a whole (a segment checked at its end).
     */
    fun writer(key: String, position: Long, length: Long, publishOnCommit: Boolean = false): BlobWriter

    /**
     * Drop every committed span overlapping `[position, position+length)` — bytes found corrupt. The range
     * (and the rest of any span straddling its edges) becomes a miss at once; a reader already holding one of
     * those spans keeps reading it, and its file is deleted after the last such reader closes.
     */
    fun invalidate(key: String, position: Long, length: Long)

    /** Every stream key with at least one committed span — the `keepKeys` set for `SegmentIndex.cleanup`. */
    fun keys(): Set<String>

    /** Total bytes of all committed spans (the LRU budget is measured against this). */
    fun totalSize(): Long

    /** The LRU budget in bytes; 0 means caching is off. */
    val maxSize: Long

    /**
     * Set the budget to [bytes], capped so the cache never takes the volume's last 100 MB, and evict
     * down to it now.
     */
    fun setMaxSize(bytes: Long)
}

/**
 * The cache's backing storage failed under it — a span open/read or a span write/commit hit an IO error
 * (disk full, directory gone). Unlike a miss, this means the whole on-disk cache is suspect.
 */
class MediaCacheStorageException(message: String, cause: Throwable) : IOException(message, cause)

/** True when this failure, or any cause under it, is a [MediaCacheStorageException]. */
fun Throwable.isMediaCacheStorageFault(): Boolean =
    generateSequence(this) { it.cause }.any { it is MediaCacheStorageException }

/** A stitched read over pinned, immutable spans. Closing it unpins them (idempotent). */
interface PinnedSource : Source {
    /**
     * Positional read of up to [byteCount] bytes at [position] (relative to the source's first byte) into
     * [sink]; -1 at the end. For a source not read sequentially: a sequential read closes the spans it passes.
     */
    fun readAt(position: Long, sink: Buffer, byteCount: Long): Long
}

/**
 * A single region's writer. [sink] takes the region's bytes; the cache spills them to a temp file and
 * commits immutable span blocks as they fill. Finish with exactly one of [commit] / [abort]; both are
 * idempotent and release the in-flight lock for the region.
 */
@WorkerThread
interface BlobWriter {
    val sink: BufferedSink

    /** Commit the trailing partial block (and, for a `publishOnCommit` writer, every held block) and release
     *  the region. Blocks already committed mid-stream stay. */
    fun commit()

    /** Drop the uncommitted temp block and release the region. Blocks committed mid-stream still stay
     *  (they are valid cached bytes for their own ranges — the point of per-block commit); a
     *  `publishOnCommit` writer committed none, and drops the ones it held. */
    fun abort()
}
