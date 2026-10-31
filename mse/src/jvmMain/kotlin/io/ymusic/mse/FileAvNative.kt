package io.ymusic.mse

import androidx.annotation.Keep

/** JNI callbacks from the in-process libav file player (`file_av.c`). */
interface FileAvSink {
    @Keep fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)

    @Keep fun onEvent(ended: Boolean, error: String?)

    @Keep fun onCaption(id: Int, startSec: Double, endSec: Double, text: String) {}
}

/**
 * Desktop file playback in the same `libmseffmpegjni` as leftover MSE. Separate C state
 * (`file_av_*`): `avformat_open_input` + PTS clock, not append/seek leftover.
 */
object FileAvNative {
    init {
        MseHostNative.vtable()
    }

    @JvmStatic external fun create(): Long

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun open(handle: Long, path: String): Int

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun play(handle: Long)

    @JvmStatic external fun pause(handle: Long)

    @JvmStatic external fun seek(handle: Long, seconds: Double)

    @JvmStatic external fun step(handle: Long): Int

    @JvmStatic external fun setSpeed(handle: Long, speed: Double)

    @JvmStatic external fun setVolume(handle: Long, volume: Double)

    @JvmStatic external fun pcmLevels(handle: Long, out: FloatArray): Int

    @JvmStatic external fun setPitch(handle: Long, pitch: Double)

    @JvmStatic external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic external fun setBass(handle: Long, db: Float)

    @JvmStatic external fun setVirtualizer(handle: Long, width: Float)

    @JvmStatic external fun setPreamp(handle: Long, db: Float)

    @JvmStatic external fun setAudioIndex(handle: Long, index: Int)

    /** -1 turns captions off. [index] is the file stream index (`streams()` kind=2). */
    @JvmStatic external fun setSubtitleIndex(handle: Long, index: Int)

    /** Every text cue on [index] as `startSec|endSec|text`. Empty if the track has no samples. */
    @JvmStatic external fun dumpCaptions(handle: Long, index: Int): Array<String>

    @JvmStatic external fun setVideoEnabled(handle: Long, on: Boolean)

    /**
     * Pictures go to [presenter] (a `VideoPresenterNative` handle, 0 = none: video waits for one), bound with
     * this player as the token. Windows reopens the video decoder when the presenter's D3D11 support changes.
     */
    @JvmStatic external fun bindPresenter(handle: Long, presenter: Long)

    /** Pictures the presenter showed. */
    @JvmStatic external fun frames(handle: Long): Int

    /** Place [below] immediately under [above] without making [below] key. macOS only. */
    @JvmStatic external fun orderWindowBelow(below: java.awt.Window, above: java.awt.Window)

    /** Enable NSWindowCollectionBehaviorFullScreenPrimary on the Compose window. */
    @JvmStatic external fun prepareFullscreen(primary: java.awt.Window)

    @JvmStatic external fun toggleFullScreen(primary: java.awt.Window)

    @Volatile @JvmStatic
    var onFullScreen: ((Boolean) -> Unit)? = null

    @Keep @JvmStatic
    fun onFullScreenChanged(on: Boolean) {
        onFullScreen?.invoke(on)
    }

    /**
     * Overlay NSView mouse. [id] is `java.awt.event.MouseEvent` id; x/y are Java screen
     * (top-left). Desktop shell toggles chrome on mouse-up; do not copy events into Compose.
     */
    @Volatile @JvmStatic
    var overlayMouse: OverlayMouse? = null

    @JvmStatic
    fun onOverlayMouse(id: Int, xOnScreen: Int, yOnScreen: Int, modifiers: Int, button: Int, clickCount: Int, wheel: Int) {
        overlayMouse?.onMouse(id, xOnScreen, yOnScreen, modifiers, button, clickCount, wheel)
    }

    fun interface OverlayMouse {
        fun onMouse(id: Int, xOnScreen: Int, yOnScreen: Int, modifiers: Int, button: Int, clickCount: Int, wheel: Int)
    }

    @JvmStatic external fun duration(handle: Long): Double

    @JvmStatic external fun position(handle: Long): Double

    @JvmStatic external fun ended(handle: Long): Boolean

    @JvmStatic external fun videoSize(handle: Long, outWh: IntArray): Boolean

    @JvmStatic external fun lastError(handle: Long): String

    @JvmStatic external fun streams(handle: Long): Array<String>

    @JvmStatic external fun setSink(handle: Long, sink: FileAvSink?)
}
