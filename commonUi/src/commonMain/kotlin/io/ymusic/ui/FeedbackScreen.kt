package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.settings.AppSettings
import io.ymusic.settings.anonymousDeviceId
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.feedback_app
import io.ymusic.ui.resources.feedback_continue
import io.ymusic.ui.resources.feedback_copied
import io.ymusic.ui.resources.feedback_copy
import io.ymusic.ui.resources.feedback_device_id
import io.ymusic.ui.resources.feedback_email_hint
import io.ymusic.ui.resources.feedback_email_subject
import io.ymusic.ui.resources.feedback_email_title
import io.ymusic.ui.resources.feedback_engine
import io.ymusic.ui.resources.feedback_github_hint
import io.ymusic.ui.resources.feedback_github_title
import io.ymusic.ui.resources.feedback_info_note
import io.ymusic.ui.resources.feedback_info_title
import io.ymusic.ui.resources.feedback_intro
import io.ymusic.ui.resources.feedback_language
import io.ymusic.ui.resources.feedback_prefilled_note
import io.ymusic.ui.resources.feedback_reports_off_message
import io.ymusic.ui.resources.feedback_reports_off_note
import io.ymusic.ui.resources.feedback_reports_off_title
import io.ymusic.ui.resources.feedback_sentry_label
import io.ymusic.ui.resources.feedback_sentry_link
import io.ymusic.ui.resources.feedback_sentry_note
import io.ymusic.ui.resources.feedback_sentry_send
import io.ymusic.ui.resources.feedback_sentry_sent
import io.ymusic.ui.resources.feedback_sentry_title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The drawer's Feedback section: the anonymous info a user quotes on GitHub or by email, copied by
 * its Copy button and prefilled into both, or sent with a message through Sentry. The app logs go
 * out through [AppGraph.captureFeedbackReport], found in Sentry by the Device ID: once per visit on
 * Copy, GitHub or email, and with every sent message; with anonymous reports off, a dialog first asks
 * to turn them back on, and declining leaves the section.
 */
@Composable
internal fun FeedbackScreen(graph: AppGraph, onDismiss: () -> Unit) {
    val capture = graph.captureFeedbackReport
    val log = remember(graph) { graph.logger?.fork("Feedback") }
    val reportsEnabled = remember(graph) { graph.settings.flow(AppSettings.crashReports) }.collectAsState().value
    var consented by remember { mutableStateOf(capture == null || reportsEnabled) }
    val deviceId by produceState("", graph) { value = graph.settings.anonymousDeviceId() }
    val config by remember(graph) { graph.remoteConfig ?: emptyFlow() }.collectAsState(initial = null)
    val links = AppLinks(config)
    val bundles = graph.runningBundles
    bundles?.engineFlow?.collectAsState()
    bundles?.playerFlow?.collectAsState()
    val info = FeedbackInfo(
        deviceId = deviceId,
        app = listOfNotNull(
            "YMusic" + (graph.appVersionName?.let { name -> " $name" + (graph.appVersionCode?.let { " ($it)" } ?: "") } ?: ""),
            graph.platformInfo,
        ).joinToString(" · "),
        engine = bundles?.let { "engine ${it.reportedEngine ?: "-"} · player ${it.reportedPlayer ?: "-"}" },
        language = Locale.current.toLanguageTag(),
        email = links.feedbackEmail,
        issueUrl = links.feedbackIssue,
    )
    val currentInfo by rememberUpdatedState(info)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy = { scope.launch { clipboard.setClipEntry(plainTextClipEntry(currentInfo.text)) } }
    val sendReport: (String, String?) -> Unit = { subject, message ->
        if (capture != null) {
            FeedbackSendScope.launch {
                try {
                    capture(subject, message)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    log?.captureError(e, "feedback report capture failed")
                }
            }
        }
    }
    var contactReportSent by remember { mutableStateOf(false) }
    val sendContactReport: (String) -> Unit = { channel ->
        if (!contactReportSent) {
            contactReportSent = true
            sendReport(channel, null)
        }
    }

    if (!consented) {
        ReportsOffDialog(
            onContinue = {
                scope.launch {
                    graph.settings.put(AppSettings.crashReports, true)
                    consented = true
                }
            },
            onCancel = onDismiss,
        )
        return
    }

    val scrollState = rememberScrollState()
    TrackScroll { scrollState.canScrollBackward }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = gridMetricsFor(maxWidth).margin
        val centerVertically = maxWidth >= 840.dp && maxHeight >= 600.dp
        val viewportHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = LocalMiniPlayerInset.current),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = DesignSpacing.FormMaxWidth)
                    .fillMaxWidth()
                    .then(if (centerVertically) Modifier.heightIn(min = viewportHeight) else Modifier)
                    .padding(horizontal = margin, vertical = DesignSpacing.Space16),
                verticalArrangement = if (centerVertically) {
                    Arrangement.spacedBy(DesignSpacing.Space16, Alignment.CenterVertically)
                } else {
                    Arrangement.spacedBy(DesignSpacing.Space16)
                },
            ) {
                Text(
                    stringResource(Res.string.feedback_intro),
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = DesignColors.InkMuted),
                )
                var copiedByTap by remember { mutableStateOf(false) }
                LaunchedEffect(copiedByTap) {
                    if (copiedByTap) {
                        delay(COPIED_LABEL_MILLIS)
                        copiedByTap = false
                    }
                }
                InfoCard(info, copied = copiedByTap, onCopy = {
                    sendContactReport("Copy")
                    copy()
                    copiedByTap = true
                })
                val uriHandler = LocalUriHandler.current
                ContactRow(
                    icon = Icons.Outlined.Public,
                    title = stringResource(Res.string.feedback_github_title),
                    hint = stringResource(Res.string.feedback_github_hint),
                    onClick = {
                        sendContactReport("Github")
                        uriHandler.openUri(currentInfo.githubIssueUrl())
                    },
                )
                val subject = stringResource(Res.string.feedback_email_subject)
                ContactRow(
                    icon = Icons.Outlined.Email,
                    title = stringResource(Res.string.feedback_email_title, links.feedbackEmail),
                    hint = stringResource(Res.string.feedback_email_hint),
                    onClick = {
                        sendContactReport("Email")
                        uriHandler.openUri(currentInfo.mailtoUrl(subject))
                    },
                )
                if (capture != null) {
                    var writing by remember { mutableStateOf(false) }
                    var sent by remember { mutableStateOf(false) }
                    SentryLink(sent = sent, onClick = { writing = true })
                    if (writing) {
                        SentryMessageDialog(
                            onSend = { message ->
                                sendReport(message, message)
                                sent = true
                                writing = false
                            },
                            onCancel = { writing = false },
                        )
                    }
                }
                Text(
                    stringResource(Res.string.feedback_prefilled_note),
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = DesignColors.InkSoft),
                )
            }
        }
    }
}

@Composable
internal fun ReportsOffDialog(onContinue: () -> Unit, onCancel: () -> Unit) {
    ElevatedBarDialog(
        title = stringResource(Res.string.feedback_reports_off_title),
        onDismiss = onCancel,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onCancel)
            TextButton(onClick = onContinue) { Text(stringResource(Res.string.feedback_continue), color = DesignColors.Accent) }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            Text(
                stringResource(Res.string.feedback_reports_off_message),
                style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink, lineHeight = 20.sp),
            )
            Text(
                stringResource(Res.string.feedback_reports_off_note),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSoft, lineHeight = 18.sp),
            )
        }
    }
}

@Composable
private fun SentryLink(sent: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (sent) {
            Text(
                stringResource(Res.string.feedback_sentry_sent),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSoft),
                modifier = Modifier.padding(DesignSpacing.Space8),
            )
        } else {
            Text(
                stringResource(Res.string.feedback_sentry_link),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
                modifier = Modifier.clip(RoundedCornerShape(DesignSpacing.Space4)).clickable(onClick = onClick).padding(DesignSpacing.Space8),
            )
        }
    }
}

@Composable
private fun SentryMessageDialog(onSend: (String) -> Unit, onCancel: () -> Unit) {
    var message by remember { mutableStateOf("") }
    ElevatedBarDialog(
        title = stringResource(Res.string.feedback_sentry_title),
        onDismiss = onCancel,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onCancel)
            TextButton(enabled = message.isNotBlank(), onClick = { onSend(message.trim()) }) {
                Text(stringResource(Res.string.feedback_sentry_send), color = DesignColors.Accent)
            }
        },
        dismissOnClickOutside = false,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            AppTextField(
                value = message,
                onValueChange = { message = it },
                label = stringResource(Res.string.feedback_sentry_label),
                singleLine = false,
                autoFocus = true,
            )
            Text(
                stringResource(Res.string.feedback_sentry_note),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSoft, lineHeight = 18.sp),
            )
        }
    }
}

@Composable
private fun InfoCard(info: FeedbackInfo, copied: Boolean, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(DesignColors.SettingsCard, RoundedCornerShape(DesignSpacing.SettingsCardCorner))
            .padding(start = DesignSpacing.Space16, end = DesignSpacing.Space4, top = DesignSpacing.Space4, bottom = DesignSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.feedback_info_title).uppercase(),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkMuted),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    tint = DesignColors.Accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(if (copied) Res.string.feedback_copied else Res.string.feedback_copy),
                    color = DesignColors.Accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = DesignSpacing.Space8),
                )
            }
        }
        Column(Modifier.padding(end = DesignSpacing.Space16), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
            if (info.deviceId.isNotEmpty()) InfoRow(stringResource(Res.string.feedback_device_id), info.deviceId, monospace = true)
            InfoRow(stringResource(Res.string.feedback_app), info.app)
            info.engine?.let { InfoRow(stringResource(Res.string.feedback_engine), it) }
            InfoRow(stringResource(Res.string.feedback_language), info.language)
            Text(
                stringResource(Res.string.feedback_info_note),
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = DesignColors.InkSoft),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        Text(label, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = DesignColors.InkSoft), modifier = Modifier.width(InfoLabelWidth))
        Text(
            value,
            style = TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = DesignColors.InkStrong,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ContactRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DesignSpacing.SettingsCardCorner)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, DesignColors.OutlineSoft, shape)
            .background(DesignColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.DialogRowVertical),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(DesignColors.Accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = DesignColors.Accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space2)) {
            Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkStrong))
            Text(hint, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = DesignColors.InkSoft))
        }
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = DesignColors.InkSoft, modifier = Modifier.size(18.dp))
    }
}

private val InfoLabelWidth = 96.dp

private const val COPIED_LABEL_MILLIS = 2_000L

/** Fire and forget: a sent report must go out even when the user leaves the section right away. */
private val FeedbackSendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
