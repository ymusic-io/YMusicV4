package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.PlaybackState
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.Player
import io.ymusic.player.PlayerError
import io.ymusic.player.QueueOrigin
import io.ymusic.player.QueueSource
import io.ymusic.player.QueueState
import io.ymusic.ui.playhistory.PlayAgainSession
import kmpcommon.log.Logger
import kmpcommon.log.info
import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import projectcommon.error.TechnicalException
import kotlin.time.TimeSource

/**
 * UI-side playback glue over the [Player] facade. Every queue item is self-describing — a
 * [PlayableItem.LocalMedia] / [PlayableItem.YtMedia] carries its own id + display metadata — so the
 * coordinator holds NO side map: Now Playing reads the item's `display*` accessors straight off the
 * item. Watch-time ranking (when [AppGraph.playHistory] is set) credits elapsed play while Prepared.
 * Queues that keep growing (a video's radio, a playlist past its loaded page) carry a
 * [NextQueueSource].
 */
class PlaybackCoordinator(
    internal val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    val player: Player get() = graph.player

    private val log = graph.logger?.fork("player")
    private val playHistory = graph.playHistory
    private val mediaStore = graph.mediaStore

    init {
        // The player surfaces failure as state, not a throw — this transition IS the job-death
        // frame, so the one crash record per technical playback failure is captured here. A
        // playability verdict is the user's to act on: a warning, not a crash.
        if (log != null) {
            scope.launch {
                combine(player.status, player.queue, player.config) { status, queue, config ->
                    playTrail(status, queue, config)
                }.distinctUntilChanged().collect { log.info(it) }
            }
            scope.launch {
                player.queue.filter { it.queueId != null }.distinctUntilChangedBy { it.queueId }.collect { queue ->
                    log.info("queue origin=${queue.origin.name.lowercase()} size=${queue.items.size} index=${queue.currentIndex}")
                }
            }
            scope.launch {
                player.status.map { it.error }.distinctUntilChanged().collect { err ->
                    val id = itemId(player.queue.value.currentItem)
                    if (err != null && err.playability() != null) {
                        log.log(Logger.Level.WARN, "playability ${err.code} id=$id: ${err.message}")
                    } else if (err != null && err.code == PlayerError.FILE_MISSING) {
                        log.log(Logger.Level.WARN, "file missing id=$id: ${err.message}", err.cause)
                    } else if (err != null && err.code == PlayerError.FOLDER_ACCESS_LOST) {
                        log.log(Logger.Level.WARN, "folder access lost id=$id: ${err.message}", err.cause)
                    } else if (err != null && err.code == "network") {
                        log.log(Logger.Level.WARN, "network id=$id: ${err.message}", err.cause)
                    } else if (err != null && err.code == PlayerError.PLAYBACK_UNAVAILABLE) {
                        log.log(Logger.Level.WARN, "playback unavailable id=$id: ${err.message}")
                    } else if (err != null) {
                        log.captureError(
                            TechnicalException(err.code, err.message ?: "player error", cause = err.cause),
                            prop = mapOf("id" to id, "canRetry" to err.canRetry, "needReset" to err.needReset),
                        )
                    }
                }
            }
        }
        val pruneUngrantedFolders = graph.pruneUngrantedFolders
        if (pruneUngrantedFolders != null) {
            scope.launch {
                player.status.map { it.error?.code == PlayerError.FOLDER_ACCESS_LOST }.distinctUntilChanged().collect { lost ->
                    if (lost) pruneUngrantedFolders()
                }
            }
        }
        // Watch-time ranking: credit elapsedRealtime only while Prepared + playing, not buffering.
        // Same item staying playing (volume/size ticks) keeps the mark — resetting it would drop time.
        if (playHistory != null) {
            scope.launch {
                var trackingItem: PlayableItem? = null
                var trackedAt: TimeSource.Monotonic.ValueTimeMark? = null
                suspend fun flush() {
                    val item = trackingItem
                    val mark = trackedAt
                    trackingItem = null
                    trackedAt = null
                    if (item == null || mark == null) return
                    val watchTimeMs = mark.elapsedNow().inWholeMilliseconds
                    if (watchTimeMs <= 0L) return
                    try {
                        addWatchTime(item, watchTimeMs)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        log?.log(Logger.Level.WARN, "addWatchTime failed", e)
                    }
                }
                try {
                    combine(player.status, player.queue) { status, queue -> status to queue }
                        .collect { (status, queue) ->
                            val item = queue.currentItem
                            val prepared = status.state as? PlaybackState.Prepared
                            val playing = prepared != null &&
                                prepared.playWhenReady &&
                                !prepared.buffering &&
                                !prepared.ended &&
                                item != null
                            if (!playing) {
                                flush()
                            } else if (item != trackingItem) {
                                flush()
                                trackingItem = item
                                trackedAt = TimeSource.Monotonic.markNow()
                            }
                        }
                } finally {
                    withContext(NonCancellable) { flush() }
                }
            }
        }
    }

    /** The video to hand the download launcher — only a non-live YouTube item is downloadable (local or
     *  live → null).
     *  The one place a queue item is rebuilt into a [YtFeedItem], for the launcher's API. */
    fun downloadable(item: PlayableItem?): YtFeedItem? = (item as? PlayableItem.YtMedia)?.takeIf { !it.live }?.let {
        YtFeedItem(
            kind = YtFeedItem.KIND_VIDEO,
            id = it.videoId,
            title = it.title ?: it.videoId,
            subtitleLine1 = it.artist,
            channelTitle = it.artist,
            channelUrl = it.channelUrl,
            duration = it.duration,
            thumbs = it.thumbnailUrl?.let { url -> listOf(YtThumb(url)) } ?: emptyList(),
        )
    }

    /** Play [video] now with its radio (a YouTube Mix) as the rest of the queue, loading as it plays;
     *  its related videos when YouTube has no radio for it. */
    fun playNow(video: YtFeedItem) {
        player.setQueue(
            listOf(video.toYtMedia()),
            source = NextQueueSource.radio(graph.engines, video.id, log?.fork("queue-source")),
            origin = QueueOrigin.Radio,
        )
    }

    /** Play [video] alone — no radio. */
    fun playSingle(video: YtFeedItem) {
        player.setQueue(listOf(video.toYtMedia()), origin = QueueOrigin.Single)
    }

    /** Play [videos] as the queue (a playlist page), starting at [startIndex]; [source] loads the rest of
     *  the list as the queue goes. [shuffle] null keeps the user's shuffle mode. */
    fun playAll(videos: List<YtFeedItem>, startIndex: Int = 0, shuffle: Boolean? = null, source: QueueSource? = null) {
        val playable = videos.filter { it.kind == YtFeedItem.KIND_VIDEO }
        if (playable.isEmpty()) return
        shuffle?.let(player::setShuffle)
        player.setQueue(
            playable.map { it.toYtMedia() },
            playIndex = startIndex.coerceIn(0, playable.size - 1),
            source = source,
            origin = QueueOrigin.Playlist,
        )
    }

    /** Play the playlist at [url] from [videoId], [page] being its loaded first page; the rest of the list
     *  loads as the queue goes. False when [videoId] isn't on [page]. */
    fun playPlaylist(url: String, page: YtFeedPage, videoId: String): Boolean {
        val videos = page.sections.flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }
        val index = videos.indexOfFirst { it.id == videoId }
        if (index < 0) return false
        playAll(
            videos,
            startIndex = index,
            source = NextQueueSource.playlist(graph.engines, url, videos, page.pageHeader?.itemCount, log?.fork("queue-source")),
        )
        return true
    }

    /** Play local library [songs] as the queue, starting at [index]. [shuffle] null keeps the user's shuffle mode. */
    fun playLocal(songs: List<MediaStoreItem>, index: Int, shuffle: Boolean? = null) {
        shuffle?.let(player::setShuffle)
        player.setQueue(songs.map { it.toLocalMedia() }, playIndex = index, origin = QueueOrigin.Library)
    }

    /** Play just [song] now (queue of one). */
    fun playLocalSingle(song: MediaStoreItem) {
        player.setQueue(listOf(song.toLocalMedia()), origin = QueueOrigin.Single)
    }

    /**
     * Play a finished download now (queue of one). Its library item when the store has indexed it —
     * same id, tags and embedded-cover art as the Library; else the file by uri with [videoId]'s
     * thumbnail, resolved at the view size by the `art:` fetcher.
     */
    suspend fun playDownloaded(uri: String, videoId: String, title: String, artist: String?) {
        val item = mediaStore?.findItemByUri(Uri.parse(uri))?.toLocalMedia() ?: run {
            val art = if (mediaStore != null) youtubeArtUrl(videoId) else youtubeThumbUrl(videoId)
            PlayableItem.LocalMedia(uri = uri, title = title, artist = artist, thumbnailUrl = art)
        }
        player.setQueue(listOf(item), origin = QueueOrigin.Downloads)
    }

    /**
     * Resume a "Play again" queue as the same queue (its session keeps updating), its playlist
     * continuing from where the queue had loaded to. Mid-item only for long content
     * ([PlayAgainSession.resumesMidItem]) unless [fromBeginning]. False when the session is gone.
     */
    suspend fun playAgain(session: PlayAgainSession, fromBeginning: Boolean = false): Boolean {
        val store = playHistory?.playAgain ?: return false
        val (saved, items) = store.load(session.queueId) ?: return false
        if (items.isEmpty()) return false
        player.setQueue(
            items,
            playIndex = saved.queueIndex.coerceIn(0, items.lastIndex),
            startPositionMs = if (fromBeginning || !saved.resumesMidItem) 0L else saved.positionMs,
            origin = QueueOrigin.Resume,
            queueId = saved.queueId,
            source = saved.sourceToken?.let { NextQueueSource.resume(it, graph.engines, items, log?.fork("queue-source")) },
        )
        return true
    }

    /** Queue [song] right after the current item. */
    fun queueLocalNext(song: MediaStoreItem) {
        player.addToPlayNext(listOf(song.toLocalMedia()))
    }

    /** Append [song] to the end of the queue. */
    fun queueLocalLast(song: MediaStoreItem) {
        player.add(listOf(song.toLocalMedia()))
    }

    fun playNext(video: YtFeedItem) {
        player.addToPlayNext(listOf(video.toYtMedia()))
    }

    fun playLater(video: YtFeedItem) {
        player.add(listOf(video.toYtMedia()))
    }

    private suspend fun addWatchTime(item: PlayableItem, watchTimeMs: Long) {
        val history = playHistory ?: return
        when (item) {
            is PlayableItem.LocalMedia -> {
                val id = item.mediaId ?: return
                val row = mediaStore?.findItem(id) ?: return
                history.trackLocal(row.playlistCode, watchTimeMs)
                val vid = row.youtubeVid
                if (vid != null && !graph.guest) history.trackYoutube(vid, watchTimeMs)
            }
            is PlayableItem.YtMedia -> {
                if (graph.guest) return
                history.saveYoutubeMetadata(
                    videoId = item.videoId,
                    title = item.title ?: item.videoId,
                    artist = item.artist,
                    thumbnailUrl = item.thumbnailUrl ?: youtubeThumbUrl(item.videoId),
                    duration = item.duration,
                    durationMs = item.durationMs,
                    live = item.live,
                )
                history.trackYoutube(item.videoId, watchTimeMs)
            }
        }
    }
}

/** One line per playback-state change — what a report needs for buffering / black screen / stuck. */
private fun itemId(item: PlayableItem?): String = when (item) {
    is PlayableItem.YtMedia -> item.videoId
    is PlayableItem.LocalMedia -> item.uri.substringAfterLast('/')
    null -> "-"
}

private fun playTrail(status: PlaybackStatus, queue: QueueState, config: PlaybackConfig): String {
    val id = itemId(queue.currentItem)
    val phase = when (val st = status.state) {
        is PlaybackState.Uninitialized -> if (st.isInitializing) "init" else "uninit"
        is PlaybackState.Idle -> "idle"
        is PlaybackState.Error -> "error ${st.error.code}"
        is PlaybackState.Prepared -> when {
            st.ended -> "ended"
            st.buffering -> "buffering"
            st.playWhenReady -> "playing"
            else -> "paused"
        }
    }
    val size = status.videoSize?.let { "${it.width}x${it.height}" } ?: "-"
    return buildString {
        append(phase)
        append(" id=")
        append(id)
        if (status.live) append(" live")
        append(" ao=")
        append(if (config.audioOnly) "1" else "0")
        append(" q=")
        append(config.preferVideoQuality.first)
        append(" vol=${config.volume}")
        append(" speed=${config.speed} pitch=${config.pitchFactor}")
        append(' ')
        append(size)
        append(" queue=${queue.currentIndex + 1}/${queue.items.size}")
        append(" via=${queue.lastChange.name.lowercase()}")
    }
}

// ---- self-describing queue items ----
// Shared with the platform shells (the Android session service converts controller MediaItems
// back through these), so the queue-item mapping never forks.

/** A library item as a self-describing queue item (id + display metadata travel with it). */
fun MediaStoreItem.toLocalMedia() = PlayableItem.LocalMedia(
    uri = uri.toString(),
    mediaId = id,
    title = title,
    artist = artist,
    album = album,
    thumbnailUrl = localArtUrl(this),
    durationMs = durationMs,
)

/** A scraped video as a self-describing queue item — keeps just what the UI + downloads need. */
fun YtFeedItem.toYtMedia() = PlayableItem.YtMedia(
    videoId = id,
    title = title,
    artist = channelTitle,
    channelUrl = channelUrl,
    thumbnailUrl = thumbs.firstOrNull()?.url,
    duration = duration,
    durationMs = durationMs,
    live = live,
)
