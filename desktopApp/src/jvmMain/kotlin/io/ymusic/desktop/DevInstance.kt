package io.ymusic.desktop

/**
 * A dev run of one checkout: its dev data slot and branch, passed by the Gradle run task from the
 * checkout's `.ymusic-dev-slot` (tools/dev-slot.sh) and git. A packaged build reads neither.
 */
internal object DevInstance {
    /** Not started from a packaged build: jpackage's launcher sets this property, `gradlew run` does not. */
    val isDevRun: Boolean = System.getProperty("jpackage.app-path") == null

    /** N of `~/.ymusic-dev/<N>`, whose `jvm/` is this run's data dir; null without a claimed slot. */
    val slot: Int? = devProperty("ymusic.devSlot")?.toIntOrNull()

    val branch: String? = devProperty("ymusic.devBranch")

    /** `YMusic · <branch> · #<slot>`, each part only when known: plain `YMusic` outside dev runs. */
    val appTitle: String = listOfNotNull("YMusic", branch, slot?.let { "#$it" }).joinToString(" · ")

    private fun devProperty(name: String): String? = if (isDevRun) System.getProperty(name)?.takeIf { it.isNotBlank() } else null
}
