package io.ymusic.dynamicengine.player

import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import io.ymusic.mse.NativeMseSink
import io.ymusic.quickjs.QuickJsRuntime

internal actual fun bindNativeMse(runtime: QuickJsRuntime, surface: MseVideoSurface): NativeMseHost {
    val host = MseHostNative.create()
    if (host == 0L) error("mse_host_create failed")
    surface.host = host
    val sink = NativeMseSink(host, surface)
    runtime.attachMseHost(host, MseHostNative.vtable())
    return object : NativeMseHost {
        override fun setVideoSurface(surface: MseVideoSurface?) = Unit

        override fun setSpeed(speed: Float, tempo: Float) {
            MseHostNative.setSpeed(host, speed, tempo)
        }

        override fun close() {
            // A successor session may already own the shared surface. Unbind before the destroy: the
            // presenter must not keep a freed host as its token.
            val owned = surface.host == host
            if (owned) surface.host = 0L
            sink.destroy(clearSurface = owned)
        }
    }
}
