package io.ymusic.desktop

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

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

/** The slot number on a disc: the Windows taskbar overlay that tells dev runs apart. */
internal fun devSlotBadge(slot: Int): BufferedImage {
    val size = 32
    return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
        val graphics = createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.color = Color(0xE5, 0x39, 0x35)
            graphics.fillOval(0, 0, size, size)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
            val label = slot.toString()
            val metrics = graphics.fontMetrics
            graphics.drawString(label, (size - metrics.stringWidth(label)) / 2, (size - metrics.height) / 2 + metrics.ascent)
        } finally {
            graphics.dispose()
        }
    }
}
