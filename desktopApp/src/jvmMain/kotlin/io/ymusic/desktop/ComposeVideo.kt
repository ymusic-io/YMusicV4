package io.ymusic.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The CPU video picture drawn by Compose, for Linux: the Compose window's X visual has no alpha, so the
 * underlay behind it never shows through. One reused bitmap, rewritten on the EDT, where Compose draws it.
 */
internal object ComposeVideoFrame {
    private val lock = Any()
    private var pixels = ByteArray(0)
    private var width = 0
    private var height = 0
    private var dirty = false
    private val scheduled = AtomicBoolean(false)
    private val bitmap = Bitmap()
    private val image: ImageBitmap = bitmap.asComposeImageBitmap()
    private val shown = mutableStateOf(false)
    private val generation = mutableIntStateOf(0)

    /** Presenting thread; [bgra] holds tight rows and is valid only during the call (null = clear). */
    fun publish(w: Int, h: Int, bgra: ByteBuffer?) {
        synchronized(lock) {
            if (bgra == null || w <= 0 || h <= 0) {
                width = 0
                height = 0
            } else {
                val n = w * h * 4
                if (pixels.size != n) pixels = ByteArray(n)
                bgra.get(0, pixels, 0, minOf(n, bgra.capacity()))
                width = w
                height = h
            }
            dirty = true
        }
        if (scheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(::install)
    }

    private fun install() {
        scheduled.set(false)
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            if (width == 0) {
                shown.value = false
                return
            }
            if (bitmap.width != width || bitmap.height != height) {
                bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
            }
            bitmap.installPixels(pixels)
        }
        shown.value = true
        generation.intValue++
    }

    @Composable
    fun Layer(modifier: Modifier = Modifier) {
        Canvas(modifier.fillMaxSize()) {
            generation.intValue
            drawRect(Color.Black)
            if (!shown.value || image.width <= 0 || image.height <= 0) return@Canvas
            val scale = min(size.width / image.width, size.height / image.height)
            val dw = (image.width * scale).roundToInt().coerceAtLeast(1)
            val dh = (image.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
                dstSize = IntSize(dw, dh),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}
