package io.ymusic.ui

import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadPipeline
import io.ymusic.download.DownloadTask
import io.ymusic.mediascanner.MediaScanner
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.MediaItemDraft
import io.ymusic.settings.SettingsStore
import kmpcommon.uri.Uri
import kotlinx.coroutines.flow.collect

/**
 * Register a finished download into the local library so it shows up in the Library. Wired as the
 * `DownloadService.onCompleted` hook by the shells. The file lives in the user's download-location folder
 * (a media-store bucket), so we file it under that bucket keyed on the file uri — no full rescan needed.
 *
 * Metadata comes from [scanner] when the shell has one (real duration/dimensions probed from the file, and
 * the YouTube id recovered from the `composer` tag the pipeline embeds) — the same path the Library scan
 * uses, but pinned to the known bucket instead of orphaning the new file. Without a scanner (no ffmpeg —
 * e.g. an m4a copy on a host lacking it) we fall back to a draft built from what the download already knows.
 *
 * No-op when there's no download location (the file went to the app default dir, not a Library folder).
 */
suspend fun registerCompletedDownload(
    mediaStore: BetterMediaStore,
    settings: SettingsStore,
    scanner: MediaScanner?,
    task: DownloadTask,
    result: DownloadPipeline.Result,
) {
    val folder = settings.downloadLocationFolder() ?: return
    val bucket = mediaStore.findBucketByUri(Uri.parse(folder)) ?: return
    val fileUri = Uri.parse(result.uri)

    if (scanner != null) {
        // Probe the file into its folder's bucket (not an orphan). If the probe finds nothing indexable
        // it adds nothing — the hand-built fallback below only runs when there's no scanner at all.
        scanner.scan(fileUri, intoBucket = bucket.id).collect()
        return
    }

    mediaStore.upsertItem(
        bucketId = bucket.id,
        draft = MediaItemDraft(
            uri = fileUri,
            title = task.title,
            mimeType = task.format.mimeType(),
            fileSize = result.bytes,
            lastModified = task.updatedAt,
            artist = task.artist,
            durationMs = result.durationMs,
            width = result.width,
            height = result.height,
            youtubeVid = task.videoId,
        ),
    )
}

internal fun DownloadFormat.mimeType(): String = when (this) {
    DownloadFormat.MP3 -> "audio/mpeg"
    DownloadFormat.M4A -> "audio/mp4"
    DownloadFormat.AUDIO_WEBM -> "audio/ogg"
    DownloadFormat.VIDEO_MP4 -> "video/mp4"
    DownloadFormat.VIDEO_WEBM -> "video/webm"
}
