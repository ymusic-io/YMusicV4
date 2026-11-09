package io.ymusic.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.FrameWindowScope
import org.jetbrains.skiko.transparentWindowBackgroundHack

/**
 * Keep the AWT window's own background in step with the app's ground.
 *
 * The frame and its content pane are painted by Swing, not by Compose, and show through wherever
 * Compose has not drawn yet — during a resize, on an expose, and while a live theme change settles.
 * Both are set, per JetBrains/compose-multiplatform#1794.
 *
 * This does NOT remove the flash when a window first opens. That comes from below AWT: Compose's
 * Skia layer is a non-opaque component, so Swing never fills it and the native surface shows until
 * Skia presents its first frame. Established by testing plain Swing windows — no flash, with or
 * without a heavyweight canvas — against a Compose window, which flashes; and the flash survives
 * `-Dskiko.renderApi=SOFTWARE`, so the GPU path is not responsible either.
 */
@Composable
fun FrameWindowScope.paintWindowGround(color: Color) {
    val awt = remember(color) { java.awt.Color(color.toArgb(), true) }
    SideEffect {
        if (window.background != awt) {
            window.background = awt
            window.contentPane.background = awt
        }
    }
}

/**
 * Windows: a transparent window's background must suit skiko's live renderer. Only Direct3D draws
 * into a per-pixel-alpha (layered) window; OpenGL and software show nothing in one and make the
 * window see-through themselves. Compose picks it once, before skiko falls back off Direct3D.
 */
fun ComposeWindow.followRendererTransparency(): () -> Unit {
    var active = true
    val apply = { if (active) background = transparentWindowBackgroundHack(renderApi) }
    onRenderApiChanged(apply)
    apply()
    return { active = false }
}

/** [followRendererTransparency] for the composed window's lifetime. */
@Composable
fun FrameWindowScope.followRendererTransparency() {
    DisposableEffect(window) {
        val stop = window.followRendererTransparency()
        onDispose(stop)
    }
}

/** The ground a window should wear, for a given appearance. */
fun groundColorFor(dark: Boolean): Color =
    if (dark) io.ymusic.ui.DarkDesignColors.Surface else io.ymusic.ui.LightDesignColors.SurfaceElevated
