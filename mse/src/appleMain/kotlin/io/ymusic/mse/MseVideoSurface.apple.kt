package io.ymusic.mse

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.Volatile

actual class MseVideoSurface {
    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. */
    @Volatile
    var host: Long = 0L

    /** Borrowed `AVSampleBufferDisplayLayer*`. Kotlin owns the layer. */
    @OptIn(ExperimentalForeignApi::class)
    var layer: COpaquePointer? = null

    actual fun setBlitSize(width: Int, height: Int) = Unit

    actual fun presentedFrames(): Int = if (host == 0L) 0 else MseHostNative.frames(host)

    actual fun droppedFrames(): Int = if (host == 0L) 0 else MseHostNative.droppedFrames(host)

    actual fun videoDecoder(): String = if (host == 0L) "" else MseHostNative.videoDecoder(host)

    actual fun setVolume(volume: Float) {
        if (host == 0L) return
        MseHostNative.setVolume(host, volume)
    }

    actual fun setAudioEffect(
        bands: FloatArray,
        bass: Float,
        virtualizer: Float,
        preamp: Float,
    ) {
        if (host == 0L) return
        MseHostNative.setEq(host, bands)
        MseHostNative.setBass(host, bass)
        MseHostNative.setVirtualizer(host, virtualizer)
        MseHostNative.setPreamp(host, preamp)
    }

    actual fun pcmLevels(out: FloatArray): Int =
        if (host == 0L || out.isEmpty()) 0 else MseHostNative.pcmLevels(host, out)

    actual fun audioSessionId(): Int = 0

    actual fun videoFailure(): String? = null
}
