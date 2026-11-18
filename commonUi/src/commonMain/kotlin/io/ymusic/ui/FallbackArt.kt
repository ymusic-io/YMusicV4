package io.ymusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** The art url of an item with none, for surfaces that take a url (the system media session). */
const val FALLBACK_ART_URL = "art://fallback"

/** How far the fallback art's bottom edge darkens the accent toward black. */
const val FALLBACK_ART_SHADE = 0.35f

/** The note's side as a share of the art's shorter side. */
const val FALLBACK_ART_ICON_SHARE = 0.6f

const val FALLBACK_ART_ICON_ALPHA = 0.85f

/**
 * The picture for an item with no art, or whose every art source failed: [accent] fading darker
 * downward under a large music note. The accent is capped at a luminance white reads on
 * ([MaxAccentLuminance]), so it reads in light and dark themes alike.
 */
fun DrawScope.drawFallbackArt(accent: Color) {
    drawRect(Brush.verticalGradient(listOf(accent, lerp(accent, Color.Black, FALLBACK_ART_SHADE))))
    val side = size.minDimension * FALLBACK_ART_ICON_SHARE
    val viewport = Icons.Default.MusicNote.viewportWidth
    translate(left = (size.width - side) / 2f, top = (size.height - side) / 2f) {
        scale(side / viewport, pivot = Offset.Zero) {
            drawPath(musicNotePath(), Color.White.copy(alpha = FALLBACK_ART_ICON_ALPHA))
        }
    }
}

/** A fresh path each call: drawn from the UI thread and from bitmap renders on worker threads. */
private fun musicNotePath(): Path {
    val path = Path()
    fun add(group: VectorGroup) {
        for (node in group) {
            when (node) {
                is VectorPath -> path.addPath(node.pathData.toPath())
                is VectorGroup -> add(node)
            }
        }
    }
    add(Icons.Default.MusicNote.root)
    return path
}

/**
 * [drawFallbackArt] over the box, for an item with no art url or a load that definitively failed.
 * It fades in like a loaded picture; [alpha] instead lets a caller run that fade itself.
 */
@Composable
internal fun BoxScope.FallbackArt(alpha: (() -> Float)? = null) {
    val accent = DesignColors.Accent
    val fadeIn = remember { Animatable(if (alpha == null) 0f else 1f) }
    if (alpha == null) {
        LaunchedEffect(Unit) { fadeIn.animateTo(1f, tween(FALLBACK_ART_FADE_IN_MS, easing = FastOutSlowInEasing)) }
    }
    Canvas(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha?.invoke() ?: fadeIn.value }) {
        drawFallbackArt(accent)
    }
}

private const val FALLBACK_ART_FADE_IN_MS = 420

/** [drawFallbackArt] as a [px]-square bitmap, for surfaces outside Compose (the system media session). */
fun fallbackArtBitmap(accent: Color, px: Int): ImageBitmap {
    val bitmap = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        drawFallbackArt(accent)
    }
    return bitmap
}
