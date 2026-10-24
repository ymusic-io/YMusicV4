@file:androidx.annotation.OptIn(UnstableApi::class)

package io.ymusic.player.media3

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.ymusic.player.LoopMode
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackState
import io.ymusic.player.Player
import io.ymusic.player.PlayerError
import io.ymusic.player.isPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.media3.common.Player as Media3Player

/**
 * Adapts the [Player] facade to a media3 `Player` (via [SimpleBasePlayer]) so a media3
 * `MediaSession`/`MediaSessionService` can drive it — notification, media buttons, lock screen,
 * Bluetooth/Auto transport all come from media3 reading this adapter. State flows one way (facade
 * flows → [invalidateState]); commands flow back onto the facade's own transport methods, so the
 * session and the in-app UI always agree.
 *
 * Never releases the shared facade: [handleRelease] only stops mirroring. Main-thread bound, like
 * the facade it wraps.
 *
 * The published timeline is a FLOATING WINDOW of at most [QUEUE_WINDOW_MAX] items around the
 * current one (the in-app UI reads the full queue off the facade directly): controllers speak
 * window coordinates, so every index a command carries translates through [toAbsolute] before it
 * touches the facade. Uids carry absolute indices, keeping item identity stable as the window
 * slides.
 *
 * [describe] supplies per-item display metadata (the queue holds only [PlayableItem]s);
 * [extraInvalidations] re-snapshots when an external metadata source updates (e.g. titles arriving
 * after the queue was set).
 */
class Media3PlayerAdapter(
    private val player: Player,
    private val describe: (PlayableItem) -> MediaMetadata,
    private val logger: kmpcommon.log.Logger? = null,
    extraInvalidations: Flow<*> = emptyFlow<Unit>(),
    /** Applies a controller's setMediaItems (an Android Auto browse tap arrives this way after the
     *  session callback resolved the ids) onto the passed facade — the owner decides how a mediaId
     *  becomes a queue (context expansion, watch-next autoplay). Null = the command is advertised
     *  but a set is ignored (in-app UIs drive the facade directly). */
    private val applyMediaItems: ((player: Player, items: List<MediaItem>, startIndex: Int, startPositionMs: Long) -> Unit)? = null,
    /** Inserts controller-added items (addMediaItems / legacy addQueueItem) into the passed
     *  facade's queue at absolute queue position `index` (already translated from the published
     *  window's coordinates). The items arrive pre-resolved by the session callback's
     *  onAddMediaItems, so the owner converts them back to [PlayableItem]s. Null = adds are
     *  dropped. */
    private val insertMediaItems: ((player: Player, index: Int, items: List<MediaItem>) -> Unit)? = null,
    /** The user-facing message controllers (Android Auto) show for a playback failure; null = the
     *  raw code + message. Re-read on every [extraInvalidations] emission. */
    private val errorMessage: (PlayerError) -> String? = { null },
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        var prevPositionMs = 0L
        var prevAtMs = SystemClock.elapsedRealtime()
        var prevDurationMs = Long.MIN_VALUE
        scope.launch {
            merge(
                player.status,
                player.queue,
                player.config,
                // The play-head is read live via PositionSupplier — a tick needs no invalidation.
                // A seek re-anchors the snapshot, judged against the WALL CLOCK, not the previous
                // tick: while playing, position is expected to advance by elapsed×speed, so slow
                // or throttled ticks (screen-off timers) don't masquerade as seeks. A
                // duration re-anchors only when it meaningfully changes (appears, or moves >1s).
                player.progress.mapNotNull { p ->
                    val now = SystemClock.elapsedRealtime()
                    val status = player.status.value
                    val expectedAdvance =
                        if (status.state.isPlaying()) ((now - prevAtMs) * player.speed).toLong() else 0L
                    val jumped = abs((p.positionMs - prevPositionMs) - expectedAdvance) > POSITION_JUMP_MS
                    val durationChanged = meaningfulDurationChange(p.durationMs, prevDurationMs)
                    prevPositionMs = p.positionMs
                    prevAtMs = now
                    prevDurationMs = p.durationMs
                    if (jumped || durationChanged) Unit else null
                },
                // An external metadata source updated → cached snapshots describe() stale items.
                extraInvalidations.onEach { builtFor = null },
            ).collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val status = player.status.value
        val queue = player.queue.value
        val items = queue.items
        val prepared = status.state as? PlaybackState.Prepared
        val error = (status.state as? PlaybackState.Error)?.error

        // media3 has no CONNECTING state: an engine still initializing with a queue reads as
        // BUFFERING, so session surfaces show their loading treatment instead of a dead IDLE.
        val initializing = (status.state as? PlaybackState.Uninitialized)?.isInitializing == true
        val playbackState = when {
            items.isEmpty() || error != null -> Media3Player.STATE_IDLE
            prepared == null ->
                if (initializing || status.playWhenReady) Media3Player.STATE_BUFFERING else Media3Player.STATE_IDLE
            prepared.ended -> Media3Player.STATE_ENDED
            prepared.buffering -> Media3Player.STATE_BUFFERING
            else -> Media3Player.STATE_READY
        }

        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(status.playWhenReady, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(playbackState == Media3Player.STATE_BUFFERING)
            .setRepeatMode(
                when (queue.loopMode) {
                    LoopMode.None -> Media3Player.REPEAT_MODE_OFF
                    LoopMode.One -> Media3Player.REPEAT_MODE_ONE
                    LoopMode.All -> Media3Player.REPEAT_MODE_ALL
                },
            )
            .setShuffleModeEnabled(queue.shuffle)
            .setVolume(player.volume.coerceIn(0f, 1f))
            .setPlaybackParameters(
                if (player.speed == 1f) PlaybackParameters.DEFAULT else PlaybackParameters(player.speed),
            )
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)

        if (error != null) {
            builder.setPlayerError(
                PlaybackException(
                    errorMessage(error) ?: listOfNotNull(error.code, error.message).joinToString(": "),
                    error.cause,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                ),
            )
        }

        if (items.isNotEmpty()) {
            val currentIndex = queue.currentIndex.coerceIn(0, items.lastIndex)
            builder.setPlaylist(playlist(items, currentIndex, player.progress.value.durationMs))
            builder.setCurrentMediaItemIndex(currentIndex - windowStart(items.size, currentIndex))
            builder.setContentPositionMs { player.progress.value.positionMs }
            builder.setContentBufferedPositionMs { player.progress.value.bufferedPositionMs }
            builder.setTotalBufferedDurationMs {
                (player.progress.value.bufferedPositionMs - player.progress.value.positionMs).coerceAtLeast(0L)
            }
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = done {
        if (playWhenReady) player.play() else player.pause()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = done {
        applyMediaItems?.invoke(player, mediaItems, startIndex, startPositionMs)
    }

    // COMMAND_CHANGE_MEDIA_ITEMS gates add/remove/move/replace together, and SimpleBasePlayer's
    // defaults THROW for any advertised-but-unimplemented one. Remove/move map onto the facade's
    // range edits. The facade contract is STRICT (in-app index bugs must surface); THIS is the
    // stranger boundary — controller edits arrive async and cross-process, in WINDOW coordinates,
    // against a queue snapshot that may already be stale — so indices translate to absolute and
    // clamp here, and only here. Add routes to the owner's converter hook (dropped when no hook
    // is installed). Replace = add+remove.
    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> = done {
        insertMediaItems?.invoke(player, toAbsolute(index), mediaItems)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = done {
        val size = player.queue.value.items.size
        val from = toAbsolute(fromIndex).coerceIn(0, size)
        val to = toAbsolute(toIndex).coerceIn(from, size)
        try {
            if (to > from) player.remove(from, to)
        } catch (e: IllegalArgumentException) {
            // Belt over the clamp: a stale cross-process edit is dropped, never fatal.
            logger?.log(kmpcommon.log.Logger.Level.WARN, "stale removeMediaItems($fromIndex, $toIndex)", e)
        }
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = done {
        val size = player.queue.value.items.size
        val from = toAbsolute(fromIndex).coerceIn(0, size)
        val to = toAbsolute(toIndex).coerceIn(from, size)
        val target = toAbsolute(newIndex).coerceIn(0, size - (to - from))
        try {
            if (to > from) player.move(from, to, target)
        } catch (e: IllegalArgumentException) {
            logger?.log(kmpcommon.log.Logger.Level.WARN, "stale moveMediaItems($fromIndex, $toIndex, $newIndex)", e)
        }
    }

    /** A controller index (window coordinates — what the published timeline showed) → the facade's
     *  absolute queue index. */
    private fun toAbsolute(windowIndex: Int): Int {
        if (windowIndex == C.INDEX_UNSET) return C.INDEX_UNSET
        val queue = player.queue.value
        return windowIndex + windowStart(queue.items.size, queue.currentIndex.coerceAtLeast(0))
    }

    override fun handlePrepare(): ListenableFuture<*> = done { player.play() }

    override fun handleStop(): ListenableFuture<*> = done { player.stop() }

    override fun handleRelease(): ListenableFuture<*> = done { scope.cancel() }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = done {
        when (seekCommand) {
            Media3Player.COMMAND_SEEK_TO_NEXT, Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> player.skipNext()
            Media3Player.COMMAND_SEEK_TO_PREVIOUS, Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                player.skipPrev()
            Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val absolute = toAbsolute(mediaItemIndex)
                if (absolute != C.INDEX_UNSET && absolute != player.queue.value.currentIndex) {
                    player.playAt(absolute) // lenient: a stale windowed index just misses
                } else if (positionMs != C.TIME_UNSET) {
                    player.seekTo(positionMs)
                }
            }
            else -> player.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = done {
        player.setShuffle(shuffleModeEnabled)
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = done {
        player.setLoopMode(
            when (repeatMode) {
                Media3Player.REPEAT_MODE_ONE -> LoopMode.One
                Media3Player.REPEAT_MODE_ALL -> LoopMode.All
                else -> LoopMode.None
            },
        )
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> = done {
        logger?.log(kmpcommon.log.Logger.Level.INFO, "controller setVolume $volume")
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = done {
        player.speed = playbackParameters.speed
    }

    // getState runs only on the main looper (as do the invalidation collectors), so the memo
    // needs no locking. Rebuilding one MediaItemData per queue item on EVERY snapshot is the
    // SimpleBasePlayer tax this avoids: the playlist part changes only when the queue instance,
    // the current index, or the current item's (meaningful) duration does.
    private var builtFor: List<PlayableItem>? = null
    private var builtIndex = -1
    private var builtDurationMs = Long.MIN_VALUE
    private var builtPlaylist: List<MediaItemData> = emptyList()

    private fun playlist(items: List<PlayableItem>, currentIndex: Int, durationMs: Long): List<MediaItemData> {
        if (items !== builtFor || currentIndex != builtIndex || meaningfulDurationChange(durationMs, builtDurationMs)) {
            builtFor = items
            builtIndex = currentIndex
            builtDurationMs = durationMs
            // The published timeline is a floating window around the current item (the legacy
            // 15-item window, widened): the facade may hold a 1000-item queue, but controllers,
            // car queue UIs and AVRCP get a usable slice without a full re-convert per change.
            // The window is a pure function of (size, currentIndex), so the memo key above
            // already covers every slide.
            val start = windowStart(items.size, currentIndex)
            val end = (start + QUEUE_WINDOW_MAX).coerceAtMost(items.size)
            builtPlaylist = (start until end).map { i ->
                val item = items[i]
                val key = item.mediaKey
                // The engine's measured duration (current item only) beats the item's own
                // source-declared one; other rows fall back to what they carried in.
                val itemDurationMs = when {
                    i == currentIndex && durationMs > 0 -> durationMs
                    else -> item.durationMs?.takeIf { it > 0 }
                }
                // Uid carries the ABSOLUTE index: a sliding window keeps unchanged items' identity.
                MediaItemData.Builder("$i:$key")
                    .setMediaItem(
                        MediaItem.Builder().setMediaId(key).setMediaMetadata(describe(item)).build(),
                    )
                    .setDurationUs(itemDurationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                    .setIsSeekable((item as? PlayableItem.YtMedia)?.live != true)
                    .build()
            }
        }
        return builtPlaylist
    }

    private inline fun done(block: () -> Unit): ListenableFuture<*> {
        block()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val TAG = "Media3PlayerAdapter"
        const val SEEK_INCREMENT_MS = 10_000L

        /** Max published timeline items — the floating window around the current item. Media3
         *  survives huge queues (ParceledListSlice chunks the platform parcel), but every
         *  timeline change re-converts and re-streams the whole list to every controller, and a
         *  car UI showing 1000 rows is its own failure; ±25 keeps the queue drawer useful. */
        const val QUEUE_WINDOW_MAX = 51

        /** First absolute index of the published window: centered on current, clamped so a full
         *  window survives near either end. */
        fun windowStart(size: Int, currentIndex: Int): Int {
            if (size <= QUEUE_WINDOW_MAX) return 0
            return (currentIndex - QUEUE_WINDOW_MAX / 2)
                .coerceIn(0, size - QUEUE_WINDOW_MAX)
        }
        const val POSITION_JUMP_MS = 2_000L
        const val DURATION_EPSILON_MS = 1_000L

        /** True when a duration re-anchor is worth a snapshot: it appeared/vanished, or moved by
         *  more than the epsilon — engines refining a known duration by a few ms are noise. */
        fun meaningfulDurationChange(durationMs: Long, prevMs: Long): Boolean =
            (durationMs > 0) != (prevMs > 0) || (durationMs > 0 && abs(durationMs - prevMs) > DURATION_EPSILON_MS)

        val COMMANDS: Media3Player.Commands = Media3Player.Commands.Builder()
            .addAll(
                Media3Player.COMMAND_PLAY_PAUSE,
                Media3Player.COMMAND_PREPARE,
                Media3Player.COMMAND_STOP,
                Media3Player.COMMAND_SET_MEDIA_ITEM,
                Media3Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Media3Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_PREVIOUS,
                Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_NEXT,
                Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_BACK,
                Media3Player.COMMAND_SEEK_FORWARD,
                Media3Player.COMMAND_SET_SPEED_AND_PITCH,
                Media3Player.COMMAND_SET_SHUFFLE_MODE,
                Media3Player.COMMAND_SET_REPEAT_MODE,
                Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Media3Player.COMMAND_GET_TIMELINE,
                Media3Player.COMMAND_GET_METADATA,
                Media3Player.COMMAND_GET_VOLUME,
                Media3Player.COMMAND_SET_VOLUME,
                Media3Player.COMMAND_RELEASE,
            )
            .build()
    }
}

/** The queue key for a [PlayableItem] — stable identity for media3 timeline items. */
val PlayableItem.mediaKey: String
    get() = when (this) {
        is PlayableItem.YtMedia -> videoId
        is PlayableItem.LocalMedia -> uri
    }
