package io.ymusic.ui.sponsorblock

import kmphttp.HttpStatusException
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.toHttpUrlOrNull
import kmphttp.utf8String
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import okio.use
import projectcommon.json.arrayOrNull
import projectcommon.json.doubleOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/** The public SponsorBlock API; only a 4-character SHA-256 prefix of the video id leaves the device. */
class PublicSponsorBlockApi(private val client: KmpHttpClient) : SponsorBlockApi {
    override suspend fun getSkipSegments(videoId: String): GetSkipSegmentsResponse {
        val prefix = videoId.encodeUtf8().sha256().hex().take(HASH_PREFIX_LENGTH)
        val url = requireNotNull("$ENDPOINT/$prefix".toHttpUrlOrNull()) { "not a url: $ENDPOINT" }
            .newBuilder()
            .addQueryParameter("categories", CATEGORIES)
            .addQueryParameter("actionTypes", ACTION_TYPES)
            .build()
        val body = client.execute(RequestBuilder().url(url).get().build()).use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw HttpStatusException(response.code)
                else -> response.body.utf8String()
            }
        } ?: return GetSkipSegmentsResponse(emptyList())
        val entries = Json.parseToJsonElement(body) as? JsonArray ?: return GetSkipSegmentsResponse(emptyList())
        val segments = entries
            .filterIsInstance<JsonObject>()
            .filter { it.stringOrNull("videoID") == videoId }
            .flatMap { it.objects("segments") }
            .mapNotNull(::toSegment)
            .sortedBy { it.startTime }
        return GetSkipSegmentsResponse(segments)
    }

    private fun toSegment(segment: JsonObject): SponsorBlockSegment? {
        val range = segment.arrayOrNull("segment") ?: return null
        val start = (range.getOrNull(0) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val end = (range.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val category = segment.stringOrNull("category") ?: return null
        return SponsorBlockSegment(start, end, category, segment.doubleOrNull("videoDuration") ?: 0.0)
    }

    private companion object {
        const val ENDPOINT = "https://sponsor.ajay.app/api/skipSegments"
        const val HASH_PREFIX_LENGTH = 4
        const val CATEGORIES = """["sponsor","selfpromo","interaction","intro","outro","music_offtopic"]"""
        const val ACTION_TYPES = """["skip"]"""
    }
}
