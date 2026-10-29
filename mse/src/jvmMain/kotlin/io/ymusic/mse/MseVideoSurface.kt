package io.ymusic.mse

actual class MseVideoSurface(val video: DesktopVideoSurface = DesktopVideoSurface(cpuOnly = true)) {
    private var binder: ((Long) -> Unit)? = null

    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. The host is bound to [video] while set. */
    @Volatile
    var host: Long = 0L
        set(value) {
            synchronized(this) {
                if (field == value) return
                binder?.let { video.unregister(VideoEngine.MSE, it) }
                binder = null
                field = value
                if (value != 0L) {
                    val bind: (Long) -> Unit = { presenter -> MseHostNative.bindPresenter(value, presenter) }
                    binder = bind
                    video.register(VideoEngine.MSE, bind)
                }
            }
        }

    actual fun setBlitSize(width: Int, height: Int) {
        video.setSize(width, height)
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
}
