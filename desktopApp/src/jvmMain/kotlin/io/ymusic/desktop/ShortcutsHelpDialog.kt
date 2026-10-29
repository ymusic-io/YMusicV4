package io.ymusic.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_back
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.cd_forward10
import io.ymusic.ui.resources.cd_fullscreen
import io.ymusic.ui.resources.cd_next
import io.ymusic.ui.resources.cd_previous
import io.ymusic.ui.resources.cd_rewind10
import io.ymusic.ui.resources.cd_search
import io.ymusic.ui.resources.shortcuts_escape
import io.ymusic.ui.resources.shortcuts_help
import io.ymusic.ui.resources.shortcuts_nav_menu
import io.ymusic.ui.resources.shortcuts_now_playing
import io.ymusic.ui.resources.shortcuts_play_pause
import io.ymusic.ui.resources.shortcuts_switch_tabs
import io.ymusic.ui.resources.shortcuts_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ShortcutsHelpDialog(onDismiss: () -> Unit) {
    // Cmd on macOS, Ctrl everywhere else; the handler accepts both so this is purely a display label.
    val cmd = if (isMac) "⌘" else "Ctrl"
    val rows = listOf(
        "F8" to Res.string.shortcuts_play_pause,
        "F7" to Res.string.cd_rewind10,
        "F9" to Res.string.cd_forward10,
        "F11" to Res.string.cd_fullscreen,
        "$cmd + [" to Res.string.cd_previous,
        "$cmd + ]" to Res.string.cd_next,
        "$cmd + N" to Res.string.shortcuts_now_playing,
        "$cmd + M" to Res.string.shortcuts_nav_menu,
        "$cmd + F" to Res.string.cd_search,
        "$cmd + 1…9" to Res.string.shortcuts_switch_tabs,
        "Esc" to Res.string.shortcuts_escape,
        "Esc Esc" to Res.string.cd_back,
        "F1" to Res.string.shortcuts_help,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cd_close)) } },
        title = { Text(stringResource(Res.string.shortcuts_title)) },
        text = {
            Column(Modifier.widthIn(min = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEach { (key, label) -> ShortcutRow(key, stringResource(label)) }
            }
        },
    )
}

/** One Tab stop read as "action, keys". focusable goes before clearAndSetSemantics, which drops later modifiers. */
@Composable
private fun ShortcutRow(key: String, label: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else Color.Transparent)
            .focusable(interactionSource = interaction)
            .clearAndSetSemantics { text = AnnotatedString("$label, $key") }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.widthIn(min = 110.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
