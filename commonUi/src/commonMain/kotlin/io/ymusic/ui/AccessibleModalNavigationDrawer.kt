package io.ymusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch

/** The app's navigation drawer: Material3's on touch platforms (swipe-to-open, predictive back),
 *  [AccessibleModalNavigationDrawer] with a mouse, where screen readers hit-test by pointer. */
@Composable
internal fun AppNavigationDrawer(
    drawerContent: @Composable () -> Unit,
    drawerState: DrawerState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (platformUsesTouch) {
        ModalNavigationDrawer(drawerContent = drawerContent, modifier = modifier, drawerState = drawerState, content = content)
    } else {
        AccessibleModalNavigationDrawer(drawerContent = drawerContent, drawerState = drawerState, modifier = modifier, content = content)
    }
}

/**
 * A start-anchored modal navigation drawer with screen-reader-friendly semantics.
 *
 * Material3's [androidx.compose.material3.ModalNavigationDrawer] inserts a scrim that owns a
 * "Close navigation menu" click action and whose bounds cover the entire area including the
 * sheet. Point-based a11y hit-test (getAccessibleAt) walks sibling nodes in traversal order and
 * stops at the first match, so hover on a nav item lands on the scrim first and NVDA reads
 * "Close navigation menu" instead of the item. We can't influence Material3's internal sibling
 * ordering from outside, so this is a minimal reimplementation we fully control:
 *
 *  * sheet is marked [traversalIndex] = -1f (visited BEFORE content and scrim by hit-test),
 *  * while open, the content behind is a silent leaf ([clearAndSetSemantics]) and so is the scrim,
 *    so the dimmed area reads nothing and screen readers cannot walk into the hidden page,
 *  * the dismiss click is a plain [pointerInput] on the scrim; it does not add an accessibility
 *    action, so there is no competing hit-test match either,
 *  * closing returns keyboard focus to what held it before opening, else to the first focusable
 *    item behind (the app bar's menu button); an opening with nothing focused leaves focus alone.
 *
 * The caller passes a Material3 [DrawerState] so the rest of the shell (hamburger wiring,
 * keyboard shortcuts, section selection) stays unchanged.
 */
@Composable
internal fun AccessibleModalNavigationDrawer(
    drawerContent: @Composable () -> Unit,
    drawerState: DrawerState,
    modifier: Modifier = Modifier,
    scrimColor: Color = DrawerDefaults.scrimColor,
    content: @Composable () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val open = drawerState.isOpen
    val contentFocus = remember { FocusRequester() }
    val focusBeforeOpen = remember { FocusMemory() }
    LaunchedEffect(open) {
        if (open || !focusBeforeOpen.saved) return@LaunchedEffect
        focusBeforeOpen.saved = false
        if (!contentFocus.restoreFocusedChild()) contentFocus.requestFocus()
    }
    // Slide offsets are absolute, not mirrored: the start-anchored sheet sits on the right in RTL.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val offscreen: (Int) -> Int = { width -> if (rtl) width else -width }
    // isTraversalGroup = true on the parent Box so the sheet's traversalIndex = -1f actually
    // sorts it before content/scrim siblings. Without this, Compose uses composition order and
    // the content underneath wins hit-test first.
    Box(modifier.semantics { isTraversalGroup = true }) {
        Box(
            Modifier.focusRequester(contentFocus)
                .focusGroup()
                .then(if (open) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            content()
        }
        AnimatedVisibility(visible = open, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { scope.launch { drawerState.close() } }
                    }
                    .background(scrimColor)
                    .clearAndSetSemantics {},
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(250), offscreen),
            exit = slideOutHorizontally(tween(250), offscreen),
            modifier = Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
        ) {
            // Composed ahead of drawerContent: saves focus before the sheet pulls it in.
            LaunchedEffect(Unit) { focusBeforeOpen.saved = contentFocus.saveFocusedChild() }
            drawerContent()
        }
    }
}

private class FocusMemory {
    var saved = false
}
