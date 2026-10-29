package io.ymusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.download.StillProcessingException
import io.ymusic.dynamicengine.yt.YtChip
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtSection
import io.ymusic.dynamicengine.yt.YtSectionHeader
import io.ymusic.dynamicengine.yt.subscribeChannel
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_download
import io.ymusic.ui.resources.cd_share
import io.ymusic.ui.resources.cd_sort
import io.ymusic.ui.resources.error_captcha
import io.ymusic.ui.resources.error_disk_full
import io.ymusic.ui.resources.error_generic
import io.ymusic.ui.resources.error_live_still_processing
import io.ymusic.ui.resources.error_network
import io.ymusic.ui.resources.error_playlist_backup_invalid
import io.ymusic.ui.resources.error_playlist_backup_newer
import io.ymusic.ui.resources.error_supervised_account
import io.ymusic.ui.resources.feed_empty
import io.ymusic.ui.resources.menu_add_to_playlist
import io.ymusic.ui.resources.menu_play_single
import io.ymusic.ui.resources.menu_remove
import io.ymusic.ui.resources.menu_remove_from_playlist
import io.ymusic.ui.resources.menu_start_radio
import io.ymusic.ui.resources.queue_last
import io.ymusic.ui.resources.queue_next
import io.ymusic.ui.resources.quick_download_menu
import io.ymusic.ui.resources.reload
import io.ymusic.ui.resources.retry
import io.ymusic.ui.resources.show_less
import io.ymusic.ui.resources.show_more
import io.ymusic.ui.resources.show_more_n
import io.ymusic.ui.resources.subscribe
import io.ymusic.ui.resources.subscribed
import io.ymusic.ui.resources.unsubscribe
import io.ymusic.ui.resources.unsubscribed
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.DISK_FULL_CODE
import projectcommon.error.errorCode

/** Paged feed loading over any yt page source (browse / search / watch-next continuations).
 *  [fetchTimeoutMs] is the watchdog for a wedged engine (seen live: Android CookieManager deadlocking
 *  against WebView browser-process startup) — a Failed page with Retry instead of an eternal spinner.
 *  It must outlast the HTTP execute timeout (10s), so a plain network stall fails first as the
 *  network error, and [TIMEOUT_CODE] means the engine itself hung. [reloadFetch] serves a user
 *  [reload] when the surface must bypass the engine's held session (null = [fetch]). */
class Pager(
    private val scope: CoroutineScope,
    private val fetchTimeoutMs: Long = 15_000,
    private val logger: Logger? = null,
    private val reloadFetch: (suspend (continuation: String?) -> io.ymusic.dynamicengine.yt.YtFeedPage)? = null,
    private val fetch: suspend (continuation: String?) -> io.ymusic.dynamicengine.yt.YtFeedPage,
) {
    sealed interface State {
        data object Loading : State

        data class Failed(val code: String, val detail: String?) : State

        data class Ready(
            val sections: List<YtSection>,
            val chips: List<YtChip>,
            val selectedChipToken: String?,
            /** The page's sort menu (music library): option tokens are server continuations. */
            val sort: List<YtChip> = emptyList(),
            /** Engine layout intent: wrap the chip row instead of scrolling it (YtFeedPage.chipsWrap). */
            val chipsWrap: Boolean = false,
            /** The source's own empty-state explainer (YtFeedPage.emptyTitle/emptyMessage) —
             *  YouTube's localized words, shown over the app's generic empty text when present. */
            val emptyTitle: String? = null,
            val emptyMessage: String? = null,
            val continuation: String?,
            val loadingMore: Boolean,
            /** Pull-to-refresh in flight: the list stays on screen, only the pull indicator spins. */
            val refreshing: Boolean = false,
            /** Search surfaces only: the server's filter submenu (empty elsewhere). */
            val filterGroups: List<io.ymusic.dynamicengine.yt.YtFilterGroup> = emptyList(),
            /** Detail surfaces only (channel / playlist browse). */
            val pageHeader: io.ymusic.dynamicengine.yt.YtPageHeader? = null,
            val pageTabs: List<io.ymusic.dynamicengine.yt.YtPageTab> = emptyList(),
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Error codes from a keep-content [reload] (the list stays put) or a page's failed part — the UI shows a snackbar. */
    private val _errors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: kotlinx.coroutines.flow.SharedFlow<String> = _errors.asSharedFlow()

    private var job: Job? = null

    /** (Re)load from the top; [chipToken] re-requests the feed filtered by that chip. */
    fun refresh(chipToken: String? = null) = load(chipToken, fetch)

    private fun load(
        chipToken: String?,
        source: suspend (continuation: String?) -> io.ymusic.dynamicengine.yt.YtFeedPage,
    ) {
        job?.cancel()
        // A CHIP-CLICKED page often carries no chip row/filters of its own — keep the ones we came
        // from. A plain refresh is a new surface (a channel tab switch): stale chips must NOT leak
        // into it, or a Playlists tab shows the Videos tab's sort chips and replays their tokens.
        val carryOver = chipToken != null
        val previous = if (carryOver) _state.value as? State.Ready else null
        val previousChips = previous?.chips.orEmpty()
        val previousSort = previous?.sort.orEmpty()
        val previousWrap = previous?.chipsWrap == true
        val previousFilters = previous?.filterGroups.orEmpty()
        _state.value = State.Loading
        job = scope.launch {
            _state.value = try {
                val page = withTimeout(fetchTimeoutMs) { source(chipToken) }
                readyFrom(page, chipToken, previousChips, previousSort, previousWrap, previousFilters)
            } catch (e: TimeoutCancellationException) {
                logger?.log(Logger.Level.WARN, "fetch timed out after ${fetchTimeoutMs}ms", e)
                State.Failed(TIMEOUT_CODE, "fetch timed out after ${fetchTimeoutMs}ms")
            } catch (e: CancellationException) {
                throw e // a newer refresh took over the state — this coroutine just dies
            } catch (e: Exception) {
                logger?.captureError(e)
                State.Failed(e.errorCode() ?: "E20032", e.message)
            }
        }
    }

    /** Keep-content reload (pull-to-refresh AND chip taps): the visible list stays on screen with
     *  only the pull indicator spinning, then the fresh page swaps in when it lands. A failed or
     *  timed-out reload keeps the old data and emits an error code ([errors], shown as a snackbar)
     *  instead of dropping the user onto a full-screen error page. [chipToken] filters by that chip;
     *  null reloads the surface's default top-of-feed. */
    fun reload(chipToken: String? = null) {
        val source = reloadFetch ?: fetch
        val current = _state.value as? State.Ready ?: return load(chipToken, source)
        if (current.refreshing) return
        job?.cancel()
        val carryOver = chipToken != null
        val previousChips = if (carryOver) current.chips else emptyList()
        val previousSort = if (carryOver) current.sort else emptyList()
        val previousWrap = carryOver && current.chipsWrap
        val previousFilters = if (carryOver) current.filterGroups else emptyList()
        _state.value = current.copy(refreshing = true)
        job = scope.launch {
            _state.value = try {
                val page = withTimeout(fetchTimeoutMs) { source(chipToken) }
                readyFrom(page, chipToken, previousChips, previousSort, previousWrap, previousFilters)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                logger?.log(Logger.Level.WARN, "reload timed out after ${fetchTimeoutMs}ms", e)
                _errors.tryEmit(TIMEOUT_CODE)
                current.copy(refreshing = false)
            } catch (e: Exception) {
                logger?.captureError(e)
                _errors.tryEmit(e.errorCode() ?: "E20033")
                current.copy(refreshing = false)
            }
        }
    }

    private fun readyFrom(
        page: io.ymusic.dynamicengine.yt.YtFeedPage,
        chipToken: String?,
        previousChips: List<YtChip>,
        previousSort: List<YtChip>,
        previousWrap: Boolean,
        previousFilters: List<io.ymusic.dynamicengine.yt.YtFilterGroup>,
    ): State.Ready {
        // Selection can't ride on token identity across reloads: a chip-filtered page that DOES
        // return its own row returns fresh tokens (the clicked one matches nothing), with the
        // applied chip server-marked selected instead. So trust a fresh row's own marks; only when
        // the row lacks them (carried-over channel sort chips) re-mark it by the clicked token.
        page.partialErrorCode?.let { _errors.tryEmit(it) }
        val rawChips = page.chips.ifEmpty { previousChips }
        val chips = when {
            page.chips.any { it.selected } -> page.chips
            chipToken != null -> rawChips.map { it.copy(selected = it.token == chipToken) }
            else -> rawChips
        }
        return State.Ready(
            sections = page.sections,
            chips = chips,
            selectedChipToken = chips.firstOrNull { it.selected }?.token ?: chipToken,
            sort = page.sort.ifEmpty { previousSort },
            // The serving command stamps this on every response of its surface; OR-ing in the
            // carried value just guards a chip-tapped page that forgot it.
            chipsWrap = page.chipsWrap || previousWrap,
            emptyTitle = page.emptyTitle,
            emptyMessage = page.emptyMessage,
            continuation = page.continuation,
            loadingMore = false,
            filterGroups = page.filterGroups.ifEmpty { previousFilters },
            pageHeader = page.pageHeader,
            pageTabs = page.pageTabs,
        )
    }

    /** Drop the loaded items matching [predicate] in place (a confirmed server-side removal) — no refetch. */
    fun removeItems(predicate: (YtFeedItem) -> Boolean) {
        _state.update { state ->
            if (state !is State.Ready) return@update state
            state.copy(sections = state.sections.map { section -> section.copy(items = section.items.filterNot(predicate)) })
        }
    }

    fun loadMore() {
        val current = _state.value as? State.Ready ?: return
        val token = current.continuation ?: return
        if (current.loadingMore) return
        _state.value = current.copy(loadingMore = true)
        job = scope.launch {
            _state.value = try {
                val page = withTimeout(fetchTimeoutMs) { fetch(token) }
                current.copy(
                    sections = appendSections(current.sections, page.sections),
                    continuation = page.continuation,
                    loadingMore = false,
                )
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                logger?.log(Logger.Level.WARN, "loadMore timed out after ${fetchTimeoutMs}ms", e)
                current.copy(loadingMore = false) // timed out — the tail button stays for a retry
            } catch (e: Exception) {
                logger?.captureError(e)
                // Keep what we have; the tail button stays for a retry.
                current.copy(loadingMore = false)
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
    }

    companion object {
        /** The watchdog's error code — the retry page for a hung (not failed) fetch. */
        const val TIMEOUT_CODE = "E20031"
    }

    private fun appendSections(current: List<YtSection>, more: List<YtSection>): List<YtSection> {
        // A continuation usually keeps feeding the run it left off in — an incoming section with
        // the tail's header (none, or the same one re-sent) continues it rather than starting a break.
        val last = current.lastOrNull()
        val first = more.firstOrNull()
        if (last == null || first == null || last.header != first.header) return current + more
        return current.dropLast(1) + last.copy(items = last.items + first.items) + more.drop(1)
    }
}

/**
 * A feed surface's retained state — the loaded pages plus the scroll position. Hoist one of these
 * above the navigation switch (the app's layer stack, the Discover tab cache) so leaving and
 * returning to the surface shows it exactly as it was instead of reloading from the top.
 */
class FeedSession(
    scope: CoroutineScope,
    logger: Logger? = null,
    reloadFetch: (suspend (continuation: String?) -> io.ymusic.dynamicengine.yt.YtFeedPage)? = null,
    fetch: suspend (continuation: String?) -> io.ymusic.dynamicengine.yt.YtFeedPage,
) {
    val pager = Pager(scope, logger = logger, reloadFetch = reloadFetch, fetch = fetch)
    val listState = LazyListState()

    fun close() {
        pager.close()
    }
}

/**
 * One feed surface (design/final/feed-example.html): filter chips + sections, each rendered as a
 * list on a phone-portrait width or as a 2/3-column grid on tablet/landscape widths. Item tap /
 * queue actions come from [coordinator]; [onOpen] navigates a tapped playlist/channel item (null =
 * those items aren't tappable); [onPlayItem] overrides the default video tap (playlist context
 * plays within the whole list instead of an autoplay queue). [topContent] renders above the
 * sections and scrolls with them (a detail page's header).
 */
@Composable
fun FeedList(
    pager: Pager,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    emptyMessage: String = stringResource(Res.string.feed_empty),
    emptyAction: EmptyAction? = null,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onPlayItem: ((YtFeedItem) -> Unit)? = null,
    topContent: (@Composable () -> Unit)? = null,
    listState: LazyListState? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    val state by pager.state.collectAsState()
    // Pull-to-refresh / F5 reloads the surface from the top: drop any chip filter and take the
    // server's default first response, keeping the current list on screen until the new page lands.
    val reload = { pager.reload() }
    // Desktop F5/Cmd+R: only composed surfaces collect, so the request reaches the visible feed.
    val refreshRequests = LocalRefreshRequests.current
    if (refreshRequests != null) {
        LaunchedEffect(refreshRequests, pager) { refreshRequests.collect { reload() } }
    }
    // A keep-content reload that fails (pull-to-refresh, chip tap) keeps the old list and surfaces
    // the error as a snackbar instead of blanking the screen.
    val showSnackbar = LocalSnackbar.current
    var errorCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pager) { pager.errors.collect { errorCode = it } }
    errorCode?.let { code ->
        val message = errorMessage(code)
        LaunchedEffect(code, message) {
            showSnackbar(message)
            errorCode = null
        }
    }
    val refreshing = (state as? Pager.State.Ready)?.refreshing == true
    PullToRefreshFeed(refreshing = refreshing, onRefresh = reload) {
        when (val s = state) {
            is Pager.State.Loading -> Column(modifier) {
                topContent?.invoke()
                CenteredBox { CircularProgressIndicator() }
            }
            is Pager.State.Failed -> Column(modifier) {
                topContent?.invoke()
                ErrorState(s.code) { pager.refresh() }
            }
            is Pager.State.Ready -> {
                if (s.sections.all { it.items.isEmpty() }) {
                    Column(modifier) {
                        topContent?.invoke()
                        FeedChips(s, pager)
                        // The source's own explainer (anonymous Home's "Try searching to get
                        // started") beats the surface's generic empty text.
                        if (emptyAction != null) {
                            EmptyState(
                                s.emptyMessage ?: emptyMessage,
                                title = s.emptyTitle,
                                actionLabel = emptyAction.label,
                                onSecondaryReload = { pager.refresh(s.selectedChipToken) },
                                onReload = emptyAction.onClick,
                            )
                        } else {
                            EmptyState(
                                s.emptyMessage ?: emptyMessage,
                                title = s.emptyTitle,
                            ) { pager.refresh(s.selectedChipToken) }
                        }
                    }
                } else {
                    FeedSections(s, pager, coordinator, modifier, onOpen, onPlayItem, topContent, listState, onDeeplink)
                }
            }
        }
    }
}

/** Leads an empty feed's actions, for a surface whose emptiness a reload rarely fixes; reload stays
 *  beside it, outlined. */
class EmptyAction(val label: String, val onClick: () -> Unit)

/** Touch platforms wrap the feed in Material's pull-to-refresh; desktop reloads via F5/Cmd+R
 *  instead (a mouse can't over-drag a list), so the wrapper is a no-op there. The indicator spins
 *  for any keep-content reload — a pull gesture or a chip tap — but not the first full-screen load
 *  (that state has no list to pull against and shows its own centered spinner). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefreshFeed(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    if (!platformUsesTouch) {
        content()
        return
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) {
        content()
    }
}

/** Full-surface empty page: picture + message + an emphasized action (reload by default; none when
 *  [onReload] is null). [title], when the source supplies its own explainer (anonymous Home's nudge),
 *  leads in emphasis over the message. */
@Composable
internal fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String = stringResource(Res.string.reload),
    onSecondaryReload: (() -> Unit)? = null,
    onReload: (() -> Unit)?,
) {
    CenteredBox(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.Inbox,
                null,
                Modifier.size(56.dp).padding(bottom = 4.dp),
                tint = DesignColors.PlaceholderIcon,
            )
            if (title != null) {
                Text(
                    title,
                    style = TextStyle(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = DesignColors.InkStrong,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (onReload == null) return@Column
            Row(
                Modifier.padding(top = DesignSpacing.Space8),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
            ) {
                if (onSecondaryReload != null) {
                    OutlinedButton(
                        onClick = onSecondaryReload,
                        border = BorderStroke(1.dp, DesignColors.Outline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignColors.Ink),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(Res.string.reload))
                    }
                }
                Button(onClick = onReload) { Text(actionLabel) }
            }
        }
    }
}

/** Full-surface error page: picture + "Something went wrong (code)" + an emphasized retry action. */
/** A failure for the user: a known category as its own message, any other code as "Something went
 *  wrong (E…)" for them to report. */
@Composable
internal fun errorMessage(code: String): String = errorText(code).resolve()

internal fun errorText(code: String): UiText = when (code) {
    "network" -> UiText.Resource(Res.string.error_network)
    DISK_FULL_CODE -> UiText.Resource(Res.string.error_disk_full)
    StillProcessingException.CODE -> UiText.Resource(Res.string.error_live_still_processing)
    CAPTCHA_CODE -> UiText.Resource(Res.string.error_captcha)
    SUPERVISED_ACCOUNT_CODE -> UiText.Resource(Res.string.error_supervised_account)
    PLAYLIST_BACKUP_INVALID_CODE -> UiText.Resource(Res.string.error_playlist_backup_invalid)
    PLAYLIST_BACKUP_NEWER_CODE -> UiText.Resource(Res.string.error_playlist_backup_newer)
    else -> UiText.Resource(Res.string.error_generic, code)
}

/** YouTube's bot challenge — the engine's category for a google.com/sorry / reCAPTCHA answer. */
internal const val CAPTCHA_CODE = "captcha"

/** The signed-in account is a supervised (Family Link) one, which YouTube's site turns away. */
internal const val SUPERVISED_ACCOUNT_CODE = "supervised_account"

/** A captcha failure leads with the resolver's action (which retries once cleared), Retry beside it. */
@Composable
internal fun ErrorState(code: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    CenteredBox(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.CloudOff,
                null,
                Modifier.size(56.dp).padding(bottom = 4.dp),
                tint = DesignColors.PlaceholderIcon,
            )
            Text(
                errorMessage(code),
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            val captcha = LocalCaptchaResolver.current?.takeIf { code == CAPTCHA_CODE }
            if (captcha == null) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = DesignSpacing.Space8)) {
                    Text(stringResource(Res.string.retry))
                }
            } else {
                Row(
                    Modifier.padding(top = DesignSpacing.Space8),
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        border = BorderStroke(1.dp, DesignColors.Outline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignColors.Ink),
                    ) {
                        Text(stringResource(Res.string.retry))
                    }
                    Button(onClick = { captcha.resolve(onRetry) }) { Text(stringResource(captcha.label)) }
                }
            }
        }
    }
}

@Composable
private fun FeedSections(
    state: Pager.State.Ready,
    pager: Pager,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onPlayItem: ((YtFeedItem) -> Unit)? = null,
    topContent: (@Composable () -> Unit)? = null,
    hoistedListState: LazyListState? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    // Collapsible sections start collapsed; the map survives recomposition, keyed by section slot.
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val metrics = gridMetricsFor(maxWidth)
        val layout = feedLayoutFor(maxWidth)
        val columns = if (layout != FeedLayout.Grid) 1 else gridColumnsFor(maxWidth)
        val wide = layout == FeedLayout.WideList
        val compactTitle = feedCompactTitleFor(maxWidth)
        val listState = hoistedListState ?: rememberLazyListState()
        // Report scroll to the shell (which lifts the app bar); the feed doesn't touch the bar itself.
        TrackScroll { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
        LazyColumn(Modifier.fillMaxSize().listKeyboardNavigation(), state = listState, contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
            if (topContent != null) item { topContent() }
            // A 0-height chip slot at index 0 makes firstVisibleItemIndex > 0 while still at the top.
            val hasChips = state.chips.isNotEmpty() || state.sort.isNotEmpty()
            if (hasChips) item { FeedChips(state, pager) }
            if (topContent == null && !hasChips) {
                // Nothing above the first item (search results, channel content tabs) — the
                // mockups give the feed a small inset under the app bar (search.html .grid).
                item { Spacer(Modifier.height(6.dp)) }
            }
            state.sections.forEachIndexed { index, section ->
                if (index > 0) {
                    // The section separator: a hairline centered in the inter-section air (the
                    // header's own top inset supplies the space below it).
                    item {
                        HorizontalDivider(
                            Modifier.padding(
                                start = DesignSpacing.MarginCompact,
                                end = DesignSpacing.MarginCompact,
                                top = DesignSpacing.Space16,
                            ),
                            color = DesignColors.Divider,
                        )
                    }
                }
                feedSection(
                    section = section,
                    columns = columns,
                    metrics = metrics,
                    wide = wide,
                    compactTitle = compactTitle,
                    expanded = expanded[index] ?: !section.collapsible,
                    onToggle = { expanded[index] = !(expanded[index] ?: !section.collapsible) },
                    coordinator = coordinator,
                    onOpen = onOpen,
                    onPlayItem = onPlayItem,
                    onDeeplink = onDeeplink,
                )
            }
            if (state.continuation != null) {
                item {
                    if (state.loadingMore) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    } else {
                        ShowMoreButton(stringResource(Res.string.show_more), expanded = false, onClick = { pager.loadMore() })
                    }
                }
            }
        }
    }
}

/** Pills shown while a collapsible tag section is collapsed (roughly two rows). */
private const val TAGS_COLLAPSED = 10

/** Default rows a collapsed section keeps by column count — 4 single-column, 3 at two columns,
 *  2 from three up (see YtSection.collapsedRows for the per-section engine override). */
private val COLLAPSED_ROWS_BY_COLUMNS = listOf(4, 3, 2)

private fun androidx.compose.foundation.lazy.LazyListScope.feedSection(
    section: YtSection,
    columns: Int,
    metrics: GridMetrics,
    wide: Boolean,
    compactTitle: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    coordinator: PlaybackCoordinator,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onPlayItem: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    if (section.header != null || section.collapsible) {
        item {
            val deeplink = section.header?.ctaDeeplink
            SectionHeader(
                section.header,
                collapsed = if (section.collapsible) !expanded else null,
                onToggle = if (section.collapsible) onToggle else null,
                onCta = when {
                    section.header?.ctaAction == YtSectionHeader.CTA_PLAY_ALL -> {
                        { coordinator.playAll(section.items, shuffle = false) }
                    }
                    deeplink != null && onDeeplink != null -> {
                        { onDeeplink(deeplink, section.header?.title) }
                    }
                    else -> null
                },
            )
        }
    }
    // A tag section (Explore's Moods & genres, its entry buttons) is a wrap of navigation pills, not
    // list rows or grid cards. A long wrap (37 mood/genre tiles) collapses to a couple rows behind a
    // "Show more" toggle — the header's chevron and the button below both expand it.
    if (section.layout == YtSection.LAYOUT_TAGS) {
        val shown = if (expanded || !section.collapsible) section.items else section.items.take(TAGS_COLLAPSED)
        item { TagFlow(shown, onOpen) }
        if (section.collapsible && section.items.size > TAGS_COLLAPSED) {
            item {
                val label = if (expanded) {
                    stringResource(Res.string.show_less)
                } else {
                    stringResource(Res.string.show_more_n, section.items.size - TAGS_COLLAPSED)
                }
                ShowMoreButton(label, expanded = expanded, onClick = onToggle)
            }
        }
        return
    }
    if (section.layout == YtSection.LAYOUT_CAROUSEL) {
        item { ChannelCarousel(section.items, onOpen) }
        return
    }
    // A collapsed section keeps a few rows, resolved by column count from an engine-supplied
    // ladder (YtSection.collapsedRows, remotely tunable per section) or the app default —
    // index columns-1, the last entry extending to wider layouts.
    val ladder = section.collapsedRows.ifEmpty { COLLAPSED_ROWS_BY_COLUMNS }
    val collapsedCount = columns * ladder[(columns - 1).coerceAtMost(ladder.lastIndex)].coerceAtLeast(1)
    val shown = if (expanded) section.items else section.items.take(collapsedCount)
    if (columns == 1) {
        items(shown.size) { i ->
            val item = shown[i]
            FeedListItem(
                item,
                actions = itemActions(item, coordinator),
                onClick = itemClick(item, section, coordinator, onOpen, onPlayItem),
                wide = wide,
                compactTitle = compactTitle,
            )
        }
    } else {
        val rows = shown.chunked(columns)
        items(rows.size) { r ->
            Row(
                // Margin/gutter minus the inset each card re-adds around itself as the hover's
                // breathing room — visual metrics stay margin/gutter exactly. Rows sit a full
                // Space16 apart vertically regardless of the width class's (narrower) gutter —
                // the card meta under each thumb needs more separation than thumbs side by side.
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = metrics.margin - metrics.inset,
                        end = metrics.margin - metrics.inset,
                        bottom = (DesignSpacing.GridRowGap - metrics.inset * 2).coerceAtLeast(0.dp),
                    ),
                horizontalArrangement = Arrangement.spacedBy(metrics.gutter - metrics.inset * 2),
            ) {
                rows[r].forEach { item ->
                    FeedGridCard(
                        item,
                        actions = itemActions(item, coordinator),
                        onClick = itemClick(item, section, coordinator, onOpen, onPlayItem),
                        modifier = Modifier.weight(1f),
                        inset = metrics.inset,
                    )
                }
                repeat(columns - rows[r].size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (section.collapsible && section.items.size > collapsedCount) {
        item {
            val label = if (expanded) {
                stringResource(Res.string.show_less)
            } else {
                stringResource(Res.string.show_more_n, section.items.size - collapsedCount)
            }
            ShowMoreButton(label, expanded = expanded, onClick = onToggle)
        }
    }
}

private fun itemClick(
    item: YtFeedItem,
    section: YtSection,
    coordinator: PlaybackCoordinator,
    onOpen: ((YtFeedItem) -> Unit)?,
    onPlayItem: ((YtFeedItem) -> Unit)?,
): (() -> Unit)? = when (item.kind) {
    YtFeedItem.KIND_VIDEO -> ({
        when {
            onPlayItem != null -> onPlayItem(item)
            section.playMode == YtSection.PLAY_QUEUE -> {
                val videos = section.items.filter { it.kind == YtFeedItem.KIND_VIDEO }
                coordinator.playAll(videos, startIndex = videos.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            }
            else -> coordinator.playNow(item)
        }
    })
    YtFeedItem.KIND_PLAYLIST, YtFeedItem.KIND_CHANNEL, YtFeedItem.KIND_TAG -> onOpen?.let { { it(item) } }
    else -> null
}

/** Overflow "Remove from playlist" on the viewer's own playlist (items carrying a removeToken). */
internal val LocalRemoveFromYtPlaylist = compositionLocalOf<((YtFeedItem) -> Unit)?> { null }

/** Overflow "Remove" on Discover Top tracks. Null everywhere else hides the item. */
internal val LocalRemoveFromTopTracks = compositionLocalOf<((YtFeedItem) -> Unit)?> { null }

@Composable
private fun itemActions(item: YtFeedItem, coordinator: PlaybackCoordinator): ItemActions {
    val downloadLauncher = LocalDownloadLauncher.current
    val quickPreset = downloadLauncher?.quickPreset?.collectAsState()?.value
    val removeFromTopTracks = LocalRemoveFromTopTracks.current
    val ytPlaylists = LocalYtPlaylistActions.current
    val removeFromYtPlaylist = LocalRemoveFromYtPlaylist.current
    val share = LocalShareYoutube.current
    val subscribe = if (item.kind == YtFeedItem.KIND_CHANNEL) rememberChannelSubscriber(coordinator) else null
    return ItemActions(
        if (item.kind == YtFeedItem.KIND_VIDEO) {
            buildList {
                add(stringResource(Res.string.menu_play_single) to { coordinator.playSingle(item) })
                add(stringResource(Res.string.menu_start_radio) to { coordinator.playNow(item) })
                add(stringResource(Res.string.queue_next) to { coordinator.playNext(item) })
                add(stringResource(Res.string.queue_last) to { coordinator.playLater(item) })
                if (ytPlaylists != null) {
                    add(stringResource(Res.string.menu_add_to_playlist) to { ytPlaylists.addToPlaylist(item.id) })
                }
                if (downloadLauncher != null && !item.live) {
                    add(stringResource(Res.string.cd_download) to { downloadLauncher.launch(item) })
                    if (quickPreset != null) {
                        add(stringResource(Res.string.quick_download_menu, quickPreset.label()) to { downloadLauncher.quickLaunch(item) })
                    }
                }
                if (share != null) add(stringResource(Res.string.cd_share) to { share(item.kind, item.id) })
                if (removeFromYtPlaylist != null && item.removeToken != null) {
                    add(stringResource(Res.string.menu_remove_from_playlist) to { removeFromYtPlaylist(item) })
                }
                if (removeFromTopTracks != null) {
                    add(stringResource(Res.string.menu_remove) to { removeFromTopTracks(item) })
                }
            }
        } else {
            buildList {
                val channelId = (item.channelId ?: item.id).takeIf { item.kind == YtFeedItem.KIND_CHANNEL }
                if (channelId != null && subscribe != null) {
                    add(stringResource(Res.string.subscribe) to { subscribe(channelId, true) })
                    add(stringResource(Res.string.unsubscribe) to { subscribe(channelId, false) })
                }
                if (share != null && youtubeShareLink(item.kind, item.id) != null) {
                    add(stringResource(Res.string.cd_share) to { share(item.kind, item.id) })
                }
            }
        },
    )
}

/** A channel row's Subscribe / Unsubscribe, reported by snackbar; null while signed out. */
@Composable
private fun rememberChannelSubscriber(coordinator: PlaybackCoordinator): ((channelId: String, subscribe: Boolean) -> Unit)? {
    val signIn = LocalSignInResolver.current
    if (signIn == null || !signIn.signedIn) return null
    val graph = coordinator.graph
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    return { channelId, subscribe ->
        scope.launch {
            val log = graph.logger?.fork("channel-subscribe", mapOf("channelId" to channelId))
            val message = try {
                graph.engines.subscribeChannel(channelId, subscribe, log)
                getString(if (subscribe) Res.string.subscribed else Res.string.unsubscribed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "subscribe from a channel row failed", error)
                errorText(error.errorCode() ?: "E20161").load()
            }
            snackbar(message)
        }
    }
}

/** The feed filter chips ("All / Music / Gaming…"); tapping one reloads the feed through its token.
 *  The engine picks the layout ([Pager.State.Ready.chipsWrap]): wrapped to as many lines as needed
 *  (the music library's fixed filter set), or the default horizontal scroll (the open-ended strips
 *  everywhere else). A sort menu, when the page publishes one, rides inline at the end either way. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FeedChips(state: Pager.State.Ready, pager: Pager) {
    if (state.chips.isEmpty() && state.sort.isEmpty()) return
    // Keep-content reload either way: the list stays put with the pull indicator spinning, so a
    // failed chip fetch keeps the current data (error → snackbar) instead of blanking the screen.
    if (state.chipsWrap) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignSpacing.MarginCompact, vertical = DesignSpacing.Space8),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            state.chips.forEach { chip ->
                FeedChip(chip.title, selected = chip.selected) { pager.reload(chip.token) }
            }
            if (state.sort.isNotEmpty()) {
                FeedSortMenu(state.sort) { token -> pager.reload(token) }
            }
        }
        return
    }
    val chipScroll = rememberLazyListState()
    LazyRow(
        Modifier.fillMaxWidth().horizontalWheelScroll(chipScroll),
        state = chipScroll,
        contentPadding = PaddingValues(horizontal = DesignSpacing.MarginCompact, vertical = DesignSpacing.Space8),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        items(state.chips) { chip ->
            FeedChip(chip.title, selected = chip.selected) { pager.reload(chip.token) }
        }
        if (state.sort.isNotEmpty()) {
            item { FeedSortMenu(state.sort) { token -> pager.reload(token) } }
        }
    }
}

/** The server sort menu ("Recent activity ⌄"): the current order named on the trigger, the options
 *  in a dropdown; picking one re-serves the feed through its continuation token (keep-content). */
@Composable
private fun FeedSortMenu(options: List<YtChip>, modifier: Modifier = Modifier, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.selected } ?: options.first()
    Box(modifier) {
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
                    current.title,
                    style = TextStyle(fontSize = ChipTextSize, fontWeight = FontWeight.Medium, color = DesignColors.Ink),
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    leadingIcon = {
                        if (option.selected) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = DesignColors.Accent)
                        }
                    },
                    onClick = {
                        open = false
                        if (!option.selected) onSelect(option.token)
                    },
                )
            }
        }
    }
}

/** One feed filter pill; [onClick] null = a non-interactive label in the same style. */
@Composable
fun FeedChip(title: String, selected: Boolean, onClick: (() -> Unit)?) {
    ChipTapArea(onClick) { indication ->
        Text(
            title,
            style = TextStyle(fontSize = ChipTextSize, color = if (selected) Color.White else DesignColors.InkSoft),
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) DesignColors.Accent else Color.Transparent)
                .border(1.dp, if (selected) DesignColors.Accent else DesignColors.Outline, RoundedCornerShape(999.dp))
                .then(indication)
                .chipPadding(),
        )
    }
}

/**
 * Music-first ordering for the Home feed (a music app's home): YouTube Mixes (the endless RD…
 * radios) first, then music videos, then videos from an Official Artist Channel, then verified
 * channels, then the rest. Every other item type (regular playlists, channels) is PINNED — it
 * keeps its exact slot while the ranked items reorder through the remaining slots (stable within
 * each band).
 */
internal fun rankMusicFirst(items: List<YtFeedItem>): List<YtFeedItem> {
    fun band(item: YtFeedItem): Int? = when {
        item.kind == YtFeedItem.KIND_PLAYLIST && item.id.startsWith("RD") -> 0
        item.kind != YtFeedItem.KIND_VIDEO -> null // pinned
        item.musicVideo -> 1
        item.ownerBadge == YtFeedItem.OWNER_BADGE_ARTIST -> 2
        item.ownerBadge == YtFeedItem.OWNER_BADGE_VERIFIED -> 3
        else -> 4
    }
    val movable = items.filter { band(it) != null }.sortedBy { band(it) }
    var next = 0
    return items.map { item -> if (band(item) == null) item else movable[next++] }
}

/** The three feed layout bands a surface width falls into. */
internal enum class FeedLayout { SquareList, WideList, Grid }

/** The ONE place the feed breakpoints live: square list under 320dp, 16:9 list to 469dp, then the
 *  grid ([gridColumnsFor] columns). */
internal fun feedLayoutFor(width: androidx.compose.ui.unit.Dp): FeedLayout = when {
    width < 320.dp -> FeedLayout.SquareList
    width < 470.dp -> FeedLayout.WideList
    else -> FeedLayout.Grid
}

/** Feed list rows under 380dp use [ListTitleCompactStyle]. */
internal fun feedCompactTitleFor(width: androidx.compose.ui.unit.Dp): Boolean = width < 380.dp

/** Max-item-width model: the FEWEST columns (≥2) keeping every card ≤ [GRID_CARD_MAX_WIDTH], with
 *  the gutter between columns counted. Cards shrink toward the next column, never past the max. */
internal fun gridColumnsFor(width: androidx.compose.ui.unit.Dp): Int {
    val metrics = gridMetricsFor(width)
    return kotlin.math.ceil((width - metrics.margin * 2 + metrics.gutter) / (GRID_CARD_MAX_WIDTH + metrics.gutter))
        .toInt()
        .coerceAtLeast(2)
}

@Composable
internal fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
