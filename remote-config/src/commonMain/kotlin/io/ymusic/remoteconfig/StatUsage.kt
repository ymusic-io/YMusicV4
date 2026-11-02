package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.PulseEvent.Companion.value
import kmpcommon.log.Logger
import kmpcommon.log.info
import kmphttp.json.JsonWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/** Minimum spacing between flushes: at most one request per five minutes, whatever happens. */
const val STAT_FLUSH_INTERVAL_MS: Long = 5 * 60 * 1000L

/** A full pulse ([PULSE_MAX_EVENTS]): worth scheduling the next flush for when the interval ends. */
const val STAT_FLUSH_BATCH = PULSE_MAX_EVENTS

/** More than this many queued events means nobody is listening; the oldest stop mattering. */
private const val MAX_QUEUE = 200

/**
 * Anonymous usage stats, gated on the "Send anonymous stats" answer and built to cost nothing:
 * disabled means [record] returns before doing any work at all.
 *
 * The queue is the in-memory list; disk is its crash mirror, an APPEND-ONLY file of one JSON
 * event per line — recording costs one appended line, never a read, and a torn last line from a
 * crash mid-append is skipped at load without taking the rest with it. The file is compacted
 * only at flush time (rewritten to the unsent remainder, deleted when drained).
 *
 * Flushing, three rules and nothing else: the FIRST event of a launch ships immediately
 * (carrying any backlog with it); any later event ships everything pending when the last flush
 * attempt is [STAT_FLUSH_INTERVAL_MS] behind; and a full batch of [STAT_FLUSH_BATCH] schedules
 * ONE one-shot flush for the moment the interval ends, so a burst does not need a further event
 * to drain. Nothing periodic ever runs — an idle app keeps no timer alive — and a failed
 * attempt still counts against the interval, so an outage is never a hammer.
 */
class StatUsage(
    private val api: ApiService?,
    /** The "Send anonymous stats" answer, read per event so the switch applies live. */
    private val enabled: () -> Boolean,
    /** The crash mirror of the in-memory queue — replayed on the next launch. */
    private val queueFile: Path,
    private val fileSystem: FileSystem,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val log: Logger? = null,
) {
    private val mutex = Mutex()

    /** ArrayDeque: the flush drains from the head, and head removal must not shift an array. */
    private val pending = ArrayDeque<PulseEvent>()
    private var loaded = false
    private var flushTimer: Job? = null
    private var flushedOnce = false // the first event of a launch ships without waiting

    /** Attempt time, success or not: the throttle everything above compares against. */
    private var lastFlushAt = 0L

    fun onAppStart() = record("app_start")

    fun record(name: String, properties: Map<String, String> = emptyMap()) {
        if (api == null || !enabled()) return
        val event = PulseEvent(name = name, at = now(), properties = properties)
        scope.launch {
            mutex.withLock {
                loadIfNeeded()
                try {
                    append(event)
                } catch (error: IOException) {
                    // Still queued in memory; only a restart before the next flush loses it.
                    log?.log(Logger.Level.WARN, "stat queue append failed", error)
                }
                pending.add(event)
                if (!flushedOnce || now() - lastFlushAt >= STAT_FLUSH_INTERVAL_MS) {
                    flushTimer?.cancel()
                    flushLocked()
                } else if (pending.size >= STAT_FLUSH_BATCH) {
                    scheduleFlushLocked()
                } // else: waits for a later event, the batch timer, or the next launch
                // Capped AFTER the flush had its chance: only what truly could not ship is dropped.
                while (pending.size > MAX_QUEUE) pending.removeFirst()
            }
        }
    }

    /**
     * Inside the lock: chunks of at most [PULSE_MAX_EVENTS], oldest first. The file only forgets
     * what was actually sent — a failure mid-drain keeps the rest and simply waits for whatever
     * triggers the next flush; it never schedules its own retry, so no timer chain can build up.
     */
    private suspend fun flushLocked() {
        flushedOnce = true // attempted counts: a failed first try waits like any other
        lastFlushAt = now()
        while (pending.isNotEmpty()) {
            val chunk = pending.take(PULSE_MAX_EVENTS)
            try {
                checkNotNull(api).pulse(chunk)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // Best-effort by design: the queue survives, and stats are never worth an alert.
                log?.info("Stat flush failed, ${pending.size} queued", error)
                return
            }
            repeat(chunk.size) { pending.removeFirst() }
            if (pending.isEmpty()) fileSystem.delete(queueFile) else compact()
        }
    }

    /** One one-shot timer at a time, aimed at the moment the interval ends. */
    private fun scheduleFlushLocked() {
        if (flushTimer?.isActive == true) return
        val wait = (lastFlushAt + STAT_FLUSH_INTERVAL_MS - now()).coerceAtLeast(0)
        flushTimer = scope.launch {
            delay(wait)
            mutex.withLock { flushLocked() }
        }
    }

    /** One line appended per event — the record path never reads the file. */
    private fun append(event: PulseEvent) {
        queueFile.parent?.let { fileSystem.createDirectories(it) }
        val writer = JsonWriter.Sync()
        writer.value(event)
        writer.buffer.writeByte('\n'.code)
        fileSystem.appendingSink(queueFile).use { it.buffer().use { out -> out.writeAll(writer.buffer) } }
    }

    /** Flush-time only: rewrite the file down to the unsent remainder. */
    private fun compact() {
        val tmp = queueFile.parent?.resolve(queueFile.name + ".tmp") ?: return
        fileSystem.sink(tmp).use { sink ->
            sink.buffer().use { out ->
                for (event in pending) {
                    // A writer per line: each line is its own single-root JSON document.
                    val writer = JsonWriter.Sync()
                    writer.value(event)
                    out.writeAll(writer.buffer)
                    out.writeByte('\n'.code)
                }
            }
        }
        fileSystem.atomicMove(tmp, queueFile)
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!fileSystem.exists(queueFile)) return
        try {
            for (line in fileSystem.read(queueFile) { readUtf8() }.lineSequence()) {
                if (line.isBlank()) continue
                // Per-line: one bad record (a torn tail included) never drops the readable ones.
                val event = runCatching {
                    (Json.parseToJsonElement(line) as? JsonObject)?.let(PulseEvent::from)
                }.getOrNull() ?: continue
                pending.add(event)
            }
            while (pending.size > MAX_QUEUE) pending.removeFirst()
        } catch (error: Throwable) {
            runCatching { fileSystem.delete(queueFile) }.exceptionOrNull()?.let(error::addSuppressed)
            log?.info("Stat queue unreadable, starting over", error)
            pending.clear()
        }
    }
}
