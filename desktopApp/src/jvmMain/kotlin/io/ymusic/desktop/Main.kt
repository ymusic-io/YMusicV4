package io.ymusic.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import io.sentry.kotlin.multiplatform.Sentry
import io.ymusic.desktop.media.MacDock
import io.ymusic.desktop.media.connectMediaSession
import io.ymusic.desktop.resources.ymusic_logo_dock
import io.ymusic.desktop.update.DesktopAppUpdates
import io.ymusic.desktop.update.UpdateService
import io.ymusic.desktop.update.desktopArch
import io.ymusic.desktop.update.desktopPlatformKey
import io.ymusic.desktop.update.installerFor
import io.ymusic.dynamicengine.DefaultJsSourceProvider
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EMBEDDED_PLAYER_JS_VERSION
import io.ymusic.dynamicengine.ENGINE_BUNDLE
import io.ymusic.dynamicengine.desktopEngineCacheStore
import io.ymusic.dynamicengine.player.bindMseNativeLogs
import io.ymusic.dynamicengine.playerJsSource
import io.ymusic.dynamicengine.yt.ytLoginStatus
import io.ymusic.imageloader.DiskScaledImageCache
import io.ymusic.imageloader.DiskVariantIndex
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.ImageSourceCache
import io.ymusic.imageloader.defaultMemoryCacheBudget
import io.ymusic.imageloader.fileFetcher
import io.ymusic.imageloader.networkFetcher
import io.ymusic.mediascanner.FfmpegMetadataExtractor
import io.ymusic.mediascanner.FileSystemDocumentsProvider
import io.ymusic.mediascanner.MediaScanner
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mse.FileAvNative
import io.ymusic.mse.MseHostNative
import io.ymusic.player.LocalPlayerContext
import io.ymusic.player.LocalVideoSurface
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.PlayerEngines
import io.ymusic.player.createLocalPlayer
import io.ymusic.player.createPlayer
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
import io.ymusic.ui.ActionIcon
import io.ymusic.ui.App
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppHttpClients
import io.ymusic.ui.CatalogueScreen
import io.ymusic.ui.FolderWriteProblem
import io.ymusic.ui.IncomingLinks
import io.ymusic.ui.ScanResult
import io.ymusic.ui.VideoOverlayNative
import io.ymusic.ui.YMusicTheme
import io.ymusic.ui.YtEngineSabrSpecResolver
import io.ymusic.ui.bundleConfig
import io.ymusic.ui.designPaletteFor
import io.ymusic.ui.downloadLocationFolder
import io.ymusic.ui.ensureAppLanguage
import io.ymusic.ui.importSessionCookies
import io.ymusic.ui.isDarkTheme
import io.ymusic.ui.loaderYoutubeArt
import io.ymusic.ui.mediaStoreArtFetcher
import io.ymusic.ui.playhistory.PlayHistoryStore
import io.ymusic.ui.playhistory.playHistoryJdbcDriver
import io.ymusic.ui.registerCompletedDownload
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_always_on_top
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.cd_fullscreen
import io.ymusic.ui.resources.cd_minimize
import io.ymusic.ui.resources.folder_picker_title
import io.ymusic.ui.resources.playlist_menu_backup
import io.ymusic.ui.resources.playlist_menu_restore
import io.ymusic.ui.resources.ymusic_logo
import io.ymusic.ui.sponsorblock.SqliteSponsorBlockStore
import io.ymusic.ui.sponsorblock.sponsorBlockJdbcDriver
import io.ymusic.ui.startupFailedMessage
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightCacheStats
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kmpcommon.log.Logger
import kmpcommon.log.TeeLogger
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmpcommon.uri.fileUri
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import projectcommon.log.FileLogger
import projectcommon.log.LoggingWorker
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.desktop.AppReopenedListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.awt.Color as AwtColor

/**
 * The desktop shell: boots the scraping engine, assembles the [AppGraph] (player facade over ffmpeg
 * local + MSE YouTube playback), and runs the shared [App].
 */
private val logWorker = LoggingWorker(File(ymusicDataDir(), "logs").absolutePath.toPath())

// File + Sentry (no-op when Sentry wasn't initialized) teed with stdout.
private val rootLogger: Logger =
    TeeLogger(FileLogger(logWorker), TeeLogger(SentryLogger(), StdoutLogger()))
        .also { it.bindMseNativeLogs() }

private val LOG_FLUSH_TIMEOUT = 3.seconds

/** An exit that skips the application's quit still writes the log: the worker's threads are daemons. */
private fun flushLogBeforeExit() {
    kotlinx.coroutines.runBlocking { withTimeoutOrNull(LOG_FLUSH_TIMEOUT) { logWorker.flush() } }
}

// Long enough for the recorders to write what clearing the queue triggers (watch time, the Play again play-head).
private val QUIT_SAVE_DELAY = 500.milliseconds

/** The installed YMusic.exe (jpackage launcher); null under `gradlew run`, where tasks have nothing to relaunch. */
private val jumpListExe: String? = System.getProperty("jpackage.app-path")?.takeIf { isWindows }

/** Ticks when the OS hands over a link or the app icon is clicked, so a window hidden to the tray comes back. */
private val showWindowRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

/** The main window, owner of the file dialogs: an ownerless FileDialog opens at the screen's top-left on Windows. */
@Volatile
private var mainWindow: Frame? = null

fun main(args: Array<String>) {
    defaultLinuxSoftwareRendering()
    val instance = if (isWindows) {
        val command = InstanceCommand.of(args.toList())
        val log = rootLogger.fork("instance")
        val claim = try {
            SingleInstance.claim(ymusicDataDir(), command, log)
        } catch (e: InstanceHandoffException) {
            log.log(Logger.Level.ERROR, "another instance holds the lock but did not answer", e)
            flushLogBeforeExit()
            return
        } catch (e: java.io.IOException) {
            log.log(Logger.Level.WARN, "single-instance setup failed, starting without it", e)
            null
        }
        // Handed to the running app; or a Jump List task with no app running, which has nothing to control.
        if (claim == InstanceClaim.HandedOver || command != InstanceCommand.Show) {
            flushLogBeforeExit()
            return
        }
        (claim as InstanceClaim.Owner?)?.instance
    } else {
        null
    }
    useBundledMseNatives()
    // macOS delivers ymusic:// opens (Info.plist CFBundleURLTypes) as Apple Events to the running
    // instance — set before the toolkit starts so a cold-launch link is not lost.
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
        Desktop.getDesktop().setOpenURIHandler { event ->
            IncomingLinks.open(event.uri.toString())
            showWindowRequests.tryEmit(Unit)
        }
    }
    // Dock / Finder / Launchpad click on the running instance (kAEReopenApplication).
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
        Desktop.getDesktop().addAppEventListener(AppReopenedListener { showWindowRequests.tryEmit(Unit) })
    }
    // Opened before anything else: crash-reporting consent lives here.
    val appStore = AppStore.open(ymusicDataDir(), rootLogger.fork("app-store"))
    val settings = kotlinx.coroutines.runBlocking {
        AppStoreSettings.open(appStore).also {
            // First launch decides the app language (device if supported, else English) and persists it.
            ensureAppLanguage(it)
        }
    }
    // Whether this launch is dark, decided before any window exists: it drives both the OS window
    // decorations and the colour the frame wears before Compose has drawn a thing.
    val darkChrome = settings.flow(AppSettings.theme).value != "light"
    applyWindowChrome(darkChrome)
    // Crash reporting is consent-gated and LIVE: init runs once (crash handlers install and
    // stay), but nothing leaves the process unless the Settings toggle says yes (default off).
    if (SENTRY_DSN.isNotEmpty()) {
        val telemetryDeviceId = kotlinx.coroutines.runBlocking { settings.anonymousDeviceId() }
        bindCrashReporting(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            consent = settings.flow(AppSettings.crashReports),
        ) {
            Sentry.initWithPlatformOptions {
                it.applyYMusicTelemetry()
                it.dsn = SENTRY_DSN
                // The commit in the release traces an event to git history (no source upload).
                it.release = SENTRY_RELEASE
                it.environment = if (DEBUG_BUILD) "debug" else "production"
            }
            installSentryDeviceId(telemetryDeviceId)
        }
    }

    application {
        // Dock ignores Window(icon=). Vector is 192dp; Density(1024/192) makes the
        // VectorPainter cache 1024px. Size must stay 192: macOS getResolutionVariants
        // rasterizes size and size*density (Size(1024) was a 5461px / 113MB CGImage).
        val dockTile = painterResource(io.ymusic.desktop.resources.Res.drawable.ymusic_logo_dock)
        LaunchedEffect(dockTile) {
            runCatching {
                if (java.awt.Taskbar.isTaskbarSupported()) {
                    val taskbar = java.awt.Taskbar.getTaskbar()
                    if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                        taskbar.iconImage = dockTile.toAwtImage(
                            Density(1024f / 192f),
                            LayoutDirection.Ltr,
                            androidx.compose.ui.geometry.Size(192f, 192f),
                        )
                    }
                }
            }.onFailure { rootLogger.fork("dock").log(Logger.Level.WARN, "dock icon set failed", it) }
        }
        if (isLinux) {
            val logo = painterResource(io.ymusic.desktop.resources.Res.drawable.ymusic_logo_dock)
            LaunchedEffect(logo) {
                val image = logo.rasterize(256)
                withContext(Dispatchers.IO) {
                    runCatching { installLinuxDesktopEntry(image) }
                        .onFailure { rootLogger.fork("desktop-entry").log(Logger.Level.WARN, "desktop entry install failed", it) }
                }
            }
        }
        var graph by remember { mutableStateOf<AppGraph?>(null) }
        // Hoisted out of the window: whether the window may show at all depends on it.
        var bootError by remember { mutableStateOf<String?>(null) }
        var lockedCookies by remember { mutableStateOf<CookieStoreOpen.Locked?>(null) }
        var windowVisible by remember { mutableStateOf(true) }
        val (initialSize, initialPosition) = remember { loadWindowPrefs() }
        val windowState = rememberWindowState(size = initialSize, position = initialPosition)
        val frame = remember { DesktopFrame(windowState, initialSize, initialPosition) }
        val quitScope = rememberCoroutineScope()
        var quitting by remember { mutableStateOf(false) }
        fun quit() {
            if (quitting) return
            quitting = true
            if (!frame.fullscreen) {
                saveWindowPrefs(frame.persistSize(), frame.persistPosition())
            }
            // A launch during the wind-down below starts a new instance instead of handing over to this one.
            try {
                instance?.release()
            } catch (e: java.io.IOException) {
                rootLogger.fork("instance").log(Logger.Level.WARN, "instance release failed", e)
            }
            windowVisible = false
            graph?.player?.clearQueue()
            quitScope.launch {
                delay(QUIT_SAVE_DELAY)
                // A pinned button keeps its Jump List after exit; tasks for a closed app would only relaunch it.
                if (jumpListExe != null) {
                    withContext(WindowsJumpList.dispatcher) {
                        runCatching { WindowsJumpList.clear() }
                            .onFailure { rootLogger.fork("jump-list").log(Logger.Level.WARN, "jump list clear failed", it) }
                    }
                }
                videoUnderlay.dispose()
                withTimeoutOrNull(LOG_FLUSH_TIMEOUT) { logWorker.flush() }
                exitApplication()
            }
        }
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size to windowState.position }
                .collectLatest { (size, position) ->
                    delay(500)
                    frame.noteFloatingIfIdle()
                    if (!frame.fullscreen && !frame.zoomed) {
                        withContext(Dispatchers.IO) { saveWindowPrefs(size, position) }
                    }
                }
        }
        LaunchedEffect(Unit) {
            showWindowRequests.collect {
                windowVisible = true
                windowState.isMinimized = false
                // Showing raises a hidden window; one already shown but behind other apps needs the nudge.
                mainWindow?.takeIf { it.isShowing }?.let {
                    it.toFront()
                    it.requestFocus()
                }
            }
        }
        LaunchedEffect(windowVisible, windowState.isMinimized) {
            videoUnderlay.shown = windowVisible && !windowState.isMinimized
        }
        Window(
            // Held back until the graph is ready: an empty window that then repaints is a flash of
            // whatever the toolkit felt like showing. Composed all along, so paintWindowGround has
            // already coloured it by the time it appears.
            visible = windowVisible && (graph != null || bootError != null || lockedCookies != null),
            onCloseRequest = {
                // A non-empty queue → the close button minimizes to the system tray and playback
                // carries on; an empty queue → quit.
                if (graph?.player?.queue?.value?.items?.isNotEmpty() == true) windowVisible = false else quit()
            },
            title = "YMusic",
            icon = null,
            state = windowState,
            undecorated = true,
            transparent = true,
            onPreviewKeyEvent = preview@{ event ->
                if (event.type != KeyEventType.KeyDown) return@preview false
                if (handleGlobalShortcut(event, graph?.player, graph?.pip?.nowPlayingOpen, frame)) return@preview true
                val refresh = event.key == Key.F5 ||
                    ((event.isMetaPressed || event.isCtrlPressed) && event.key == Key.R)
                if (refresh) graph?.requestRefresh()
                refresh
            },
        ) {
            paintWindowGround(Color.Transparent)
            // Not Window(icon=): that is one 32dp raster the OS nearest-scales into each of its
            // icon slots (Windows: 16/20/24/32/40/48/64/256 px by DPI). One exact raster per slot.
            val windowIcon = painterResource(Res.drawable.ymusic_logo)
            LaunchedEffect(window, windowIcon) {
                window.iconImages = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { windowIcon.rasterize(it) }
            }
            val closeApp = {
                if (graph?.player?.queue?.value?.items?.isNotEmpty() == true) windowVisible = false else quit()
            }
            val themePref by settings.flow(AppSettings.theme).collectAsState()
            val darkBar = isDarkTheme(themePref, isSystemInDarkTheme())
            val accentPref by settings.flow(AppSettings.themeAccent).collectAsState()
            val palette = designPaletteFor(themePref, isSystemInDarkTheme(), accentPref)
            val frameBorder =
                if (darkBar) Color.White.copy(alpha = 0.14f) else palette.OutlineSoft
            CompositionLocalProvider(LocalAppWindow provides window) {
                DisposableEffect(window) {
                    frame.host = window
                    mainWindow = window
                    FileAvNative.onFullScreen = { on ->
                        SwingUtilities.invokeLater { frame.onNativeFullscreen(on) }
                    }
                    SwingUtilities.invokeLater { FileAvNative.prepareFullscreen(window) }
                    onDispose {
                        FileAvNative.onFullScreen = null
                        if (mainWindow === window) mainWindow = null
                    }
                }
                var shortcutsHelpOpen by remember { mutableStateOf(false) }
                DisposableEffect(Unit) {
                    io.ymusic.ui.GlobalShortcutActions.toggleShortcutsHelp = { shortcutsHelpOpen = !shortcutsHelpOpen }
                    onDispose { io.ymusic.ui.GlobalShortcutActions.toggleShortcutsHelp = {} }
                }
                if (shortcutsHelpOpen) YMusicTheme(palette) { ShortcutsHelpDialog(onDismiss = { shortcutsHelpOpen = false }) }
                val rootFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
                Column(
                    Modifier.fillMaxSize()
                        .then(if (frame.fullscreen) Modifier else Modifier.border(1.dp, frameBorder))
                        // Global arrow-key → focus-move fallback. Fires only when the key bubbles
                        // all the way to the root (nothing inside consumed it — list rows handle
                        // their own arrows via listKeyboardNavigation, TextFields keep their cursor
                        // behaviour). Isolated buttons (back / search / pin / tabs) let arrows
                        // through, land here, and move focus geometrically.
                        .onKeyEvent { event ->
                            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                            val dir = when (event.key) {
                                androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                                androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                                androidx.compose.ui.input.key.Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
                                androidx.compose.ui.input.key.Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
                                else -> return@onKeyEvent false
                            }
                            rootFocusManager.moveFocus(dir)
                        },
                ) {
                    if (!frame.fullscreen) Box(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(palette.SurfaceElevated)
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { frame.toggleZoom() })
                            },
                    ) {
                        WindowDraggableArea(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
                        Row(
                            Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MacTrafficLight(Color(0xFFFF5F57), stringResource(Res.string.cd_close), "✕", closeApp)
                            MacTrafficLight(
                                Color(0xFFFEBC2E),
                                stringResource(Res.string.cd_minimize),
                                "─",
                            ) { window.extendedState = java.awt.Frame.ICONIFIED }
                            MacTrafficLight(
                                Color(0xFF28C840),
                                stringResource(Res.string.cd_fullscreen),
                                "＋",
                                onClick = { frame.toggleFullscreen() },
                            )
                        }
                        Text(
                            "YMusic",
                            color = if (darkBar) Color.White else palette.InkStrong,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        var alwaysOnTop by remember { mutableStateOf(window.isAlwaysOnTop) }
                        LaunchedEffect(alwaysOnTop) {
                            // Both peer windows need the bit or one falls behind other apps; setting it
                            // raises the video frame last, so restack it under the UI.
                            window.isAlwaysOnTop = alwaysOnTop
                            videoUnderlay.frame.isAlwaysOnTop = alwaysOnTop
                            videoUnderlay.orderJustBehind(window)
                        }
                        ActionIcon(
                            icon = if (alwaysOnTop) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(Res.string.cd_always_on_top),
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                            iconSize = 14.dp,
                            hitSize = 28.dp,
                            tint = when {
                                alwaysOnTop -> palette.Accent
                                darkBar -> Color.White.copy(alpha = 0.70f)
                                else -> palette.InkSecondary
                            },
                            onClick = { alwaysOnTop = !alwaysOnTop },
                        )
                    }

                    val g = graph
                    val resizePulse = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
                    DisposableEffect(window) {
                        VideoOverlayNative.onOverlayPress = { videoUnderlay.orderJustBehind(window) }
                        videoUnderlay.ownerWindow = window
                        val stack = object : java.awt.event.WindowAdapter() {
                            override fun windowActivated(e: java.awt.event.WindowEvent) {
                                videoUnderlay.orderJustBehind(window)
                            }
                        }
                        window.addWindowListener(stack)
                        onDispose {
                            window.removeWindowListener(stack)
                            VideoOverlayNative.onOverlayPress = null
                            videoUnderlay.ownerWindow = null
                        }
                    }
                    DisposableEffect(window, g) {
                        if (g == null) return@DisposableEffect onDispose { }
                        var lastW = -1
                        var lastH = -1
                        val listener = object : ComponentAdapter() {
                            override fun componentResized(e: ComponentEvent) {
                                val w = window.width
                                val h = window.height
                                if (w == lastW && h == lastH) return
                                val first = lastW < 0
                                lastW = w
                                lastH = h
                                if (first) return
                                g.windowResizing.value = true
                                resizePulse.tryEmit(Unit)
                            }
                        }
                        window.addComponentListener(listener)
                        onDispose {
                            window.removeComponentListener(listener)
                            g.windowResizing.value = false
                        }
                    }
                    LaunchedEffect(g) {
                        if (g == null) return@LaunchedEffect
                        resizePulse.collectLatest {
                            delay(300)
                            g.windowResizing.value = false
                        }
                    }

                    val bootScope = rememberCoroutineScope()

                    suspend fun boot(open: suspend () -> CookieSession?) {
                        try {
                            val session = open() ?: return
                            graph = buildGraph(settings, session)
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c // the effect was disposed — not a boot failure
                        } catch (t: Throwable) {
                            rootLogger.fork("boot").captureError(t)
                            bootError = t.message ?: t.toString()
                        }
                    }
                    LaunchedEffect(Unit) {
                        boot {
                            when (val opened = openCookieStore(appStore, ymusicDataDir().apply { mkdirs() }, rootLogger.fork("cookies"))) {
                                is CookieStoreOpen.Ready -> opened.session
                                is CookieStoreOpen.Locked -> {
                                    lockedCookies = opened
                                    null
                                }
                            }
                        }
                    }

                    // App applies its own theme; the boot screens before it need this one.
                    Box(Modifier.weight(1f)) {
                        YMusicTheme(palette) {
                            when {
                                bootError != null -> Booting {
                                    Text(startupFailedMessage("$bootError"), color = MaterialTheme.colorScheme.error)
                                }
                                graph == null && lockedCookies != null -> CookieUnlockScreen(
                                    locked = lockedCookies!!,
                                    logger = rootLogger.fork("cookies"),
                                    onSession = { session ->
                                        lockedCookies = null
                                        bootScope.launch { boot { session } }
                                    },
                                )
                                // No spinner while the graph builds: the window paints the app's own ground until
                                // the first frame has something in it.
                                graph == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                                else -> App(graph!!, catalogue = if (DEBUG_BUILD) ({ CatalogueScreen() }) else null)
                            }
                        }
                    }
                }
            }
        }
        // The tray icon lives whenever there is a queue to control — window open or not — and
        // always while the window is hidden (it is the only way back then). A plain click opens
        // the notification-style mini player anchored under the icon; a right-click the player menu.
        var miniOpen by remember { mutableStateOf(false) }
        var miniAnchor by remember { mutableStateOf<java.awt.Point?>(null) }
        var queueNotEmpty by remember { mutableStateOf(false) }
        LaunchedEffect(graph) {
            graph?.player?.queue?.collect {
                queueNotEmpty = it.items.isNotEmpty()
                // On the transition only: a tray click with an empty queue still opens it (the way back to a hidden window).
                if (!queueNotEmpty) miniOpen = false
            }
        }
        val playing = graph?.player?.status?.collectAsState()?.value?.playWhenReady == true
        val playerMenu = rememberPlayerMenu(
            playing = playing,
            hasQueue = queueNotEmpty,
            withQuit = !isMac,
            onTogglePlay = { graph?.player?.togglePlay() },
            onStop = {
                miniOpen = false
                graph?.player?.clearQueue()
            },
            onQuit = { quit() },
        )
        if (isMac) DockMenu(playerMenu)
        if (jumpListExe != null) JumpListTasks(jumpListExe, playing, queueNotEmpty, rootLogger.fork("jump-list"))
        LaunchedEffect(instance) {
            instance?.commands?.collect { command ->
                if (quitting) return@collect
                when (command) {
                    InstanceCommand.Show -> showWindowRequests.tryEmit(Unit)
                    InstanceCommand.TogglePlay -> graph?.player?.togglePlay()
                    InstanceCommand.Stop -> {
                        miniOpen = false
                        graph?.player?.clearQueue()
                    }
                    InstanceCommand.Quit -> quit()
                }
            }
        }
        if (!quitting && (queueNotEmpty || !windowVisible)) {
            PlayerTray(
                painterResource(Res.drawable.ymusic_logo),
                rootLogger.fork("tray"),
                menu = playerMenu.takeUnless { isMac },
            ) { point ->
                miniAnchor = point
                miniOpen = !miniOpen
            }
        }
        val g = graph
        if (miniOpen && g != null) {
            MiniPlayerWindow(
                graph = g,
                anchor = miniAnchor,
                onOpenApp = {
                    windowVisible = true
                    miniOpen = false
                },
                onDismiss = {
                    // End the listening session: the queue clears (which retires the tray icon),
                    // and with no window left the app exits; an open window keeps running.
                    miniOpen = false
                    g.player.clearQueue()
                    if (!windowVisible) quit()
                },
                onClose = { miniOpen = false },
            )
        }
        // Tray-only mode also drops the Dock icon (macOS accessory activation); restoring the
        // window brings it back and re-activates the app.
        LaunchedEffect(windowVisible) { MacDock.setDockIconVisible(windowVisible, rootLogger.fork("mac-dock")) }
    }
}

private suspend fun buildGraph(settings: AppStoreSettings, session: CookieSession): AppGraph {
    val dataDir = ymusicDataDir().apply { mkdirs() }
    // File-backed jar for YouTube (engine cookie/get|set + player + proxy) and the YMusic API.
    // Desktop has no WebView cookie store: sign-in imports cookies from a phone QR or a browser paste.
    val cookies = session.jar
    // One client stack, one disk cache. The engine HTTP client stays jar-less — JS cooks Cookie
    // headers against [cookies] — while the API client / media proxy use the jar directly.
    val http = AppHttpClients(File(session.cacheRoot, HTTP_CACHE_DIR).absolutePath.toPath(), logger = rootLogger)
    val deviceId = settings.anonymousDeviceId()

    // The one client of the YMusic service; null when this build has no service url.
    val runningBundles = RunningBundles(
        engineFloor = maxOf(ENGINE_JS_VERSION.toInt(), settings.flow(AppSettings.lastLoadedEngineJsVer).value),
        playerFloor = maxOf(EMBEDDED_PLAYER_JS_VERSION.toInt(), settings.flow(AppSettings.lastLoadedPlayerJsVer).value),
    )
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = REMOTE_CONFIG_URL.takeIf { it.isNotBlank() }?.let { baseUrl ->
        HttpApiService(
            client = http.engine.newBuilder().apply {
                cookieJar = cookies
            }.build(),
            os = desktopOsKey(),
            osVersion = System.getProperty("os.version") ?: "",
            appVersionCode = APP_VERSION_CODE,
            appVersionName = APP_VERSION_NAME,
            deviceId = deviceId,
            baseUrl = baseUrl,
            now = { System.currentTimeMillis() },
            arch = desktopArch(),
            bundles = runningBundles,
        )
    }
    // Flags, the newest JS bundle revisions, and update availability. Started here so the first
    // fetch overlaps the rest of startup; nothing waits on it, the JS bundles follow what it publishes.
    val remoteConfig = RemoteConfig(
        api = api,
        cacheFile = File(dataDir, "remote-config.json").absolutePath.toPath(),
        fileSystem = FileSystem.SYSTEM,
        scope = backgroundScope,
        now = { System.currentTimeMillis() },
        log = rootLogger.fork("remote-config"),
    ).also { it.checkUpdate() } // app open is itself a hotpoint
    // Anonymous usage stats — held by the graph so any screen can record events. The "Send
    // anonymous stats" answer is read per event, so the Settings switch applies live, and a
    // disabled gate costs nothing.
    val stats = StatUsage(
        api = api,
        enabled = { settings.flow(AppSettings.stats).value },
        queueFile = File(dataDir, "stat-queue.json").absolutePath.toPath(),
        fileSystem = FileSystem.SYSTEM,
        scope = backgroundScope,
        now = { System.currentTimeMillis() },
        log = rootLogger.fork("stats"),
    ).also { it.onAppStart() }

    // The engine bundle: jar-resource embed (no compiled-in constant) + file cache + the revision
    // the config service publishes, which the manager follows from start.
    val engineSource = DefaultJsSourceProvider(
        client = http.engine,
        config = remoteConfig.bundleConfig { it.engineJs },
        name = ENGINE_BUNDLE,
        publicKey = BUNDLE_PUBLIC_KEY,
        embedVersion = ENGINE_JS_VERSION,
        embedSource = { readAppResource("engine_ymusic.js") },
        fileSystem = FileSystem.SYSTEM,
        directory = File(dataDir, "engine-js").absolutePath.toPath(),
        now = { System.currentTimeMillis() },
        logger = rootLogger,
        ioDispatcher = Dispatchers.IO,
    )
    val engineCache = desktopEngineCacheStore(File(session.cacheRoot, ENGINE_CACHE_DIR), rootLogger)
    val engines = withContext(Dispatchers.IO) {
        DynamicEngineManager(
            http.engine,
            appVersion = APP_VERSION_CODE,
            logger = rootLogger,
            cacheStore = engineCache,
            cookieJar = cookies,
            settings = settings,
            strictStints = DEBUG_BUILD,
            // Every Sentry event and service call names the engine-js bundle that was live (start + each swap).
            onBackendVersion = { version ->
                installSentryEngineVersion(version)
                runningBundles.engine = version
                backgroundScope.launch { settings.put(AppSettings.lastLoadedEngineJsVer, version) }
            },
            jsSource = engineSource,
        )
    }
    engines.start()

    val mediaStore = withContext(Dispatchers.IO) {
        BetterMediaStore.create(mediaStoreJdbcDriver(File(dataDir, "media-store.db").absolutePath))
    }

    // Thumbnails: everything is a fetcher — http on a cache-less client (originals stream to the
    // fetcher's disk cache, decode from file) + local files + ffmpeg-extracted embedded art
    // (absent ffmpeg just keeps art:// urls unresolved → placeholder boxes).
    val imageNetworkFetcher = networkFetcher(
        http.image,
        FileSystem.SYSTEM,
        File(dataDir, "image-source-cache").absolutePath.toPath(),
    )
    var loaderRef: ImageLoader? = null
    val imageLoader = ImageLoader(
        fetchers = listOfNotNull(
            imageNetworkFetcher,
            fileFetcher(),
            resolveFfmpeg()?.let {
                mediaStoreArtFetcher(
                    FfmpegMetadataGetter(it),
                    mediaStore,
                    loaderYoutubeArt { checkNotNull(loaderRef) },
                    coverCache = ImageSourceCache(FileSystem.SYSTEM, File(dataDir, "image-cover-cache").absolutePath.toPath(), 64L * 1024 * 1024),
                )
            },
        ),
        scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, File(dataDir, "image-scaled-cache").absolutePath.toPath()),
        logger = rootLogger,
        memoryCacheBytes = defaultMemoryCacheBudget(),
        variantIndex = DiskVariantIndex(FileSystem.SYSTEM, File(dataDir, "image-variants").absolutePath.toPath()),
    ).also { loaderRef = it }

    val localSurface = LocalVideoSurface()
    val mseSurface = io.ymusic.mse.MseVideoSurface()

    // Player cache: SABR miss is forwarded and teed to disk so re-plays/downloads reuse it.
    // The player cache; shared read-only with the download stack so a download reuses segments already
    // cached from playback (never writing into it).
    val playerCache = withContext(Dispatchers.IO) {
        val cacheRoot = File(dataDir, "player-cache").apply { mkdirs() }
        // One persistent DB for both the blob-span index and the segment index.
        val driver = jvmSegmentDatabaseDriver("jdbc:sqlite:${File(cacheRoot, "cache-index.db").absolutePath}")
        MediaCache(
            DiskMediaBlobCache(
                cacheRoot.absolutePath.toPath(),
                FileSystem.SYSTEM,
                SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() }),
                logger = rootLogger.fork("player-cache"),
            ),
            SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
            SqlDelightCacheStats(driver),
        )
    }
    val mediaProxy = withContext(Dispatchers.IO) {
        val client = KmpHttpClient.create {
            defaultExecuteTimeout = APP_HTTP_TIMEOUT
            logger = rootLogger.fork("kmphttp")
            cookieJar = cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        }
        LocalMediaProxy(
            playerCache, client,
            userAgent = DESKTOP_CHROME_UA,
            logger = rootLogger,
            // Chatty per-part/decision logs; -Dio.ymusic.player.verbose overrides the build default.
            verbose = System.getProperty("io.ymusic.player.verbose")?.toBoolean() ?: DEBUG_BUILD,
            // -Dymusic.player.serveSabrHits=false → fill-only, to test whether synthesized hits wedge switches.
            serveSabrHits = System.getProperty("io.ymusic.player.serveSabrHits", "true").toBoolean(),
        )
    }

    var ytEngine: io.ymusic.player.YtEngine? = null
    val player = withContext(Dispatchers.Main) {
        createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(localSurface, resolveFfmpeg())) },
                youtube = {
                    io.ymusic.player.mse.MsePlayer.create(
                        client = http.engine,
                        appVersion = APP_VERSION_CODE,
                        logger = rootLogger,
                        cacheStore = engineCache,
                        cookieJar = cookies,
                        settings = settings,
                        videoSurface = mseSurface,
                        main = engines,
                        mediaProxy = mediaProxy,
                        playerJs = playerJsSource(
                            client = http.engine,
                            config = remoteConfig.bundleConfig { it.playerJs },
                            publicKey = BUNDLE_PUBLIC_KEY,
                            fileSystem = FileSystem.SYSTEM,
                            directory = File(dataDir, "player-js").absolutePath.toPath(),
                            now = { System.currentTimeMillis() },
                            logger = rootLogger,
                            ioDispatcher = Dispatchers.IO,
                        ),
                        onPlayerJsVersion = { version ->
                            installSentryPlayerVersion(version)
                            runningBundles.player = version
                            backgroundScope.launch { settings.put(AppSettings.lastLoadedPlayerJsVer, version) }
                        },
                    ).also { ytEngine = it }
                },
            ),
        )
    }

    // OS media surface — macOS Now Playing widget + media keys (Linux MPRIS / Windows SMTC have no
    // backend yet). Commands come back on the Main dispatcher, where the player is driven.
    connectMediaSession(player, CoroutineScope(Dispatchers.Main), rootLogger.fork("now-playing"))

    // Offline downloads: SABR stack over ffmpeg. Spec comes from the MSE engine's cached
    // /player + decoded googlevideo URL (preload if that video is not already cached).
    val downloadService = withContext(Dispatchers.IO) {
        val ffmpegPath = resolveFfmpeg()
        val ffmpeg = ffmpegPath?.let { io.ymusic.download.Ffmpeg(it) }
        // Probes a finished download's metadata into its Library bucket (same path as the folder scan);
        // null without ffmpeg → registerCompletedDownload falls back to a hand-built draft.
        val downloadScanner = ffmpegPath?.let {
            MediaScanner.create(
                mediaStore,
                FileSystemDocumentsProvider(FileSystem.SYSTEM),
                FfmpegMetadataExtractor(FfmpegMetadataGetter(it)),
            )
        }
        val store = io.ymusic.download.DownloadStore(
            io.ymusic.download.downloadDatabaseJdbcDriver("jdbc:sqlite:${File(dataDir, "downloads.db").absolutePath}"),
            rootLogger,
        )
        val sabrClient = KmpHttpClient.create {
            defaultExecuteTimeout = APP_HTTP_TIMEOUT
            logger = rootLogger.fork("kmphttp")
            cookieJar = cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
        }
        val resolver = YtEngineSabrSpecResolver {
            withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
            ytEngine ?: error("youtube engine missing")
        }
        // Own segment index (byte range per segment) → SABR resume across pause/re-attest. Separate from
        // the player cache DB: downloads store bytes in their own file, not the player blob cache.
        val segmentIndex = SqlDelightSegmentIndex(
            jvmSegmentDatabaseDriver("jdbc:sqlite:${File(dataDir, "download-segments.db").absolutePath}"),
            now = { System.currentTimeMillis() },
        )
        io.ymusic.download.DownloadService(
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = { System.currentTimeMillis() },
            resolver = resolver,
            client = sabrClient,
            userAgent = DESKTOP_CHROME_UA,
            ffmpeg = ffmpeg,
            storage = io.ymusic.download.DefaultDownloadStorage(
                FileSystem.SYSTEM,
                File(dataDir, "download-work").absolutePath.toPath(),
            ),
            mediaCache = playerCache, // read-only (see YtStreamDownloader): reuse cached playback bytes
            segmentIndex = segmentIndex,
            // The user's chosen download location — always set: the download flow requires picking one
            // before enqueue.
            downloadFolder = { settings.downloadLocationFolder()!! },
            onCompleted = { task, result -> registerCompletedDownload(mediaStore, settings, downloadScanner, task, result) },
            logger = rootLogger,
            playerResponse = resolver::playerResponse,
        )
    }

    // Self-update: the config service's appUpdate, verified against the pinned update key.
    // Disabled outright without a key — an unverifiable update is worse than none.
    val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updateService = UpdateService(
        client = http.engine,
        publicKey = UPDATE_PUBLIC_KEY,
        currentVersionCode = APP_VERSION_CODE,
        platform = desktopPlatformKey(desktopOsKey()),
        downloadDir = File(dataDir, "update"),
        installer = installerFor(desktopOsKey()),
        scope = updateScope,
        configUpdates = remoteConfig.config.map { it.appUpdate },
        logger = rootLogger,
        autoDownload = { settings.flow(AppSettings.autoDownloadUpdates).value },
        autoDownloadFailed = { settings.flow(AppSettings.updateAutoDownloadFailed).value },
        rememberAutoDownloadFailed = { settings.put(AppSettings.updateAutoDownloadFailed, it) },
    )
    val desktopUpdates = if (updateService.isEnabled) {
        updateService.pruneDownloads()
        DesktopAppUpdates(
            updateService,
            updateScope,
            freshConfig = { remoteConfig.awaitFresh(timeoutMs = 3_000L) },
            downloadSite = { remoteConfig.config.value.downloadPageUrl },
        )
    } else {
        null
    }

    val windowResizing = MutableStateFlow(false)
    return AppGraph(
        engines,
        player,
        logger = rootLogger,
        playerCache = playerCache,
        mediaStore = mediaStore,
        playHistory = PlayHistoryStore(playHistoryJdbcDriver(File(dataDir, "play-history.db").absolutePath)),
        sponsorBlockStore = SqliteSponsorBlockStore(
            sponsorBlockJdbcDriver(File(dataDir, "sponsor-block.db").absolutePath),
        ),
        scanLibraryFolder = { onProgress -> scanPickedFolder(mediaStore, onProgress) },
        libraryFolderPicker = { pickAndRegisterFolder(mediaStore) },
        canWriteFolder = { uri -> File(Uri.parse(uri).filePath()).let { it.isDirectory && it.canWrite() } },
        probeDownloadFolder = { uri -> probeDownloadFolder(File(Uri.parse(uri).filePath())) },
        rescanLibrary = { folderUri, onProgress -> rescanKnownRoots(mediaStore, folderUri, onProgress) },
        deleteFromDisk = { item ->
            withContext(Dispatchers.IO) {
                check(item.uri.scheme.contentEquals("file")) { "not a local file: ${item.uri}" }
                // mustExist=false: an already-gone file still clears its library row.
                FileSystem.SYSTEM.delete(item.uri.filePath().toPath(), mustExist = false)
            }
        },
        fileRenaming = DesktopFileRenaming,
        revealInFolder = { uri ->
            withContext(Dispatchers.IO) {
                val path = Uri.parse(uri).filePath()
                runCatching { ProcessBuilder("open", "-R", path).start() } // mac Finder reveal
                    .onFailure { rootLogger.fork("reveal").log(Logger.Level.WARN, "Finder reveal failed for $path", it) }
            }
        },
        appVersion = "$APP_VERSION_NAME · ${desktopArch()}",
        appVersionName = APP_VERSION_NAME,
        appVersionCode = APP_VERSION_CODE,
        runningBundles = runningBundles,
        imageLoader = imageLoader,
        settings = settings,
        // MSE blit and ffmpeg frames scale; safe to display at art-tile size.
        surfaceScalesDown = true,
        loginBrowser = null,
        guest = session.isGuest,
        cookieSignIn = if (session.isGuest) null else { onComplete, onBack ->
            DesktopCookieSignIn(
                api = api,
                importCookies = { cookies.importSessionCookies(it) },
                loginStatus = { engines.ytLoginStatus() },
                protectWithPassword = if (session.canProtect) session::protect else null,
                logger = rootLogger,
                onComplete = onComplete,
                onBack = onBack,
            )
        },
        downloads = downloadService,
        updates = desktopUpdates,
        stats = stats,
        api = api,
        remoteConfig = remoteConfig.config,
        captureFeedbackReport = captureFeedbackReportAction.takeIf { SENTRY_DSN.isNotEmpty() },
        sendFeedbackMessage = ::sendFeedbackMessage.takeIf { SENTRY_DSN.isNotEmpty() },
        platformInfo = "${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${desktopArch()}",
        createDocument = { name, _ -> pickSaveFile(name)?.let { file -> withContext(Dispatchers.IO) { FileSystem.SYSTEM.sink(file.toOkioPath()) } } },
        openDocument = { _ -> pickOpenFile()?.let { file -> withContext(Dispatchers.IO) { FileSystem.SYSTEM.source(file.toOkioPath()) } } },
        windowResizing = windowResizing,
    ) { modifier ->
        DesktopPlayerSurface(localSurface, mseSurface, windowResizing, player.status, modifier)
    }
}

/**
 * Wires both shared panels to their surfaces exactly once per process. Idempotent — every
 * [DesktopPlayerSurface] call site invokes this but only the first one actually runs. Panels
 * stay alive for the lifetime of the app; the Canvas HWND / NSView and its D3D11 swapchain /
 * AVSampleBufferDisplayLayer are never torn down by Compose re-composition. Attach to
 * [videoUnderlay] happens once too, and later swaps between localPanel/msePanel are driven
 * by [localSurface].visible observed OUTSIDE Compose — not by Compose recomposition.
 *
 * Doing the attach from inside a Compose DisposableEffect was the root of the mini↔NowPlaying
 * black screen: each DesktopPlayerSurface call site is a separate composition slot, so the
 * effect ran per site and called videoUnderlay.attach(panel) → frame.removeAll() →
 * frame.add(panel), which cycled the heavyweight Canvas's removeNotify/addNotify and spun
 * up a fresh HWND every switch. The new HWND re-triggered file_av_set_video_hwnd → the
 * deferred decoder reopen → an av_seek_frame that flushed the audio ring (audio glitch).
 */
private val panelSwapScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
)

private fun wirePlayerSurfacesOnce(
    localSurface: LocalVideoSurface,
    mseSurface: io.ymusic.mse.MseVideoSurface,
) {
    if (!playerSurfaceWired.compareAndSet(false, true)) return
    FileAvNative.overlayMouse = FileAvNative.OverlayMouse { id, _, _, _, _, _, _ ->
        // Do not dispatchEvent into Skia from this JNI click — that froze AppKit.
        if (id == MouseEvent.MOUSE_RELEASED) {
            SwingUtilities.invokeLater { VideoOverlayNative.poke() }
        }
    }
    sharedMsePanel.onPainted = { mseSurface.swingShown = it }
    mseSurface.swingBlit = {
        sharedMsePanel.setImage(it)
        if (it != null) mseSurface.lastStill = copyRgb(it)
    }
    sharedMsePanel.onPeer = { c ->
        mseSurface.nativePeer = c
        sharedMsePanel.setHardware(mseSurface.attach(c))
        if (sharedMsePanel.covered) FileAvNative.setOverlayHidden(true)
    }
    sharedMsePanel.extraSnapshot = {
        val frame = MseHostNative.copyFrame(mseSurface.host)
        if (frame == null) null else bgraToRgb(frame.width, frame.height, frame.bgra)
    }
    mseSurface.onNativePresent = { sharedMsePanel.setHardware(it) }
    sharedLocalPanel.onPainted = { localSurface.swingShown = it }
    localSurface.swingBlit = { sharedLocalPanel.setImage(it) }
    sharedLocalPanel.onPeer = { localSurface.nativePeer = it }
    sharedLocalPanel.extraSnapshot = {
        localSurface.swingShown?.let { copyRgb(it) }
    }
    localSurface.onNativePresent = { sharedLocalPanel.setHardware(it) }
    // Attach the panel that matches the active engine; swap only when the engine itself
    // changes (local file ↔ YouTube MSE). mini↔NowPlaying never flips this flag so the
    // Canvas and its HWND persist forever.
    panelSwapScope.launch {
        localSurface.visible.collect { visible ->
            // videoUnderlay.attach touches Swing component hierarchy — must run on EDT.
            SwingUtilities.invokeLater {
                videoUnderlay.attach(if (visible) sharedLocalPanel else sharedMsePanel)
            }
        }
    }
}

@Composable
private fun DesktopPlayerSurface(
    localSurface: LocalVideoSurface,
    mseSurface: io.ymusic.mse.MseVideoSurface,
    windowResizing: MutableStateFlow<Boolean>,
    playbackStatus: kotlinx.coroutines.flow.StateFlow<PlaybackStatus>,
    modifier: Modifier,
) {
    wirePlayerSurfacesOnce(localSurface, mseSurface)
    val localVisible by localSurface.visible.collectAsState()
    val status by playbackStatus.collectAsState()
    val msePanel = sharedMsePanel
    val localPanel = sharedLocalPanel
    val windowResizingNow by windowResizing.collectAsState()
    LaunchedEffect(status.playWhenReady, windowResizingNow) {
        if (status.playWhenReady && !windowResizingNow) {
            msePanel.setCovered(false)
            localPanel.setCovered(false)
        }
    }
    // videoUnderlay.attach is done in wirePlayerSurfacesOnce (observes localSurface.visible
    // outside Compose), so panel identity doesn't tie into any DisposableEffect here. Panel
    // selection stays local so per-site effects (window-resize still capture) still see the
    // active panel.
    val panel = if (localVisible) localPanel else msePanel
    val hostWindow = LocalAppWindow.current
    val density = LocalDensity.current
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(windowResizingNow) {
        if (windowResizingNow) {
            still = panel.captureStill()?.toComposeImageBitmap()
            panel.setCovered(true)
        } else {
            still = null
            panel.setCovered(false)
        }
    }
    Box(
        modifier
            .onSizeChanged {
                if (it.width > 0 && it.height > 0) {
                    mseSurface.setBlitSize(it.width, it.height)
                    localSurface.setBlitSize(it.width, it.height)
                }
            }
            .onGloballyPositioned { coords ->
                val win = hostWindow ?: return@onGloballyPositioned
                val loc = try {
                    win.locationOnScreen
                } catch (_: Exception) {
                    return@onGloballyPositioned
                }
                val p = coords.positionInWindow()
                val insets = win.insets
                with(density) {
                    // Round outward so the hole always covers the whole video box.
                    val left = floor(p.x.toDp().value).toInt()
                    val top = floor(p.y.toDp().value).toInt()
                    val right = ceil((p.x + coords.size.width).toDp().value).toInt()
                    val bottom = ceil((p.y + coords.size.height).toDp().value).toInt()
                    videoUnderlay.setHole(
                        loc.x + insets.left + left,
                        loc.y + insets.top + top,
                        right - left,
                        bottom - top,
                    )
                }
            },
    ) {
        if (windowResizingNow) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                still?.let { bmp ->
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        surfaceMountCount++
        onDispose {
            surfaceMountCount--
            // A swap disposes the old site before the new one mounts, in the same apply: check once it has.
            SwingUtilities.invokeLater {
                if (surfaceMountCount == 0) videoUnderlay.setHole(0, 0, 0, 0)
            }
        }
    }
}

/**
 * Heavyweight [Canvas] for JAWT / AVSampleBufferDisplayLayer. CPU [setImage] is the fallback
 * when attach fails. [contains] always misses so Compose keeps clicks.
 */
private class MseVideoPanel : JPanel(BorderLayout()) {
    @Volatile private var img: BufferedImage? = null

    @Volatile private var hardware = false

    /** Latest native-present state, kept while [covered] so uncovering restores it. */
    @Volatile private var nativeLive = false
    var covered = false
        private set

    @Volatile var onPainted: ((BufferedImage?) -> Unit)? = null

    @Volatile var onPeer: ((Canvas?) -> Unit)? = null

    @Volatile var extraSnapshot: (() -> BufferedImage?)? = null
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var overlayX = Int.MIN_VALUE
    private var overlayY = Int.MIN_VALUE
    private var overlayW = 0
    private var overlayH = 0
    private val canvas =
        object : Canvas() {
            override fun addNotify() {
                super.addNotify()
                if (!covered && width > 0 && height > 0) onPeer?.invoke(this)
            }

            override fun removeNotify() {
                overlayX = Int.MIN_VALUE
                if (!covered) onPeer?.invoke(null)
                super.removeNotify()
            }

            override fun contains(x: Int, y: Int): Boolean = false

            override fun paint(g: Graphics) {
                val image = img
                if (image == null || hardware) {
                    /* HW overlay letterbox is transparent; this fill is the hole, not a VT sample. */
                    g.color = AwtColor.BLACK
                    g.fillRect(0, 0, width, height)
                    return
                }
                onPainted?.invoke(image)
                val g2 = g as Graphics2D
                val scale = min(width / image.width.toDouble(), height / image.height.toDouble())
                val dw = (image.width * scale).toInt().coerceAtLeast(1)
                val dh = (image.height * scale).toInt().coerceAtLeast(1)
                val x = (width - dw) / 2
                val y = (height - dh) / 2
                // Fill only the letterbox bars: the image covers its rect in a single drawImage,
                // so the frame area never flashes to black between the clear and the blit. (The
                // heavyweight Canvas's Graphics draws straight to the window surface, so any
                // whole-canvas fillRect before drawImage is a visible mid-cycle black flash
                // whenever a repaint catches the painter between the two calls.)
                g.color = AwtColor.BLACK
                if (y > 0) {
                    g.fillRect(0, 0, width, y)
                    g.fillRect(0, y + dh, width, height - y - dh)
                }
                if (x > 0) {
                    g.fillRect(0, y, x, dh)
                    g.fillRect(x + dw, y, width - x - dw, dh)
                }
                if (dw == image.width && dh == image.height) {
                    g2.drawImage(image, x, y, null)
                } else {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g2.drawImage(image, x, y, dw, dh, null)
                }
            }

            override fun update(g: Graphics) = paint(g)
        }

    init {
        isOpaque = true
        background = AwtColor.BLACK
        isFocusable = false
        isRequestFocusEnabled = false
        canvas.background = AwtColor.BLACK
        canvas.isFocusable = false
        // HW Canvas / VT NSView sit above Skia. Do not copy AWT events into
        // Compose — that freeze is AppKit re-entry. Click toggles chrome.
        val overlayClicks = object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                SwingUtilities.invokeLater { VideoOverlayNative.poke() }
            }
        }
        canvas.addMouseListener(overlayClicks)
        canvas.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (covered || canvas.width <= 0 || canvas.height <= 0) return
                    /* Compose addNotify is often 0×0. 8f510cba attached here; layout-only
                       skipped that and stayed on the CPU blit (~300%). */
                    if (!hardware) onPeer?.invoke(canvas)
                    else layoutOverlay()
                }

                override fun componentMoved(e: ComponentEvent) {
                    if (!covered && hardware) layoutOverlay()
                }
            },
        )
        add(canvas, BorderLayout.CENTER)
    }

    override fun doLayout() {
        canvas.setBounds(0, 0, width, height)
    }

    private fun layoutOverlay() {
        if (!canvas.isShowing || canvas.width <= 0 || canvas.height <= 0) return
        val loc = try {
            canvas.locationOnScreen
        } catch (_: Exception) {
            return
        }
        if (loc.x == overlayX && loc.y == overlayY && canvas.width == overlayW && canvas.height == overlayH) return
        overlayX = loc.x
        overlayY = loc.y
        overlayW = canvas.width
        overlayH = canvas.height
        FileAvNative.layoutVideo(loc.x, loc.y, canvas.width, canvas.height)
    }

    override fun contains(x: Int, y: Int): Boolean = false

    fun captureStill(): BufferedImage? {
        img?.let { return copyRgb(it) }
        val wh = IntArray(2)
        val bgra = FileAvNative.snapshotOverlay(wh)
        if (bgra != null && wh[0] > 0 && wh[1] > 0) return bgraToRgb(wh[0], wh[1], bgra)
        extraSnapshot?.invoke()?.let { return it }
        return null
    }

    fun holdLastFrame(snap: BufferedImage) {
        covered = true
        hardware = false
        img = snap
        FileAvNative.setOverlayHidden(true)
        canvas.repaint()
    }

    fun setCovered(covered: Boolean) {
        if (this.covered == covered) return
        this.covered = covered
        if (covered) {
            val snap = captureStill()
            FileAvNative.setOverlayHidden(true)
            hardware = false
            img = snap
            canvas.repaint()
        } else {
            FileAvNative.setOverlayHidden(false)
            hardware = nativeLive
            if (nativeLive) img = null
            overlayX = Int.MIN_VALUE
            layoutOverlay()
            canvas.repaint()
        }
    }

    fun setHardware(on: Boolean) {
        nativeLive = on
        if (covered) return
        hardware = on
        if (on) img = null
        canvas.repaint()
    }

    fun setImage(image: BufferedImage?) {
        if (hardware || covered) return
        img = image
        if (pending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                pending.set(false)
                canvas.repaint()
            }
        }
    }
}

private fun copyRgb(src: BufferedImage): BufferedImage {
    val dst = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    val g = dst.createGraphics()
    g.drawImage(src, 0, 0, null)
    g.dispose()
    return dst
}

private fun bgraToRgb(width: Int, height: Int, bgra: ByteArray): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val pixels = (img.raster.dataBuffer as DataBufferInt).data
    val n = minOf(pixels.size, width * height, bgra.size / 4)
    var i = 0
    var p = 0
    while (i < n) {
        val b = bgra[p].toInt() and 0xff
        val g = bgra[p + 1].toInt() and 0xff
        val r = bgra[p + 2].toInt() and 0xff
        pixels[i] = (r shl 16) or (g shl 8) or b
        i++
        p += 4
    }
    return img
}

/** FileDialog pick + full scan into [store]; returns how it ended, or null when the pick was canceled. */
private suspend fun scanPickedFolder(store: BetterMediaStore, onProgress: (Int) -> Unit): ScanResult? {
    val picked = pickFolder() ?: return null
    val rootUri = fileUri(picked.absolutePath)
    return scanUris(store, listOf(rootUri), onProgress)
}

/** Pick a folder and register it as a Library bucket (no media scan — works even when empty), returning
 *  its uri. Used to choose a download location. */
private suspend fun pickAndRegisterFolder(store: BetterMediaStore): String? {
    val picked = pickFolder() ?: return null
    val uri = fileUri(picked.absolutePath)
    store.upsertBucket(BucketDraft(uri = uri, name = picked.name))
    return uri.toString()
}

/** Creates, writes and deletes a scratch file in [folder] — the steps a finished download takes. */
private suspend fun probeDownloadFolder(folder: File): FolderWriteProblem? = withContext(Dispatchers.IO) {
    val log = rootLogger.fork("download-folder-probe", mapOf("folder" to folder.path))
    if (!folder.isDirectory) return@withContext FolderWriteProblem.Missing
    val probe = try {
        java.nio.file.Files.createTempFile(folder.toPath(), ".ymusic-write-test", null)
    } catch (e: java.nio.file.AccessDeniedException) {
        log.log(Logger.Level.WARN, "test file refused", e)
        return@withContext FolderWriteProblem.NoPermission
    } catch (e: java.nio.file.NoSuchFileException) {
        log.log(Logger.Level.WARN, "folder gone", e)
        return@withContext FolderWriteProblem.Missing
    } catch (e: Exception) {
        log.log(Logger.Level.WARN, "test file not created", e)
        return@withContext FolderWriteProblem.CannotCreateFiles
    }
    try {
        java.nio.file.Files.write(probe, byteArrayOf(0))
        null
    } catch (e: java.nio.file.AccessDeniedException) {
        log.log(Logger.Level.WARN, "test file not writable", e)
        FolderWriteProblem.NoPermission
    } catch (e: Exception) {
        log.log(Logger.Level.WARN, "test file not writable", e)
        FolderWriteProblem.CannotCreateFiles
    } finally {
        try {
            java.nio.file.Files.deleteIfExists(probe)
        } catch (e: Exception) {
            log.log(Logger.Level.WARN, "test file left behind", e)
        }
    }
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

/** Scans each root [uris] into [store], reporting the running file count through [onProgress] and
 *  returning the combined result. */
private suspend fun scanUris(store: BetterMediaStore, uris: List<Uri>, onProgress: (Int) -> Unit): ScanResult {
    val ffmpeg = resolveFfmpeg()
        ?: return ScanResult.ExtractorMissing
    val scanner = MediaScanner.create(
        store,
        FileSystemDocumentsProvider(FileSystem.SYSTEM),
        FfmpegMetadataExtractor(FfmpegMetadataGetter(ffmpeg)),
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

// The window-geometry memory lives in its OWN tiny file, read synchronously before the first
// frame — the settings store only exists once the async graph build runs, too late to size the
// window.
/**
 * Everything the app persists lives here: engine + image caches, the media store, downloads,
 * cookies, window geometry. `$YMUSIC_DATA_DIR` relocates the lot — a packaged build's launcher
 * passes neither JVM args nor JAVA_TOOL_OPTIONS through, so an env var is the only way to run a
 * second copy (testing a release build while the dev one is open) or to keep state on an external
 * disk.
 */
internal fun ymusicDataDir(): File =
    System.getenv("YMUSIC_DATA_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".ymusic")

private val windowPrefsFile = File(ymusicDataDir(), "window.properties")
private val defaultWindowSize = DpSize(480.dp, 860.dp)

private fun loadWindowPrefs(): Pair<DpSize, WindowPosition> {
    val p = java.util.Properties()
    runCatching { windowPrefsFile.inputStream().use(p::load) } // no file yet / unreadable → defaults
    val w = p.getProperty("width")?.toFloatOrNull()
    val h = p.getProperty("height")?.toFloatOrNull()
    // Sanity floor: never restore a degenerate size a bad write (or a past bug) left behind.
    val size = if (w != null && h != null && w >= 320f && h >= 480f) DpSize(w.dp, h.dp) else defaultWindowSize
    val x = p.getProperty("x")?.toFloatOrNull()
    val y = p.getProperty("y")?.toFloatOrNull()
    val position = when {
        x == null || y == null -> WindowPosition.PlatformDefault
        // The saved spot may be gone (monitor unplugged, resolution change) — a window whose title
        // bar lands on no screen can't be grabbed back, so it resets to center instead.
        isOnScreen(x, y) -> WindowPosition(x.dp, y.dp)
        else -> WindowPosition.Aligned(Alignment.Center)
    }
    return size to position
}

/** True when a title-bar point of a window at ([x], [y]) lands on some connected screen. */
private fun isOnScreen(x: Float, y: Float): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
        val b = device.defaultConfiguration.bounds
        x + 100 > b.x && x + 100 < b.x + b.width && y + 20 > b.y && y + 20 < b.y + b.height
    }
}.getOrDefault(true) // can't enumerate screens → don't block the restore

private fun saveWindowPrefs(size: DpSize, position: WindowPosition) {
    val p = java.util.Properties()
    p.setProperty("width", size.width.value.toString())
    p.setProperty("height", size.height.value.toString())
    if (position.isSpecified) {
        p.setProperty("x", position.x.value.toString())
        p.setProperty("y", position.y.value.toString())
    }
    try {
        windowPrefsFile.parentFile?.mkdirs()
        windowPrefsFile.outputStream().use { p.store(it, null) }
    } catch (e: java.io.IOException) {
        // Environmental (disk full, permissions) — the window still works, only the memory is lost.
        rootLogger.fork("window").log(Logger.Level.WARN, "window prefs save failed", e)
    }
}

/** Sends the app logs as one anonymous Sentry report for the Feedback screen; returns its event id. */
private val captureFeedbackReportAction: suspend () -> String? = {
    withContext(Dispatchers.IO) {
        runCatching { logWorker.flush() }
            .onFailure { rootLogger.fork("feedback").log(Logger.Level.WARN, "log flush before feedback failed", it) }
        captureFeedbackReport(
            logWorker.filePaths().map { path ->
                io.sentry.kotlin.multiplatform.Attachment(path, path.substringAfterLast('/').substringAfterLast('\\'), "text/plain")
            },
        )
    }
}

/** The "save as" dialog for a playlist backup; null when canceled. */
private suspend fun pickSaveFile(suggestedName: String): File? {
    val title = getString(Res.string.playlist_menu_backup)
    return withContext(Dispatchers.Main) {
        val dialog = FileDialog(mainWindow, title, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    }
}

/** The open dialog for a playlist backup; null when canceled. */
private suspend fun pickOpenFile(): File? {
    val title = getString(Res.string.playlist_menu_restore)
    return withContext(Dispatchers.Main) {
        val dialog = FileDialog(mainWindow, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    }
}

// Windows gets the native Explorer folder picker. macOS's AWT FileDialog only picks directories
// while this property is set; on Linux the dialog picks a single file, which the scanner indexes on
// its own (a supported single-file scan).
private suspend fun pickFolder(): File? {
    val title = getString(Res.string.folder_picker_title)
    if (desktopOsKey() == "windows") {
        val owner = withContext(Dispatchers.Main) { mainWindow?.let { Native.getComponentPointer(it) } }
        try {
            return WindowsFolderPicker.pick(owner, title)
        } catch (e: Exception) {
            rootLogger.fork("folder-picker").log(Logger.Level.WARN, "native folder picker failed, using FileDialog", e)
        }
    }
    return withContext(Dispatchers.Main) {
        val prop = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(prop)
        System.setProperty(prop, "true")
        try {
            val dialog = FileDialog(mainWindow, title, FileDialog.LOAD)
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        } finally {
            if (previous == null) System.clearProperty(prop) else System.setProperty(prop, previous)
        }
    }
}

/** The coarse platform name the config service keys releases by. */
internal fun desktopOsKey(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("win") -> "windows"
        else -> "linux"
    }
}

/**
 * The ffmpeg this run should use, or null when the host has none.
 *
 * One ladder for every caller (tag reading, artwork, download muxing, local playback) so they can
 * never disagree about which binary is in play: an explicit override, then the copy packed into the
 * app, then the PATH.
 *
 * A dev run takes the first rung: `:desktopApp:run` and the tests are given -Dio.ymusic.ffmpeg
 * pointing at the staged host pack's CLI (libav sits next to it; rpath, not LD_LIBRARY_PATH).
 * A packaged build has no such property and uses the CLI next to the compose resources.
 */
internal fun resolveFfmpeg(): String? {
    System.getProperty("io.ymusic.ffmpeg")?.let { return File(it).takeIf { f -> f.canExecute() }?.absolutePath }
    System.getenv("YMUSIC_FFMPEG")?.let { if (File(it).canExecute()) return it }
    packedFfmpeg()?.let { return it.absolutePath }
    val os = System.getProperty("os.name").lowercase()
    val name = if (os.contains("win")) "ffmpeg.exe" else "ffmpeg"
    val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) +
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    return dirs.asSequence().map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath
}

/** A packaged build ships the MSE JNI beside the libav it links, in app resources, not in its jar. */
private fun useBundledMseNatives() {
    val dir = System.getProperty("compose.application.resources.dir") ?: return
    if (File(dir, System.mapLibraryName("mseffmpegjni")).isFile) System.setProperty("mse.native.dir", dir)
}

/** The ffmpeg shipped inside a packaged build (`compose.application.resources.dir`). */
private fun packedFfmpeg(): File? {
    val dir = System.getProperty("compose.application.resources.dir") ?: return null
    val name = if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
    return File(dir, name).takeIf { it.canExecute() }
}

/**
 * Match the OS window decorations — title bar, traffic lights — to the theme the app will render in.
 *
 * macOS reads this once when AWT starts its toolkit, so it must be set before any window exists, and
 * a theme change in Settings only reaches the title bar on the next launch. The value is an explicit
 * appearance rather than "system": the app's theme is its own setting, and a dark app under a light
 * OS should not get a white title bar.
 *
 * Windows would need DwmSetWindowAttribute per window and Linux is at the window manager's
 * discretion; neither is wired up, so both keep the system default for now.
 */
private fun applyWindowChrome(dark: Boolean) {
    // A JFrame takes its background from UIManager's "control" colour AT CONSTRUCTION — before any
    // composable, effect or window property of ours can run. That is the whole launch flash: the
    // frame is built white and Compose only covers it when it first draws. Setting the colour here,
    // before the toolkit builds anything, is the only place early enough.
    // https://github.com/JetBrains/compose-multiplatform/issues/1794
    val ground = java.awt.Color(groundColorFor(dark).toArgb(), true)
    // "control" alone is not enough: it colours the frame and its root pane, but the content pane
    // inside them is an opaque JPanel that takes Aqua's own system colour (a SystemColorProxy at
    // #EEEEEE) and stays white. That panel is what actually flashes, so every key the Swing window
    // is assembled from has to be set, not just the one the issue names.
    listOf("control", "Panel.background", "RootPane.background", "Viewport.background")
        .forEach { javax.swing.UIManager.put(it, ground) }

    if (!System.getProperty("os.name").lowercase().contains("mac")) return
    System.setProperty(
        "apple.awt.application.appearance",
        if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
    )
}

/**
 * GPU skiko is not trustworthy on Linux: NVIDIA's GL and Vulkan under Xwayland render frames that
 * never reach the screen, and nothing errors for skiko to fall back on. SKIKO_RENDER_API or
 * -Dskiko.renderApi opts back into the GPU.
 */
private fun defaultLinuxSoftwareRendering() {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return
    if (System.getenv("SKIKO_RENDER_API") != null || System.getProperty("skiko.renderApi") != null) return
    System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
}

private val LocalAppWindow = staticCompositionLocalOf<java.awt.Window?> { null }

private val videoUnderlay by lazy { VideoUnderlay() }

/**
 * The heavyweight Canvas-bearing panels are singletons so the OS HWND / NSView and its
 * attached D3D11 swapchain / AVSampleBufferDisplayLayer survive every Compose recomposition.
 * [graph.playerSurface] expands to [DesktopPlayerSurface] at multiple call sites
 * (mini-player, NowPlaying full view, PiP). Each call site is a separate composition slot,
 * so a per-call `remember { MseVideoPanel() }` would spin up a fresh Canvas on every switch,
 * and `videoUnderlay.attach(panel)` would then destroy the previous Canvas's HWND — triggering
 * a decoder reopen plus a visible black-screen while the new HWND warms up. Hoisting the
 * panels to module scope keeps a single HWND for the app's lifetime; call sites only update
 * the hole rect and blit size.
 */
private val sharedMsePanel by lazy { MseVideoPanel() }
private val sharedLocalPanel by lazy { MseVideoPanel() }
private val playerSurfaceWired = java.util.concurrent.atomic.AtomicBoolean(false)

/** Mounted [DesktopPlayerSurface] call sites; the hole closes only when none is left. EDT only. */
private var surfaceMountCount = 0

private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

/** A file packed into the app jar (the staged engine bundle + version — see stageEngineJs). */
private fun readAppResource(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/" + name)) { name + " missing from app resources" }
        .readBytes().decodeToString()

@Composable
private fun MacTrafficLight(color: Color, label: String, glyph: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
            .hoverable(hover)
            // Window-chrome buttons stay out of the Tab cycle (standard Windows app pattern —
            // Chrome / Firefox / VS Code do the same). Mouse + hover-read still work. Users close
            // / minimize / fullscreen via Alt+F4 / Win+↓ / F11 keyboard shortcuts.
            .focusProperties { canFocus = false }
            .clickable(indication = null, interactionSource = hover, role = Role.Button, onClick = onClick)
            // Hover-only glyph (✕ ─ ＋) is a Text node whose text would otherwise merge into the
            // button's semantics and NVDA would read "plus" instead of "Full screen". Strip
            // descendants and set the real label as `text`, not `contentDescription` — Compose
            // Desktop's a11y bridge maps contentDescription to BOTH JAB name AND description,
            // so NVDA reads it twice; using `text` fills name only.
            .clearAndSetSemantics {
                text = AnnotatedString(label)
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hovered) {
            Text(glyph, color = Color(0x99000000), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Booting(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
