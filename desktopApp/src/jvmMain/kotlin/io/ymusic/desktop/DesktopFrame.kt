package io.ymusic.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.ymusic.mse.FileAvNative
import java.awt.Toolkit
import java.awt.Window

/**
 * Floating, zoom (fit the work area), and fullscreen: native on macOS, elsewhere the undecorated
 * window sized to its monitor's full bounds.
 *
 * Never uses [WindowPlacement.Maximized]: AWT's maximized flag fights
 * [NSWindow.toggleFullScreen] and forgets the floating size.
 */
internal class DesktopFrame(
    private val state: WindowState,
    startSize: DpSize,
    startPosition: WindowPosition,
) {
    var host: Window? = null
    var fullscreen by mutableStateOf(false)
        private set
    var zoomed by mutableStateOf(false)
        private set

    private var floatingSize = startSize
    private var floatingPosition = startPosition

    fun persistSize(): DpSize = floatingSize

    fun persistPosition(): WindowPosition = floatingPosition

    fun noteFloatingIfIdle() {
        if (fullscreen || zoomed) return
        if (state.placement != WindowPlacement.Floating) return
        floatingSize = state.size
        floatingPosition = state.position
    }

    fun toggleZoom() {
        if (fullscreen) return
        val w = host ?: return
        if (zoomed) {
            zoomed = false
            put(floatingSize, floatingPosition)
        } else {
            floatingSize = state.size
            floatingPosition = state.position
            zoomed = true
            val (size, position) = workArea(w)
            put(size, position)
        }
    }

    fun toggleFullscreen() {
        val w = host ?: return
        if (isMac) return FileAvNative.toggleFullScreen(w)
        if (fullscreen) return onNativeFullscreen(false)
        if (!zoomed) {
            floatingSize = state.size
            floatingPosition = state.position
        }
        fullscreen = true
        val b = w.graphicsConfiguration.bounds
        put(DpSize(b.width.dp, b.height.dp), WindowPosition(b.x.dp, b.y.dp))
    }

    fun onNativeFullscreen(on: Boolean) {
        val w = host ?: return
        fullscreen = on
        if (on) {
            val b = w.graphicsConfiguration.bounds
            state.size = DpSize(b.width.dp, b.height.dp)
            return
        }
        if (zoomed) {
            val (size, position) = workArea(w)
            put(size, position)
        } else {
            put(floatingSize, floatingPosition)
        }
    }

    private fun put(size: DpSize, position: WindowPosition) {
        state.placement = WindowPlacement.Floating
        state.size = size
        state.position = position
    }
}

internal fun workArea(w: Window): Pair<DpSize, WindowPosition> {
    val gc = w.graphicsConfiguration
    val b = gc.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val width = (b.width - insets.left - insets.right).coerceAtLeast(320)
    val height = (b.height - insets.top - insets.bottom).coerceAtLeast(480)
    return DpSize(width.dp, height.dp) to WindowPosition((b.x + insets.left).dp, (b.y + insets.top).dp)
}
