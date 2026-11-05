package io.ymusic.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.playlist_download_all
import io.ymusic.ui.resources.playlist_redownload_message
import io.ymusic.ui.resources.playlist_skip_downloaded
import io.ymusic.ui.resources.redownload_confirm
import io.ymusic.ui.resources.redownload_message
import io.ymusic.ui.resources.redownload_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Asks before downloading a video the Library already holds; a confirmed re-download is saved as a
 *  new file (`name_1.ext`), never over the old one. */
@Composable
internal fun RedownloadDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(stringResource(Res.string.redownload_message, title), onDismiss) {
        TextButton(onClick = onConfirm) { Text(stringResource(Res.string.redownload_confirm), color = DesignColors.Accent) }
    }
}

/** The playlist form: [count] of the picked videos are already downloaded — skip them or take all. */
@Composable
internal fun PlaylistRedownloadDialog(count: Int, onSkip: () -> Unit, onAll: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(pluralStringResource(Res.plurals.playlist_redownload_message, count, count), onDismiss) {
        TextButton(onClick = onAll) { Text(stringResource(Res.string.playlist_download_all), color = DesignColors.InkSoft) }
        TextButton(onClick = onSkip) { Text(stringResource(Res.string.playlist_skip_downloaded), color = DesignColors.Accent) }
    }
}

@Composable
private fun ConfirmDialog(message: String, onDismiss: () -> Unit, actions: @Composable () -> Unit) {
    ElevatedBarDialog(
        title = stringResource(Res.string.redownload_title),
        onDismiss = onDismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onDismiss)
            actions()
        },
    ) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
            style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink, lineHeight = 20.sp),
        )
    }
}
