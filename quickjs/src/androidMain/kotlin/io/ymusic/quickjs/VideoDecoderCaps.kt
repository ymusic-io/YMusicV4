package io.ymusic.quickjs

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Called from quickjs_jni.c on the engine thread: whether the decoder `createDecoderByType(mime)` picks
 * takes [width]x[height] — at [fps] when > 0, any rate otherwise. 1 yes, 0 no, -1 unknown.
 */
internal object VideoDecoderCaps {
    private const val UNKNOWN = -1

    private val capabilities = ConcurrentHashMap<String, Result<MediaCodecInfo.VideoCapabilities?>>()
    private val answers = ConcurrentHashMap<String, Int>()

    @JvmStatic
    fun sizeSupported(mime: String, width: Int, height: Int, fps: Double): Int =
        answers.getOrPut("$mime $width $height $fps") {
            val caps = capabilities.getOrPut(mime) { runCatching { firstDecoderCaps(mime) } }
                .onFailure { Log.w("mse-v", "video caps for $mime unreadable", it) }
                .getOrNull() ?: return@getOrPut UNKNOWN
            val ok = if (fps > 0) caps.areSizeAndRateSupported(width, height, fps) else caps.isSizeSupported(width, height)
            Log.i("mse-v", "videoSizeOk $mime ${width}x$height fps=$fps -> $ok")
            if (ok) 1 else 0
        }

    /** The list's first decoder for [mime]: the one createDecoderByType instantiates. */
    private fun firstDecoderCaps(mime: String): MediaCodecInfo.VideoCapabilities? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            ?.getCapabilitiesForType(mime)
            ?.videoCapabilities
}
