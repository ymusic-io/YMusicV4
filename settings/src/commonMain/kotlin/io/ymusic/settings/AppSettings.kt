package io.ymusic.settings

import kotlinx.coroutines.flow.StateFlow

data class StringSetting(val key: String, val default: String)

fun SettingsStore.flow(setting: StringSetting): StateFlow<String> = string(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: StringSetting,
    value: String,
) = putString(setting.key, value)

/**
 * A multi-value string setting: a set of values kept in one key, newline-joined (values themselves
 * must not contain '\n' — BCP-47 codes, uris, etc. don't). The decoded view preserves insertion
 * order and drops blanks; an empty key reads as the empty set.
 */
data class StringSetSetting(val key: String, val default: Set<String> = emptySet())

private const val SET_DELIMITER = "\n"

fun SettingsStore.flow(setting: StringSetSetting): StateFlow<Set<String>> =
    MappedStateFlow(string(setting.key, "")) { decodeStringSet(it) }

suspend fun SettingsStore.put(
    setting: StringSetSetting,
    values: Set<String>,
) = putString(setting.key, values.joinToString(SET_DELIMITER))

internal fun decodeStringSet(raw: String): Set<String> =
    raw.split(SET_DELIMITER).filter { it.isNotBlank() }.toCollection(LinkedHashSet())

data class BoolSetting(val key: String, val default: Boolean)

fun SettingsStore.flow(setting: BoolSetting): StateFlow<Boolean> = boolean(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: BoolSetting,
    value: Boolean,
) = putBoolean(setting.key, value)

data class IntSetting(val key: String, val default: Int)

fun SettingsStore.flow(setting: IntSetting): StateFlow<Int> = int(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: IntSetting,
    value: Int,
) = putInt(setting.key, value)

/**
 * The app language, seeded on first ask: a blank read resolves [default], PERSISTS it, and returns
 * it — from then on the key is never blank and is the single source of truth for every reader (the
 * UI and the engine's settings pull alike). Shells call this once before starting the engine.
 */
suspend fun SettingsStore.ensureUiLanguage(default: () -> String): String {
    val held = string(AppSettings.uiLanguage.key, "").value
    if (held.isNotBlank()) return held
    val resolved = default()
    putString(AppSettings.uiLanguage.key, resolved)
    return resolved
}

object AppSettings {
    // The CONTENT locale (YouTube hl/gl) is deliberately absent: it lives in the engine
    // (yt/locale/apply + the engine's own ladder), not in app settings.

    /** The accent color as `RRGGBB`; empty = the default orange. */
    val themeAccent = StringSetting("theme.accent", "")

    /**
     * Whether the media SESSION metadata carries artwork — what the lock screen / system media
     * controls show (the app's own notification always carries the art). The v4 form of legacy's
     * albumArtOnLockScreen ALBUM_ART/ALBUM_ART2 trick.
     */

    /** The Quick download preset, JSON-encoded by the UI; empty = the UI's default preset. */
    val quickDownloadPreset = StringSetting("download.quickPreset", "")

    /** Player cache size limit in MB; 0 = no cache. The cache also leaves the disk's last 100 MB free. */
    val playerCacheMaxMb = IntSetting("playerCache.maxMb", 2048)

    /** UI language override (BCP-47); empty = follow the system locale. */
    val uiLanguage = StringSetting("app.uiLanguage", "")

    /** The main section (Discover / Library / YtMusic) the app reopens on: an AppSection id, 0 = none yet. */
    val lastSection = IntSetting("app.lastSection", 0)

    /** The Discover tab the app reopens on: a DiscoverTab id, 0 = none yet. */
    val lastDiscoverTab = IntSetting("app.lastDiscoverTab", 0)

    /** A Library list's sort choice, `"<option index>,<1 = descending>"`; blank = the list's default. */
    fun librarySort(id: String) = StringSetting("library.sort.$id", "")

    /**
     * Fetch updates in the background as soon as they are found, so the update prompt opens ready
     * to install. On by default; the install itself always waits for the user's tap. Android and
     * desktop (iOS has no updater).
     */
    val autoDownloadUpdates = BoolSetting("update.autoDownload", true)

    /** The versionCode of the last launch (0 = none recorded), for the "updated" notice. */
    val lastRunVersionCode = IntSetting("update.lastRunVersionCode", 0)

    /**
     * The sha256 of the release whose automatic download failed. That release is never fetched
     * automatically again — the user downloads it from the update dialog — while a corrected one
     * (a new hash) is.
     */
    val updateAutoDownloadFailed = StringSetting("update.autoDownloadFailed", "")

    /** Anonymous stable device id — a random UUID minted on first launch by [anonymousDeviceId],
     *  never derived from hardware identity. Empty = unseeded. */
    val deviceId = StringSetting("telemetry.deviceId", "")

    /** The engine.js / player.js revision last loaded on this device (0 = never): reported before the bundle boots. */
    val lastLoadedEngineJsVer = IntSetting("telemetry.lastLoadedEngineJsVer", 0)
    val lastLoadedPlayerJsVer = IntSetting("telemetry.lastLoadedPlayerJsVer", 0)

    /**
     * Theme: "light" | "dark" | "amoled" (dark on pure black), or empty to follow the system appearance.
     *
     * Defaults to DARK rather than to the system: this is a music app that is mostly looked at
     * while something else has the foreground, and a white sheet is the wrong default for that.
     * Following the system is still one of the choices, just not the unset one.
     */
    val theme = StringSetting("app.theme", "dark")

    /**
     * Whether anonymous crash reports may be sent. Default OFF — reporting must never start before
     * the user said yes in Settings.
     */
    val crashReports = BoolSetting("telemetry.crashReports", true)

    /**
     * Whether anonymous usage stats (app opens and the like) may be sent. Default OFF, same
     * contract as [crashReports]: nothing is ever sent before the user said yes.
     */
    val stats = BoolSetting("telemetry.stats", true)

    /**
     * Enter picture-in-picture when the OS asks (Home / app switch) while Now Playing or
     * fullscreen video is up. Off: going to background drops to AudioOnly until the app returns.
     */
    val enablePip = BoolSetting("playback.enablePip", true)

    /**
     * Android: publish the audio session to equalizer apps (AudioEffect control-session broadcasts)
     * and bypass the in-app equalizer. Ignored on shells that don't offer it.
     */
    val systemAudioEffects = BoolSetting("playback.systemAudioEffects", true)

    /**
     * Android: when other audio pauses playback — [AUDIO_FOCUS_APPS] (default) follows audio focus
     * and pauses whenever another app plays, [AUDIO_FOCUS_CALLS] pauses only for phone calls.
     */
    val audioFocus = StringSetting("playback.audioFocus", AUDIO_FOCUS_APPS)
    const val AUDIO_FOCUS_CALLS = "calls"
    const val AUDIO_FOCUS_APPS = "apps"

    /** The sleep-timer dialog's last chosen duration, its starting value next time. */
    val sleepTimerMinutes = IntSetting("playback.sleepTimerMinutes", 30)

    /**
     * Where finished downloads are written — a media-store bucket uri (the folder is also a Library
     * bucket, since downloads land inside the local library). Empty = not chosen yet: the download
     * flow asks the user to pick a folder before the first download.
     */
    val downloadLocation = StringSetting("download.location", "")

    /** Encode MP3 downloads at the source stream's own bitrate. Off = 320 kbps. */
    val downloadMp3SourceBitrate = BoolSetting("download.mp3SourceBitrate", false)

    /**
     * Skip SponsorBlock segments on YouTube items. Default on; a per-video whitelist still
     * excludes individual videos.
     */
    val sponsorBlockEnabled = BoolSetting("playback.sponsorBlock", true)

    /**
     * Overlay pictures-per-second on the Now Playing / fullscreen video hole.
     * Off by default. Sampling runs only while that hole is composed, the window is focused,
     * and playWhenReady is true.
     */
    val showFps = BoolSetting("debug.showFps", false)
}
