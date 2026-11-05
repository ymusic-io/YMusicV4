package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.RemoteConfigData.Companion.value
import kmpcommon.io.asAsyncSource
import kmpcommon.io.buffer
import kmpcommon.log.Logger
import kmpcommon.log.info
import kmphttp.HttpStatusException
import kmphttp.json.JsonReader
import kmphttp.json.JsonWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import projectcommon.error.DISK_FULL_CODE
import projectcommon.error.errorCode

/**
 * Five minutes. Long enough that hammering [RemoteConfig.checkUpdate] from every screen costs one
 * request, short enough that a scraping fix reaches a running app while someone is still using it.
 */
const val REMOTE_CONFIG_MIN_INTERVAL_MS: Long = 5 * 60 * 1000L

/**
 * The app's view of the remote config: feature flags, the newest JS bundle revisions, and whether a
 * newer build exists. How the service is reached is [ApiService]'s business; this class owns only
 * the policy — the throttle, the on-disk cache, and what counts as fresh.
 *
 * [config] is the one way to read it: the defaults until the cache of the previous run loads, then every
 * config fetched since. A fetch that fails publishes nothing, so the previous value stays: a service
 * outage must not change how the app behaves.
 *
 * Nothing polls. [checkUpdate] is called wherever the config is used — app open, every engine and
 * player operation — and fetches only when what is held is older than the caller accepts, so an idle
 * app in the background makes no requests and keeps no timer alive to wake the device for.
 *
 * What gets cached for the next cold start is the parsed [RemoteConfigData], re-encoded. Outside
 * [Features], a field this build does not know is therefore not preserved across a restart; it
 * arrives again on the first refresh.
 */
class RemoteConfig(
    /** Null when this build has no config service: it runs on the cache, or on the defaults. */
    private val api: ApiService?,
    /** Where the last answer is kept for the next cold start. Written and read as a stream. */
    private val cacheFile: Path,
    private val fileSystem: FileSystem,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val log: Logger? = null,
    /** The cache file's reads and writes; [scope] may be the main thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _config = MutableStateFlow(RemoteConfigData())
    val config: StateFlow<RemoteConfigData> = _config.asStateFlow()
    val features: Flow<Features> = _config.map { it.features }.distinctUntilChanged()
    val appUpdate: Flow<AppUpdate?> = _config.map { it.appUpdate }.distinctUntilChanged()
    val engineJs: Flow<BundleVersion?> = _config.map { it.engineJs }.distinctUntilChanged()
    val playerJs: Flow<BundleVersion?> = _config.map { it.playerJs }.distinctUntilChanged()
    val sentryTags: Flow<Map<String, String>> = _config.map { it.sentryTags }.distinctUntilChanged()

    private val mutex = Mutex()
    private var initialized = false
    private var inFlight: Job? = null

    init {
        scope.launch { initIfNotYet() }
    }

    private suspend fun initIfNotYet() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            // The whole read is guarded, not just the parse: exists() and source() touch the file
            // system too, and this runs from init on a scope whose cancellation would take the app
            // with it. Starting on the defaults is always better than not starting.
            val latestValue = try {
                withContext(io) {
                    if (fileSystem.exists(cacheFile)) {
                        RemoteConfigData.from(
                            fileSystem.source(cacheFile).use { source ->
                                JsonReader(source.asAsyncSource().buffer()).readObject()
                            },
                            now,
                        )
                    } else {
                        RemoteConfigData()
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // Best effort: a cache that cannot be read is worse than none, but failing to
                // remove it must not become a second failure on top of the first.
                runCatching { fileSystem.delete(cacheFile) }.exceptionOrNull()?.let(error::addSuppressed)
                log?.captureError(error, "read config cache failure")
                RemoteConfigData()
            }
            initialized = true
            _config.value = latestValue
            return
        }
    }

    /**
     * Fetch in the background when the config is older than [acceptMaxAgeMs]; otherwise do nothing.
     *
     * Returns immediately and reports nothing — a fetched config arrives through [config], a failed
     * one is logged and leaves [config] as it was. Only a SUCCESSFUL fetch moves
     * [RemoteConfigData.updatedAt], so an outage does not buy the service a quiet window.
     */
    fun checkUpdate(acceptMaxAgeMs: Long = REMOTE_CONFIG_MIN_INTERVAL_MS) {
        if (api == null || inFlight?.isActive == true) return
        if (isFresh(_config.value, acceptMaxAgeMs)) return

        scope.launch {
            initIfNotYet()
            // Re-checked inside: initIfNotYet may have just loaded a cache newer than the window.
            if (isFresh(_config.value, acceptMaxAgeMs)) return@launch
            fetch().forget()
        }
    }

    private fun isFresh(data: RemoteConfigData, maxAgeMs: Long): Boolean = now() - data.updatedAt < maxAgeMs

    /** The config once the previous run's cache has loaded (or none existed), never the boot-time default stand-in. */
    suspend fun awaitLoaded(): RemoteConfigData {
        initIfNotYet()
        return _config.value
    }

    /**
     * For a person waiting on an answer ("Check for update"): fetch now and wait up to [timeoutMs]
     * for the result. Null when none arrives in time — failed, or no service.
     */
    suspend fun awaitFresh(timeoutMs: Long): RemoteConfigData? {
        initIfNotYet()
        val held = _config.value
        checkUpdate(acceptMaxAgeMs = 0L)
        return withTimeoutOrNull(timeoutMs) { config.first { it != held } }
    }

    private suspend fun fetch(): Job {
        // fetch update is app scope, not callable by caller
        check(initialized) { "Do init first" }
        val api = checkNotNull(api) { "api = null" }
        inFlight?.takeIf { it.isActive }?.let { return it }
        mutex.withLock {
            inFlight?.takeIf { it.isActive }?.let { return it }

            return scope.launch {
                try {
                    val data = api.getConfig()
                    try {
                        withContext(io) { cache(data) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        // A full or failing disk is the device's state; the fetched config is still published.
                        if (error.errorCode() == DISK_FULL_CODE || error is IOException) {
                            log?.log(Logger.Level.WARN, "Write config cache failed", error)
                        } else {
                            log?.captureError(error, "Write config cache failure")
                        }
                    }
                    _config.value = data
                } catch (cancel: CancellationException) {
                    // Rethrown, not logged: cancelling the scope is not a fetch failure, and
                    // swallowing it here would leave the coroutine reported as completed normally.
                    throw cancel
                } catch (refused: HttpStatusException) {
                    // A refusal the server may recover from is a state; one it will not is a bug.
                    // The last good value stays published either way.
                    // 403 is the edge refusing this client (WAF/bot rules hit data-center IPs such as
                    // Play pre-launch devices), not a malformed request from this build.
                    if (refused.retryable) {
                        log?.info("Fetch config: ${refused.message}")
                    } else if (refused.code == 403) {
                        log?.log(Logger.Level.WARN, "Fetch config refused", refused)
                    } else {
                        log?.captureError(refused, "Fetch config failed")
                    }
                } catch (offline: IOException) {
                    // Being offline is a state, not a bug: retrying is the fix, and checkUpdate
                    // will — nothing to capture.
                    log?.info("Fetch config: ${offline.message}")
                } catch (error: Throwable) {
                    log?.captureError(error, "Fetch config failed")
                }
                // Not nulled on completion: the body can finish before `also` below assigns the
                // field, which would park a COMPLETED Job here forever. Every reader tests
                // isActive instead.
            }.also {
                inFlight = it
            }
        }
    }

    private fun cache(data: RemoteConfigData) {
        val tmp = cacheFile.parent?.resolve(cacheFile.name + ".tmp") ?: return
        cacheFile.parent?.let { fileSystem.createDirectories(it) }
        val writer = JsonWriter.Sync()
        writer.value(data)
        // The document is a few hundred bytes, so it is written whole and drained once. okio's
        // writeAll moves segments rather than copying them.
        fileSystem.openReadWrite(tmp).use { handle ->
            handle.resize(0)
            handle.sink().buffer().use { out -> out.writeAll(writer.buffer) }
            // fsync before the rename: otherwise a power loss can persist the rename but not the
            // data, leaving an empty cache file (YMUSIC-KMP-8F).
            handle.flush()
        }
        fileSystem.atomicMove(tmp, cacheFile)
    }

    @Suppress("UnusedReceiverParameter")
    private fun Job.forget() = Unit
}
