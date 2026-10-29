package io.ymusic.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.Player
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.sound_ok
import io.ymusic.ui.resources.sound_pitch
import io.ymusic.ui.resources.sound_playback
import io.ymusic.ui.resources.sound_reset
import io.ymusic.ui.resources.sound_speed
import io.ymusic.ui.resources.sound_volume
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.round

/** Same chips for speed and pitch. */
private val RATE_CHIPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val DialogPad = Modifier.padding(horizontal = 24.dp)

@Composable
internal fun PlaybackFxSheet(player: Player, onDismiss: () -> Unit) {
    val config by player.config.collectAsState()
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.sound_playback),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.sound_reset)) {
                player.volume = 1f
                player.speed = 1f
                player.setPitch(1f)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sound_ok), color = DesignColors.Accent)
            }
        },
    ) {
        Column(Modifier.verticalScroll(scroll).padding(vertical = 8.dp)) {
            LabelValue(
                stringResource(Res.string.sound_volume, (config.volume * 100).toInt()),
                "",
            )
            FxBar(
                fraction = config.volume,
                onChange = { player.volume = it },
                onScrub = { f -> if (f != null) player.volume = f },
            )
            Spacer(Modifier.height(16.dp))
            RateBlock(
                title = stringResource(Res.string.sound_speed),
                value = config.speed,
                range = 0.25f..3f,
                onChange = { player.speed = it },
            )
            Spacer(Modifier.height(20.dp))
            RateBlock(
                title = stringResource(Res.string.sound_pitch, "").trim(),
                value = config.pitchFactor,
                range = 0.5f..2f,
                onChange = { player.setPitch(it) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RateBlock(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    fun snapped(f: Float) = snapStep(range.start + f * (range.endInclusive - range.start))
    LabelValue("$title ×${trimFloat(scrubbing ?: value)}", "")
    FlowRow(
        DialogPad.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RATE_CHIPS.forEach { s ->
            RateChip(
                label = if (s == 1f) "1×" else "${trimFloat(s)}×",
                selected = abs(value - s) < RATE_STEP / 2,
                onClick = { onChange(s) },
            )
        }
    }
    Row(
        DialogPad.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stepper("−") { onChange(snapStep(value - RATE_STEP).coerceIn(range.start, range.endInclusive)) }
        FxBar(
            fraction = (value - range.start) / (range.endInclusive - range.start),
            onChange = { f -> onChange(snapped(f)) },
            onScrub = { f -> scrubbing = f?.let(::snapped) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Stepper("+") { onChange(snapStep(value + RATE_STEP).coerceIn(range.start, range.endInclusive)) }
    }
}

@Composable
private fun FxBar(
    fraction: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = DialogPad.fillMaxWidth(),
    onScrub: ((Float?) -> Unit)? = null,
) {
    SlimSeekBar(
        fraction = fraction.coerceIn(0f, 1f),
        enabled = true,
        onSeek = onChange,
        onScrub = onScrub,
        trackColor = DesignColors.OutlineSoft,
        modifier = modifier,
    )
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        DialogPad.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink))
        if (value.isNotEmpty()) {
            Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent))
        }
    }
}

@Composable
private fun RateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    ChipTapArea(onClick, selected = selected) { indication ->
        Text(
            label,
            style = TextStyle(
                fontSize = ChipTextSize,
                color = if (selected) Color.White else DesignColors.InkSoft,
            ),
            modifier = Modifier
                .clip(shape)
                .background(if (selected) DesignColors.Accent else Color.Transparent)
                .border(1.dp, if (selected) DesignColors.Accent else DesignColors.Outline, shape)
                .then(indication)
                .chipPadding(),
        )
    }
}

/** Tap steps once; holding repeats, and the release that ends a hold doesn't step again. */
@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val step by rememberUpdatedState(onClick)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        // Runs after the release's click, which a hold must swallow; a later quick tap must not be.
        if (!pressed) {
            repeated = false
            return@LaunchedEffect
        }
        delay(STEPPER_HOLD_DELAY_MS)
        repeated = true
        while (true) {
            step()
            delay(STEPPER_REPEAT_MS)
        }
    }
    Text(
        label,
        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                if (!repeated) step()
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private const val RATE_STEPS_PER_UNIT = 100f
private const val RATE_STEP = 1f / RATE_STEPS_PER_UNIT
private const val STEPPER_HOLD_DELAY_MS = 400L
private const val STEPPER_REPEAT_MS = 60L

private fun snapStep(v: Float): Float = round(v * RATE_STEPS_PER_UNIT) / RATE_STEPS_PER_UNIT

internal fun trimFloat(v: Float): String {
    val x = round(v * 100f) / 100f
    val asInt = x.toInt()
    return if (abs(x - asInt) < 0.001f) asInt.toString() else x.toString()
}
