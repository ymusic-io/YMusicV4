package io.ymusic.android

import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred

/**
 * The folder picks waiting on a picker result. Process-scoped: Android may destroy MainActivity while a picker
 * covers it (low-memory TVs do), and the recreated instance receives the result for the pick the old one launched.
 * Main thread only.
 */
internal class FolderPicks {
    /** The whole pick, completed by the system picker's result or by a fallback picker. */
    var tree: CompletableDeferred<Uri?>? = null

    /** The YMusic Files pick inside [tree]'s fallback. */
    var companion: CompletableDeferred<android.net.Uri?>? = null

    /** Whether the open pick only accepts a writable folder (the download location). */
    var writable: Boolean = false

    /** The purpose of a pick launched by a process Android has since killed, restored from the Activity's saved
     *  state: its result arrives with nothing waiting, and is applied without a caller. */
    var orphanWritable: Boolean? = null

    val waiting: Boolean get() = tree != null || companion != null

    fun abandon() {
        companion?.complete(null)
        companion = null
        tree?.complete(null)
        tree = null
    }
}
