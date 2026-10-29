package io.ymusic.mse

import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

actual object MseHostNative {
    private var frameScratch = ByteArray(0)
    private val frameMeta = IntArray(3)
    private val audioOutputs = ConcurrentHashMap<Long, MseAudioOutput>()

    init {
        System.loadLibrary("mseffmpegjni")
    }

    @JvmStatic
    actual external fun vtable(): Long

    actual fun create(): Long {
        val host = createHost()
        if (host != 0L) audioOutputs[host] = MseAudioOutput(host)
        return host
    }

    actual fun destroy(handle: Long) {
        audioOutputs.remove(handle)?.release()
        destroyHost(handle)
    }

    @JvmStatic
    private external fun createHost(): Long

    @JvmStatic
    private external fun destroyHost(handle: Long)

    @JvmStatic
    actual external fun frames(handle: Long): Int

    @JvmStatic
    actual external fun samplesPlayed(handle: Long): Long

    actual fun copyFrame(handle: Long, afterGen: Int): NativeMseFrame? {
        if (handle == 0L) return null
        var n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
        if (n <= 0) return null
        if (n > frameScratch.size) {
            frameScratch = ByteArray(n)
            n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
            if (n <= 0 || n > frameScratch.size) return null
        }
        return NativeMseFrame(frameMeta[0], frameMeta[1], frameMeta[2], frameScratch)
    }

    @JvmStatic
    private external fun copyFrameBytes(handle: Long, afterGen: Int, meta: IntArray, dest: ByteArray): Int

    @JvmStatic
    external fun setVideoSurface(handle: Long, surface: Surface?)

    @JvmStatic
    external fun videoSize(handle: Long, outWh: IntArray): Boolean

    @JvmStatic
    actual external fun setVolume(handle: Long, vol: Float)

    @JvmStatic
    actual external fun setSpeed(handle: Long, speed: Float, tempo: Float)

    @JvmStatic
    actual external fun setPitch(handle: Long, pitch: Float)

    @JvmStatic
    actual external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic
    actual external fun setBass(handle: Long, db: Float)

    @JvmStatic
    actual external fun setVirtualizer(handle: Long, width: Float)

    actual external fun setPreamp(handle: Long, db: Float)

    @JvmStatic
    actual external fun pcmLevels(handle: Long, out: FloatArray): Int

    @JvmStatic
    external fun audioSessionId(handle: Long): Int

    @JvmStatic
    external fun videoFailure(handle: Long): String?

    @JvmStatic
    external fun dacAttach(handle: Long, attached: Boolean)

    @JvmStatic
    external fun dacPoll(handle: Long, dst: ByteBuffer, cap: Int, waitMs: Int, state: IntArray): Int

    @JvmStatic
    external fun dacReport(handle: Long, gen: Int, position: Long, written: Long, pending: Boolean, bufferUs: Long)

    @JvmStatic
    external fun dacSession(handle: Long, session: Int)

    /**
     * Replaces the process-wide decoder table the player's `navigator.mediaCapabilities` answers from
     * (`mse_caps.h`); until it is set, the player keeps its pre-table behaviour. [rows] is n x 7 ints:
     * codec (1 H.264, 2 HEVC, 3 VP8, 4 VP9, 5 AV1), max bit depth, widest chroma (1 4:2:0, 2 4:2:2,
     * 3 4:4:4), max width, max height (either orientation), max fps at that size (0 = no limit), hw (0/1).
     * Must run before the player engine boots.
     */
    @JvmStatic
    external fun setDecoderCaps(rows: IntArray)
}
