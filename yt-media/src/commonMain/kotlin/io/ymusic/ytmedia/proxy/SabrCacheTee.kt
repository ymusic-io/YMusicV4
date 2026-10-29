package io.ymusic.ytmedia.proxy

import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SegmentFill
import io.ymusic.ytmedia.cache.isMediaCacheStorageFault
import io.ymusic.ytmedia.proto.ProtoFieldFixed32
import io.ymusic.ytmedia.proto.ProtoFieldFixed64
import io.ymusic.ytmedia.proto.ProtoFieldLen
import io.ymusic.ytmedia.proto.ProtoFieldVarInt
import io.ymusic.ytmedia.proto.ProtoReader
import io.ymusic.ytmedia.proto.message
import io.ymusic.ytmedia.sabr.FormatId
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.NextRequestPolicy
import io.ymusic.ytmedia.sabr.SabrParts
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.readUmpPartHeader
import kmpcommon.io.AsyncSource
import kmpcommon.log.Logger
import kmpcommon.log.debug
import okio.Buffer
import okio.EOFException

/**
 * Tees a SABR `/videoplayback` UMP response into the player cache **while passing the raw bytes through
 * to the WebView untouched** — the fill-on-forward counterpart of [CachingSource] for SABR (mirrors
 * legacy `SabrCacheInputStream.cacheResponseBody`: the player gets the exact network bytes, a copy is
 * UMP-parsed to fill the cache; only complete segments commit).
 *
 * The response is a stream of UMP parts (`varint partId`, `varint size`, payload). A segment is
 * `MEDIA_HEADER` (its [MediaHeader] proto) → one or more `MEDIA_DATA` parts (each `1 headerId byte` +
 * media bytes) → `MEDIA_END` (`1 headerId byte`). On `MEDIA_HEADER` we open a [SegmentFill] at the
 * segment's `[startRange, startRange+contentLength)`, stream each `MEDIA_DATA` payload into it, and
 * `commit()` on `MEDIA_END`; a segment left open when the stream ends (truncated) is `abort()`ed so no
 * partial span survives (the [MediaBlobCache] writer no-ops a segment already cached / being written).
 *
 * The parser runs incrementally off a *copy* of each read's bytes, so pass-through is byte-exact and
 * never reframed. A parse error, a cache-write failure, or an inconsistent-framing check flips
 * [illegalState] — open fills are aborted and caching is disabled for the rest of this response — but the
 * pass-through is left intact: a cache miss is always safe, caching corrupt bytes or corrupting the
 * player's stream is not.
 */
internal class SabrCacheTee(
    private val network: AsyncSource,
    private val cache: MediaCache,
    logger: Logger? = null,
    private val verbose: Boolean = false, // per-part chatter; WARN events (errors, protection rejects) always log
    private val onStorageFault: (Throwable) -> Unit = {},
) : AsyncSource {

    private val logger = logger?.fork("sabr-fill")

    /** Emit a DEBUG line only in [verbose] mode; the message is built lazily so it's free when off. */
    private inline fun logVerbose(message: () -> String) {
        if (verbose) logger?.debug(message)
    }

    /** A segment in this response: its writer (null when not mirrored), the media bytes this response
     *  carries ([length]), and how many of them are still owed. */
    private class OpenSeg(val header: MediaHeader, val fill: SegmentFill?, val length: Long) {
        var remaining = length
    }

    private val scan = Buffer() // undispatched bytes (at most a part header + one MEDIA_HEADER payload)
    private val open = mutableMapOf<Int, OpenSeg>()

    private var inPayload = false
    private var partId = 0
    private var partRemaining = 0L
    private var headerBuf: Buffer? = null // accumulates a MEDIA_HEADER's proto until complete
    private var dataNeedId = false // next MEDIA_DATA byte is the 1-byte headerId, not media
    private var curSeg: OpenSeg? = null // the OpenSeg the current MEDIA_DATA feeds
    private var fimBuf: Buffer? = null // accumulates a FORMAT_INITIALIZATION_METADATA payload — always (stored for hit replay)
    private var ctrlBuf: Buffer? = null // accumulates a log-only control part's payload (only when logging)

    private var illegalState = false // corrupt/inconsistent stream or a cache-write error — stop caching
    private var finished = false
    private var sealedSegments = 0 // whole segments committed this response — for the fill-summary log
    private var sealedBytes = 0L

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        val n = network.read(sink, byteCount)
        if (n == -1L) {
            finish() // clean EOF: any still-open segment is truncated → drop it
            return -1L
        }
        if (!illegalState) {
            val copy = Buffer()
            sink.copyTo(copy, sink.size - n, n) // the bytes just appended to sink
            try {
                scan.writeAll(copy)
                dispatch()
            } catch (t: Throwable) {
                // A thrown parse (bad varint / proto) or cache write (disk / index) — never trust the rest.
                markIllegal("parse or cache-write error: ${t.message}", t)
                if (t.isMediaCacheStorageFault()) onStorageFault(t)
            }
        }
        return n
    }

    override fun close() {
        network.close()
        finish()
    }

    /** Consume as many complete UMP structures as [scan] holds, opening/filling/committing segments. All
     *  blocking (okio writes, `@WorkerThread` commit) — non-suspend; only [read] suspends, on `network.read`. */
    private fun dispatch() {
        while (!illegalState) {
            if (!inPayload) {
                // A part header is (varint partId, varint size). Probe a peek so a straddling header
                // is left in [scan] for the next read instead of being half-consumed.
                val probe = scan.peek()
                val header = try {
                    probe.readUmpPartHeader()
                } catch (e: EOFException) {
                    return // full header not here yet
                }
                scan.readUmpPartHeader() // enough bytes present → consume for real
                partId = header.partId
                partRemaining = header.size
                inPayload = true
                logVerbose { "part ${partName(partId)}($partId) size=$partRemaining" }
                startPart()
            }
            if (illegalState || !consumePayload()) return // startPart may have tripped illegalState
        }
    }

    private fun startPart() {
        when (partId) {
            UmpPart.MEDIA_HEADER -> headerBuf = Buffer()
            UmpPart.MEDIA_DATA -> {
                // Each MEDIA_DATA re-declares its target (a leading headerId) and is self-contained; the
                // previous data part clears [curSeg] when it ends ([endPart]). A non-null one here means a
                // MEDIA_DATA began while another was unfinished — framing lost, don't trust the bytes.
                if (curSeg != null) return markIllegal("MEDIA_DATA began with an unfinished segment")
                dataNeedId = true
            }
            // Format-init is captured always — its own buffer — it's stored and replayed in synthesized hits
            // (the player needs it to (re)initialize a format on a switch).
            UmpPart.FORMAT_INITIALIZATION_METADATA -> fimBuf = Buffer()
            // Buffer log-only control-part payloads to decode for logs. Protection/error can WARN even when
            // not verbose, so buffer them whenever a logger is set; the verbose-only parts only when verbose.
            UmpPart.STREAM_PROTECTION_STATUS, UmpPart.SABR_ERROR -> if (logger != null) ctrlBuf = Buffer()
            UmpPart.NEXT_REQUEST_POLICY, UmpPart.SABR_REDIRECT -> if (verbose) ctrlBuf = Buffer()
            // MEDIA_END / other control parts need no per-start state.
        }
    }

    /** Feed available payload bytes for the current part; returns false when it needs more bytes. */
    private fun consumePayload(): Boolean {
        if (partRemaining == 0L) return endPart()
        if (scan.size == 0L) return false
        when (partId) {
            UmpPart.MEDIA_HEADER -> {
                val take = minOf(partRemaining, scan.size)
                headerBuf!!.write(scan, take)
                partRemaining -= take
            }

            UmpPart.MEDIA_DATA -> {
                if (dataNeedId) {
                    val id = scan.readByte().toInt() and 0xFF // headerId — NOT a media byte
                    partRemaining -= 1
                    dataNeedId = false
                    curSeg = open[id]
                    // Every MEDIA_HEADER opens an entry, so an unknown id means its header never arrived or
                    // its MEDIA_END already closed it (data-after-end) — either way the framing is lost.
                    if (curSeg == null) {
                        markIllegal("MEDIA_DATA for unopened id $id")
                        return false
                    }
                } else {
                    val seg = curSeg!! // set above; an unknown id already tripped illegalState
                    // This part still owes [partRemaining] media bytes and the segment expects [seg.remaining]
                    // more; more in the part than the segment can take overruns the declared contentLength —
                    // the mirror image of a short MEDIA_END, and just as corrupt.
                    if (partRemaining > seg.remaining) {
                        markIllegal("MEDIA_DATA overruns id: $partRemaining owed vs ${seg.remaining} expected")
                        return false
                    }
                    val take = minOf(partRemaining, scan.size)
                    val fill = seg.fill
                    if (fill != null) fill.sink.write(scan, take) else scan.skip(take)
                    seg.remaining -= take
                    partRemaining -= take
                }
            }

            UmpPart.MEDIA_END -> {
                // A MEDIA_END is exactly its 1-byte headerId; any other size is malformed framing (and would
                // otherwise re-read the trailing bytes as further "ids").
                if (partRemaining != 1L) {
                    markIllegal("MEDIA_END payload is $partRemaining bytes, expected 1")
                    return false
                }
                val id = scan.readByte().toInt() and 0xFF
                partRemaining -= 1
                val seg = open.remove(id)
                if (seg == null) {
                    // No open segment for this id: its MEDIA_HEADER never arrived, or this is a second
                    // MEDIA_END closing it again (double-close) — either way the framing is lost.
                    markIllegal("MEDIA_END for unopened id $id")
                    return false
                }
                if (seg.remaining > 0L) {
                    // Short of the declared contentLength: a wrong-length span would desync the index, so drop
                    // it and stop caching rather than commit a truncated segment.
                    seg.fill?.abort()
                    markIllegal("MEDIA_END for id $id short by ${seg.remaining} of ${seg.length} bytes (${seg.header.describe()})")
                    return false
                }
                seg.fill?.let { fill ->
                    // A throw here propagates to read()'s catch → markIllegal.
                    val failure = fill.commit()
                    if (failure != null) {
                        markIllegal(
                            "segment failed its structure check, not cached (${seg.header.describe()}) " +
                                failure.describe(seg.header.startRange),
                        )
                        return false
                    }
                    sealedSegments++
                    sealedBytes += seg.length
                }
            }

            else -> {
                val take = minOf(partRemaining, scan.size)
                // FIM always accumulates (fimBuf); log-only parts only when buffered (ctrlBuf may be null → skip).
                val buf = if (partId == UmpPart.FORMAT_INITIALIZATION_METADATA) fimBuf else ctrlBuf
                if (buf != null) buf.write(scan, take) else scan.skip(take)
                partRemaining -= take
            }
        }
        if (partRemaining == 0L) return endPart()
        return true
    }

    /** Finish the current part (parse a completed MEDIA_HEADER → open a fill) and rearm for the next. */
    private fun endPart(): Boolean {
        when (partId) {
            UmpPart.MEDIA_HEADER -> {
                val bytes = headerBuf!!.readByteArray()
                headerBuf = null
                // A malformed header proto throws here → read()'s catch flips illegalState (don't cache a
                // segment whose byte range we can't trust).
                val fields = ProtoReader(bytes).readAllFields()
                val header = MediaHeader.from(fields)
                if (open.containsKey(header.headerId)) {
                    // A second live segment on the same id before the first's MEDIA_END — lost framing.
                    markIllegal("duplicate MEDIA_HEADER id ${header.headerId}, still open")
                } else if (header.dataOffset != 0L && header.dataOffset !in 0 until header.contentLength) {
                    markIllegal("MEDIA_HEADER data offset outside its segment (${header.describe()})")
                } else {
                    logVerbose {
                        "media-header vid=${header.videoId} itag=${header.itag} seg=${header.segmentIndex} " +
                            "range=${header.startRange}+${header.contentLength} t=${header.startMs}+${header.durationMs}ms"
                    }
                    // DIAGNOSTIC: a media segment with no start_ms can't be time-looked-up on serve (cache
                    // never hits); fields past 15 are ones the parser may not know. Dump the raw fields.
                    if (header.segmentIndex != 0 && header.startMs == null || fields.any { it.fieldNumber > 15 }) {
                        logVerbose { "  ^ MEDIA_HEADER raw fields: ${rawFields(bytes)}" }
                    }
                    open[header.headerId] = if (header.dataOffset > 0) {
                        // Only the tail arrives: caching it would leave the segment a hole, and a later full
                        // fetch rewrites it anyway.
                        logger?.log(Logger.Level.WARN, "resumed segment, not cached (${header.describe()})")
                        OpenSeg(header, fill = null, length = header.contentLength - header.dataOffset)
                    } else {
                        // Row saved now, span sealed on MEDIA_END (writer no-ops if already cached/in-flight).
                        OpenSeg(header, cache.openSegmentFill(header), header.contentLength)
                    }
                }
            }
            // A data part is self-contained; clear the pointer so the next MEDIA_DATA starts from null
            // (the invariant [startPart] checks). Robust to a segment's media spanning several parts and to
            // interleaved segments — each part re-derives its target from its own headerId.
            UmpPart.MEDIA_DATA -> curSeg = null
            // A part we buffered: format-init is stored for hit replay, control parts are logged.
            UmpPart.FORMAT_INITIALIZATION_METADATA -> {
                val buf = fimBuf!! // startPart unconditionally creates it for this part (cf. headerBuf)
                fimBuf = null
                val size = buf.size
                var error: Throwable? = null
                val fmt = try {
                    ProtoReader(buf.peek(), size).readAllFields().message(2)?.let { FormatId.from(it) }
                } catch (t: Throwable) {
                    error = t
                    null
                }
                if (fmt == null) {
                    markIllegal("parse FORMAT_INITIALIZATION_METADATA failed ${error?.message}", error)
                } else {
                    cache.index.putFormatInit(fmt.itag, fmt.lmt, buf.readByteArray())
                    logVerbose { "stored format-init for itag=${fmt.itag} ($size bytes)" }
                }
            }
            else -> ctrlBuf?.let { buf ->
                val bytes = buf.readByteArray()
                ctrlBuf = null
                logControlPart(bytes)
            }
        }
        inPayload = false
        return true
    }

    private fun finish() {
        if (finished) return
        finished = true
        abortOpen() // a segment still open at EOF/close is truncated (no MEDIA_END) — drop it
        if (sealedSegments > 0) {
            logVerbose { "sabr-fill sealed $sealedSegments segments ($sealedBytes bytes)" }
        }
    }

    private fun abortOpen() {
        open.values.forEach { segment ->
            runCatching { segment.fill?.abort() }
                .onFailure { logger?.log(Logger.Level.WARN, "sabr-fill abort failed", it) }
        }
        open.clear()
    }

    /** Corrupt/inconsistent stream (or a cache-write failure): drop every open fill and stop caching for the
     *  rest of this response. Pass-through to the player is untouched — only the tee gives up. Idempotent. */
    private fun markIllegal(reason: String, cause: Throwable? = null) {
        if (illegalState) return
        illegalState = true
        logger?.log(Logger.Level.WARN, "sabr-fill disabled (illegal state): $reason", cause)
        abortOpen()
    }

    /** Decode + log a buffered control part (backoff / protection status / error / redirect) — the same
     *  parts `SabrSession` acts on, surfaced here for fill-side debugging. */
    private fun logControlPart(payload: ByteArray) {
        val log = logger ?: return
        when (partId) {
            UmpPart.NEXT_REQUEST_POLICY -> logVerbose {
                val p = runCatching { NextRequestPolicy.from(ProtoReader(payload).readAllFields()) }.getOrNull()
                "next-request-policy backoff=${p?.backoffTimeMs ?: 0}ms " +
                    "readahead a=${p?.targetAudioReadaheadMs} v=${p?.targetVideoReadaheadMs}"
            }

            UmpPart.STREAM_PROTECTION_STATUS -> {
                val s = SabrParts.parseStreamProtectionStatus(payload)
                if (s == 3) {
                    log.log(Logger.Level.WARN, "stream-protection-status=3 (invalid PO token — media will stop)")
                } else {
                    logVerbose { "stream-protection-status=$s" }
                }
            }

            UmpPart.SABR_ERROR -> log.log(Logger.Level.WARN, "sabr-error: ${SabrParts.parseErrorMessage(payload)}")
            UmpPart.SABR_REDIRECT -> logVerbose { "sabr-redirect → ${SabrParts.parseRedirectUrl(payload)}" }
        }
    }

    /** DIAGNOSTIC: dump a proto's top-level fields as `num=value` (LEN fields show their sub-field nums). */
    private fun rawFields(bytes: ByteArray): String = runCatching {
        ProtoReader(bytes).readAllFields().joinToString(", ") { f ->
            when (f) {
                is ProtoFieldVarInt -> "${f.fieldNumber}=${f.asLong}"
                is ProtoFieldLen -> {
                    val sub = runCatching { f.asMessage.joinToString("+") { "${it.fieldNumber}:${it.wireType}" } }.getOrNull()
                    "${f.fieldNumber}=len[${sub ?: "${f.payload.size}b"}]"
                }
                is ProtoFieldFixed64 -> "${f.fieldNumber}=f64(${f.asLong})"
                is ProtoFieldFixed32 -> "${f.fieldNumber}=f32"
            }
        }
    }.getOrElse { "?" }

    private fun partName(id: Int): String = when (id) {
        UmpPart.MEDIA_HEADER -> "MEDIA_HEADER"
        UmpPart.MEDIA_DATA -> "MEDIA_DATA"
        UmpPart.MEDIA_END -> "MEDIA_END"
        UmpPart.NEXT_REQUEST_POLICY -> "NEXT_REQUEST_POLICY"
        UmpPart.FORMAT_INITIALIZATION_METADATA -> "FORMAT_INITIALIZATION_METADATA"
        UmpPart.SABR_REDIRECT -> "SABR_REDIRECT"
        UmpPart.SABR_ERROR -> "SABR_ERROR"
        UmpPart.SABR_CONTEXT_UPDATE -> "SABR_CONTEXT_UPDATE"
        UmpPart.STREAM_PROTECTION_STATUS -> "STREAM_PROTECTION_STATUS"
        else -> "UNKNOWN"
    }
}
