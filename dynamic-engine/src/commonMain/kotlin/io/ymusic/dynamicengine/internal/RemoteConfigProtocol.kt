package io.ymusic.dynamicengine.internal

import io.ymusic.dynamicengine.WireError
import io.ymusic.quickjs.BrokenPipeException
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.quickjs.asAsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmpcommon.log.Logger
import kmphttp.json.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import projectcommon.json.stringOrNull

internal fun QuickJsRuntime.installRemoteConfig(
    feature: (suspend (String) -> JsonElement?)?,
    parked: ParkedLoggers,
) {
    onAccept("remote-config/get") { pipe ->
        val log = parked.root?.fork("remote-config/get")
        try {
            val head = JsonReader(BufferedAsyncSource(pipe.source.asAsyncSource())).readObject()
            val key = requireNotNull(head.stringOrNull("key")) { "remote-config request missing 'key'" }
            val out = Buffer().writeUtf8(
                buildJsonObject { feature?.invoke(key)?.let { put("value", it) } }.toString(),
            )
            pipe.sink.send(out, out.size)
            pipe.sink.close()
        } catch (_: PipeResetException) {
        } catch (_: BrokenPipeException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log?.log(Logger.Level.WARN, "E20062 remote-config/get: ${e.message}", e)
            val wire = WireError(code = "E20062", message = "remote-config/get: ${e.message}")
            runCatching { pipe.sink.close(Json.encodeToString(wire)) }.exceptionOrNull()?.let(e::addSuppressed)
        } finally {
            pipe.source.close()
        }
    }
}
