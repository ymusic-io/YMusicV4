package io.ymusic.telemetry

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import projectcommon.error.CodedError
import projectcommon.error.NETWORK_CODE
import projectcommon.error.codedError
import projectcommon.log.trailMark

/**
 * The app's root [Logger] over Sentry: every [captureError] a Sentry event (the ignored categories
 * excepted, and those [bindErrorPolicy]'s remote rules filter or sample out) whose grouping comes from the coded error — else exception type + tag path — applied by
 * [applyYMusicTelemetry]'s `beforeSend` off the tags set here. Lines are not sent from here: the app
 * log (`:log-store`) keeps them, and an error event takes the newest along ([installErrorLogs]).
 */
class SentryLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {

    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = SentryLogger(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) = Unit

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any>?) {
        // The deepest trail stamped in flight (Throwable.markTrail), else this catch site's.
        val trail = error.trailMark() ?: crumbs
        val trailPath = trail.joinToString("/") { it.tag }
        val coded = error.codedError()
        if (isIgnored(error, coded) || !errorPolicy.allows(error)) return
        captureTrailedError(error, trail, trailPath, coded?.code, message, prop)
    }

    internal companion object {
        internal const val TRAIL_TAG = "trail"
        internal const val CODE_TAG = "error.code"

        /** Failures that are never defects — the user's connectivity, a cancelled job: the UI already
         *  shows the localized category, so they are INFO logs, never Sentry events. */
        private val IGNORED_CATEGORIES = setOf(NETWORK_CODE)

        private fun isIgnored(error: Throwable, coded: CodedError?): Boolean {
            if (coded?.code in IGNORED_CATEGORIES) return true
            var cause: Throwable? = error
            var depth = 0 // cause chains can be cyclic on some platforms
            while (cause != null && depth++ < 100) {
                if (cause is CancellationException) return true
                cause = cause.cause
            }
            return false
        }
    }
}

/** Sends [error] as an event: [trailPath] and [code] as the grouping tags, every [trail] hop a
 *  breadcrumb stamped with its own time, [message] and [prop] as extras/contexts/attachments. */
internal expect fun captureTrailedError(
    error: Throwable,
    trail: List<Crumb>,
    trailPath: String,
    code: String?,
    message: String?,
    prop: Map<String, Any>?,
)
