package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.serveProgressive
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.DownloadFile
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.SabrSession
import io.ymusic.ytmedia.sabr.SabrTrack
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.writeUmpPart
import io.ymusic.ytmedia.ump.writeUmpPartHeader
import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Step 5 — the cross-protocol payoff: bytes filled via **SABR** serve a **progressive `range=`** read.
 * A progressive `/videoplayback` URL carries only `itag`+`lmt` (no videoId), and because a format's
 * segments are contiguous in byte space, `serveProgressive` reads any byte window straight off the same
 * spans SABR wrote — even a window that straddles several segment/span boundaries.
 */
class ServeProgressiveTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-progressive-test"
    private val itag = 251
    private val lmt = 100L

    private class MemoryFile : DownloadFile {
        val data = Buffer()
        override fun length() = data.size
        override fun appendSink(): BufferedSink = data
        override fun close() {}
    }

    private fun asyncOf(bytes: Buffer): BufferedAsyncSource = BufferedAsyncSource(
        object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long): Long =
                if (bytes.exhausted()) -1 else bytes.read(sink, byteCount)
            override fun close() {}
        },
    )

    private val fmt = AdaptiveFormat(
        itag = itag, lmt = lmt, xtag = null, mimeType = "audio/webm; codecs=\"opus\"",
        clen = 40, indexRangeStart = 0, indexRangeEnd = 0, width = null, height = null,
        approxDurationMs = 3_000, audioTrackIsDefault = true, audioTrackId = null, isDrc = false,
    )

    private fun Buffer.putSegment(headerId: Int, seg: Int, startRange: Long, data: ByteArray, isInit: Boolean) {
        val header = MediaHeader(
            headerId = headerId, videoId = "vid", itag = itag, lmt = lmt, xtag = null,
            startRange = startRange, isInitSegment = isInit, segmentIndex = seg,
            startMs = if (isInit) null else (seg - 1) * 1000L, durationMs = if (isInit) null else 1000L,
            contentLength = data.size.toLong(),
        ).encode()
        writeUmpPart(UmpPart.MEDIA_HEADER, header)
        writeUmpPartHeader(UmpPart.MEDIA_DATA, data.size + 1L).writeByte(headerId).write(data)
        writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(headerId)
    }

    /** init, seg1, seg2, seg3: 10 bytes each over [0,40), payloads distinct per segment. */
    private val segments = listOf(webmSegment(10, 0, init = true), webmSegment(10, 1), webmSegment(10, 2), webmSegment(10, 3))
    private val stream = segments.reduce(ByteArray::plus)

    /** Fill [0,40) with [segments] via a SABR parse. */
    private suspend fun sabrFilledCache(): MediaCache {
        FileSystem.SYSTEM.deleteRecursively(dir)
        val driver = testSegmentDriver()
        val cache = MediaCache(
            DiskMediaBlobCache(dir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 1_000 })),
            SqlDelightSegmentIndex(driver, now = { 1_000 }),
        )
        val resp = Buffer().apply {
            segments.forEachIndexed { i, data -> putSegment(i, i, i * 10L, data, isInit = i == 0) }
        }
        SabrSession(random = Random(1)).readResponse(asyncOf(resp), SabrTrack(fmt, MemoryFile()), null, cache)
        return cache
    }

    private suspend fun MediaCache.progressive(start: Long, end: Long): ByteArray? =
        serveProgressive(itag, lmt, start, end)?.buffer()?.use { it.readByteArray() }

    @Test
    fun a_sabr_filled_key_serves_a_progressive_range_across_segment_boundaries() = runTest {
        val cache = sabrFilledCache()
        // bytes=5-24 spans three spans: the tail of init[0,10), all of seg1[10,20), the head of seg2[20,30).
        assertContentEquals(stream.copyOfRange(5, 25), cache.progressive(5, 24))
    }

    @Test
    fun a_progressive_read_of_the_whole_stream_matches_the_download() = runTest {
        val cache = sabrFilledCache()
        assertContentEquals(stream, cache.progressive(0, 39))
    }

    @Test
    fun a_progressive_read_past_the_cached_bytes_misses() = runTest {
        val cache = sabrFilledCache()
        assertNull(cache.progressive(30, 60), "a range beyond cached bytes → miss (network)")
        assertNull(cache.progressive(0, -1), "an open-ended range is never a full-cover hit")
    }
}
