package io.ymusic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

internal enum class ListOrientation { Vertical, Horizontal, Both }

/** Arrows held with Ctrl/Cmd/Alt are app shortcuts, not navigation or adjustment. */
internal val KeyEvent.hasCommandModifier: Boolean get() = isCtrlPressed || isMetaPressed || isAltPressed

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
 *
 * With [items] it is a list of items ([itemList]) as well.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.listKeyboardNavigation(
    orientation: ListOrientation = ListOrientation.Vertical,
    items: Boolean = false,
): Modifier {
    val focusManager = LocalFocusManager.current
    return (if (items) itemList() else focusGroup()).onKeyEvent { event -> arrowMove(event, orientation, focusManager) }
}

private fun arrowMove(event: KeyEvent, orientation: ListOrientation, focusManager: FocusManager): Boolean {
    if (event.type != KeyEventType.KeyDown || event.hasCommandModifier) return false
    val horizontal = orientation == ListOrientation.Horizontal || orientation == ListOrientation.Both
    val vertical = orientation == ListOrientation.Vertical || orientation == ListOrientation.Both
    val direction = when (event.key) {
        Key.DirectionDown -> if (vertical) FocusDirection.Down else return false
        Key.DirectionUp -> if (vertical) FocusDirection.Up else return false
        Key.DirectionRight -> if (horizontal) FocusDirection.Right else return false
        Key.DirectionLeft -> if (horizontal) FocusDirection.Left else return false
        else -> return false
    }
    return focusManager.moveFocus(direction)
}

/**
 * A lazy list of items for keyboard users: Tab moves item to item (an item's ⋮ is not focusable, see
 * [MoreMenu]) and leaves past the last; focus coming back in, by Tab or Shift+Tab, lands on the item
 * focused last; Shift+F10 or the context-menu key opens the focused item's ⋮. Only on a lazy list or
 * grid: [focusRestorer] restores into the list's own focus group, whose children are the items.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.itemList(): Modifier {
    val focused = remember { FocusedItem() }
    return onFocusedBoundsChanged { focused.coordinates = it }
        .focusProperties {
            val restore = onEnter
            // focusRestorer also redirects a requestFocus aimed at another item; restore only for Tab.
            onEnter = {
                if (requestedFocusDirection == FocusDirection.Next || requestedFocusDirection == FocusDirection.Previous) {
                    restore()
                }
            }
        }
        .focusRestorer()
        .onKeyEvent { event ->
            event.type == KeyEventType.KeyDown && !event.hasCommandModifier && event.isMenuKey &&
                focused.coordinates?.let(ItemMenus::openInside) == true
        }
}

private class FocusedItem {
    var coordinates: LayoutCoordinates? = null
}

private val KeyEvent.isMenuKey: Boolean
    get() = (key == Key.F10 && isShiftPressed) || (key == contextMenuKey && !isShiftPressed)

/** The item ⋮ menus on screen, so the context-menu key on a focused item opens the one inside it. */
internal object ItemMenus {
    class Anchor(val open: () -> Unit) {
        var coordinates: LayoutCoordinates? = null
    }

    private val anchors = mutableListOf<Anchor>()

    fun add(anchor: Anchor) {
        anchors += anchor
    }

    fun remove(anchor: Anchor) {
        anchors -= anchor
    }

    fun openInside(item: LayoutCoordinates): Boolean {
        if (!item.isAttached) return false
        val root = item.findRootCoordinates()
        val bounds = root.localBoundingBoxOf(item, clipBounds = false)
        val anchor = anchors.firstOrNull { anchor ->
            val coordinates = anchor.coordinates?.takeIf { it.isAttached && it.findRootCoordinates() === root }
            coordinates != null && bounds.contains(root.localBoundingBoxOf(coordinates, clipBounds = false).center)
        } ?: return false
        anchor.open()
        return true
    }
}

/** Registers this ⋮ with [ItemMenus], opened by [open] when its item holds focus. */
@Composable
internal fun Modifier.itemMenuAnchor(open: () -> Unit): Modifier {
    val currentOpen by rememberUpdatedState(open)
    val anchor = remember { ItemMenus.Anchor { currentOpen() } }
    DisposableEffect(anchor) {
        ItemMenus.add(anchor)
        onDispose { ItemMenus.remove(anchor) }
    }
    return onGloballyPositioned { anchor.coordinates = it }
}

/**
 * TV remote keys on a text field. Compose's own D-pad escape skips remotes whose key events report
 * only `SOURCE_KEYBOARD` (common on cheap boxes), leaving ↑/↓ as cursor moves and OK as a no-op —
 * focus could never leave the field and the IME could never be reopened.
 */
@Composable
internal fun Modifier.tvTextFieldDpad(singleLine: Boolean): Modifier {
    if (!platformIsTelevision) return this
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.hasCommandModifier) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp -> singleLine && focusManager.moveFocus(FocusDirection.Up)
            Key.DirectionDown -> singleLine && focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionCenter -> {
                keyboard?.show()
                keyboard != null
            }
            else -> false
        }
    }
}
