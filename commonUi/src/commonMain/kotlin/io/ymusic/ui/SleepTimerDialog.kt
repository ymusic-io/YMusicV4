package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_sleep_timer
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.ic_sleep_timer
import io.ymusic.ui.resources.sleep_add_minutes
import io.ymusic.ui.resources.sleep_end_of_track
import io.ymusic.ui.resources.sleep_hours
import io.ymusic.ui.resources.sleep_hours_minutes
import io.ymusic.ui.resources.sleep_minutes
import io.ymusic.ui.resources.sleep_pausing_after_track
import io.ymusic.ui.resources.sleep_pausing_in
import io.ymusic.ui.resources.sleep_start
import io.ymusic.ui.resources.sleep_stops_at
import io.ymusic.ui.resources.sleep_turn_off
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock

/** What the sleep-timer dialog asks the player for. */
internal sealed interface SleepChoice {
    data class Timer(val minutes: Int) : SleepChoice

    data object EndOfTrack : SleepChoice

    data object Off : SleepChoice
}

private const val MIN_MINUTES = 5
private const val MAX_MINUTES = 180
private const val STEP_MINUTES = 5
private const val EXTEND_MINUTES = 5
private val PresetMinutes = listOf(15, 30, 45, 60, 90)
private val ArmedIconSize = 20.dp

/** The Now Playing bed icon (accent while armed) opening [SleepTimerDialog]. */
@Composable
internal fun SleepTimerButton(
    sleepMs: Long?,
    afterCurrent: Boolean,
    lastMinutes: Int,
    onChoose: (SleepChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HoldVideoOverlay(open)
    val armed = when {
        sleepMs != null -> stringResource(Res.string.sleep_pausing_in, spokenDuration((sleepMs + 59_999) / 60_000 * 60_000))
        afterCurrent -> stringResource(Res.string.sleep_pausing_after_track)
        else -> null
    }
    ActionIcon(
        painterResource(Res.drawable.ic_sleep_timer),
        listOfNotNull(stringResource(Res.string.cd_sleep_timer), armed).joinToString(", "),
        tint = if (sleepMs != null || afterCurrent) DesignColors.Accent else DesignColors.InkMuted,
        onClick = { open = true },
    )
    if (open) {
        SleepTimerDialog(
            sleepMs = sleepMs,
            afterCurrent = afterCurrent,
            lastMinutes = lastMinutes,
            onDismiss = { open = false },
            onChoose = { choice ->
                open = false
                onChoose(choice)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepTimerDialog(
    sleepMs: Long?,
    afterCurrent: Boolean,
    lastMinutes: Int,
    onDismiss: () -> Unit,
    onChoose: (SleepChoice) -> Unit,
) {
    var minutes by remember { mutableStateOf(lastMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES)) }
    var endOfTrack by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = Clock.System.now().toEpochMilliseconds()
        }
    }

    ElevatedBarDialog(
        title = stringResource(Res.string.cd_sleep_timer),
        onDismiss = onDismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onDismiss)
            TextButton(
                onClick = {
                    onChoose(if (endOfTrack) SleepChoice.EndOfTrack else SleepChoice.Timer(minutes))
                },
            ) { Text(stringResource(Res.string.sleep_start), color = DesignColors.Accent) }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            if (sleepMs != null || afterCurrent) {
                ArmedCard(
                    sleepMs = sleepMs,
                    nowMs = nowMs,
                    onExtend = sleepMs?.let {
                        {
                            val extended = ((it + 59_999) / 60_000).toInt() + EXTEND_MINUTES
                            onChoose(SleepChoice.Timer(extended))
                        }
                    },
                    onTurnOff = { onChoose(SleepChoice.Off) },
                )
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (endOfTrack) stringResource(Res.string.sleep_end_of_track) else durationLabel(minutes),
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkStrong),
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (endOfTrack) {
                        stringResource(Res.string.sleep_pausing_after_track)
                    } else {
                        stringResource(Res.string.sleep_stops_at, formatTimeOfDay(nowMs + minutes * 60_000L))
                    },
                    style = TextStyle(fontSize = 14.sp, color = DesignColors.InkMuted),
                    textAlign = TextAlign.Center,
                )
            }
            val pickMinutes = { fraction: Float ->
                minutes = MIN_MINUTES + (fraction * (MAX_MINUTES - MIN_MINUTES) / STEP_MINUTES).roundToInt() * STEP_MINUTES
                endOfTrack = false
            }
            SlimSeekBar(
                fraction = (minutes - MIN_MINUTES).toFloat() / (MAX_MINUTES - MIN_MINUTES),
                enabled = true,
                onSeek = pickMinutes,
                onScrub = { fraction -> fraction?.let(pickMinutes) },
                trackColor = DesignColors.OutlineSoft,
                fillColor = if (endOfTrack) DesignColors.InkFaint else DesignColors.Accent,
                thumbColor = if (endOfTrack) DesignColors.InkFaint else DesignColors.Accent,
                thumbSize = 16.dp,
                trackHeight = 4.dp,
                barHeight = 32.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
            ) {
                PresetMinutes.forEach { preset ->
                    LibraryChip(stringResource(Res.string.sleep_minutes, preset), selected = !endOfTrack && minutes == preset) {
                        minutes = preset
                        endOfTrack = false
                    }
                }
                LibraryChip(stringResource(Res.string.sleep_end_of_track), selected = endOfTrack) { endOfTrack = true }
            }
        }
    }
}

@Composable
private fun ArmedCard(sleepMs: Long?, nowMs: Long, onExtend: (() -> Unit)?, onTurnOff: () -> Unit) {
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = DesignSpacing.Space16, end = DesignSpacing.Space4, top = DesignSpacing.Space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_sleep_timer), null, Modifier.size(ArmedIconSize), tint = DesignColors.Accent)
            Text(
                if (sleepMs != null) {
                    stringResource(Res.string.sleep_pausing_in, formatMillis(sleepMs))
                } else {
                    stringResource(Res.string.sleep_pausing_after_track)
                },
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onCard),
                modifier = Modifier.weight(1f).padding(horizontal = DesignSpacing.Space8),
            )
        }
        if (sleepMs != null) {
            Text(
                stringResource(Res.string.sleep_stops_at, formatTimeOfDay(nowMs + sleepMs)),
                style = TextStyle(fontSize = 12.sp, color = onCard),
                modifier = Modifier.padding(start = ArmedIconSize + DesignSpacing.Space8),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onExtend != null) {
                TextButton(onClick = onExtend) {
                    Text(stringResource(Res.string.sleep_add_minutes, EXTEND_MINUTES), color = DesignColors.Accent)
                }
            }
            TextButton(onClick = onTurnOff) {
                Text(stringResource(Res.string.sleep_turn_off), color = DesignColors.Accent)
            }
        }
    }
}

@Composable
private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(Res.string.sleep_minutes, minutes)
        rest == 0 -> stringResource(Res.string.sleep_hours, hours)
        else -> stringResource(Res.string.sleep_hours_minutes, hours, rest)
    }
}
