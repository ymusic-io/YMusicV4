package io.ymusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.download.AudioOption
import io.ymusic.download.CaptionOption
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.download.Mp3Encoding
import io.ymusic.download.StillProcessingException
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.download_action
import io.ymusic.ui.resources.download_action_detail
import io.ymusic.ui.resources.download_audio_only
import io.ymusic.ui.resources.download_audio_track
import io.ymusic.ui.resources.download_caption_translated
import io.ymusic.ui.resources.download_captions
import io.ymusic.ui.resources.download_captions_as_lyrics
import io.ymusic.ui.resources.download_captions_off
import io.ymusic.ui.resources.download_container
import io.ymusic.ui.resources.download_default_track
import io.ymusic.ui.resources.download_down_to
import io.ymusic.ui.resources.download_edit_name
import io.ymusic.ui.resources.download_failed
import io.ymusic.ui.resources.download_file_name
import io.ymusic.ui.resources.download_forbidden_hint
import io.ymusic.ui.resources.download_format
import io.ymusic.ui.resources.download_less
import io.ymusic.ui.resources.download_live_unsupported
import io.ymusic.ui.resources.download_loading
import io.ymusic.ui.resources.download_location_not_set
import io.ymusic.ui.resources.download_m4a_detail
import io.ymusic.ui.resources.download_more_qualities
import io.ymusic.ui.resources.download_mp3_detail
import io.ymusic.ui.resources.download_name_empty
import io.ymusic.ui.resources.download_quality
import io.ymusic.ui.resources.download_rename
import io.ymusic.ui.resources.download_save
import io.ymusic.ui.resources.download_save_quick
import io.ymusic.ui.resources.download_save_to
import io.ymusic.ui.resources.download_video
import io.ymusic.ui.resources.download_webm_detail
import io.ymusic.ui.resources.error_live_still_processing
import io.ymusic.ui.resources.playback_retry_later
import io.ymusic.ui.resources.playback_sign_in_required
import io.ymusic.ui.resources.playback_unavailable
import io.ymusic.ui.resources.retry
import io.ymusic.ui.resources.signin_button
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.Playability
import projectcommon.error.playabilityOf

/** The filled call-to-action: the accent, deepened so white text on it stays readable. */
internal val downloadPrimary: Color @Composable get() = lerp(DesignColors.Accent, Color.Black, 0.22f)

private const val QUALITY_COLUMNS = 3

/** More qualities than this collapse behind a "+N more" tile. */
private const val QUALITY_COLLAPSE_OVER = 6

/** Design A: the download options as a bottom sheet (touch platforms). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadSheet(
    state: DownloadOptionsState,
    locationName: String?,
    onPickLocation: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: (() -> Unit)?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DesignColors.Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = DesignSpacing.Space24, end = DesignSpacing.Space24, bottom = DesignSpacing.Space24),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            SheetHeader(state, onRename = { renaming = true })
            val options = state.options
            when {
                state.isLive -> LiveNotDownloadable()
                options == null && state.errorCode != null -> ProbeFailed(state.errorCode!!, onRetry, onSignIn)
                options == null -> ProbeLoading()
                else -> {
                    if (state.hasVideo) TypeSegments(state)
                    if (state.hasVideo && !state.audioOnly) QualityGrid(state) else AudioFormats(state, options.audio)
                    Column {
                        if (state.format.kind == DownloadKind.VIDEO) {
                            DropdownOptionRow(
                                Icons.Outlined.InsertDriveFile,
                                stringResource(Res.string.download_container),
                                state.format.displayName(),
                                containerChoices(state),
                            )
                        }
                        DropdownOptionRow(
                            Icons.Outlined.RecordVoiceOver,
                            stringResource(Res.string.download_audio_track),
                            audioTrackLabel(state),
                            audioTrackChoices(state),
                        )
                        if (options.captions.isNotEmpty() && state.captionsSupported) {
                            DropdownOptionRow(
                                Icons.Outlined.Subtitles,
                                stringResource(Res.string.download_captions),
                                captionSummary(state),
                                captionChoices(state),
                            )
                        }
                        OptionRow(
                            Icons.Outlined.Folder,
                            stringResource(Res.string.download_save_to),
                            locationName ?: stringResource(Res.string.download_location_not_set),
                            onClick = onPickLocation,
                        )
                    }
                }
            }
            if (state.options != null && !state.isLive) SaveAsQuickCheckbox(state)
            DownloadButton(state, onDownload)
        }
    }
    if (renaming) RenameDialog(state, onDone = { renaming = false })
}

@Composable
private fun SheetHeader(state: DownloadOptionsState, onRename: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            ThumbImage(state.video.thumbs, RoundedCornerShape(8.dp)) {
                ThumbPlaceholder(Icons.Outlined.MusicNote, 24.dp)
            }
            Badges(state.video, Modifier.align(Alignment.BottomEnd).padding(DesignSpacing.Space4), textSize = 10, iconSize = 12.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
            Text(
                buildAnnotatedString {
                    append(state.fileName)
                    withStyle(SpanStyle(color = DesignColors.InkFaint, fontWeight = FontWeight.Medium)) { append(state.extension) }
                },
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink, lineHeight = 20.sp),
            )
        }
        IconButton(onClick = onRename, modifier = Modifier.a11yActionLabel(stringResource(Res.string.download_edit_name))) {
            Icon(Icons.Outlined.Edit, null, tint = DesignColors.InkSoft)
        }
    }
}

@Composable
private fun ProbeLoading() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = DesignSpacing.Space24),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = DesignColors.Accent, strokeWidth = 2.dp)
        Text(stringResource(Res.string.download_loading), style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary))
    }
}

@Composable
internal fun LiveNotDownloadable() {
    Text(
        stringResource(Res.string.download_live_unsupported),
        Modifier.fillMaxWidth().padding(vertical = DesignSpacing.Space24),
        style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary, textAlign = TextAlign.Center),
    )
}

@Composable
internal fun ProbeFailed(code: String, onRetry: () -> Unit, onSignIn: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = DesignSpacing.Space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        val playability = playabilityOf(code)
        val message = when (playability) {
            Playability.SignInRequired -> stringResource(Res.string.playback_sign_in_required)
            Playability.Unavailable -> stringResource(Res.string.playback_unavailable)
            Playability.RetryLater -> stringResource(Res.string.playback_retry_later)
            null -> if (code == StillProcessingException.CODE) {
                stringResource(Res.string.error_live_still_processing)
            } else {
                stringResource(Res.string.download_failed, code)
            }
        }
        Text(message, style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary))
        when {
            playability == Playability.SignInRequired && onSignIn != null ->
                TextButton(onClick = onSignIn) { Text(stringResource(Res.string.signin_button), color = DesignColors.Accent) }
            // YouTube's verdict, not a fault: a re-probe gets the same answer.
            playability == Playability.Unavailable -> Unit
            else -> TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry), color = DesignColors.Accent) }
        }
    }
}

@Composable
private fun TypeSegments(state: DownloadOptionsState) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DesignColors.SurfaceDim)
            .padding(DesignSpacing.Space4),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
    ) {
        Segment(Icons.Outlined.Headphones, stringResource(Res.string.download_audio_only), state.audioOnly, Modifier.weight(1f)) {
            state.audioOnly = true
        }
        Segment(Icons.Outlined.Videocam, stringResource(Res.string.download_video), !state.audioOnly, Modifier.weight(1f)) {
            state.audioOnly = false
        }
    }
}

@Composable
private fun Segment(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) DesignColors.Surface else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) DesignColors.Ink else DesignColors.InkSecondary
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = DesignColors.InkSecondary),
    )
}

@Composable
private fun QualityGrid(state: DownloadOptionsState) {
    val video = state.options?.video.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val collapsible = video.size > QUALITY_COLLAPSE_OVER
    val shown = if (collapsible && !expanded) video.take(QUALITY_COLLAPSE_OVER - 1) else video
    Column(verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
        SectionLabel(stringResource(Res.string.download_quality))
        val tiles: List<@Composable (Modifier) -> Unit> = buildList {
            shown.forEach { option ->
                add { modifier ->
                    Tile(option.label(), formatDownloadSize(if (state.format == DownloadFormat.VIDEO_WEBM) option.webmBytes ?: 0 else option.mp4Bytes), option.height == state.height, modifier) {
                        state.height = option.height
                    }
                }
            }
            if (collapsible) {
                add { modifier ->
                    if (expanded) {
                        MoreTile(stringResource(Res.string.download_less), null, modifier) { expanded = false }
                    } else {
                        MoreTile(
                            stringResource(Res.string.download_more_qualities, video.size - shown.size),
                            stringResource(Res.string.download_down_to, video.last().label()),
                            modifier,
                        ) { expanded = true }
                    }
                }
            }
        }
        tiles.chunked(QUALITY_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(QUALITY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(label: String, size: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DesignColors.Accent.copy(alpha = 0.12f) else DesignColors.Surface)
            .border(
                if (selected) BorderStroke(2.dp, DesignColors.Accent) else BorderStroke(1.dp, DesignColors.OutlineSoft),
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
        Text(size, style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
    }
}

@Composable
private fun MoreTile(label: String, detail: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DesignColors.SurfaceDim)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
        detail?.let { Text(it, style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary)) }
    }
}

@Composable
private fun AudioFormats(state: DownloadOptionsState, audio: List<AudioOption>) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
        SectionLabel(stringResource(Res.string.download_format))
        audio.forEach { option ->
            val selected = state.audioFormat == option.format
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) DesignColors.Accent.copy(alpha = 0.12f) else DesignColors.Surface)
                    .border(
                        if (selected) BorderStroke(2.dp, DesignColors.Accent) else BorderStroke(1.dp, DesignColors.OutlineSoft),
                        RoundedCornerShape(12.dp),
                    )
                    .selectable(selected = selected, role = Role.RadioButton) { state.audioFormat = option.format }
                    .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = DesignColors.Accent))
                Column(Modifier.weight(1f)) {
                    Text(option.format.displayName(), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
                    Text(option.detail(), style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                }
                Text(formatDownloadSize(option.bytes), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary))
            }
        }
    }
}

internal fun DownloadFormat.displayName(): String = when (this) {
    DownloadFormat.AUDIO_WEBM -> "Opus"
    DownloadFormat.VIDEO_WEBM -> "WebM"
    DownloadFormat.M4A -> "M4A"
    DownloadFormat.MP3 -> "MP3"
    DownloadFormat.VIDEO_MP4 -> "MP4"
}

@Composable
internal fun AudioOption.detail(): String = when (format) {
    DownloadFormat.AUDIO_WEBM -> stringResource(Res.string.download_webm_detail)
    DownloadFormat.M4A -> stringResource(Res.string.download_m4a_detail)
    DownloadFormat.MP3 -> stringResource(Res.string.download_mp3_detail, kbps ?: Mp3Encoding.ABR_320.kbps)
    DownloadFormat.VIDEO_WEBM, DownloadFormat.VIDEO_MP4 -> ""
}

/** An [OptionRow] that opens a dropdown of [choices] anchored under it. */
@Composable
private fun DropdownOptionRow(icon: ImageVector, title: String, value: String, choices: List<Choice>) {
    var open by remember { mutableStateOf(false) }
    Box {
        OptionRow(icon, title, value) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(x = DesignSpacing.Space48, y = 0.dp),
            containerColor = DesignColors.Surface,
        ) { ChoiceMenuItems(choices) { open = false } }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = DesignSpacing.Space16),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = DesignColors.InkSoft)
        Text(title, style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink), maxLines = 1)
        Text(
            value,
            Modifier.weight(1f),
            style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary, textAlign = TextAlign.End),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = DesignColors.InkFaint)
    }
}

/** "English", "English · as lyrics" (MP3), or "Off". */
@Composable
internal fun captionSummary(state: DownloadOptionsState): String {
    val name = state.options?.captions?.firstOrNull { it.key == state.caption }?.displayName()
        ?: return stringResource(Res.string.download_captions_off)
    return if (state.format == DownloadFormat.MP3) stringResource(Res.string.download_captions_as_lyrics, name) else name
}

/** One pickable entry of an options dropdown. */
internal class Choice(val label: String, val selected: Boolean, val pick: () -> Unit)

/** The chosen audio track's name — "Default" when the video has a single track. */
@Composable
internal fun audioTrackLabel(state: DownloadOptionsState): String =
    state.options?.audioTracks?.firstOrNull { it.id == state.audioTrackId }?.name
        ?: stringResource(Res.string.download_default_track)

@Composable
internal fun audioTrackChoices(state: DownloadOptionsState): List<Choice> {
    val tracks = state.options?.audioTracks.orEmpty()
    if (tracks.isEmpty()) return listOf(Choice(stringResource(Res.string.download_default_track), selected = true) {})
    return tracks.map { track -> Choice(track.name, track.id == state.audioTrackId) { state.audioTrackId = track.id } }
}

internal fun containerChoices(state: DownloadOptionsState): List<Choice> =
    state.containers.map { container ->
        Choice(container.displayName(), state.format == container) { state.container = container }
    }

@Composable
internal fun captionChoices(state: DownloadOptionsState): List<Choice> =
    listOf(Choice(stringResource(Res.string.download_captions_off), state.caption == null) { state.caption = null }) +
        state.options?.captions.orEmpty().map { track -> Choice(track.displayName(), track.key == state.caption) { state.caption = track.key } }

/** The track's name, marked when it is one of YouTube's auto-translations. */
@Composable
internal fun CaptionOption.displayName(): String =
    if (isTranslated) stringResource(Res.string.download_caption_translated, name) else name

/** The menu items for [choices], the selected one checked. */
@Composable
internal fun ChoiceMenuItems(choices: List<Choice>, close: () -> Unit) {
    choices.forEach { choice ->
        DropdownMenuItem(
            text = { Text(choice.label, color = DesignColors.Ink) },
            trailingIcon = if (choice.selected) {
                { Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = DesignColors.Accent) }
            } else {
                null
            },
            onClick = {
                choice.pick()
                close()
            },
        )
    }
}

@Composable
internal fun SaveAsQuickCheckbox(state: DownloadOptionsState) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = state.saveAsQuick, role = Role.Checkbox) { state.saveAsQuick = it },
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.saveAsQuick, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = DesignColors.Accent))
        Text(stringResource(Res.string.download_save_quick), style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
    }
}

@Composable
private fun DownloadButton(state: DownloadOptionsState, onDownload: () -> Unit) {
    val bytes = state.selectedBytes
    val what = if (state.format.kind == io.ymusic.download.DownloadKind.VIDEO) state.selectedVideo?.label() else state.format.displayName()
    Button(
        onClick = onDownload,
        enabled = state.options != null && !state.isLive,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = downloadPrimary, contentColor = Color.White),
    ) {
        Text(
            if (bytes != null && what != null) {
                stringResource(Res.string.download_action_detail, what, formatDownloadSize(bytes))
            } else {
                stringResource(Res.string.download_action)
            },
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** The file-name editor: a dialog of its own so the long name gets the full width to edit in. */
@Composable
internal fun RenameDialog(state: DownloadOptionsState, onDone: () -> Unit) {
    FileNameDialog(state.fileName, state.extension, onDismiss = onDone) { stem ->
        state.fileName = stem
        onDone()
    }
}

/** Edits a file's stem ([extension] is shown, not editable) under the download file-name policy; [onSave]
 *  gets the finalized stem. [error] shows under the field; [busy] locks the dialog while a save runs. */
@Composable
internal fun FileNameDialog(
    initial: String,
    extension: String,
    error: String? = null,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var stripped by remember { mutableStateOf(false) }
    val blank = DownloadFileNames.finalize(draft).isEmpty()
    val canSave = !blank && !busy
    val dismiss = { if (!busy) onDismiss() }
    val save = { if (canSave) onSave(DownloadFileNames.finalize(draft)) }
    ElevatedBarDialog(
        title = stringResource(Res.string.download_rename),
        onDismiss = dismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = !busy, onClick = dismiss)
            TextButton(enabled = canSave, onClick = save) {
                Text(stringResource(Res.string.download_save), color = if (canSave) DesignColors.Accent else DesignColors.InkFaint)
            }
        },
    ) {
        AppTextField(
            value = draft,
            onValueChange = { raw ->
                val clean = DownloadFileNames.stripForbidden(raw)
                stripped = clean != raw
                draft = clean
            },
            label = stringResource(Res.string.download_file_name),
            autoFocus = true,
            isError = blank || stripped || error != null,
            supportingText = when {
                error != null -> error
                blank -> stringResource(Res.string.download_name_empty)
                else -> stringResource(Res.string.download_forbidden_hint, DownloadFileNames.FORBIDDEN_DISPLAY)
            },
            suffix = extension,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}
