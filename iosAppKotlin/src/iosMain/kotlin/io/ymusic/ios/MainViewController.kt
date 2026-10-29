package io.ymusic.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EMBEDDED_ENGINE_JS_VERSION
import io.ymusic.dynamicengine.EMBEDDED_PLAYER_JS_VERSION
import io.ymusic.dynamicengine.appleEngineCacheStore
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.player.bindMseNativeLogs
import io.ymusic.imageloader.DiskScaledImageCache
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.fileFetcher
import io.ymusic.imageloader.networkFetcher
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.LocalPlayerContext
import io.ymusic.player.PlayerEngines
import io.ymusic.player.createLocalPlayer
import io.ymusic.player.createPlayer
import io.ymusic.player.mse.MsePlayer
import io.ymusic.remoteconfig.HttpApiService
import io.ymusic.remoteconfig.RemoteConfig
import io.ymusic.remoteconfig.RunningBundles
import io.ymusic.remoteconfig.StatUsage
import io.ymusic.settings.AppSettings
import io.ymusic.settings.anonymousDeviceId
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.App
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppHttpClients
import io.ymusic.ui.CatalogueScreen
import io.ymusic.ui.ensureAppLanguage
import io.ymusic.ui.playhistory.PlayHistoryStore
import io.ymusic.ui.playhistory.playHistoryNativeDriver
import io.ymusic.ui.sponsorblock.SqliteSponsorBlockStore
import io.ymusic.ui.sponsorblock.sponsorBlockNativeDriver
import io.ymusic.ui.startupFailedMessage
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightCacheStats
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.nativeSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kmpcommon.log.TeeLogger
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import projectcommon.log.FileLogger
import projectcommon.log.LoggingWorker

/**
 * The iOS entry point — the Swift app hosts `MainViewControllerKt.MainViewController()`. Builds the
 * [AppGraph] (scraping engine + player facade over AVFoundation local + MSE YouTube, VideoToolbox
 * hole) and runs the same shared [App] the desktop and Android shells run.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { IosAppRoot() }

@Composable
private fun IosAppRoot() {
    // Host for the AVFoundation local player; YouTube MSE paints into [IosMseVideo].
    val container = remember { UIView() }
    var graph by remember { mutableStateOf<AppGraph?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            graph = buildIosGraph(container)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c // the effect was disposed — not a boot failure
        } catch (t: Throwable) {
            IosLogger().fork("boot").captureError(t)
            error = t.message ?: t.toString()
        }
    }

    val g = graph
    when {
        error != null -> Center { Text(startupFailedMessage("$error"), color = MaterialTheme.colorScheme.error) }
        g == null -> Center { CircularProgressIndicator() }
        else -> {
            LaunchedEffect(g) {
                connectIosMediaSession(g.player, this, g.logger?.fork("now-playing"), g.imageLoader)
            }
            LaunchedEffect(g) {
                combine(
                    g.settings.flow(AppSettings.enablePip),
                    g.pip.nowPlayingOpen,
                    g.pip.videoFullscreen,
                    g.player.status,
                    g.player.videoQuality,
                ) { _, _, _, _, _ -> }
                    .combine(g.player.config) { _, _ -> }
                    .collect { iosNotifyPipPrepare() }
            }
            App(g, catalogue = if (DEBUG_BUILD) ({ CatalogueScreen() }) else null)
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun buildIosGraph(container: UIView): AppGraph {
    val logDir = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve("ymusic-logs")
    val logWorker = LoggingWorker(logDir)
    val rootLogger = TeeLogger(FileLogger(logWorker), IosLogger()).also { it.bindMseNativeLogs() }
    val cookies = IosWebViewCookieJar()
    val http = AppHttpClients(
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
            .toPath()
            .resolve("ymusic-http-cache"),
        logger = rootLogger,
    )
    val settings = IosSettingsStore()
    // First launch decides the app language (device if supported, else English) and persists it —
    // BEFORE the engine starts, because the engine pulls the same key as its content-language (hl).
    ensureAppLanguage(settings)
    val cacheStore = appleEngineCacheStore(logger = rootLogger)
    // The running engine.js / player.js revisions the service calls report.
    val runningBundles = RunningBundles(
        engineFloor = maxOf(EMBEDDED_ENGINE_JS_VERSION.toInt(), settings.flow(AppSettings.lastLoadedEngineJsVer).value),
        playerFloor = maxOf(EMBEDDED_PLAYER_JS_VERSION.toInt(), settings.flow(AppSettings.lastLoadedPlayerJsVer).value),
    )
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appVersionCode = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: -1
    val engines = DynamicEngineManager(
        http.engine,
        appVersion = appVersionCode,
        jsSource = embeddedEngineJsSource(),
        logger = rootLogger,
        cookieJar = cookies,
        settings = settings,
        cacheStore = cacheStore,
        strictStints = DEBUG_BUILD,
        onBackendVersion = { version ->
            runningBundles.engine = version
            backgroundScope.launch { settings.put(AppSettings.lastLoadedEngineJsVer, version) }
        },
    )
    engines.start()

    val cacheRoot = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve("ymusic-images")
    val imageLoader = ImageLoader(
        fetchers = listOf(
            networkFetcher(http.image, FileSystem.SYSTEM, cacheRoot.resolve("source")),
            fileFetcher(),
        ),
        scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, cacheRoot.resolve("scaled")),
    )

    // The one client of the YMusic service; null when this build has no service url. Versions come
    // from Info.plist, the same values the App Store sees.
    val now = { (NSDate().timeIntervalSince1970 * 1000).toLong() }
    val appVersionName = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
    val api = REMOTE_CONFIG_URL.takeIf { it.isNotBlank() }?.let { baseUrl ->
        HttpApiService(
            client = http.engine.newBuilder().apply {
                cookieJar = cookies
            }.build(),
            os = "ios",
            osVersion = UIDevice.currentDevice.systemVersion,
            appVersionCode = appVersionCode,
            appVersionName = appVersionName,
            deviceId = settings.anonymousDeviceId(),
            baseUrl = baseUrl,
            now = now,
            bundles = runningBundles,
        )
    }
    // Kept, not cached: Application Support is never purged under storage pressure.
    val dataDir = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve("ymusic")
    FileSystem.SYSTEM.createDirectories(dataDir)
    // Flags and the newest JS bundle revisions (the App Store owns app updates). Started here so
    // the first fetch overlaps the rest of startup.
    val remoteConfig = RemoteConfig(
        api = api,
        cacheFile = dataDir.resolve("remote-config.json"),
        fileSystem = FileSystem.SYSTEM,
        scope = backgroundScope,
        now = now,
        log = rootLogger.fork("remote-config"),
    ).also { it.checkUpdate() }
    // Anonymous usage stats, gated per event by the "Send anonymous stats" setting.
    val stats = StatUsage(
        api = api,
        enabled = { settings.flow(AppSettings.stats).value },
        queueFile = dataDir.resolve("stat-queue.json"),
        fileSystem = FileSystem.SYSTEM,
        scope = backgroundScope,
        now = now,
        log = rootLogger.fork("stats"),
    ).also { it.onAppStart() }

    val playerCacheRoot = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve("ymusic-player-cache")
    FileSystem.SYSTEM.createDirectories(playerCacheRoot)
    val cacheDriver = nativeSegmentDatabaseDriver()
    val playerCache = MediaCache(
        DiskMediaBlobCache(
            playerCacheRoot,
            FileSystem.SYSTEM,
            SqlDelightBlobIndex(cacheDriver, now = { (NSDate().timeIntervalSince1970 * 1000).toLong() }),
            logger = rootLogger.fork("player-cache"),
        ),
        SqlDelightSegmentIndex(
            cacheDriver,
            now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
        ),
        SqlDelightCacheStats(cacheDriver),
        logger = rootLogger.fork("player-cache"),
    )
    val mediaProxy = LocalMediaProxy(
        cache = playerCache,
        client = KmpHttpClient.create {
            cookieJar = cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        },
        userAgent = MSE_CHROME_UA,
    )

    val mseHole = IosMseHole()
    val mseSurface = MseVideoSurface().apply { layer = mseHole.layerPtr() }
    val msePlayer = MsePlayer.create(
        client = http.engine,
        appVersion = appVersionCode,
        logger = rootLogger,
        cacheStore = cacheStore,
        cookieJar = cookies,
        settings = settings,
        videoSurface = mseSurface,
        main = engines,
        mediaProxy = mediaProxy,
        onPlayerJsVersion = { version ->
            runningBundles.player = version
            backgroundScope.launch { settings.put(AppSettings.lastLoadedPlayerJsVer, version) }
        },
    )
    val player = createPlayer(
        PlayerEngines(
            local = { createLocalPlayer(LocalPlayerContext(container)) },
            youtube = { msePlayer },
        ),
    )

    val graph = AppGraph(
        engines = engines,
        player = player,
        logger = rootLogger,
        playerCache = playerCache,
        mediaStore = null,
        playHistory = PlayHistoryStore(playHistoryNativeDriver()),
        sponsorBlockStore = SqliteSponsorBlockStore(sponsorBlockNativeDriver()),
        scanLibraryFolder = null,
        imageLoader = imageLoader,
        settings = settings,
        appVersion = appVersionName,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        platformInfo = "iOS ${UIDevice.currentDevice.systemVersion}",
        runningBundles = runningBundles,
        stats = stats,
        api = api,
        remoteConfig = remoteConfig.config,
        surfaceScalesDown = true,
        loginBrowser = { url, userAgent, state, onPageChanged, modifier ->
            IosLoginBrowser(url, userAgent, state, onPageChanged, modifier)
        },
        exportSessionCookies = { cookies.exportSessionCookies() },
        scanQrCode = { iosScanQrCode() },
        confirmDeviceOwner = { title, subtitle -> iosConfirmDeviceOwner(title, subtitle) },
        shareText = ::iosShareText,
    ) { modifier -> IosMseVideo(mseHole, msePlayer, modifier) }
    IosPipHost.hole = mseHole
    IosPipHost.pip = graph.pip
    IosPipHost.player = player
    IosPipHost.mse = msePlayer
    return graph
}

private const val MSE_CHROME_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
