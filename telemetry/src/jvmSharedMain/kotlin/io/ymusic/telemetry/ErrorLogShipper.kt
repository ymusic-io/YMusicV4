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
import io.sentry.SpanId
import io.sentry.protocol.SentryId
import io.ymusic.logstore.LogStore
import io.ymusic.remoteconfig.RemoteConfig
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Lets every error event that leaves take its run's newest unsent lines from the app log along: [store] is the
 * one log (the feedback report reads it too), [remoteConfig]'s loaded `errorLogLines` says how many.
 * Call once, before Sentry.init's first event.
 */
fun installErrorLogs(store: LogStore, remoteConfig: RemoteConfig, appVersionCode: Int) {
    errorLogShipper = ErrorLogShipper(store, remoteConfig, appVersionCode).also { it.onSentryStarted() }
}

/**
 * Call right after Sentry.init: Sentry's trace for this app open becomes the app log's
 * [LogStore.launch], and lines still pending from earlier runs go out.
 */
fun shareLogTraceWithSentry() {
    errorLogShipper?.onSentryStarted()
}

@Volatile
internal var errorLogShipper: ErrorLogShipper? = null

/**
 * Sentry's trace for this app open is the app log's id for it ([onSentryStarted]), so an event
 * and its run's lines share one trace. An event claims the unsent lines among the newest lines of
 * the launch its trace id names (Sentry restores the dead run's trace on an ANR or native crash the next
 * process reports); one worker sends every pending line, each as a Sentry Log with only body, level,
 * timestamp, its trace id and `app.version_code`. Lines a dying process claimed go out on the next start.
 */
internal class ErrorLogShipper(
    private val store: LogStore,
    private val remoteConfig: RemoteConfig,
    appVersionCode: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val versionCode = SentryLogEventAttributeValue(SentryAttributeType.INTEGER, appVersionCode)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            wake.consumeEach {
                sendPending()
            }
        }
    }

    /** Sets the log's id for this app open as Sentry's trace id and sends pending lines; a no-op before Sentry starts. */
    fun onSentryStarted() {
        if (!Sentry.isEnabled()) return
        Sentry.configureScope { it.propagationContext = PropagationContext(SentryId(store.launch), SpanId(), null, null, null) }
        wake.trySend(Unit)
    }

    /** Called from beforeSend for an event that leaves; returns at once, the claim runs on [scope]. */
    fun attach(event: SentryEvent) {
        val launch = event.contexts.trace?.traceId?.toString() ?: return
        val eventId = event.eventId.toString()
        // A crashing process may not live until the config loads: it takes the config in memory.
        val crashLimit = if (event.isCrashed) remoteConfig.config.value.features.errorLogLines else null
        scope.launch { claim(launch, eventId, crashLimit ?: remoteConfig.awaitLoaded().features.errorLogLines) }
    }

    private suspend fun claim(launch: String, eventId: String, limit: Int) {
        try {
            if (limit <= 0) return
            store.claimUnsent(launch, limit.coerceAtMost(MAX_LINES))
            wake.trySend(Unit)
        } catch (e: Exception) {
            log("log lines for $eventId not claimed", e)
        }
    }

    /**
     * Hands pending lines to Sentry a batch at a time, each batch marked sent once Sentry has it. The cursor
     * lives for one pass: a claim behind it (an earlier run's lines) wakes the next pass, which starts over.
     */
    private suspend fun sendPending() {
        try {
            val client = Sentry.getCurrentScopes().globalScope.client
            var afterSeq = 0L
            while (true) {
                val batch = store.pending(BATCH, afterSeq).ifEmpty { return }
                val lines = batch.map { record ->
                    SentryLogEvent(SentryId(record.launch), record.epochMs / 1000.0, record.message, record.level.toSentry())
                        .apply { attributes = hashMapOf(VERSION_CODE to versionCode) }
                }
                client.captureBatchedLogEvents(SentryLogEvents(lines))
                store.markSent(batch.map { it.seq })
                afterSeq = batch.last().seq
            }
        } catch (e: Exception) {
            log("pending log lines not sent", e)
        }
    }

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
        const val VERSION_CODE = "app.version_code"
    }
}
