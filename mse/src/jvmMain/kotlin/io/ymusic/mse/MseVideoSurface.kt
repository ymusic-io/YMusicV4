package io.ymusic.mse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

actual class MseVideoSurface {
    private val _frame = MutableStateFlow<BufferedImage?>(null)
    val frame: StateFlow<BufferedImage?> = _frame.asStateFlow()

    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. */
    @Volatile
    var host: Long = 0L
        set(value) {
            field = value
            if (value != 0L && blitW > 0 && blitH > 0) {
                MseHostNative.setBlitSize(value, blitW, blitH)
            }
            if (value != 0L && nativePeer != null) attach(nativePeer)
        }

    private var blitW = 0
    private var blitH = 0

    actual fun setBlitSize(width: Int, height: Int) {
        blitW = width.coerceAtLeast(0)
        blitH = height.coerceAtLeast(0)
        if (host != 0L) {
            MseHostNative.setBlitSize(host, blitW, blitH)
        }
    }

    /** Native presented video frames (`mse_host_frames`). */
    actual fun presentedFrames(): Int = if (host == 0L) 0 else MseHostNative.frames(host)

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

    /** DAC / pull-audio sample frames (`mse_host_samples_played`). */
    fun samplesPlayed(): Long = if (host == 0L) 0L else MseHostNative.samplesPlayed(host)

    @Volatile
    var nativePeer: java.awt.Component? = null

    @Volatile
    var nativePresent: Boolean = false
        private set

    @Volatile
    var onNativePresent: ((Boolean) -> Unit)? = null

    /** Swing hole. Invoked off the decode thread; must not recompose Compose. */
    @Volatile
    var swingBlit: ((BufferedImage?) -> Unit)? = null

    /** Buffer the panel is painting. Decode must not write this image. */
    @Volatile
    var swingShown: BufferedImage? = null

    internal fun publish(frame: BufferedImage) {
        // Receiving a CPU-side frame means scale_bgra took the SW path — the HW present swapchain
        // isn't driving the Canvas for this codec/stream. Flip nativePresent off so MseVideoPanel's
        // setHardware(false) re-enables CPU blit (otherwise setImage early-returns and the Canvas
        // stays black). HW-decoded streams never reach this method (they take the D3D11 branch in
        // scale_bgra and skip on_frame entirely).
        if (nativePresent) {
            nativePresent = false
            onNativePresent?.invoke(false)
        }
        val hole = swingBlit
        if (hole != null) hole(frame)
        else _frame.value = frame
    }

    internal fun clear() {
        swingBlit?.invoke(null)
        swingShown = null
        _frame.value = null
    }

    /** Overlay HW video on [component] when the platform can. JAWT must run on the AWT thread. */
    fun attach(component: java.awt.Component?): Boolean {
        if (host == 0L) {
            nativePresent = false
            onNativePresent?.invoke(false)
            return false
        }
        fun bind(): Boolean {
            nativePresent = MseHostNative.setVideoComponent(host, component)
            onNativePresent?.invoke(nativePresent)
            return nativePresent
        }
        if (SwingUtilities.isEventDispatchThread()) return bind()
        SwingUtilities.invokeLater { bind() }
        return nativePresent
    }
}
