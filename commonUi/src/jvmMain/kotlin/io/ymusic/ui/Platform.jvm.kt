package io.ymusic.ui

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

internal actual val platformUsesTouch: Boolean = false

internal actual val desktopLayoutHole: Boolean = true

internal actual val cullShellWhenNowPlayingCovers: Boolean = true

internal actual fun Modifier.a11yActionLabel(label: String?): Modifier =
    if (label.isNullOrEmpty()) this
    else this.semantics { text = AnnotatedString(label) }

@Composable
internal actual fun AppNavigationDrawer(
    drawerContent: @Composable () -> Unit,
    drawerState: DrawerState,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    AccessibleModalNavigationDrawer(
        drawerContent = drawerContent,
        drawerState = drawerState,
        modifier = modifier,
        content = content,
    )
}

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back concept on desktop; navigation uses the in-app back arrows.
}

@Composable
internal actual fun SyncSystemBars(dark: Boolean) {
    // Desktop windows carry no app-tinted OS chrome.
}

@Composable
internal actual fun SyncSensorRotation(locked: Boolean) = Unit

internal actual val rotationLockSupported: Boolean = false

@Composable
internal actual fun SyncImmersiveSystemBars(immersive: Boolean) = Unit

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) = Unit

@Composable
internal actual fun Modifier.keepBelowStatusBar(): Modifier = this

@Composable
internal actual fun Modifier.drawerEdgeGestureExclusion(enabled: Boolean): Modifier = this
