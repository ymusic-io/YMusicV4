package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.play_again_track_of
import io.ymusic.ui.resources.settings_accent_color
import io.ymusic.ui.resources.sound_ok
import io.ymusic.ui.resources.sound_reset
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min

/** Settings → Appearance row: the label and a swatch of the current accent; a tap opens [AccentPickerDialog]. */
@Composable
internal fun AccentRow(current: Color, onPick: (Color) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.settings_accent_color),
            style = TextStyle(fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = DesignColors.Ink),
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(20.dp).clip(CircleShape).background(current))
    }
    if (open) {
        AccentPickerDialog(
            initial = current,
            onDismiss = { open = false },
            onPick = { onPick(it); open = false },
        )
    }
}

/** Preset swatches, a hue bar and a hex field; Reset returns to [DefaultAccent]. The bar keeps the current saturation and
 *  brightness, capped per hue ([darkenedAccent]) so no pick is lighter than [MaxAccentLuminance]. */
@Composable
private fun AccentPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val start = remember { hsvOf(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    val picked = Color.hsv(hue, saturation, min(brightness, maxBrightness(hue, saturation)))
    var hexText by remember { mutableStateOf(accentHexOf(initial)) }
    fun setColor(color: Color) {
        val hsv = hsvOf(darkenedAccent(color))
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        hexText = accentHexOf(darkenedAccent(color))
    }
    ElevatedBarDialog(
        title = stringResource(Res.string.settings_accent_color),
        onDismiss = onDismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.sound_reset)) { setColor(DefaultAccent) }
            TextButton(onClick = { onPick(picked) }) {
                Text(stringResource(Res.string.sound_ok), color = picked)
            }
        },
    ) {
        Column(
            Modifier.padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            AccentPresetRow(selectedHex = accentHexOf(picked), onPick = ::setColor)
            HueBar(hue) { h ->
                hue = h
                hexText = accentHexOf(Color.hsv(h, saturation, min(brightness, maxBrightness(h, saturation))))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(picked).border(1.dp, DesignColors.OutlineSoft, CircleShape))
                Text("#", style = TextStyle(fontSize = 16.sp, color = DesignColors.InkSecondary, fontFamily = FontFamily.Monospace))
                BasicTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        val clean = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                        hexText = clean
                        if (clean.length == 6) setColor(accentColorOf(clean))
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = DesignColors.Ink, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(picked),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.width(96.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPresetRow(selectedHex: String, onPick: (Color) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        val name = stringResource(Res.string.settings_accent_color)
        AccentPresets.forEachIndexed { i, preset ->
            val selected = accentHexOf(darkenedAccent(preset)) == selectedHex
            val position = stringResource(Res.string.play_again_track_of, i + 1, AccentPresets.size)
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) DesignColors.Ink else Color.Transparent, CircleShape)
                    .padding(DesignSpacing.Space4)
                    .clip(CircleShape)
                    .background(darkenedAccent(preset))
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(preset) }
                    .a11yActionLabel("$name, $position"),
            )
        }
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    fun pick(x: Float, width: Int) = onChange((x / width).coerceIn(0f, 1f) * 359.99f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { pick(it.x, size.width) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { pick(it.x, size.width) }) { change, _ -> pick(change.position.x, size.width) }
            },
    ) {
        drawRect(Brush.horizontalGradient(HueStops))
        val x = hue / 360f * size.width
        val radius = size.height / 2f - 2.dp.toPx()
        drawCircle(Color.White, radius = radius, center = Offset(x.coerceIn(radius, size.width - radius), size.height / 2f), style = Stroke(width = 2.5.dp.toPx()))
    }
}

private val AccentPresets = listOf(
    DefaultAccent,
    Color(0xFFD32F2F),
    Color(0xFFC2185B),
    Color(0xFF7B1FA2),
    Color(0xFF341C9A),
    Color(0xFF1565C0),
    Color(0xFF00796B),
    Color(0xFF2E7D32),
)

private val HueStops = (0..6).map { Color.hsv((it * 60f).coerceAtMost(359.99f), 1f, 1f) }

/** The highest brightness [darkenedAccent] allows at this hue and saturation. */
private fun maxBrightness(hue: Float, saturation: Float): Float = hsvOf(darkenedAccent(Color.hsv(hue, saturation, 1f)))[2]

/** [hue 0..360, saturation 0..1, value 0..1]. */
private fun hsvOf(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val hue = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta).mod(6f))
        maxC == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    val saturation = if (maxC == 0f) 0f else delta / maxC
    return floatArrayOf(hue.coerceIn(0f, 359.99f), saturation, maxC)
}
