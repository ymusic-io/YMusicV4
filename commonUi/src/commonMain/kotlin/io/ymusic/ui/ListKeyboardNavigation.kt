package io.ymusic.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

internal enum class ListOrientation { Vertical, Horizontal, Both }

/**
 * Arrow-key navigation for keyboard users: the arrow keys matching the list's [orientation] move
 * focus between items via Compose's geometric search. The orthogonal arrows (e.g. ↓ from a
 * horizontal tab row) are NOT intercepted, so pressing Down on a tab lets Compose jump focus
 * from the tab row into the first focusable row of the list beneath it. Tab still stops at every
 * item: [focusGroup] only keeps the list's items together in the Tab order.
 *
 *   * [ListOrientation.Vertical]   → ↑/↓ (default; LazyColumn, menus, side navs)
 *   * [ListOrientation.Horizontal] → ←/→ (LazyRow, tab bars)
 *   * [ListOrientation.Both]       → all four (grids)
 *
 * Uses [onKeyEvent] rather than `onPreviewKeyEvent` so a focused child (e.g. a TextField or a
 * nested list) gets first dibs on the arrow key — the list only steals it when nothing inside
 * cares. Items inside become focus targets automatically via their own [Modifier.clickable].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
@ReadOnlyComposable
internal fun Modifier.listKeyboardNavigation(
    orientation: ListOrientation = ListOrientation.Vertical,
): Modifier {
    val focusManager = LocalFocusManager.current
    val horizontal = orientation == ListOrientation.Horizontal || orientation == ListOrientation.Both
    val vertical = orientation == ListOrientation.Vertical || orientation == ListOrientation.Both
    return focusGroup().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val dir = when (event.key) {
            Key.DirectionDown -> if (vertical) FocusDirection.Down else return@onKeyEvent false
            Key.DirectionUp -> if (vertical) FocusDirection.Up else return@onKeyEvent false
            Key.DirectionRight -> if (horizontal) FocusDirection.Right else return@onKeyEvent false
            Key.DirectionLeft -> if (horizontal) FocusDirection.Left else return@onKeyEvent false
            else -> return@onKeyEvent false
        }
        focusManager.moveFocus(dir)
    }
}
