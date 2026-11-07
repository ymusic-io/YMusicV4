package io.ymusic.ui.sponsorblock

import io.ymusic.player.SkipSegment
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.AppGraph
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches the current queue item and arms [io.ymusic.player.Player.setSkipSegments] from
 * [AppGraph.sponsorBlock], keyed by [io.ymusic.player.PlayableItem.videoId] so downloaded files skip too.
 * Reloads when the enable setting or per-video whitelist changes.
 */
class SponsorBlockCoordinator(
    private val graph: AppGraph,
    scope: CoroutineScope,
) {
    private val log = graph.logger?.fork("sponsor-block")

    init {
        scope.launch {
            var fetchJob: Job? = null
            combine(
                graph.player.queue.map { it.currentItem }.distinctUntilChanged(),
                graph.sponsorBlock.changes,
                graph.settings.flow(AppSettings.sponsorBlockEnabled),
            ) { item, _, _ -> item }
                .collect { item ->
                    fetchJob?.cancel()
                    if (item == null) {
                        graph.player.clearSkipSegments()
                        return@collect
                    }
                    fetchJob = launch {
                        val segments = try {
                            item.videoId?.let { graph.sponsorBlock.load(it).segments }.orEmpty()
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            log?.log(Logger.Level.WARN, "getSkipSegments failed", e)
                            emptyList()
                        }
                        if (graph.player.queue.value.currentItem == item) {
                            graph.player.setSkipSegments(item, segments.toPlayerSkipSegments())
                        }
                    }
                }
        }
    }
}

internal fun List<SponsorBlockSegment>.toPlayerSkipSegments(): List<SkipSegment> =
    mapNotNull { seg ->
        val lengthMs = ((seg.endTime - seg.startTime) * 1_000.0).toLong()
        if (lengthMs < 1_000L) {
            null
        } else {
            SkipSegment(
                startMs = (seg.startTime * 1_000.0).toLong(),
                lengthMs = lengthMs,
            )
        }
    }
