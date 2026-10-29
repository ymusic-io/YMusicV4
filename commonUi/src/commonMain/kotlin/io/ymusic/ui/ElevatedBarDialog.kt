package io.ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

/**
 * The app's standard picker/confirm dialog chrome (first used by the content-region picker): a
 * full-bleed [body] on [DesignColors.Surface], with the title and the button bar sitting on
 * [DesignColors.SurfaceElevated] behind a divider and casting a shadow onto the content when it can
 * scroll under them — the ElevatedBarColumn idiom. The body is bounded to a scrollable region; pass the
 * scroll edges of whatever scrollable it hosts ([androidx.compose.foundation.lazy.LazyListState] or a
 * [androidx.compose.foundation.ScrollState]) as [canScrollBackward]/[canScrollForward] so the shadows
 * follow it. [buttons] fills the bottom bar (right-aligned). [maxWidth] widens it past the 560dp
 * picker width for a dialog with more to lay out (the download options). [titleAction] sits at the
 * title bar's end (the equalizer's preset menu). [dismissOnClickOutside] false keeps it open on a
 * stray tap outside (a dialog holding a draft). [keyboardNav] makes the title the first focus stop,
 * focused on open so a screen reader announces the dialog by name, and lets Up/Down walk the focus
 * stops that don't use those keys themselves.
 */
@Composable
fun ElevatedBarDialog(
    title: String,
    onDismiss: () -> Unit,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    buttons: @Composable RowScope.() -> Unit,
    maxWidth: Dp = 560.dp,
    titleAction: (@Composable () -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    keyboardNav: Boolean = false,
    body: @Composable () -> Unit,
) {
    // The title/button bars sit a step above the body. On dark, the full SurfaceElevated lift (0x12 →
    // 0x1F) reads too strongly against the near-black body, so use the gentler SurfaceDim step there
    // (the divider + scroll shadow still delineate the bars); light keeps the usual elevated grey.
    val barColor = if (DesignColors.isDark) DesignColors.SurfaceDim else DesignColors.SurfaceElevated
    val wide = maxWidth > 560.dp
    // Dialog, not BasicAlertDialog: that one clamps its content to 560dp whatever the properties.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !wide, dismissOnClickOutside = dismissOnClickOutside)) {
        // Measured inside the Dialog: its constraints are the window. Outside, a caller in a scrolling
        // column has no height bound at all.
        BoxWithConstraints {
            val windowHeight = this.maxHeight
            val bodyMax = if (windowHeight.value.isFinite() && windowHeight > 0.dp) {
                (windowHeight * 0.85f - 112.dp).coerceAtLeast(240.dp)
            } else {
                420.dp
            }
            // Read inside the Dialog: it has its own focus owner.
            val focusManager = LocalFocusManager.current
            val titleFocus = remember { FocusRequester() }
            val titleInteraction = remember { MutableInteractionSource() }
            if (keyboardNav) LaunchedEffect(Unit) { titleFocus.requestFocus() }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = DesignColors.Surface,
                modifier = Modifier
                    .then(if (wide) Modifier.padding(horizontal = 24.dp) else Modifier)
                    .widthIn(min = 280.dp, max = maxWidth)
                    .then(if (keyboardNav) Modifier.onKeyEvent { arrowMovesFocus(it, focusManager) } else Modifier),
            ) {
                Column {
                    val topShadow by animateDpAsState(if (canScrollBackward) 6.dp else 0.dp)
                    Column(Modifier.fillMaxWidth().zIndex(1f).shadow(topShadow).background(barColor)) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = if (titleAction != null) 16.dp else 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                title,
                                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (keyboardNav) {
                                            Modifier
                                                .focusRequester(titleFocus)
                                                .focusable(interactionSource = titleInteraction)
                                                .indication(titleInteraction, LocalIndication.current)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(vertical = 16.dp),
                            )
                            titleAction?.invoke()
                        }
                        HorizontalDivider(color = DesignColors.Divider)
                    }
                    Box(Modifier.weight(1f, fill = false).heightIn(max = bodyMax)) { body() }
                    val bottomShadow by animateDpAsState(if (canScrollForward) 6.dp else 0.dp)
                    Column(Modifier.fillMaxWidth().zIndex(1f).shadow(bottomShadow).background(barColor)) {
                        HorizontalDivider(color = DesignColors.Divider)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            content = buttons,
                        )
                    }
                }
            }
        }
    }
}

private fun arrowMovesFocus(event: KeyEvent, focusManager: FocusManager): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
        else -> false
    }
}

/** A dialog's secondary action (Cancel, Not now, Later, Reset): secondary ink, so only the primary action carries the accent. */
@Composable
fun DialogSecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = DesignColors.InkSecondary),
    ) { Text(text) }
}
