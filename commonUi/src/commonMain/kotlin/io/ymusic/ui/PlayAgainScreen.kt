package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ymusic.player.formatProgressClock
import io.ymusic.ui.playhistory.PlayAgainSession
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.menu_play_from_beginning
import io.ymusic.ui.resources.menu_remove
import io.ymusic.ui.resources.play_again_empty
import io.ymusic.ui.resources.play_again_resume
import io.ymusic.ui.resources.play_again_resume_at
import io.ymusic.ui.resources.play_again_track_of
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.TechnicalException

/** "Play again" (legacy Rewind playlist): the queues played lately, newest first; a tap resumes one. */
@Composable
fun PlayAgainScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val store = graph.playHistory?.playAgain
    val log = remember { graph.logger?.fork("play-again") }
    val scope = rememberCoroutineScope()
    val showSnackbar = LocalSnackbar.current
    var sessions by remember { mutableStateOf<List<PlayAgainSession>?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }

    if (store == null) {
        EmptyState(stringResource(Res.string.play_again_empty), onReload = {})
        return
    }
    LaunchedEffect(store, reloads) {
        store.changes.onStart { emit(Unit) }.collectLatest {
            try {
                sessions = store.sessions()
                errorCode = null
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                log?.captureError(TechnicalException("E60060", "play-again list failed to load", cause = e))
                errorCode = "E60060"
            }
        }
    }
    actionError?.let { code ->
        val message = errorMessage(code)
        LaunchedEffect(code, message) {
            showSnackbar(message)
            actionError = null
        }
    }

    fun runAction(session: PlayAgainSession, code: String, what: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                log?.captureError(TechnicalException(code, what, cause = e), prop = mapOf("queueId" to session.queueId))
                actionError = code
            }
        }
    }

    fun restore(session: PlayAgainSession, fromBeginning: Boolean) =
        runAction(session, "E60061", "play-again queue failed to restore") {
            coordinator.playAgain(session, fromBeginning)
        }

    val gridState = rememberLazyGridState()
    TrackScroll { gridState.canScrollBackward }
    val code = errorCode
    val list = sessions
    when {
        code != null -> ErrorState(code) { reloads++ }
        list == null -> CenteredBox { CircularProgressIndicator() }
        list.isEmpty() -> EmptyState(stringResource(Res.string.play_again_empty), onReload = { reloads++ })
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = PlayAgainCardMinWidth),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(
                start = DesignSpacing.Space16,
                end = DesignSpacing.Space16,
                top = DesignSpacing.Space16,
                bottom = DesignSpacing.Space16 + LocalMiniPlayerInset.current,
            ),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            items(list, key = { it.queueId }) { session ->
                PlayAgainCard(
                    session,
                    modifier = Modifier.animateItem(),
                    onPlay = { restore(session, fromBeginning = false) },
                    onPlayFromBeginning = { restore(session, fromBeginning = true) },
                    onRemove = {
                        runAction(session, "E60062", "play-again session failed to delete") {
                            store.delete(session.queueId)
                        }
                    },
                )
            }
        }
    }
}

/** One column on a phone; side-by-side cards on a wide window. */
private val PlayAgainCardMinWidth = 340.dp
private val StripTileSize = 30.dp

/** Room kept for the "+N" label after the tiles (three digits at the sub style). */
private val StripMoreLabelWidth = 32.dp

/** A saved queue: its current item, a strip of the queue around it (played dimmed, current ringed), Resume. */
@Composable
private fun PlayAgainCard(
    session: PlayAgainSession,
    modifier: Modifier,
    onPlay: () -> Unit,
    onPlayFromBeginning: () -> Unit,
    onRemove: () -> Unit,
) {
    val fromBeginning = stringResource(Res.string.menu_play_from_beginning)
    val remove = stringResource(Res.string.menu_remove)
    val cardShape = RoundedCornerShape(MediaCardCorner)
    Column(
        modifier
            .fillMaxWidth()
            .background(DesignColors.SettingsCard, cardShape)
            .border(1.dp, DesignColors.Divider, cardShape)
            .padding(start = DesignSpacing.Space16, end = DesignSpacing.Space4, top = DesignSpacing.Space8, bottom = DesignSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = DesignSpacing.Space8).semantics(mergeDescendants = true) {},
        ) {
            LibraryThumb(Icons.Default.MusicNote, badge = null, artUrl = session.thumbnailUrl)
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = ListTitleStyle.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sessionLine(session, formatProgressClock(session.positionMs)),
                    style = ListSubStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = DesignSpacing.Space4)
                        .spokenAs(sessionLine(session, spokenDuration(session.positionMs))),
                )
            }
            MoreMenu(ItemActions(listOf(fromBeginning to onPlayFromBeginning, remove to onRemove)))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
            modifier = Modifier.padding(end = DesignSpacing.Space8),
        ) {
            QueueStrip(session, Modifier.weight(1f))
            PlayPill(Icons.Default.PlayArrow, stringResource(Res.string.play_again_resume), filled = false, onClick = onPlay)
        }
    }
}

@Composable
private fun QueueStrip(session: PlayAgainSession, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val gap = DesignSpacing.Space4
        // Whole tiles only: the ones that don't fit fold into the "+N" label, never a squeezed tile.
        val fit = ((maxWidth - StripMoreLabelWidth + gap) / (StripTileSize + gap)).toInt().coerceAtLeast(1)
        val currentOffset = session.queueIndex - session.stripStart
        val first = (currentOffset - fit + 1).coerceIn(0, (session.stripThumbs.size - 1).coerceAtLeast(0))
        val shown = session.stripThumbs.drop(first).take(fit)
        val more = session.queueSize - session.stripStart - first - shown.size
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            val tileShape = RoundedCornerShape(6.dp)
            shown.forEachIndexed { offset, url ->
                val index = session.stripStart + first + offset
                Box(
                    Modifier
                        .size(StripTileSize)
                        .alpha(if (index < session.queueIndex) 0.35f else 1f)
                        .then(
                            if (index == session.queueIndex) Modifier.border(1.dp, DesignColors.Accent, tileShape) else Modifier,
                        )
                        .padding(if (index == session.queueIndex) 1.5.dp else 0.dp)
                        .clip(tileShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtImage(url, tileShape) { ThumbPlaceholder(Icons.Default.MusicNote, 14.dp) }
                }
            }
            if (more > 0) {
                Text(
                    "+$more",
                    style = ListSubStyle.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = Modifier.padding(start = DesignSpacing.Space4),
                )
            }
        }
    }
}

/** "artist · 7 of 32" — or "artist · Resume at 1:12:40" for long content left mid-item, the time as [resumeAt]. */
@Composable
private fun sessionLine(session: PlayAgainSession, resumeAt: String): String {
    // Under a second the clock reads 0:00 — that is the track's start, not a resume point.
    val position = if (session.resumesMidItem && session.positionMs >= 1_000L) {
        stringResource(Res.string.play_again_resume_at, resumeAt)
    } else {
        stringResource(Res.string.play_again_track_of, session.queueIndex + 1, session.queueSize)
    }
    return listOfNotNull(session.subtitle, position).joinToString(" · ")
}
