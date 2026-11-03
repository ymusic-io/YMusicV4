package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.caption_size_preview
import io.ymusic.ui.resources.settings_caption_size
import io.ymusic.ui.resources.settings_caption_size_hint
import io.ymusic.ui.resources.sound_ok
import io.ymusic.ui.resources.sound_reset
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.round

private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 2f
private const val SCALE_STEP = 0.1f

private fun snapScale(value: Float): Float = (round(value / SCALE_STEP) * SCALE_STEP).coerceIn(MIN_SCALE, MAX_SCALE)

private fun scaleLabel(scale: Float): String = "${trimFloat(scale)}×"

@Composable
internal fun CaptionSizeRow(graph: AppGraph) {
    val scale by remember(graph) { graph.settings.flow(AppSettings.captionScale) }.collectAsState()
    var open by remember { mutableStateOf(false) }
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
                stringResource(Res.string.settings_caption_size),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                stringResource(Res.string.settings_caption_size_hint),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
        Text(scaleLabel(scale), style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
    }
    if (open) CaptionSizeDialog(graph, scale, onDismiss = { open = false })
}

@Composable
private fun CaptionSizeDialog(graph: AppGraph, saved: Float, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing ?: saved
    fun save(value: Float) {
        scope.launch { graph.settings.put(AppSettings.captionScale, value) }
    }
    fun fractionToScale(fraction: Float) = snapScale(MIN_SCALE + fraction * (MAX_SCALE - MIN_SCALE))
    val title = stringResource(Res.string.settings_caption_size)
    ElevatedBarDialog(
        title = title,
        onDismiss = onDismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.sound_reset)) { save(AppSettings.captionScale.default) }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sound_ok), color = DesignColors.Accent)
            }
        },
    ) {
        Column(
            Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            // Tall enough for the largest size, so the dialog doesn't resize while dragging.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = captionTwoRowHeight(MAX_SCALE) + DesignSpacing.Space16 * 2)
                    .background(Color(0xFF202020), RoundedCornerShape(8.dp))
                    .padding(DesignSpacing.Space16),
                contentAlignment = Alignment.BottomCenter,
            ) {
                CaptionCue(stringResource(Res.string.caption_size_preview), captionStyle(shown))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
                SlimSeekBar(
                    fraction = (shown - MIN_SCALE) / (MAX_SCALE - MIN_SCALE),
                    enabled = true,
                    onSeek = { fraction ->
                        scrubbing = null
                        save(fractionToScale(fraction))
                    },
                    onScrub = { fraction -> scrubbing = fraction?.let(::fractionToScale) },
                    trackColor = DesignColors.OutlineSoft,
                    modifier = Modifier.weight(1f),
                    a11y = SliderA11y(title, scaleLabel(shown), SCALE_STEP / (MAX_SCALE - MIN_SCALE)),
                )
                Text(
                    scaleLabel(shown),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent, textAlign = TextAlign.End),
                    modifier = Modifier.widthIn(min = DesignSpacing.Space48),
                )
            }
        }
    }
}
