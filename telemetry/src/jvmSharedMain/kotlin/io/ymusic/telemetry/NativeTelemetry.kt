package io.ymusic.telemetry

import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import kmpcommon.log.Crumb
import projectcommon.log.FileAttachment
import java.util.Date

/**
 * The Sentry options every app shell shares, applied inside its `Sentry.initWithPlatformOptions`
 * block. Native options, not the KMP ones: a KMP `beforeSend` round-trips each event through KMP
 * types and rebuilds every breadcrumb, which re-stamps them all with the send time. No PII
 * scrubbing: sensitive values (cookie values, SAPISIDHASH) are never put into log lines at the
 * source — names only. Log lines reach Sentry only with an error event ([installErrorLogs]).
 */
fun SentryOptions.applyYMusicTelemetry() {
    // The KMP options default this on; keep it now that they no longer apply.
    isAttachThreads = true
    // One scope for every thread, as SentryAndroid.init already sets: off, each thread forks its own and
    // the log's trace (ErrorLogShipper.onSentryStarted) reaches only the thread that set it.
    isGlobalHubMode = true
    maxBreadcrumbs = 200
    // Consent is enforced HERE, not by deinitializing (see bindCrashReporting): everything that
    // would leave the process passes one of these hooks and is dropped while consent says no.
    // A feedback report is its own consent (the Feedback screen turns reports on as it sends), so it
    // passes even before the gate's collector has seen that flip.
    setBeforeSend { event, _ ->
        val feedback = event.getTag(REPORT_TYPE_TAG) == REPORT_TYPE_FEEDBACK
        when {
            feedback -> fingerprintPolicy(event).also(::sortBreadcrumbs)
            !crashReportingAllowed -> null
            else -> {
                errorLogShipper?.attach(event)
                fingerprintPolicy(event).also(::sortBreadcrumbs)
            }
        }
    }
    // The log lines an error event takes go to the client directly; a Sentry Log only needs the
    // pipeline enabled.
    logs.isEnabled = true
    // Sessions are the one outbound thing WITHOUT a consent hook, so the option itself is the
    // gate: bindCrashReporting sets the flag from the stored answer BEFORE first init, so this
    // reads the real consent. Decided per launch — a mid-session change applies from the next
    // start, which suits a launch-scoped concept.
    isEnableAutoSessionTracking = crashReportingAllowed
}

/** The trail rides in last but happened first: order the event's crumbs by time. */
private fun sortBreadcrumbs(event: SentryEvent) {
    event.breadcrumbs = event.breadcrumbs?.sortedBy { it.timestamp }
}

/**
 * Grouping for [SentryLogger.captureError] events (async stacks make frame-based grouping
 * meaningless here): a site code (`E#####`) groups alone — one code, one throw site, one issue;
 * a category code ("parse", "oom"; "network" and cancellations are never captured) and a non-coded exception
 * group per trail, so the same failure shape in two subsystems stays two issues. Events with no
 * trail tag (uncaught crashes the SDK captured itself) keep Sentry's default grouping. Each feedback
 * report is its own issue; everything else is tagged [REPORT_TYPE_ERROR] so the two filter apart.
 */
internal fun fingerprintPolicy(event: SentryEvent): SentryEvent {
    if (event.getTag(REPORT_TYPE_TAG) == REPORT_TYPE_FEEDBACK) {
        event.fingerprints = listOf(REPORT_TYPE_FEEDBACK, event.eventId.toString())
        return event
    }
    event.setTag(REPORT_TYPE_TAG, REPORT_TYPE_ERROR)
    // TrailMark rides throws as a SUPPRESSED marker, but sentry-java serializes suppressed
    // exceptions into the visible chain (exception groups) — it is transport, never a report.
    val exceptions = event.exceptions.orEmpty()
    if (exceptions.any { it.type == "TrailMark" }) {
        event.exceptions = exceptions.filterNot { it.type == "TrailMark" }
    }
    // An ANR or native crash is reported by the NEXT process, enriched from the scope Sentry persisted
    // before the death — including the trail/code tags of the last event captureError sent. They don't
    // describe this event and would group every such ANR under that unrelated code.
    if (exceptions.any { it.type in NEXT_PROCESS_REPORTS }) {
        event.removeTag(SentryLogger.TRAIL_TAG)
        event.removeTag(SentryLogger.CODE_TAG)
        return event
    }
    val code = event.getTag(SentryLogger.CODE_TAG)
    val trail = event.getTag(SentryLogger.TRAIL_TAG)
    if (code != null) {
        // A site code (E#####) IS one throw site — it groups alone. Category codes ("parse",
        // "oom") are shared by every subsystem, so they group per trail: a search parse failure
        // and a download parse failure are different issues.
        event.fingerprints = if (SITE_CODE.matches(code) || trail == null) listOf(code) else listOf(code, trail)
    } else if (trail != null) {
        event.fingerprints = listOf(event.exceptions?.lastOrNull()?.type ?: "error", trail)
    }
    return event
}

internal actual fun captureTrailedError(
    error: Throwable,
    trail: List<Crumb>,
    trailPath: String,
    code: String?,
    message: String?,
    prop: Map<String, Any>?,
) {
    Sentry.captureException(error) { scope ->
        scope.setTag(SentryLogger.TRAIL_TAG, trailPath)
        code?.let { scope.setTag(SentryLogger.CODE_TAG, it) }
        // One row per hop, stamped when the hop was entered; beforeSend orders them among the rest.
        for (crumb in trail) {
            scope.addBreadcrumb(
                Breadcrumb(Date(crumb.atMillis)).apply {
                    this.message = crumb.tag
                    crumb.prop?.forEach { (key, value) -> setData(key, value) }
                },
            )
        }
        message?.let { scope.setExtra("capture.message", it) }
        val context = mutableMapOf<String, Any>()
        prop?.forEach { (key, value) ->
            when (value) {
                is FileAttachment -> scope.addAttachment(Attachment(value.path, value.filename, value.contentType))
                is ByteArray -> scope.addAttachment(Attachment(value, key))
                else -> context[key] = value
            }
        }
        // Sentry drops a context that is not an object, so the props travel as one map.
        if (context.isNotEmpty()) scope.setContexts("capture", context)
    }
}

private val SITE_CODE = Regex("""E\d{5}""")

private val NEXT_PROCESS_REPORTS = setOf("ApplicationNotResponding", "SIGSEGV", "SIGABRT", "SIGBUS", "SIGILL", "SIGFPE", "SIGTRAP")
