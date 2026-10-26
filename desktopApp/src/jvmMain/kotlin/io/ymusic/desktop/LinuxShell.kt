package io.ymusic.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kmpcommon.WorkerThread
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

internal val isLinux = "linux" in System.getProperty("os.name").orEmpty().lowercase()

/** WM_CLASS of every AWT window: XToolkit derives it from the main class name. */
private const val LINUX_WM_CLASS = "io-ymusic-desktop-MainKt"

/**
 * A [px]-square raster of the painter drawn at [scale] × [px], centered (overflow cropped).
 *
 * Hack: supersampled. The logo's stacked layers each AA-clip the same rim, so at 1x an edge pixel's
 * coverage compounds to near-opaque (a jagged circle); at [factor]x that error shrinks to a sliver.
 */
internal fun Painter.rasterize(px: Int, scale: Float = 1f, tint: Color? = null): BufferedImage {
    val factor = (1024 / px).coerceIn(1, 8)
    val big = px * factor
    val bitmap = ImageBitmap(big, big)
    val drawn = big * scale
    val offset = (big - drawn) / 2
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(big.toFloat(), big.toFloat())) {
        translate(offset, offset) {
            with(this@rasterize) { draw(Size(drawn, drawn), colorFilter = tint?.let(ColorFilter::tint)) }
        }
    }
    return bitmap.toAwtImage().boxDownsample(factor)
}

/** Averages each [f]×[f] block, alpha-weighted so transparent pixels don't darken the edge. */
private fun BufferedImage.boxDownsample(f: Int): BufferedImage {
    if (f == 1) return this
    val w = width / f
    val h = height / f
    val src = getRGB(0, 0, width, height, null, 0, width)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val n = f * f
    for (y in 0 until h) {
        for (x in 0 until w) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until f) {
                for (dx in 0 until f) {
                    val p = src[(y * f + dy) * width + x * f + dx]
                    val pa = p ushr 24
                    a += pa
                    r += (p shr 16 and 0xFF) * pa
                    g += (p shr 8 and 0xFF) * pa
                    b += (p and 0xFF) * pa
                }
            }
            out.setRGB(x, y, if (a == 0) 0 else ((a + n / 2) / n shl 24) or (r / a shl 16) or (g / a shl 8) or (b / a))
        }
    }
    return out
}

/**
 * Hack: the XEmbed frame hosting our TrayIcon. The tray host resizes it (GNOME: 16×16) but the
 * peer keeps painting its fixed 24px canvas, so the image must be sized to this frame to fit.
 */
internal fun linuxTrayEmbedFrame(): Window? =
    Window.getWindows().lastOrNull { it.javaClass.name.endsWith("XTrayIconEmbeddedFrame") }

/**
 * Hack: AWT routes every pointer event to a pressed window until a release that frees all buttons
 * (XBaseWindow.isFullRelease). GNOME's tray synthesizes the release with an empty button state, so
 * that routing never ends and hover/clicks on the mini player land on the tray icon instead.
 */
internal fun clearAwtPointerAutoGrab() {
    Class.forName("sun.awt.X11.XAwtState")
        .getDeclaredMethod("setAutoGrabWindow", Class.forName("sun.awt.X11.XBaseWindow"))
        .apply { isAccessible = true }
        .invoke(null, null)
}

/**
 * GNOME Shell takes window icons only from a .desktop entry matched by StartupWMClass, so a hidden
 * (NoDisplay) entry maps our windows to the logo. Rewritten only when its contents change.
 */
@WorkerThread
internal fun installLinuxDesktopEntry(icon: BufferedImage) {
    val iconFile = File(ymusicDataDir().apply { mkdirs() }, "ymusic.png")
    val png = ByteArrayOutputStream().also { ImageIO.write(icon, "png", it) }.toByteArray()
    if (!iconFile.exists() || !iconFile.readBytes().contentEquals(png)) iconFile.writeBytes(png)

    val exec = System.getProperty("jpackage.app-path")
        ?: ProcessHandle.current().info().command().orElse("ymusic")
    val entry = """
        |[Desktop Entry]
        |Type=Application
        |Name=YMusic
        |Exec="$exec"
        |Icon=${iconFile.absolutePath}
        |StartupWMClass=$LINUX_WM_CLASS
        |NoDisplay=true
        |
    """.trimMargin()
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share")
    val entryFile = File(File(dataHome, "applications").apply { mkdirs() }, "io.ymusic.desktop.desktop")
    if (!entryFile.exists() || entryFile.readText() != entry) entryFile.writeText(entry)
}
