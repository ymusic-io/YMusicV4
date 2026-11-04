package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ymusic.download.AudioOption
import io.ymusic.download.AudioQuality
import io.ymusic.download.AudioQualityOption
import io.ymusic.download.CaptionChoice
import io.ymusic.download.CaptionOption
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadOptions
import io.ymusic.download.DownloadRequest
import io.ymusic.download.DownloadService
import io.ymusic.download.LiveVideoException
import io.ymusic.download.VideoOption
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_size_gb
import io.ymusic.ui.resources.download_size_mb
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.codedError

/** The last picks, so the next download opens where this one left off (for the app session). */
private object LastDownloadChoice {
    var audioOnly = true
    var audioFormat = DownloadFormat.M4A
    var audioQuality = AudioQuality.BEST
    var container = DownloadFormat.VIDEO_MP4
    var height = 1080
}

/**
 * The download options dialog's model, shared by the mobile sheet and the desktop dialog: the probed
 * [options] for [video] and the user's picks, turned into a [DownloadRequest] by [request].
 */
@Stable
internal class DownloadOptionsState(
    val video: YtFeedItem,
    private val downloads: DownloadService,
    /** The Quick download caption language the dialog starts from; null starts with captions Off. */
    private val preferredCaptionLanguage: String?,
) {
    /** Also keep these picks as the Quick download preset. */
    var saveAsQuick by mutableStateOf(false)

    var options by mutableStateOf<DownloadOptions?>(null)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set

    /** The probe found a live broadcast — nothing to download, so no retry either. */
    var isLive by mutableStateOf(video.live)
        private set

    var audioOnly by mutableStateOf(LastDownloadChoice.audioOnly)
    var audioFormat by mutableStateOf(LastDownloadChoice.audioFormat)
    var audioQuality by mutableStateOf(LastDownloadChoice.audioQuality)
    var container by mutableStateOf(LastDownloadChoice.container)
    var height by mutableStateOf<Int?>(null)
    var audioTrackId by mutableStateOf<String?>(null)
    /** The one caption track to include ([CaptionOption.key]); null = none. */
    var caption by mutableStateOf<String?>(null)
    var fileName by mutableStateOf(DownloadFileNames.fromTitle(video.title).ifEmpty { video.id })

    /** [video] with the probed length filled in when the feed item came without a duration badge. */
    val badgeItem: YtFeedItem
        get() {
            val probedMs = options?.durationMs?.takeIf { it > 0 }
            return if (video.duration != null || probedMs == null) video else video.copy(duration = formatMillis(probedMs), durationMs = probedMs)
        }

    val hasVideo: Boolean get() = options?.video?.isNotEmpty() == true

    /** The video containers this video can be downloaded in. */
    val containers: List<DownloadFormat>
        get() = if (options?.video?.any { it.webmBytes != null } == true) {
            listOf(DownloadFormat.VIDEO_MP4, DownloadFormat.VIDEO_WEBM)
        } else {
            listOf(DownloadFormat.VIDEO_MP4)
        }

    val format: DownloadFormat
        get() = when {
            audioOnly || !hasVideo -> audioFormat
            container in containers -> container
            else -> DownloadFormat.VIDEO_MP4
        }

    val extension: String get() = "." + format.fileExtension

    /** Captions go into the file — subtitle tracks, or lyrics for MP3 and Opus — which takes ffmpeg (absent on iOS). */
    val captionsSupported: Boolean get() = downloads.ffmpegAvailable

    val audioOption: AudioOption? get() = options?.audio?.firstOrNull { it.format == audioFormat }

    /** [audioQuality] as the selected format offers it. */
    val selectedAudioQuality: AudioQualityOption? get() = audioOption?.pick(audioQuality)

    val showsAudioQuality: Boolean get() = format.kind == io.ymusic.download.DownloadKind.AUDIO && audioOption != null

    val selectedVideo: VideoOption? get() = options?.video?.firstOrNull { it.height == height }

    val selectedBytes: Long?
        get() {
            val opts = options ?: return null
            return if (format.kind == io.ymusic.download.DownloadKind.AUDIO) {
                opts.audioBytes(format, audioQuality)
            } else {
                selectedVideo?.let { if (format == DownloadFormat.VIDEO_WEBM) it.webmBytes else it.mp4Bytes }
            }
        }

    suspend fun load(log: Logger?) {
        errorCode = null
        if (isLive) return
        try {
            val probed = downloads.probe(video.id)
            if (probed.audio.none { it.format == audioFormat }) audioFormat = probed.audio.first().format
            height = pickHeight(probed.video, LastDownloadChoice.height)
            audioTrackId = probed.audioTracks.firstOrNull { it.isDefault }?.id ?: probed.audioTracks.firstOrNull()?.id
            caption = defaultCaption(probed.captions)
            options = probed
        } catch (c: CancellationException) {
            throw c
        } catch (e: LiveVideoException) {
            isLive = true
        } catch (e: Exception) {
            log?.fork("download-options", mapOf("videoId" to video.id))?.log(Logger.Level.WARN, "probe failed", e)
            errorCode = e.codedError()?.code ?: "E70043"
        }
    }

    /** These picks as languages — what [QuickDownloadPreset] applies to other videos. */
    fun quickPreset(): QuickDownloadPreset {
        val opts = options
        return QuickDownloadPreset(
            format = format,
            height = height,
            audioQuality = selectedAudioQuality?.quality ?: audioQuality,
            audioLanguage = audioTrackId?.takeIf { opts?.audioTracks?.isNotEmpty() == true }?.substringBefore('.'),
            captionLanguage = opts?.captions?.firstOrNull { captionsSupported && it.key == caption }?.languageCode,
        )
    }

    fun request(): DownloadRequest {
        LastDownloadChoice.audioOnly = audioOnly
        LastDownloadChoice.audioFormat = audioFormat
        LastDownloadChoice.audioQuality = audioQuality
        LastDownloadChoice.container = container
        height?.let { LastDownloadChoice.height = it }
        val opts = options
        return DownloadRequest(
            videoId = video.id,
            title = video.title,
            artist = null,
            thumbnailUrl = video.thumbs.largestThumbUrl(),
            format = format,
            requestedHeight = if (format.kind == io.ymusic.download.DownloadKind.VIDEO) height else null,
            fileName = DownloadFileNames.finalize(fileName).ifEmpty { null },
            audioTrackId = audioTrackId?.takeIf { opts?.audioTracks?.isNotEmpty() == true },
            captions = CaptionChoice(
                listOfNotNull(caption?.takeIf { key -> captionsSupported && opts?.captions?.any { it.key == key } == true }),
            ),
            audioQuality = selectedAudioQuality?.quality ?: audioQuality,
        )
    }

    /** The highest quality at or below the last pick, else the lowest offered. */
    private fun pickHeight(video: List<VideoOption>, preferred: Int): Int? =
        video.firstOrNull { it.height <= preferred }?.height ?: video.lastOrNull()?.height

    /** What Quick download would take: the video's own track in [preferredCaptionLanguage] (exact or base
     *  language, uploaded before auto-generated), else its default track. Never an auto-translation — that
     *  is only ever a deliberate pick. */
    private fun defaultCaption(tracks: List<CaptionOption>): String? {
        val language = preferredCaptionLanguage ?: return null
        val own = tracks.filter { !it.isTranslated }
        val wanted = own.filter {
            it.languageCode.equals(language, ignoreCase = true) ||
                it.languageCode.substringBefore('-').equals(language.substringBefore('-'), ignoreCase = true)
        }
        return (wanted.firstOrNull { !it.isAsr } ?: wanted.firstOrNull() ?: own.firstOrNull { it.isDefault })?.key
    }
}

/** "4K", "1080p60" — the quality name a tile/menu shows. */
internal fun VideoOption.label(): String {
    val name = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        else -> "${height}p"
    }
    return if (fps >= 48) "$name$fps" else name
}

/** A download size as "4.1 MB" / "92 MB" / "1.2 GB". */
@Composable
internal fun formatDownloadSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return when {
        mb >= 1000 -> stringResource(Res.string.download_size_gb, oneDecimal(mb / 1000))
        mb >= 10 -> stringResource(Res.string.download_size_mb, mb.toLong().toString())
        else -> stringResource(Res.string.download_size_mb, oneDecimal(mb))
    }
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
