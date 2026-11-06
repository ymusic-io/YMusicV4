package io.ymusic.android

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.remoteconfig.DownloadMismatchException
import io.ymusic.remoteconfig.RemoteConfigData
import io.ymusic.remoteconfig.downloadResumable
import io.ymusic.ui.AppUpdateStatus
import io.ymusic.ui.AppUpdates
import io.ymusic.ui.UpdateCheck
import io.ymusic.ui.UpdateDownload
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toOkioPath
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import org.jetbrains.compose.resources.StringResource
import projectcommon.error.DISK_FULL_CODE
import projectcommon.error.NETWORK_CODE
import projectcommon.error.errorCode
import java.io.File

/**
 * Updates the app from the config service's `appUpdate`: download the APK, check it against the
 * entry's size and sha256, hand it to [PackageInstaller]. The entry needs no signature of ours —
 * the session is pinned to our package, and Android installs an update only when it is signed with
 * the installed app's certificate and has a higher versionCode.
 *
 * [configUpdates] is the one source of truth: [status] is derived from the release it offers now,
 * and every download belongs to one release (versionCode + sha256) — a config that moves on cancels
 * it, and nothing it writes afterwards reaches the dialog.
 *
 * A download or install the user asks for first needs the OS's install permission ("Install unknown
 * apps"); without it the user grants it ([allowInstall], continued in [onResume]), or takes the
 * release from the browser — offered at once for a download, and for an install once the downloaded
 * APK has waited [BROWSER_FALLBACK_AFTER_MS].
 */
class AndroidAppUpdates(
    private val context: Context,
    private val client: KmpHttpClient,
    private val currentVersionCode: Int,
    private val scope: CoroutineScope,
    private val logger: Logger?,
    /** Download as soon as an update is offered, without asking. Mirrors the user's setting. */
    private val autoDownload: () -> Boolean,
    /** The sha256 of the release whose automatic download failed ("" for none), and its setter. */
    private val autoDownloadFailed: () -> String,
    private val rememberAutoDownloadFailed: suspend (sha256: String) -> Unit,
    /** A freshly fetched config; null when none arrives in time. */
    private val freshConfig: suspend () -> RemoteConfigData?,
    /** The config's official download site, if it names one. */
    private val downloadSite: () -> String?,
    /** The config's update entry, every time the config changes. */
    configUpdates: Flow<AppUpdate?>,
) : AppUpdates {

    /** The newer release the config offers now; null when there is none. */
    private val offer = MutableStateFlow<AppUpdate?>(null)

    private class Tracked(val release: String, val download: UpdateDownload)

    /** One release's download; shown only while that release is still [offer]ed. */
    private val tracked = MutableStateFlow<Tracked?>(null)
    private val lock = Any()

    private class Following(val release: String, val scope: CoroutineScope)

    /** The offered release's scope: downloads run in it and die with it when the config moves on. */
    @Volatile private var following: Following? = null

    override val status: StateFlow<AppUpdateStatus> = combine(offer, tracked) { update, tracked ->
        if (update == null) {
            AppUpdateStatus.None
        } else {
            offered(update, tracked?.takeIf { it.release == update.release }?.download ?: UpdateDownload.Idle)
        }
    }.stateIn(scope, SharingStarted.Eagerly, AppUpdateStatus.None)

    override val blockedReason: StringResource? = null

    // Not cacheDir: the system may clear it between download and install.
    private val downloadDir = File(context.filesDir, "update")

    /** What the user asked for before being sent to grant the install permission: install, or download. */
    @Volatile private var installAfterPermission = false

    private val log get() = logger?.fork("update")

    private fun offered(update: AppUpdate, download: UpdateDownload) = AppUpdateStatus.Offered(
        versionCode = update.versionCode,
        versionName = update.versionName,
        notes = update.notes,
        changelogUrl = update.changelogUrl,
        installInApp = update.installInApp,
        downloadPage = update.downloadPage ?: downloadSite() ?: update.url,
        download = download,
    )

    init {
        scope.launch { configUpdates.collect(::accept) }
        scope.launch {
            offer.map { it?.release }.distinctUntilChanged().collectLatest { release ->
                if (release != null) follow(release)
            }
        }
    }

    private fun accept(update: AppUpdate?) {
        offer.value = update?.takeIf { it.versionCode > currentVersionCode }
    }

    /** While [release] is offered: show whether it is already downloaded, then fetch it automatically
     *  — once per release; a release whose automatic download failed waits for a tap. */
    private suspend fun follow(release: String) = coroutineScope {
        val update = offer.value?.takeIf { it.release == release } ?: return@coroutineScope
        following = Following(release, this)
        val apk = apkFile(update)
        val downloaded = update.installInApp && withContext(Dispatchers.IO) { apk.isFile && sha256Of(apk) == update.sha256 }
        set(release, if (downloaded) UpdateDownload.Ready else UpdateDownload.Idle)
        if (!downloaded && update.installInApp && autoDownload() && update.sha256 != autoDownloadFailed()) {
            start(update, auto = true)
        }
        awaitCancellation()
    }

    /** Writes [download] only while [release] is still offered. */
    private fun set(release: String, download: UpdateDownload) {
        synchronized(lock) {
            if (offer.value?.release == release) tracked.value = Tracked(release, download)
        }
    }

    /** The offered release and its download state — exactly what [status] shows. */
    private fun current(): Pair<AppUpdate, UpdateDownload>? {
        val update = offer.value ?: return null
        return update to (tracked.value?.takeIf { it.release == update.release }?.download ?: UpdateDownload.Idle)
    }

    /** Remove APKs and partial downloads this version has caught up with; a newer release's
     *  partial download stays for the next attempt to resume. */
    fun pruneDownloads() {
        downloadDir.listFiles().orEmpty().forEach { file ->
            val versionCode = file.name.removePrefix("YMusic-").substringBefore('.').substringBefore('-').toIntOrNull()
            if (versionCode == null || versionCode <= currentVersionCode) file.delete()
        }
    }

    override fun download() {
        val (update, _) = current() ?: return
        if (canInstall()) start(update, auto = false) else needInstallPermission(update, install = false)
    }

    /**
     * Fetch [update]. A failure deletes the partial file: the next attempt starts fresh. A cancelled
     * one (the config moved on) keeps it, to resume if the release comes back. An automatic download
     * that fails is remembered and the release offered the normal way — no error for something the
     * user never asked for; one the user started reports Failed.
     */
    private fun start(update: AppUpdate, auto: Boolean) {
        val release = update.release
        if (!update.installInApp) return
        val releaseScope = following?.takeIf { it.release == release }?.scope ?: return
        releaseScope.launch {
            val claimed = synchronized(lock) {
                val download = tracked.value?.takeIf { it.release == release }?.download
                val busy = download is UpdateDownload.Running || download is UpdateDownload.Ready ||
                    download is UpdateDownload.Installing || download is UpdateDownload.InstallFailed
                if (offer.value?.release != release || busy) {
                    false
                } else {
                    tracked.value = Tracked(release, UpdateDownload.Running(0, update.size))
                    true
                }
            }
            if (!claimed) return@launch
            try {
                val apk = apkFile(update)
                val downloaded = withContext(Dispatchers.IO) { apk.isFile && sha256Of(apk) == update.sha256 }
                if (!downloaded) withContext(Dispatchers.IO) { fetch(update) }
                set(release, UpdateDownload.Ready)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { partFile(update).delete() }
                if (auto) {
                    log?.log(Logger.Level.WARN, "automatic download of update ${update.versionCode} failed; offering it instead", error)
                    rememberAutoDownloadFailed(update.sha256)
                    set(release, UpdateDownload.Idle)
                } else if (error is DownloadMismatchException) {
                    log?.captureError(error, "update ${update.versionCode} failed verification")
                    set(release, UpdateDownload.Failed("E91011"))
                } else if (error.errorCode() == DISK_FULL_CODE) {
                    log?.log(Logger.Level.WARN, "downloading update ${update.versionCode} failed: storage is full", error)
                    set(release, UpdateDownload.Failed(DISK_FULL_CODE))
                } else if (error is javax.net.ssl.SSLHandshakeException) {
                    // The device doesn't trust the host (stale root store, wrong clock, intercepting network).
                    log?.log(Logger.Level.WARN, "downloading update ${update.versionCode} failed: untrusted TLS", error)
                    set(release, UpdateDownload.Failed("E91014"))
                } else if (error.errorCode() == NETWORK_CODE) {
                    log?.log(Logger.Level.WARN, "downloading update ${update.versionCode} hit a network failure", error)
                    set(release, UpdateDownload.Failed(NETWORK_CODE))
                } else {
                    log?.captureError(error, "downloading update ${update.versionCode} failed")
                    set(release, UpdateDownload.Failed("E91012"))
                }
            }
        }
    }

    private suspend fun fetch(update: AppUpdate) {
        downloadDir.mkdirs()
        val part = partFile(update)
        // Another release's leftovers can never be resumed into this one.
        downloadDir.listFiles().orEmpty().filter { it.name.endsWith(".part") && it != part }.forEach { it.delete() }
        downloadResumable(client, update.url, part.toOkioPath(), FileSystem.SYSTEM, update.size, update.sha256) { done ->
            set(update.release, UpdateDownload.Running(done, update.size))
        }
        check(part.renameTo(apkFile(update))) { "could not move the APK into place" }
    }

    /** Opens the system install prompt; Android restarts nothing — the user reopens the app. */
    override fun installAndRestart() {
        val (update, download) = current() ?: return
        if (download !is UpdateDownload.Ready && download !is UpdateDownload.InstallFailed) return
        if (!canInstall()) return needInstallPermission(update, install = true)
        set(update.release, UpdateDownload.Installing)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(apkFile(update)) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val code = when {
                    isStorageFull(error) -> {
                        log?.log(Logger.Level.WARN, "installing update ${update.versionCode} failed: storage is full", error)
                        DISK_FULL_CODE
                    }
                    isDeviceInstallBlock(error) -> {
                        log?.log(Logger.Level.WARN, "device refused installing update ${update.versionCode}", error)
                        "E91015"
                    }
                    else -> {
                        log?.captureError(error, "installing update ${update.versionCode} failed")
                        "E91013"
                    }
                }
                set(update.release, UpdateDownload.InstallFailed(code))
            }
        }
    }

    /** The install session ended without replacing this process: offer it again, and say why unless the user cancelled. */
    fun onInstallEnded(status: Int) {
        val (update, download) = current() ?: return
        if (download !is UpdateDownload.Installing) return
        val next = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateDownload.Ready
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateDownload.InstallFailed(DISK_FULL_CODE)
            else -> UpdateDownload.InstallFailed("E91016")
        }
        set(update.release, next)
    }

    private fun needInstallPermission(update: AppUpdate, install: Boolean) {
        installAfterPermission = install
        val apk = apkFile(update)
        // A download held back by the permission never writes the APK, so it can't age into the
        // fallback; the browser is offered at once. The APK's mtime is when its download finished.
        val offerBrowser = !install || (apk.isFile && System.currentTimeMillis() - apk.lastModified() >= BROWSER_FALLBACK_AFTER_MS)
        set(update.release, UpdateDownload.NeedsInstallPermission(offerBrowser = offerBrowser))
    }

    override fun allowInstall() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        val opened = context.startFirstActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (!opened) {
            log?.log(Logger.Level.WARN, "no settings screen grants the install permission; offering the browser")
            val (update, download) = current() ?: return
            if (download is UpdateDownload.NeedsInstallPermission) {
                set(update.release, UpdateDownload.NeedsInstallPermission(offerBrowser = true))
            }
        }
    }

    /** Back in the app: a request held for the install permission continues once it is granted. */
    fun onResume() {
        val (update, download) = current() ?: return
        if (download !is UpdateDownload.NeedsInstallPermission || !canInstall()) return
        if (installAfterPermission) {
            set(update.release, UpdateDownload.Ready)
            installAndRestart()
        } else {
            set(update.release, UpdateDownload.Idle)
            start(update, auto = false)
        }
    }

    private fun canInstall(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        // API 24-25 have no per-app grant, only the global "Unknown sources" switch.
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    }

    /** PackageInstaller's "Requested internal only, but not enough space" and the like. */
    private fun isStorageFull(error: Exception): Boolean =
        error is java.io.IOException && error.message?.contains("not enough space", ignoreCase = true) == true

    /** Device state the app can't change; the official-site link the dialog offers is the way out. */
    private fun isDeviceInstallBlock(error: Exception): Boolean = when (error) {
        // Installed on adopted/SD storage the session can't move: "Cannot automatically move … to internal storage".
        is java.io.IOException -> error.message?.startsWith("Cannot automatically move") == true
        // "Can't install packages while in secure FRP", device-policy restrictions.
        is SecurityException -> true
        // The system handed back a null installer session (IPackageInstallerSession.openWrite on null).
        is NullPointerException -> error.stackTrace.any { it.className.startsWith("android.content.pm.PackageInstaller") }
        else -> false
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Without it a session installs whatever package the APK holds, as a new app.
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                // fsync needs the stream open: closing the okio sink would close [out] with it.
                val sink = out.sink().buffer()
                apk.source().use { sink.writeAll(it) }
                sink.flush()
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    override suspend fun checkNow(): UpdateCheck {
        accept((freshConfig() ?: return UpdateCheck.Failed).appUpdate)
        return if (offer.value == null) UpdateCheck.UpToDate else UpdateCheck.Found
    }

    /** Named after the version AND hash: a re-published release must not continue the old bytes. */
    private fun partFile(update: AppUpdate) = File(downloadDir, "YMusic-${update.versionCode}-${update.sha256.take(16)}.apk.part")

    private fun apkFile(update: AppUpdate) = File(downloadDir, "YMusic-${update.versionCode}.apk")
}

private const val BROWSER_FALLBACK_AFTER_MS = 60 * 60 * 1000L

/** One published build: a re-published version is a different release. */
private val AppUpdate.release: String get() = "$versionCode-$sha256"

/** [PackageInstaller]'s answer: shows the system confirmation the session is waiting on. */
class UpdateInstallReceiver : BroadcastReceiver() {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (error: ActivityNotFoundException) {
                    val app = context.applicationContext as? YMusicApplication ?: return
                    app.rootLogger.fork("update").log(Logger.Level.WARN, "no screen confirms the package install", error)
                    app.services.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()?.updates
                        ?.onInstallEnded(PackageInstaller.STATUS_FAILURE)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val app = context.applicationContext as? YMusicApplication ?: return
                app.rootLogger.fork("update").log(
                    Logger.Level.WARN,
                    "package install ended with status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
                )
                app.services.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()?.updates
                    ?.onInstallEnded(status)
            }
        }
    }
}

private fun sha256Of(file: File): String {
    val hashing = HashingSink.sha256(blackholeSink())
    hashing.buffer().use { out -> file.source().use { out.writeAll(it) } }
    return hashing.hash.hex()
}
