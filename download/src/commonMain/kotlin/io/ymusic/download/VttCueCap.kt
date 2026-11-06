package io.ymusic.download

// MP4 can't hold a sample over 35:47, and ffmpeg fails the whole file on a longer cue.
private const val MAX_CUE_MS = 35 * 60 * 1000L

private val TIMING = Regex("""^((?:\d+:)?\d{2}:\d{2}\.\d{3})(\s+-->\s+)((?:\d+:)?\d{2}:\d{2}\.\d{3})""", RegexOption.MULTILINE)

/** [vtt] with every cue that shows for longer than [MAX_CUE_MS] ending that long after its start. */
internal fun capCueDuration(vtt: String): String = TIMING.replace(vtt) { match ->
    val (start, arrow, end) = match.destructured
    val startMs = vttMillis(start)
    if (vttMillis(end) - startMs <= MAX_CUE_MS) match.value else "$start$arrow${vttTime(startMs + MAX_CUE_MS)}"
}

private fun vttMillis(time: String): Long {
    val (clock, millis) = time.split('.')
    val seconds = clock.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
    return seconds * 1000 + millis.toLong()
}

private fun vttTime(ms: Long): String {
    fun pad(value: Long, width: Int = 2) = value.toString().padStart(width, '0')
    return "${pad(ms / 3_600_000)}:${pad(ms / 60_000 % 60)}:${pad(ms / 1000 % 60)}.${pad(ms % 1000, 3)}"
}
