package io.ymusic.mse

expect object MseHostNative {
    fun vtable(): Long

    fun create(): Long

    fun destroy(handle: Long)

    fun frames(handle: Long): Int

    fun droppedFrames(handle: Long): Int

    fun videoDecoder(handle: Long): String

    fun samplesPlayed(handle: Long): Long

    fun copyFrame(handle: Long, afterGen: Int = -1): NativeMseFrame?

    fun setVolume(handle: Long, vol: Float)

    fun setSpeed(handle: Long, speed: Float, tempo: Float)

    fun setPitch(handle: Long, pitch: Float)

    fun setEq(handle: Long, bands: FloatArray)

    fun setBass(handle: Long, db: Float)

    fun setVirtualizer(handle: Long, width: Float)

    fun setPreamp(handle: Long, db: Float)

    /** Fixed-position log-frequency bars in 0..1. Returns count written. */
    fun pcmLevels(handle: Long, out: FloatArray): Int
}
