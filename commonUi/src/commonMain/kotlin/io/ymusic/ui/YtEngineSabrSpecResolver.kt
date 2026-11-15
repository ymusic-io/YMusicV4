package io.ymusic.ui

import io.ymusic.download.ResolvedVideo
import io.ymusic.download.SabrSpecBuilder
import io.ymusic.download.SabrSpecResolver
import io.ymusic.download.SpecRemint
import io.ymusic.dynamicengine.player.ContentPoRemint
import io.ymusic.player.YtEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Obtains SABR inputs for a download from a [YtEngine] (`MsePlayer`).
 * `requestPlayerResponse` returns cached `/player` + a decoded SABR URL the player already
 * fetched, or preloads the video so those requests fire. `requestPoToken` mints a content PO.
 *
 * Hold ONE instance per app: [mutex] serializes requests against the single player, so a resolver
 * built per call would serialize nothing. [engine] prepares and returns the YouTube engine.
 */
class YtEngineSabrSpecResolver(
    private val engine: suspend () -> YtEngine,
) : SabrSpecResolver {

    private val mutex = Mutex()

    override suspend fun resolve(videoId: String): ResolvedVideo = mutex.withLock {
        val player = engine()
        withContext(Dispatchers.Main) { player.init() }
        val playerResponse = player.requestPlayerResponse(videoId)
        val poToken = player.requestPoToken(videoId)
        SabrSpecBuilder.resolve(videoId, playerResponse, poToken)
    }

    override suspend fun remint(videoId: String): SpecRemint = mutex.withLock {
        val player = engine()
        withContext(Dispatchers.Main) { player.init() }
        when (val result = player.remintPoToken(videoId)) {
            is ContentPoRemint.Minted ->
                SpecRemint.Minted(SabrSpecBuilder.resolve(videoId, player.requestPlayerResponse(videoId), result.token))
            ContentPoRemint.PlayerAccepted -> SpecRemint.PlayerAccepted
            ContentPoRemint.Unavailable -> SpecRemint.Unavailable
        }
    }

    /** The player response alone — what the download options dialog reads; no PO token minted. */
    suspend fun playerResponse(videoId: String): String = mutex.withLock {
        val player = engine()
        withContext(Dispatchers.Main) { player.init() }
        player.requestPlayerResponse(videoId)
    }
}
