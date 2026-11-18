package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ymusic.player.Player

/**
 * Letterbox [surface] inside [modifier] using the playing picture's aspect ratio. [fill] stretches
 * to the hole (fullscreen toggle). Black shows in the unused region; on [desktopLayoutHole] it is
 * only the bars, never the picture tile (that tile is glass over the native Frame).
 */
@Composable
internal fun VideoHole(
    player: Player,
    surface: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val status by player.status.collectAsStateWithLifecycle()
    val ratio = status.videoSize?.aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
    var holeBounds by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(
        modifier.then(
            if (desktopLayoutHole) {
                // Bars are everything but the hole's laid-out rect, so they meet it to the pixel.
                Modifier.drawBehind {
                    clipRect(holeBounds.left, holeBounds.top, holeBounds.right, holeBounds.bottom, ClipOp.Difference) {
                        drawRect(Color.Black)
                    }
                }
            } else {
                Modifier.background(Color.Black)
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        val unbounded = maxHeight == Dp.Infinity || maxHeight == Dp.Unspecified
        val hole = if (fill) {
            Modifier.fillMaxSize()
        } else {
            val parentRatio =
                if (!unbounded && maxHeight > 0.dp) maxWidth / maxHeight else ratio
            if (unbounded || parentRatio <= ratio) {
                Modifier.fillMaxWidth().aspectRatio(ratio)
            } else {
                Modifier.fillMaxHeight().aspectRatio(ratio)
            }
        }
        surface(if (desktopLayoutHole) hole.onPlaced { holeBounds = it.boundsInParent() } else hole)
    }
}
