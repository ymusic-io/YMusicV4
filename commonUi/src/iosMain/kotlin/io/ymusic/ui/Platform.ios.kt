package io.ymusic.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import platform.UIKit.UIApplication

internal actual val platformUsesTouch: Boolean = true

internal actual val desktopLayoutHole: Boolean = false

internal actual fun Modifier.a11yActionLabel(label: String?): Modifier =
    if (label.isNullOrEmpty()) this
    else this.semantics { contentDescription = label }

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; the edge-swipe gesture can be wired here later.
}

@Composable
internal actual fun SyncSystemBars(dark: Boolean) {
    // The status bar rides the hosting UIViewController; Compose already draws edge-to-edge
    // under it, and its text style can be wired through MainViewController when needed.
}

@Composable
internal actual fun SyncSensorRotation(locked: Boolean) {
    // Info.plist already lists portrait and both landscapes.
}

internal actual val rotationLockSupported: Boolean = false

@Composable
internal actual fun SyncImmersiveSystemBars(immersive: Boolean) {
    // UIApplication.setStatusBarHidden is not in the iOS 26 Kotlin bindings.
}

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

@Composable
internal actual fun Modifier.keepBelowStatusBar(): Modifier =
    windowInsetsPadding(WindowInsets.statusBars)

@Composable
internal actual fun Modifier.drawerEdgeGestureExclusion(enabled: Boolean): Modifier = this
