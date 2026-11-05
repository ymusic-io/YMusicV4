package io.ymusic.player.mse

import io.ymusic.dynamicengine.BundleSignatureException
import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.JsSourceProvider
import io.ymusic.dynamicengine.SignedBundleRef
import io.ymusic.dynamicengine.VerifiedBundle
import io.ymusic.dynamicengine.embeddedPlayerJsSource
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.AudioSessionSource
import io.ymusic.player.AudioTrack
import io.ymusic.player.AudioTrackState
import io.ymusic.player.CaptionLine
import io.ymusic.player.CaptionState
import io.ymusic.player.CaptionTrack
import io.ymusic.player.MediaDetails
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.PlaybackState
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.PlayerError
import io.ymusic.player.PlayerErrorException
import io.ymusic.player.Progress
import io.ymusic.player.VideoQualityOption
import io.ymusic.player.VideoQualityState
import io.ymusic.player.VideoSize
import io.ymusic.player.WatchNextSeen
import io.ymusic.player.YtEngine
import io.ymusic.player.requireMainThread
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.SettingsStore
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.proxy.SabrProxyHttpClient
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import projectcommon.error.TechnicalException
import projectcommon.error.errorCode
import projectcommon.json.boolOrNull
import projectcommon.json.intOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull
import projectcommon.log.markTrail
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * YouTube [YtEngine] over engine-js + C MSE, mirroring legacy `WebViewYtPlayer`. Each [init]/[reset]
 * starts a [Session] (the legacy embed page): its own [newEngineThread] (single-threaded), one
 * [DynamicEnginePlayer] built on it, `player/init`, then a FIFO command queue. Control is
 * main-thread; commands run in call order.
 *
 * player.js upgrades only between sessions — a running player is never swapped under playback.
 * Every config the app receives downloads and verifies a newer published bundle and holds it for
 * the next session, which boots it as its gate: it is cached once it boots, and dropped for this
 * process when it does not. Otherwise a session boots the cached bundle, else the embedded one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MsePlayer internal constructor(
    private val newEngineThread: () -> CloseableCoroutineDispatcher,
    val videoSurface: MseVideoSurface,
    logger: Logger?,
    private val playerJs: JsSourceProvider,
    private val buildPlayer: (engineThread: CoroutineDispatcher, playerJs: String) -> DynamicEnginePlayer,
) : YtEngine,
    AudioSessionSource {
    private val log = logger?.fork("mse")

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Fire-and-forget teardown of abandoned sessions; deliberately outlives [release]. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(
        PlaybackStatus(state = PlaybackState.Uninitialized(isInitializing = false)),
    )
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
    override val videoQuality: StateFlow<VideoQualityState> = _videoQuality.asStateFlow()

    private val _captions = MutableStateFlow(CaptionState.DEFAULT)
    override val captions: StateFlow<CaptionState> = _captions.asStateFlow()

    private val _captionLines = MutableStateFlow<List<CaptionLine>>(emptyList())
    override val captionLines: StateFlow<List<CaptionLine>> = _captionLines.asStateFlow()

    private val _audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioTracks: StateFlow<AudioTrackState> = _audioTracks.asStateFlow()

    private val _audioSessionId = MutableStateFlow(0)
    override val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()

    private val _watchNextSeen = MutableSharedFlow<WatchNextSeen>(extraBufferCapacity = 8)
    override val watchNextSeen: SharedFlow<WatchNextSeen> = _watchNextSeen.asSharedFlow()

    private class Session(val job: Job) {
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)
        val commands = Channel<suspend (DynamicEnginePlayer) -> Unit>(Channel.UNLIMITED)
        val startedAt = TimeSource.Monotonic.markNow()
        var player: DynamicEnginePlayer? = null
        var captionJob: Job? = null

        /** What this session has applied (JS + native audio); null until the first push, which sends everything. */
        var config: PlaybackConfig? = null

        /** Loop as last sent to JS: `config.loop` forced off while a live stream is loaded. */
        var loop: Boolean? = null
        var live = false

        /** Commands sent but not yet started, and when the running one started — for [MseLoadTrace]. */
        var queued = 0
        var runningSince: TimeMark? = null
    }

    private var session: Session? = null

    /** The last torn-down session; the next one waits for it so two engines never overlap. */
    private var closing: Job? = null
    private var released = false

    /** Latest config from the caller. */
    private var config = PlaybackConfig()

    private var loadingVideoId: String? = null
    private var loadedVideoId: String? = null
    private var loadTimeoutJob: Job? = null
    private var loadTrace: MseLoadTrace? = null
    private var pendingVideoSurface: MseVideoSurface? = null
    private var hasPendingVideoSurface = false
    private var viewportW: Int = 640
    private var viewportH: Int = 360

    /** A newer published player.js, downloaded and verified, waiting for the next session to boot it. */
    private val pendingPlayerJs = MutableStateFlow<VerifiedBundle?>(null)

    /** Published player.js that failed its signature or its boot — not tried again by this player. */
    private val rejectedPlayerJs = MutableStateFlow<Set<SignedBundleRef>>(emptySet())

    private val bundleWatch: Job = cleanupScope.launch {
        playerJs.published.conflate().collect { remote -> if (remote != null) download(remote) }
    }

    override fun init() {
        requireMainThread()
        if (released || session != null) return
        startSession("init", freshConfig = false)
    }

    override fun reset() = reset("unspecified")

    override fun reset(reason: String) {
        requireMainThread()
        if (released) return
        tearDownSession("reset: $reason")
        startSession("reset: $reason", freshConfig = true)
    }

    override fun load(
        source: PlayableItem.YtMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        requireMainThread()
        if (released) return
        if (!_status.value.state.readyToLoad) {
            log?.log(Logger.Level.WARN, "mse load(${source.videoId}) while ${_status.value.state}")
            return
        }
        val activeSession = session ?: return
        this.config = config
        val previous = activeSession.config
        val loop = config.loop && !source.live
        activeSession.config = config
        activeSession.loop = loop
        activeSession.live = source.live
        loadingVideoId = source.videoId
        _progress.value = Progress(positionMs = startPositionMs)
        _status.update { it.copy(details = null) }
        applySurfaceAudio(previous, config)
        val width = viewportW
        val height = viewportH
        if (captionChanged(previous, config)) send { applyCaptionConfig(it, config) }
        val quality = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
        val trace = MseLoadTrace(
            source.videoId,
            config.audioOnly,
            quality,
            queuedAhead = activeSession.queued,
            runningCommandMs = activeSession.runningSince?.elapsedNow()?.inWholeMilliseconds,
            framesAtQueue = if (hostLive()) videoSurface.presentedFrames() else null,
        )
        loadTrace = trace
        send { enginePlayer ->
            trace.commandStarted()
            try {
                enginePlayer.load(
                    source.videoId,
                    log,
                    quality = quality,
                    width = width,
                    height = height,
                    audioOnly = config.audioOnly,
                    startPositionMs = startPositionMs,
                    playWhenReady = playWhenReady,
                    speed = config.speed.coerceIn(0.25f, 3f),
                    pitch = config.pitchFactor.coerceIn(0.5f, 2f),
                    loop = loop,
                )
                trace.commandAcked()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                loadingVideoId = null
                fail(error, "mse load failed", needReset = true)
                return@send
            }
        }
        loadTimeoutJob?.cancel()
        loadTimeoutJob = mainScope.launch {
            delay(LOAD_TIMEOUT_MS)
            loadTimeoutJob = null
            val loadLog = log?.fork("load", mapOf("videoId" to source.videoId))
            val stalled = trace.describe(
                presentedFrames = if (hostLive()) videoSurface.presentedFrames() else null,
                runningNowMs = session?.runningSince?.elapsedNow()?.inWholeMilliseconds,
                queuedNow = session?.queued,
            )
            loadLog?.log(Logger.Level.WARN, "E60051 load stalled $stalled")
            tearDownSession("load timeout")
            val timeout = TechnicalException("E60051", "load timeout ${source.videoId}")
                .markTrail(loadLog)
            fail(timeout, "load timeout", needReset = true)
        }
    }

    override fun preload(source: PlayableItem.YtMedia?) {
        requireMainThread()
        source ?: return
        if (released || !_status.value.state.readyToLoad) return
        send { it.preload(source.videoId, log) }
    }

    override fun play() {
        requireMainThread()
        if (released || !_status.value.state.readyToLoad) return
        send { it.play(log) }
    }

    override fun pause() {
        requireMainThread()
        if (released || !_status.value.state.readyToLoad) return
        send { it.pause(log) }
    }

    override fun seekTo(positionMs: Long) {
        requireMainThread()
        if (released || _status.value.state is PlaybackState.Uninitialized) return
        send { it.seekTo(positionMs, log) }
    }

    override fun stop() {
        requireMainThread()
        if (released) return
        when (_status.value.state) {
            is PlaybackState.Uninitialized -> return
            is PlaybackState.Error -> {
                tearDownSession("stop in error")
                return
            }
            else -> Unit
        }
        loadingVideoId = null
        loadedVideoId = null
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        send { it.stop(log) }
        _status.update { it.copy(state = PlaybackState.Idle, details = null) }
        _progress.value = Progress()
    }

    override fun setConfig(config: PlaybackConfig) {
        requireMainThread()
        if (released) return
        this.config = config
        if (!_status.value.state.readyToLoad) return
        val activeSession = session ?: return
        val previous = activeSession.config
        if (previous == config) return
        val previousLoop = activeSession.loop
        val loop = config.loop && !activeSession.live
        activeSession.config = config
        activeSession.loop = loop
        applySurfaceAudio(previous, config)
        send { enginePlayer ->
            val previousQuality = previous?.preferVideoQuality?.first?.takeIf { it != VideoQualityState.AUDIO_ONLY }
                ?: VideoQualityState.AUTO
            val nextQuality = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                ?: VideoQualityState.AUTO
            if (previous == null || config.audioOnly != previous.audioOnly || nextQuality != previousQuality) {
                enginePlayer.setQuality(nextQuality, config.audioOnly, log)
            }
            if (loop != previousLoop) enginePlayer.setLoop(loop, log)
            if (previous == null || config.speed != previous.speed || config.pitchFactor != previous.pitchFactor) {
                enginePlayer.setSpeedPitch(config.speed.coerceIn(0.25f, 3f), config.pitchFactor.coerceIn(0.5f, 2f), log)
            }
            if (captionChanged(previous, config)) applyCaptionConfig(enginePlayer, config)
        }
    }

    override fun selectAudioTrack(id: String) {
        _audioTracks.update { it.copy(selectedId = id) }
        send { it.setAudioTrack(id, log) }
    }

    /** Android TextureView: pass the window when it appears, null on destroy. JVM bitmap path ignores this. */
    fun setVideoSurface(surface: MseVideoSurface?) {
        pendingVideoSurface = surface
        hasPendingVideoSurface = true
        session?.player?.setVideoSurface(surface)
    }

    /**
     * Display hole in CSS pixels; YouTube Auto ABR uses it × devicePixelRatio. No floor here: base.js
     * raises the viewport it sends to at least 640x360 real pixels itself.
     */
    fun setViewport(width: Int, height: Int) {
        requireMainThread()
        if (released) return
        val holeWidth = width.coerceAtLeast(1)
        val holeHeight = height.coerceAtLeast(1)
        if (holeWidth == viewportW && holeHeight == viewportH) return
        viewportW = holeWidth
        viewportH = holeHeight
        if (!_status.value.state.readyToLoad) return
        send { it.setViewport(holeWidth, holeHeight, log) }
    }

    override suspend fun requestPlayerResponse(videoId: String): String =
        readyPlayer().cachedPlayerResponse(videoId, log).toString()

    override suspend fun requestPoToken(videoId: String): String =
        readyPlayer().mintContentPoToken(videoId, log)

    override fun pcmLevels(out: FloatArray): Int =
        if (!hostLive() || out.isEmpty()) 0 else videoSurface.pcmLevels(out)

    override fun presentedFrames(): Int =
        if (!hostLive()) 0 else videoSurface.presentedFrames()

    override fun droppedFrames(): Int =
        if (!hostLive()) 0 else videoSurface.droppedFrames()

    override fun videoDecoder(): String? = if (!hostLive()) null else videoSurface.videoDecoder().ifEmpty { null }

    /** A closing session frees its native host off main, only after main has cleared its player. */
    private fun hostLive(): Boolean = !released && session?.player != null

    override fun release() {
        requireMainThread()
        if (released) return
        released = true
        bundleWatch.cancel()
        tearDownSession("release")
        val lastClose = closing
        mainScope.launch {
            lastClose?.join()
            mainScope.cancel()
        }
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private suspend fun download(remote: SignedBundleRef) {
        try {
            if (remote in rejectedPlayerJs.value || pendingPlayerJs.value?.remote == remote) return
            if (remote.version <= (playerJs.getCached()?.second ?: playerJs.getEmbed().second)) return
            pendingPlayerJs.value = playerJs.download(remote)
            log?.log(Logger.Level.INFO, "player.js v${remote.version} downloaded for the next session")
        } catch (error: CancellationException) {
            throw error
        } catch (error: BundleSignatureException) {
            rejectedPlayerJs.update { it + remote }
            log?.captureError(error, "player.js update rejected")
        } catch (error: Throwable) {
            log?.log(Logger.Level.WARN, "player.js update failed", error)
        }
    }

    /** Boot is a blocking eval on the engine thread; null (reported) when [source] will not boot. */
    private suspend fun bootOrNull(
        engineThread: CoroutineDispatcher,
        source: String,
        failure: String,
        onBuilt: (DynamicEnginePlayer) -> Unit,
    ): DynamicEnginePlayer? = try {
        withContext(engineThread + NonCancellable) { buildPlayer(engineThread, source).also(onBuilt) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log?.captureError(error, failure)
        null
    }

    /** [freshConfig] re-scrapes the homepage: a reset session's held params may be the ones the server rejected. */
    private fun startSession(reason: String, freshConfig: Boolean) {
        check(session == null) { "mse session already running" }
        log?.log(Logger.Level.INFO, "mse session start ($reason) freshConfig=$freshConfig")
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = true)) }
        val newSession = Session(SupervisorJob(mainScope.coroutineContext[Job]))
        session = newSession
        val previousClose = closing
        newSession.scope.launch {
            // NonCancellable so a reset-during-reset still chains behind the older close.
            withContext(NonCancellable) { previousClose?.join() }
            ensureActive()
            val engineThread = newEngineThread()
            // Set from inside the boot: cancelling the session mid-boot discards withContext's result,
            // and the runtime it built must still reach the finally.
            var builtPlayer: DynamicEnginePlayer? = null
            try {
                // A remote bundle that will not boot must not take playback down with it: the next
                // one down boots.
                var enginePlayer: DynamicEnginePlayer? = null
                val fresh = pendingPlayerJs.getAndUpdate { null }
                if (fresh != null) {
                    enginePlayer = bootOrNull(engineThread, fresh.source, "player.js v${fresh.version} failed to boot, discarded") {
                        builtPlayer = it
                    }
                    if (enginePlayer == null) rejectedPlayerJs.update { it + fresh.remote } else keep(fresh)
                }
                if (enginePlayer == null) {
                    enginePlayer = playerJs.getCached()?.let { (source, version) ->
                        bootOrNull(engineThread, source, "player.js v$version failed to boot, using the embedded one") {
                            builtPlayer = it
                        }
                    }
                }
                if (enginePlayer == null) {
                    enginePlayer = withContext(engineThread + NonCancellable) {
                        buildPlayer(engineThread, playerJs.getEmbed().first).also { builtPlayer = it }
                    }
                }
                newSession.player = enginePlayer
                if (hasPendingVideoSurface) enginePlayer.setVideoSurface(pendingVideoSurface)
                withTimeoutOrNull(INIT_TIMEOUT_MS) { enginePlayer.init(log, viewportW, viewportH, freshConfig) }
                    ?: throw TechnicalException("E60066", "mse player/init not done within $INIT_TIMEOUT_MS ms")
                listen(newSession, enginePlayer)
                _status.update {
                    if (it.state is PlaybackState.Uninitialized) it.copy(state = PlaybackState.Idle) else it
                }
                for (command in newSession.commands) {
                    newSession.queued--
                    newSession.runningSince = TimeSource.Monotonic.markNow()
                    try {
                        command(enginePlayer)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log?.log(Logger.Level.WARN, "mse command failed", error)
                    } finally {
                        newSession.runningSince = null
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (session === newSession) fail(error, "mse init failed", needReset = true)
            } finally {
                newSession.player = null
                withContext(Dispatchers.Default + NonCancellable) { closeSessionEngine(builtPlayer, engineThread) }
            }
        }
    }

    /**
     * A wedged engine thread never runs its close hop, so the wait is bounded and the successor
     * boots anyway. The thread closes strictly after its runtime, wedged or not: a closed executor
     * sends every coroutine still bound to it to Dispatchers.IO, into JS from several threads.
     */
    private suspend fun closeSessionEngine(enginePlayer: DynamicEnginePlayer?, engineThread: CloseableCoroutineDispatcher) {
        if (enginePlayer == null) {
            closeEngineThread(engineThread)
            return
        }
        val closeJob = cleanupScope.launch {
            try {
                enginePlayer.close()
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse close failed", error)
            } finally {
                closeEngineThread(engineThread)
            }
        }
        if (withTimeoutOrNull(ENGINE_CLOSE_TIMEOUT_MS) { closeJob.join() } == null) {
            log?.log(Logger.Level.WARN, "mse engine close timed out; abandoning it")
        }
    }

    /** Off main and not awaited: native `close` blocks until the worker terminates. */
    private fun closeEngineThread(engineThread: CloseableCoroutineDispatcher) {
        cleanupScope.launch(Dispatchers.IO) {
            try {
                engineThread.close()
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse engine thread close failed", error)
            }
        }
    }

    /** Legacy `resetInternal(loadBlank = true)`: kill the session, leave [status] as is. */
    private fun tearDownSession(reason: String) {
        session?.let {
            log?.log(
                Logger.Level.INFO,
                "mse session teardown ($reason) age=${it.startedAt.elapsedNow().inWholeMilliseconds}ms " +
                    "loaded=${loadedVideoId ?: "-"} loading=${loadingVideoId ?: "-"}",
            )
        }
        loadingVideoId = null
        loadedVideoId = null
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        loadTrace = null
        _audioSessionId.value = 0
        _captionLines.value = emptyList()
        val oldSession = session ?: return
        session = null
        oldSession.commands.close()
        oldSession.job.cancel()
        closing = oldSession.job
    }

    /** Caches a downloaded bundle that booted; failing to only costs the next launch a download. */
    private suspend fun keep(bundle: VerifiedBundle) {
        try {
            playerJs.store(bundle)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log?.log(Logger.Level.WARN, "player.js v${bundle.version} booted but could not be cached", error)
        }
    }

    private fun send(command: suspend (DynamicEnginePlayer) -> Unit) {
        playerJs.checkUpdate()
        val activeSession = session ?: return
        if (activeSession.commands.trySend(command).isSuccess) activeSession.queued++
    }

    private suspend fun readyPlayer(): DynamicEnginePlayer = withContext(Dispatchers.Main.immediate) {
        if (released) throw TechnicalException("E60034", "mse engine released")
        init()
        var state = awaitSettled()
        if (state is PlaybackState.Error && state.error.needReset) {
            reset("request after ${state.error.code}")
            state = awaitSettled()
        }
        val enginePlayer = session?.player
        if (!state.readyToLoad || enginePlayer == null) {
            throw TechnicalException(
                "E60034",
                "mse engine not ready: $state",
                cause = (state as? PlaybackState.Error)?.error?.let(::PlayerErrorException),
            )
        }
        enginePlayer
    }

    private suspend fun awaitSettled(): PlaybackState =
        _status.first { it.state.readyToLoad || it.state is PlaybackState.Error }.state

    /** Native host audio; the host is per session, so [previous] is that session's last push. */
    private fun applySurfaceAudio(previous: PlaybackConfig?, config: PlaybackConfig) {
        if (!hostLive()) return
        if (previous == null || config.volume != previous.volume) {
            videoSurface.setVolume(config.volume.coerceIn(0f, 1f))
        }
        if (previous == null ||
            config.eqBands != previous.eqBands ||
            config.bassBoostDb != previous.bassBoostDb ||
            config.virtualizer != previous.virtualizer ||
            config.preampDb != previous.preampDb
        ) {
            videoSurface.setAudioEffect(
                FloatArray(10) { index -> config.eqBands.getOrElse(index) { 0f } },
                config.bassBoostDb.coerceIn(-12f, 12f),
                config.virtualizer.coerceIn(0f, 2f),
                config.preampDb.coerceIn(-12f, 12f),
            )
        }
    }

    private fun captionChanged(previous: PlaybackConfig?, config: PlaybackConfig): Boolean =
        previous == null ||
            config.captionVisible != previous.captionVisible ||
            config.preferCaptionLanguage != previous.preferCaptionLanguage

    private suspend fun applyCaptionConfig(enginePlayer: DynamicEnginePlayer, config: PlaybackConfig) {
        val preferred = config.preferCaptionLanguage.first.takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE }
        enginePlayer.setCaption(preferred, log, visible = config.captionVisible)
        syncCaptionSession(enginePlayer, config.captionVisible)
    }

    private fun syncCaptionSession(enginePlayer: DynamicEnginePlayer, visible: Boolean) {
        val activeSession = session ?: return
        if (!visible) {
            activeSession.captionJob?.cancel()
            activeSession.captionJob = null
            _captionLines.value = emptyList()
            return
        }
        if (activeSession.captionJob?.isActive == true) return
        activeSession.captionJob = activeSession.scope.launch {
            try {
                enginePlayer.collectCaptionLines({ rows ->
                    _captionLines.value = rows.map { CaptionLine(it.id, it.text) }
                }, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse caption stream failed", error)
                _captionLines.value = emptyList()
            }
        }
    }

    private fun listen(activeSession: Session, enginePlayer: DynamicEnginePlayer) {
        activeSession.scope.launch {
            try {
                enginePlayer.collectStatus({
                    if (session === activeSession) loadTrace?.engineEvent()
                    applyListen(it)
                }, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (session === activeSession) fail(error, "mse status stream failed", needReset = true)
            }
        }
        activeSession.scope.launch {
            try {
                enginePlayer.collectCatalog({ applyCatalog(it) }, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse catalog stream failed", error)
            }
        }
    }

    private fun applyListen(json: JsonObject) {
        if (json.boolOrNull("poRejected") == true) {
            // googlevideo rejected this session's PO token: only a fresh session re-attests.
            tearDownSession("po token rejected")
            fail(TechnicalException(PlayerError.PO_TOKEN_REJECTED, "googlevideo rejected the PO token (StreamProtectionStatus=3)"), "po token rejected", needReset = true)
            return
        }
        _audioSessionId.value = if (hostLive()) videoSurface.audioSessionId() else 0
        if (hostLive() && _status.value.state !is PlaybackState.Error) {
            val reason = videoSurface.videoFailure()
            if (reason != null) {
                // The session would keep playing and its next state would bury this error.
                tearDownSession("video decoder failed")
                fail(TechnicalException(PlayerError.VIDEO_DECODER_FAILED, "video decoder failed: $reason"), "video decoder failed", needReset = true)
                return
            }
        }
        json.objectOrNull("progress")?.let { applyProgress(it) }
        json.objectOrNull("state")?.let { applyState(it) }
        json.objectOrNull("picture")?.let { applyPicture(it) }
        json.objectOrNull("details")?.let { applyDetails(it) }
        json.objectOrNull("watchNext")?.let { applyWatchNext(it) }
    }

    private fun applyWatchNext(json: JsonObject) {
        val videoId = json.stringOrNull("videoId") ?: return
        val contents = json.stringOrNull("contents") ?: return
        _watchNextSeen.tryEmit(WatchNextSeen(videoId, contents))
    }

    private fun applyDetails(json: JsonObject) {
        val videoId = json.stringOrNull("videoId") ?: return
        if (videoId != (loadingVideoId ?: loadedVideoId)) return
        if (videoId == loadingVideoId) {
            loadTrace?.details(hasPlayerResponse = json.stringOrNull("channelTitle") != null)
            loadTimeoutJob?.cancel()
            loadTimeoutJob = null
        }
        val details = MediaDetails(videoId, json.stringOrNull("channelTitle"), json.stringOrNull("channelUrl"))
        _status.update { it.copy(details = details) }
    }

    private fun applyProgress(json: JsonObject) {
        val videoId = json.stringOrNull("videoId")
        val loading = loadingVideoId
        if (loading != null) {
            if (videoId != loading) {
                loadTrace?.progressFor(videoId)
                return
            }
            loadingVideoId = null
            loadedVideoId = videoId
            loadTimeoutJob?.cancel()
            loadTimeoutJob = null
            loadTrace = null
        } else if (videoId != loadedVideoId) {
            return
        }
        val durationMs = json.longOrNull("duration") ?: json.longOrNull("durationMs") ?: 0L
        val seekableStartMs = json.longOrNull("seekableStartMs") ?: 0L
        val seekableEndMs = json.longOrNull("seekableEndMs") ?: 0L
        /* Engine already maps the DVR window onto progress/duration (0..positionMs). */
        val positionMs = json.longOrNull("progress") ?: json.longOrNull("positionMs") ?: 0L
        _progress.value = Progress(
            positionMs = positionMs,
            durationMs = if (durationMs > 0L) durationMs else Progress.DURATION_UNKNOWN,
            bufferedPositionMs = json.longOrNull("bufferedMs") ?: 0L,
            seekable = json.boolOrNull("seekable") ?: (durationMs > 0L),
            isLive = json.boolOrNull("live") ?: _status.value.live,
            seekableStartMs = seekableStartMs,
            seekableEndMs = if (seekableEndMs > seekableStartMs) seekableEndMs else if (durationMs > 0L) durationMs else 0L,
        )
    }

    private fun applyState(json: JsonObject) {
        val newState = json.stringOrNull("newState") ?: return
        if (loadingVideoId != null) loadTrace?.state(newState)
        val playWhenReady = json.boolOrNull("playWhenReady") ?: false
        val live = json.boolOrNull("live") ?: false
        val state = when (newState) {
            "idle" -> PlaybackState.Idle
            "buffering" -> PlaybackState.Prepared(playWhenReady, buffering = true, ended = false)
            "ready" -> PlaybackState.Prepared(playWhenReady, buffering = false, ended = false)
            "ended" -> PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true)
            "error" -> PlaybackState.Error(
                PlayerError(
                    json.stringOrNull("code") ?: "E60033",
                    json.stringOrNull("message"),
                    userMessage = json.stringOrNull("userMessage")?.takeIf { it.isNotBlank() },
                    canRetry = json.boolOrNull("canRetry") ?: true,
                    needReset = json.boolOrNull("needReset") ?: false,
                ),
            )
            else -> return
        }
        if (state is PlaybackState.Error) {
            loadTimeoutJob?.cancel()
            loadTimeoutJob = null
        }
        _status.update { it.copy(state = state, live = live) }
    }

    private fun applyPicture(json: JsonObject) {
        val videoWidth = json.intOrNull("videoWidth") ?: 0
        val videoHeight = json.intOrNull("videoHeight") ?: 0
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (loadingVideoId != null) loadTrace?.picture()
        _status.update { it.copy(videoSize = VideoSize(videoWidth, videoHeight)) }
    }

    private fun applyCatalog(json: JsonObject) {
        val audioOnly = json.boolOrNull("audioOnly") ?: false
        val actual = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            json.stringOrNull("quality") ?: VideoQualityState.AUTO
        }
        val prefer = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            json.stringOrNull("preferQuality") ?: config.preferVideoQuality.first
        }
        val engineOptions = json.qualityOptions().filter {
            it.quality != VideoQualityState.AUDIO_ONLY && it.quality != "tiny"
        }
        val options = listOf(VideoQualityOption(VideoQualityState.AUDIO_ONLY, "Audio-only")) +
            engineOptions.ifEmpty { listOf(VideoQualityOption(VideoQualityState.AUTO, "Auto")) }
        _videoQuality.value = VideoQualityState(
            options = options,
            actualQuality = actual,
            preferredQuality = prefer,
        )
        _captions.value = CaptionState(tracks = json.captionTracks(), selectedLanguage = json.stringOrNull("caption"))
        _audioTracks.value = AudioTrackState(tracks = json.audioTracks(), selectedId = json.stringOrNull("audioTrack"))
    }

    private fun fail(error: Throwable, message: String, needReset: Boolean) {
        val code = error.errorCode()
            ?: Regex("""E\d{5}""").find(error.message.orEmpty())?.value
            ?: "E60033"
        log?.log(Logger.Level.WARN, "$code $message", error)
        // The state change, not a throw, carries this failure out — its capture site must still see the mse trail.
        error.markTrail(log)
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        _status.update {
            it.copy(
                state = PlaybackState.Error(
                    PlayerError(code, message, canRetry = true, needReset = needReset, cause = error),
                ),
            )
        }
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val LOAD_TIMEOUT_MS = 5_000L
        private const val ENGINE_CLOSE_TIMEOUT_MS = 3_000L

        fun create(
            client: KmpHttpClient,
            appVersion: Int,
            logger: Logger? = null,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            remoteFeature: (suspend (String) -> JsonElement?)? = null,
            videoSurface: MseVideoSurface,
            main: DynamicEngineManager,
            mediaProxy: LocalMediaProxy? = null,
            playerJs: JsSourceProvider = embeddedPlayerJsSource(),
            /** The running player.js revision, reported each time a player session's bundle starts. */
            onPlayerJsVersion: ((Int) -> Unit)? = null,
        ): MsePlayer {
            val engineClient = SabrProxyHttpClient(client, mediaProxy)
            return MsePlayer({ QuickJsRuntime.newDispatcher() }, videoSurface, logger, playerJs) { engineThread, source ->
                DynamicEnginePlayer.create(
                    engineClient, appVersion, engineThread, logger,
                    engineJs = source,
                    cacheStore = cacheStore,
                    cookieJar = cookieJar,
                    settings = settings,
                    remoteFeature = remoteFeature,
                    videoSurface = videoSurface,
                    main = main,
                    onBackendVersion = onPlayerJsVersion,
                )
            }
        }
    }
}

private fun JsonObject.captionTracks(): List<CaptionTrack> {
    return objects("captionTracks").mapNotNull { row ->
        val vssId = row.stringOrNull("vssId") ?: return@mapNotNull null
        CaptionTrack(
            languageCode = row.stringOrNull("languageCode").orEmpty(),
            displayName = row.stringOrNull("displayName") ?: vssId,
            vssId = vssId,
            kind = row.stringOrNull("kind").orEmpty(),
        )
    }
}

private fun JsonObject.audioTracks(): List<AudioTrack> {
    return objects("audioTracks").mapNotNull { row ->
        val id = row.stringOrNull("id") ?: return@mapNotNull null
        AudioTrack(id, row.stringOrNull("displayName") ?: id)
    }
}

private fun JsonObject.qualityOptions(): List<VideoQualityOption> {
    return objects("qualities").mapNotNull { option ->
        val quality = option.stringOrNull("quality") ?: return@mapNotNull null
        VideoQualityOption(quality, option.stringOrNull("label") ?: quality)
    }
}
