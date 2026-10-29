package io.ymusic.player

import io.ymusic.mse.DesktopVideoSurface
import projectcommon.error.TechnicalException

/**
 * [videoSurface] is the video Canvas the player presents into while it plays (null: no picture).
 * [ffmpegPath] is unused (in-process libav); kept so the shell can still pass the packed CLI for
 * metadata/mux.
 */
actual class LocalPlayerContext(
    val videoSurface: DesktopVideoSurface? = null,
    val ffmpegPath: String? = null,
)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer {
    return try {
        JvmLocalPlayer(context.videoSurface)
    } catch (error: Throwable) {
        throw TechnicalException("E60020", error.message ?: "libav local player failed", cause = error)
    }
}
