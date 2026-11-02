@file:androidx.annotation.OptIn(UnstableApi::class)

package io.ymusic.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.SubtitleView
import io.ymusic.mse.AudioFxNative
import io.ymusic.player.media3.AudioFxProcessor
import io.ymusic.player.media3.AudioFxProcessorChain
import io.ymusic.player.media3.AudioFxTimingSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

/**
 * Media3 ExoPlayer backend. ExoPlayer has strict single-thread affinity — it is bound to the main
 * looper here, so the caller must drive this player from the **main thread** (ExoPlayer throws
 * otherwise; this is the strictest backend and the reason the whole `LocalPlayer` contract is
 * main-thread). No calls are marshaled. State is driven by a [Player.Listener], not written
 * speculatively; the position ticker runs on the main dispatcher (the same thread the player is used on).
 */
internal class AndroidLocalPlayer(
    context: Context,
    private val videoContainer: ViewGroup? = null,
) : LocalPlayer,
    AudioSessionSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow(PlaybackStatus(state = PlaybackState.Idle))
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
    override val videoQuality: StateFlow<VideoQualityState> = _videoQuality.asStateFlow()

    private val _captions = MutableStateFlow(CaptionState.DEFAULT)
    override val captions: StateFlow<CaptionState> = _captions.asStateFlow()

    private val _audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioTracks: StateFlow<AudioTrackState> = _audioTracks.asStateFlow()

    private val fxHandle = AudioFxNative.create()
    private val fxProcessor = AudioFxProcessor(fxHandle)
    private var fxTiming: AudioFxTimingSink? = null

    private val _audioSessionId = MutableStateFlow(0)
    override val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()

    private val player = ExoPlayer.Builder(context.applicationContext)
        .setLooper(Looper.getMainLooper())
        .setRenderersFactory(
            object : DefaultRenderersFactory(context.applicationContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean,
                ): AudioSink = AudioFxTimingSink(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessorChain(AudioFxProcessorChain(fxProcessor))
                        .build(),
                ).also { fxTiming = it }
            },
        )
        .build()

    private var released = false
    private var audioOnly = false
    private var preferVideoQuality = VideoQualityState.AUTO

    /** The last-applied config; [setConfig] diffs against it so only changed fields re-touch ExoPlayer. */
    private var appliedConfig = PlaybackConfig()

    /** Legacy LocalPlayerView shape: one root owns the surface and caption overlay visibility. */
    private val rootView: FrameLayout? = videoContainer?.let { FrameLayout(it.context) }
    private val surfaceView: SurfaceView? = rootView?.let { SurfaceView(it.context) }
    private val subtitleView: SubtitleView? = rootView?.let { SubtitleView(it.context) }

    /** The preloaded next item that failed to open, and its error, published when that item is loaded. */
    private var preloadError: Pair<MediaItem, PlayerError>? = null

    // Audio-track id → its (group, index) for TrackSelectionOverride, refreshed on onTracksChanged.
    private val audioGroups = mutableMapOf<String, Pair<Tracks.Group, Int>>()

    init {
        _audioSessionId.value = player.audioSessionId
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = syncFromPlayer(playbackState)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncFromPlayer(player.playbackState)
                snapshotProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && player.currentMediaItemIndex == 1) {
                    player.playWhenReady = false
                    _status.update {
                        it.copy(
                            state = PlaybackState.Prepared(
                                playWhenReady = false,
                                buffering = false,
                                ended = true,
                            ),
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = error.errorCodeName + ": " + error.message
                val playerError = if (error.isFileMissing()) {
                    PlayerError(PlayerError.FILE_MISSING, detail, canRetry = false, cause = error)
                } else if (error.isAccessDenied()) {
                    PlayerError(PlayerError.FOLDER_ACCESS_LOST, detail, canRetry = false, cause = error)
                } else {
                    PlayerError(error.localErrorCode(), detail, cause = error)
                }
                // ExoPlayer raises the preloaded next item's open failure only once the current one has
                // played to its end: that track ended, and the error waits for the next item's load.
                val failedPreload = failedItem(error)?.takeIf { it == PRELOAD_INDEX }?.let(player::getMediaItemAt)
                if (failedPreload != null) {
                    preloadError = failedPreload to playerError
                    _status.update {
                        it.copy(state = PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true))
                    }
                    return
                }
                _status.update { it.copy(state = PlaybackState.Error(playerError)) }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                _audioSessionId.value = audioSessionId
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val size = if (audioOnly || videoSize.width == 0 || videoSize.height == 0) {
                    null
                } else {
                    VideoSize(videoSize.width, videoSize.height)
                }
                _status.update { it.copy(videoSize = size) }
            }

            override fun onTracksChanged(tracks: Tracks) = updateTrackStates(tracks)

            override fun onCues(cueGroup: CueGroup) {
                subtitleView?.setCues(cueGroup.cues)
            }
        })
        rootView?.let { root ->
            root.visibility = View.GONE
            root.setBackgroundColor(android.graphics.Color.BLACK)
            surfaceView?.let { sv ->
                root.addView(
                    sv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            subtitleView?.let { captions ->
                captions.visibility = View.GONE
                root.addView(
                    captions,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            videoContainer?.addView(
                root,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        surfaceView?.let { sv ->
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (!released) player.setVideoSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (!released) player.setVideoSurface(null)
                }
            })
        }
        scope.launch { tick() }
    }

    /** Show/hide this engine's `SurfaceView` in the shared container (a no-op without one). When
     *  shown, raise it to the front so this (active) engine's picture covers a co-hosted surface. */
    private fun showSurface(visible: Boolean) {
        rootView?.let { root ->
            root.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) root.bringToFront()
        }
    }

    override fun init() {}

    override fun reset() {
        if (released) return
        stop()
    }

    override fun load(source: PlayableItem.LocalMedia, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
        if (released) return
        val uri = source.uri
        val failedPreload = preloadError
        preloadError = null
        if (failedPreload != null && sameUri(failedPreload.first, MediaItem.fromUri(uri.toPlayableUri()))) {
            _status.update { it.copy(state = PlaybackState.Error(failedPreload.second)) }
            return
        }
        // Apply config before prepare so the first frame is already at the right settings.
        appliedConfig = config
        audioOnly = config.audioOnly
        preferVideoQuality = config.preferVideoQuality.first
        player.volume = config.volume.coerceIn(0f, 1f)
        player.playbackParameters = PlaybackParameters(
            config.speed.coerceAtLeast(0.01f),
            config.pitchFactor.coerceIn(0.5f, 2f),
        )
        applyColor(config)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, config.audioOnly)
            .build()
        applyCaptions(config)
        _status.update {
            it.copy(state = PlaybackState.Prepared(playWhenReady, buffering = true, ended = false))
        }
        _progress.value = Progress(positionMs = startPositionMs)
        val mediaItem = MediaItem.fromUri(uri.toPlayableUri())
        var preloaded = false
        val count = player.mediaItemCount
        if (player.playbackState != Player.STATE_IDLE && count > 0) {
            for (i in 0 until count) {
                if (sameUri(player.getMediaItemAt(i), mediaItem)) {
                    preloaded = true
                    if (player.currentMediaItemIndex != i) {
                        player.seekTo(i, startPositionMs)
                    } else if (startPositionMs > 0L) {
                        player.seekTo(startPositionMs)
                    }
                    if (i == 1) player.removeMediaItem(0)
                    break
                }
            }
        }
        if (!preloaded) player.setMediaItem(mediaItem, startPositionMs)
        player.repeatMode = if (config.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.playWhenReady = playWhenReady
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        showSurface(true)
    }

    override fun preload(source: PlayableItem.LocalMedia?) {
        if (released) return
        val mediaItem = source?.let { MediaItem.fromUri(it.uri.toPlayableUri()) }
        when (val count = player.mediaItemCount) {
            0 -> {
                mediaItem ?: return
                player.playWhenReady = false
                player.addMediaItem(mediaItem)
                player.prepare()
            }
            1 -> {
                mediaItem ?: return
                player.addMediaItem(mediaItem)
            }
            2 -> {
                if (mediaItem == null) {
                    player.removeMediaItem(1)
                    return
                }
                if (!sameUri(player.getMediaItemAt(1), mediaItem)) {
                    player.replaceMediaItem(1, mediaItem)
                }
            }
            else -> error("unexpected media item count $count")
        }
    }

    override fun play() {
        if (!released) player.play()
    }

    override fun pause() {
        if (!released) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        _progress.update { it.copy(positionMs = clamped) }
    }

    /** The playlist index of the item [error] came from, when ExoPlayer names its media period. */
    private fun failedItem(error: PlaybackException): Int? {
        val periodUid = (error as? ExoPlaybackException)?.mediaPeriodId?.periodUid ?: return null
        val timeline = player.currentTimeline
        val periodIndex = timeline.getIndexOfPeriod(periodUid)
        if (periodIndex == C.INDEX_UNSET) return null
        return timeline.getPeriod(periodIndex, Timeline.Period()).windowIndex
    }

    override fun stop() {
        if (released) return
        preloadError = null
        player.stop()
        player.clearMediaItems()
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle) }
        showSurface(false)
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = appliedConfig
        appliedConfig = config

        if (config.volume != prev.volume) {
            player.volume = config.volume.coerceIn(0f, 1f)
        }
        if (config.speed != prev.speed || config.pitchFactor != prev.pitchFactor) {
            player.playbackParameters = PlaybackParameters(
                config.speed.coerceAtLeast(0.01f),
                config.pitchFactor.coerceIn(0.5f, 2f),
            )
        }
        if (config.eqBands != prev.eqBands || config.bassBoostDb != prev.bassBoostDb ||
            config.virtualizer != prev.virtualizer || config.preampDb != prev.preampDb
        ) {
            applyColor(config)
        }
        if (config.preferVideoQuality.first != prev.preferVideoQuality.first) {
            applyVideoQuality(config.preferVideoQuality.first)
        }
        if (config.preferCaptionLanguage.first != prev.preferCaptionLanguage.first ||
            config.captionVisible != prev.captionVisible
        ) {
            applyCaptions(config)
        }
        if (config.loop != prev.loop) {
            player.repeatMode = if (config.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    private fun applyVideoQuality(quality: String) {
        preferVideoQuality = quality
        val ao = quality == VideoQualityState.AUDIO_ONLY
        audioOnly = ao
        val height = VideoQualityState.heightForQuality(quality)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, ao)
            .apply { if (height != null) setMaxVideoSize(Int.MAX_VALUE, height) else clearVideoSizeConstraints() }
            .build()
        _status.update { it.copy(videoSize = if (ao) null else it.videoSize) }
        _videoQuality.update { it.copy(preferredQuality = quality) }
        if (_status.value.state is PlaybackState.Prepared) showSurface(true)
    }

    private fun applyCaptions(config: PlaybackConfig) {
        val lang = config.preferCaptionLanguage.first
        val undetermined = lang == PlaybackConfig.UNDETERMINED_LANGUAGE
        val off = !config.captionVisible || undetermined
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, off)
            .apply { if (!undetermined) setPreferredTextLanguage(lang) }
            .build()
        _captions.update { it.copy(selectedLanguage = if (off) null else lang) }
        subtitleView?.visibility = if (config.captionVisible) View.VISIBLE else View.GONE
    }

    override fun selectAudioTrack(id: String) {
        if (released) return
        val (group, index) = audioGroups[id] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
        _audioTracks.update { it.copy(selectedId = id) }
    }

    /** Rebuild the three capability states from the player's current [Tracks] (on onTracksChanged). */
    private fun updateTrackStates(tracks: Tracks) {
        val sizes = mutableListOf<Int>()
        var selectedSize = 0
        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }.forEach { g ->
            for (i in 0 until g.length) {
                val h = minOf(g.getTrackFormat(i).width, g.getTrackFormat(i).height)
                if (h > 0) {
                    if (h !in sizes) sizes.add(h)
                    if (g.isTrackSelected(i)) selectedSize = h
                }
            }
        }
        _videoQuality.value = if (tracks.isEmpty) {
            VideoQualityState.DEFAULT.copy(preferredQuality = preferVideoQuality)
        } else if (sizes.isEmpty()) {
            VideoQualityState(emptyList(), VideoQualityState.AUDIO_ONLY, preferVideoQuality)
        } else {
            sizes.sortDescending()
            val options = sizes.map { VideoQualityState.optionForHeight(it) } + VideoQualityState.LOCAL_BASE_OPTIONS
            val actual = if (selectedSize > 0) {
                VideoQualityState.optionForHeight(selectedSize).quality
            } else {
                VideoQualityState.AUDIO_ONLY
            }
            VideoQualityState(options, actual, preferVideoQuality)
        }

        val captionTracks = mutableListOf<CaptionTrack>()
        var selectedCaption: String? = null
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }.forEach { g ->
            for (i in 0 until g.length) {
                val fmt = g.getTrackFormat(i)
                val lang = fmt.language ?: "und"
                captionTracks.add(CaptionTrack(lang, fmt.label ?: lang))
                if (g.isTrackSelected(i)) selectedCaption = lang
            }
        }
        _captions.value =
            if (captionTracks.isEmpty()) CaptionState.DEFAULT else CaptionState(captionTracks, selectedCaption)

        audioGroups.clear()
        val audio = mutableListOf<AudioTrack>()
        var selectedAudio: String? = null
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }.forEach { g ->
            for (i in 0 until g.length) {
                val fmt = g.getTrackFormat(i)
                val id = fmt.id ?: "${fmt.language ?: "und"}#$i"
                audioGroups[id] = g to i
                audio.add(AudioTrack(id, fmt.label ?: fmt.language ?: id))
                if (g.isTrackSelected(i)) selectedAudio = id
            }
        }
        _audioTracks.value = if (audio.isEmpty()) AudioTrackState.DEFAULT else AudioTrackState(audio, selectedAudio)
    }

    override fun pcmLevels(out: FloatArray): Int {
        if (released || fxHandle == 0L || out.isEmpty()) return 0
        return AudioFxNative.levels(fxHandle, out, fxTiming?.pendingFrames() ?: 0)
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        rootView?.let { root -> videoContainer?.removeView(root) }
        player.release()
        AudioFxNative.destroy(fxHandle)
        _audioSessionId.value = 0
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private fun syncFromPlayer(playbackState: Int) {
        if (player.currentMediaItemIndex == 1 && playbackState != Player.STATE_IDLE) return
        val mapped = when (playbackState) {
            Player.STATE_IDLE -> PlaybackState.Idle
            Player.STATE_BUFFERING -> PlaybackState.Prepared(player.playWhenReady, buffering = true, ended = false)
            Player.STATE_READY -> PlaybackState.Prepared(player.playWhenReady, buffering = false, ended = false)
            Player.STATE_ENDED -> PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true)
            else -> PlaybackState.Idle
        }
        _status.update {
            it.copy(
                state = if (it.state is PlaybackState.Error || preloadError != null) it.state else mapped,
            )
        }
        _progress.update { it.copy(durationMs = durationOrUnknown()) }
    }

    private fun snapshotProgress() {
        if (released || player.currentMediaItemIndex != 0) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val buf = player.bufferedPosition.coerceAtLeast(0L)
        _progress.update {
            it.copy(positionMs = pos, bufferedPositionMs = buf, durationMs = durationOrUnknown())
        }
    }

    private fun ticking(): Boolean {
        if (released) return false
        val st = _status.value.state as? PlaybackState.Prepared ?: return false
        return st.playWhenReady && !st.ended
    }

    private suspend fun tick() {
        while (scope.isActive) {
            if (ticking()) {
                snapshotProgress()
                delay(TICK_MS)
            } else {
                snapshotProgress()
                _status.first {
                    val st = it.state as? PlaybackState.Prepared
                    st != null && st.playWhenReady && !st.ended
                }
            }
        }
    }

    private fun applyColor(config: PlaybackConfig) {
        if (fxHandle == 0L) return
        AudioFxNative.setEq(fxHandle, FloatArray(10) { i -> config.eqBands.getOrElse(i) { 0f } })
        AudioFxNative.setBass(fxHandle, config.bassBoostDb)
        AudioFxNative.setVirtualizer(fxHandle, config.virtualizer)
        AudioFxNative.setPreamp(fxHandle, config.preampDb)
    }

    private fun durationOrUnknown(): Long =
        player.duration.let { if (it == C.TIME_UNSET) Progress.DURATION_UNKNOWN else it }

    private companion object {
        const val TICK_MS = 200L
    }
}

/** ExoPlayer needs a scheme; a bare path becomes `file://…`. */
private fun String.toPlayableUri(): Uri =
    if (contains("://")) Uri.parse(this) else Uri.fromFile(java.io.File(this))

/** The playlist slot [AndroidLocalPlayer.preload] fills with the next item. */
private const val PRELOAD_INDEX = 1

private fun sameUri(a: MediaItem, b: MediaItem): Boolean =
    a.localConfiguration?.uri == b.localConfiguration?.uri

/** ExoPlayer's own not-found code, a [FileNotFoundException] anywhere in the chain, or the documents
 *  provider's IllegalArgumentException ("Failed to determine if … is child of …: java.io.FileNotFoundException")
 *  that carries it only in its message — the cause doesn't survive the binder. */
private fun PlaybackException.isAccessDenied(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { it is SecurityException }

private fun PlaybackException.isFileMissing(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
        generateSequence<Throwable>(this) { it.cause }.any {
            it is FileNotFoundException || (it is IllegalArgumentException && it.message?.contains("FileNotFoundException") == true)
        }

/** ExoPlayer's error code ranges: 3xxx parsing, 4xxx decoding, 5xxx audio output. */
private fun PlaybackException.localErrorCode(): String = when (errorCode) {
    in 4000..4999 -> "E60011"
    in 3000..3999 -> "E60012"
    in 5000..5999 -> "E60013"
    PlaybackException.ERROR_CODE_TIMEOUT -> "E60014"
    else -> "E60010"
}
