//! The caption language codes last picked, most recent first: one list shared by Now Playing's
//! caption menu and the download pickers (every pick but Off), the JVM app's `captions.recentTranslateLanguages`.

use gpui_kit::App;

/// Shared with the JVM app: one code per line, most recent first.
const RECENT_CAPTIONS_KEY: &str = "captions.recentTranslateLanguages";
/// Generous, as a local file's codes ("eng", "und", a stream index) are recorded too and must not push real languages out.
const RECENT_CAPTION_CODES: usize = 20;

pub fn recent_caption_languages(cx: &App) -> Vec<String> {
    let graph = cx.global::<crate::graph::AppGraph>();
    let raw = graph.settings.get_raw(RECENT_CAPTIONS_KEY).ok().flatten().unwrap_or_default();
    let mut recent: Vec<String> = Vec::new();
    for code in raw.split('\n').filter(|code| !code.trim().is_empty()) {
        if !recent.iter().any(|known| known == code) {
            recent.push(code.to_owned());
        }
    }
    recent
}

/// Puts `code` first, once, keeping the newest [`RECENT_CAPTION_CODES`]; a blank code is not recorded.
pub fn record_caption_language(code: String, cx: &mut App) {
    if code.trim().is_empty() {
        return;
    }
    let mut recent = recent_caption_languages(cx);
    recent.retain(|known| *known != code);
    recent.insert(0, code);
    recent.truncate(RECENT_CAPTION_CODES);
    crate::shell::persist_setting(cx, RECENT_CAPTIONS_KEY, recent.join("\n"));
}

/// The indices of `entries` in the `recent` languages, in that order, each once; a code no entry
/// matches is skipped. An entry matches a code when its language is the code, the code's base
/// language ("en-GB" → "en"), or, for a bare code, has it as base. Languages with different scripts or regions ("zh-Hans", "zh-CN") never match.
pub fn in_recent_languages<T>(entries: &[T], recent: &[String], language_of: impl Fn(&T) -> &str) -> Vec<usize> {
    let mut found: Vec<usize> = Vec::new();
    for code in recent {
        let base = code.split('-').next().unwrap_or(code);
        let position = entries
            .iter()
            .position(|entry| language_of(entry).eq_ignore_ascii_case(code))
            .or_else(|| entries.iter().position(|entry| language_of(entry).eq_ignore_ascii_case(base)))
            .or_else(|| {
                (code == base)
                    .then(|| {
                        entries.iter().position(|entry| {
                            language_of(entry).split('-').next().is_some_and(|other| other.eq_ignore_ascii_case(base))
                        })
                    })
                    .flatten()
            });
        if let Some(position) = position
            && !found.contains(&position)
        {
            found.push(position);
        }
    }
    found
}
