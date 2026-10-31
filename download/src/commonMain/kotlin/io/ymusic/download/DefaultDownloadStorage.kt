package io.ymusic.download

import io.ymusic.ytmedia.cache.usableSpaceBytes
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmpcommon.uri.fileUri
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.use
import projectcommon.error.CodedError
import projectcommon.error.DiskFullException
import projectcommon.error.TechnicalException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [DownloadStorage] over an okio [FileSystem]. Scratch files live under [scratchDir]; [publish] moves the
 * finished file into a `file://` destination folder. Non-`file://` destinations (e.g. Android SAF
 * `content://`) are delegated to [publishToUri], which the base rejects — a platform subclass
 * (`AndroidDownloadStorage`) overrides it. Used directly on desktop/iOS, where destinations are `file://`.
 */
open class DefaultDownloadStorage(
    private val fileSystem: FileSystem,
    private val scratchDir: Path,
) : DownloadStorage {

    init {
        fileSystem.createDirectories(scratchDir)
    }

    override fun scratch(taskId: String, name: String): DownloadScratch =
        DefaultDownloadScratch(fileSystem, scratchDir / taskId / name)

    override fun deleteScratch(taskId: String) {
        fileSystem.deleteRecursively(scratchDir / taskId, mustExist = false)
    }

    override fun scratchTaskIds(): List<String> =
        fileSystem.listOrNull(scratchDir).orEmpty().map { it.name }

    final override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String {
        val folder = Uri.parse(destinationFolder)
        if (folder.scheme != "file") {
            val uri = try {
                publishToUri(scratch.path, fileName, folder)
            } catch (e: Exception) {
                if (e is CodedError || e is CancellationException) throw e
                throw TechnicalException("E70032", "could not publish $fileName into $destinationFolder", cause = e)
            }
            scratch.delete()
            return uri
        }
        val dir = folder.filePath().toPath()
        val src = scratch.path.toPath()
        var dest = dir / fileName
        try {
            fileSystem.createDirectories(dir)
            dest = freeName(dir, fileName)
            // The task is marked complete right after this returns: its bytes must be on disk first, or a
            // power loss can keep the rename and lose the data, leaving a "completed" empty file.
            sync(src)
            // A same-filesystem rename is atomic and cheap; a cross-mount one can't be, and THAT is the only
            // failure a copy legitimately recovers. okio surfaces every atomicMove failure as a bare
            // IOException with no portable errno, so we can't tell "cross-device" from "no space / no
            // permission / source gone" by inspecting it — we attempt the rename, and on any IOException try
            // the copy. If the copy also fails it's a genuine storage error, surfaced coded below (not as a
            // second bare okio throw).
            try {
                fileSystem.atomicMove(src, dest)
            } catch (moveError: IOException) {
                try {
                    fileSystem.source(src).use { s -> fileSystem.sink(dest).buffer().use { it.writeAll(s) } }
                    sync(dest)
                    fileSystem.delete(src, mustExist = false)
                } catch (copyError: Exception) {
                    copyError.addSuppressed(moveError)
                    throw copyError
                }
            }
        } catch (e: TechnicalException) {
            throw e
        } catch (e: Exception) {
            throw TechnicalException("E70030", "could not publish $fileName into $dir", cause = e)
        }
        // Build the uri (percent-encoding the path) rather than "file://$dest" — a name with a space or
        // '#' would otherwise produce a malformed string that won't round-trip or match the scanned uri.
        return fileUri(dest.toString()).toString()
    }

    /**
     * Publish [scratchPath]'s finished bytes into a non-`file://` [destinationFolder] as [fileName] and
     * return the created file's uri. The base implementation rejects any such scheme; a platform subclass
     * that can reach the folder (Android SAF `content://`) overrides this.
     */
    protected open suspend fun publishToUri(scratchPath: String, fileName: String, destinationFolder: Uri): String =
        throw TechnicalException(
            "E70031",
            "destination scheme '${destinationFolder.scheme}' unsupported by ${this::class.simpleName}",
        )

    /** fsync on the JVM/Android; okio's native FileHandle.flush() is only fflush. */
    private fun sync(file: Path) {
        fileSystem.openReadWrite(file, mustCreate = false, mustExist = true).use { it.flush() }
    }

    /** [fileName] in [dir], or the next free `stem_N.ext` ([DownloadFileNames.freeName]). */
    private fun freeName(dir: Path, fileName: String): Path =
        dir / DownloadFileNames.freeName(fileName) { fileSystem.exists(dir / it) }
}

/** okio-backed [DownloadScratch]: append-only writes (resume via on-disk size) + a real path for ffmpeg. */
private class DefaultDownloadScratch(
    private val fileSystem: FileSystem,
    private val file: Path,
) : DownloadScratch {

    init {
        file.parent?.let { dir ->
            try {
                fileSystem.createDirectories(dir)
            } catch (e: IOException) {
                // okio's mkdir failure carries no errno; a full volume is the common cause.
                val free = generateSequence(dir) { it.parent }.firstOrNull { fileSystem.exists(it) }?.let(::usableSpaceBytes)
                if (free != null && free < MIN_FREE_BYTES) throw DiskFullException(e)
                throw TechnicalException("E70060", "could not create the download scratch directory $dir", cause = e)
            }
        }
    }

    override val path: String get() = file.toString()

    override fun length(): Long =
        if (fileSystem.exists(file)) fileSystem.metadata(file).size ?: 0L else 0L

    override fun appendSink(): BufferedSink = fileSystem.appendingSink(file).buffer()

    override fun source(): Source = fileSystem.source(file)

    override fun delete() = fileSystem.delete(file, mustExist = false)

    override fun close() {}
}

/** Below this, a failed scratch mkdir counts as a full volume. */
private const val MIN_FREE_BYTES = 1L shl 20
