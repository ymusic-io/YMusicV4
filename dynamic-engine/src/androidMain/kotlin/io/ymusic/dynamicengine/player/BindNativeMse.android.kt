package io.ymusic.dynamicengine.player

import io.ymusic.mse.MseDecoderCaps
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import io.ymusic.quickjs.QuickJsRuntime
import kmpcommon.log.Logger

private const val DECODER_TABLE_WAIT_MS = 3_000L

internal actual fun bindNativeMse(runtime: QuickJsRuntime, surface: MseVideoSurface): NativeMseHost {
    val host = MseHostNative.create()
    if (host == 0L) error("mse_host_create failed")
    surface.host = host
    MseHostNative.setVideoSurface(host, surface.surface)
    runtime.attachMseHost(host, MseHostNative.vtable())
    return object : NativeMseHost {
        override fun setVideoSurface(surface: MseVideoSurface?) {
            MseHostNative.setVideoSurface(host, surface?.surface)
        }

        override fun setSpeed(speed: Float, tempo: Float) {
            MseHostNative.setSpeed(host, speed, tempo)
        }

        override suspend fun awaitDecoderTable(logger: Logger?) {
            MseDecoderCaps.install(DECODER_TABLE_WAIT_MS, logger)
        }

        override fun close() {
            // A successor session may already own the shared surface.
            if (surface.host == host) surface.host = 0L
            MseHostNative.setVideoSurface(host, null)
            MseHostNative.destroy(host)
        }
    }
}
