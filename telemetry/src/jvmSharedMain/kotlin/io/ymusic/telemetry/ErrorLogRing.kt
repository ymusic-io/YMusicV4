package io.ymusic.telemetry

import io.sentry.ISentryClient
import io.sentry.JsonObjectReader
import io.sentry.JsonObjectWriter
import io.sentry.SentryAttributeType
import io.sentry.SentryLevel
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.SentryLogEvents
import io.sentry.SentryOptions
import io.sentry.logger.ILoggerBatchProcessor
import io.sentry.logger.ILoggerBatchProcessorFactory
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

/**
 * How many of the newest log lines an error event takes along (remote config); 0 sends none, null
 * restores the default. Lines stay on disk otherwise and are never sent twice.
 */
fun setErrorLogLines(lines: Int?) {
    errorLogLines = (lines ?: DEFAULT_ERROR_LOG_LINES).coerceIn(0, MAX_ERROR_LOG_LINES)
}

@Volatile
private var errorLogLines = DEFAULT_ERROR_LOG_LINES

/** The ring the SDK's log pipeline writes into; null until Sentry.init built it. */
@Volatile
internal var errorLogRing: ErrorLogRing? = null

/** Replaces the SDK's 5-second log batching: every line goes to [dir], none leaves until an error. */
internal fun errorLogRingFactory(dir: File) = ILoggerBatchProcessorFactory { options, client ->
    ErrorLogRing(dir, options, client).also { errorLogRing = it }
}

/**
 * Sentry Logs held on disk and sent only with an error. Every line, fully built (attributes, trace,
 * timestamp, after `logs.beforeSend`), is appended to a two-file ring as `<seq>\t<json>`; [sendFor]
 * sends the newest lines above the persisted sent mark, up to [errorLogLines], tagged with the
 * event's id, then moves the mark past everything it considered, so no line goes twice. All file
 * work runs on one thread, in order, so a send sees every line added before it.
 */
internal class ErrorLogRing(
    private val dir: File,
    private val options: SentryOptions,
    private val client: ISentryClient,
) : ILoggerBatchProcessor {
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "sentry-log-ring").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val current = File(dir, "ring-0.log")
    private val previous = File(dir, "ring-1.log")
    private val markFile = File(dir, "sent.seq")
    private val dropped = AtomicLong()
    private var nextSeq = -1L
    private var currentLines = 0

    override fun add(event: SentryLogEvent) {
        if (worker.queue.remainingCapacity() == 0) dropped.incrementAndGet()
        worker.execute { append(event) }
    }

    /** Lines leave only with an error. */
    override fun flush(timeoutMillis: Long) = Unit

    override fun close(isRestarting: Boolean) {
        if (errorLogRing === this) errorLogRing = null
        worker.shutdown()
        worker.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Sends the newest unsent lines for [eventId]. [sync] waits (bounded) for the send to be handed to
     * the transport — for a crash, whose process is about to end.
     */
    fun sendFor(eventId: String, sync: Boolean) {
        val limit = errorLogLines
        if (limit <= 0) return
        val task = try {
            worker.submit {
                try {
                    send(eventId, limit)
                } catch (e: Exception) {
                    options.logger.log(SentryLevel.WARNING, "log lines for $eventId not sent", e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            options.logger.log(SentryLevel.WARNING, "log ring closed, no log lines for $eventId", e)
            return
        }
        if (!sync) return
        try {
            task.get(SYNC_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            options.logger.log(SentryLevel.WARNING, "log lines for $eventId still pending after ${SYNC_WAIT_MS}ms", e)
        } catch (e: ExecutionException) {
            options.logger.log(SentryLevel.WARNING, "log lines for $eventId not sent", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun append(event: SentryLogEvent) {
        try {
            if (nextSeq < 0) restore()
            val json = StringWriter().also { event.serialize(JsonObjectWriter(it, JSON_DEPTH), options.logger) }
            current.appendText("$nextSeq\t$json\n")
            nextSeq++
            currentLines++
            if (currentLines >= maxOf(errorLogLines, MIN_LINES_PER_FILE)) rotate()
            val lost = dropped.getAndSet(0)
            if (lost > 0) options.logger.log(SentryLevel.WARNING, "log ring dropped $lost lines (writer behind)")
        } catch (e: IOException) {
            options.logger.log(SentryLevel.WARNING, "log ring write failed", e)
        }
    }

    private fun send(eventId: String, limit: Int) {
        if (nextSeq < 0) restore()
        val sentUpTo = readMark()
        val newest = ArrayDeque<String>(limit)
        var newestSeq = sentUpTo
        for (file in listOf(previous, current)) {
            if (!file.exists()) continue
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                val seq = if (tab > 0) line.substring(0, tab).toLongOrNull() else null
                if (seq != null && seq > sentUpTo) {
                    if (newest.size == limit) newest.removeFirst()
                    newest.addLast(line.substring(tab + 1))
                    if (seq > newestSeq) newestSeq = seq
                }
            }
        }
        if (newest.isEmpty()) return
        // Marked first: a send that fails halfway still never repeats a line.
        writeMark(newestSeq)
        val events = newest.mapNotNull { json -> parse(json) }
        val tag = SentryLogEventAttributeValue(SentryAttributeType.STRING, eventId)
        events.forEach { it.setAttribute(EVENT_ID_ATTRIBUTE, tag) }
        events.chunked(BATCH_SIZE).forEach { client.captureBatchedLogEvents(SentryLogEvents(it)) }
    }

    private fun parse(json: String): SentryLogEvent? = try {
        SentryLogEvent.Deserializer().deserialize(JsonObjectReader(StringReader(json)), options.logger)
    } catch (e: Exception) {
        options.logger.log(SentryLevel.WARNING, "log ring line unreadable, skipped", e)
        null
    }

    /** Next seq and the current file's line count, from what is on disk (a new process continues it). */
    private fun restore() {
        dir.mkdirs()
        var last = -1L
        var count = 0
        for (file in listOf(previous, current)) {
            if (!file.exists()) continue
            val isCurrent = file == current
            file.forEachLine { line ->
                line.substringBefore('\t').toLongOrNull()?.let { if (it > last) last = it }
                if (isCurrent) count++
            }
        }
        nextSeq = maxOf(last, readMark()) + 1
        currentLines = count
    }

    private fun rotate() {
        if (previous.exists() && !previous.delete()) throw IOException("cannot delete $previous")
        if (!current.renameTo(previous)) throw IOException("cannot rotate $current")
        currentLines = 0
    }

    private fun readMark(): Long = try {
        markFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: -1L
    } catch (e: IOException) {
        options.logger.log(SentryLevel.WARNING, "log ring sent mark unreadable", e)
        -1L
    }

    private fun writeMark(seq: Long) {
        val tmp = File(dir, "sent.seq.tmp")
        tmp.writeText(seq.toString())
        if (!tmp.renameTo(markFile)) {
            markFile.delete()
            if (!tmp.renameTo(markFile)) throw IOException("cannot write $markFile")
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 2_000
        const val JSON_DEPTH = 100
        const val MIN_LINES_PER_FILE = 100
        const val BATCH_SIZE = 100
        const val SYNC_WAIT_MS = 2_000L
        const val CLOSE_WAIT_MS = 1_000L
        const val EVENT_ID_ATTRIBUTE = "event_id"
    }
}

private const val DEFAULT_ERROR_LOG_LINES = 1_000
private const val MAX_ERROR_LOG_LINES = 10_000
