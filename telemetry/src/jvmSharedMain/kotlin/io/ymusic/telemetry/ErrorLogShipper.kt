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
    errorLogShipper = ErrorLogShipper(store, remoteConfig, appVersionCode).also { it.shareTraceWithSentry() }
}

/** Call right after Sentry.init: Sentry's trace for this app open becomes the app log's [LogStore.launch]. */
fun shareLogTraceWithSentry() {
    errorLogShipper?.shareTraceWithSentry()
}

@Volatile
internal var errorLogShipper: ErrorLogShipper? = null

/**
 * Sentry's trace for this app open is the app log's id for it ([shareTraceWithSentry]), so an event
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

    /** Sets the log's id for this app open as Sentry's trace id; a no-op before Sentry has started. */
    fun shareTraceWithSentry() {
        if (!Sentry.isEnabled()) return
        Sentry.configureScope { it.propagationContext = PropagationContext(SentryId(store.launch), SpanId(), null, null, null) }
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
            if (wait { send(eventId) } == null) log("log lines for $eventId still pending after ${WAIT_MS}ms", null)
        } else {
            scope.launch { send(eventId) }
        }
    }

    private suspend fun send(eventId: String) {
        try {
            val limit = remoteConfig.awaitLoaded().errorLogLines.coerceIn(0, MAX_LINES)
            if (limit == 0) return
            val client = Sentry.getCurrentScopes().globalScope.client
            val lines = store.claimUnsent(limit, Logger.Level.INFO).map { record ->
                SentryLogEvent(SentryId(record.launch), record.epochMs / 1000.0, record.message, record.level.toSentry())
                    .apply { attributes = hashMapOf(VERSION_CODE to versionCode) }
            }
            lines.chunked(BATCH).forEach { client.captureBatchedLogEvents(SentryLogEvents(it)) }
        } catch (e: Exception) {
            log("log lines for $eventId not sent", e)
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
