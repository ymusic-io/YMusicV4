package io.ymusic.ui

import androidx.compose.ui.window.DialogProperties

internal actual fun wideDialogProperties(dismissOnClickOutside: Boolean): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOnClickOutside)
