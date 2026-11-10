package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.LoopMode
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackStatus
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_next
import io.ymusic.ui.resources.cd_pause
import io.ymusic.ui.resources.cd_play
import io.ymusic.ui.resources.cd_previous
import io.ymusic.ui.resources.cd_shuffle
import io.ymusic.ui.resources.menu_speed_pitch
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sound_volume
import org.jetbrains.compose.resources.stringResource

private val BarInfoMinWidth = 300.dp

/** Share of the bar the track info takes once that beats [BarInfoMinWidth]. */
private const val BAR_INFO_SHARE = 0.3f
private val BarVolumeWidth = 120.dp
private val BarSeekMaxWidth = 560.dp

/** Below this the side controls drop out; Now Playing still has them. */
private val BarSideMinWidth = 900.dp

/** The sleep timer joins the side controls from this width. */
private val BarSleepMinWidth = 1100.dp
private val BarSmallIcon = 20.dp
private val BarSmallHit = 36.dp
private val BarPlaySize = 36.dp
private val BarArtShape = RoundedCornerShape(DesignSpacing.Space4)
private const val VOLUME_KEY_STEP = 0.05f

/**
 * The desktop mini player: a full-width bar under the rail and the content, in the rail's tone. What is playing on the
 * left (opens Now Playing), transport and seek in the middle, equalizer · sleep timer · volume on the right.
 *
 * [surface] is the shared video container: the art tile keeps it mounted so playback goes on, under the cover.
 */
@Composable
internal fun DesktopPlayerBar(
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)?,
    background: Color,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val queue by coordinator.player.queue.collectAsState()
    val item = queue.currentItem ?: return
    val expand by rememberUpdatedState(onExpand)
    Column(
        modifier.fillMaxWidth().background(background)
            // A tap the controls leave unconsumed; not clickable(), which would merge the controls into one a11y node.
            .pointerInput(Unit) { detectTapGestures { expand() } },
    ) {
        HorizontalDivider(color = DesignColors.Divider)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = maxWidth >= BarSideMinWidth
            val sleep = maxWidth >= BarSleepMinWidth
            val infoWidth = maxOf(BarInfoMinWidth, maxWidth * BAR_INFO_SHARE)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarNowPlaying(
                    coordinator,
                    item,
                    surface,
                    onExpand,
                    modifier = Modifier.width(infoWidth),
                )
                BarTransport(coordinator, Modifier.weight(1f))
                if (side) BarSideControls(coordinator, sleep)
            }
        }
    }
}

@Composable
private fun BarNowPlaying(
    coordinator: PlaybackCoordinator,
    item: PlayableItem,
    surface: (@Composable (Modifier) -> Unit)?,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val status by coordinator.player.status.collectAsState()
    Row(
        modifier.clip(BarArtShape).clickable(onClick = onExpand),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniArtTile(
            coordinator,
            item,
            surface,
            onExpand,
            Modifier.size(DesignSpacing.Space48).clip(BarArtShape),
            coverOnly = true,
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.displayTitle,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            artistText(item, status)?.let {
                Text(
                    it,
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** shuffle · prev · [play circle] · next · repeat over the seek bar with its times. */
@Composable
private fun BarTransport(coordinator: PlaybackCoordinator, modifier: Modifier) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MediaLtr {
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8), verticalAlignment = Alignment.CenterVertically) {
                ToggleActionIcon(
                    Icons.Default.Shuffle,
                    stringResource(Res.string.cd_shuffle),
                    checked = queue.shuffle,
                    iconSize = BarSmallIcon,
                    hitSize = BarSmallHit,
                    tint = if (queue.shuffle) DesignColors.Accent else DesignColors.InkMuted,
                    onCheckedChange = { player.setShuffle(it) },
                )
                ActionIcon(Icons.Default.SkipPrevious, stringResource(Res.string.cd_previous), onClick = { player.skipPrev() })
                BarPlayPause(coordinator, status)
                ActionIcon(Icons.Default.SkipNext, stringResource(Res.string.cd_next), onClick = { player.skipNext() })
                val loopMode = queue.loopMode
                ActionIcon(
                    if (loopMode == LoopMode.One) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    stringResource(loopMode.label()),
                    iconSize = BarSmallIcon,
                    hitSize = BarSmallHit,
                    tint = if (loopMode != LoopMode.None) DesignColors.Accent else DesignColors.InkMuted,
                    onClick = { player.setLoopMode(loopMode.cycled()) },
                )
            }
        }
        MediaLtr {
            Row(
                Modifier.widthIn(max = BarSeekMaxWidth).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val timeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary)
                val shown = scrubMs?.let { progress.copy(positionMs = it) } ?: progress
                Text(shown.progressLabel.orEmpty(), style = timeStyle, modifier = Modifier.a11ySilent())
                Box(Modifier.weight(1f)) {
                    SeekSlider(player, progress, trackColor = DesignColors.OutlineSoft, onScrubMs = { scrubMs = it })
                }
                DurationLabel(player, progress, timeStyle)
            }
        }
    }
}

@Composable
private fun BarPlayPause(coordinator: PlaybackCoordinator, status: PlaybackStatus) {
    val pause = status.showsPause()
    Box(
        Modifier.size(BarPlaySize).clip(CircleShape).background(DesignColors.InkStrong)
            .clickable(role = Role.Button) { coordinator.player.togglePlay() }
            .a11yActionLabel(stringResource(if (pause) Res.string.cd_pause else Res.string.cd_play)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (pause) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(BarSmallIcon), tint = DesignColors.OnInk)
    }
}

/** equalizer · speed and pitch · [sleep timer] · volume. */
@Composable
private fun BarSideControls(coordinator: PlaybackCoordinator, sleep: Boolean) {
    val player = coordinator.player
    val config by player.config.collectAsState()
    val sleepMs by player.sleepTimerMs.collectAsState()
    var eqOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(Icons.Default.Tune, stringResource(Res.string.sound_eq), iconSize = BarSmallIcon, onClick = { eqOpen = true })
        ActionIcon(
            Icons.Outlined.Speed,
            stringResource(Res.string.menu_speed_pitch),
            iconSize = BarSmallIcon,
            tint = if (config.speed != 1f || config.pitchFactor != 1f) DesignColors.Accent else DesignColors.InkMuted,
            onClick = { speedOpen = true },
        )
        if (sleep) PlayerSleepTimerButton(coordinator, sleepMs)
        val volume = config.volume
        Icon(
            when {
                volume <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
                volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            null,
            Modifier.padding(horizontal = DesignSpacing.Space4).size(BarSmallIcon),
            tint = DesignColors.InkMuted,
        )
        val volumeLabel = stringResource(Res.string.sound_volume, (volume * 100).toInt())
        SlimSeekBar(
            fraction = volume,
            enabled = true,
            onSeek = { player.volume = it },
            onScrub = { fraction -> if (fraction != null) player.volume = fraction },
            trackColor = DesignColors.OutlineSoft,
            modifier = Modifier.width(BarVolumeWidth),
            a11y = SliderA11y(null, volumeLabel, VOLUME_KEY_STEP),
        )
    }
    if (eqOpen) EqualizerFxSheet(coordinator.graph, onDismiss = { eqOpen = false })
    if (speedOpen) PlaybackFxSheet(player, onDismiss = { speedOpen = false })
}
