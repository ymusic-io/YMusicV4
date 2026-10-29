package io.ymusic.ytmedia

/**
 * A WebM segment of exactly [size] bytes whose payload is all [fill]: an EBML header element for an init
 * segment, a Void element for a media one. Fixtures need real container structure: the cache refuses to
 * store or serve a segment that fails its structural check.
 */
internal fun webmSegment(size: Int, fill: Int, init: Boolean = false): ByteArray {
    val id = if (init) byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) else byteArrayOf(0xEC.toByte())
    val width = (1..8).first { (size - id.size - it).toLong() in 0L until (1L shl (7 * it)) - 1 }
    val body = size - id.size - width
    val sizeField = ByteArray(width) { i -> (body.toLong() shr (8 * (width - 1 - i))).toByte() }
    sizeField[0] = (sizeField[0].toInt() or (0x80 shr (width - 1))).toByte()
    return id + sizeField + ByteArray(body) { fill.toByte() }
}
