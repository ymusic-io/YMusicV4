package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.SabrCacheTee
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.formatCacheKey
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.writeUmpPart
import io.ymusic.ytmedia.ump.writeUmpPartHeader
import kmpcommon.io.asAsyncSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SABR fill-on-forward: [SabrCacheTee] must pass the UMP response through **byte-for-byte** while
 * mirroring each COMPLETE segment into the cache — and drop a segment truncated by a short stream.
 */
class SabrCacheTeeTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "ymusic-sabrtee-test").absolutePath
    private val itag = 251
    private val lmt = 100L
    private val key = formatCacheKey(itag, lmt)

    private fun header(headerId: Int, startRange: Long, len: Long, seg: Int) = MediaHeader(
        headerId = headerId, videoId = "vid00000001", itag = itag, lmt = lmt, xtag = null,
        startRange = startRange, isInitSegment = false, segmentIndex = seg,
        startMs = (seg - 1) * 5000L, durationMs = 5000L, contentLength = len,
    )

    private fun Buffer.segment(header: MediaHeader, media: ByteArray) {
        writeUmpPart(UmpPart.MEDIA_HEADER, header.encode())
        writeUmpPartHeader(UmpPart.MEDIA_DATA, media.size + 1L).writeByte(header.headerId).write(media)
        writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(header.headerId)
    }

    private fun Buffer.mediaHeader(header: MediaHeader) {
        writeUmpPart(UmpPart.MEDIA_HEADER, header.encode())
    }

    private fun newCache(): MediaCache {
        FileSystem.SYSTEM.deleteRecursively(dir.toPath())
        val driver = jvmSegmentDatabaseDriver()
        val blobs = DiskMediaBlobCache(dir.toPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 0 }))
        return MediaCache(blobs, SqlDelightSegmentIndex(driver, now = { 0 }))
    }

    /** Drain [body] through the tee in 4-byte reads (every part header/payload straddles a boundary),
     *  returning the pass-through bytes so a test can assert they're byte-exact. */
    private fun drainThroughTee(body: Buffer, cache: MediaCache): ByteArray = runBlocking {
        val passed = Buffer()
        withContext(Dispatchers.IO) {
            SabrCacheTee(body.asAsyncSource(), cache).use { tee ->
                val chunk = Buffer()
                while (true) {
                    val n = tee.read(chunk, 4)
                    if (n == -1L) break
                    passed.writeAll(chunk)
                }
            }
        }
        passed.readByteArray()
    }

    @Test
    fun tees_complete_segments_and_passes_bytes_through_unchanged() = runBlocking {
        FileSystem.SYSTEM.deleteRecursively(dir.toPath())
        val driver = jvmSegmentDatabaseDriver()
        val blobs = DiskMediaBlobCache(dir.toPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 0 }))
        val cache = MediaCache(blobs, SqlDelightSegmentIndex(driver, now = { 0 }))

        val seg0 = webmSegment(10, 1)
        val seg1 = webmSegment(8, 2)
        // A third segment whose MEDIA_DATA is cut short (no MEDIA_END) — must NOT be cached.
        val seg2Partial = webmSegment(6, 3).copyOf(3)

        val body = Buffer().apply {
            segment(header(0, 0, 10, 1), seg0)
            segment(header(1, 10, 8, 2), seg1)
            // truncated tail: header promises 6 bytes, only 3 data bytes arrive, stream ends (no END)
            val h2 = header(2, 18, 6, 3)
            writeUmpPart(UmpPart.MEDIA_HEADER, h2.encode())
            writeUmpPartHeader(UmpPart.MEDIA_DATA, seg2Partial.size + 1L).writeByte(2).write(seg2Partial)
        }
        val original = body.snapshot().toByteArray()

        // Drain through the tee in tiny reads so every UMP part header/payload straddles a read boundary.
        val passed = Buffer()
        withContext(Dispatchers.IO) {
            SabrCacheTee(body.asAsyncSource(), cache).use { tee ->
                val chunk = Buffer()
                while (true) {
                    val n = tee.read(chunk, 4)
                    if (n == -1L) break
                    passed.writeAll(chunk)
                }
            }
        }

        // 1) pass-through is byte-exact.
        assertContentEquals(original, passed.readByteArray(), "passthrough must be unchanged")

        // 2) both complete segments are cached and read back byte-identical.
        assertTrue(blobs.isCached(key, 0, 10), "seg0 cached")
        assertTrue(blobs.isCached(key, 10, 8), "seg1 cached")
        val readBack = blobs.read(key, 0, 18)?.buffer()?.use { it.readByteArray() }
        assertContentEquals(seg0 + seg1, readBack, "cached bytes == segment media")

        // 3) the truncated segment left no partial span.
        assertFalse(blobs.isCached(key, 18, 6), "truncated seg2 must not be cached")
        assertFalse(blobs.isCached(key, 18, 3), "not even the partial prefix")
        assertEquals(18L, blobs.totalSize(), "only the two complete segments' bytes are stored")
    }

    @Test
    fun a_short_media_end_drops_the_segment_and_disables_further_caching() {
        val cache = newCache()
        val seg0 = webmSegment(10, 1)
        val seg2 = webmSegment(7, 3) // well-formed, but arrives after the fault
        val body = Buffer().apply {
            segment(header(0, 0, 10, 1), seg0) // complete → cached
            // seg1: header promises 8 bytes, only 5 arrive, then a MEDIA_END → short by 3 → abort + illegal.
            mediaHeader(header(1, 10, 8, 2))
            val short = webmSegment(8, 2).copyOf(5)
            writeUmpPartHeader(UmpPart.MEDIA_DATA, short.size + 1L).writeByte(1).write(short)
            writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(1)
            segment(header(2, 18, 7, 3), seg2) // after illegalState → must NOT be cached
        }
        val original = body.snapshot().toByteArray()

        val passed = drainThroughTee(body, cache)

        assertContentEquals(original, passed, "pass-through is byte-exact even though the tee gave up")
        assertTrue(cache.blobs.isCached(key, 0, 10), "the segment before the fault is cached")
        assertFalse(cache.blobs.isCached(key, 10, 8), "the short segment is dropped, not committed at wrong length")
        assertFalse(cache.blobs.isCached(key, 18, 7), "a later segment isn't cached — caching is disabled")
        assertEquals(10L, cache.blobs.totalSize(), "only the pre-fault segment's bytes are stored")
    }

    @Test
    fun media_data_overrunning_the_declared_length_disables_caching() {
        val cache = newCache()
        val body = Buffer().apply {
            segment(header(0, 0, 10, 1), webmSegment(10, 1)) // complete → cached
            // seg1: header promises 6 bytes but the MEDIA_DATA carries 9 → over-length.
            mediaHeader(header(1, 10, 6, 2))
            val over = ByteArray(9) { (it + 100).toByte() }
            writeUmpPartHeader(UmpPart.MEDIA_DATA, over.size + 1L).writeByte(1).write(over)
            writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(1)
            segment(header(2, 16, 8, 3), webmSegment(8, 2)) // after the fault → not cached
        }
        val original = body.snapshot().toByteArray()

        val passed = drainThroughTee(body, cache)

        assertContentEquals(original, passed, "pass-through stays byte-exact")
        assertTrue(cache.blobs.isCached(key, 0, 10), "the segment before the overrun is cached")
        assertFalse(cache.blobs.isCached(key, 10, 6), "the over-length segment is dropped")
        assertFalse(cache.blobs.isCached(key, 16, 8), "caching disabled after the overrun")
        assertEquals(10L, cache.blobs.totalSize())
    }

    @Test
    fun media_data_for_an_unopened_id_disables_caching() {
        val cache = newCache()
        val body = Buffer().apply {
            segment(header(0, 0, 10, 1), webmSegment(10, 1)) // complete → cached
            // MEDIA_DATA for id 7 that no MEDIA_HEADER ever opened → lost framing.
            val orphan = ByteArray(5) { it.toByte() }
            writeUmpPartHeader(UmpPart.MEDIA_DATA, orphan.size + 1L).writeByte(7).write(orphan)
            segment(header(1, 10, 8, 2), webmSegment(8, 2)) // after the fault → not cached
        }
        val original = body.snapshot().toByteArray()

        val passed = drainThroughTee(body, cache)

        assertContentEquals(original, passed, "pass-through stays byte-exact")
        assertTrue(cache.blobs.isCached(key, 0, 10), "the segment before the orphan data is cached")
        assertFalse(cache.blobs.isCached(key, 10, 8), "caching disabled after the orphan MEDIA_DATA")
        assertEquals(10L, cache.blobs.totalSize())
    }

    @Test
    fun a_media_end_for_an_unopened_id_disables_caching() {
        val cache = newCache()
        val body = Buffer().apply {
            segment(header(0, 0, 10, 1), webmSegment(10, 1)) // complete → cached
            writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(5) // MEDIA_END for id 5 — never opened
            segment(header(1, 10, 8, 2), webmSegment(8, 2)) // after the fault → not cached
        }
        val original = body.snapshot().toByteArray()

        val passed = drainThroughTee(body, cache)

        assertContentEquals(original, passed, "pass-through stays byte-exact")
        assertTrue(cache.blobs.isCached(key, 0, 10), "the segment before the stray MEDIA_END is cached")
        assertFalse(cache.blobs.isCached(key, 10, 8), "caching disabled after the stray MEDIA_END")
        assertEquals(10L, cache.blobs.totalSize())
    }

    @Test
    fun a_wrong_sized_media_end_disables_caching_before_committing() {
        val cache = newCache()
        val body = Buffer().apply {
            // seg0's header + a full MEDIA_DATA, then a 3-byte MEDIA_END (must be 1) → malformed framing.
            mediaHeader(header(0, 0, 10, 1))
            val media = webmSegment(10, 1)
            writeUmpPartHeader(UmpPart.MEDIA_DATA, media.size + 1L).writeByte(0).write(media)
            writeUmpPartHeader(UmpPart.MEDIA_END, 3).writeByte(0).write(ByteArray(2)) // size 3, not 1
            segment(header(1, 10, 8, 2), webmSegment(8, 2)) // after the fault → not cached
        }
        val original = body.snapshot().toByteArray()

        val passed = drainThroughTee(body, cache)

        assertContentEquals(original, passed, "pass-through stays byte-exact")
        assertFalse(cache.blobs.isCached(key, 0, 10), "seg0 not committed — its MEDIA_END framing was wrong")
        assertFalse(cache.blobs.isCached(key, 10, 8), "caching disabled after the malformed MEDIA_END")
        assertEquals(0L, cache.blobs.totalSize())
    }

    @Test
    fun a_duplicate_open_header_id_disables_caching() {
        val cache = newCache()
        val body = Buffer().apply {
            // Two MEDIA_HEADERs for id 0 with no MEDIA_END between → a second live segment on a live id.
            mediaHeader(header(0, 0, 9, 1))
            mediaHeader(header(0, 0, 9, 1))
            segment(header(1, 9, 9, 2), webmSegment(9, 2)) // after the fault → not cached
        }
        val original = body.snapshot().toByteArray()

        val passed = drainThroughTee(body, cache)

        assertContentEquals(original, passed, "pass-through stays byte-exact")
        assertFalse(cache.blobs.isCached(key, 9, 9), "caching disabled after the duplicate id")
        assertEquals(0L, cache.blobs.totalSize(), "nothing is cached")
    }
}
