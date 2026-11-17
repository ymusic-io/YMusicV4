package io.ymusic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.a11y_loading
import org.jetbrains.compose.resources.stringResource

/**
 * Keeps keyboard and screen-reader focus in place across a "load more". Activating the control
 * ([start]) focuses the [LoadingMoreIndicator] shown while the page loads; once it settles ([Settle])
 * focus moves to the first new item, else to the control that is back (Show more again, Retry), else to
 * the last item, else to the caller's fallback, unless the user moved focus off the indicator meanwhile.
 * Expanding a collapsed section ([reveal]) focuses the first revealed item; collapsing it ([collapse])
 * keeps focus on the toggle and scrolls it into view. Items carry [item], the control [control].
 */
@Stable
@OptIn(ExperimentalFoundationApi::class)
internal class LoadMoreFocus {
    private enum class Mode { Idle, Loading, Reveal, Collapse }

    private var mode by mutableStateOf(Mode.Idle)
    private var from by mutableStateOf<Int?>(null)
    private var request by mutableIntStateOf(0)
    private var sawLoading = false

    // Plain fields written during composition: the indicator leaving composition reports a focus
    // loss after its load ended, which must not count as the user moving away.
    private var loadingNow = false
    private var indicatorHeld = false
    private var movedAway = false

    internal val indicator = FocusRequester()
    private val controlRequester = FocusRequester()
    private val controlView = BringIntoViewRequester()
    private val first = FocusRequester()
    private val last = FocusRequester()

    internal val awaitingIndicator: Boolean get() = mode == Mode.Loading && from != null

    /** The control was activated with [count] items loaded. */
    fun start(count: Int) {
        from = count
        mode = Mode.Loading
        sawLoading = false
        indicatorHeld = false
        movedAway = false
        request++
    }

    /** A collapsed section showing [count] items expanded. */
    fun reveal(count: Int) {
        from = count
        mode = Mode.Reveal
        request++
    }

    /** The expanded section collapsed. */
    fun collapse() {
        from = null
        mode = Mode.Collapse
        request++
    }

    /** On the item at [index], so focus can land on it. */
    fun item(index: Int): Modifier = when (index) {
        from -> Modifier.focusRequester(first)
        from?.minus(1) -> Modifier.focusRequester(last)
        else -> Modifier
    }

    /** On the load-more control, and on the Retry that takes its place after a failure. */
    val control: Modifier get() = Modifier.focusRequester(controlRequester).bringIntoViewRequester(controlView)

    internal fun onIndicatorFocus(focused: Boolean) {
        if (focused) {
            indicatorHeld = true
        } else if (loadingNow && indicatorHeld) {
            movedAway = true
        }
    }

    /** Moves focus once the load settles: [loading] is the load's state, [count] the items loaded,
     *  [controlShown] whether the control (or Retry) is on screen again. */
    @Composable
    fun Settle(loading: Boolean, count: Int, controlShown: Boolean, fallback: FocusRequester? = null) {
        loadingNow = loading
        val currentCount by rememberUpdatedState(count)
        val currentControlShown by rememberUpdatedState(controlShown)
        LaunchedEffect(this, request, loading) {
            when (mode) {
                Mode.Idle -> Unit
                Mode.Loading -> {
                    val start = from ?: return@LaunchedEffect
                    if (loading) {
                        sawLoading = true
                        return@LaunchedEffect
                    }
                    if (!sawLoading) return@LaunchedEffect
                    mode = Mode.Idle
                    if (!movedAway) land(start, currentCount, currentControlShown, fallback)
                    from = null
                }
                Mode.Reveal -> {
                    val start = from ?: return@LaunchedEffect
                    mode = Mode.Idle
                    land(start, currentCount, controlShown = true, fallback = null)
                    from = null
                }
                Mode.Collapse -> {
                    mode = Mode.Idle
                    if (controlRequester.requestWhenAttached()) controlView.bringIntoView()
                }
            }
        }
    }

    private suspend fun land(start: Int, count: Int, controlShown: Boolean, fallback: FocusRequester?) {
        if (count > start && first.requestWhenAttached()) return
        if (controlShown && controlRequester.requestWhenAttached()) return
        if (start > 0 && last.requestWhenAttached()) return
        fallback?.requestWhenAttached()
    }
}

/** A lazy list builds an item only in the layout pass after its data arrives, so a requester on it
 *  attaches a frame or more after the composition that added it. [now]: already attached, and asked
 *  at once (Now Playing's focus catcher takes focus left unclaimed for a frame). */
private suspend fun FocusRequester.requestWhenAttached(now: Boolean = false): Boolean {
    if (now && requestFocus()) return true
    repeat(ATTACH_FRAMES) {
        withFrameNanos {}
        if (requestFocus()) return true
    }
    return false
}

private const val ATTACH_FRAMES = 4

/**
 * The spinner of a load in progress. While a load the user started is pending it is focusable and
 * takes focus, read as [label] ("Loading" by default).
 */
@Composable
internal fun LoadingMoreIndicator(
    focus: LoadMoreFocus,
    modifier: Modifier = Modifier,
    label: String = stringResource(Res.string.a11y_loading),
    content: @Composable () -> Unit = { CircularProgressIndicator(Modifier.size(28.dp)) },
) {
    val focusable = focus.awaitingIndicator
    Box(
        modifier
            .focusRequester(focus.indicator)
            .onFocusChanged { focus.onIndicatorFocus(it.isFocused) }
            .focusRing(RoundedCornerShape(percent = 50))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .then(if (focusable) Modifier.focusable() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
    if (focusable) {
        LaunchedEffect(focus) { focus.indicator.requestWhenAttached(now = true) }
    }
}
