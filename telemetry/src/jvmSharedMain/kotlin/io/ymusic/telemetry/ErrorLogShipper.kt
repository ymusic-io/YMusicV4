package io.ymusic.telemetry

import io.sentry.PropagationContext
import io.sentry.Sentry
import io.sentry.SentryAttributeType
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.SentryLogEvents
import io.sentry.SentryLogLevel
import io.sentry.SpanContext
import io.sentry.SpanId
import io.sentry.protocol.SentryId
import io.ymusic.logstore.LogRecord
import io.ymusic.logstore.LogStore
import io.ymusic.remoteconfig.RemoteConfig
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Lets every error event that leaves take the app log's newest unsent lines along: [store] is the
 * one log (the feedback report reads it too), [remoteConfig]'s loaded `errorLogLines` says how many.
 * Call once, before Sentry.init's first event.
 */
fun installErrorLogs(store: LogStore, remoteConfig: RemoteConfig, appVersionCode: Int) {
    errorLogShipper = ErrorLogShipper(store, remoteConfig, appVersionCode).also { it.onSentryStarted() }
}

/**
 * Call right after Sentry.init: Sentry's trace for this app open becomes the app log's
 * [LogStore.launch], and lines an earlier run claimed but never sent go out.
 */
fun shareLogTraceWithSentry() {
    errorLogShipper?.onSentryStarted()
}

@Volatile
internal var errorLogShipper: ErrorLogShipper? = null

/**
 * Sentry's trace for this app open is the app log's id for it ([onSentryStarted]), so an event
 * and its run's lines share one trace; a report the next process makes about the previous run (ANR,
 * native crash) takes that run's trace instead. An event's lines are the store's newest
 * unsent INFO+ lines, each sent as a Sentry Log with only body, level, timestamp, its trace id and
 * `app.version_code`.
 */
internal class ErrorLogShipper(
    private val store: LogStore,
    private val remoteConfig: RemoteConfig,
    appVersionCode: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val versionCode = SentryLogEventAttributeValue(SentryAttributeType.INTEGER, appVersionCode)

    /** Sets the log's id for this app open as Sentry's trace id and resends pending lines; a no-op before Sentry starts. */
    fun onSentryStarted() {
        if (!Sentry.isEnabled()) return
        Sentry.configureScope { it.propagationContext = PropagationContext(SentryId(store.launch), SpanId(), null, null, null) }
        scope.launch {
            try {
                ship(store.pendingFromEarlierRuns(MAX_LINES))
            } catch (e: Exception) {
                log("pending log lines not resent", e)
            }
        }
    }

    /** Called from beforeSend for an event that leaves. A crash waits (bounded): its process is ending. */
    fun attach(event: SentryEvent, previousRun: Boolean) {
        if (previousRun) {
            wait { store.previousLaunch() }?.let { trace ->
                val span = event.contexts.trace
                event.contexts.setTrace(SpanContext(SentryId(trace), span?.spanId ?: SpanId(), span?.operation ?: "default", span?.parentSpanId, null))
            }
        }
        val eventId = event.eventId.toString()
        if (event.isCrashed) {
            // The config already in memory: waiting for it to load can wait on the crashing thread itself.
            // Lines claimed but not shipped in time stay pending for the next launch.
            val limit = remoteConfig.config.value.errorLogLines
            if (wait { send(eventId, limit) } == null) log("log lines for $eventId still pending after ${WAIT_MS}ms", null)
        } else {
            scope.launch { send(eventId, remoteConfig.awaitLoaded().errorLogLines) }
        }
    }

    private suspend fun send(eventId: String, limit: Int) {
        try {
            if (limit <= 0) return
            ship(store.claimUnsent(limit.coerceAtMost(MAX_LINES), Logger.Level.INFO))
        } catch (e: Exception) {
            log("log lines for $eventId not sent", e)
        }
    }

    /** Hands [records] to Sentry a batch at a time, each batch marked sent once Sentry has it. */
    private suspend fun ship(records: List<LogRecord>) {
        val client = Sentry.getCurrentScopes().globalScope.client
        records.chunked(BATCH).forEach { batch ->
            val lines = batch.map { record ->
                SentryLogEvent(SentryId(record.launch), record.epochMs / 1000.0, record.message, record.level.toSentry())
                    .apply { attributes = hashMapOf(VERSION_CODE to versionCode) }
            }
            client.captureBatchedLogEvents(SentryLogEvents(lines))
            store.markSent(batch.map { it.seq })
        }
    }

    private fun <T> wait(block: suspend () -> T): T? = runBlocking { withTimeoutOrNull(WAIT_MS) { block() } }

    private fun log(message: String, error: Throwable?) {
        Sentry.getCurrentScopes().options.logger.log(SentryLevel.WARNING, message, error)
    }

    private fun Logger.Level.toSentry(): SentryLogLevel = when (this) {
        Logger.Level.DEBUG -> SentryLogLevel.DEBUG
        Logger.Level.INFO -> SentryLogLevel.INFO
        Logger.Level.WARN -> SentryLogLevel.WARN
        Logger.Level.ERROR -> SentryLogLevel.ERROR
    }

    private companion object {
        const val MAX_LINES = 10_000
        const val BATCH = 100
        const val WAIT_MS = 2_000L
        const val VERSION_CODE = "app.version_code"
    }
}
