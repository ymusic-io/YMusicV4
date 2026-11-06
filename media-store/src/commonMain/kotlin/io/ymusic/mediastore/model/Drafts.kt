package io.ymusic.mediastore.model

import kmpcommon.uri.Uri

/**
 * Write payload for creating/updating a bucket. Carries only caller-supplied fields; the store owns
 * [MediaStoreBucket.id], timestamps and the soft-delete flag.
 */
data class BucketDraft(
    val uri: Uri,
    val name: String,
    val parentId: Long? = null,
    val aliasName: String? = null,
)

/**
 * Write payload for creating/updating a media item. Carries only caller-supplied metadata; the store
 * owns [MediaStoreItem.id], [MediaStoreItem.bucketId] (passed separately), timestamps and the
 * soft-delete flag.
 */
data class MediaItemDraft(
    val uri: Uri,
    val title: String,
    val mimeType: String,
    val fileSize: Long,
    val lastModified: Long,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val subtitleCodec: String? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailStreamIndex: Int? = null,
    val youtubeVid: String? = null,
    val youtubeItags: String? = null,
    val loudnessGainDb: Double? = null,
)
