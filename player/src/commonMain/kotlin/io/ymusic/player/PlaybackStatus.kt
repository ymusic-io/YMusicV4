package io.ymusic.player

import projectcommon.error.CodedError
import projectcommon.error.PLAYBACK_UNAVAILABLE_CODE

/**
 * Engine + media lifecycle, preserving the legacy player contract. [readyToLoad] means an ordinary
 * media load can be sent to the existing initialized engine; it does not mean that media is ready
 * for playback.
 */
sealed class PlaybackState(val readyToLoad: Boolean) {
    /** The engine does not yet have a usable native/page session. */
    data class Uninitialized(val isInitializing: Boolean) : PlaybackState(readyToLoad = false)

    /** A failed load/session. A broken session must be reset before another load; otherwise the
     *  error belongs to the failed item ([PlayerError.canRetry] is about retrying that item). */
    data class Error(val error: PlayerError) :
        PlaybackState(readyToLoad = !error.needReset)

    /** The initialized engine has no media loaded. */
    data object Idle : PlaybackState(readyToLoad = true)

    /** Media is loaded; buffering/ended remain orthogonal to the user's play intent. */
    data class Prepared(
        val playWhenReady: Boolean,
        val buffering: Boolean,
        val ended: Boolean,
    ) : PlaybackState(readyToLoad = true)
}

fun PlaybackState.isPlaying(): Boolean = (this as? PlaybackState.Prepared)?.playWhenReady == true

fun PlaybackState.isEnded(): Boolean = (this as? PlaybackState.Prepared)?.ended == true

/** Video is on screen: not audio-only by the user's pick, and the source has a video track (engines
 *  report [VideoQualityState.AUDIO_ONLY] as the actual quality when it has none). */
fun showsVideo(config: PlaybackConfig, quality: VideoQualityState): Boolean =
    !config.audioOnly &&
        quality.preferredQuality != VideoQualityState.AUDIO_ONLY &&
        quality.actualQuality != VideoQualityState.AUDIO_ONLY

/** A current queue item and the user's play intent — true across Idle/Uninitialized load gaps. */
fun isPlaying(status: PlaybackStatus, queue: QueueState): Boolean =
    queue.currentItem != null && status.playWhenReady

/** How the video fills its surface. Mirrors the legacy player's scale modes. */
enum class ScaleMode { COVER, FILL, CONTAIN }

/**
 * The full settable playback state — a single object pushed to the engine via [MediaEngine.setConfig]
 * (and carried by [MediaEngine.load] to apply atomically before the first frame). One config, one
 * application path: the engine diffs old→new and applies what changed, so load-time and runtime
 * settings never drift. A faithful port of the legacy `BasicMediaPlayer.Config`.
 *
 * **Audio-only is [audioOnly]**, independent of [preferVideoQuality]. Quality is the last Auto/hd720/…
 * choice and is restored when audio-only turns off. The `Pair<String, Long>` preference fields carry a
 * monotonic sequence in `.second` so the engine can tell the freshest user choice from a stale restore.
 *
 * Quality/caption *selection* lives here (as in legacy); the available-options **state** stays on the
 * read-only capability flows ([VideoQualitySelectable.videoQuality] etc.). Audio-track selection is an
 * immediate action ([AudioTrackSelectable.selectAudioTrack]), not a persistent preference.
 */
data class PlaybackConfig(
    val preferVideoQuality: Pair<String, Long> = VideoQualityState.AUTO to 0L,
    val audioOnly: Boolean = true,
    val captionVisible: Boolean = false,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val pitchFactor: Float = 1f,
    val eqBands: List<Float> = List(10) { 0f },
    /** Low-shelf gain below ~90 Hz, −12..+12 dB; negative cuts. */
    val bassBoostDb: Float = 0f,
    /** 1 = natural stereo; 0 = mono; >1 = wider. */
    val virtualizer: Float = 1f,
    /** Level trim into the equalizer, −12..+12 dB, on top of its automatic loudness matching. */
    val preampDb: Float = 0f,
    /** False bypasses the equalizer/bass/virtualizer/preamp (system audio effects take over); the values are kept. */
    val inAppAudioFx: Boolean = true,
    val loop: Boolean = false,
    val preferCaptionLanguage: Pair<String, Long> = UNDETERMINED_LANGUAGE to 0L,
    val scaleMode: ScaleMode = ScaleMode.CONTAIN,
) {
    init {
        require(volume in 0f..1f) { "volume out of range: $volume" }
    }

    companion object {
        const val UNDETERMINED_LANGUAGE = "und"
    }
}

/**
 * A recoverable classification the caller can surface — a [CodedError] with a stable `E6xxxx` player
 * code, or a well-known category ([FILE_MISSING], [FOLDER_ACCESS_LOST]). [message] is developer detail (the backend's own error text) — log material, never shown.
 *
 * [canRetry]/[needReset] mirror the legacy `PlayerState.Error` flags: [canRetry] = a fresh
 * [MediaEngine.load] of the same source may succeed (false = don't offer retry — e.g. no engine for
 * the item type); [needReset] = the engine session is broken (dead page, mill failure, load timeout).
 * The facade calls [MediaEngine.reset] before the next load when [needReset] is set.
 */
data class PlayerError(
    override val code: String,
    val message: String? = null,
    override val userMessage: String? = null,
    val canRetry: Boolean = true,
    val needReset: Boolean = false,
    val cause: Throwable? = null,
) : CodedError {
    companion object {
        /** A local item's file is gone from storage (deleted/moved since the scan) — never retryable. */
        const val FILE_MISSING = "file_missing"

        /** The app no longer holds the storage grant for a local item's folder (Android SAF) — re-adding the folder fixes it. */
        const val FOLDER_ACCESS_LOST = "folder_access_lost"

        /** This host cannot play anything at all ([PlayerEngines.unavailable]); the shell has reported why. */
        const val PLAYBACK_UNAVAILABLE = PLAYBACK_UNAVAILABLE_CODE

        /** The platform video decoder refused the stream (size or profile beyond the device). */
        const val VIDEO_DECODER_FAILED = "E60064"

        /** googlevideo rejected the session's PO token (StreamProtectionStatus=3). */
        const val PO_TOKEN_REJECTED = "E60065"
    }
}

/** [error] as a cause, so a caller's own coded wrapper keeps the engine's code (`network`, `captcha`, …). */
class PlayerErrorException(val error: PlayerError) :
    Exception("[${error.code}] ${error.message}", error.cause),
    CodedError by error

/** A YouTube video's owner channel, as its player response reports it. [channelUrl] is an
 *  engine-composed app link. */
data class MediaDetails(
    val videoId: String,
    val channelTitle: String? = null,
    val channelUrl: String? = null,
)

/** Natural pixel dimensions of the current video track. */
data class VideoSize(val width: Int, val height: Int) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 0f
}

/**
 * The fast-changing play-head — position/duration/buffered — ticked ~5 Hz while playing. Split out
 * from [PlaybackStatus] so an observer of a rarely-changing field (state, videoSize, config) is
 * never woken by a position tick, and needs no `map`/`distinctUntilChanged` filtering.
 */
data class Progress(
    /** Playhead on the scrubber, 0..[durationMs]. Live DVR is already mapped into that range. */
    val positionMs: Long = 0L,
    /** Scrubber length. [DURATION_UNKNOWN] until the media reports it. */
    val durationMs: Long = DURATION_UNKNOWN,
    val bufferedPositionMs: Long = 0L,
    /** False for a live head with no DVR — the host must not seek. */
    val seekable: Boolean = true,
    val isLive: Boolean = false,
    /** Media-time origin so [seekTo] can map a scrubber position back. Not a UI field. */
    val seekableStartMs: Long = 0L,
    val seekableEndMs: Long = 0L,
) {
    /** Within [thresholdMs] of the live head. */
    fun atLiveHead(thresholdMs: Long = LIVE_HEAD_MS): Boolean {
        if (!isLive) return false
        val span = durationMs.coerceAtLeast(0L)
        return span - positionMs <= thresholdMs
    }

    /**
     * Left caption. VOD: elapsed. Live behind the head: `-00:03`. Null at the live head
     * (the UI shows nothing on the left).
     */
    val progressLabel: String?
        get() = when {
            isLive && atLiveHead() -> null
            isLive -> "-${formatProgressClock((durationMs - positionMs).coerceAtLeast(0L))}"
            durationMs > 0L || positionMs > 0L -> formatProgressClock(positionMs)
            else -> null
        }

    /** Right caption when not [durationIsLive]. Null if unknown / live (UI shows LIVE). */
    val durationLabel: String?
        get() = if (isLive) null else durationMs.takeIf { it > 0L }?.let { formatProgressClock(it) }

    /** Right caption is the LIVE badge, not a clock. */
    val durationIsLive: Boolean get() = isLive

    /** Color the duration caption (LIVE at the live head). */
    val highlightDuration: Boolean get() = atLiveHead()

    fun mediaSeekMs(uiPositionMs: Long): Long {
        val window = isLive && seekableEndMs > seekableStartMs
        return if (window) seekableStartMs + uiPositionMs.coerceAtLeast(0L) else uiPositionMs.coerceAtLeast(0L)
    }

    companion object {
        const val DURATION_UNKNOWN: Long = -1L
        const val LIVE_HEAD_MS: Long = 2_000L

        /** Map YouTube/media times onto the scrubber the UI already understands (0..duration). */
        fun of(
            positionMs: Long,
            durationMs: Long,
            bufferedPositionMs: Long = 0L,
            seekable: Boolean = true,
            isLive: Boolean = false,
            seekableStartMs: Long = 0L,
            seekableEndMs: Long = 0L,
        ): Progress {
            val window = isLive && seekableEndMs > seekableStartMs
            val start = if (window) seekableStartMs else 0L
            val end = when {
                window -> seekableEndMs
                durationMs > 0L -> durationMs
                else -> 0L
            }
            val span = (end - start).coerceAtLeast(0L)
            val pos = if (span > 0L) {
                (positionMs - start).coerceIn(0L, span)
            } else {
                (positionMs - start).coerceAtLeast(0L)
            }
            val dur = if (span > 0L) span else if (durationMs > 0L) durationMs else DURATION_UNKNOWN
            return Progress(
                positionMs = pos,
                durationMs = dur,
                bufferedPositionMs = (bufferedPositionMs - start).coerceAtLeast(0L),
                seekable = seekable,
                isLive = isLive,
                seekableStartMs = seekableStartMs,
                seekableEndMs = if (window) seekableEndMs else end,
            )
        }
    }
}

/** `02:03` or `1:02:03`. */
fun formatProgressClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}

/**
 * Everything about the player that is NOT the moving play-head — it changes only on discrete events
 * (load, play/pause, track/size discovery, error), so its flow stays quiet between them.
 * The play-head lives in [Progress]. Volume and speed live on [PlaybackConfig].
 */
data class PlaybackStatus(
    val state: PlaybackState = PlaybackState.Uninitialized(isInitializing = false),
    /** The last picture's size, kept across loads and audio-only; null until the first one. Whether
     *  video is on screen is [showsVideo], never this. */
    val videoSize: VideoSize? = null,
    /** Unbounded stream (HTML duration Infinity). DVR vs head-only is [Progress.seekable]. */
    val live: Boolean = false,
    /** User play intent. Independent of [state] so a load gap still counts as playing. */
    val playWhenReady: Boolean = (state as? PlaybackState.Prepared)?.playWhenReady == true,
    /** What the engine learned about the loaded video beyond the queue item; null until known. */
    val details: MediaDetails? = null,
) {
    val error: PlayerError? get() = (state as? PlaybackState.Error)?.error
}
