package io.ymusic.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * The playback facade the app talks to — one player, many items. Owns the queue
 * (shuffle/loop/reorder), routes each item to the engine that plays it ([LocalPlayer] for
 * [PlayableItem.LocalMedia], [YtEngine] for [PlayableItem.YtMedia]), auto-advances at end of
 * track, and re-exposes the **active** engine's flows so observers never care which engine is
 * playing (a switch-latest merge — when the engine changes, the facade flows follow the new one).
 *
 * Main-thread only, like the engines it drives ([MediaEngine]); flows collect from anywhere.
 * [config] is facade state: it survives track and engine changes, applied atomically at every load and
 * pushed to the active engine on every change via [MediaEngine.setConfig].
 */
interface Player {
    /** The active engine's [MediaEngine.status]; [PlaybackStatus] default when nothing has loaded. */
    val status: StateFlow<PlaybackStatus>

    /** The active engine's [MediaEngine.progress] (~5 Hz play-head). */
    val progress: StateFlow<Progress>

    val queue: StateFlow<QueueState>

    /** Capability states of the active engine ([VideoQualitySelectable] etc.); DEFAULT when absent. */
    val videoQuality: StateFlow<VideoQualityState>
    val captions: StateFlow<CaptionState>
    val captionLines: StateFlow<List<CaptionLine>>
    val audioTracks: StateFlow<AudioTrackState>

    /** The active engine's [AudioSessionSource.audioSessionId]; 0 when it has none. */
    val audioSessionId: StateFlow<Int>

    /** The current playback settings, pushed to the active engine on every change. Audio-only is
     *  [PlaybackConfig.audioOnly], not a quality token. */
    val config: StateFlow<PlaybackConfig>

    /** Remaining sleep-timer time (ticked ~1 Hz), or null when no timer is armed. */
    val sleepTimerMs: StateFlow<Long?>

    /** Playback pauses when the current track ends (the queue still moves on to the next item). */
    val sleepAfterCurrent: StateFlow<Boolean>

    /**
     * Watch-next payloads reported while a YouTube item plays — the app forwards each to the scraping
     * engine, which builds the Recommended feed out of them. Not playback state: a side channel.
     * Empty on engines that report none.
     */
    val watchNextSeen: SharedFlow<WatchNextSeen>

    /** SponsorBlock ranges for the current item; empty when none are armed. */
    val skipSegments: StateFlow<List<SkipSegment>>

    /** Replace the queue and load [playIndex]. An empty [items] clears and stops. [queueId] becomes
     *  [QueueState.queueId] — pass a saved queue's id to resume it as the same queue; null mints one.
     *  [source] grows the queue past [items] (see [QueueSource]). [origin] labels it in the log. */
    fun setQueue(
        items: List<PlayableItem>,
        playIndex: Int = 0,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        queueId: String? = null,
        source: QueueSource? = null,
        origin: QueueOrigin = QueueOrigin.Unknown,
    )

    /** Fetch the queue source's next page now (the queue's "Load more" / retry). No-op without one. */
    fun loadMore()

    /** Append (or insert at [at]) without interrupting playback. */
    fun add(items: List<PlayableItem>, at: Int? = null)

    /** Insert right after the current item. */
    fun addToPlayNext(items: List<PlayableItem>)

    /** Move the queued item at [index] to play next, after anything already queued to play next. */
    fun moveToPlayNext(index: Int)

    fun move(from: Int, to: Int)

    /** Move the `[fromIndex, toIndex)` block so it starts at [newIndex] (post-removal indexing —
     *  media3's moveMediaItems contract). STRICT: an invalid range throws — a boundary relaying
     *  cross-process edits against a possibly-stale queue snapshot clamps before calling. */
    fun move(fromIndex: Int, toIndex: Int, newIndex: Int)

    fun removeAt(index: Int)

    /** Remove the `[fromIndex, toIndex)` range. Same strict contract as range [move]. */
    fun remove(fromIndex: Int, toIndex: Int)

    fun clearQueue()

    /** Jump to and load the queue item at [index]. */
    fun playAt(index: Int)

    fun play()

    fun pause()

    fun togglePlay()

    /** Stop and unload; the queue is kept. */
    fun stop()

    fun seekTo(positionMs: Long)

    /**
     * Arm SponsorBlock skips for [item]. Ranges shorter than 1 s are dropped. The skip loop only
     * fires while [item] is still the current queue item.
     */
    fun setSkipSegments(item: PlayableItem, segments: List<SkipSegment>)

    fun clearSkipSegments()

    fun skipNext()

    /** Goes to the previous item, or restarts the current one when it has played more than ~3 s. */
    fun skipPrev()

    fun fastForward(ms: Long = 10_000L)

    fun rewind(ms: Long = 10_000L)

    fun setLoopMode(mode: LoopMode)

    fun setShuffle(enabled: Boolean)

    /** Last applied volume. Write pushes [PlaybackConfig]; not a flow. */
    var volume: Float

    /** Audio-focus ducking: lowers what the engine plays without touching [volume] or [config]. */
    fun setDucked(ducked: Boolean)

    /** Last applied playback speed. Write pushes [PlaybackConfig]; not a flow. */
    var speed: Float

    fun setPitch(pitch: Float)

    fun setEqBands(bands: List<Float>)

    fun setBassBoost(db: Float)

    fun setVirtualizer(width: Float)

    fun setPreamp(db: Float)

    /** Sets [PlaybackConfig.inAppAudioFx]. */
    fun setInAppAudioFx(enabled: Boolean)

    /** Sets [PlaybackConfig.audioOnly]. Quality is unchanged and restored when this is false. */
    fun setAudioOnly(audioOnly: Boolean)

    /** Sets [PlaybackConfig.preferVideoQuality]. [VideoQualityState.AUDIO_ONLY] maps to [setAudioOnly]. */
    fun selectVideoQuality(quality: String)

    /** Sets [PlaybackConfig.preferCaptionLanguage] (a language code, or null for undetermined). */
    fun selectCaption(languageCode: String?)

    /** Sets [PlaybackConfig.captionVisible]. */
    fun setCaptionVisible(visible: Boolean)

    /** Sets [PlaybackConfig.scaleMode] (how the video fills its surface). */
    fun setScaleMode(mode: ScaleMode)

    /** The one immediate action — forwarded to the active engine when it is [AudioTrackSelectable]. */
    fun selectAudioTrack(id: String)

    /** Pause automatically after [durationMs]; null disarms. Disarms [sleepAfterCurrent]. */
    fun setSleepTimer(durationMs: Long?)

    /** Arms/disarms [sleepAfterCurrent]; arming disarms a running [sleepTimerMs]. */
    fun setSleepAfterCurrent(enabled: Boolean)

    /**
     * Mark the YouTube engine stale so the NEXT track re-inits under the current cookie identity —
     * called after a YouTube sign-in or sign-out. The current track keeps playing until it ends.
     * No-op when the YouTube engine isn't built yet (a fresh one already loads under the new identity).
     */
    fun refreshOnNext()

    /**
     * [MediaEngine.reset] the active engine and reload the current item at the current position.
     * Recovers a wedged player. No-op when nothing is loaded. Keeps the current play/pause intent.
     */
    fun reload()

    /**
     * Eagerly create + warm the YouTube engine so the first video play skips construction cost.
     * [createPlayer] already does this ~3s after construction on every platform. Safe to call
     * again (downloads). No-op if the engine already exists or none is configured.
     */
    fun prepareYoutubeEngine()

    /**
     * Fixed-position log-frequency bars in 0..1 from the active engine's PCM.
     * Writes up to [out].size samples and returns the count; 0 when idle.
     */
    fun pcmLevels(out: FloatArray): Int = 0

    /** Pictures presented by the active engine since attach. 0 when the backend has no counter. */
    fun presentedFrames(): Int = 0

    /** Pictures the active engine dropped for missing their time since its current load. 0 when it has no counter. */
    fun droppedFrames(): Int = 0

    /** The active engine's video decoder, e.g. "hw vp9"; null when unknown. Main thread: off it the engine may free its host mid-call. */
    fun videoDecoder(): String? = null

    /** Releases both engines and stops all internal work. The instance is unusable afterwards. */
    fun release()
}

/**
 * Lazy engine factories — an engine is only constructed when the queue first needs its item type.
 * [youtube] may be null on hosts without a YouTube engine; a [PlayableItem.YtMedia] then fails with
 * `E60050` through [Player.status] instead of crashing. [unavailable] is for a host whose media
 * backend failed to come up: no engine is built and every load publishes it.
 */
class PlayerEngines(
    val local: () -> LocalPlayer,
    val youtube: (() -> YtEngine)? = null,
    val unavailable: PlayerError? = null,
)

fun createPlayer(
    engines: PlayerEngines,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    random: Random = Random.Default,
): Player = PlayerImpl(engines, mainDispatcher, random)

/**
 * One watch-next payload the playing page reported: [contentsJson] is YouTube's related-items array
 * verbatim. Opaque to the app and to the player — only the scraping engine parses it.
 */
data class WatchNextSeen(val videoId: String, val contentsJson: String)

/** A SponsorBlock skip range in media time. [startMs] is the play-head that arms the skip. */
data class SkipSegment(val startMs: Long, val lengthMs: Long)
