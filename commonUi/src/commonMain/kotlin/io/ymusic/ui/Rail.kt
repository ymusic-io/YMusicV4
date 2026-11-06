package io.ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import io.ymusic.ui.resources.ymusic_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val RailMinWindowWidth = 600.dp
private val RailMinWindowHeight = 480.dp
private val RailPinnedWindowWidth = 1200.dp

private val RailItemShape = RoundedCornerShape(DesignSpacing.Space8)
private val RailThumbnailShape = RoundedCornerShape(DesignSpacing.Space8)

/** Square on desktop: the native frame behind the window fills the whole tile, so round corners would show it. */
private val RailVideoShape: Shape = if (desktopLayoutHole) RectangleShape else RailThumbnailShape
private val RailCollapsedTile = DesignSpacing.RailCollapsedWidth - DesignSpacing.Space16
private val RailItemTarget = DesignSpacing.Space48
private val RailOpenHintSize = DesignSpacing.Space24 + DesignSpacing.Space4
private val RailOpenHintFill = Color.Black.copy(alpha = 0.6f)

/** Landscape phones stay on the drawer: the rail's items would not fit their height. */
internal fun windowUsesRail(width: Dp, height: Dp): Boolean = width >= RailMinWindowWidth && height >= RailMinWindowHeight

/** Wide desktop windows keep the expanded rail beside the content; elsewhere it opens over the content. */
internal fun windowPinsRail(width: Dp): Boolean = !platformUsesTouch && width >= RailPinnedWindowWidth

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
 * mini player. [pinned] keeps the [expanded] rail beside [content]; otherwise it opens over it.
 */
@Composable
internal fun AppRail(
    rail: RailContent,
    pinned: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)?,
    onExpandPlayer: () -> Unit,
    content: @Composable () -> Unit,
) {
    val overlay = expanded && !pinned
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .padding(start = railReserve(pinned, expanded))
                // Desktop screen readers hit-test by point: a cleared node here would shadow the rail.
                .then(if (overlay && platformUsesTouch) Modifier.clearAndSetSemantics {} else Modifier),
        ) { content() }
        if (overlay) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = RailScrimAlpha))
                    .pointerInput(Unit) { detectTapGestures { onToggle() } },
            )
        }
        RailPanel(rail, expanded, onToggle, coordinator, surface, onExpandPlayer)
    }
}

private const val RailScrimAlpha = 0.5f

/** The width the rail takes from the content; an overlay rail keeps only its collapsed width. */
internal fun railReserve(pinned: Boolean, expanded: Boolean): Dp =
    if (pinned && expanded) DesignSpacing.RailExpandedWidth else DesignSpacing.RailCollapsedWidth

@Composable
private fun RailPanel(
    rail: RailContent,
    expanded: Boolean,
    onToggle: () -> Unit,
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)?,
    onExpandPlayer: () -> Unit,
) {
    val width by animateDpAsState(if (expanded) DesignSpacing.RailExpandedWidth else DesignSpacing.RailCollapsedWidth)
    val background = DesignColors.SurfaceDim
    var panel by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tileBounds by remember { mutableStateOf(Rect.Zero) }
    val placeTile: (LayoutCoordinates) -> Unit = { tile ->
        panel?.takeIf { it.isAttached }?.let { tileBounds = it.localBoundingBoxOf(tile) }
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
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RailToggle(rail, expanded, onToggle) }
            }
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
            RailSections(rail, expanded)
        }
    }
}

@Composable
private fun RailHeader(rail: RailContent, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(end = DesignSpacing.Space16),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailToggle(rail, expanded = true, onToggle)
        Image(
            painterResource(Res.drawable.ymusic_logo),
            contentDescription = null,
            modifier = Modifier.size(DesignSpacing.Space24).clip(RailItemShape),
        )
        Text(
            stringResource(Res.string.app_name),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.InkStrong),
        )
        if (rail.premium) PremiumBadge()
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
    Column(Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space8)) {
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
private fun ColumnScope.RailSections(rail: RailContent, expanded: Boolean) {
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
