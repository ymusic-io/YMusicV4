package io.ymusic.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import io.ymusic.ui.DefaultAccent
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.tray_pause
import io.ymusic.ui.resources.tray_quit
import io.ymusic.ui.resources.tray_resume
import io.ymusic.ui.resources.tray_stop
import kmpcommon.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar
import java.io.File
import java.io.IOException

/**
 * Pause/Resume, Stop (clear the queue) and, when [withQuit], Quit: the tray icon's right-click
 * menu, or the macOS Dock menu (which carries its own Quit).
 */
@Composable
internal fun rememberPlayerMenu(
    playing: Boolean,
    hasQueue: Boolean,
    withQuit: Boolean,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onQuit: () -> Unit,
): PopupMenu {
    val toggleLabel = stringResource(if (playing) Res.string.tray_pause else Res.string.tray_resume)
    val stopLabel = stringResource(Res.string.tray_stop)
    val quitLabel = stringResource(Res.string.tray_quit)
    val currentToggle by rememberUpdatedState(onTogglePlay)
    val currentStop by rememberUpdatedState(onStop)
    val currentQuit by rememberUpdatedState(onQuit)
    val toggle = remember { MenuItem().apply { addActionListener { currentToggle() } } }
    val stop = remember { MenuItem().apply { addActionListener { currentStop() } } }
    val quit = remember { MenuItem().apply { addActionListener { currentQuit() } } }
    val menu = remember(withQuit) {
        PopupMenu().apply {
            add(toggle)
            add(stop)
            if (withQuit) {
                addSeparator()
                add(quit)
            }
        }
    }
    SideEffect {
        toggle.label = toggleLabel
        toggle.isEnabled = hasQueue
        stop.label = stopLabel
        stop.isEnabled = hasQueue
        quit.label = quitLabel
    }
    return menu
}

/** The Dock icon's menu. AWT supports [Taskbar.Feature.MENU] on macOS only. */
@Composable
internal fun DockMenu(menu: PopupMenu) {
    DisposableEffect(menu) {
        val taskbar = if (Taskbar.isTaskbarSupported()) {
            Taskbar.getTaskbar().takeIf { it.isSupported(Taskbar.Feature.MENU) }
        } else {
            null
        }
        taskbar?.menu = menu
        onDispose { taskbar?.menu = null }
    }
}

/** The same actions as Windows taskbar Jump List tasks, each relaunching [exe] with its command. */
@Composable
internal fun JumpListTasks(exe: String, playing: Boolean, hasQueue: Boolean, logger: Logger) {
    val toggleLabel = stringResource(if (playing) Res.string.tray_pause else Res.string.tray_resume)
    val stopLabel = stringResource(Res.string.tray_stop)
    val quitLabel = stringResource(Res.string.tray_quit)
    val glyphs = mapOf(
        "toggle-true" to rememberVectorPainter(Icons.Default.Pause),
        "toggle-false" to rememberVectorPainter(Icons.Default.PlayArrow),
        "stop" to rememberVectorPainter(Icons.Default.Stop),
        "quit" to rememberVectorPainter(Icons.AutoMirrored.Filled.ExitToApp),
    )
    var icons by remember { mutableStateOf<Map<String, File>?>(null) }
    LaunchedEffect(Unit) {
        // Drawn here, on the composition thread: a VectorPainter reads snapshot state.
        val images = glyphs.mapValues { (_, glyph) -> JUMP_LIST_ICON_SIZES.map { glyph.rasterize(it, tint = DefaultAccent) } }
        icons = withContext(Dispatchers.IO) {
            val dir = File(ymusicDataDir(), "jumplist")
            images.mapNotNull { (name, sizes) ->
                val file = File(dir, "$name.ico")
                try {
                    WindowsJumpList.writeIcon(file, sizes)
                    name to file
                } catch (e: IOException) {
                    logger.log(Logger.Level.WARN, "jump list icon not written, using the exe's", e)
                    null
                }
            }.toMap()
        }
    }
    val files = icons ?: return
    LaunchedEffect(exe, playing, toggleLabel, stopLabel, quitLabel, hasQueue, files) {
        withContext(WindowsJumpList.dispatcher) {
            runCatching {
                val tasks = buildList {
                    if (hasQueue) {
                        add(WindowsJumpList.Task(toggleLabel, InstanceCommand.TogglePlay, files["toggle-$playing"]))
                        add(WindowsJumpList.Task(stopLabel, InstanceCommand.Stop, files["stop"]))
                    }
                    add(WindowsJumpList.Task(quitLabel, InstanceCommand.Quit, files["quit"]))
                }
                WindowsJumpList.set(exe, tasks)
            }.onFailure { logger.log(Logger.Level.WARN, "jump list update failed", it) }
        }
    }
}

// Windows menu icon slots across 100–200% DPI.
private val JUMP_LIST_ICON_SIZES = listOf(16, 20, 24, 32, 40, 48)
