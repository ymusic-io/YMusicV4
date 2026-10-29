package io.ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.ytmedia.db.SegmentDatabase
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.formatCacheKey
import kmpcommon.WorkerThread

/**
 * SqlDelight-backed [SegmentIndex]. The [driver]'s schema must already be created
 * ([SegmentDatabase.Schema]); the app builds the platform driver (JDBC / Android / sqliter) and hands it
 * over, exactly like `DiskCacheStore`. Synchronous — the queries block, matching the non-suspend
 * [WorkerThread] contract (the SABR session / proxy calling in is already off the main thread). [now]
 * supplies epoch-millis `download_time` stamps (injectable for tests).
 */
@WorkerThread
class SqlDelightSegmentIndex(
    driver: SqlDriver,
    private val now: () -> Long,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
) : SegmentIndex {

    private val queries = SegmentDatabase(driver).segmentQueries

    override fun save(header: MediaHeader) {
        // Legacy shape: init segment stores start_ms=0, end_ms=-1 (window degenerate); a real segment
        // stores [startMs, startMs+durationMs-1]. Byte range is always [startRange, startRange+len-1].
        val startMs = header.startMs ?: 0
        val endMs = startMs + (header.durationMs ?: 0) - 1
        queries.save(
            itag = header.itag.toLong(),
            lmt = header.lmt,
            seg_index = header.segmentIndex.toLong(),
            xtags = header.xtag,
            vid = header.videoId,
            start_ms = startMs,
            end_ms = endMs,
            r_start = header.startRange,
            r_end = header.startRange + header.contentLength - 1,
            download_time = now(),
        )
    }

    override fun findByStartTime(itag: Int, lmt: Long, playerMs: Long): MediaHeader? =
        queries.findByStartTime(itag.toLong(), lmt, playerMs, ::toHeader).executeAsOneOrNull()

    override fun findBySegmentIndex(itag: Int, lmt: Long, seg: Int): MediaHeader? =
        queries.findBySegmentIndex(itag.toLong(), lmt, seg.toLong(), ::toHeader).executeAsOneOrNull()

    override fun findByByteOffset(itag: Int, lmt: Long, byteIndex: Long): MediaHeader? =
        queries.findByByteOffset(itag.toLong(), lmt, byteIndex, ::toHeader).executeAsOneOrNull()

    override fun delete(itag: Int, lmt: Long, seg: Int) {
        queries.deleteSegment(itag.toLong(), lmt, seg.toLong())
    }

    override fun markDownloadTime(itag: Int, lmt: Long) {
        queries.markDownloadTime(now(), itag.toLong(), lmt)
    }

    override fun putFormatInit(itag: Int, lmt: Long, bytes: ByteArray) {
        queries.putFormatInit(itag.toLong(), lmt, bytes)
    }

    override fun formatInit(itag: Int, lmt: Long): ByteArray? =
        queries.formatInit(itag.toLong(), lmt).executeAsOneOrNull()

    override fun cleanup(keepKeys: Set<String>) {
        val cutoff = now() - retentionMillis
        queries.transaction {
            for (stream in queries.distinctStreams().executeAsList()) {
                if (formatCacheKey(stream.itag.toInt(), stream.lmt) !in keepKeys) {
                    queries.deleteStreamOlderThan(stream.itag, stream.lmt, cutoff)
                }
            }
        }
    }

    // SELECT * column order = table order. Reconstruct a MediaHeader: init (seg 0) has null time window;
    // a real segment's durationMs is (end_ms - start_ms + 1). headerId is synthetic on read (0).
    @Suppress("LongParameterList")
    private fun toHeader(
        itag: Long,
        lmt: Long,
        segIndex: Long,
        xtags: String?,
        vid: String,
        startMs: Long,
        endMs: Long,
        rStart: Long,
        rEnd: Long,
        downloadTime: Long,
    ): MediaHeader {
        val isInit = segIndex == 0L
        return MediaHeader(
            headerId = 0,
            videoId = vid,
            itag = itag.toInt(),
            lmt = lmt,
            xtag = xtags,
            startRange = rStart,
            isInitSegment = isInit,
            segmentIndex = segIndex.toInt(),
            startMs = if (isInit) null else startMs,
            durationMs = if (isInit) null else endMs - startMs + 1,
            contentLength = rEnd - rStart + 1,
        )
    }

    private companion object {
        const val DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days, as legacy
    }
}
