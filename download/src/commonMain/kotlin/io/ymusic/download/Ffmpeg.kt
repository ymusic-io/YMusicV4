package io.ymusic.download

import kmpffmpeg.KmpProcessBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.IOException
import okio.buffer
import projectcommon.error.DiskFullException
import projectcommon.error.TechnicalException

/** One subtitle track to mux into a video container: its local [path], BCP-47/ISO-639-1 [language]
 *  (e.g. `en`, `pt-BR`) and an optional display [title]. */
class SubtitleInput(val path: String, val language: String, val title: String?)

/** One `-progress` line → the output time in microseconds, or null for any other key. ffmpeg emits a
 *  block of `key=value` lines per update; `out_time_us` is the reliable elapsed-output-time field. */
internal fun parseFfmpegProgressUs(line: String): Long? {
    val value = line.substringAfter("out_time_us=", missingDelimiterValue = "")
    if (value.isEmpty()) return null
    return value.trim().toLongOrNull()
}

/**
 * Thin async wrapper over [KmpProcessBuilder] for the operations the downloader needs — transcode a
 * best-audio stream to MP3, remux to M4A, and mux separate audio+video elementary streams (plus any
 * subtitle tracks) into one container — each optionally tagging the output with container metadata
 * (`-metadata k=v`). There is no ffmpeg DSL in the repo; you assemble argv and await the exit code. File
 * I/O uses `file:` specs (no resolver needed for plain files); ffmpeg does not percent-decode, so we pass
 * the raw path.
 *
 * Absent on iOS: `KmpProcessBuilder.start()` throws on Apple no-process targets, so an [Ffmpeg] is only
 * constructed by shells that ship a binary (Android's `libffmpeg.so`, desktop's prebuilt). The service
 * treats a null [Ffmpeg] as "ffmpeg unavailable" and restricts itself to no-transcode outputs.
 */
class Ffmpeg(
    private val binaryPath: String,
    /** The child inherits NO parent env (the process backend clears it). Packed ffmpeg finds libav
     *  via rpath; leave this empty. */
    private val env: Map<String, String> = emptyMap(),
) {

    // The output container is named explicitly with `-f` so it doesn't depend on the output filename's
    // extension — scratch/publish names (and content:// targets) may have none.

    /** Any audio (opus/aac/ec-3) → mp3 via libmp3lame with [encoding], tagged ID3v2.3 — the version
     *  [Id3Lyrics] extends and the one most players read. A JPEG [cover] becomes the APIC picture. */
    suspend fun toMp3(
        input: String,
        output: String,
        encoding: Mp3Encoding,
        cover: String? = null,
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(mp3Args(input, output, encoding, cover, metadata), onProgressUs)

    /** AAC audio → m4a, stream-copied (+ any [captions], as mov_text). M4A is an ISO-BMFF container, so it
     *  carries subtitle tracks like MP4. A JPEG [cover] becomes the `covr` artwork. */
    suspend fun toM4a(
        input: String,
        output: String,
        captions: List<SubtitleInput> = emptyList(),
        cover: String? = null,
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(m4aArgs(input, output, captions, metadata, cover), onProgressUs)

    /** Opus → Ogg, stream-copied. Cover and lyrics are added afterward ([OggOpusTags]). */
    suspend fun toOpus(
        input: String,
        output: String,
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(opusArgs(input, output, metadata), onProgressUs)

    /** Mux VP9/AV1 video + Opus audio, both stream-copied (+ any [captions], as WebVTT), into one WebM. */
    suspend fun muxWebm(
        video: String,
        audio: String,
        output: String,
        captions: List<SubtitleInput> = emptyList(),
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(webmMuxArgs(video, audio, output, captions, metadata), onProgressUs)

    /** Mux video + AAC audio, both stream-copied (+ any [captions], as mov_text), into one MP4. */
    suspend fun muxMp4(
        video: String,
        audio: String,
        output: String,
        captions: List<SubtitleInput> = emptyList(),
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(mp4MuxArgs(video, audio, output, captions, metadata), onProgressUs)

    /**
     * Run ffmpeg with the given args (a `-hide_banner -loglevel error -y` prefix is added). Throws on
     * non-zero exit with the captured stderr for diagnostics. When [onProgressUs] is set, `-progress
     * pipe:1` is added and each progress tick's output time (microseconds) is delivered to it.
     */
    suspend fun run(args: List<String>, onProgressUs: ((Long) -> Unit)? = null) {
        val builder = KmpProcessBuilder(binaryPath)
            .appendArg("-hide_banner", "-loglevel", "error", "-y")
        if (onProgressUs != null) builder.appendArg("-progress", "pipe:1", "-nostats")
        builder.appendArgs(args).setEnv(env)
        if (onProgressUs == null) builder.discardStdout()
        val process = builder.start()
        val stderr = process.scope.async { process.stderr.buffer().readUtf8() }
        val progress = onProgressUs?.let { cb ->
            process.scope.async {
                val source = process.stdout.buffer()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    parseFfmpegProgressUs(line)?.let(cb)
                }
            }
        }
        val code = process.exitCode.await()
        val progressError = collectorFailure { progress?.await() }
        // The E70021 diagnostic below IS this text — a failed collector must say so, not report "".
        var stderrText = ""
        val stderrError = collectorFailure { stderrText = stderr.await() }
        val log = if (stderrError != null) "stderr collector failed: $stderrError" else stderrText
        process.close()
        if (code != 0) {
            val detail = "ffmpeg exited $code:\n$log"
            val failure = if ("No space left on device" in log) {
                DiskFullException(IOException(detail))
            } else {
                TechnicalException("E70021", detail)
            }
            progressError?.let(failure::addSuppressed)
            stderrError?.let(failure::addSuppressed)
            throw failure
        }
    }

    private suspend inline fun collectorFailure(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        currentCoroutineContext().ensureActive()
        error
    }
}

// --- pure argv builders (unit-tested; ffmpeg not invoked) ------------------------------------------

/** best audio → mp3 (libmp3lame with [encoding], ID3v2.3), with an optional JPEG [cover] as the APIC picture. */
internal fun mp3Args(
    audio: String,
    output: String,
    encoding: Mp3Encoding,
    cover: String?,
    metadata: List<Pair<String, String>>,
): List<String> = buildList {
    add("-i"); add(audio.ffSpec())
    if (cover != null) addAll(coverInputArgs(cover))
    add("-map"); add("0:a:0")
    if (cover != null) addAll(coverOutputArgs(inputIndex = 1))
    add("-c:a"); add("libmp3lame")
    when (encoding) {
        Mp3Encoding.ABR_128 -> { add("-abr"); add("1"); add("-b:a"); add("128k") }
        Mp3Encoding.ABR_320 -> { add("-abr"); add("1"); add("-b:a"); add("320k") }
        Mp3Encoding.VBR_V0 -> { add("-q:a"); add("0") }
    }
    addAll(metaArgs(metadata))
    add("-id3v2_version"); add("3")
    add("-f"); add("mp3"); add(output.ffSpec())
}

/** AAC audio (+ subtitles as mov_text) → m4a via the `ipod` muxer, audio stream-copied. */
internal fun m4aArgs(
    audio: String,
    output: String,
    captions: List<SubtitleInput>,
    metadata: List<Pair<String, String>>,
    cover: String? = null,
): List<String> = buildList {
    add("-i"); add(audio.ffSpec())
    captions.forEach { add("-i"); add(it.path.ffSpec()) }
    if (cover != null) addAll(coverInputArgs(cover))
    add("-map"); add("0:a:0")
    captions.indices.forEach { add("-map"); add("${it + 1}:0") } // subtitle inputs start at index 1
    if (cover != null) addAll(coverOutputArgs(inputIndex = captions.size + 1))
    add("-c:a"); add("copy")
    if (captions.isNotEmpty()) { add("-c:s"); add("mov_text") }
    addAll(subtitleMetaArgs(captions))
    addAll(metaArgs(metadata))
    add("-f"); add("ipod"); add(output.ffSpec()) // ipod = the m4a muxer
}

internal fun opusArgs(
    audio: String,
    output: String,
    metadata: List<Pair<String, String>>,
): List<String> = buildList {
    add("-i"); add(audio.ffSpec())
    add("-map"); add("0:a:0")
    add("-c:a"); add("copy")
    addAll(metaArgs(metadata))
    add("-f"); add("opus"); add(output.ffSpec())
}

/** Mux video + AAC audio (+subtitles as mov_text) into an MP4, both stream-copied. */
internal fun mp4MuxArgs(
    video: String,
    audio: String,
    output: String,
    captions: List<SubtitleInput>,
    metadata: List<Pair<String, String>>,
): List<String> = buildList {
    add("-i"); add(video.ffSpec())
    add("-i"); add(audio.ffSpec())
    captions.forEach { add("-i"); add(it.path.ffSpec()) }
    add("-map"); add("0:v:0")
    add("-map"); add("1:a:0")
    captions.indices.forEach { add("-map"); add("${it + 2}:0") } // subtitle inputs start at index 2
    add("-c:v"); add("copy")
    add("-c:a"); add("copy")
    if (captions.isNotEmpty()) { add("-c:s"); add("mov_text") }
    addAll(subtitleMetaArgs(captions))
    add("-movflags"); add("+faststart")
    addAll(metaArgs(metadata))
    add("-f"); add("mp4"); add(output.ffSpec())
}

/** Mux video + Opus audio (+subtitles as WebVTT) into a WebM, both stream-copied. */
internal fun webmMuxArgs(
    video: String,
    audio: String,
    output: String,
    captions: List<SubtitleInput>,
    metadata: List<Pair<String, String>>,
): List<String> = buildList {
    add("-i"); add(video.ffSpec())
    add("-i"); add(audio.ffSpec())
    captions.forEach { add("-i"); add(it.path.ffSpec()) }
    add("-map"); add("0:v:0")
    add("-map"); add("1:a:0")
    captions.indices.forEach { add("-map"); add("${it + 2}:0") }
    add("-c:v"); add("copy")
    add("-c:a"); add("copy")
    if (captions.isNotEmpty()) { add("-c:s"); add("webvtt") }
    addAll(subtitleMetaArgs(captions))
    addAll(metaArgs(metadata))
    add("-f"); add("webm"); add(output.ffSpec())
}

/** The cover file is an extension-less scratch, so name its demuxer and codec instead of probing. */
private fun coverInputArgs(cover: String): List<String> =
    listOf("-f", "image2", "-c:v", "mjpeg", "-i", cover.ffSpec())

/** Map input [inputIndex]'s picture as the output's only video stream, stream-copied as attached art. */
private fun coverOutputArgs(inputIndex: Int): List<String> = listOf(
    "-map", "$inputIndex:v:0", "-c:v", "copy", "-disposition:v:0", "attached_pic",
    "-metadata:s:v:0", "title=Album cover", "-metadata:s:v:0", "comment=Cover (Front)",
)

/** Per-subtitle-stream `language`/`title` tags (`-metadata:s:s:<n> …`), language mapped to ISO-639-2. */
private fun subtitleMetaArgs(captions: List<SubtitleInput>): List<String> = buildList {
    captions.forEachIndexed { i, cc ->
        add("-metadata:s:s:$i"); add("language=${iso639_2(cc.language)}")
        cc.title?.takeIf { it.isNotBlank() }?.let { add("-metadata:s:s:$i"); add("title=$it") }
    }
}

/** `-metadata k=v` per non-blank tag; applied to the output container. */
private fun metaArgs(metadata: List<Pair<String, String>>): List<String> =
    metadata.filter { it.second.isNotBlank() }.flatMap { (k, v) -> listOf("-metadata", "$k=$v") }

/** ISO-639-1 (YouTube's 2-letter caption code) → ISO-639-2 3-letter, which container language atoms
 *  expect; the base language is used for regional variants (`pt-BR` → `por`), unknowns pass through. */
internal fun iso639_2(code: String): String {
    val base = code.substringBefore('-').lowercase()
    return ISO_639_2[base] ?: base
}

private val ISO_639_2: Map<String, String> = mapOf(
    "en" to "eng", "es" to "spa", "pt" to "por", "fr" to "fre", "de" to "ger", "it" to "ita",
    "nl" to "dut", "ru" to "rus", "uk" to "ukr", "pl" to "pol", "cs" to "cze", "ro" to "rum",
    "el" to "gre", "tr" to "tur", "ar" to "ara", "he" to "heb", "fa" to "per", "hi" to "hin",
    "bn" to "ben", "ta" to "tam", "te" to "tel", "ur" to "urd", "th" to "tha", "vi" to "vie",
    "id" to "ind", "ms" to "may", "ja" to "jpn", "ko" to "kor", "zh" to "chi", "sv" to "swe",
    "da" to "dan", "nb" to "nob", "fi" to "fin", "hu" to "hun", "sk" to "slo", "bg" to "bul",
    "hr" to "hrv", "sr" to "srp", "sl" to "slv", "et" to "est", "lv" to "lav", "lt" to "lit",
    "ca" to "cat", "sw" to "swa", "af" to "afr",
)

private fun String.ffSpec(): String = "file:$this"
