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
import android.speech.RecognizerIntent
import android.util.Rational
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.ymusic.player.PlayableItem
import io.ymusic.player.VideoSize
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.AndroidInputMode
import io.ymusic.ui.App
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppSection
import io.ymusic.ui.DesignColors
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.DialogSecondaryButton
import io.ymusic.ui.ElevatedBarDialog
import io.ymusic.ui.IncomingLinks
import io.ymusic.ui.LinkAction
import io.ymusic.ui.YMusicTheme
import io.ymusic.ui.designPaletteFor
import io.ymusic.ui.rememberStartSection
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.dialog_ok
import io.ymusic.ui.resources.error_generic
import io.ymusic.ui.resources.folder_picker_companion_message
import io.ymusic.ui.resources.folder_picker_companion_open
import io.ymusic.ui.resources.folder_picker_companion_update
import io.ymusic.ui.resources.folder_picker_disabled
import io.ymusic.ui.resources.folder_picker_missing
import io.ymusic.ui.resources.folder_picker_open_settings
import io.ymusic.ui.resources.folder_picker_storage_denied
import io.ymusic.ui.resources.folder_picker_title
import io.ymusic.ui.startupFailedMessage
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import projectcommon.error.TechnicalException
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

    /** The trusted picker app the next [openFolder] launch goes to, so no other handler of the intent can take it. */
    private var folderPickerPackage: String? = null
    private val openFolder =
        registerForActivityResult(
            object : ActivityResultContracts.OpenDocumentTree() {
                override fun createIntent(context: android.content.Context, input: android.net.Uri?): Intent =
                    super.createIntent(context, input).setPackage(folderPickerPackage)
            },
        ) { treeUri ->
            treeUri?.let(::persistTreeGrant)
            pendingPick?.complete(treeUri?.let { Uri.parse(it.toString()) })
            pendingPick = null
        }

    /** Any picked folder can become the download location, so write is persisted too: the picker's transient
     *  grant dies with this process. */
    private fun persistTreeGrant(treeUri: android.net.Uri) {
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

    /** Launches the SAF folder picker and suspends until the user picks a tree (or cancels → null). Without
     *  one: browsing storage paths up to Android 10 (a `file://` uri), the YMusic Files helper from 11. */
    private val pickFolder: suspend () -> Uri? = {
        pendingPick?.complete(null) // a prior pick still open → abandon it
        val deferred = CompletableDeferred<Uri?>()
        pendingPick = deferred
        val trusted = (application as YMusicApplication).services.await().remoteConfig.awaitLoaded().features.safPickerPackages
        withContext(Dispatchers.Main) {
            val log = (application as YMusicApplication).rootLogger.fork("folder-pick")
            val picker = trustedFolderPicker(trusted, log)
            if (picker != null) {
                try {
                    folderPickerPackage = picker
                    openFolder.launch(null)
                } catch (e: ActivityNotFoundException) {
                    log.log(kmpcommon.log.Logger.Level.WARN, "$picker handles OPEN_DOCUMENT_TREE but failed to launch", e)
                    deferred.complete(pickFolderWithoutSystemPicker())
                }
            } else {
                when (val problem = folderPickerProblem(trusted)) {
                    FolderPickerIssue.Missing -> {
                        log.log(kmpcommon.log.Logger.Level.INFO, "no trusted system folder picker")
                        deferred.complete(pickFolderWithoutSystemPicker())
                    }
                    else -> {
                        log.log(kmpcommon.log.Logger.Level.WARN, "no folder picker: $problem")
                        folderPickerIssue.value = problem
                        deferred.complete(null)
                    }
                }
            }
        }
        deferred.await()
    }

    private val folderPickerIssue = mutableStateOf<FolderPickerIssue?>(null)

    private var pendingStorageAccess: CompletableDeferred<Boolean>? = null
    private val storageAccessLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            pendingStorageAccess?.complete(results.isNotEmpty() && results.values.all { it })
            pendingStorageAccess = null
        }

    // From Android 11 storage permissions no longer reach other apps' files: only all-files access does,
    // and that lives in the helper app rather than in YMusic.
    private suspend fun pickFolderWithoutSystemPicker(): Uri? =
        if (Build.VERSION.SDK_INT >= 30) pickFolderWithCompanion() else pickFolderByPath()

    /** While set, [CompanionInstallDialog] asks the user to install (or update) the helper from its link. */
    private val companionDownload = mutableStateOf<CompanionDownload?>(null)

    private var pendingCompanionPick: CompletableDeferred<android.net.Uri?>? = null
    private val companionPickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingCompanionPick?.complete(result.data?.data?.takeIf { result.resultCode == RESULT_OK })
            pendingCompanionPick = null
        }

    /** The YMusic Files folder pick: a SAF tree, or null when canceled or the user has yet to install the helper. */
    private suspend fun pickFolderWithCompanion(): Uri? {
        val log = (application as YMusicApplication).rootLogger.fork("storage-companion")
        val features = (application as YMusicApplication).services.await().remoteConfig.awaitLoaded().features
        val helper = features.safHelperPackage
        val downloadUrl = features.safHelperUrl
        if (helper.isBlank() || downloadUrl.isBlank()) {
            log.log(Logger.Level.WARN, "the remote config switched the folder picker helper off")
            folderPickerIssue.value = FolderPickerIssue.Unsupported
            return null
        }
        val version = StorageCompanion.installedVersion(this, helper)
        when {
            version == null -> {
                if (!StorageCompanion.canGrantAllFilesAccess(this, helper)) {
                    log.log(Logger.Level.WARN, "no All files access screen either: no way to pick a folder on this device")
                    folderPickerIssue.value = FolderPickerIssue.Unsupported
                    return null
                }
                companionDownload.value = CompanionDownload(downloadUrl, update = false)
                return null
            }
            !StorageCompanion.isSignedLikeUs(this, helper) -> {
                log.log(Logger.Level.WARN, "$helper is installed but signed with another key")
                folderPickerIssue.value = FolderPickerIssue.ForeignSignature(helper)
                return null
            }
            version < features.safHelperMinVersion -> {
                log.log(Logger.Level.INFO, "$helper $version is older than ${features.safHelperMinVersion}")
                companionDownload.value = CompanionDownload(downloadUrl, update = true)
                return null
            }
        }
        val picked = CompletableDeferred<android.net.Uri?>()
        pendingCompanionPick?.complete(null)
        pendingCompanionPick = picked
        try {
            companionPickLauncher.launch(StorageCompanion.pickFolderIntent(helper))
        } catch (e: ActivityNotFoundException) {
            pendingCompanionPick = null
            val error = TechnicalException("E50011", "the installed YMusic Files has no folder picker", cause = e)
            log.captureError(error)
            folderPickerIssue.value = FolderPickerIssue.Failed(error.code)
            return null
        }
        val treeUri = picked.await() ?: return null
        persistTreeGrant(treeUri)
        return Uri.parse(treeUri.toString())
    }

    /** The open path pick: [PathFolderPickerDialog] shows while this is set, and completes it. */
    private val pathPick = mutableStateOf<PathPick?>(null)

    /** The storage-permission + path-browsing folder pick: a `file://` folder uri, or null when canceled. */
    private suspend fun pickFolderByPath(): Uri? {
        if (!hasStorageAccess()) {
            pendingStorageAccess?.complete(false)
            val access = CompletableDeferred<Boolean>()
            pendingStorageAccess = access
            storageAccessLauncher.launch(STORAGE_PERMISSIONS)
            if (!access.await()) {
                // Denied for good: the system dialog won't show again, only the app's settings screen can grant it.
                if (STORAGE_PERMISSIONS.none { shouldShowRequestPermissionRationale(it) }) {
                    folderPickerIssue.value = FolderPickerIssue.StorageAccessDenied
                }
                return null
            }
        }
        val roots = withContext(Dispatchers.IO) { io.ymusic.mediascanner.storageRoots(this@MainActivity) }
        val result = CompletableDeferred<java.io.File?>()
        pathPick.value?.result?.complete(null)
        pathPick.value = PathPick(roots, result)
        val dir = result.await() ?: return null
        return Uri.parse(android.net.Uri.fromFile(dir).toString())
    }

    private fun hasStorageAccess(): Boolean =
        STORAGE_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    /** The first of [trusted] that handles the folder-pick intent. Other handlers (a dummy that shows nothing on
     *  some builds, or a real picker we don't know yet) are never used, but reported, so the list can grow without
     *  waiting for user feedback. */
    private fun trustedFolderPicker(trusted: List<String>, log: Logger): String? {
        val handlers = packageManager.queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0)
            .map { it.activityInfo.packageName }
            .distinct()
        val picker = trusted.firstOrNull { it in handlers }
        val untrusted = handlers - trusted.toSet()
        if (picker == null && untrusted.isNotEmpty()) {
            log.captureError(
                IllegalStateException("untrusted OPEN_DOCUMENT_TREE handlers: $untrusted (trusted: $trusted)"),
                prop = mapOf("handlers" to untrusted.joinToString(",")),
            )
        }
        return picker
    }

    /** Why no trusted picker can launch: one of [trusted] is installed but disabled, or none is there. */
    private fun folderPickerProblem(trusted: List<String>): FolderPickerIssue {
        val disabled = trusted
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

    private var pendingSpeech: CompletableDeferred<String?>? = null
    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            pendingSpeech?.complete(spoken.takeIf { result.resultCode == RESULT_OK })
            pendingSpeech = null
        }

    private val speechDialog: suspend (Intent) -> String? = { intent ->
        pendingSpeech?.complete(null)
        val deferred = CompletableDeferred<String?>()
        pendingSpeech = deferred
        withContext(Dispatchers.Main) {
            try {
                speechLauncher.launch(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                pendingSpeech = null
                throw e
            }
        }
        deferred.await()
    }

    private val speechInput by lazy {
        AndroidSpeechInput(this, AndroidInputMode.isTelevision, requestPermission, speechDialog)
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
        pathPick.value?.result?.complete(null)
        pathPick.value = null
        pendingStorageAccess?.complete(false)
        pendingStorageAccess = null
        pendingCompanionPick?.complete(null)
        pendingCompanionPick = null
        if (app.qrScanner === scanQr) app.qrScanner = null
        if (app.speechInput === speechInput) app.speechInput = null
        pendingSpeech?.complete(null)
        pendingSpeech = null
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
        AndroidInputMode.init(this)
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
        (application as YMusicApplication).speechInput = speechInput
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
                AppThemed {
                    FolderPickerIssueDialog(
                        issue = issue,
                        onOpenSettings = { pkg ->
                            folderPickerIssue.value = null
                            openAppDetails(pkg ?: packageName)
                        },
                        onDismiss = { folderPickerIssue.value = null },
                    )
                }
            }
            companionDownload.value?.let { download ->
                val url = download.url
                AppThemed {
                    CompanionInstallDialog(
                        url = url,
                        update = download.update,
                        canOpen = remember(url) { Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).resolveActivity(packageManager) != null },
                        onOpen = {
                            companionDownload.value = null
                            if (!startFirstActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))) {
                                (application as YMusicApplication).rootLogger.fork("storage-companion")
                                    .log(Logger.Level.WARN, "no app opens the helper download link")
                            }
                        },
                        onDismiss = { companionDownload.value = null },
                    )
                }
            }
            pathPick.value?.let { pick ->
                AppThemed {
                    PathFolderPickerDialog(
                        roots = pick.roots,
                        onPick = { dir ->
                            pathPick.value = null
                            pick.result.complete(dir)
                        },
                        onDismiss = {
                            pathPick.value = null
                            pick.result.complete(null)
                        },
                    )
                }
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

/** What browsing storage by path needs; only used up to Android 10, where these still cover all shared storage. */
private val STORAGE_PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

private class PathPick(val roots: List<io.ymusic.mediascanner.StorageRoot>, val result: CompletableDeferred<java.io.File?>)

private sealed interface FolderPickerIssue {
    data class Disabled(val packageName: String) : FolderPickerIssue

    /** No system tree picker: the folder is picked by path instead. */
    data object Missing : FolderPickerIssue

    data object StorageAccessDenied : FolderPickerIssue

    data class Failed(val code: String) : FolderPickerIssue

    /** Neither a system folder picker nor a way to grant the helper All files access. */
    data object Unsupported : FolderPickerIssue

    /** The installed helper and this build are signed with different keys (a self-built or re-signed copy). */
    data class ForeignSignature(val packageName: String) : FolderPickerIssue
}

/** [onOpenSettings] gets the app whose details screen fixes the issue, null for this app. */
@androidx.compose.runtime.Composable
private fun FolderPickerIssueDialog(issue: FolderPickerIssue, onOpenSettings: (String?) -> Unit, onDismiss: () -> Unit) {
    when (issue) {
        is FolderPickerIssue.Disabled -> FolderPickDialog(
            message = resString(Res.string.folder_picker_disabled),
            action = resString(Res.string.folder_picker_open_settings),
            onAction = { onOpenSettings(issue.packageName) },
            onDismiss = onDismiss,
        )
        FolderPickerIssue.StorageAccessDenied -> FolderPickDialog(
            message = resString(Res.string.folder_picker_storage_denied),
            action = resString(Res.string.folder_picker_open_settings),
            onAction = { onOpenSettings(null) },
            onDismiss = onDismiss,
        )
        is FolderPickerIssue.Failed -> FolderPickDialog(resString(Res.string.error_generic, issue.code), onDismiss = onDismiss)
        FolderPickerIssue.Unsupported -> FolderPickDialog(resString(Res.string.folder_picker_missing), onDismiss = onDismiss)
        // English only by choice: only self-built or re-signed copies get here.
        is FolderPickerIssue.ForeignSignature -> FolderPickDialog(
            message = "YMusic Files is installed, but it isn't signed with the same key as this copy of YMusic, so " +
                "YMusic can't use it. Install both apps from their official releases, or uninstall YMusic Files.",
            action = resString(Res.string.folder_picker_open_settings),
            onAction = { onOpenSettings(issue.packageName) },
            onDismiss = onDismiss,
        )
        FolderPickerIssue.Missing -> Unit
    }
}

private class CompanionDownload(val url: String, val update: Boolean)

/** Asks the user to install the helper from [url] themselves; the link is spelled out for devices with no browser. */
@androidx.compose.runtime.Composable
private fun CompanionInstallDialog(url: String, update: Boolean, canOpen: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    FolderPickDialog(
        message = resString(if (update) Res.string.folder_picker_companion_update else Res.string.folder_picker_companion_message),
        action = if (canOpen) resString(Res.string.folder_picker_companion_open) else null,
        onAction = onOpen,
        onDismiss = onDismiss,
    ) {
        SelectionContainer {
            Text(
                url,
                Modifier.fillMaxWidth().padding(start = DesignSpacing.Space24, end = DesignSpacing.Space24, bottom = DesignSpacing.Space16),
                style = TextStyle(fontSize = 14.sp, color = DesignColors.Accent, lineHeight = 20.sp),
            )
        }
    }
}

/** The app's message dialog for the folder pick: [action] beside Cancel, or a lone OK without one. */
@androidx.compose.runtime.Composable
private fun FolderPickDialog(
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
    onDismiss: () -> Unit,
    extra: @androidx.compose.runtime.Composable () -> Unit = {},
) {
    ElevatedBarDialog(
        title = resString(Res.string.folder_picker_title),
        onDismiss = onDismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            if (action != null) {
                DialogSecondaryButton(resString(Res.string.dialog_cancel), onClick = onDismiss)
                TextButton(onClick = onAction) { Text(action, color = DesignColors.Accent) }
            } else {
                TextButton(onClick = onDismiss) { Text(resString(Res.string.dialog_ok), color = DesignColors.Accent) }
            }
        },
    ) {
        Column {
            Text(
                message,
                Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
                style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink, lineHeight = 20.sp),
            )
            extra()
        }
    }
}

/** These dialogs show outside [App], so they take the app's theme here. */
@androidx.compose.runtime.Composable
private fun AppThemed(content: @androidx.compose.runtime.Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { PrefsSettingsStore.open(context) }
    val theme by settings.flow(AppSettings.theme).collectAsState()
    val accent by settings.flow(AppSettings.themeAccent).collectAsState()
    YMusicTheme(designPaletteFor(theme, androidx.compose.foundation.isSystemInDarkTheme(), accent), content)
}

@androidx.compose.runtime.Composable
private fun Booting(content: @androidx.compose.runtime.Composable () -> Unit) {
    // The first frame App will paint, so the launch window → this → App never changes colour.
    val context = androidx.compose.ui.platform.LocalContext.current
    val theme = remember { PrefsSettingsStore.open(context).flow(AppSettings.theme).value }
    val ground = designPaletteFor(theme, androidx.compose.foundation.isSystemInDarkTheme()).Surface
    Box(Modifier.fillMaxSize().background(ground), contentAlignment = Alignment.Center) { content() }
}
