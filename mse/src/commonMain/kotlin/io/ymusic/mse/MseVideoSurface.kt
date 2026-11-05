package io.ymusic.mse

expect class MseVideoSurface {
    /** JVM desktop: convert dest in pixels. 0×0 = decode size. No-op on Android/iOS. */
    fun setBlitSize(width: Int, height: Int)

    /** Native presents since attach (`mse_host_frames`). */
    fun presentedFrames(): Int

    /** Pictures dropped for missing their time since the last load (`mse_host_video_quality`). */
    fun droppedFrames(): Int

    /** "hw vp9", "sw av1"; "" while no video decoder is open. Never waits on the decoder. */
    fun videoDecoder(): String

    fun setVolume(volume: Float)

    fun setAudioEffect(
        bands: FloatArray,
        bass: Float,
        virtualizer: Float,
        preamp: Float,
    )

    /** Fixed-position log-frequency bars in 0..1. Returns count written. */
    fun pcmLevels(out: FloatArray): Int

    /** Android: the audio output's session id, for system audio effects. 0 = none (or not Android). */
    fun audioSessionId(): Int

    /**
     * Android: why MediaCodec gave up on the video stream; null while video decodes (or not Android).
     * Null can also mean the host was busy, so one null clears nothing: poll again. Never blocks.
     */
    fun videoFailure(): String?
}
