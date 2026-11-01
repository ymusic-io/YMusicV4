package io.ymusic.ytmedia.cache

import io.ymusic.ytmedia.sabr.BufferedRange
import io.ymusic.ytmedia.sabr.FormatId
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.NextRequestPolicy
import io.ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import io.ymusic.ytmedia.sabr.formatCacheKey
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.writeUmpPart
import io.ymusic.ytmedia.ump.writeUmpPartHeader
import okio.Buffer
import okio.Source
import okio.Timeout

/** What the cache can do for a SABR request (plan §3c). */
sealed class SabrServe {
    /** A synthesized UMP body of cached segments — serve it to the requester with no network.
     *  [servedRanges] is a per-format summary of the playback served (`itag@startMs-endMs[segA-segB]`,
     *  the same shape as the request's buffered ranges). */
    class Hit(
        val ump: Source,
        val cachedBytes: Long,
        val segmentCount: Int,
        val servedRanges: String,
        val audioMaxEndMs: Long? = null,
        val audioMaxCx: Int? = null,
    ) : SabrServe()

    /** The requester is already buffered far enough ahead; a policy-only body tells it to back off. */
    class Backoff(val ump: Source) : SabrServe()

    /** Nothing useful cached — go to the network. [reason] explains why, for logging. */
    class Miss(val reason: String) : SabrServe()
}

// Readahead windows, ported from legacy SabrCacheHandler. MIN is the "keep filling until covered" target;
// TARGET is what the synthesized NextRequestPolicy advertises back.
private const val TARGET_AUDIO_READAHEAD_MS = 60_000
private const val MIN_AUDIO_READAHEAD_MS = TARGET_AUDIO_READAHEAD_MS + 45_000L
private const val TARGET_VIDEO_READAHEAD_MS = 60_000
private const val MIN_VIDEO_READAHEAD_MS = TARGET_VIDEO_READAHEAD_MS + 45_000L

/**
 * Plan a local UMP response for a SABR request from the cached segments (port of
 * `SabrCacheHandler.handleSabrRequest`). Walks [SegmentIndex] forward from the request's `playerTimeMs`
 * until the readahead window is covered, **verifying every segment against `blobs.isCached`**, prepends
 * each format's init segment, then re-acquires the bytes with `blobs.read` (plan-then-reacquire: any span
 * lost to eviction in the gap collapses the whole serve to [SabrServe.Miss] → network). A fully-buffered
 * requester gets a [SabrServe.Backoff]; nothing cached gets [SabrServe.Miss].
 *
 * The downloader passes its own request here first so a re-download reuses the cache; the player
 * interception layer (later) passes the intercepted request the same way.
 */
fun MediaCache.serveSabr(request: VideoPlaybackAbrRequest): SabrServe {
    val abr = request.clientAbrState
    val playerTimeMs = abr.playerTimeMs
    val isAudioOnly = abr.enabledTrackTypesBitfield == 1
    val byCacheKey = request.adaptiveFormats.associateBy { it.cacheKey }
    val itagMime = request.adaptiveFormats.associate { it.itag to it.mimeTypeWithoutCodec }
    val selectedMimes = request.selectedFormats.mapNotNull { itagMime[it.itag] }.toSet()

    // Prefer webm, then formats the client already selected, then (if DRC) DRC formats. When the request
    // carries no adaptiveFormats (e.g. our own downloader), every score is 0 and the given order stands.
    fun score(f: FormatId): Int {
        var s = 0
        val mime = itagMime[f.itag] ?: ""
        if ("webm" in mime) s += 1
        if (mime in selectedMimes) s += 2
        if (abr.drcEnabled && byCacheKey[f.cacheKey]?.isDrc == true) s += 4
        return s
    }

    // Empty 16/17 means server-side format selection: the catalog is the ladder.
    val audioLadder = request.selectedAudioFormats.ifEmpty {
        request.adaptiveFormats.filter { !it.isVideo }.map { it.toFormatId() }
    }
    var audioFormats = audioLadder.sortedByDescending(::score)
    audioFormats = narrowAudioFormats(audioFormats, request, byCacheKey)

    val audioItags = audioLadder.map { it.itag }.toSet()
    val audioBuffered = request.bufferedRanges.filter { it.formatId.itag in audioItags }
    val audio = findCachedSegments(playerTimeMs, audioFormats, audioBuffered, MIN_AUDIO_READAHEAD_MS)

    var video: CheckResult = CheckResult.Miss
    // Audio-only (bitfield=1): googlevideo sends no video. Do not serve cached
    // video either. Lockstep is MSE dummy `buffered` + request 40=1, not Cx.
    if (!isAudioOnly) {
        var videoLadder = request.selectedVideoFormats.ifEmpty {
            request.adaptiveFormats.filter { it.isVideo }.map { it.toFormatId() }
        }
        val requested = abr.requestedVideoResolution
        if (requested != null && requested > 0 && byCacheKey.isNotEmpty()) {
            videoLadder = videoLadder.filter { f ->
                (byCacheKey[f.cacheKey]?.let { minOf(it.width ?: 0, it.height ?: 0) } ?: 0) >= requested
            }
        }
        val videoFormats = videoLadder.sortedByDescending { f ->
            var s = byCacheKey[f.cacheKey]?.let { minOf(it.width ?: 0, it.height ?: 0) } ?: 0
            val mime = itagMime[f.itag] ?: ""
            if ("webm" in mime) s += 10_000
            if (mime in selectedMimes) s += 20_000
            s
        }
        val videoItags = videoLadder.map { it.itag }.toSet()
        val videoBuffered = request.bufferedRanges.filter { it.formatId.itag in videoItags }
        video = findCachedSegments(playerTimeMs, videoFormats, videoBuffered, MIN_VIDEO_READAHEAD_MS)
    }

    val segments = buildList {
        (audio as? CheckResult.Cached)?.let { addAll(it.segments) }
        (video as? CheckResult.Cached)?.let { addAll(it.segments) }
    }.sortedBy { it.startMs ?: -1 }

    if (segments.isEmpty()) {
        // Both tracks already buffered far enough → tell the requester to back off rather than refetch.
        if (audio is CheckResult.Buffered && (isAudioOnly || video is CheckResult.Buffered)) {
            var backoff = audio.bufferedPosMs - playerTimeMs
            if (video is CheckResult.Buffered) backoff = minOf(backoff, video.bufferedPosMs - playerTimeMs)
            if (backoff - minOf(TARGET_AUDIO_READAHEAD_MS.toLong(), TARGET_VIDEO_READAHEAD_MS.toLong()) > 2000) {
                val policy = NextRequestPolicy(
                    targetAudioReadaheadMs = TARGET_AUDIO_READAHEAD_MS,
                    targetVideoReadaheadMs = TARGET_VIDEO_READAHEAD_MS,
                    backoffTimeMs = 2000,
                    playbackCookie = request.streamerContext.playbackCookie,
                    videoId = null,
                )
                // A policy-only body — the one time we send NEXT_REQUEST_POLICY: to tell the player to back off.
                val body = Buffer()
                body.writeUmpPart(UmpPart.NEXT_REQUEST_POLICY, policy.encode())
                return SabrServe.Backoff(body)
            }
        }
        return SabrServe.Miss(
            if (audio is CheckResult.Buffered) "buffered ahead but below the backoff threshold"
            else "no cached segments cover playerTime=${playerTimeMs}ms",
        )
    }

    // Re-acquire every segment's bytes; a miss here (evicted in the plan→serve gap) means network.
    val ump = synthesizeUmp(segments) ?: return SabrServe.Miss("${segments.size} segments planned but evicted before re-acquire")
    val bytes = segments.sumOf { it.contentLength }
    // Per-format summary of the playback served (itag@start-end[segA-segB]), matching the request's buffered.
    val servedRanges = segments.filter { it.startMs != null }
        .groupBy { it.itag }
        .entries.joinToString(",") { (itag, segs) ->
            "$itag@${segs.minOf { it.startMs!! }}-${segs.maxOf { it.startMs!! + (it.durationMs ?: 0) }}ms" +
                "[seg${segs.minOf { it.segmentIndex }}-${segs.maxOf { it.segmentIndex }}]"
        }
    val audioMedia = segments.filter { it.itag in audioItags && !it.isInitSegment }
    val audioMaxEndMs = audioMedia.maxOfOrNull { (it.startMs ?: 0) + (it.durationMs ?: 0) }
    val audioMaxCx = audioMedia.maxOfOrNull { it.segmentIndex }
    return SabrServe.Hit(ump, bytes, segments.size, servedRanges, audioMaxEndMs, audioMaxCx)
}

/** Legacy multi-audio narrowing: honor a requested track id, DRC preference, then the default track. */
private fun narrowAudioFormats(
    formats: List<FormatId>,
    request: VideoPlaybackAbrRequest,
    byCacheKey: Map<String, io.ymusic.ytmedia.sabr.AdaptiveFormat>,
): List<FormatId> {
    val requestedTrack = request.clientAbrState.requestAudioTrackId
    if (requestedTrack == null && formats.size <= 1) return formats
    var out = formats
    if (requestedTrack != null) {
        val f = out.filter { byCacheKey[it.cacheKey]?.audioTrackId == requestedTrack }
        if (f.isNotEmpty()) out = f
    }
    if (request.clientAbrState.drcEnabled && out.size > 1) {
        val f = out.filter { byCacheKey[it.cacheKey]?.isDrc == true }
        if (f.isNotEmpty()) out = f
    }
    if (out.size > 1) {
        val def = out.firstOrNull { byCacheKey[it.cacheKey]?.audioTrackIsDefault == true }
            ?: out.firstOrNull { fmt -> request.selectedFormats.any { it.cacheKey == fmt.cacheKey } }
        if (def != null) out = listOf(def)
    }
    return out
}

private sealed class CheckResult {
    data object Miss : CheckResult()
    class Buffered(val bufferedPosMs: Long) : CheckResult()
    class Cached(val bufferedPosMs: Long, val segments: List<MediaHeader>) : CheckResult()
}

/**
 * Walk cached segments of the first covered format forward from [playerTimeMs] until [minBufferMs] is
 * covered, prepending each format's init segment on first use. Every candidate is verified against
 * `blobs.isCached`, then its stored bytes against [MediaCache.checkStored] (which purges a corrupt one).
 * Port of legacy `findFormatCachedSegments`.
 */
private fun MediaCache.findCachedSegments(
    playerTimeMs: Long,
    formats: List<FormatId>,
    bufferedRanges: List<BufferedRange>,
    minBufferMs: Long,
): CheckResult {
    val sorted = bufferedRanges.sortedBy { it.startSegmentIndex }
    val initialized = bufferedRanges.map { it.formatId }.toMutableSet()
    val result = mutableListOf<MediaHeader>()

    fun addNext(find: (FormatId) -> MediaHeader?): MediaHeader? {
        for (format in formats) {
            val segment = find(format) ?: continue
            if (!blobs.isCached(segment.cacheKey, segment.startRange, segment.contentLength)) continue
            if (!checkStored(segment)) continue
            if (format !in initialized) {
                val init = index.findBySegmentIndex(format.itag, format.lmt, 0) ?: continue
                if (!blobs.isCached(init.cacheKey, init.startRange, init.contentLength)) continue
                if (!checkStored(init)) continue
                result += init
                initialized += format
            }
            result += segment
            return segment
        }
        return null
    }

    var bufferedPosMs = -1L
    var nextSeg: Int
    var newBufferedPos: Long
    val insideBuffered = sorted.firstOrNull { it.startMs <= playerTimeMs && playerTimeMs < it.endMs }
    if (insideBuffered != null) {
        nextSeg = insideBuffered.startSegmentIndex
        sorted.forEach {
            if (it.startSegmentIndex <= nextSeg && it.endSegmentIndex >= nextSeg) {
                nextSeg = it.endSegmentIndex + 1
                bufferedPosMs = it.startMs + it.durationMs - 1
            }
        }
        newBufferedPos = bufferedPosMs
    } else {
        val seg = addNext { index.findByStartTime(it.itag, it.lmt, playerTimeMs) } ?: return CheckResult.Miss
        nextSeg = seg.segmentIndex + 1
        newBufferedPos = checkNotNull(seg.startMs) + checkNotNull(seg.durationMs) - 1
    }

    while (newBufferedPos - playerTimeMs < minBufferMs) {
        if (sorted.any { it.startSegmentIndex <= nextSeg && it.endSegmentIndex >= nextSeg }) break
        val seg = addNext { index.findBySegmentIndex(it.itag, it.lmt, nextSeg) } ?: break
        nextSeg++
        newBufferedPos = checkNotNull(seg.startMs) + checkNotNull(seg.durationMs) - 1
    }

    if (result.isEmpty()) {
        return if (bufferedPosMs - playerTimeMs >= minBufferMs) CheckResult.Buffered(bufferedPosMs) else CheckResult.Miss
    }
    return CheckResult.Cached(bufferedPosMs, result)
}

/**
 * Build a hit's UMP body — exactly what the player needs and nothing else: for each segment, its format's
 * FORMAT_INITIALIZATION_METADATA (only before an init segment — i.e. when the format is being initialized,
 * as the real server does) then MEDIA_HEADER / MEDIA_DATA / MEDIA_END streamed from the blob store. No
 * protection status, no NEXT_REQUEST_POLICY. Returns null if any segment's bytes can't be re-acquired
 * (evicted since planning) — the caller then treats the whole serve as a network miss.
 */
private fun MediaCache.synthesizeUmp(segments: List<MediaHeader>): Source? {
    // Build a list of pieces to stream in order: small framing buffers (part headers + FIM + media header)
    // interleaved with each segment's PINNED span source. The segment bytes are NEVER read into memory —
    // they stream lazily as the consumer pulls, so a multi-MB hit costs only the framing (~100 B/segment).
    // Pin every segment up front so eviction in the plan→serve gap is caught before we commit to a hit.
    val pieces = ArrayList<Source>(segments.size * 3)
    for ((i, segment) in segments.withIndex()) {
        val pin = try {
            blobs.read(segment.cacheKey, segment.startRange, segment.contentLength)
        } catch (e: Throwable) {
            pieces.forEach { runCatching { it.close() } }
            throw e
        }
        if (pin == null) {
            pieces.forEach { runCatching { it.close() } } // release the spans pinned so far, give up the hit
            return null
        }
        val headerId = i and 0xFF
        val head = Buffer()
        // An init segment in the plan means this format is being (re)initialized → send its init metadata
        // first, as the server does. Best-effort: skip if the tee hasn't captured it (media still serves).
        if (segment.isInitSegment) {
            index.formatInit(segment.itag, segment.lmt)?.let { fim ->
                head.writeUmpPartHeader(UmpPart.FORMAT_INITIALIZATION_METADATA, fim.size.toLong()).write(fim)
            }
        }
        head.writeUmpPart(UmpPart.MEDIA_HEADER, segment.copy(headerId = headerId).encode())
        head.writeUmpPartHeader(UmpPart.MEDIA_DATA, segment.contentLength + 1).writeByte(headerId)
        pieces += head // framing
        pieces += pin // segment media, streamed
        pieces += Buffer().apply { writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(headerId) }
    }
    return ConcatSource(pieces)
}

/**
 * Reads a list of [Source] pieces in order, closing each as it's exhausted; [close] releases any still open
 * (the pinned segment spans). Lets a synthesized hit stream instead of materializing the whole body.
 */
private class ConcatSource(private val pieces: List<Source>) : Source {
    private var idx = 0
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "closed" }
        while (idx < pieces.size) {
            val n = pieces[idx].read(sink, byteCount)
            if (n != -1L) return n
            pieces[idx].close() // exhausted → release and advance
            idx++
        }
        return -1L
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        for (i in idx until pieces.size) runCatching { pieces[i].close() } // earlier pieces closed on exhaustion
    }
}

/**
 * Serve a progressive `/videoplayback` range read `bytes=[rangeStart, rangeEnd]` (inclusive, HTTP
 * semantics) straight from the byte store — the cross-protocol payoff (plan §1): bytes cached by *any*
 * protocol (a SABR fill, a prior progressive fill) answer a progressive range because within a format
 * stream segments are contiguous in byte space. Returns the pinned slice, or null (→ network) if the
 * range isn't fully cached right now. `rangeEnd < 0` means open-ended, which the cache never fully
 * covers, so it's always a miss here (let the network stream it).
 */
fun MediaCache.serveProgressive(itag: Int, lmt: Long, rangeStart: Long, rangeEnd: Long): PinnedSource? {
    if (rangeEnd < rangeStart) return null
    val length = rangeEnd - rangeStart + 1
    return blobs.read(formatCacheKey(itag, lmt), rangeStart, length)
}

// NB: fillFromProgressive (teeing a progressive network body into the cache) lands with the platform
// interception layer (step 6) — it needs a coroutine scope to abort its writer on a cancelled stream,
// which the interception plumbing provides. Downloads already fill the cache via fillFromSabr.

/** The cache keys of every stream with committed bytes — the `keepKeys` set for `SegmentIndex.cleanup`. */
fun MediaCache.liveKeys(): Set<String> = blobs.keys()

/** Convenience for callers that hold (itag, lmt) rather than a key. */
fun cacheKeyOf(itag: Int, lmt: Long): String = formatCacheKey(itag, lmt)
