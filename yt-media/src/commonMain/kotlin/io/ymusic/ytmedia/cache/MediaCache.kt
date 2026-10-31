package io.ymusic.ytmedia.cache

import io.ymusic.ytmedia.sabr.MediaHeader
import kmpcommon.WorkerThread
import kmpcommon.log.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.use

/**
 * The facade the downloader and (later) the player-interception layer both use to fill and serve the
 * player cache. It ties the byte substrate ([blobs]) to the segment metadata ([index]) so one stored
 * copy of a segment's bytes answers progressive / UMP / SABR reads alike (plan §3c).
 *
 * This step wires the **fill** side into the SABR downloader ([openSegmentFill]); serving
 * (`serveSabr` / `serveProgressive`) lands in the following steps.
 */
class MediaCache(
    val blobs: MediaBlobCache,
    val index: SegmentIndex,
    /** Lifetime counters; null keeps none. */
    val stats: CacheStats? = null,
    private val logger: Logger? = null,
) {
    private val intactLock = SynchronizedObject()
    private val intact = LinkedHashSet<String>() // segments whose stored bytes passed checkStored, oldest first

    /**
     * Begin mirroring one segment into the cache. The metadata row is saved **now** (on the MEDIA_HEADER,
     * a queryable hint) while the bytes are committed later on [SegmentFill.commit] (the MEDIA_END, the
     * truth) — so a crash in between leaves an orphan row that `index.cleanup(blobs.keys())` prunes, with
     * no cross-store transaction (plan §3b). No byte of the segment is readable before that commit, and an
     * abort leaves none behind. The returned writer is a no-op if these bytes are already cached or being
     * written elsewhere.
     */
    fun openSegmentFill(header: MediaHeader): SegmentFill {
        index.save(header)
        return openGapFill(header.cacheKey, header.startRange, header.contentLength, header.isInitSegment)
    }

    /**
     * Whether [segment]'s stored bytes pass the structural [SegmentCheck]; walked once per process, since
     * stored spans are immutable. A failing segment is purged — its spans and its index row — so planning
     * treats it as a miss and the next network fetch refills it. False too when the segment is not fully
     * cached right now. Throws [MediaCacheStorageException] when a span cannot be read.
     */
    @WorkerThread
    fun checkStored(segment: MediaHeader): Boolean {
        val id = "${segment.cacheKey} ${segment.startRange} ${segment.contentLength}"
        if (synchronized(intactLock) { id in intact }) return true
        val stored = blobs.read(segment.cacheKey, segment.startRange, segment.contentLength) ?: return false
        val failure = stored.use { checkStoredSegment(segment.contentLength, segment.isInitSegment, it::readAt) }
        if (failure == null) {
            synchronized(intactLock) {
                intact += id
                if (intact.size > INTACT_MAX) intact.remove(intact.first())
            }
            return true
        }
        logger?.log(
            Logger.Level.WARN,
            "corrupt cached segment purged (itag=${segment.itag} seg=${segment.segmentIndex} " +
                "bytes=${segment.startRange}..${segment.startRange + segment.contentLength - 1}) " +
                failure.describe(segment.startRange),
        )
        blobs.invalidate(segment.cacheKey, segment.startRange, segment.contentLength)
        index.delete(segment.itag, segment.lmt, segment.segmentIndex)
        return false
    }

    /**
     * Decompose `[start, start+length)` into its currently-uncached gaps (via [MediaBlobCache.cachedLength])
     * and open a writer per gap, so the returned fill mirrors **only the missing bytes** and never re-stores
     * an already-cached range as an overlapping span. A fully-cached segment yields an empty fill (every byte
     * routed to the blackhole); a fully-uncached one yields a single gap over the whole region — the common
     * fresh-download case, with the same exact-region dedupe token as a plain writer.
     */
    private fun openGapFill(key: String, start: Long, length: Long, isInit: Boolean): SegmentFill {
        val gaps = ArrayList<GapWriter>()
        var pos = start
        val end = start + length
        while (pos < end) {
            val cached = blobs.cachedLength(key, pos, end - pos)
            if (cached > 0) { pos += cached; continue } // a cached run — skip it, don't rewrite
            if (cached == 0L) break // defensive: cachedLength only returns 0 when nothing remains
            val hole = -cached
            gaps += GapWriter(pos, pos + hole, blobs.writer(key, pos, hole, publishOnCommit = true))
            pos += hole
        }
        return SegmentFill(start, length, isInit, gaps)
    }

    private companion object {
        const val INTACT_MAX = 4096
    }
}

/**
 * A one-segment mirror handle: write the segment's **whole** byte stream to [sink], then [commit] (or
 * [abort]). Because the segment may be partly cached already, [sink] routes bytes by offset — mirroring the
 * uncached gaps and discarding bytes that fall in cached runs — so a re-download never stores overlapping
 * spans. Every byte also feeds a structural [SegmentCheck]; [commit] publishes the gaps only when the whole
 * segment passed it, [abort] drops every gap's blocks.
 */
@WorkerThread
class SegmentFill internal constructor(
    segStart: Long,
    length: Long,
    isInit: Boolean,
    private val gaps: List<GapWriter>,
) {
    private val check = StreamingSegmentCheck(length, isInit)
    val sink: BufferedSink = GapRoutingSink(segStart, gaps, check).buffer()

    /** Publish the mirrored gaps, or — when the segment's bytes failed the check — drop them and return why. */
    fun commit(): SegmentCheckFailure? {
        sink.flush()
        val failure = check.result()
        if (failure != null) abort() else gaps.forEach { it.writer.commit() }
        return failure
    }

    fun abort() = gaps.forEach { it.writer.abort() }
}

/** One uncached sub-range `[start, end)` of a segment, and the writer mirroring it. */
internal class GapWriter(val start: Long, val end: Long, val writer: BlobWriter)

/**
 * Routes a segment's sequential byte stream (starting at [segStart]) to the per-gap writers, skipping bytes
 * that fall in the already-cached runs between gaps, and shows every byte to [check]. [gaps] are sorted,
 * disjoint and ascending.
 */
private class GapRoutingSink(
    segStart: Long,
    private val gaps: List<GapWriter>,
    private val check: StreamingSegmentCheck,
) : Sink {
    private var pos = segStart
    private var gi = 0

    override fun write(source: Buffer, byteCount: Long) {
        check.write(source, 0, byteCount)
        var remaining = byteCount
        while (remaining > 0) {
            while (gi < gaps.size && pos >= gaps[gi].end) gi++ // drop gaps fully behind the cursor
            if (gi >= gaps.size) { source.skip(remaining); pos += remaining; return } // only cached bytes left
            val gap = gaps[gi]
            if (pos < gap.start) {
                val skip = minOf(gap.start - pos, remaining) // a cached run before the gap — drop it
                source.skip(skip); pos += skip; remaining -= skip
            } else {
                val w = minOf(gap.end - pos, remaining) // inside the gap — mirror to its writer
                gap.writer.sink.write(source, w); pos += w; remaining -= w
            }
        }
    }

    override fun flush() = gaps.forEach { it.writer.sink.flush() }
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = gaps.forEach { it.writer.sink.close() }
}
