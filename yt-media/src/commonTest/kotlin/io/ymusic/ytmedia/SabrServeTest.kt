package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SabrServe
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.serveSabr
import io.ymusic.ytmedia.proto.ProtoReader
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.ClientAbrState
import io.ymusic.ytmedia.sabr.ClientInfo
import io.ymusic.ytmedia.sabr.DownloadFile
import io.ymusic.ytmedia.sabr.FormatId
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.SabrSession
import io.ymusic.ytmedia.sabr.SabrTrack
import io.ymusic.ytmedia.sabr.StreamerContext
import io.ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import io.ymusic.ytmedia.sabr.formatCacheKey
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.readUmpPartHeader
import io.ymusic.ytmedia.ump.writeUmpPart
import io.ymusic.ytmedia.ump.writeUmpPartHeader
import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 4: [serveSabr] plans a local UMP response from cached segments, and that response re-parses through
 * [SabrSession.readResponse] into the same bytes — the round-trip that lets a re-download reuse the cache
 * with zero network.
 */
class SabrServeTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-sabrserve-test"
    private val itag = 251
    private val lmt = 100L
    private val key = formatCacheKey(itag, lmt)

    private class MemoryFile : DownloadFile {
        val data = Buffer()
        override fun length() = data.size
        override fun appendSink(): BufferedSink = data
        override fun close() {}
    }

    /** Materialize a hit's streaming body so it can be re-parsed (the serve now streams, not a Buffer). */
    private fun Source.toBuffer(): Buffer = Buffer().also { out -> buffer().use { it.readAll(out) } }

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

    /** init + 3 audio segments, 10 bytes each, contiguous over [0,40). */
    private val segments = listOf(webmSegment(10, 0, init = true), webmSegment(10, 1), webmSegment(10, 2), webmSegment(10, 3))

    private fun Buffer.putSegment(headerId: Int, seg: Int, startRange: Long, data: ByteArray, isInit: Boolean) {
        val header = MediaHeader(
            headerId = headerId, videoId = "vid", itag = itag, lmt = lmt, xtag = null,
            startRange = startRange, isInitSegment = isInit, segmentIndex = seg,
            // Media segment index 1 is the first, at playback time 0 → startMs = (seg-1)*1000.
            startMs = if (isInit) null else (seg - 1) * 1000L, durationMs = if (isInit) null else 1000L,
            contentLength = data.size.toLong(),
        ).encode()
        writeUmpPart(UmpPart.MEDIA_HEADER, header)
        writeUmpPartHeader(UmpPart.MEDIA_DATA, data.size + 1L).writeByte(headerId).write(data)
        writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(headerId)
    }

    /** A cache pre-filled with init + 3 audio segments over [0,40), via a real download parse. */
    private suspend fun filledCache(): MediaCache {
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
        // A synthesized hit replays the format's init metadata before its init segment (the tee captures it
        // on the network response; the download parse above doesn't) — store one. Its bytes are opaque to
        // the serve (replayed verbatim) and skipped on re-parse, so any payload works.
        cache.index.putFormatInit(itag, lmt, byteArrayOf(1, 2, 3, 4))
        return cache
    }

    private fun request(
        playerTimeMs: Long,
        audioOnly: Boolean = true,
        videoItag: Int? = null,
    ) = VideoPlaybackAbrRequest(
        clientAbrState = ClientAbrState(
            playerTimeMs = playerTimeMs,
            enabledTrackTypesBitfield = if (audioOnly) 1 else 0,
        ),
        selectedFormats = emptyList(),
        bufferedRanges = emptyList(),
        selectedAudioFormats = listOf(fmt.toFormatId()),
        selectedVideoFormats = listOfNotNull(videoItag?.let { FormatId(it, 1, "") }),
        streamerContext = StreamerContext(clientInfo = ClientInfo(clientName = 1), poToken = byteArrayOf(1), playbackCookie = ByteArray(0)),
        ustreamerConfig = ByteArray(0),
    )

    @Test
    fun serve_hits_the_cache_and_the_ump_reparses_to_the_same_bytes() = runTest {
        val cache = filledCache()
        val serve = cache.serveSabr(request(playerTimeMs = 0))
        assertTrue(serve is SabrServe.Hit, "a filled cache serves the request")
        serve as SabrServe.Hit
        // init + 3 segments; 40 payload bytes.
        assertEquals(4, serve.segmentCount)
        assertEquals(40, serve.cachedBytes)

        // The synthesized UMP re-parses through readResponse into a byte-identical stream.
        val outFile = MemoryFile()
        val outcome = SabrSession(random = Random(2))
            .readResponse(asyncOf(serve.ump.toBuffer()), SabrTrack(fmt, outFile), null, null)
        assertEquals(4, outcome.completedSegments)
        assertContentEquals(segments.reduce(ByteArray::plus), outFile.data.peek().readByteArray())
    }

    @Test
    fun serve_misses_when_nothing_is_cached() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir)
        val driver = testSegmentDriver()
        val cache = MediaCache(
            DiskMediaBlobCache(dir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 1_000 })),
            SqlDelightSegmentIndex(driver, now = { 1_000 }),
        )
        assertTrue(cache.serveSabr(request(playerTimeMs = 0)) is SabrServe.Miss)
    }

    @Test
    fun serve_reparse_then_reserve_is_stable() = runTest {
        // Re-download simulation: parse the cache-served UMP into a fresh file, and a second serve from the
        // same cache still hits and yields the same bytes — the reuse loop the downloader relies on.
        val cache = filledCache()
        val first = cache.serveSabr(request(0)) as SabrServe.Hit
        val fileA = MemoryFile()
        SabrSession(random = Random(3)).readResponse(asyncOf(first.ump.toBuffer()), SabrTrack(fmt, fileA), null, cache)

        val second = cache.serveSabr(request(0)) as SabrServe.Hit
        val fileB = MemoryFile()
        SabrSession(random = Random(4)).readResponse(asyncOf(second.ump.toBuffer()), SabrTrack(fmt, fileB), null, cache)

        assertContentEquals(fileA.data.peek().readByteArray(), fileB.data.peek().readByteArray())
        assertEquals(40, fileB.data.size)
    }

    private suspend fun drain(body: AsyncSource): ByteArray {
        val out = Buffer()
        val buf = Buffer()
        while (true) {
            val n = body.read(buf, 16 * 1024)
            if (n == -1L) break
            out.write(buf, buf.size)
        }
        body.close()
        return out.readByteArray()
    }

    private fun containsItag(ump: ByteArray, itag: Int): Boolean {
        val buf = Buffer().write(ump)
        while (!buf.exhausted()) {
            val h = buf.readUmpPartHeader()
            if (h.partId == UmpPart.MEDIA_HEADER) {
                val mh = MediaHeader.from(ProtoReader(buf.readByteArray(h.size)).readAllFields())
                if (mh.itag == itag) return true
            } else {
                buf.skip(h.size)
            }
        }
        return false
    }

    @Test
    fun serve_hit_audio_only_does_not_append_video() = runTest {
        val cache = filledCache()
        val proxy = LocalMediaProxy(cache, KmpHttpClient.create {}, userAgent = "ymusic-test")
        val resp = proxy.serveRequest(
            "https://r1.googlevideo.com/videoplayback?sabr=1",
            "POST",
            request(0, videoItag = 243).encode().buffer,
        )
        assertEquals(200, resp.status)
        val bytes = drain(resp.body)
        assertTrue(containsItag(bytes, itag), "audio itag missing")
        assertTrue(!containsItag(bytes, 243), "audio-only HIT must not invent a video tail")
    }
}
