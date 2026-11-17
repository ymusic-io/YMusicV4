package io.ymusic.desktop

/**
 * A dev run of one checkout: its workspace (its own copy of the app data) and branch, passed by the
 * Gradle run task from the checkout's `.ymusic-dev-data` (tools/dev-data.sh) and git. A packaged
 * build reads neither.
 */
internal object DevInstance {
    /** Not started from a packaged build: jpackage's launcher sets this property, `gradlew run` does not. */
    val isDevRun: Boolean = System.getProperty("jpackage.app-path") == null

    /** The name of `~/.ymusic-dev/<name>`, whose `jvm/` is this run's data dir; null without a workspace. */
    val workspace: String? = devProperty("ymusic.devWorkspace")

    /** [workspace]'s stable 8-hex tag in OS ids (the Rust app derives the same). */
    val workspaceTag: String? = workspace?.let(::fnv1a32Hex)

    val branch: String? = devProperty("ymusic.devBranch")

    /** `YMusic · <branch>` in a dev run on a known branch, plain `YMusic` otherwise. */
    val appTitle: String = listOfNotNull("YMusic", branch).joinToString(" · ")

    /** The Linux desktop entry's Name: [appTitle] in a workspace run, which has its own entry; plain `YMusic` in the shared one. */
    val desktopEntryName: String = if (workspace != null) appTitle else "YMusic"

    /** Workspace runs leave the OS media session (and so the media keys) alone unless asked. */
    val usesMediaSession: Boolean = workspace == null || System.getenv("YMUSIC_DEV_MEDIA_SESSION") == "1"

    private fun devProperty(name: String): String? = if (isDevRun) System.getProperty(name)?.takeIf { it.isNotBlank() } else null
}

/** 32-bit FNV-1a over [text]'s UTF-8 bytes, as 8 lowercase hex digits. */
internal fun fnv1a32Hex(text: String): String {
    var hash = 0x811C9DC5.toInt()
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
    }
    return "%08x".format(hash)
}
