package io.ymusic.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.LanguageDirection
import io.ymusic.ui.YMusicTheme
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.app_name
import io.ymusic.ui.resources.data_dir_unusable
import io.ymusic.ui.resources.tray_quit
import kmpcommon.log.Logger
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.TechnicalException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What [prepareDataDir] had to do before the data dir could be used. */
internal sealed interface DataDirSetup {
    data object Ready : DataDirSetup

    data object Created : DataDirSetup

    /** The data dir is a symlink whose folder was gone: the folder was made again where it points. */
    data class RecreatedLinkTarget(val target: Path) : DataDirSetup

    fun report(dataDir: File, log: Logger) {
        when (this) {
            Ready -> Unit
            Created -> log.log(Logger.Level.INFO, "created the data dir $dataDir")
            is RecreatedLinkTarget ->
                log.log(Logger.Level.WARN, "E92001 the data dir $dataDir links to $target, which was gone: made it again, empty")
        }
    }
}

private const val MAX_LINK_HOPS = 16
private const val WRITE_CHECK_PREFIX = ".write-check-"
private val SIDECARS = listOf("-wal", "-shm", "-journal")
private const val SQLITE_CORRUPT = 11
private const val SQLITE_NOTADB = 26

/**
 * Blocking. Makes [dataDir] a folder this process can write to before anything opens a file in it.
 * A missing folder, or the missing folder a symlink points at, is created; anything else in the way
 * (a file, no write permission, a link into a folder that is gone) throws.
 */
@Throws(IOException::class)
internal fun prepareDataDir(dataDir: File): DataDirSetup {
    val path = dataDir.toPath()
    val setup = when {
        Files.exists(path) -> DataDirSetup.Ready
        else -> when (val target = danglingLinkTarget(path)) {
            // Only the last component: a link into a folder that is gone (an unplugged drive) is not made on the boot disk.
            null -> DataDirSetup.Created.also { Files.createDirectories(path) }
            else -> DataDirSetup.RecreatedLinkTarget(target).also { Files.createDirectory(target) }
        }
    }
    if (!Files.isDirectory(path)) throw NotDirectoryException(path.toString())
    val probe = path.resolve("$WRITE_CHECK_PREFIX${ProcessHandle.current().pid()}")
    Files.newOutputStream(probe).close()
    Files.delete(probe)
    return setup
}

/** The path at the end of [path]'s symlink chain when [path] is a symlink; null when it is not one. */
private fun danglingLinkTarget(path: Path): Path? {
    var current = path
    repeat(MAX_LINK_HOPS) {
        if (!Files.isSymbolicLink(current)) {
            return current.takeIf { it != path }
        }
        val target = Files.readSymbolicLink(current)
        current = current.parent?.resolve(target) ?: target
    }
    throw IOException("the data dir's symlinks loop")
}

/** What [openDatabase] found when the file SQLite opened was unreadable. */
internal class CorruptDatabaseException(name: String, cause: Throwable) :
    IOException("E92002 $name is not a readable database", cause)

/**
 * Blocking. [open] on the database [file]; a file SQLite reports corrupt or not a database is moved
 * aside, with its write-ahead log and journal, as `<name>.corrupt-<local time>`, and a fresh one opened
 * in its place. Any other failure, or one the move does not get past, is [open]'s.
 */
internal fun <T> openDatabase(file: File, log: Logger, open: (File) -> T): T {
    val error = try {
        return open(file)
    } catch (e: Exception) {
        if (!e.isCorruption()) throw e
        e
    }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val aside = File(file.path + ".corrupt-$stamp")
    try {
        moveDatabase(file.toPath(), aside.toPath())
    } catch (moveError: IOException) {
        log.log(Logger.Level.WARN, "E92003 the unreadable ${file.name} could not be moved aside", moveError)
        error.addSuppressed(moveError)
        throw error
    }
    log.captureError(CorruptDatabaseException(file.name, error), "unreadable database moved to $aside, starting a new one")
    return open(file)
}

private fun Throwable.isCorruption(): Boolean =
    generateSequence(this) { it.cause }.take(32).filterIsInstance<SQLException>()
        .any { (it.errorCode and 0xff) == SQLITE_CORRUPT || (it.errorCode and 0xff) == SQLITE_NOTADB }

/**
 * The main file goes last: a failed move must never leave a sidecar where the next open creates a fresh
 * database, which SQLite would replay that sidecar into.
 */
private fun moveDatabase(file: Path, aside: Path) {
    for (suffix in SIDECARS) {
        try {
            Files.move(file.resolveSibling(file.fileName.toString() + suffix), aside.resolveSibling(aside.fileName.toString() + suffix))
        } catch (_: NoSuchFileException) {
            // That sidecar is not there: nothing to move.
        }
    }
    Files.move(file, aside)
}

/** The launch cannot use its data dir: logs it, shows the code until the user closes the window, and returns. */
internal fun dataDirUnusable(dataDir: File, error: Throwable, log: Logger) {
    val code = "E92004"
    log.captureError(TechnicalException(code, "the data dir $dataDir cannot be used", cause = error))
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = DevInstance.appTitle,
            state = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(480.dp, 280.dp)),
        ) {
            YMusicTheme {
                LanguageDirection {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(DesignSpacing.Space24),
                        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(Res.string.data_dir_unusable, dataDir.path, code),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = ::exitApplication) { Text(stringResource(Res.string.tray_quit)) }
                    }
                }
            }
        }
    }
}
