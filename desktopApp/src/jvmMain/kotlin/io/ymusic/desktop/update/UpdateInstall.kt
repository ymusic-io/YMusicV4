package io.ymusic.desktop.update

import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.update_blocked_folder_read_only
import io.ymusic.ui.resources.update_blocked_mac_swap
import io.ymusic.ui.resources.update_blocked_no_pkexec
import io.ymusic.ui.resources.update_blocked_not_deb
import io.ymusic.ui.resources.update_blocked_not_installed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Runs a downloaded, verified installer over the running copy. Every platform installs the whole
 * build: macOS swaps the .app, Windows and Linux hand the package to the OS installer, which asks
 * for admin rights — the install lives where a user-level process cannot rewrite it.
 */
internal interface Installer {
    /** The installer file this platform publishes. */
    val extension: String

    /** Null when an in-place update is possible here; otherwise why not, shown to the user. */
    val blockedReason: StringResource?

    /**
     * Install [file], already checked against [update]'s signed hash. True: the new version is
     * starting and this process must exit now. False: the user declined the admin prompt.
     */
    suspend fun install(file: File, update: AppUpdate): Boolean
}

/** `macos-arm64`, `windows-x64`, `linux-x64`: the key the config service publishes releases by. */
internal fun desktopPlatformKey(os: String): String = "$os-${desktopArch()}"

internal fun desktopArch(): String = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    else -> "x64"
}

internal fun installerFor(os: String): Installer {
    // jpackage's launcher sets this; absent when running from Gradle.
    val launcher = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }
    return when (os) {
        "macos" -> MacDmgInstaller(launcher?.let { generateSequence(it) { f -> f.parentFile }.firstOrNull { f -> f.name.endsWith(".app") } })
        "windows" -> WindowsSetupInstaller(launcher)
        else -> LinuxDebInstaller(launcher)
    }
}

private const val NOT_INSTALLED = "the app is not running from an installed copy"

/** Copies the .app out of the DMG beside the installed one, checks it is ours, swaps, relaunches. */
internal class MacDmgInstaller(private val bundle: File?) : Installer {
    override val extension = "dmg"

    override val blockedReason: StringResource?
        get() = when {
            bundle == null -> Res.string.update_blocked_not_installed
            !MacBundleSwap.isSupported -> Res.string.update_blocked_mac_swap
            // The swap renames inside the folder holding the .app.
            bundle.parentFile?.canWrite() != true -> Res.string.update_blocked_folder_read_only
            else -> null
        }

    override suspend fun install(file: File, update: AppUpdate): Boolean = withContext(Dispatchers.IO) {
        val bundle = checkNotNull(bundle) { NOT_INSTALLED }
        // Same volume as the install, so the exchange is one rename.
        val staged = File(bundle.parentFile, ".${bundle.name}.new-${update.versionCode}")
        staged.deleteRecursively()
        val mount = Files.createTempDirectory("ymusic-update").toFile()
        run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path, file.path)
        try {
            val app = mount.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.endsWith(".app") }
                ?: error("the disk image holds no .app")
            run("ditto", app.path, staged.path)
        } finally {
            run("hdiutil", "detach", "-force", mount.path)
            mount.delete()
        }
        try {
            // A signed install only ever becomes a bundle signed by the same team. An unsigned
            // (local) build has no team to hold the update to.
            teamId(bundle)?.let { team ->
                run("codesign", "--verify", "--deep", "--strict", staged.path)
                check(teamId(staged) == team) { "the update is not signed by team $team" }
            }
            MacBundleSwap.exchange(bundle, staged)
        } catch (error: Throwable) {
            staged.deleteRecursively()
            throw error
        }
        staged.deleteRecursively() // now the superseded version
        // `open -n`: the new app must not be a child that dies with this process.
        ProcessBuilder("open", "-n", bundle.path).start()
        true
    }

    private fun teamId(app: File): String? {
        val result = exec("codesign", "-dv", "--verbose=2", app.path)
        if (result.exitCode != 0) return null
        return result.output.lineSequence().firstOrNull { it.startsWith("TeamIdentifier=") }
            ?.substringAfter('=')?.takeIf { it != "not set" }
    }
}

/**
 * Runs the Inno Setup installer once this process has exited — a running app's files are locked.
 * A detached PowerShell step waits for our exit, runs the setup elevated (the UAC prompt) showing
 * progress only, then starts YMusic again unelevated, whether or not the user accepted.
 */
internal class WindowsSetupInstaller(private val launcher: File?) : Installer {
    override val extension = "exe"

    override val blockedReason: StringResource? get() = if (launcher == null) Res.string.update_blocked_not_installed else null

    override suspend fun install(file: File, update: AppUpdate): Boolean = withContext(Dispatchers.IO) {
        val launcher = checkNotNull(launcher) { NOT_INSTALLED }
        val script = File(file.parentFile, "install-update.ps1")
        script.writeText(
            """
            param([int]${'$'}WaitPid, [string]${'$'}Setup, [string]${'$'}Launcher)
            Wait-Process -Id ${'$'}WaitPid -ErrorAction SilentlyContinue
            try {
                ${'$'}setupArgs = @('/SILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-')
                Start-Process -FilePath ${'$'}Setup -Verb RunAs -Wait -ArgumentList ${'$'}setupArgs
            } catch {}
            Start-Process -FilePath ${'$'}Launcher
            """.trimIndent(),
        )
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.path,
            "-WaitPid", ProcessHandle.current().pid().toString(),
            "-Setup", file.path,
            "-Launcher", launcher.path,
        ).start()
        true
    }
}

/** Installs the .deb through apt behind a polkit password prompt, then relaunches. */
internal class LinuxDebInstaller(private val launcher: File?) : Installer {
    override val extension = "deb"

    override val blockedReason: StringResource?
        get() = when {
            launcher == null -> Res.string.update_blocked_not_installed
            !launcher.path.startsWith("/opt/") -> Res.string.update_blocked_not_deb
            !File(PKEXEC).canExecute() || !File(APT_GET).canExecute() -> Res.string.update_blocked_no_pkexec
            else -> null
        }

    override suspend fun install(file: File, update: AppUpdate): Boolean = withContext(Dispatchers.IO) {
        val launcher = checkNotNull(launcher) { NOT_INSTALLED }
        val result = exec(PKEXEC, APT_GET, "install", "-y", file.absolutePath)
        // pkexec: 126 = the user dismissed the prompt, 127 = not authorized.
        if (result.exitCode == 126 || result.exitCode == 127) return@withContext false
        check(result.exitCode == 0) { "apt-get exited ${result.exitCode}: ${result.output.takeLast(2000)}" }
        ProcessBuilder(launcher.path).start()
        true
    }

    private companion object {
        const val PKEXEC = "/usr/bin/pkexec"
        const val APT_GET = "/usr/bin/apt-get"
    }
}

private class ExecResult(val exitCode: Int, val output: String)

private fun exec(vararg command: String): ExecResult {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return ExecResult(process.waitFor(), output)
}

private fun run(vararg command: String) {
    val result = exec(*command)
    check(result.exitCode == 0) { "${command.first()} exited ${result.exitCode}: ${result.output.takeLast(2000)}" }
}

/** SHA-256 of a file, streamed — an installer is a few hundred MB. */
internal fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
