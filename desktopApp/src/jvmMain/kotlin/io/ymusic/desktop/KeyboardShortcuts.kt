package io.ymusic.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.ymusic.player.Player
import io.ymusic.ui.GlobalShortcutActions
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * App-wide hot keys for every desktop OS. The command modifier accepts either Ctrl (Win/Linux) or
 * Meta/Cmd (macOS) so the same binding works everywhere without an OS switch.
 */

private const val SEEK_STEP_MS = 10_000L

private val digitKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private fun digitIndex(key: Key): Int? = digitKeys.indexOf(key).takeIf { it >= 0 }?.plus(1)

internal fun handleGlobalShortcut(
    event: KeyEvent,
    player: Player?,
    nowPlayingOpen: MutableStateFlow<Boolean>?,
    frame: DesktopFrame,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val cmd = event.isMetaPressed || event.isCtrlPressed
    return when {
        event.key == Key.F8 -> { player?.togglePlay(); true }
        event.key == Key.F7 -> {
            player?.let { it.seekTo((it.progress.value.positionMs - SEEK_STEP_MS).coerceAtLeast(0)) }
            true
        }
        event.key == Key.F9 -> {
            player?.let {
                val dur = it.progress.value.durationMs
                val cap = if (dur > 0) dur else Long.MAX_VALUE
                it.seekTo((it.progress.value.positionMs + SEEK_STEP_MS).coerceAtMost(cap))
            }
            true
        }
        event.key == Key.F11 -> { frame.toggleFullscreen(); true }
        event.key == Key.F1 -> { GlobalShortcutActions.toggleShortcutsHelp(); true }
        cmd && event.key == Key.LeftBracket -> { player?.skipPrev(); true }
        cmd && event.key == Key.RightBracket -> { player?.skipNext(); true }
        cmd && event.key == Key.N -> {
            nowPlayingOpen?.let { it.value = !it.value }
            true
        }
        cmd && event.key == Key.M -> { GlobalShortcutActions.toggleNavDrawer(); true }
        // macOS's standard Ctrl+Cmd+F full screen; must precede Cmd+F.
        event.isMetaPressed && event.isCtrlPressed && event.key == Key.F -> { frame.toggleFullscreen(); true }
        cmd && event.key == Key.F -> { GlobalShortcutActions.openSearch(); true }
        cmd && digitIndex(event.key) != null -> {
            GlobalShortcutActions.selectTabByIndex(digitIndex(event.key)!!)
            true
        }
        event.key == Key.Escape -> when {
            // Compose popups/dialogs handle their own Esc first; by the time it reaches
            // the window handler the cascade peels back fullscreen → NowPlaying → nav drawer.
            frame.fullscreen -> { frame.toggleFullscreen(); true }
            nowPlayingOpen?.value == true -> { nowPlayingOpen.value = false; true }
            GlobalShortcutActions.closeNavDrawerIfOpen() -> true
            else -> false
        }
        else -> false
    }
}
