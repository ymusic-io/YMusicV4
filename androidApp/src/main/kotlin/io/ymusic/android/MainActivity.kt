package io.ymusic.android

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.ymusic.player.PlayableItem
import io.ymusic.player.VideoSize
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.App
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppSection
import io.ymusic.ui.IncomingLinks
import io.ymusic.ui.LinkAction
import io.ymusic.ui.designPaletteFor
import io.ymusic.ui.rememberStartSection
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.dialog_ok
import io.ymusic.ui.resources.folder_picker_disabled
import io.ymusic.ui.resources.folder_picker_missing
import io.ymusic.ui.resources.folder_picker_open_settings
import io.ymusic.ui.startupFailedMessage
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import org.jetbrains.compose.resources.stringResource as resString

/**
 * The Android UI shell: boots the shared :commonUi [App] over the application-scoped [AppGraph]
 * (see [YMusicApplication] — playback outlives this Activity in the [PlaybackService]). The
 * Activity contributes only what needs one: the SAF folder pick and the notification permission.
 */
class MainActivity : FragmentActivity() {
    /** A section requested by a platform surface (download notification tap), consumed by the
     *  composition once the graph is up — survives arriving before the app finishes booting. */
    private val pendingSection = mutableStateOf<AppSection?>(null)
    private var appGraph: AppGraph? = null
    /** PiP just ended; [onResume] means expand-to-full, [onStop] means the user closed PiP. */
    private var pipExitPending = false

    // Some ROMs declare FEATURE_PICTURE_IN_PICTURE yet the system rejects every PiP call; flipped off on the first rejection.
    private var pipSupported = false

    // Notifications are MANDATORY: playback lives in a foreground service whose notification is
    // its lifeline. While disabled, a non-dismissible dialog sits over the app with granting as
    // the only way out — [notificationsEnabled] is re-checked on every resume, so returning from
    // the settings screen unblocks without a restart.
    private val notificationsEnabled = mutableStateOf(true)
    private var askedForNotifications = false
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            askedForNotifications = true
            notificationsEnabled.value = granted
            // Second "Don't allow" is permanent on 13+ — the settings screen is the only path left.
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openNotificationSettings()
            }
        }

    // Bridges the callback-based SAF folder pick to the shell's suspend ScanAction: launch() fires the
    // system ACTION_OPEN_DOCUMENT_TREE, the result callback completes the pending deferred.
    private var pendingPick: CompletableDeferred<Uri?>? = null
    private val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                // Any picked folder can become the download location, so persist write too: the picker's
                // transient write grant dies with this process.
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    // A provider that grants read-only: the folder still scans; the download-location pick refuses it.
                    (application as YMusicApplication).rootLogger.fork("folder-pick")
                        .log(kmpcommon.log.Logger.Level.WARN, "folder $treeUri has no write grant; persisting read-only", e)
                    contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            pendingPick?.complete(treeUri?.let { Uri.parse(it.toString()) })
            pendingPick = null
        }

    /** Launches the SAF folder picker and suspends until the user picks a tree (or cancels → null). */
    private val pickFolder: suspend () -> Uri? = {
        pendingPick?.complete(null) // a prior pick still open → abandon it
        val deferred = CompletableDeferred<Uri?>()
        pendingPick = deferred
        withContext(Dispatchers.Main) {
            val problem = folderPickerProblem()
            if (problem == null) {
                try {
                    openFolder.launch(null)
                } catch (e: ActivityNotFoundException) {
                    (application as YMusicApplication).rootLogger.fork("folder-pick")
                        .log(kmpcommon.log.Logger.Level.WARN, "OPEN_DOCUMENT_TREE resolved but failed to launch", e)
                    folderPickerIssue.value = FolderPickerIssue.Missing
                    deferred.complete(null)
                }
            } else {
                (application as YMusicApplication).rootLogger.fork("folder-pick")
                    .log(kmpcommon.log.Logger.Level.WARN, "no folder picker: $problem")
                folderPickerIssue.value = problem
                deferred.complete(null)
            }
        }
        deferred.await()
    }

    private val folderPickerIssue = mutableStateOf<FolderPickerIssue?>(null)

    /** Null when the system tree picker can launch; else whether its app is disabled or absent. */
    private fun folderPickerProblem(): FolderPickerIssue? {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (intent.resolveActivity(packageManager) != null) return null
        val disabled = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.activityInfo.packageName }
            .plus(DOCUMENTS_UI_PACKAGES)
            .firstOrNull { pkg ->
                try {
                    !packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }
        return if (disabled != null) FolderPickerIssue.Disabled(disabled) else FolderPickerIssue.Missing
    }

    private fun openAppDetails(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null)))
        } catch (e: ActivityNotFoundException) {
            (application as YMusicApplication).rootLogger.fork("folder-pick")
                .log(kmpcommon.log.Logger.Level.WARN, "no app details screen for $pkg", e)
            if (!startFirstActivity(Intent(Settings.ACTION_SETTINGS))) {
                (application as YMusicApplication).rootLogger.fork("folder-pick")
                    .log(kmpcommon.log.Logger.Level.WARN, "no settings screen on this device")
            }
        }
    }

    private var pendingCreate: CompletableDeferred<android.net.Uri?>? = null
    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingCreate?.complete(result.data?.data?.takeIf { result.resultCode == RESULT_OK })
            pendingCreate = null
        }

    private val createDocument: suspend (String, String) -> android.net.Uri? = { name, mimeType ->
        pendingCreate?.complete(null)
        val deferred = CompletableDeferred<android.net.Uri?>()
        pendingCreate = deferred
        withContext(Dispatchers.Main) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, name)
            createDocumentLauncher.launch(intent)
        }
        deferred.await()
    }

    private var pendingOpen: CompletableDeferred<android.net.Uri?>? = null
    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingOpen?.complete(uri)
            pendingOpen = null
        }

    private val openDocument: suspend (List<String>) -> android.net.Uri? = { mimeTypes ->
        pendingOpen?.complete(null)
        val deferred = CompletableDeferred<android.net.Uri?>()
        pendingOpen = deferred
        withContext(Dispatchers.Main) { openDocumentLauncher.launch(mimeTypes.toTypedArray()) }
        deferred.await()
    }

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
            pendingPermission = null
        }

    private val requestPermission: suspend (String) -> Boolean = { permission ->
        pendingPermission?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        withContext(Dispatchers.Main) { permissionLauncher.launch(permission) }
        deferred.await()
    }

    private var pendingScan: CompletableDeferred<String?>? = null
    private val scanQr: suspend () -> String? = {
        pendingScan?.complete(null)
        val deferred = CompletableDeferred<String?>()
        pendingScan = deferred
        withContext(Dispatchers.Main) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this@MainActivity, QrScanActivity::class.java), REQ_QR_SCAN)
        }
        deferred.await()
    }

    private var pendingOwner: CompletableDeferred<Boolean>? = null
    private val confirmOwner: suspend (String, String) -> Boolean = { title, subtitle ->
        pendingOwner?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingOwner = deferred
        withContext(Dispatchers.Main) {
            val km = getSystemService(KeyguardManager::class.java)
            if (km == null || !km.isDeviceSecure) {
                pendingOwner = null
                error("no-lock")
            }
            val prompt = BiometricPrompt(
                this@MainActivity,
                ContextCompat.getMainExecutor(this@MainActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        pendingOwner?.complete(true)
                        pendingOwner = null
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val d = pendingOwner
                        pendingOwner = null
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            -> d?.complete(false)
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                            -> d?.completeExceptionally(IllegalStateException("no-lock"))
                            else -> d?.completeExceptionally(IllegalStateException("auth"))
                        }
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setDeviceCredentialAllowed(true)
                    }
                }
                .build()
            prompt.authenticate(info)
        }
        deferred.await()
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as YMusicApplication
        if (app.folderPicker === pickFolder) app.folderPicker = null
        pendingPick?.complete(null)
        pendingPick = null
        if (app.qrScanner === scanQr) app.qrScanner = null
        pendingScan?.complete(null)
        pendingScan = null
        if (app.deviceOwnerAuth === confirmOwner) app.deviceOwnerAuth = null
        pendingOwner?.complete(false)
        pendingOwner = null
        if (app.documentCreator === createDocument) app.documentCreator = null
        pendingCreate?.complete(null)
        pendingCreate = null
        if (app.documentOpener === openDocument) app.documentOpener = null
        pendingOpen?.complete(null)
        pendingOpen = null
        if (app.permissionRequester === requestPermission) app.permissionRequester = null
        pendingPermission?.complete(false)
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is light-only (commonUi LightScheme paints white behind the bars), so the bar
        // icon color must not follow the SYSTEM dark-mode setting — that yields white-on-white.
        val light = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = light, navigationBarStyle = light)
        // Compose paints its own surface; a window background under it is 1× overdraw on every pixel.
        window.setBackgroundDrawable(null)
        // Diagnosis-only instrumentation: the watchdog probes the main looper/choreographer/frame
        // clock every 3s and the composition probe forces a frame request every 500ms — never in a
        // release build. BuildConfig.DEBUG is a compile-time constant, so R8 strips these branches
        // (and the watchdog classes with them) from the release dex entirely.
        if (BuildConfig.DEBUG) {
            FreezeWatchdog.start()
            lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    android.util.Log.i("FreezeWatchdog", "lifecycle $event")
                },
            )
        }
        (application as YMusicApplication).folderPicker = pickFolder
        (application as YMusicApplication).qrScanner = scanQr
        (application as YMusicApplication).deviceOwnerAuth = confirmOwner
        (application as YMusicApplication).documentCreator = createDocument
        (application as YMusicApplication).documentOpener = openDocument
        (application as YMusicApplication).permissionRequester = requestPermission
        sectionFor(intent)?.let { pendingSection.value = it }
        // A recreation re-delivers the launching intent; its link was already opened.
        if (savedInstanceState == null) openLink(intent)
        setContent {
            if (BuildConfig.DEBUG) CompositionClockProbe()
            var graph by remember { mutableStateOf<AppGraph?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    graph = (application as YMusicApplication).graphAsync().await()
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c // the effect was disposed — not a boot failure
                } catch (t: Throwable) {
                    (application as YMusicApplication).rootLogger.fork("boot").captureError(t)
                    error = t.message ?: t.toString()
                }
            }
            when {
                error != null -> Booting { Text(startupFailedMessage("$error"), color = MaterialTheme.colorScheme.error) }
                graph == null -> Booting { CircularProgressIndicator() }
                else -> {
                    LaunchedEffect(graph) { attachPip(graph!!) }
                    val section = rememberStartSection(graph!!)
                    LaunchedEffect(pendingSection.value) {
                        pendingSection.value?.let {
                            section.value = it
                            pendingSection.value = null
                        }
                    }
                    App(graph!!, section, catalogueScreen)
                }
            }
            val notificationsOk = notificationsEnabled.value
            LaunchedEffect(notificationsOk) {
                // First launch on 13+: fire the system dialog straight away; the dialog's button
                // covers retries and the pre-13 app-setting case (which has no dialog to show).
                if (!notificationsOk && Build.VERSION.SDK_INT >= 33 && !askedForNotifications) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (!notificationsOk) {
                NotificationRequiredDialog(onAllow = ::requestNotificationAccess)
            }
            folderPickerIssue.value?.let { issue ->
                FolderPickerIssueDialog(
                    issue = issue,
                    onOpenSettings = { pkg ->
                        folderPickerIssue.value = null
                        openAppDetails(pkg)
                    },
                    onDismiss = { folderPickerIssue.value = null },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val app = application as YMusicApplication
        if (app.resumedActivity?.get() === this) app.resumedActivity = null
    }

    override fun onResume() {
        super.onResume()
        // Covers 13+ permission AND the pre-13 app-level notification toggle.
        notificationsEnabled.value = NotificationManagerCompat.from(this).areNotificationsEnabled()
        // The Support Development app may have been installed or removed while we were away.
        (application as YMusicApplication).refreshPremium()
        (application as YMusicApplication).resumedActivity = WeakReference(this)
        val g = appGraph
        (g?.updates as? AndroidAppUpdates)?.onResume()
        if (pipExitPending) {
            pipExitPending = false
            g?.pip?.onPipStopped(appInForeground = true)
        } else {
            g?.pip?.onEnterForeground()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val g = appGraph ?: return
        if (Build.VERSION.SDK_INT in 26..30 && pipSupported && g.pip.autoEnterEligible()) {
            enterPip()
        }
    }

    override fun onStop() {
        super.onStop()
        val g = appGraph ?: return
        val inPip = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
        if (inPip) return
        if (pipExitPending) {
            pipExitPending = false
            g.pip.onPipStopped(appInForeground = false)
        } else {
            g.pip.onLeaveWithoutPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val g = appGraph ?: return
        if (isInPictureInPictureMode) {
            pipExitPending = false
            g.pip.onPipStarted()
        } else {
            // Runs before onResume on expand. onResume vs onStop decides restore vs pause+AudioOnly.
            pipExitPending = true
        }
    }

    private fun attachPip(graph: AppGraph) {
        appGraph = graph
        if (Build.VERSION.SDK_INT < 26 ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            return
        }
        pipSupported = true
        lifecycleScope.launch {
            combine(
                graph.settings.flow(AppSettings.enablePip),
                graph.pip.nowPlayingOpen,
                graph.pip.videoFullscreen,
                graph.player.status,
                graph.player.videoQuality,
            ) { _, _, _, _, _ -> }
                .combine(graph.player.config) { _, _ -> graph.pip.autoEnterEligible() }
                .collect { eligible ->
                    if (!pipSupported) return@collect
                    val b = PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio(graph.player.status.value.videoSize))
                    if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(eligible)
                    guardPip { setPictureInPictureParams(b.build()) }
                }
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || !pipSupported) return
        guardPip {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio(appGraph?.player?.status?.value?.videoSize)).build(),
            )
        }
    }

    private inline fun guardPip(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            pipSupported = false
            (application as YMusicApplication).rootLogger.fork("pip")
                .log(kmpcommon.log.Logger.Level.WARN, "system rejected PiP; disabled for this activity", e)
        }
    }

    // Android rejects ratios outside [1/2.39, 2.39] (config_pictureInPictureMin/MaxAspectRatio); 2.35 keeps float rounding inside.
    private fun pipAspectRatio(size: VideoSize?): Rational {
        if (size == null || size.width <= 0 || size.height <= 0) return Rational(16, 9)
        val ratio = size.aspectRatio
        return when {
            ratio > 2.35f -> Rational(235, 100)
            ratio < 1 / 2.35f -> Rational(100, 235)
            else -> Rational(size.width, size.height)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sectionFor(intent)?.let { pendingSection.value = it }
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW && intent?.action != ACTION_OPEN_LINK) return
        // Reopened from Recents: the task's original link intent comes back, already handled.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == ContentResolver.SCHEME_CONTENT) {
            playFile(data)
            return
        }
        val url = intent.dataString ?: return
        val action = intent.getStringExtra(EXTRA_LINK_ACTION)?.let { name -> LinkAction.entries.firstOrNull { it.name == name } }
        IncomingLinks.open(url, action ?: LinkAction.OPEN)
    }

    /** An audio or video file opened from another app ("Open with"): played as a queue of one. */
    private fun playFile(uri: android.net.Uri) {
        lifecycleScope.launch {
            // A failed startup is already reported by the boot screen; there is no player to hand the file to.
            val graph = runCatching { (application as YMusicApplication).graphAsync().await() }.getOrNull() ?: return@launch
            val title = try {
                withContext(Dispatchers.IO) { displayName(uri) }?.substringBeforeLast('.')
            } catch (e: Exception) {
                graph.logger?.log(Logger.Level.WARN, "open-with display name failed", e)
                null
            }
            graph.temporaryUris.update { it + uri.toString() }
            graph.player.setQueue(
                listOf(PlayableItem.LocalMedia(uri = uri.toString(), title = title)),
                origin = io.ymusic.player.QueueOrigin.File,
            )
            graph.pip.nowPlayingOpen.value = true
        }
    }

    private fun displayName(uri: android.net.Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_QR_SCAN) {
            val value = data?.getStringExtra(QrScanActivity.EXTRA_VALUE)
            pendingScan?.complete(if (resultCode == RESULT_OK) value else null)
            pendingScan = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun sectionFor(intent: Intent?): AppSection? = when (intent?.action) {
        ACTION_OPEN_DOWNLOADS -> AppSection.Downloads
        else -> null
    }

    companion object {
        /** Launch intent action landing on the Download manager (the download notifications). */
        const val ACTION_OPEN_DOWNLOADS = "io.ymusic.android.action.OPEN_DOWNLOADS"

        /** A shared link ([ShareActivity]): the url as data, the [LinkAction] name in [EXTRA_LINK_ACTION]. */
        const val ACTION_OPEN_LINK = "io.ymusic.android.action.OPEN_LINK"
        const val EXTRA_LINK_ACTION = "io.ymusic.android.extra.LINK_ACTION"

        /** FragmentActivity rejects Activity Result request codes above 16 bits. */
        private const val REQ_QR_SCAN = 0x51
    }

    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 33 &&
            (!askedForNotifications || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
        }
        val opened = startFirstActivity(
            intent,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)),
            Intent(Settings.ACTION_SETTINGS),
        )
        if (!opened) {
            (application as YMusicApplication).rootLogger.fork("notification-settings")
                .log(kmpcommon.log.Logger.Level.WARN, "no settings screen on this device")
        }
    }
}

/** Notifications are mandatory (the playback service's notification is its lifeline): this sits
 *  over whatever is showing and offers exactly one way out — granting. Not dismissible. */
@androidx.compose.runtime.Composable
private fun NotificationRequiredDialog(onAllow: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = { Text(stringResource(R.string.notifications_required)) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.notifications_allow)) }
        },
    )
}

private val DOCUMENTS_UI_PACKAGES = listOf("com.google.android.documentsui", "com.android.documentsui")

private sealed interface FolderPickerIssue {
    data class Disabled(val packageName: String) : FolderPickerIssue

    data object Missing : FolderPickerIssue
}

@androidx.compose.runtime.Composable
private fun FolderPickerIssueDialog(issue: FolderPickerIssue, onOpenSettings: (String) -> Unit, onDismiss: () -> Unit) {
    when (issue) {
        is FolderPickerIssue.Disabled -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(resString(Res.string.folder_picker_disabled)) },
            confirmButton = {
                TextButton(onClick = { onOpenSettings(issue.packageName) }) {
                    Text(resString(Res.string.folder_picker_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(resString(Res.string.dialog_cancel)) }
            },
        )
        FolderPickerIssue.Missing -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(resString(Res.string.folder_picker_missing)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(resString(Res.string.dialog_ok)) }
            },
        )
    }
}

@androidx.compose.runtime.Composable
private fun Booting(content: @androidx.compose.runtime.Composable () -> Unit) {
    // The first frame App will paint, so the launch window → this → App never changes colour.
    val context = androidx.compose.ui.platform.LocalContext.current
    val theme = remember { PrefsSettingsStore.open(context).flow(AppSettings.theme).value }
    val ground = designPaletteFor(theme, androidx.compose.foundation.isSystemInDarkTheme()).Surface
    Box(Modifier.fillMaxSize().background(ground), contentAlignment = Alignment.Center) { content() }
}
