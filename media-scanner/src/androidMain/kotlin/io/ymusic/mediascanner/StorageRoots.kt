package io.ymusic.mediascanner

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import kmpcommon.WorkerThread
import java.io.File

/** A mounted shared-storage volume reachable by path: the primary storage, an SD card or a USB drive. */
data class StorageRoot(val directory: File, val label: String)

/**
 * Every mounted shared-storage volume, primary first. Found through the app's own files dir on each volume
 * (`<root>/Android/data/<pkg>/files`): the only path-based volume listing below API 30.
 */
@WorkerThread
fun storageRoots(context: Context): List<StorageRoot> {
    val suffix = "/Android/data/${context.packageName}/files"
    val storageManager = context.getSystemService(StorageManager::class.java)
    return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
        val state = Environment.getExternalStorageState(appDir)
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
        val path = appDir.absolutePath
        if (!path.endsWith(suffix)) return@mapNotNull null
        val root = File(path.removeSuffix(suffix))
        StorageRoot(root, storageManager?.getStorageVolume(appDir)?.getDescription(context) ?: root.name)
    }
}
