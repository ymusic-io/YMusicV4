package io.ymusic.mediastore.internal

import io.ymusic.mediastore.model.MediaStoreBucket
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.mediastore.model.Playlist
import io.ymusic.mediastore.model.PlaylistItem
import kmpcommon.uri.Uri
import io.ymusic.mediastore.db.Bucket as DbBucket
import io.ymusic.mediastore.db.MediaItem as DbMediaItem
import io.ymusic.mediastore.db.Playlist as DbPlaylist
import io.ymusic.mediastore.db.PlaylistItem as DbPlaylistItem

// SQLite stores every integer as Long; narrow the fields the model exposes as Int.
internal fun DbBucket.toModel() = MediaStoreBucket(
    id = id,
    parentId = parentId,
    uri = Uri.parse(uri),
    name = name,
    aliasName = aliasName,
    excluded = excluded != 0L,
    createdAt = createdAt,
)

internal fun DbMediaItem.toModel() = MediaStoreItem(
    id = id,
    bucketId = bucketId,
    uri = Uri.parse(uri),
    title = title,
    mimeType = mimeType,
    artist = artist,
    album = album,
    genre = genre,
    trackNumber = trackNumber?.toInt(),
    year = year?.toInt(),
    durationMs = durationMs,
    audioCodec = audioCodec,
    videoCodec = videoCodec,
    subtitleCodec = subtitleCodec,
    bitrate = bitrate?.toInt(),
    width = width?.toInt(),
    height = height?.toInt(),
    thumbnailStreamIndex = thumbnailStreamIndex?.toInt(),
    youtubeVid = youtubeVid,
    youtubeItags = youtubeItags,
    fileSize = fileSize,
    lastModified = lastModified,
    createdAt = createdAt,
    loudnessGainDb = loudnessGainDb,
)

internal fun DbPlaylist.toModel() = Playlist(id = id, name = name, createdAt = createdAt)

internal fun DbPlaylistItem.toModel() = PlaylistItem(
    playlistId = playlistId,
    code = code,
    sortKey = sortKey,
    addedAt = addedAt,
)
