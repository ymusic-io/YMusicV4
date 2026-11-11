package io.ymusic.desktop.update

import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.remoteconfig.RemoteConfigData
import io.ymusic.ui.AppUpdateStatus
import io.ymusic.ui.AppUpdates
import io.ymusic.ui.UpdateCheck
import io.ymusic.ui.UpdateDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.system.exitProcess

/**
 * Binds [UpdateService] to the shared UI's [AppUpdates].
 *
 * Dismissals live here rather than in the service: they are a UI concern (this user said "later"
 * to this version), and they must not stop a background download the user already opted into.
 */
class DesktopAppUpdates(
    private val service: UpdateService,
    private val scope: CoroutineScope,
    /** A freshly fetched config; null when none arrives in time. */
    private val freshConfig: suspend () -> RemoteConfigData?,
    /** The config's official download site, if it names one. */
    private val downloadSite: () -> String?,
) : AppUpdates {

    private val _status = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.None)
    override val status: StateFlow<AppUpdateStatus> = _status
    override val blockedReason: StringResource? get() = service.selfUpdateBlockedReason

    init {
        scope.launch {
            service.state.collect { s -> _status.value = statusOf(s) }
        }
    }

    private fun statusOf(s: UpdateState): AppUpdateStatus = when (s) {
        UpdateState.Idle -> AppUpdateStatus.None
        is UpdateState.Available -> offered(s.update, UpdateDownload.Idle)
        is UpdateState.Downloading -> offered(s.update, UpdateDownload.Running(s.done, s.total))
        is UpdateState.Ready -> offered(s.update, UpdateDownload.Ready)
        is UpdateState.Installing -> offered(s.update, UpdateDownload.Installing)
        is UpdateState.InstallFailed -> offered(s.update, UpdateDownload.InstallFailed(s.code))
        is UpdateState.Failed -> offered(s.update, UpdateDownload.Failed(s.code), trusted = s.trusted)
    }

    /** An untrusted entry's own links are never followed: only the official site is offered. */
    private fun offered(update: AppUpdate, download: UpdateDownload, trusted: Boolean = true) = AppUpdateStatus.Offered(
        versionCode = update.versionCode,
        versionName = update.versionName,
        notes = update.notes,
        changelogUrl = update.changelogUrl.takeIf { trusted },
        installInApp = trusted && update.installInApp,
        downloadPage = if (trusted) update.downloadPage ?: downloadSite() ?: update.url else downloadSite(),
        download = download,
    )

    override suspend fun checkNow(): UpdateCheck {
        service.accept((freshConfig() ?: return UpdateCheck.Failed).appUpdate)
        return if (service.hasOffer) UpdateCheck.Found else UpdateCheck.UpToDate
    }

    override fun download() {
        service.download()
    }

    override fun installAndRestart() {
        // The new version is already starting; this process must go, or two copies fight over
        // the data dir — and on Windows the installer waits for it.
        scope.launch { if (service.install()) exitProcess(0) }
    }
}
