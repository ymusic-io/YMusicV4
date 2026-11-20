package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.DynamicEngine
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EngineError
import io.ymusic.dynamicengine.decodeEngineError
import io.ymusic.dynamicengine.internal.ParkedLoggers
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.asAsyncSink
import io.ymusic.quickjs.asBufferedSource
import kmpcommon.log.Logger
import kmphttp.json.JsonReader
import kmphttp.json.JsonWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.withContext
import projectcommon.log.markTrail

/**
 * The YouTube extraction surface — intent-level twins of the engine bundle's `yt/…` commands
 * (engine-js `logic/yt/commands.ts`), shaped like the legacy `ExtractorLibWrapper`: the app states
 * WHAT it wants (the home feed, a playlist page behind an app link, a search), never HOW YouTube
 * serves it. All browseIds, params protobufs and Mix mechanics live in the engine; the app's only
 * currencies are engine-composed app links (round-tripped verbatim), opaque continuations, and the
 * user's own input (a query, a tapped video). Each call pins the manager's current engine, streams
 * one JSON args head to the pipe with a [JsonWriter], stream-parses one JSON result back with a
 * [JsonReader], and decodes an engine RESET into the typed [EngineError] — no reflective
 * (de)serializer touches the wire.
 */

/**
 * The Discover home feed (first page or a page's [YtFeedPage.continuation]). The engine serves the
 * FIRST fresh load after start from its session bootstrap's own youtube.com homepage (the page's
 * inline data is the same browse answer) — one fetch bootstraps both — and browses normally after.
 * [refresh] (a user reload) re-scrapes the homepage and serves the first page from it.
 */
suspend fun DynamicEngineManager.extractHomeFeed(
    continuation: String? = null,
    logger: Logger? = null,
    refresh: Boolean = false,
): YtFeedPage = feedCommand("yt/home", logger.cmd("extractHomeFeed", "continuation" to continuation.crumbToken())) { w ->
    w.optionalField("continuation", continuation)
    if (refresh) w.name("refresh").value(true)
}

/**
 * The signed-in Library feed (youtube.com's "/feed/you"): the account's own shelves — history,
 * playlists, Watch later, Liked videos — with per-shelf View-all CTAs as engine-composed app links.
 * Call only behind the sign-in gate; an anonymous browse answers a nudge page, not an error.
 */
suspend fun DynamicEngineManager.extractAccountLibrary(
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand("yt/library", logger.cmd("extractAccountLibrary", "continuation" to continuation.crumbToken())) { w ->
    w.optionalField("continuation", continuation)
}

/**
 * Hand the engine the watch-next payload the WebView player's page reported for the music video it
 * is playing ([contents] is that payload's JSON text, forwarded VERBATIM — the app never parses it).
 * The engine parses and stores it; it is the only thing feeding [getRecommendedFeed]. Fire and
 * forget: nothing comes back, and a failure here must never disturb playback.
 */
suspend fun DynamicEngineManager.handleWebViewPlayNext(videoId: String, contents: String, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand("yt/webview-next", log = logger.cmd("handleWebViewPlayNext", "videoId" to videoId), writeFields = { w ->
            w.name("videoId").value(videoId)
            w.name("contents").value(contents)
        }) { it.skipValue() }
    }
}

/**
 * The Recommended feed — built entirely from what [handleWebViewPlayNext] harvested, so it needs no
 * network and returns an empty page until something has been played. Re-shuffled on every call.
 */
suspend fun DynamicEngineManager.getRecommendedFeed(logger: Logger? = null): YtFeedPage =
    feedCommand("yt/recommended", logger.cmd("getRecommendedFeed")) {}

/**
 * The Top charts page — youtube.com/music for the engine-resolved region (the user's pick, else the
 * connection's detected region), one page. The engine keeps it a day; [refresh] (a user reload)
 * fetches it anew.
 */
suspend fun DynamicEngineManager.extractTrendingMusicFeed(logger: Logger? = null, refresh: Boolean = false): YtFeedPage =
    feedCommand("yt/trending", logger.cmd("extractTrendingMusicFeed")) { w ->
        if (refresh) w.name("refresh").value(true)
    }

/**
 * A playlist detail page. [url] is an engine-composed app link ("/playlist?list=…", possibly with
 * engine-private query state) from a [YtFeedItem.url] or [YtSectionHeader.ctaDeeplink] — passed back
 * VERBATIM; the engine decides how to serve it (VL browse, Mix watch-panel, …).
 */
suspend fun DynamicEngineManager.extractPlaylistDetail(
    url: String,
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand(
    "yt/playlist",
    logger.cmd("extractPlaylistDetail", "url" to url, "continuation" to continuation.crumbToken()),
) { w ->
    w.name("url").value(url)
    w.optionalField("continuation", continuation)
}

/** A channel detail page. [url] is an engine-composed "/channel/…" app link (an item's, a tab's, or
 *  a shelf CTA's), passed back verbatim — same contract as [extractPlaylistDetail]. */
suspend fun DynamicEngineManager.extractChannelDetail(
    url: String,
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand(
    "yt/channel",
    logger.cmd("extractChannelDetail", "url" to url, "continuation" to continuation.crumbToken()),
) { w ->
    w.name("url").value(url)
    w.optionalField("continuation", continuation)
}

/** Subscribe the signed-in viewer to [channelId] ([YtPageHeader.channelId]), or unsubscribe when [subscribed] is false. */
suspend fun DynamicEngineManager.subscribeChannel(channelId: String, subscribed: Boolean, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand(
            "yt/channel/subscribe",
            log = logger.cmd("subscribeChannel", "channelId" to channelId, "subscribed" to subscribed),
            writeFields = { w ->
                w.name("channelId").value(channelId)
                w.name("subscribed").value(subscribed)
            },
        ) { it.skipValue() }
    }
}

/** Add [playlistId] ([YtPageHeader.savePlaylistId]) to the signed-in viewer's library, or remove it when [saved] is false. */
suspend fun DynamicEngineManager.savePlaylist(playlistId: String, saved: Boolean, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand(
            "yt/playlist/save",
            log = logger.cmd("savePlaylist", "playlistId" to playlistId, "saved" to saved),
            writeFields = { w ->
                w.name("playlistId").value(playlistId)
                w.name("saved").value(saved)
            },
        ) { it.skipValue() }
    }
}

suspend fun DynamicEngineManager.search(
    query: String? = null,
    params: String? = null,
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand(
    "yt/search",
    // query is user input — never a prop
    logger.cmd("search", "params" to params, "continuation" to continuation.crumbToken()),
) { w ->
    w.optionalField("query", query)
    w.optionalField("params", params)
    w.optionalField("continuation", continuation)
}

/** Watch-next: related items + [YtFeedPage.autoplayVideoId] for [videoId] (or a continuation). */
suspend fun DynamicEngineManager.watchNext(
    videoId: String? = null,
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand(
    "yt/next",
    logger.cmd("watchNext", "videoId" to videoId, "continuation" to continuation.crumbToken()),
) { w ->
    w.optionalField("videoId", videoId)
    w.optionalField("continuation", continuation)
}

/**
 * The play queue's first page for [videoId]: its radio, the videos after it. When YouTube has no
 * radio for it, its related videos instead, with no continuation (the queue ends there).
 */
suspend fun DynamicEngineManager.startRadioQueue(videoId: String, logger: Logger? = null): YtFeedPage = feedCommand(
    "yt/queue",
    logger.cmd("startRadioQueue", "videoId" to videoId),
) { w ->
    w.name("videoId").value(videoId)
}

/**
 * The play queue continuing a playlist page's list after [videoId], the item at [index] of it. [url]
 * is the page's engine link, passed back verbatim. [YtFeedPage.pageHeader] carries the list's
 * itemCount when it is finite.
 */
suspend fun DynamicEngineManager.playlistQueue(
    url: String,
    videoId: String,
    index: Int,
    logger: Logger? = null,
): YtFeedPage = feedCommand(
    "yt/queue",
    logger.cmd("playlistQueue", "url" to url, "videoId" to videoId, "index" to index),
) { w ->
    w.name("url").value(url)
    w.name("videoId").value(videoId)
    w.name("index").value(index.toLong())
}

/** The play queue's next page from a [startRadioQueue] / [playlistQueue] page's continuation. */
suspend fun DynamicEngineManager.queueContinuation(continuation: String, logger: Logger? = null): YtFeedPage =
    feedCommand(
        "yt/queue",
        logger.cmd("queueContinuation", "continuation" to continuation.crumbToken()),
    ) { w ->
        w.name("continuation").value(continuation)
    }

suspend fun DynamicEngineManager.searchSuggestions(query: String, logger: Logger? = null): List<String> =
    use { engine ->
        engine.jsonCommand("yt/suggest", log = logger.cmd("searchSuggestions"), writeFields = { w ->
            w.name("query").value(query)
        }) { readSuggestions(it) }
    }

/**
 * The locale-changed notification (the legacy `YTPrefsUpdateTask` role). [gl] is the region pick,
 * blank = automatic (the slot leaves PREF, so the bootstrap homepage answers with YouTube's
 * IP-derived region again), null = a language change that leaves the region alone — PREF itself,
 * the persistent WebView-shared cookie store, is the pick's one record. There is no hl argument — the app language IS the content language, and the
 * engine re-pulls it from host settings on every notify (only the hl slot is re-asserted on every
 * cooked request; the gl slot is YouTube's to control). On a real change the engine deletes the
 * sticky `s_gl` echo and refreshes its session under the new identity: the re-parsed ytcfg GL
 * becomes the working region value — YouTube outranks the pick. Call when the user picks a region
 * OR a language (after persisting the language setting) — never on start.
 */
suspend fun DynamicEngineManager.applyYtLocale(gl: String?, logger: Logger? = null): Unit = use { engine ->
    engine.jsonCommand("yt/locale/apply", log = logger.cmd("applyYtLocale", "gl" to gl), writeFields = { w ->
        w.optionalField("gl", gl)
    }) { it.skipValue() }
}

/**
 * The Settings region picker's one read: the WORKING region ([YtLocales.gl] — the session's parsed
 * answer, YouTube's own) and the menus YouTube offers labelled in the effective language. The
 * engine keeps ONE cached copy of the menus (in-memory + [io.ymusic.dynamicengine.CacheStore]) tagged
 * with the pair it was fetched under: it re-fetches only when the effective pair changed or the
 * copy is over a week old, and a failed re-fetch falls back to the cached copy instead of erroring.
 */
suspend fun DynamicEngineManager.ytLocaleOptions(logger: Logger? = null): YtLocales = use { engine ->
    engine.jsonCommand("yt/locales", log = logger.cmd("ytLocaleOptions"), writeFields = { w ->
    }) { readLocales(it) }
}

/** This API layer's own hop: the function name plus its call args as props (null args omitted,
 *  opaque tokens truncated). Free-text user input (a search query) is deliberately NOT a prop —
 *  props reach crash reports. */
internal fun Logger?.cmd(tag: String, vararg props: Pair<String, Any?>): Logger? =
    this?.fork(tag, props.mapNotNull { (k, v) -> v?.let { k to it } }.toMap<String, Any>().ifEmpty { null })

internal fun String?.crumbToken(): String? = this?.take(100)

/** Write `"key":value` only when [value] is present — absent optional fields are omitted, not null. */
internal fun JsonWriter.optionalField(key: String, value: String?) {
    if (value != null) name(key).value(value)
}

internal suspend fun DynamicEngineManager.feedCommand(
    command: String,
    log: Logger?,
    writeFields: suspend (JsonWriter) -> Unit,
): YtFeedPage = use { engine -> engine.jsonCommand(command, writeFields, log) { readFeedPage(it) } }

/**
 * Stream the args head to the pipe sink — jsonCommand owns the envelope: `{` + the parked [log]'s
 * key + [writeFields]'s fields + `}` — then stream-parse the result off the pipe source with
 * [read]; neither side is buffered whole. [log] is parked for exactly this command's lifetime so
 * the JS side's host callbacks join the caller's trail (see [ParkedLoggers]). A RESET decodes to
 * the typed [EngineError]; a malformed body surfaces as `E20030`.
 */
internal suspend fun <R> DynamicEngine.jsonCommand(
    command: String,
    writeFields: suspend (JsonWriter) -> Unit,
    log: Logger? = null,
    read: suspend (JsonReader) -> R,
): R {
    if (log == null) return jsonCommandPipe(command, 0, writeFields, read)
    val key = loggers.park(log)
    try {
        return jsonCommandPipe(command, key, writeFields, read)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // The trail would die with the unwind — stamp it so the eventual captureError (frames up,
        // on a shallower logger) still reports this call's full story.
        throw e.markTrail(log)
    } finally {
        loggers.unpark(key)
    }
}

private suspend fun <R> DynamicEngine.jsonCommandPipe(
    command: String,
    key: Int,
    writeFields: suspend (JsonWriter) -> Unit,
    read: suspend (JsonReader) -> R,
): R = withContext(CoroutineName(command)) {
    val pipe = openPipe(command)
    try {
        // Async: the pipe's sink suspends. The args head is small, so it is built whole and drained
        // once — the writer only fills its buffer, draining is ours to do.
        val sink = pipe.sink.asAsyncSink()
        val writer = JsonWriter.Async { w -> sink.write(w.buffer, w.buffer.size).also { sink.flush() } }
        writer.beginObject()
        if (key != 0) writer.name("logKey").value(key)
        writeFields(writer)
        writer.endObject()
        writer.flush()
        pipe.sink.close()
        try {
            read(JsonReader(pipe.source.asBufferedSource()))
        } catch (e: PipeResetException) {
            throw decodeEngineError(e.reason, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EngineError.Technical(
                "E20030",
                "$command returned a malformed result: ${e.message}",
                null,
                null,
                null,
                e,
            )
        }
    } finally {
        pipe.sink.close()
        pipe.source.close()
    }
}
