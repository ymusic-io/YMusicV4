package io.ymusic.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import io.ymusic.imageloader.AsyncImage
import io.ymusic.player.isPlaying
import io.ymusic.ui.AppGraph
import io.ymusic.ui.SlimSeekBar
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.badge_live
import io.ymusic.ui.resources.cd_next
import io.ymusic.ui.resources.cd_open_app
import io.ymusic.ui.resources.cd_pause
import io.ymusic.ui.resources.cd_play
import io.ymusic.ui.resources.cd_previous
import io.ymusic.ui.resources.cd_stop_dismiss
import io.ymusic.ui.resources.mini_player_nothing_playing
import io.ymusic.ui.skipSegmentFractions
import kmpcommon.log.Logger
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.WindowConstants

/**
 * The tray's notification-style popup: a small always-on-top card near the tray corner with the
 * current media info and transport — art, title/artist, prev / play-pause / next, a seek bar,
 * "open the app", and ✕ = [onDismiss] (end the listening session: clear the queue — which removes
 * the tray — and exit the app when no window is left). Dismisses itself when it loses focus
 * (click-away), like a system notification.
 */
@Composable
fun ApplicationScope.MiniPlayerWindow(
    graph: AppGraph,
    anchor: Point? = null,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val currentOnClose by rememberUpdatedState(onClose)
    Window(
        create = {
            ComposeWindow().apply {
                // No taskbar button on Windows. The type can only be set before the window is shown.
                if (isWindows) type = java.awt.Window.Type.UTILITY
                title = "YMusic"
                isUndecorated = true
                isTransparent = true
                isResizable = false
                isAlwaysOnTop = true
                val position = miniPlayerPosition(anchor)
                setBounds(position.x, position.y, WIDTH_DP, HEIGHT_DP)
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) = currentOnClose()
                })
            }
        },
        dispose = ComposeWindow::dispose,
    ) {
        DisposableEffect(Unit) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) = Unit

                override fun windowLostFocus(e: WindowEvent) = onClose()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            MiniPlayerContent(graph, onOpenApp, onDismiss)
        }
    }
}

/**
 * Beside the tray at [anchor] (else the top-right corner), inside the clicked monitor's work area:
 * against the edge the tray bar occupies — the inset edge nearest the click, or the nearest screen
 * edge when the bar auto-hides — and centered on the click along it.
 */
private fun miniPlayerPosition(anchor: Point?): Point {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val gc = anchor?.let { p -> env.screenDevices.map { it.defaultConfiguration }.firstOrNull { p in it.bounds } }
        ?: env.defaultScreenDevice.defaultConfiguration
    val bounds = gc.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val left = bounds.x + insets.left
    val top = bounds.y + insets.top
    val right = bounds.x + bounds.width - insets.right
    val bottom = bounds.y + bounds.height - insets.bottom
    val p = anchor ?: Point(right, top)
    // (edge, click distance to it, inset it carries)
    val edges = listOf(
        Triple(TrayEdge.Top, p.y - bounds.y, insets.top),
        Triple(TrayEdge.Bottom, bounds.y + bounds.height - p.y, insets.bottom),
        Triple(TrayEdge.Left, p.x - bounds.x, insets.left),
        Triple(TrayEdge.Right, bounds.x + bounds.width - p.x, insets.right),
    )
    val edge = edges.filter { it.third > 0 }.ifEmpty { edges }.minBy { it.second }.first
    fun centered(at: Int, size: Int, from: Int, to: Int) =
        (at - size / 2).coerceIn(from + MARGIN_DP, maxOf(from + MARGIN_DP, to - size - MARGIN_DP))
    val (x, y) = when (edge) {
        TrayEdge.Top -> centered(p.x, WIDTH_DP, left, right) to top + MARGIN_DP
        TrayEdge.Bottom -> centered(p.x, WIDTH_DP, left, right) to bottom - HEIGHT_DP - MARGIN_DP
        TrayEdge.Left -> left + MARGIN_DP to centered(p.y, HEIGHT_DP, top, bottom)
        TrayEdge.Right -> right - WIDTH_DP - MARGIN_DP to centered(p.y, HEIGHT_DP, top, bottom)
    }
    return Point(x, y)
}

private enum class TrayEdge { Top, Bottom, Left, Right }

@Composable
private fun MiniPlayerContent(graph: AppGraph, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
    val player = graph.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val skipSegments by player.skipSegments.collectAsState()
    val item = queue.currentItem
    val playing = status.state.isPlaying()

    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                val loader = graph.imageLoader
                val art = item?.displayThumbnailUrl
                if (loader != null && art != null) {
                    AsyncImage(
                        loader,
                        art,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    item?.displayTitle ?: stringResource(Res.string.mini_player_nothing_playing),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item?.displaySubtitle?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenApp, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(Res.string.cd_open_app), Modifier.size(16.dp))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Close, stringResource(Res.string.cd_stop_dismiss), Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.height(6.dp))

        val durationMs = progress.durationMs
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.progressLabel.orEmpty(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SlimSeekBar(
                fraction = if (durationMs > 0) (progress.positionMs.toFloat() / durationMs) else 0f,
                enabled = progress.seekable && durationMs > 0,
                onSeek = { f -> player.seekTo((f * durationMs).toLong()) },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                skipFractions = skipSegmentFractions(skipSegments, durationMs),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                fillColor = MaterialTheme.colorScheme.primary,
                thumbColor = MaterialTheme.colorScheme.primary,
                thumbSize = 10.dp,
                barHeight = 22.dp,
                thumbShadow = 0.dp,
            )
            Text(
                if (progress.durationIsLive) {
                    stringResource(Res.string.badge_live)
                } else {
                    progress.durationLabel ?: "--:--"
                },
                fontSize = 10.sp,
                color = if (progress.highlightDuration) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = if (progress.durationIsLive && progress.seekable) {
                    Modifier.clickable { player.seekTo(durationMs.coerceAtLeast(0L)) }
                } else {
                    Modifier
                },
            )
        }

        Spacer(Modifier.height(2.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { player.skipPrev() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipPrevious, stringResource(Res.string.cd_previous), Modifier.size(20.dp))
            }
            IconButton(onClick = { player.togglePlay() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playing) Res.string.cd_pause else Res.string.cd_play),
                    Modifier.size(26.dp),
                )
            }
            IconButton(onClick = { player.skipNext() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipNext, stringResource(Res.string.cd_next), Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The tray icon, managed straight through AWT — NOT the compose `Tray`, which always attaches a
 * popup menu that macOS then opens on click instead of firing the action. Bare TrayIcon + mouse
 * listener = a plain click reaches [onClick] (with the click's screen point, to anchor the mini
 * player at the icon).
 *
 * The icon is a multi-resolution image of plain BufferedImages, one per display scale: a single
 * 1x raster is upscaled by the OS on HiDPI and turns soft, and Windows' tray peer draws Compose's
 * lazy `toAwtImage` PainterImage variants as nothing at all.
 */
@Composable
fun PlayerTray(icon: Painter, logger: Logger, menu: PopupMenu?, onClick: (screen: Point) -> Unit) {
    val currentOnClick by rememberUpdatedState(onClick)
    DisposableEffect(icon, menu) {
        val tray = if (SystemTray.isSupported()) SystemTray.getSystemTray() else null
        val trayIcon = tray?.let {
            val px = it.trayIconSize.width
            val image = if (isLinux) {
                linuxTrayImage(icon, px)
            } else {
                java.awt.image.BaseMultiResolutionImage(
                    *TRAY_SCALES.map { s -> icon.rasterize(kotlin.math.ceil(px * s).toInt()) }.toTypedArray(),
                )
            }
            TrayIcon(image, "YMusic").apply {
                addMouseListener(
                    object : MouseAdapter() {
                        // TrayIcon events carry SCREEN coordinates (there is no component space).
                        override fun mousePressed(e: MouseEvent) {
                            if (e.button == MouseEvent.BUTTON1) currentOnClick(e.point)
                        }

                        override fun mouseReleased(e: MouseEvent) {
                            if (!isLinux) return
                            runCatching { clearAwtPointerAutoGrab() }
                                .onFailure { logger.log(Logger.Level.WARN, "tray pointer auto-grab clear failed", it) }
                        }
                    },
                )
                popupMenu = menu
                it.add(this)
            }
        }
        val embed = if (isLinux && trayIcon != null) linuxTrayEmbedFrame() else null
        val fit = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                val px = minOf(e.component.width, e.component.height)
                if (px > 0) trayIcon?.image = linuxTrayImage(icon, px)
            }
        }
        embed?.let { frame ->
            frame.addComponentListener(fit)
            fit.componentResized(java.awt.event.ComponentEvent(frame, java.awt.event.ComponentEvent.COMPONENT_RESIZED))
        }
        onDispose {
            embed?.removeComponentListener(fit)
            trayIcon?.let { t -> tray?.remove(t) }
        }
    }
}

/** X11's tray peer paints transparent pixels in its own (white) background: the disc covers the cell. */
private fun linuxTrayImage(icon: Painter, px: Int) = icon.rasterize(px, scale = kotlin.math.sqrt(2f))

private val TRAY_SCALES = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private const val WIDTH_DP = 340
private const val HEIGHT_DP = 132
private const val MARGIN_DP = 8
