package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtFilterGroup
import io.ymusic.dynamicengine.yt.YtFilterOption
import io.ymusic.dynamicengine.yt.musicSearch
import io.ymusic.dynamicengine.yt.musicSearchSuggestions
import io.ymusic.dynamicengine.yt.search
import io.ymusic.dynamicengine.yt.searchSuggestions
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_insert_query
import io.ymusic.ui.resources.cd_remove_from_history
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.search_clear_history
import io.ymusic.ui.resources.search_filter_title
import io.ymusic.ui.resources.search_no_results
import io.ymusic.ui.resources.search_type_to_search
import io.ymusic.ui.resources.search_youtube
import io.ymusic.ui.resources.ytmusic_search_hint
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val SearchHistoryKey = "search.history"
private const val HistoryLimit = 20

/**
 * The search layer's retained state — query, mode, results and scroll. Hoisted into the app's
 * layer stack so a detail page pushed above the results doesn't wipe them; popping back shows the
 * results exactly as left.
 *
 * Engine-agnostic: the actual search + suggest calls are injected ([youtube] = YouTube via
 * `yt/search`, [music] = YouTube Music via `ytmusic/search`), so the whole search UI — history,
 * live suggestions, the filter dialog — is shared across both surfaces. [hint] labels the input box.
 */
class SearchSession(
    scope: CoroutineScope,
    val hint: StringResource,
    internal val log: Logger? = null,
    private val onSuggest: suspend (String) -> List<String>,
    private val onSearch: suspend (query: String?, params: String?, continuation: String?) -> YtFeedPage,
) {
    var field by mutableStateOf(TextFieldValue(""))
    var submitted by mutableStateOf<String?>(null)
    var editing by mutableStateOf(true)
    var filterParams by mutableStateOf<String?>(null)
    val listState = LazyListState()
    val pager = Pager(scope, logger = log) { continuation ->
        onSearch(
            if (continuation == null) submitted else null,
            if (continuation == null) filterParams else null,
            continuation,
        )
    }

    suspend fun suggest(query: String): List<String> = onSuggest(query)

    fun close() {
        pager.close()
    }

    companion object {
        /** YouTube search (the Discover surface): `yt/search` + `yt/suggest`. */
        fun youtube(graph: AppGraph, scope: CoroutineScope, parentLog: Logger? = graph.logger): SearchSession {
            val log = parentLog?.fork("Search")
            return SearchSession(
                scope,
                Res.string.search_youtube,
                log = log,
                onSuggest = { graph.engines.searchSuggestions(it, logger = log) },
                onSearch = { q, p, c -> graph.engines.search(query = q, params = p, continuation = c, logger = log) },
            )
        }

        /** YouTube Music search (the YT Music surface): `ytmusic/search` + `ytmusic/suggest`. */
        fun music(graph: AppGraph, scope: CoroutineScope, parentLog: Logger? = graph.logger): SearchSession {
            val log = parentLog?.fork("MusicSearch")
            return SearchSession(
                scope,
                Res.string.ytmusic_search_hint,
                log = log,
                onSuggest = { graph.engines.musicSearchSuggestions(it, logger = log) },
                onSearch = { q, p, c -> graph.engines.musicSearch(query = q, params = p, continuation = c, logger = log) },
            )
        }
    }
}

/**
 * YouTube search (design/final/search.html), two app-bar modes:
 * - **input** — back · pill text field; below it the saved history (empty field) or live
 *   suggestions (typing). Rows submit on tap; the ↖ arrow only inserts the text into the field.
 * - **results** — back · query title · filter · search; below it the paged result feed. The filter
 *   icon opens the centered dialog of server-provided filter groups; picking an option re-runs the
 *   search with that option's params (the server composes the combinations).
 */
@Composable
fun SearchScreen(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    onBack: () -> Unit,
    onOpen: ((io.ymusic.dynamicengine.yt.YtFeedItem) -> Unit)? = null,
    session: SearchSession? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val s = session ?: remember { SearchSession.youtube(graph, scope) }
    var suggestions by remember { mutableStateOf(listOf<String>()) }
    var filterOpen by remember { mutableStateOf(false) }

    val historyRaw by remember(graph) { graph.settings.string(SearchHistoryKey, "") }.collectAsState()
    val history = remember(historyRaw) { historyRaw.split('\n').filter { it.isNotBlank() } }
    fun saveHistory(entries: List<String>) {
        scope.launch {
            graph.settings.putString(SearchHistoryKey, entries.take(HistoryLimit).joinToString("\n"))
        }
    }

    val pagerState by s.pager.state.collectAsState()

    // Debounced suggestions while typing in input mode.
    LaunchedEffect(s.editing, s.field.text) {
        if (!s.editing || s.field.text.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = try {
            s.suggest(s.field.text)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            s.log?.log(Logger.Level.WARN, "suggest failed", e)
            emptyList() // suggestions are best-effort sugar; the search box still works
        }
    }

    fun setField(text: String) {
        s.field = TextFieldValue(text, TextRange(text.length))
    }

    fun submit(q: String) {
        val trimmed = q.trim().replace('\n', ' ')
        if (trimmed.isEmpty()) return
        setField(trimmed)
        s.submitted = trimmed
        s.filterParams = null
        s.editing = false
        saveHistory(listOf(trimmed) + history.filter { it != trimmed })
        s.pager.refresh()
    }

    Column(Modifier.fillMaxSize()) {
        if (s.editing) {
            SearchInputBar(
                s.field,
                onValue = { s.field = it },
                onSubmit = ::submit,
                onBack = { if (s.submitted != null) s.editing = false else onBack() },
                placeholder = stringResource(s.hint),
            )
            when {
                s.field.text.isBlank() && history.isEmpty() -> CenteredBox {
                    Text(stringResource(Res.string.search_type_to_search), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.field.text.isBlank() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                    items(history) { entry ->
                        QueryRow(
                            icon = Icons.Outlined.Schedule,
                            text = AnnotatedString(entry),
                            onClick = { submit(entry) },
                            onInsert = { setField(entry) },
                            onRemove = { saveHistory(history - entry) },
                        )
                    }
                    item { ClearHistoryRow { saveHistory(emptyList()) } }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                    items(suggestions) { sug ->
                        QueryRow(
                            icon = Icons.Default.Search,
                            text = suggestionText(sug, s.field.text),
                            onClick = { submit(sug) },
                            onInsert = { setField(sug) },
                        )
                    }
                }
            }
        } else {
            val filterGroups = (pagerState as? Pager.State.Ready)?.filterGroups.orEmpty()
            SearchResultsBar(
                title = s.submitted.orEmpty(),
                onBack = onBack,
                showFilter = filterGroups.isNotEmpty(),
                onFilter = { filterOpen = true },
                onSearch = { s.editing = true },
            )
            FeedList(
                s.pager,
                coordinator,
                emptyMessage = stringResource(Res.string.search_no_results),
                onOpen = onOpen,
                listState = s.listState,
                onDeeplink = onDeeplink,
            )
            if (filterOpen) {
                FilterDialog(filterGroups, onDismiss = { filterOpen = false }) { option ->
                    filterOpen = false
                    option.params?.let {
                        s.filterParams = it
                        s.pager.refresh()
                    }
                }
            }
        }
    }
}

/** One history/suggestion row (`.srow`): icon · text · [✕ remove] · ↖ insert-into-field. */
@Composable
private fun QueryRow(
    icon: ImageVector,
    text: AnnotatedString,
    onClick: () -> Unit,
    onInsert: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        // The trailing ActionIcons' 8dp inset completes the 16dp end margin.
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = DesignColors.InkSecondary)
        Text(
            text,
            style = TextStyle(fontSize = 15.sp, color = DesignColors.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row {
            if (onRemove != null) {
                ActionIcon(
                    Icons.Default.Close,
                    stringResource(Res.string.cd_remove_from_history),
                    tint = DesignColors.InkSecondary,
                    onClick = onRemove,
                )
            }
            ActionIcon(
                Icons.Default.NorthWest,
                stringResource(Res.string.cd_insert_query),
                tint = DesignColors.PlaceholderIcon,
                onClick = onInsert,
            )
        }
    }
}

/** The centered "Clear history" action under the history rows (`.clearh`). */
@Composable
private fun ClearHistoryRow(onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClear).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Delete, null, Modifier.size(15.dp), tint = DesignColors.InkSoft)
        Text(
            stringResource(Res.string.search_clear_history),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSoft),
        )
    }
}

/** Bold the typed prefix inside a suggestion (the mockup's `<b>bảo anh</b> karaoke`). */
private fun suggestionText(suggestion: String, typed: String): AnnotatedString {
    val prefix = typed.trim()
    if (prefix.isEmpty() || !suggestion.startsWith(prefix, ignoreCase = true)) return AnnotatedString(suggestion)
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(suggestion.take(prefix.length)) }
        append(suggestion.drop(prefix.length))
    }
}

/** The centered filter dialog (`.dlg`): server filter groups as chip rows; a tap applies/removes
 *  that option (its params carry the whole resulting filter state). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(
    groups: List<YtFilterGroup>,
    onDismiss: () -> Unit,
    onSelect: (YtFilterOption) -> Unit,
) {
    val scrollState = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.search_filter_title),
        onDismiss = onDismiss,
        canScrollBackward = scrollState.canScrollBackward,
        canScrollForward = scrollState.canScrollForward,
        buttons = { DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onDismiss) },
    ) {
        Column(
            Modifier
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            groups.forEach { group ->
                Text(
                    group.title.uppercase(),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = DesignColors.InkSecondary,
                        letterSpacing = 0.4.sp,
                    ),
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.options.forEach { option ->
                        FilterPill(option) { onSelect(option) }
                    }
                }
            }
        }
    }
}

/** One filter chip: selected = accent-filled, disabled = faint and inert. */
@Composable
private fun FilterPill(option: YtFilterOption, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    ChipTapArea(onClick, enabled = !option.disabled, selected = option.selected) { indication ->
        Text(
            option.label,
            style = TextStyle(
                fontSize = ChipTextSize,
                color = when {
                    option.selected -> Color.White
                    option.disabled -> DesignColors.InkFaint
                    else -> DesignColors.InkSoft
                },
            ),
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .background(if (option.selected) DesignColors.Accent else Color.Transparent)
                .border(
                    1.dp,
                    when {
                        option.selected -> DesignColors.Accent
                        option.disabled -> DesignColors.OutlineSoft
                        else -> DesignColors.Outline
                    },
                    shape,
                )
                .then(indication)
                .chipPadding(),
        )
    }
}
