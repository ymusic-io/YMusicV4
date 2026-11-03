package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.settings.AppSettings
import io.ymusic.settings.FloatSetting
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.caption_background
import io.ymusic.ui.resources.caption_background_opacity
import io.ymusic.ui.resources.caption_size_preview
import io.ymusic.ui.resources.caption_text_color
import io.ymusic.ui.resources.settings_caption_size
import io.ymusic.ui.resources.settings_caption_style
import io.ymusic.ui.resources.settings_caption_style_hint
import io.ymusic.ui.resources.sound_ok
import io.ymusic.ui.resources.sound_reset
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.round
import kotlin.math.roundToInt

private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 2f
private const val SCALE_STEP = 0.1f
private const val OPACITY_STEP = 0.05f

private val TextColors = listOf("FFFFFF", "FFEB3B", "76FF03", "18FFFF", "FF4081", "000000")
private val BackgroundColors = listOf("000000", "FFFFFF", "616161", "0D47A1", "FFEB3B")

private fun snap(value: Float, step: Float, min: Float, max: Float): Float = (round(value / step) * step).coerceIn(min, max)

private fun scaleLabel(scale: Float): String = "${trimFloat(scale)}×"

private fun opacityLabel(opacity: Float): String = "${(opacity * 100).roundToInt()}%"

/** The saved caption style, live. */
@Composable
internal fun rememberCaptionLook(graph: AppGraph): CaptionLook {
    val scale by remember(graph) { graph.settings.flow(AppSettings.captionScale) }.collectAsState()
    val text by remember(graph) { graph.settings.flow(AppSettings.captionTextColor) }.collectAsState()
    val background by remember(graph) { graph.settings.flow(AppSettings.captionBackgroundColor) }.collectAsState()
    val opacity by remember(graph) { graph.settings.flow(AppSettings.captionBackgroundOpacity) }.collectAsState()
    return CaptionLook(
        scale = scale,
        text = captionColor(text, Color.White),
        background = captionColor(background, Color.Black).copy(alpha = opacity.coerceIn(0f, 1f)),
    )
}

@Composable
internal fun CaptionStyleRow(graph: AppGraph) {
    var open by remember { mutableStateOf(false) }
    val look = rememberCaptionLook(graph)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.settings_caption_style),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                stringResource(Res.string.settings_caption_style_hint),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
        Text(
            "Aa",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = look.text),
            modifier = Modifier
                .background(look.background, RoundedCornerShape(4.dp))
                .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space2),
        )
    }
    if (open) CaptionStyleDialog(graph, look, onDismiss = { open = false })
}

@Composable
private fun CaptionStyleDialog(graph: AppGraph, saved: CaptionLook, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val textHex by remember(graph) { graph.settings.flow(AppSettings.captionTextColor) }.collectAsState()
    val backgroundHex by remember(graph) { graph.settings.flow(AppSettings.captionBackgroundColor) }.collectAsState()
    var scaleDrag by remember { mutableStateOf<Float?>(null) }
    var opacityDrag by remember { mutableStateOf<Float?>(null) }
    val scale = scaleDrag ?: saved.scale
    val opacity = opacityDrag ?: saved.background.alpha
    val look = saved.copy(scale = scale, background = saved.background.copy(alpha = opacity))
    fun save(setting: FloatSetting, value: Float) {
        scope.launch { graph.settings.put(setting, value) }
    }
    fun saveColor(text: Boolean, hex: String) {
        scope.launch { graph.settings.put(if (text) AppSettings.captionTextColor else AppSettings.captionBackgroundColor, hex) }
    }
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.settings_caption_style),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.sound_reset)) {
                scope.launch {
                    graph.settings.put(AppSettings.captionScale, AppSettings.captionScale.default)
                    graph.settings.put(AppSettings.captionTextColor, AppSettings.captionTextColor.default)
                    graph.settings.put(AppSettings.captionBackgroundColor, AppSettings.captionBackgroundColor.default)
                    graph.settings.put(AppSettings.captionBackgroundOpacity, AppSettings.captionBackgroundOpacity.default)
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sound_ok), color = DesignColors.Accent)
            }
        },
    ) {
        Column(
            Modifier.verticalScroll(scroll).padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            // Tall enough for the largest size, so the dialog doesn't resize while dragging.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = captionTwoRowHeight(MAX_SCALE) + DesignSpacing.Space16 * 2)
                    .background(Color(0xFF6B7B8C), RoundedCornerShape(8.dp))
                    .padding(DesignSpacing.Space16),
                contentAlignment = Alignment.BottomCenter,
            ) {
                CaptionCue(stringResource(Res.string.caption_size_preview), captionStyle(scale), look)
            }
            val sizeTitle = stringResource(Res.string.settings_caption_size)
            SectionTitle(sizeTitle)
            ValueSlider(
                title = sizeTitle,
                fraction = (scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE),
                label = scaleLabel(scale),
                keyStep = SCALE_STEP / (MAX_SCALE - MIN_SCALE),
                toValue = { snap(MIN_SCALE + it * (MAX_SCALE - MIN_SCALE), SCALE_STEP, MIN_SCALE, MAX_SCALE) },
                onDrag = { scaleDrag = it },
                onDone = { scaleDrag = null; save(AppSettings.captionScale, it) },
            )
            SectionTitle(stringResource(Res.string.caption_text_color))
            Swatches(TextColors, textHex) { saveColor(text = true, hex = it) }
            SectionTitle(stringResource(Res.string.caption_background))
            Swatches(BackgroundColors, backgroundHex) { saveColor(text = false, hex = it) }
            val opacityTitle = stringResource(Res.string.caption_background_opacity)
            SectionTitle(opacityTitle)
            ValueSlider(
                title = opacityTitle,
                fraction = opacity,
                label = opacityLabel(opacity),
                keyStep = OPACITY_STEP,
                toValue = { snap(it, OPACITY_STEP, 0f, 1f) },
                onDrag = { opacityDrag = it },
                onDone = { opacityDrag = null; save(AppSettings.captionBackgroundOpacity, it) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary))
}

/** A 0..1 slider whose drag previews through [onDrag] (null when it ends) and saves once on release. */
@Composable
private fun ValueSlider(
    title: String,
    fraction: Float,
    label: String,
    keyStep: Float,
    toValue: (Float) -> Float,
    onDrag: (Float?) -> Unit,
    onDone: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        SlimSeekBar(
            fraction = fraction.coerceIn(0f, 1f),
            enabled = true,
            onSeek = { onDone(toValue(it)) },
            onScrub = { onDrag(it?.let(toValue)) },
            trackColor = DesignColors.OutlineSoft,
            modifier = Modifier.weight(1f),
            a11y = SliderA11y(title, label, keyStep),
        )
        Text(
            label,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent, textAlign = TextAlign.End),
            modifier = Modifier.widthIn(min = DesignSpacing.Space48),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
        colors.forEach { hex ->
            val color = captionColor(hex, Color.White)
            val isSelected = hex.equals(selected, ignoreCase = true)
            val outline = DesignColors.Outline
            val ring = if (isSelected) DesignColors.Accent else outline
            Box(
                Modifier
                    .size(DesignSpacing.Space32)
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton) { onPick(hex) }
                    .drawBehind {
                        val outer = size.minDimension / 2
                        if (isSelected) {
                            val stroke = 2.dp.toPx()
                            val inner = outer - stroke - 2.dp.toPx()
                            drawCircle(ring, radius = outer - stroke / 2, style = Stroke(stroke))
                            drawCircle(color, radius = inner)
                            drawCircle(outline, radius = inner, style = Stroke(1.dp.toPx()))
                        } else {
                            val stroke = 1.dp.toPx()
                            drawCircle(color, radius = outer - stroke)
                            drawCircle(ring, radius = outer - stroke, style = Stroke(stroke))
                        }
                    }
                    .semantics {
                        contentDescription = "#$hex"
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        Modifier.size(DesignSpacing.Space16),
                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}
