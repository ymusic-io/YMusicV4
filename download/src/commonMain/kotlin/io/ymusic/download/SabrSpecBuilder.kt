package io.ymusic.download

import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.ClientInfo
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import io.ymusic.ytmedia.sabr.toAdaptiveFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.decodeBase64
import projectcommon.json.boolOrNull
import projectcommon.json.doubleOrNull
import projectcommon.json.intOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/**
 * Turns the player-response JSON (from `YtEngine.requestPlayerResponse`) plus a PO token
 * (from `requestPoToken`) into a [SabrStreamSpec] for `SabrSession.download`. Pure data mapping.
 * `serverAbrStreamingUrl` already has sig/n decoded (player fetch, not a local decipher).
 */
object SabrSpecBuilder {

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws IllegalStateException when the response isn't a SABR stream (no `serverAbrStreamingUrl`). */
    fun build(videoId: String, playerResponseJson: String, poTokenBase64: String?): SabrStreamSpec {
        val data = json.parseToJsonElement(playerResponseJson).jsonObject
        val streaming = data.objectOrNull("streamingData") ?: error("no streamingData for $videoId")
        val sabrUrl = streaming.stringOrNull("serverAbrStreamingUrl")
            ?: error("no serverAbrStreamingUrl (SABR-only stream required) for $videoId")
        val ustreamer = data.stringOrNull("videoPlaybackUstreamerConfig")
            ?: error("no videoPlaybackUstreamerConfig for $videoId")
        val clientVersion = data.stringOrNull("clientVersion")
        val clientName = data.intOrNull("clientNameId") ?: 1
        checkProcessed(data, streaming)
        val formats = adaptiveFormats(streaming)
        val captions = captionEntries(data).map { it.toTrack(poTokenBase64) }
        return SabrStreamSpec(
            videoId = videoId,
            sabrUrl = sabrUrl,
            ustreamerConfig = ustreamer.decodeBase64Bytes(),
            poToken = poTokenBase64?.takeIf { it.isNotBlank() }?.decodeBase64Bytes(),
            clientInfo = ClientInfo(clientName = clientName, clientVersion = clientVersion, osName = "Windows", osVersion = "10.0"),
            formats = formats,
            captionTracks = captions,
        )
    }

    /** [build], plus the artist tag (`videoDetails.author`, which the engine already resolved to the track
     *  artist) and the video's loudness target. */
    fun resolve(videoId: String, playerResponseJson: String, poTokenBase64: String?): ResolvedVideo {
        val data = json.parseToJsonElement(playerResponseJson).jsonObject
        return ResolvedVideo(
            spec = build(videoId, playerResponseJson, poTokenBase64),
            artist = data.objectOrNull("videoDetails")?.stringOrNull("author")?.takeIf { it.isNotBlank() },
            loudnessTargetLkfs = data.objectOrNull("audioConfig")?.doubleOrNull("loudnessTargetLkfs"),
        )
    }

    /** @throws LiveVideoException for a stream that is live now.
     *  @throws StillProcessingException when formats lack `lastModified` (a replay or upload YouTube hasn't finished processing). */
    internal fun checkProcessed(data: JsonObject, streaming: JsonObject) {
        val details = data.objectOrNull("videoDetails")
        if (details?.boolOrNull("isLive") == true) throw LiveVideoException(details.stringOrNull("videoId"))
        if (streaming.objects("adaptiveFormats").any { "lastModified" !in it }) {
            throw StillProcessingException(details?.stringOrNull("videoId"))
        }
    }

    internal fun adaptiveFormats(streaming: JsonObject): List<AdaptiveFormat> =
        streaming.objects("adaptiveFormats").map { it.toAdaptiveFormat() }

    /** Decode standard or URL-safe base64 (YouTube mixes both); okio handles both alphabets. */
    private fun String.decodeBase64Bytes(): ByteArray =
        (decodeBase64() ?: trim().decodeBase64())?.toByteArray()
            ?: error("invalid base64")
}
