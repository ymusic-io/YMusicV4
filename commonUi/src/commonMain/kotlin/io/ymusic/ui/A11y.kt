package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.a11y_duration_hours
import io.ymusic.ui.resources.a11y_duration_minutes
import io.ymusic.ui.resources.a11y_duration_seconds
import org.jetbrains.compose.resources.pluralStringResource
import kotlin.math.roundToInt

/** A length in words, "1 hour 5 minutes 3 seconds": a clock like "1:05:03" reads digit by digit. */
@Composable
internal fun spokenDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = (total / 3600).toInt()
    val m = (total % 3600 / 60).toInt()
    val s = (total % 60).toInt()
    return listOfNotNull(
        if (h > 0) pluralStringResource(Res.plurals.a11y_duration_hours, h, h) else null,
        if (m > 0) pluralStringResource(Res.plurals.a11y_duration_minutes, m, m) else null,
        if (s > 0 || total == 0L) pluralStringResource(Res.plurals.a11y_duration_seconds, s, s) else null,
    ).joinToString(" ")
}

/** [clock] ("3:45", "1:02:03") in words; null when it is no clock. */
@Composable
internal fun spokenClock(clock: String): String? = clockMillis(clock)?.let { spokenDuration(it) }

internal fun clockMillis(clock: String): Long? {
    val parts = clock.trim().split(':')
    if (parts.size !in 2..3 || parts.any { part -> part.isEmpty() || part.length > 4 || part.any { it !in '0'..'9' } }) return null
    return parts.fold(0L) { total, part -> total * 60 + part.toLong() } * 1000
}

/** The length of an item that carries both: [ms] when known, else its [clock] badge in words. */
@Composable
internal fun spokenLength(ms: Long?, clock: String?): String? =
    ms?.takeIf { it > 0 }?.let { spokenDuration(it) } ?: clock?.let { spokenClock(it) }

/** [a11ySlider] for TalkBack and VoiceOver: [value] is the spoken state, and their adjust gestures
 *  arrive as setProgress. The range moves in whole percents so a playing track doesn't churn it. */
internal fun Modifier.rangeSliderSemantics(
    label: String?,
    value: String,
    fraction: Float,
    enabled: Boolean,
    onSet: (Float) -> Unit,
): Modifier = semantics {
    if (label != null) contentDescription = label
    stateDescription = value
    progressBarRangeInfo = ProgressBarRangeInfo((fraction.coerceIn(0f, 1f) * 100).roundToInt() / 100f, 0f..1f)
    if (enabled) {
        setProgress { target ->
            onSet(target.coerceIn(0f, 1f))
            true
        }
    } else {
        disabled()
    }
}

/** A non-interactive node read as [spoken] instead of its visible text and children. */
internal fun Modifier.spokenAs(spoken: String): Modifier = clearAndSetSemantics { text = AnnotatedString(spoken) }

/** A node a screen reader skips: its text and children are already in an ancestor's label. */
internal fun Modifier.a11ySilent(): Modifier = clearAndSetSemantics {}
