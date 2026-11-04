package io.ymusic.download

import io.ymusic.ytmedia.sabr.AdaptiveFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import projectcommon.error.CodedError
import projectcommon.error.TechnicalException
import projectcommon.json.boolOrNull
import projectcommon.json.intOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/** The video is a live broadcast in progress: there is no finished file to download until it ends. A
 *  well-known category ([code] `"live"`) the UI renders as its own message, not an error code. */
class LiveVideoException(videoId: String?) : Exception("live video ${videoId.orEmpty()}"), CodedError {
    override val code: String = CODE
    override val userMessage: String? = null

    companion object {
        const val CODE = "live"
    }
}

/** YouTube is still processing the video (typically a just-ended live stream's replay), so its formats have
 *  no `lastModified`/`contentLength` yet. A well-known category ([code] `"processing"`); a later
 *  retry succeeds. */
class StillProcessingException(videoId: String?) : Exception("post-live replay still processing ${videoId.orEmpty()}"), CodedError {
    override val code: String = CODE
    override val userMessage: String? = null

    companion object {
        const val CODE = "processing"
    }
}

/** One downloadable video height; sizes are estimates for each container (video + its audio track).
 *  [webmBytes] is null when the video has no VP9/AV1 or no Opus stream, so WebM can't be downloaded. */
class VideoOption(val height: Int, val fps: Int, val mp4Bytes: Long, val webmBytes: Long?)

/** One [quality] of an audio output and its estimated size; [kbps] is the encode bitrate of a transcoded output (MP3). */
class AudioQualityOption(val quality: AudioQuality, val bytes: Long, val kbps: Int? = null)

/** One audio-only output and its [qualities], lowest first. */
class AudioOption(val format: DownloadFormat, val qualities: List<AudioQualityOption>) {
    /** [quality], else the next one up, else the highest offered. */
    fun pick(quality: AudioQuality): AudioQualityOption =
        qualities.firstOrNull { it.quality >= quality } ?: qualities.last()
}

/** One audio language of a video that has dubs. */
class AudioTrackOption(val id: String, val name: String, val isDefault: Boolean)

/** One caption track a download can fetch. */
class CaptionOption(
    val languageCode: String,
    val name: String,
    val isAsr: Boolean,
    val isDefault: Boolean,
    /** One of YouTube's auto-translations of the video's own track. */
    val isTranslated: Boolean = false,
) {
    val key: String get() = captionKey(languageCode, isAsr)
}

/**
 * What one video can be downloaded as, read from its player response — the input of the download options
 * dialog. Sizes are estimates: stream-copied tracks use their `contentLength`, MP3 uses the encoder's
 * target bitrate over the duration.
 */
class DownloadOptions(
    val durationMs: Long,
    /** Highest first. */
    val video: List<VideoOption>,
    val audio: List<AudioOption>,
    /** Empty unless the video carries more than one audio language. */
    val audioTracks: List<AudioTrackOption>,
    val captions: List<CaptionOption>,
) {
    fun audioBytes(format: DownloadFormat, quality: AudioQuality): Long? =
        audio.firstOrNull { it.format == format }?.pick(quality)?.bytes

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(playerResponseJson: String, ffmpegAvailable: Boolean): DownloadOptions {
            val data = json.parseToJsonElement(playerResponseJson).jsonObject
            val details = data.objectOrNull("videoDetails")
            val streaming = data.objectOrNull("streamingData")
                ?: throw TechnicalException("E70041", "download options: no streamingData")
            SabrSpecBuilder.checkProcessed(data, streaming)
            val formats = SabrSpecBuilder.adaptiveFormats(streaming)
            val raw = streaming.objects("adaptiveFormats")
            val durationMs = formats.maxOfOrNull { it.approxDurationMs.toLong() }?.takeIf { it > 0 }
                ?: (details?.longOrNull("lengthSeconds") ?: 0L) * 1000
            val audio = audioOptions(formats, durationMs, ffmpegAvailable)
            if (audio.isEmpty()) throw TechnicalException("E70042", "download options: no audio format this platform can save")
            return DownloadOptions(
                durationMs = durationMs,
                video = if (ffmpegAvailable) videoOptions(formats, raw, durationMs) else emptyList(),
                audio = audio,
                audioTracks = audioTracks(raw),
                captions = captionEntries(data).map { CaptionOption(it.languageCode, it.name, it.isAsr, it.isDefault, it.isTranslated) },
            )
        }

        private fun videoOptions(formats: List<AdaptiveFormat>, raw: List<JsonObject>, durationMs: Long): List<VideoOption> {
            val fpsByItag = raw.associate { (it.intOrNull("itag") ?: -1) to (it.intOrNull("fps") ?: 0) }
            val heights = formats.filter { it.isVideo && it.clen > 0 }.mapNotNull { it.height }.distinct().sortedDescending()
            val aacBytes = FormatSelection.bestAudio(formats, AudioCodec.AAC)?.clen ?: 0
            val opus = FormatSelection.bestAudio(formats, AudioCodec.OPUS)
            return heights.map { height ->
                val atHeight = formats.filter { it.isVideo && it.height == height }
                val mp4Video = FormatSelection.videoNearest(atHeight, height, DownloadFormat.VIDEO_MP4)
                val webmVideo = FormatSelection.videoNearest(formats, height, DownloadFormat.VIDEO_WEBM)
                VideoOption(
                    height = height,
                    fps = atHeight.maxOfOrNull { fpsByItag[it.itag] ?: 0 } ?: 0,
                    mp4Bytes = (mp4Video?.clen ?: 0) + aacBytes,
                    webmBytes = if (opus == null) null else webmVideo?.let { it.clen + opus.clen },
                )
            }
        }

        private fun audioOptions(
            formats: List<AdaptiveFormat>,
            durationMs: Long,
            ffmpegAvailable: Boolean,
        ): List<AudioOption> {
            val m4a = streamOption(DownloadFormat.M4A, formats, AudioCodec.AAC)
            if (!ffmpegAvailable) return listOfNotNull(m4a)
            val mp3 = AudioQuality.entries.map { quality ->
                val kbps = Mp3Encoding.of(quality).kbps
                AudioQualityOption(quality, bytesAt(kbps * 1000L, durationMs), kbps)
            }
            return listOfNotNull(
                streamOption(DownloadFormat.AUDIO_WEBM, formats, AudioCodec.OPUS),
                m4a,
                AudioOption(DownloadFormat.MP3, mp3),
            )
        }

        private fun streamOption(format: DownloadFormat, formats: List<AdaptiveFormat>, codec: AudioCodec): AudioOption? =
            FormatSelection.audioTiers(formats, codec)
                .map { (quality, stream) -> AudioQualityOption(quality, stream.clen) }
                .takeIf { it.isNotEmpty() }
                ?.let { AudioOption(format, it) }

        private fun audioTracks(raw: List<JsonObject>): List<AudioTrackOption> {
            val tracks = raw.mapNotNull { fmt ->
                val track = fmt.objectOrNull("audioTrack") ?: return@mapNotNull null
                val id = track.stringOrNull("id") ?: return@mapNotNull null
                AudioTrackOption(id, track.stringOrNull("displayName") ?: id, track.boolOrNull("audioIsDefault") ?: false)
            }.distinctBy { it.id }
            return if (tracks.size > 1) tracks.sortedByDescending { it.isDefault } else emptyList()
        }

        private fun bytesAt(bitsPerSecond: Long, durationMs: Long): Long = bitsPerSecond / 8 * durationMs / 1000
    }
}
