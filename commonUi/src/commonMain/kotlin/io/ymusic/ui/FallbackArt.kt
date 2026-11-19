package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** The art url of an item with none, for surfaces that take a url (the system media session). */
const val FALLBACK_ART_URL = "art://fallback"

/** Deep enough that the white note reads on each, in light and dark themes alike. */
private val FALLBACK_ART_PALETTE = longArrayOf(
    0xFFC62828, 0xFFAD1457, 0xFF6A1B9A, 0xFF4527A0, 0xFF283593, 0xFF1565C0, 0xFF0277BD, 0xFF00838F,
    0xFF00695C, 0xFF2E7D32, 0xFF558B2F, 0xFF827717, 0xFFE65100, 0xFFD84315, 0xFF4E342E, 0xFF37474F,
)

/** [key]'s fallback art colour; the key is the art's image identity, so every surface agrees. */
fun fallbackArtColor(key: String?): Color = Color(FALLBACK_ART_PALETTE[key.hashCode().mod(FALLBACK_ART_PALETTE.size)])

private fun DrawScope.drawFallbackArt(color: Color) {
    drawRect(Brush.verticalGradient(listOf(color, lerp(color, Color.Black, 0.35f))))
    val side = size.minDimension * 0.6f
    translate(left = (size.width - side) / 2f, top = (size.height - side) / 2f) {
        scale(side / Icons.Default.MusicNote.viewportWidth, pivot = Offset.Zero) {
            drawPath(musicNotePath(), Color.White.copy(alpha = 0.85f))
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

/** The picture for art with no source, or whose load failed: [key]'s colour under a music note. */
@Composable
internal fun BoxScope.FallbackArt(key: String?) {
    val color = fallbackArtColor(key)
    Canvas(Modifier.matchParentSize()) { drawFallbackArt(color) }
}

/** [FallbackArt] as a [px]-square bitmap, for surfaces outside Compose (the system media session). */
fun fallbackArtBitmap(color: Color, px: Int): ImageBitmap {
    val bitmap = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        drawFallbackArt(color)
    }
    return bitmap
}
