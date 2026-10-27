package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small icon action: the glyph centered in a [hitSize] circular hover/press target — the standard
 * is a 24dp glyph with 8dp around it (40dp).
 */
@Composable
fun ActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    hitSize: Dp = 40.dp,
    tint: Color = DesignColors.InkMuted,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ActionIconBox(
        modifier,
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        hitSize,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

@Composable
fun ActionIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    hitSize: Dp = 40.dp,
    tint: Color = DesignColors.InkMuted,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ActionIconBox(
        modifier,
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        hitSize,
    ) {
        Icon(painter, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

/** An on/off [ActionIcon]. Keep [contentDescription] fixed — screen readers announce [checked] themselves. */
@Composable
fun ToggleActionIcon(
    icon: ImageVector,
    contentDescription: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    hitSize: Dp = 40.dp,
    tint: Color = DesignColors.InkMuted,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ActionIconBox(
        modifier,
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        hitSize,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

@Composable
private fun ActionIconBox(
    modifier: Modifier,
    interaction: Modifier,
    hitSize: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(hitSize)
            .clip(CircleShape)
            .then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
