package io.ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.download.AudioQuality
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.dynamicengine.yt.YtLocaleOption
import io.ymusic.dynamicengine.yt.ytLocaleOptions
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_audio_language
import io.ymusic.ui.resources.download_audio_only
import io.ymusic.ui.resources.download_captions
import io.ymusic.ui.resources.download_captions_off
import io.ymusic.ui.resources.download_default_track
import io.ymusic.ui.resources.download_format
import io.ymusic.ui.resources.download_quality
import io.ymusic.ui.resources.download_video
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

private val PRESET_HEIGHTS = listOf(2160, 1440, 1080, 720, 480, 360)

/** Format / audio language / captions pickers for a [QuickDownloadPreset]; captions need ffmpeg to embed. */
@Composable
internal fun QuickPresetFields(
    preset: QuickDownloadPreset,
    languages: List<YtLocaleOption>,
    ffmpegAvailable: Boolean,
    onChange: (QuickDownloadPreset) -> Unit,
) {
    val audioFormats = if (ffmpegAvailable) listOf(DownloadFormat.AUDIO_WEBM, DownloadFormat.M4A, DownloadFormat.MP3) else listOf(DownloadFormat.M4A)
    val videoFormats = if (ffmpegAvailable) listOf(DownloadFormat.VIDEO_MP4, DownloadFormat.VIDEO_WEBM) else emptyList()
    OptionLine(stringResource(Res.string.download_format), presetLabel(preset)) { close ->
        MenuGroupLabel(stringResource(Res.string.download_audio_only))
        ChoiceMenuItems(
            audioFormats.map { f -> Choice(f.displayName(), f == preset.format) { onChange(preset.copy(format = f)) } },
            close,
        )
        videoFormats.forEach { container ->
            MenuGroupLabel("${stringResource(Res.string.download_video)} · ${container.displayName()}")
            ChoiceMenuItems(
                PRESET_HEIGHTS.map { h ->
                    Choice(heightLabel(h), container == preset.format && h == preset.height) {
                        onChange(preset.copy(format = container, height = h))
                    }
                },
                close,
            )
        }
    }
    if (preset.format.kind == DownloadKind.AUDIO) {
        OptionLine(stringResource(Res.string.download_quality), preset.audioQuality.label(preset.format)) { close ->
            ChoiceMenuItems(
                AudioQuality.entries.map { quality ->
                    Choice(quality.label(preset.format), quality == preset.audioQuality) { onChange(preset.copy(audioQuality = quality)) }
                },
                close,
            )
        }
    }
    val default = stringResource(Res.string.download_default_track)
    OptionLine(stringResource(Res.string.download_audio_language), languageName(preset.audioLanguage, languages) ?: default) { close ->
        ChoiceMenuItems(
            listOf(Choice(default, preset.audioLanguage == null) { onChange(preset.copy(audioLanguage = null)) }) +
                languages.map { lang -> Choice(lang.label, lang.code == preset.audioLanguage) { onChange(preset.copy(audioLanguage = lang.code)) } },
            close,
        )
    }
    if (ffmpegAvailable) {
        val off = stringResource(Res.string.download_captions_off)
        OptionLine(stringResource(Res.string.download_captions), languageName(preset.captionLanguage, languages) ?: off) { close ->
            ChoiceMenuItems(
                listOf(Choice(off, preset.captionLanguage == null) { onChange(preset.copy(captionLanguage = null)) }) +
                    languages.map { lang -> Choice(lang.label, lang.code == preset.captionLanguage) { onChange(preset.copy(captionLanguage = lang.code)) } },
                close,
            )
        }
    }
}

@Composable
internal fun OptionLine(title: String, value: String, menu: @Composable (close: () -> Unit) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        Text(title, Modifier.width(112.dp), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink))
        Box(Modifier.weight(1f)) { DropdownField(value) { close -> menu(close) } }
    }
}

/** YouTube's own language menu (labelled in the app language); empty until loaded or if it fails. */
@Composable
internal fun rememberYtLanguages(graph: AppGraph): List<YtLocaleOption> {
    val languages by produceState(emptyList<YtLocaleOption>(), graph) {
        value = try {
            graph.engines.ytLocaleOptions(graph.logger).languages
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            graph.logger?.fork("QuickPreset")?.log(Logger.Level.WARN, "language list failed; offering Default / Off only", e)
            emptyList()
        }
    }
    return languages
}

internal fun languageName(code: String?, languages: List<YtLocaleOption>): String? =
    code?.let { c -> languages.firstOrNull { it.code == c }?.label ?: c }

/** "M4A · Audio only", "MP4 · 1080p". */
@Composable
internal fun presetLabel(preset: QuickDownloadPreset): String =
    if (preset.format.kind == DownloadKind.AUDIO) {
        "${preset.format.displayName()} · ${stringResource(Res.string.download_audio_only)}"
    } else {
        "${preset.format.displayName()} · ${heightLabel(preset.height ?: 1080)}"
    }

private fun heightLabel(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    else -> "${height}p"
}
