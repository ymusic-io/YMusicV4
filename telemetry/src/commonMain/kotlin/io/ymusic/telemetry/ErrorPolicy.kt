package io.ymusic.telemetry

import io.ymusic.remoteconfig.RemoteConfig
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * Keeps [SentryLogger.captureError]'s remote rules in step with [remoteConfig]'s `errorSampleRate` and
 * `errorFilters`, starting from the cached config. Crashes, ANRs and feedback reports never pass these rules.
 */
fun bindErrorPolicy(scope: CoroutineScope, remoteConfig: RemoteConfig, log: Logger) {
    scope.launch {
        remoteConfig.config
            .map { it.features }
            .map { ErrorRules(it.errorSampleRate, it.errorFilters) }
            .distinctUntilChanged()
            .collect { rules -> errorPolicy = ErrorPolicy.compile(rules, log) }
    }
}

internal data class ErrorRules(val sampleRate: Double, val filters: List<String>)

internal class ErrorPolicy(
    private val sampleRate: Double,
    private val filters: List<Regex>,
    private val log: Logger?,
) {

    /** Whether a non-fatal [error] becomes a Sentry event. */
    fun allows(error: Throwable): Boolean {
        if (filters.isNotEmpty() && matchesFilter(error)) return false
        return sampleRate >= 1.0 || Random.nextDouble() < sampleRate
    }

    private fun matchesFilter(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
            val message = cause.message.orEmpty()
            val subjects = listOfNotNull(cause::class.simpleName, cause::class.qualifiedName)
                .distinct()
                .map { "$it: $message".take(MAX_SUBJECT_CHARS) }
            if (filters.any { filter -> subjects.any { filter.safeContainsMatchIn(it) } }) return true
            cause = cause.cause
        }
        return false
    }

    private fun Regex.safeContainsMatchIn(subject: String): Boolean = try {
        containsMatchIn(subject)
    } catch (e: Exception) {
        log?.log(Logger.Level.WARN, "error filter \"$pattern\" failed to match, counted as no match", e)
        false
    }

    internal companion object {
        private const val MAX_CAUSE_DEPTH = 5

        // The match runs on the caller's thread; the cap bounds a careless pattern's backtracking.
        private const val MAX_SUBJECT_CHARS = 512

        val SEND_ALL = ErrorPolicy(1.0, emptyList(), null)

        fun compile(rules: ErrorRules, log: Logger): ErrorPolicy {
            val filters = rules.filters.mapNotNull { pattern ->
                try {
                    Regex(pattern)
                } catch (e: Exception) {
                    log.log(Logger.Level.WARN, "error filter skipped: invalid regex \"$pattern\"", e)
                    null
                }
            }
            val sampleRate = rules.sampleRate.takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0) ?: 1.0
            return ErrorPolicy(sampleRate, filters, log)
        }
    }
}

@Volatile
internal var errorPolicy: ErrorPolicy = ErrorPolicy.SEND_ALL
