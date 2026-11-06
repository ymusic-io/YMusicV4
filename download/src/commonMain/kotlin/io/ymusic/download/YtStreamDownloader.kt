package io.ymusic.download

import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SegmentIndex
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.CaptionTrack
import io.ymusic.ytmedia.sabr.DownloadFile
import io.ymusic.ytmedia.sabr.SabrAttestationPending
import io.ymusic.ytmedia.sabr.SabrPoTokenInvalid
import io.ymusic.ytmedia.sabr.SabrProgress
import io.ymusic.ytmedia.sabr.SabrServerError
import io.ymusic.ytmedia.sabr.SabrSession
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import io.ymusic.ytmedia.sabr.SabrTrack
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.byteString
import kotlinx.coroutines.CancellationException
import okio.IOException
import okio.use
import projectcommon.error.TechnicalException
import projectcommon.error.codedError

/** Caption tracks to fetch: the ones a per-task [choice] names (MP3 and Opus take them as lyrics); else the
 *  track in [preferredLanguage] or, lacking it, the video's default; else none. */
internal fun captionsToDownload(
    tracks: List<CaptionTrack>,
    choice: CaptionChoice?,
    preferredLanguage: String?,
): List<CaptionTrack> = when {
    choice != null -> choice.languages.mapNotNull { key -> tracks.firstOrNull { captionKey(it.languageCode, it.isAsr) == key } }
    preferredLanguage != null -> listOfNotNull(
        tracks.firstOrNull { it.languageCode.equals(preferredLanguage, ignoreCase = true) }
            ?: tracks.firstOrNull { sameLanguage(it.languageCode, preferredLanguage) }
            ?: tracks.firstOrNull { it.isDefault },
    )
    else -> emptyList()
}

/** One downloaded elementary media stream: its selected [format] and the scratch file it landed in. */
class DownloadedStream(val format: AdaptiveFormat, val scratch: DownloadScratch)

/** One downloaded caption track: the source metadata plus the scratch file (a `.vtt` document). */
class DownloadedCaption(
    val languageCode: String,
    val isAsr: Boolean,
    val name: String,
    val isDefault: Boolean,
    val scratch: DownloadScratch,
)

/** Everything [YtStreamDownloader] hands to the next pipeline step (mux/convert): the files on disk. */
class DownloadedStreams(
    val audio: DownloadedStream,
    val video: DownloadedStream?,
    val captions: List<DownloadedCaption>,
    /** The artist read from the player response, for when the request carried none. */
    val artist: String? = null,
    /** The output format these streams are for: the task's, or MP4 when it asked for WebM video and the
     *  video has no WebM-muxable stream. */
    val format: DownloadFormat,
)

/**
 * The download step of the pipeline: takes a queued [DownloadTask] and produces the elementary files
 * (audio, optional video, captions) the mux/convert step then assembles. It:
 *
 *  1. resolves the SABR inputs (player response + PO token) for the videoId — via [SabrSpecResolver],
 *     which drives the WebView.
 *  2. picks the concrete audio/video [AdaptiveFormat]s for the task and the caption tracks for [captions].
 *  3. streams each audio/video track to its own scratch file over SABR — resumable (the scratch's
 *     on-disk length is the resume point, on the streams the task remembers), re-attesting once if the
 *     PO token is rejected mid-stream.
 *  4. fetches each selected caption over plain HTTP (its baseUrl carries the content PO token, see
 *     [timedTextUrl]) into a scratch file.
 *
 * Scratch files live in app-private storage ([DownloadStorage.scratch]) — unrelated to the user's
 * download-location setting, which only governs where the *final* file is published.
 */
class YtStreamDownloader(
    private val resolver: SabrSpecResolver,
    private val client: KmpHttpClient,
    private val userAgent: String,
    private val storage: DownloadStorage,
    private val mediaCache: MediaCache?,
    /** Persistent segment index — records each downloaded segment's byte range and seeds the SABR resume
     *  cursor from the on-disk file length. Null → no resume (re-downloads from zero on pause/resume). */
    private val segmentIndex: SegmentIndex?,
    logger: Logger?,
) {

    private val log = logger?.fork("download")?.fork("streams")

    suspend fun download(
        task: DownloadTask,
        onPreparing: () -> Unit,
        onDownloading: () -> Unit,
        /** The output and streams differ from the task's: persist them, before any byte lands. */
        onStreams: suspend (format: DownloadFormat, audio: StreamId, video: StreamId?) -> Unit = { _, _, _ -> },
        onProgress: (done: Long, total: Long) -> Unit,
    ): DownloadedStreams {
        onPreparing()
        val resolved = resolveSpec(task.videoId)
        var spec = resolved.spec
        val format = outputFormat(task, spec.formats)
        val (audioFmt, videoFmt) = selectFormats(task, format, spec.formats)
        val captionPicks = captionsToDownload(spec.captionTracks, task.captions, task.captionLanguage)

        val audioScratch = storage.scratch(task.id, AUDIO_SCRATCH)
        val videoScratch = if (videoFmt != null) storage.scratch(task.id, "video") else null
        dropOtherStream(task.audioStream, audioFmt, audioScratch)
        if (videoFmt != null) dropOtherStream(task.videoStream, videoFmt, videoScratch!!)
        if (format != task.format || audioFmt.streamId != task.audioStream || videoFmt?.streamId != task.videoStream) {
            onStreams(format, audioFmt.streamId, videoFmt?.streamId)
        }

        onDownloading()
        downloadTracks(
            spec, audioFmt, videoFmt, audioScratch, videoScratch, onProgress,
            reAttest = { spec = resolveSpec(task.videoId).spec; spec },
        )

        // Captions are KB-scale; fetched after the media, whole (no range-resume), not folded into progress.
        // Best-effort: a caption that fails to download is logged and skipped — it must not fail the media.
        val captionFiles = captionPicks.mapNotNull { track ->
            runCatching { downloadCaption(task.id, track) }
                .onFailure {
                    if (it is CancellationException) throw it
                    log?.log(Logger.Level.WARN, "caption ${track.languageCode} failed: ${it.message}", it)
                }
                .getOrNull()
        }

        return DownloadedStreams(
            audio = DownloadedStream(audioFmt, audioScratch),
            video = videoFmt?.let { DownloadedStream(it, videoScratch!!) },
            captions = captionFiles,
            artist = resolved.artist,
            format = format,
        )
    }

    /** Runs before the new pick is recorded, so a row never names a stream its scratch doesn't hold. */
    private fun dropOtherStream(remembered: StreamId?, picked: AdaptiveFormat, scratch: DownloadScratch) {
        if (remembered == null || remembered == picked.streamId) return
        log?.log(Logger.Level.INFO, "stream $remembered is gone; restarting its scratch on ${picked.streamId}")
        scratch.delete()
    }

    private suspend fun resolveSpec(videoId: String): ResolvedVideo =
        try {
            resolver.resolve(videoId)
        } catch (e: IllegalStateException) {
            // SabrSpecBuilder throws this when the response carries no serverAbrStreamingUrl.
            if (e.message?.contains("serverAbrStreamingUrl") == true) {
                throw TechnicalException("E70010", "no SABR stream for $videoId; progressive download is not yet supported", cause = e)
            }
            throw e
        }

    private fun outputFormat(task: DownloadTask, formats: List<AdaptiveFormat>): DownloadFormat {
        if (task.format == DownloadFormat.AUDIO_WEBM) {
            if (FormatSelection.bestAudio(formats, AudioCodec.OPUS) != null) return task.format
            log?.log(Logger.Level.INFO, "${task.videoId} has no Opus audio stream; downloading M4A")
            return DownloadFormat.M4A
        }
        if (task.format != DownloadFormat.VIDEO_WEBM) return task.format
        if (FormatSelection.videoNearest(formats, task.requestedHeight, DownloadFormat.VIDEO_WEBM) != null) return task.format
        log?.log(Logger.Level.INFO, "${task.videoId} has no WebM video stream; downloading MP4")
        return DownloadFormat.VIDEO_MP4
    }

    /** Choose the audio (and, for video outputs, video) [AdaptiveFormat]s for a [format] output. */
    private fun selectFormats(task: DownloadTask, format: DownloadFormat, formats: List<AdaptiveFormat>): Pair<AdaptiveFormat, AdaptiveFormat?> {
        val codec = AudioCodec.of(format)
        val trackId = task.audioTrackId
        val language = task.audioLanguage
        val audio = task.audioStream?.let { FormatSelection.rememberedAudio(formats, codec, it, trackId, language) }
            ?: when (format) {
                DownloadFormat.M4A, DownloadFormat.AUDIO_WEBM -> FormatSelection.audioAt(formats, codec, task.audioQuality, trackId, language)
                // A video keeps its best audio, and MP3 sets its quality when it encodes.
                else -> FormatSelection.bestAudio(formats, codec, trackId, language)
            }
            ?: throw TechnicalException("E70011", "no $codec audio format for ${task.videoId} ($format)")
        val video = if (format.kind == DownloadKind.VIDEO) {
            task.videoStream?.let { FormatSelection.rememberedVideo(formats, it, format) }
                ?: FormatSelection.videoNearest(formats, task.requestedHeight, format)
                ?: throw TechnicalException("E70012", "no video format for ${task.videoId}")
        } else {
            null
        }
        return audio to video
    }

    /** GET the caption's WebVTT into a scratch file. A body without cues is a failure, never an empty track. */
    private suspend fun downloadCaption(taskId: String, track: CaptionTrack): DownloadedCaption {
        val scratch = storage.scratch(taskId, "cc.${captionKey(track.languageCode, track.isAsr)}.vtt")
        scratch.delete() // tiny file — always refetch whole rather than resume a partial
        val url = track.baseUrl + (if ('?' in track.baseUrl) "&" else "?") + "fmt=vtt"
        val response = client.execute(RequestBuilder().url(url).header("user-agent", userAgent).get().build())
        try {
            if (!response.isSuccessful) throw IOException("caption ${track.languageCode} HTTP ${response.code}")
            val bytes = response.body.byteString()
            val vtt = bytes.utf8()
            if (!vtt.contains("-->")) throw IOException("caption ${track.languageCode} has no cues (${bytes.size} bytes)")
            scratch.appendSink().use { sink -> sink.writeUtf8(capCueDuration(vtt)); sink.flush() }
        } finally {
            response.close()
        }
        return DownloadedCaption(track.languageCode, track.isAsr, track.name, track.isDefault, scratch)
    }

    /**
     * Run the SABR download, re-priming a fresh spec once if the PO token is rejected mid-stream. Each
     * session records its segments in [segmentIndex] and seeds its resume cursor from the on-disk file
     * length via that index — so pause/resume and re-attest continue from the last segment (see
     * [SabrSession.download]), rather than re-fetching from zero.
     */
    private suspend fun downloadTracks(
        spec: SabrStreamSpec,
        audioFmt: AdaptiveFormat,
        videoFmt: AdaptiveFormat?,
        audioFile: DownloadFile,
        videoFile: DownloadFile?,
        onProgress: (Long, Long) -> Unit,
        reAttest: suspend () -> SabrStreamSpec,
    ) {
        val progress = SabrProgress { aDone, aLen, vDone, vLen -> onProgress(aDone + vDone, aLen + vLen) }
        var current = spec
        var invalid: SabrPoTokenInvalid? = null
        while (true) {
            try {
                SabrSession(logger = log).download(
                    client = client,
                    userAgent = userAgent,
                    spec = current,
                    audio = SabrTrack(audioFmt, audioFile),
                    video = videoFmt?.let { SabrTrack(it, videoFile!!) },
                    progress = progress,
                    mediaCache = mediaCache,
                    cacheReadOnly = true, // reuse cached playback bytes, but never write into the player cache
                    segmentIndex = segmentIndex,
                )
                return
            } catch (e: SabrPoTokenInvalid) {
                invalid?.let {
                    e.addSuppressed(it)
                    throw sabrFailure(e)
                }
                invalid = e
                log?.log(Logger.Level.WARN, "PO token invalid, re-attesting: ${e.message}")
                current = try {
                    reAttest()
                } catch (error: Throwable) {
                    error.addSuppressed(e)
                    throw error
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw sabrFailure(e)
            }
        }
    }
}

/** An uncoded SABR session failure as its site code; coded, network and disk-full failures pass through. */
private fun sabrFailure(error: Throwable): Throwable = when {
    error.codedError() != null -> error
    error is SabrPoTokenInvalid -> TechnicalException("E70050", "PO token rejected after a re-attest: ${error.message}", cause = error)
    error is SabrAttestationPending -> TechnicalException("E70051", error.message ?: "attestation pending", cause = error)
    error is SabrServerError -> TechnicalException("E70052", error.message ?: "SABR server error", cause = error)
    else -> TechnicalException("E70053", "SABR download failed: ${error.message}", cause = error)
}
