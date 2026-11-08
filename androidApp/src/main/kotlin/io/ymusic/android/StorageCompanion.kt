package io.ymusic.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kmpcommon.WorkerThread
import kmpcommon.log.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * YMusic Files (the `android-saf/` submodule): on Android 11+ without a system folder picker it holds all-files access
 * and hands YMusic the folders the user picks as writable SAF trees. Ships inside this APK as an asset.
 */
internal object StorageCompanion {
    const val PACKAGE = "io.ymusic.android.saf"
    private const val ACTION_PICK_FOLDER = "io.ymusic.android.saf.action.PICK_FOLDER"
    private const val ASSET = "android-saf.apk"

    class InstallResult(val status: Int, val message: String?)

    @Volatile private var pendingInstall: CompletableDeferred<InstallResult>? = null

    fun pickFolderIntent(): Intent = Intent(ACTION_PICK_FOLDER).setPackage(PACKAGE)

    /** Whether the device has a screen to grant the helper All files access; without one it can't work at all. */
    fun canGrantAllFilesAccess(context: Context): Boolean = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$PACKAGE")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ).any { it.resolveActivity(context.packageManager) != null }

    /** Whether the installed helper is at least as new as the bundled one. */
    @WorkerThread
    fun isUpToDate(context: Context): Boolean {
        val installed = try {
            context.packageManager.getPackageInfo(PACKAGE, 0).longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val bundled = context.packageManager.getPackageArchiveInfo(bundledApk(context).path, 0)?.longVersionCode
            ?: error("the bundled $ASSET does not parse")
        return installed >= bundled
    }

    @WorkerThread
    private fun bundledApk(context: Context): File {
        val file = File(context.cacheDir, ASSET)
        context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** Installs the bundled helper; suspends through the system's confirmation until the session ends. */
    suspend fun install(context: Context): InstallResult {
        val result = CompletableDeferred<InstallResult>()
        pendingInstall?.complete(InstallResult(PackageInstaller.STATUS_FAILURE_ABORTED, "superseded"))
        pendingInstall = result
        withContext(Dispatchers.IO) { commit(context, bundledApk(context)) }
        return result.await()
    }

    @WorkerThread
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(PACKAGE)
        params.setSize(apk.length())
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, CompanionInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    fun onInstallEnded(status: Int, message: String?) {
        pendingInstall?.complete(InstallResult(status, message))
        pendingInstall = null
    }
}

/** [PackageInstaller]'s answer for the [StorageCompanion] session: shows the confirmation it waits on. */
class CompanionInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            StorageCompanion.onInstallEnded(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            return
        }
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        try {
            context.startActivity(checkNotNull(confirm) { "no confirmation intent" }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: RuntimeException) {
            (context.applicationContext as? YMusicApplication)?.rootLogger?.fork("storage-companion")
                ?.log(Logger.Level.WARN, "no screen confirms the companion install", error)
            StorageCompanion.onInstallEnded(PackageInstaller.STATUS_FAILURE, "no confirmation screen: $error")
        }
    }
}
