package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ymusic.download.AudioQuality
import io.ymusic.download.CaptionChoice
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.download.DownloadRequest
import io.ymusic.download.DownloadService
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtLinkTarget
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_added
import io.ymusic.ui.resources.link_unsupported
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.TechnicalException
import projectcommon.error.errorCode
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

    /** This preset saving as [format] instead (null: unchanged); the height belongs to the old format, so a switch drops it. */
    fun withFormat(format: DownloadFormat?): QuickDownloadPreset =
        if (format != null && format != this.format) copy(format = format, height = null) else this

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
    val saved = graph.quickDownload.collectAsStateWithLifecycle().value
    val preset = saved.withFormat(format)
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
        if (!needsDownloadFolderPick(graph) || pickDownloadFolder(graph, snackbar, scans)) {
            downloads.enqueue(preset.request(video))
            snackbar(added)
        }
        onDone()
    }
}

/** The format a Download MP3 / M4A link action saves as; null for any other action, and for MP3 without ffmpeg. */
internal fun LinkAction.quickDownloadFormat(downloads: DownloadService): DownloadFormat? = when (this) {
    LinkAction.DOWNLOAD_M4A -> DownloadFormat.M4A
    LinkAction.DOWNLOAD_MP3 -> DownloadFormat.MP3.takeIf { downloads.ffmpegAvailable }
    else -> null
}

/** A download has to ask for a folder first: the shell can pick one and the saved location is unusable. */
internal suspend fun needsDownloadFolderPick(graph: AppGraph): Boolean =
    graph.mediaStore != null && graph.libraryFolderPicker != null && !hasUsableDownloadLocation(graph)

/** What [quickDownloadLink] did with a link. */
sealed interface LinkQuickDownload {
    data object Enqueued : LinkQuickDownload

    /** Only [App] can run this link: it shows a dialog, a picker or a page first. */
    data object NeedsUi : LinkQuickDownload

    /** The link opens nothing YMusic knows. */
    data object Unsupported : LinkQuickDownload

    /** Resolving or enqueueing the link failed with [code]. */
    data class Failed(val code: String) : LinkQuickDownload
}

/**
 * A Download MP3 / M4A link action without any UI, decided as [App] decides it: the Quick download is
 * enqueued when App would go straight to it, else [LinkQuickDownload.NeedsUi] — any other action, MP3
 * without ffmpeg, no usable download folder, a link that isn't a video, or a video the Library holds.
 */
suspend fun quickDownloadLink(graph: AppGraph, url: String, action: LinkAction): LinkQuickDownload {
    val log = graph.logger?.fork("IncomingLink", mapOf("action" to action.name))
    return try {
        enqueueQuickDownload(graph, url, action, log)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        val wrapped = TechnicalException("E70090", "quick download of a shared link failed", cause = failure)
        log?.captureError(wrapped)
        LinkQuickDownload.Failed(wrapped.errorCode() ?: "E70090")
    }
}

private suspend fun enqueueQuickDownload(graph: AppGraph, url: String, action: LinkAction, log: Logger?): LinkQuickDownload {
    val downloads = graph.downloads ?: return LinkQuickDownload.NeedsUi
    val format = action.quickDownloadFormat(downloads) ?: return LinkQuickDownload.NeedsUi
    if (needsDownloadFolderPick(graph)) return LinkQuickDownload.NeedsUi
    val target = when (val resolved = resolveIncomingLink(graph, url, log)) {
        is LinkResolution.Failed -> return LinkQuickDownload.Failed(resolved.code)
        is LinkResolution.Resolved -> resolved.target ?: return LinkQuickDownload.Unsupported
    }
    val video = (target as? YtLinkTarget.Video)?.item ?: return LinkQuickDownload.NeedsUi
    if (graph.mediaStore?.youtubeVideoIds()?.contains(video.id) == true) return LinkQuickDownload.NeedsUi
    downloads.enqueue(graph.quickDownload.value.withFormat(format).request(video))
    return LinkQuickDownload.Enqueued
}

/** The line telling the user how [quickDownloadLink] went; null for [LinkQuickDownload.NeedsUi], which App words itself. */
suspend fun LinkQuickDownload.userMessage(): String? = when (this) {
    LinkQuickDownload.Enqueued -> getString(Res.string.download_added)
    LinkQuickDownload.Unsupported -> getString(Res.string.link_unsupported)
    is LinkQuickDownload.Failed -> errorText(code).load()
    LinkQuickDownload.NeedsUi -> null
}
