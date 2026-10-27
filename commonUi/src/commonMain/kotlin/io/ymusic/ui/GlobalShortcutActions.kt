package io.ymusic.ui

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
    /**
     * Cmd/Ctrl + 1..9 → pick that tab in the current screen's tab row. Screens that have tabs
     * push their own picker on compose and restore the prior one on dispose, so the innermost
     * tab-having composable wins. Returns true if [index] (1-based) resolved to a tab.
     */
    @Volatile var selectTabByIndex: (Int) -> Boolean = { false }
}
