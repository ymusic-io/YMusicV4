package io.ymusic.ui

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** True where the primary pointer is a finger (Android/iOS). Mouse-driven platforms disable
 *  touch idioms like edge-swipe — Compose's mouse drag slop is ~0.1dp, so a whole-screen drag
 *  gesture would steal any click whose press wanders a pixel. */
internal expect val platformUsesTouch: Boolean

/** Desktop Compose UI uses a transparent window with a layout hole over a video-only Frame. */
internal expect val desktopLayoutHole: Boolean

/** The system back gesture/button (Android's BackHandler): while [enabled], [onBack] intercepts
 *  it instead of finishing the app. No-op on platforms without a system back concept. */
@Composable
internal expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Keeps OS chrome legible against the active theme — on Android, flips the status/navigation
 *  bar icon appearance to contrast the edge-to-edge app background. No-op elsewhere. */
@Composable
internal expect fun SyncSystemBars(dark: Boolean)

/** Fullscreen video: hide status/nav bars; a swipe at the bar edge shows them (system). */
@Composable
internal expect fun SyncImmersiveSystemBars(immersive: Boolean)

/** Fullscreen video: follow the phone's rotation, or hold the current orientation while [locked]. */
@Composable
internal expect fun SyncSensorRotation(locked: Boolean)

/** Whether [SyncSensorRotation] can hold an orientation against the sensor and the system lock. */
internal expect val rotationLockSupported: Boolean

/** Holds the display awake (no idle dim/lock) while [enabled]. No-op on desktop. */
@Composable
internal expect fun KeepScreenOn(enabled: Boolean)

/** Status-bar height even while bars are hidden (fullscreen overlay title). No-op on desktop. */
@Composable
internal expect fun Modifier.keepBelowStatusBar(): Modifier

/** Android gesture nav: claims a vertically centred slice of the start-edge back-gesture strip (the
 *  OS-maximum 200dp) so a swipe there opens the nav drawer instead of going back. No-op elsewhere. */
@Composable
internal expect fun Modifier.drawerEdgeGestureExclusion(enabled: Boolean): Modifier

/** The app's navigation drawer. Desktop uses an accessibility-friendly fork (sheet marked
 *  [traversalIndex] -1 so screen-reader hover hit-tests land on nav items before the content
 *  behind the scrim). Touch platforms keep Material3's [androidx.compose.material3.ModalNavigationDrawer]
 *  for its swipe-to-open gesture and predictive back — mouse-less screen readers don't do
 *  point-based hit-tests, so there's nothing to fix there. */
@Composable
internal expect fun AppNavigationDrawer(
    drawerContent: @Composable () -> Unit,
    drawerState: DrawerState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)

/** Whether to skip composing the shell when Now Playing fully covers it. True on desktop (saves
 *  4× overdraw and keeps the shell's semantics tree out of hover hit-tests). False on mobile:
 *  the shell stays composed under NowPlaying for peek-dismiss and faster back-to-shell transition. */
internal expect val cullShellWhenNowPlayingCovers: Boolean
