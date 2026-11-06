package io.ymusic.ytmedia.proxy

import io.ymusic.ytmedia.cache.BlobWriter
import io.ymusic.ytmedia.cache.CacheStats
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SabrServe
import io.ymusic.ytmedia.cache.isMediaCacheStorageFault
import io.ymusic.ytmedia.cache.serveProgressive
import io.ymusic.ytmedia.cache.serveSabr
import io.ymusic.ytmedia.proto.ProtoReader
import io.ymusic.ytmedia.sabr.ADAPTIVE_FORMATS_HEADER
import io.ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import io.ymusic.ytmedia.sabr.formatCacheKey
import io.ymusic.ytmedia.sabr.parseAdaptiveFormatsHeader
import kmpcommon.io.AsyncSource
import kmpcommon.io.asAsyncSource
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kmpcommon.uri.PercentEncoder
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.RequestBodyX.asOneshotRequestBody
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kotlinx.atomicfu.atomic
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import kotlin.time.TimeSource

/**
 * One response handed back to the player's HTTP client. [body] streams until EOF; [contentLength] is null
 * when unknown. [url] is the effective request URL (the final one after any followed redirect).
 *
 * **The consumer owns [body] and must close it** when done: that releases the upstream network response —
 * the SABR tee / progressive caching source forward `close()` down to it, and a cache-hit body has no
 * upstream to release. There is no separate teardown hook.
 */
class ProxyResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: AsyncSource,
    val contentLength: Long?,
    val url: String,
)

/**
 * The media router behind the player cache (see `docs/player/youtube-playback.md`). [serveRequest] tries the
 * player [cache] first (a hit → synthesized bytes, zero network) and on a miss forwards through [client]
 * **while teeing the response back into the cache** so a later request is a hit. MsePlayer reaches it
 * through [SabrProxyHttpClient], which hands over each googlevideo request's url, method and body.
 *
 * **[client] must carry the same cookie identity and User-Agent as the rest of the YouTube session**:
 * googlevideo + BotGuard reject a request whose cookies/UA don't match (see the "request headers must be
 * consistent" rule).
 */
class LocalMediaProxy(
    private val cache: MediaCache,
    private val client: KmpHttpClient,
    private val userAgent: String,
    logger: Logger? = null,
    /** Per-request/part chatter (cache hit-miss decisions, fill sizes, UMP parts). Toggle at runtime;
     *  WARN-level events (cache-serve faults, SABR errors, protection rejects) always log if [logger] is set. */
    var verbose: Boolean = false,
    /** Serve SABR from cache (synthesized UMP hits). A synthesized hit omits the control parts a real
     *  response carries — `FORMAT_INITIALIZATION_METADATA`, `STREAM_PROTECTION_STATUS` — that the player
     *  needs to (re)initialize a format, so a hit on a quality / audio-only switch can wedge the player
     *  (the blank-video hack relies on the native format-switch path). Set false to fill-only (always
     *  forward SABR; the tee still caches) until the hit builder replays those control parts. */
    var serveSabrHits: Boolean = true,
    /** Asked per request; false sends the request straight to the network, neither reading nor filling the cache. */
    private val cacheEnabled: () -> Boolean = { true },
) {
    private val log = logger?.fork("mediaproxy")

    /** Set once the on-disk cache proved broken (a span read or write hit a storage IO error; a deleted span file is only a miss): for the rest of
     *  this process every request goes straight to the network, uncached. Deliberately not persisted. */
    private val bypassCache = atomic(false)

    private fun onStorageFault(error: Throwable) {
        if (bypassCache.compareAndSet(expect = false, update = true)) {
            log?.log(Logger.Level.WARN, "player cache storage broken — bypassing the cache for this session", error)
        }
    }

    /** Emit a DEBUG line only in [verbose] mode; the message is built lazily so it's free when off. */
    private inline fun logVerbose(message: () -> String) {
        if (verbose) log?.debug(message)
    }

    /** One-line dump of what the player is asking for — the state the serve decision is made against.
     *  `vid` tells main-video vs ad; empty `selV` (or tracks=1) means audio-only; `buffered` is what the
     *  player claims it already has (drives HIT vs BACKOFF). */
    private fun sabrRequestSummary(r: VideoPlaybackAbrRequest): String {
        val abr = r.clientAbrState
        val buffered = r.bufferedRanges.joinToString(",") {
            "${it.formatId.itag}@${it.startMs}-${it.endMs}ms[seg${it.startSegmentIndex}-${it.endSegmentIndex}]"
        }
        return "sabr-req vid=${r.videoId} t=${abr.playerTimeMs}ms tracks=${abr.enabledTrackTypesBitfield} " +
            "selA=${r.selectedAudioFormats.map { it.itag }} selV=${r.selectedVideoFormats.map { it.itag }} " +
            "avail=${r.adaptiveFormats.size} buffered=[$buffered]"
    }

    /**
     * Serve one request: a cache hit → synthesized bytes (zero network), a miss → forwarded through
     * [client] and teed back into the cache.
     *
     * [body] is taken. A SABR parse reads a [Buffer.peek] so the cursor stays at 0; a network miss
     * then drains the same buffer into the forwarded request. A cache hit never reads it. The caller
     * must not use [body] after this returns.
     *
     * [extraHeaders] are forwarded on a network miss (Cookie / Origin / Referer from engine-js).
     * Hop-by-hop headers are dropped.
     */
    suspend fun serveRequest(
        url: String,
        method: String,
        body: Buffer?,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): ProxyResponse {
        if (!cacheEnabled() || bypassCache.value || cache.blobs.maxSize <= 0) {
            return forward(url, method, body, extraHeaders, fill = false)
        }
        val sabrReq = body?.let { parseSabrRequest(url, method, it.peek(), it.size) }
            ?.withAdaptiveFormatsHeader(extraHeaders)
        // A cache-serve fault must never fail the request — fall through to the network — but it's a real
        // bug (bad parse, cache read), so log it as a breadcrumb rather than swallowing it silently.
        val hit = runCatching { serveFromCache(url, method, sabrReq) }
            .onFailure {
                log?.log(Logger.Level.WARN, "cache serve failed, forwarding: $url", it)
                if (it.isMediaCacheStorageFault()) onStorageFault(it)
            }
            .getOrNull()
        return hit ?: forward(url, method, body, extraHeaders, fill = !bypassCache.value)
    }

    /** A cache hit → a synthesized response; null → miss (forward to the network). Logs the decision and
     *  how long it took: HIT / BACKOFF / MISS with the reason and, for a progressive miss, how much of the
     *  range *was* cached (so a partial-coverage miss is visible). */
    private fun serveFromCache(
        url: String,
        method: String,
        sabrReq: VideoPlaybackAbrRequest?,
    ): ProxyResponse? {
        val started = TimeSource.Monotonic.markNow()
        if (isSabrCache(url) && method == "POST" && sabrReq != null) {
            logVerbose { sabrRequestSummary(sabrReq) }
            if (!serveSabrHits) return null // fill-only: forward SABR to the network (still teed into cache)
            when (val serve = cache.serveSabr(sabrReq)) {
                is SabrServe.Hit -> {
                    logVerbose { "HIT sabr: served=[${serve.servedRanges}] (${serve.segmentCount} segments, ${serve.cachedBytes} bytes, built in ${started.elapsedNow()})" }
                    cache.stats?.addHitBytes(serve.cachedBytes)
                    return umpResponse(StorageFaultSource(serve.ump, ::onStorageFault), url)
                }
                is SabrServe.Backoff -> {
                    logVerbose { "BACKOFF sabr: requester buffered ahead — told to back off (${started.elapsedNow()})" }
                    return umpResponse(serve.ump, url)
                }
                is SabrServe.Miss -> {
                    logVerbose { "MISS sabr: ${serve.reason} (${started.elapsedNow()})" }
                    return null
                }
            }
        }
        val prog = progressiveRange(url) ?: return null
        val len = prog.end - prog.start + 1
        val src = cache.serveProgressive(prog.itag, prog.lmt, prog.start, prog.end)
        if (src == null) {
            logVerbose {
                val cached = cache.blobs.cachedLength(formatCacheKey(prog.itag, prog.lmt), prog.start, len).coerceAtLeast(0)
                "MISS progressive itag=${prog.itag} range=${prog.start}-${prog.end}: cached $cached/$len bytes (${started.elapsedNow()})"
            }
            return null
        }
        logVerbose { "HIT progressive itag=${prog.itag} range=${prog.start}-${prog.end} (${started.elapsedNow()})" }
        return ProxyResponse(
            status = 200, reason = "OK",
            headers = listOf("Content-Type" to "application/octet-stream", "Content-Length" to "$len"),
            body = StorageFaultSource(cache.stats?.let { HitCountingSource(src, it) } ?: src, ::onStorageFault).asAsyncSource(),
            contentLength = len,
            url = url,
        )
    }

    private fun umpResponse(ump: okio.Source, url: String): ProxyResponse {
        val len = (ump as? Buffer)?.size
        return ProxyResponse(
            status = 200, reason = "OK",
            headers = buildList {
                add("Content-Type" to "application/vnd.yt-ump")
                if (len != null) add("Content-Length" to "$len")
            },
            body = ump.asAsyncSource(), contentLength = len, url = url,
        )
    }

    /** Miss → fetch from the network, teeing the body back into the cache as it streams to the WebView. */
    private suspend fun forward(
        url: String,
        method: String,
        body: Buffer?,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        fill: Boolean = true,
    ): ProxyResponse {
        val builder = RequestBuilder().url(url)
        for ((name, value) in extraHeaders) {
            if (name.lowercase() in SKIP_FORWARD_HEADERS) continue
            builder.addHeader(name, value)
        }
        if (extraHeaders.none { it.first.equals("user-agent", ignoreCase = true) }) {
            builder.header("user-agent", userAgent)
        }
        if (body != null) builder.method(method, body.asOneshotRequestBody(null, body.size)) else builder.get()
        val response = client.execute(builder.build(), RedirectOptions(followRedirects = true, followSslRedirects = true))
        val length = response.body.contentLength().takeIf { it >= 0 }
        val headers = buildList {
            runCatching { response.body.contentType()?.toString() }.getOrNull()?.let { add("Content-Type" to it) }
            if (length != null) add("Content-Length" to "$length")
        }
        // Fill on the way through: SABR responses are a UMP part stream (tee each complete segment into
        // the cache while passing the raw bytes to the WebView untouched); progressive responses are a
        // plain byte range (tee at the range's known offset). Both leave the response bytes unchanged.
        var networkBody = response.body.asyncSource()
        val effectiveUrl = response.request.url.toString()
        logVerbose { "cache-miss → network ${response.code}: $effectiveUrl" }
        if (!response.isSuccessful || !fill) {
            return ProxyResponse(response.code, "OK", headers, networkBody, length, url = effectiveUrl)
        }
        if (isSabrCache(url)) {
            networkBody = SabrCacheTee(networkBody, cache, log, verbose, ::onStorageFault)
        } else {
            networkBody = fillProgressive(effectiveUrl, networkBody) ?: networkBody
        }
        return ProxyResponse(response.code, "OK", headers, networkBody, length, url = effectiveUrl)
    }

    /**
     * If [url] is a progressive range read, wrap [network] so its bytes also land in the cache — but only
     * the part that isn't cached yet. The WebView asked for the whole `[start, end]`, so we forward and
     * pass the whole body through untouched; the writer, though, opens at the first uncached byte (skipping
     * the already-cached leading prefix `cachedLength` reports). Re-teeing a cached prefix would only spill
     * it to a temp and drop it at commit — pointless disk churn. Interior holes past the prefix are still
     * covered (the per-block commit dedupe drops any block that already exists).
     */
    private fun fillProgressive(url: String, network: AsyncSource): AsyncSource? {
        val prog = progressiveRange(url) ?: return null
        val key = formatCacheKey(prog.itag, prog.lmt)
        val length = prog.end - prog.start + 1
        val skip = cache.blobs.cachedLength(key, prog.start, length).coerceAtLeast(0) // cached leading prefix
        if (skip >= length) return network // whole range cached (a serve race lost it) — nothing to write
        val writer = cache.blobs.writer(key, prog.start + skip, length - skip)
        return CachingSource(
            network,
            writer,
            skip,
            onWriteFailed = { error ->
                log?.log(Logger.Level.WARN, "progressive-fill disabled: ${error.message}", error)
                if (error.isMediaCacheStorageFault()) onStorageFault(error)
            },
        ) { teed ->
            logVerbose { "progressive-fill itag=${prog.itag} $teed bytes @ ${prog.start + skip} (skipped $skip cached)" }
        }
    }

    private class Progressive(val itag: Int, val lmt: Long, val start: Long, val end: Long)

    private fun progressiveRange(url: String): Progressive? {
        if (!url.contains("/videoplayback") || !url.contains("clen=")) return null
        val q = queryOf(url)
        val itag = q["itag"]?.toIntOrNull() ?: return null
        val lmt = q["lmt"]?.toLongOrNull() ?: return null
        val range = q["range"]?.split('-') ?: return null
        val start = range.getOrNull(0)?.toLongOrNull() ?: return null
        val end = range.getOrNull(1)?.toLongOrNull() ?: return null
        return Progressive(itag, lmt, start, end)
    }

    /** VOD SABR that may be cache-served / teed. Live (`live=1`) and `noclen=1` stay uncached. */
    private fun isSabrCache(url: String) =
        url.contains("sabr=1") && !url.contains("live=1") && !url.contains("noclen=1")

    /** Consumes [byteCount] bytes from [body]. The caller that still needs those bytes passes a peek. */
    private fun parseSabrRequest(
        url: String,
        method: String,
        body: BufferedSource,
        byteCount: Long,
    ): VideoPlaybackAbrRequest? {
        if (!url.contains("sabr=1") || method != "POST") return null
        return runCatching {
            VideoPlaybackAbrRequest.from(ProtoReader(body, byteCount).readAllFields())
        }.getOrNull()
    }

    /** A malformed header is an engine-js bug: logged, and the request still forwards without the catalog. */
    private fun VideoPlaybackAbrRequest.withAdaptiveFormatsHeader(
        headers: List<Pair<String, String>>,
    ): VideoPlaybackAbrRequest {
        val raw = headers.firstOrNull { it.first.equals(ADAPTIVE_FORMATS_HEADER, ignoreCase = true) }?.second
            ?: return this
        val formats = runCatching { parseAdaptiveFormatsHeader(raw) }
            .onFailure { log?.log(Logger.Level.WARN, "ignoring malformed $ADAPTIVE_FORMATS_HEADER", it) }
            .getOrNull()
        return if (formats.isNullOrEmpty()) this else withAdaptiveFormats(formats)
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i < 0) null else {
            val key = PercentEncoder.decode(pair.substring(0, i), plusIsSpace = true).toString()
            val value = PercentEncoder.decode(pair.substring(i + 1), plusIsSpace = true).toString()
            key to value
        }
    }.toMap()

    private fun queryOf(url: String): Map<String, String> = parseQuery(url.substringAfter('?', ""))

    companion object {
        private val SKIP_FORWARD_HEADERS =
            setOf("host", "content-length", "transfer-encoding", "connection", ADAPTIVE_FORMATS_HEADER.lowercase())
    }
}

/**
 * Tees a network body into a [BlobWriter] as it streams through: each read mirrors the freshly-read bytes
 * to the cache, EOF commits, an early [close] aborts (dropping only the uncommitted trailing block —
 * committed blocks stay, per MediaBlobCache's per-block commit). [BlobWriter.commit]/[abort] are
 * non-suspend, so [close] releases the region cleanly without a coroutine. A failing cache write never
 * fails the body: the writer is aborted, [onWriteFailed] is told, and the bytes keep passing through.
 *
 * [skip] is a count of leading network bytes that are already cached: they pass through to the reader
 * untouched but are **not** teed (the writer starts at the first uncached byte). Once consumed, every
 * subsequent byte is mirrored.
 */
internal class CachingSource(
    private val network: AsyncSource,
    private val writer: BlobWriter,
    private var skip: Long = 0L,
    private val onWriteFailed: (Throwable) -> Unit = {},
    private val onCommit: (teed: Long) -> Unit = {},
) : AsyncSource {
    private var finished = false
    private var teed = 0L // bytes actually mirrored into the cache (excludes the skipped cached prefix)

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        val n = network.read(sink, byteCount)
        if (n == -1L) {
            finish(commit = true)
            return -1L
        }
        var teeOffset = sink.size - n // the bytes just appended to sink
        var teeLen = n
        if (skip > 0) {
            val drop = minOf(skip, teeLen)
            teeOffset += drop
            teeLen -= drop
            skip -= drop
        }
        if (teeLen > 0 && !finished) {
            val copy = Buffer()
            sink.copyTo(copy, teeOffset, teeLen)
            try {
                writer.sink.write(copy, teeLen)
                teed += teeLen
            } catch (e: Exception) {
                writeFailed(e)
            }
        }
        return n
    }

    override fun close() {
        network.close()
        finish(commit = false)
    }

    private fun finish(commit: Boolean) {
        if (finished) return
        finished = true
        try {
            if (commit) {
                writer.commit()
                onCommit(teed)
            } else {
                writer.abort()
            }
        } catch (e: Exception) {
            onWriteFailed(e)
        }
    }

    private fun writeFailed(error: Exception) {
        finished = true
        runCatching { writer.abort() }.onFailure { error.addSuppressed(it) }
        onWriteFailed(error)
    }
}

/** Reports a cache-storage failure surfacing mid-body, then rethrows: bytes already went out, so this
 *  response fails and the player's retry is served uncached. */
private class StorageFaultSource(delegate: Source, private val onFault: (Throwable) -> Unit) : ForwardingSource(delegate) {
    override fun read(sink: Buffer, byteCount: Long): Long = try {
        super.read(sink, byteCount)
    } catch (e: Exception) {
        if (e.isMediaCacheStorageFault()) onFault(e)
        throw e
    }
}

/** Adds the bytes read through it to [stats] once, at EOF or close, whichever comes first. */
private class HitCountingSource(delegate: Source, private val stats: CacheStats) : ForwardingSource(delegate) {
    private var read = 0L
    private var recorded = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        val n = super.read(sink, byteCount)
        if (n == -1L) record() else read += n
        return n
    }

    override fun close() {
        record()
        super.close()
    }

    private fun record() {
        if (recorded) return
        recorded = true
        stats.addHitBytes(read)
    }
}
