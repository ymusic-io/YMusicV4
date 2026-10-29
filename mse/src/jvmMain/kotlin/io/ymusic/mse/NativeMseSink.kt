package io.ymusic.mse

import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean

/** JVM desktop host callbacks. Video goes through [MseVideoSurface.video]'s presenter; audio stays in native C. */
class NativeMseSink(val host: Long, private val surface: MseVideoSurface?) : MseHostSink {
    private val released = AtomicBoolean(false)

    init {
        MseHostNative.setSink(host, this)
    }

    fun destroy(clearSurface: Boolean = true) {
        if (!released.compareAndSet(false, true)) return
        MseHostNative.destroy(host)
        if (clearSurface) surface?.video?.clear(VideoEngine.MSE)
    }

    /** Only for the JNI method lookup: every desktop mse_host plays its audio through its AudioQueue DAC. */
    @Keep
    override fun onPcm(rate: Int, channels: Int, s16: ByteArray) = Unit

    @Keep
    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit
}
