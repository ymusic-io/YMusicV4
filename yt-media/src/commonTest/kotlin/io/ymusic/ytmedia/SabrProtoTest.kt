package io.ymusic.ytmedia

import io.ymusic.ytmedia.proto.ProtoReader
import io.ymusic.ytmedia.proto.ProtoWriter
import io.ymusic.ytmedia.sabr.BufferedRange
import io.ymusic.ytmedia.sabr.ClientAbrState
import io.ymusic.ytmedia.sabr.ClientInfo
import io.ymusic.ytmedia.sabr.FormatId
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.NextRequestPolicy
import io.ymusic.ytmedia.sabr.SabrContextUpdate
import io.ymusic.ytmedia.sabr.SabrParts
import io.ymusic.ytmedia.sabr.StreamerContext
import io.ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import io.ymusic.ytmedia.sabr.formatCacheKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SabrProtoTest {

    private fun parse(writer: ProtoWriter) =
        ProtoReader(writer.buffer, writer.size).readAllFields()

    @Test
    fun video_playback_abr_request_roundtrip() {
        val audio = FormatId(itag = 251, lmt = 1_700_000_000_123_456L)
        val video = FormatId(itag = 247, lmt = 1_700_000_000_654_321L, xtag = "xt")
        val request = VideoPlaybackAbrRequest(
            clientAbrState = ClientAbrState(
                playerTimeMs = 30_001,
                enabledTrackTypesBitfield = 1,
                drcEnabled = true,
                viewportWidth = 640,
                viewportHeight = 360,
                timeSinceLastSeek = 17,
                visibility = 0,
                elapsedWallTimeMs = 12_345,
                timeSinceLastAction = 9,
            ),
            selectedFormats = listOf(audio),
            bufferedRanges = listOf(
                BufferedRange(audio, startMs = 0, durationMs = 30_000, startSegmentIndex = 1, endSegmentIndex = 6),
            ),
            selectedAudioFormats = listOf(audio),
            selectedVideoFormats = listOf(video),
            streamerContext = StreamerContext(
                clientInfo = ClientInfo(clientName = 1, clientVersion = "2.20250221.00.00", osName = "Android"),
                poToken = byteArrayOf(1, 2, 3, 4),
                playbackCookie = byteArrayOf(9, 8),
                sabrContextUpdates = listOf(SabrContextUpdate(type = 3, value = byteArrayOf(5, 5))),
            ),
            ustreamerConfig = byteArrayOf(42, 43, 44),
        )

        val decoded = VideoPlaybackAbrRequest.from(parse(request.encode()))

        assertEquals(30_001, decoded.clientAbrState.playerTimeMs)
        assertEquals(1, decoded.clientAbrState.enabledTrackTypesBitfield)
        assertTrue(decoded.clientAbrState.drcEnabled)
        assertEquals(640, decoded.clientAbrState.viewportWidth)
        assertEquals(17, decoded.clientAbrState.timeSinceLastSeek)
        assertEquals(listOf(audio), decoded.selectedFormats)
        assertEquals(listOf(audio), decoded.selectedAudioFormats)
        assertEquals(listOf(video), decoded.selectedVideoFormats)
        val range = decoded.bufferedRanges.single()
        assertEquals(audio, range.formatId)
        assertEquals(30_000, range.endMs)
        assertEquals(6, range.endSegmentIndex)
        assertEquals(1, decoded.streamerContext.clientInfo?.clientName)
        assertEquals("2.20250221.00.00", decoded.streamerContext.clientInfo?.clientVersion)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), decoded.streamerContext.poToken)
        assertContentEquals(byteArrayOf(9, 8), decoded.streamerContext.playbackCookie)
        val update = decoded.streamerContext.sabrContextUpdates.single()
        assertEquals(3, update.type)
        assertContentEquals(byteArrayOf(5, 5), update.value)
        assertContentEquals(byteArrayOf(42, 43, 44), decoded.ustreamerConfig)
        // private extensions are parse-only
        assertTrue(decoded.adaptiveFormats.isEmpty())
        assertEquals("", decoded.videoId)
    }

    @Test
    fun media_header_roundtrip_and_init_segment_rules() {
        val segment = MediaHeader(
            headerId = 2,
            videoId = "dQw4w9WgXcQ",
            itag = 251,
            lmt = 1_700_000_000_123_456L,
            xtag = null,
            startRange = 524_288,
            isInitSegment = false,
            segmentIndex = 7,
            startMs = 30_000,
            durationMs = 5_000,
            contentLength = 81_920,
        )
        assertEquals(segment, MediaHeader.from(parse(segment.encode())))
        assertEquals(formatCacheKey(251, 1_700_000_000_123_456L), segment.cacheKey)

        val init = segment.copy(isInitSegment = true, segmentIndex = 0, startMs = null, durationMs = null, startRange = 0)
        val decodedInit = MediaHeader.from(parse(init.encode()))
        assertTrue(decodedInit.isInitSegment)
        assertEquals(0, decodedInit.segmentIndex)
        // start/duration are suppressed for init segments even if set
        assertNull(MediaHeader.from(parse(init.copy(startMs = 1L, durationMs = 2L).encode())).startMs)
    }

    @Test
    fun gold_live_media_header_omits_lmt_range_dur_clen() {
        val live = ProtoWriter()
            .varint(1, 0)
            .string(2, "BEtsctac5KQ")
            .varint(3, 243)
            .varint(9, 9084)
            .varint(11, 9_084_167)
            .message(13, ProtoWriter().varint(1, 243))
            .message(15, ProtoWriter().varint(1, 9_084_167L * 1_000_000L).varint(3, 1_000_000_000L))
            .varint(16, 1)
        val h = MediaHeader.from(parse(live))
        assertEquals("BEtsctac5KQ", h.videoId)
        assertEquals(243, h.itag)
        assertEquals(0L, h.lmt)
        assertEquals(0L, h.startRange)
        assertEquals(false, h.isInitSegment)
        assertEquals(9084, h.segmentIndex)
        assertEquals(9_084_167L, h.startMs)
        assertEquals(null, h.durationMs)
        assertEquals(0L, h.contentLength)
    }

    @Test
    fun next_request_policy_roundtrip() {
        val policy = NextRequestPolicy(
            targetAudioReadaheadMs = 60_000,
            targetVideoReadaheadMs = 60_000,
            backoffTimeMs = 2_000,
            playbackCookie = byteArrayOf(1, 2),
            videoId = "abc",
        )
        val decoded = NextRequestPolicy.from(parse(policy.encode()))
        assertEquals(60_000, decoded.targetAudioReadaheadMs)
        assertEquals(2_000, decoded.backoffTimeMs)
        assertContentEquals(byteArrayOf(1, 2), decoded.playbackCookie)
        assertEquals("abc", decoded.videoId)
    }

    // Part 57 arrives as {type=1, scope=2, value=3, sendByDefault=4} and must go back out
    // as {type=1, value=2} inside StreamerContext.
    @Test
    fun sabr_context_update_response_to_request_shape() {
        val responsePayload = ProtoWriter()
            .varint(1, 5)
            .varint(2, 2)
            .bytes(3, byteArrayOf(7, 7, 7))
            .varint(4, 1)
        val update = SabrContextUpdate.fromUmpResponse(parse(responsePayload))
        assertEquals(5, update.type)
        assertEquals(2, update.scope)
        assertEquals(true, update.sendByDefault)

        val reEncoded = SabrContextUpdate.fromRequestBody(parse(update.encode()))
        assertEquals(5, reEncoded.type)
        assertContentEquals(byteArrayOf(7, 7, 7), reEncoded.value)
    }

    @Test
    fun control_part_payload_parsers() {
        val error = ProtoWriter().string(1, "compliance check failed")
        assertEquals("compliance check failed", SabrParts.parseErrorMessage(error.buffer.readByteArray()))

        val redirect = ProtoWriter().string(1, "https://r2---sn.googlevideo.com/videoplayback?x=1")
        assertEquals("https://r2---sn.googlevideo.com/videoplayback?x=1", SabrParts.parseRedirectUrl(redirect.buffer.readByteArray()))

        val protection = ProtoWriter().varint(1, 3)
        assertEquals(3, SabrParts.parseStreamProtectionStatus(protection.buffer.readByteArray()))

        // malformed payloads are survivable
        assertNull(SabrParts.parseErrorMessage(byteArrayOf(-1, -1, -1)))
        assertNull(SabrParts.parseStreamProtectionStatus(byteArrayOf(-1)))
    }
}
