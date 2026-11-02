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
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window

/**
 * Floating, zoom (fit the work area), and fullscreen: native on macOS, the window manager's on Linux,
 * on Windows the undecorated window sized to its monitor's full bounds.
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
        val (size, position) = linuxFloatingFrame(state.size, state.position)
        floatingSize = size
        floatingPosition = position
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
        if (isLinux) return toggleWmFullscreen(w)
        if (fullscreen) return onNativeFullscreen(false)
        if (!zoomed) {
            floatingSize = state.size
            floatingPosition = state.position
        }
        fullscreen = true
        val b = w.graphicsConfiguration.bounds
        put(DpSize(b.width.dp, b.height.dp), WindowPosition(b.x.dp, b.y.dp))
    }

    /**
     * The window manager's own fullscreen (_NET_WM_STATE_FULLSCREEN), entered and left through AWT. A
     * monitor-sized undecorated window is promoted to that state by Mutter anyway, and then ignores the
     * resize that would end it; leaving the state restores the window's previous frame.
     */
    private fun toggleWmFullscreen(w: Window) {
        val device = w.graphicsConfiguration.device
        if (fullscreen) {
            if (device.fullScreenWindow === w) device.fullScreenWindow = null
            fullscreen = false
        } else {
            fullscreen = true
            device.fullScreenWindow = w
        }
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

internal fun workArea(w: Window): Pair<DpSize, WindowPosition> = workArea(w.graphicsConfiguration)

private fun workArea(gc: GraphicsConfiguration): Pair<DpSize, WindowPosition> {
    val b = gc.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val width = (b.width - insets.left - insets.right).coerceAtLeast(320)
    val height = (b.height - insets.top - insets.bottom).coerceAtLeast(480)
    return DpSize(width.dp, height.dp) to WindowPosition((b.x + insets.left).dp, (b.y + insets.top).dp)
}

private fun covers(size: DpSize, gc: GraphicsConfiguration): Boolean =
    size.width.value >= gc.bounds.width && size.height.value >= gc.bounds.height

/**
 * A floating frame for Linux that is not its monitor's full size: GNOME takes an undecorated window that
 * covers the monitor for a fullscreen one, and then ignores the resizes that would shrink it.
 */
internal fun linuxFloatingFrame(size: DpSize, position: WindowPosition): Pair<DpSize, WindowPosition> {
    if (!isLinux) return size to position
    val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
    val at = (position as? WindowPosition.Absolute)?.let { p ->
        screens.firstOrNull { it.bounds.contains(p.x.value.toInt(), p.y.value.toInt()) }
    } ?: screens.firstOrNull()
    return if (at != null && covers(size, at)) workArea(at) else size to position
}
