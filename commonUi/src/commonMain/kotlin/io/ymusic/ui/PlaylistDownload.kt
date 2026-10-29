package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtLocaleOption
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.Playlist
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.a11y_downloaded
import io.ymusic.ui.resources.a11y_kind_live
import io.ymusic.ui.resources.a11y_kind_music_video
import io.ymusic.ui.resources.cd_back
import io.ymusic.ui.resources.cd_more
import io.ymusic.ui.resources.download_location_not_set
import io.ymusic.ui.resources.download_save_to
import io.ymusic.ui.resources.playlist_add_to
import io.ymusic.ui.resources.playlist_added
import io.ymusic.ui.resources.playlist_check_all
import io.ymusic.ui.resources.playlist_check_not_downloaded
import io.ymusic.ui.resources.playlist_create_confirm
import io.ymusic.ui.resources.playlist_create_title
import io.ymusic.ui.resources.playlist_download_action
import io.ymusic.ui.resources.playlist_download_title
import io.ymusic.ui.resources.playlist_index_prefix
import io.ymusic.ui.resources.playlist_index_prefix_hint
import io.ymusic.ui.resources.playlist_load_more
import io.ymusic.ui.resources.playlist_options
import io.ymusic.ui.resources.playlist_select_library
import io.ymusic.ui.resources.playlist_target_create
import io.ymusic.ui.resources.playlist_target_none
import io.ymusic.ui.resources.playlist_uncheck_all
import io.ymusic.ui.resources.playlist_uncheck_downloaded
import io.ymusic.ui.resources.playlist_videos_loaded
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Video heights a playlist download can ask for; each video takes its nearest available. */

/**
 * A playlist download page's retained state. It shares the playlist page's [pager], so the videos
 * already loaded there are listed at once and "Load more" extends both. [preset] holds the picks every
 * checked video is downloaded with — languages, not tracks, resolved per video like Quick download.
 */
@Stable
class PlaylistDownloadSession(val title: String, val pager: Pager, initialPreset: QuickDownloadPreset) {
    var preset by mutableStateOf(initialPreset)
    var checked by mutableStateOf<Set<String>>(emptySet())

    /** File names start with the video's playlist position: "1. ", "2. ", … */
    var indexPrefix by mutableStateOf(false)

    /** The Library playlist the downloads join (each song shows up there as it lands); null = none. */
    var targetPlaylist by mutableStateOf<Playlist?>(null)

    /** Every video id listed so far: a newly loaded page arrives checked, a seen one keeps its box. */
    private val seen = mutableSetOf<String>()

    internal fun onLoaded(videos: List<YtFeedItem>) {
        val fresh = videos.filter { seen.add(it.id) }
        if (fresh.isNotEmpty()) checked = checked + fresh.filter { !it.live }.map { it.id }
    }
}

@Composable
fun PlaylistDownloadScreen(graph: AppGraph, session: PlaylistDownloadSession, onBack: () -> Unit) {
    val downloads = graph.downloads ?: return
    val state by session.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    val videos = ready?.sections.orEmpty().flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }.distinctBy { it.id }
    LaunchedEffect(videos) { session.onLoaded(videos) }
    val offline = LocalAvailableOffline.current
    val languages = rememberYtLanguages(graph)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val scans = LocalScanController.current
    val count = videos.count { it.id in session.checked && !it.live }
    var confirming by remember { mutableStateOf<List<YtFeedItem>?>(null) }
    val startDownload: (List<YtFeedItem>) -> Unit = { picked ->
        val preset = session.preset
        scope.launch {
            val gated = graph.mediaStore != null && graph.libraryFolderPicker != null
            if (gated && !hasUsableDownloadLocation(graph) && !pickDownloadFolder(graph, snackbar, scans)) return@launch
            val mp3SourceBitrate = graph.mp3SourceBitrate()
            val positions = videos.withIndex().associate { (index, video) -> video.id to index + 1 }
            picked.forEach { video ->
                val prefix = if (session.indexPrefix) positions[video.id]?.let { "$it. " }.orEmpty() else ""
                downloads.enqueue(preset.request(video, mp3SourceBitrate, prefix))
            }
            val target = session.targetPlaylist
            val store = graph.mediaStore
            if (target != null && store != null) store.addCodesToPlaylist(target.id, picked.map { it.id })
            snackbar(getPluralString(Res.plurals.playlist_added, picked.size, picked.size))
            onBack()
        }
    }
    val locationName = rememberDownloadLocationName(graph)

    Column(Modifier.fillMaxSize().background(DesignColors.Surface)) {
        ElevatedBarColumn {
            AppBarRow {
                ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
                AppBarTitle(stringResource(Res.string.playlist_download_title))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                LoadedHeader(session, ready, videos, offline)
            }
            item {
                OptionsCard(
                    session,
                    languages,
                    ffmpegAvailable = downloads.ffmpegAvailable,
                    locationName = locationName,
                    onPickLocation = { scope.launch { pickDownloadFolder(graph, snackbar, scans) } },
                    libraryStore = graph.mediaStore,
                )
            }
            itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                VideoCheckRow(
                    video,
                    position = (index + 1).takeIf { session.indexPrefix },
                    checked = video.id in session.checked,
                    downloaded = video.id in offline,
                ) { on ->
                    session.checked = if (on) session.checked + video.id else session.checked - video.id
                }
            }
        }
        HorizontalDivider(color = DesignColors.Divider)
        Box(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = LocalMiniPlayerInset.current).padding(DesignSpacing.Space16),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = {
                    val picked = videos.filter { it.id in session.checked && !it.live }
                    if (picked.any { it.id in offline }) confirming = picked else startDownload(picked)
                },
                enabled = count > 0,
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = downloadPrimary, contentColor = Color.White),
            ) {
                Text(pluralStringResource(Res.plurals.playlist_download_action, count, count), fontWeight = FontWeight.Bold)
            }
        }
    }
    confirming?.let { picked ->
        PlaylistRedownloadDialog(
            count = picked.count { it.id in offline },
            onSkip = {
                confirming = null
                picked.filter { it.id !in offline }.takeIf { it.isNotEmpty() }?.let(startDownload)
            },
            onAll = {
                confirming = null
                startDownload(picked)
            },
            onDismiss = { confirming = null },
        )
    }
}

private val CONTENT_MAX_WIDTH = 760.dp

@Composable
private fun LoadedHeader(session: PlaylistDownloadSession, ready: Pager.State.Ready?, videos: List<YtFeedItem>, offline: Set<String>) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
    ) {
        Text(
            session.title,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
            FeedChip(pluralStringResource(Res.plurals.playlist_videos_loaded, videos.size, videos.size), selected = false, onClick = null)
            when {
                ready?.loadingMore == true -> CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.Accent, strokeWidth = 2.dp)
                ready?.continuation != null -> FeedChip(stringResource(Res.string.playlist_load_more), selected = true) { session.pager.loadMore() }
            }
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.a11yActionLabel(stringResource(Res.string.cd_more))) {
                    Icon(Icons.Default.MoreVert, null, tint = DesignColors.InkSoft)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = DesignColors.Surface) {
                    val pickable = videos.filter { !it.live }
                    fun pick(label: String, apply: () -> Unit) = Choice(label, selected = false, pick = apply)
                    ChoiceMenuItems(
                        listOf(
                            pick(stringResource(Res.string.playlist_check_all)) { session.checked = pickable.map { it.id }.toSet() },
                            pick(stringResource(Res.string.playlist_check_not_downloaded)) {
                                session.checked = session.checked + pickable.filter { it.id !in offline }.map { it.id }
                            },
                            pick(stringResource(Res.string.playlist_uncheck_all)) { session.checked = emptySet() },
                            pick(stringResource(Res.string.playlist_uncheck_downloaded)) {
                                session.checked = session.checked - offline
                            },
                        ),
                    ) { menuOpen = false }
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(
    session: PlaylistDownloadSession,
    languages: List<YtLocaleOption>,
    ffmpegAvailable: Boolean,
    locationName: String?,
    onPickLocation: () -> Unit,
    libraryStore: BetterMediaStore?,
) {
    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(12.dp))
            .padding(DesignSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Text(
            stringResource(Res.string.playlist_options).uppercase(),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = DesignColors.InkSecondary),
        )
        QuickPresetFields(session.preset, languages, ffmpegAvailable) { session.preset = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
            Text(
                stringResource(Res.string.download_save_to),
                Modifier.width(112.dp),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            OutlinedButton(
                onClick = onPickLocation,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(9.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DesignColors.Outline),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DesignSpacing.Space8),
            ) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = DesignColors.InkSoft)
                Text(
                    locationName ?: stringResource(Res.string.download_location_not_set),
                    Modifier.weight(1f).padding(start = DesignSpacing.Space8),
                    style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().toggleable(value = session.indexPrefix, role = Role.Switch) { session.indexPrefix = it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            Text(
                stringResource(Res.string.playlist_index_prefix),
                Modifier.width(112.dp),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                stringResource(Res.string.playlist_index_prefix_hint),
                Modifier.weight(1f),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
            )
            CompactSwitch(session.indexPrefix)
        }
        val store = libraryStore
        if (store != null) {
            var reload by remember { mutableStateOf(0) }
            var creating by remember { mutableStateOf(false) }
            val playlists by produceState(emptyList<Playlist>(), store, reload) { value = store.playlists() }
            val none = stringResource(Res.string.playlist_target_none)
            OptionLine(
                stringResource(Res.string.playlist_add_to),
                session.targetPlaylist?.name ?: stringResource(Res.string.playlist_select_library),
            ) { close ->
                ChoiceMenuItems(
                    listOf(
                        Choice(none, session.targetPlaylist == null) { session.targetPlaylist = null },
                        Choice(stringResource(Res.string.playlist_target_create), selected = false) { creating = true },
                    ) + playlists.map { pl -> Choice(pl.name, pl.id == session.targetPlaylist?.id) { session.targetPlaylist = pl } },
                    close,
                )
            }
            if (creating) {
                PlaylistNameDialog(
                    title = stringResource(Res.string.playlist_create_title),
                    confirmLabel = stringResource(Res.string.playlist_create_confirm),
                    initial = session.title,
                    existingNames = playlists.map { it.name },
                    onDismiss = { creating = false },
                    onConfirm = { name ->
                        session.targetPlaylist = store.createPlaylist(name)
                        reload++
                    },
                )
            }
        }
    }
}

@Composable
private fun VideoCheckRow(video: YtFeedItem, position: Int?, checked: Boolean, downloaded: Boolean, onToggle: (Boolean) -> Unit) {
    val title = if (position != null) "$position. ${video.title}" else video.title
    val label = listOfNotNull(
        title,
        when {
            video.live -> stringResource(Res.string.a11y_kind_live)
            video.musicVideo -> stringResource(Res.string.a11y_kind_music_video)
            else -> null
        },
        spokenLength(video.durationMs, video.duration),
        video.subtitleLine1,
        if (downloaded) stringResource(Res.string.a11y_downloaded) else null,
    ).joinToString(", ")
    Row(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .toggleable(value = checked, enabled = !video.live, role = Role.Checkbox, onValueChange = onToggle)
            .a11yActionLabel(label)
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Checkbox(
            checked = checked && !video.live,
            onCheckedChange = null,
            enabled = !video.live,
            colors = CheckboxDefaults.colors(checkedColor = DesignColors.Accent),
        )
        Box(
            Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)).a11ySilent(),
            contentAlignment = Alignment.Center,
        ) {
            ThumbImage(video.thumbs, RoundedCornerShape(6.dp)) { ThumbPlaceholder(Icons.Outlined.Image, 20.dp) }
            Badges(video, Modifier.align(Alignment.BottomEnd).padding(DesignSpacing.Space2), textSize = 10, iconSize = 12.dp)
            if (downloaded) AvailableOfflineMark(Modifier.align(Alignment.TopStart))
        }
        Column(Modifier.weight(1f).a11ySilent()) {
            Text(
                title,
                style = TextStyle(fontSize = 14.sp, color = if (video.live) DesignColors.InkFaint else DesignColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.subtitleLine1?.let {
                Text(it, style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
