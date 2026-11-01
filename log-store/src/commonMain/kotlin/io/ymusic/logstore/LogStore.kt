@file:OptIn(ExperimentalTime::class)

package io.ymusic.logstore

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.logstore.db.LogDatabase
import io.ymusic.logstore.db.Log_line
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.gzip
import okio.use
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One stored log line; [launch] identifies the app open that wrote it. */
data class LogRecord(
    val seq: Long,
    val epochMs: Long,
    val level: Logger.Level,
    val launch: String,
    val trail: String,
    val message: String,
)

/**
 * The app's log: every line from every layer in one SQLite table, the source of the feedback report
 * ([exportGzip]) and of the lines an error event takes to Sentry ([claimUnsent]). [enqueue] never
 * blocks (engine-thread safe) and drops the oldest queued line under a burst. All database work runs
 * one task at a time: queued lines go in one transaction per batch, and every query runs after the lines
 * queued before it, so it sees them. Nothing is trimmed until [setMaxLines] gives the cap.
 */
class LogStore(
    driver: SqlDriver,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    /** This app open's id, stored on each of its lines: 32 hex chars, so Sentry takes it as its trace id. */
    val launch: String = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val queries = LogDatabase(driver).logQueries
    private val lines = Channel<Line>(queueCapacity, BufferOverflow.DROP_OLDEST)
    /** Runs one task at a time: the writer loop and every query take turns on the database. */
    private val db = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + db)

    @Volatile
    private var maxLines = 0L
    private var rowCount = -1L

    /** Batches lost to a failed write since the last one that succeeded, and the newest cause. */
    private var lostBatches = 0
    private var lostCause = ""

    init {
        scope.launch {
            for (line in lines) writeOrDrop { writeBatch(line) }
        }
    }

    /**
     * Keep the newest [lines]: once the table holds 10% more, the oldest go down to [lines]. Until
     * the first call nothing is trimmed (the cap comes from the loaded remote config).
     */
    fun setMaxLines(lines: Int) {
        maxLines = lines.coerceAtLeast(MIN_LINES).toLong()
    }

    /** Timestamps and queues one line. Safe on any thread; never blocks or throws. */
    fun enqueue(level: Logger.Level, trail: String, message: String, error: Throwable?) {
        val text = if (error == null) {
            message
        } else {
            message + "\n" + error.stackTraceToString().take(STACK_CAP)
        }
        lines.trySend(Line(Clock.System.now().toEpochMilliseconds(), level, trail, text))
    }

    /**
     * The newest unsent lines at [minLevel] or above, at most [limit], oldest first, now pending: they
     * stay pending until [markSent], so a send that never completes is retried from [pending]. Every
     * older unsent line is skipped in the same transaction, so a later claim never reaches back past it.
     */
    suspend fun claimUnsent(limit: Int, minLevel: Logger.Level): List<LogRecord> = onDb {
        queries.transactionWithResult {
            val rows = queries.newestUnsent(minLevel.ordinal.toLong(), limit.toLong()).executeAsList()
            if (rows.isNotEmpty()) queries.markPending(rows.map { it.seq })
            queries.maxSeq().executeAsOne().seq?.let { queries.skipUnsentThrough(it) }
            rows.asReversed().map { it.toRecord() }
        }
    }

    /** Lines an earlier app open claimed ([claimUnsent]) that never reached [markSent], oldest first. */
    suspend fun pendingFromEarlierRuns(limit: Int): List<LogRecord> = onDb {
        queries.pendingBefore(launch, limit.toLong()).executeAsList().map { it.toRecord() }
    }

    /** These lines reached Sentry; they are never returned again. */
    suspend fun markSent(seqs: List<Long>): Unit = onDb {
        if (seqs.isNotEmpty()) queries.markSent(seqs)
    }

    /** Returns once every line queued before the call is written. */
    suspend fun flush(): Unit = onDb { }

    /** The app open before this one, by its newest line; null when every stored line is this launch's. */
    suspend fun previousLaunch(): String? = onDb { queries.previousLaunch(launch).executeAsOneOrNull() }

    /** Every stored line as text, gzip-compressed, into [path] (replaced). */
    suspend fun exportGzip(fileSystem: FileSystem, path: Path): Unit = onDb {
        fileSystem.sink(path).gzip().buffer().use { out ->
            var after = -1L
            var lastLaunch = ""
            while (true) {
                val page = queries.linesAfter(after, EXPORT_PAGE).executeAsList()
                if (page.isEmpty()) break
                for (row in page) {
                    if (row.launch != lastLaunch) {
                        out.writeUtf8("--- launch ").writeUtf8(row.launch).writeUtf8(" ---\n")
                        lastLaunch = row.launch
                    }
                    out.writeUtf8(formatTime(row.ts)).writeByte(' '.code)
                    out.writeUtf8(levelOf(row.level).name)
                    if (row.trail.isNotEmpty()) out.writeByte(' '.code).writeUtf8(row.trail)
                    out.writeUtf8(": ").writeUtf8(row.message).writeByte('\n'.code)
                }
                after = page.last().seq
            }
        }
    }

    fun close() {
        lines.close()
        scope.cancel()
    }

    /**
     * Runs [block] on [db] after the lines queued before the call are written, so it
     * sees them. [block] never suspends, so the writer loop cannot interleave with it.
     */
    private suspend fun <T> onDb(block: () -> T): T = withContext(db) {
        writeOrDrop { drainLines() }
        block()
    }

    /**
     * The log can't log its own failure, and a throw here would end the worker for good (every later
     * query then waits forever) or kill the process: a batch that fails to write (full disk, a database
     * that won't open) is dropped, and the next batch that does write records how many were lost.
     */
    private fun writeOrDrop(write: () -> Unit) {
        try {
            write()
        } catch (e: Throwable) {
            lostBatches++
            lostCause = e.toString().take(STACK_CAP)
            rowCount = -1
        }
    }

    private fun drainLines() {
        while (true) writeBatch(lines.tryReceive().getOrNull() ?: return)
    }

    /** [first] and every line queued behind it (up to a batch), in one transaction. */
    private fun writeBatch(first: Line) {
        queries.transaction {
            if (lostBatches > 0) {
                val note = "log store lost $lostBatches batch(es) of lines: $lostCause"
                queries.insertLine(first.epochMs, Logger.Level.WARN.ordinal.toLong(), launch, "log-store", note)
            }
            var line: Line? = first
            var count = 0
            while (line != null) {
                queries.insertLine(line.epochMs, line.level.ordinal.toLong(), launch, line.trail, line.message)
                count++
                line = if (count < BATCH_MAX) lines.tryReceive().getOrNull() else null
            }
            if (rowCount < 0) rowCount = queries.countLines().executeAsOne() else rowCount += count
            val cap = maxLines
            if (cap > 0 && rowCount >= cap + cap / 10) {
                val newest = queries.maxSeq().executeAsOne().seq ?: 0L
                queries.trimTo(newest - cap)
                rowCount = queries.countLines().executeAsOne()
            }
        }
        lostBatches = 0
    }

    private class Line(val epochMs: Long, val level: Logger.Level, val trail: String, val message: String)

    private companion object {
        const val MIN_LINES = 100
        const val DEFAULT_QUEUE_CAPACITY = 1_000
        const val BATCH_MAX = 500
        const val EXPORT_PAGE = 2_000L
        const val STACK_CAP = 2_000

        fun levelOf(ordinal: Long): Logger.Level = Logger.Level.entries.getOrElse(ordinal.toInt()) { Logger.Level.INFO }

        fun Log_line.toRecord() = LogRecord(seq, ts, levelOf(level), launch, trail, message)

        fun formatTime(epochMs: Long): String {
            val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
            fun pad(n: Int, width: Int = 2) = n.toString().padStart(width, '0')
            return "${t.year}-${pad(t.month.ordinal + 1)}-${pad(t.day)} ${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}.${pad(t.nanosecond / 1_000_000, 3)}"
        }
    }
}

/** [CrumbLogger] sink that hands every line to [store]. */
class DbLogger private constructor(
    private val store: LogStore,
    crumbs: List<Crumb>,
) : CrumbLogger(crumbs) {
    constructor(store: LogStore) : this(store, emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = DbLogger(store, crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        store.enqueue(level, path, message, error)
    }
}
