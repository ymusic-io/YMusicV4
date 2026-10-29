package io.ymusic.mse

import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Canvas
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.EnumSet

/** Who paints the Canvas: a GPU swapchain / overlay, or the CPU through [DesktopVideoSurface.onCpuFrame]. */
enum class PresentMode { NONE, GPU, CPU }

enum class VideoEngine { MSE, LOCAL }

/**
 * One desktop video Canvas and its native presenter, bound to the active engine: [VideoEngine.LOCAL]
 * while claimed, else [VideoEngine.MSE]. Engines [register] a binder that hands the presenter handle
 * (0 = none) to their native side.
 *
 * Locking: the presenter's lifetime and every binder call run under [bindLock], so [unregister] has
 * returned before an engine destroys its native handle. CPU frames take only [frameLock]: a binder can
 * wait on an engine's video lock while that engine's thread is inside onFrame.
 */
class DesktopVideoSurface(private val cpuOnly: Boolean = false) {
    private val _mode = MutableStateFlow(PresentMode.NONE)
    val mode: StateFlow<PresentMode> = _mode.asStateFlow()

    private val _active = MutableStateFlow(VideoEngine.MSE)

    /** The engine the presenter is bound to: [VideoEngine.LOCAL] while claimed, else [VideoEngine.MSE]. */
    val active: StateFlow<VideoEngine> = _active.asStateFlow()

    /** A Canvas is set: engines get its presenter (GPU, or CPU where the GPU path failed on it). */
    val hasCanvas: Boolean get() = canvas != null

    /** Each CPU picture (null = clear), on the presenting thread. */
    @Volatile
    var onCpuFrame: ((BufferedImage?) -> Unit)? = null

    /** The picture the panel is painting; frames are never written into it. */
    @Volatile
    var painting: BufferedImage? = null

    private class Binder(val bind: (Long) -> Unit) {
        var bound = 0L
    }

    private val bindLock = Any()

    @Volatile
    private var canvas: Canvas? = null
    private var handle = 0L
    private var sink: Sink? = null
    private val binders = EnumMap<VideoEngine, Binder>(VideoEngine::class.java)
    private val claimed = EnumSet.noneOf(VideoEngine::class.java)
    private var fitW = 0
    private var fitH = 0
    private var hidden = false

    private val frameLock = Any()
    private val bufs = arrayOfNulls<BufferedImage>(3)
    private var lastPub: BufferedImage? = null

    /** EDT, after [canvas]'s addNotify (null on removeNotify). The same Canvas again is a no-op. */
    fun setCanvas(canvas: Canvas?) {
        if (cpuOnly) return
        synchronized(bindLock) {
            if (canvas === this.canvas && handle != 0L) return
            this.canvas = canvas
            replacePresenter()
        }
    }

    /** CPU pictures fit inside [width]×[height], never upscaled; 0 on a side = unconstrained. */
    fun setSize(width: Int, height: Int) {
        synchronized(bindLock) {
            fitW = width.coerceAtLeast(0)
            fitH = height.coerceAtLeast(0)
            if (handle != 0L) VideoPresenterNative.setSize(handle, fitW, fitH)
        }
    }

    /** The Canvas screen rect (AWT y-down), for the macOS overlay. */
    fun layout(x: Int, y: Int, width: Int, height: Int) {
        synchronized(bindLock) {
            if (handle != 0L) VideoPresenterNative.layout(handle, x, y, width, height)
        }
    }

    /** macOS: hide the overlay under a menu; unhiding waits for the next frame. */
    fun setOverlayHidden(hidden: Boolean) {
        synchronized(bindLock) {
            this.hidden = hidden
            if (handle != 0L) VideoPresenterNative.setOverlayHidden(handle, hidden)
        }
    }

    /** The picture on screen, or null when there is none or the presenter cannot read it back. */
    fun snapshot(): BufferedImage? {
        synchronized(bindLock) {
            val h = handle
            if (h == 0L) return null
            val wh = IntArray(2)
            if (VideoPresenterNative.snapshot(h, null, wh) <= 0) return null
            val w = wh[0]
            val ht = wh[1]
            val img = BufferedImage(w, ht, BufferedImage.TYPE_INT_RGB)
            val pixels = (img.raster.dataBuffer as DataBufferInt).data
            val n = VideoPresenterNative.snapshot(h, pixels, wh)
            // A picture of another size arrived in between: nothing was copied for this image.
            return if (n == w * ht && wh[0] == w && wh[1] == ht) img else null
        }
    }

    /** Drops the CPU picture, unless the presenter belongs to another engine than [engine]. */
    fun clear(engine: VideoEngine) {
        if (_active.value != engine) return
        synchronized(frameLock) { lastPub = null }
        onCpuFrame?.invoke(null)
    }

    /** Replaces [engine]'s earlier binder (unbinding it first); the presenter is created on first use. */
    fun register(engine: VideoEngine, bind: (presenter: Long) -> Unit) {
        synchronized(bindLock) {
            val prev = binders[engine]
            if (prev?.bind !== bind) {
                if (prev != null && prev.bound != 0L) prev.bind(0L)
                binders[engine] = Binder(bind)
            }
            if (handle == 0L) replacePresenter() else rebind()
        }
    }

    /** No-op unless [bind] is [engine]'s current binder (a successor session may have replaced it). */
    fun unregister(engine: VideoEngine, bind: (Long) -> Unit) {
        synchronized(bindLock) {
            val b = binders[engine]
            if (b == null || b.bind !== bind) return
            binders.remove(engine)
            if (b.bound != 0L) b.bind(0L)
        }
    }

    fun claim(engine: VideoEngine) {
        synchronized(bindLock) { if (claimed.add(engine)) rebind() }
    }

    fun release(engine: VideoEngine) {
        synchronized(bindLock) { if (claimed.remove(engine)) rebind() }
    }

    /** Destroys the presenter (engines are unbound); a later [register] or [setCanvas] creates another. */
    fun close() {
        synchronized(bindLock) {
            val old = handle
            sink?.live = false
            sink = null
            handle = 0L
            canvas = null
            _mode.value = PresentMode.NONE
            rebind()
            if (old != 0L) VideoPresenterNative.destroy(old)
        }
    }

    /** bindLock. Engines move to the new presenter before the old one is destroyed. */
    private fun replacePresenter() {
        val old = handle
        sink?.live = false
        val s = Sink()
        sink = s
        handle = VideoPresenterNative.create(if (cpuOnly) null else canvas, s)
        if (handle == 0L) {
            _mode.value = PresentMode.NONE
        } else {
            if (fitW > 0 || fitH > 0) VideoPresenterNative.setSize(handle, fitW, fitH)
            if (hidden) VideoPresenterNative.setOverlayHidden(handle, true)
        }
        rebind()
        if (old != 0L) VideoPresenterNative.destroy(old)
    }

    /** bindLock. The active engine binds first, so its bind flushes the other engine's picture. */
    private fun rebind() {
        val active = if (VideoEngine.LOCAL in claimed) VideoEngine.LOCAL else VideoEngine.MSE
        binders[active]?.let { bindTo(it, handle) }
        for ((engine, b) in binders) {
            if (engine != active) bindTo(b, 0L)
        }
        _active.value = active
    }

    private fun bindTo(b: Binder, presenter: Long) {
        if (b.bound == presenter) return
        b.bind(presenter)
        b.bound = presenter
    }

    /** frameLock. Never the picture being painted nor the one published and not yet painted. */
    private fun grab(w: Int, h: Int): BufferedImage {
        val shown = painting
        var free = -1
        for (i in bufs.indices) {
            val b = bufs[i]
            if (b != null && (b === shown || b === lastPub)) continue
            if (b != null && b.width == w && b.height == h) return b
            if (free < 0) free = i
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        bufs[if (free >= 0) free else 0] = img
        return img
    }

    private inner class Sink : PresenterSink {
        @Volatile
        var live = true

        @Keep
        override fun onFrame(width: Int, height: Int, bgra: ByteBuffer) {
            if (!live) return
            val img = synchronized(frameLock) {
                val img = grab(width, height)
                val pixels = (img.raster.dataBuffer as DataBufferInt).data
                VideoPresenterNative.copyBgra(bgra, pixels, minOf(pixels.size, width * height))
                lastPub = img
                img
            }
            onCpuFrame?.invoke(img)
        }

        @Keep
        override fun onPresentMode(mode: Int) {
            if (live) _mode.value = PresentMode.entries.getOrElse(mode) { PresentMode.NONE }
        }
    }
}
