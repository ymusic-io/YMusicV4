package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ymusic.download.AudioQuality
import io.ymusic.download.CaptionChoice
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.download.DownloadRequest
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_added
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource
import projectcommon.json.intOrNull
import projectcommon.json.string
import projectcommon.json.stringOrNull

/**
 * The Quick download preset — picks saved from the options dialog, applied to any video without asking.
 * Languages, not tracks: each download resolves them against its own player response (the nearest
 * [height], the dubbed track in [audioLanguage], the caption in [captionLanguage] — else the defaults).
 * [captionLanguage] null means captions off. Persisted in [AppSettings.quickDownloadPreset] ([AppGraph.quickDownload]);
 * there is always one ([default] until the user saves their own).
 */
data class QuickDownloadPreset(
    val format: DownloadFormat,
    val height: Int?,
    val audioLanguage: String?,
    val captionLanguage: String?,
    val audioQuality: AudioQuality = AudioQuality.BEST,
) {
    fun request(video: YtFeedItem, fileNamePrefix: String = ""): DownloadRequest = DownloadRequest(
        videoId = video.id,
        title = video.title,
        artist = null,
        thumbnailUrl = video.thumbs.largestThumbUrl(),
        format = format,
        requestedHeight = if (format.kind == DownloadKind.VIDEO) height else null,
        fileName = DownloadFileNames.fromTitle(fileNamePrefix + video.title).ifEmpty { null },
        captions = if (captionLanguage == null) CaptionChoice(emptyList()) else null,
        audioLanguage = audioLanguage,
        captionLanguage = captionLanguage,
        audioQuality = audioQuality,
    )

    fun encode(): String = buildJsonObject {
        put(KEY_FORMAT, format.name)
        put(KEY_AUDIO_QUALITY, audioQuality.name)
        height?.let { put(KEY_HEIGHT, it) }
        audioLanguage?.let { put(KEY_AUDIO, it) }
        captionLanguage?.let { put(KEY_CAPTION, it) }
    }.toString()

    companion object {
        private const val KEY_FORMAT = "format"
        private const val KEY_AUDIO_QUALITY = "audioQuality"
        private const val KEY_HEIGHT = "height"
        private const val KEY_AUDIO = "audioLanguage"
        private const val KEY_CAPTION = "captionLanguage"

        /**
         * Opus (YouTube's best audio, in Ogg) when ffmpeg can write the WebM, else M4A; the default
         * audio track; captions in the device language — until the user saves a preset.
         */
        fun default(ffmpegAvailable: Boolean): QuickDownloadPreset = QuickDownloadPreset(
            format = if (ffmpegAvailable) DownloadFormat.AUDIO_WEBM else DownloadFormat.M4A,
            height = null,
            audioLanguage = null,
            captionLanguage = systemLanguageTag(),
        )

        /** [default] when never saved, or when the saved format is unknown to this build (an older app version). */
        fun decode(raw: String, ffmpegAvailable: Boolean): QuickDownloadPreset {
            if (raw.isBlank()) return default(ffmpegAvailable)
            val json = Json.parseToJsonElement(raw).jsonObject
            val format = DownloadFormat.entries.firstOrNull { it.name == json.string(KEY_FORMAT) } ?: return default(ffmpegAvailable)
            return QuickDownloadPreset(
                format = format,
                height = json.intOrNull(KEY_HEIGHT),
                audioLanguage = json.stringOrNull(KEY_AUDIO),
                captionLanguage = json.stringOrNull(KEY_CAPTION),
                audioQuality = AudioQuality.entries.firstOrNull { it.name == json.stringOrNull(KEY_AUDIO_QUALITY) } ?: AudioQuality.BEST,
            )
        }
    }

    /** "MP3", "MP4 · 1080p". */
    fun label(): String {
        val name = format.displayName()
        if (format.kind != DownloadKind.VIDEO || height == null) return name
        val quality = when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            else -> "${height}p"
        }
        return "$name · $quality"
    }
}

/**
 * Runs a Quick download for the [pending] video: straight into the download folder when one is set, else
 * through the folder picker first (a cancel drops the download). No options dialog. [format] overrides
 * the preset's (its languages still apply).
 */
@Composable
internal fun QuickDownloadHost(graph: AppGraph, pending: YtFeedItem?, format: DownloadFormat? = null, onDone: () -> Unit) {
    val downloads = graph.downloads ?: return
    val video = pending ?: return
    val saved = graph.quickDownload.collectAsState().value
    val preset = if (format != null && format != saved.format) saved.copy(format = format, height = null) else saved
    val snackbar = LocalSnackbar.current
    val scans = LocalScanController.current
    val added = stringResource(Res.string.download_added)
    val alreadyDownloaded = video.id in LocalAvailableOffline.current
    var confirmed by remember(video.id) { mutableStateOf(false) }
    if (alreadyDownloaded && !confirmed) {
        RedownloadDialog(video.title, onConfirm = { confirmed = true }, onDismiss = onDone)
        return
    }
    LaunchedEffect(video) {
        val gated = graph.mediaStore != null && graph.libraryFolderPicker != null
        if (!gated || hasUsableDownloadLocation(graph) || pickDownloadFolder(graph, snackbar, scans)) {
            downloads.enqueue(preset.request(video))
            snackbar(added)
        }
        onDone()
    }
}
