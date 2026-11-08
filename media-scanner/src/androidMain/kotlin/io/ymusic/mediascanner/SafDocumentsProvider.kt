package io.ymusic.mediascanner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.Document.COLUMN_FLAGS
import android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
import android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
import android.provider.DocumentsContract.Document.COLUMN_SIZE
import android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
import android.provider.DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
import android.provider.DocumentsContract.Document.MIME_TYPE_DIR
import io.ymusic.mediascanner.internal.guessMimeType
import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import android.net.Uri as AndroidUri

/**
 * A [DocumentsProvider] over Android's Storage Access Framework — the counterpart to the desktop
 * [FileSystemDocumentsProvider]. Handles SAF tree/document uris (`content://…/tree/…`) via
 * `DocumentsContract` + `ContentResolver`, and plain `file://` uris via [File]. The app is
 * responsible for obtaining and persisting the tree permission
 * (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`) and passing the granted tree uri.
 */
class SafDocumentsProvider(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DocumentsProvider {

    private val projection = arrayOf(
        COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME, COLUMN_MIME_TYPE,
        COLUMN_SIZE, COLUMN_LAST_MODIFIED, COLUMN_FLAGS,
    )

    override suspend fun queryDocument(uri: Uri): DocumentInfo = withContext(io) {
        val android = AndroidUri.parse(uri.toString())
        when (android.scheme) {
            "file" -> {
                val file = File(requireNotNull(android.path))
                val info = fileDocument(uri, file)
                // A volume root's own name is a mount id ("0", "1234-5678"); the volume's label reads better.
                val volume = (info as? DocumentInfo.Folder)?.let { storageRoots(context).firstOrNull { it.directory == file } }
                if (volume != null) info.copy(name = volume.label) else info
            }
            "content" -> {
                if (!hasReadPermission(android)) return@withContext DocumentInfo.Missing(uri, permissionDenied = true)
                // A bare tree uri (content://auth/tree/X — what the app grants and the docs tell it
                // to pass) is NOT itself queryable; resolve it to the tree's root document uri.
                val docUri = asDocumentUri(android)
                val docKmpUri = if (docUri == android) uri else Uri.parse(docUri.toString())
                try {
                    context.contentResolver.query(docUri, projection, null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return@withContext DocumentInfo.Missing(docKmpUri)
                        c.toDocumentInfo(docKmpUri)
                    } ?: DocumentInfo.Missing(docKmpUri)
                } catch (e: SecurityException) {
                    DocumentInfo.Missing(docKmpUri, permissionDenied = true)
                } catch (e: FileNotFoundException) {
                    DocumentInfo.Missing(docKmpUri)
                } catch (e: IllegalArgumentException) {
                    // ExternalStorageProvider's "Missing file for …".
                    DocumentInfo.Missing(docKmpUri)
                } catch (e: IllegalStateException) {
                    // "File has no filepath. Could not be found."
                    DocumentInfo.Missing(docKmpUri)
                }
            }
            else -> DocumentInfo.Missing(uri)
        }
    }

    override suspend fun listChildren(folderUri: Uri): List<DocumentInfo> = withContext(io) {
        val android = AndroidUri.parse(folderUri.toString())
        if (android.scheme == "file") {
            val dir = File(requireNotNull(android.path))
            // Unreadable/unenumerable is an ERROR, never an empty listing — the scanner turns
            // absences into deletions, so swallowing this would tombstone the folder's library.
            if (!dir.isDirectory) throw IOException("not a folder: $dir")
            if (!dir.canRead()) throw IOException("folder not readable: $dir")
            val children = dir.listFiles() ?: throw IOException("could not list: $dir")
            return@withContext children.map { child ->
                fileDocument(Uri.parse(AndroidUri.fromFile(child).toString()), child)
            }
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            android, documentIdOf(android), // getDocumentId throws on a bare tree uri
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@withContext emptyList()
            val colId = c.getColumnIndexOrThrow(COLUMN_DOCUMENT_ID)
            val result = ArrayList<DocumentInfo>(c.count)
            do {
                val childDocId = c.getString(colId)
                val childAndroidUri = DocumentsContract.buildDocumentUriUsingTree(android, childDocId)
                result += c.toDocumentInfo(Uri.parse(childAndroidUri.toString()))
            } while (c.moveToNext())
            result
        } ?: throw IOException("provider query failed for $childrenUri") // null cursor is an error, not an empty folder
    }

    // A bare tree uri (…/tree/X) has no document segment: getDocumentId throws and query()
    // rejects it. Map it to the tree's root document uri; a real document uri is returned as-is.
    private fun asDocumentUri(android: AndroidUri): AndroidUri =
        if (DocumentsContract.isDocumentUri(context, android)) android
        else DocumentsContract.buildDocumentUriUsingTree(android, DocumentsContract.getTreeDocumentId(android))

    private fun documentIdOf(android: AndroidUri): String =
        if (DocumentsContract.isDocumentUri(context, android)) DocumentsContract.getDocumentId(android)
        else DocumentsContract.getTreeDocumentId(android)

    private fun hasReadPermission(android: AndroidUri): Boolean = runCatching {
        context.checkCallingOrSelfUriPermission(android, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrNull() == PackageManager.PERMISSION_GRANTED

    private fun fileDocument(uri: Uri, file: File): DocumentInfo = when {
        // Unreadable: it's a permission problem only if the file is actually there; otherwise it's gone.
        !file.canRead() -> DocumentInfo.Missing(uri, permissionDenied = file.exists())
        file.isDirectory -> DocumentInfo.Folder(uri, name = file.name)
        else -> DocumentInfo.File(
            uri = uri,
            name = file.name,
            mimeType = guessMimeType(file.name),
            size = file.length(),
            lastModified = file.lastModified(),
        )
    }

    // Some providers (seen on Android 8.1 for a tree's root) answer a null display name.
    private fun nameFromDocumentId(uri: Uri): String {
        val id = AndroidUri.parse(uri.toString()).lastPathSegment.orEmpty()
        return id.substringAfterLast('/').substringAfterLast(':')
    }

    private fun Cursor.toDocumentInfo(uri: Uri): DocumentInfo {
        val name = getString(getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)) ?: nameFromDocumentId(uri)
        val mimeType = getString(getColumnIndexOrThrow(COLUMN_MIME_TYPE))
        val flags = runCatching { getInt(getColumnIndexOrThrow(COLUMN_FLAGS)) }.getOrDefault(0)
        val isVirtual = flags and FLAG_VIRTUAL_DOCUMENT != 0
        val isDirectory = mimeType == MIME_TYPE_DIR || flags and FLAG_DIR_SUPPORTS_CREATE != 0

        return if (isDirectory) {
            DocumentInfo.Folder(uri, name = name, isVirtual = isVirtual)
        } else {
            val sizeCol = getColumnIndexOrThrow(COLUMN_SIZE)
            val modCol = getColumnIndexOrThrow(COLUMN_LAST_MODIFIED)
            DocumentInfo.File(
                uri = uri,
                name = name,
                mimeType = mimeType ?: guessMimeType(name),
                size = if (isNull(sizeCol)) -1L else getLong(sizeCol),
                lastModified = if (isNull(modCol)) -1L else getLong(modCol),
                isVirtual = isVirtual,
            )
        }
    }
}
