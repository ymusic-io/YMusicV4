package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.update_allow_install
import io.ymusic.ui.resources.update_ask_later
import io.ymusic.ui.resources.update_available_title
import io.ymusic.ui.resources.update_check
import io.ymusic.ui.resources.update_check_failed
import io.ymusic.ui.resources.update_checking
import io.ymusic.ui.resources.update_close
import io.ymusic.ui.resources.update_download
import io.ymusic.ui.resources.update_download_in_browser
import io.ymusic.ui.resources.update_downloading
import io.ymusic.ui.resources.update_failed
import io.ymusic.ui.resources.update_full_changelog
import io.ymusic.ui.resources.update_install_now
import io.ymusic.ui.resources.update_install_permission
import io.ymusic.ui.resources.update_install_permission_allow
import io.ymusic.ui.resources.update_installing
import io.ymusic.ui.resources.update_manual_hint
import io.ymusic.ui.resources.update_official_site
import io.ymusic.ui.resources.update_ready
import io.ymusic.ui.resources.update_up_to_date
import io.ymusic.ui.resources.update_version
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.DISK_FULL_CODE

/**
 * The update prompt, one shape for every state of the release: the version, its brief notes, the
 * full changelog, and below them what the user's own download is doing.
 *
 * Progress and failure show only once the user has tapped "Download" this run: a background
 * download is invisible until then (it prompts only once ready), and "Download" joins it, retries
 * it, or starts it. A ready
 * installer turns the button into "Install now" however it was downloaded. A failure links the
 * official download site. Without the OS's install permission the user is asked to grant it; the
 * browser download is offered only once an installer has waited on that for a while.
 *
 * "Ask later" hides it for the rest of this run (the update itself stays offered: the drawer still
 * flags it), except that a download the user started brings it back once ready; the next cold
 * start or a "Check for update" asks again.
 */
@Composable
fun UpdateDialog(graph: AppGraph, recheck: Int = 0) {
    val updates = graph.updates ?: return
    val status by updates.status.collectAsState()
    val uriHandler = LocalUriHandler.current
    // Keyed on [recheck]: a manual "Check for update" brings back a release asked about later.
    var dismissedVersion by remember(recheck) { mutableStateOf<Int?>(null) }
    var requestedVersion by remember { mutableStateOf<Int?>(null) }
    val offer = status as? AppUpdateStatus.Offered ?: return
    val requested = requestedVersion == offer.versionCode
    val download = offer.download
    LaunchedEffect(requested && download is UpdateDownload.Ready) {
        if (requested && download is UpdateDownload.Ready) dismissedVersion = null
    }
    if (offer.versionCode == dismissedVersion) return
    // A background download in flight never prompts on its own; it does once ready, or on "Check for update".
    if (!requested && download is UpdateDownload.Running && recheck == 0) return

    val blocked = updates.blockedReason
    val inPlace = blocked == null && offer.installInApp
    val askLater: () -> Unit = { dismissedVersion = offer.versionCode }
    val openDownloadPage: (() -> Unit)? = offer.downloadPage?.let { url -> { uriHandler.openUri(url) } }

    ElevatedBarDialog(
        title = stringResource(Res.string.update_available_title),
        // Back acts as the first button; a tap outside does nothing — only the buttons close it.
        onDismiss = askLater,
        dismissOnClickOutside = false,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.update_ask_later), onClick = askLater)
            when {
                !inPlace -> TextButton(onClick = { openDownloadPage?.invoke() }, enabled = openDownloadPage != null) {
                    Text(stringResource(Res.string.update_download))
                }
                download is UpdateDownload.NeedsInstallPermission -> TextButton(onClick = { updates.allowInstall() }) {
                    Text(stringResource(Res.string.update_allow_install))
                }
                download is UpdateDownload.Ready || download is UpdateDownload.InstallFailed -> TextButton(onClick = { updates.installAndRestart() }) {
                    Text(stringResource(Res.string.update_install_now))
                }
                download is UpdateDownload.Installing || (requested && download is UpdateDownload.Running) -> TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(Res.string.update_install_now))
                }
                else -> TextButton(
                    onClick = {
                        requestedVersion = offer.versionCode
                        updates.download()
                    },
                ) { Text(stringResource(Res.string.update_download)) }
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.update_version, offer.versionName),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                )
                if (inPlace && download is UpdateDownload.Ready) {
                    Text(
                        stringResource(Res.string.update_ready),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.Accent),
                    )
                }
            }
            ChangeNotes(offer.notes)
            offer.changelogUrl?.let { url -> LinkText(stringResource(Res.string.update_full_changelog)) { uriHandler.openUri(url) } }
            if (requested && download is UpdateDownload.Running) {
                Text(
                    stringResource(Res.string.update_downloading, formatBytes(download.done), formatBytes(download.total)),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                )
                LinearProgressIndicator(
                    progress = { if (download.total > 0) (download.done.toFloat() / download.total) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (download is UpdateDownload.Installing) {
                Text(
                    stringResource(Res.string.update_installing),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (download is UpdateDownload.NeedsInstallPermission) {
                val browserLink = openDownloadPage?.takeIf { download.offerBrowser }
                Text(
                    stringResource(
                        if (browserLink != null) Res.string.update_install_permission else Res.string.update_install_permission_allow,
                    ),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                )
                browserLink?.let { LinkText(stringResource(Res.string.update_download_in_browser), it) }
            }
            if (requested && download is UpdateDownload.Failed) {
                Text(
                    if (download.code == DISK_FULL_CODE) errorMessage(download.code) else stringResource(Res.string.update_failed, download.code),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                )
                openDownloadPage?.let { LinkText(stringResource(Res.string.update_official_site), it) }
            }
            if (download is UpdateDownload.InstallFailed) {
                Text(
                    if (download.code == DISK_FULL_CODE) errorMessage(download.code) else stringResource(Res.string.update_failed, download.code),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                )
                openDownloadPage?.let { LinkText(stringResource(Res.string.update_official_site), it) }
            }
            if (blocked != null) {
                Text(
                    stringResource(Res.string.update_manual_hint, stringResource(blocked)),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                )
            }
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = DesignSpacing.Space4),
    )
}

/**
 * "Check for update" from the drawer: checking, then up to date or failed. A found update closes
 * this ([onDone] true) and the update prompt takes over.
 */
@Composable
fun CheckUpdateDialog(graph: AppGraph, onDone: (found: Boolean) -> Unit) {
    val updates = graph.updates ?: return
    var result by remember { mutableStateOf<UpdateCheck?>(null) }
    LaunchedEffect(Unit) {
        val check = updates.checkNow()
        if (check == UpdateCheck.Found) onDone(true) else result = check
    }
    ElevatedBarDialog(
        title = stringResource(Res.string.update_check),
        onDismiss = { onDone(false) },
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.update_close)) { onDone(false) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (result) {
                null -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.update_checking), style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
                }
                UpdateCheck.UpToDate -> {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = DesignColors.BrandGreen)
                    Text(stringResource(Res.string.update_up_to_date), style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
                }
                else -> Text(
                    stringResource(Res.string.update_check_failed),
                    style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary),
                )
            }
        }
    }
}

@Composable
private fun ChangeNotes(notes: String) {
    if (notes.isBlank()) return
    Text(
        notes,
        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
        maxLines = 7,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
