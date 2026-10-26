package io.ymusic.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.tray_exit
import io.ymusic.ui.resources.tray_pause
import io.ymusic.ui.resources.tray_resume
import io.ymusic.ui.resources.tray_stop
import org.jetbrains.compose.resources.stringResource
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar

/**
 * Pause/Resume, Stop (clear the queue) and, when [withExit], Exit: the tray icon's right-click
 * menu, or the macOS Dock menu (which carries its own Quit).
 */
@Composable
internal fun rememberPlayerMenu(
    playing: Boolean,
    hasQueue: Boolean,
    withExit: Boolean,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onExit: () -> Unit,
): PopupMenu {
    val toggleLabel = stringResource(if (playing) Res.string.tray_pause else Res.string.tray_resume)
    val stopLabel = stringResource(Res.string.tray_stop)
    val exitLabel = stringResource(Res.string.tray_exit)
    val currentToggle by rememberUpdatedState(onTogglePlay)
    val currentStop by rememberUpdatedState(onStop)
    val currentExit by rememberUpdatedState(onExit)
    val toggle = remember { MenuItem().apply { addActionListener { currentToggle() } } }
    val stop = remember { MenuItem().apply { addActionListener { currentStop() } } }
    val exit = remember { MenuItem().apply { addActionListener { currentExit() } } }
    val menu = remember(withExit) {
        PopupMenu().apply {
            add(toggle)
            add(stop)
            if (withExit) {
                addSeparator()
                add(exit)
            }
        }
    }
    SideEffect {
        toggle.label = toggleLabel
        toggle.isEnabled = hasQueue
        stop.label = stopLabel
        stop.isEnabled = hasQueue
        exit.label = exitLabel
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
