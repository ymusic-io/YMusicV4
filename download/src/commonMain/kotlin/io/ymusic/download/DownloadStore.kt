package io.ymusic.download

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.download.db.DownloadDatabase
import io.ymusic.download.internal.downloadDbDispatcher
import kmpcommon.log.Logger
import kotlinx.coroutines.withContext

/**
 * Persistence for the download queue and the single source of truth — a thin suspend facade over the
 * generated SqlDelight queries, all work confined to [downloadDbDispatcher]. The service writes here and
 * the UI's [DownloadTasksAggregator] reads/observes here; nothing holds the whole list resident. Build the
 * [driver] with the per-platform `downloadDatabase*Driver` factory.
 */
class DownloadStore(driver: SqlDriver, logger: Logger? = null) {

    private val queries = DownloadDatabase(driver).downloadTaskQueries
    private val log = logger?.fork("download-store")

    suspend fun all(): List<DownloadTask> = withContext(downloadDbDispatcher()) {
        queries.selectAll(::mapRow).executeAsList()
    }

    suspend fun get(id: String): DownloadTask? = withContext(downloadDbDispatcher()) {
        queries.selectById(id, ::mapRow).executeAsOneOrNull()
    }

    /** The oldest [DownloadState.QUEUED] task — what the worker runs next. */
    suspend fun nextQueued(): DownloadTask? = withContext(downloadDbDispatcher()) {
        queries.selectNextQueued(DownloadState.QUEUED.name, ::mapRow).executeAsOneOrNull()
    }

    suspend fun upsert(task: DownloadTask): Unit = withContext(downloadDbDispatcher()) {
        queries.upsert(
            id = task.id,
            video_id = task.videoId,
            title = task.title,
            artist = task.artist,
            thumbnail_url = task.thumbnailUrl,
            format = task.format.name,
            requested_height = task.requestedHeight?.toLong(),
            state = task.state.name,
            downloaded_bytes = task.downloadedBytes,
            total_bytes = task.totalBytes,
            file_path = task.fileUri,
            error_message = task.errorMessage,
            created_at = task.createdAt,
            updated_at = task.updatedAt,
            file_name = task.fileName,
            audio_track_id = task.audioTrackId,
            caption_langs = task.captions?.languages?.joinToString(","),
            audio_language = task.audioLanguage,
            caption_language = task.captionLanguage,
            error_code = task.errorCode,
            audio_quality = task.audioQuality.name,
            audio_itag = task.audioStream?.itag?.toLong(),
            audio_lmt = task.audioStream?.lmt,
            video_itag = task.videoStream?.itag?.toLong(),
            video_lmt = task.videoStream?.lmt,
            loudness_gain_db = task.loudnessGainDb,
        )
    }

    suspend fun updateState(id: String, state: DownloadState, error: DownloadError?, updatedAt: Long): Unit =
        withContext(downloadDbDispatcher()) { queries.updateState(state.name, error?.userMessage, error?.code, updatedAt, id) }

    suspend fun updateStreams(id: String, format: DownloadFormat, audio: StreamId, video: StreamId?, updatedAt: Long): Unit =
        withContext(downloadDbDispatcher()) {
            queries.updateStreams(format.name, audio.itag.toLong(), audio.lmt, video?.itag?.toLong(), video?.lmt, updatedAt, id)
        }

    suspend fun updateProgress(id: String, downloaded: Long, total: Long, updatedAt: Long): Unit =
        withContext(downloadDbDispatcher()) { queries.updateProgress(downloaded, total, updatedAt, id) }

    suspend fun updateCompleted(id: String, fileUri: String, bytes: Long, loudnessGainDb: Double?, updatedAt: Long): Unit =
        withContext(downloadDbDispatcher()) {
            queries.updateCompleted(DownloadState.COMPLETED.name, fileUri, bytes, bytes, loudnessGainDb, updatedAt, id)
        }

    /** Reset every in-flight or queued task to PAUSED in one statement — startup crash recovery. */
    suspend fun pauseActiveAndQueued(updatedAt: Long): Unit = withContext(downloadDbDispatcher()) {
        val states = DownloadState.entries.filter { it.isActive }.map { it.name }
        queries.pauseActiveAndQueued(paused = DownloadState.PAUSED.name, updatedAt = updatedAt, states = states)
    }

    suspend fun delete(id: String): Unit = withContext(downloadDbDispatcher()) { queries.deleteById(id) }

    @Suppress("LongParameterList")
    private fun mapRow(
        id: String,
        video_id: String,
        title: String,
        artist: String?,
        thumbnail_url: String?,
        format: String,
        requested_height: Long?,
        state: String,
        downloaded_bytes: Long,
        total_bytes: Long,
        file_path: String?,
        error_message: String?,
        created_at: Long,
        updated_at: Long,
        file_name: String?,
        audio_track_id: String?,
        caption_langs: String?,
        audio_language: String?,
        caption_language: String?,
        error_code: String?,
        mp3_source_bitrate: Long,
        audio_quality: String,
        audio_itag: Long?,
        audio_lmt: Long?,
        video_itag: Long?,
        video_lmt: Long?,
        loudness_gain_db: Double?,
    ) = DownloadTask(
        id = id,
        videoId = video_id,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnail_url,
        format = runCatching { DownloadFormat.valueOf(format) }
            .onFailure { log?.log(Logger.Level.WARN, "task $id has unknown format '$format', defaulting M4A", it) }
            .getOrDefault(DownloadFormat.M4A),
        requestedHeight = requested_height?.toInt(),
        state = runCatching { DownloadState.valueOf(state) }
            .onFailure { log?.log(Logger.Level.WARN, "task $id has unknown state '$state', defaulting FAILED", it) }
            .getOrDefault(DownloadState.FAILED),
        downloadedBytes = downloaded_bytes,
        totalBytes = total_bytes,
        fileUri = file_path,
        errorMessage = error_message,
        createdAt = created_at,
        updatedAt = updated_at,
        fileName = file_name,
        audioTrackId = audio_track_id,
        captions = caption_langs?.let { langs -> CaptionChoice(langs.split(',').filter { it.isNotEmpty() }) },
        audioLanguage = audio_language,
        captionLanguage = caption_language,
        errorCode = error_code,
        audioQuality = AudioQuality.entries.firstOrNull { it.name == audio_quality } ?: AudioQuality.BEST,
        audioStream = streamId(audio_itag, audio_lmt),
        videoStream = streamId(video_itag, video_lmt),
        loudnessGainDb = loudness_gain_db,
    )

    private fun streamId(itag: Long?, lmt: Long?): StreamId? =
        if (itag != null && lmt != null) StreamId(itag.toInt(), lmt) else null
}
