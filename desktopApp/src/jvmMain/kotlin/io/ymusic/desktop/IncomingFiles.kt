package io.ymusic.desktop

import io.ymusic.player.PlayableItem
import io.ymusic.player.QueueOrigin
import io.ymusic.ui.AppGraph
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.URI

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
    args.filterNot { it.startsWith("--") }.map(::File).filter { it.isFile }.map { it.toURI().toString() }

/** Plays [uris] as the queue, outside the Library and Play Again: they are the user's loose files. */
internal fun AppGraph.playFiles(uris: List<String>) {
    temporaryUris.update { it + uris }
    player.setQueue(
        uris.map { uri -> PlayableItem.LocalMedia(uri = uri, title = File(URI(uri)).nameWithoutExtension) },
        origin = QueueOrigin.File,
    )
    pip.nowPlayingOpen.value = true
}
