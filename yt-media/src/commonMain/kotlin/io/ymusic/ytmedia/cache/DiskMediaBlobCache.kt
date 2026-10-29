package io.ymusic.ytmedia.cache

import kmpcommon.WorkerThread
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileHandle
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Sink
import okio.Timeout
import okio.buffer

/**
 * okio-`FileSystem`-backed [MediaBlobCache]. Committed bytes live in **immutable span files**
 * `{root}/{key}/{start}_{len}.span`; a persistent [BlobIndex] (SQLite via [SqlDelightBlobIndex]) is the
 * index of record — which ranges are cached, the total cached size, and LRU recency — so coverage, the
 * byte budget and eviction order never depend on scanning directories and all survive a restart. Read
 * pins are the one piece of state that stays in memory (they are per-process: a span being
 * read right now must not be evicted). The index (and pins / in-flight) are guarded by one
 * [lock] — small critical sections around point/range queries; the large byte IO (spilling and reading
 * span files) happens outside it.
 *
 * This reproduces media3 `SimpleCache`'s six concurrency guarantees without media3 (see
 * `docs/player/sabr-cache-plan.md` §3a):
 *
 * 1. **Atomic commit / immutable spans** — a block streams to a `.tmp`, then `atomicMove`s to its
 *    `{start}_{len}.span` and only then gets a row. Readers open committed `.span` files, so a partial
 *    write is never visible.
 * 2. **Per-block commit** — a region is cut into blocks of ≤[maxSpanBytes]; each commits independently, so
 *    an interrupted write keeps the blocks it finished (valid bytes for their ranges) and a retry resumes.
 *    A `publishOnCommit` writer (a segment fill) instead keeps its sealed blocks as temps until
 *    [BlobWriter.commit] publishes them all; an abort drops them all.
 * 3. **Single-writer-per-region dedupe** — an in-flight set keyed `(key, start, len)`; [writer] hands back
 *    a no-op sink when the exact region is already committed (a row exists) or in flight.
 * 4. **Read pinning** — [read] ref-count-pins its covering spans; eviction never touches a pinned span.
 * 5. **Plan-then-reacquire** — [isCached] is a hint; [read] re-plans coverage under the lock (over the
 *    in-memory span cache, which is kept in sync with eviction), returning null (→ network) on any gap and
 *    holding no lock across plan→serve. Each span's file is validated when its key loads into the
 *    span cache (a phantom row whose file is gone is dropped then), and [read] opens every planned file
 *    before returning, so a file deleted behind the index (the OS trimming the cache dir) is a miss.
 *    Any other storage IO failure (open/read of a span, span write/commit) throws [MediaCacheStorageException].
 * 6. **LRU eviction** — on commit, delete least-recently-used **unpinned** spans (rows + files) until
 *    `SUM(len) ≤` the budget ([budgetBytes] until [setMaxSize]).
 */
@WorkerThread
class DiskMediaBlobCache(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val index: BlobIndex,
    budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val maxSpanBytes: Long = DEFAULT_MAX_SPAN_BYTES,
    private val logger: Logger? = null,
) : MediaBlobCache {

    /** One contiguous slice to read from one span: `[skip, skip+take)` of the span `(key,start,len)`. */
    private class Chunk(val key: String, val start: Long, val len: Long, val skip: Long, val take: Long)

    private val lock = SynchronizedObject()
    private var budget = budgetBytes
    private val pins = mutableMapOf<String, Int>() // spanId → read-pin count (in-memory, per-process)
    private val inFlight = mutableSetOf<String>() // region tokens "key pos len" — known-length write dedupe
    private val activeWriters = mutableSetOf<Long>() // live writer ids; temp files carry the id in their name
    private var writerId = 0L // monotonic per-writer id counter

    // Hot span lists: key → its committed spans (sorted by start), for the ~SPAN_CACHE_MAX_KEYS keys most
    // recently touched. Repeated isCached/read/writer for the video being played or downloaded then hit
    // memory instead of re-querying SQLite every call. Insertion order = LRU (move-to-recent on hit).
    // Loaded lazily and validated against disk; kept in sync with [index] at every insert/delete.
    private val spanCache = LinkedHashMap<String, MutableList<BlobSpan>>()

    override fun isCached(key: String, position: Long, length: Long): Boolean = synchronized(lock) {
        cover(key, cachedSpans(key), position, length) != null
    }

    override fun cachedLength(key: String, position: Long, length: Long): Long = synchronized(lock) {
        if (length <= 0) return@synchronized 0L
        val spans = cachedSpans(key)
        val end = position + length
        // Greedy sweep from position (spans sorted by start), stopping at the first gap.
        var cursor = position
        var i = 0
        while (cursor < end) {
            var bestEnd = cursor
            while (i < spans.size && spans[i].start <= cursor) {
                if (spans[i].end > bestEnd) bestEnd = spans[i].end
                i++
            }
            if (bestEnd <= cursor) break // gap at cursor
            cursor = bestEnd
        }
        if (cursor > position) return@synchronized minOf(cursor, end) - position // cached run
        // position itself is a hole → distance to the next cached byte (capped at length), negated.
        val nextStart = spans.firstOrNull { it.start > position }?.start ?: end
        -(minOf(nextStart, end) - position)
    }

    override fun read(key: String, position: Long, length: Long): PinnedSource? {
        val chunks = synchronized(lock) {
            // Coverage over the validated in-memory span list (files were checked when it loaded, and it's
            // kept in sync with eviction, so no per-read file re-check — plan-then-reacquire holds via the
            // lock + the synced cache).
            val plan = cover(key, cachedSpans(key), position, length) ?: return@synchronized null
            plan.forEach {
                pin(spanId(it.key, it.start, it.len))
                index.touch(it.key, it.start, it.len) // LRU touch (stamps now), persisted
            }
            plan
        } ?: return null
        // Every span opens up front, deliberately: a missing file becomes a cache miss here rather than a failure
        // mid-stream, and an open handle keeps the bytes readable even if the file is deleted under us.
        val handles = ArrayList<FileHandle>(chunks.size)
        for (chunk in chunks) {
            try {
                handles += fileSystem.openReadOnly(pathOf(chunk.key, chunk.start, chunk.len))
            } catch (e: Throwable) {
                handles.forEach { it.close() }
                val missing = e is FileNotFoundException
                synchronized(lock) {
                    chunks.forEach { unpin(spanId(it.key, it.start, it.len)) }
                    if (missing) {
                        index.delete(chunk.key, chunk.start, chunk.len)
                        // Drop the key's resident list so its next load re-validates every row against disk.
                        spanCache.remove(chunk.key)
                    }
                }
                if (!missing) throw if (e is IOException) MediaCacheStorageException("span open: ${e.message}", e) else e
                logger?.log(Logger.Level.WARN, "span file missing, cache miss", e)
                return null
            }
        }
        return StitchedSource(chunks, handles)
    }

    override fun writer(key: String, position: Long, length: Long, publishOnCommit: Boolean): BlobWriter {
        if (length < 0) {
            // Unknown length: no region dedupe (the region isn't known). Just a unique writer id for
            // collision-free temp names; per-block dedupe in publish still avoids re-storing bytes.
            val id = synchronized(lock) { nextWriterId() }
            return RealBlobWriter(key, position, regionToken = null, id = id, publishOnCommit)
        }
        val token = flightToken(key, position, length)
        val id = synchronized(lock) {
            // Exact region already cached or being written → no-op.
            if (cover(key, cachedSpans(key), position, length) != null || token in inFlight) return NoOpBlobWriter()
            inFlight += token
            nextWriterId()
        }
        return RealBlobWriter(key, position, regionToken = token, id = id, publishOnCommit)
    }

    /** A process-unique id per writer, recorded in [activeWriters] so the maintenance worker can tell a
     *  live writer's temp from a crashed one. Call under [lock]. */
    private fun nextWriterId(): Long {
        writerId += 1
        activeWriters += writerId
        return writerId
    }

    override fun keys(): Set<String> = synchronized(lock) { index.distinctKeys() }

    override fun totalSize(): Long = synchronized(lock) { index.totalSize() }

    override val maxSize: Long get() = synchronized(lock) { budget }

    override fun setMaxSize(bytes: Long) {
        val free = usableSpaceBytes(root)
        val capped = if (free == null) bytes else minOf(bytes, free + totalSize() - FREE_SPACE_RESERVE_BYTES)
        val victims = synchronized(lock) {
            budget = capped.coerceAtLeast(0)
            planEviction(0)
        }
        victims.forEach { fileSystem.delete(it, mustExist = false) }
    }

    private fun pin(id: String) { pins[id] = (pins[id] ?: 0) + 1 }

    private fun unpin(id: String) {
        val n = pins[id] ?: return
        if (n <= 1) pins.remove(id) else pins[id] = n - 1
    }

    // --- hot span-list cache -------------------------------------------------------------------------

    /** The key's committed spans (sorted by start), from an in-memory LRU of ~[SPAN_CACHE_MAX_KEYS] keys.
     *  A miss loads from [index] and validates each span's file holds exactly its length, dropping a
     *  phantom row (file gone) or a torn span (a power loss can persist the commit's rename without its
     *  bytes) as it loads. Kept in sync by [addSpanToCache]/[removeSpanFromCache] at every mutation, so it
     *  never diverges from [index]. Call under [lock]. */
    private fun cachedSpans(key: String): MutableList<BlobSpan> {
        spanCache.remove(key)?.let { spanCache[key] = it; return it } // hit → move to most-recent
        val loaded = ArrayList<BlobSpan>()
        for (span in index.spansForKey(key)) {
            val path = pathOf(span.key, span.start, span.len)
            val size = fileSystem.metadataOrNull(path)?.let { it.size ?: span.len }
            if (size == span.len) {
                loaded += span
            } else {
                index.delete(span.key, span.start, span.len)
                if (size != null) {
                    logger?.log(Logger.Level.WARN, "span ${span.start}_${span.len} holds $size bytes, dropped")
                    fileSystem.delete(path, mustExist = false)
                }
            }
        }
        spanCache[key] = loaded
        while (spanCache.size > SPAN_CACHE_MAX_KEYS) spanCache.remove(spanCache.keys.first())
        return loaded
    }

    /** Reflect a just-committed span in the resident list (if any), keeping it sorted by start. */
    private fun addSpanToCache(key: String, start: Long, len: Long) {
        val list = spanCache[key] ?: return
        if (list.any { it.start == start && it.len == len }) return // INSERT-OR-IGNORE duplicate
        val at = list.indexOfFirst { it.start > start }
        if (at < 0) list.add(BlobSpan(key, start, len)) else list.add(at, BlobSpan(key, start, len))
    }

    /** Reflect an evicted / dropped span in the resident list (if any). */
    private fun removeSpanFromCache(key: String, start: Long, len: Long) {
        spanCache[key]?.removeAll { it.start == start && it.len == len }
    }

    // --- covering (greedy max-reach; tolerates overlapping spans) ------------------------------------

    /**
     * Chunks exactly covering `[position, position+length)`, or null on any gap. **[spans] must be sorted
     * by `start` ascending** ([BlobIndex.spansForKey] guarantees it). One O(n) greedy sweep: at each cursor
     * take the reachable span (`start ≤ cursor`) with the furthest `end`, advancing a single pointer [i]
     * through [spans] that never rewinds — a span left behind ends at or before the cursor, so it can never
     * help a later cursor. Overlap is tolerated (bytes are identical by the contiguity invariant). Call
     * under [lock].
     */
    private fun cover(key: String, spans: List<BlobSpan>, position: Long, length: Long): List<Chunk>? {
        if (length <= 0) return emptyList()
        if (spans.isEmpty()) return null
        val end = position + length
        var cursor = position
        var i = 0
        val chunks = ArrayList<Chunk>()
        while (cursor < end) {
            // Among spans starting at or before the cursor, the one reaching furthest past it. `bestEnd`
            // starts at `cursor`, so only spans that actually cover it (end > cursor) can win.
            var best: BlobSpan? = null
            var bestEnd = cursor
            while (i < spans.size && spans[i].start <= cursor) {
                val s = spans[i]
                if (s.end > bestEnd) { bestEnd = s.end; best = s }
                i++
            }
            val b = best ?: return null // gap: nothing covers the cursor
            val take = minOf(bestEnd - cursor, end - cursor)
            chunks += Chunk(key, b.start, b.len, skip = cursor - b.start, take = take)
            cursor += take
        }
        return chunks
    }

    // --- eviction ------------------------------------------------------------------------------------

    /** Under [lock], delete unpinned LRU spans (rows now) until `SUM(len)+incoming ≤ budget`; returns their
     *  files to delete outside the lock. */
    private fun planEviction(incoming: Long): List<Path> {
        var total = index.totalSize()
        if (total + incoming <= budget) return emptyList()
        val victims = ArrayList<Path>()
        for (row in index.lruOrder()) {
            if (total + incoming <= budget) break
            if ((pins[spanId(row.key, row.start, row.len)] ?: 0) > 0) continue // pinned → never evict
            index.delete(row.key, row.start, row.len)
            removeSpanFromCache(row.key, row.start, row.len)
            total -= row.len
            victims += pathOf(row.key, row.start, row.len)
        }
        return victims
    }

    // Crashed writers leave `{blockStart}__{writerId}.tmp` files; they're inert (readers go through the
    // index, never a dir scan) and reclaimed by the background maintenance worker, which must skip any temp
    // whose {writerId} is still in [activeWriters] (a live writer's temp) — not on the write path anymore.

    // --- writer --------------------------------------------------------------------------------------

    /** A full temp block of `[start, start+len)`, not yet a span. */
    private class SealedBlock(val tmp: Path, val start: Long, val len: Long)

    private inner class RealBlobWriter(
        private val key: String,
        position: Long,
        private val regionToken: String?, // known-length: the [inFlight] dedupe token to release; else null
        private val id: Long, // unique per writer → collision-free temp names + [activeWriters] membership
        private val publishOnCommit: Boolean,
    ) : BlobWriter {
        private val dir = root / key
        private var blockStart = position
        private var blockBytes = 0L
        private var tmp: Path? = null
        private var tmpSink: BufferedSink? = null
        private val sealed = ArrayList<SealedBlock>() // publishOnCommit: blocks waiting for commit()
        private var released = false

        override val sink: BufferedSink = RelaySink().buffer()

        /** Spills the region to disk block-by-block: each `write` fills the current temp until it reaches
         *  [maxSpanBytes], then seals it and starts the next. */
        private inner class RelaySink : Sink {
            override fun write(source: Buffer, byteCount: Long) = storage("span write") {
                var remaining = byteCount
                while (remaining > 0) {
                    val s = tmpSink ?: openBlock()
                    val chunk = minOf(maxSpanBytes - blockBytes, remaining)
                    s.write(source, chunk)
                    blockBytes += chunk
                    remaining -= chunk
                    if (blockBytes >= maxSpanBytes) sealBlock()
                }
            }
            override fun flush() { tmpSink?.flush() }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { tmpSink?.flush() }
        }

        // Temp name = `{blockStart}__{writerId}.tmp`. blockStart alone can collide between two writers whose
        // current block starts at the same offset (e.g. a progressive and a SABR writer), so the per-writer
        // [id] keeps it unique; both parts are plain numbers, so the name needs no escaping.
        private fun openBlock(): BufferedSink = storage("span open") {
            fileSystem.createDirectories(dir)
            val t = dir / "${blockStart}__$id.$TMP_EXT"
            tmp = t
            fileSystem.sink(t).buffer().also { tmpSink = it }
        }

        /** Close the current temp as a block; publish it now, or hold it for [commit]. No-op when empty. */
        private fun sealBlock(): Unit = storage("span commit") {
            val t = tmp ?: return@storage
            tmpSink?.close(); tmpSink = null; tmp = null
            val len = blockBytes; blockBytes = 0
            val start = blockStart; blockStart += len
            if (len == 0L) { fileSystem.delete(t, mustExist = false); return@storage }
            val block = SealedBlock(t, start, len)
            if (publishOnCommit) sealed += block else publish(block)
        }

        /** Move a sealed block to `{start}_{len}.span` and index it. */
        private fun publish(block: SealedBlock): Unit = storage("span commit") {
            val start = block.start
            val len = block.len
            val spanPath = dir / spanName(start, len)
            if (fileSystem.exists(spanPath)) {
                // Identical range already committed (resume, or a concurrent equal writer beat us). Bytes are
                // identical by the contiguity invariant — drop our temp; don't atomicMove over a file a
                // reader may hold open. Make sure it's indexed (INSERT OR IGNORE).
                fileSystem.delete(block.tmp, mustExist = false)
                synchronized(lock) { index.insert(key, start, len); addSpanToCache(key, start, len) }
                return@storage
            }
            fileSystem.atomicMove(block.tmp, spanPath)
            val victims = synchronized(lock) {
                index.insert(key, start, len)
                addSpanToCache(key, start, len)
                planEviction(0)
            }
            logger?.debug { "commit span $key ${start}_$len writer=$id" }
            victims.forEach { fileSystem.delete(it, mustExist = false) }
        }

        override fun commit() = finish(commitTail = true)
        override fun abort() = finish(commitTail = false)

        private fun finish(commitTail: Boolean) {
            if (released) return
            released = true
            try {
                if (commitTail) {
                    sink.flush() // push whatever the buffered façade still holds into RelaySink → the temp
                    sealBlock()
                    while (sealed.isNotEmpty()) publish(sealed.removeAt(0))
                } else {
                    tmpSink?.close()
                    tmp?.let { fileSystem.delete(it, mustExist = false) }
                    tmpSink = null; tmp = null
                }
            } finally {
                try {
                    // Held blocks of an abort, or the rest of a commit that failed part-way.
                    sealed.forEach { fileSystem.delete(it.tmp, mustExist = false) }
                    sealed.clear()
                } finally {
                    // Released even when the tail fails, or the region stays "in flight" (unwritable) forever.
                    val victims = synchronized(lock) {
                        regionToken?.let { inFlight -= it } // known-length only
                        activeWriters -= id
                        planEviction(0)
                    }
                    victims.forEach { fileSystem.delete(it, mustExist = false) }
                }
            }
        }
    }

    /** Reads pre-planned, pinned [chunks] in order from their already-open [handles]; [close] closes the
     *  handles and unpins (idempotent). Inner so it shares [lock]. */
    private inner class StitchedSource(
        private val chunks: List<Chunk>,
        private val handles: List<FileHandle>,
    ) : PinnedSource {
        private var idx = 0
        private var current: BufferedSource? = null
        private var currentRemaining = 0L
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "closed" }
            while (true) {
                val src = current
                if (src != null && currentRemaining > 0) {
                    val n = storage("span read") { src.read(sink, minOf(byteCount, currentRemaining)) }
                    if (n == -1L) { currentRemaining = 0; continue }
                    currentRemaining -= n
                    return n
                }
                closeCurrent()
                if (idx >= chunks.size) return -1
                val chunk = chunks[idx]
                // Seek to the span offset. `source().skip(skip)` would read-and-discard the prefix — up to a
                // whole span for a read starting deep into it.
                current = handles[idx].source(fileOffset = chunk.skip).buffer()
                idx++
                currentRemaining = chunk.take
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            if (closed) return
            closed = true
            closeCurrent()
            handles.forEach { it.close() }
            synchronized(lock) { chunks.forEach { unpin(spanId(it.key, it.start, it.len)) } }
        }

        /** Close the current span's source then its handle (okio handle close is idempotent, so [close]
         *  closing every handle again is safe). */
        private fun closeCurrent() {
            current?.close(); current = null
            if (idx > 0) handles[idx - 1].close()
        }
    }

    private inline fun <T> storage(op: String, block: () -> T): T = try {
        block()
    } catch (e: MediaCacheStorageException) {
        throw e
    } catch (e: IOException) {
        throw MediaCacheStorageException("$op: ${e.message}", e)
    }

    private fun pathOf(key: String, start: Long, len: Long): Path = root / key / spanName(start, len)

    private fun spanId(key: String, start: Long, len: Long) = "$key $start $len"

    private fun flightToken(key: String, position: Long, length: Long) = "$key $position $length"

    private companion object {
        const val DEFAULT_BUDGET_BYTES = 512L * 1024 * 1024
        const val FREE_SPACE_RESERVE_BYTES = 100L * 1024 * 1024
        const val DEFAULT_MAX_SPAN_BYTES = 5L * 1024 * 1024 // 5 MB span blocks
        const val SPAN_CACHE_MAX_KEYS = 10 // hot span-lists kept in memory (≈ current + queued videos)
        const val SPAN_EXT = "span"
        const val TMP_EXT = "tmp"

        fun spanName(start: Long, len: Long) = "${start}_$len.$SPAN_EXT"
    }
}

/** Discards its bytes — returned when the region is already committed or being written elsewhere. */
private class NoOpBlobWriter : BlobWriter {
    override val sink: BufferedSink = okio.blackholeSink().buffer()
    override fun commit() { /* nothing written */ }
    override fun abort() { /* nothing written */ }
}
