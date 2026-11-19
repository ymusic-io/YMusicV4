package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * In-memory LRU of decoded bitmaps, bounded by estimated pixel bytes (w×h×4). One plain lock
 * around a couple of map operations — cheap enough for [peek]'s composition-time calls because the
 * lock is never held across decode or I/O (put receives an already-decoded bitmap), so the
 * worst-case wait is a few map ops.
 */
internal class MemoryImageCache(initialMaxBytes: Long) {
    private val lock = SynchronizedObject()
    private val map = LinkedHashMap<String, ImageBitmap>()
    private val standIns = HashSet<String>()
    private var totalBytes = 0L
    private var maxBytes = initialMaxBytes

    /** [includeStandIns] false misses an entry put as a stand-in. */
    fun peek(key: String, includeStandIns: Boolean = true): ImageBitmap? = synchronized(lock) {
        if (!includeStandIns && key in standIns) null else map.remove(key)?.also { map[key] = it } // re-insert = most recently used
    }

    /**
     * The smallest cached size of identity [key] (entries `<key>@<w>x<h><suffix>`) that is at least
     * [width]×[height] with the same [suffix] — a larger decode draws fine — LRU order untouched.
     */
    fun peekCovering(key: String, width: Int, height: Int, suffix: String): ImageBitmap? = synchronized(lock) {
        var best: ImageBitmap? = null
        var bestArea = Long.MAX_VALUE
        forEachSize(key) { w, h, entrySuffix, bitmap ->
            val area = w.toLong() * h
            if (entrySuffix == suffix && w >= width && h >= height && area < bestArea) {
                best = bitmap
                bestArea = area
            }
        }
        best
    }

    /** The largest cached decode of identity [key], any size — a stand-in when every source failed. */
    fun peekLargest(key: String): ImageBitmap? = synchronized(lock) {
        var best: ImageBitmap? = null
        forEachSize(key) { _, _, _, bitmap -> if (best == null || bitmap.byteSize() > best!!.byteSize()) best = bitmap }
        best
    }

    private inline fun forEachSize(key: String, block: (Int, Int, String, ImageBitmap) -> Unit) {
        val prefix = "$key@"
        for ((entryKey, bitmap) in map) {
            if (!entryKey.startsWith(prefix)) continue
            val match = SIZE_SUFFIX.matchEntire(entryKey.substring(prefix.length)) ?: continue
            block(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3], bitmap)
        }
    }

    fun put(key: String, bitmap: ImageBitmap, standIn: Boolean = false): Unit = synchronized(lock) {
        map.remove(key)?.let { totalBytes -= it.byteSize() }
        map[key] = bitmap
        if (standIn) standIns += key else standIns -= key
        totalBytes += bitmap.byteSize()
        // The fresh insert is last in iteration order, so it only evicts itself when it ALONE
        // exceeds the budget — callers hold their own reference for display, so that's just
        // "too big to cache", and a zero budget cleanly disables the cache.
        evictTo(maxBytes)
    }

    /** New steady-state budget; evicts down to it immediately. */
    fun setBudget(bytes: Long): Unit = synchronized(lock) {
        maxBytes = bytes
        evictTo(bytes)
    }

    /** One-shot eviction down to [bytes] (memory pressure); the budget is unchanged. */
    fun trimTo(bytes: Long): Unit = synchronized(lock) {
        evictTo(bytes)
    }

    private fun evictTo(target: Long) {
        val it = map.entries.iterator()
        while (totalBytes > target && it.hasNext()) {
            val eldest = it.next()
            it.remove()
            standIns -= eldest.key
            totalBytes -= eldest.value.byteSize()
        }
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * 4

    private companion object {
        val SIZE_SUFFIX = Regex("(\\d+)x(\\d+)(~fit)?")
    }
}
