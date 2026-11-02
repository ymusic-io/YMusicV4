package io.ymusic.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Facade/router behavior over scripted fake engines: routing by item type, switch-latest flow
 * binding, auto-advance, config re-apply, sleep timer. Engine realism is covered by the per-backend
 * tests; here the engines are instrumented fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerFacadeTest {
    private class Load(val item: PlayableItem, val startMs: Long, val playWhenReady: Boolean, val config: PlaybackConfig)

    private open class FakeEngine<S : PlayableItem> :
        MediaEngine<S>, VideoQualitySelectable, CaptionSelectable, CaptionRenderable, AudioTrackSelectable {
        val loads = mutableListOf<Load>()
        val preloads = mutableListOf<S?>()
        var stopped = 0
        var releasedCount = 0
        var lastConfig = PlaybackConfig()

        private val _status = MutableStateFlow(PlaybackStatus(state = PlaybackState.Idle))
        override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

        private val _progress = MutableStateFlow(Progress())
        override val progress: StateFlow<Progress> = _progress.asStateFlow()

        override val videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
        override val captions = MutableStateFlow(CaptionState.DEFAULT)
        override val captionLines = MutableStateFlow<List<CaptionLine>>(emptyList())
        override val audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)

        override fun preload(source: S?) {
            preloads += source
        }

        override fun load(source: S, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
            loads += Load(source, startPositionMs, playWhenReady, config)
            lastConfig = config
            _status.value = PlaybackStatus(
                state = PlaybackState.Prepared(playWhenReady, buffering = false, ended = false),
            )
            _progress.value = Progress(positionMs = startPositionMs, durationMs = 60_000)
        }

        fun finishTrack() = _status.update {
            it.copy(state = PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true))
        }

        fun failTrack(canRetry: Boolean = true, needReset: Boolean = false) = _status.update {
            it.copy(
                state = PlaybackState.Error(
                    PlayerError("E60010", "boom", canRetry = canRetry, needReset = needReset),
                ),
            )
        }

        fun tickTo(positionMs: Long) = _progress.update { it.copy(positionMs = positionMs) }

        override fun play() = _status.update {
            val state = it.state as? PlaybackState.Prepared ?: return@update it
            it.copy(state = state.copy(playWhenReady = true, ended = false))
        }

        override fun pause() = _status.update {
            val state = it.state as? PlaybackState.Prepared ?: return@update it
            it.copy(state = state.copy(playWhenReady = false))
        }

        override fun seekTo(positionMs: Long) = _progress.update { it.copy(positionMs = positionMs) }

        override fun init() {
            if (_status.value.state is PlaybackState.Uninitialized) {
                _status.value = PlaybackStatus(state = PlaybackState.Idle)
            }
        }

        override fun reset() {
            _status.value = PlaybackStatus(state = PlaybackState.Idle)
            _progress.value = Progress()
        }

        override fun stop() {
            stopped++
            _status.value = PlaybackStatus(state = PlaybackState.Idle)
            _progress.value = Progress()
        }

        override fun setConfig(config: PlaybackConfig) {
            lastConfig = config
            videoQuality.update { it.copy(preferredQuality = config.preferVideoQuality.first) }
            captions.update {
                it.copy(selectedLanguage = config.preferCaptionLanguage.first.takeIf { l -> l != PlaybackConfig.UNDETERMINED_LANGUAGE })
            }
        }

        override fun selectAudioTrack(id: String) {
            audioTracks.update { it.copy(selectedId = id) }
        }

        override fun release() {
            releasedCount++
        }
    }

    private class FakeLocal : FakeEngine<PlayableItem.LocalMedia>(), LocalPlayer

    private class FakeYt : FakeEngine<PlayableItem.YtMedia>(), YtEngine {
        /** The harvest side channel — tests emit here to drive the facade's re-exposure. */
        val reports = kotlinx.coroutines.flow.MutableSharedFlow<WatchNextSeen>(
            extraBufferCapacity = 4,
        )
        override val watchNextSeen: kotlinx.coroutines.flow.SharedFlow<WatchNextSeen> =
            reports

        override suspend fun requestPlayerResponse(videoId: String): String = error("not used in this test")

        override suspend fun requestPoToken(videoId: String): String = error("not used in this test")
    }

    private class Harness(scope: TestScope, youtubeFactory: Boolean = true) {
        val local = FakeLocal()
        val youtube = FakeYt()
        var localBuilds = 0
        var youtubeBuilds = 0
        val player: Player = createPlayer(
            engines = PlayerEngines(
                local = { localBuilds++; local },
                youtube = if (youtubeFactory) {
                    { youtubeBuilds++; youtube }
                } else {
                    null
                },
            ),
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun local(n: Int) = PlayableItem.LocalMedia("file:///$n.mp3")

    private fun yt(id: String) = PlayableItem.YtMedia(id)

    @Test
    fun routes_by_item_type_and_builds_engines_lazily() = runTest {
        val h = Harness(this)
        assertEquals(0, h.localBuilds)

        h.player.setQueue(listOf(local(1), yt("abc")), playIndex = 0)
        runCurrent()
        assertEquals(1, h.localBuilds)
        assertEquals(0, h.youtubeBuilds, "YouTube engine must not build until a yt item plays")
        assertEquals(local(1), h.local.loads.single().item, "local item routes to the local engine")

        h.player.skipNext()
        runCurrent()
        assertEquals(1, h.youtubeBuilds)
        assertEquals(1, h.local.stopped, "switching engines must stop the old one")
        assertEquals(yt("abc"), h.youtube.loads.single().item, "yt item routes to the YouTube engine")
    }

    @Test
    fun the_youtube_engines_watch_next_reports_reach_the_facade() = runTest {
        val h = Harness(this)
        val seen = mutableListOf<WatchNextSeen>()
        val job = backgroundScope.launch { h.player.watchNextSeen.collect { seen += it } }
        runCurrent()

        h.player.setQueue(listOf(yt("abc")), playIndex = 0)
        runCurrent()
        h.youtube.reports.emit(WatchNextSeen("abc", "[]"))
        runCurrent()

        assertEquals(listOf(WatchNextSeen("abc", "[]")), seen)
        job.cancel()
    }

    @Test
    fun facade_flows_follow_the_active_engine() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), yt("a")), playIndex = 0)
        runCurrent()
        h.local.tickTo(5_000)
        runCurrent()
        assertEquals(5_000, h.player.progress.value.positionMs)

        h.player.skipNext()
        runCurrent()
        h.youtube.tickTo(9_000)
        h.local.tickTo(7_777)
        runCurrent()
        assertEquals(9_000, h.player.progress.value.positionMs, "old engine's flow must be unbound")
        assertIs<PlaybackState.Prepared>(h.player.status.value.state)
    }

    @Test
    fun preloads_next_item_in_the_last_ten_seconds() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), local(2)), playIndex = 0)
        runCurrent()
        assertTrue(h.local.preloads.isEmpty(), "must not preload at the start of a 60s track")
        h.local.tickTo(50_000)
        runCurrent()
        assertEquals(listOf<PlayableItem.LocalMedia?>(local(2)), h.local.preloads)
        h.local.tickTo(55_000)
        runCurrent()
        assertEquals(1, h.local.preloads.size, "preload once per next item")
    }

    @Test
    fun preload_cross_engine_clears_the_current_engine_cue() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), yt("abc")), playIndex = 0)
        runCurrent()
        h.local.tickTo(50_000)
        runCurrent()
        assertEquals(1, h.youtubeBuilds)
        assertEquals(listOf<PlayableItem.YtMedia?>(yt("abc")), h.youtube.preloads)
        assertEquals(listOf<PlayableItem.LocalMedia?>(null), h.local.preloads, "other engine: drop the current engine's extra cue")
    }

    @Test
    fun preload_skips_live_and_loop_one() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(PlayableItem.YtMedia("live", live = true), yt("next")), playIndex = 0)
        runCurrent()
        h.youtube.tickTo(50_000)
        runCurrent()
        assertTrue(h.youtube.preloads.isEmpty(), "live must not preload")

        h.player.setQueue(listOf(local(1), local(2)), playIndex = 0)
        h.player.setLoopMode(LoopMode.One)
        runCurrent()
        h.local.tickTo(50_000)
        runCurrent()
        assertTrue(h.local.preloads.isEmpty(), "loop-one repeats the current item")
    }

    @Test
    fun auto_advances_on_ended_and_stays_on_last() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), local(2)), playIndex = 0)
        runCurrent()
        h.local.finishTrack()
        runCurrent()
        assertEquals(1, h.player.queue.value.currentIndex)
        assertEquals(local(2), h.local.loads[1].item)
        assertTrue(h.local.loads[1].playWhenReady, "auto-advance keeps playing")

        h.local.finishTrack()
        runCurrent()
        assertEquals(1, h.player.queue.value.currentIndex, "no next: stays put")
        assertEquals(2, h.local.loads.size, "no reload on exhaustion")
        assertTrue(assertIs<PlaybackState.Prepared>(h.player.status.value.state).ended)
    }

    @Test
    fun loop_one_is_engine_native_not_a_facade_reload() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), local(2)), playIndex = 0)
        h.player.setLoopMode(LoopMode.One)
        runCurrent()
        assertTrue(h.local.lastConfig.loop)
        h.local.finishTrack()
        runCurrent()
        assertEquals(0, h.player.queue.value.currentIndex)
        assertEquals(1, h.local.loads.size, "loop-one must not seek/reload; the engine repeats")
        assertTrue(assertIs<PlaybackState.Prepared>(h.player.status.value.state).ended)
    }

    @Test
    fun config_survives_track_and_engine_changes() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), yt("a")), playIndex = 0)
        runCurrent()
        h.player.volume = 0.3f
        h.player.speed = 1.5f
        h.player.setAudioOnly(true)
        runCurrent()

        h.player.skipNext()
        runCurrent()
        val loaded = h.youtube.loads.single().config
        assertEquals(0.3f, loaded.volume)
        assertEquals(1.5f, loaded.speed)
        assertTrue(loaded.audioOnly)
    }

    @Test
    fun smart_prev_restarts_after_threshold() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), local(2)), playIndex = 1)
        runCurrent()
        h.local.tickTo(10_000)
        runCurrent()
        h.player.skipPrev()
        runCurrent()
        assertEquals(1, h.player.queue.value.currentIndex, "past threshold: restart, not previous")
        assertEquals(0, h.player.progress.value.positionMs)

        h.player.skipPrev()
        runCurrent()
        assertEquals(0, h.player.queue.value.currentIndex, "under threshold: previous item")
    }

    @Test
    fun missing_youtube_engine_reports_e60050() = runTest {
        val h = Harness(this, youtubeFactory = false)
        h.player.setQueue(listOf(yt("a")), playIndex = 0)
        runCurrent()
        assertIs<PlaybackState.Error>(h.player.status.value.state)
        assertEquals("E60050", assertNotNull(h.player.status.value.error).code)
    }

    @Test
    fun remove_current_loads_next_in_slot() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), local(2), local(3)), playIndex = 1)
        runCurrent()
        h.player.removeAt(1)
        runCurrent()
        assertEquals(1, h.player.queue.value.currentIndex)
        assertEquals(local(3), h.local.loads.last().item)
    }

    @Test
    fun clear_queue_stops() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1)), playIndex = 0)
        runCurrent()
        h.player.clearQueue()
        runCurrent()
        assertEquals(PlaybackState.Idle, h.player.status.value.state)
        assertTrue(h.player.queue.value.items.isEmpty())
    }

    @Test
    fun sleep_timer_pauses_and_disarms() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1)), playIndex = 0)
        runCurrent()
        assertTrue(h.player.status.value.playWhenReady)

        h.player.setSleepTimer(5_000)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3_000, h.player.sleepTimerMs.value)
        assertTrue(h.player.status.value.playWhenReady)

        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(null, h.player.sleepTimerMs.value)
        assertFalse(h.player.status.value.playWhenReady, "timer must pause playback")

        h.player.setSleepTimer(1_000)
        h.player.setSleepTimer(null)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(null, h.player.sleepTimerMs.value)
    }

    @Test
    fun capability_calls_forward_to_active_engine() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(yt("a")), playIndex = 0)
        runCurrent()
        h.player.selectVideoQuality("hd720")
        h.player.selectCaption("en")
        h.player.selectAudioTrack("t1")
        runCurrent()
        assertEquals("hd720", h.player.videoQuality.value.preferredQuality)
        assertEquals("en", h.player.captions.value.selectedLanguage)
        assertEquals("t1", h.player.audioTracks.value.selectedId)
    }

    @Test
    fun release_releases_built_engines() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1)), playIndex = 0)
        runCurrent()
        h.player.release()
        assertEquals(1, h.local.releasedCount)
        assertEquals(0, h.youtube.releasedCount, "never-built engine is not the facade's to release")

        h.player.setQueue(listOf(local(2)), playIndex = 0)
        runCurrent()
        assertEquals(1, h.local.loads.size, "released facade ignores commands")
    }

    @Test
    fun play_after_retryable_error_reloads_from_last_position() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1)), playIndex = 0)
        runCurrent()
        h.local.tickTo(12_000)
        h.local.failTrack(canRetry = true, needReset = true)
        runCurrent()
        assertIs<PlaybackState.Error>(h.player.status.value.state)

        h.player.play()
        runCurrent()
        assertEquals(2, h.local.loads.size, "play after a retryable error must reload")
        assertEquals(12_000, h.local.loads.last().startMs, "retry resumes where playback failed")
        assertIs<PlaybackState.Prepared>(h.player.status.value.state)
    }

    @Test
    fun play_after_non_retryable_error_does_not_reload() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1)), playIndex = 0)
        runCurrent()
        h.local.failTrack(canRetry = false)
        runCurrent()

        h.player.play()
        runCurrent()
        assertEquals(1, h.local.loads.size, "canRetry=false: play must not spin another load")
    }

    @Test
    fun play_after_stop_reloads_current() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), local(2)), playIndex = 1)
        runCurrent()
        h.player.stop()
        runCurrent()
        assertEquals(PlaybackState.Idle, h.player.status.value.state)

        h.player.play()
        runCurrent()
        assertEquals(2, h.local.loads.size, "play from Idle reloads the current item")
        assertEquals(local(2), h.local.loads.last().item)
        assertEquals(0, h.local.loads.last().startMs)
    }

    @Test
    fun play_at_jumps_and_plays() = runTest {
        val h = Harness(this)
        h.player.setQueue(listOf(local(1), local(2), local(3)), playIndex = 0, playWhenReady = false)
        runCurrent()
        assertFalse(h.local.loads.single().playWhenReady)
        h.player.playAt(2)
        runCurrent()
        val load = h.local.loads.last()
        assertIs<PlayableItem.LocalMedia>(load.item)
        assertEquals(local(3), load.item)
        assertTrue(load.playWhenReady, "playAt implies intent to play")
    }
}
