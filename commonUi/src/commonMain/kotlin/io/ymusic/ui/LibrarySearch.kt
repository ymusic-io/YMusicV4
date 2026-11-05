package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtSectionHeader
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.library_search_field_hint
import io.ymusic.ui.resources.library_search_hint
import io.ymusic.ui.resources.library_search_no_matches
import io.ymusic.ui.resources.library_search_online
import io.ymusic.ui.resources.library_tab_albums
import io.ymusic.ui.resources.library_tab_artists
import io.ymusic.ui.resources.library_tab_songs
import io.ymusic.ui.resources.n_songs
import io.ymusic.ui.resources.unknown_album
import io.ymusic.ui.resources.unknown_artist
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Library search's retained state: the query, the open artist/album drill-down and the item
 * snapshot it filters (kept fresh over the store's change flow). A layer object, like
 * [SearchSession] — the flow differs (local filter-as-you-type, no suggestions, no paging).
 */
class LibrarySearchSession(private val store: BetterMediaStore) {
    var field by mutableStateOf(TextFieldValue(""))

    /** Open artist/album drill-down: header title + its songs. Back clears it before popping. */
    var drill by mutableStateOf<Pair<String, List<MediaStoreItem>>?>(null)

    var all by mutableStateOf<List<MediaStoreItem>>(emptyList())
        private set

    suspend fun collectItems() {
        store.itemsChanges().onStart { emit(Unit) }.conflate().collect { all = store.allItems() }
    }
}

/**
 * The local-files search flow (mirrors the legacy app's Library search): type-to-filter over the
 * library — no suggestions, no submit round-trip — with a "Search "q" online" hand-off row on top,
 * then Songs / Artists / Albums matches. Artist/album taps drill into their songs in place.
 */
@Composable
fun LibrarySearchScreen(
    session: LibrarySearchSession,
    coordinator: PlaybackCoordinator,
    onBack: () -> Unit,
    onSearchOnline: (String) -> Unit,
) {
    LaunchedEffect(session) { session.collectItems() }
    val unknownArtist = stringResource(Res.string.unknown_artist)
    val unknownAlbum = stringResource(Res.string.unknown_album)
    // "Go to artist/album" drills in place, like tapping a matched artist/album row.
    val goToGroup: (LibraryGroupKind, MediaStoreItem) -> Unit = { kind, song ->
        val (keyOf, unknown) = when (kind) {
            LibraryGroupKind.Artist -> MediaStoreItem::artist to unknownArtist
            LibraryGroupKind.Album -> MediaStoreItem::album to unknownAlbum
        }
        val name = keyOf(song)?.takeIf(String::isNotBlank)
        session.drill = (name ?: unknown) to session.all.filter { keyOf(it)?.takeIf(String::isNotBlank) == name }
    }
    CompositionLocalProvider(LocalGoToLibraryGroup provides goToGroup) {
        LibrarySearchContent(session, coordinator, onBack, onSearchOnline, unknownArtist, unknownAlbum)
    }
}

@Composable
private fun LibrarySearchContent(
    session: LibrarySearchSession,
    coordinator: PlaybackCoordinator,
    onBack: () -> Unit,
    onSearchOnline: (String) -> Unit,
    unknownArtist: String,
    unknownAlbum: String,
) {
    Column(Modifier.fillMaxSize()) {
        SearchInputBar(
            value = session.field,
            onValue = {
                session.field = it
                session.drill = null
            },
            onSubmit = {},
            onBack = { if (session.drill != null) session.drill = null else onBack() },
            autoFocus = session.drill == null,
            placeholder = stringResource(Res.string.library_search_field_hint),
        )
        val drill = session.drill
        val query = session.field.text.trim()
        when {
            drill != null -> SongList(
                drill.second,
                coordinator,
                header = { DrillDownHeader(drill.first, onBack = { session.drill = null }) },
            )
            query.isEmpty() -> CenteredBox {
                Text(stringResource(Res.string.library_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val songs = session.all.filter { it.title.contains(query, ignoreCase = true) }
                val artists = groupMatches(session.all, query, unknownArtist) { it.artist }
                val albums = groupMatches(session.all, query, unknownAlbum) { it.album }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Matched songs get the Library's min-width multi-column sizing on large screens
                    // (columns == 1 collapses the grid back to the phone-width single-column list); the
                    // online hand-off, section headers and artist/album rows always span the full width.
                    val columns = if (maxWidth >= LibraryGridMinWidth) librarySongColumns(maxWidth) else 1
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(Res.string.library_search_online, query),
                                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSearchOnline(query) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        if (songs.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(YtSectionHeader(title = stringResource(Res.string.library_tab_songs), count = "${songs.size}"))
                            }
                            items(songs.size, key = { songs[it].id }) { index ->
                                SongRow(songs[index], coordinator) { coordinator.playLocal(songs, index) }
                            }
                        }
                        groupSection(Res.string.library_tab_artists, Icons.Default.Person, artists, session)
                        groupSection(Res.string.library_tab_albums, Icons.Default.Album, albums, session)
                        if (songs.isEmpty() && artists.isEmpty() && albums.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    stringResource(Res.string.library_search_no_matches),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Groups whose display name matches the query, sorted by name. */
private fun groupMatches(
    all: List<MediaStoreItem>,
    query: String,
    unknownLabel: String,
    keyOf: (MediaStoreItem) -> String?,
): List<Pair<String, List<MediaStoreItem>>> =
    all.groupBy { keyOf(it)?.takeIf(String::isNotBlank) ?: unknownLabel }
        .filterKeys { it.contains(query, ignoreCase = true) }
        .toList()
        .sortedBy { (name, _) -> name.lowercase() }

private fun LazyGridScope.groupSection(
    title: org.jetbrains.compose.resources.StringResource,
    icon: ImageVector,
    groups: List<Pair<String, List<MediaStoreItem>>>,
    session: LibrarySearchSession,
) {
    if (groups.isEmpty()) return
    item(span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(YtSectionHeader(title = stringResource(title), count = "${groups.size}"))
    }
    items(groups.size, span = { GridItemSpan(maxLineSpan) }, key = { "$title-${groups[it].first}" }) { i ->
        val (name, members) = groups[i]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { session.drill = name to members }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LibraryThumb(icon, badge = null, artUrl = members.firstNotNullOfOrNull(::localArtUrl))
            Column(Modifier.weight(1f)) {
                Text(name, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(Res.plurals.n_songs, members.size, members.size),
                    style = ListSubStyle,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
