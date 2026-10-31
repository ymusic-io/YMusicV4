package io.ymusic.ytmedia.cache

import okio.Buffer

/** Where and why a segment failed [SegmentCheck]: [offset] is inside the segment, [head] the bytes found there. */
class SegmentCheckFailure(val offset: Long, val head: String, val reason: String) {
    /** The failure placed in the format's byte stream, for log lines. */
    fun describe(segmentStart: Long) = "at byte ${segmentStart + offset} (+$offset) [$head]: $reason"
}

/**
 * Header-only structural walk of one SABR segment: WebM elements, or fMP4 boxes, must chain exactly from the
 * segment's first byte to its last, using only the IDs / box types that belong there (a media segment is
 * Clusters and other Segment-level elements, an init one starts with the EBML header / `ftyp` and has a
 * `moov`). Payloads are never read: the walk asks for the few bytes at [next] and is fed them, from a stream
 * passing by ([StreamingSegmentCheck]) or by positional reads of stored bytes ([checkStoredSegment]).
 */
internal class SegmentCheck(private val length: Long, private val isInit: Boolean) {
    /** Offset of the next header the walk needs; everything before it is checked or skipped payload. */
    var next = 0L
        private set
    var failure: SegmentCheckFailure? = null
        private set
    val done: Boolean get() = failure != null || next >= length

    private var mp4: Boolean? = null
    private var clusterEnd = NO_CLUSTER
    private var sawMoov = false
    private var sawMoof = false
    private var sawMdat = false

    init {
        if (length <= 0) failure = SegmentCheckFailure(0, "", "empty segment")
    }

    /** How many bytes at [next] the next [step] takes: a header's worth, or what is left of the segment. */
    fun headBytes(): Int = minOf(HEAD_BYTES.toLong(), length - next).toInt()

    /** Walk the header at [next]; [head] holds exactly [headBytes] bytes from there and is not consumed. */
    fun step(head: Buffer) {
        if (done) return
        val isMp4 = mp4 ?: (head.size >= 8 && head.int32(4) in MP4_BOXES).also { mp4 = it }
        if (isMp4) stepMp4(head) else stepWebm(head)
    }

    /** The verdict once every byte was seen: the first failure, or a missing piece the walk could not flag. */
    fun finish(): SegmentCheckFailure? {
        failure?.let { return it }
        if (next != length) return SegmentCheckFailure(next, "", "segment ends after ${length - next} unchecked bytes")
        if (mp4 == true) {
            if (isInit && !sawMoov) return SegmentCheckFailure(0, "", "init segment has no moov")
            if (!isInit && !(sawMoof && sawMdat)) return SegmentCheckFailure(0, "", "media segment has no moof+mdat")
        }
        return null
    }

    private fun stepWebm(head: Buffer) {
        val h = webmHeader(head) ?: return fail(head, "invalid element header")
        val name = "0x${h.id.toString(16)}"
        val hdrEnd = next + h.headerLen
        val end = if (h.unknownSize) UNKNOWN_END else hdrEnd + h.size
        if (clusterEnd != NO_CLUSTER) {
            if (h.id in CLUSTER_CHILDREN) {
                if (h.unknownSize) return fail(head, "unknown-size cluster child $name")
                if (end > minOf(clusterEnd, length)) return fail(head, "cluster child $name overruns its cluster")
                next = end
                if (next == clusterEnd) clusterEnd = NO_CLUSTER
                return
            }
            if (clusterEnd != UNKNOWN_END || h.id !in SEGMENT_CHILDREN) return fail(head, "element $name inside a cluster")
            clusterEnd = NO_CLUSTER
        }
        when {
            next == 0L && isInit && h.id != ID_EBML -> fail(head, "init segment does not start with an EBML header")
            // Entered, not skipped: its size covers the whole stream, not just the init segment.
            h.id == ID_SEGMENT && isInit -> next = hdrEnd
            h.id == ID_CLUSTER && !isInit && h.unknownSize -> {
                next = hdrEnd
                clusterEnd = UNKNOWN_END
            }
            h.unknownSize -> fail(head, "unknown-size element $name")
            end > length -> fail(head, "element $name overruns the segment by ${end - length}")
            h.id == ID_EBML && next == 0L && isInit -> next = end
            h.id == ID_CLUSTER && !isInit -> {
                next = hdrEnd
                if (next != end) clusterEnd = end
            }
            h.id in SEGMENT_CHILDREN && h.id != ID_CLUSTER -> next = end
            else -> fail(head, "element $name does not belong in a ${if (isInit) "init" else "media"} segment")
        }
    }

    private fun stepMp4(head: Buffer) {
        if (head.size < 8) return fail(head, "truncated box header")
        val size32 = head.int32(0).toLong() and 0xFFFFFFFFL
        val type = head.int32(4)
        val size = when (size32) {
            0L -> length - next
            1L -> {
                if (head.size < 16) return fail(head, "truncated box header")
                head.int64(8)
            }
            else -> size32
        }
        if (size < 8 || (size32 == 1L && size < 16)) return fail(head, "box size $size")
        if (size > length - next) return fail(head, "box overruns the segment by ${size - (length - next)}")
        val allowed = if (isInit) MP4_INIT_BOXES else MP4_MEDIA_BOXES
        if (type !in allowed) return fail(head, "box type 0x${type.toString(16)} does not belong in a ${if (isInit) "init" else "media"} segment")
        if (next == 0L && isInit && type != BOX_FTYP) return fail(head, "init segment does not start with ftyp")
        when (type) {
            BOX_MOOV -> sawMoov = true
            BOX_MOOF -> sawMoof = true
            BOX_MDAT -> sawMdat = true
        }
        next += size
    }

    private fun fail(head: Buffer, reason: String) {
        failure = SegmentCheckFailure(next, head.snapshot(minOf(head.size, 8L).toInt()).hex(), reason)
    }

    private class WebmHeader(val id: Long, val size: Long, val unknownSize: Boolean, val headerLen: Int)

    private fun webmHeader(head: Buffer): WebmHeader? {
        if (head.size < 2) return null
        val b0 = head[0].toInt() and 0xFF
        val idLen = vintLength(b0).takeIf { it in 1..4 } ?: return null
        if (head.size <= idLen) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (head[i.toLong()].toLong() and 0xFF)
        val s0 = head[idLen.toLong()].toInt() and 0xFF
        val sizeLen = vintLength(s0).takeIf { it in 1..8 } ?: return null
        if (head.size < idLen + sizeLen) return null
        var size = (s0 and (0xFF shr sizeLen)).toLong()
        var allOnes = size == (0xFF shr sizeLen).toLong()
        for (i in 1 until sizeLen) {
            val b = head[(idLen + i).toLong()].toLong() and 0xFF
            if (b != 0xFFL) allOnes = false
            size = (size shl 8) or b
        }
        return WebmHeader(id, size, allOnes, idLen + sizeLen)
    }

    /** Bytes in an EBML variable-length integer whose first byte is [b0]; 9 when [b0] is zero (invalid). */
    private fun vintLength(b0: Int): Int {
        var mask = 0x80
        var n = 1
        while (n <= 8 && b0 and mask == 0) {
            mask = mask shr 1
            n++
        }
        return n
    }

    private fun Buffer.int32(at: Long): Int =
        ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
            ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

    private fun Buffer.int64(at: Long): Long = (int32(at).toLong() shl 32) or (int32(at + 4).toLong() and 0xFFFFFFFFL)

    private companion object {
        const val HEAD_BYTES = 16
        const val NO_CLUSTER = -1L
        const val UNKNOWN_END = Long.MAX_VALUE

        const val ID_EBML = 0x1A45DFA3L
        const val ID_SEGMENT = 0x18538067L
        const val ID_CLUSTER = 0x1F43B675L

        /** Segment children: SeekHead, Info, Tracks, Cluster, Cues, Attachments, Chapters, Tags, Void, CRC-32. */
        val SEGMENT_CHILDREN = setOf(
            0x114D9B74L, 0x1549A966L, 0x1654AE6BL, ID_CLUSTER, 0x1C53BB6BL, 0x1941A469L, 0x1043A770L,
            0x1254C367L, 0xECL, 0xBFL,
        )

        /** Cluster children: Timestamp, SilentTracks, Position, PrevSize, SimpleBlock, BlockGroup,
         *  EncryptedBlock, Void, CRC-32. */
        val CLUSTER_CHILDREN = setOf(0xE7L, 0x5854L, 0xA7L, 0xABL, 0xA3L, 0xA0L, 0xAFL, 0xECL, 0xBFL)

        const val BOX_FTYP = 0x66747970
        const val BOX_MOOV = 0x6d6f6f76
        const val BOX_MOOF = 0x6d6f6f66
        const val BOX_MDAT = 0x6d646174

        private fun fourcc(s: String) = s.fold(0) { acc, c -> (acc shl 8) or c.code }

        val MP4_SHARED = setOf("sidx", "free", "skip", "uuid", "styp", "emsg", "prft").map(::fourcc).toSet()
        val MP4_INIT_BOXES = MP4_SHARED + setOf(BOX_FTYP, BOX_MOOV, fourcc("pdin"), fourcc("meta"))
        val MP4_MEDIA_BOXES = MP4_SHARED + setOf(BOX_MOOF, BOX_MDAT, fourcc("ssix"), fourcc("mfra"))
        val MP4_BOXES = MP4_INIT_BOXES + MP4_MEDIA_BOXES
    }
}

/** A [SegmentCheck] fed with the segment's bytes in order as they stream past (never consumed). */
internal class StreamingSegmentCheck(private val length: Long, isInit: Boolean) {
    private val check = SegmentCheck(length, isInit)
    private var pos = 0L
    private val head = Buffer() // bytes from [headAt] the walk needs and has not had yet
    private var headAt = 0L

    /** The segment's next [byteCount] bytes are `[offset, offset+byteCount)` of [source]. */
    fun write(source: Buffer, offset: Long, byteCount: Long) {
        var i = 0L
        while (i < byteCount && !check.done) {
            val at = pos + i
            if (head.size == 0L) {
                if (at < check.next) {
                    i += minOf(check.next - at, byteCount - i)
                    continue
                }
                headAt = at
            }
            val take = minOf(check.headBytes() - head.size, byteCount - i)
            source.copyTo(head, offset + i, take)
            i += take
            stepHeld()
        }
        pos += byteCount
    }

    fun result(): SegmentCheckFailure? {
        if (pos != length && check.failure == null) {
            return SegmentCheckFailure(pos, "", "segment has $pos of $length bytes")
        }
        return check.finish()
    }

    /** Step through every header that starts inside the held bytes. */
    private fun stepHeld() {
        while (!check.done && head.size > 0) {
            if (check.next >= headAt + head.size) {
                head.clear()
                return
            }
            if (check.next > headAt) {
                head.skip(check.next - headAt)
                headAt = check.next
            }
            val need = check.headBytes().toLong()
            if (head.size < need) return
            check.step(Buffer().also { head.copyTo(it, 0, need) })
        }
    }
}

/**
 * Check a stored segment of [length] bytes through positional reads: [readAt] reads bytes at a segment
 * offset into a sink, like `FileHandle.read`. Reads a small window per header, so a segment costs about one
 * read per element rather than its size in bytes. Stored bytes that end early fail the check.
 */
internal fun checkStoredSegment(
    length: Long,
    isInit: Boolean,
    readAt: (position: Long, sink: Buffer, byteCount: Long) -> Long,
): SegmentCheckFailure? {
    val check = SegmentCheck(length, isInit)
    val window = Buffer()
    var windowAt = 0L
    while (!check.done) {
        val need = check.headBytes().toLong()
        if (check.next < windowAt || check.next + need > windowAt + window.size) {
            window.clear()
            windowAt = check.next
            val want = minOf(WINDOW_BYTES, length - windowAt)
            while (window.size < want) {
                val n = readAt(windowAt + window.size, window, want - window.size)
                if (n == -1L) {
                    val at = windowAt + window.size
                    return SegmentCheckFailure(at, "", "stored bytes end at +$at of $length")
                }
            }
        }
        check.step(Buffer().also { window.copyTo(it, check.next - windowAt, need) })
    }
    return check.finish()
}

private const val WINDOW_BYTES = 4096L
