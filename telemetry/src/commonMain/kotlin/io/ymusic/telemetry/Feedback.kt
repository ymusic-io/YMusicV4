package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Attachment
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.UserFeedback

/**
 * Sends the app logs as one anonymous feedback event titled "Feedback: [subject]"; a user quoting their
 * Device ID finds it by the device_id tag. A [message] is also linked to the event in the feedback
 * inbox. Every report is its own issue. A no-op until Sentry.init ran (the SDK hands back the empty id).
 */
fun captureFeedbackReport(subject: String, message: String?, attachments: List<Attachment>) {
    val eventId = Sentry.captureMessage("Feedback: $subject") { scope ->
        scope.setTag(REPORT_TYPE_TAG, REPORT_TYPE_FEEDBACK)
        attachments.forEach(scope::addAttachment)
    }
    if (message == null || eventId.toString().all { char -> char == '0' || char == '-' }) return
    Sentry.captureUserFeedback(UserFeedback(eventId).also { it.comments = message })
}

internal const val REPORT_TYPE_TAG = "report_type"
internal const val REPORT_TYPE_FEEDBACK = "feedback"
internal const val REPORT_TYPE_ERROR = "error"
