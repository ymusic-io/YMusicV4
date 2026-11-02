package io.ymusic.ui

import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

/**
 * The shell's updater, as the shared UI sees it. Desktop and Android implement this; iOS leaves it
 * null (the App Store owns updating) and the UI then shows nothing at all.
 */
interface AppUpdates {
    val status: StateFlow<AppUpdateStatus>

    /** Why an in-place update is impossible here, or null when it is possible. Shown next to the
     *  manual download so a blocked user is told what to do instead of silently getting nothing. */
    val blockedReason: StringResource?

    /** The "Download" button: start the download, retry a failed one, or join one already running.
     *  No-op once ready. */
    fun download()

    /** Apply a ready update and relaunch. */
    fun installAndRestart()

    /** [UpdateDownload.NeedsInstallPermission]'s "Allow": open the system screen that grants it. The
     *  download or install the user asked for continues once they come back with it granted. */
    fun allowInstall() {}

    /**
     * "Check for update": ask the service now, ignoring the refresh throttle and any earlier "Ask
     * later". A found update lands in [status] as usual, so the update prompt shows it.
     */
    suspend fun checkNow(): UpdateCheck
}

enum class UpdateCheck { UpToDate, Found, Failed }

/** What the UI renders. Mirrors the shell's state machine without exposing its internals. */
sealed interface AppUpdateStatus {
    data object None : AppUpdateStatus

    /**
     * A newer release, whatever its download is doing. [installInApp] false: it cannot replace the
     * installed copy, so "Download" opens [downloadPage] — the release's page or the official
     * download site, null when neither is known.
     */
    data class Offered(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val changelogUrl: String?,
        val installInApp: Boolean,
        val downloadPage: String?,
        val download: UpdateDownload,
    ) : AppUpdateStatus
}

sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    data class Running(val done: Long, val total: Long) : UpdateDownload
    data object Ready : UpdateDownload

    /** "Install now" was tapped and the installer is running; the app relaunches when it is done. */
    data object Installing : UpdateDownload
    data class Failed(val code: String) : UpdateDownload

    /** The downloaded installer is intact but installing it failed; "Install now" tries again. */
    data class InstallFailed(val code: String) : UpdateDownload

    /** The OS will not let this app install packages until the user allows it ("Install unknown
     *  apps"). [offerBrowser]: the user has struggled with that long enough to be offered the
     *  release in the browser instead. */
    data class NeedsInstallPermission(val offerBrowser: Boolean) : UpdateDownload
}
