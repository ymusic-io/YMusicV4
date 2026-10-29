package io.ymusic.dynamicengine.player

import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngine
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.decodeEngineError
import io.ymusic.dynamicengine.internal.PLAYER_JS
import io.ymusic.dynamicengine.internal.installMainProxy
import io.ymusic.dynamicengine.yt.jsonCommand
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseJsEventBridge
import io.ymusic.mse.MseLogBridge
import io.ymusic.mse.MseVideoSurface
import io.ymusic.mse.NativeMseFrame
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.asBufferedSource
import io.ymusic.settings.SettingsStore
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kmphttp.json.JsonReader
import kmphttp.json.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import projectcommon.error.CodedError
import projectcommon.json.intOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/**
 * YouTube playback via JS player commands. MSE is JS + native C (sync getters). The app supplies
 * a [videoSurface] for pictures and calls [play]/[pause]/[seekTo] — those are JS, not a Kotlin MSE API.
 */
class DynamicEnginePlayer private constructor(
    val engine: DynamicEngine,
    private val nativeMse: NativeMseHost?,
    private val jsEventBridge: (String) -> Unit,
) {
    /** [freshConfig]: take the player config from a new homepage scrape, not the held session. */
    suspend fun init(
        logger: Logger? = null,
        width: Int? = null,
        height: Int? = null,
        freshConfig: Boolean = false,
    ): JsonObject {
        return engine.jsonCommand(
            "player/init",
            { w ->
                if (width != null) w.name("width").value(width)
                if (height != null) w.name("height").value(height)
                if (freshConfig) w.name("freshConfig").value(true)
            },
            logger?.fork("init", mapOf("freshConfig" to freshConfig)),
        ) { it.readObject() }
    }

    suspend fun load(
        videoId: String,
        logger: Logger? = null,
        quality: String? = null,
        width: Int? = null,
        height: Int? = null,
        audioOnly: Boolean = quality == null,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        speed: Float = 1f,
        pitch: Float = 1f,
        loop: Boolean = false,
    ): JsonObject {
        return engine.jsonCommand(
            "player/load",
            { writer ->
                writer.name("videoId").value(videoId)
                if (quality != null) writer.name("quality").value(quality)
                writer.name("audioOnly").value(audioOnly)
                if (width != null) writer.name("width").value(width)
                if (height != null) writer.name("height").value(height)
                if (startPositionMs > 0L) writer.name("startPositionMs").value(startPositionMs)
                writer.name("playWhenReady").value(playWhenReady)
                writer.name("speed").value(speed.toString())
                writer.name("pitch").value(pitch.toString())
                writer.name("loop").value(loop)
            },
            logger?.fork("load", mapOf("videoId" to videoId, "audioOnly" to audioOnly)),
        ) { it.readObject() }
    }

    /** Display hole size in CSS pixels. YouTube ABR uses this as the viewport. Default 640×360. */
    suspend fun setViewport(width: Int, height: Int, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setViewport",
            { w ->
                w.name("width").value(width)
                w.name("height").value(height)
            },
            logger,
        ) { it.skipValue() }
    }

    /** Cue the next video without interrupting current playback. */
    suspend fun preload(videoId: String, logger: Logger? = null): JsonObject {
        return engine.jsonCommand(
            "player/preload",
            { w -> w.name("videoId").value(videoId) },
            logger?.fork("preload", mapOf("videoId" to videoId)),
        ) { it.readObject() }
    }

    /** YouTube quality token (`auto`, `hd720`, …) and audio-only, applied together. */
    suspend fun setQuality(quality: String, audioOnly: Boolean, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setQuality",
            { w ->
                w.name("quality").value(quality)
                w.name("audioOnly").value(audioOnly)
            },
            logger,
        ) { it.skipValue() }
    }

    /** Switches to one of the catalog's `audioTracks` (a dub or the original language) by its `id`. */
    suspend fun setAudioTrack(id: String, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setAudioTrack",
            { w -> w.name("id").value(id) },
            logger,
        ) { it.skipValue() }
    }

    suspend fun setLoop(enable: Boolean, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setLoop",
            { w -> w.name("enable").value(enable) },
            logger,
        ) { it.skipValue() }
    }

    suspend fun setSpeed(speed: Float, tempo: Float = speed, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setSpeed",
            { w ->
                w.name("speed").value(speed.toString())
                w.name("tempo").value(tempo.toString())
            },
            logger,
        ) { it.skipValue() }
    }

    suspend fun setSpeedPitch(speed: Float, pitch: Float, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setSpeedPitch",
            { w ->
                w.name("speed").value(speed.toString())
                w.name("pitch").value(pitch.toString())
            },
            logger,
        ) { it.skipValue() }
    }

    suspend fun play(logger: Logger? = null) {
        engine.jsonCommand("player/play", { }, logger) { it.skipValue() }
    }

    suspend fun pause(logger: Logger? = null) {
        engine.jsonCommand("player/pause", { }, logger) { it.skipValue() }
    }

    suspend fun seekTo(positionMs: Long, logger: Logger? = null) {
        engine.jsonCommand("player/seek", { w -> w.name("positionMs").value(positionMs) }, logger) { it.skipValue() }
    }

    suspend fun stop(logger: Logger? = null) {
        engine.jsonCommand("player/stop", { }, logger) { it.skipValue() }
    }

    suspend fun status(logger: Logger? = null): JsonObject {
        return engine.jsonCommand("player/status", { }, logger) { it.readObject() }
    }

    /** Subscribe to event groups (`progress` / `state` / `picture`) until cancelled. */
    suspend fun collectStatus(onStatus: (JsonObject) -> Unit, logger: Logger? = null) {
        collectJsonPipe("listen/player/status", onStatus, logger)
    }

    /** Subscribe to qualities + caption until cancelled. Host close unsubscribes. */
    suspend fun collectCatalog(onCatalog: (JsonObject) -> Unit, logger: Logger? = null) {
        collectJsonPipe("listen/player/catalog", onCatalog, logger)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun collectJsonPipe(
        command: String,
        onObj: (JsonObject) -> Unit,
        logger: Logger?,
    ) {
        val pipe = engine.openPipe(command)
        try {
            val reader = JsonReader(pipe.source.asBufferedSource())
            while (true) {
                val obj = try {
                    reader.readObject()
                } catch (_: JsonSyntaxException) {
                    break
                } catch (e: PipeResetException) {
                    throw decodeEngineError(e.reason, e)
                }
                onObj(obj)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    suspend fun captionTracks(logger: Logger? = null): List<CaptionTrackInfo> {
        val o = engine.jsonCommand("player/captions", { }, logger) { it.readObject() }
        return o.objects("tracks").mapNotNull { t ->
            val vss = t.stringOrNull("vssId") ?: return@mapNotNull null
            CaptionTrackInfo(
                vssId = vss,
                languageCode = t.stringOrNull("languageCode") ?: "",
                kind = t.stringOrNull("kind") ?: "",
                displayName = t.stringOrNull("displayName") ?: vss,
            )
        }
    }

    /**
     * Captions on/off plus the preferred track: [vssId] may also be a language code. JS keeps the
     * preference across videos and reports the actual pick on the catalog channel.
     */
    suspend fun setCaption(vssId: String?, logger: Logger? = null, visible: Boolean = vssId != null) {
        engine.jsonCommand(
            "player/setCaption",
            { writer ->
                if (vssId == null) writer.name("vssId").nullValue() else writer.name("vssId").value(vssId)
                writer.name("visible").value(visible)
            },
            logger,
        ) { it.skipValue() }
    }

    /** Subscribe to paint-on lines until cancelled. Host close pauses JS caption work. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun collectCaptionLines(onLines: (List<CaptionLine>) -> Unit, logger: Logger? = null) {
        val pipe = engine.openPipe("player/caption")
        try {
            val reader = JsonReader(pipe.source.asBufferedSource())
            while (true) {
                val obj = try {
                    reader.readObject()
                } catch (_: JsonSyntaxException) {
                    break
                } catch (e: PipeResetException) {
                    throw decodeEngineError(e.reason, e)
                }
                onLines(obj.captionLines())
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    /** /player JSON plus decoded SABR URL captured from the player's googlevideo request. */
    suspend fun cachedPlayerResponse(videoId: String, logger: Logger? = null): JsonObject =
        engine.jsonCommand(
            "player/playerResponse",
            { w -> w.name("videoId").value(videoId) },
            logger,
        ) { it.readObject() }

    suspend fun mintContentPoToken(videoId: String, logger: Logger? = null): String =
        engine.jsonCommand("player/mintPo", { w -> w.name("videoId").value(videoId) }, logger) { r ->
            val o = r.readObject()
            val token = o.stringOrNull("contentPoToken").orEmpty()
            if (token.length < 20) error("missing contentPoToken")
            token
        }

    /** Android TextureView surface, iOS layer. The JVM binds the surface's presenter when its host is set. */
    fun setVideoSurface(surface: MseVideoSurface?) {
        nativeMse?.setVideoSurface(surface)
    }

    fun mseFrames(handle: Long): Int = MseHostNative.frames(handle)

    fun mseCopyFrame(handle: Long, afterGen: Int = -1): NativeMseFrame? =
        MseHostNative.copyFrame(handle, afterGen)

    suspend fun close() {
        // Best-effort, like the manager's retire: a refused or slow shutdown is backstopped by the
        // hard close below. Wall-clock bound (Dispatchers.Default) so test virtual time can't skip it.
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(SHUTDOWN_GRACE_MS) { runCatching { engine.signalShutdown() } }
        }
        MseJsEventBridge.clear(jsEventBridge)
        /* Stop JS (__mse*) before freeing the C host. */
        engine.close()
        nativeMse?.close()
    }

    companion object {
        /** How long the player JS gets to release what it holds (e.g. bg_dump's open put). */
        private const val SHUTDOWN_GRACE_MS = 1_500L

        fun create(
            client: KmpHttpClient,
            appVersion: Int,
            dispatcher: CoroutineDispatcher,
            logger: Logger? = null,
            engineJs: String = PLAYER_JS,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            videoSurface: MseVideoSurface? = null,
            main: DynamicEngineManager? = null,
            /** The health-checked player bundle's revision, once per session. */
            onBackendVersion: ((Int) -> Unit)? = null,
        ): DynamicEnginePlayer {
            var nativeMse: NativeMseHost? = null
            val engine = try {
                DynamicEngine.createEngine(
                    client, appVersion, dispatcher, logger, engineJs, cacheStore, cookieJar, settings,
                ) { parked ->
                    if (videoSurface != null) nativeMse = bindNativeMse(this, videoSurface)
                    installMainProxy(parked) { cmd ->
                        val mgr = main ?: throw NoMainEngine()
                        mgr.use { it.openPipe(cmd) }
                    }
                }
            } catch (t: Throwable) {
                runCatching { nativeMse?.close() }.exceptionOrNull()?.let(t::addSuppressed)
                throw t
            }
            try {
                onBackendVersion?.invoke(engine.healthCheck())
            } catch (t: Throwable) {
                /* JS stops before the C host it calls into is freed. */
                runCatching { engine.closeNow() }.exceptionOrNull()?.let(t::addSuppressed)
                runCatching { nativeMse?.close() }.exceptionOrNull()?.let(t::addSuppressed)
                throw t
            }
            logger?.bindMseNativeLogs()
            val jsEventBridge: (String) -> Unit = { eventName -> engine.fire(eventName) }
            MseJsEventBridge.setJsEventBridge(jsEventBridge)
            return DynamicEnginePlayer(engine, nativeMse, jsEventBridge)
        }
    }
}

internal interface NativeMseHost {
    fun setVideoSurface(surface: MseVideoSurface?)
    fun setSpeed(speed: Float, tempo: Float)
    fun close()
}

internal expect fun bindNativeMse(runtime: io.ymusic.quickjs.QuickJsRuntime, surface: MseVideoSurface): NativeMseHost

/** Process-wide C→Logger hop. Safe to call more than once; last logger wins. */
fun Logger.bindMseNativeLogs() {
    val mse = fork("mse")
    MseLogBridge.setLogBridge { level, message ->
        when (level) {
            0 -> mse.debug { message }
            2 -> mse.log(Logger.Level.WARN, message)
            3 -> mse.log(Logger.Level.ERROR, message)
            else -> mse.log(Logger.Level.INFO, message)
        }
    }
}

internal class NoMainEngine : Exception("no main engine"), CodedError {
    override val code = "E20078"
    override val userMessage: String? = null
}

data class CaptionTrackInfo(
    val vssId: String,
    val languageCode: String,
    val kind: String,
    val displayName: String,
)

data class CaptionLine(
    val id: Int,
    val text: String,
)

private fun JsonObject.captionLines(): List<CaptionLine> {
    return objects("lines").mapNotNull { o ->
        val id = o.intOrNull("id") ?: return@mapNotNull null
        CaptionLine(id = id, text = o.stringOrNull("text").orEmpty())
    }
}
