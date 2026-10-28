package io.ymusic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.ymusic.dynamicengine.yt.YtPageTab
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_back
import io.ymusic.ui.resources.cd_clear
import io.ymusic.ui.resources.cd_filter
import io.ymusic.ui.resources.cd_menu
import io.ymusic.ui.resources.cd_more_tabs
import io.ymusic.ui.resources.cd_search
import io.ymusic.ui.resources.search_youtube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.exp

/*
 * The ONE app-bar language, every scenario (see the "App bars" catalogue group):
 *  - [DesignAppBar]        home shell: menu · title · [inline tabs] · search
 *  - [SearchInputBar]      search input: back · pill text field · clear
 *  - [SearchResultsBar]    search results: back · query · [filter] · search
 *  - [DetailAppBar]        detail pages: back · title · search (+ e.g. [ChannelTabRow] riding it)
 * All bars share [AppBarRow] metrics + [AppBarTitle] type; screens host them in (or as)
 * [ElevatedBarColumn] so the lift behavior is identical everywhere.
 */

/**
 * The shell's scroll→chrome bridge. Elevation is the *shell's* decision, so content never touches the
 * app bar: the active screen's scroll container REPORTS its position with [TrackScroll], and the bar
 * ([ElevatedBarColumn]) READS [scrolled] to lift. Owned and provided by the app shell.
 */
@Stable
internal class ScrollHost(scrolled: Boolean = false) {
    private val state = mutableStateOf(scrolled)

    /** True while the active content is scrolled away from its top — read by the bar. */
    val scrolled: Boolean get() = state.value

    // Only the most-recently-attached content drives [scrolled]; the token guard stops a departing
    // screen from clobbering the one that replaced it (dispose can race the new claim).
    private var owner: Any? = null
    fun claim(token: Any) { owner = token }
    fun report(token: Any, value: Boolean) { if (owner === token) state.value = value }
    fun release(token: Any) { if (owner === token) { owner = null; state.value = false } }
}

/** The app shell's [ScrollHost]; null outside the shell. */
internal val LocalScrollHost = compositionLocalOf<ScrollHost?> { null }

/** Opens the app's navigation drawer; null outside the shell. Lets content (e.g. the Library tab
 *  switcher's leading-edge swipe) trigger the drawer without holding its state. */
internal val LocalOpenNavDrawer = compositionLocalOf<(() -> Unit)?> { null }

/** Report whether this screen's primary scroll container is scrolled away from its top, so the shell
 *  can lift the app bar. Resets when the content leaves composition. Content calls this and stays
 *  ignorant of the app bar — it only states a fact about its own scroll. */
@Composable
internal fun TrackScroll(scrolled: () -> Boolean) {
    val host = LocalScrollHost.current ?: return
    val token = remember { Any() }
    DisposableEffect(host, token) {
        host.claim(token)
        onDispose { host.release(token) }
    }
    LaunchedEffect(host, token) { snapshotFlow(scrolled).collect { host.report(token, it) } }
}

/** An app-bar surface that lifts while the screen under it is scrolled: shadow + the elevated
 *  tone (elevated white ≠ white). Drawn above siblings so the shadow is never painted over.
 *  [bottomShadow] = false keeps the tone but drops the shadow, for when another elevated surface
 *  continues directly below (e.g. the Library bar with the tab row beneath it) and should cast the
 *  single shadow itself. */
@Composable
internal fun ElevatedBarColumn(bottomShadow: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val elevated = LocalScrollHost.current?.scrolled == true
    // The shadow is PAINTED (a gradient scrim below the bar), not Modifier.shadow — that blur is
    // swallowed in the Scaffold topBar slot. The stops approximate the 6dp elevation blur the
    // ElevatedBarDialog bars cast (soft peak, gaussian-ish falloff), so every lifted bar in the
    // app reads identically.
    val shadowAlpha by animateFloatAsState(if (elevated && bottomShadow) 1f else 0f)
    val tone by animateColorAsState(if (elevated) DesignColors.SurfaceElevated else DesignColors.Surface)
    Column(
        Modifier
            .zIndex(1f)
            .drawBehind {
                if (shadowAlpha > 0f) {
                    val reach = 12.dp.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.20f * shadowAlpha),
                            0.4f to Color.Black.copy(alpha = 0.07f * shadowAlpha),
                            1f to Color.Transparent,
                            startY = size.height,
                            endY = size.height + reach,
                        ),
                        topLeft = Offset(0f, size.height),
                        size = Size(size.width, reach),
                    )
                }
            }
            .background(tone),
        content = content,
    )
}

/**
 * The shared section tab row (design: a 2dp accent underline that slides AND stretches between tabs).
 * A single indicator reads each tab's (offset, width) via [onGloballyPositioned] and animates, so it
 * glides rather than snapping; [dragProgress] (−1..1 from the content swipe) leans it toward the
 * neighbour in the drag direction. The row scrolls when the tabs overflow. [label] renders one tab's
 * content (text or icon) given its active state; a null [selected] draws no underline. Sit it inside an [ElevatedBarColumn] + a
 * [HorizontalDivider] so it reads as one continuous surface with the app bar (see Library / YT Music).
 */
@Composable
internal fun <T : Any> UnderlineTabRow(
    selected: T?,
    entries: List<T>,
    dragProgress: Float,
    onSelect: (T) -> Unit,
    label: @Composable (tab: T, active: Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Each tab's (offset, width) in the row's own space — the sliding indicator reads the selected one.
    val bounds = remember(rtl) { mutableStateMapOf<T, Pair<Dp, Dp>>() }
    // Focus the current tab on first composition so keyboard users land on it on screen entry.
    val activeTabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { activeTabFocus.requestFocus() } }
    HorizontalOverflowBox(scrollState) {
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalWheelScroll(scrollState)
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp),
        ) {
            Row(
                Modifier.listKeyboardNavigation(ListOrientation.Horizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEach { t ->
                    val active = t == selected
                    Box(
                        Modifier
                            .onGloballyPositioned { c ->
                                val parent = c.parentLayoutCoordinates
                                val left = parent?.localPositionOf(c)?.x ?: 0f
                                // Start-relative, as Modifier.offset mirrors in RTL.
                                val x = if (rtl && parent != null) parent.size.width - left - c.size.width else left
                                bounds[t] = with(density) { x.toDp() to c.size.width.toDp() }
                            }
                            .then(if (active) Modifier.focusRequester(activeTabFocus) else Modifier)
                            .semantics {
                                this.selected = active
                                role = Role.Tab
                            }
                            .clickable { onSelect(t) },
                    ) { label(t, active) }
                }
            }
            // Interpolate toward the neighbour in the drag direction so the underline follows the swipe;
            // at rest (dragProgress 0) it sits on the selected tab. animateDpAsState smooths the
            // hand-off when the drag releases or commits.
            val cur = selected?.let { bounds[it] }
            val i = entries.indexOf(selected)
            val neighbor = bounds[entries[(i + if (dragProgress > 0f) 1 else -1).coerceIn(0, entries.lastIndex)]]
            val frac = abs(dragProgress) * TabIndicatorDragTravel
            val targetX = if (cur != null && neighbor != null) lerp(cur.first, neighbor.first, frac) else cur?.first ?: 0.dp
            val targetWidth = if (cur != null && neighbor != null) lerp(cur.second, neighbor.second, frac) else cur?.second ?: 0.dp
            val indicatorX by animateDpAsState(targetX, label = "tabIndicatorX")
            val indicatorWidth by animateDpAsState(targetWidth, label = "tabIndicatorWidth")
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(indicatorWidth)
                    .height(2.dp)
                    .background(DesignColors.Accent),
            )
        }
    }
}

/** A text tab label for [UnderlineTabRow] — accent + semibold when active, faint otherwise. */
@Composable
internal fun TabLabel(text: String, active: Boolean) {
    Text(
        text,
        style = TextStyle(
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) DesignColors.Accent else DesignColors.InkFaint,
        ),
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

/** The bars' shared metrics row. Horizontal paddings/gaps shave the ActionIcon hit-inset (8dp) so
 *  the glyphs keep the mockups' 16dp margins; vertical paddings are the mockup values unshaved
 *  (5/7dp, top +1 standing in for the under-status-bar margin) because the title and the search
 *  pill carry no inset — shaving the bottom made non-icon content sit visibly low in the bar. */
@Composable
internal fun AppBarRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The bar-title text role; [fill] claims the remaining row width (off when inline content follows).
 *  [reserveWidthOf] pads the slot to the widest of these labels so trailing inline content keeps a
 *  fixed position while the title text changes underneath it. */
@Composable
internal fun RowScope.AppBarTitle(text: String, fill: Boolean = true, reserveWidthOf: List<String> = emptyList()) {
    val style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink)
    val modifier = if (fill) Modifier.weight(1f) else Modifier
    if (reserveWidthOf.isEmpty()) {
        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            for (label in reserveWidthOf) {
                Text(label, style = style, maxLines = 1, modifier = Modifier.alpha(0f).clearAndSetSemantics {})
            }
            Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The home shell's app bar (design/final/home.html): menu · title · [inline tabs on landscape] ·
 *  search. Bare (no elevation wrap) — the shell's ElevatedBarColumn carries it plus the tab row. */
@Composable
internal fun DesignAppBar(
    title: String,
    onMenu: () -> Unit,
    onSearch: (() -> Unit)?,
    titleReserveWidthOf: List<String> = emptyList(),
    inlineContent: (@Composable () -> Unit)? = null,
) {
    AppBarRow {
        ActionIcon(Icons.Default.Menu, stringResource(Res.string.cd_menu), onClick = onMenu)
        AppBarTitle(title, fill = false, reserveWidthOf = titleReserveWidthOf)
        if (inlineContent != null) {
            Box(Modifier.weight(1f).padding(start = 16.dp)) { inlineContent() }
        } else {
            Box(Modifier.weight(1f))
        }
        if (onSearch != null) {
            ActionIcon(Icons.Default.Search, stringResource(Res.string.cd_search), onClick = onSearch)
        }
    }
}

/** The search input bar: back · pill field (`.sfield`: 40dp gray pill, 15sp text, clear ✕ while
 *  non-empty). [autoFocus] off for showcase hosting (the catalogue). */
@Composable
internal fun SearchInputBar(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean = true,
    placeholder: String = stringResource(Res.string.search_youtube),
) = ElevatedBarColumn {
    val focusRequester = remember { FocusRequester() }
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(DesignColors.FieldPill)
                // The ✕'s own 8dp inset is the trailing padding while it shows.
                .padding(start = 16.dp, end = if (value.text.isEmpty()) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = DesignColors.Ink),
                cursorBrush = SolidColor(DesignColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(value.text) }),
                modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                placeholder,
                                style = TextStyle(fontSize = 15.sp, color = DesignColors.InkFaint),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.text.isNotEmpty()) {
                ActionIcon(
                    Icons.Default.Close,
                    stringResource(Res.string.cd_clear),
                    tint = DesignColors.InkSecondary,
                    onClick = { onValue(TextFieldValue("")) },
                )
            }
        }
    }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

/** The search results bar: back · submitted query · [filter] · search. */
@Composable
internal fun SearchResultsBar(
    title: String,
    onBack: () -> Unit,
    showFilter: Boolean,
    onFilter: () -> Unit,
    onSearch: () -> Unit,
) = ElevatedBarColumn {
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        AppBarTitle(title)
        if (showFilter) {
            ActionIcon(Icons.Outlined.FilterAlt, stringResource(Res.string.cd_filter), onClick = onFilter)
        }
        ActionIcon(Icons.Default.Search, stringResource(Res.string.cd_search), onClick = onSearch)
    }
}

/** The detail pages' bar: back · title · search; [bottomContent] (the channel tab row) rides
 *  inside the same lifted surface so the scroll shadow falls below it. */
@Composable
internal fun DetailAppBar(
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    bottomContent: (@Composable () -> Unit)? = null,
) = ElevatedBarColumn {
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        AppBarTitle(title)
        ActionIcon(Icons.Default.Search, stringResource(Res.string.cd_search), onClick = onSearch)
    }
    bottomContent?.invoke()
}

/** A channel's tabs in the shared [UnderlineTabRow] style. */
@Composable
internal fun ChannelTabRow(tabs: List<YtPageTab>, activeUrl: String?, onSelect: (YtPageTab) -> Unit) {
    // The page was opened by a link that matches a tab → highlight it; opened by any other link
    // (e.g. a bare channel item) → fall back to the server's selected flag.
    val selected = tabs.firstOrNull { it.url != null && it.url == activeUrl } ?: tabs.firstOrNull { it.selected }
    UnderlineTabRow(selected, tabs, dragProgress = 0f, onSelect) { tab, active ->
        TabLabel(tab.title, active)
    }
}

/** Hosts a `horizontalScroll(scrollState)` row and overlays the cut-off side(s) with the fade +
 *  chevron paging affordance (desktop mice can't drag a scrollable row). */
@Composable
internal fun HorizontalOverflowBox(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        content()
        if (scrollState.maxValue > 0 && scrollState.value > 0) {
            TabOverflowHint(
                towardStart = true,
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { scope.launch { scrollState.animateScrollBy(-TabScrollStep) } },
            )
        }
        if (scrollState.maxValue > 0 && scrollState.value < scrollState.maxValue) {
            TabOverflowHint(
                towardStart = false,
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = { scope.launch { scrollState.animateScrollBy(TabScrollStep) } },
            )
        }
    }
}

private const val TabScrollStep = 240f

/** Redirect the vertical mouse wheel to a horizontal [scrollState] — desktop wheels scroll vertically,
 *  so a horizontally-scrolling row (tabs, chips) would otherwise ignore them. Works on any
 *  [ScrollableState] (ScrollState or a LazyRow's LazyListState). No-op on touch, which produces drags
 *  rather than scroll events. */
internal fun Modifier.horizontalWheelScroll(scrollState: ScrollableState): Modifier =
    pointerInput(scrollState) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f && scrollState.dispatchRawDelta(dy * WheelScrollStep) != 0f) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }

private const val WheelScrollStep = 64f

/** Mouse click-drag scrolls a horizontal [scrollState] — desktop scrollables take touch drags only,
 *  so a mouse drag would otherwise fall through to the parent (Discover's tab swipe). Touch stays
 *  with the row's own scrolling. */
internal fun Modifier.horizontalMouseDragScroll(scrollState: ScrollableState): Modifier = composed {
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    pointerInput(scrollState, sign) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                scrollState.dispatchRawDelta(sign * over)
            } ?: return@awaitEachGesture
            horizontalDrag(drag.id) { change ->
                scrollState.dispatchRawDelta(sign * change.positionChange().x)
                change.consume()
            }
        }
    }
}

/**
 * Swipe left/right on the body to step to the adjacent entry, with a slide animation — a lightweight
 * alternative to HorizontalPager that composes ONLY the current page. A pager keeps its neighbours
 * alive and eagerly loads their data (memory + jank the user may never see); this composes the outgoing
 * page only for the ~[SwipeSwitchDurationMs] of the transition, then drops it.
 *
 * A horizontal drag past [SwipeSwitchDistance], or a flick past [SwipeSwitchVelocity], commits the
 * switch; a mostly-vertical drag never crosses horizontal slop, so the scrolling body keeps it. RTL
 * mirrors both the trigger direction and the slide. It's a discrete gesture (no 1:1 finger tracking) —
 * that's the trade for not rendering neighbours.
 */
@Composable
internal fun <T> SwipeTabSwitcher(
    selected: T,
    entries: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // Live drag progress in [-1, 1]: +1 = fully toward the next entry, -1 = fully toward the previous;
    // 0 at rest. Lets an external indicator (the tab underline) track the drag, then reset on release.
    onDragProgress: (Float) -> Unit = {},
    // Width of the start-edge zone where a drag toward centre opens the nav drawer (via
    // [onLeadingEdgeSwipe]) instead of switching tabs. Sits just inside the OS back-gesture inset.
    leadingEdgeReserve: Dp = 0.dp,
    // Invoked when a drag begins in the leading-edge zone and heads toward centre (open the drawer).
    onLeadingEdgeSwipe: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl
    val density = LocalDensity.current
    // Yield the system's back-gesture zone at BOTH edges (its full reserved width). Just inside the
    // start edge, carve a drawer zone where a drag opens the nav drawer.
    val gestures = WindowInsets.systemGestures
    val backLeft = gestures.getLeft(density, layoutDirection).toFloat()
    val backRight = gestures.getRight(density, layoutDirection).toFloat()
    // Start (leading) side holds the drawer; end (trailing) side is left to the OS back gesture.
    val backStart = if (rtl) backRight else backLeft
    val backEnd = if (rtl) backLeft else backRight
    // Drawer zone spans from the physical start edge inward, past the OS inset (the OS claims the very
    // edge on gesture-nav; on 3-button nav there's no inset, so this whole zone is the app's).
    val drawerZone = if (onLeadingEdgeSwipe != null) backStart + with(density) { leadingEdgeReserve.toPx() } else 0f
    // Live feedback: the page rubber-bands toward the drag so the gesture is visible under the finger,
    // then springs back if you release short of the commit threshold. Only the current page moves —
    // still nothing rendered on the side.
    val peek = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(selected, entries, rtl, backStart, backEnd, drawerZone, onLeadingEdgeSwipe) {
                val distance = SwipeSwitchDistance.toPx()
                val velocity = SwipeSwitchVelocity.toPx()
                val k = RubberPeekStiffness.toPx()

                // Report progress (for the indicator) + rubber-band the page from the total drag.
                fun reportDrag(dx: Float) {
                    onDragProgress(((if (rtl) dx else -dx) / distance).coerceIn(-1f, 1f))
                    val forward = if (rtl) dx > 0 else dx < 0
                    val hasNext = (entries.indexOf(selected) + if (forward) 1 else -1) in entries.indices
                    val stiffness = if (hasNext) k else k * 0.3f
                    scope.launch { peek.snapTo(weightedRubberPeek(dx, stiffness)) }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val x = down.position.x
                    val distFromStart = if (rtl) size.width - x else x
                    val distFromEnd = if (rtl) x else size.width - x
                    // Trailing edge → leave it to the OS back gesture.
                    if (distFromEnd < backEnd) return@awaitEachGesture
                    // Leading (drawer) edge → a drag toward centre opens the nav drawer.
                    if (drawerZone > 0f && distFromStart < drawerZone) {
                        var over = 0f
                        val past = awaitHorizontalTouchSlopOrCancellation(down.id) { change, o -> over = o; change.consume() }
                        // Toward centre = away from the start edge: rightward in LTR, leftward in RTL.
                        if (past != null && (if (rtl) over < 0 else over > 0)) onLeadingEdgeSwipe?.invoke()
                        return@awaitEachGesture
                    }
                    // No drawer here → still reserve the leading OS back inset.
                    if (drawerZone == 0f && distFromStart < backStart) return@awaitEachGesture
                    var dx = 0f
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val past = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        dx += over
                        tracker.addPosition(change.uptimeMillis, change.position)
                        reportDrag(dx)
                        change.consume()
                    }
                    if (past == null) return@awaitEachGesture // canceled before slop (tap / vertical scroll won)
                    val completed = horizontalDrag(down.id) { change ->
                        dx += change.positionChange().x
                        tracker.addPosition(change.uptimeMillis, change.position)
                        reportDrag(dx)
                        change.consume()
                    }
                    if (completed) {
                        val flung = abs(tracker.calculateVelocity().x) >= velocity
                        // Swipe left (dx < 0) advances to the next entry; RTL flips it.
                        val advance = if (rtl) dx > 0 else dx < 0
                        val next = entries.indexOf(selected) + if (advance) 1 else -1
                        if ((abs(dx) >= distance || flung) && next in entries.indices) onSelect(entries[next])
                    }
                    onDragProgress(0f)
                    // On commit the slide takes over from here; either way settle the peek back to rest.
                    scope.launch { peek.animateTo(0f, PeekSpring) }
                }
            },
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                // The new page enters from the side you're heading toward; the old exits the other way.
                val advancing = entries.indexOf(targetState) > entries.indexOf(initialState)
                val sign = if (advancing != rtl) 1 else -1
                slideInHorizontally(tween(SwipeSwitchDurationMs)) { w -> sign * w }
                    .togetherWith(slideOutHorizontally(tween(SwipeSwitchDurationMs)) { w -> -sign * w })
            },
            label = "tab",
            modifier = Modifier.graphicsLayer { translationX = peek.value },
        ) { current -> content(current) }
    }
}

/** Fraction of the way to the neighbour a tab underline leans while dragging (the rest lands on commit). */
internal const val TabIndicatorDragTravel = 0.2f

internal val SwipeSwitchDistance = 56.dp
internal val SwipeSwitchVelocity = 400.dp // per second
internal const val SwipeSwitchDurationMs = 220
/** Curve scale for tab + Now Playing rubber-band: slope starts at 1 and eases off. */
private val RubberPeekStiffness = 48.dp

/** Signed `k (1 - e^{-|raw|/k})`. */
private fun weightedRubberPeek(rawPx: Float, k: Float): Float {
    if (rawPx == 0f || k <= 0f) return 0f
    val s = if (rawPx > 0f) 1f else -1f
    return s * k * (1f - exp(-abs(rawPx) / k))
}

/** Width of the start-edge zone that opens the nav drawer instead of switching tabs (on top of the
 *  system back-gesture inset). Passed as SwipeTabSwitcher's leadingEdgeReserve on drawer screens. */
internal val NavDrawerEdgeReserve = 24.dp
internal val PeekSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/** Finger travel that commits a Now Playing dismiss (heavier than the tab-switch 56.dp). */
private val DismissDragDistance = 120.dp

/**
 * Same rubber-band as [SwipeTabSwitcher], vertical: the page peeks down under the finger, then
 * either springs back or slides off and [onDismiss]s. Only a real touch drag counts — a flying
 * queue fling that hits the top must not pull the sheet. Nested scroll at the top of a list and
 * a downward drag on non-scrolling chrome (top bar, video) both work.
 */
@Composable
internal fun SwipeDownDismissBox(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** When set, visual max is this fraction of height; release at half of that commits. */
    commitFractionOfHeight: Float? = null,
    /** True while a downward pull is in progress (so a parent clickable does not fire). */
    onPull: (Boolean) -> Unit = {},
    /** Mini-player overlay keeps a thin top shadow at rest; Now Playing only while pulling. */
    restingShadow: Boolean = false,
    /** Visual translation in px; parent uses this to draw the shell only while the sheet is peeking. */
    onPeek: (Float) -> Unit = {},
    /** Off on desktop: a peek would show the transparent video hole, not the shell. */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val peek = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember { DragDown(peek, scope) }
    drag.onDismiss = onDismiss
    drag.onPull = onPull
    drag.stiffnessPx = with(density) { RubberPeekStiffness.toPx() }
    val defaultCommit = with(density) { DismissDragDistance.toPx() }
    drag.commitPx = defaultCommit
    val peekY = peek.value
    SideEffect { onPeek(peekY) }
    val fullPeek = (
        if (drag.maxMovePx > 0f) drag.maxMovePx
        else weightedRubberPeek(drag.commitPx, drag.stiffnessPx)
        ).coerceAtLeast(1f)
    val topShadow = (peekY / fullPeek).coerceIn(0f, 1f)
    val shadowAlpha = if (restingShadow) 0.10f + 0.08f * topShadow else 0.16f * topShadow
    val showShadow = restingShadow || topShadow > 0.01f

    val gestures = if (enabled) {
        Modifier
            .onSizeChanged {
                drag.heightPx = it.height.toFloat()
                val frac = commitFractionOfHeight
                if (frac != null && it.height > 0) {
                    val px = it.height.toFloat() * frac
                    drag.maxMovePx = px
                    drag.commitPx = px * 0.5f
                } else {
                    drag.commitPx = defaultCommit
                    drag.maxMovePx = 0f
                }
            }
            .nestedScroll(drag.connection)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        if (amount > 0f || drag.raw > 0f) {
                            change.consume()
                            drag.report(amount)
                        }
                    },
                    onDragEnd = { if (drag.raw > 0f) drag.settle() },
                    onDragCancel = { if (drag.raw > 0f) drag.settle() },
                )
            }
    } else {
        Modifier
    }
    Box(modifier.then(gestures)) {
        Box(Modifier.fillMaxWidth().graphicsLayer { translationY = peekY }) {
            if (showShadow) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(y = (-8).dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = shadowAlpha),
                            ),
                        ),
                )
            }
            content()
        }
    }
}

private class DragDown(
    private val peek: Animatable<Float, *>,
    private val scope: CoroutineScope,
) {
    var raw = 0f
    var heightPx = 0f
    var stiffnessPx = 0f
    var commitPx = 0f
    /** Visual ceiling; 0 = Now Playing exponential (no hard max). */
    var maxMovePx = 0f
    var onDismiss: () -> Unit = {}
    var onPull: (Boolean) -> Unit = {}
    private var pulling = false

    fun report(deltaY: Float) {
        raw = (raw + deltaY).coerceAtLeast(0f)
        if (!pulling) {
            pulling = true
            onPull(true)
        }
        val cap = maxMovePx
        val target = if (cap > 0f) {
            /* t uses 4×cap so g'(0)=1 for 1-(1-t)^4. */
            val t = (raw / (4f * cap)).coerceIn(0f, 1f)
            val u = 1f - t
            cap * (1f - u * u * u * u)
        } else {
            weightedRubberPeek(raw, stiffnessPx)
        }
        scope.launch { peek.snapTo(target) }
    }

    /** Commit only after the full finger distance — no flick shortcut. */
    fun settle() {
        val cap = maxMovePx
        val commit = raw >= commitPx || (cap > 0f && peek.value >= cap * 0.5f)
        scope.launch {
            try {
                if (commit) {
                    val end = heightPx.takeIf { it > 0f } ?: (peek.value + 400f)
                    peek.animateTo(end, tween(SwipeSwitchDurationMs))
                    onDismiss()
                } else {
                    raw = 0f
                    peek.animateTo(0f, PeekSpring)
                }
            } finally {
                pulling = false
                onPull(false)
            }
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!source.isTouchDrag() || raw <= 0f || available.y >= 0f) return Offset.Zero
            val before = raw
            report(available.y)
            return Offset(0f, raw - before)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!source.isTouchDrag() || available.y <= 0f) return Offset.Zero
            report(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (raw > 0f || peek.value > 0f) {
                /* Finger already left — commit only if the drag itself passed the threshold,
                   not because leftover list fling velocity is large. */
                settle()
                return available
            }
            return Velocity.Zero
        }
    }
}

private fun NestedScrollSource.isTouchDrag(): Boolean = this == NestedScrollSource.UserInput

/** The cut-off side's affordance: a fade to the surface color under a chevron. */
@Composable
private fun TabOverflowHint(towardStart: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val surface = DesignColors.Surface
    val colors = listOf(surface, surface.copy(alpha = 0f))
    Box(
        modifier
            .fillMaxHeight()
            .background(Brush.horizontalGradient(if (towardStart) colors else colors.reversed()))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (towardStart) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
            stringResource(Res.string.cd_more_tabs),
            Modifier.size(20.dp),
            tint = DesignColors.InkSoft,
        )
    }
}
