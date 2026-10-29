package io.ymusic.ytmedia.cache

import io.ymusic.ytmedia.sabr.MediaHeader
import kmpcommon.WorkerThread
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.Timeout
import okio.buffer

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
) {
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
        return openGapFill(header.cacheKey, header.startRange, header.contentLength)
    }

    /**
     * Decompose `[start, start+length)` into its currently-uncached gaps (via [MediaBlobCache.cachedLength])
     * and open a writer per gap, so the returned fill mirrors **only the missing bytes** and never re-stores
     * an already-cached range as an overlapping span. A fully-cached segment yields an empty fill (every byte
     * routed to the blackhole); a fully-uncached one yields a single gap over the whole region — the common
     * fresh-download case, with the same exact-region dedupe token as a plain writer.
     */
    private fun openGapFill(key: String, start: Long, length: Long): SegmentFill {
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
        return SegmentFill(start, gaps)
    }
}

/**
 * A one-segment mirror handle: write the segment's **whole** byte stream to [sink], then [commit] (or
 * [abort]). Because the segment may be partly cached already, [sink] routes bytes by offset — mirroring the
 * uncached gaps and discarding bytes that fall in cached runs — so a re-download never stores overlapping
 * spans. [commit] flushes the routed bytes and commits each gap; [abort] drops every gap's temp block.
 */
@WorkerThread
class SegmentFill internal constructor(segStart: Long, private val gaps: List<GapWriter>) {
    val sink: BufferedSink = GapRoutingSink(segStart, gaps).buffer()
    fun commit() { sink.flush(); gaps.forEach { it.writer.commit() } }
    fun abort() = gaps.forEach { it.writer.abort() }
}

/** One uncached sub-range `[start, end)` of a segment, and the writer mirroring it. */
internal class GapWriter(val start: Long, val end: Long, val writer: BlobWriter)

/**
 * Routes a segment's sequential byte stream (starting at [segStart]) to the per-gap writers, skipping bytes
 * that fall in the already-cached runs between gaps. [gaps] are sorted, disjoint and ascending.
 */
private class GapRoutingSink(segStart: Long, private val gaps: List<GapWriter>) : Sink {
    private var pos = segStart
    private var gi = 0

    override fun write(source: Buffer, byteCount: Long) {
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
