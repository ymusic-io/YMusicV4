package io.ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.arrange_songs
import io.ymusic.ui.resources.cd_sort
import io.ymusic.ui.resources.cd_sort_ascending
import io.ymusic.ui.resources.cd_sort_descending
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One sort choice: a localized [label] and the ascending [comparator] it sorts by. A non-[directional]
 *  option (a list's own manual order) ignores the direction toggle. */
internal class SortOption<T>(val label: StringResource, val comparator: Comparator<T>, val directional: Boolean = true)

/** The selected sort option (index into a tab's options) + direction, persisted per list. */
internal class SortState(index: Int = 0, descending: Boolean = false) {
    var index by mutableStateOf(index)
    var descending by mutableStateOf(descending)

    fun <T> sort(items: List<T>, options: List<SortOption<T>>): List<T> {
        val option = options.getOrElse(index) { options.first() }
        val sorted = items.sortedWith(option.comparator)
        return if (descending && option.directional) sorted.asReversed() else sorted
    }

    fun encode(): String = "$index,${if (descending) 1 else 0}"

    companion object {
        fun decode(raw: String, defaultIndex: Int = 0): SortState {
            val parts = raw.split(',')
            val index = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 0 } ?: return SortState(defaultIndex)
            return SortState(index, parts.getOrNull(1) == "1")
        }
    }
}

@Composable
internal fun rememberSortState(id: String, settings: SettingsStore, defaultIndex: Int = 0): SortState {
    val setting = remember(id) { AppSettings.librarySort(id) }
    val state = remember(id, settings) { SortState.decode(settings.flow(setting).value, defaultIndex) }
    LaunchedEffect(state) {
        snapshotFlow { state.encode() }.drop(1).collect { settings.put(setting, it) }
    }
    return state
}

/** A full-width row that right-aligns the [SortControl], with optional [leading] actions on the
 *  left. The Folders tab instead drops [SortControl] into its scan row. */
@Composable
internal fun SortBar(
    labels: List<StringResource>,
    state: SortState,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(LibrarySubToolbarHeight).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Spacer(Modifier.weight(1f))
        SortControl(labels, state)
    }
}

/** The sort control: a chip naming the current key (tap → menu) + a direction toggle. Picking the
 *  current key in the menu flips direction; picking another selects it ascending. With [onArrange], the
 *  option at [manualIndex] is the list's manual order: picking it selects it and opens the arranger,
 *  and while it is selected an edit icon (which also opens it) replaces the direction toggle; that menu
 *  shows no check marks. */
@Composable
internal fun SortControl(
    labels: List<StringResource>,
    state: SortState,
    manualIndex: Int = -1,
    onArrange: (() -> Unit)? = null,
) {
    val manual = onArrange != null && state.index == manualIndex
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            ChipTapArea({ open = true }) { indication ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .then(indication)
                        .chipPadding(leadingIcon = true),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        null,
                        Modifier.size(16.dp).spokenAs(stringResource(Res.string.cd_sort)),
                        tint = DesignColors.InkSoft,
                    )
                    Text(
                        stringResource(labels.getOrElse(state.index) { labels.first() }),
                        style = TextStyle(fontSize = ChipTextSize, fontWeight = FontWeight.Medium, color = DesignColors.Ink),
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                labels.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = if (onArrange != null) {
                            null
                        } else {
                            {
                                if (i == state.index) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = DesignColors.Accent)
                            }
                        },
                        onClick = {
                            when {
                                onArrange != null && i == manualIndex -> {
                                    state.index = i
                                    state.descending = false
                                    onArrange()
                                }
                                i == state.index -> state.descending = !state.descending
                                else -> {
                                    state.index = i
                                    state.descending = false
                                }
                            }
                            open = false
                        },
                    )
                }
            }
        }
        if (manual) {
            ActionIcon(Icons.Outlined.Edit, stringResource(Res.string.arrange_songs), tint = DesignColors.InkSoft) { onArrange?.invoke() }
        } else {
            ActionIcon(
                if (state.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                stringResource(if (state.descending) Res.string.cd_sort_descending else Res.string.cd_sort_ascending),
                tint = DesignColors.InkSoft,
            ) { state.descending = !state.descending }
        }
    }
}
