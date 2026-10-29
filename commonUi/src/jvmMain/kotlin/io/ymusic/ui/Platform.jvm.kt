package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.awtRole
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import javax.accessibility.AccessibleRole

internal actual val platformUsesTouch: Boolean = false

internal actual val desktopLayoutHole: Boolean = true

actual fun Modifier.a11yActionLabel(label: String?): Modifier =
    if (label.isNullOrEmpty()) this
    else this.semantics { text = AnnotatedString(label) }

// No progressBarRangeInfo: the bridge fires a value change, spoken as a bare number, on every tick.
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.a11ySlider(
    label: String?,
    value: String,
    fraction: Float,
    enabled: Boolean,
    onSet: (Float) -> Unit,
): Modifier = semantics {
    text = AnnotatedString(listOfNotNull(label, value).joinToString(", "))
    awtRole = AccessibleRole.SLIDER
    if (!enabled) disabled()
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
