package io.ymusic.desktop

import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.menu_reveal_explorer
import io.ymusic.ui.resources.menu_reveal_file_manager
import io.ymusic.ui.resources.menu_reveal_finder
import org.jetbrains.compose.resources.StringResource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The host file manager's own name for showing an item in place. */
internal fun fileManagerRevealLabel(): StringResource = when (desktopOsKey()) {
    "macos" -> Res.string.menu_reveal_finder
    "windows" -> Res.string.menu_reveal_explorer
    else -> Res.string.menu_reveal_file_manager
}

/** Opens the file manager on [path]'s folder with [path] (a file or a folder) selected. Blocking. */
internal fun revealInFileManager(path: String) {
    val file = File(path).absoluteFile
    when (desktopOsKey()) {
        "macos" -> ProcessBuilder("open", "-R", file.path).start()
        // Explorer exits 1 even on success, so its status is not checked.
        "windows" -> ProcessBuilder("explorer.exe", "/select,", file.path).start()
        else -> if (!showItemsOverDbus(file)) ProcessBuilder("xdg-open", (file.parentFile ?: file).path).start()
    }
}

/** FileManager1.ShowItems selects the item (Nautilus, Dolphin, Nemo, …); false = no such service here. */
private fun showItemsOverDbus(file: File): Boolean {
    val process = try {
        ProcessBuilder(
            "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1",
            "--type=method_call", "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
            "array:string:file://${file.toURI().rawPath}", "string:",
        ).redirectErrorStream(true).start()
    } catch (_: IOException) {
        // No dbus-send on this system: the caller falls back to xdg-open.
        return false
    }
    process.inputStream.close()
    return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
}
