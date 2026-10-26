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

    /** Set once per unique (rate, channels); the JVM SourceDataLine is sticky across appends. */
    private var pcmLine: SourceDataLine? = null
    private var pcmRate: Int = 0
    private var pcmChannels: Int = 0

    /** nanos of the last onPcm call; detects pause/seek gaps so we can flush stale buffered audio
     *  instead of replaying it on resume. */
    @Volatile private var lastPcmNs: Long = 0

    init {
        MseHostNative.setSink(host, this)
    }

    fun destroy(clearSurface: Boolean = true) {
        if (!released.compareAndSet(false, true)) return
        pcmLine?.runCatching { drain(); stop(); close() }
        pcmLine = null
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

    @Keep
    override fun onPcm(rate: Int, channels: Int, s16: ByteArray) {
        if (released.get() || rate <= 0 || channels <= 0 || s16.isEmpty()) return
        val line = ensurePcmLine(rate, channels) ?: return
        // Flush stale queued audio when PCM has been silent >300 ms (pause/seek): otherwise the
        // SourceDataLine replays whatever remained from before the gap, which sounds like a loop.
        val now = System.nanoTime()
        val last = lastPcmNs
        if (last != 0L && now - last > 300_000_000L) {
            runCatching { line.flush() }
        }
        lastPcmNs = now
        // Blocking write: when the line's internal buffer is full, SourceDataLine.write stalls the
        // decode thread until samples drain. Dropping (previous non-blocking variant) produced
        // audible pops on heavy video decode (1440p AV1 sw-decode on CPU). Audio decode runs on its
        // own thread so stalling here does not slow video.
        runCatching { line.write(s16, 0, s16.size) }
    }

    private fun ensurePcmLine(rate: Int, channels: Int): SourceDataLine? {
        val existing = pcmLine
        if (existing != null && pcmRate == rate && pcmChannels == channels) return existing
        existing?.runCatching { drain(); stop(); close() }
        pcmLine = null
        val fmt = AudioFormat(rate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) return null
        val line = runCatching { AudioSystem.getLine(info) as SourceDataLine }.getOrNull() ?: return null
        // ~500 ms buffer: enough headroom to absorb GC and heavy-decode pauses before writes block
        // (shorter buffers meant 1440p playback kept hitting the backpressure path and clicking).
        val bufBytes = rate * channels * 2 / 2
        val opened = runCatching {
            line.open(fmt, bufBytes)
            line.start()
        }
        if (opened.isFailure) {
            runCatching { line.close() }
            return null
        }
        pcmLine = line
        pcmRate = rate
        pcmChannels = channels
        return line
    }

    @Keep
    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit

    /** Fired by the C side on pause/seek; see MseHost.on_audio_flush. Flush drops queued samples
     *  so the SourceDataLine doesn't play the pre-pause buffer out over seconds. */
    @Keep
    override fun onAudioFlush() {
        pcmLine?.runCatching { flush() }
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
