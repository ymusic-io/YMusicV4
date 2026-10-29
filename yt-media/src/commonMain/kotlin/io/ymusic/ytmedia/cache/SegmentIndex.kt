package io.ymusic.ytmedia.cache

import io.ymusic.ytmedia.sabr.MediaHeader
import kmpcommon.WorkerThread

/**
 * The segment ⇄ (timeRange, byteRange) map SABR planning needs on top of the raw bytes in
 * [MediaBlobCache]. A **queryable hint**, not the source of truth: a row can exist while the segment's
 * bytes are still being written or were lost to a crash, so a planner always verifies a hit against
 * `blobs.isCached` before serving it (plan §3b). Port of legacy `SabrCacheDb`.
 *
 * Every call is blocking SQLite — [WorkerThread] like [MediaBlobCache], so the caller (the SABR session /
 * proxy, already off the main thread) owns threading rather than each method faking async with a dispatch.
 */
@WorkerThread
interface SegmentIndex {

    /** Record a segment (INSERT-OR-IGNORE: the first writer of a `(itag, lmt, seg)` wins). */
    fun save(header: MediaHeader)

    /** The segment whose player-time window contains [playerMs], or null. */
    fun findByStartTime(itag: Int, lmt: Long, playerMs: Long): MediaHeader?

    fun findBySegmentIndex(itag: Int, lmt: Long, seg: Int): MediaHeader?

    /** Downloader/seek resume: the furthest segment ending at or before [byteIndex], or null. */
    fun findByByteOffset(itag: Int, lmt: Long, byteIndex: Long): MediaHeader?

    /** Forget one segment, e.g. when its cached bytes are purged. */
    fun delete(itag: Int, lmt: Long, seg: Int)

    /** Bump the `download_time` of every segment of a stream (used to protect a just-downloaded track). */
    fun markDownloadTime(itag: Int, lmt: Long)

    /** Store a format's raw FORMAT_INITIALIZATION_METADATA (UMP part 42) for hit replay (INSERT-OR-REPLACE). */
    fun putFormatInit(itag: Int, lmt: Long, bytes: ByteArray)

    /** The format's stored init metadata, or null if the tee hasn't captured it yet. */
    fun formatInit(itag: Int, lmt: Long): ByteArray?

    /**
     * Prune orphan rows: any stream whose `formatCacheKey` is **not** in [keepKeys] (i.e. has no blobs)
     * and whose segments are older than the retention window is deleted. Run on startup with
     * `keepKeys = blobs.keys()` — this one rule keeps the index and the blob store consistent without a
     * cross-store transaction (plan §3b).
     */
    fun cleanup(keepKeys: Set<String>)
}
