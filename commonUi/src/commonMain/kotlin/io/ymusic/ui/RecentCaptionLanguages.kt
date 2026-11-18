package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put

/** Generous, as a local file's codes ("eng", "und", a stream index) are recorded too and must not push real languages out. */
private const val RECENT_CAPTION_CODES = 20

/**
 * The caption language codes last picked, most recent first ([AppSettings.recentTranslateLanguages]): one list
 * shared by Now Playing's caption menu and the download pickers; every caption pick but Off records its code.
 */
@Composable
internal fun rememberRecentCaptionLanguages(settings: SettingsStore): List<String> {
    val recent by remember(settings) { settings.flow(AppSettings.recentTranslateLanguages) }.collectAsStateWithLifecycle()
    return recent.toList()
}

internal suspend fun SettingsStore.recordRecentCaptionLanguage(code: String) {
    if (code.isBlank()) return
    val recent = (listOf(code) + flow(AppSettings.recentTranslateLanguages).value).distinct().take(RECENT_CAPTION_CODES)
    put(AppSettings.recentTranslateLanguages, recent.toSet())
}

/**
 * The entries in the [recent] languages, in that order, each once; a code no entry matches is skipped. An
 * entry matches a code when its language is the code, the code's base language ("en-GB" → "en"), or, for a bare code, has it as base.
 * Languages with different scripts or regions ("zh-Hans", "zh-CN") never match each other.
 */
internal fun <T> List<T>.inRecentCaptionLanguages(recent: List<String>, languageOf: (T) -> String): List<T> =
    recent.mapNotNull { code ->
        val base = code.substringBefore('-')
        firstOrNull { languageOf(it).equals(code, ignoreCase = true) }
            ?: firstOrNull { languageOf(it).equals(base, ignoreCase = true) }
            ?: firstOrNull { code == base && languageOf(it).substringBefore('-').equals(base, ignoreCase = true) }
    }.distinct()
