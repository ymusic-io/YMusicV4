package io.ymusic.ui

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * Callbacks that App composition owns (navigation, focus) but platform key-event handlers
 * outside the composition need to invoke. Set from App.kt once the targets exist; the handler
 * calls through at runtime. Multi-window apps overwrite the latest.
 */
object GlobalShortcutActions {
    @Volatile var openSearch: () -> Unit = {}

    @Volatile var toggleShortcutsHelp: () -> Unit = {}

    @Volatile var toggleNavDrawer: () -> Unit = {}

    /** Returns true if a drawer was actually closed (so Esc can know to stop its cascade). */
    @Volatile var closeNavDrawerIfOpen: () -> Boolean = { false }

    // Cmd/Ctrl + 1..9 → the top-most pushed tab handler wins. A plain save/restore var is racy
    // across section transitions (the entering screen can capture the leaving screen's handler
    // as its "prior" before the leaving dispose fires, then dispose wipes the new one). Keep a
    // proper LIFO stack so order of compose/dispose doesn't matter.
    private val tabHandlers = mutableListOf<(Int) -> Boolean>()
    private val tabLock = SynchronizedObject()
    val selectTabByIndex: (Int) -> Boolean = { i ->
        synchronized(tabLock) { tabHandlers.lastOrNull() }?.invoke(i) ?: false
    }

    /** Push a tab handler and get back a function that removes exactly this registration. */
    fun pushTabHandler(handler: (Int) -> Boolean): () -> Unit {
        synchronized(tabLock) { tabHandlers.add(handler) }
        return { synchronized(tabLock) { tabHandlers.remove(handler) } }
    }
}
