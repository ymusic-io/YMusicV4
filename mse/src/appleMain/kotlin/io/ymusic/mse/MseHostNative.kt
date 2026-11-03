package io.ymusic.mse

import io.ymusic.mse.cinterop.mse_host_copy_frame
import io.ymusic.mse.cinterop.mse_host_create
import io.ymusic.mse.cinterop.mse_host_destroy
import io.ymusic.mse.cinterop.mse_host_dropped_frames
import io.ymusic.mse.cinterop.mse_host_frames
import io.ymusic.mse.cinterop.mse_host_pcm_levels
import io.ymusic.mse.cinterop.mse_host_samples_played
import io.ymusic.mse.cinterop.mse_host_set_bass
import io.ymusic.mse.cinterop.mse_host_set_eq
import io.ymusic.mse.cinterop.mse_host_set_js_event
import io.ymusic.mse.cinterop.mse_host_set_pitch
import io.ymusic.mse.cinterop.mse_host_set_preamp
import io.ymusic.mse.cinterop.mse_host_set_speed
import io.ymusic.mse.cinterop.mse_host_set_video_surface
import io.ymusic.mse.cinterop.mse_host_set_virtualizer
import io.ymusic.mse.cinterop.mse_host_set_volume
import io.ymusic.mse.cinterop.mse_host_video_decoder
import io.ymusic.mse.cinterop.mse_host_video_size
import io.ymusic.mse.cinterop.mse_host_vtable
import io.ymusic.mse.cinterop.mse_ios_log
import io.ymusic.mse.cinterop.mse_set_log_callback
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class)
actual object MseHostNative {
    private const val VIDEO_DECODER_CAP = 48
    private var frameScratch = ByteArray(0)

    init {
        mse_host_set_js_event(
            staticCFunction { name ->
                if (name != null) MseJsEventBridge.fire(name.toKString())
            },
        )
        mse_set_log_callback(
            staticCFunction { level, msg ->
                if (msg != null) MseLogBridge.fire(level, msg.toKString())
            },
        )
    }

    fun log(msg: String) {
        mse_ios_log(msg)
    }

    actual fun vtable(): Long = mse_host_vtable()?.rawValue?.toLong() ?: 0L

    actual fun create(): Long = mse_host_create()?.rawValue?.toLong() ?: 0L

    fun setVideoLayer(handle: Long, layer: COpaquePointer?) {
        if (handle == 0L) return
        mse_host_set_video_surface(handle.toCPointer<COpaque>(), layer)
    }

    fun videoSize(handle: Long, outWh: IntArray): Boolean {
        if (handle == 0L || outWh.size < 2) return false
        return memScoped {
            val w = alloc<IntVar>()
            val ht = alloc<IntVar>()
            if (mse_host_video_size(handle.toCPointer<COpaque>(), w.ptr, ht.ptr) == 0) {
                false
            } else {
                outWh[0] = w.value
                outWh[1] = ht.value
                true
            }
        }
    }

    actual fun destroy(handle: Long) {
        if (handle == 0L) return
        mse_host_destroy(handle.toCPointer<COpaque>())
    }

    actual fun frames(handle: Long): Int {
        if (handle == 0L) return 0
        return mse_host_frames(handle.toCPointer<COpaque>())
    }

    actual fun droppedFrames(handle: Long): Int {
        if (handle == 0L) return 0
        return mse_host_dropped_frames(handle.toCPointer<COpaque>())
    }

    actual fun videoDecoder(handle: Long): String {
        if (handle == 0L) return ""
        return memScoped {
            val buf = allocArray<ByteVar>(VIDEO_DECODER_CAP)
            mse_host_video_decoder(handle.toCPointer<COpaque>(), buf, VIDEO_DECODER_CAP)
            buf.toKString()
        }
    }

    actual fun samplesPlayed(handle: Long): Long {
        if (handle == 0L) return 0L
        return mse_host_samples_played(handle.toCPointer<COpaque>())
    }

    actual fun setVolume(handle: Long, vol: Float) {
        if (handle == 0L) return
        mse_host_set_volume(handle.toCPointer<COpaque>(), vol)
    }

    actual fun setSpeed(handle: Long, speed: Float, tempo: Float) {
        if (handle == 0L) return
        mse_host_set_speed(handle.toCPointer<COpaque>(), speed, tempo)
    }

    actual fun setPitch(handle: Long, pitch: Float) {
        if (handle == 0L) return
        mse_host_set_pitch(handle.toCPointer<COpaque>(), pitch)
    }

    actual fun setEq(handle: Long, bands: FloatArray) {
        if (handle == 0L) return
        bands.usePinned { pinned ->
            mse_host_set_eq(handle.toCPointer<COpaque>(), pinned.addressOf(0))
        }
    }

    actual fun setBass(handle: Long, db: Float) {
        if (handle == 0L) return
        mse_host_set_bass(handle.toCPointer<COpaque>(), db)
    }

    actual fun setVirtualizer(handle: Long, width: Float) {
        if (handle == 0L) return
        mse_host_set_virtualizer(handle.toCPointer<COpaque>(), width)
    }

    actual fun setPreamp(handle: Long, db: Float) {
        if (handle == 0L) return
        mse_host_set_preamp(handle.toCPointer<COpaque>(), db)
    }

    actual fun pcmLevels(handle: Long, out: FloatArray): Int {
        if (handle == 0L || out.isEmpty()) return 0
        return out.usePinned { pinned ->
            mse_host_pcm_levels(handle.toCPointer<COpaque>(), pinned.addressOf(0), out.size)
        }
    }

    actual fun copyFrame(handle: Long, afterGen: Int): NativeMseFrame? {
        if (handle == 0L) return null
        val host = handle.toCPointer<COpaque>() ?: return null
        return memScoped {
            val w = alloc<IntVar>()
            val ht = alloc<IntVar>()
            val gen = alloc<IntVar>()
            val need = mse_host_copy_frame(host, null, 0, w.ptr, ht.ptr, gen.ptr)
            if (need <= 0 || gen.value == afterGen) return@memScoped null
            if (frameScratch.size < need) frameScratch = ByteArray(need)
            val n = frameScratch.usePinned { pinned ->
                mse_host_copy_frame(
                    host,
                    pinned.addressOf(0).reinterpret<UByteVar>(),
                    need,
                    w.ptr,
                    ht.ptr,
                    gen.ptr,
                )
            }
            if (n <= 0 || n > frameScratch.size) return@memScoped null
            NativeMseFrame(w.value, ht.value, gen.value, frameScratch)
        }
    }
}
