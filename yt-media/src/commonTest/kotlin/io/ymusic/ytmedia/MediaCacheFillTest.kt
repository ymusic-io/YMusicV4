package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.DownloadFile
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.NextRequestPolicy
import io.ymusic.ytmedia.sabr.SabrSession
import io.ymusic.ytmedia.sabr.SabrTrack
import io.ymusic.ytmedia.sabr.formatCacheKey
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step 3 acceptance: a download routed with a [MediaCache] writes **both** the muxed file and the player
 * cache — byte spans in [DiskMediaBlobCache] and rows in [SqlDelightSegmentIndex] — and the cached bytes
 * read back identical to the file (proving the same bytes will later serve progressive/SABR).
 */
class MediaCacheFillTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-cachefill-test"

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
        itag = 251, lmt = 100, xtag = null, mimeType = "audio/webm; codecs=\"opus\"",
        clen = 40, indexRangeStart = 0, indexRangeEnd = 0, width = null, height = null,
        approxDurationMs = 3_000, audioTrackIsDefault = true, audioTrackId = null, isDrc = false,
    )

    private fun Buffer.putSegment(headerId: Int, seg: Int, startRange: Long, data: ByteArray, isInit: Boolean) {
        val header = MediaHeader(
            headerId = headerId, videoId = "vid", itag = 251, lmt = 100, xtag = null,
            startRange = startRange, isInitSegment = isInit, segmentIndex = seg,
            startMs = if (isInit) null else seg * 1000L, durationMs = if (isInit) null else 1000L,
            contentLength = data.size.toLong(),
        ).encode()
        writeUmpPart(UmpPart.MEDIA_HEADER, header)
        writeUmpPartHeader(UmpPart.MEDIA_DATA, data.size + 1L).writeByte(headerId).write(data)
        writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(headerId)
    }

    @Test
    fun a_download_populates_both_the_file_and_the_player_cache() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir)
        val driver = testSegmentDriver()
        val blobs = DiskMediaBlobCache(dir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 1_000 }))
        val index = SqlDelightSegmentIndex(driver, now = { 1_000 })
        val cache = MediaCache(blobs, index)
        val session = SabrSession(random = Random(1))
        val audio = SabrTrack(fmt, MemoryFile())

        // init(0) + seg1 + seg2 + seg3, each 10 bytes, contiguous over [0,40).
        val resp = Buffer().apply {
            writeUmpPart(
                UmpPart.NEXT_REQUEST_POLICY,
                NextRequestPolicy(60_000, 60_000, null, byteArrayOf(1), null).encode(),
            )
            putSegment(0, 0, 0, webmSegment(10, 0, init = true), isInit = true)
            putSegment(1, 1, 10, webmSegment(10, 1), isInit = false)
            putSegment(2, 2, 20, webmSegment(10, 2), isInit = false)
            putSegment(3, 3, 30, webmSegment(10, 3), isInit = false)
        }
        val outcome = session.readResponse(asyncOf(resp), audio, null, cache)
        assertEquals(4, outcome.completedSegments)

        val key = formatCacheKey(251, 100)
        val fileBytes = (audio.file as MemoryFile).data.peek().readByteArray()
        assertEquals(40, fileBytes.size)

        // Blob store: the whole stream is cached and reads back byte-identical to the file.
        assertTrue(blobs.isCached(key, 0, 40), "the full stream is cached")
        val cached = blobs.read(key, 0, 40)!!.buffer().use { it.readByteArray() }
        assertContentEquals(fileBytes, cached, "cached bytes == downloaded file bytes")

        // Segment index: a row per segment with the right byte ranges.
        val seg2 = assertNotNull(index.findBySegmentIndex(251, 100, 2))
        assertEquals(20, seg2.startRange)
        assertEquals(10, seg2.contentLength)
        // The init row is present and time-windowless.
        val init = assertNotNull(index.findBySegmentIndex(251, 100, 0))
        assertTrue(init.isInitSegment)
        // A player-time lookup finds the covering segment, as the SABR planner will use.
        assertEquals(2, assertNotNull(index.findByStartTime(251, 100, 2_500)).segmentIndex)
    }

    @Test
    fun a_segment_fill_writes_only_the_gaps_never_overlapping_spans() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir)
        val driver = testSegmentDriver()
        val blobs = DiskMediaBlobCache(dir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 1_000 }))
        val cache = MediaCache(blobs, SqlDelightSegmentIndex(driver, now = { 1_000 }))
        val key = formatCacheKey(251, 100)

        // Pre-cache two disjoint chunks of the segment's byte range, leaving a hole [500, 600).
        blobs.writer(key, 0, 500).also { it.sink.write(ByteArray(500) { 1 }); it.commit() }
        blobs.writer(key, 600, 400).also { it.sink.write(ByteArray(400) { 2 }); it.commit() }
        assertEquals(900, blobs.totalSize())

        // A SABR fill for the whole segment [0, 1000) must write ONLY the 100-byte hole.
        val whole = webmSegment(1000, 7)
        val header = MediaHeader(
            headerId = 0, videoId = "vid", itag = 251, lmt = 100, xtag = null,
            startRange = 0, isInitSegment = false, segmentIndex = 1,
            startMs = 0, durationMs = 1000, contentLength = 1000,
        )
        cache.openSegmentFill(header).also { it.sink.write(whole); it.commit() }

        assertEquals(1000, blobs.totalSize(), "only the hole was written — no overlapping re-store of cached bytes")
        assertTrue(blobs.isCached(key, 0, 1000), "the whole range is now covered")
        val hole = blobs.read(key, 500, 100)!!.buffer().use { it.readByteArray() }
        assertContentEquals(whole.copyOfRange(500, 600), hole, "the hole holds the segment's real bytes")
    }

    @Test
    fun a_truncated_segment_leaves_no_partial_span() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir)
        val driver = testSegmentDriver()
        val blobs = DiskMediaBlobCache(dir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 1_000 }))
        val index = SqlDelightSegmentIndex(driver, now = { 1_000 })
        val cache = MediaCache(blobs, index)
        val session = SabrSession(random = Random(2))
        val audio = SabrTrack(fmt, MemoryFile())

        // A MEDIA_HEADER + MEDIA_DATA for seg0 but the stream ends before MEDIA_END (truncated).
        val header = MediaHeader(
            headerId = 0, videoId = "vid", itag = 251, lmt = 100, xtag = null,
            startRange = 0, isInitSegment = true, segmentIndex = 0, startMs = null, durationMs = null,
            contentLength = 10,
        ).encode()
        val resp = Buffer().apply {
            writeUmpPart(UmpPart.MEDIA_HEADER, header)
            writeUmpPartHeader(UmpPart.MEDIA_DATA, 11).writeByte(0).write(ByteArray(10))
            // no MEDIA_END — the segment never seals
        }
        session.readResponse(asyncOf(resp), audio, null, cache)
        // The uncommitted span was aborted; nothing is servable, and no orphan .span/.tmp lingers.
        assertTrue(!blobs.isCached(key = formatCacheKey(251, 100), position = 0, length = 10))
        assertEquals(0, blobs.totalSize())
    }
}
