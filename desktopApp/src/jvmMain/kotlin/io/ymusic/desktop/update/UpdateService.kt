package io.ymusic.desktop.update

import io.ymusic.dynamicengine.verifyBundleSignature
import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.remoteconfig.DownloadMismatchException
import io.ymusic.remoteconfig.downloadResumable
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import okio.Path.Companion.toOkioPath
import org.jetbrains.compose.resources.StringResource
import projectcommon.error.NETWORK_CODE
import projectcommon.error.errorCode
import java.io.File

/** What the UI shows. A verified download survives a restart, so [Ready] can be the first state. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data class Available(val update: AppUpdate) : UpdateState

    data class Downloading(val update: AppUpdate, val done: Long, val total: Long) : UpdateState

    data class Ready(val update: AppUpdate, val installer: File) : UpdateState

    data class Installing(val update: AppUpdate) : UpdateState

    /** [code] is shown to the user. [trusted] false: [update]'s signature did not verify, so nothing
     *  of it may be downloaded or opened — only its version and notes are shown. */
    data class Failed(val code: String, val update: AppUpdate, val trusted: Boolean = true) : UpdateState
}

/**
 * Turns the config service's `appUpdate` into an installed new version: verify the entry's
 * signature, download the installer, check its hash, hand it to the platform [Installer].
 *
 * The config document is unsigned and cached on disk, so the entry is only trusted through
 * [publicKey]: an installer runs only if its sha256 is the one signed for this platform and version.
 *
 * [configUpdates] is the one source of truth: [state] is derived from the release it offers now, and
 * every download belongs to one release (versionCode + sha256) — a config that moves on cancels it,
 * and nothing it writes afterwards shows. Only a running install holds still until it ends.
 */
class UpdateService internal constructor(
    private val client: KmpHttpClient,
    private val publicKey: String,
    private val currentVersionCode: Int,
    /** `macos-arm64`, …: part of the signed message, so one platform's build cannot pass as another's. */
    private val platform: String,
    /** Where installers are downloaded; nothing else lives here. */
    private val downloadDir: File,
    private val installer: Installer,
    private val scope: CoroutineScope,
    /** The config's update entry, every time the config changes. */
    configUpdates: Flow<AppUpdate?>,
    private val logger: Logger? = null,
    /** Download as soon as an update is offered, without asking. Mirrors the user's setting. */
    private val autoDownload: () -> Boolean = { false },
    /** The sha256 of the release whose automatic download failed ("" for none), and its setter. */
    private val autoDownloadFailed: () -> String = { "" },
    private val rememberAutoDownloadFailed: suspend (sha256: String) -> Unit = {},
) {
    private class Offer(val update: AppUpdate, val trusted: Boolean)

    /** The newer release the config offers now; null when there is none. */
    private val offer = MutableStateFlow<Offer?>(null)

    private sealed interface Step {
        data object Idle : Step

        data class Downloading(val done: Long, val total: Long) : Step

        data class Ready(val installer: File) : Step

        data class Failed(val code: String) : Step
    }

    private class Tracked(val release: String, val step: Step)

    /** One release's download; shown only while that release is still [offer]ed. */
    private val tracked = MutableStateFlow<Tracked?>(null)

    /** The release whose installer is running: shown whatever the config says meanwhile. */
    private val installing = MutableStateFlow<AppUpdate?>(null)
    private val lock = Any()

    private class Following(val release: String, val scope: CoroutineScope)

    /** The offered release's scope: downloads run in it and die with it when the config moves on. */
    @Volatile private var following: Following? = null

    /** An entry that failed verification: reported once, not on every config refresh. */
    private var rejected: AppUpdate? = null

    val state: StateFlow<UpdateState> = combine(offer, tracked, installing) { offer, tracked, installing ->
        stateOf(offer, tracked, installing)
    }.stateIn(scope, SharingStarted.Eagerly, UpdateState.Idle)

    val selfUpdateBlockedReason: StringResource? get() = installer.blockedReason

    /** Without a pinned key nothing can be verified, so no update is ever offered. */
    val isEnabled: Boolean get() = publicKey.isNotBlank()

    /** Whether the config offers a newer release right now, trusted or not. */
    val hasOffer: Boolean get() = offer.value != null

    private val log get() = logger?.fork("update")

    init {
        if (isEnabled) {
            scope.launch { configUpdates.collect(::accept) }
            scope.launch {
                offer.map { it?.takeIf { offer -> offer.trusted }?.update?.release }.distinctUntilChanged().collectLatest { release ->
                    if (release != null) follow(release)
                }
            }
        }
    }

    private fun stateOf(offer: Offer?, tracked: Tracked?, installing: AppUpdate?): UpdateState {
        if (installing != null) return UpdateState.Installing(installing)
        val update = offer?.update ?: return UpdateState.Idle
        if (!offer.trusted) return UpdateState.Failed("E91001", update, trusted = false)
        return when (val step = tracked?.takeIf { it.release == update.release }?.step ?: Step.Idle) {
            Step.Idle -> UpdateState.Available(update)
            is Step.Downloading -> UpdateState.Downloading(update, step.done, step.total)
            is Step.Ready -> UpdateState.Ready(update, step.installer)
            is Step.Failed -> UpdateState.Failed(step.code, update)
        }
    }

    /** Take the config's latest answer; only a newer, correctly signed entry is ever downloaded. */
    fun accept(update: AppUpdate?) {
        if (!isEnabled) return
        synchronized(lock) {
            if (update == null || update.versionCode <= currentVersionCode) {
                offer.value = null
                return
            }
            if (offer.value?.update == update) return
            val signed = "app-$platform"
            val trusted = update != rejected &&
                verifyBundleSignature(publicKey, signed, update.versionCode.toLong(), update.sha256, update.signature)
            if (!trusted && update != rejected) {
                log?.captureError(
                    UpdateVerificationException("$signed ${update.versionCode}: signature does not match the pinned key"),
                )
                rejected = update
            }
            offer.value = Offer(update, trusted)
        }
    }

    /** While [release] is offered: show whether it is already downloaded, then fetch it automatically
     *  — once per release; a release whose automatic download failed waits for a tap. */
    private suspend fun follow(release: String) = coroutineScope {
        val update = offer.value?.takeIf { it.trusted && it.update.release == release }?.update ?: return@coroutineScope
        following = Following(release, this)
        val file = installerFile(update)
        val downloaded = update.installInApp && withContext(Dispatchers.IO) { file.isFile && sha256Of(file) == update.sha256 }
        set(release, if (downloaded) Step.Ready(file) else Step.Idle)
        if (!downloaded && update.installInApp && autoDownload() && selfUpdateBlockedReason == null &&
            update.sha256 != autoDownloadFailed()
        ) {
            start(update, auto = true)
        }
        awaitCancellation()
    }

    /** Writes [step] only while [release] is still offered. */
    private fun set(release: String, step: Step) {
        synchronized(lock) {
            if (offer.value?.update?.release == release) tracked.value = Tracked(release, step)
        }
    }

    /** Fetch the offered release's installer — the "Download" button. */
    fun download() {
        val update = offer.value?.takeIf { it.trusted }?.update ?: return
        start(update, auto = false)
    }

    /**
     * Fetch [update]'s installer and verify it against the signed hash. A failure deletes the
     * partial file: the next attempt starts fresh. A cancelled one (the config moved on) keeps it,
     * to resume if the release comes back. An [auto]matic download that fails is remembered and the
     * release offered the normal way; one the user started reports Failed.
     */
    private fun start(update: AppUpdate, auto: Boolean) {
        val release = update.release
        if (!update.installInApp) return
        val releaseScope = following?.takeIf { it.release == release }?.scope ?: return
        releaseScope.launch {
            val claimed = synchronized(lock) {
                val step = tracked.value?.takeIf { it.release == release }?.step
                val busy = step is Step.Downloading || step is Step.Ready || installing.value != null
                if (offer.value?.update?.release != release || busy) {
                    false
                } else {
                    tracked.value = Tracked(release, Step.Downloading(0, update.size))
                    true
                }
            }
            if (!claimed) return@launch
            try {
                val file = withContext(Dispatchers.IO) { fetch(update) }
                set(release, Step.Ready(file))
                log?.log(Logger.Level.INFO, "update ${update.versionCode} downloaded and verified")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { partFile(update).delete() }
                if (auto) {
                    log?.log(Logger.Level.WARN, "automatic download of update ${update.versionCode} failed; offering it instead", error)
                    rememberAutoDownloadFailed(update.sha256)
                    set(release, Step.Idle)
                } else if (error is DownloadMismatchException) {
                    log?.captureError(error, "update ${update.versionCode} failed verification")
                    set(release, Step.Failed("E91002"))
                } else if (error.errorCode() == NETWORK_CODE) {
                    log?.log(Logger.Level.WARN, "downloading update ${update.versionCode} hit a network failure", error)
                    set(release, Step.Failed(NETWORK_CODE))
                } else {
                    log?.captureError(error, "downloading update ${update.versionCode} failed")
                    set(release, Step.Failed("E91003"))
                }
            }
        }
    }

    private suspend fun fetch(update: AppUpdate): File {
        downloadDir.mkdirs()
        val target = installerFile(update)
        val part = partFile(update)
        // Another release's leftovers can never be resumed into this one.
        downloadDir.listFiles().orEmpty().filter { it.name.endsWith(".part") && it != part }.forEach { it.deleteRecursively() }
        downloadResumable(client, update.url, part.toOkioPath(), FileSystem.SYSTEM, update.size, update.sha256) { done ->
            set(update.release, Step.Downloading(done, update.size))
        }
        check(part.renameTo(target)) { "could not move the installer into place" }
        return target
    }

    /**
     * Run the downloaded installer. True: the new version is starting and the caller must exit
     * at once. False: nothing changed (not ready, or the user declined the admin prompt), and the
     * state is again whatever the config offers now.
     */
    suspend fun install(): Boolean {
        val (update, file) = synchronized(lock) {
            if (installing.value != null) return false
            val update = offer.value?.takeIf { it.trusted }?.update ?: return false
            val ready = tracked.value?.takeIf { it.release == update.release }?.step as? Step.Ready ?: return false
            installing.value = update
            update to ready.installer
        }
        return try {
            installer.install(file, update).also { started ->
                if (!started) installing.value = null
            }
        } catch (cancel: CancellationException) {
            installing.value = null
            throw cancel
        } catch (error: Exception) {
            log?.captureError(error, "installing update ${update.versionCode} failed")
            set(update.release, Step.Failed("E91004"))
            installing.value = null
            false
        }
    }

    /** Remove installers and partial downloads this version has caught up with; a newer release's
     *  partial download stays for the next attempt to resume. */
    fun pruneDownloads() {
        downloadDir.listFiles().orEmpty().forEach { file ->
            val versionCode = file.name.removePrefix("YMusic-").substringBefore('.').substringBefore('-').toIntOrNull()
            if (versionCode == null || versionCode <= currentVersionCode) file.deleteRecursively()
        }
    }

    /** Named after the version AND hash: a re-published release must not continue the old bytes. */
    private fun partFile(update: AppUpdate) =
        File(downloadDir, "YMusic-${update.versionCode}-${update.sha256.take(16)}.${installer.extension}.part")

    private fun installerFile(update: AppUpdate) =
        File(downloadDir, "YMusic-${update.versionCode}.${installer.extension}")
}

class UpdateVerificationException(message: String) : Exception(message)

/** One published build: a re-published version is a different release. */
private val AppUpdate.release: String get() = "$versionCode-$sha256"
