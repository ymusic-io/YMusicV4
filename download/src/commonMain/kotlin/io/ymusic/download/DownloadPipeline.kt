package io.ymusic.download

import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SegmentIndex
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use
import projectcommon.error.TechnicalException

/**
 * Resolves the SABR inputs for a video (player response + PO token). Implemented by the app shell
 * over its `YtEngine`; the download module stays headless.
 */
fun interface SabrSpecResolver {
    suspend fun resolve(videoId: String): ResolvedVideo
}

/** A resolved video: the SABR [spec], plus the file tags read from the same player response. */
class ResolvedVideo(val spec: SabrStreamSpec, val artist: String? = null)

/**
 * Executes one [DownloadTask] end to end, over a [DownloadStorage] (no direct FileSystem — so the same
 * pipeline works whether the final file is a `file://` path or an Android `content://` SAF document):
 *
 *  1a. download the elementary stream(s) + captions to scratch files ([YtStreamDownloader]).
 *  1b. audio-only downloads: fetch the largest JPEG thumbnail as cover art ([CoverArt]), best-effort.
 *  2.  mux/convert + embed metadata with [ffmpeg] into a final scratch file.
 *  3.  clean up the elementary scratch files.
 *  4.  publish the final scratch to the destination (returns its uri) — the shell then adds it to the
 *      local library ([DownloadService.onCompleted]).
 *
 * Audio elementary streams are already valid container files, so audio-only + no-ffmpeg (iOS M4A) skips
 * step 2 — the raw stream is published as-is and its metadata lives in the DB task row.
 *
 * The final scratch is marked ready before step 4, so a run that fails only to publish is retried from
 * step 4 alone (the scratch folder outlives the failure; only cancel or a successful publish deletes it).
 */
class DownloadPipeline(
    resolver: SabrSpecResolver,
    client: KmpHttpClient,
    userAgent: String,
    private val ffmpeg: Ffmpeg?,
    private val storage: DownloadStorage,
    mediaCache: MediaCache?,
    segmentIndex: SegmentIndex?,
    private val logger: Logger?,
) {

    private val downloader = YtStreamDownloader(resolver, client, userAgent, storage, mediaCache, segmentIndex, logger)
    private val coverArt = CoverArt(client, userAgent, storage, logger)

    /** A completed run: the published file's uri + byte size, and the media facts library ingest uses. */
    class Result(val uri: String, val bytes: Long, val durationMs: Long?, val width: Int?, val height: Int?)

    suspend fun run(
        task: DownloadTask,
        destinationFolder: String,
        onPreparing: () -> Unit,
        onDownloading: () -> Unit,
        onProcessing: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
        /** The output (WebM video unavailable → MP4) or streams differ from the task's; see [YtStreamDownloader.download]. */
        onStreams: suspend (format: DownloadFormat, audio: StreamId, video: StreamId?) -> Unit = { _, _, _ -> },
    ): Result {
        // A previous run already produced the final file and failed only to publish it (e.g. the download
        // folder lost its write grant): publish that again, skipping steps 1-2.
        readyOutput(task.id)?.let { ready ->
            onProcessing()
            return publish(task, ready, destinationFolder)
        }

        // 1. download the elementary streams (+ captions) to scratch files.
        val streams = downloader.download(task, onPreparing, onDownloading, onStreams, onProgress)
        return assemble(task.copy(format = streams.format), streams, destinationFolder, onProcessing, onProgress)
    }

    /** Steps 2-3 for the downloaded [streams]: mux/convert into the final file and publish it. */
    private suspend fun assemble(
        task: DownloadTask,
        streams: DownloadedStreams,
        destinationFolder: String,
        onProcessing: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result {
        val audioFmt = streams.audio.format
        val videoFmt = streams.video?.format

        onProcessing()

        // No-ffmpeg M4A (iOS): the raw best-AAC stream IS a playable .m4a — publish it as-is, no step 2
        // (metadata lives in the DB task row). Captions can't be muxed without ffmpeg here, so drop them.
        if (task.format == DownloadFormat.M4A && ffmpeg == null) {
            return publish(task, markReady(task.id, AUDIO_SCRATCH, audioFmt, null), destinationFolder)
        }

        // 2. mux/convert + metadata into the final scratch, reporting ffmpeg's output-time progress. The
        // media duration bounds it; we map output-time → the same done/total the UI's bar reads. Start at 0
        // so the bar refills for the processing phase rather than jumping from the download's 100%.
        val ff = ffmpeg ?: throw TechnicalException(
            "E70020", "ffmpeg unavailable: ${task.format} needs transcode/mux on this platform",
        )
        val durationMs = maxOf(audioFmt.approxDurationMs, videoFmt?.approxDurationMs ?: 0).toLong()
        val onFfProgress: ((Long) -> Unit)? = if (durationMs > 0) {
            { us -> onProgress((us / 1000).coerceIn(0, durationMs), durationMs) }
        } else {
            null
        }
        if (durationMs > 0) onProgress(0, durationMs)

        val audio = streams.audio.scratch
        val video = streams.video?.scratch
        val out = storage.scratch(task.id, OUT_SCRATCH)
        var outName = OUT_SCRATCH
        val meta = task.tags(streams.artist)
        val subs = streams.captions.map { SubtitleInput(it.scratch.path, it.languageCode, it.name) }
        when (task.format) {
            DownloadFormat.MP3 -> {
                val encoding = Mp3Encoding.of(task.audioQuality)
                withCover(task) { cover -> ff.toMp3(audio.path, out.path, encoding, cover, meta, onFfProgress) }
                if (streams.captions.isNotEmpty()) outName = withLyrics(task.id, out, streams.captions)
            }
            DownloadFormat.M4A -> withCover(task) { cover ->
                ff.toM4a(audio.path, out.path, captions = subs, cover = cover, metadata = meta, onProgressUs = onFfProgress)
            }
            DownloadFormat.AUDIO_WEBM -> {
                ff.toOpus(audio.path, out.path, meta, onFfProgress)
                outName = withOpusTags(task, out, streams.captions)
            }
            DownloadFormat.VIDEO_WEBM -> ff.muxWebm(video!!.path, audio.path, out.path, captions = subs, metadata = meta, onProgressUs = onFfProgress)
            DownloadFormat.VIDEO_MP4 -> ff.muxMp4(video!!.path, audio.path, out.path, subs, meta, onFfProgress)
        }

        // 3. publish the final file (moved out of the scratch folder).
        return publish(task, markReady(task.id, outName, audioFmt, videoFmt), destinationFolder)
    }

    /** The finished scratch [scratchName] plus the media facts the [Result] carries — persisted as the
     *  task's [READY_SCRATCH] marker so a run that failed at publish can be retried from publish alone. */
    private class ReadyOutput(val scratchName: String, val durationMs: Long?, val width: Int?, val height: Int?)

    private fun markReady(taskId: String, scratchName: String, audio: AdaptiveFormat, video: AdaptiveFormat?): ReadyOutput {
        val ready = ReadyOutput(scratchName, audio.approxDurationMs.toLong().takeIf { it > 0 }, video?.width, video?.height)
        val marker = storage.scratch(taskId, READY_SCRATCH)
        marker.delete()
        marker.appendSink().use { sink ->
            sink.writeUtf8(listOf(ready.scratchName, ready.durationMs ?: "", ready.width ?: "", ready.height ?: "").joinToString("\n"))
        }
        return ready
    }

    /** The task's [READY_SCRATCH] marker, or null when there is none or the file it names is gone. */
    private fun readyOutput(taskId: String): ReadyOutput? {
        val marker = storage.scratch(taskId, READY_SCRATCH)
        if (marker.length() == 0L) return null
        val fields = marker.source().buffer().use { it.readUtf8() }.split('\n')
        val ready = ReadyOutput(
            scratchName = fields[0],
            durationMs = fields.getOrNull(1)?.toLongOrNull(),
            width = fields.getOrNull(2)?.toIntOrNull(),
            height = fields.getOrNull(3)?.toIntOrNull(),
        )
        if (storage.scratch(taskId, ready.scratchName).length() > 0) return ready
        marker.delete()
        return null
    }

    /** Publish [ready]'s scratch into [destinationFolder], then drop the task's whole scratch folder —
     *  elementary streams, captions and the marker in one delete. */
    // Not cancellable: a pause landing mid-copy would leave the file published but the task resumable,
    // and its resume would publish a second copy.
    private suspend fun publish(task: DownloadTask, ready: ReadyOutput, destinationFolder: String): Result =
        withContext(NonCancellable) {
            val scratch = storage.scratch(task.id, ready.scratchName)
            val bytes = scratch.length()
            val uri = storage.publish(scratch, "${baseName(task)}.${task.format.fileExtension}", destinationFolder)
            storage.deleteScratch(task.id)
            Result(uri, bytes, ready.durationMs, ready.width, ready.height)
        }

    /** Run [convert] with the cover art's path; a cover ffmpeg rejects is dropped and the conversion rerun
     *  without it, so the song still publishes — cover art is best-effort like lyrics. */
    private suspend fun withCover(task: DownloadTask, convert: suspend (cover: String?) -> Unit) {
        val cover = coverArt.fetch(task) ?: return convert(null)
        try {
            convert(cover.path)
        } catch (e: TechnicalException) {
            logger?.log(Logger.Level.WARN, "ffmpeg failed with cover art; retrying without it", e)
            try {
                convert(null)
            } catch (retryError: Throwable) {
                retryError.addSuppressed(e)
                throw retryError
            }
        }
    }

    /** The name of the scratch holding [mp3] (the [OUT_SCRATCH]) with [captions] written into its tag as LRC
     *  lyrics — a new scratch, or [OUT_SCRATCH] itself when there is nothing to write or the tag can't take
     *  it. Best-effort like the captions themselves: a failure is logged and the song still publishes, just
     *  without lyrics. */
    private fun withLyrics(taskId: String, mp3: DownloadScratch, captions: List<DownloadedCaption>): String {
        val tagged = storage.scratch(taskId, OUT_LYRICS_SCRATCH)
        try {
            val lyrics = captions.mapNotNull { cc ->
                val lrc = cc.scratch.source().buffer().use { VttToLrc.convert(it.readUtf8()) }
                lrc.takeIf { it.isNotEmpty() }?.let { Lyrics(iso639_2(cc.languageCode), cc.name, it) }
            }
            if (lyrics.isEmpty()) return OUT_SCRATCH
            tagged.delete()
            val written = mp3.source().buffer().use { source ->
                tagged.appendSink().use { sink -> Id3Lyrics.insert(source, sink, lyrics) }
            }
            if (written) return OUT_LYRICS_SCRATCH
            logger?.log(Logger.Level.WARN, "mp3 tag can't take lyrics; publishing without them")
        } catch (e: Exception) {
            logger?.log(Logger.Level.WARN, "embedding lyrics failed; publishing without them", e)
        }
        tagged.delete()
        return OUT_SCRATCH
    }

    /** The name of the scratch holding [opus] (the [OUT_SCRATCH]) with cover art and [captions] (as LRC lyrics)
     *  in its tags — a new scratch, or [OUT_SCRATCH] itself when there is nothing to add. Best-effort tag by
     *  tag like both of those: a failure is logged and the song still publishes with the rest. */
    private suspend fun withOpusTags(task: DownloadTask, opus: DownloadScratch, captions: List<DownloadedCaption>): String {
        val comments = buildList {
            opusComment("cover") {
                coverArt.fetch(task)?.let { cover -> OggOpusTags.pictureComment(cover.source().buffer().use { it.readByteArray() }) }
            }?.let(::add)
            captions.forEach { cc ->
                opusComment("lyrics ${cc.languageCode}") {
                    val lrc = cc.scratch.source().buffer().use { VttToLrc.convert(it.readUtf8()) }
                    if (lrc.isEmpty()) null else "LYRICS=$lrc"
                }?.let(::add)
            }
        }
        if (comments.isEmpty()) return OUT_SCRATCH
        val tagged = storage.scratch(task.id, OUT_LYRICS_SCRATCH)
        try {
            tagged.delete()
            opus.source().buffer().use { source ->
                tagged.appendSink().use { sink -> OggOpusTags.insert(source, sink, comments) }
            }
            return OUT_LYRICS_SCRATCH
        } catch (e: Exception) {
            logger?.log(Logger.Level.WARN, "opus tags failed; publishing without them", e)
        }
        tagged.delete()
        return OUT_SCRATCH
    }

    private inline fun opusComment(tag: String, build: () -> String?): String? =
        try {
            build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.log(Logger.Level.WARN, "opus $tag failed; publishing without it", e)
            null
        }

    /** Container tags embedded into the finished file. The videoId goes in the standard `composer` field
     *  — it survives in both ID3 (TCOM) and MP4 (©wrt), and the app's ffmpeg metadata parser reads it
     *  (`MetadataMap.composer`), unlike a custom atom MP4 hides. Title always; artist from the request,
     *  else [resolvedArtist] from the player response (album is not modelled yet). */
    private fun DownloadTask.tags(resolvedArtist: String?): List<Pair<String, String>> = buildList {
        add("title" to title)
        (artist ?: resolvedArtist)?.let { add("artist" to it) }
        add("composer" to videoId)
    }

    /** The media file's stem: the user's [DownloadTask.fileName] when set
     *  (the destination de-duplicates a clash), else `Title-videoId`. Callers append the extension. */
    private fun baseName(task: DownloadTask): String {
        task.fileName?.let { DownloadFileNames.finalize(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        val title = task.title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim()
            .let { DownloadFileNames.capBytes(it, DownloadFileNames.MAX_STEM_BYTES - task.videoId.length - 1) }
            .ifBlank { task.videoId }
        return "$title-${task.videoId}"
    }
}

internal const val AUDIO_SCRATCH = "audio"
private const val OUT_SCRATCH = "out"
private const val OUT_LYRICS_SCRATCH = "out-lyrics"
private const val READY_SCRATCH = "ready"
