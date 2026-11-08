package io.ymusic.download

import io.ymusic.download.internal.downloadDbDispatcher
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SegmentIndex
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import projectcommon.error.DISK_FULL_CODE
import projectcommon.error.NETWORK_CODE
import projectcommon.error.PLAYBACK_UNAVAILABLE_CODE
import projectcommon.error.TechnicalException
import projectcommon.error.codedError
import projectcommon.error.errorCode
import projectcommon.error.playabilityOf
import kotlin.random.Random

/**
 * Manages the download queue. The DB ([store]) is the single source of truth — the service holds no task
 * list, only the [maxConcurrent] worker slots; it just dispatches [changes] events that a UI-owned
 * [DownloadTasksAggregator] folds into a list while the screen is shown.
 *
 * Queue model: up to [maxConcurrent] tasks run at once; their player-response fetches — and the options
 * [probe] — go one at a time, at least 3s apart ([PlayerResponseGate]). Adding a
 * task fills a free slot; when a task finishes or is canceled, the next queued one takes the slot.
 * **[pause] is per-task** — it frees that slot and the queue keeps flowing; **[pauseAll] holds the whole
 * queue**. [resume]/[resumeAll] bring tasks back. Nothing auto-starts on [load] (a crash leaves tasks
 * PAUSED).
 *
 * Pause and cancel both cancel the task's [Job]; the difference is the recorded state and whether the
 * partially-downloaded files are kept (pause, for resume via the scratch length) or deleted (cancel).
 * Control methods are fire-and-forget — they launch into [scope].
 */
class DownloadService(
    private val store: DownloadStore,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    resolver: SabrSpecResolver,
    client: KmpHttpClient,
    userAgent: String,
    ffmpeg: Ffmpeg?,
    private val storage: DownloadStorage,
    /** How many downloads run at once — the concurrent byte-download + ffmpeg work. Player-response
     *  fetches are paced separately ([PlayerResponseGate]). */
    private val maxConcurrent: Int = 3,
    mediaCache: MediaCache? = null,
    /** Persistent segment index used to resume SABR downloads (share the player cache's index). */
    segmentIndex: SegmentIndex? = null,
    logger: Logger? = null,
    /** The user's chosen download-location folder uri, read fresh at publish time (shell sources it from
     *  settings, falling back to the platform's default downloads dir). Always concrete — the UX requires
     *  a location before a download runs. */
    private val downloadFolder: suspend () -> String,
    /** Optional hook to register a finished file into the local library (media-store); shell-provided. */
    private val onCompleted: (suspend (DownloadTask, DownloadPipeline.Result) -> Unit)? = null,
    /** The player-response JSON for a video (no PO token) — what [probe] reads the options from. */
    private val playerResponse: (suspend (videoId: String) -> String)? = null,
) {
    /** True where ffmpeg exists (Android/desktop). False on iOS — the UI hides MP3/video outputs there. */
    val ffmpegAvailable: Boolean = ffmpeg != null

    private val log = logger?.fork("download")?.fork("service")

    private val playerGate = PlayerResponseGate()

    private val pipeline = DownloadPipeline(
        SabrSpecResolver { videoId -> playerGate.run { resolver.resolve(videoId) } },
        client, userAgent, ffmpeg, storage, mediaCache, segmentIndex, logger,
    )

    private val _changes = MutableSharedFlow<DownloadChange>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Per-task change events. A UI builds a [DownloadTasksAggregator] over this + [snapshot] to render the
     *  queue while it's on screen; nothing is materialized here. */
    val changes: SharedFlow<DownloadChange> = _changes.asSharedFlow()

    // The worker slots. `gate` guards them, the global-pause flag, and the interrupt reasons.
    private val gate = Mutex()
    private var queuePaused = false
    private val active = mutableMapOf<String, Job>()
    private val interrupts = mutableMapOf<String, DownloadState>()

    private val initMutex = Mutex()
    private var initialized = false

    /**
     * One-time startup recovery, run lazily on first use (no explicit start-up call): anything left
     * running or queued — in-flight when the app was killed/crashed, or still waiting — is reset to
     * [DownloadState.PAUSED] so the queue never auto-starts and the user resumes explicitly.
     * Terminal/failed/already-paused tasks are kept. Scratch folders no resumable task owns are deleted —
     * the scratch root is app data storage, which the OS never trims. Idempotent.
     */
    internal suspend fun initIfNotYet() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            store.pauseActiveAndQueued(now())
            pruneOrphanScratch()
            initialized = true
        }
    }

    /** A one-off imperative read of the whole queue (headless callers/tests, and the aggregator's seed). */
    suspend fun snapshot(): List<DownloadTask> {
        initIfNotYet()
        return store.all()
    }

    /** What [videoId] can be downloaded as — the options dialog's input. */
    suspend fun probe(videoId: String): DownloadOptions {
        val fetch = playerResponse ?: throw TechnicalException("E70040", "download options: no player-response source")
        val json = playerGate.run { fetch(videoId) }
        return withContext(Dispatchers.Default) { DownloadOptions.parse(json, ffmpegAvailable) }
    }

    fun enqueue(request: DownloadRequest): String {
        val id = "${now()}-${request.videoId}-${Random.nextInt(1_000_000)}"
        val task = DownloadTask(
            id = id,
            videoId = request.videoId,
            title = request.title,
            artist = request.artist,
            thumbnailUrl = request.thumbnailUrl,
            format = request.format,
            requestedHeight = request.requestedHeight,
            fileName = request.fileName,
            audioTrackId = request.audioTrackId,
            captions = request.captions,
            audioLanguage = request.audioLanguage,
            captionLanguage = request.captionLanguage,
            audioQuality = request.audioQuality,
            state = DownloadState.QUEUED,
            downloadedBytes = 0,
            totalBytes = 0,
            fileUri = null,
            errorMessage = null,
            createdAt = now(),
            updatedAt = now(),
        )
        command(null) {
            initIfNotYet()
            store.upsert(task)
            _changes.emit(DownloadChange.Added(task))
            fillSlots()
        }
        return id
    }

    /** Pause one task and free its slot; the next queued task takes it (the queue keeps flowing). */
    fun pause(id: String) {
        // Act under the same lock fillSlots claims with, so a QUEUED task can't be started between the
        // read and the pause (which would lose the pause, or leave a "paused" task still downloading).
        command(id) {
            gate.withLock {
                val running = active[id]
                if (running != null) {
                    interrupts[id] = DownloadState.PAUSED
                    running.cancel() // execute → PAUSED; completion frees the slot and fillSlots continues
                } else if (store.get(id)?.state == DownloadState.QUEUED) {
                    setState(id, DownloadState.PAUSED)
                }
            }
        }
    }

    fun resume(id: String) {
        command(id) {
            val task = store.get(id) ?: return@command
            if (task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED) {
                gate.withLock { queuePaused = false } // resuming a task un-holds the queue
                setState(id, DownloadState.QUEUED, error = null)
                fillSlots()
            }
        }
    }

    /** Alias for [resume] on a failed task — the same "back to QUEUED" transition. */
    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        command(id) {
            gate.withLock {
                val running = active[id]
                if (running != null) {
                    interrupts[id] = DownloadState.CANCELED
                    running.cancel()
                } else {
                    cleanupTemp(id)
                    setState(id, DownloadState.CANCELED)
                }
            }
        }
    }

    /** Remove a task (and its temp files) entirely. The finished output file is left on disk. */
    fun remove(id: String) {
        // Decide + delete under the claim lock so a QUEUED task can't be started into a deleted row. An
        // active task is cancelled — its execute cleans up its own scratch on the CANCELED path; a
        // non-active one is cleaned up here.
        command(id) {
            gate.withLock {
                val running = active[id]
                if (running != null) {
                    interrupts[id] = DownloadState.CANCELED
                    running.cancel()
                } else {
                    cleanupTemp(id)
                }
                store.delete(id)
            }
            _changes.emit(DownloadChange.Removed(id))
        }
    }

    /** Point a completed task at its file's new uri, after the file was renamed or moved outside the service. */
    suspend fun relocate(id: String, fileUri: String) {
        val task = store.get(id)?.takeIf { it.state == DownloadState.COMPLETED } ?: return
        val bytes = task.totalBytes ?: task.downloadedBytes
        store.updateCompleted(id, fileUri, bytes, task.loudnessGainDb, now())
        _changes.emit(DownloadChange.Completed(id, fileUri, bytes))
    }

    /** Global pause: hold the whole queue — stop every active download and every queued task. */
    fun pauseAll() {
        command(null) {
            gate.withLock {
                queuePaused = true
                active.forEach { (id, job) -> interrupts[id] = DownloadState.PAUSED; job.cancel() }
            }
            store.all().filter { it.state == DownloadState.QUEUED }.forEach { setState(it.id, DownloadState.PAUSED) }
        }
    }

    /** Global resume: re-queue every paused task and run up to [maxConcurrent] of them. */
    fun resumeAll() {
        command(null) {
            gate.withLock { queuePaused = false }
            store.all().filter { it.state == DownloadState.PAUSED }.forEach { setState(it.id, DownloadState.QUEUED) }
            fillSlots()
        }
    }

    // --- worker --------------------------------------------------------------------------------------

    /** Start queued tasks until [maxConcurrent] are running (unless the queue is globally paused). Each
     *  pick is claimed as PREPARING under the lock so the next [DownloadStore.nextQueued] skips it. */
    private fun fillSlots() {
        command(null) {
            gate.withLock {
                if (queuePaused) return@withLock
                while (active.size < maxConcurrent) {
                    val next = store.nextQueued() ?: break
                    // Unreachable by construction (see runTask); never start a second run on one scratch dir.
                    if (next.id in active) break
                    try {
                        setState(next.id, DownloadState.PREPARING)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Throwable) {
                        commandFailed(next.id, e)
                        break
                    }
                    active[next.id] = scope.launch { runTask(next) }
                }
            }
        }
    }

    /** Runs a queue command; a failure (typically a DB write on full storage) is logged and, for a
     *  task command, shown on that task instead of escaping to the scope's crash handler. */
    private fun command(id: String?, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                commandFailed(id, e)
            }
        }
    }

    private suspend fun commandFailed(id: String?, e: Throwable) {
        val commandLog = log?.fork("command", id?.let { mapOf("id" to it) })
        if (e.errorCode() == DISK_FULL_CODE) {
            commandLog?.log(Logger.Level.WARN, "download command hit full storage", e)
        } else {
            commandLog?.captureError(e, "download command failed")
        }
        if (id != null) _changes.emit(DownloadChange.StateChanged(id, DownloadState.FAILED, downloadError(e)))
    }

    /**
     * One run of [task]. It holds its slot until it has fully unwound, and writes its end state
     * (PAUSED / CANCELED / FAILED) in the same locked step that frees the slot: a task is only
     * resumable once nothing of its previous run can still touch its scratch files or its row.
     */
    private suspend fun runTask(task: DownloadTask) {
        var failure: DownloadError? = null
        var interrupted = false
        try {
            failure = execute(task)
        } catch (c: CancellationException) {
            interrupted = true
            throw c
        } catch (e: Throwable) {
            // Outside the pipeline (e.g. a state/progress DB write): still an end state, never a row
            // stuck in DOWNLOADING with no slot.
            val taskLog = log?.fork("task", mapOf("id" to task.id, "videoId" to task.videoId))
            if (e.errorCode() == DISK_FULL_CODE) {
                taskLog?.log(Logger.Level.WARN, "download run hit full storage", e)
            } else {
                taskLog?.captureError(e, "download run failed")
            }
            failure = downloadError(e)
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    try {
                        val target = interrupts.remove(task.id)
                        val completed = store.get(task.id)?.state == DownloadState.COMPLETED
                        if (interrupted && !completed) {
                            val state = target ?: DownloadState.PAUSED
                            if (state == DownloadState.CANCELED) cleanupTemp(task.id)
                            setState(task.id, state)
                        } else if (failure != null && !completed) {
                            setState(task.id, DownloadState.FAILED, error = failure)
                        }
                    } catch (e: Throwable) {
                        endStateNotRecorded(task, e)
                    } finally {
                        active.remove(task.id)
                    }
                }
                fillSlots()
            }
        }
    }

    /** Runs the pipeline; returns null on success, the user-facing error on failure (already logged). */
    private suspend fun execute(task: DownloadTask): DownloadError? = coroutineScope {
        // Pipeline callbacks fire from any thread: one writer applies them in order, and all of them have
        // landed (or been dropped, on cancel) before the run's final state is written.
        val updates = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val writer = launch { for (update in updates) update() }
        val result = try {
            pipeline.run(
                task,
                destinationFolder = downloadFolder(),
                onPreparing = { updates.trySend { setState(task.id, DownloadState.PREPARING) } },
                onDownloading = { updates.trySend { setState(task.id, DownloadState.DOWNLOADING) } },
                onProcessing = { updates.trySend { setState(task.id, DownloadState.PROCESSING) } },
                onProgress = { done, total -> updates.trySend { setProgress(task.id, done, total) } },
                onStreams = { format, audio, video ->
                    store.updateStreams(task.id, format, audio, video, now())
                    if (format != task.format) _changes.emit(DownloadChange.FormatChanged(task.id, format))
                },
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            updates.close()
            writer.join()
            // Log the full technical failure here; the user sees only a code. An uncoded exception
            // (SABR, disk, …) gets the E70001 fallback so every failure is reportable.
            val taskLog = log?.fork("task", mapOf("id" to task.id, "videoId" to task.videoId))
            val code = e.errorCode()
            when {
                playabilityOf(code) != null -> taskLog?.log(Logger.Level.WARN, "download refused by playability", e)
                code == DownloadFolderAccessException.CODE -> taskLog?.log(Logger.Level.WARN, "download folder not writable", e)
                code == DISK_FULL_CODE -> taskLog?.log(Logger.Level.WARN, "download hit full storage", e)
                code == NETWORK_CODE -> taskLog?.log(Logger.Level.WARN, "download hit a network failure", e)
                code == PLAYBACK_UNAVAILABLE_CODE -> taskLog?.log(Logger.Level.WARN, "download needs the media library", e)
                code == StillProcessingException.CODE -> taskLog?.log(Logger.Level.WARN, "download of a replay still processing", e)
                else -> taskLog?.captureError(e, "download failed")
            }
            return@coroutineScope downloadError(e)
        }
        // The file is published: the task has succeeded, even if a pause/cancel lands now. A failure past
        // this point must not fail it — a retry would publish a second copy.
        withContext(NonCancellable) {
            updates.close()
            writer.join()
            store.updateCompleted(task.id, result.uri, result.bytes, result.loudnessGainDb, now())
            _changes.emit(DownloadChange.Completed(task.id, result.uri, result.bytes))
            try {
                store.get(task.id)?.let { onCompleted?.invoke(it, result) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // The file stays downloaded; the Library picks it up on its next scan.
                log?.fork("task", mapOf("id" to task.id, "videoId" to task.videoId))
                    ?.captureError(e, "registering a finished download in the library failed")
            }
        }
        null
    }

    // --- writes (DB + change event) ------------------------------------------------------------------

    private suspend fun setState(id: String, state: DownloadState, error: DownloadError? = null) {
        store.updateState(id, state, error, now())
        _changes.emit(DownloadChange.StateChanged(id, state, error))
    }

    private suspend fun setProgress(id: String, done: Long, total: Long) {
        store.updateProgress(id, done, total, now())
        _changes.emit(DownloadChange.ProgressChanged(id, done, total))
    }

    /** The row could not take the run's end state (e.g. SQLITE_FULL): the task is reported FAILED to
     *  listeners all the same, so the card doesn't sit in DOWNLOADING. */
    private suspend fun endStateNotRecorded(task: DownloadTask, e: Throwable) {
        val taskLog = log?.fork("task", mapOf("id" to task.id, "videoId" to task.videoId))
        if (e.errorCode() == DISK_FULL_CODE) {
            taskLog?.log(Logger.Level.WARN, "download end state not recorded: storage full", e)
        } else {
            taskLog?.captureError(e, "download end state not recorded")
        }
        _changes.emit(DownloadChange.StateChanged(task.id, DownloadState.FAILED, downloadError(e)))
    }

    private fun downloadError(e: Throwable): DownloadError {
        val coded = e.codedError()
        return DownloadError(coded?.code ?: "E70001", coded?.userMessage)
    }

    private suspend fun pruneOrphanScratch() {
        val resumable = store.all().filter { !it.state.isTerminal }.mapTo(HashSet()) { it.id }
        withContext(downloadDbDispatcher()) {
            val owners = try {
                storage.scratchTaskIds()
            } catch (e: okio.IOException) {
                log?.log(Logger.Level.WARN, "scratch listing failed (orphans leak until next run)", e)
                return@withContext
            }
            owners.filter { it !in resumable }.forEach(::cleanupTemp)
        }
    }

    private fun cleanupTemp(id: String) {
        runCatching { storage.deleteScratch(id) }
            .onFailure { log?.log(Logger.Level.WARN, "scratch cleanup failed for $id (files leak until next run)", it) }
    }
}
