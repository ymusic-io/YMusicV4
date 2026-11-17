import org.gradle.api.GradleException
import org.gradle.api.Project

/** This checkout's own copy of the app data, `~/.ymusic-dev/<name>` at [root], made by `tools/dev-data.sh create`. */
class DevWorkspace(val name: String, val root: String)

/** The workspace named by the checkout's `.ymusic-dev-data`, or null when it has none. */
fun Project.devWorkspace(): DevWorkspace? {
    val dataFile = rootProject.layout.projectDirectory.file(".ymusic-dev-data")
    val text = providers.fileContents(dataFile).asText.orNull ?: return null
    val fields = text.lines()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key.trim() to value.trim() } }
        .toMap()
    val name = fields["name"]?.takeIf { it.isNotEmpty() }
    val root = fields["root"]?.takeIf { it.isNotEmpty() }
    if (name == null || root == null) throw GradleException("${dataFile.asFile} is malformed: rerun tools/dev-data.sh create")
    return DevWorkspace(name, root)
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
