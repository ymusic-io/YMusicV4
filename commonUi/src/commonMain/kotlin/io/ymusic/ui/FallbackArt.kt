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
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ymusic.player.PlayableItem
import kmpcommon.uri.PercentEncoder

/** The art url of an item with none, for surfaces that take a url (the system media session). */
const val FALLBACK_ART_URL = "art://fallback"

/** NFC: a macOS-decomposed name hashes like the composed one. */
internal expect fun nfc(text: String): String

/** The note's side as a share of the art's shorter side. */
const val FALLBACK_ART_ICON_SHARE = 0.6f

const val FALLBACK_ART_ICON_ALPHA = 0.85f

/**
 * The fallback art's colours for one item, top and bottom of its gradient, as `0xRRGGBB`. Integers
 * so the Rust app's `fallback_art.rs` produces the same pixels.
 */
data class FallbackArtColors(val top: Int, val bottom: Int)

/**
 * Sixteen deep colours (Material 800s, lime and orange at 900), each dark enough that the white
 * note reads at 3.8:1 or better, in light and dark themes alike. Indexed by [fallbackArtColors].
 */
private val FALLBACK_ART_PALETTE = intArrayOf(
    0xC62828, 0xAD1457, 0x6A1B9A, 0x4527A0, 0x283593, 0x1565C0, 0x0277BD, 0x00838F,
    0x00695C, 0x2E7D32, 0x558B2F, 0x827717, 0xE65100, 0xD84315, 0x4E342E, 0x37474F,
)

/** The bottom edge keeps this share of each top channel (integer, so both apps agree). */
private const val FALLBACK_ART_BOTTOM_PERCENT = 65

/**
 * What an item's fallback colour is derived from, the same on every platform: its file's name
 * (NFC, without the extension), else its YouTube video id, else its title (NFC). So a song keeps
 * its colour across launches, rescans, moves and both apps.
 */
fun fallbackArtKey(fileName: String?, videoId: String?, title: String?): String {
    fileName?.takeIf { it.isNotBlank() }?.let { name ->
        val normalized = nfc(name)
        val dot = normalized.lastIndexOf('.')
        return if (dot > 0) normalized.substring(0, dot) else normalized
    }
    videoId?.takeIf { it.isNotBlank() }?.let { return it }
    return nfc(title.orEmpty())
}

/** [fallbackArtKey] for a queue item: a local file's name from its uri (a SAF document's, a path's). */
fun PlayableItem.fallbackArtKey(): String =
    fallbackArtKey((this as? PlayableItem.LocalMedia)?.uri?.let(::fileNameOf), videoId, displayTitle)

/** The last segment of a path or file uri, percent-decoded; a SAF document id's name after its `/` or volume `:`. */
internal fun fileNameOf(uri: String): String? {
    val decoded = PercentEncoder.decode(uri.substringBefore('?').substringAfterLast('/')).toString()
    val name = decoded.substringAfterLast('/').substringAfterLast(':')
    return name.takeIf { it.isNotBlank() }
}

/** The palette entry FNV-1a 32 of [key]'s UTF-8 bytes picks, and its darker bottom. */
fun fallbackArtColors(key: String): FallbackArtColors {
    val top = FALLBACK_ART_PALETTE[(fnv1a32(key.encodeToByteArray()) % FALLBACK_ART_PALETTE.size.toUInt()).toInt()]
    fun shade(shift: Int) = (((top shr shift) and 0xFF) * FALLBACK_ART_BOTTOM_PERCENT / 100) shl shift
    return FallbackArtColors(top, shade(16) or shade(8) or shade(0))
}

/** 32-bit FNV-1a: "" is 811c9dc5, "a" e40c292c, "foobar" bf9cf968. */
internal fun fnv1a32(bytes: ByteArray): UInt {
    var hash = 0x811C9DC5u
    for (byte in bytes) {
        hash = (hash xor (byte.toUInt() and 0xFFu)) * 0x01000193u
    }
    return hash
}

private fun rgb(value: Int): Color = Color(0xFF000000 or value.toLong())

/**
 * The picture for an item with no art, or whose every art source failed: its [colors] (see
 * [fallbackArtColors]) fading darker downward under a large music note.
 */
fun DrawScope.drawFallbackArt(colors: FallbackArtColors) {
    drawRect(Brush.verticalGradient(listOf(rgb(colors.top), rgb(colors.bottom))))
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
internal fun BoxScope.FallbackArt(colors: FallbackArtColors, alpha: (() -> Float)? = null) {
    val fadeIn = remember { Animatable(if (alpha == null) 0f else 1f) }
    if (alpha == null) {
        LaunchedEffect(Unit) { fadeIn.animateTo(1f, tween(FALLBACK_ART_FADE_IN_MS, easing = FastOutSlowInEasing)) }
    }
    Canvas(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha?.invoke() ?: fadeIn.value }) {
        drawFallbackArt(colors)
    }
}

private const val FALLBACK_ART_FADE_IN_MS = 420

/** [drawFallbackArt] as a [px]-square bitmap, for surfaces outside Compose (the system media session). */
fun fallbackArtBitmap(colors: FallbackArtColors, px: Int): ImageBitmap {
    val bitmap = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        drawFallbackArt(colors)
    }
    return bitmap
}
