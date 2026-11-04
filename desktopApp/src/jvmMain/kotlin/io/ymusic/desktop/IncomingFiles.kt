package io.ymusic.desktop

import io.ymusic.mediascanner.DocumentInfo
import io.ymusic.mediascanner.MediaMetadataExtractor
import io.ymusic.player.PlayableItem
import io.ymusic.player.QueueOrigin
import io.ymusic.ui.AppGraph
import io.ymusic.ui.fileArtUrl
import io.ymusic.ui.toLocalMedia
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmpcommon.uri.fileUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Media files the OS asked the app to open (Open With, double-click), held until a player exists. */
internal object IncomingFiles {
    private val channel = Channel<List<String>>(Channel.UNLIMITED)

    val uris: Flow<List<String>> = channel.receiveAsFlow()

    fun open(uris: List<String>) {
        if (uris.isNotEmpty()) channel.trySend(uris)
    }
}

/** The existing files among a launch's [args], as file URIs: the OS passes them as plain paths. */
internal fun launchFileUris(args: List<String>): List<String> =
    args.filterNot { it.startsWith("--") }.map(::File).filter { it.isFile }.map { fileUri(it.path).toString() }

/** Plays [uris] as the queue. A file in the Library plays as that item; any other is outside the Library and Play Again. */
internal suspend fun AppGraph.playFiles(uris: List<String>, metadata: MediaMetadataExtractor?) {
    val items = uris.map { uri -> mediaStore?.findItemByUri(Uri.parse(uri))?.toLocalMedia() ?: looseFile(uri, metadata) }
    temporaryUris.update { held -> held + items.filter { it.mediaId == null }.map { it.uri } }
    player.setQueue(items, origin = QueueOrigin.File)
    pip.nowPlayingOpen.value = true
}

/** A file outside the Library, described by its own tags; a file that can't be probed still plays under its name. */
private suspend fun AppGraph.looseFile(uri: String, metadata: MediaMetadataExtractor?): PlayableItem.LocalMedia {
    val file = File(Uri.parse(uri).filePath())
    val document = DocumentInfo.File(Uri.parse(uri), file.name, "", file.length(), file.lastModified())
    val draft = try {
        metadata?.extract(document)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger?.log(Logger.Level.WARN, "opened file probe failed", e)
        null
    }
    return PlayableItem.LocalMedia(
        uri = uri,
        title = draft?.title ?: file.nameWithoutExtension,
        artist = draft?.artist,
        album = draft?.album,
        thumbnailUrl = draft?.let { fileArtUrl(uri, it.thumbnailStreamIndex != null, it.youtubeVid, it.videoCodec != null) },
        durationMs = draft?.durationMs,
    )
}
