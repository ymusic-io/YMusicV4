package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.ymusic.player.SkipSegment
import kotlin.math.roundToInt

/** Amber marks on the seek track — contrast against the orange fill, like the legacy skip overlay. */
val DefaultSkipSegmentColor = Color(0xFFFFC107)

fun skipSegmentFractions(segments: List<SkipSegment>, durationMs: Long): List<Pair<Float, Float>> {
    if (durationMs <= 0L || segments.isEmpty()) return emptyList()
    return segments.map { seg ->
        val start = (seg.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val endMs = seg.startMs + seg.lengthMs
        val end = if (endMs >= durationMs - 1_000L) {
            1f
        } else {
            (endMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
        start to end
    }
}

/** What a screen reader says for a [SlimSeekBar]: [label] names it, [value] says in words where it
 *  stands. Focused, the left/right arrow keys move it by [keyStep] of its length. */
class SliderA11y(val label: String?, val value: String, val keyStep: Float)

/**
 * The app's slim seek bar, built from scratch: the M3 `Slider`'s internal layout does not center
 * its thumb/track slots inside a height-constrained row — the thumb clips into a floating dot and
 * the bar sits off the row's centerline. Here the track and thumb are children of ONE CenterStart
 * box, so everything sits on the centerline by construction, at any [barHeight].
 *
 * Tap seeks. A drag scrubs — the shown position holds locally so the live tick doesn't fight the
 * thumb — and [onSeek] fires ONCE on release (a cancelled drag discards), so scrubbing never
 * floods the engine with intermediate seeks.
 */
@Composable
fun SlimSeekBar(
    fraction: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    trackColor: Color,
    modifier: Modifier = Modifier,
    fillColor: Color = DesignColors.Accent,
    bufferedFraction: Float = 0f,
    bufferedColor: Color = fillColor.copy(alpha = 0.35f),
    thumbColor: Color = DesignColors.Accent,
    thumbSize: Dp = 13.dp,
    trackHeight: Dp = 3.dp,
    barHeight: Dp = 20.dp,
    thumbShadow: Dp = 1.dp,
    /** Live fraction while dragging. Null on release or cancel. */
    onScrub: ((Float?) -> Unit)? = null,
    skipFractions: List<Pair<Float, Float>> = emptyList(),
    skipColor: Color = DefaultSkipSegmentColor,
    a11y: SliderA11y? = null,
) {
    var widthPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val buffered = bufferedFraction.coerceIn(0f, 1f)
    val seek = rememberUpdatedState(onSeek)
    val scrub = rememberUpdatedState(onScrub)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Pointer x is physical; the fill grows from the start edge.
    fun fractionAt(x: Float, width: Int) = ((if (rtl) width - x else x) / width).coerceIn(0f, 1f)
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    Box(
        modifier
            .height(barHeight)
            .then(
                if (a11y == null) {
                    Modifier
                } else {
                    Modifier.adjustable(a11y.label, a11y.value, shown, a11y.keyStep, enabled, focus, rtl = rtl) { seek.value(it) }
                },
            )
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> seek.value(fractionAt(offset.x, size.width)) }
            }
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val f = fractionAt(offset.x, size.width)
                        dragging = f
                        scrub.value?.invoke(f)
                        VideoOverlayNative.scrubbing = true
                        VideoOverlayNative.show()
                    },
                    onHorizontalDrag = { change, _ ->
                        val f = fractionAt(change.position.x, size.width)
                        dragging = f
                        scrub.value?.invoke(f)
                    },
                    onDragEnd = {
                        dragging?.let { seek.value(it) }
                        dragging = null
                        scrub.value?.invoke(null)
                        VideoOverlayNative.scrubbing = false
                    },
                    onDragCancel = {
                        dragging = null
                        scrub.value?.invoke(null)
                        VideoOverlayNative.scrubbing = false
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(trackColor)
                .drawWithContent {
                    drawContent()
                    skipFractions.forEach { (begin, end) ->
                        val startPx = size.width * begin.coerceIn(0f, 1f)
                        val endPx = size.width * end.coerceIn(0f, 1f)
                        val left = if (rtl) size.width - endPx else startPx
                        val right = if (rtl) size.width - startPx else endPx
                        if (right > left) {
                            drawRect(
                                skipColor,
                                topLeft = Offset(left, 0f),
                                size = Size(right - left, size.height),
                            )
                        }
                    }
                },
        ) {
            if (buffered > 0f) {
                Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(bufferedColor))
            }
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(fillColor))
        }
        Box(
            Modifier
                .offset { IntOffset((shown * (widthPx - thumbSize.toPx())).roundToInt().coerceAtLeast(0), 0) }
                .size(thumbSize)
                .then(
                    if (focused) {
                        Modifier.drawBehind { drawCircle(thumbColor.copy(alpha = 0.3f), size.minDimension / 2 + FocusRing.toPx()) }
                    } else {
                        Modifier
                    },
                )
                .shadow(thumbShadow, CircleShape)
                .background(thumbColor, CircleShape),
        )
    }
}

private val FocusRing = 5.dp
