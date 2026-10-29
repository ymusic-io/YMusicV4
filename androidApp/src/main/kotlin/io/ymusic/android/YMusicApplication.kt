package io.ymusic.android

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.sentry.kotlin.multiplatform.Sentry
import io.ymusic.download.DownloadChange
import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DefaultJsSourceProvider
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EMBEDDED_PLAYER_JS_VERSION
import io.ymusic.dynamicengine.ENGINE_BUNDLE
import io.ymusic.dynamicengine.JsSourceProvider
import io.ymusic.dynamicengine.androidEngineCacheStore
import io.ymusic.dynamicengine.player.bindMseNativeLogs
import io.ymusic.dynamicengine.playerJsSource
import io.ymusic.imageloader.DiskScaledImageCache
import io.ymusic.imageloader.DiskVariantIndex
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.ImageSourceCache
import io.ymusic.imageloader.defaultMemoryCacheBudget
import io.ymusic.imageloader.fileFetcher
import io.ymusic.imageloader.networkFetcher
import io.ymusic.mediascanner.DocumentInfo
import io.ymusic.mediascanner.FfmpegMetadataExtractor
import io.ymusic.mediascanner.MediaScanner
import io.ymusic.mediascanner.SafDocumentsProvider
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreAndroidDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mse.MseDecoderCaps
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.LocalPlayerContext
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackState
import io.ymusic.player.Player
import io.ymusic.player.PlayerEngines
import io.ymusic.player.createLocalPlayer
import io.ymusic.player.createPlayer
import io.ymusic.player.isPlaying
import io.ymusic.player.mse.MsePlayer
import io.ymusic.player.showsVideo
import io.ymusic.remoteconfig.ApiService
import io.ymusic.remoteconfig.HttpApiService
import io.ymusic.remoteconfig.RemoteConfig
import io.ymusic.remoteconfig.RunningBundles
import io.ymusic.remoteconfig.StatUsage
import io.ymusic.settings.AppSettings
import io.ymusic.settings.anonymousDeviceId
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.telemetry.SentryLogger
import io.ymusic.telemetry.applyYMusicTelemetry
import io.ymusic.telemetry.bindCrashReporting
import io.ymusic.telemetry.captureFeedbackReport
import io.ymusic.telemetry.installSentryDeviceId
import io.ymusic.telemetry.installSentryEngineVersion
import io.ymusic.telemetry.installSentryPlayerVersion
import io.ymusic.telemetry.sendFeedbackMessage
import io.ymusic.ui.APP_HTTP_TIMEOUT
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppHttpClients
import io.ymusic.ui.FolderWriteProblem
import io.ymusic.ui.ScanResult
import io.ymusic.ui.YtEngineSabrSpecResolver
import io.ymusic.ui.bundleConfig
import io.ymusic.ui.downloadLocationFolder
import io.ymusic.ui.ensureAppLanguage
import io.ymusic.ui.loaderYoutubeArt
import io.ymusic.ui.mediaStoreArtFetcher
import io.ymusic.ui.playhistory.PlayHistoryStore
import io.ymusic.ui.playhistory.playHistoryAndroidDriver
import io.ymusic.ui.registerCompletedDownload
import io.ymusic.ui.sponsorblock.SqliteSponsorBlockStore
import io.ymusic.ui.sponsorblock.sponsorBlockAndroidDriver
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightCacheStats
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.androidSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kmpcommon.log.Logger
import kmpcommon.log.TeeLogger
import kmpcommon.uri.Uri
import kmpffmpeg.AndroidFfInputResolver
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.sink
import okio.source
import projectcommon.log.FileLogger
import projectcommon.log.LoggingWorker
import java.io.File
import java.lang.ref.WeakReference

/**
 * The PROCESS-scoped half of the app graph: everything that must exist exactly once no matter how
 * many times the Activity is recreated. AppHttpClients is the load-bearing member — a second
 * instance would open a second okhttp Cache over the same directory, which corrupts it (see its
 * KDoc) — but the engine manager, media store, settings and image loader equally have no business
 * dying with a rotation.
 */
class SharedServices(
    val http: AppHttpClients,
    val cookies: WebViewCookieJar,
    val settings: PrefsSettingsStore,
    val engines: DynamicEngineManager,
    val cacheStore: CacheStore,
    val mediaStore: BetterMediaStore,
    /** The MSE player bundle, upgraded between player sessions. */
    val playerJs: JsSourceProvider,
    val imageLoader: ImageLoader,
    val stats: StatUsage,
    val updates: AndroidAppUpdates,
    /** The YMusic service; null when this build has no service url. */
    val api: ApiService?,
    val remoteConfig: RemoteConfig,
    /** The running engine.js / player.js revisions the service calls report. */
    val runningBundles: RunningBundles,
)

class YMusicApplication : Application() {

    private val _premium = MutableStateFlow(false)

    /** The Support Development app is installed ([SupportApp]); re-checked on every resume. */
    val premium: StateFlow<Boolean> = _premium

    fun refreshPremium() {
        _premium.value = SupportApp.installed(this)
    }

    /** The resumed MainActivity, for launches that must stay in YMusic's task (Donate). */
    var resumedActivity: WeakReference<Activity>? = null

    /** The system share sheet, over the visible activity when there is one. */
    private fun startShare(intent: Intent) {
        val chooser = Intent.createChooser(intent, null)
        val activity = resumedActivity?.get()
        if (activity != null) activity.startActivity(chooser) else startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Rotating file sink (`filesDir/logs/ymusic.log`, 5 MiB × 3). Lazy: needs [filesDir]. */
    val logWorker: LoggingWorker by lazy { LoggingWorker(File(filesDir, "logs").toOkioPath()) }

    /** The app's root logger: file + Sentry (no-op until Sentry.init) teed with logcat. */
    val rootLogger: Logger by lazy {
        TeeLogger(FileLogger(logWorker), TeeLogger(SentryLogger(), LogcatLogger()))
            .also { it.bindMseNativeLogs() }
    }

    /** Escaped coroutine failures are still one job dying — capture, don't crash the process. */
    private fun uncaught(scopeName: String) = CoroutineExceptionHandler { _, e ->
        rootLogger.fork(scopeName).fork("uncaught").captureError(e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught("app-default"))

    /** Main-thread, app-lifetime scope for playback glue (service start, audio focus). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + uncaught("app-main"))

    /** The home-screen player widgets; live once the graph is built. */
    val widgets: PlayerWidgets by lazy { PlayerWidgets(this, appScope, rootLogger.fork("widgets")) }

    /** The current Activity's SAF folder pick, registered while it lives. The graph is app-scoped
     *  but a pick needs an Activity; a scan is always user-driven, so one is up when this runs. */
    @Volatile var folderPicker: (suspend () -> Uri?)? = null

    /** The current Activity's QR scan — same lifecycle as [folderPicker]. */
    @Volatile var qrScanner: (suspend () -> String?)? = null

    /** The current Activity's PIN / biometric prompt — same lifecycle as [folderPicker]. */
    @Volatile var deviceOwnerAuth: (suspend (String, String) -> Boolean)? = null

    /** The current Activity's "save as" picker (suggested name, mime type) — same lifecycle as [folderPicker]. */
    @Volatile var documentCreator: (suspend (String, String) -> android.net.Uri?)? = null

    /** The current Activity's single-file picker (accepted mime types) — same lifecycle as [folderPicker]. */
    @Volatile var documentOpener: (suspend (List<String>) -> android.net.Uri?)? = null

    /** The current Activity's runtime permission prompt — same lifecycle as [folderPicker]. */
    @Volatile var permissionRequester: (suspend (String) -> Boolean)? = null

    // For onTrimMemory, which cannot await the deferred; set once the build completes.
    @Volatile private var builtImageLoader: ImageLoader? = null

    /** Built off-main on FIRST await, not in onCreate — the first CookieManager.getInstance()
     *  alone initializes the whole WebView provider, which froze a slow device for seconds. */
    val services: Deferred<SharedServices> by lazy { scope.async { buildServices() } }

    private var builtGraph: Deferred<AppGraph>? = null

    /** Single-flight graph build over [services]; call from the main thread. Playback must outlive
     *  the Activity (the [PlaybackService] mirrors the player into a MediaSession after the UI is
     *  gone), so the player and its video container live here, against the application context. */
    fun graphAsync(): Deferred<AppGraph> =
        builtGraph ?: appScope.async { buildGraph() }.also { builtGraph = it }

    private suspend fun buildServices(): SharedServices {
        MseDecoderCaps.start(rootLogger)
        // YouTube + API cookies live in the WebView CookieManager (the login WebView writes
        // there; this jar is a wrapper so HTTP sees the same store).
        val cookies = WebViewCookieJar()
        // One client stack, one disk cache, shared by the engine and the player's embed fetch.
        val http = AppHttpClients(cacheDir.resolve("http-cache").toOkioPath(), logger = rootLogger)
        val settings = PrefsSettingsStore.open(this)
        if (Build.VERSION.SDK_INT >= 31) {
            val uiModes = getSystemService(UiModeManager::class.java)
            scope.launch {
                settings.flow(AppSettings.theme).collect { theme ->
                    uiModes.setApplicationNightMode(
                        when (theme) {
                            "light" -> UiModeManager.MODE_NIGHT_NO
                            "dark", "amoled" -> UiModeManager.MODE_NIGHT_YES
                            else -> UiModeManager.MODE_NIGHT_AUTO
                        },
                    )
                }
            }
        }
        val deviceId = settings.anonymousDeviceId()
        // Crash reporting is consent-gated and LIVE: init runs once (crash handlers install and
        // stay), but nothing leaves the process unless the settings toggle says yes — default
        // off, so nothing is ever sent before the user opted in.
        if (BuildConfig.SENTRY_DSN.isNotEmpty()) {
            bindCrashReporting(appScope, settings.flow(AppSettings.crashReports)) {
                Sentry.initWithPlatformOptions {
                    it.applyYMusicTelemetry()
                    it.dsn = BuildConfig.SENTRY_DSN
                    // The commit in the release traces an event to git history (no source upload).
                    it.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_COMMIT}"
                    // Explicit and persisted: without it, ANR/tombstone events replayed by the next
                    // process take dist from the text after '+' in the release (the commit).
                    it.dist = BuildConfig.VERSION_CODE.toString()
                    it.environment = if (BuildConfig.DEBUG) "debug" else "production"
                }
                installSentryDeviceId(deviceId)
            }
        }

        // The one client of the YMusic service; null when this build has no service url. Its own
        // client instance carries the API cookie jar (the activity watermark survives restarts) —
        // the engine client stays jar-less on purpose, the engine JS owns YouTube cookies.
        val runningBundles = RunningBundles(
            engineFloor = maxOf(BuildConfig.ENGINE_JS_VERSION.toInt(), settings.flow(AppSettings.lastLoadedEngineJsVer).value),
            playerFloor = maxOf(EMBEDDED_PLAYER_JS_VERSION.toInt(), settings.flow(AppSettings.lastLoadedPlayerJsVer).value),
        )
        val api = BuildConfig.REMOTE_CONFIG_URL.takeIf { it.isNotBlank() }?.let { baseUrl ->
            HttpApiService(
                client = http.engine.newBuilder().apply {
                    cookieJar = cookies
                }.build(),
                os = "android",
                osVersion = android.os.Build.VERSION.RELEASE ?: "",
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                deviceId = deviceId,
                baseUrl = baseUrl,
                now = { System.currentTimeMillis() },
                // The device's preferred ABI, so the service can offer its split APK.
                arch = android.os.Build.SUPPORTED_ABIS.firstOrNull(),
                bundles = runningBundles,
            )
        }
        // Flags, the newest JS bundle revisions, and update availability. Started here so the first
        // fetch overlaps the rest of startup; nothing waits on it, the JS bundles follow what it publishes.
        val remoteConfig = RemoteConfig(
            api = api,
            cacheFile = File(filesDir, "remote-config.json").toOkioPath(),
            fileSystem = FileSystem.SYSTEM,
            scope = appScope,
            now = { System.currentTimeMillis() },
            log = rootLogger.fork("remote-config"),
        ).also { it.checkUpdate() } // app open is itself a hotpoint
        // Anonymous usage stats — held by SharedServices so the graph (and any screen) can
        // record events. The "Send anonymous stats" answer is read per event, so the Settings
        // switch applies live, and a disabled gate costs nothing.
        val stats = StatUsage(
            api = api,
            enabled = { settings.flow(AppSettings.stats).value },
            queueFile = File(filesDir, "stat-queue.json").toOkioPath(),
            fileSystem = FileSystem.SYSTEM,
            scope = scope,
            now = { System.currentTimeMillis() },
            log = rootLogger.fork("stats"),
        ).also { it.onAppStart() }
        // First launch decides the app language (device if supported, else English) and persists
        // it — BEFORE the engine starts: the engine pulls the same key as its content-language.
        ensureAppLanguage(settings)
        // The engine bundle: asset embed (no compiled-in constant) + file cache + the revision the
        // config service publishes, which the manager follows from start.
        val engineSource = DefaultJsSourceProvider(
            client = http.engine,
            config = remoteConfig.bundleConfig { it.engineJs },
            name = ENGINE_BUNDLE,
            publicKey = BuildConfig.BUNDLE_PUBLIC_KEY,
            embedVersion = BuildConfig.ENGINE_JS_VERSION,
            embedSource = { assets.open("engine_ymusic.js").readBytes().decodeToString() },
            fileSystem = FileSystem.SYSTEM,
            directory = File(filesDir, "engine-js").toOkioPath(),
            now = { System.currentTimeMillis() },
            logger = rootLogger,
            ioDispatcher = Dispatchers.IO,
        )
        val cacheStore = androidEngineCacheStore(this, logger = rootLogger)
        val engines = DynamicEngineManager(
            http.engine,
            appVersion = BuildConfig.VERSION_CODE,
            logger = rootLogger,
            cacheStore = cacheStore,
            cookieJar = cookies,
            settings = settings,
            // Debug builds crash on an engine-thread stall (StrictMode-style); release only logs.
            strictStints = BuildConfig.DEBUG,
            // Every Sentry event and service call names the engine-js bundle that was live (start + each swap).
            onBackendVersion = { version ->
                installSentryEngineVersion(version)
                runningBundles.engine = version
                scope.launch { settings.put(AppSettings.lastLoadedEngineJsVer, version) }
            },
            jsSource = engineSource,
        )
        engines.start()

        val mediaStore = BetterMediaStore.create(mediaStoreAndroidDriver(this))

        // Thumbnails: everything is a fetcher — http on a cache-less client (originals stream to
        // the fetcher's disk cache, decode from file) + local files + art:// embedded covers via
        // the bundled ffmpeg (absent binary keeps art:// unresolved → placeholders). Memory budget
        // scales with the device's heap class; onTrimMemory (below) sheds it under pressure.
        val ffmpegBin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
            .takeIf { it.exists() }?.absolutePath
        val imageNetworkFetcher = networkFetcher(
            http.image,
            FileSystem.SYSTEM,
            cacheDir.resolve("image-source-cache").toOkioPath(),
        )
        var loaderRef: ImageLoader? = null
        val imageLoader = ImageLoader(
            fetchers = listOfNotNull(
                imageNetworkFetcher,
                fileFetcher(),
                ffmpegBin?.let {
                    mediaStoreArtFetcher(
                        FfmpegMetadataGetter(it, AndroidFfInputResolver(contentResolver)),
                        mediaStore,
                        loaderYoutubeArt { checkNotNull(loaderRef) },
                        coverCache = ImageSourceCache(FileSystem.SYSTEM, cacheDir.resolve("image-cover-cache").toOkioPath(), 32L * 1024 * 1024),
                    )
                },
            ),
            scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, cacheDir.resolve("image-scaled-cache").toOkioPath()),
            logger = rootLogger,
            memoryCacheBytes = defaultMemoryCacheBudget(this),
            variantIndex = DiskVariantIndex(FileSystem.SYSTEM, cacheDir.resolve("image-variants").toOkioPath()),
        ).also {
            loaderRef = it
            builtImageLoader = it
        }

        val playerJs = playerJsSource(
            client = http.engine,
            config = remoteConfig.bundleConfig { it.playerJs },
            publicKey = BuildConfig.BUNDLE_PUBLIC_KEY,
            fileSystem = FileSystem.SYSTEM,
            directory = File(filesDir, "player-js").toOkioPath(),
            now = { System.currentTimeMillis() },
            logger = rootLogger,
            ioDispatcher = Dispatchers.IO,
        )
        // In-app updates from the config's appUpdate.
        val updates = AndroidAppUpdates(
            context = this,
            client = http.engine,
            currentVersionCode = BuildConfig.VERSION_CODE,
            scope = scope,
            logger = rootLogger,
            autoDownload = { settings.flow(AppSettings.autoDownloadUpdates).value },
            autoDownloadFailed = { settings.flow(AppSettings.updateAutoDownloadFailed).value },
            rememberAutoDownloadFailed = { settings.put(AppSettings.updateAutoDownloadFailed, it) },
            freshConfig = { remoteConfig.awaitFresh(timeoutMs = 3_000L) },
            downloadSite = { remoteConfig.config.value.downloadPageUrl },
            configUpdates = remoteConfig.config.map { it.appUpdate },
        )
        updates.pruneDownloads()
        return SharedServices(http, cookies, settings, engines, cacheStore, mediaStore, playerJs, imageLoader, stats, updates, api, remoteConfig, runningBundles)
    }

    private suspend fun buildGraph(): AppGraph {
        // One shared video host (a FrameLayout): the local engine adds its SurfaceView, MSE paints
        // into a TextureView, and a hidden WebView cooks download specs. Views must be born on the
        // main thread; everything else builds off it (see [services]).
        val videoContainer = FrameLayout(this)
        val downloadContainer = FrameLayout(this).apply { visibility = View.GONE }
        videoContainer.addView(downloadContainer, FrameLayout.LayoutParams(1, 1))
        val mseTexture = TextureView(this).apply { isOpaque = true }
        videoContainer.addView(
            mseTexture,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        mseTexture.visibility = View.GONE
        val mseBridge = MseSurfaceBridge(mseTexture, rootLogger.fork("mse-surface"))
        mseTexture.surfaceTextureListener = mseBridge.listener
        val graph = withContext(Dispatchers.Default) {
            buildGraphOffMain(videoContainer, downloadContainer, mseTexture, mseBridge)
        }
        // The session/notification host must be up whenever playback is — every playing transition
        // (re)starts it. Playback only ever starts from a user action with the app foregrounded, so
        // the FGS-start restriction doesn't bite; if it ever does, media3 notification actions and
        // media buttons can still revive the service.
        appScope.launch {
            combine(graph.player.status, graph.player.queue) { status, queue -> isPlaying(status, queue) }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    try {
                        ContextCompat.startForegroundService(
                            this@YMusicApplication,
                            Intent(this@YMusicApplication, PlaybackService::class.java),
                        )
                    } catch (e: IllegalStateException) {
                        rootLogger.fork("playback-service").log(Logger.Level.WARN, "foreground start rejected", e)
                    }
                }
        }
        AudioFocusCoordinator(this, graph.player, graph.settings, appScope).start()
        SystemAudioEffects(this, graph.player, graph.settings, appScope, rootLogger.fork("system-audio-fx")).start()
        widgets.attach(graph)
        graph.pruneUngrantedFolders?.let { prune -> scope.launch { prune() } }
        scope.launch { StartupCleanups(this@YMusicApplication, graph.settings, rootLogger).run() }
        // Same pattern for downloads: any change that (re)starts work also (re)starts the
        // dataSync foreground host; the service winds itself down when the queue drains. Enqueue
        // and resume are user actions with the app foregrounded, so FGS-start is permitted.
        graph.downloads?.let { downloads ->
            appScope.launch {
                downloads.changes.collect { change ->
                    val startsWork = change is DownloadChange.Added ||
                        (
                            change is DownloadChange.StateChanged &&
                                change.state.isActive
                            )
                    if (startsWork) {
                        try {
                            ContextCompat.startForegroundService(
                                this@YMusicApplication,
                                Intent(this@YMusicApplication, DownloadForegroundService::class.java),
                            )
                        } catch (e: IllegalStateException) {
                            rootLogger.fork("download-service").log(Logger.Level.WARN, "foreground start rejected", e)
                        }
                    }
                }
            }
        }
        return graph
    }

    private suspend fun buildGraphOffMain(
        videoContainer: FrameLayout,
        downloadContainer: FrameLayout,
        mseTexture: TextureView,
        mseBridge: MseSurfaceBridge,
    ): AppGraph {
        val shared = services.await()

        // The player cache; shared read-only with the download stack so a download reuses segments already
        // cached from playback (never writing into it).
        val playerCache = run {
            val cacheRoot = File(applicationContext.cacheDir, "player-cache").apply { mkdirs() }
            val driver = androidSegmentDatabaseDriver(applicationContext) // shared: span index + segment index
            MediaCache(
                DiskMediaBlobCache(
                    cacheRoot.toOkioPath(),
                    FileSystem.SYSTEM,
                    SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() }),
                    logger = rootLogger.fork("player-cache"),
                ),
                SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
                SqlDelightCacheStats(driver),
                logger = rootLogger.fork("player-cache"),
            )
        }
        val mediaProxyClient = KmpHttpClient.create {
            defaultExecuteTimeout = APP_HTTP_TIMEOUT
            logger = rootLogger.fork("kmphttp")
            cookieJar = shared.cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        }
        val mediaProxy = LocalMediaProxy(
            playerCache,
            mediaProxyClient,
            userAgent = WebSettings.getDefaultUserAgent(applicationContext),
            logger = rootLogger,
            // Per-request media-path logging — debug builds only, it narrates the hot path.
            verbose = BuildConfig.DEBUG,
        )

        var ytEngine: io.ymusic.player.YtEngine? = null
        var msePlayer: MsePlayer? = null
        val player = createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(this, videoContainer)) },
                youtube = {
                    MsePlayer.create(
                        client = shared.http.engine,
                        appVersion = BuildConfig.VERSION_CODE,
                        logger = rootLogger,
                        cacheStore = shared.cacheStore,
                        cookieJar = shared.cookies,
                        settings = shared.settings,
                        videoSurface = MseVideoSurface(),
                        main = shared.engines,
                        mediaProxy = mediaProxy,
                        playerJs = shared.playerJs,
                        onPlayerJsVersion = { version ->
                            installSentryPlayerVersion(version)
                            shared.runningBundles.player = version
                            scope.launch { shared.settings.put(AppSettings.lastLoadedPlayerJsVer, version) }
                        },
                    ).also {
                        ytEngine = it
                        msePlayer = it
                        mseBridge.attach(it)
                    }
                },
            ),
        )
        mseBridge.videoShown = { showsYoutubeVideo(player) }

        // Offline downloads: SABR stack + on-device ffmpeg. Spec from MSE /player + decoded SABR URL.
        val downloadService = run {
            val ffmpegBin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
                .takeIf { f -> f.exists() }?.absolutePath
            // Probes a finished download's metadata into its Library bucket (same path as the folder scan,
            // and it reads content:// via SAF); null without ffmpeg → a hand-built draft fallback.
            val downloadScanner = ffmpegBin?.let {
                MediaScanner.create(
                    shared.mediaStore,
                    SafDocumentsProvider(applicationContext),
                    FfmpegMetadataExtractor(FfmpegMetadataGetter(it, AndroidFfInputResolver(contentResolver))),
                )
            }
            val sabrClient = KmpHttpClient.create {
                defaultExecuteTimeout = APP_HTTP_TIMEOUT
                logger = rootLogger.fork("kmphttp")
                cookieJar = shared.cookies
                defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
            }
            val ytResolver = YtEngineSabrSpecResolver {
                withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
                ytEngine ?: error("youtube engine missing")
            }
            // Leftover scratch root of beta builds; best-effort, the OS may trim it anyway.
            File(cacheDir, "download-work").deleteRecursively()
            io.ymusic.download.DownloadService(
                store = io.ymusic.download.DownloadStore(io.ymusic.download.downloadDatabaseAndroidDriver(applicationContext), rootLogger),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught("downloads")),
                now = { System.currentTimeMillis() },
                resolver = ytResolver,
                client = sabrClient,
                userAgent = WebSettings.getDefaultUserAgent(applicationContext),
                ffmpeg = ffmpegBin?.let { bin -> io.ymusic.download.Ffmpeg(bin) },
                // Handles file:// (app dirs) via okio and user-picked SAF folders (content://) via SAF.
                // App data, not cacheDir (trimmed mid-download under storage pressure); no_backup keeps
                // multi-MB scratch out of Auto Backup's quota.
                storage = AndroidDownloadStorage(
                    applicationContext,
                    FileSystem.SYSTEM,
                    File(noBackupFilesDir, "download-work").absolutePath.toPath(),
                    rootLogger.fork("download-storage"),
                ),
                mediaCache = playerCache, // read-only (see YtStreamDownloader): reuse cached playback bytes
                // Own segment index for SABR resume (byte range per segment), separate from the player cache DB.
                segmentIndex = SqlDelightSegmentIndex(
                    androidSegmentDatabaseDriver(applicationContext, name = "download_segments.db"),
                    now = { System.currentTimeMillis() },
                ),
                // The user's chosen download location (SAF content:// or file://) — always set: the download
                // flow requires picking one before enqueue.
                downloadFolder = { shared.settings.downloadLocationFolder()!! },
                onCompleted = { task, result ->
                    registerCompletedDownload(shared.mediaStore, shared.settings, downloadScanner, task, result)
                },
                logger = rootLogger,
                playerResponse = ytResolver::playerResponse,
            )
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        return AppGraph(
            shared.engines,
            player,
            logger = rootLogger,
            playerCache = playerCache,
            systemAudioEffects = true,
            audioFocusSetting = true,
            stats = shared.stats,
            updates = shared.updates,
            premium = premium,
            openDonation = {
                resumedActivity?.get()?.let { activity ->
                    SupportApp.openDonation(activity, shared.remoteConfig.config.value.donationUrl, rootLogger)
                }
            },
            shareText = { text ->
                startShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
            },
            fileRenaming = AndroidFileRenaming(contentResolver),
            api = shared.api,
            remoteConfig = shared.remoteConfig.config,
            mediaStore = shared.mediaStore,
            playHistory = PlayHistoryStore(playHistoryAndroidDriver(this)),
            sponsorBlockStore = SqliteSponsorBlockStore(sponsorBlockAndroidDriver(this)),
            scanLibraryFolder = { onProgress -> scanPickedFolder(shared.mediaStore, onProgress) },
            libraryFolderPicker = { pickAndRegisterFolder(shared.mediaStore) },
            canWriteFolder = { uri ->
                checkCallingOrSelfUriPermission(android.net.Uri.parse(uri), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
            },
            probeDownloadFolder = { uri -> probeDownloadFolder(android.net.Uri.parse(uri)) },
            rescanLibrary = { folderUri, onProgress -> rescanKnownRoots(shared.mediaStore, folderUri, onProgress) },
            pruneUngrantedFolders = { pruneUngrantedFolders(shared.mediaStore) },
            deleteFromDisk = { item ->
                withContext(Dispatchers.IO) {
                    val uri = android.net.Uri.parse(item.uri.toString())
                    // Rides the persisted tree permission from the folder pick. A file that is already gone
                    // (or whose folder is) needs no delete: the caller still clears its library row.
                    if (!documentExists(uri)) return@withContext
                    val deleted = try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (e: java.io.FileNotFoundException) {
                        true
                    } catch (e: IllegalArgumentException) {
                        if (documentExists(uri)) throw e
                        true
                    }
                    // Before Android 10 deleteDocument swallows the provider's exception into false.
                    check(deleted || !documentExists(uri)) { "Android refused to delete this document" }
                }
            },
            appVersion = listOfNotNull(versionName, android.os.Build.SUPPORTED_ABIS.firstOrNull()).joinToString(" · "),
            appVersionName = versionName,
            appVersionCode = BuildConfig.VERSION_CODE,
            runningBundles = shared.runningBundles,
            imageLoader = shared.imageLoader,
            settings = shared.settings,
            loginBrowser = { url, userAgent, state, onPageChanged, modifier ->
                AndroidLoginBrowser(url, userAgent, state, onPageChanged, modifier)
            },
            exportSessionCookies = { shared.cookies.exportSessionCookies() },
            scanQrCode = { qrScanner?.invoke() },
            confirmDeviceOwner = { title, subtitle ->
                val fn = deviceOwnerAuth ?: error("no-lock")
                fn(title, subtitle)
            },
            downloads = downloadService,
            captureFeedbackReport = captureFeedbackReportAction.takeIf { BuildConfig.SENTRY_DSN.isNotEmpty() },
            sendFeedbackMessage = ::sendFeedbackMessage.takeIf { BuildConfig.SENTRY_DSN.isNotEmpty() },
            platformInfo = listOfNotNull("Android ${android.os.Build.VERSION.RELEASE}", android.os.Build.SUPPORTED_ABIS.firstOrNull()).joinToString(" · "),
            createDocument = { name, mimeType ->
                documentCreator?.invoke(name, mimeType)?.let { uri ->
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "wt")?.sink() ?: error("no output stream for $uri")
                    }
                }
            },
            openDocument = { mimeTypes ->
                documentOpener?.invoke(mimeTypes)?.let { uri ->
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.source() ?: error("no input stream for $uri")
                    }
                }
            },
            devicePlaylists = AndroidDevicePlaylists(this) { permission -> permissionRequester?.invoke(permission) ?: false },
        ) { modifier ->
            // The ONE shared videoContainer is hosted at two call sites (the docked preview and Now
            // Playing). They're logically exclusive, but across a transition frame Compose builds the
            // new host before disposing the old one — and Android's addView does NOT auto-detach
            // (unlike Swing/UIKit), so adding the container directly throws "child already has a
            // parent". So each AndroidView gets its own empty host and re-parents the shared container
            // into whichever is active, detaching from the previous parent first.
            val queue by player.queue.collectAsState()
            val config by player.config.collectAsState()
            val quality by player.videoQuality.collectAsState()
            val showMse = queue.currentItem is PlayableItem.YtMedia && showsVideo(config, quality)
            AndroidView(
                factory = { context -> FrameLayout(context) },
                modifier = modifier.fillMaxSize(),
                update = { host ->
                    if (videoContainer.parent !== host) {
                        (videoContainer.parent as? ViewGroup)?.removeView(videoContainer)
                        host.addView(videoContainer)
                        mseBridge.reattach(mseTexture)
                    }
                    mseTexture.visibility = if (showMse) View.VISIBLE else View.GONE
                    if (showMse) mseTexture.bringToFront()
                },
            )
        }
    }

    /** Sends the app logs as one anonymous Sentry report for the Feedback screen; returns its event id. */
    private val captureFeedbackReportAction: suspend () -> String? = {
        withContext(Dispatchers.IO) {
            runCatching { logWorker.flush() }
                .onFailure { rootLogger.fork("feedback").log(Logger.Level.WARN, "log flush before feedback failed", it) }
            captureFeedbackReport(
                logWorker.filePaths().map { path ->
                    io.sentry.kotlin.multiplatform.Attachment(path, path.substringAfterLast('/'), "text/plain")
                },
            )
        }
    }

    /** Whether the document at [uri] is still there. A vanished file, or one under a vanished folder, comes
     *  back as FileNotFoundException / IllegalArgumentException("Missing file for …") / IllegalStateException
     *  ("File has no filepath"), zero rows, or a null cursor (DocumentsProvider.query swallows its
     *  FileNotFoundException) — but null also means the provider is unreachable, which proves nothing. A
     *  document we may no longer read (SecurityException) counts as gone too: its row is cleared either way. */
    private fun documentExists(uri: android.net.Uri): Boolean = try {
        contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.count > 0 }
            ?: (contentResolver.acquireUnstableContentProviderClient(uri)?.also { it.close() } == null)
    } catch (e: java.io.FileNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: IllegalStateException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Creates, writes and deletes a scratch file in [folder] — the steps a finished download takes. */
    private suspend fun probeDownloadFolder(folder: android.net.Uri): FolderWriteProblem? = withContext(Dispatchers.IO) {
        val log = rootLogger.fork("download-folder-probe", mapOf("folder" to folder.toString()))
        if (checkCallingOrSelfUriPermission(folder, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext FolderWriteProblem.NoPermission
        }
        val parent = if (android.provider.DocumentsContract.isDocumentUri(this@YMusicApplication, folder)) {
            folder
        } else {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(folder, android.provider.DocumentsContract.getTreeDocumentId(folder))
        }
        val probe = try {
            android.provider.DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", ".ymusic-write-test")
        } catch (e: SecurityException) {
            log.log(Logger.Level.WARN, "test file refused", e)
            return@withContext FolderWriteProblem.NoPermission
        } catch (e: java.io.FileNotFoundException) {
            log.log(Logger.Level.WARN, "folder gone", e)
            return@withContext FolderWriteProblem.Missing
        } catch (e: Exception) {
            log.log(Logger.Level.WARN, "test file not created", e)
            return@withContext FolderWriteProblem.CannotCreateFiles
        } ?: return@withContext FolderWriteProblem.CannotCreateFiles
        try {
            contentResolver.openOutputStream(probe)?.use { it.write(0) } ?: return@withContext FolderWriteProblem.CannotCreateFiles
            null
        } catch (e: SecurityException) {
            log.log(Logger.Level.WARN, "test file not writable", e)
            FolderWriteProblem.NoPermission
        } catch (e: Exception) {
            log.log(Logger.Level.WARN, "test file not writable", e)
            FolderWriteProblem.CannotCreateFiles
        } finally {
            try {
                android.provider.DocumentsContract.deleteDocument(contentResolver, probe)
            } catch (e: Exception) {
                log.log(Logger.Level.WARN, "test file left behind", e)
            }
        }
    }

    /** SAF folder pick + full scan into [store]; returns how it ended, or null when canceled. */
    private suspend fun scanPickedFolder(store: BetterMediaStore, onProgress: (Int) -> Unit): ScanResult? {
        val treeUri = folderPicker?.invoke() ?: return null
        return scanUris(store, listOf(treeUri), onProgress)
    }

    /** Pick a folder and register it as a Library bucket (no media scan — works even when empty),
     *  returning its uri. Used to choose a download location. */
    private suspend fun pickAndRegisterFolder(store: BetterMediaStore): String? {
        val treeUri = folderPicker?.invoke() ?: return null
        val folder = SafDocumentsProvider(applicationContext).queryDocument(treeUri) as? DocumentInfo.Folder ?: return null
        store.upsertBucket(BucketDraft(uri = folder.uri, name = folder.name))
        return folder.uri.toString()
    }

    /** Re-scans [folderUri] (and its children) in place, or every already-added root when null. */
    private suspend fun rescanKnownRoots(store: BetterMediaStore, folderUri: String?, onProgress: (Int) -> Unit): ScanResult? {
        val uris = if (folderUri != null) {
            listOf(Uri.parse(folderUri))
        } else {
            val roots = store.childBuckets(null)
            if (roots.isEmpty()) return ScanResult.NoFolders
            roots.map { it.uri }
        }
        return scanUris(store, uris, onProgress)
    }

    /** Soft-deletes each Library root whose SAF tree grant is gone (revoked, or restored from a backup made on
     *  another install); picking the folder again re-grants it and the scan revives its rows. */
    private suspend fun pruneUngrantedFolders(store: BetterMediaStore) {
        val ungranted = withContext(Dispatchers.IO) {
            store.childBuckets(null).filter { bucket ->
                val uri = android.net.Uri.parse(bucket.uri.toString())
                uri.scheme == "content" &&
                    checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED
            }
        }
        for (bucket in ungranted) {
            rootLogger.fork("library-grants", mapOf("bucket" to bucket.uri.toString()))
                .log(Logger.Level.WARN, "folder grant lost; soft-deleting its library rows")
            store.deleteBucket(bucket.id)
        }
    }

    /** Scans each root [uris] into [store] via SAF + the bundled ffmpeg (content:// proxied over ymix),
     *  reporting the running file count and returning the combined result. */
    private suspend fun scanUris(store: BetterMediaStore, uris: List<Uri>, onProgress: (Int) -> Unit): ScanResult {
        val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath
        val scanner = MediaScanner.create(
            store,
            SafDocumentsProvider(applicationContext),
            FfmpegMetadataExtractor(FfmpegMetadataGetter(ffmpeg, AndroidFfInputResolver(contentResolver))),
        )
        var scanned = 0
        var added = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        for (uri in uris) {
            val progress = scanner.scan(uri).onEach { onProgress(scanned + it.scanned) }.last()
            progress.errors.forEach { rootLogger.fork("library-scan").log(Logger.Level.WARN, "scan error at ${it.uri}", it.cause) }
            scanned += progress.scanned
            added += progress.added
            updated += progress.updated
            deleted += progress.deleted
            failed += progress.errors.size
        }
        return ScanResult.Done(scanned, added, updated, deleted, failed)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val loader = builtImageLoader ?: return
        when {
            // In the background LRU list — decoded thumbnails are pure regenerable weight, drop all.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> loader.trimMemoryCache(0f)
            // Running but memory is tight (or UI just hidden) — keep the hot half.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> loader.trimMemoryCache(0.5f)
        }
    }
}

/**
 * Hands the TextureView window to [MsePlayer]. MiniPlayer ↔ Now Playing re-parents the same
 * TextureView; releasing the SurfaceTexture there tears MediaCodec and glitches audio. Keep the
 * producer and restore it after re-attach (`setSurfaceTexture`); only bind MediaCodec once.
 */
private fun showsYoutubeVideo(player: Player): Boolean =
    player.queue.value.currentItem is PlayableItem.YtMedia && showsVideo(player.config.value, player.videoQuality.value)

private class MseSurfaceBridge(private val textureView: TextureView, private val log: Logger) {
    private var player: MsePlayer? = null
    var videoShown: () -> Boolean = { false }
    private var pending: Surface? = null
    private var texture: SurfaceTexture? = null
    private var frames = 0L
    private var watchedFrames = 0L
    private var stalledSinceMs = 0L

    /* Pairs with native "mse-v stats": decoder frames rising while this sees none = a stale consumer. */
    private val watchdog = object : Runnable {
        override fun run() {
            checkFrames()
            textureView.postDelayed(this, WATCH_MS)
        }
    }

    fun attach(p: MsePlayer) {
        player = p
        pending?.let { p.setVideoSurface(MseVideoSurface(it)) }
        textureView.post {
            textureView.removeCallbacks(watchdog)
            textureView.postDelayed(watchdog, WATCH_MS)
        }
    }

    fun reattach(tv: TextureView) {
        val st = texture ?: return
        if (tv.surfaceTexture === st) return
        runCatching { tv.setSurfaceTexture(st) }
    }

    private fun checkFrames() {
        val status = player?.status?.value ?: return
        val prepared = status.state as? PlaybackState.Prepared
        val expectFrames = videoShown() && prepared != null &&
            prepared.playWhenReady && !prepared.buffering && !prepared.ended &&
            textureView.isShown && textureView.windowVisibility == View.VISIBLE
        val delta = frames - watchedFrames
        watchedFrames = frames
        val now = SystemClock.uptimeMillis()
        if (expectFrames && delta == 0L) {
            if (stalledSinceMs != 0L) return
            stalledSinceMs = now - WATCH_MS
            log.log(
                Logger.Level.INFO,
                "no frames for ${WATCH_MS}ms while playing: attached=${textureView.isAttachedToWindow} " +
                    "available=${textureView.isAvailable} ownTexture=${textureView.surfaceTexture === texture} " +
                    "view=${textureView.width}x${textureView.height} video=${status.videoSize}",
            )
        } else if (delta > 0L && stalledSinceMs != 0L) {
            log.log(Logger.Level.INFO, "frames resumed after ${now - stalledSinceMs}ms")
            stalledSinceMs = 0L
        } else if (!expectFrames) {
            stalledSinceMs = 0L
        }
    }

    val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            if (texture == null) {
                texture = st
                pending = Surface(st)
                player?.setVideoSurface(MseVideoSurface(pending))
                return
            }
            if (st !== texture) {
                log.log(Logger.Level.INFO, "new SurfaceTexture on reattach; restoring the bound one")
                texture?.let { kept -> runCatching { textureView.setSurfaceTexture(kept) } }
            }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            /* false = we own the SurfaceTexture; MediaCodec stays bound across reparent. */
            return false
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            frames++
        }
    }

    private companion object {
        const val WATCH_MS = 2000L
    }
}
