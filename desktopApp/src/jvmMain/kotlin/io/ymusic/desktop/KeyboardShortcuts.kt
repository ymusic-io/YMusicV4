package io.ymusic.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.ymusic.player.Player
import io.ymusic.ui.GlobalShortcutActions
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.Toolkit
import kotlin.math.roundToInt

private const val SEEK_STEP_MS = 10_000L

/** The volume slider's keyboard step. */
private const val VOLUME_STEP = 0.05f

private val digitKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private fun digitIndex(key: Key): Int? = digitKeys.indexOf(key).takeIf { it >= 0 }?.plus(1)

/**
 * App-wide hot keys for every desktop OS. The command modifier accepts either Ctrl (Win/Linux) or
 * Meta/Cmd (macOS) so the same binding works everywhere without an OS switch.
 */
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
        event.key == Key.F7 -> { player?.let { seekBy(it, -SEEK_STEP_MS) }; true }
        event.key == Key.F9 -> { player?.let { seekBy(it, SEEK_STEP_MS) }; true }
        event.key == Key.F11 -> { frame.toggleFullscreen(); true }
        event.key == Key.F1 -> { GlobalShortcutActions.toggleShortcutsHelp(); true }
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
        else -> false
    }
}

/**
 * Cmd/Ctrl + arrows: ←/→ previous/next, with Shift rewind/forward, Shift + ↑/↓ volume. For the window's
 * onKeyEvent, not [handleGlobalShortcut]: a focused text field gets them first for word moves and
 * selection.
 */
internal fun handleArrowShortcut(event: KeyEvent, player: Player?): Boolean {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed || !(event.isMetaPressed || event.isCtrlPressed)) return false
    val shift = event.isShiftPressed
    when {
        event.key == Key.DirectionLeft -> if (shift) player?.let { seekBy(it, -SEEK_STEP_MS) } else player?.skipPrev()
        event.key == Key.DirectionRight -> if (shift) player?.let { seekBy(it, SEEK_STEP_MS) } else player?.skipNext()
        shift && event.key == Key.DirectionUp -> player?.let { stepVolume(it, 1) }
        shift && event.key == Key.DirectionDown -> player?.let { stepVolume(it, -1) }
        else -> return false
    }
    return true
}

private fun seekBy(player: Player, deltaMs: Long) {
    val dur = player.progress.value.durationMs
    val cap = if (dur > 0) dur else Long.MAX_VALUE
    player.seekTo((player.progress.value.positionMs + deltaMs).coerceIn(0, cap))
}

/** Snapped to the step so repeated presses land on whole percents. */
private fun stepVolume(player: Player, steps: Int) {
    val snapped = ((player.volume / VOLUME_STEP).roundToInt() + steps) * VOLUME_STEP
    player.volume = snapped.coerceIn(0f, 1f)
}

/**
 * Esc peels back full screen → Now Playing → nav drawer; with none of those open, a double Esc is the
 * system back (search → home). A back handler rather than a key handler: the scene turns an
 * unconsumed Esc into a back event for the newest handler first, so an open popup or dialog closes
 * before this cascade runs.
 */
@Composable
internal fun EscapeCascade(nowPlayingOpen: MutableStateFlow<Boolean>?, frame: DesktopFrame) {
    val idleEscAt = remember { longArrayOf(0L) }
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None)) {
        when {
            frame.fullscreen -> frame.toggleFullscreen()
            nowPlayingOpen?.value == true -> nowPlayingOpen.value = false
            GlobalShortcutActions.closeNavDrawerIfOpen() -> Unit
            else -> {
                val now = System.nanoTime()
                if (idleEscAt[0] != 0L && now - idleEscAt[0] <= doubleEscWindowNs()) {
                    idleEscAt[0] = 0L
                    GlobalShortcutActions.back()
                } else {
                    idleEscAt[0] = now
                }
            }
        }
    }
}

/** The OS double-click interval, which users who need more time lengthen. */
private fun doubleEscWindowNs(): Long {
    val ms = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500
    return ms.coerceIn(200, 2000) * 1_000_000L
}
