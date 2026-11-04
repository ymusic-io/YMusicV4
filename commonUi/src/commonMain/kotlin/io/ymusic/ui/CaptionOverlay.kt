package io.ymusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.ymusic.player.CaptionLine

/**
 * YouTube-style caption box: each [CaptionLine] is one cue whose `\n`s are hard breaks and whose long rows
 * wrap — never truncated. Rows are left-aligned at a fixed inset ([captionInsetFraction]). Shows up to two rows, more when
 * the newest cue alone needs them; the newest cue is at the bottom, and an older cue that no longer fits whole
 * is dropped. A cue that joins an overlapping window (roll-up) scrolls in when it is a single row; anything
 * taller swaps.
 */
@Composable
fun CaptionOverlay(lines: List<CaptionLine>, look: CaptionLook, modifier: Modifier = Modifier) {
    val style = captionStyle(look.scale)
    val inset = captionInsetFraction()
    val strip = remember { CaptionStrip() }
    val frame = strip.advance(lines)
    val scroll = remember(frame.generation) { Animatable(if (frame.tailCount > 0) 1f else 0f) }
    LaunchedEffect(frame.generation) {
        scroll.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }

    Layout(
        content = {
            frame.entries.forEach { entry ->
                key(entry.id) { CaptionCue(entry.text, style, look) }
            }
        },
        modifier = modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val rowPx = captionRowHeight(style).roundToPx()
        val width = constraints.maxWidth
        val left = (width * inset).toInt()
        val cueWidth = (width - left * 2).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = cueWidth)) }
        val totalHeight = placeables.sumOf { it.height }
        val boxHeight = minOf(totalHeight, maxOf(rowPx * 2, placeables.lastOrNull()?.height ?: 0))
        val tailHeight = placeables.takeLast(frame.tailCount).sumOf { it.height }
        val rollUp = if (tailHeight < rowPx * 3 / 2) tailHeight else 0
        val restTops = placeables.runningFold(boxHeight - totalHeight) { top, placeable -> top + placeable.height }
        layout(width, boxHeight) {
            val offset = (scroll.value * rollUp).toInt()
            placeables.forEachIndexed { index, placeable ->
                if (offset > 0 || restTops[index] >= 0) placeable.place(left, restTops[index] + offset)
            }
        }
    }
}

/**
 * Left/right caption inset as a fraction of the picture: phones (window's short side under 600dp) 3% portrait,
 * 5% landscape; larger windows grow from 10% at 600dp wide to 20% at 1920dp.
 */
@Composable
private fun captionInsetFraction(): Float {
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    val portrait = window.height > window.width
    if (minOf(window.width, window.height) < 600.dp) return if (portrait) 0.03f else 0.05f
    val growth = ((window.width - 600.dp) / (1920.dp - 600.dp)).coerceIn(0f, 1f)
    return 0.1f + 0.1f * growth
}

private fun Density.captionRowHeight(style: TextStyle): Dp = style.lineHeight.toDp() + DesignSpacing.Space2 * 2

/** Two caption rows: what the area below the picture must fit for captions to sit there. */
@Composable
fun captionTwoRowHeight(scale: Float): Dp = with(LocalDensity.current) { captionRowHeight(captionStyle(scale)) * 2 }

@Composable
internal fun captionStyle(scale: Float): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    return base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
}

/** The user's caption style: size multiplier, text color, and box color (alpha = its opacity). */
data class CaptionLook(val scale: Float, val text: Color, val background: Color)

internal fun captionColor(hex: String, fallback: Color): Color =
    hex.toLongOrNull(16)?.takeIf { hex.length == 6 }?.let { Color(0xFF000000 or it) } ?: fallback

@Composable
internal fun CaptionCue(text: String, style: TextStyle, look: CaptionLook) {
    Text(
        text = text,
        color = look.text,
        style = style,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .background(look.background, RoundedCornerShape(4.dp))
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space2),
    )
}

private class CaptionFrame(val entries: List<CaptionLine>, val tailCount: Int, val generation: Int)

/** Keeps cues that just left the window long enough to scroll out above the box. Idempotent per input. */
private class CaptionStrip {
    private val held = mutableMapOf<Int, String>()
    private var lastIds: List<Int> = emptyList()
    private var entryIds: List<Int> = emptyList()
    private var tailCount = 0
    private var generation = 0

    fun advance(lines: List<CaptionLine>): CaptionFrame {
        val ids = lines.map { it.id }
        if (ids != lastIds) {
            val sequential = lastIds.isNotEmpty() && ids.any { it in lastIds }
            if (sequential) {
                val tail = ids.filter { it !in entryIds }
                entryIds = (entryIds.filter { it !in tail } + tail).takeLast(ids.size + 1)
                tailCount = tail.size
            } else {
                entryIds = ids
                tailCount = 0
            }
            lastIds = ids
            generation++
        }
        val texts = lines.associate { it.id to it.text }
        val entries = entryIds.mapNotNull { id ->
            texts[id]?.let { CaptionLine(id, it) } ?: held[id]?.let { CaptionLine(id, it) }
        }
        held.clear()
        entries.forEach { held[it.id] = it.text }
        return CaptionFrame(entries, tailCount, generation)
    }
}
