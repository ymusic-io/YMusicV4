package io.ymusic.mediastore.model

import kmpcommon.uri.Uri

/**
 * A single audio/video file with its extracted metadata. Identified externally by [uri] and
 * internally by the database-generated [id].
 *
 * [fileSize] and [lastModified] are the cheap change/move fingerprint: an unchanged size+mtime lets
 * a scanner skip re-reading the file, and a soft-deleted item whose [fileSize] reappears under a new
 * uri in the same bucket is treated as the *same* file moved (its [id] is preserved) rather than a
 * duplicate — see BetterMediaStore.upsertItem.
 */
data class MediaStoreItem(
    val id: Long,
    /** Enclosing bucket, or null for an orphan item — no containing bucket yet (e.g. added by a
     *  single-file scan, before its folder is scanned and adopts it). */
    val bucketId: Long?,
    val uri: Uri,
    val title: String,
    val mimeType: String,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val trackNumber: Int?,
    val year: Int?,
    val durationMs: Long?,
    val audioCodec: String?,
    val videoCodec: String?,
    val subtitleCodec: String?,
    val bitrate: Int?,
    val width: Int?,
    val height: Int?,
    val thumbnailStreamIndex: Int?,
    val youtubeVid: String?,
    val youtubeItags: String?,
    val fileSize: Long,
    val lastModified: Long,
    /** When this item was first indexed into the store (epoch millis) — the "date added". */
    val createdAt: Long,
    /** dB from the file's measured loudness to its target; null when unmeasured. */
    val loudnessGainDb: Double? = null,
) {
    /**
     * Stable content fingerprint used for playlist membership (see PlaylistItem): the YouTube video
     * id if present, else "@fileSize:durationSec". Derived from content, so it survives moves,
     * renames and rescans — a playlist keyed by this code never orphans.
     */
    val playlistCode: String get() = youtubeVid ?: "@$fileSize:${(durationMs ?: 0) / 1000}"
}
