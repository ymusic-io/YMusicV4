package io.ymusic.mse

import androidx.annotation.Keep
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * JVM desktop: copy pooled BGRA into a reused BufferedImage. The Swing hole
 * paints it; Compose does not recompose per frame. Audio is routed through
 * javax.sound.sampled on non-Apple/Linux hosts (notably Windows, where the C
 * side has no MSE_AQ sink); macOS/Linux keep their native AudioQueue/Pulse paths.
 */
class NativeMseSink(val host: Long, private val surface: MseVideoSurface?) : MseHostSink {
    private val released = AtomicBoolean(false)

    /** Guards [pcmLine], [pcmRate] and [pcmChannels]: the C decode thread, the JS thread and [destroy]. */
    private val lineLock = Any()

    /** Set once per unique (rate, channels); the JVM SourceDataLine is sticky across appends. */
    private var pcmLine: SourceDataLine? = null
    private var pcmRate: Int = 0
    private var pcmChannels: Int = 0

    /** nanos of the last onPcm call; detects pause/seek gaps so we can flush stale buffered audio
     *  instead of replaying it on resume. */
    @Volatile private var lastPcmNs: Long = 0

    /** C decode thread only: failure streaks, each logged once. */
    private var writeFailing = false
    private var lineFailing = false
    private var lineFailedNs = 0L

    init {
        MseHostNative.setSink(host, this)
    }

    fun destroy(clearSurface: Boolean = true) {
        if (!released.compareAndSet(false, true)) return
        val line = synchronized(lineLock) { pcmLine.also { pcmLine = null } }
        line?.let {
            logFailure("close") {
                it.flush()
                it.close()
            }
        }
        MseHostNative.destroy(host)
        if (clearSurface) surface?.clear()
    }

    @Keep
    override fun onFrame(width: Int, height: Int, bgra: ByteBuffer) {
        val img = grab(width, height)
        blit(img, width, height, bgra)
        lastPub = img
        surface?.publish(img)
    }

    /** C decode thread. Must not throw: the JNI caller does not clear exceptions. */
    @Keep
    override fun onPcm(rate: Int, channels: Int, s16: ByteArray) {
        if (released.get() || rate <= 0 || channels <= 0 || s16.isEmpty()) return
        val line = ensurePcmLine(rate, channels) ?: return
        // Flush stale queued audio when PCM has been silent >300 ms (pause/seek): otherwise the
        // SourceDataLine replays whatever remained from before the gap, which sounds like a loop.
        val now = System.nanoTime()
        val last = lastPcmNs
        lastPcmNs = now
        try {
            if (last != 0L && now - last > 300_000_000L) line.flush()
            // Blocking: a full line stalls this (audio-only) decode thread rather than dropping samples.
            line.write(s16, 0, s16.size)
            writeFailing = false
        } catch (e: Exception) {
            // A write racing destroy()'s close is expected teardown.
            if (!released.get() && !writeFailing) {
                MseLogBridge.fire(LOG_WARN, "mse audio line write failed, YouTube audio is silent: $e")
            }
            writeFailing = true
        }
    }

    private fun ensurePcmLine(rate: Int, channels: Int): SourceDataLine? {
        val stale = synchronized(lineLock) {
            val cur = pcmLine
            if (cur != null && pcmRate == rate && pcmChannels == channels) return cur
            pcmLine = null
            cur
        }
        // Outside the lock: the drain plays out up to the line's ~500 ms and must not stall destroy().
        stale?.let {
            logFailure("format switch") {
                it.drain()
                it.stop()
                it.close()
            }
        }
        val now = System.nanoTime()
        if (lineFailing && now - lineFailedNs < LINE_RETRY_NS) return null
        val line = try {
            openLine(rate, channels)
        } catch (e: Exception) {
            if (!lineFailing) {
                MseLogBridge.fire(LOG_WARN, "mse audio line ${rate}Hz/${channels}ch unavailable, YouTube audio is silent: $e")
            }
            lineFailing = true
            lineFailedNs = now
            return null
        }
        if (lineFailing) MseLogBridge.fire(LOG_INFO, "mse audio line ${rate}Hz/${channels}ch open again")
        lineFailing = false
        synchronized(lineLock) {
            if (!released.get()) {
                pcmLine = line
                pcmRate = rate
                pcmChannels = channels
                return line
            }
        }
        logFailure("close") { line.close() }
        return null
    }

    private fun openLine(rate: Int, channels: Int): SourceDataLine {
        val fmt = AudioFormat(rate.toFloat(), 16, channels, true, false)
        val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, fmt)) as SourceDataLine
        try {
            // ~500 ms: headroom for GC and heavy-decode pauses before writes block.
            line.open(fmt, rate * channels)
            line.start()
        } catch (e: Exception) {
            logFailure("close") { line.close() }
            throw e
        }
        return line
    }

    @Keep
    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit

    /** Fired by the C side on pause/seek; see MseHost.on_audio_flush. Flush drops queued samples
     *  so the SourceDataLine doesn't play the pre-pause buffer out over seconds. */
    @Keep
    override fun onAudioFlush() {
        synchronized(lineLock) { pcmLine }?.let { logFailure("flush") { it.flush() } }
    }

    private inline fun logFailure(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            MseLogBridge.fire(LOG_WARN, "mse audio line $what failed: $e")
        }
    }

    private val bufs = arrayOfNulls<BufferedImage>(3)
    private var lastPub: BufferedImage? = null

    /** Third buffer so we never write the image Swing is painting (half-frame tear). */
    private fun grab(w: Int, h: Int): BufferedImage {
        val shown = surface?.swingShown
        for (i in 0..2) {
            val cur = bufs[i]
            if (cur != null && cur === shown) continue
            if (cur != null && cur.width == w && cur.height == h) return cur
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            bufs[i] = img
            return img
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        bufs[0] = img
        return img
    }

    private fun blit(img: BufferedImage, w: Int, h: Int, bgra: ByteBuffer) {
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        val n = minOf(pixels.size, w * h)
        bgra.order(ByteOrder.LITTLE_ENDIAN)
        bgra.position(0)
        bgra.limit((n * 4).coerceAtMost(bgra.capacity()))
        bgra.asIntBuffer().get(pixels, 0, n)
    }
}

private const val LOG_INFO = 1
private const val LOG_WARN = 2

/** A line that failed to open is retried at most this often. */
private const val LINE_RETRY_NS = 1_000_000_000L
