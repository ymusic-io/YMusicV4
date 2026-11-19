package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtLinkTarget
import io.ymusic.dynamicengine.yt.resolveLink
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.error_captcha
import io.ymusic.ui.resources.error_generic
import io.ymusic.ui.resources.error_network
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.compose.resources.getString
import projectcommon.error.errorCode

/** What to do with an outside link's video; any other target just opens. */
enum class LinkAction {
    /** Play with its radio (or the link's playlist) and show Now Playing. */
    OPEN,
    PLAY_SINGLE,
    QUEUE_NEXT,
    QUEUE_LAST,

    /** The download options dialog. */
    DOWNLOAD,
    DOWNLOAD_M4A,
    DOWNLOAD_MP3,
}

internal data class IncomingLink(val url: String, val action: LinkAction)

/**
 * Outside links the app was opened with (an Android VIEW intent or share, a desktop/iOS
 * custom-scheme open), handed in by the shell and opened by [App] at the right page. Process-wide
 * because a link can arrive before the graph exists (a cold start): it waits here until [App] drains it.
 */
object IncomingLinks {
    private val channel = Channel<IncomingLink>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal val links: Flow<IncomingLink> = channel.receiveAsFlow()

    fun open(url: String, action: LinkAction = LinkAction.OPEN) {
        channel.trySend(IncomingLink(url, action))
    }
}

/** What an outside link opens (null: nothing YMusic opens), or the error code resolving it failed with. */
internal sealed interface LinkResolution {
    data class Resolved(val target: YtLinkTarget?) : LinkResolution

    data class Failed(val code: String) : LinkResolution
}

internal suspend fun resolveIncomingLink(graph: AppGraph, url: String, log: Logger?): LinkResolution = try {
    LinkResolution.Resolved(graph.engines.resolveLink(url, log))
} catch (c: CancellationException) {
    throw c
} catch (e: Exception) {
    log?.log(Logger.Level.WARN, "resolve link failed", e)
    LinkResolution.Failed(e.errorCode() ?: "E20113")
}

internal suspend fun linkFailureMessage(code: String): String = when (code) {
    "network" -> getString(Res.string.error_network)
    CAPTCHA_CODE -> getString(Res.string.error_captcha)
    else -> getString(Res.string.error_generic, code)
}
