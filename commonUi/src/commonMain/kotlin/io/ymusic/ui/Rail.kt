package io.ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.PlayableItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.app_name
import io.ymusic.ui.resources.cd_collapse
import io.ymusic.ui.resources.cd_expand
import io.ymusic.ui.resources.cd_next
import io.ymusic.ui.resources.cd_pause
import io.ymusic.ui.resources.cd_play
import io.ymusic.ui.resources.cd_stop_dismiss
import org.jetbrains.compose.resources.stringResource

private val RailMinWindowWidth = 750.dp
private val RailMinWindowHeight = 480.dp
private val RailOpenWindowWidth = 1200.dp

private val RailItemShape = RoundedCornerShape(DesignSpacing.Space8)
private val RailThumbnailShape = RoundedCornerShape(DesignSpacing.Space8)

/** Square on desktop: the native frame behind the window fills the whole tile, so round corners would show it. */
private val RailVideoShape: Shape = if (desktopLayoutHole) RectangleShape else RailThumbnailShape
private val RailCollapsedTile = DesignSpacing.RailCollapsedWidth - DesignSpacing.Space16
private val RailItemTarget = DesignSpacing.Space48

/** The section icons sit 28dp in (8dp list inset + the drawer row's 20dp); less the 8dp gap before the logo. */
private val RailTvLogoInset = 20.dp
private val RailOpenHintSize = DesignSpacing.Space24 + DesignSpacing.Space4
private val RailOpenHintFill = Color.Black.copy(alpha = 0.6f)

/** Landscape phones stay on the drawer: the rail's items would not fit their height. */
internal fun windowUsesRail(width: Dp, height: Dp): Boolean = width >= RailMinWindowWidth && height >= RailMinWindowHeight

/**
 * Width the rail can still take from the content by expanding. Deliberately not snapshot state: it is written in the
 * layout pass right before the content is measured, so a width and its slack are always read as a matching pair.
 */
internal class RailSlack {
    var width: Dp = 0.dp
}

internal val LocalRailSlack = staticCompositionLocalOf { RailSlack() }

/** The content [width] with the rail expanded: size-based layout reads this so expanding never changes a column count. */
@Composable
@ReadOnlyComposable
internal fun railStableWidth(width: Dp): Dp = (width - LocalRailSlack.current.width).coerceAtLeast(0.dp)

/** [GridCells.Adaptive] over [railStableWidth]. */
@Composable
internal fun railStableAdaptive(minSize: Dp): GridCells {
    val slack = LocalRailSlack.current
    return remember(minSize) {
        object : GridCells {
            override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
                val stable = (availableSize - slack.width.roundToPx()).coerceAtLeast(0)
                val columns = ((stable + spacing) / (minSize.roundToPx() + spacing)).coerceAtLeast(1)
                return with(GridCells.Fixed(columns)) { calculateCrossAxisCellSizes(availableSize, spacing) }
            }
        }
    }
}

/** Desktop windows this wide start with the rail expanded. */
internal fun windowStartsRailOpen(width: Dp): Boolean = !platformUsesTouch && width >= RailOpenWindowWidth

/**
 * Desktop keeps the expanded rail beside the content: its picture tile is glass over a native frame behind the
 * window, so content under the rail would show through. Touch windows open it over the content instead.
 */
internal val railBesideContent: Boolean get() = !platformUsesTouch

/** What the rail lists; the same choices the drawer offers. */
internal class RailContent(
    val section: AppSection,
    val sections: List<AppSection>,
    val appVersion: String?,
    val premium: Boolean,
    val updateWaiting: Boolean,
    val onCheckUpdate: (() -> Unit)?,
    val websiteUrl: String,
    val onSelect: (AppSection) -> Unit,
)

/**
 * The navigation shell of tablet and desktop windows: a left rail that replaces the drawer and carries the
 * mini player. [besideContent] keeps the [expanded] rail beside [content]; otherwise it opens over it.
 */
@Composable
internal fun AppRail(
    rail: RailContent,
    besideContent: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)?,
    onExpandPlayer: () -> Unit,
    /** Whether the app bar is lifted: the rail is the same layer, so it lifts with it. */
    chromeElevated: Boolean,
    content: @Composable () -> Unit,
) {
    // A TV opens the rail while it holds focus. It closes only once focus reaches the content: expanding swaps the
    // focused item for its expanded twin, and that momentary loss must not collapse it again.
    var railFocused by remember { mutableStateOf(false) }

    @Suppress("NAME_SHADOWING")
    val expanded = if (platformIsTelevision) railFocused else expanded
    val overlay = expanded && !besideContent
    val reserve = animateDpAsState(railReserve(besideContent, expanded))
    val slack = remember { RailSlack() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .layout { measurable, constraints ->
                    val reservePx = reserve.value.roundToPx()
                    val expandedPx = DesignSpacing.RailExpandedWidth.roundToPx()
                    slack.width = if (besideContent) (expandedPx - reservePx).toDp() else 0.dp
                    val width = (constraints.maxWidth - reservePx).coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(reservePx, 0) }
                }
                .then(if (overlay) Modifier.clearAndSetSemantics {} else Modifier)
                .onFocusChanged { if (it.hasFocus) railFocused = false }
                .focusGroup(),
        ) {
            CompositionLocalProvider(LocalRailSlack provides slack) { content() }
        }
        if (overlay) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = RailScrimAlpha))
                    .pointerInput(Unit) { detectTapGestures { onToggle() } },
            )
        }
        Row(Modifier.fillMaxHeight().onFocusChanged { if (it.hasFocus) railFocused = true }.focusGroup()) {
            // Over the content the rail floats and casts a shadow; beside it, it shares the app bar's plane.
            RailPanel(rail, expanded, chromeTone(chromeElevated || overlay), onToggle, coordinator, surface, onExpandPlayer)
            if (overlay) RailShadow() else VerticalDivider(color = DesignColors.Divider)
        }
    }
}

@Composable
private fun RailShadow() {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier.width(RailShadowReach).fillMaxHeight().drawBehind {
            drawRect(
                Brush.horizontalGradient(
                    *elevationShadowStops(1f),
                    startX = if (rtl) size.width else 0f,
                    endX = if (rtl) 0f else size.width,
                ),
            )
        },
    )
}

private const val RailScrimAlpha = 0.5f
private val RailShadowReach = 12.dp

/** The width the rail takes from the content; an overlay rail keeps only its collapsed width. */
internal fun railReserve(besideContent: Boolean, expanded: Boolean): Dp =
    if (besideContent && expanded) DesignSpacing.RailExpandedWidth else DesignSpacing.RailCollapsedWidth

@Composable
private fun RailPanel(
    rail: RailContent,
    expanded: Boolean,
    background: Color,
    onToggle: () -> Unit,
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)?,
    onExpandPlayer: () -> Unit,
) {
    val width by animateDpAsState(if (expanded) DesignSpacing.RailExpandedWidth else DesignSpacing.RailCollapsedWidth)
    var panel by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tileBounds by remember { mutableStateOf(Rect.Zero) }
    val placeTile: (LayoutCoordinates) -> Unit = { tile ->
        panel?.takeIf { it.isAttached }?.let { tileBounds = it.localBoundingBoxOf(tile, clipBounds = true) }
    }
    Box(
        Modifier.width(width).fillMaxHeight().clipToBounds()
            .onGloballyPositioned { panel = it }
            // On desktop the picture is a native frame behind the window: the tile stays glass.
            .drawBehind {
                clipRect(tileBounds.left, tileBounds.top, tileBounds.right, tileBounds.bottom, ClipOp.Difference) {
                    drawRect(background)
                }
            },
    ) {
        // Laid out at the final width and clipped by the animated panel: the labels must not reflow on the way.
        Column(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight()
                .width(if (expanded) DesignSpacing.RailExpandedWidth else DesignSpacing.RailCollapsedWidth),
        ) {
            val queue by coordinator.player.queue.collectAsState()
            val item = queue.currentItem
            if (expanded) {
                RailHeader(rail, onToggle)
            } else if (!platformIsTelevision) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RailToggle(rail, expanded, onToggle) }
            } else {
                Spacer(Modifier.height(DesignSpacing.Space8))
            }
            RailSections(rail, expanded) {
                if (item != null) {
                    val holePunched = desktopLayoutHole && surface != null && expanded
                    DisposableEffect(holePunched) { onDispose { tileBounds = Rect.Zero } }
                    if (expanded) {
                        val tile = if (holePunched) Modifier.onGloballyPositioned(placeTile) else Modifier
                        RailMiniExpanded(coordinator, item, surface, onExpandPlayer, tile)
                    } else {
                        RailMiniCollapsed(coordinator, item, surface, onExpandPlayer)
                    }
                    HorizontalDivider(color = DesignColors.Divider, modifier = Modifier.padding(vertical = DesignSpacing.Space4))
                }
            }
        }
    }
}

@Composable
private fun RailHeader(rail: RailContent, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RailItemTarget).padding(end = DesignSpacing.Space16),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Without the toggle, the logo lines up with the section icons below.
        if (platformIsTelevision) Spacer(Modifier.width(RailTvLogoInset)) else RailToggle(rail, expanded = true, onToggle)
        AppLogo(DesignSpacing.Space24)
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.app_name),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.InkStrong),
                )
                if (rail.premium) PremiumBadge()
            }
            rail.appVersion?.let {
                Text(
                    it,
                    style = TextStyle(fontSize = 11.sp, color = DesignColors.InkFaint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RailToggle(rail: RailContent, expanded: Boolean, onToggle: () -> Unit) {
    Box(contentAlignment = Alignment.TopEnd) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.a11yActionLabel(stringResource(if (expanded) Res.string.cd_collapse else Res.string.cd_expand)),
        ) {
            Icon(Icons.Default.Menu, null, tint = DesignColors.InkMuted)
        }
        if (rail.updateWaiting && !expanded) {
            Box(
                Modifier.padding(DesignSpacing.Space8).size(DesignSpacing.Space8)
                    .background(DesignColors.Accent, CircleShape),
            )
        }
    }
}

@Composable
private fun ColumnScope.RailMiniCollapsed(
    coordinator: PlaybackCoordinator,
    item: PlayableItem,
    surface: (@Composable (Modifier) -> Unit)?,
    onExpand: () -> Unit,
) {
    val status by coordinator.player.status.collectAsState()
    val progress by coordinator.player.progress.collectAsState()
    val title = item.displayTitle
    Column(
        Modifier.align(Alignment.CenterHorizontally).width(RailCollapsedTile)
            .clickable(onClick = onExpand)
            .semantics { contentDescription = title },
    ) {
        MiniArtTile(
            coordinator,
            item,
            surface,
            onExpand,
            Modifier.size(RailCollapsedTile).clip(RailThumbnailShape),
            coverOnly = true,
        )
        MiniProgress(progress, Modifier.fillMaxWidth().padding(top = DesignSpacing.Space4).height(DesignSpacing.Space2))
    }
    Box(Modifier.align(Alignment.CenterHorizontally)) { RailPlayPause(coordinator, status.showsPause()) }
}

@Composable
private fun RailMiniExpanded(
    coordinator: PlaybackCoordinator,
    item: PlayableItem,
    surface: (@Composable (Modifier) -> Unit)?,
    onExpand: () -> Unit,
    tileModifier: Modifier,
) {
    val status by coordinator.player.status.collectAsState()
    val progress by coordinator.player.progress.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onExpand)) {
            Box(tileModifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RailVideoShape)) {
                MiniArtTile(coordinator, item, surface, onExpand, Modifier.fillMaxSize())
                Icon(
                    Icons.Default.ArrowOutward,
                    null,
                    Modifier.align(Alignment.TopEnd).padding(DesignSpacing.Space4).size(RailOpenHintSize)
                        .background(RailOpenHintFill, CircleShape).padding(DesignSpacing.Space4),
                    tint = Color.White,
                )
            }
            MiniProgress(progress, Modifier.fillMaxWidth().padding(top = DesignSpacing.Space4).height(DesignSpacing.Space2))
            Text(
                item.displayTitle,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = DesignSpacing.Space8),
            )
            artistText(item, status)?.let {
                Text(
                    it,
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RailPlayPause(coordinator, status.showsPause())
            IconButton(
                onClick = { coordinator.player.skipNext() },
                modifier = Modifier.a11yActionLabel(stringResource(Res.string.cd_next)),
            ) {
                Icon(Icons.Default.SkipNext, null, tint = DesignColors.InkMuted)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    coordinator.player.stop()
                    coordinator.player.clearQueue()
                },
                modifier = Modifier.a11yActionLabel(stringResource(Res.string.cd_stop_dismiss)),
            ) {
                Icon(Icons.Default.Close, null, tint = DesignColors.InkMuted)
            }
        }
    }
}

@Composable
private fun RailPlayPause(coordinator: PlaybackCoordinator, showsPause: Boolean) {
    IconButton(
        onClick = { coordinator.player.togglePlay() },
        modifier = Modifier.a11yActionLabel(stringResource(if (showsPause) Res.string.cd_pause else Res.string.cd_play)),
    ) {
        Icon(if (showsPause) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = DesignColors.InkMuted)
    }
}

@Composable
private fun ColumnScope.RailSections(rail: RailContent, expanded: Boolean, header: @Composable ColumnScope.() -> Unit) {
    // An opened rail lands keyboard focus on the current section, as the drawer does.
    val activeItemFocus = remember { FocusRequester() }
    LaunchedEffect(expanded) {
        if (expanded && rail.section in rail.sections) activeItemFocus.requestFocus()
    }
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).listKeyboardNavigation()
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        header()
        rail.sections.groupBy { it.group }.entries.forEachIndexed { index, (_, sections) ->
            if (index > 0) {
                HorizontalDivider(color = DesignColors.Divider, modifier = Modifier.padding(vertical = DesignSpacing.Space4))
            }
            sections.forEach { section ->
                val active = section == rail.section
                val focus = if (active) activeItemFocus else null
                if (expanded) {
                    DrawerItem(section, active, focus, RailItemShape) { rail.onSelect(section) }
                } else {
                    RailIconItem(section, active, focus) { rail.onSelect(section) }
                }
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().padding(start = DesignSpacing.Space8)) {
                DrawerLinks(rail.onCheckUpdate, rail.updateWaiting, rail.websiteUrl)
            }
        }
    }
}

@Composable
private fun RailIconItem(section: AppSection, active: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(section.label)
    Box(
        Modifier.size(RailItemTarget)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RailItemShape)
            .clickable(onClick = onClick)
            .semantics {
                selected = active
                contentDescription = label
            }
            .background(if (active) scheme.primaryContainer else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(section.icon, null, Modifier.size(DesignSpacing.Space24), tint = if (active) scheme.onPrimaryContainer else DesignColors.Ink)
    }
}
