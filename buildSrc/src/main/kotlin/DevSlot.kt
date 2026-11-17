import org.gradle.api.GradleException
import org.gradle.api.Project

/** This checkout's dev data slot: `~/.ymusic-dev/<number>` at [root], claimed by `tools/dev-slot.sh claim`. */
class DevSlot(val number: Int, val root: String)

/** The slot named by the checkout's `.ymusic-dev-slot`, or null when it has none. */
fun Project.devSlot(): DevSlot? {
    val slotFile = rootProject.layout.projectDirectory.file(".ymusic-dev-slot")
    val text = providers.fileContents(slotFile).asText.orNull ?: return null
    val fields = text.lines()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key.trim() to value.trim() } }
        .toMap()
    val number = fields["slot"]?.toIntOrNull()
    val root = fields["root"]?.takeIf { it.isNotEmpty() }
    if (number == null || root == null) throw GradleException("${slotFile.asFile} is malformed: rerun tools/dev-slot.sh claim")
    return DevSlot(number, root)
}

/** The checkout's branch (a worktree's own), or its short commit on a detached HEAD; null outside git. */
fun Project.devBranch(): String? {
    val branch = git("rev-parse", "--abbrev-ref", "HEAD") ?: return null
    return if (branch == "HEAD") git("rev-parse", "--short", "HEAD") else branch
}

private fun Project.git(vararg args: String): String? {
    val run = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (run.result.get().exitValue != 0) return null
    return run.standardOutput.asText.get().trim().ifEmpty { null }
}
