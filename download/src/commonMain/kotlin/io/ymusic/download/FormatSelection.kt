package io.ymusic.download

import io.ymusic.ytmedia.sabr.AdaptiveFormat

/**
 * Chooses the concrete [AdaptiveFormat]s to download for a [DownloadFormat] from a video's
 * `adaptiveFormats`. Audio and video are separate elementary streams (SABR delivers each unmuxed); the
 * pipeline muxes them afterward. `contentLength` (`clen`) stands in for bitrate — there is no explicit
 * bitrate field — so "best" means largest `clen`.
 */
internal object FormatSelection {

    /**
     * Best audio track whose codec [codec] accepts — the source is lossy, so a non-MP3 output only ever
     * stream-copies and must take a codec its container holds. The requested [audioTrackId] (a dubbed
     * language) wins when the video has it, then a track in [audioLanguage]; otherwise default
     * (non-dubbed) tracks win over alternates so we don't grab a foreign-language dub.
     */
    fun bestAudio(
        formats: List<AdaptiveFormat>,
        codec: AudioCodec,
        audioTrackId: String? = null,
        audioLanguage: String? = null,
    ): AdaptiveFormat? {
        val audio = audioStreams(formats, codec)
        if (audio.isEmpty()) return null
        return audio.sortedWith(
            compareByDescending<AdaptiveFormat> { audioTrackId != null && it.audioTrackId == audioTrackId }
                .thenByDescending { audioLanguage != null && it.audioTrackId?.let(::audioTrackLanguage)?.let { lang -> sameLanguage(lang, audioLanguage) } == true }
                .thenByDescending { it.audioTrackIsDefault != false }
                .thenByDescending { it.clen },
        ).first()
    }

    /**
     * The tiers of the track [bestAudio] picks, lowest first: its smallest stream as [AudioQuality.SAVE],
     * largest as [AudioQuality.BEST], and a middle one as [AudioQuality.MEDIUM] when there are three or more.
     */
    fun audioTiers(
        formats: List<AdaptiveFormat>,
        codec: AudioCodec,
        audioTrackId: String? = null,
        audioLanguage: String? = null,
    ): List<Pair<AudioQuality, AdaptiveFormat>> {
        val best = bestAudio(formats, codec, audioTrackId, audioLanguage) ?: return emptyList()
        val ladder = audioStreams(formats, codec)
            .filter { it.audioTrackId == best.audioTrackId }
            .distinctBy { it.itag }
            .sortedBy { it.clen }
        return when (ladder.size) {
            1 -> listOf(AudioQuality.BEST to best)
            2 -> listOf(AudioQuality.SAVE to ladder.first(), AudioQuality.BEST to best)
            else -> listOf(
                AudioQuality.SAVE to ladder.first(),
                AudioQuality.MEDIUM to ladder[ladder.size / 2],
                AudioQuality.BEST to best,
            )
        }
    }

    /** The tier at [quality], else the next one up; a video with fewer tiers lands on its best. */
    fun audioAt(
        formats: List<AdaptiveFormat>,
        codec: AudioCodec,
        quality: AudioQuality,
        audioTrackId: String? = null,
        audioLanguage: String? = null,
    ): AdaptiveFormat? {
        val tiers = audioTiers(formats, codec, audioTrackId, audioLanguage)
        return (tiers.firstOrNull { it.first >= quality } ?: tiers.lastOrNull())?.second
    }

    /** [stream] on the track [bestAudio] picks; null when the video no longer offers it. */
    fun rememberedAudio(
        formats: List<AdaptiveFormat>,
        codec: AudioCodec,
        stream: StreamId,
        audioTrackId: String? = null,
        audioLanguage: String? = null,
    ): AdaptiveFormat? {
        val best = bestAudio(formats, codec, audioTrackId, audioLanguage) ?: return null
        return audioStreams(formats, codec).firstOrNull { it.streamId == stream && it.audioTrackId == best.audioTrackId }
    }

    /** [stream] when the video still offers it for [container]. */
    fun rememberedVideo(formats: List<AdaptiveFormat>, stream: StreamId, container: DownloadFormat): AdaptiveFormat? =
        videoStreams(formats, container).firstOrNull { it.streamId == stream }

    private fun audioStreams(formats: List<AdaptiveFormat>, codec: AudioCodec): List<AdaptiveFormat> =
        formats.filter { it.mimeType.startsWith("audio") && it.clen > 0 && !it.isDrc && codec.accepts(it.mimeType) }

    /**
     * Video track nearest [requestedHeight] (or the highest when null), biased to the codec family that
     * stream-copies into [container]: VP9/WebM for a WebM output, AVC/mp4 for an MP4 output (VP9-in-mp4
     * copies but plays back poorly on many devices). Ties break on larger [AdaptiveFormat.clen]. A WebM
     * output only considers codecs the WebM muxer accepts, even at a farther height.
     */
    fun videoNearest(
        formats: List<AdaptiveFormat>,
        requestedHeight: Int?,
        container: DownloadFormat,
    ): AdaptiveFormat? {
        val video = videoStreams(formats, container)
        if (video.isEmpty()) return null
        val preferMp4Codec = container == DownloadFormat.VIDEO_MP4
        return video.sortedWith(
            // 1) closeness to the requested height (0 distance when unspecified → pure quality sort)
            compareBy<AdaptiveFormat> { requestedHeight?.let { req -> kotlin.math.abs((it.height ?: 0) - req) } ?: 0 }
                // 2) codec that copies cleanly into the target container
                .thenByDescending { if (preferMp4Codec) it.mimeType.contains("mp4") else it.mimeType.contains("webm") }
                // 3) higher resolution, then bigger stream (bitrate proxy)
                .thenByDescending { it.height ?: 0 }
                .thenByDescending { it.clen },
        ).first()
    }

    private fun videoStreams(formats: List<AdaptiveFormat>, container: DownloadFormat): List<AdaptiveFormat> {
        val playable = formats.filter { it.isVideo && it.clen > 0 && it.height != null }
        return if (container == DownloadFormat.VIDEO_WEBM) playable.filter { isWebmVideo(it.mimeType) } else playable
    }
}

internal val AdaptiveFormat.streamId: StreamId get() = StreamId(itag, lmt)

/** The audio codecs an output can take: [AAC] for M4A/MP4, [OPUS] for WebM, [ANY] for MP3 (decoded). */
internal enum class AudioCodec {
    AAC,
    OPUS,
    ANY,
    ;

    fun accepts(mimeType: String): Boolean {
        val codecs = mimeType.substringAfter("codecs=", "").trim('"', ' ')
        return when (this) {
            AAC -> codecs.startsWith("mp4a")
            OPUS -> codecs.startsWith("opus")
            ANY -> true
        }
    }

    companion object {
        fun of(format: DownloadFormat): AudioCodec = when (format) {
            DownloadFormat.MP3 -> ANY
            DownloadFormat.M4A, DownloadFormat.VIDEO_MP4 -> AAC
            DownloadFormat.AUDIO_WEBM, DownloadFormat.VIDEO_WEBM -> OPUS
        }
    }
}

/** VP8/VP9/AV1 — the video codecs ffmpeg's `webm` muxer accepts. */
internal fun isWebmVideo(mimeType: String): Boolean {
    val codecs = mimeType.substringAfter("codecs=", "").trim('"', ' ')
    return codecs.startsWith("vp8") || codecs.startsWith("vp9") || codecs.startsWith("vp09") || codecs.startsWith("av01")
}

/** An `audioTrack.id`'s language: `en` of `en.4`, `es-US` of `es-US.3`. */
internal fun audioTrackLanguage(trackId: String): String = trackId.substringBefore('.')

/** Same language, regional variants included (`pt` ~ `pt-BR`). */
internal fun sameLanguage(a: String, b: String): Boolean =
    a.equals(b, ignoreCase = true) || a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
