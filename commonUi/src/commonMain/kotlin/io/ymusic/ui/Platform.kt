package io.ymusic.ui

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

/** Attach [label] as the screen-reader-announced label on an icon-only button, worked around for
 *  Compose Desktop's a11y bridge: it maps `contentDescription` to BOTH JAB `name` and
 *  `description`, so NVDA/JAWS read it twice ("Download Download"). On JVM we set the `text`
 *  semantic instead — `name` falls back to it, `description` stays null, reads once. On
 *  Android/iOS the native assistive techs don't have this double-read, so contentDescription
 *  is still the right channel. [label] may be null for a decorative glyph. Never on a node whose
 *  merged children already show the label as Text: desktop joins the texts and reads it twice. */
expect fun Modifier.a11yActionLabel(label: String?): Modifier

/** A slider as screen readers see it: [label] names it, [value] says in words where it stands, and the
 *  reader's adjust gesture sets a new [fraction] (0..1) through [onSet]. Compose Desktop's bridge speaks
 *  no stateDescription and reads a range only as a bare number, so on JVM both go into the name. */
internal expect fun Modifier.a11ySlider(
    label: String?,
    value: String,
    fraction: Float,
    enabled: Boolean,
    onSet: (Float) -> Unit,
): Modifier
