package io.ymusic.player

import androidx.annotation.Keep
import io.ymusic.mse.FileAvNative
import io.ymusic.mse.FileAvSink
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmpcommon.uri.fileUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * JVM desktop local player, ExoPlayer-shaped:
 * - Public API is main/AWT only: enqueue and return (except [release], which waits).
 * - One `player` thread calls [FileAvNative.step] (present).
 * - Video decode is a FileAv helper thread; audio decode+AudioQueue is another, like MSE.
 *   The DAC `samples_played` clock is gold; video follows it.
 * - BGRA goes to the Swing hole here (same as [io.ymusic.mse.NativeMseSink]);
 *   progress and engine state hop to AWT via [SwingUtilities.invokeLater].
 */
internal class JvmLocalPlayer(
    private val videoSurface: LocalVideoSurface?,
) : LocalPlayer,
    CaptionRenderable,
    FileAvSink {
    private var handle: Long = 0
    private val cmds = LinkedBlockingQueue<() -> Unit>()

    @Volatile private var pumping = false

    private val _status = MutableStateFlow(PlaybackStatus(state = PlaybackState.Idle))
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
    override val videoQuality: StateFlow<VideoQualityState> = _videoQuality.asStateFlow()

    private val _captions = MutableStateFlow(CaptionState.DEFAULT)
    override val captions: StateFlow<CaptionState> = _captions.asStateFlow()

    private val _captionLines = MutableStateFlow<List<CaptionLine>>(emptyList())
    override val captionLines: StateFlow<List<CaptionLine>> = _captionLines.asStateFlow()

    private val nativeCues = ArrayList<LocalCue>()
    private var sidecarTracks: List<SidecarTrack> = emptyList()
    private var sidecarCues: List<LocalCue> = emptyList()
    private var usingSidecar = false
    private var dumpedCues: Map<Int, List<LocalCue>> = emptyMap()

    private val _audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioTracks: StateFlow<AudioTrackState> = _audioTracks.asStateFlow()

    @Volatile private var released = false

    @Volatile private var stopped = false

    @Volatile private var appliedConfig = PlaybackConfig()

    @Volatile private var durationMs: Long = Progress.DURATION_UNKNOWN

    @Volatile private var loadedPath: String? = null

    @Volatile private var audioOnly = true

    @Volatile private var preferVideoQuality = VideoQualityState.AUDIO_ONLY

    @Volatile private var volume: Float = 1f

    @Volatile private var loop = false

    private val frameBufs = arrayOfNulls<BufferedImage>(3)

    @Volatile private var lastPub: BufferedImage? = null

    @Volatile private var streams: List<AvStreamRow> = emptyList()

    private val pendingSeekMs = AtomicLong(-1)
    private val seekQueued = AtomicBoolean(false)
    private val loadGen = AtomicLong(0)
    private val uiEpoch = AtomicLong(0)
    private val progressPosted = AtomicBoolean(false)

    @Volatile private var pendingPosMs: Long = 0

    @Volatile private var pendingDurMs: Long = Progress.DURATION_UNKNOWN

    @Volatile private var pendingVw: Int = 0

    @Volatile private var pendingVh: Int = 0

    /** The current load wants a video decoder as soon as the Canvas HWND is attached. */
    @Volatile private var videoWanted: Boolean = false

    @Volatile private var viewW: Int = 0

    @Volatile private var viewH: Int = 0

    val surface: LocalVideoSurface? get() = videoSurface

    private val playerThread = Thread({ playerLoop() }, "player").apply {
        isDaemon = true
        start()
    }

    init {
        dispatch {
            handle = FileAvNative.create()
            if (handle == 0L) {
                onApp {
                    _status.update {
                        it.copy(state = PlaybackState.Error(PlayerError("E60020", "libav file player failed to start")))
                    }
                }
            } else {
                FileAvNative.setSink(handle, this)
                bindVideoComponent(handle, videoSurface?.nativePeer)
            }
        }
        videoSurface?.onViewSize = { w, h ->
            viewW = w
            viewH = h
            dispatch { applyBlit() }
        }
        videoSurface?.onNativePeer = { c ->
            dispatch {
                bindVideoComponent(handle, c)
            }
        }
    }

    private fun dispatch(block: () -> Unit) {
        if (released) return
        cmds.offer(block)
    }

    /** JAWT GetDrawingSurface must run on the AWT thread after [java.awt.Canvas.addNotify]. */
    private fun bindVideoComponent(h: Long, c: java.awt.Component?) {
        if (h == 0L) return
        fun bind() {
            if (released) return
            val ok = FileAvNative.setVideoComponent(h, c)
            videoSurface?.setNativePresent(ok)
            // Canvas just became available (or was swapped): if load() marked this as a video
            // playback, open the video decoder NOW — p->d3d11_hwnd was set by setVideoComponent
            // above, so start_video_locked's open_codec gates straight into D3D11VA.
            if (c != null && videoWanted) {
                dispatch {
                    if (!released && handle == h) FileAvNative.setVideoEnabled(h, true)
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            bind()
        } else {
            SwingUtilities.invokeLater { bind() }
        }
    }

    /** Called from the load path when the Canvas is already attached before open completes. */
    private fun enableVideoWhenAttached() {
        val peer = videoSurface?.nativePeer ?: return
        bindVideoComponent(handle, peer)
    }

    /** Listener/UI hop. Always posts so player-thread work never runs re-entrantly on AWT. */
    private fun onApp(block: () -> Unit) {
        SwingUtilities.invokeLater {
            if (!released) block()
        }
    }

    private fun playerLoop() {
        while (!stopped) {
            var cmd = cmds.poll()
            while (cmd != null) {
                cmd.invoke()
                cmd = cmds.poll()
            }
            if (stopped) break
            try {
                if (released) {
                    cmd = cmds.poll(50, TimeUnit.MILLISECONDS)
                    cmd?.invoke()
                } else if (pumping && handle != 0L) {
                    FileAvNative.step(handle)
                } else {
                    cmd = cmds.poll(50, TimeUnit.MILLISECONDS)
                    cmd?.invoke()
                }
            } catch (_: InterruptedException) {
                // Drain the shutdown command; do not exit before it runs.
            }
        }
    }

    override fun init() {}

    override fun reset() {
        if (released) return
        stop()
    }

    override fun load(
        source: PlayableItem.LocalMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        if (released) return
        appliedConfig = config
        applyConfigFlags(config)
        val path = parseLocalUri(source.uri).toFilePath()
        val gen = loadGen.incrementAndGet()
        uiEpoch.incrementAndGet()
        pendingSeekMs.set(-1)
        _status.update { it.copy(state = PlaybackState.Prepared(playWhenReady, buffering = true, ended = false)) }
        _progress.value = Progress(positionMs = startPositionMs.coerceAtLeast(0L))
        _videoQuality.value = VideoQualityState.DEFAULT.copy(preferredQuality = preferVideoQuality)
        showSurface(true)
        videoWanted = !audioOnly
        dispatch {
            if (handle == 0L || gen != loadGen.get()) return@dispatch
            FileAvNative.setVolume(handle, volume.toDouble())
            FileAvNative.setAudioIndex(handle, -1)
            // Open the file with video OFF so audio starts immediately. The video decoder stays
            // closed until the Canvas HWND is attached, so when it does open_codec sees
            // p->d3d11_hwnd and goes straight to D3D11VA — no SW→HW decoder reopen on the hot
            // path. For audio-only files this is just the normal setting. The attach is handled
            // after open below (and by videoSurface?.onNativePeer for Canvas swaps).
            FileAvNative.setVideoEnabled(handle, false)
            applyBlit()
            FileAvNative.setSpeed(handle, config.speed.coerceAtLeast(0.05f).toDouble())
            FileAvNative.setPitch(handle, config.pitchFactor.toDouble())
            FileAvNative.setEq(handle, FloatArray(10) { i -> config.eqBands.getOrElse(i) { 0f } })
            FileAvNative.setBass(handle, config.bassBoostDb)
            FileAvNative.setVirtualizer(handle, config.virtualizer)
            FileAvNative.setPreamp(handle, config.preampDb)
            if (FileAvNative.open(handle, path) != 0) {
                val err = FileAvNative.lastError(handle).ifBlank { "open failed" }
                fail(err)
                return@dispatch
            }
            if (gen != loadGen.get()) return@dispatch
            loadedPath = path
            streams = FileAvNative.streams(handle).mapNotNull(::parseAvStream)
            sidecarTracks = LocalSidecarCaptions.find(path)
            dumpedCues = dumpEmbeddedCaptions()
            synchronized(nativeCues) { nativeCues.clear() }
            applyNativeCaption(config)
            val dur = FileAvNative.duration(handle)
            durationMs = if (dur > 0) (dur * 1000.0).toLong() else Progress.DURATION_UNKNOWN
            val srcVideo = streams.firstOrNull { it.kind == KIND_VIDEO }
            FileAvNative.seek(handle, startPositionMs.coerceAtLeast(0L) / 1000.0)
            if (playWhenReady) FileAvNative.play(handle) else FileAvNative.pause(handle)
            pumping = playWhenReady
            // Refine videoWanted now that we know whether the file actually has a video stream.
            // bindVideoComponent reads this on every Canvas attach; if the Canvas was already
            // present when load started, trigger the attach+enable path once.
            videoWanted = !audioOnly && srcVideo != null
            if (videoWanted && videoSurface?.nativePeer != null) {
                enableVideoWhenAttached()
            }
            val size = if (srcVideo != null && !audioOnly && srcVideo.width > 0 && srcVideo.height > 0) {
                VideoSize(srcVideo.width, srcVideo.height)
            } else {
                null
            }
            onApp {
                if (gen != loadGen.get()) return@onApp
                publishTracks(config)
                _status.update {
                    val s = it.state as? PlaybackState.Prepared
                    it.copy(
                        state = s?.copy(buffering = false) ?: PlaybackState.Prepared(playWhenReady, false, false),
                        videoSize = size,
                    )
                }
            }
        }
    }

    override fun play() {
        if (released || loadedPath == null) return
        _status.update {
            val s = it.state
            it.copy(
                state = if (s is PlaybackState.Prepared) {
                    s.copy(playWhenReady = true, ended = false)
                } else {
                    PlaybackState.Prepared(true, buffering = false, ended = false)
                },
            )
        }
        dispatch {
            if (handle != 0L) FileAvNative.play(handle)
            pumping = true
        }
    }

    override fun pause() {
        if (released) return
        _status.update {
            val s = it.state as? PlaybackState.Prepared ?: return
            it.copy(state = s.copy(playWhenReady = false))
        }
        dispatch {
            pumping = false
            if (handle != 0L) FileAvNative.pause(handle)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released || loadedPath == null) return
        val ms = positionMs.coerceAtLeast(0L)
        pendingSeekMs.set(ms)
        uiEpoch.incrementAndGet()
        _progress.update { it.copy(positionMs = ms, durationMs = durationMs) }
        if (!seekQueued.compareAndSet(false, true)) return
        dispatch {
            seekQueued.set(false)
            if (handle == 0L) return@dispatch
            while (!released) {
                val s = pendingSeekMs.getAndSet(-1)
                if (s < 0) break
                synchronized(nativeCues) { nativeCues.clear() }
                FileAvNative.seek(handle, s / 1000.0)
            }
        }
    }

    override fun stop() {
        if (released) return
        loadGen.incrementAndGet()
        uiEpoch.incrementAndGet()
        pendingSeekMs.set(-1)
        videoWanted = false
        loadedPath = null
        streams = emptyList()
        sidecarTracks = emptyList()
        sidecarCues = emptyList()
        usingSidecar = false
        synchronized(nativeCues) { nativeCues.clear() }
        _captionLines.value = emptyList()
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle) }
        showSurface(false)
        dispatch {
            pumping = false
            if (handle != 0L) {
                FileAvNative.pause(handle)
                FileAvNative.close(handle)
            }
        }
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = appliedConfig
        appliedConfig = config
        loop = config.loop
        if (config.volume != prev.volume) {
            volume = config.volume.coerceIn(0f, 1f)
            dispatch { if (handle != 0L) FileAvNative.setVolume(handle, volume.toDouble()) }
        }
        if (config.speed != prev.speed) {
            dispatch { if (handle != 0L) FileAvNative.setSpeed(handle, config.speed.coerceAtLeast(0.05f).toDouble()) }
        }
        if (config.pitchFactor != prev.pitchFactor) {
            dispatch { if (handle != 0L) FileAvNative.setPitch(handle, config.pitchFactor.toDouble()) }
        }
        if (config.eqBands != prev.eqBands || config.bassBoostDb != prev.bassBoostDb ||
            config.virtualizer != prev.virtualizer || config.preampDb != prev.preampDb
        ) {
            val bands = FloatArray(10) { i -> config.eqBands.getOrElse(i) { 0f } }
            dispatch {
                if (handle != 0L) {
                    FileAvNative.setEq(handle, bands)
                    FileAvNative.setBass(handle, config.bassBoostDb)
                    FileAvNative.setVirtualizer(handle, config.virtualizer)
                    FileAvNative.setPreamp(handle, config.preampDb)
                }
            }
        }
        val quality = config.preferVideoQuality.first
        if (quality != prev.preferVideoQuality.first) {
            preferVideoQuality = quality
            audioOnly = config.audioOnly
            if (audioOnly) videoSurface?.clearFrame()
            _status.update {
                it.copy(videoSize = if (audioOnly) null else videoSizeFromStreams() ?: it.videoSize)
            }
            publishVideoQuality()
            if (_status.value.state is PlaybackState.Prepared) showSurface(true)
            videoWanted = !audioOnly && videoSizeFromStreams() != null
            dispatch {
                if (handle == 0L) return@dispatch
                if (audioOnly) {
                    FileAvNative.setVideoEnabled(handle, false)
                } else if (videoSurface?.nativePeer != null) {
                    // Attach-then-enable, same ordering as load so open_codec sees d3d11_hwnd.
                    enableVideoWhenAttached()
                }
                applyBlit()
            }
        }
        if (config.preferCaptionLanguage != prev.preferCaptionLanguage ||
            config.captionVisible != prev.captionVisible
        ) {
            publishCaptions(config)
            if (!config.captionVisible) _captionLines.value = emptyList()
            dispatch {
                if (handle == 0L) return@dispatch
                applyNativeCaption(config)
                val live = config.captionVisible && !usingSidecar
                if (live) {
                    synchronized(nativeCues) { nativeCues.clear() }
                    val pos = FileAvNative.position(handle)
                    if (pos >= 0) FileAvNative.seek(handle, pos)
                }
                onApp { emitCaptionLines(pendingPosMs / 1000.0) }
            }
        }
    }

    override fun selectAudioTrack(id: String) {
        if (released) return
        val index = id.toIntOrNull() ?: return
        _audioTracks.update { it.copy(selectedId = id) }
        val path = loadedPath ?: return
        val playing = (_status.value.state as? PlaybackState.Prepared)?.playWhenReady == true
        dispatch {
            if (handle == 0L) return@dispatch
            FileAvNative.setAudioIndex(handle, index)
            reopenAtPlayhead(playing, path)
        }
    }

    override fun pcmLevels(out: FloatArray): Int {
        val h = handle
        if (released || h == 0L || out.isEmpty()) return 0
        return FileAvNative.pcmLevels(h, out)
    }

    override fun presentedFrames(): Int =
        if (released) 0 else videoSurface?.presentedFrames() ?: 0

    override fun release() {
        if (released) return
        cmds.offer {
            pumping = false
            if (handle != 0L) {
                FileAvNative.destroy(handle)
                handle = 0
            }
            stopped = true
        }
        released = true
        loadGen.incrementAndGet()
        uiEpoch.incrementAndGet()
        playerThread.interrupt()
        if (Thread.currentThread() !== playerThread) {
            try {
                playerThread.join(5_000)
            } catch (_: InterruptedException) {
            }
        }
        videoSurface?.onViewSize = null
        videoSurface?.setVisible(false)
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    @Keep
    override fun onFrame(width: Int, height: Int, bgra: ByteBuffer?) {
        if (released || audioOnly) return
        if (bgra == null) {
            videoSurface?.presented()
            return
        }
        videoSurface?.setNativePresent(false)
        val img = grab(width, height)
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        val n = minOf(pixels.size, width * height)
        FileAvNative.copyBgra(bgra, pixels, n)
        lastPub = img
        videoSurface?.publish(img)
    }

    @Keep
    override fun onCaption(id: Int, startSec: Double, endSec: Double, text: String) {
        if (released || text.isBlank()) return
        val cue = LocalCue(id, startSec, endSec, text.trim())
        synchronized(nativeCues) {
            nativeCues.add(cue)
            val cutoff = pendingPosMs / 1000.0 - 30.0
            nativeCues.removeAll { it.endSec < cutoff }
        }
        onApp { emitCaptionLines(pendingPosMs / 1000.0) }
    }

    @Keep
    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) {
        if (released) return
        if (pendingSeekMs.get() >= 0) return
        val pos = (positionSec * 1000.0).toLong().coerceAtLeast(0L)
        if (durationSec > 0) durationMs = (durationSec * 1000.0).toLong()
        pendingPosMs = pos
        pendingDurMs = durationMs
        pendingVw = width
        pendingVh = height
        val epoch = uiEpoch.get()
        if (progressPosted.compareAndSet(false, true)) {
            onApp {
                progressPosted.set(false)
                if (epoch != uiEpoch.get() || pendingSeekMs.get() >= 0) return@onApp
                val p = pendingPosMs
                val d = pendingDurMs
                _progress.update {
                    it.copy(positionMs = p, durationMs = d, bufferedPositionMs = d)
                }
                emitCaptionLines(p / 1000.0)
                val w = pendingVw
                val h = pendingVh
                if (w > 0 && h > 0 && !audioOnly) {
                    val cur = _status.value.videoSize
                    if (cur == null || cur.width != w || cur.height != h) {
                        _status.update { it.copy(videoSize = VideoSize(w, h)) }
                    }
                }
            }
        }
    }

    @Keep
    override fun onEvent(ended: Boolean, error: String?) {
        if (released) return
        if (error != null && error.isNotEmpty()) {
            fail(error)
            return
        }
        if (!ended) return
        if (loop && loadedPath != null) {
            dispatch {
                if (handle == 0L) return@dispatch
                FileAvNative.seek(handle, 0.0)
                FileAvNative.play(handle)
                pumping = true
            }
            return
        }
        pumping = false
        val endPos = durationMs
        val gen = loadGen.get()
        onApp {
            if (gen != loadGen.get()) return@onApp
            _progress.update { it.copy(positionMs = if (endPos > 0) endPos else it.positionMs) }
            _status.update {
                it.copy(state = PlaybackState.Prepared(false, buffering = false, ended = true))
            }
        }
    }

    private fun applyConfigFlags(config: PlaybackConfig) {
        volume = config.volume.coerceIn(0f, 1f)
        audioOnly = config.audioOnly
        preferVideoQuality = config.preferVideoQuality.first
        loop = config.loop
    }

    /** VT scales into the window; 0×0 means native. */
    private fun applyBlit() {
        if (handle == 0L) return
        FileAvNative.setBlitSize(handle, viewW, viewH)
    }

    /** Player thread. `open` rebuilds from current audio_index; video decoder follows want_video. */
    private fun reopenAtPlayhead(play: Boolean, path: String? = loadedPath) {
        val file = path ?: return
        if (handle == 0L) return
        val pos = FileAvNative.position(handle)
        if (FileAvNative.open(handle, file) != 0) {
            fail(FileAvNative.lastError(handle).ifBlank { "open failed" })
            return
        }
        loadedPath = file
        streams = FileAvNative.streams(handle).mapNotNull(::parseAvStream)
        applyNativeCaption(appliedConfig)
        FileAvNative.seek(handle, pos)
        if (play) FileAvNative.play(handle)
        pumping = play
        val size = videoSizeFromStreams()
        onApp {
            publishTracks(appliedConfig)
            _status.update { it.copy(videoSize = size) }
        }
    }

    private fun videoSizeFromStreams(): VideoSize? {
        if (audioOnly) return null
        val v = streams.firstOrNull { it.kind == KIND_VIDEO } ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        return VideoSize(v.width, v.height)
    }

    private fun publishTracks(config: PlaybackConfig) {
        val auds = streams.filter { it.kind == KIND_AUDIO }
        _audioTracks.value = AudioTrackState(
            auds.map { AudioTrack(it.index.toString(), it.lang.ifBlank { "Audio ${it.index}" }) },
            auds.firstOrNull()?.index?.toString(),
        )
        publishVideoQuality()
        publishCaptions(config)
    }

    private fun publishVideoQuality() {
        val video = streams.firstOrNull { it.kind == KIND_VIDEO }
        val h = video?.let { minOf(it.width, it.height) } ?: 0
        _videoQuality.value = if (h <= 0) {
            VideoQualityState(emptyList(), VideoQualityState.AUDIO_ONLY, preferVideoQuality)
        } else {
            val opt = VideoQualityState.optionForHeight(h)
            VideoQualityState(
                listOf(opt) + VideoQualityState.LOCAL_BASE_OPTIONS,
                if (audioOnly) VideoQualityState.AUDIO_ONLY else opt.quality,
                preferVideoQuality,
            )
        }
    }

    private fun publishCaptions(config: PlaybackConfig) {
        val subs = streams.filter { it.kind == KIND_TEXT }
        val embedded = subs.map { s ->
            val lang = s.lang.ifBlank { s.index.toString() }
            CaptionTrack(lang, captionLabel(s.lang, s.index))
        }
        val sides = sidecarTracks.map { CaptionTrack(it.id, it.label) }
        val tracks = LinkedHashMap<String, CaptionTrack>()
        for (t in embedded) tracks[captionLangKey(t.languageCode)] = t
        for (t in sides) tracks[captionLangKey(t.id)] = t
        val list = tracks.values.toList()
        _captions.value = if (list.isEmpty()) {
            CaptionState.DEFAULT
        } else {
            val lang = config.preferCaptionLanguage.first
            val chosen = list.firstOrNull { it.id == lang || it.languageCode.equals(lang, true) }
                ?: list.first()
            CaptionState(list, if (config.captionVisible) chosen.id else null)
        }
    }

    override fun injectCaptionTracks(tracks: List<InjectedCaptionTrack>) {
        if (tracks.isEmpty()) return
        val extra = tracks.map { t ->
            SidecarTrack(
                id = t.id,
                lang = t.languageCode,
                label = t.displayName,
                cues = t.cues.mapIndexed { i, c -> LocalCue(i + 1, c.startSec, c.endSec, c.text) },
            )
        }
        sidecarTracks = (sidecarTracks + extra).distinctBy { it.id }
        onApp {
            publishCaptions(appliedConfig)
            if (appliedConfig.captionVisible) {
                dispatch {
                    applyNativeCaption(appliedConfig)
                    onApp { emitCaptionLines(pendingPosMs / 1000.0) }
                }
            }
        }
    }

    private fun dumpEmbeddedCaptions(): Map<Int, List<LocalCue>> {
        if (handle == 0L) return emptyMap()
        val out = LinkedHashMap<Int, List<LocalCue>>()
        for (s in streams.filter { it.kind == KIND_TEXT }) {
            val rows = FileAvNative.dumpCaptions(handle, s.index)
            val cues = rows.mapIndexedNotNull { i, line -> parseDumpedCue(i + 1, line) }
            if (cues.isNotEmpty()) out[s.index] = cues
        }
        return out
    }

    private fun applyNativeCaption(config: PlaybackConfig) {
        if (handle == 0L) return
        if (!config.captionVisible) {
            usingSidecar = false
            sidecarCues = emptyList()
            FileAvNative.setSubtitleIndex(handle, -1)
            return
        }
        val key = config.preferCaptionLanguage.first
        val keyNorm = captionLangKey(key)
        val side = sidecarTracks.firstOrNull {
            captionLangKey(it.id) == keyNorm || captionLangKey(it.lang) == keyNorm
        }
        if (side != null) {
            usingSidecar = true
            sidecarCues = side.cues
            FileAvNative.setSubtitleIndex(handle, -1)
            return
        }
        val subs = streams.filter { it.kind == KIND_TEXT }
        val chosen = subs.firstOrNull { it.lang.equals(key, true) || it.index.toString() == key }
            ?: subs.firstOrNull()
        val dumped = chosen?.let { dumpedCues[it.index] }.orEmpty()
        if (dumped.isNotEmpty()) {
            usingSidecar = true
            sidecarCues = dumped
            FileAvNative.setSubtitleIndex(handle, -1)
            return
        }
        usingSidecar = false
        sidecarCues = emptyList()
        FileAvNative.setSubtitleIndex(handle, chosen?.index ?: -1)
    }

    private fun emitCaptionLines(posSec: Double) {
        if (!appliedConfig.captionVisible) {
            if (_captionLines.value.isNotEmpty()) _captionLines.value = emptyList()
            return
        }
        val src = if (usingSidecar) sidecarCues else synchronized(nativeCues) { nativeCues.toList() }
        val active = src.filter { posSec + 0.05 >= it.startSec && posSec < it.endSec }
        val lines = active.mapNotNull { cue ->
            val rows = cue.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (rows.isEmpty()) null else CaptionLine(cue.id, rows.joinToString("\n"))
        }
        if (lines != _captionLines.value) _captionLines.value = lines
    }

    private fun fail(message: String) {
        pumping = false
        onApp {
            _status.update { it.copy(state = PlaybackState.Error(PlayerError("E60021", message))) }
        }
        dispatch { if (handle != 0L) FileAvNative.pause(handle) }
    }

    private fun showSurface(visible: Boolean) {
        videoSurface?.setVisible(visible)
    }

    private fun grab(w: Int, h: Int): BufferedImage {
        val shown = videoSurface?.swingShown
        val pending = lastPub
        for (i in 0..2) {
            val cur = frameBufs[i]
            if (cur != null && (cur === shown || cur === pending)) continue
            if (cur != null && cur.width == w && cur.height == h) return cur
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            frameBufs[i] = img
            return img
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        frameBufs[0] = img
        return img
    }
}

private const val KIND_AUDIO = 0
private const val KIND_VIDEO = 1
private const val KIND_TEXT = 2

private fun parseDumpedCue(id: Int, line: String): LocalCue? {
    val a = line.indexOf('|')
    val b = if (a >= 0) line.indexOf('|', a + 1) else -1
    if (a < 0 || b < 0) return null
    val start = line.substring(0, a).toDoubleOrNull() ?: return null
    val end = line.substring(a + 1, b).toDoubleOrNull() ?: return null
    val text = line.substring(b + 1).trim()
    if (text.isEmpty()) return null
    return LocalCue(id, start, end, text)
}

private fun captionLangKey(code: String): String {
    val c = code.removePrefix("file:").removePrefix("yt:").lowercase()
    return when (c) {
        "vie" -> "vi"
        "eng" -> "en"
        "spa" -> "es"
        "jpn" -> "ja"
        "ger", "deu" -> "de"
        "fre", "fra" -> "fr"
        else -> c
    }
}

private fun captionLabel(lang: String, index: Int): String {
    val code = lang.trim()
    if (code.isEmpty() || code.equals("und", ignoreCase = true)) return "Caption $index"
    val tag = when (code.lowercase()) {
        "vie" -> "vi"
        "eng" -> "en"
        "spa" -> "es"
        "jpn" -> "ja"
        "kor" -> "ko"
        "chi", "zho" -> "zh"
        "ger", "deu" -> "de"
        "fre", "fra" -> "fr"
        else -> code
    }
    val name = java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.getDefault())
    return name.replaceFirstChar { it.uppercase() }.ifBlank { code }
}

private data class AvStreamRow(
    val kind: Int,
    val index: Int,
    val width: Int,
    val height: Int,
    val lang: String,
    val codec: String,
)

fun LocalPlayer.localVideoSurface(): LocalVideoSurface? = (this as? JvmLocalPlayer)?.surface

private fun parseLocalUri(raw: String): Uri {
    val t = raw.trim()
    return if (t.contains("://") || t.startsWith("file:")) {
        Uri.parse(t)
    } else {
        fileUri(t)
    }
}

private fun Uri.toFilePath(): String =
    if (scheme?.toString().equals("file", ignoreCase = true)) filePath() else toString()

private fun parseAvStream(line: String): AvStreamRow? {
    val p = line.split('|')
    if (p.size < 6) return null
    return AvStreamRow(
        kind = p[0].toIntOrNull() ?: return null,
        index = p[1].toIntOrNull() ?: return null,
        width = p[2].toIntOrNull() ?: 0,
        height = p[3].toIntOrNull() ?: 0,
        lang = p[4],
        codec = p[5],
    )
}
