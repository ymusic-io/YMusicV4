package io.ymusic.playground

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.min
import java.awt.Color as AwtColor

/**
 * MSE preview: Swing paints BGRA, Compose does not recompose each frame.
 *
 * Compose Desktop's SwingPanel always installs a pointer-interop filter that
 * redispatches the click into this component via [javax.swing.SwingUtilities.getDeepestComponentAt]
 * and then **consumes** it. Returning false from [contains] makes that lookup
 * miss, so the event stays in Compose (popups, menus, overlays over the video).
 */
@Composable
internal actual fun MseNativeVideo(
    surface: MseVideoSurface,
    player: DynamicEnginePlayer?,
    sink: MseVideoSink,
    placeholder: String,
    modifier: Modifier,
    @Suppress("UNUSED_PARAMETER") pictureWidth: Int,
    @Suppress("UNUSED_PARAMETER") pictureHeight: Int,
) {
    val panel = remember(sink) { MseVideoPanel(sink) }
    DisposableEffect(surface, panel) {
        val video = surface.video
        panel.onPainted = { video.painting = it }
        val blit: (BufferedImage?) -> Unit = { panel.setImage(it) }
        video.onCpuFrame = blit
        onDispose {
            panel.onPainted = null
            if (video.onCpuFrame === blit) video.onCpuFrame = null
        }
    }
    SwingPanel(
        background = Color.Black,
        factory = { panel },
        update = { it.placeholder = placeholder },
        modifier = modifier,
    )
}

private class MseVideoPanel(private val sink: MseVideoSink) : JPanel() {
    @Volatile
    var placeholder: String = "Waiting for video…"
        set(value) {
            if (field == value) return
            field = value
            if (img == null) repaintOnEdt()
        }

    @Volatile
    private var img: BufferedImage? = null

    @Volatile
    var onPainted: ((BufferedImage?) -> Unit)? = null

    private val pending = AtomicBoolean(false)

    init {
        isOpaque = true
        background = AwtColor.BLACK
        isFocusable = false
        isEnabled = false
        isRequestFocusEnabled = false
    }

    /**
     * Always miss AWT hit-testing. Compose's interop filter uses this to decide
     * whether to consume the pointer event; a miss leaves the click in Compose.
     */
    override fun contains(x: Int, y: Int): Boolean = false

    fun setImage(image: BufferedImage?) {
        img = image
        if (image != null) {
            sink.frameCount++
            sink.frameW = image.width
            sink.frameH = image.height
        }
        if (pending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                pending.set(false)
                repaint()
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.color = AwtColor.BLACK
        g2.fillRect(0, 0, width, height)
        val image = img
        onPainted?.invoke(image)
        if (image == null) {
            g2.color = AwtColor(255, 255, 255, 180)
            val s = placeholder
            val fm = g2.fontMetrics
            g2.drawString(s, (width - fm.stringWidth(s)).coerceAtLeast(0) / 2, height / 2)
            return
        }
        val scale = min(width / image.width.toDouble(), height / image.height.toDouble())
        val dw = (image.width * scale).toInt().coerceAtLeast(1)
        val dh = (image.height * scale).toInt().coerceAtLeast(1)
        val x = (width - dw) / 2
        val y = (height - dh) / 2
        if (dw == image.width && dh == image.height) {
            g2.drawImage(image, x, y, null)
        } else {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(image, x, y, dw, dh, null)
        }
    }

    private fun repaintOnEdt() {
        if (SwingUtilities.isEventDispatchThread()) repaint()
        else SwingUtilities.invokeLater { repaint() }
    }
}
