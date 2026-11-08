package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_voice_search
import org.jetbrains.compose.resources.stringResource

/** What the shell's recognizer hears of one spoken query. The flow ends after [Final] or [NoMatch];
 *  ending with neither means the user canceled. */
sealed interface SpeechEvent {
    /** Input loudness, 0..1. */
    data class Level(val value: Float) : SpeechEvent

    data class Partial(val text: String) : SpeechEvent

    data class Final(val text: String) : SpeechEvent

    data object NoMatch : SpeechEvent
}

internal enum class VoiceStatus { Idle, Listening, Missed }

/** The TV search's leading mic: accent while listening, with a halo that follows [level]. */
@Composable
internal fun VoiceMicButton(
    listening: Boolean,
    level: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = DesignColors.Accent
    Box(
        modifier
            .size(VoiceMicSize)
            .drawBehind {
                if (!listening) return@drawBehind
                val loudness = level()
                val radius = size.minDimension / 2
                drawCircle(accent.copy(alpha = 0.16f), radius * (1.2f + 0.35f * loudness))
                drawCircle(accent.copy(alpha = 0.32f), radius * (1.08f + 0.17f * loudness))
            }
            .focusRing(CircleShape)
            .clip(CircleShape)
            .background(if (listening) accent else DesignColors.FieldPill)
            .a11yActionLabel(stringResource(Res.string.cd_voice_search))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Mic, null, Modifier.size(24.dp), tint = if (listening) Color.White else DesignColors.Ink)
    }
}

/** Off the step scale: a TV's one-press entry point stands out from the bar's 40dp icons. */
private val VoiceMicSize = 48.dp
