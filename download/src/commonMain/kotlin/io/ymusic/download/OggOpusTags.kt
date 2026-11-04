package io.ymusic.download

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

/**
 * Adds Vorbis comments to an Ogg Opus stream. ffmpeg's Ogg muxer refuses a picture stream, and a cover
 * as a `-metadata` value overruns the OS limit on one process argument, so this happens after the mux:
 * only the tags packet is rebuilt, and every audio page keeps its bytes (its page number and checksum follow).
 */
internal object OggOpusTags {
    private const val HEADER_SIZE = 27
    private const val MAX_SEGMENTS = 255
    private const val SEGMENT_SIZE = 255
    private const val FLAG_CONTINUED = 1

    /** Rewrites [source] into [sink] with [comments] (`KEY=value`) appended. */
    fun insert(source: BufferedSource, sink: BufferedSink, comments: List<String>) {
        val head = readPage(source)
        check(head.body.startsWith("OpusHead")) { "not an Ogg Opus stream" }
        writePage(sink, head, head.sequence)

        val tags = Buffer()
        var last: Page
        do {
            last = readPage(source)
            tags.write(last.body)
        } while (last.segments.last().toInt() and 0xFF == SEGMENT_SIZE)
        var sequence = writeTags(sink, head, withComments(tags.readByteArray(), comments), head.sequence + 1)

        while (!source.exhausted()) writePage(sink, readPage(source), sequence++)
    }

    /** A `METADATA_BLOCK_PICTURE` comment holding front-cover [jpeg], whole because a comment is one
     *  base64 string; its size fields may stay 0. */
    fun pictureComment(jpeg: ByteArray): String {
        val mime = "image/jpeg".encodeToByteArray()
        val block = Buffer()
            .writeInt(FRONT_COVER)
            .writeInt(mime.size)
            .write(mime)
            .writeInt(0) // description length
            .writeInt(0) // width
            .writeInt(0) // height
            .writeInt(0) // color depth
            .writeInt(0) // indexed colors
            .writeInt(jpeg.size)
            .write(jpeg)
        return "METADATA_BLOCK_PICTURE=" + block.readByteString().base64()
    }

    private const val FRONT_COVER = 3

    private fun withComments(packet: ByteArray, comments: List<String>): ByteArray {
        val input = Buffer().write(packet)
        check(input.readUtf8(8) == "OpusTags") { "missing OpusTags packet" }
        val vendor = input.readByteArray(input.readIntLe().toLong())
        val existing = input.readIntLe()
        val entries = input.readByteArray(commentBytes(input.copy(), existing))
        val out = Buffer()
            .writeUtf8("OpusTags")
            .writeIntLe(vendor.size)
            .write(vendor)
            .writeIntLe(existing + comments.size)
            .write(entries)
        comments.forEach { comment ->
            val bytes = comment.encodeToByteArray()
            out.writeIntLe(bytes.size).write(bytes)
        }
        return out.readByteArray()
    }

    /** Stops after [count] comments: the packet may carry padding past them. */
    private fun commentBytes(buffer: Buffer, count: Int): Long {
        val total = buffer.size
        repeat(count) { buffer.skip(buffer.readIntLe().toLong()) }
        return total - buffer.size
    }

    /** Splits [packet] over pages, ending it with a segment shorter than [SEGMENT_SIZE]. Returns the next page number. */
    private fun writeTags(sink: BufferedSink, head: Page, packet: ByteArray, firstSequence: Int): Int {
        val lacing = ByteArray(packet.size / SEGMENT_SIZE + 1) { SEGMENT_SIZE.toByte() }
        lacing[lacing.lastIndex] = (packet.size % SEGMENT_SIZE).toByte()
        var sequence = firstSequence
        var offset = 0
        var segment = 0
        while (segment < lacing.size) {
            val segments = lacing.copyOfRange(segment, minOf(segment + MAX_SEGMENTS, lacing.size))
            val size = segments.sumOf { it.toInt() and 0xFF }
            val flags = if (segment == 0) 0 else FLAG_CONTINUED
            segment += segments.size
            val granule = if (segment == lacing.size) GRANULE_ZERO else GRANULE_NO_PACKET_ENDS
            val page = Page(flags, granule, head.serial, segments, packet.copyOfRange(offset, offset + size))
            writePage(sink, page, sequence++)
            offset += size
        }
        return sequence
    }

    private val GRANULE_ZERO = ByteArray(8)
    private val GRANULE_NO_PACKET_ENDS = ByteArray(8) { -1 }

    private class Page(
        val flags: Int,
        val granule: ByteArray,
        val serial: ByteArray,
        val segments: ByteArray,
        val body: ByteArray,
        val sequence: Int = 0,
    )

    private fun ByteArray.startsWith(text: String): Boolean =
        size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

    private fun readPage(source: BufferedSource): Page {
        val header = source.readByteArray(HEADER_SIZE.toLong())
        check(header.startsWith("OggS")) { "not an Ogg page" }
        val segments = source.readByteArray((header[26].toInt() and 0xFF).toLong())
        val body = source.readByteArray(segments.sumOf { (it.toInt() and 0xFF).toLong() })
        val sequence = Buffer().write(header, 18, 4).readIntLe()
        return Page(header[5].toInt(), header.copyOfRange(6, 14), header.copyOfRange(14, 18), segments, body, sequence)
    }

    private fun writePage(sink: BufferedSink, page: Page, sequence: Int) {
        val out = Buffer()
            .writeUtf8("OggS")
            .writeByte(0)
            .writeByte(page.flags)
            .write(page.granule)
            .write(page.serial)
            .writeIntLe(sequence)
            .writeIntLe(0)
            .writeByte(page.segments.size)
            .write(page.segments)
            .write(page.body)
        val bytes = out.readByteArray()
        val crc = crc32(bytes)
        Buffer().writeIntLe(crc).readByteArray().copyInto(bytes, CRC_OFFSET)
        sink.write(bytes)
    }

    private const val CRC_OFFSET = 22

    private val CRC_TABLE = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) { value = if (value < 0) (value shl 1) xor 0x04C11DB7 else value shl 1 }
        value
    }

    /** Ogg's CRC: polynomial 0x04C11DB7, no reflection, no final inversion. */
    private fun crc32(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}
