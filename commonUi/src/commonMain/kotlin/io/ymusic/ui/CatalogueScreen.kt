package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtSectionHeader
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.ic_sleep_timer
import org.jetbrains.compose.resources.painterResource

/**
 * Development-only component catalogue (a Storybook, not a product screen): every shared UI
 * component rendered on mock data, for design iteration and feedback. Reached from the drawer
 * ("UI catalogue") or standalone via `./gradlew :desktopApp:catalogue` — the standalone window
 * skips the whole engine boot, so the design loop is seconds. Only debug shells reference it, so
 * release binaries shrink it away.
 */
@Composable
fun CatalogueScreen(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val windowClass = when (feedLayoutFor(width)) {
            FeedLayout.SquareList -> "square list (<320)"
            FeedLayout.WideList -> "16:9 list (320–469)"
            FeedLayout.Grid -> "grid (≥470)"
        }
        Column(Modifier.fillMaxSize()) {
            Text(
                "${width.value.toInt()} × ${height.value.toInt()} dp — $windowClass",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DesignColors.Accent,
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            HorizontalDivider(color = DesignColors.Divider)
            CatalogueList()
        }
    }
}

@Composable
private fun CatalogueList() {
    LazyColumn(Modifier.fillMaxSize()) {
        item { CatLabel("App bars — ONE bar language (AppBars.kt): home · search input · search results · detail") }
        item { DesignAppBar("Home", onMenu = {}, onSearch = {}) }
        item { BarGap() }
        item {
            DesignAppBar("Home", onMenu = {}, onSearch = {}, inlineContent = {
                DiscoverTabRow(DiscoverTab.Home, inline = true) {}
            })
        }
        item { BarGap() }
        item {
            var field by remember { mutableStateOf(TextFieldValue("bảo anh")) }
            SearchInputBar(field, onValue = { field = it }, onSubmit = {}, onBack = {}, autoFocus = false)
        }
        item { BarGap() }
        item { SearchResultsBar("son tung mtp", onBack = {}, showFilter = true, onFilter = {}, onSearch = {}) }
        item { BarGap() }
        item { DetailAppBar("Playlist", onBack = {}, onSearch = {}) }
        item { BarGap() }
        item {
            var active by remember { mutableStateOf<String?>(null) }
            DetailAppBar("Sơn Tùng M-TP Official", onBack = {}, onSearch = {}) {
                ChannelTabRow(mockChannelTabs, activeUrl = active) { active = it.url }
                HorizontalDivider(color = DesignColors.Divider)
            }
        }

        item { CatLabel("App bars — ELEVATED (screen scrolled): 16dp shadow + SurfaceElevated tone") }
        item {
            CompositionLocalProvider(LocalScrollHost provides remember { ScrollHost(scrolled = true) }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    DetailAppBar("Scrolled state", onBack = {}, onSearch = {})
                }
            }
        }

        item { CatLabel("SectionHeader — title · icon+count · CTA · collapsible") }
        item { SectionHeader(YtSectionHeader(title = "Recommended")) }
        item { SectionHeader(YtSectionHeader(title = "Sơn Tùng M-TP", iconThumbs = mockAvatarThumbs, count = "500+")) }
        item { SectionHeader(YtSectionHeader(title = "Trending", count = "50", ctaText = "View all", ctaToken = "t")) }
        item {
            var collapsed by remember { mutableStateOf(false) }
            SectionHeader(YtSectionHeader(title = "Collapsible"), collapsed = collapsed, onToggle = { collapsed = !collapsed })
        }

        item { CatLabel("FeedListItem — square list form (<320dp) · video · live · playlist · channel") }
        item { FeedListItem(mockVideo, mockActions, onClick = {}) }
        item { FeedListItem(mockLive, mockActions, onClick = {}) }
        item { FeedListItem(mockPlaylist, mockActions, onClick = {}) }
        item { FeedListItem(mockChannel, mockActions, onClick = {}) }

        item { CatLabel("FeedListItem wide — 16:9 list form (320–469dp)") }
        item { FeedListItem(mockVideo, mockActions, onClick = {}, wide = true) }
        item { FeedListItem(mockPlaylist, mockActions, onClick = {}, wide = true) }

        item { CatLabel("Breakpoints — adaptive: resize the window, this section switches band live") }
        item { AdaptiveBandDemo() }

        item { CatLabel("FeedGridCard — video · playlist · channel (metrics follow window width)") }
        item {
            CatalogueGridRow { inset ->
                FeedGridCard(mockVideo, mockActions, onClick = {}, modifier = Modifier.weight(1f), inset = inset)
                FeedGridCard(mockPlaylist, mockActions, onClick = {}, modifier = Modifier.weight(1f), inset = inset)
                FeedGridCard(mockChannel, mockActions, onClick = {}, modifier = Modifier.weight(1f), inset = inset)
            }
        }

        item { CatLabel("ShowMoreButton") }
        item {
            var expanded by remember { mutableStateOf(false) }
            ShowMoreButton(
                if (expanded) "Show less" else "Show more (12)",
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
        }

        item { CatLabel("Typography — the design-language text roles (each specimen in its own style)") }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                typeRoles.forEach { (label, style) -> Text(label, style = style, maxLines = 1) }
                Text(
                    "Body — M3 bodyLarge · search suggestion rows",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            }
        }

        item { CatLabel("DiscoverTabRow — standalone · inline (app-bar) form") }
        item {
            var tab by remember { mutableStateOf(DiscoverTab.Home) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DiscoverTabRow(tab) { tab = it }
                DiscoverTabRow(tab, inline = true) { tab = it }
            }
        }

        item { CatLabel("Library tabs — LibraryChip row") }
        item {
            var selected by remember { mutableStateOf(0) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                mockLibraryTabs.forEachIndexed { i, label ->
                    LibraryChip(label, selected = i == selected) { selected = i }
                }
            }
        }

        item { CatLabel("Chips — FeedChip · QualityChip (dropdown)") }
        item {
            var chip by remember { mutableStateOf(0) }
            var quality by remember { mutableStateOf("1080p") }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                mockFeedChips.forEachIndexed { i, label ->
                    FeedChip(label, selected = i == chip) { chip = i }
                }
                QualityChip(
                    quality,
                    mockQualities,
                    selectedId = mockQualities.firstOrNull { it.second == quality }?.first,
                ) { id -> quality = mockQualities.first { it.first == id }.second }
            }
        }

        item { CatLabel("ActionIcon — 22/32 default · 26/36 transport · accent · disabled") }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                ActionIcon(Icons.Default.MoreVert, "more") {}
                ActionIcon(Icons.Default.PlayArrow, "play", iconSize = 26.dp, hitSize = 36.dp) {}
                ActionIcon(painterResource(Res.drawable.ic_sleep_timer), "sleep", tint = DesignColors.Accent) {}
                ActionIcon(Icons.Default.SkipNext, "next", enabled = false, tint = DesignColors.InkFaint) {}
            }
        }

        item { CatLabel("Badges — duration · LIVE · playlist count (thumb bottom-right)") }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(mockVideo, mockLive, mockPlaylist).forEach { item ->
                    Box(
                        Modifier
                            .size(width = 92.dp, height = 52.dp)
                            .background(DesignColors.Placeholder, RoundedCornerShape(10.dp)),
                    ) {
                        Badges(
                            item,
                            Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(4.dp),
                            textSize = 9,
                            iconSize = 10.dp,
                        )
                    }
                }
            }
        }

        item { CatLabel("Buttons & menus — OutlinedButton · SleepTimerButton (accent = armed)") }
        item {
            var sleepMs by remember { mutableStateOf<Long?>(null) }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {}) { Text("Retry") }
                SleepTimerButton(sleepMs, afterCurrent = false, lastMinutes = 30) { choice ->
                    sleepMs = (choice as? SleepChoice.Timer)?.let { it.minutes * 60_000L }
                }
            }
        }

        item { CatLabel("Search field — OutlinedTextField with clear affordance") }
        item {
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        ActionIcon(Icons.Default.Close, "clear") { query = "" }
                    }
                },
            )
        }

        item { CatLabel("Breadcrumb — library folder path (tap a crumb to navigate up)") }
        item {
            var depth by remember { mutableStateOf(mockCrumbs.size) }
            Breadcrumb(mockCrumbs.take(depth)) { depth = it }
        }

        item { CatLabel("Empty page — message + reload") }
        item { Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyState("Nothing here") {} } }

        item { CatLabel("Error page — code + retry") }
        item { Box(Modifier.fillMaxWidth().height(200.dp)) { ErrorState("E20030") {} } }

        item { CatLabel("Progress — circular (feed loading) · linear (mini-player)") }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                LinearProgressIndicator(progress = { 0.4f }, modifier = Modifier.weight(1f))
            }
        }

        item { CatLabel("Thumbnails — live loader (needs network; placeholder without)") }
        item { FeedListItem(mockVideo.copy(thumbs = mockVideoThumbs), mockActions, onClick = {}) }
        item {
            CatalogueGridRow { inset ->
                FeedGridCard(mockVideo.copy(thumbs = mockVideoThumbs), mockActions, onClick = {}, modifier = Modifier.weight(1f), inset = inset)
                FeedGridCard(mockChannel.copy(thumbs = mockAvatarThumbs), mockActions, onClick = {}, modifier = Modifier.weight(1f), inset = inset)
            }
        }

        item { CatLabel("AppDrawer") }
        item {
            var selected by remember { mutableStateOf(AppSection.Discover) }
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .height(560.dp)
                    .border(1.dp, DesignColors.Outline)
                    .clipToBounds(),
            ) {
                AppDrawer(
                    section = selected,
                    appVersion = "catalogue · mock",
                    onSelect = { selected = it },
                )
            }
        }

        item { CatLabel("DesignColors") }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                colorSwatches.forEach { (name, color) -> ColorSwatch(name, color) }
            }
        }

        item { CatLabel("Key spacings (dp)") }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                spacingTokens.forEach { (value, label) -> SpacingBar(value, label) }
            }
        }

        item { CatLabel("Corner radii (dp)") }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                radiusTokens.forEach { (value, label) -> RadiusBox(value, label) }
            }
        }

        item { Box(Modifier.height(40.dp)) }
    }
}

/** A bar exactly [value] dp wide — the spacing at true size, next to its name. */
@Composable
private fun SpacingBar(value: Int, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(
            Modifier
                .width(value.dp)
                .height(14.dp)
                .background(DesignColors.Accent.copy(alpha = 0.4f)),
        )
        Text(
            "  $value · $label",
            style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DesignColors.InkSoft),
            maxLines = 1,
        )
    }
}

@Composable
private fun RadiusBox(value: Int, label: String) {
    val shape = RoundedCornerShape(if (value == PILL_RADIUS) 999.dp else value.dp)
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 64.dp, height = 40.dp)
                .background(DesignColors.Placeholder, shape)
                .border(1.dp, DesignColors.Outline, shape),
        )
        Text(
            if (value == PILL_RADIUS) "pill · $label" else "$value · $label",
            style = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DesignColors.InkSoft),
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 1,
        )
    }
}

/**
 * The feed rendered at the CURRENT window width, band picked by the real [feedLayoutFor] — resize
 * the window and this section flips square list → 16:9 list → grid (with the real column count)
 * exactly where the app does.
 */
@Composable
private fun AdaptiveBandDemo() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        val layout = feedLayoutFor(width)
        Column(Modifier.fillMaxWidth()) {
            Text(
                "${width.value.toInt()}dp → $layout",
                style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DesignColors.InkSoft),
                modifier = Modifier.padding(start = 16.dp, bottom = 3.dp),
            )
            when (layout) {
                FeedLayout.SquareList -> {
                    FeedListItem(mockVideo, mockActions, onClick = {})
                    FeedListItem(mockPlaylist, mockActions, onClick = {})
                }
                FeedLayout.WideList -> {
                    FeedListItem(mockVideo, mockActions, onClick = {}, wide = true)
                    FeedListItem(mockPlaylist, mockActions, onClick = {}, wide = true)
                }
                FeedLayout.Grid -> {
                    val columns = gridColumnsFor(width)
                    val items = listOf(mockVideo, mockPlaylist, mockLive)
                    CatalogueGridRow { inset ->
                        repeat(columns) { i ->
                            FeedGridCard(
                                items[i % items.size],
                                mockActions,
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                inset = inset,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A grid demo row on the real per-window-class metrics — resize the window to see them adapt. */
@Composable
private fun CatalogueGridRow(content: @Composable RowScope.(inset: Dp) -> Unit) {
    BoxWithConstraints {
        val metrics = gridMetricsFor(maxWidth)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = metrics.margin - metrics.inset),
            horizontalArrangement = Arrangement.spacedBy(metrics.gutter - metrics.inset * 2),
        ) {
            content(metrics.inset)
        }
    }
}

/** A hairline separating stacked bar specimens (they're all white-on-white). */
@Composable
private fun BarGap() {
    HorizontalDivider(color = DesignColors.StackFar, modifier = Modifier.padding(vertical = 4.dp))
}

/** Dev chrome: monospace caption + divider above each catalogue group. */
@Composable
private fun CatLabel(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Text(
            text,
            style = TextStyle(
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DesignColors.InkSecondary,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider(color = DesignColors.Divider)
    }
}

@Composable
private fun ColorSwatch(name: String, color: Color) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 64.dp, height = 40.dp)
                .background(color, RoundedCornerShape(6.dp))
                .border(1.dp, DesignColors.Divider, RoundedCornerShape(6.dp)),
        )
        Text(
            name,
            style = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DesignColors.InkSoft),
            modifier = Modifier.padding(top = 3.dp).width(64.dp),
            maxLines = 1,
        )
    }
}

// ---- mock data (titles from the test fixtures; thumbs are real CDN URLs for the loader demo) ----

private val mockVideoThumbs = listOf(
    YtThumb("https://i.ytimg.com/vi/FN7ALfpGxiI/mqdefault.jpg", 320, 180),
    YtThumb("https://i.ytimg.com/vi/FN7ALfpGxiI/hqdefault.jpg", 480, 360),
    YtThumb("https://i.ytimg.com/vi/FN7ALfpGxiI/hq720.jpg", 1280, 720),
)

private val mockAvatarThumbs = listOf(
    YtThumb(
        "https://yt3.googleusercontent.com/ru3SakOhxGBHZnrVeKPwmMndF81j1-gIrsPtmGSHV1YbX3Y6JoFVMR93UdlfpKp9_xIeh62t=s176-c-k-c0x00ffffff-no-rj-mo",
        176,
        176,
    ),
)

private val mockVideo = YtFeedItem(
    kind = YtFeedItem.KIND_VIDEO,
    id = "FN7ALfpGxiI",
    title = "NƠI NÀY CÓ ANH | OFFICIAL MUSIC VIDEO | SƠN TÙNG M-TP",
    subtitleLine1 = "Sơn Tùng M-TP Official",
    subtitleLine2 = "453,141,392 views · 9 years ago",
    channelId = "UClyA28-01x4z60eWQ2kiNbA",
    duration = "4:39",
)

private val mockLive = YtFeedItem(
    kind = YtFeedItem.KIND_VIDEO,
    id = "live1",
    title = "lofi hip hop radio — beats to relax/study to",
    subtitleLine1 = "Lofi Girl",
    subtitleLine2 = "32K watching",
    live = true,
)

private val mockPlaylist = YtFeedItem(
    kind = YtFeedItem.KIND_PLAYLIST,
    id = "RDEMeyKnYmm7ScRhnRO9nwOSNA",
    title = "Mix — Sơn Tùng M-TP",
    subtitleLine1 = "Sơn Tùng M-TP, JustaTee, Karik, and more",
    itemCount = "42",
)

private val mockChannel = YtFeedItem(
    kind = YtFeedItem.KIND_CHANNEL,
    id = "UClyA28-01x4z60eWQ2kiNbA",
    title = "Sơn Tùng M-TP Official",
    subtitleLine1 = "11.8M subscribers",
    channelId = "UClyA28-01x4z60eWQ2kiNbA",
)

private val mockActions = ItemActions(
    listOf(
        "Play next" to {},
        "Add to queue" to {},
        "Download" to {},
    ),
)

private val mockLibraryTabs = listOf("Folders", "Playlists", "Songs", "Artists", "Albums", "Genres")

private val mockChannelTabs = listOf(
    io.ymusic.dynamicengine.yt.YtPageTab("Home", selected = true),
    io.ymusic.dynamicengine.yt.YtPageTab("Videos", url = "/channel/UCmock?params=videos"),
    io.ymusic.dynamicengine.yt.YtPageTab("Shorts", url = "/channel/UCmock?params=shorts"),
    io.ymusic.dynamicengine.yt.YtPageTab("Live", url = "/channel/UCmock?params=live"),
    io.ymusic.dynamicengine.yt.YtPageTab("Releases", url = "/channel/UCmock?params=releases"),
    io.ymusic.dynamicengine.yt.YtPageTab("Playlists", url = "/channel/UCmock?params=playlists"),
    io.ymusic.dynamicengine.yt.YtPageTab("Posts", url = "/channel/UCmock?params=posts"),
)

private val mockFeedChips = listOf("All", "Music", "Podcasts", "Live")

private val mockQualities = listOf("zero" to "Audio only", "720" to "720p", "1080" to "1080p")

private val mockCrumbs = listOf("Music", "Albums", "Sơn Tùng M-TP")

// Specimens carry their own style; sizes in the label. The app has no central type scale —
// these are the de-facto roles (ListTitleStyle/… live in FeedItems.kt).
private val typeRoles: List<Pair<String, TextStyle>> @Composable get() = listOf(
    "Headline — section header · 15 Bold Ink" to
        TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
    "Title — list item · 13.5 SemiBold (ListTitleStyle)" to ListTitleStyle,
    "Title — grid card · 12.5 SemiBold (GridTitleStyle)" to GridTitleStyle,
    "Caption — list subtitle · 11.5 InkSecondary (ListSubStyle)" to ListSubStyle,
    "Caption — grid subtitle · 11 InkSecondary (GridSubStyle)" to GridSubStyle,
    "Label — chip · 12.5 Medium" to
        TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = DesignColors.Ink),
    "Mono — dev chrome · 11 Bold Monospace" to
        TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DesignColors.InkSecondary),
)

// The spacing scale (DesignSpacing): every layout spacing snaps to one of these steps.
private val spacingTokens: List<Pair<Int, String>> = listOf(
    48 to "space-48",
    32 to "space-32",
    24 to "space-24 · window margin (expanded >=840dp)",
    16 to "space-16 · margin compact / gutter / row gap / header top",
    8 to "space-8 · row vertical / header gap / chips / text top",
    4 to "space-4 · title-subtitle / badge inset / hover inset",
    2 to "space-2 · fine nudges",
)

private const val PILL_RADIUS = -1

private val radiusTokens: List<Pair<Int, String>> = listOf(
    13 to "logo tile",
    8 to "mini art",
    6 to "list thumb",
    5 to "grid thumb",
    3 to "badge",
    PILL_RADIUS to "chips/CTA",
)

private val colorSwatches: List<Pair<String, Color>> @Composable get() = listOf(
    "Accent" to DesignColors.Accent,
    "Ink" to DesignColors.Ink,
    "InkMuted" to DesignColors.InkMuted,
    "InkSoft" to DesignColors.InkSoft,
    "InkSecondary" to DesignColors.InkSecondary,
    "InkFaint" to DesignColors.InkFaint,
    "Placeholder" to DesignColors.Placeholder,
    "PlaceholderDim" to DesignColors.PlaceholderDim,
    "PlaceholderIcon" to DesignColors.PlaceholderIcon,
    "Outline" to DesignColors.Outline,
    "OutlineSoft" to DesignColors.OutlineSoft,
    "Divider" to DesignColors.Divider,
    "SurfaceDim" to DesignColors.SurfaceDim,
    "BadgeScrim" to DesignColors.BadgeScrim,
    "BrandGreen" to DesignColors.BrandGreen,
)
