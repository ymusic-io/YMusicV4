package io.ymusic.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal actual val platformUsesTouch: Boolean get() = !AndroidInputMode.television

internal actual val platformIsTelevision: Boolean get() = AndroidInputMode.television

/** How the device is driven, recorded by the app shell before any UI composes. */
object AndroidInputMode {
    /** A TV is driven by a D-pad, whatever touchscreen it reports: the emulator image claims one. */
    @Volatile
    internal var television = false
        private set

    val isTelevision: Boolean get() = television

    fun init(context: Context) {
        val uiMode = context.getSystemService(UiModeManager::class.java)
        television = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

internal actual val desktopLayoutHole: Boolean = false

actual fun Modifier.a11yActionLabel(label: String?): Modifier =
    if (label.isNullOrEmpty()) this
    else this.semantics { contentDescription = label }

internal actual fun Modifier.a11ySlider(
    label: String?,
    value: String,
    fraction: Float,
    enabled: Boolean,
    onSet: (Float) -> Unit,
): Modifier = rangeSliderSemantics(label, value, fraction, enabled, onSet)

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled, onBack)
}

@Composable
internal actual fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        enabled = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable
internal actual fun SyncSystemBars(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.SideEffect {
        val window = view.activityWindow() ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }
}

@Composable
internal actual fun SyncSensorRotation(locked: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = remember(view) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) {
            ctx = ctx.baseContext
        }
        ctx as? Activity
    }
    // Toggling must not pass through UNSPECIFIED, or LOCKED could capture the system's orientation.
    SideEffect {
        activity?.requestedOrientation =
            if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

internal actual val rotationLockSupported: Boolean get() = !AndroidInputMode.television

@Composable
internal actual fun SyncImmersiveSystemBars(immersive: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(immersive) {
        val window = view.activityWindow()
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (immersive) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun Modifier.keepBelowStatusBar(): Modifier =
    windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)

// The system honours at most this much exclusion height per edge (config_systemGestureExclusionLimitDp).
private val SystemGestureExclusionLimit = 200.dp

@Composable
internal actual fun Modifier.drawerEdgeGestureExclusion(enabled: Boolean): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val rtl = layoutDirection == LayoutDirection.Rtl
    val stripWidth = (if (rtl) gestures.getRight(density, layoutDirection) else gestures.getLeft(density, layoutDirection)).toFloat()
    if (stripWidth <= 0f) return this
    val limit = with(density) { SystemGestureExclusionLimit.toPx() }
    return systemGestureExclusion { coordinates ->
        val width = coordinates.size.width.toFloat()
        val height = coordinates.size.height.toFloat()
        val sliceHeight = minOf(limit, height)
        val top = (height - sliceHeight) / 2
        if (rtl) {
            Rect(width - stripWidth, top, width, top + sliceHeight)
        } else {
            Rect(0f, top, stripWidth, top + sliceHeight)
        }
    }
}

private fun android.view.View.activityWindow(): android.view.Window? {
    var context = context
    while (context is ContextWrapper && context !is Activity) {
        context = context.baseContext
    }
    return (context as? Activity)?.window
}
