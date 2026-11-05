package io.ymusic.telemetry

import io.sentry.PropagationContext
import io.sentry.Sentry
import io.sentry.SentryAttributeType
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.SentryLogEvents
import io.sentry.SentryLogLevel
import io.sentry.SpanId
import io.sentry.hints.Cached
import io.sentry.hints.Retryable
import io.sentry.hints.SubmissionResult
import io.sentry.protocol.SentryId
import io.sentry.util.HintUtils
import io.ymusic.logstore.LogStore
import io.ymusic.remoteconfig.RemoteConfig
import kmpcommon.log.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private var retryDelayMs = RETRY_MIN_MS

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
     * Hands pending lines to Sentry a batch at a time, each batch marked sent once the transport delivered it or
     * Sentry refused it (a 429 or a rate limit drops data on purpose, and a retry only adds load). A batch that
     * didn't get through (no network) or got no answer stays pending, ends the pass and schedules a retry with
     * backoff. The cursor lives for one pass: a claim behind it (an earlier run's lines) wakes the
     * next pass, which starts over.
     */
    private suspend fun sendPending() {
        try {
            val client = Sentry.getCurrentScopes().globalScope.client
            val serializer = Sentry.getCurrentScopes().options.serializer
            val sdkVersion = Sentry.getCurrentScopes().options.sdkVersion
            var afterSeq = 0L
            while (true) {
                val batch = store.pending(BATCH, afterSeq).ifEmpty { break }
                val lines = batch.map { record ->
                    SentryLogEvent(SentryId(record.launch), record.epochMs / 1000.0, record.message, record.level.toSentry())
                        .apply { attributes = hashMapOf(VERSION_CODE to versionCode) }
                }
                val envelope = SentryEnvelope(
                    SentryEnvelopeHeader(null, sdkVersion, null),
                    listOf(SentryEnvelopeItem.fromLogs(serializer, SentryLogEvents(lines))),
                )
                val delivery = Delivery()
                client.captureEnvelope(envelope, HintUtils.createWithTypeCheckHint(delivery))
                when (withTimeoutOrNull(DELIVERY_TIMEOUT_MS) { delivery.outcome.await() }) {
                    Outcome.DELIVERED -> Unit
                    Outcome.REFUSED -> log("log batch of ${batch.size} lines refused by Sentry, dropped", null)
                    Outcome.UNSENT, null -> {
                        scheduleRetry()
                        return
                    }
                }
                store.markSent(batch.map { it.seq })
                afterSeq = batch.last().seq
            }
            retryDelayMs = RETRY_MIN_MS
        } catch (e: Exception) {
            log("pending log lines not sent", e)
        }
    }

    private fun scheduleRetry() {
        val delayMs = retryDelayMs
        retryDelayMs = (delayMs * 2).coerceAtMost(RETRY_MAX_MS)
        scope.launch {
            delay(delayMs)
            wake.trySend(Unit)
        }
    }

    private enum class Outcome { DELIVERED, REFUSED, UNSENT }

    /**
     * The transport's verdict on one envelope: it flags a failure retryable (network, offline) before
     * reporting the result, and a refusal (HTTP error, rate limit) never. [Cached] keeps the SDK from storing
     * the envelope on disk and resending it on its own: an unsent batch is retried from the log, and a second
     * copy would duplicate it.
     */
    private class Delivery : SubmissionResult, Retryable, Cached {
        val outcome = CompletableDeferred<Outcome>()

        @Volatile
        private var retry = false

        override fun setRetry(retry: Boolean) {
            this.retry = retry
        }

        override fun isRetry(): Boolean = retry

        override fun setResult(success: Boolean) {
            outcome.complete(
                when {
                    success -> Outcome.DELIVERED
                    retry -> Outcome.UNSENT
                    else -> Outcome.REFUSED
                },
            )
        }

        override fun isSuccess(): Boolean = outcome.isCompleted && outcome.getCompleted() == Outcome.DELIVERED
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
        const val DELIVERY_TIMEOUT_MS = 60_000L
        const val RETRY_MIN_MS = 60_000L
        const val RETRY_MAX_MS = 30 * 60_000L
        const val VERSION_CODE = "app.version_code"
    }
}
