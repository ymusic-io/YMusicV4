package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtLocaleOption
import io.ymusic.dynamicengine.yt.YtLocales
import io.ymusic.dynamicengine.yt.applyYtLocale
import io.ymusic.dynamicengine.yt.ytLocaleOptions
import io.ymusic.settings.AppSettings
import io.ymusic.settings.BoolSetting
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.settings_audio_focus
import io.ymusic.ui.resources.settings_audio_focus_apps
import io.ymusic.ui.resources.settings_audio_focus_calls
import io.ymusic.ui.resources.settings_auto_update
import io.ymusic.ui.resources.settings_auto_update_hint
import io.ymusic.ui.resources.settings_changelog
import io.ymusic.ui.resources.settings_content_locale_hint
import io.ymusic.ui.resources.settings_content_region
import io.ymusic.ui.resources.settings_crash_reports
import io.ymusic.ui.resources.settings_crash_reports_hint
import io.ymusic.ui.resources.settings_debug
import io.ymusic.ui.resources.settings_enable_pip
import io.ymusic.ui.resources.settings_enable_pip_hint
import io.ymusic.ui.resources.settings_eq_hint
import io.ymusic.ui.resources.settings_language
import io.ymusic.ui.resources.settings_licenses
import io.ymusic.ui.resources.settings_licenses_intro
import io.ymusic.ui.resources.settings_section_about
import io.ymusic.ui.resources.settings_section_appearance
import io.ymusic.ui.resources.settings_section_content
import io.ymusic.ui.resources.settings_section_devices
import io.ymusic.ui.resources.settings_section_downloads
import io.ymusic.ui.resources.settings_section_playback
import io.ymusic.ui.resources.settings_section_privacy
import io.ymusic.ui.resources.settings_show_fps
import io.ymusic.ui.resources.settings_show_fps_hint
import io.ymusic.ui.resources.settings_source_code
import io.ymusic.ui.resources.settings_speed_hint
import io.ymusic.ui.resources.settings_sponsor_block
import io.ymusic.ui.resources.settings_sponsor_block_hint
import io.ymusic.ui.resources.settings_stats
import io.ymusic.ui.resources.settings_stats_hint
import io.ymusic.ui.resources.settings_terms
import io.ymusic.ui.resources.settings_theme
import io.ymusic.ui.resources.settings_theme_amoled
import io.ymusic.ui.resources.settings_theme_dark
import io.ymusic.ui.resources.settings_theme_light
import io.ymusic.ui.resources.settings_theme_system
import io.ymusic.ui.resources.settings_versions
import io.ymusic.ui.resources.settings_versions_app
import io.ymusic.ui.resources.settings_versions_engine
import io.ymusic.ui.resources.settings_versions_player
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sound_speed
import kmpcommon.log.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.runSuspendCatching
import projectcommon.log.captureUnlessNetwork

@Composable
fun SettingsScreen(graph: AppGraph, signedIn: Boolean = false, onLogin: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val uiLanguage by remember(graph) { graph.settings.flow(AppSettings.uiLanguage) }.collectAsState()
    val theme by remember(graph) { graph.settings.flow(AppSettings.theme) }.collectAsState()
    val accent by remember(graph) { graph.settings.flow(AppSettings.themeAccent) }.collectAsState()
    // The content locale LIVES in the engine; this screen has exactly two jobs: ask the engine for
    // the region menu + the current pick (one call, when the screen opens and again after a pick),
    // and report picks with applyYtLocale. There is ONE language: the app language doubles as the
    // content language (hl, resolved from the picker's supported list), so only the region has its
    // own YouTube-served picker. Until the reply lands (or if it fails) the region row still shows
    // Automatic; it just can't offer alternatives yet.
    var options by remember { mutableStateOf(YtLocales()) }
    var loadingOptions by remember { mutableStateOf(true) }
    var localeRevision by remember { mutableStateOf(0) }
    LaunchedEffect(graph.engines, localeRevision) {
        loadingOptions = true
        runSuspendCatching { graph.engines.ytLocaleOptions() }
            .onSuccess { options = it }
            .onFailure { graph.logger?.fork("settings")?.log(Logger.Level.WARN, "locale options fetch failed", it) }
        loadingOptions = false
    }
    suspend fun applyLocale(gl: String?) {
        runSuspendCatching { graph.engines.applyYtLocale(gl) }
            .onFailure { graph.logger?.fork("settings")?.captureUnlessNetwork(it, "applyYtLocale failed — UI reloads as if it changed") }
        localeRevision++ // re-ask: the labels and the automatic answer follow the new locale
        graph.localeEpoch.value++ // locale-shaped caches (Discover feeds) refetch
    }
    fun pickLocale(gl: String) {
        scope.launch { applyLocale(gl) }
    }

    // Report scroll to the shell so the app bar lifts once the content scrolls under it (every other
    // scrolling screen does this via TrackScroll; without it the bar never elevates).
    val scrollState = rememberScrollState()
    TrackScroll { scrollState.canScrollBackward }
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = DesignSpacing.SettingsSectionGap + LocalMiniPlayerInset.current)) {
        SettingsSection(Res.string.settings_section_appearance) {
            ThemeRow(
                current = theme,
                onSelect = { value -> scope.launch { graph.settings.put(AppSettings.theme, value) } },
            )
            AccentRow(
                current = accentColorOf(accent),
                onPick = { color ->
                    val hex = accentHexOf(color).takeIf { it != accentHexOf(DefaultAccent) }.orEmpty()
                    scope.launch { graph.settings.put(AppSettings.themeAccent, hex) }
                },
            )
            LanguageRow(
                current = uiLanguage.ifBlank { defaultUiLanguageTag() },
                onSelect = { tag ->
                    scope.launch {
                        // The put rebuilds the app under key(tag), cancelling this screen's scope —
                        // the engine notify must outlive it.
                        withContext(NonCancellable) {
                            // Persist FIRST: apply is only the notify — the engine re-pulls this setting.
                            graph.settings.put(AppSettings.uiLanguage, tag)
                            applyLocale(gl = null)
                        }
                    }
                },
            )
        }
        SettingsSection(Res.string.settings_section_content) {
            ContentLocaleRow(
                title = stringResource(Res.string.settings_content_region),
                subtitle = stringResource(Res.string.settings_content_locale_hint),
                current = options.gl,
                options = options.regions,
                loading = loadingOptions,
                onSelect = { code -> pickLocale(gl = code) },
            )
        }
        SettingsSection(Res.string.settings_section_playback) {
            SoundRow(graph, speed = true)
            SoundRow(graph, speed = false)
            CaptionStyleRow(graph)
            ToggleRow(graph, AppSettings.enablePip, Res.string.settings_enable_pip, Res.string.settings_enable_pip_hint)
            if (graph.audioFocusSetting) AudioFocusRow(graph)
            ToggleRow(
                graph,
                AppSettings.sponsorBlockEnabled,
                Res.string.settings_sponsor_block,
                Res.string.settings_sponsor_block_hint,
            )
            PlayerCacheRow(graph)
        }
        if (graph.downloads != null) {
            SettingsSection(Res.string.settings_section_downloads) { DownloadSettingsRows(graph) }
        }
        if (graph.exportSessionCookies != null && graph.scanQrCode != null &&
            graph.confirmDeviceOwner != null && graph.api != null
        ) {
            SettingsSection(Res.string.settings_section_devices) {
                LinkDevicesRow(graph, signedIn = signedIn, onLogin = onLogin)
            }
        }
        // Both telemetry consents are LIVE: the shells mirror them into the send-gates, so nothing
        // leaves the app while a switch is off, and no restart is needed either way.
        SettingsSection(Res.string.settings_section_privacy) {
            ToggleRow(graph, AppSettings.crashReports, Res.string.settings_crash_reports, Res.string.settings_crash_reports_hint)
            ToggleRow(graph, AppSettings.stats, Res.string.settings_stats, Res.string.settings_stats_hint)
        }
        SettingsSection(Res.string.settings_section_about) {
            VersionsRow(graph)
            // Download updates in the background (on by default); installing still waits for a tap.
            if (graph.updates != null) {
                ToggleRow(graph, AppSettings.autoDownloadUpdates, Res.string.settings_auto_update, Res.string.settings_auto_update_hint)
            }
            val config by remember(graph) { graph.remoteConfig ?: emptyFlow() }.collectAsState(initial = null)
            config?.features?.changelogUrl?.let { LinkRow(Res.string.settings_changelog, it) }
            val links = AppLinks(config)
            LinkRow(Res.string.settings_source_code, links.sourceCode)
            LinkRow(Res.string.settings_terms, links.terms)
            LicensesRow()
        }
        SettingsSection(Res.string.settings_debug) {
            ToggleRow(graph, AppSettings.showFps, Res.string.settings_show_fps, Res.string.settings_show_fps_hint)
        }
    }
}

@Composable
private fun SoundRow(graph: AppGraph, speed: Boolean) {
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
                stringResource(if (speed) Res.string.sound_speed else Res.string.sound_eq),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                stringResource(if (speed) Res.string.settings_speed_hint else Res.string.settings_eq_hint),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
    }
    if (open) {
        if (speed) PlaybackFxSheet(graph.player, onDismiss = { open = false })
        else EqualizerFxSheet(graph, onDismiss = { open = false })
    }
}

/** A boolean setting as a switch row: tap anywhere to flip, persisted immediately. */
@Composable
internal fun ToggleRow(graph: AppGraph, setting: BoolSetting, title: StringResource, hint: StringResource) {
    val scope = rememberCoroutineScope()
    val enabled by remember(graph, setting) { graph.settings.flow(setting) }.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch) { scope.launch { graph.settings.put(setting, it) } }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(title),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                stringResource(hint),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
        CompactSwitch(enabled)
    }
}

private val AudioFocusOptions: List<Pair<String, StringResource>> = listOf(
    AppSettings.AUDIO_FOCUS_APPS to Res.string.settings_audio_focus_apps,
    AppSettings.AUDIO_FOCUS_CALLS to Res.string.settings_audio_focus_calls,
)

@Composable
private fun AudioFocusRow(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val current by remember(graph) { graph.settings.flow(AppSettings.audioFocus) }.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        ) {
            Text(
                stringResource(Res.string.settings_audio_focus),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                stringResource((AudioFocusOptions.firstOrNull { it.first == current } ?: AudioFocusOptions.first()).second),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AudioFocusOptions.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        scope.launch { graph.settings.put(AppSettings.audioFocus, value) }
                    },
                )
            }
        }
    }
}

@Composable
private fun LinkRow(label: StringResource, url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        stringResource(label),
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
    )
}

/** Informational only: the app build and the JS bundle revisions in effect ("—" until a bundle has run). */
@Composable
private fun VersionsRow(graph: AppGraph) {
    val engine by remember(graph) { graph.runningBundles?.engineFlow ?: flowOf(null) }.collectAsState(initial = null)
    val player by remember(graph) { graph.runningBundles?.playerFlow ?: flowOf(null) }.collectAsState(initial = null)
    val parts = listOfNotNull(
        graph.appVersionName?.let { name ->
            stringResource(Res.string.settings_versions_app, name, graph.appVersionCode?.toString() ?: "—")
        },
        stringResource(Res.string.settings_versions_engine, engine?.toString() ?: "—"),
        stringResource(Res.string.settings_versions_player, player?.toString() ?: "—"),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
    ) {
        Text(
            stringResource(Res.string.settings_versions),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
        )
        Text(
            parts.joinToString(" · "),
            style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = DesignSpacing.Space2),
        )
    }
}

/** Settings → Open source licenses: the shipped third-party components ([OssComponents]) in the
 *  ElevatedBarDialog idiom (see [LocalePickerDialog]). */
@Composable
private fun LicensesRow() {
    var open by remember { mutableStateOf(false) }
    Text(
        stringResource(Res.string.settings_licenses),
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
    )
    if (open) {
        val listState = rememberLazyListState()
        val uriHandler = LocalUriHandler.current
        ElevatedBarDialog(
            title = stringResource(Res.string.settings_licenses),
            onDismiss = { open = false },
            canScrollBackward = listState.canScrollBackward,
            canScrollForward = listState.canScrollForward,
            buttons = { DialogSecondaryButton(stringResource(Res.string.dialog_cancel)) { open = false } },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                item("intro") {
                    Text(
                        stringResource(Res.string.settings_licenses_intro),
                        style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                items(OssComponents, key = { it.name }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(c.url) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.name,
                                style = TextStyle(fontSize = 13.sp, color = DesignColors.Ink),
                            )
                            c.scope?.let {
                                Text(
                                    it,
                                    style = TextStyle(fontSize = 11.sp, color = DesignColors.InkMuted),
                                    modifier = Modifier.padding(top = DesignSpacing.Space2),
                                )
                            }
                        }
                        Text(
                            c.license,
                            style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The content-region picker. [current] is the WORKING region — YouTube's own answer, whether
 * IP-derived or following a pick, so there is no separate "automatic" entry: the row always shows
 * a concrete region and selecting one is a plain write. The menu runs to ~100 entries, so picking
 * happens in a dialog, not a dropdown.
 */
@Composable
private fun ContentLocaleRow(
    title: String,
    subtitle: String,
    current: String,
    options: List<YtLocaleOption>,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun labelOf(code: String) = options.firstOrNull { it.code.equals(code, ignoreCase = true) }?.label ?: code
    // Title + caption are one row: the whole two-line block is the hover/click target, and the value
    // sits vertically centred against it (Row-level CenterVertically).
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
                title,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                subtitle,
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
        Text(
            labelOf(current),
            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
        )
    }
    if (open) {
        LocalePickerDialog(
            title = title,
            entries = options,
            current = current,
            loading = loading,
            onSelect = { code -> onSelect(code); open = false },
            onDismiss = { open = false },
        )
    }
}

/**
 * A scrollable single-choice dialog, opened on the row that shows the current choice. While the
 * menus are still being fetched and there is nothing to offer beyond Automatic, a spinner says so
 * (a stale-but-usable list from the previous locale is shown instead of the spinner). Custom
 * layout, not [AlertDialog]'s slots (those inset the content into a floating panel): the list is
 * full-bleed on [DesignPalette.Surface], and the fixed title/button bars sit on
 * [DesignPalette.SurfaceElevated] behind a divider, casting a shadow onto the list while content
 * continues past their edge — the ElevatedBarColumn idiom.
 */
@Composable
private fun LocalePickerDialog(
    title: String,
    entries: List<YtLocaleOption>,
    current: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    fun isCurrent(code: String) = code.equals(current, ignoreCase = true)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = entries.indexOfFirst { isCurrent(it.code) }.coerceAtLeast(0),
    )
    ElevatedBarDialog(
        title = title,
        onDismiss = onDismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        buttons = { DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onDismiss) },
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(entries, key = { it.code }) { option ->
                val selected = isCurrent(option.code)
                Text(
                    option.label,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) DesignColors.Accent else DesignColors.Ink,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.code) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            if (loading && entries.size <= 1) {
                item("loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = DesignColors.Accent)
                    }
                }
            }
        }
    }
}

/** M3's stock 52×32 Switch reads oversized against the 14sp row text — scaled to row weight.
 *  scale() shrinks the visual only (the layout slot stays M3's), which is fine in a text-tall row. */
@Composable
internal fun CompactSwitch(checked: Boolean) {
    Switch(checked = checked, onCheckedChange = null, modifier = Modifier.scale(0.8f))
}

/** "" = follow system, per [AppSettings.theme]. */
private val ThemeOptions: List<Pair<String, StringResource>> = listOf(
    "" to Res.string.settings_theme_system,
    "light" to Res.string.settings_theme_light,
    "dark" to Res.string.settings_theme_dark,
    "amoled" to Res.string.settings_theme_amoled,
)

@Composable
private fun ThemeRow(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.settings_theme),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            modifier = Modifier.weight(1f),
        )
        // The menu anchors to the value text, so it drops on the right under the current choice —
        // anchored to the row it would open at the far-away left edge of the screen.
        Box {
            Text(
                stringResource((ThemeOptions.firstOrNull { it.first == current } ?: ThemeOptions.first()).second),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ThemeOptions.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onSelect(value); open = false })
                }
            }
        }
    }
}

/** ONE language, always a concrete pick (the first launch seeded it) — no "system" entry. */
@Composable
private fun LanguageRow(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.settings_language),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            modifier = Modifier.weight(1f),
        )
        Text(
            UiLanguages.firstOrNull { it.tag == current }?.nativeName ?: current,
            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
        )
    }
    if (open) {
        val entries = remember { UiLanguages.map { YtLocaleOption(it.tag, it.nativeName) } }
        LocalePickerDialog(
            title = stringResource(Res.string.settings_language),
            entries = entries,
            current = current,
            loading = false,
            onSelect = { tag -> onSelect(tag); open = false },
            onDismiss = { open = false },
        )
    }
}
