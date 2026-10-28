package io.ymusic.desktop

import io.ymusic.mse.FileAvNative
import java.awt.Canvas
import java.awt.Color
import java.awt.Container
import java.awt.Frame
import java.awt.Window
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Video-only owner Frame behind the transparent Compose UI. Owned dialogs stay above this
 * Frame, so the canvas cannot restack over chrome. Never toFront, never key.
 */
internal class VideoUnderlay {
    val frame: Frame = Frame("YMusic video").apply { // title matched in mse_vt_present fullscreen restack
        isUndecorated = true
        isResizable = false
        focusableWindowState = false
        isAutoRequestFocus = false
        background = Color.BLACK
        layout = java.awt.BorderLayout()
        setLocation(0, 0)
        setSize(1, 1)
    }

    /** The Compose window to stay just below. Set once by Main after it's displayable, used by
     *  setHole to re-establish z-order whenever the Frame transitions to visible or moves. */
    @Volatile var ownerWindow: Window? = null

    /** Follows the main window: a separate top-level frame on X11/Windows, it would outlive a hide. */
    var shown = true
        set(value) {
            field = value
            frame.isVisible = value
        }

    fun attach(panel: JComponent?) {
        frame.removeAll()
        if (panel != null) frame.add(panel, java.awt.BorderLayout.CENTER)
        frame.validate()
        frame.repaint()
        // Force a synchronous BLACK fill on the heavyweight Canvas inside, after its HWND is
        // realized. A fresh Canvas HWND on Windows holds transparent pixels until its first
        // WM_PAINT writes them — DWM composites past that transparency to whatever window happens
        // to be beneath the Compose transparent-hole (not necessarily this Frame). canvas.repaint()
        // alone just schedules an async paint that can be deferred past the user-visible gap;
        // drawing straight into getGraphics guarantees the Canvas has real pixels NOW.
        if (panel != null) {
            SwingUtilities.invokeLater { findCanvas(panel)?.let { blackoutCanvas(it) } }
        }
    }

    private fun findCanvas(c: Container): Canvas? {
        for (child in c.components) {
            if (child is Canvas) return child
            if (child is Container) findCanvas(child)?.let { return it }
        }
        return null
    }

    private fun blackoutCanvas(c: Canvas) {
        if (c.width <= 0 || c.height <= 0) return
        val g = c.graphics ?: return
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, c.width, c.height)
        } finally {
            g.dispose()
        }
    }

    fun setHole(x: Int, y: Int, w: Int, h: Int) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { setHole(x, y, w, h) }
            return
        }
        if (w < 1 || h < 1) {
            if (frame.width != 1 || frame.height != 1) frame.setBounds(0, 0, 1, 1)
            if (shown && !frame.isVisible) frame.isVisible = true
            return
        }
        val moved = frame.x != x || frame.y != y || frame.width != w || frame.height != h
        if (moved) {
            frame.setBounds(x, y, w, h)
            // setBounds alone does NOT re-run BorderLayout — the heavyweight Canvas inside
            // keeps its old size and the OS HWND's client rect stays stale, which pins the
            // D3D11 swapchain to the first-attached size (seen as video rendered into a tiny
            // rect of a big black frame). The previous per-site DisposableEffect that
            // dispatched videoUnderlay.attach on view switches implicitly covered this via
            // frame.validate(); the single-attach architecture needs the explicit call here.
            frame.validate()
        }
        val revealed = shown && !frame.isVisible
        if (revealed) frame.isVisible = true
        // Windows: z-order drifts whenever another app foregrounds, so DWM may composite the
        // transparent hole against some other window; re-anchor below the owner. Only when the
        // hole changed (activation re-anchors on its own): off macOS the restack is two toFront
        // calls that flash the video over the UI, and this runs on every ancestor relayout.
        if (moved || revealed) ownerWindow?.let { orderJustBehind(it) }
    }

    /** Sit immediately under [front] so another app cannot stay in the hole. Not key. */
    fun orderJustBehind(front: Window) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { orderJustBehind(front) }
            return
        }
        if (!frame.isDisplayable || !front.isDisplayable) return
        if (!isMac) {
            if (!frame.isVisible) return
            // AWT has no relative restack: raise the pair in order. The frame is unfocusable, so focus stays on front.
            frame.toFront()
            front.toFront()
            return
        }
        try {
            FileAvNative.orderWindowBelow(frame, front)
        } catch (_: Throwable) {
        }
    }

    fun dispose() {
        frame.removeAll()
        frame.dispose()
    }
}

internal val isMac = "mac" in System.getProperty("os.name").orEmpty().lowercase()
