package io.ymusic.player

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/**
 * A resolved, playable source. The `Player` router picks an engine by variant; each engine plays
 * only the variant it understands (a [LocalPlayer] only ever receives a [LocalMedia]). Both variants
 * are self-describing — they carry their own display metadata — like the legacy LocalSong / YtVideo,
 * so the UI never needs a side map to render a queue item.
 */
sealed interface PlayableItem {
    /** How the item shows in Now Playing — each variant describes itself (title/subtitle fallbacks,
     *  "artist · album"), so the UI reads `item.displayTitle` with no adapter or external `when`. */
    val displayTitle: String
    val displaySubtitle: String?
    val displayThumbnailUrl: String?
    val displayDuration: String?

    /** The source-declared duration in ms, when known ahead of load (the store row, a scraped
     *  badge); null = discovered only once the engine loads the item. Lets a session surface show
     *  durations for every queue row, not just the loaded one. */
    val durationMs: Long?

    /** The YouTube video this item plays: always set for [YtMedia]; for [LocalMedia], the video a download came from. */
    val videoId: String?

    /** What the facade multiplies into the volume for this item; 1 when its engine levels it. */
    val loudnessScale: Float get() = 1f

    /**
     * A local file. [uri] (a `file://` URI or an absolute path) is all the native-local engine needs;
     * the rest travels with the item so the UI (Now Playing display, play-count recording) never has
     * to re-derive it. [mediaId] is the media-store row id (null for a file with no library row, e.g. a
     * download); [title]/[artist]/[album]/[thumbnailUrl] are its display metadata.
     */
    data class LocalMedia(
        val uri: String,
        val mediaId: Long? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val thumbnailUrl: String? = null,
        override val durationMs: Long? = null,
        /** dB from the file's measured loudness to its target; null when unmeasured. */
        val loudnessGainDb: Double? = null,
        override val videoId: String? = null,
    ) : PlayableItem {
        /** YouTube's rule: loud files come down to the target, quiet ones are never raised. */
        override val loudnessScale: Float
            get() = loudnessGainDb?.let { 10.0.pow(minOf(it, 0.0) / 20).toFloat() } ?: 1f

        override val displayTitle: String get() = title ?: uri.substringAfterLast('/')
        override val displaySubtitle: String? get() = listOfNotNull(artist, album).joinToString(" · ").ifEmpty { null }
        override val displayThumbnailUrl: String? get() = thumbnailUrl
        override val displayDuration: String? get() = null
    }

    /**
     * A YouTube video, played by the YouTube engine (`MediaEngine<YtMedia>`). [videoId] is all the
     * engine needs; [title]/[artist] (channel) / [thumbnailUrl] / [duration] travel with it for the
     * UI. [live] marks a live stream (unbounded duration). Seekability is
     * [Progress.seekable] — DVR live is seekable, head-only live is not.
     */
    data class YtMedia(
        override val videoId: String,
        val title: String? = null,
        val artist: String? = null,
        val thumbnailUrl: String? = null,
        val duration: String? = null,
        override val durationMs: Long? = null,
        val live: Boolean = false,
        /** The engine-composed app link to the owner channel's page, when the source knew it. */
        val channelUrl: String? = null,
    ) : PlayableItem {
        override val displayTitle: String get() = title ?: videoId
        override val displaySubtitle: String? get() = artist
        override val displayThumbnailUrl: String? get() = thumbnailUrl
        override val displayDuration: String? get() = duration
    }
}

/**
 * The engine SPI that every playback backend satisfies — the native-local engine ([LocalPlayer])
 * and the YouTube engine ([YtEngine] / [io.ymusic.player.mse.MsePlayer]). The `Player` facade/router
 * drives whichever engine plays the current item through this one contract and merges
 * [status]/[progress] the same way regardless of which engine is active (swapping engines when the
 * play-index crosses item types).
 *
 * This is a deliberately **narrow common core** — only what genuinely overlaps between a native
 * decoder and the YouTube engine. Engine-specific features do NOT go here; they belong behind
 * optional capability interfaces queried with `engine as? Capability` (audio-track selection,
 * captions, video-quality selection for YouTube) — never as degenerate methods every engine must
 * stub, which was the legacy `BasicMediaPlayer` smell.
 *
 * **Main-thread only.** Create the engine and call every method on the **main thread**. Each
 * backend's underlying native player (ExoPlayer / AVPlayer / ffmpeg / C MSE) does its own
 * decoding/playback on its own threads; only this control surface is main-thread-bound, and no call
 * is marshaled — that is the caller's responsibility. The [status] / [progress] flows may be
 * collected from any thread.
 */
interface MediaEngine<in S : PlayableItem> {
    /**
     * Rarely-changing status: playback state, play/pause intent, video size, error.
     * Emits only on discrete events — never on a position tick (that's [progress]).
     * Volume and speed are applied through [setConfig] and are not published here.
     */
    val status: StateFlow<PlaybackStatus>

    /** The play-head — position/duration/buffered — ticked ~5 Hz while playing. */
    val progress: StateFlow<Progress>

    /**
     * Bring up the engine session if [status] is cold [PlaybackState.Uninitialized].
     * No-op when already initializing, Idle, or Prepared. Observe [status] for Idle/Error.
     */
    fun init()

    /**
     * Start loading [source] and return immediately. Call only when [PlaybackState.readyToLoad].
     * Readiness is observed through [status]: the state becomes [PlaybackState.Prepared] (first
     * buffering, then ready), or [PlaybackState.Error] on failure — loading never throws.
     * [startPositionMs] seeks before the first frame; playback begins as soon as it is ready when
     * [playWhenReady]. A second [load] replaces whatever was loaded before. [S] is the engine's
     * source variant — a mismatched source is a compile error, not a runtime failure.
     *
     * [config] is applied **atomically before the first frame**, so the media starts with the right
     * settings — no full-volume blip or a video flash before audio-only. [setConfig] changes the same
     * settings at runtime through the same application path.
     */
    fun load(
        source: S,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        config: PlaybackConfig = PlaybackConfig(),
    )

    /**
     * Cue [source] as the next item without interrupting current playback. Null clears a previously
     * cued item. Default is a no-op (JVM/Apple local have no second-item playlist).
     */
    fun preload(source: S?) {}

    fun play()

    fun pause()

    /** Clamped to `0..duration`. Safe before/after the end. */
    fun seekTo(positionMs: Long)

    /** Stop and unload the current source; returns to [PlaybackState.Idle]. The engine stays usable. */
    fun stop()

    /**
     * Rebuild the engine session. Safe in any state. YouTube re-inits mill/page; local unloads
     * media and returns to Idle. Observe [status] for Idle/Error before the next [load].
     */
    fun reset()

    /** [reset], naming why — for engines that log their session lifecycle. */
    fun reset(reason: String) = reset()

    /**
     * Push the full playback settings ([PlaybackConfig]) — the single runtime-tunable path, the same
     * object [load] applies atomically. The engine diffs against its current config and applies only
     * what changed (volume, speed/pitch, video quality, audio-only, captions, loop, scale). Fields a
     * backend does not support are ignored. Cheap to call on
     * every change; idempotent for an unchanged config.
     */
    fun setConfig(config: PlaybackConfig)

    /**
     * Fixed-position log-frequency bars in 0..1 from PCM currently hitting the DAC.
     * Writes up to [out].size samples and returns the count; 0 when idle or unsupported.
     */
    fun pcmLevels(out: FloatArray): Int = 0

    /** Pictures presented since attach. 0 when the backend has no counter. */
    fun presentedFrames(): Int = 0

    /** Pictures dropped for missing their time since the current load. 0 when the backend has no counter. */
    fun droppedFrames(): Int = 0

    /** The video decoder in use, e.g. "hw vp9"; null when unknown. Main thread: off it the engine may free its host mid-call. */
    fun videoDecoder(): String? = null

    /** Releases native resources. The instance is unusable afterwards. Idempotent. */
    fun release()
}
