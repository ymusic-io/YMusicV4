package io.ymusic.mse

import androidx.annotation.Keep
import java.awt.Canvas
import java.nio.ByteBuffer

/**
 * Callbacks of one native presenter (`vpresent_jni.c`). They must not call a presenter function: onFrame
 * runs under the presenter's lock, onPresentMode under the sink's.
 */
interface PresenterSink {
    /** CPU mode, on the presenting thread: [bgra] holds [width]×[height] BGRA pixels for this call only. */
    @Keep fun onFrame(width: Int, height: Int, bgra: ByteBuffer)

    /** A [PresentMode] ordinal, from any thread; calls are serialised and the last one is current. */
    @Keep fun onPresentMode(mode: Int)
}

/** The video presenters of desktop Canvases (`vpresent*.c`): handles are native `VPresenter*`. */
internal object VideoPresenterNative {
    init {
        MseHostNative.vtable()
    }

    /**
     * EDT, after the Canvas's addNotify. A D3D11 swapchain (Windows) or VideoToolbox overlay (macOS) on
     * [canvas]; a CPU presenter when [canvas] is null, on Linux, or when the GPU path cannot present
     * there. Reports its first mode to [sink] before returning. 0 = failed.
     */
    @JvmStatic external fun create(canvas: Canvas?, sink: PresenterSink): Long

    /** The Canvas is gone: drops its surface now; engines still bound only see their frames dropped. */
    @JvmStatic external fun destroy(handle: Long)

    /** CPU pictures fit inside [width]×[height], never upscaled; 0 on a side = unconstrained. */
    @JvmStatic external fun setSize(handle: Long, width: Int, height: Int)

    /** macOS overlay: the Canvas screen rect (AWT y-down). No-op elsewhere. */
    @JvmStatic external fun layout(handle: Long, x: Int, y: Int, width: Int, height: Int)

    /** macOS overlay: hide the view (a menu over the Canvas); unhiding waits for the next frame. */
    @JvmStatic external fun setOverlayHidden(handle: Long, hidden: Boolean)

    /**
     * The pixel count of the picture on screen and its size in [wh]; copied into [rgb] (a TYPE_INT_RGB
     * raster) only when it fits. 0 = none (a GPU presenter that cannot read its picture back).
     */
    @JvmStatic external fun snapshot(handle: Long, rgb: IntArray?, wh: IntArray): Int

    /** memcpy [n] BGRA pixels into a TYPE_INT_RGB raster. */
    @JvmStatic external fun copyBgra(bgra: ByteBuffer, rgb: IntArray, n: Int)
}
