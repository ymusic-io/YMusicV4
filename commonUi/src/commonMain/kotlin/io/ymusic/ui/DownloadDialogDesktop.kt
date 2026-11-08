package io.ymusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.download.captionsAsLyrics
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.download_action
import io.ymusic.ui.resources.download_audio_language
import io.ymusic.ui.resources.download_audio_only
import io.ymusic.ui.resources.download_container
import io.ymusic.ui.resources.download_file_name
import io.ymusic.ui.resources.download_forbidden_hint
import io.ymusic.ui.resources.download_format
import io.ymusic.ui.resources.download_loading
import io.ymusic.ui.resources.download_location_not_set
import io.ymusic.ui.resources.download_lyrics_note
import io.ymusic.ui.resources.download_mp4_note
import io.ymusic.ui.resources.download_name_empty
import io.ymusic.ui.resources.download_quality
import io.ymusic.ui.resources.download_save_to
import io.ymusic.ui.resources.download_subtitles
import io.ymusic.ui.resources.download_title
import io.ymusic.ui.resources.download_video
import org.jetbrains.compose.resources.stringResource

/** Design D: the download options as a two-pane dialog (desktop). */
@Composable
internal fun DownloadDialogDesktop(
    state: DownloadOptionsState,
    locationName: String?,
    onPickLocation: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: (() -> Unit)?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.download_title),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        maxWidth = 880.dp,
        buttons = {
            Text(
                summary(state),
                Modifier.weight(1f).padding(start = DesignSpacing.Space8),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSoft),
                maxLines = 2,
            )
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onDismiss)
            val ready = state.options != null && !state.isLive && DownloadFileNames.finalize(state.fileName).isNotEmpty()
            TextButton(onClick = onDownload, enabled = ready) {
                Text(stringResource(Res.string.download_action), color = if (ready) DesignColors.Accent else DesignColors.InkFaint)
            }
        },
    ) {
        Row(Modifier.verticalScroll(scroll).height(IntrinsicSize.Min).heightIn(min = 360.dp)) {
            LeftPane(state, locationName, onPickLocation)
            Box(Modifier.width(1.dp).fillMaxHeight().background(DesignColors.Divider))
            Column(
                Modifier.weight(1f).padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space8),
            ) {
                val options = state.options
                when {
                    state.isLive -> LiveNotDownloadable()
                    options == null && state.errorCode != null -> ProbeFailed(state.errorCode!!, onRetry, onSignIn)
                    options == null -> Text(
                        stringResource(Res.string.download_loading),
                        Modifier.padding(vertical = DesignSpacing.Space24),
                        style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary),
                    )
                    else -> {
                        TrackRows(state)
                        Box(Modifier.padding(top = DesignSpacing.Space8)) { SaveAsQuickCheckbox(state) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeftPane(state: DownloadOptionsState, locationName: String?, onPickLocation: () -> Unit) {
    Column(
        Modifier.width(272.dp).fillMaxHeight().background(DesignColors.SurfaceDim).padding(DesignSpacing.Space24),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            ThumbImage(state.video.thumbs, RoundedCornerShape(10.dp)) {
                ThumbPlaceholder(Icons.Outlined.Image, 26.dp)
            }
            Badges(state.badgeItem, Modifier.align(Alignment.BottomEnd).padding(DesignSpacing.Space8), textSize = 11, iconSize = 14.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
            Text(state.video.title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink, lineHeight = 18.sp), maxLines = 3)
        }
        FileNameField(state)
        Column(verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
            Text(stringResource(Res.string.download_save_to), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary))
            OutlinedButton(
                onClick = onPickLocation,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, DesignColors.Outline),
            ) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = DesignColors.InkSoft)
                Text(
                    locationName ?: stringResource(Res.string.download_location_not_set),
                    Modifier.weight(1f).padding(start = DesignSpacing.Space8),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.Ink),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FileNameField(state: DownloadOptionsState) {
    var stripped by remember { mutableStateOf(false) }
    val blank = DownloadFileNames.finalize(state.fileName).isEmpty()
    OutlinedTextField(
        value = state.fileName,
        onValueChange = { raw ->
            val clean = DownloadFileNames.stripForbidden(raw)
            stripped = clean != raw
            state.fileName = clean
        },
        label = { Text(stringResource(Res.string.download_file_name)) },
        suffix = { Text(state.extension, color = DesignColors.InkFaint) },
        isError = blank || stripped,
        supportingText = {
            Text(
                if (blank) {
                    stringResource(Res.string.download_name_empty)
                } else {
                    stringResource(Res.string.download_forbidden_hint, DownloadFileNames.FORBIDDEN_DISPLAY)
                },
            )
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = DesignColors.Ink),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackRows(state: DownloadOptionsState) {
    val options = state.options ?: return
    TrackRow(Icons.Outlined.InsertDriveFile, stringResource(Res.string.download_format)) {
        FormatDropdown(state)
    }
    if (state.showsAudioQuality) {
        TrackRow(Icons.Outlined.HighQuality, stringResource(Res.string.download_quality)) {
            val choices = audioQualityChoices(state)
            DropdownField(audioQualityLabel(state)) { close -> ChoiceMenuItems(choices, close) }
        }
    }
    TrackRow(Icons.Outlined.VolumeUp, stringResource(Res.string.download_audio_language)) {
        val choices = audioTrackChoices(state)
        DropdownField(audioTrackLabel(state)) { close -> ChoiceMenuItems(choices, close) }
    }
    if (options.captions.isNotEmpty() && state.captionsSupported) {
        TrackRow(Icons.Outlined.Subtitles, stringResource(Res.string.download_subtitles), alignTop = state.format.captionsAsLyrics) {
            Column(verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
                val choices = captionChoices(state)
                DropdownField(captionSummary(state)) { close -> ChoiceMenuItems(choices, close) }
                if (state.format.captionsAsLyrics) {
                    Text(stringResource(Res.string.download_lyrics_note), style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                }
            }
        }
    }
    if (state.format.kind == DownloadKind.VIDEO) {
        TrackRow(Icons.Outlined.ViewAgenda, stringResource(Res.string.download_container)) {
            Column(verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
                ContainerToggle(state)
                if (state.format == DownloadFormat.VIDEO_MP4) {
                    Text(stringResource(Res.string.download_mp4_note), style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                }
            }
        }
    }
}

@Composable
private fun TrackRow(icon: ImageVector, title: String, alignTop: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = DesignSpacing.Space16),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(DesignColors.SurfaceDim),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(18.dp), tint = DesignColors.InkSoft) }
        Text(
            title,
            Modifier.width(96.dp).padding(top = if (alignTop) DesignSpacing.Space8 else 0.dp),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
        )
        Box(Modifier.weight(1f)) { content() }
    }
    HorizontalDivider(color = DesignColors.DividerMini)
}

@Composable
private fun FormatDropdown(state: DownloadOptionsState) {
    val options = state.options ?: return
    val current = if (state.format.kind == DownloadKind.VIDEO) {
        state.selectedVideo?.let { "${state.format.displayName()} · ${it.label()}" }
    } else {
        state.format.displayName()
    }
    DropdownField(current.orEmpty()) { close ->
        if (options.video.isNotEmpty()) {
            MenuGroupLabel(stringResource(Res.string.download_video))
            options.video.forEach { video ->
                val bytes = if (state.format == DownloadFormat.VIDEO_WEBM) video.webmBytes ?: 0 else video.mp4Bytes
                DropdownMenuItem(text = { Text("${video.label()} — ${formatDownloadSize(bytes)}") }, onClick = {
                    state.audioOnly = false
                    state.height = video.height
                    close()
                })
            }
        }
        MenuGroupLabel(stringResource(Res.string.download_audio_only))
        options.audio.forEach { audio ->
            DropdownMenuItem(
                text = { Text("${audio.format.displayName()} · ${audio.detail()}") },
                onClick = {
                    state.audioOnly = true
                    state.audioFormat = audio.format
                    close()
                },
            )
        }
    }
}

@Composable
internal fun MenuGroupLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = DesignColors.InkSecondary),
    )
}

@Composable
internal fun DropdownField(value: String, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .border(1.dp, DesignColors.Outline, RoundedCornerShape(9.dp))
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = DesignSpacing.Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, Modifier.weight(1f), style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink), maxLines = 1)
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp), tint = DesignColors.InkSoft)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = DesignColors.Surface) {
            menu { open = false }
        }
    }
}

@Composable
private fun ContainerToggle(state: DownloadOptionsState) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(DesignColors.SurfaceDim).padding(DesignSpacing.Space2),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space2),
    ) {
        state.containers.forEach { container ->
            val selected = state.format == container
            Box(
                Modifier
                    .width(96.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) DesignColors.Surface else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { state.container = container },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    container.displayName(),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) DesignColors.Ink else DesignColors.InkSecondary),
                )
            }
        }
    }
}

/** "MP4 · 1080p60 · English · Captions: English · in file · ≈ 92 MB". */
@Composable
private fun summary(state: DownloadOptionsState): String {
    val options = state.options ?: return ""
    val parts = buildList {
        add(state.format.displayName())
        if (state.format.kind == DownloadKind.VIDEO) state.selectedVideo?.let { add(it.label()) }
        options.audioTracks.firstOrNull { it.id == state.audioTrackId }?.let { add(it.name) }
        if (state.caption != null && state.captionsSupported) add(captionSummary(state))
        state.selectedBytes?.let { add("≈ " + formatDownloadSize(it)) }
    }
    return parts.joinToString(" · ")
}
