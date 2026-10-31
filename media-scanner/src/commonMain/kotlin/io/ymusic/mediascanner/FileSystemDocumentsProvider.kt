package io.ymusic.mediascanner

import io.ymusic.mediascanner.internal.guessMimeType
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmpcommon.uri.fileUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * A [DocumentsProvider] over an okio [FileSystem]. Works on every platform okio supports (JVM,
 * native Linux/macOS, Apple); desktop apps construct it with `FileSystem.SYSTEM`, tests with a
 * `FakeFileSystem`. Handles `file:` uris (path taken from the uri's decoded path).
 */
class FileSystemDocumentsProvider(
    private val fileSystem: FileSystem,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : DocumentsProvider {

    override suspend fun queryDocument(uri: Uri): DocumentInfo = withContext(io) {
        if (!uri.scheme.contentEquals("file")) return@withContext DocumentInfo.Missing(uri)
        val path = uri.filePath().toPath()
        val md = fileSystem.metadataOrNull(path) ?: return@withContext DocumentInfo.Missing(uri)
        when {
            md.isDirectory -> DocumentInfo.Folder(uri, name = path.name)
            else -> DocumentInfo.File(
                uri = uri,
                name = path.name,
                mimeType = guessMimeType(path.name),
                size = md.size ?: -1L,
                lastModified = md.lastModifiedAtMillis ?: -1L,
            )
        }
    }

    override suspend fun listChildren(folderUri: Uri): List<DocumentInfo> = withContext(io) {
        if (!folderUri.scheme.contentEquals("file")) return@withContext emptyList()
        val dir = folderUri.filePath().toPath()
        val children = fileSystem.list(dir) // throws on unreadable: NEVER report it as empty

        children.map { child ->
            val childUri = fileUri(child.toString())
            val md = fileSystem.metadataOrNull(child)
            when {
                md == null -> DocumentInfo.Missing(childUri)
                md.isDirectory -> DocumentInfo.Folder(childUri, name = child.name)
                else -> DocumentInfo.File(
                    uri = childUri,
                    name = child.name,
                    mimeType = guessMimeType(child.name),
                    size = md.size ?: -1L,
                    lastModified = md.lastModifiedAtMillis ?: -1L,
                )
            }
        }
    }
}
