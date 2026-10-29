package io.ymusic.mse

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import kmpcommon.WorkerThread
import kmpcommon.log.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The device's MediaCodec video decoders as the MSE host's decoder table (`mse_caps.h`), which the
 * player's `navigator.mediaCapabilities` and type checks answer from. One MediaCodecList walk per
 * process, on its own thread; [install] hands the rows to C once, before a player boots.
 */
object MseDecoderCaps {
    private val started = AtomicBoolean()
    private val installed = AtomicBoolean()
    private val probed = CompletableDeferred<IntArray?>()

    /** Starts the probe on its own thread, once per process. Any thread; never blocks. */
    fun start(logger: Logger?) {
        if (!started.compareAndSet(false, true)) return
        val log = logger?.fork("mse-caps")
        try {
            thread(name = "mse-caps", isDaemon = true) {
                var rows: IntArray? = null
                try {
                    rows = probe(log)
                } finally {
                    probed.complete(rows)
                }
            }
        } catch (e: OutOfMemoryError) {
            log?.log(Logger.Level.WARN, "no thread for the MediaCodecList probe, no decoder table", e)
            probed.complete(null)
        }
    }

    /**
     * Fills the decoder table from the probe (started here if [start] was not called), waiting up to
     * [timeoutMs] for it. Past that, or when the probe failed, the table stays empty and the player
     * behaves as without one. The table outlives every MSE host, so only the first call sets it.
     */
    suspend fun install(timeoutMs: Long, logger: Logger?) {
        if (installed.get()) return
        start(logger)
        val rows = withTimeoutOrNull(timeoutMs) { probed.await() }
        if (rows == null) {
            if (!probed.isCompleted) {
                logger?.fork("mse-caps")?.log(
                    Logger.Level.INFO,
                    "MediaCodecList probe still running after $timeoutMs ms, this player starts without a decoder table",
                )
            }
            return
        }
        if (installed.compareAndSet(false, true)) MseHostNative.setDecoderCaps(rows)
    }

    /** `mse_caps_set_rows` ints, or null (logged) when there is nothing to fill the table with. */
    @WorkerThread
    internal fun probe(log: Logger?): IntArray? {
        val t0 = SystemClock.elapsedRealtime()
        val decoders = try {
            listDecoders(log)
        } catch (e: RuntimeException) {
            log?.log(Logger.Level.WARN, "MediaCodecList probe failed, no decoder table", e)
            return null
        }
        val trustPps = performancePointsTrusted(decoders, log)
        val rows = ArrayList<CapRow>()
        val notes = ArrayList<String>()
        for (d in decoders) {
            val kind = if (d.hw) "hw" else "sw"
            if (!d.used) {
                notes += "${d.name} $kind unused"
                continue
            }
            val source = try {
                decoderRows(d, trustPps, rows)
            } catch (e: RuntimeException) {
                log?.log(Logger.Level.WARN, "MediaCodecList probe: ${d.name} ${d.mime} skipped", e)
                continue
            }
            notes += "${d.name} $kind $source"
        }
        val table = rows.filterIndexed { i, r ->
            rows.withIndex().none { (j, o) -> j != i && o.covers(r) && (!r.covers(o) || j < i) }
        }.sortedWith(compareByDescending<CapRow> { it.hw }.thenBy { it.codec })
        log?.log(
            Logger.Level.INFO,
            "MediaCodecList probe ${SystemClock.elapsedRealtime() - t0} ms: ${table.size} rows from " +
                notes.joinToString(", ").ifEmpty { "no video decoders" },
        )
        if (table.isEmpty()) return null
        val out = IntArray(table.size * ROW_INTS)
        table.forEachIndexed { i, r -> r.writeTo(out, i * ROW_INTS) }
        return out
    }
}

private const val ROW_INTS = 7
private const val H264 = 1
private const val HEVC = 2
private const val VP8 = 3
private const val VP9 = 4
private const val AV1 = 5
private const val C420 = 1
private const val C422 = 2
private const val C444 = 3

private val CODECS = mapOf(
    "video/avc" to H264,
    "video/hevc" to HEVC,
    "video/x-vnd.on2.vp8" to VP8,
    "video/x-vnd.on2.vp9" to VP9,
    "video/av01" to AV1,
)

/** Landscape; the table takes each in either orientation. */
private val SIZES = listOf(
    7680 to 4320, 3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480, 640 to 360,
    426 to 240, 256 to 144,
)

private val RATES = intArrayOf(120, 60, 50, 48, 30, 25, 24, 15, 10, 5)

private class CapRow(
    val codec: Int,
    val bits: Int,
    val chroma: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hw: Boolean,
) {
    private val pixelRate get() = width.toDouble() * height * fps

    fun covers(o: CapRow): Boolean = codec == o.codec && (hw || !o.hw) && bits >= o.bits && chroma >= o.chroma &&
        width >= o.width && height >= o.height && pixelRate >= o.pixelRate

    fun writeTo(out: IntArray, at: Int) {
        out[at] = codec
        out[at + 1] = bits
        out[at + 2] = chroma
        out[at + 3] = width
        out[at + 4] = height
        out[at + 5] = fps
        out[at + 6] = if (hw) 1 else 0
    }
}

private class Decoder(
    val name: String,
    val mime: String,
    val codec: Int,
    val caps: CodecCapabilities,
    val hw: Boolean,
    val used: Boolean,
)

/**
 * The MSE path opens `createDecoderByType`, i.e. the first decoder listed for the type, and falls
 * back to ffmpeg when that one refuses a stream: a hardware decoder listed after another one is never
 * used. Software decoders stay in as the measure of what the CPU sustains.
 */
@WorkerThread
private fun listDecoders(log: Logger?): List<Decoder> {
    val first = HashMap<String, String>()
    val out = ArrayList<Decoder>()
    for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
        if (info.isEncoder) continue
        for (type in info.supportedTypes) {
            val mime = type.lowercase(Locale.ROOT)
            val codec = CODECS[mime] ?: continue
            val caps = try {
                info.getCapabilitiesForType(type)
            } catch (e: IllegalArgumentException) {
                log?.log(Logger.Level.WARN, "MediaCodecList probe: ${info.name} $type has no capabilities", e)
                continue
            }
            if (info.name.lowercase(Locale.ROOT).endsWith(".secure") ||
                caps.isFeatureRequired(CodecCapabilities.FEATURE_SecurePlayback) ||
                caps.isFeatureRequired(CodecCapabilities.FEATURE_TunneledPlayback)
            ) {
                continue
            }
            val canonical = canonicalName(info)
            val picked = first.getOrPut(mime) { canonical } == canonical
            if (isAlias(info)) continue
            val hw = isHardware(info)
            out += Decoder(info.name, mime, codec, caps, hw, used = picked || !hw)
        }
    }
    return out
}

private fun canonicalName(info: MediaCodecInfo): String =
    if (Build.VERSION.SDK_INT >= 29) info.canonicalName else info.name

private fun isAlias(info: MediaCodecInfo): Boolean = Build.VERSION.SDK_INT >= 29 && info.isAlias

/** Before API 29 by name, with ExoPlayer's software-only rules. */
private fun isHardware(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
    val n = info.name.lowercase(Locale.ROOT)
    if (n.startsWith("arc.")) return true
    val software = n.startsWith("omx.google.") || n.startsWith("omx.ffmpeg.") || n.startsWith("c2.android.") ||
        n.startsWith("c2.google.") || n.contains(".sw.") || n.endsWith(".sw") || n.contains("swvdec") ||
        n.contains("sw-based") || (!n.startsWith("omx.") && !n.startsWith("c2."))
    return !software
}

/**
 * Below API 35 some devices under-report performance points; like ExoPlayer, trust them only when the
 * first H.264 decoder that lists any covers the CDD's 1280x720@60.
 */
private fun performancePointsTrusted(decoders: List<Decoder>, log: Logger?): Boolean {
    if (Build.VERSION.SDK_INT >= 35) return true
    if (Build.VERSION.SDK_INT < 29) return false
    for (d in decoders) {
        if (d.codec != H264 || !d.used) continue
        val pps = d.caps.videoCapabilities?.supportedPerformancePoints
        if (pps.isNullOrEmpty()) continue
        val ok = pps.any { it.covers(PerformancePoint(1280, 720, 60)) }
        if (!ok) log?.log(Logger.Level.INFO, "performance points ignored: ${d.name} does not list 1280x720@60")
        return ok
    }
    return true
}

/**
 * Adds [d]'s rows to [rows]: per bit depth / chroma pair its profiles reach, the largest size it
 * takes and each smaller one that sustains a higher pixel rate. Returns where the frame rates came
 * from: performance points, the vendor's measured rates (their lower bound), or the declared limits.
 */
private fun decoderRows(d: Decoder, trustPps: Boolean, rows: MutableList<CapRow>): String {
    val vc = d.caps.videoCapabilities ?: return "no video capabilities"
    val pps = if (trustPps && Build.VERSION.SDK_INT >= 29) {
        vc.supportedPerformancePoints?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    val sized = ArrayList<IntArray>()
    var best = 0.0
    var source = if (pps != null) "pp" else "declared"
    for ((w, h) in SIZES) {
        if (!vc.isSizeSupported(w, h)) continue
        val measured = if (pps == null) vc.getAchievableFrameRatesFor(w, h) else null
        if (measured != null && sized.isEmpty()) source = "measured"
        val fps = RATES.firstOrNull { fps ->
            when {
                pps != null -> pps.any { it.covers(PerformancePoint(w, h, fps)) }
                measured != null -> fps <= measured.lower && vc.areSizeAndRateSupported(w, h, fps.toDouble())
                else -> vc.areSizeAndRateSupported(w, h, fps.toDouble())
            }
        } ?: 1
        val rate = w.toDouble() * h * fps
        if (rate > best) {
            sized += intArrayOf(w, h, fps)
            best = rate
        }
    }
    for ((bits, chroma) in formats(d.codec, d.caps.profileLevels)) {
        for (s in sized) rows += CapRow(d.codec, bits, chroma, s[0], s[1], s[2], d.hw)
    }
    return source
}

/** The bit depth / chroma pairs the profiles reach, none covered by another. */
private fun formats(codec: Int, levels: Array<CodecProfileLevel>?): List<Pair<Int, Int>> {
    val all = levels.orEmpty().map { format(codec, it.profile) }.toSet().ifEmpty { setOf(8 to C420) }
    return all.filter { f -> all.none { o -> o != f && o.first >= f.first && o.second >= f.second } }
}

private fun format(codec: Int, profile: Int): Pair<Int, Int> = when (codec) {
    H264 -> when (profile) {
        CodecProfileLevel.AVCProfileHigh10 -> 10 to C420
        CodecProfileLevel.AVCProfileHigh422 -> 10 to C422
        CodecProfileLevel.AVCProfileHigh444 -> 10 to C444
        else -> 8 to C420
    }
    HEVC -> when (profile) {
        CodecProfileLevel.HEVCProfileMain10,
        CodecProfileLevel.HEVCProfileMain10HDR10,
        CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        -> 10 to C420
        else -> 8 to C420
    }
    VP9 -> when (profile) {
        CodecProfileLevel.VP9Profile1 -> 8 to C444
        CodecProfileLevel.VP9Profile2,
        CodecProfileLevel.VP9Profile2HDR,
        CodecProfileLevel.VP9Profile2HDR10Plus,
        -> 10 to C420
        CodecProfileLevel.VP9Profile3,
        CodecProfileLevel.VP9Profile3HDR,
        CodecProfileLevel.VP9Profile3HDR10Plus,
        -> 10 to C444
        else -> 8 to C420
    }
    AV1 -> when (profile) {
        CodecProfileLevel.AV1ProfileMain10,
        CodecProfileLevel.AV1ProfileMain10HDR10,
        CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        -> 10 to C420
        else -> 8 to C420
    }
    else -> 8 to C420
}
