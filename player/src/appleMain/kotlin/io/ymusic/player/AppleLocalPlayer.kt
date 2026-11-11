package io.ymusic.player

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
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
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusUnknown
import platform.AVFoundation.AVPlayerItemTrack
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentMediaSelection
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.extendedLanguageTag
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.mediaType
import platform.AVFoundation.naturalSize
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.selectedMediaOptionInMediaSelectionGroup
import platform.AVFoundation.setRate
import platform.AVFoundation.setVolume
import platform.AVFoundation.status
import platform.AVFoundation.tracks
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.volume
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL

/**
 * AVFoundation `AVPlayer` backend — one implementation for iOS and macOS.
 *
 * Per the `LocalPlayer` contract the caller drives it on the main thread; `AVPlayer` is itself
 * thread-safe, so the internal readiness poll + position ticker run on a background dispatcher.
 * Readiness and end-of-file are detected by polling `AVPlayerItem.status` and comparing the
 * play-head to the duration, so nothing here depends on the host app's run loop delivering KVO/
 * notification callbacks (the module must work inside plain test processes too).
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleLocalPlayer(
    createSurface: (AVPlayer) -> ApplePlayerSurface? = { null },
) : LocalPlayer {
    private val player = AVPlayer()
    private val surface = createSurface(player)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private var desiredSpeed: Float = 1f
    private var audioOnly = false
    private var videoHidden = false
    private var released = false
    private var preferVideoQuality = VideoQualityState.AUTO

    /** The last-applied config; [setConfig] diffs against it so only changed fields re-touch AVPlayer. */
    private var appliedConfig = PlaybackConfig()

    // Track id → (group, option) for selectMediaOption, refreshed when a load reaches Ready.
    private val captionOptions = mutableMapOf<String, Pair<AVMediaSelectionGroup, AVMediaSelectionOption>>()
    private val audioOptions = mutableMapOf<String, Pair<AVMediaSelectionGroup, AVMediaSelectionOption>>()

    /** The underlying player, for an iOS/macOS `AVPlayerLayer`-backed surface to bind to. */
    val player0: AVPlayer get() = player

    init {
        ensurePlaybackAudioSession()
        scope.launch { tick() }
    }

    override fun init() {}

    override fun reset() {
        if (released) return
        stop()
    }

    override fun load(source: PlayableItem.LocalMedia, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
        if (released) return
        val uri = source.uri
        val url = uri.toNsUrl()
        if (url == null) {
            fail("E60001", "bad media uri: $uri")
            return
        }
        // Config applies before finalizeLoad (which calls applyAudioOnly on the new item).
        appliedConfig = config
        audioOnly = config.audioOnly
        videoHidden = config.videoHidden
        preferVideoQuality = config.preferVideoQuality.first
        desiredSpeed = config.speed.coerceAtLeast(0.01f)
        player.setVolume(config.volume.coerceIn(0f, 1f))
        val item = AVPlayerItem(uRL = url)
        player.replaceCurrentItemWithPlayerItem(item)
        surface?.setVisible(true)
        _status.update {
            it.copy(state = PlaybackState.Prepared(playWhenReady, buffering = true, ended = false))
        }
        _progress.value = Progress(positionMs = startPositionMs)
        scope.launch { finalizeLoad(item, uri, startPositionMs, playWhenReady) }
    }

    /** Poll the item to readiness (AVPlayer prepares asynchronously) and finalize, or surface an error. */
    private suspend fun finalizeLoad(item: AVPlayerItem, uri: String, startPositionMs: Long, playWhenReady: Boolean) {
        val deadlineIterations = LOAD_TIMEOUT_MS / POLL_MS
        var i = 0L
        while (item.status == AVPlayerItemStatusUnknown) {
            if (i++ >= deadlineIterations) {
                if (player.currentItem == item) fail("E60002", "timed out preparing $uri")
                return
            }
            delay(POLL_MS)
        }
        // A newer load may have replaced this item while we polled — abandon the stale one.
        if (player.currentItem != item) return
        if (item.status == AVPlayerItemStatusFailed) {
            // NSError.description carries domain/code/underlying error; localizedDescription is
            // usually just "The operation could not be completed".
            fail("E60003", item.error?.description ?: item.error?.localizedDescription ?: "unknown AVPlayerItem failure")
            return
        }

        val durationMs = item.duration.toMillisOrUnknown()
        if (startPositionMs > 0L) player.seekToTime(cmTime(startPositionMs))
        applyAudioOnly()
        _progress.value = Progress(positionMs = startPositionMs, durationMs = durationMs)
        _status.update {
            val play = (it.state as? PlaybackState.Prepared)?.playWhenReady == true
            it.copy(
                state = PlaybackState.Prepared(play, buffering = false, ended = false),
                videoSize = if (audioOnly) null else item.videoSize(),
            )
        }
        populateTracks(item)
        if (playWhenReady) play()
    }

    override fun play() {
        if (released) return
        player.setRate(desiredSpeed)
        _status.update { it.copy(state = it.state.withPlayWhenReady(true)) }
    }

    override fun pause() {
        if (released) return
        player.pause()
        _status.update { it.copy(state = it.state.withPlayWhenReady(false)) }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekToTime(cmTime(clamped))
        _progress.update { it.copy(positionMs = clamped) }
        _status.update { it.copy(state = it.state.withPlayWhenReady(it.state.isPlaying())) }
        if (_status.value.playWhenReady) player.setRate(desiredSpeed)
    }

    override fun stop() {
        if (released) return
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle) }
        surface?.setVisible(false)
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = appliedConfig
        appliedConfig = config

        if (config.volume != prev.volume) {
            player.setVolume(config.volume.coerceIn(0f, 1f))
        }
        if (config.speed != prev.speed) {
            desiredSpeed = config.speed.coerceAtLeast(0.01f)
            if (_status.value.playWhenReady && _status.value.state is PlaybackState.Prepared) {
                player.setRate(desiredSpeed)
            }
        }
        if (config.preferVideoQuality.first != prev.preferVideoQuality.first || config.audioOnly != prev.audioOnly) {
            applyVideoQuality(config.preferVideoQuality.first)
        }
        if (config.videoHidden != prev.videoHidden) {
            videoHidden = config.videoHidden
            applyAudioOnly()
        }
        if (config.preferCaptionLanguage.first != prev.preferCaptionLanguage.first ||
            config.captionVisible != prev.captionVisible
        ) {
            applyCaptions(config)
        }
    }

    private fun applyVideoQuality(quality: String) {
        preferVideoQuality = quality
        // AVPlayer has no per-resolution selection for progressive files; audio-only is the honored axis.
        val ao = appliedConfig.audioOnly || quality == VideoQualityState.AUDIO_ONLY
        audioOnly = ao
        applyAudioOnly()
        _status.update { it.copy(videoSize = if (ao) null else it.videoSize ?: player.currentItem?.videoSize()) }
        _videoQuality.update { state ->
            val video = state.options.firstOrNull { VideoQualityState.heightForQuality(it.quality) != null }
            val actual = when {
                ao -> VideoQualityState.AUDIO_ONLY
                video != null -> video.quality
                else -> state.actualQuality
            }
            state.copy(preferredQuality = quality, actualQuality = actual)
        }
    }

    private fun applyCaptions(config: PlaybackConfig) {
        val item = player.currentItem ?: return
        val legible = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible) ?: return
        val lang = config.preferCaptionLanguage.first
        val undetermined = lang == PlaybackConfig.UNDETERMINED_LANGUAGE
        if (!config.captionVisible || undetermined) {
            item.selectMediaOption(null, legible)
            _captions.update { it.copy(selectedLanguage = null) }
        } else {
            captionOptions[lang]?.let { (group, opt) ->
                item.selectMediaOption(opt, group)
                _captions.update { it.copy(selectedLanguage = lang) }
            }
        }
    }

    override fun selectAudioTrack(id: String) {
        if (released) return
        val item = player.currentItem ?: return
        val (group, opt) = audioOptions[id] ?: return
        item.selectMediaOption(opt, group)
        _audioTracks.update { it.copy(selectedId = id) }
    }

    /** Populate the caption/audio/video-quality states from the ready item's selection groups + tracks. */
    private fun populateTracks(item: AVPlayerItem) {
        val asset = item.asset
        audioOptions.clear()
        captionOptions.clear()

        val audible = asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)
        _audioTracks.value = if (audible == null) {
            AudioTrackState.DEFAULT
        } else {
            val current = item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(audible)
            val tracks = mutableListOf<AudioTrack>()
            var selected: String? = null
            (audible.options ?: emptyList<Any?>()).filterIsInstance<AVMediaSelectionOption>().forEachIndexed { i, opt ->
                val id = opt.extendedLanguageTag ?: "audio#$i"
                audioOptions[id] = audible to opt
                tracks.add(AudioTrack(id, opt.displayName ?: id))
                if (opt == current) selected = id
            }
            if (tracks.isEmpty()) AudioTrackState.DEFAULT else AudioTrackState(tracks, selected)
        }

        val legible = asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)
        _captions.value = if (legible == null) {
            CaptionState.DEFAULT
        } else {
            val current = item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(legible)
            val tracks = mutableListOf<CaptionTrack>()
            var selected: String? = null
            (legible.options ?: emptyList<Any?>()).filterIsInstance<AVMediaSelectionOption>().forEach { opt ->
                val lang = opt.extendedLanguageTag ?: "und"
                captionOptions[lang] = legible to opt
                tracks.add(CaptionTrack(lang, opt.displayName ?: lang))
                if (opt == current) selected = lang
            }
            if (tracks.isEmpty()) CaptionState.DEFAULT else CaptionState(tracks, selected)
        }

        val heights = (asset.tracksWithMediaType(AVMediaTypeVideo) ?: emptyList<Any?>())
            .filterIsInstance<AVAssetTrack>()
            .map { it.naturalSize.useContents { minOf(width, height).toInt() } }
            .filter { it > 0 }.distinct().sortedDescending()
        _videoQuality.value = if (heights.isEmpty()) {
            VideoQualityState(emptyList(), VideoQualityState.AUDIO_ONLY, preferVideoQuality)
        } else {
            val options = heights.map { VideoQualityState.optionForHeight(it) } + VideoQualityState.LOCAL_BASE_OPTIONS
            val actual = if (audioOnly) VideoQualityState.AUDIO_ONLY else VideoQualityState.optionForHeight(heights.first()).quality
            VideoQualityState(options, actual, preferVideoQuality)
        }
    }

    /** Enable/disable the video item tracks so audio-only (or a hidden picture) truly stops decoding video. */
    private fun applyAudioOnly() {
        val item = player.currentItem ?: return
        item.tracks.forEach { element ->
            val track = element as? AVPlayerItemTrack ?: return@forEach
            if (track.assetTrack?.mediaType == AVMediaTypeVideo) track.setEnabled(!audioOnly && !videoHidden)
        }
    }

    override fun release() {
        if (released) return
        released = true
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        surface?.setVisible(false)
        surface?.release()
        scope.cancel()
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private fun ticking(): Boolean {
        if (released) return false
        val st = _status.value.state as? PlaybackState.Prepared ?: return false
        return st.playWhenReady && !st.ended
    }

    private fun tickOnce() {
        val item = player.currentItem ?: return
        val st = _status.value
        if (st.state !is PlaybackState.Prepared) return
        val posMs = player.currentTime().toMillisOrUnknown().coerceAtLeast(0L)
        val prevDur = _progress.value.durationMs
        val durMs = if (prevDur > 0L) prevDur else item.duration.toMillisOrUnknown()
        val ended = durMs > 0L && posMs >= durMs - END_EPSILON_MS && player.rate == 0f && st.playWhenReady
        if (ended && appliedConfig.loop) {
            player.seekToTime(cmTime(0L))
            player.setRate(desiredSpeed)
            _progress.update { it.copy(positionMs = 0L, durationMs = durMs) }
        } else {
            _progress.update { it.copy(positionMs = if (ended) durMs else posMs, durationMs = durMs) }
            if (ended) {
                _status.update { it.copy(state = PlaybackState.Prepared(false, buffering = false, ended = true)) }
            }
        }
        if (!audioOnly) {
            val vs = item.videoSize()
            if (vs != null && vs != st.videoSize) _status.update { it.copy(videoSize = vs) }
        }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            if (ticking()) {
                tickOnce()
                delay(TICK_MS)
            } else {
                tickOnce()
                _status.first {
                    val st = it.state as? PlaybackState.Prepared
                    st != null && st.playWhenReady && !st.ended
                }
            }
        }
    }

    private fun fail(code: String, message: String) {
        _status.update { it.copy(state = PlaybackState.Error(PlayerError(code, message))) }
    }

    private fun PlaybackState.withPlayWhenReady(playWhenReady: Boolean): PlaybackState =
        if (this is PlaybackState.Prepared) copy(playWhenReady = playWhenReady, ended = false) else this

    private companion object {
        const val POLL_MS = 20L
        const val LOAD_TIMEOUT_MS = 15_000L
        const val TICK_MS = 200L
        const val END_EPSILON_MS = 120L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cmTime(ms: Long) = CMTimeMakeWithSeconds(ms / 1000.0, preferredTimescale = 1000)

/** Natural video dimensions of the item, or null if it has no visible video (audio-only file). */
@OptIn(ExperimentalForeignApi::class)
private fun AVPlayerItem.videoSize(): VideoSize? =
    presentationSize.useContents {
        if (width > 0.0 && height > 0.0) VideoSize(width.toInt(), height.toInt()) else null
    }

/** `CValue<CMTime>` → milliseconds, or [Progress.DURATION_UNKNOWN] for NaN/indefinite times. */
@OptIn(ExperimentalForeignApi::class)
private fun CValue<CMTime>.toMillisOrUnknown(): Long {
    val seconds = CMTimeGetSeconds(this)
    return if (seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) {
        Progress.DURATION_UNKNOWN
    } else {
        (seconds * 1000.0).toLong()
    }
}

private fun String.toNsUrl(): NSURL? =
    if (contains("://")) NSURL.URLWithString(this) else NSURL.fileURLWithPath(this)
