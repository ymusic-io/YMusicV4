package io.ymusic.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.delete_from_disk_confirm
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.downloads_action_select
import io.ymusic.ui.resources.downloads_delete_selected_message
import io.ymusic.ui.resources.downloads_delete_selected_title
import io.ymusic.ui.resources.downloads_selected_count
import io.ymusic.ui.resources.select_all
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.TechnicalException
import projectcommon.error.errorCode

/**
 * A list's multi-select (the Download manager, the Library's Songs tab): the mode lasts while anything is
 * selected; Select all remembers the selection it replaced and a second press restores it.
 */
@Stable
internal class MultiSelection<K> {
    var selected by mutableStateOf(emptySet<K>())
        private set

    /** What Select all restores when pressed again; only valid until the selection or the listed items change otherwise. */
    private var beforeSelectAll by mutableStateOf<Set<K>?>(null)

    /** The item that started the mode or was toggled last: focus returns to it when the mode ends. */
    private var origin by mutableStateOf<K?>(null)

    /** The item to focus once the mode ended, consumed by that item. */
    var focusTarget by mutableStateOf<K?>(null)
        private set

    val active: Boolean get() = selected.isNotEmpty()

    fun set(ids: Set<K>) {
        selected = ids
        beforeSelectAll = null
    }

    fun start(id: K) {
        origin = id
        set(setOf(id))
    }

    /** [start], then in keyboard mode focus [id]'s item: the ⋮ that held focus gives way to a non-focusable checkbox. */
    fun startKeepingFocus(id: K, keyboardMode: Boolean) {
        start(id)
        if (keyboardMode) focusTarget = id
    }

    fun toggle(id: K) {
        origin = id
        set(if (id in selected) selected - id else selected + id)
    }

    fun end() = set(emptySet())

    /** Ends the mode because its list is going away (a tab switch, a reveal elsewhere): focus does not come back later. */
    fun abandon() {
        origin = null
        end()
    }

    fun allSelected(listed: Collection<K>): Boolean = listed.isNotEmpty() && selected.containsAll(listed)

    /** Adds every [listed] item, or, once all are, restores the selection from before (without one, deselects them). */
    fun toggleAll(listed: List<K>) {
        if (allSelected(listed)) {
            set(beforeSelectAll ?: (selected - listed.toSet()))
        } else if (listed.isNotEmpty()) {
            val before = selected
            set(selected + listed)
            beforeSelectAll = before
        }
    }

    /** Drops the picks whose item is gone. */
    fun retain(present: Set<K>) {
        val kept = selected intersect present
        if (kept != selected) set(kept)
    }

    /** The listed items changed: Select all no longer restores. */
    fun forgetSelectAll() {
        beforeSelectAll = null
    }

    fun focusHandled() {
        focusTarget = null
    }

    internal fun takeOrigin(): K? = origin.also { origin = null }

    internal fun requestFocus(id: K) {
        focusTarget = id
    }
}

@Composable
internal fun <K> rememberMultiSelection(): MultiSelection<K> = remember { MultiSelection() }

/**
 * Back ends [selection]'s mode; once it ends in keyboard mode, focus goes back to the item that started it (else
 * the first of [listed]), after [scrollTo] brought that item's index into view.
 */
@Composable
internal fun <K> MultiSelectionEffects(selection: MultiSelection<K>, listed: List<K>, scrollTo: suspend (index: Int) -> Unit) {
    val active = selection.active
    NestedBackHandler(enabled = active) { selection.end() }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val currentListed by rememberUpdatedState(listed)
    val currentScrollTo by rememberUpdatedState(scrollTo)
    LaunchedEffect(active) {
        if (active) return@LaunchedEffect
        val origin = selection.takeOrigin() ?: return@LaunchedEffect
        if (!keyboardMode) return@LaunchedEffect
        val items = currentListed
        val target = origin.takeIf { it in items } ?: items.firstOrNull() ?: return@LaunchedEffect
        currentScrollTo(items.indexOf(target))
        selection.requestFocus(target)
    }
}

/**
 * An item's gesture: while [selecting], one checkbox toggle over the whole item; otherwise a long press starts the
 * mode with it selected, and a tap runs [onClick] (none: the item only takes focus).
 */
@Composable
internal fun Modifier.multiSelectGesture(
    selecting: Boolean,
    selected: Boolean,
    shape: Shape,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    onToggle: () -> Unit,
    onStartSelection: () -> Unit,
    onClick: (() -> Unit)? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val startSelection by rememberUpdatedState(onStartSelection)
    val focusRequester = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            onFocusRequested()
        }
    }
    val startWithHaptics = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        startSelection()
    }
    val gesture = when {
        selecting -> Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        onClick != null -> Modifier.combinedClickable(
            onClickLabel = null,
            onLongClickLabel = stringResource(Res.string.downloads_action_select),
            onLongClick = startWithHaptics,
            onClick = onClick,
        )
        else -> Modifier.focusable().pointerInput(Unit) { detectTapGestures(onLongPress = { startWithHaptics() }) }
    }
    return focusRequester(focusRequester).keyboardFocusRing(shape).then(gesture)
}

/** A selected item's fill: what a hovered item shows, the accent ripple at Material 3's hover state-layer opacity. */
@Composable
internal fun selectedItemFill(): Color = DesignColors.Accent.copy(alpha = HoverStateLayerAlpha)

private const val HoverStateLayerAlpha = 0.08f

/** The checkbox in an item's ⋮ slot while selecting; the item's own toggle acts and speaks for it. */
@Composable
internal fun SelectionCheck(selected: Boolean) {
    Box(Modifier.size(SelectionCheckSize), contentAlignment = Alignment.Center) {
        Checkbox(checked = selected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = DesignColors.Accent))
    }
}

/** The selection mode's bar: close · "N selected" · Select all · [actions]. */
@Composable
internal fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    AppBarRow {
        ActionIcon(Icons.Default.Close, stringResource(Res.string.cd_close), onClick = onClose)
        AppBarTitle(pluralText(Res.plurals.downloads_selected_count, count, count))
        ToggleActionIcon(
            Icons.Default.SelectAll,
            stringResource(Res.string.select_all),
            checked = allSelected,
            tint = if (allSelected) DesignColors.Accent else DesignColors.InkMuted,
            onCheckedChange = { onToggleSelectAll() },
        )
        actions()
    }
}

/**
 * Deletes each target's file and Library row, then runs [afterDelete] for it. A failed one stays, counted in the
 * title, with its code shown (in a snackbar once the dialog is gone); [fallbackCode] is the code of a failure that
 * carries none. The batch runs on the process scope: leaving the screen mid-batch must not skip the rest.
 */
@Composable
internal fun <K> DeleteSelectedFilesDialog(
    graph: AppGraph,
    targets: Map<K, MediaStoreItem>,
    fallbackCode: String,
    log: (K) -> Logger?,
    afterDelete: suspend (K) -> Unit,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val snackbar = LocalSnackbar.current
    var shown by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { shown = false } }
    var remaining by remember(targets) { mutableStateOf(targets) }
    var busy by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    val dismiss = { if (!busy) onDismiss() }
    val deleteFile = graph.deleteFromDisk
    val store = graph.mediaStore
    ElevatedBarDialog(
        title = pluralText(Res.plurals.downloads_delete_selected_title, remaining.size, remaining.size),
        onDismiss = dismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = !busy, onClick = dismiss)
            TextButton(
                enabled = !busy && deleteFile != null && store != null,
                onClick = {
                    if (deleteFile == null || store == null) return@TextButton
                    busy = true
                    errorCode = null
                    graph.processScope.launch {
                        var firstError: String? = null
                        for ((key, item) in remaining) {
                            try {
                                deleteFile(item)
                                store.deleteItem(item.id, hard = true)
                                afterDelete(key)
                                remaining = remaining - key
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: Throwable) {
                                val code = failure.errorCode()
                                if (code == null) {
                                    log(key)?.captureError(TechnicalException(fallbackCode, "delete from storage failed", cause = failure))
                                } else {
                                    log(key)?.log(Logger.Level.WARN, "delete from storage failed", failure)
                                }
                                firstError = firstError ?: code ?: fallbackCode
                            }
                        }
                        busy = false
                        errorCode = firstError
                        when {
                            firstError == null -> onDone()
                            !shown -> snackbar(errorText(firstError).load())
                        }
                    }
                },
            ) { Text(stringResource(Res.string.delete_from_disk_confirm), color = MaterialTheme.colorScheme.error) }
        },
    ) {
        Column(Modifier.padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16)) {
            Text(
                stringResource(Res.string.downloads_delete_selected_message),
                style = MaterialTheme.typography.bodyMedium,
                color = DesignColors.Ink,
            )
            errorCode?.let {
                Text(
                    errorMessage(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = DesignSpacing.Space8),
                )
            }
        }
    }
}

private val SelectionCheckSize = 40.dp // ActionIcon's hit size: the checkbox takes the ⋮'s slot without a layout shift
