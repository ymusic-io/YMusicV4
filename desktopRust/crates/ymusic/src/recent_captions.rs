//! The caption languages last picked, most recent first: one list shared by Now Playing's
//! auto-translations and the download pickers, the JVM app's `captions.recentTranslateLanguages`.

use gpui_kit::{App, Global};

/// Shared with the JVM app: one language code per line, most recent first.
const RECENT_CAPTIONS_KEY: &str = "captions.recentTranslateLanguages";
const RECENT_CAPTIONS: usize = 3;

/// The list as last recorded, ahead of the settings write that persists it.
struct RecentCaptionLanguages(Vec<String>);

impl Global for RecentCaptionLanguages {}

pub fn recent_caption_languages(cx: &App) -> Vec<String> {
    if let Some(recent) = cx.try_global::<RecentCaptionLanguages>() {
        return recent.0.clone();
    }
    let graph = cx.global::<crate::graph::AppGraph>();
    let raw = graph.settings.get_raw(RECENT_CAPTIONS_KEY).ok().flatten().unwrap_or_default();
    raw.lines().filter(|code| !code.is_empty()).map(str::to_owned).collect()
}

/// Puts `code` first, once, keeping the newest few.
pub fn record_caption_language(code: String, cx: &mut App) {
    let mut recent = recent_caption_languages(cx);
    recent.retain(|known| *known != code);
    recent.insert(0, code);
    recent.truncate(RECENT_CAPTIONS);
    crate::shell::persist_setting(cx, RECENT_CAPTIONS_KEY, recent.join("\n"));
    cx.set_global(RecentCaptionLanguages(recent));
}

/// The indices of `entries` in the `recent` languages, in that order, each once: an entry matches a
/// code when its language is the code, the code's base language ("en-GB" → "en"), or, for a bare
/// code, has it as base. Languages with different scripts or regions ("zh-Hans", "zh-CN") never match.
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
