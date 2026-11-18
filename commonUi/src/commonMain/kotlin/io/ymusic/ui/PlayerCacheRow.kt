package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_size_gb
import io.ymusic.ui.resources.download_size_mb
import io.ymusic.ui.resources.settings_player_cache
import io.ymusic.ui.resources.settings_player_cache_off
import io.ymusic.ui.resources.settings_player_cache_summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

private val PlayerCacheSizesMb = listOf(0, 128, 256, 512, 1_024, 2_048, 5_120, 10_240)

/** Settings → Player cache: the size limit, with how much is used and how much cache hits saved. */
@Composable
internal fun PlayerCacheRow(graph: AppGraph) {
    val cache = graph.playerCache ?: return
    val scope = rememberCoroutineScope()
    val maxMb by remember(graph) { graph.settings.flow(AppSettings.playerCacheMaxMb) }.collectAsStateWithLifecycle()
    val saved by remember(cache) { cache.stats?.hitBytes ?: MutableStateFlow(0L) }.collectAsStateWithLifecycle()
    var used by remember { mutableStateOf(0L) }
    LaunchedEffect(cache, maxMb, saved) { used = withContext(Dispatchers.IO) { cache.blobs.totalSize() } }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.settings_player_cache),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                if (maxMb > 0) {
                    stringResource(
                        Res.string.settings_player_cache_summary,
                        cacheSizeLabel(maxMb * 1024L * 1024L),
                        cacheSizeLabel(used),
                        cacheSizeLabel(saved),
                    )
                } else {
                    stringResource(Res.string.settings_player_cache_off)
                },
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
        // Anchored to the value text — see ThemeRow.
        Box {
            Text(maxLabel(maxMb), style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                PlayerCacheSizesMb.forEach { mb ->
                    DropdownMenuItem(
                        text = { Text(maxLabel(mb)) },
                        onClick = {
                            open = false
                            scope.launch { graph.settings.put(AppSettings.playerCacheMaxMb, mb) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun maxLabel(mb: Int): String =
    if (mb > 0) cacheSizeLabel(mb * 1024L * 1024L) else stringResource(Res.string.settings_player_cache_off)

/** Binary units, like the size choices: "2 GB", "1.4 GB", "512 MB". */
@Composable
private fun cacheSizeLabel(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    if (mb < 1024) return stringResource(Res.string.download_size_mb, mb.roundToLong().toString())
    val tenths = (mb / 1024 * 10).roundToLong()
    val gb = if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    return stringResource(Res.string.download_size_gb, gb)
}
