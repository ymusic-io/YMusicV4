package io.ymusic.android

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import io.ymusic.download.DefaultDownloadStorage
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFolderAccessException
import kmpcommon.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.SyncFailedException

/**
 * Android [DefaultDownloadStorage]: `file://` destinations (app dirs) go through okio as usual; a
 * user-picked SAF folder (`content://` tree, stored as the download-location bucket) is handled here by
 * creating a document and streaming the finished scratch file in via `ContentResolver`.
 */
class AndroidDownloadStorage(
    private val context: Context,
    fileSystem: FileSystem,
    scratchDir: Path,
    private val logger: Logger?,
) : DefaultDownloadStorage(fileSystem, scratchDir) {

    override suspend fun publishToUri(
        scratchPath: String,
        fileName: String,
        destinationFolder: kmpcommon.uri.Uri,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val parent = Uri.parse(destinationFolder.toString()).let { uri ->
            // Defensive: accept a bare tree uri too, though the bucket stores a document uri.
            if (DocumentsContract.isDocumentUri(context, uri)) uri
            else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        }
        // SAF would rename a clash itself ("name (1).ext"); pick the app-wide "name_1.ext" instead.
        val child = try {
            createFreeDocument(parent, fileName)
        } catch (e: SecurityException) {
            // The tree's persisted grant is gone or read-only: only re-picking the folder restores it.
            throw DownloadFolderAccessException(destinationFolder.toString(), e)
        } catch (e: FileNotFoundException) {
            // ExternalStorageProvider's "Missing file for primary:Music": the picked folder was deleted.
            throw DownloadFolderAccessException(destinationFolder.toString(), e)
        } catch (e: IllegalStateException) {
            // ExternalStorageProvider's own wording when File.createNewFile() refuses the path it picked.
            if (e.message?.startsWith("Failed to touch") == true) throw DownloadFolderAccessException(destinationFolder.toString(), e)
            throw e
        } catch (e: IllegalArgumentException) {
            // "Unknown authority …": the storage provider isn't reachable for this tree.
            // "Parent document isn't a directory": the picked folder was replaced or removed.
            val message = e.message.orEmpty()
            if (message.startsWith("Unknown authority") || message.startsWith("Parent document isn't a directory")) {
                throw DownloadFolderAccessException(destinationFolder.toString(), e)
            }
            throw e
        } catch (e: NullPointerException) {
            // Thrown provider-side (a null ContentProviderClient inside ExternalStorageProvider).
            throw DownloadFolderAccessException(destinationFolder.toString(), e)
        } ?: error("SAF createDocument failed in $destinationFolder")
        try {
            resolver.openOutputStream(child)?.use { out ->
                File(scratchPath).inputStream().use { it.copyTo(out) }
                // On disk before the task is marked complete; see DefaultDownloadStorage.publish.
                // A provider that streams through a pipe (cloud storage) has nothing here to sync.
                val fd = (out as? FileOutputStream)?.fd
                if (fd != null && OsConstants.S_ISREG(Os.fstat(fd).st_mode)) {
                    try {
                        fd.sync()
                    } catch (e: SyncFailedException) {
                        // Some SD cards refuse fsync after taking every byte: keep the download.
                        logger?.log(Logger.Level.WARN, "fsync refused while publishing a download", e)
                    }
                }
            } ?: error("SAF openOutputStream failed for $child")
        } catch (e: Exception) {
            // Drop the partial document so a retry doesn't leave a truncated "name.ext" beside "name_1.ext".
            try {
                DocumentsContract.deleteDocument(resolver, child)
            } catch (cleanupError: Exception) {
                e.addSuppressed(cleanupError)
            }
            // A grant revoked between createDocument and the write is the same lost-folder case.
            if (e is SecurityException) throw DownloadFolderAccessException(destinationFolder.toString(), e)
            throw e
        }
        child.toString()
    }

    /**
     * Android 10's Downloads provider answers "Invalid file path" for a name that is taken on disk but
     * missing from its listing (a file it never indexed): such names are skipped, a few at most.
     */
    private fun createFreeDocument(parent: Uri, fileName: String): Uri? {
        val taken = childNames(parent).toMutableSet()
        var attempt = 0
        while (true) {
            val name = DownloadFileNames.freeName(fileName) { it in taken }
            try {
                return DocumentsContract.createDocument(context.contentResolver, parent, mimeFor(name), name)
            } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("Invalid file path") != true || ++attempt >= MAX_UNLISTED_CLASHES) throw e
                taken += name
            }
        }
    }

    /** Empty when the provider can't list the folder (Android 10's Downloads provider overflows SQLite's
     *  variable limit past ~1000 files): createDocument then resolves a clash itself as "name (1).ext". */
    private fun childNames(parent: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val names = mutableSetOf<String>()
        try {
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
            }
        } catch (e: SQLiteException) {
            logger?.fork("child-names", mapOf("folder" to parent.toString()))
                ?.log(Logger.Level.WARN, "provider can't list the folder; leaving name clashes to it", e)
            return emptySet()
        }
        return names
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "opus" -> "audio/ogg"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

    private companion object {
        const val MAX_UNLISTED_CLASHES = 5
    }
}
