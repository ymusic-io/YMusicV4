package io.ymusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ShortcutsHelpDialog(onDismiss: () -> Unit) {
    // Cmd on macOS, Ctrl everywhere else; the handler accepts both so this is purely a display label.
    val cmd = if (isMac) "⌘" else "Ctrl"
    val rows = listOf(
        "F8" to "Play / Pause",
        "F7" to "Rewind 10s",
        "F9" to "Forward 10s",
        "F11" to "Toggle fullscreen",
        "$cmd + [" to "Previous track",
        "$cmd + ]" to "Next track",
        "$cmd + N" to "Toggle Now Playing",
        "$cmd + M" to "Toggle nav menu",
        "$cmd + F" to "Search",
        "$cmd + 1…9" to "Switch tabs (sections / channel tabs)",
        "Esc" to "Exit fullscreen · close Now Playing · close nav / popups",
        "F1" to "This help",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(Modifier.widthIn(min = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (key, label) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            key,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            modifier = Modifier.widthIn(min = 110.dp),
                        )
                        Spacer(Modifier.height(0.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}
