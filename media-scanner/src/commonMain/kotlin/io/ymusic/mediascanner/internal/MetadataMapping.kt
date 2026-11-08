package io.ymusic.mediascanner.internal

import io.ymusic.mediascanner.DocumentInfo
import io.ymusic.mediastore.model.MediaItemDraft
import kmpffmpeg.metadata.MediaFileMetadata
import kmpffmpeg.metadata.StreamMetadata
import kmpffmpeg.metadata.isAttachedThumbnail

private val videoIdFromComposer = Regex("^([a-zA-Z0-9-_]{11})(\\||$)")
private val loudnessGainFromComposer = Regex("^[a-zA-Z0-9-_]{11}\\|gain=(-?\\d+(?:\\.\\d+)?)$")
private val videoIdFromFileName =
    Regex("\\s*\\[([a-zA-Z0-9-_]{11})]\\s*([-_()\\[\\]\\d]+)?\\s*(\\.\\w+)?$")
private val yearRegex = Regex("[12][0-9]{3}")

/**
 * Maps a probed [MediaFileMetadata] to a [MediaItemDraft], filling gaps from the [file] stat.
 * Port of YMusic-legacy `Utils.toDatabaseRecord` + `MediaStoreProcessor.extractItem`.
 */
internal fun MediaFileMetadata.toMediaItemDraft(file: DocumentInfo.File): MediaItemDraft {
    val tagBags = listOf(container.metadataMap) + streams.map { it.metadataMap }
    fun firstTag(select: (kmpffmpeg.metadata.MetadataMap) -> String?): String? =
        tagBags.firstNotNullOfOrNull { select(it)?.takeIf { s -> s.isNotBlank() } }

    val title = firstTag { it.title } ?: file.name.substringBeforeLast('.')

    val trackNumber = firstTag { it.track }
        ?.split('/')?.firstOrNull()?.trim()?.toIntOrNull()

    val year = firstTag { it.year }
        ?.let { yearRegex.find(it)?.value?.toIntOrNull() }

    val audio = streams.filterIsInstance<StreamMetadata.Audio>().firstOrNull()
    val video = streams.filterIsInstance<StreamMetadata.Video>().firstOrNull { !it.isAttachedThumbnail() }
    val subtitle = streams.filterIsInstance<StreamMetadata.Text>().firstOrNull()
    val thumbnailStreamIndex = streams.indexOfFirst { it.isAttachedThumbnail() }.takeIf { it >= 0 }

    val durationMs = container.duration ?: streams.mapNotNull {
        when (it) {
            is StreamMetadata.Audio -> it.duration
            is StreamMetadata.Video -> it.duration
            else -> null
        }
    }.maxOrNull()

    // Scan every tag bag's composer for the first that actually carries a YouTube id — not merely
    // the first non-blank composer (a later bag may hold the id when an earlier one doesn't).
    val videoId = tagBags.firstNotNullOfOrNull { bag ->
        bag.composer?.let { videoIdFromComposer.find(it)?.groupValues?.get(1) }
    } ?: videoIdFromFileName.find(file.name)?.groupValues?.get(1)
    val loudnessGainDb = tagBags.firstNotNullOfOrNull { bag ->
        bag.composer?.let { loudnessGainFromComposer.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
    }

    val mimeType = getMimeType().takeIf { it.isNotBlank() }
        ?: file.mimeType.takeIf { it.isNotBlank() }
        ?: "application/octet-stream"

    return MediaItemDraft(
        uri = file.uri,
        title = title,
        mimeType = mimeType,
        fileSize = file.size,
        lastModified = file.lastModified,
        artist = firstTag { it.artist },
        album = firstTag { it.album },
        genre = firstTag { it.genre },
        trackNumber = trackNumber,
        year = year,
        durationMs = durationMs,
        audioCodec = audio?.codec,
        videoCodec = video?.codec,
        subtitleCodec = subtitle?.codec,
        bitrate = container.bitrate?.toInt(),
        width = video?.width,
        height = video?.height,
        thumbnailStreamIndex = thumbnailStreamIndex,
        youtubeVid = videoId,
        youtubeItags = null,
        loudnessGainDb = loudnessGainDb,
    )
}
