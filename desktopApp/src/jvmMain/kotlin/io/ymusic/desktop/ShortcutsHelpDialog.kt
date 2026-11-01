package io.ymusic.desktop

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.DesignColors
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.ElevatedBarDialog
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.a11y_key_arrow_down
import io.ymusic.ui.resources.a11y_key_arrow_left
import io.ymusic.ui.resources.a11y_key_arrow_right
import io.ymusic.ui.resources.a11y_key_arrow_up
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
import io.ymusic.ui.resources.shortcuts_volume_down
import io.ymusic.ui.resources.shortcuts_volume_up
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ShortcutsHelpDialog(onDismiss: () -> Unit) {
    // Cmd on macOS, Ctrl everywhere else; the handler accepts both so this is purely a display label.
    val cmd = if (isMac) "⌘" else "Ctrl"
    val rows = listOf(
        listOf("F8") to Res.string.shortcuts_play_pause,
        listOf("$cmd + ←") to Res.string.cd_previous,
        listOf("$cmd + →") to Res.string.cd_next,
        listOf("$cmd + Shift + ←", "F7") to Res.string.cd_rewind10,
        listOf("$cmd + Shift + →", "F9") to Res.string.cd_forward10,
        listOf("$cmd + ↑") to Res.string.shortcuts_volume_up,
        listOf("$cmd + ↓") to Res.string.shortcuts_volume_down,
        listOf("F11") to Res.string.cd_fullscreen,
        listOf("$cmd + N") to Res.string.shortcuts_now_playing,
        listOf("$cmd + M") to Res.string.shortcuts_nav_menu,
        listOf("$cmd + F") to Res.string.cd_search,
        listOf("$cmd + 1…9") to Res.string.shortcuts_switch_tabs,
        listOf("Esc") to Res.string.shortcuts_escape,
        listOf("Esc Esc") to Res.string.cd_back,
        listOf("F1") to Res.string.shortcuts_help,
    )
    // Arrow glyphs are spoken inconsistently (or not at all) across screen readers.
    val arrows = mapOf(
        "←" to stringResource(Res.string.a11y_key_arrow_left),
        "→" to stringResource(Res.string.a11y_key_arrow_right),
        "↑" to stringResource(Res.string.a11y_key_arrow_up),
        "↓" to stringResource(Res.string.a11y_key_arrow_down),
    )
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.shortcuts_title),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cd_close)) } },
        keyboardNav = true,
    ) {
        Column(Modifier.verticalScroll(scroll).padding(vertical = DesignSpacing.Space8)) {
            rows.forEach { (keys, label) ->
                val spoken = keys.joinToString(", ") { combo -> arrows.entries.fold(combo) { s, (glyph, name) -> s.replace(glyph, name) } }
                ShortcutRow(keys.joinToString("\n"), spoken, stringResource(label))
            }
        }
    }
}

/** One focus stop read as "action, keys". focusable goes before clearAndSetSemantics, which drops later modifiers. */
@Composable
private fun ShortcutRow(key: String, spokenKey: String, label: String) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .focusable(interactionSource = interaction)
            .indication(interaction, LocalIndication.current)
            .clearAndSetSemantics { text = AnnotatedString("$label, $spokenKey") }
            .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.DialogRowVertical),
    ) {
        Text(
            key,
            style = TextStyle(
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = DesignColors.Ink,
            ),
            modifier = Modifier.width(176.dp),
        )
        Text(label, style = TextStyle(fontSize = 15.sp, color = DesignColors.Ink))
    }
}
