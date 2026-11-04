package io.ymusic.desktop.media

import io.ymusic.imageloader.ImageLoader
import io.ymusic.player.PlayableItem
import io.ymusic.player.Player
import io.ymusic.player.isPlaying
import io.ymusic.ui.youtubeThumbUrl
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
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

    /** Bring the app window forward, from the tray too (the user clicked the OS media surface). */
    fun raise() = Unit
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
@OptIn(ExperimentalCoroutinesApi::class)
fun connectMediaSession(
    player: Player,
    scope: CoroutineScope,
    imageLoader: ImageLoader? = null,
    logger: Logger? = null,
    onRaise: () -> Unit = {},
): DesktopMediaSession? {
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

            override fun raise() {
                scope.launch { onRaise() }
            }
        },
        logger = logger,
    ) ?: return null
    // Tagged with its item, so a track change never publishes the previous track's art.
    val localArtwork = player.queue
        .map { it.currentItem }
        .distinctUntilChanged()
        .mapLatest { item ->
            item to item?.takeIf { it !is PlayableItem.YtMedia }?.let { localArtworkUrl(it, imageLoader, logger) }
        }
        .onStart { emit(null to null) }
    scope.launch {
        var last: MediaSessionState? = null
        var lastAtMs = 0L
        combine(player.status, player.queue, player.progress, player.config, localArtwork) { status, queue, progress, config, art ->
            val item = queue.currentItem
            val video = item as? PlayableItem.YtMedia
            MediaSessionState(
                title = item?.displayTitle,
                // A video queued without its channel gets it once the player response names it.
                artist = item?.displaySubtitle
                    ?: status.details?.takeIf { video != null && it.videoId == video.videoId }?.channelTitle,
                artworkUrl = if (video != null) youtubeThumbUrl(video.videoId) else art.second.takeIf { art.first == item },
                playing = status.state.isPlaying(),
                positionMs = progress.positionMs,
                durationMs = progress.durationMs.takeIf { it > 0 },
                speed = config.speed,
                hasItem = item != null,
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

/**
 * A local item's art as an http(s) or `file:` url. App schemes (`art://` embedded album art) have no
 * form an OS process can read, so they go through the loader's scaled disk cache.
 */
private suspend fun localArtworkUrl(item: PlayableItem, imageLoader: ImageLoader?, logger: Logger?): String? {
    val url = item.displayThumbnailUrl ?: return null
    if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file:")) return url
    return try {
        imageLoader?.loadFile(url, ARTWORK_PX, ARTWORK_PX)?.toNioPath()?.toUri()?.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger?.log(Logger.Level.WARN, "media session artwork unavailable: $url", e)
        null
    }
}

private const val ARTWORK_PX = 512

/** Position drift beyond normal tick jitter = a seek the OS must be told about. */
private const val SEEK_DRIFT_MS = 2_000L
