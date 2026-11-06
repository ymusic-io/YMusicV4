package io.ymusic.download

import projectcommon.error.CodedError

/**
 * The container/codec a download is delivered as. The pipeline that produces each is in
 * [DownloadPipeline]; which are available depends on the platform (ffmpeg presence):
 *
 * - [MP3] / [M4A] / [AUDIO_WEBM] — audio only. MP3 decodes the highest-bitrate audio of any codec and
 *   encodes it with libmp3lame; M4A stream-copies the AAC track, AUDIO_WEBM the Opus track.
 * - [VIDEO_WEBM] — VP9/AV1 video + Opus audio, stream-copied into one WebM.
 * - [VIDEO_MP4] — video + AAC audio, stream-copied into one MP4.
 *
 * Only MP3 re-encodes: every source is lossy, so any other output just muxes the original streams.
 *
 * Every output goes through ffmpeg where it exists — not just to transcode/mux, but to embed the
 * metadata tags (title / artist / composer=videoId|gain) into the container. iOS has no ffmpeg: there M4A
 * degrades to the raw best-AAC stream written straight to the file, its metadata living in the download
 * DB row instead (video would need an AVFoundation muxer — separate). So on iOS only M4A is offered;
 * MP3/video are hidden — the picker gates on [DownloadService.ffmpegAvailable].
 */
enum class DownloadFormat(val kind: DownloadKind, val fileExtension: String) {
    MP3(DownloadKind.AUDIO, "mp3"),
    M4A(DownloadKind.AUDIO, "m4a"),
    /** Opus in Ogg. The name is persisted on tasks, so it outlives the container it was named for. */
    AUDIO_WEBM(DownloadKind.AUDIO, "opus"),
    VIDEO_WEBM(DownloadKind.VIDEO, "webm"),
    VIDEO_MP4(DownloadKind.VIDEO, "mp4"),
}

/** The container has no subtitle tracks, so captions go in as LRC lyrics. */
val DownloadFormat.captionsAsLyrics: Boolean get() = this == DownloadFormat.MP3 || this == DownloadFormat.AUDIO_WEBM

enum class DownloadKind { AUDIO, VIDEO }

/** An audio-only download's size/quality tier, lowest first. */
enum class AudioQuality { SAVE, MEDIUM, BEST }

/**
 * Lifecycle of one download. Only [QUEUED], [PAUSED] and [FAILED] are resumable entry points; the
 * worker drives a task through [PREPARING] → [DOWNLOADING] → [PROCESSING] → [COMPLETED]. [CANCELED]
 * and [COMPLETED] are terminal.
 */
enum class DownloadState {
    /** Waiting for a worker. */
    QUEUED,

    /** Priming the SABR inputs (player response + PO token) — needs a live WebView. */
    PREPARING,

    /** Streaming the elementary track(s) to disk. [DownloadTask.downloadedBytes]/[totalBytes] advance. */
    DOWNLOADING,

    /** ffmpeg transcode/mux of the downloaded track(s) into the final container. */
    PROCESSING,

    /** Final file written and (if a library sink is set) registered. Terminal. */
    COMPLETED,

    /** User-paused; keeps any partially-downloaded bytes for resume. */
    PAUSED,

    /** Failed with [DownloadTask.errorCode]; retryable. */
    FAILED,

    /** User-canceled; partial files cleaned up. Terminal. */
    CANCELED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELED

    /** In flight from the user's perspective: waiting for a slot or actively being worked. NOT a
     *  worker-slot predicate — the service picks work with explicit [QUEUED] checks. */
    val isActive: Boolean get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == PROCESSING
}

/** What the caller asks to download; the [videoId] plus display metadata and the desired output. */
class DownloadRequest(
    val videoId: String,
    val title: String,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    val format: DownloadFormat,
    /** For [DownloadKind.VIDEO]: the target height (e.g. 1080); the nearest available is chosen. Null = best. */
    val requestedHeight: Int? = null,
    /** The output file's stem (no extension), already user-edited; null = derived from [title]. Sanitized
     *  again at publish ([DownloadFileNames.finalize]). */
    val fileName: String? = null,
    /** The `audioTrack.id` to download (a dubbed language); null = the video's default track. */
    val audioTrackId: String? = null,
    /** The caption tracks to fetch; null = [captionLanguage]. */
    val captions: CaptionChoice? = null,
    /** A preferred audio language (`en`), resolved when the download runs: the dubbed track in it, else
     *  the default track. Used when [audioTrackId] is null. */
    val audioLanguage: String? = null,
    /** A preferred caption language, resolved when the download runs: the track in it (uploaded,
     *  auto-generated or auto-translated), else the video's default caption. Used when [captions] is null. */
    val captionLanguage: String? = null,
    /** Audio-only outputs: the stream tier for Opus/M4A, the encoding for MP3. */
    val audioQuality: AudioQuality = AudioQuality.BEST,
)

/** One exact stream of a video: [lmt] changes when YouTube re-encodes an [itag]. */
data class StreamId(val itag: Int, val lmt: Long)

/** Captions picked for one download — caption keys ([captionKey]). Embedded as subtitle tracks; a format
 *  with none ([captionsAsLyrics]) gets them as LRC lyrics in its tags. */
class CaptionChoice(val languages: List<String>)

/**
 * One row of the download queue — the persisted, observable unit the UI renders. Immutable snapshot;
 * the store emits a new instance on every change. [downloadedBytes]/[totalBytes] are the sum across
 * the (one or two) elementary tracks during [DownloadState.DOWNLOADING].
 */
class DownloadTask(
    val id: String,
    val videoId: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val format: DownloadFormat,
    val requestedHeight: Int?,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** URI of the finished file once [state] is [DownloadState.COMPLETED]; null before. A platform URI —
     *  on Android a `content://` SAF document in one of the user's Library folders, a `file://`/path
     *  elsewhere — so it round-trips to the player and the library regardless of where the file lives. */
    val fileUri: String?,
    /** A failure's display-ready, already-localized text minted at its origin ([CodedError.userMessage]);
     *  null when it had none — the UI then renders [errorCode]. */
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val fileName: String? = null,
    val audioTrackId: String? = null,
    val captions: CaptionChoice? = null,
    val audioLanguage: String? = null,
    val captionLanguage: String? = null,
    /** The failure's [CodedError.code] — a well-known category or an `E#####` site code; null unless FAILED. */
    val errorCode: String? = null,
    val audioQuality: AudioQuality = AudioQuality.BEST,
    /** The streams the scratches hold, kept across runs: their bytes can only continue those streams. */
    val audioStream: StreamId? = null,
    val videoStream: StreamId? = null,
    /** dB from the file's measured loudness to its target; null until completed, or when unmeasured. */
    val loudnessGainDb: Double? = null,
) {
    /** 0f..1f, or null when the total isn't known yet (before the first MEDIA_HEADER). */
    val progress: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    fun copy(
        format: DownloadFormat = this.format,
        state: DownloadState = this.state,
        downloadedBytes: Long = this.downloadedBytes,
        totalBytes: Long = this.totalBytes,
        fileUri: String? = this.fileUri,
        errorMessage: String? = this.errorMessage,
        errorCode: String? = this.errorCode,
        updatedAt: Long = this.updatedAt,
    ) = DownloadTask(
        id, videoId, title, artist, thumbnailUrl, format, requestedHeight,
        state, downloadedBytes, totalBytes, fileUri, errorMessage, createdAt, updatedAt,
        fileName, audioTrackId, captions, audioLanguage, captionLanguage, errorCode, audioQuality, audioStream, videoStream,
    )
}

/** A failed task's error as the user sees it: the [CodedError.code] plus its origin-minted
 *  [CodedError.userMessage], if any. */
data class DownloadError(val code: String, val userMessage: String?)

/** A caption track's identity within one video: its `languageCode`, `a.`-prefixed for the auto-generated
 *  (ASR) track — a video can carry both a manual and an ASR track in the same language. */
fun captionKey(languageCode: String, isAsr: Boolean): String = if (isAsr) "a.$languageCode" else languageCode
