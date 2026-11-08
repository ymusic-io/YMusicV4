package io.ymusic.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * YMusic Files (github.com/ymusic-io/android-saf): on Android 11+ without a system folder picker it holds all-files
 * access and hands YMusic the folders the user picks as writable SAF trees. The user installs it themselves; the
 * remote config names its package and download link.
 */
internal object StorageCompanion {
    private const val ACTION_PICK_FOLDER = "io.ymusic.android.saf.action.PICK_FOLDER"

    fun pickFolderIntent(packageName: String): Intent = Intent(ACTION_PICK_FOLDER).setPackage(packageName)

    /** The installed helper's versionCode; null when it isn't installed. */
    fun installedVersion(context: Context, packageName: String): Long? = try {
        context.packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Anything else under that package name is not ours, and ours refuses a caller signed differently. */
    fun isSignedLikeUs(context: Context, packageName: String): Boolean =
        context.packageManager.checkSignatures(context.packageName, packageName) == PackageManager.SIGNATURE_MATCH

    /** Whether the device has a screen to grant the helper All files access; without one it can't work at all. */
    fun canGrantAllFilesAccess(context: Context, packageName: String): Boolean = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ).any { it.resolveActivity(context.packageManager) != null }
}
