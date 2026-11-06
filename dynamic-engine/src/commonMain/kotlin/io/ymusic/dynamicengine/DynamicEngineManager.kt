package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.DynamicEngineManager.Companion.BOOT_STINT_MS
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.SettingsStore
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Owns **the current [DynamicEngine]** and the remote-update seam: [swap] boots a new bundle,
 * health-checks it, flips new work onto it, and retires the old engine gracefully — the reload
 * path that ships YouTube feed-shape fixes without an app release.
 *
 * Retirement is Node's SIGINT over pipes: the old engine's `"shutdown"` command tells the bundle
 * to stop repeat work, then the manager waits (bounded by [drainTimeoutMs]) for the engine to
 * drain to idle — in-flight jobs finish, cron pipes close — and closes it. A wedged engine hits
 * the deadline and is force-closed; its callers get the typed close error and retry on the new
 * engine (scrape jobs are idempotent reads).
 *
 * Task methods route through [use], which pins the engine current *at call start* — a swap mid-job
 * never reroutes or aborts that job. Swaps are serialized; the flip itself is a lock-protected
 * reference write, so [use] never waits on a swap in progress.
 */
@OptIn(ExperimentalAtomicApi::class)
class DynamicEngineManager(
    private val client: KmpHttpClient,
    private val appVersion: Int,
    private val logger: Logger? = null,
    /** Where the engine bundle comes from: [start] boots `getCached() ?: getEmbed()` (a rejected
     *  cache falls back to the embed); from then on every config the app receives swaps in a newer
     *  published bundle that passes the boot gate. Apps wire a [DefaultJsSourceProvider] over the asset-shipped bundle;
     *  fixed-bundle callers (tests, probes, the iOS lab) use [StaticJsSource] /
     *  [embeddedEngineJsSource]. */
    private val jsSource: JsSourceProvider,
    private val cacheStore: CacheStore? = null,
    private val drainTimeoutMs: Long = 30_000L,
    /** The app-owned cookie store the cookie protocol serves (see [DynamicEngine.create]). */
    private val cookieJar: CookieJar = client.cookieJar,
    private val settings: SettingsStore? = null,
    /** `remote-config/get`'s answers; null = every read gets its default. */
    private val remoteFeature: (suspend (String) -> JsonElement?)? = null,
    /** Debug builds: a task that held the engine thread past [BOOT_STINT_MS] THROWS on it
     *  (StrictMode's penaltyDeath — unmissable in development), and slow-stint logs carry a live
     *  stack, minus any time an OS freezer stopped the whole process. Otherwise the watchdog only
     *  times tasks and logs to [logger]: two clock reads per task, no stack, frozen time counted. */
    private val strictStints: Boolean = false,
    /** Deadline for booting a REMOTE bundle ([start]'s remoteJs / [swap]) — untrusted code must
     *  not be able to hang the boot path. The baked-in baseline boots without one. */
    private val remoteBootTimeoutMs: Long = 10_000L,
    /** Fired with the running bundle's self-declared revision each time one goes live ([start]
     *  and every successful [swap]) — the shells stamp it onto the Sentry scope. */
    private val onBackendVersion: ((Int) -> Unit)? = null,
) {

    private val stintLog = logger?.fork("engine-stint")
    private val mgrLog = logger?.fork("engine-manager")

    /** Hosts remote boots so a timed-out (orphaned) one outlives its caller; cancelled on [close]. */
    private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A booted engine + the JS thread it lives on ([dispatcher] closes after the engine does). */
    private class Slot(val engine: DynamicEngine, val dispatcher: kotlinx.coroutines.CloseableCoroutineDispatcher) {
        val inFlight = AtomicInt(0)
        val retired = AtomicBoolean(false)
        val drained = CompletableDeferred<Unit>()

        fun enter() {
            inFlight.addAndFetch(1)
        }

        fun exit() {
            if (inFlight.addAndFetch(-1) == 0 && retired.load()) drained.complete(Unit)
        }

        fun retire() {
            retired.store(true)
            if (inFlight.load() == 0) drained.complete(Unit)
        }
    }

    private val activeLock = Mutex() // guards the `active` reference only — never held while working
    private var active: Slot? = null

    private val swapLock = Mutex() // serializes start/swap/close end to end

    // Each engine gets its OWN JS thread for life (DynamicEngine.create's synchronous contract),
    // created with the engine and closed after it retires — so booting a new engine (bundle eval +
    // health check, easily 100ms+) never holds the thread the SERVING engine runs on: during a
    // swap the old engine keeps answering on its thread while the new one boots on a fresh one.
    // Shared host state tolerates the brief two-thread overlap: DiskCacheStore serializes on its
    // own Mutex + IO dispatcher, the cookie jar is the platform's thread-safe store, and
    // ParkedLoggers is deliberately unlocked (misses degrade to root).
    // The watchdog on every engine thread: a task that HELD the thread 50ms+ in one stretch logs
    // (ERROR past [BOOT_STINT_MS], where [strictStints] also throws and adds the culprit's stack).
    // A task that WAITED 50ms+ for the thread (the victim — cumulative congestion) logs WARN.
    /**
     * An engine's private JS thread. [booting] is THIS thread's stint grace: while the engine on
     * it boots, the watchdog answers to [BOOT_STINT_MS] — the bundle eval + health check is one
     * irreducible >50ms stint by nature, and crashing on it teaches nothing (YMUSIC-KMP-5); past
     * even the boot budget, a stall is a real bug again. Per-thread state, so a boot never
     * relaxes another engine's watchdog. Volatile: cleared on the boot caller's thread, read by
     * the watchdog on this thread.
     */
    private inner class EngineThread {
        @kotlin.concurrent.Volatile
        var booting = true

        private val watched = strictStints || stintLog != null

        val dispatcher = QuickJsRuntime.newDispatcher(
            slowStintMs = if (watched) SLOW_STINT_MS else 0L,
            onSlowStint = { ms, paused, detail ->
                val held = ms - paused
                if (!booting || held >= BOOT_STINT_MS) {
                    if (held >= SLOW_STINT_MS) {
                        val budget = if (booting) BOOT_STINT_MS else SLOW_STINT_MS
                        val message = "engine thread held for ${held}ms by one task (budget ${budget}ms) — " +
                            "every pipe, timer and command stalls while the thread is held" +
                            pausedNote(paused) + "\n$detail"
                        val level = if (held >= BOOT_STINT_MS) Logger.Level.ERROR else Logger.Level.WARN
                        stintLog?.log(level, message)
                        /* A 50ms stint is a bug to find, not a reason to kill playback.
                           Throw only past the boot budget — that is a real hang. */
                        if (strictStints && held >= BOOT_STINT_MS) throw IllegalStateException(message)
                    } else if (paused > 0L) {
                        stintLog?.log(Logger.Level.INFO, "process frozen ${paused}ms during an engine task (OS freezer)")
                    }
                }
            },
            slowWaitMs = if (watched) SLOW_STINT_MS else 0L,
            onSlowWait = { ms ->
                stintLog?.log(
                    Logger.Level.WARN,
                    "a task waited ${ms}ms for the engine thread (budget ${SLOW_STINT_MS}ms) — " +
                        "the thread was congested, though no single task held it over budget",
                )
            },
            liveStacks = strictStints,
        )
    }

    // Boot + gate: the manager is the accept/reject seam for bundles, so IT drives the health
    // check — create itself just boots. The engine boots on its OWN fresh dispatcher (a serving
    // engine is never stalled by a boot); a failed boot closes the half-born engine AND its
    // never-activated thread here.
    private suspend fun createEngine(js: String): Slot {
        val thread = EngineThread()
        try {
            return withContext(thread.dispatcher) {
                val engine = DynamicEngine.create(client, appVersion, thread.dispatcher, logger, js, cacheStore, cookieJar, settings, remoteFeature)
                try {
                    engine.healthCheck()
                } catch (t: Throwable) {
                    runCatching { engine.closeNow() }.exceptionOrNull()?.let(t::addSuppressed)
                    throw t
                }
                // Orphaned by the boot deadline (cancelled while the eval held this thread):
                // withContext discards the result, so nobody can ever receive this engine — free
                // it now, before the cancellation propagates.
                if (!isActive) runCatching { engine.closeNow() }
                Slot(engine, thread.dispatcher)
            }
        } catch (t: Throwable) {
            runCatching { thread.dispatcher.close() }.exceptionOrNull()?.let(t::addSuppressed)
            throw t
        } finally {
            thread.booting = false
        }
    }

    /**
     * Boot an UNTRUSTED (remote) bundle with a deadline. A hostile or broken bundle can hang boot
     * two ways: a stuck eval (uninterruptible — the boot is orphaned and its daemon thread lives
     * until the eval ends, self-disposing then) or a health check that never answers (cancellable
     * — clean teardown). Either way the caller gets the typed E20022 inside [remoteBootTimeoutMs]
     * and treats it like any rejected bundle; the serving engine is untouched throughout.
     */
    private suspend fun createEngineWithDeadline(js: String): Slot {
        // NOT a plain withTimeout(createEngine(js)): a timeout only DELIVERS at a suspension
        // point, and a bundle stuck in its eval never reaches one — the caller would stay
        // suspended (holding swapLock) for as long as the hostile eval runs, and the eval's own
        // eventual error would outrace the timeout. The boot must be detached (bootScope) so the
        // caller can stop AWAITING at the deadline while the boot runs on to self-dispose.
        val boot = bootScope.async { createEngine(js) }
        return try {
            // Wall-clock deadline (Dispatchers.Default): virtual-time test dispatchers must not skip it.
            withContext(Dispatchers.Default) { withTimeout(remoteBootTimeoutMs) { boot.await() } }
        } catch (e: TimeoutCancellationException) {
            boot.cancel()
            throw EngineError.Technical(
                "E20022",
                "remote bundle boot timed out after ${remoteBootTimeoutMs}ms",
                null,
                null,
                null,
                e,
            )
        }
    }

    /** Whether the last retirement drained inside the deadline (vs. force-closed). Test seam. */
    internal var lastRetirementDrained: Boolean = true

    /** The current bundle's self-declared revision, or null before [start]. */
    suspend fun backendVersion(): Int? = activeLock.withLock { active?.engine?.backendVersion }

    /**
     * Boot the first engine from [jsSource]: the cached remote bundle (never older than the embed)
     * when there is one — a failure to boot it is LOGGED and the embed boots instead, a bad push must
     * never brick the engine — else the embed. An embed failure is a build defect — it throws.
     * Returns the running bundle's backendVersion.
     */
    suspend fun start(): Int = swapLock.withLock {
        check(activeLock.withLock { active } == null) { "already started" }
        val slot = bootFromSource(jsSource)
        activeLock.withLock { active = slot }
        followPublished()
        checkNotNull(slot.engine.backendVersion).also { onBackendVersion?.invoke(it) } // health-checked
    }

    private suspend fun bootFromSource(source: JsSourceProvider): Slot {
        val cached = try {
            source.getCached()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            mgrLog?.log(Logger.Level.WARN, "cached bundle unavailable — booting the embed", e)
            null
        }
        if (cached != null) {
            try {
                return createEngineWithDeadline(cached.first)
            } catch (e: EngineError) {
                mgrLog?.log(
                    Logger.Level.WARN,
                    "${e.code} cached bundle rejected at boot, falling back to the embed: ${e.message}",
                    e,
                )
            }
        }
        return createEngine(source.getEmbed().first)
    }

    /** Published bundles that failed their signature or the boot gate — not tried again by this
     *  manager; a newly published one is. Touched only by the one [followPublished] collector. */
    private val rejected = mutableSetOf<SignedBundleRef>()

    private var following = false

    /** From [start] on, every config the app receives swaps in the bundle it names when newer. */
    private fun followPublished() {
        if (following) return
        following = true
        bootScope.launch {
            jsSource.published.conflate().collect { remote ->
                try {
                    updateTo(remote)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: BundleSignatureException) {
                    mgrLog?.captureError(e, "engine.js update rejected")
                } catch (e: Exception) {
                    mgrLog?.log(Logger.Level.WARN, "engine.js update failed", e)
                }
            }
        }
    }

    /** Download, verify and swap to [remote] when it is newer than the running bundle, then cache
     *  it. Returns the new version, else null. */
    internal suspend fun updateTo(remote: SignedBundleRef?): Int? {
        if (remote == null || remote in rejected) return null
        val current = backendVersion()?.toLong() ?: return null
        if (remote.version <= current) return null
        val fetched = try {
            jsSource.download(remote)
        } catch (e: BundleSignatureException) {
            rejected += remote
            throw e
        }
        val version = try {
            swap(fetched.source)
        } catch (e: EngineError) {
            rejected += remote
            throw e
        }
        try {
            jsSource.store(fetched)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // Running regardless; the next launch boots the previous bundle and swaps again.
            mgrLog?.log(Logger.Level.WARN, "engine.js v${fetched.version} is live but could not be cached", e)
        }
        return version
    }

    /**
     * Reload: boot [newJs], health-check it, flip new work onto it, then retire the old engine
     * (shutdown signal → bounded drain → close). Throws the typed [EngineError] (`E20020`/`E20021`
     * or the bundle's own code) when the new bundle is rejected — the current engine keeps
     * serving, unchanged. The flip happens BEFORE the drain: new jobs never wait on (or run on)
     * the outgoing engine. Returns the new bundle's backendVersion.
     */
    suspend fun swap(newJs: String): Int = swapLock.withLock {
        val fresh = createEngineWithDeadline(newJs) // throws typed on rejection/timeout; boots on its own thread
        val old = activeLock.withLock {
            val prev = active
            active = fresh
            prev
        }
        mgrLog?.log(
            Logger.Level.INFO,
            "engine ${old?.engine?.backendVersion} -> ${fresh.engine.backendVersion}",
        )
        if (old != null) retire(old)
        checkNotNull(fresh.engine.backendVersion).also { onBackendVersion?.invoke(it) } // health-checked
    }

    /** Run a task on the engine that is current NOW — pinned for the whole block, so a concurrent
     *  [swap] neither reroutes nor aborts it (the old engine drains until this exits). */
    internal suspend fun <T> use(block: suspend (DynamicEngine) -> T): T {
        jsSource.checkUpdate()
        // enter() under the lock: entered-or-not-visible. Pinned outside it, a just-retired slot
        // could count us after its drain already completed and close under the running block.
        val slot = activeLock.withLock { (active ?: error("manager not started")).also { it.enter() } }
        try {
            return block(slot.engine)
        } finally {
            slot.exit()
        }
    }

    /** Close the current engine and its JS thread (no drain — app teardown; terminal). */
    suspend fun close(): Unit = swapLock.withLock {
        val slot = activeLock.withLock { val s = active; active = null; s }
        slot?.engine?.close()
        slot?.dispatcher?.close()
        bootScope.cancel()
    }

    private suspend fun retire(slot: Slot) {
        slot.retire()
        // Wall-clock deadline (Dispatchers.Default): virtual-time test dispatchers must not skip it.
        val drained = withContext(Dispatchers.Default) {
            withTimeoutOrNull(drainTimeoutMs) {
                signalShutdown(slot.engine)
                slot.drained.await() // Kotlin-initiated jobs out of [use]
                slot.engine.awaitIdle() // every pipe closed: jobs done, crons cleared, ack observed
            }
        } != null
        lastRetirementDrained = drained
        if (!drained) {
            mgrLog?.log(
                Logger.Level.WARN,
                "retired engine ${slot.engine.backendVersion} did not drain in ${drainTimeoutMs}ms — force-closing",
            )
        }
        slot.engine.close()
        slot.dispatcher.close() // the retired engine's thread dies with it
    }

    /** SIGINT, best-effort: a bundle without the lifecycle module refuses the open — skip (the
     *  drain deadline and close() are the real backstop, so a failure here is deliberately only
     *  logged-by-outcome, never propagated). */
    private suspend fun signalShutdown(engine: DynamicEngine) {
        runCatching { engine.signalShutdown() }
    }

    private companion object {
        fun pausedNote(paused: Long) = if (paused > 0L) " (plus ${paused}ms the whole process was frozen)" else ""

        /** One stint at/over this holds the engine thread long enough to feel: log it. */
        const val SLOW_STINT_MS = 50L

        /** The stint budget while an engine BOOTS ([booting]): the bundle eval + health check is
         *  one irreducible stint that runs ~50-100ms on real phones (YMUSIC-KMP-5 crashed a debug
         *  launch at 56ms) — only a stall past this still throws during boot. */
        const val BOOT_STINT_MS = 500L
    }
}
