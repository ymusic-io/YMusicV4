package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.imageloader.ImageLoader
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.player.LoopMode
import io.ymusic.player.PlayableItem
import io.ymusic.player.Player
import io.ymusic.remoteconfig.ApiService
import io.ymusic.remoteconfig.RemoteConfigData
import io.ymusic.remoteconfig.StatUsage
import io.ymusic.settings.AppSettings
import io.ymusic.settings.InMemorySettingsStore
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.mapState
import io.ymusic.settings.put
import io.ymusic.ui.playhistory.PlayAgainRecorder
import io.ymusic.ui.playhistory.PlayHistoryStore
import io.ymusic.ui.sponsorblock.InMemorySponsorBlockStore
import io.ymusic.ui.sponsorblock.ServiceSponsorBlockApi
import io.ymusic.ui.sponsorblock.SponsorBlockApi
import io.ymusic.ui.sponsorblock.SponsorBlockService
import io.ymusic.ui.sponsorblock.SponsorBlockStore
import kmpcommon.WorkerThread
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The player-quality settings key — public because shell settings stores (iOS NSUserDefaults
 *  seeding) enumerate the keys they persist. */
const val PlaybackQualitySettingKey = "player.preferVideoQuality"
const val PlaybackAudioOnlyKey = "player.audioOnly"
const val PlaybackVolumeKey = "player.volume"
const val PlaybackSpeedKey = "player.speed"
const val PlaybackPitchKey = "player.pitch"
const val PlaybackEqKey = "player.eq"
const val PlaybackShuffleKey = "player.shuffle"
const val PlaybackLoopModeKey = "player.loopMode"

/** How a shell scan ended; the shared UI renders it as the user-facing summary. */
sealed interface ScanResult {
    data class Done(val scanned: Int, val added: Int, val updated: Int, val removed: Int, val failed: Int) : ScanResult

    /** A rescan of every library root found none to rescan. */
    data object NoFolders : ScanResult

    /** The metadata extractor (ffmpeg) is missing from this install. */
    data object ExtractorMissing : ScanResult
}

/**
 * A shell scan/rescan action: reports the running scanned-file count through [onProgress] while it
 * works, and returns how it ended (or null when a folder pick was canceled).
 */
typealias ScanAction = suspend (onProgress: (scanned: Int) -> Unit) -> ScanResult?

/**
 * Re-scan already-indexed folders. [folderUri] null means every library root; otherwise only that
 * folder and its children. Reports the running scanned-file count through [onProgress] and returns
 * how it ended.
 */
typealias RescanAction = suspend (folderUri: String?, onProgress: (scanned: Int) -> Unit) -> ScanResult?

/**
 * Everything the shared UI needs, built once by the platform shell (the composition root): the
 * running engine manager, the player facade, and the platform video surface. [playerSurface] hosts
 * the **one** shared video container the shell handed to the engines (a Swing panel on desktop, a
 * `FrameLayout` on Android) — each engine attaches its own surface (MSE / SurfaceView / ffmpeg)
 * into it and owns that surface's visibility, so the UI never needs to know which engine is active.
 *
 * [mediaStore] backs the Library screen; a shell without one (no local library yet) leaves it null.
 * [playHistory] is watch-time ranking (Library Recent play / Top tracks, Discover Top tracks) and the
 * "Play again" saved queues; null skips recording both.
 * [scanLibraryFolder] is the shell's directory-pick + scan action (desktop's FileDialog + media
 * scanner): it returns a user-facing summary line, or null when the pick was canceled.
 */
class AppGraph(
    val engines: DynamicEngineManager,
    val player: Player,
    val mediaStore: BetterMediaStore? = null,
    val playHistory: PlayHistoryStore? = null,
    /**
     * A throwaway guest launch: no sign-in, no account, no YouTube play history (Top tracks, Play
     * again) — nothing of it outlives the process. The local Library works as for anyone.
     */
    val guest: Boolean = false,
    val scanLibraryFolder: ScanAction? = null,
    /**
     * Opens the OS folder picker and registers the chosen folder as a Library bucket (a lightweight
     * `upsertBucket`, so it works even for an empty folder — no media scan), returning its bucket uri,
     * or null if the user canceled. Unlike [scanLibraryFolder] (which scans + reports a summary for the
     * Library pill), the caller needs the picked folder's identity — used to set a download location.
     * Null on shells without a folder picker.
     */
    val libraryFolderPicker: (suspend () -> String?)? = null,
    /** Whether the app can write into a folder [libraryFolderPicker] returned; Android checks the SAF grant,
     *  which some providers refuse (read-only). */
    val canWriteFolder: (String) -> Boolean = { true },
    /** Test-writes (creates, writes, deletes) a file in a would-be download folder; null when that works. */
    val probeDownloadFolder: suspend (String) -> FolderWriteProblem? = { null },
    /**
     * Re-scans already-added folders in place (no folder pick). [folderUri] null means every library
     * root; otherwise only that folder and its children. Null when the shell can't scan.
     */
    val rescanLibrary: RescanAction? = null,
    /**
     * Soft-deletes every Library folder whose storage grant the app no longer holds (Android SAF), so
     * its items stop showing as playable; re-adding the folder revives them. Null where grants can't be lost.
     */
    val pruneUngrantedFolders: (suspend () -> Unit)? = null,
    /**
     * Permanently deletes the item's FILE from storage (okio delete on desktop, SAF
     * DocumentsContract on Android) — the destructive half of the Library's "Remove from disk";
     * the UI removes the media-store row only after this returns. Throws on failure (surfaced in
     * the confirm dialog). Null hides the menu action entirely.
     */
    val deleteFromDisk: (suspend (io.ymusic.mediastore.model.MediaStoreItem) -> Unit)? = null,
    /** Reveal a file (by its uri) in the OS file manager — desktop only (mac Finder / file explorer);
     *  null on mobile, which hides the "Reveal in Finder" download action. */
    val revealInFolder: (suspend (uri: String) -> Unit)? = null,
    /** Opens the system share sheet with a text (a link). Null = the UI copies it to the clipboard instead. */
    val shareText: ((text: String) -> Unit)? = null,
    /** Renames downloaded files in place (Downloads' "Rename"); null hides the action. */
    val fileRenaming: FileRenaming? = null,
    /** Display version for the drawer header (e.g. "4.0.1 · arm64"); null hides the line. */
    val appVersion: String? = null,
    /** Settings › Versions: the build's versionName and versionCode; null hides the app line. */
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    /** Settings › Versions: the engine.js / player.js revisions actually running. */
    val runningBundles: io.ymusic.remoteconfig.RunningBundles? = null,
    /** Thumbnail loading; null keeps the placeholder boxes (e.g. a shell without a cache dir yet). */
    val imageLoader: ImageLoader? = null,
    /** The app's root logger (Sentry-backed in the shells); screens fork their own scope off it. */
    val logger: kmpcommon.log.Logger? = null,
    /** App settings (UI language, theme, …); the in-memory default works but doesn't persist. */
    val settings: SettingsStore = InMemorySettingsStore(),
    /**
     * True when [playerSurface] may be shown at any size (MSE blit / ffmpeg frames scale) —
     * the mini player then hosts it as its 36dp art tile. False keeps a real-size docked preview
     * instead of shrinking the surface into the mini-player art.
     */
    val surfaceScalesDown: Boolean = false,
    /**
     * The platform browser WebView for the YouTube sign-in flow, provided by the shell (like
     * [playerSurface]) so the shared UI carries no WebView dependency. Null hosts fall through to
     * [cookieSignIn] (desktop QR / paste) or a "sign-in unavailable" note. It MUST use the
     * platform's default cookie store — the same jar the engine reads — so sign-in cookies reach the
     * login gate.
     */
    val loginBrowser: LoginBrowser? = null,
    /**
     * Desktop YouTube sign-in (QR / DevTools paste), provided by the shell like [loginBrowser].
     * Null on mobile.
     */
    val cookieSignIn: CookieSignIn? = null,
    /**
     * Reads the current YouTube/Google cookies from the shell's store. Null hides Settings →
     * Link devices together with [scanQrCode].
     */
    val exportSessionCookies: (suspend () -> List<SessionCookie>)? = null,
    /**
     * Opens the camera, scans one QR, and returns its raw payload (null if the user canceled).
     * Null hides Settings → Link devices together with [exportSessionCookies].
     */
    val scanQrCode: (suspend () -> String?)? = null,
    /**
     * OS PIN / biometric / passcode prompt. True if the user unlocked, false if they canceled.
     * Throws with message `no-lock` when the phone has no screen lock. Null hides Settings →
     * Link devices together with [exportSessionCookies] and [scanQrCode].
     */
    val confirmDeviceOwner: (suspend (title: String, subtitle: String) -> Boolean)? = null,
    /**
     * The offline-download queue service; null on a shell that can't download (headless test
     * graph) — the Downloads screen then shows an unavailable note. Built once per shell,
     * like [mediaStore].
     */
    val downloads: io.ymusic.download.DownloadService? = null,
    /**
     * Sends the app logs as one anonymous report (Sentry in the shells) and returns its id, which a
     * message sent from the Feedback section links to; null when nothing was sent. Null drops the
     * reports-off dialog and the message option from the Feedback section.
     */
    val captureFeedbackReport: (suspend () -> String?)? = null,
    /** Sends a message typed in the Feedback section along with the report it belongs to; null hides that option. */
    val sendFeedbackMessage: ((reportId: String, message: String) -> Unit)? = null,
    /** The OS and CPU for the Feedback section's info (e.g. "Android 16 · arm64-v8a"); null omits them. */
    val platformInfo: String? = null,
    /** The OS "save as" picker: a sink into the chosen file (the caller closes it), null when canceled.
     *  Null hides playlist backup. */
    val createDocument: (suspend (suggestedName: String, mimeType: String) -> okio.Sink?)? = null,
    /** The OS file picker: a source over the chosen file (the caller closes it), null when canceled.
     *  Null hides playlist restore. */
    val openDocument: (suspend (mimeTypes: List<String>) -> okio.Source?)? = null,
    /** Playlists other apps registered with the OS; null hides "Import from device". */
    val devicePlaylists: DevicePlaylistSource? = null,
    /**
     * The shell's self-updater; null where the platform store owns updates (iOS) or the shell
     * has none — the update prompt and its setting then never appear.
     */
    val updates: AppUpdates? = null,
    /** The YMusic service's latest config (flags, links); null without a service client. */
    val remoteConfig: SharedFlow<RemoteConfigData>? = null,
    /** Premium: on Android, the "Support Development" app is installed. Shows the drawer's badge. */
    val premium: StateFlow<Boolean> = MutableStateFlow(false),
    /** Opens the donation screen; null where there is none (only Android has one) — the drawer's
     *  Donate item then hides. */
    val openDonation: (() -> Unit)? = null,
    /**
     * Anonymous usage stats — `stats?.record("play")` from any screen. The consent gate and the
     * no-service case are its own business ([StatUsage.record] returns before doing any work);
     * null only in shells and tests that wire no recorder at all.
     */
    val stats: StatUsage? = null,
    /** The YMusic service (the QR sign-in relay rides it); null when this build has no service url. */
    val api: ApiService? = null,
    /**
     * Desktop: true while the top-level window is being resized. The shell sets this from
     * `componentResized` and clears it after 300ms without another event. Unused on mobile.
     */
    val windowResizing: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** The shell honors [io.ymusic.settings.AppSettings.systemAudioEffects] (Android); false hides the setting. */
    val systemAudioEffects: Boolean = false,
    /** The shell honors [io.ymusic.settings.AppSettings.audioFocus] (Android); false hides the setting. */
    val audioFocusSetting: Boolean = false,
    /** The player cache, for its size limit and Settings' usage line; null hides the setting. */
    val playerCache: io.ymusic.ytmedia.cache.MediaCache? = null,
    /**
     * SponsorBlock skip-segment fetch, over [api]; null (no service) skips nothing. Shells pass a
     * SQLite [sponsorBlockStore] for cache + per-video whitelist.
     */
    sponsorBlockApi: SponsorBlockApi? = api?.let(::ServiceSponsorBlockApi),
    sponsorBlockStore: SponsorBlockStore = InMemorySponsorBlockStore(),
    // Kept last so shells pass it as the trailing lambda.
    val playerSurface: @Composable (modifier: Modifier) -> Unit,
) {
    val sponsorBlock = SponsorBlockService(sponsorBlockApi, sponsorBlockStore, settings, logger)

    /** The Quick download preset, [QuickDownloadPreset.default] until one is saved. Change it with [saveQuickDownload]. */
    val quickDownload: StateFlow<QuickDownloadPreset> =
        settings.flow(AppSettings.quickDownloadPreset).mapState { QuickDownloadPreset.decode(it, downloads?.ffmpegAvailable == true) }

    suspend fun saveQuickDownload(preset: QuickDownloadPreset) = settings.put(AppSettings.quickDownloadPreset, preset.encode())
    /**
     * Bumped after a locale pick landed in the engine (SettingsScreen). The content locale itself
     * LIVES in the engine — the app only hears "it changed" and throws locale-shaped caches away.
     */
    val pip = PipCoordinator(player, settings)

    /** Uris readable only through a temporary grant ("Open with" a file), kept out of Play again, which outlives the grant. */
    val temporaryUris = MutableStateFlow<Set<String>>(emptySet())

    /** Per process, so the notice outlives an activity recreation. */
    internal val upgradeNotice by lazy { UpgradeNotice(settings, appVersionCode) }

    /** One per process — a second instance double-captures playback failures and double-credits watch time.
     *  Lazy: its collectors start at once and must only see a fully constructed graph. */
    val playback by lazy {
        val onFailure = CoroutineExceptionHandler { _, e -> logger?.fork("playback")?.captureError(e, "playback coordinator failed") }
        PlaybackCoordinator(this, CoroutineScope(SupervisorJob() + Dispatchers.Default + onFailure))
    }

    init {
        // Process-lifetime like the graph itself: every queue is recorded, whatever screen is up.
        playHistory?.takeUnless { guest }?.let {
            PlayAgainRecorder(player, it.playAgain, logger?.fork("play-again")) { item ->
                item is PlayableItem.LocalMedia && item.uri in temporaryUris.value
            }
                .start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
        // Here, not in App: Android Auto can start playback without the UI ever composing.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val savedLoop = settings.string(PlaybackLoopModeKey, "").value
            player.setLoopMode(LoopMode.entries.firstOrNull { it.name == savedLoop } ?: LoopMode.None)
            player.setShuffle(settings.string(PlaybackShuffleKey, "0").value == "1")
            player.queue.map { it.shuffle to it.loopMode }.distinctUntilChanged().collect { (shuffle, loop) ->
                settings.putString(PlaybackShuffleKey, if (shuffle) "1" else "0")
                settings.putString(PlaybackLoopModeKey, loop.name)
            }
        }
    }

    /** Fullscreen only: stretch the picture to the hole. Mini / Now Playing always fit. */
    val videoFillScreen = mutableStateOf(false)

    /** Fullscreen only: hold the current orientation regardless of the sensor and the system lock. */
    val videoRotationLocked = mutableStateOf(false)

    val localeEpoch = MutableStateFlow(0)

    /** App-wide reload signal (desktop F5/Cmd+R): the visible feed surface refetches. */
    val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }
}

/** Renaming a media file in its own folder. */
@WorkerThread
interface FileRenaming {
    /** The file's current name, extension included; null when it is gone. */
    fun nameOf(uri: String): String?

    /** Renames the file to [newName] (extension included) and returns its new uri. Throws on failure. */
    fun rename(uri: String, newName: String): String
}
