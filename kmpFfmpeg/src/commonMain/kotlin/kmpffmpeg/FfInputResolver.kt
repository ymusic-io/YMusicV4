package kmpffmpeg

import kmpcommon.uri.Uri
import kmpcommon.uri.filePath

/**
 * Maps a logical [Uri] to an [FfInput] ffmpeg can actually read. The default [Passthrough] hands
 * the URI text straight to ffmpeg (fine for `file:`/`http:`/`pipe:`/`ymix:`). A platform that must
 * proxy a scheme ffmpeg doesn't understand — notably Android's `content://` via `ContentResolver` —
 * supplies its own resolver that opens the source and returns an [FfInput] backing it (`pipe:0`
 * when the fd isn't seekable, or `ymix:` when it is).
 *
 * The resolver is the single seam where `content://` handling plugs in, and it runs in the PRE-start
 * phase: open the fd and launch any ymix server here, so the server is listening before ffmpeg
 * connects. The caller owns the resolve → start → [FfInput.onProcessStarted] → [FfInput.close]
 * bracket and guarantees [FfInput.close] even if the process fails to start, so a server opened in
 * [resolve] is never leaked.
 */
fun interface FfInputResolver {
    suspend fun resolve(uri: Uri): FfInput

    companion object {
        /** Hands the URI to ffmpeg directly — no proxying. See [toFfmpegSpec] for the `file:` handling. */
        val Passthrough: FfInputResolver = FfInputResolver { FfInput.passthrough(it.toFfmpegSpec()) }
    }
}

/**
 * Convert a [Uri] to the spec ffmpeg should receive.
 *
 * `file:` is special: ffmpeg's file protocol takes the path **literally** — it does NOT percent-decode —
 * so a `file:/a%20b.mp3` would make it look for a file literally named `a%20b.mp3`. We therefore emit
 * the **decoded** path (`file:/a b.mp3`), which is what ffmpeg wants and what survives as a single argv
 * argument. Every other scheme (`http:`, `pipe:`, `ymix:`, …) is passed through verbatim, since those
 * are consumed by code that does expect proper URI encoding.
 */
internal fun Uri.toFfmpegSpec(): String =
    if (scheme?.toString().equals("file", ignoreCase = true)) "file:${filePath()}" else toString()
