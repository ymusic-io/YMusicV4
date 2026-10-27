package io.ymusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import kotlinx.coroutines.launch

/**
 * A left-anchored modal navigation drawer with screen-reader-friendly semantics.
 *
 * Material3's [androidx.compose.material3.ModalNavigationDrawer] inserts a scrim that owns a
 * "Close navigation menu" click action and whose bounds cover the entire area including the
 * sheet. Point-based a11y hit-test (getAccessibleAt) walks sibling nodes in traversal order and
 * stops at the first match, so hover on a nav item lands on the scrim first and NVDA reads
 * "Close navigation menu" instead of the item. We can't influence Material3's internal sibling
 * ordering from outside, so this is a minimal reimplementation we fully control:
 *
 *  * sheet is marked [traversalIndex] = -1f (visited BEFORE content and scrim by hit-test),
 *  * scrim's semantics are cleared ([clearAndSetSemantics] with no properties), so if the pointer
 *    does land in a scrim-only region the node is a silent leaf and NVDA reads nothing,
 *  * the dismiss click is a plain [pointerInput] on the scrim; it does not add an accessibility
 *    action, so there is no competing hit-test match either.
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
    // isTraversalGroup = true on the parent Box so the sheet's traversalIndex = -1f actually
    // sorts it before content/scrim siblings. Without this, Compose uses composition order and
    // the content underneath wins hit-test first.
    Box(modifier.semantics { isTraversalGroup = true }) {
        content()
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
            enter = slideInHorizontally(tween(250)) { -it },
            exit = slideOutHorizontally(tween(250)) { -it },
            modifier = Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
        ) {
            drawerContent()
        }
    }
}
