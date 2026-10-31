package io.ymusic.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import io.ymusic.player.AudioTrackState
import io.ymusic.player.CaptionState
import io.ymusic.player.LoopMode
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.Player
import io.ymusic.player.Progress
import io.ymusic.player.QueueState
import io.ymusic.player.ScaleMode
import io.ymusic.player.VideoQualityState
import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.HeadersBuilder
import kmphttp.InMemoryCookieJar
import kmphttp.KmpHttpClient
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.Response
import kmphttp.ResponseBuilder
import kmphttp.SslConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import kotlin.time.Duration

/** A clickable element by the screen-reader label [a11yActionLabel] gives it: `text` on desktop, `contentDescription` elsewhere. */
internal fun hasActionLabel(label: String): SemanticsMatcher =
    (hasContentDescription(label) or hasText(label)) and hasClickAction()

internal fun SemanticsNodeInteractionsProvider.onNodeWithActionLabel(label: String) = onNode(hasActionLabel(label))

internal fun SemanticsNodeInteractionsProvider.onAllNodesWithActionLabel(label: String) = onAllNodes(hasActionLabel(label))

/** A canned [KmpHttpClient]: [handler] gets each request plus its decoded body text. */
internal class CannedHttp(
    private val handler: suspend (request: Request, body: String?) -> Response,
) : KmpHttpClient {
    override var cookieJar: CookieJar = InMemoryCookieJar()
    override val interceptors: List<AsyncInterceptor> = emptyList()
    override val networkInterceptors: List<AsyncInterceptor> = emptyList()
    override val cache: Cache? = null
    override val sslConfig: SslConfig = SslConfig()
    override val defaultRedirectOptions: RedirectOptions =
        RedirectOptions(followRedirects = true, followSslRedirects = true)
    override val defaultExecuteTimeout: Duration? = null
    override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val logger: kmpcommon.log.Logger? = null

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: Duration?): Response {
        // Off the engine thread: a streamed body's writeTo runBlocking-pulls pipe reads that must
        // dispatch onto the engine thread — pulling from it would deadlock.
        val body = request.body?.let { b ->
            withContext(Dispatchers.IO) { Buffer().also(b::writeTo).readUtf8() }
        }
        return handler(request, body)
    }

    override fun newBuilder(): KmpHttpClient.BaseBuilder<out KmpHttpClient> =
        throw UnsupportedOperationException("CannedHttp has no builder")
}

internal fun textResponse(
    request: Request,
    text: String,
    contentType: String,
    extraHeaders: List<Pair<String, String>> = emptyList(),
): Response = ResponseBuilder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(200)
    .message("OK")
    .headers(
        HeadersBuilder().add("Content-Type", contentType)
            .apply { for ((name, value) in extraHeaders) add(name, value) }
            .build(),
    )
    .body(Buffer().write(text.encodeToByteArray()).asResponseBody(null, -1L))
    .build()

/** The ytcfg constants the engine's session bootstrap scrapes off the homepage. */
internal val HOMEPAGE_FIXTURE = """<!DOCTYPE html><html><head><script>
ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTESTKEY","INNERTUBE_CONTEXT_CLIENT_NAME":1,"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260708.00.00","VISITOR_DATA":"CgtzZXNzaW9uVGVzdA%3D%3D","PAGE_CL":700000000,"PAGE_BUILD_LABEL":"youtube.desktop_20260708","INNERTUBE_CONTEXT":{"client":{"signatureTimestamp":20260708}}});
</script></head><body></body></html>"""

/** A [Player] whose state never changes; queue/shuffle calls are recorded for assertions. */
internal class StubPlayer : Player {
    override val status: StateFlow<PlaybackStatus> = MutableStateFlow(PlaybackStatus())
    override val progress: StateFlow<Progress> = MutableStateFlow(Progress())

    /** Tests seed the queue here; [move] reorders it live so reorder UIs see their swaps land. */
    val queueState = MutableStateFlow(QueueState())
    override val queue: StateFlow<QueueState> = queueState
    override val videoQuality: StateFlow<VideoQualityState> = MutableStateFlow(VideoQualityState.DEFAULT)
    override val captions: StateFlow<CaptionState> = MutableStateFlow(CaptionState.DEFAULT)
    override val captionLines: StateFlow<List<io.ymusic.player.CaptionLine>> = MutableStateFlow(emptyList())
    override val audioTracks: StateFlow<AudioTrackState> = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioSessionId: StateFlow<Int> = MutableStateFlow(0)
    override val config: StateFlow<PlaybackConfig> = MutableStateFlow(PlaybackConfig())
    override val sleepTimerMs: StateFlow<Long?> = MutableStateFlow(null)
    override val sleepAfterCurrent: StateFlow<Boolean> = MutableStateFlow(false)

    /** Tests drive the harvest by emitting here — no YouTube engine is involved. */
    val watchNextReports = MutableSharedFlow<io.ymusic.player.WatchNextSeen>(extraBufferCapacity = 8)
    override val watchNextSeen: kotlinx.coroutines.flow.SharedFlow<io.ymusic.player.WatchNextSeen> = watchNextReports
    override val skipSegments: StateFlow<List<io.ymusic.player.SkipSegment>> = MutableStateFlow(emptyList())

    var lastQueue: List<PlayableItem>? = null
        private set
    var lastPlayIndex: Int = -1
        private set
    var lastShuffle: Boolean? = null
        private set
    val moves = mutableListOf<Pair<Int, Int>>()

    override fun setQueue(
        items: List<PlayableItem>,
        playIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
        queueId: String?,
        source: io.ymusic.player.QueueSource?,
    ) {
        lastQueue = items
        lastPlayIndex = playIndex
    }

    override fun loadMore() {}

    override fun add(items: List<PlayableItem>, at: Int?) {}

    override fun addToPlayNext(items: List<PlayableItem>) {}

    override fun move(from: Int, to: Int) {
        moves += from to to
        queueState.value = queueState.value.copy(
            items = queueState.value.items.toMutableList().also { it.add(to, it.removeAt(from)) },
        )
    }

    override fun move(fromIndex: Int, toIndex: Int, newIndex: Int) {}

    override fun removeAt(index: Int) {}

    override fun remove(fromIndex: Int, toIndex: Int) {}

    override fun clearQueue() {}

    override fun playAt(index: Int) {}

    override fun play() {}

    override fun pause() {}

    override fun togglePlay() {}

    override fun stop() {}

    override fun seekTo(positionMs: Long) {}

    override fun setSkipSegments(item: PlayableItem, segments: List<io.ymusic.player.SkipSegment>) {}

    override fun clearSkipSegments() {}

    override fun skipNext() {}

    override fun skipPrev() {}

    override fun fastForward(ms: Long) {}

    override fun rewind(ms: Long) {}

    override fun setLoopMode(mode: LoopMode) {}

    override fun setShuffle(enabled: Boolean) {
        lastShuffle = enabled
    }

    override var volume: Float = 1f

    override fun setDucked(ducked: Boolean) = Unit

    override var speed: Float = 1f

    override fun setPitch(pitch: Float) {}

    override fun setEqBands(bands: List<Float>) {}

    override fun setBassBoost(db: Float) {}

    override fun setVirtualizer(width: Float) {}

    override fun setPreamp(db: Float) {}
    override fun setInAppAudioFx(enabled: Boolean) {}

    override fun setAudioOnly(audioOnly: Boolean) {}

    override fun selectVideoQuality(quality: String) {}

    override fun selectCaption(languageCode: String?) {}

    override fun setCaptionVisible(visible: Boolean) {}

    override fun setScaleMode(mode: ScaleMode) {}

    override fun selectAudioTrack(id: String) {}

    override fun setSleepTimer(durationMs: Long?) {}

    override fun setSleepAfterCurrent(enabled: Boolean) {}

    override fun refreshOnNext() {}

    override fun reload() {}
    override fun prepareYoutubeEngine() {}

    override fun release() {}
}
