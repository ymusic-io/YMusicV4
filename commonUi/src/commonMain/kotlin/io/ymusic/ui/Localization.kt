package io.ymusic.ui

import androidx.compose.runtime.Composable
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.ensureUiLanguage
import io.ymusic.settings.flow
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.startup_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** A message that is final text or a string resource still to resolve — read both in composition and
 *  off it (a notification). */
internal sealed interface UiText {
    class Plain(val text: String) : UiText
    class Resource(val res: StringResource, vararg val args: Any) : UiText
}

@Composable
internal fun UiText.resolve(): String = when (this) {
    is UiText.Plain -> text
    is UiText.Resource -> stringResource(res, *args)
}

internal suspend fun UiText.load(): String = when (this) {
    is UiText.Plain -> text
    is UiText.Resource -> getString(res, *args)
}

/**
 * A language the in-app picker offers. [nativeName] is the language's own name for itself — it must
 * read correctly whatever locale the UI currently shows, so it is not a translated resource.
 */
class UiLanguage(val tag: String, val nativeName: String)

/**
 * The locales that actually have a `composeResources/values-*` translation, plus the base. [UiLanguage.tag]
 * doubles as YouTube's hl, so it uses YouTube's codes: Hebrew is `iw` (resolves to values-he or values-iw
 * — both ship, as JVM/iOS report `he` and Android `iw`), Indonesian `id` (values-id / values-in alike),
 * Chinese `zh-CN` / `zh-TW`.
 */
val UiLanguages: List<UiLanguage> = listOf(
    UiLanguage("en", "English"),
    UiLanguage("af", "Afrikaans"),
    UiLanguage("az", "Azərbaycan"),
    UiLanguage("id", "Bahasa Indonesia"),
    UiLanguage("ms", "Bahasa Melayu"),
    UiLanguage("ca", "Català"),
    UiLanguage("cs", "Čeština"),
    UiLanguage("de", "Deutsch"),
    UiLanguage("et", "Eesti"),
    UiLanguage("es", "Español"),
    UiLanguage("eu", "Euskara"),
    UiLanguage("fil", "Filipino"),
    UiLanguage("fr", "Français"),
    UiLanguage("hr", "Hrvatski"),
    UiLanguage("sw", "Kiswahili"),
    UiLanguage("hu", "Magyar"),
    UiLanguage("nl", "Nederlands"),
    UiLanguage("pl", "Polski"),
    UiLanguage("pt", "Português"),
    UiLanguage("ro", "Română"),
    UiLanguage("sk", "Slovenčina"),
    UiLanguage("so", "Soomaali"),
    UiLanguage("it", "Italiano"),
    UiLanguage("vi", "Tiếng Việt"),
    UiLanguage("tr", "Türkçe"),
    UiLanguage("el", "Ελληνικά"),
    UiLanguage("bg", "Български"),
    UiLanguage("mk", "Македонски"),
    UiLanguage("ru", "Русский"),
    UiLanguage("sr", "Српски"),
    UiLanguage("uk", "Українська"),
    UiLanguage("hy", "Հայերեն"),
    UiLanguage("iw", "עברית"),
    UiLanguage("ar", "العربية"),
    UiLanguage("fa", "فارسی"),
    UiLanguage("ne", "नेपाली"),
    UiLanguage("mr", "मराठी"),
    UiLanguage("hi", "हिन्दी"),
    UiLanguage("bn", "বাংলা"),
    UiLanguage("gu", "ગુજરાતી"),
    UiLanguage("ta", "தமிழ்"),
    UiLanguage("kn", "ಕನ್ನಡ"),
    UiLanguage("ml", "മലയാളം"),
    UiLanguage("si", "සිංහල"),
    UiLanguage("th", "ไทย"),
    UiLanguage("ko", "한국어"),
    UiLanguage("ja", "日本語"),
    UiLanguage("zh-CN", "简体中文"),
    UiLanguage("zh-TW", "繁體中文"),
)

/**
 * Points the platform's default locale at [tag] (null = restore the system locale), which is where
 * compose-resources resolves `stringResource` from. The caller must rebuild the composition (a
 * `key(tag)` around the app root) — the default locale is not snapshot state.
 */
internal expect fun applyUiLocale(tag: String?)

/** [epochMs] as a wall-clock time of day in the current locale's short style ("23:45" / "11:45 PM"). */
internal expect fun formatTimeOfDay(epochMs: Long): String

/** The device's own UI language subtag ("vi"), unaffected by [applyUiLocale] overrides. */
internal expect fun systemLanguageTag(): String

/** The device's full UI locale as a BCP-47 tag ("zh-Hant-TW"), unaffected by [applyUiLocale] overrides. */
internal expect fun systemLocaleTag(): String

/**
 * The app language for an unseeded install: the device language when we ship its translation, else
 * English (the base). Resolved and PERSISTED once on first launch — the app language is a concrete
 * tag from then on (it doubles as the content language hl), and later device-language changes are
 * deliberately ignored.
 */
internal fun defaultUiLanguageTag(): String = uiLanguageFor(systemLocaleTag())

/** The app language in [settings]; the shell seeds it on first launch, so blank only happens in unseeded test graphs. */
internal fun uiLanguageTag(settings: SettingsStore): String = settings.flow(AppSettings.uiLanguage).value.ifBlank { defaultUiLanguageTag() }

/** Maps a device locale tag onto a [UiLanguages] tag: legacy/modern code pairs fold together and
 *  Chinese splits by script/region (Hant or TW/HK/MO → Traditional). */
internal fun uiLanguageFor(localeTag: String): String {
    val parts = localeTag.replace('_', '-').split('-').filter { it.isNotEmpty() }
    val language = when (val primary = parts.firstOrNull()?.lowercase().orEmpty()) {
        "he" -> "iw"
        "in" -> "id"
        "tl" -> "fil"
        "zh" -> {
            val traditional = parts.drop(1).any { it.equals("Hant", ignoreCase = true) || it.uppercase() in TraditionalChineseRegions }
            if (traditional) "zh-TW" else "zh-CN"
        }
        else -> primary
    }
    return UiLanguages.firstOrNull { it.tag == language }?.tag ?: "en"
}

private val TraditionalChineseRegions = setOf("TW", "HK", "MO")

/**
 * Seed the app language if unset (see [defaultUiLanguageTag]) and return the concrete tag. Shells
 * call this BEFORE starting the engine, so the settings key is the single source of truth every
 * reader sees non-blank — the UI and the engine's own settings pull (its hl default) alike.
 */
suspend fun ensureAppLanguage(settings: SettingsStore): String =
    settings.ensureUiLanguage(::defaultUiLanguageTag)

/** Right-to-left scripts by primary subtag — the fixed ISO set, no platform lookup needed. */
private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur", "ug", "ps", "sd", "yi", "dv", "ckb")

internal fun isRtlLanguage(languageTag: String): Boolean =
    languageTag.substringBefore('-').substringBefore('_').lowercase() in RTL_LANGUAGES

/** The shells' boot-failure line, shown when the app graph could not be built. */
@Composable
fun startupFailedMessage(detail: String): String = stringResource(Res.string.startup_failed, detail)
