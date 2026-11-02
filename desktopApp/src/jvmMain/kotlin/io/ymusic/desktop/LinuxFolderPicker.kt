package io.ymusic.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Window
import java.io.File
import java.io.IOException
import javax.swing.JFileChooser

/**
 * A folder picker for Linux, where AWT's FileDialog only picks files: the desktop's own dialog through
 * kdialog / zenity when one is installed, else Swing's chooser. Null when canceled.
 */
internal object LinuxFolderPicker {
    private sealed interface Outcome {
        data class Picked(val folder: File?) : Outcome
        data object Unavailable : Outcome
    }

    suspend fun pick(owner: Window?, title: String): File? {
        for (command in commands(title)) {
            val outcome = withContext(Dispatchers.IO) { runTool(command) }
            if (outcome is Outcome.Picked) return outcome.folder
        }
        return withContext(Dispatchers.Main) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            }
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }

    private fun commands(title: String): List<List<String>> {
        val home = System.getProperty("user.home")
        val kdialog = listOf("kdialog", "--title", title, "--getexistingdirectory", home)
        val zenity = listOf("zenity", "--file-selection", "--directory", "--title=$title")
        val kde = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", ignoreCase = true)
        return if (kde) listOf(kdialog, zenity) else listOf(zenity, kdialog)
    }

    // Both tools exit 0 with the path on stdout when picked, 1 when canceled.
    private fun runTool(command: List<String>): Outcome {
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: IOException) {
            // Not installed: the caller tries the next tool.
            return Outcome.Unavailable
        }
        val path = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return when (process.waitFor()) {
            0 -> Outcome.Picked(File(path).takeIf { path.isNotEmpty() && it.isDirectory })
            1 -> Outcome.Picked(null)
            else -> Outcome.Unavailable
        }
    }
}
