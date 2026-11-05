package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.internal.ENGINE_JS
import io.ymusic.dynamicengine.internal.ParkedLoggers
import io.ymusic.dynamicengine.internal.installCache
import io.ymusic.dynamicengine.internal.installCapture
import io.ymusic.dynamicengine.internal.installCookies
import io.ymusic.dynamicengine.internal.installHttp
import io.ymusic.dynamicengine.internal.installRemoteConfig
import io.ymusic.dynamicengine.internal.installSettings
import io.ymusic.quickjs.Pipe
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.QuickJsException
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.SettingsStore
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.Buffer

/**
 * A [QuickJsRuntime] pre-wired with the host capabilities a downloadable scraping engine needs. The
 * point is that the scraping logic ships as JS that can be updated remotely — no app release — so
 * YouTube feed-shape changes don't surface to the user as errors.
 *
 * Today it wires **HTTP** — a global `HttpRequest` builder served directly on a [KmpHttpClient]
 * (streaming responses; on Android the jvm variant of `:kmpHttp:core` resolves, which is OkHttp) —
 * and **cookies**: the bundle's `Cookies.get`/`.set` over the `"cookie/get"` / `"cookie/set"`
 * commands, served on the app-provided cookie store. The engine JS owns cookie POLICY (the legacy
 * `YTHttpClientCookieStoreAndHeadersManager` role — building each request's `Cookie` header,
 * consent/SOCS synthesis, storing `Set-Cookie`s), so [create]'s `client` should carry NO cookie
 * jar — a jar would inject cookies underneath the JS. Pass the platform store (the one the WebView
 * player shares) as `cookieJar` so scraping and playback present one identity.
 * When a [CacheStore] is supplied it also wires **cache** — the bundle's `Cache` over `cache/…`,
 * a streaming keyed blob cache (JS owns eviction) for the InnerTube session params. File / SQLite
 * protocols are planned (see `quickjs/HOST_PROTOCOLS.md`). An app-provided [SettingsStore] wires
 * the bundle's typed `Settings.get`/`.set` to the same persistent preferences native UI uses.
 *
 * Creation just boots the bundle. [healthCheck] is the separate gate — driving the bundle's
 * `"init"` command and recording [backendVersion] — that callers who accept/reject bundles (the
 * manager's start/swap) run right after create.
 *
 * The pipe/command plumbing is **internal**: callers don't open pipes or register handlers, they
 * call a task method (`getHomeFeed()`, …) that drives the engine JS over pipes under the hood and
 * serves any host command the JS calls back on (via [commandHandler], using [logger]). Not
 * thread-safe — the underlying runtime confines JS to a single thread. Build one, use it, [close] it.
 */
class DynamicEngine internal constructor(
    internal val runtime: QuickJsRuntime,
    internal val logger: Logger?,
    internal val loggers: ParkedLoggers,
) {

    /** The bundle's self-declared revision (its `BACKEND_VERSION`); null until [healthCheck] ran. */
    var backendVersion: Int? = null
        private set

    /**
     * Drive the bundle's `"init"` command — the health check proving the whole stack (open
     * routing, handler dispatch, JSON encode, END) — and record [backendVersion]. Deliberately a
     * separate step, not part of [create]: create just boots, the CALLER decides whether/when to
     * gate on health (and closes the engine if this throws). Same synchronous, engine-thread-only
     * contract as create (see [QuickJsRuntime.openReadNow]). Refused / RESET / malformed /
     * not-synchronous → `E20021` (a RESET's own code wins). No timeout: init either completed
     * inside the open call or never will.
     */
    fun healthCheck(): Int {
        val body = try {
            runtime.openReadNow("init")
        } catch (e: EngineError) {
            throw e
        } catch (e: PipeResetException) {
            throw decodeEngineError(e.reason, e) // the bundle classified its own failure — keep its code
        } catch (e: Throwable) {
            throw EngineError.Technical("E20021", "engine init failed: ${e.message}", null, null, null, e)
        }
        val head = try {
            initJson.decodeFromString<InitHead>(body)
        } catch (e: Exception) {
            throw EngineError.Technical(
                "E20021",
                "engine init returned a malformed head: ${body.take(200)}",
                null, null, null, e,
            )
        }
        backendVersion = head.backendVersion
        return head.backendVersion
    }

    /** Evaluate [script] synchronously (load engine code / register `ymix.onAccept` handlers). */
    internal suspend fun load(script: String, filename: String = "<engine>"): String? = runtime.load(script, filename)

    /** Any thread. Opens `"event"`; JS `ymix.onEvent` runs on the engine thread. */
    fun fire(eventName: String) = runtime.fireEvent(eventName)

    /** Handle pipes the engine JS opens with the exact [command] name (wrap [handler] in
     *  [commandHandler] for the error contract). */
    internal fun onAccept(command: String, handler: suspend (Pipe) -> Unit) = runtime.onAccept(command, handler)

    /** Open a pipe to the engine JS addressed to [command] (its `ymix.onAccept` handler serves it). */
    internal suspend fun openPipe(command: String): Pipe = runtime.openPipe(command)

    /** Run the bundle's `onShutdown` cleanup (engine-js lifecycle.ts); returns on its ack (EOF). */
    internal suspend fun signalShutdown() {
        val pipe = openPipe("shutdown")
        try {
            pipe.sink.close()
            val buf = Buffer()
            while (pipe.source.receive(buf, 4096L) != -1L) { /* EOF = the quiesced ack */
            }
        } finally {
            pipe.source.close()
        }
    }

    /** Suspend until nothing is in flight in either direction (see [QuickJsRuntime.awaitIdle]). */
    internal suspend fun awaitIdle() = runtime.awaitIdle()

    /** Free the runtime (never the caller-owned dispatcher). Idempotent-safe to call once. */
    suspend fun close() = runtime.close()

    /** Boot-failure teardown (engine-thread-only, e.g. after [healthCheck] threw) — see
     *  [QuickJsRuntime.closeNow]. */
    internal fun closeNow() = runtime.closeNow()

    companion object {
        /**
         * Create an engine running [engineJs] — scrape `engine.js` by default (transport + protocol
         * layers + feed/search/login/download). MSE playback is a second runtime via
         * [io.ymusic.dynamicengine.player.DynamicEnginePlayer] (`player.js`). Wires the `"http"`
         * handler onto [client] before the boot eval so top-level bundle code can already call it.
         * Create just BOOTS — drive [healthCheck] afterwards if the caller gates on it (the manager does).
         *
         * NOT a suspend function on purpose: the whole boot — native engine, bundle eval, init
         * round-trip (the C layer pumps JS microtasks inside each call, so init has answered
         * before `open` returns) — runs synchronously on the current thread, which the CALLER
         * must ensure is [dispatcher]'s (`withContext(dispatcher) { create(...) }`). [dispatcher]
         * is the engine's JS confinement thread for its whole life — single-threaded, caller-owned
         * (see [QuickJsRuntime.newDispatcher]), closed by the caller after [close], never here.
         *
         * [appVersion] is the host app's version code. [engineJs] defaults to the build-time
         * baseline; the app passes a remotely-fetched bundle here, and falls back to the default
         * when that fails to load (a bad push must not brick the engine — a failure here is a
         * typed [EngineError], `E20020`). [logger] receives
         * host-protocol birth-site failures (null = logging off).
         */
        fun create(
            client: KmpHttpClient,
            appVersion: Int,
            dispatcher: CoroutineDispatcher,
            logger: Logger? = null,
            engineJs: String = ENGINE_JS,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            remoteFeature: (suspend (String) -> JsonElement?)? = null,
        ): DynamicEngine = createEngine(
            client, appVersion, dispatcher, logger, engineJs, cacheStore, cookieJar, settings, remoteFeature,
        )

        /** Create from [compile]d bytecode of a bundle — `versions.app` is only known here, so it
         *  can never be baked into the precompiled blob; C installs it before the bytecode runs.
         *  Same synchronous contract as the [String] overload: call ON [dispatcher]. */
        fun create(
            client: KmpHttpClient,
            appVersion: Int,
            engineBytecode: ByteArray,
            dispatcher: CoroutineDispatcher,
            logger: Logger? = null,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            remoteFeature: (suspend (String) -> JsonElement?)? = null,
        ): DynamicEngine = createEngineFromBytecode(
            client, appVersion, engineBytecode, dispatcher, logger, cacheStore, cookieJar, settings, remoteFeature,
        )

        internal fun createEngineFromBytecode(
            client: KmpHttpClient,
            appVersion: Int,
            engineBytecode: ByteArray,
            dispatcher: CoroutineDispatcher,
            logger: Logger? = null,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            remoteFeature: (suspend (String) -> JsonElement?)? = null,
            extraInstall: QuickJsRuntime.(ParkedLoggers) -> Unit = {},
        ): DynamicEngine = boot(logger, ParkedLoggers(logger)) { parked ->
            QuickJsRuntime.create(engineBytecode, appVersion, dispatcher, onLog = logger?.let(::jsLog)) {
                installHost(it, client, parked, cacheStore, cookieJar, settings, remoteFeature)
                it.extraInstall(parked)
            }
        }

        internal fun createEngine(
            client: KmpHttpClient,
            appVersion: Int,
            dispatcher: CoroutineDispatcher,
            logger: Logger? = null,
            engineJs: String = ENGINE_JS,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            remoteFeature: (suspend (String) -> JsonElement?)? = null,
            extraInstall: QuickJsRuntime.(ParkedLoggers) -> Unit = {},
        ): DynamicEngine = boot(logger, ParkedLoggers(logger)) { parked ->
            QuickJsRuntime.create(engineJs, appVersion, dispatcher, onLog = logger?.let(::jsLog)) {
                installHost(it, client, parked, cacheStore, cookieJar, settings, remoteFeature)
                it.extraInstall(parked)
            }
        }

        private fun installHost(
            runtime: QuickJsRuntime,
            client: KmpHttpClient,
            parked: ParkedLoggers,
            cacheStore: CacheStore?,
            cookieJar: CookieJar,
            settings: SettingsStore?,
            remoteFeature: (suspend (String) -> JsonElement?)?,
        ) {
            runtime.installHttp(client, parked)
            runtime.installCookies(cookieJar, parked)
            runtime.installCapture(cacheStore, parked)
            if (cacheStore != null) runtime.installCache(cacheStore, parked)
            if (settings != null) runtime.installSettings(settings, parked)
            runtime.installRemoteConfig(remoteFeature, parked)
        }

        /** Compile a bundle to QuickJS bytecode for [create] — per-function source text is
         *  stripped, so the blob carries no copy of the script. Compile once (e.g. after fetching
         *  a remote bundle), persist, and create from the bytes on later launches. */
        suspend fun compile(engineJs: String, filename: String = "<slice>"): ByteArray =
            QuickJsRuntime.compile(engineJs, filename)

        private fun boot(
            logger: Logger?,
            parked: ParkedLoggers,
            createRuntime: (ParkedLoggers) -> QuickJsRuntime,
        ): DynamicEngine {
            val runtime = try {
                createRuntime(parked)
            } catch (e: QuickJsException) {
                throw EngineError.Technical("E20020", "engine bundle failed to load: ${e.message}", null, null, null, e)
            }
            return DynamicEngine(runtime, logger, parked)
        }

        private val initJson = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class InitHead(val backendVersion: Int)

        /** The bundle's console + the unhandled-rejection tracker onto the app [Logger] —
         *  developer breadcrumbs (never shown to the user, per the two-channel error contract). */
        private fun jsLog(logger: Logger): (Int, String) -> Unit {
            val js = logger.fork("engine-js")
            return { level, message ->
                when (level) {
                    QuickJsRuntime.LOG_DEBUG -> js.debug { message }
                    QuickJsRuntime.LOG_INFO -> js.log(Logger.Level.INFO, message)
                    QuickJsRuntime.LOG_WARN -> js.log(Logger.Level.WARN, message)
                    QuickJsRuntime.LOG_REJECTION ->
                        js.log(Logger.Level.ERROR, "unhandled promise rejection: $message")
                    else -> js.log(Logger.Level.ERROR, message)
                }
            }
        }
    }
}
