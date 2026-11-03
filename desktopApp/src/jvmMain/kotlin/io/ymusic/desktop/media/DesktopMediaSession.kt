package io.ymusic.desktop.media

import io.ymusic.player.Player
import io.ymusic.player.isPlaying
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One snapshot of what the OS "now playing" surface shows. [positionMs] is the elapsed time at
 * publish; the OS extrapolates from it with [speed], so routine ticks need no re-publish.
 */
data class MediaSessionState(
    val title: String?,
    val artist: String?,
    /** http(s) or file URL only — MPRIS clients cannot resolve the app's custom image schemes. */
    val artworkUrl: String? = null,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val speed: Float,
    val hasItem: Boolean,
)

/**
 * Transport commands arriving from the OS (media keys, the Now Playing widget). Invoked on an OS
 * thread — [connectMediaSession] re-dispatches them onto the app main thread before touching the
 * player.
 */
interface MediaSessionCommands {
    fun togglePlay()

    fun play()

    fun pause()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)
}

/**
 * The desktop counterpart of Android's MediaSession: publishes playback state to the OS media
 * surface and receives its transport commands. Per-OS backends: macOS (MPNowPlayingInfoCenter +
 * MPRemoteCommandCenter → Control Center, media keys), Linux (MPRIS over D-Bus → the desktop's
 * Now Playing widget, media keys) and Windows (SystemMediaTransportControls → the media flyout,
 * media keys).
 */
interface DesktopMediaSession {
    fun update(state: MediaSessionState)

    fun release()

    companion object {
        /** The backend for this OS, or null (no backend, or its native setup failed — never fatal). */
        fun create(commands: MediaSessionCommands, logger: Logger? = null): DesktopMediaSession? {
            val os = System.getProperty("os.name")?.lowercase() ?: return null
            return when {
                "mac" in os -> runCatching { MacNowPlayingSession(commands, logger) }
                    .onFailure {
                        logger?.log(Logger.Level.WARN, "macOS Now Playing bridge unavailable — media keys dead", it)
                    }
                    .getOrNull()
                "linux" in os -> runCatching { LinuxMprisSession(commands, logger) }
                    .onFailure { logger?.log(Logger.Level.WARN, "MPRIS unavailable — media keys dead", it) }
                    .getOrNull()
                "windows" in os -> runCatching { WindowsSmtcSession(commands, logger) }
                    .onFailure { logger?.log(Logger.Level.WARN, "SMTC unavailable — media keys dead", it) }
                    .getOrNull()
                else -> null
            }
        }
    }
}

/**
 * Wire [player] to the OS media surface: state flows out on every identity change (track, playing,
 * duration, speed) and on position jumps the OS cannot extrapolate (seeks); commands flow back in
 * on [scope]'s main dispatcher. Returns null when this OS has no backend.
 */
fun connectMediaSession(player: Player, scope: CoroutineScope, logger: Logger? = null): DesktopMediaSession? {
    val session = DesktopMediaSession.create(
        commands = object : MediaSessionCommands {
            override fun togglePlay() {
                scope.launch { player.togglePlay() }
            }

            override fun play() {
                scope.launch { player.play() }
            }

            override fun pause() {
                scope.launch { player.pause() }
            }

            override fun next() {
                scope.launch { player.skipNext() }
            }

            override fun previous() {
                scope.launch { player.skipPrev() }
            }

            override fun seekTo(positionMs: Long) {
                scope.launch { player.seekTo(positionMs) }
            }
        },
        logger = logger,
    ) ?: return null
    scope.launch {
        var last: MediaSessionState? = null
        var lastAtMs = 0L
        combine(player.status, player.queue, player.progress, player.config) { status, queue, progress, config ->
            MediaSessionState(
                title = queue.currentItem?.displayTitle,
                artist = queue.currentItem?.displaySubtitle,
                artworkUrl = queue.currentItem?.displayThumbnailUrl
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("file:") },
                playing = status.state.isPlaying(),
                positionMs = progress.positionMs,
                durationMs = progress.durationMs.takeIf { it > 0 },
                speed = config.speed,
                hasItem = queue.currentItem != null,
            )
        }.collect { state ->
            val now = System.currentTimeMillis()
            val prev = last
            val expectedMs = when {
                prev == null -> null
                prev.playing -> prev.positionMs + ((now - lastAtMs) * prev.speed).toLong()
                else -> prev.positionMs
            }
            val identityChanged = prev == null || prev.copy(positionMs = 0) != state.copy(positionMs = 0)
            val jumped = expectedMs != null && abs(state.positionMs - expectedMs) > SEEK_DRIFT_MS
            if (identityChanged || jumped) {
                session.update(state)
                last = state
                lastAtMs = now
            }
        }
    }
    return session
}

/** Position drift beyond normal tick jitter = a seek the OS must be told about. */
private const val SEEK_DRIFT_MS = 2_000L
