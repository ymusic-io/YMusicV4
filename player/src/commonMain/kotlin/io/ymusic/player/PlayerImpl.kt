package io.ymusic.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class PlayerImpl(
    private val engines: PlayerEngines,
    mainDispatcher: CoroutineDispatcher,
    private val random: Random,
) : Player {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val controller = QueueController(random)

    private var localEngine: LocalPlayer? = null
    private var ytEngine: YtEngine? = null

    private var playWhenReadyIntent = false
    private var released = false

    /** Monotonic stamp for the `Pair<String, Long>` preference fields — freshest choice wins a restore. */
    private var configSeq = 0L
    private fun nextSeq(): Long = ++configSeq

    /** Bumped on every load; the Ended auto-advance fires at most once per generation. */
    private var loadGeneration = 0
    private var advancedGeneration = -1

    /** Next queue index ([QueueController.peekNext]); [prepareNextArmed] once preload (or skip) ran. */
    private var prepareNextIndex: Int? = null
    private var prepareNextArmed = false
    private var queueOrigin = QueueOrigin.Unknown
    private var lastChange = QueueChange.Load

    /** [prepareNextIndex] came from a freshly loaded page — a user skip honors it too. */
    private var nextFromPage = false
    private var rolledGeneration = -1
    private var source: QueueSource? = null
    private var more: QueueMore? = null
    private var sourceJob: Job? = null

    private val engineJobs = mutableListOf<Job>()
    private var sleepJob: Job? = null
    private var prepareJob: Job? = null

    /** The bound engine missed the prepare deadline: its not-yet-loaded states (Uninitialized,
     *  Idle) must not replace the published E60052 until it plays, fails, or the user moves on. */
    private var holdPrepareTimeout = false
    /** Cookie identity changed; next YouTube load [MediaEngine.reset]s first. */
    private var ytResetOnNext = false

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _queue = MutableStateFlow(QueueState())
    override val queue: StateFlow<QueueState> = _queue.asStateFlow()

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

    private val _skipSegments = MutableStateFlow<List<SkipSegment>>(emptyList())
    override val skipSegments: StateFlow<List<SkipSegment>> = _skipSegments.asStateFlow()
    private var skipSegmentData: SkipSegmentData? = null

    private val _config = MutableStateFlow(PlaybackConfig())
    override val config: StateFlow<PlaybackConfig> = _config.asStateFlow()
    private var currentConfig: PlaybackConfig
        get() = _config.value
        set(value) { _config.value = value }

    private var ducked = false

    /** What the engines apply: [currentConfig] with the audio effects flattened while they are off
     *  and the volume lowered while [ducked]. */
    private val engineConfig: PlaybackConfig
        get() = currentConfig.let {
            if (it.inAppAudioFx) {
                it
            } else {
                it.copy(eqBands = List(10) { 0f }, bassBoostDb = 0f, virtualizer = 1f, preampDb = 0f)
            }
        }.let { if (ducked) it.copy(volume = it.volume * DUCK_VOLUME) else it }

    /** The engine bound to the current item — internal routing state; not exposed (the UI hands one
     *  video container to the engines and each owns its own surface, so it never needs this). */
    private val _currentEngine = MutableStateFlow<MediaEngine<*>?>(null)

    init {
        scope.launch {
            delay(3_000)
            prepareYoutubeEngine()
        }
        scope.launch { skipSponsorLoop() }
    }

    private val _sleepTimerMs = MutableStateFlow<Long?>(null)
    override val sleepTimerMs: StateFlow<Long?> = _sleepTimerMs.asStateFlow()
    private val _sleepAfterCurrent = MutableStateFlow(false)
    override val sleepAfterCurrent: StateFlow<Boolean> = _sleepAfterCurrent.asStateFlow()

    @OptIn(ExperimentalUuidApi::class)
    override fun setQueue(
        items: List<PlayableItem>,
        playIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
        queueId: String?,
        source: QueueSource?,
        origin: QueueOrigin,
    ) {
        if (released) return
        queueOrigin = origin
        lastChange = QueueChange.Load
        attachSource(source.takeIf { items.isNotEmpty() })
        controller.setQueue(items, playIndex, queueId ?: Uuid.random().toString())
        setPlayWhenReadyIntent(playWhenReady)
        if (items.isEmpty()) {
            publishQueue()
            stopActive()
        } else {
            publishQueue(refreshNext = false)
            loadCurrent(startPositionMs)
        }
    }

    override fun add(items: List<PlayableItem>, at: Int?) {
        if (released) return
        controller.add(items, at)
        publishQueue()
    }

    override fun addToPlayNext(items: List<PlayableItem>) {
        if (released) return
        controller.addNext(items)
        publishQueue()
    }

    override fun moveToPlayNext(index: Int) {
        if (released) return
        controller.moveNext(index)
        publishQueue()
    }

    override fun move(from: Int, to: Int) {
        if (released) return
        controller.move(from, to)
        publishQueue()
    }

    override fun move(fromIndex: Int, toIndex: Int, newIndex: Int) {
        if (released) return
        controller.moveRange(fromIndex, toIndex, newIndex)
        publishQueue()
    }

    override fun removeAt(index: Int) {
        if (released) return
        val removedCurrent = controller.removeAt(index)
        if (removedCurrent) {
            lastChange = QueueChange.User
            publishQueue(refreshNext = false)
            if (controller.state.currentIndex >= 0) loadCurrent(0L) else {
                refreshPrepareNext()
                stopActive()
            }
        } else {
            publishQueue()
        }
    }

    override fun remove(fromIndex: Int, toIndex: Int) {
        if (released) return
        val removedCurrent = controller.removeRange(fromIndex, toIndex)
        if (removedCurrent) {
            lastChange = QueueChange.User
            publishQueue(refreshNext = false)
            if (controller.state.currentIndex >= 0) loadCurrent(0L) else {
                refreshPrepareNext()
                stopActive()
            }
        } else {
            publishQueue()
        }
    }

    override fun clearQueue() {
        if (released) return
        attachSource(null)
        controller.clear()
        publishQueue()
        stopActive()
    }

    override fun playAt(index: Int) {
        if (released) return
        if (controller.moveTo(index)) {
            lastChange = QueueChange.User
            publishQueue(refreshNext = false)
            setPlayWhenReadyIntent(true)
            loadCurrent(0L)
        }
    }

    override fun play() {
        if (released) return
        setPlayWhenReadyIntent(true)
        // Legacy UniPlayer.play(): Prepared resumes in-place; every other state goes through the
        // load path, which initializes/resets the selected engine when required.
        val s = _status.value
        when (val state = s.state) {
            is PlaybackState.Prepared -> _currentEngine.value?.play()
            is PlaybackState.Error -> if (state.error.canRetry && controller.state.currentItem != null) {
                loadCurrent(_progress.value.positionMs)
            }
            PlaybackState.Idle, is PlaybackState.Uninitialized -> if (controller.state.currentItem != null) {
                loadCurrent(_progress.value.positionMs)
            }
        }
    }

    override fun pause() {
        if (released) return
        setPlayWhenReadyIntent(false)
        _currentEngine.value?.pause()
    }

    override fun togglePlay() {
        val status = status.value
        when {
            status.state.isEnded() -> {
                seekTo(0L)
                play()
            }
            status.playWhenReady -> pause()
            else -> play()
        }
    }

    override fun stop() {
        if (released) return
        setPlayWhenReadyIntent(false)
        stopActive()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val engine = _currentEngine.value ?: return
        val ms = positionMs.coerceAtLeast(0L)
        engine.seekTo(ms)
    }

    override fun setSkipSegments(item: PlayableItem, segments: List<SkipSegment>) {
        if (released) return
        val usable = segments.filter { it.lengthMs >= MIN_SKIP_LENGTH_MS }
        skipSegmentData = SkipSegmentData(item, usable.associateBy { it.startMs / 1_000L })
        _skipSegments.value = usable
    }

    override fun clearSkipSegments() {
        skipSegmentData = null
        _skipSegments.value = emptyList()
    }

    override fun skipNext() {
        if (released) return
        val pagePick = prepareNextIndex.takeIf { nextFromPage }
        val next = if (pagePick != null) pagePick.also { controller.commitNext(it) } else controller.advance(userInitiated = true)
        if (next != null) {
            lastChange = QueueChange.User
            publishQueue(refreshNext = false)
            loadCurrent(0L)
        }
    }

    override fun skipPrev() {
        if (released) return
        val introEnd = skipRestartMs()
        if (progress.value.positionMs > SMART_PREV_THRESHOLD_MS + introEnd) {
            seekTo(introEnd)
            return
        }
        val prev = controller.previous()
        if (prev != null) {
            lastChange = QueueChange.User
            publishQueue(refreshNext = false)
            loadCurrent(0L)
        } else {
            seekTo(0L)
        }
    }

    override fun fastForward(ms: Long) {
        seekTo(progress.value.positionMs + ms)
    }

    override fun rewind(ms: Long) {
        seekTo((progress.value.positionMs - ms).coerceAtLeast(0L))
    }

    override fun setLoopMode(mode: LoopMode) {
        controller.setLoopMode(mode)
        // UniPlayer: loop-one is engine-native (YouTube setLoopVideo / Exo REPEAT_MODE_ONE),
        // not a facade seek-to-0 on ended.
        syncEngineLoop()
        publishQueue()
    }

    override fun setShuffle(enabled: Boolean) {
        controller.setShuffle(enabled)
        publishQueue()
    }

    override var volume: Float
        get() = currentConfig.volume
        set(value) = pushConfig(currentConfig.copy(volume = value.coerceIn(0f, 1f)))

    override fun setDucked(ducked: Boolean) {
        if (this.ducked == ducked) return
        this.ducked = ducked
        _currentEngine.value?.setConfig(engineConfig)
    }

    override var speed: Float
        get() = currentConfig.speed
        set(value) = pushConfig(currentConfig.copy(speed = value.coerceIn(0.25f, 4f)))

    override fun setPitch(pitch: Float) = pushConfig(currentConfig.copy(pitchFactor = pitch.coerceIn(0.5f, 2f)))

    override fun setEqBands(bands: List<Float>) {
        val ten = (bands + List(10) { 0f }).take(10).map { it.coerceIn(-12f, 12f) }
        pushConfig(currentConfig.copy(eqBands = ten))
    }

    override fun setBassBoost(db: Float) = pushConfig(currentConfig.copy(bassBoostDb = db.coerceIn(-12f, 12f)))

    override fun setVirtualizer(width: Float) =
        pushConfig(currentConfig.copy(virtualizer = width.coerceIn(0.5f, 2f)))

    override fun setPreamp(db: Float) = pushConfig(currentConfig.copy(preampDb = db.coerceIn(-12f, 12f)))

    override fun setInAppAudioFx(enabled: Boolean) = pushConfig(currentConfig.copy(inAppAudioFx = enabled))

    override fun setAudioOnly(audioOnly: Boolean) {
        pushConfig(currentConfig.copy(audioOnly = audioOnly))
    }

    override fun selectVideoQuality(quality: String) {
        if (quality == VideoQualityState.AUDIO_ONLY) {
            setAudioOnly(true)
            return
        }
        pushConfig(
            currentConfig.copy(
                preferVideoQuality = quality to nextSeq(),
                audioOnly = false,
            ),
        )
    }

    override fun selectCaption(languageCode: String?) =
        pushConfig(currentConfig.copy(preferCaptionLanguage = (languageCode ?: PlaybackConfig.UNDETERMINED_LANGUAGE) to nextSeq()))

    override fun setCaptionVisible(visible: Boolean) = pushConfig(currentConfig.copy(captionVisible = visible))

    override fun setScaleMode(mode: ScaleMode) = pushConfig(currentConfig.copy(scaleMode = mode))

    /** Update the held config and push it to the active engine (the one application path). */
    private fun pushConfig(next: PlaybackConfig) {
        if (next == currentConfig) return
        currentConfig = next
        _currentEngine.value?.setConfig(engineConfig)
    }

    override fun selectAudioTrack(id: String) {
        (_currentEngine.value as? AudioTrackSelectable)?.selectAudioTrack(id)
    }

    override fun setSleepTimer(durationMs: Long?) {
        if (released) return
        sleepJob?.cancel()
        sleepJob = null
        if (_sleepAfterCurrent.value) {
            _sleepAfterCurrent.value = false
            syncEngineLoop()
        }
        _sleepTimerMs.value = durationMs
        if (durationMs == null) return
        sleepJob = scope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                val step = minOf(1000L, remaining)
                delay(step)
                remaining -= step
                _sleepTimerMs.value = remaining
            }
            _sleepTimerMs.value = null
            pause()
        }
    }

    override fun setSleepAfterCurrent(enabled: Boolean) {
        if (released) return
        if (enabled) setSleepTimer(null)
        _sleepAfterCurrent.value = enabled
        syncEngineLoop()
    }

    /** Engine-native loop-one never reports Ended, so it is lifted while end-of-track sleep is armed. */
    private fun syncEngineLoop() {
        pushConfig(currentConfig.copy(loop = controller.state.loopMode == LoopMode.One && !_sleepAfterCurrent.value))
    }

    override fun refreshOnNext() {
        ytResetOnNext = true
    }

    override fun reload() {
        if (released) return
        if (controller.state.currentItem == null) return
        val resumeMs = _progress.value.positionMs
        _currentEngine.value?.reset("reload")
        loadCurrent(resumeMs)
    }

    override fun prepareYoutubeEngine() {
        if (released || engines.unavailable != null) return
        val factory = engines.youtube ?: return
        val engine = ytEngine ?: factory().also { ytEngine = it }
        engine.init()
        engine.setConfig(engineConfig)
    }

    override fun pcmLevels(out: FloatArray): Int =
        if (released || out.isEmpty()) 0 else _currentEngine.value?.pcmLevels(out) ?: 0

    override fun presentedFrames(): Int =
        if (released) 0 else _currentEngine.value?.presentedFrames() ?: 0

    override fun droppedFrames(): Int =
        if (released) 0 else _currentEngine.value?.droppedFrames() ?: 0

    override fun videoDecoder(): String? = if (released) null else _currentEngine.value?.videoDecoder()

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        localEngine?.release()
        ytEngine?.release()
        localEngine = null
        ytEngine = null
        _currentEngine.value = null
        _audioSessionId.value = 0
    }

    private fun publishQueue(refreshNext: Boolean = true) {
        _queue.value = controller.state.copy(more = more, origin = queueOrigin, lastChange = lastChange)
        if (refreshNext) refreshPrepareNext()
        maybeLoadAhead()
    }

    private fun refreshPrepareNext() {
        prepareNextIndex = controller.peekNext(userInitiated = false)
        prepareNextArmed = false
        nextFromPage = false
        if (rolledGeneration != loadGeneration) {
            rolledGeneration = loadGeneration
            // A failed page is retried once per track, not in a loop.
            more = more?.copy(error = null)
            if (shufflePickLandsUnloaded()) startLoad(pickNext = true)
        }
        maybePreloadNext()
    }

    private fun attachSource(newSource: QueueSource?) {
        sourceJob?.cancel()
        sourceJob = null
        source = newSource
        more = newSource?.let { QueueMore(totalCount = it.totalCount, resumeToken = it.resumeToken) }
    }

    override fun loadMore() {
        if (released) return
        more = more?.copy(error = null)
        startLoad(pickNext = false)
    }

    /** Known-length shuffle: the pick lands on the unloaded part with probability unloaded / (unplayed +
     *  unloaded). It then plays from the ONE next page — later pages are under-weighted; accepted. */
    private fun shufflePickLandsUnloaded(): Boolean {
        val state = more ?: return false
        val total = state.totalCount ?: return false
        if (!controller.state.shuffle || controller.hasUpNext || !state.hasMore || state.loading || state.error != null) return false
        val unloaded = total - controller.state.items.size
        if (unloaded <= 0) return false
        return random.nextInt(controller.unplayedCount() + unloaded) < unloaded
    }

    /** Any mode: the current item sits within the queue's last [LOAD_AHEAD] items. */
    private fun maybeLoadAhead() {
        val state = more ?: return
        if (!state.hasMore || state.loading || state.error != null) return
        val queue = controller.state
        if (queue.currentIndex < 0) return
        if (queue.items.lastIndex - queue.currentIndex < LOAD_AHEAD) startLoad(pickNext = false)
    }

    private fun startLoad(pickNext: Boolean) {
        val src = source ?: return
        val state = more ?: return
        if (!state.hasMore || state.loading) return
        more = state.copy(loading = true, error = null)
        publishQueue(refreshNext = false)
        sourceJob = scope.launch {
            val page = try {
                src.loadMore()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (source === src) {
                    more = more?.copy(loading = false, error = e)
                    publishQueue(refreshNext = false)
                }
                return@launch
            }
            if (released || source !== src) return@launch
            val firstNew = controller.state.items.size
            controller.add(page.items)
            more = more?.let { current ->
                current.copy(
                    loading = false,
                    hasMore = page.hasMore,
                    totalCount = if (page.hasMore) src.totalCount ?: current.totalCount else controller.state.items.size,
                    resumeToken = src.resumeToken.takeIf { page.hasMore },
                )
            }
            if (pickNext && page.items.isNotEmpty() && controller.state.shuffle) {
                prepareNextIndex = firstNew + random.nextInt(page.items.size)
                prepareNextArmed = false
                nextFromPage = true
                publishQueue(refreshNext = false)
                maybePreloadNext()
            } else {
                publishQueue()
            }
        }
    }

    private fun engineFor(item: PlayableItem, create: Boolean): MediaEngine<*>? = when (item) {
        is PlayableItem.LocalMedia ->
            localEngine ?: if (create) engines.local().also { localEngine = it } else null
        is PlayableItem.YtMedia ->
            ytEngine ?: if (create) engines.youtube?.invoke()?.also { ytEngine = it } else null
    }

    private fun preloadOn(engine: MediaEngine<*>, item: PlayableItem?) {
        when (engine) {
            is LocalPlayer -> engine.preload(item as? PlayableItem.LocalMedia)
            is YtEngine -> engine.preload(item as? PlayableItem.YtMedia)
        }
    }

    /** UniPlayer: last 10s, playing, not buffering, not live, not loop-one — cue the next engine. */
    private fun maybePreloadNext() {
        if (released || prepareNextArmed) return
        val nextIndex = prepareNextIndex ?: return
        val current = controller.state.currentItem
        if ((current as? PlayableItem.YtMedia)?.live == true) {
            prepareNextArmed = true
            return
        }
        if (controller.state.loopMode == LoopMode.One) {
            prepareNextArmed = true
            return
        }
        val engine = _currentEngine.value ?: return
        val st = engine.status.value.state as? PlaybackState.Prepared ?: return
        if (!st.playWhenReady || st.buffering || st.ended) return
        val p = engine.progress.value
        val dur = p.durationMs
        if (dur <= 0L) return
        if (p.positionMs > dur || contentEndMs(dur) - p.positionMs > PRELOAD_LEAD_MS) return
        val nextItem = controller.state.items.getOrNull(nextIndex) ?: return
        val nextEngine = engineFor(nextItem, create = true) ?: run {
            prepareNextArmed = true
            return
        }
        if (nextEngine.status.value.state.readyToLoad) {
            preloadOn(nextEngine, nextItem)
            val currentEngine = _currentEngine.value
            if (currentEngine != null && currentEngine !== nextEngine) {
                preloadOn(currentEngine, null)
            }
        } else {
            nextEngine.init()
        }
        prepareNextArmed = true
    }

    private fun loadCurrent(startPositionMs: Long) {
        val item = controller.state.currentItem ?: return
        loadGeneration++
        val gen = loadGeneration
        engines.unavailable?.let { error ->
            bind(null)
            publishStatus(PlaybackStatus(state = PlaybackState.Error(error)))
            return
        }
        when (item) {
            is PlayableItem.LocalMedia -> {
                val engine = localEngine ?: engines.local().also { localEngine = it }
                resetUnshownError(engine)
                bind(engine)
                requestLoad(engine, item, startPositionMs, gen)
            }
            is PlayableItem.YtMedia -> {
                val factory = engines.youtube
                if (factory == null) {
                    bind(null)
                    publishStatus(
                        PlaybackStatus(
                            state = PlaybackState.Error(PlayerError(
                                "E60050", "queue item needs the YouTube engine but none is configured",
                                canRetry = false,
                            )),
                        ),
                    )
                    return
                }
                val engine = ytEngine ?: factory().also { ytEngine = it }
                resetUnshownError(engine)
                bind(engine)
                requestLoad(engine, item, startPositionMs, gen)
            }
        }
        refreshPrepareNext()
    }

    /** An unbound engine's error (a failed [prepareYoutubeEngine] pre-warm) was never shown; binding
     *  it as-is would publish a stale failure the load is about to recover from. */
    private fun resetUnshownError(engine: MediaEngine<*>) {
        if (engine === _currentEngine.value) return
        val error = (engine.status.value.state as? PlaybackState.Error)?.error ?: return
        if (!error.canRetry || !error.needReset) return
        if (engine is YtEngine) ytResetOnNext = false
        engine.reset("unshown error ${error.code}")
    }

    private fun requestLoad(
        engine: MediaEngine<*>,
        item: PlayableItem,
        startPositionMs: Long,
        gen: Int,
    ) {
        prepareJob?.cancel()
        holdPrepareTimeout = false
        val resetFirst = ytResetOnNext && engine is YtEngine
        if (engine.status.value.state.readyToLoad && !resetFirst) {
            loadOn(engine, item, startPositionMs)
            return
        }
        prepareJob = scope.launch {
            val error = tryMakePlayerReady(engine)
            if (released || loadGeneration != gen) return@launch
            if (error != null) {
                if (holdPrepareTimeout) recoverFromPrepareTimeout(engine, item, startPositionMs, gen)
                return@launch
            }
            loadOn(engine, item, startPositionMs)
        }
    }

    /**
     * A slow engine often turns ready just after the prepare deadline: load this same item then, once,
     * instead of leaving the user on E60052 with nothing loading. Runs inside [prepareJob], so a new
     * load / stop cancels it; a pause since the timeout leaves the item unloaded.
     */
    private suspend fun recoverFromPrepareTimeout(
        engine: MediaEngine<*>,
        item: PlayableItem,
        startPositionMs: Long,
        gen: Int,
    ) {
        val intent = playWhenReadyIntent
        val reached = withTimeoutOrNull(PREPARE_RECOVERY_MS) {
            engine.status.first { it.state.readyToLoad || it.state is PlaybackState.Error }.state
        }
        if (reached == null || reached is PlaybackState.Error || !reached.readyToLoad) return
        if (released || loadGeneration != gen || _currentEngine.value !== engine) return
        if (playWhenReadyIntent != intent) return
        loadOn(engine, item, startPositionMs)
    }

    private fun loadOn(engine: MediaEngine<*>, item: PlayableItem, startPositionMs: Long) {
        when (item) {
            is PlayableItem.LocalMedia ->
                (engine as LocalPlayer).load(item, startPositionMs, playWhenReadyIntent, engineConfig)
            is PlayableItem.YtMedia ->
                (engine as YtEngine).load(item, startPositionMs, playWhenReadyIntent, engineConfig)
        }
    }

    /**
     * UniPlayer `tryMakePlayerReady`: init a cold engine, reset a [PlayerError.needReset] session,
     * wait until [PlaybackState.readyToLoad] or a new Error.
     */
    private suspend fun tryMakePlayerReady(engine: MediaEngine<*>): PlayerError? {
        if (ytResetOnNext && engine is YtEngine) {
            ytResetOnNext = false
            engine.reset("refresh requested")
        }
        val first = engine.status.value.state
        if (first.readyToLoad) return null
        when (first) {
            is PlaybackState.Uninitialized -> {
                if (!first.isInitializing) engine.init()
            }
            is PlaybackState.Error -> engine.reset("error ${first.error.code} needs reset")
            else -> return null
        }
        val settled = suspend {
            engine.status.first { snap ->
                when (val s = snap.state) {
                    is PlaybackState.Error -> true
                    is PlaybackState.Uninitialized -> false
                    else -> s.readyToLoad
                }
            }.state
        }
        // The YouTube engine bounds its own init and always settles in ready or Error.
        val next = if (engine is YtEngine) settled() else withTimeoutOrNull(PREPARE_TIMEOUT_MS) { settled() }
        if (next == null) {
            val err = PlayerError("E60052", "engine prepare timeout", canRetry = true, needReset = true)
            publishStatus(PlaybackStatus(state = PlaybackState.Error(err)))
            holdPrepareTimeout = _currentEngine.value === engine
            return err
        }
        return (next as? PlaybackState.Error)?.error
    }

    /** Switch-latest: point the facade flows at [engine], stopping whatever played before. */
    private fun bind(engine: MediaEngine<*>?) {
        val previous = _currentEngine.value
        if (previous === engine) return
        engineJobs.forEach { it.cancel() }
        engineJobs.clear()
        holdPrepareTimeout = false
        previous?.stop()
        _currentEngine.value = engine
        if (engine == null) {
            _audioSessionId.value = 0
            return
        }
        engineJobs += scope.launch {
            // An engine stays Ended until the next load reaches it; only entering Ended ends a track.
            var ended = engine.status.value.state.isEnded()
            engine.status.collect { snapshot ->
                if (holdPrepareTimeout && snapshot.state.notYetLoaded()) {
                    maybePreloadNext()
                    return@collect
                }
                holdPrepareTimeout = false
                publishStatus(snapshot)
                val wasEnded = ended
                ended = snapshot.state.isEnded()
                if (!ended) maybePreloadNext() else if (!wasEnded) onTrackEnded()
            }
        }
        engineJobs += scope.launch {
            engine.progress.collect {
                _progress.value = it
                maybePreloadNext()
            }
        }
        bindCapability(engine as? VideoQualitySelectable, _videoQuality, VideoQualityState.DEFAULT) { it.videoQuality }
        bindCapability(engine as? CaptionSelectable, _captions, CaptionState.DEFAULT) { it.captions }
        bindCapability(engine as? CaptionRenderable, _captionLines, emptyList()) { it.captionLines }
        bindCapability(engine as? AudioTrackSelectable, _audioTracks, AudioTrackState.DEFAULT) { it.audioTracks }
        bindCapability(engine as? AudioSessionSource, _audioSessionId, 0) { it.audioSessionId }
        (engine as? YtEngine)?.let { yt ->
            engineJobs += scope.launch {
                yt.watchNextSeen.collect { _watchNextSeen.tryEmit(it) }
            }
        }
    }

    private inline fun <C : Any, S> bindCapability(
        capability: C?,
        into: MutableStateFlow<S>,
        default: S,
        crossinline flowOf: (C) -> StateFlow<S>,
    ) {
        if (capability == null) {
            into.value = default
        } else {
            engineJobs += scope.launch { flowOf(capability).collect { into.value = it } }
        }
    }

    private fun onTrackEnded() {
        if (advancedGeneration == loadGeneration) return
        if (_sleepAfterCurrent.value) {
            _sleepAfterCurrent.value = false
            syncEngineLoop()
            setPlayWhenReadyIntent(false)
            if (controller.state.loopMode == LoopMode.One) {
                advancedGeneration = loadGeneration
                loadCurrent(0L)
                return
            }
        }
        val current = controller.state.currentItem
        if (controller.state.loopMode == LoopMode.One &&
            (current as? PlayableItem.YtMedia)?.live != true
        ) {
            return
        }
        advancedGeneration = loadGeneration
        val next = prepareNextIndex
        if (next != null) {
            controller.commitNext(next)
            lastChange = QueueChange.Auto
            publishQueue(refreshNext = false)
            loadCurrent(0L)
        }
    }

    private fun stopActive() {
        prepareJob?.cancel()
        holdPrepareTimeout = false
        val engine = _currentEngine.value
        if (engine != null) {
            engine.stop()
        } else {
            publishStatus(PlaybackStatus())
            _progress.value = Progress()
        }
    }

    private fun setPlayWhenReadyIntent(value: Boolean) {
        playWhenReadyIntent = value
        _status.value = _status.value.copy(playWhenReady = value)
    }

    private fun publishStatus(fromEngine: PlaybackStatus) {
        _status.value = fromEngine.copy(playWhenReady = playWhenReadyIntent)
    }

    private fun skipRestartMs(): Long {
        val data = skipSegmentData ?: return 0L
        if (data.playableItem != controller.state.currentItem) return 0L
        val intro = data.skipSegments[0] ?: return 0L
        return intro.startMs + intro.lengthMs
    }

    private suspend fun skipSponsorLoop() {
        var skippedSeg: SkipSegment? = null
        var lastItem: PlayableItem? = null
        _progress.collect { snapshot ->
            val playbackPosition = snapshot.positionMs
            var nowPlaying = controller.state.currentItem
            if (skippedSeg != null && (lastItem != nowPlaying || skipSegmentData == null)) {
                skippedSeg = null
                lastItem = null
            }
            val data = skipSegmentData
            if (data == null || nowPlaying != data.playableItem) return@collect
            // A late fetch in the first 3s still matches an intro that starts in second 0.
            val nearest = data.skipSegments[playbackPosition / 1_000L]
                ?: data.skipSegments[playbackPosition / 3_000L]
            if (skippedSeg == nearest && nowPlaying == lastItem) {
                return@collect
            } else {
                skippedSeg = null
                lastItem = null
            }
            if (nearest == null) return@collect
            val endTime = nearest.startMs + nearest.lengthMs
            if (playbackPosition >= endTime) return@collect
            if (nearest.startMs > playbackPosition) {
                delay(nearest.startMs - playbackPosition)
            }
            nowPlaying = controller.state.currentItem
            if (!_status.value.state.isPlaying() || nowPlaying != skipSegmentData?.playableItem) {
                return@collect
            }
            when {
                finishesTrack(nearest, snapshot.durationMs) -> onTrackEnded()
                loopsTrack(nearest, snapshot.durationMs) -> seekTo(skipRestartMs())
                else -> seekTo(endTime)
            }
            skippedSeg = nearest
            lastItem = nowPlaying
        }
    }

    /** The duration pulled in to the start of a skip-segment chain that runs out the track. */
    private fun contentEndMs(durationMs: Long): Long {
        val data = skipSegmentData ?: return durationMs
        if (data.playableItem != controller.state.currentItem) return durationMs
        var end = durationMs
        for (segment in data.skipSegments.values.sortedByDescending { it.startMs }) {
            if (segment.startMs + segment.lengthMs < end - OUTRO_TOLERANCE_MS) break
            end = minOf(end, segment.startMs)
        }
        return end
    }

    /** An outro skip can advance straight to the queued next item instead of seeking to the tail. */
    private fun finishesTrack(segment: SkipSegment, durationMs: Long): Boolean {
        if (durationMs <= 0L || segment.startMs + segment.lengthMs < durationMs - OUTRO_TOLERANCE_MS) return false
        if (controller.state.loopMode == LoopMode.One) return false
        if ((controller.state.currentItem as? PlayableItem.YtMedia)?.live == true) return false
        return prepareNextIndex != null
    }

    /** Loop-one restarts at an outro: a seek onto the MSE tail stalls paused instead of ending, so it never loops. */
    private fun loopsTrack(segment: SkipSegment, durationMs: Long): Boolean {
        if (durationMs <= 0L || segment.startMs + segment.lengthMs < durationMs - OUTRO_TOLERANCE_MS) return false
        if ((controller.state.currentItem as? PlayableItem.YtMedia)?.live == true) return false
        return controller.state.loopMode == LoopMode.One && !_sleepAfterCurrent.value
    }

    private data class SkipSegmentData(
        val playableItem: PlayableItem,
        val skipSegments: Map<Long, SkipSegment>,
    )

    private fun PlaybackState.notYetLoaded(): Boolean =
        this is PlaybackState.Uninitialized || this == PlaybackState.Idle

    private companion object {
        const val DUCK_VOLUME = 0.3f

        /** Played longer than this, "previous" restarts the current track instead. */
        const val SMART_PREV_THRESHOLD_MS = 3_000L

        /** Keep this many items queued past the current one — legacy PlayerPlaylistMaintainer. */
        const val LOAD_AHEAD = 10

        /** Cue the next item this close to the end — legacy UniPlayer. */
        const val PRELOAD_LEAD_MS = 10_000L

        /** UniPlayer `tryMakePlayerReady` timeout, for the engines that do not bound their own init. */
        const val PREPARE_TIMEOUT_MS = 10_000L

        const val MIN_SKIP_LENGTH_MS = 1_000L

        /** A skip segment ending this close to the duration counts as running out the track. */
        const val OUTRO_TOLERANCE_MS = 1_000L

        /** How long after an E60052 a late-ready engine still gets the item loaded automatically. */
        const val PREPARE_RECOVERY_MS = 15_000L
    }
}
