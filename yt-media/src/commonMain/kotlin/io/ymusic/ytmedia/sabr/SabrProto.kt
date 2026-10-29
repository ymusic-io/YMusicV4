package io.ymusic.ytmedia.sabr

import io.ymusic.ytmedia.proto.ProtoField
import io.ymusic.ytmedia.proto.ProtoWriter
import io.ymusic.ytmedia.proto.bytes
import io.ymusic.ytmedia.proto.message
import io.ymusic.ytmedia.proto.messages
import io.ymusic.ytmedia.proto.string
import io.ymusic.ytmedia.proto.varint

// Ported from legacy player2/SabrProto.kt — field numbers are the wire truth, see
// docs/player/sabr-protocol.md §3 for the full schema.

/** Groups cached/downloaded bytes per format stream; same string as legacy's cache key v2. */
fun formatCacheKey(itag: Int, lmt: Long): String = "2_${itag}_$lmt"

// optional int32 itag = 1;
// optional uint64 last_modified = 2;
// optional string xtags = 3;
data class FormatId(
    val itag: Int,
    val lmt: Long,
    val xtag: String = "",
) {
    companion object {
        fun from(fields: List<ProtoField>): FormatId {
            val itag = checkNotNull(fields.varint(1)).toInt()
            return FormatId(
                itag = itag,
                lmt = if (itag == 0) 0 else checkNotNull(fields.varint(2)),
                xtag = fields.string(3) ?: "",
            )
        }
    }

    fun encode() = ProtoWriter()
        .varint(1, itag)
        .varint(2, lmt)
        .string(3, xtag)

    val cacheKey get() = formatCacheKey(itag, lmt)
}

/**
 * One entry of `streamingData.adaptiveFormats` — metadata the protocol consumes. Supplied by the
 * caller from the player response; [from] parses the private request-body extension (field 568)
 * legacy's player shim injects.
 */
data class AdaptiveFormat(
    val itag: Int,
    val lmt: Long,
    val xtag: String?,
    val mimeType: String,
    val clen: Long,
    val indexRangeStart: Long,
    val indexRangeEnd: Long,
    val width: Int?,
    val height: Int?,
    val approxDurationMs: Int,
    val audioTrackIsDefault: Boolean?,
    val audioTrackId: String?,
    val isDrc: Boolean,
) {
    companion object {
        fun from(fields: List<ProtoField>) = AdaptiveFormat(
            itag = checkNotNull(fields.varint(1)).toInt(),
            lmt = fields.varint(2) ?: 0L,
            xtag = fields.string(3),
            mimeType = fields.string(4) ?: "",
            /* Live adaptiveFormats omit clen / indexRange / approxDurationMs. */
            clen = fields.varint(5) ?: 0L,
            indexRangeStart = fields.varint(6) ?: 0L,
            indexRangeEnd = fields.varint(7) ?: 0L,
            width = fields.varint(8)?.toInt()?.takeIf { it > 0 },
            height = fields.varint(9)?.toInt()?.takeIf { it > 0 },
            approxDurationMs = fields.varint(10)?.toInt() ?: 0,
            audioTrackIsDefault = fields.varint(11)?.let { it == 1L },
            audioTrackId = fields.string(12),
            isDrc = fields.varint(13) == 1L,
        )
    }

    val mimeTypeWithoutCodec get() = mimeType.split(";").first()
    val cacheKey get() = formatCacheKey(itag, lmt)
    val isVideo get() = mimeType.startsWith("video")
    fun toFormatId() = FormatId(itag, lmt, xtag ?: "")
}

data class ClientAbrState(
    val playerTimeMs: Long,
    // 0 = audio+video, 1 = audio only (server sends no video segments)
    val enabledTrackTypesBitfield: Int?,
    val drcEnabled: Boolean = false,
    val requestAudioTrackId: String? = null,
    // min(width, height); null/0 = server picks
    val requestedVideoResolution: Int? = null,
    val timeSinceLastManualFormatSelectionMs: Long? = null,
    val lastManualDirection: Int? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    // must be > 0
    val timeSinceLastSeek: Int? = null,
    val visibility: Int? = null,
    val elapsedWallTimeMs: Int? = null,
    val timeSinceLastAction: Int? = null,
) {
    companion object {
        fun from(fields: List<ProtoField>) = ClientAbrState(
            playerTimeMs = checkNotNull(fields.varint(28)),
            enabledTrackTypesBitfield = fields.varint(40)?.toInt(),
            drcEnabled = fields.varint(46) == 1L,
            requestAudioTrackId = fields.string(69),
            requestedVideoResolution = fields.varint(16)?.toInt(),
            timeSinceLastManualFormatSelectionMs = fields.varint(13),
            lastManualDirection = fields.varint(14)?.toInt(),
            viewportWidth = fields.varint(18)?.toInt(),
            viewportHeight = fields.varint(19)?.toInt(),
            timeSinceLastSeek = fields.varint(29)?.toInt(),
            visibility = fields.varint(34)?.toInt(),
            elapsedWallTimeMs = fields.varint(36)?.toInt(),
            timeSinceLastAction = fields.varint(39)?.toInt(),
        )
    }

    fun encode() = ProtoWriter()
        .varint(28, playerTimeMs)
        .varint(40, enabledTrackTypesBitfield)
        .varint(46, if (drcEnabled) 1 else null)
        .string(69, requestAudioTrackId)
        .varint(13, timeSinceLastManualFormatSelectionMs)
        .varint(14, lastManualDirection)
        .varint(16, requestedVideoResolution)
        .varint(18, viewportWidth)
        .varint(19, viewportHeight)
        .varint(29, timeSinceLastSeek)
        .varint(34, visibility)
        .varint(36, elapsedWallTimeMs)
        .varint(39, timeSinceLastAction)
}

// required FormatId format_id = 1;
// required int64 start_time_ms = 2;
// required int64 duration_ms = 3;
// required int32 start_segment_index = 4;
// required int32 end_segment_index = 5;
data class BufferedRange(
    val formatId: FormatId,
    val startMs: Long,
    val durationMs: Long,
    val startSegmentIndex: Int,
    val endSegmentIndex: Int,
) {
    val endMs get() = startMs + durationMs - 1

    companion object {
        fun from(fields: List<ProtoField>) = BufferedRange(
            formatId = FormatId.from(checkNotNull(fields.message(1))),
            startMs = checkNotNull(fields.varint(2)),
            durationMs = checkNotNull(fields.varint(3)),
            startSegmentIndex = checkNotNull(fields.varint(4)).toInt(),
            endSegmentIndex = checkNotNull(fields.varint(5)).toInt(),
        )
    }

    fun encode() = ProtoWriter()
        .message(1, formatId.encode())
        .varint(2, startMs)
        .varint(3, durationMs)
        .varint(4, startSegmentIndex)
        .varint(5, endSegmentIndex)
}

class ClientInfo(
    val manufacturer: String? = null,
    val deviceModel: String? = null,
    val clientName: Int? = null,
    val clientVersion: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
) {
    companion object {
        fun from(fields: List<ProtoField>) = ClientInfo(
            manufacturer = fields.string(12),
            deviceModel = fields.string(13),
            clientName = fields.varint(16)?.toInt(),
            clientVersion = fields.string(17),
            osName = fields.string(18),
            osVersion = fields.string(19),
        )
    }

    fun encode() = ProtoWriter()
        .string(12, manufacturer)
        .string(13, deviceModel)
        .varint(16, clientName)
        .string(17, clientVersion)
        .string(18, osName)
        .string(19, osVersion)
}

class SabrContextUpdate(
    val type: Int,
    val value: ByteArray,
    val scope: Int? = null,
    val sendByDefault: Boolean? = null,
) {
    companion object {
        fun fromRequestBody(fields: List<ProtoField>) = SabrContextUpdate(
            type = checkNotNull(fields.varint(1)).toInt(),
            value = checkNotNull(fields.bytes(2)),
        )

        fun fromUmpResponse(fields: List<ProtoField>) = SabrContextUpdate(
            type = checkNotNull(fields.varint(1)).toInt(),
            value = checkNotNull(fields.bytes(3)),
            scope = fields.varint(2)?.toInt(),
            sendByDefault = fields.varint(4)?.let { it != 0L },
        )
    }

    // request-body shape: the response's `value`(3) goes back as field 2
    fun encode() = ProtoWriter()
        .varint(1, type)
        .bytes(2, value)
}

// optional ClientInfo client_info = 1;
// optional bytes po_token = 2;
// optional bytes playback_cookie = 3;
// repeated SabrContextUpdate sabr_contexts = 5;
class StreamerContext(
    val clientInfo: ClientInfo?,
    val poToken: ByteArray?,
    val playbackCookie: ByteArray?,
    val sabrContextUpdates: List<SabrContextUpdate> = emptyList(),
) {
    companion object {
        fun from(fields: List<ProtoField>) = StreamerContext(
            clientInfo = fields.message(1)?.let(ClientInfo::from),
            poToken = fields.bytes(2),
            playbackCookie = fields.bytes(3),
            sabrContextUpdates = fields.messages(5).map(SabrContextUpdate::fromRequestBody),
        )
    }

    fun encode() = ProtoWriter()
        .message(1, clientInfo?.encode())
        .bytes(2, poToken)
        .bytes(3, playbackCookie)
        .messages(5, sabrContextUpdates.map { it.encode() })
}

// optional ClientAbrState client_abr_state = 1;
// repeated FormatId selected_format_ids = 2;          // initialized formats
// repeated BufferedRange buffered_ranges = 3;         // what the client has per itag
// optional bytes video_playback_ustreamer_config = 5;
// repeated FormatId selected_audio_format_ids = 16;   // playable audio formats
// repeated FormatId selected_video_format_ids = 17;   // playable video formats
// optional StreamerContext streamer_context = 19;
class VideoPlaybackAbrRequest(
    val clientAbrState: ClientAbrState,
    val selectedFormats: List<FormatId>,
    val bufferedRanges: List<BufferedRange>,
    val selectedAudioFormats: List<FormatId>,
    val selectedVideoFormats: List<FormatId>,
    val streamerContext: StreamerContext,
    val ustreamerConfig: ByteArray?,
    // 568/569 are the legacy WebView shim's private fields (parsed, never encoded). MSE sends the
    // catalog as [ADAPTIVE_FORMATS_HEADER] instead; see [withAdaptiveFormats].
    val adaptiveFormats: List<AdaptiveFormat> = emptyList(),
    val videoId: String = "",
) {
    fun withAdaptiveFormats(formats: List<AdaptiveFormat>) = VideoPlaybackAbrRequest(
        clientAbrState = clientAbrState,
        selectedFormats = selectedFormats,
        bufferedRanges = bufferedRanges,
        selectedAudioFormats = selectedAudioFormats,
        selectedVideoFormats = selectedVideoFormats,
        streamerContext = streamerContext,
        ustreamerConfig = ustreamerConfig,
        adaptiveFormats = formats,
        videoId = videoId,
    )

    companion object {
        fun from(fields: List<ProtoField>) = VideoPlaybackAbrRequest(
            clientAbrState = ClientAbrState.from(checkNotNull(fields.message(1))),
            selectedFormats = fields.messages(2).map(FormatId::from),
            bufferedRanges = fields.messages(3).map(BufferedRange::from),
            selectedAudioFormats = fields.messages(16).map(FormatId::from),
            selectedVideoFormats = fields.messages(17).map(FormatId::from),
            streamerContext = StreamerContext.from(checkNotNull(fields.message(19))),
            ustreamerConfig = fields.bytes(5),
            adaptiveFormats = fields.messages(568).map(AdaptiveFormat::from),
            videoId = fields.string(569) ?: "",
        )
    }

    fun encode() = ProtoWriter()
        .message(1, clientAbrState.encode())
        .messages(2, selectedFormats.map { it.encode() })
        .messages(3, bufferedRanges.map { it.encode() })
        .bytes(5, ustreamerConfig)
        .messages(16, selectedAudioFormats.map { it.encode() })
        .messages(17, selectedVideoFormats.map { it.encode() })
        .message(19, streamerContext.encode())
}

// optional uint32 header_id = 1;
// optional string video_id = 2;
// optional int32 itag = 3;
// optional uint64 lmt = 4;
// optional string xtags = 5;
// optional int64 start_range = 6;
// optional bool is_init_seg = 8;
// optional int64 sequence_number = 9;
// optional int64 start_ms = 11;
// optional int64 duration_ms = 12;
// optional int64 content_length = 14;
// optional int64 data_offset = 17; (unofficial name)
data class MediaHeader(
    val headerId: Int,
    val videoId: String,
    val itag: Int,
    val lmt: Long,
    val xtag: String?,
    /** BYTE offset of this segment within the format's contiguous stream. */
    val startRange: Long,
    val isInitSegment: Boolean,
    /** 0 for the init segment. */
    val segmentIndex: Int,
    val startMs: Long?,
    val durationMs: Long?,
    val contentLength: Long,
    /**
     * Offset inside the segment where this response's MEDIA_DATA starts: non-zero when the server resumes
     * a segment an earlier response delivered in part. [contentLength] still counts the whole segment.
     * Response-only, never encoded: index rows and synthesized hits describe whole segments.
     */
    val dataOffset: Long = 0,
) {
    companion object {
        fun from(fields: List<ProtoField>) = MediaHeader(
            headerId = checkNotNull(fields.varint(1)).toInt(),
            videoId = fields.string(2) ?: "",
            itag = checkNotNull(fields.varint(3)).toInt(),
            /* Gold live omits lmt / start_range / init / dur / clen. */
            lmt = fields.varint(4) ?: 0L,
            xtag = fields.string(5),
            startRange = fields.varint(6) ?: 0L,
            isInitSegment = fields.varint(8) == 1L,
            segmentIndex = fields.varint(9)?.toInt() ?: 0,
            startMs = fields.varint(11),
            durationMs = fields.varint(12),
            contentLength = fields.varint(14) ?: 0L,
            dataOffset = fields.varint(17) ?: 0L,
        )
    }

    val cacheKey get() = formatCacheKey(itag, lmt)

    /** The segment's identity and byte placement, for log lines. */
    fun describe() = "itag=$itag seg=$segmentIndex range=$startRange+$contentLength dataOffset=$dataOffset"

    fun encode() = ProtoWriter()
        .varint(1, headerId)
        .string(2, videoId)
        .varint(3, itag)
        .varint(4, lmt)
        .string(5, xtag)
        .varint(6, startRange.takeIf { it >= 0 })
        .varint(8, if (isInitSegment) 1 else 0)
        .varint(9, segmentIndex.takeIf { it != 0 })
        .varint(11, startMs.takeIf { segmentIndex != 0 })
        .varint(12, durationMs.takeIf { segmentIndex != 0 })
        .varint(14, contentLength)
}

// 1 target_audio_readahead_ms, 2 target_video_readahead_ms, 4 backoff_time_ms,
// 7 playback_cookie, 8 video_id
class NextRequestPolicy(
    val targetAudioReadaheadMs: Int?,
    val targetVideoReadaheadMs: Int?,
    val backoffTimeMs: Int?,
    val playbackCookie: ByteArray?,
    val videoId: String?,
) {
    companion object {
        fun from(fields: List<ProtoField>) = NextRequestPolicy(
            targetAudioReadaheadMs = fields.varint(1)?.toInt(),
            targetVideoReadaheadMs = fields.varint(2)?.toInt(),
            backoffTimeMs = fields.varint(4)?.toInt(),
            playbackCookie = fields.bytes(7),
            videoId = fields.string(8),
        )
    }

    fun encode() = ProtoWriter()
        .varint(1, targetAudioReadaheadMs)
        .varint(2, targetVideoReadaheadMs)
        .varint(4, backoffTimeMs)
        .bytes(7, playbackCookie)
        .string(8, videoId)
}
