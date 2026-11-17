package kmpffmpeg.metadata

import kmpcommon.uri.Uri
import kmpffmpeg.FfInput
import kmpffmpeg.FfInputResolver
import kmpffmpeg.KmpProcess
import kmpffmpeg.KmpProcessBuilder
import kmpffmpeg.autoClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.IOException
import okio.Source
import okio.buffer
import okio.use
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads a media file's metadata by running `ffmpeg -i <input>` and parsing its stderr
 * ([FfmpegInfoParser]). `ffmpeg -i` with no output file always exits non-zero ("At least one output
 * file must be specified") *after* printing the info, so the exit code is ignored — success is "did
 * the dump contain an `Input #` block", which the parser decides.
 *
 * @param ffmpegBinary  absolute path to the prebuilt `ffmpeg` executable.
 * @param inputResolver maps the requested [Uri] to the actual ffmpeg input. Defaults to
 *                      [FfInputResolver.Passthrough] (`file:`/`http:`/`pipe:`/`ymix:`); supply an
 *                      Android resolver to proxy `content://` (see [FfInputResolver]).
 * @param environment   extra env vars for the child. The packed ffmpeg finds libav via rpath
 *                      (`$ORIGIN` / `@loader_path`); do not pass `LD_LIBRARY_PATH`.
 */
open class FfmpegMetadataGetter(
    private val ffmpegBinary: String,
    private val inputResolver: FfInputResolver = FfInputResolver.Passthrough,
    private val environment: Map<String, String> = emptyMap(),
) {
    /**
     * @param uri       the logical media source; [inputResolver] turns it into ffmpeg's `-i` spec. The
     *                  original [uri] (not the resolved spec) is stored on the result.
     * @param timeoutMs hard cap on the probe; the child is terminated if it overruns.
     */
    suspend fun getMetadata(uri: Uri, timeoutMs: Long = 3000): MediaFileMetadata =
        // Run on Dispatchers.Default (not the caller's context) so withTimeout uses real wall-clock
        // delay — under kotlinx-coroutines-test's virtual time it would otherwise fire instantly.
        withContext(Dispatchers.Default) {
            inputResolver.resolve(uri).use { input ->
                KmpProcessBuilder(ffmpegBinary)
                    .appendArg("-hide_banner", "-i", input.spec)
                    .setEnv(environment)
                    .discardStdout() // ffmpeg -i writes the info to stderr; stdout stays empty
                    .start()
                    .autoClose { process ->
                        input.onProcessStarted(process) // e.g. start feeding pipe:0 / serving ymix

                        // Read stderr on the process's OWN scope: the blocking fd read isn't cancellable, so
                        // on timeout we let process.close() kill the child — that makes the read hit EOF, and
                        // close() then joins this job before freeing the fd. (exit is expected non-zero.)
                        val readStderr = process.scope.async { process.stderr.buffer().readUtf8() }
                        // Surface a probe timeout as IOException, not the TimeoutCancellationException
                        // withTimeout throws — callers must see a failure, not cooperative cancellation.
                        val stderr = try {
                            withTimeout(timeoutMs.milliseconds) { readStderr.await() }
                        } catch (e: TimeoutCancellationException) {
                            throw IOException("ffmpeg probe timed out after ${timeoutMs}ms: $uri", e)
                        }
                        process.exitCode.await() // reap on the normal path (close() below won't wait)
                        FfmpegInfoParser.parse(uri, stderr)
                    }
            }
        }

    /**
     * Streams the raw bytes of the attached picture (cover art) of [uri] — `ffmpeg -i <input>
     * -map 0:disp:attached_pic -c copy -frames:v 1 -update 1 -f image2 pipe:1`. The `disp:attached_pic`
     * stream specifier selects the cover by its disposition, so the caller needs no stream index; if a
     * file carries several covers, the first (in stream order) is returned.
     *
     * Uses the `image2` muxer with `-update 1` (writes a single image to the pipe) — the trimmed
     * prebuilt has no `image2pipe`. `-c copy` passes the embedded image (png/mjpeg) through untouched.
     *
     * The returned [Source] OWNS the ffmpeg child: it runs until you drain the source to EOF or
     * [Source.close] it, and closing tears down the process and the input proxy. Always close it
     * (`source.use { … }`), even on a partial read, or the child and its fds leak.
     *
     * Fails fast: a file with no attached picture makes the map match nothing, so ffmpeg exits non-zero
     * having written nothing and this throws [FfmpegAttachedPicException] (carrying ffmpeg's stderr)
     * before returning — it blocks only until the first output byte. To know in advance whether a cover
     * exists, check [MediaFileMetadata.streams] / [StreamMetadata.isAttachedThumbnail] from [getMetadata].
     */
    suspend fun getAttachedPic(uri: Uri): Source = streamSingleImage(
        uri,
        args = { spec ->
            listOf(
                "-hide_banner", "-loglevel", "error", "-i", spec,
                "-map", "0:disp:attached_pic", "-c", "copy", "-frames:v", "1", "-update", "1", "-f", "image2", "pipe:1",
            )
        },
        onNoOutput = { stderr, exitCode -> FfmpegAttachedPicException(stderr, exitCode) },
    )

    /**
     * Decodes ONE representative key frame of [uri], within 640x640, to a JPEG and pipes it out — a
     * poster/thumbnail for a real video.
     *
     * Uses ffmpeg's `thumbnail` filter, which scans a batch of frames and picks the most representative
     * one (by histogram), so it sidesteps black intro/lead-in frames without us probing the duration or
     * seeking to a position. The filter holds its whole batch (100 frames) and the decoder some frames per
     * thread, so only key frames are decoded, on one thread, and scaled before the filter: on full-size
     * frames a 4K video's run takes 1.7 GB. The `image2` muxer with `-update 1` writes the single JPEG to
     * the pipe (the trimmed prebuilt has no `image2pipe`). The Rust desktop app's `extract_thumbnail`
     * runs the same argv.
     *
     * Lifecycle and fail-fast match [getAttachedPic]: the returned [Source] OWNS the child (drain to EOF
     * or [Source.close] to tear down the process + input proxy — always close it), and a [uri] with no
     * decodable video produces no output, surfaced as [FfmpegThumbnailException] before returning. For an
     * audio file whose only "image" is cover art, use [getAttachedPic] instead.
     */
    suspend fun extractThumbnail(uri: Uri): Source = streamSingleImage(
        uri,
        args = { spec ->
            listOf(
                "-hide_banner", "-loglevel", "error", "-threads", "1", "-skip_frame", "nokey", "-i", spec,
                "-vf", "scale=w=640:h=640:force_original_aspect_ratio=decrease:force_divisible_by=2:flags=fast_bilinear,thumbnail",
                "-frames:v", "1", "-an", "-c:v", "mjpeg", "-update", "1", "-f", "image2", "pipe:1",
            )
        },
        onNoOutput = { stderr, exitCode -> FfmpegThumbnailException(stderr, exitCode) },
    )

    /**
     * Shared engine for the single-image extractors: resolve the input, launch ffmpeg with [args] (the
     * full argv built from the resolved `-i` spec), and return a [Source] over its stdout that owns the
     * process + proxy. Blocks only until the first output byte; if ffmpeg dies having written nothing,
     * awaits its exit and throws whatever [onNoOutput] builds from the captured stderr.
     */
    private suspend fun streamSingleImage(
        uri: Uri,
        args: (spec: String) -> List<String>,
        onNoOutput: (stderr: String, exitCode: Int) -> Throwable,
    ): Source = withContext(Dispatchers.IO) {
        val input = inputResolver.resolve(uri)
        val process = try {
            KmpProcessBuilder(ffmpegBinary)
                .appendArgs(args(input.spec))
                .setEnv(environment)
                .start()
        } catch (t: Throwable) {
            input.closeSuppressing(t) // start() never ran onProcessStarted, so just tear the proxy down
            throw t
        }
        try {
            input.onProcessStarted(process) // e.g. start serving the ymix read connection
            // Drain stderr on the process's OWN scope so a chatty ffmpeg can't stall on a full pipe;
            // keep it to explain a fail-fast exit. The blocking reads run there too, so process.close()
            // (in the catch / on Source.close) unblocks them.
            val stderr = process.scope.async { process.stderr.buffer().readUtf8() }
            val out = process.stdout.buffer()
            // Block only until ffmpeg yields its first byte (or dies): no decodable image means a
            // non-zero exit with no output, so request(1) returns false right away → fail fast.
            val hasData = process.scope.async { out.request(1) }.await()
            if (!hasData) {
                val exitCode = process.exitCode.await()
                throw onNoOutput(stderr.await().trim(), exitCode)
            }
            ProcessBoundSource(out, process, input)
        } catch (t: Throwable) {
            process.close(t)
            input.closeSuppressing(t)
            throw t
        }
    }
}

/** A [Source] that owns the ffmpeg [process] and its [input] proxy: [close] tears both down. */
private class ProcessBoundSource(
    private val source: Source,
    private val process: KmpProcess,
    private val input: FfInput,
) : Source by source {
    override fun close() {
        try {
            source.close()
        } catch (t: Throwable) {
            process.close()
            input.closeSuppressing(t)
            throw t
        }
        process.close()
        input.close()
    }
}

private fun FfInput.closeSuppressing(primary: Throwable) {
    try {
        close()
    } catch (closeError: Throwable) {
        primary.addSuppressed(closeError)
    }
}

/** Thrown by [FfmpegMetadataGetter.getAttachedPic] when the input has no attached picture. */
class FfmpegAttachedPicException(val stderr: String, val exitCode: Int) :
    Exception("ffmpeg produced no attached picture (exit $exitCode)\n$stderr")

/** Thrown by [FfmpegMetadataGetter.extractThumbnail] when ffmpeg decoded no frame from the input. */
class FfmpegThumbnailException(val stderr: String, val exitCode: Int) :
    Exception("ffmpeg produced no thumbnail frame (exit $exitCode)\n$stderr")
