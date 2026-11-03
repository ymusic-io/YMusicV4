package io.ymusic.android

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.ymusic.player.LoopMode
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackState
import io.ymusic.player.Player
import io.ymusic.player.PlayerError
import io.ymusic.player.isPlaying
import io.ymusic.player.media3.Media3PlayerAdapter
import io.ymusic.ui.AppGraph
import io.ymusic.ui.SearchFocus
import io.ymusic.ui.playFromSearch
import io.ymusic.ui.playerErrorMessage
import io.ymusic.ui.playhistory.PlayAgainStore
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_repeat_all
import io.ymusic.ui.resources.cd_repeat_off
import io.ymusic.ui.resources.cd_repeat_one
import io.ymusic.ui.resources.cd_shuffle
import io.ymusic.ui.youtubeThumbUrl
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import projectcommon.error.runSuspendCatching
import androidx.media3.session.R as Media3R

/**
 * The foreground playback host over the shared app-graph player: a media3 [MediaLibraryService]
 * provides the MediaSession surface (media buttons, lock screen, Bluetooth/Auto transport) via
 * [Media3PlayerAdapter] plus the Android Auto browse tree ([MediaBrowseTree]), while the
 * notification is built here directly from the `Player` flows — the service stays foreground while
 * there is an item, paused included, until the notification's close action ends it. Started by
 * [YMusicApplication] whenever playback begins; holds wake/wifi locks while
 * playing so YouTube/MSE keeps streaming with the screen off.
 *
 * A browse tap arrives as the controller's setMediaItems: [LibraryCallback.onAddMediaItems]
 * re-resolves the bare mediaIds through the tree, and [applyBrowseItems] turns them into facade
 * queues with the phone app's own semantics — a YouTube video plays with watch-next autoplay
 * ([io.ymusic.ui.PlaybackCoordinator.playNow]), a local song queues its whole containing list at its
 * position ([io.ymusic.ui.PlaybackCoordinator.playLocal]).
 */
class PlaybackService : MediaLibraryService() {
    private var player: Player? = null
    private var session: MediaLibrarySession? = null
    private var tree: MediaBrowseTree? = null
    private var coordinator: io.ymusic.ui.PlaybackCoordinator? = null
    private var playAgain: PlayAgainStore? = null
    private val ready = CompletableDeferred<Unit>()
    /** [ready] for Guava callers (the deferred bitmap loader). */
    private val readyFuture = SettableFuture.create<Unit>()
    /** The session's player until [init] swaps in the real adapter (cold start only). */
    private var pendingPlayer: PendingPlayer? = null
    private val bitmapLoader = DeferredBitmapLoader()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var art: Pair<String, Bitmap?>? = null

    /** Whether WE hold foreground — every promote/demote runs on the main thread through this
     *  service, so the flag is authoritative: promotes are skipped when already foreground, and
     *  a media3-triggered promotion that never leads to playback can be detected and demoted. */
    private var foreground = false

    /** media3 asks for a promotion on every player event while playing: a refusal streak logs once. */
    private var foregroundRefused = false

    /** Closed from the notification: stop() keeps the item, so nothing is posted until playback starts again. */
    private var dismissed = false

    // Set in onCreate — getApplication() is null until the service is attached.
    private var log: Logger? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> log?.fork("uncaught")?.captureError(e) },
    )
    private val searchResults = HashMap<String, List<MediaItem>>() // guarded by itself

    override fun onCreate() {
        super.onCreate()
        log = (application as YMusicApplication).rootLogger.fork("playback-service")
        createChannel()
        // The session must exist before the first bind: a legacy MediaBrowserService bind (Auto on a
        // dead process) that finds no session gets a null binder the framework caches for the whole
        // binding, leaving Auto spinning. NEVER block on graphAsync() here: it builds on main.
        val pending = PendingPlayer(
            onPlay = ::resumeWhenReady,
            onSetItems = { items, start -> serviceScope.launch { ready.await(); applyBrowseItems(items, start) } },
        ).also { pendingPlayer = it }
        sessionOwner?.takeIf { it !== this }?.let { stale ->
            log?.log(Logger.Level.WARN, "a previous PlaybackService still holds a live session; releasing it")
            stale.releaseSession()
        }
        sessionOwner = this
        session = MediaLibrarySession.Builder(this, pending, LibraryCallback())
            .setId("playback-${++sessionCount}")
            .setSessionActivity(sessionActivityIntent)
            // Artwork through the shared app ImageLoader (https + art://, UI-shared caches)
            // instead of media3's DataSource loader, which knows neither art:// nor our caches.
            // CacheBitmapLoader memoizes the current artwork; our loader stays out of the LRU.
            .setBitmapLoader(CacheBitmapLoader(bitmapLoader))
            .build()
        // The graph is normally long built when playback starts this service (await returns
        // inline); the cold path initializes async — onStartCommand's placeholder covers the
        // foreground contract meanwhile.
        serviceScope.launch {
            init((application as YMusicApplication).graphAsync().await())
        }
    }

    @OptIn(UnstableApi::class)
    private fun init(graph: AppGraph) {
        val player = graph.player.also { this.player = it }
        tree = MediaBrowseTree(this, graph)
        coordinator = graph.playback
        playAgain = graph.playHistory?.playAgain
        val adapter = Media3PlayerAdapter(
            player,
            describe = { item -> item.toMediaMetadata(this) },
            logger = log,
            applyMediaItems = { _, items, startIndex, _ -> applyBrowseItems(items, startIndex) },
            insertMediaItems = { target, index, items -> insertBrowseItems(target, index, items) },
            errorMessage = { error -> errorText?.takeIf { it.first === error }?.second },
            extraInvalidations = errorTextChanged,
        )
        bitmapLoader.delegate = graph.imageLoader?.let {
            ImageLoaderBitmapLoader(
                it,
                serviceScope,
                artworkAuthority(this@PlaybackService),
                sessionArtworkLimit(),
            )
        }
        session?.player = adapter
        pendingPlayer?.release()
        pendingPlayer = null
        serviceScope.launch {
            player.status.map { (it.state as? PlaybackState.Error)?.error }.distinctUntilChanged().collect { error ->
                errorText = error?.let { it to playerErrorMessage(it) }
                errorTextChanged.emit(Unit)
            }
        }
        serviceScope.launch {
            combine(player.status, player.queue) { status, queue ->
                Triple(queue.shuffle, queue.loopMode, isPlaying(status, queue))
            }.distinctUntilChanged().collect { (shuffle, loop, playing) ->
                session?.setMediaButtonPreferences(customButtons(shuffle, loop, playing))
            }
        }
        serviceScope.launch {
            combine(player.status, player.queue) { status, queue ->
                isPlaying(status, queue) to queue.currentItem
            }.distinctUntilChanged().collect { updateNotification() }
        }
        holdLocksWhilePlaying(player)
        ready.complete(Unit)
        readyFuture.set(Unit)
    }

    private var resumeJob: Job? = null

    /** The localized text for the player's current error, for controllers (Android Auto). */
    private var errorText: Pair<PlayerError, String>? = null
    private val errorTextChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Auto shows custom actions only (a legacy controller sees no shuffle/repeat otherwise). Android 13+
     *  media controls show two custom actions, so a paused session trades repeat for close. */
    private suspend fun customButtons(shuffle: Boolean, loop: LoopMode, playing: Boolean): ImmutableList<CommandButton> {
        val shuffleButton = CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(getString(Res.string.cd_shuffle))
            .setSessionCommand(COMMAND_SHUFFLE)
            .build()
        if (!playing) {
            val closeButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(R.drawable.ic_notification_close)
                .setDisplayName(getString(R.string.notification_close))
                .setSessionCommand(COMMAND_CLOSE)
                .build()
            return ImmutableList.of(shuffleButton, closeButton)
        }
        return ImmutableList.of(
            shuffleButton,
            CommandButton.Builder(
                when (loop) {
                    LoopMode.None -> CommandButton.ICON_REPEAT_OFF
                    LoopMode.All -> CommandButton.ICON_REPEAT_ALL
                    LoopMode.One -> CommandButton.ICON_REPEAT_ONE
                },
            )
                .setDisplayName(
                    getString(
                        when (loop) {
                            LoopMode.None -> Res.string.cd_repeat_off
                            LoopMode.All -> Res.string.cd_repeat_all
                            LoopMode.One -> Res.string.cd_repeat_one
                        },
                    ),
                )
                .setSessionCommand(COMMAND_REPEAT)
                .build(),
        )
    }

    /** [playOrResume] once the graph exists. Single-flight: one play press reaches both
     *  onPlaybackResumption and the player's play, and two concurrent resumes would load the
     *  "Play again" queue twice. */
    private fun resumeWhenReady() {
        if (resumeJob?.isActive == true) return
        resumeJob = serviceScope.launch {
            ready.await()
            playOrResume()
        }
    }

    /** Play, or with an empty queue (fresh process) resume the newest "Play again" session. */
    private suspend fun playOrResume() {
        val player = this.player ?: return
        if (player.queue.value.currentItem != null) return player.play()
        val last = playAgain?.sessions()?.firstOrNull() ?: return
        coordinator?.playAgain(last)
    }

    private suspend fun onWidgetCommand(action: String) {
        ready.await()
        val player = this.player ?: return
        when (action) {
            ACTION_WIDGET_PLAY -> playOrResume()
            ACTION_WIDGET_PAUSE -> player.pause()
            ACTION_WIDGET_NEXT -> player.skipNext()
            ACTION_WIDGET_PREV -> player.skipPrev()
        }
        // Nothing to play: no (playing × item) transition will demote the start's foreground.
        if (player.queue.value.currentItem == null) updateNotification()
    }

    /** A controller's setMediaItems (Auto browse/search tap), applied with phone-app semantics. */
    private fun applyBrowseItems(items: List<MediaItem>, startIndex: Int) {
        val tree = this.tree ?: return
        val coordinator = this.coordinator ?: return
        val chosen = items.getOrNull(startIndex) ?: items.firstOrNull() ?: return
        val id = chosen.mediaId
        when {
            // A voice request: no id, the words in requestMetadata (see onAddMediaItems).
            id.isEmpty() -> playFromVoice(chosen.requestMetadata.searchQuery, chosen.requestMetadata.extras)
            id.startsWith(MediaBrowseTree.YT_SONG_PREFIX) -> {
                val videoId = id.removePrefix(MediaBrowseTree.YT_SONG_PREFIX)
                // A cache miss (process died since browsing) still plays from the id alone.
                val video = tree.ytItem(videoId) ?: io.ymusic.dynamicengine.yt.YtFeedItem(
                    kind = io.ymusic.dynamicengine.yt.YtFeedItem.KIND_VIDEO,
                    id = videoId,
                    title = chosen.mediaMetadata.title?.toString() ?: videoId,
                    subtitleLine1 = chosen.mediaMetadata.artist?.toString(),
                    channelTitle = chosen.mediaMetadata.artist?.toString(),
                )
                coordinator.playNow(video)
            }
            id.startsWith(MediaBrowseTree.LOCAL_SONG_PREFIX) -> {
                val (containerId, rowId) = tree.parseLocalSongId(id) ?: return
                serviceScope.launch {
                    val songs = tree.localContext(containerId)
                    val index = songs.indexOfFirst { it.id == rowId }
                    if (index >= 0) coordinator.playLocal(songs, index)
                }
            }
        }
    }

    /** Gemini / Assistant "play [query] on YMusic" (Auto's play-from-search or [VoiceSearchActivity]);
     *  a blank query ("play YMusic") resumes the last session. */
    private fun playFromVoice(query: String?, extras: Bundle?) {
        if (query.isNullOrBlank()) return resumeWhenReady()
        serviceScope.launch {
            ready.await()
            val coordinator = coordinator ?: return@launch
            val voiceLog = log?.fork("voice-search", extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)?.let { mapOf("focus" to it) })
            val played = runSuspendCatching { coordinator.playFromSearch(query, searchFocus(extras)) }
                .onFailure { voiceLog?.log(Logger.Level.WARN, "voice search failed", it) }
                .getOrDefault(false)
            if (!played) voiceLog?.log(Logger.Level.WARN, "voice search matched nothing playable")
            // Nothing to play: no (playing × item) transition will demote the start's foreground.
            if (player?.queue?.value?.currentItem == null) updateNotification()
        }
    }

    /** Controller-added items (addMediaItems / legacy addQueueItem) — already resolved by
     *  [LibraryCallback.onAddMediaItems] — converted back and inserted into [player]'s queue.
     *  Local ids need one store lookup for the file uri, hence the launch. */
    private fun insertBrowseItems(player: Player, index: Int, items: List<MediaItem>) {
        val tree = this.tree ?: return
        serviceScope.launch {
            val playable = items.mapNotNull { tree.toPlayableItem(it) }
            if (playable.isNotEmpty()) {
                player.add(playable, at = index.coerceIn(0, player.queue.value.items.size))
            }
        }
    }

    // Headset button multi-click (legacy MediaSessionManager port): media3 natively handles one
    // click (toggle) and two (next) on HEADSETHOOK/PLAY_PAUSE but not three → previous, so those
    // keys are intercepted whole and counted here — 300ms window, 1=toggle 2=next ≥3=prev (the
    // third click resolves immediately).
    private var headsetClickCount = 0
    private var headsetClickJob: Job? = null

    private fun onHeadsetButtonClick() {
        headsetClickJob?.cancel()
        headsetClickCount++
        if (headsetClickCount > 2) {
            resolveHeadsetClicks()
        } else {
            headsetClickJob = serviceScope.launch {
                delay(HEADSET_CLICK_WINDOW_MS)
                resolveHeadsetClicks()
            }
        }
    }

    private fun resolveHeadsetClicks() {
        val clicks = headsetClickCount
        headsetClickCount = 0
        headsetClickJob = null
        serviceScope.launch {
            ready.await()
            val player = this@PlaybackService.player ?: return@launch
            when {
                clicks == 1 -> if (player.queue.value.currentItem == null) resumeWhenReady() else player.togglePlay()
                clicks == 2 -> player.skipNext()
                clicks > 2 -> player.skipPrev()
            }
        }
    }

    /** The Auto browse surface — all list building lives in [MediaBrowseTree]. */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        /** The tree once the graph exists; null after [INIT_TIMEOUT_MS] so Auto gets an error
         *  result instead of an endless spinner. */
        private suspend fun browseTree(): MediaBrowseTree? =
            withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() }?.let { this@PlaybackService.tree }

        // Media3 gives untrusted controllers (any third-party app, e.g. a screen reader dispatching
        // media keys) read-only player commands and drops the rest silently; any app may drive playback.
        @OptIn(UnstableApi::class)
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(COMMAND_SHUFFLE)
                        .add(COMMAND_REPEAT)
                        .add(COMMAND_CLOSE)
                        .build(),
                )
                .build(),
        )

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = this@PlaybackService.player
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_SESSION_DISCONNECTED))
            val queue = player.queue.value
            when (customCommand.customAction) {
                COMMAND_SHUFFLE.customAction -> player.setShuffle(!queue.shuffle)
                COMMAND_REPEAT.customAction -> player.setLoopMode(
                    when (queue.loopMode) {
                        LoopMode.None -> LoopMode.All
                        LoopMode.All -> LoopMode.One
                        LoopMode.One -> LoopMode.None
                    },
                )
                COMMAND_CLOSE.customAction -> dismiss()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (keyEvent != null &&
                (keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ) {
                if (keyEvent.action == KeyEvent.ACTION_DOWN) onHeadsetButtonClick()
                return true
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // Must be already done: the legacy onGetRoot blocks the main thread until this resolves,
            // and its completion callback is posted to that same main thread — a pending future deadlocks.
            Futures.immediateFuture(LibraryResult.ofItem(MediaBrowseTree.ROOT_ITEM, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val tree = browseTree() ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
            LibraryResult.ofItemList(ImmutableList.copyOf(tree.children(parentId, page, pageSize)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            browseTree()?.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            val tree = browseTree() ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
            val results = tree.searchItems(query)
            synchronized(searchResults) { searchResults[query] = results }
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = synchronized(searchResults) { searchResults[query] }
                ?: browseTree()?.searchItems(query)
                ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
            val sliced = if (pageSize > 0 && page >= 0) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(ImmutableList.copyOf(sliced), params)
        }

        /** Controllers send bare mediaIds; refill each from the tree so the adapter's queue items
         *  carry display metadata (an unknown id passes through — [applyBrowseItems] copes).
         *  A voice request is one item with an empty mediaId and the words + focus extras in
         *  requestMetadata: it passes through untouched for [applyBrowseItems] to resolve. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            if (mediaItems.singleOrNull()?.requestMetadata?.searchQuery != null) return@future mediaItems
            val tree = browseTree() ?: return@future mediaItems
            mediaItems.map { tree.item(it.mediaId) ?: it }.toMutableList()
        }

        /** "Play" with nothing queued (steering-wheel button, Auto's play on a fresh process):
         *  YMusic resumes through its own "Play again" sessions rather than a media3 item list,
         *  so start that and decline media3's resumption. isForPlayback = false is the system
         *  only asking what it would play (Android 13+ media controls) — never start then. */
        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (isForPlayback) resumeWhenReady()
            return Futures.immediateFailedFuture(UnsupportedOperationException("resumed via Play again"))
        }
    }

    /** Session artwork loader that exists before the graph: requests made during a cold start
     *  wait for [readyFuture], then go to the app ImageLoader-backed [delegate]. */
    @OptIn(UnstableApi::class)
    private inner class DeferredBitmapLoader : BitmapLoader {
        @Volatile var delegate: BitmapLoader? = null

        override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = chain { it.decodeBitmap(data) }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = chain { it.loadBitmap(uri) }

        private fun chain(op: (BitmapLoader) -> ListenableFuture<Bitmap>): ListenableFuture<Bitmap> {
            delegate?.let { return op(it) }
            return Futures.transformAsync(
                readyFuture,
                { delegate?.let(op) ?: Futures.immediateFailedFuture(IllegalStateException("no image loader")) },
                MoreExecutors.directExecutor(),
            )
        }
    }

    /** Callback work on the service scope, bridged to the ListenableFuture media3 expects. */
    private fun <T : Any> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch(Dispatchers.Default) {
            try {
                future.set(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                future.cancel(false)
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, Runnable::run)
        return future
    }

    /** media3 posts its own DefaultMediaNotificationProvider notification for an active player
     *  (1.5.1 never did without a bound controller — the gap the hand-built notification grew
     *  in), and its default id is our 1001, which would silently swap styles under us. THIS
     *  OVERRIDE EXISTING (no super call) is what suppresses that. The body handles only what the
     *  (status × queue) collector cannot see: media3 signaling the foreground contract is due (a
     *  media-button service start on a dead process) — promote immediately regardless of play
     *  state. Routine refreshes stay with the collector; mirroring them here would double-build
     *  the same notification. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) {
            if (!foreground) foreground = promote(buildNotification())
        } else if (foreground && player?.let { isPlaying(it.status.value, it.queue.value) } != true) {
            // The contract-promotion that never became playback (media-button start on a dead
            // process, nothing restored): no (playing × item) transition will ever fire the
            // collector, so demote through the one owner when media3 releases the contract.
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            super.onStartCommand(intent, flags, startId) // LifecycleService dispatches on super
            dismiss()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PLAY_FROM_SEARCH) {
            playFromVoice(intent.getStringExtra(SearchManager.QUERY), intent.extras)
        }
        intent?.action?.takeIf { it in WIDGET_ACTIONS }?.let { action ->
            serviceScope.launch { onWidgetCommand(action) }
        }
        // super routes MEDIA_BUTTON intents (our notification actions) onto the session.
        val result = super.onStartCommand(intent, flags, startId)
        // The startForegroundService contract: enter foreground now; the collector demotes on pause.
        // A plain start from the background (the system restarting the sticky service after the
        // process died) is refused promotion and stays a background service.
        foreground = promote(buildNotification())
        return result
    }

    private fun dismiss() {
        dismissed = true
        player?.stop()
        stopForegroundSafely(ServiceCompat.STOP_FOREGROUND_REMOVE, log)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        foreground = false
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // media3's own check reads its notification manager's foreground flag, which this service's own
    // notification never sets, so it would stop playback on every swipe-away. VoiceSearchActivity's
    // task ends the moment it forwards, before the request it handed over has played anything.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (rootIntent?.component?.className == VoiceSearchActivity::class.java.name) return
        if (player?.let { isPlaying(it.status.value, it.queue.value) } == true) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Teardown steps are independent: one failing must not skip the rest (a held wake lock
        // or an unreleased session outliving the service is the real leak).
        runCatching { serviceScope.cancel() }
        releaseLocks()
        releaseSession()
        if (sessionOwner === this) sessionOwner = null
        art = null
        super.onDestroy()
    }

    private fun releaseSession() {
        session?.let { mediaSession ->
            val adapter = mediaSession.player
            runCatching { mediaSession.release() }
                .onFailure { log?.log(Logger.Level.WARN, "session release failed", it) }
            runCatching { adapter.release() }
                .onFailure { log?.log(Logger.Level.WARN, "player adapter release failed", it) }
        }
        session = null
        pendingPlayer = null
    }

    /** Foreground while there is an item, paused included: Android 12+ refuses a promotion from the
     *  background, so a service demoted on pause could never come back when playback resumes there
     *  (focus regained, Bluetooth) and gets frozen. The notification's close action ends it. */
    private fun updateNotification() {
        val player = this.player ?: return
        if (player.queue.value.currentItem == null) {
            stopForegroundSafely(ServiceCompat.STOP_FOREGROUND_REMOVE, log)
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            foreground = false
            return
        }
        if (isPlaying(player.status.value, player.queue.value)) {
            dismissed = false
        } else if (dismissed) {
            return
        }
        val notification = buildNotification()
        if (foreground) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } else {
            foreground = promote(notification)
        }
    }

    /** startForeground, or the notification alone when Android 12+ refuses a promotion from the
     *  background (playback resumed by Bluetooth, a car, or audio focus coming back while the app is
     *  backgrounded). Playback continues; the service stays background until a later promotion. */
    private fun promote(notification: android.app.Notification): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, notification)
            foregroundRefused = false
            true
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is android.app.ForegroundServiceStartNotAllowedException) throw e
            if (!foregroundRefused) log?.log(Logger.Level.WARN, "foreground start refused in background", e)
            foregroundRefused = true
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            false
        }
    }

    private fun buildNotification(): android.app.Notification {
        val item = player?.queue?.value?.currentItem
        val playing = player?.let { isPlaying(it.status.value, it.queue.value) } == true
        // Legacy three-line shape: title / artist / album as subText — v4's joined "artist · album"
        // subtitle would print the album twice, so a local item splits it here.
        val contentText: String?
        val subText: String?
        when (item) {
            is PlayableItem.LocalMedia -> {
                contentText = item.artist ?: item.album
                subText = item.album.takeIf { item.artist != null }
            }
            else -> {
                contentText = item?.displaySubtitle
                subText = null
            }
        }
        // Legacy mirrored the outer actions manually in RTL: the glyph keeps its slot, the
        // dispatched command and label swap, so after the system mirrors the row the skip
        // directions still read correctly.
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // Legacy state-dependent glyph (playing shows the play arrow), not the launcher icon.
            .setSmallIcon(if (playing) Media3R.drawable.media3_icon_play else Media3R.drawable.media3_icon_pause)
            .setContentTitle(item?.displayTitle ?: getString(R.string.app_name))
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(sessionActivityIntent)
            .setDeleteIntent(dismissIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Media3R.drawable.media3_icon_previous,
                getString(if (rtl) R.string.notification_next else R.string.notification_previous),
                mediaButtonIntent(if (rtl) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            )
            .addAction(
                if (playing) Media3R.drawable.media3_icon_pause else Media3R.drawable.media3_icon_play,
                getString(if (playing) R.string.notification_pause else R.string.notification_play),
                mediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            )
            .addAction(
                Media3R.drawable.media3_icon_next,
                getString(if (rtl) R.string.notification_previous else R.string.notification_next),
                mediaButtonIntent(if (rtl) KeyEvent.KEYCODE_MEDIA_PREVIOUS else KeyEvent.KEYCODE_MEDIA_NEXT),
            )
            .addAction(R.drawable.ic_notification_close, getString(R.string.notification_close), dismissIntent)
        session?.let { s ->
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    // media3 1.7+ exposes only the platform token; MediaStyle still wants compat.
                    .setMediaSession(MediaSessionCompat.Token.fromToken(s.platformToken))
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(dismissIntent),
            )
        }
        val artUrl = item?.displayThumbnailUrl
        if (artUrl != null) {
            val cached = art
            if (cached?.first == artUrl) {
                cached.second?.let(builder::setLargeIcon)
            } else {
                loadArt(artUrl)
            }
        }
        return builder.build()
    }

    /**
     * The platform session scales artwork above config_mediaMetadataBitmapMaxSize measured with the app's
     * resources; media3's limit measures it at the system density, which differs once the display size changed.
     */
    @SuppressLint("DiscouragedApi") // the dimen is not public
    private fun sessionArtworkLimit(): Int {
        val id = resources.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android")
        val platform = if (id != 0) resources.getDimensionPixelSize(id) else Int.MAX_VALUE
        return minOf(platform, MediaSession.getBitmapDimensionLimit(this))
    }

    /** One in-flight load keyed by url; on success the notification is rebuilt with the bitmap. */
    private fun loadArt(url: String) {
        val s = session ?: return
        art = url to null
        val future = s.bitmapLoader.loadBitmap(Uri.parse(url))
        future.addListener(
            {
                val bitmap = runCatching { future.get() }
                    .onFailure { log?.debug { "notification artwork load failed for $url: $it" } }
                    .getOrNull() ?: return@addListener
                if (art?.first == url) {
                    // Its own copy: the session publishes the same cached instance.
                    art = url to bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    updateNotification()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private val sessionActivityIntent: PendingIntent by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    }

    private val dismissIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            DISMISS_REQUEST_CODE,
            Intent(this, PlaybackService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // PendingIntent.get* is a binder call (OEM hooks can stall it), and the notification is rebuilt on every status change.
    private val mediaButtonIntents = HashMap<Int, PendingIntent>()

    /** A notification action as a media-button press — super.onStartCommand routes it to the session. */
    private fun mediaButtonIntent(keyCode: Int): PendingIntent = mediaButtonIntents.getOrPut(keyCode) {
        PendingIntent.getService(
            this,
            keyCode,
            Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(this, PlaybackService::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.playback_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun holdLocksWhilePlaying(player: Player) {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YMusic:playback")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }

        @Suppress("DEPRECATION")
        val net = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YMusic:playback")
            .apply { setReferenceCounted(false) }
            .also { wifiLock = it }
        serviceScope.launch {
            combine(player.status, player.queue) { status, queue ->
                // The wifi lock is only worth its battery cost when something in the queue streams;
                // pure-local playback keeps just the partial wake lock (legacy useWifi gating).
                isPlaying(status, queue) to queue.items.any { it is PlayableItem.YtMedia }
            }.distinctUntilChanged().collectLatest { (playing, online) ->
                if (playing) {
                    @Suppress("WakelockTimeout")
                    wake.acquire()
                    if (online) {
                        net.acquire()
                    } else {
                        // release() throws on a racy double-release; a swallowed race beats a crash loop.
                        runCatching { if (net.isHeld) net.release() }
                    }
                } else {
                    delay(LOCK_RELEASE_GRACE_MS)
                    releaseLocks()
                }
            }
        }
    }

    private fun releaseLocks() {
        // release() throws on a racy double-release; a swallowed race beats a crash loop here.
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
    }

    private companion object {
        /** How long an Auto browse call waits for a cold-start graph before failing. */
        const val INIT_TIMEOUT_MS = 30_000L
        const val CHANNEL_ID = "io.ymusic.playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DISMISS = "io.ymusic.android.playback.DISMISS"
        val COMMAND_SHUFFLE = SessionCommand("io.ymusic.android.playback.SHUFFLE", Bundle.EMPTY)
        val COMMAND_REPEAT = SessionCommand("io.ymusic.android.playback.REPEAT", Bundle.EMPTY)
        val COMMAND_CLOSE = SessionCommand("io.ymusic.android.playback.CLOSE", Bundle.EMPTY)
        const val DISMISS_REQUEST_CODE = 100
        const val LOCK_RELEASE_GRACE_MS = 60_000L
        const val HEADSET_CLICK_WINDOW_MS = 300L

        // media3 keeps every session id in a process-wide registry until release(), and a constructor
        // that fails after registering leaks its id: each instance gets a fresh one. Main thread only.
        var sessionCount = 0

        // The instance whose session is live. Android can start a new instance while an old one never
        // got onDestroy; the new one releases the old session so the process never holds two.
        var sessionOwner: PlaybackService? = null
    }
}

/** Session/notification metadata for a queue item — items are self-describing. The artworkUri is
 *  published in [artworkContentUri]-wrapped form: Android Auto resolves queue icons only from
 *  local content uris (via [ArtworkProvider]); in-process, [ImageLoaderBitmapLoader] unwraps it. */
private fun PlayableItem.toMediaMetadata(context: android.content.Context): MediaMetadata {
    val builder = MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(displaySubtitle)
    // A YouTube item exposes the stable mqdefault url derived from its video id — feed thumb
    // urls carry rotating signature params and expire inside long-lived session uris. Local
    // items already carry the stable art://pic/<id>.
    val target = when (this) {
        is PlayableItem.YtMedia -> youtubeThumbUrl(videoId)
        else -> displayThumbnailUrl
    }
    target?.let { builder.setArtworkUri(artworkContentUri(context, it)) }
    return builder.build()
}

/** Android's voice-search focus extra (MediaStore.EXTRA_MEDIA_FOCUS) as a [SearchFocus]. */
private fun searchFocus(extras: Bundle?): SearchFocus = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
    MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> SearchFocus.Song
    MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> SearchFocus.Artist
    MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> SearchFocus.Album
    MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> SearchFocus.Playlist
    else -> SearchFocus.Any
}
