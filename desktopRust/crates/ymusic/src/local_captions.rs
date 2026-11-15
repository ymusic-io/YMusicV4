//! A local file's captions as the JVM `JvmLocalPlayer` reads them: SRT/VTT files beside it
//! (`LocalSidecarCaptions`) and its embedded text streams, picked by the preferred language.

use std::path::Path;
use std::sync::LazyLock;

use regex::Regex;
use ym_mse::Cue;

/// The JVM `PlaybackConfig.UNDETERMINED_LANGUAGE`: the preference before the user picks a track.
pub const UNDETERMINED_LANGUAGE: &str = "und";

/// A caption file beside the media (`song.srt`, `song.vie.srt`).
#[derive(Clone, Debug)]
pub struct SidecarTrack {
    /// `file:<lang>`.
    pub id: String,
    pub lang: String,
    pub label: String,
    pub cues: Vec<Cue>,
}

/// Blocking: the SRT/VTT files named after `media`, each with at least one cue.
pub fn find_sidecars(media: &Path) -> Vec<SidecarTrack> {
    let (Some(dir), Some(stem)) = (media.parent(), name_without_extension(media)) else { return Vec::new() };
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut tracks = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let extension = path.extension().map(|extension| extension.to_string_lossy().to_lowercase());
        if !matches!(extension.as_deref(), Some("srt" | "vtt")) {
            continue;
        }
        let Some(name) = name_without_extension(&path) else { continue };
        let lang = if name == stem {
            UNDETERMINED_LANGUAGE.to_owned()
        } else if let Some(rest) = name.strip_prefix(&format!("{stem}.")) {
            let lang = rest.split('.').next().unwrap_or_default();
            if lang.is_empty() { UNDETERMINED_LANGUAGE.to_owned() } else { lang.to_owned() }
        } else {
            continue;
        };
        let Ok(bytes) = std::fs::read(&path) else { continue };
        let cues = parse_timed_text(&String::from_utf8_lossy(&bytes));
        if cues.is_empty() {
            continue;
        }
        let label = if lang == UNDETERMINED_LANGUAGE {
            path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_else(|| lang.clone())
        } else {
            lang.clone()
        };
        tracks.push(SidecarTrack { id: format!("file:{lang}"), lang, label, cues });
    }
    tracks
}

/// The name up to its last dot, as Kotlin's `File.nameWithoutExtension`.
fn name_without_extension(path: &Path) -> Option<String> {
    let name = path.file_name()?.to_string_lossy().into_owned();
    Some(match name.rfind('.') {
        Some(dot) => name[..dot].to_owned(),
        None => name,
    })
}

static TIMESTAMP: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})")
        .unwrap()
});
static BLOCK_GAP: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\n\n+").unwrap());
static TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<[^>]+>").unwrap());

/// SRT or WebVTT cues, numbered from 1; styling tags stripped.
pub fn parse_timed_text(raw: &str) -> Vec<Cue> {
    let text = raw.replace("\r\n", "\n").replace('\r', "\n");
    let text = text.trim_start_matches('\u{FEFF}');
    let mut cues = Vec::new();
    for block in BLOCK_GAP.split(text) {
        let lines: Vec<&str> = block.trim().lines().collect();
        let Some(first) = lines.first() else { continue };
        if first.starts_with("WEBVTT") || first.starts_with("NOTE") {
            continue;
        }
        let Some(stamp_at) = lines.iter().position(|line| TIMESTAMP.is_match(line)) else { continue };
        let Some(stamp) = TIMESTAMP.captures(lines[stamp_at]) else { continue };
        let group = |index: usize| stamp.get(index).map_or("", |found| found.as_str());
        let start = seconds(group(1), group(2), group(3), group(4));
        let end = seconds(group(5), group(6), group(7), group(8));
        let body = lines[stamp_at + 1..].iter().map(|line| line.trim()).collect::<Vec<_>>().join("\n");
        let body = TAG.replace_all(&body, "").trim().to_owned();
        if body.is_empty() {
            continue;
        }
        cues.push(Cue { id: cues.len() as i32 + 1, start, end, text: body });
    }
    cues
}

fn seconds(hours: &str, minutes: &str, secs: &str, fraction: &str) -> f64 {
    let number = |value: &str| value.parse::<i64>().unwrap_or(0) as f64;
    let millis: String = format!("{fraction:0<3}").chars().take(3).collect();
    number(hours) * 3600.0 + number(minutes) * 60.0 + number(secs) + number(&millis) / 1000.0
}

/// The JVM `captionLangKey`: a track id or language reduced to the key tracks are matched by.
pub fn lang_key(code: &str) -> String {
    let code = code.strip_prefix("file:").unwrap_or(code);
    let code = code.strip_prefix("yt:").unwrap_or(code).to_lowercase();
    match code.as_str() {
        "vie" => "vi",
        "eng" => "en",
        "spa" => "es",
        "jpn" => "ja",
        "ger" | "deu" => "de",
        "fre" | "fra" => "fr",
        _ => return code,
    }
    .to_owned()
}

/// The JVM `captionLabel`: an embedded stream's language by name, or its position when untagged.
pub fn track_label(lang: &str, index: i32) -> String {
    let code = lang.trim();
    if code.is_empty() || code.eq_ignore_ascii_case(UNDETERMINED_LANGUAGE) {
        return format!("Caption {index}");
    }
    let tag = match code.to_lowercase().as_str() {
        "vie" => "vi".to_owned(),
        "eng" => "en".to_owned(),
        "spa" => "es".to_owned(),
        "jpn" => "ja".to_owned(),
        "kor" => "ko".to_owned(),
        "chi" | "zho" => "zh".to_owned(),
        "ger" | "deu" => "de".to_owned(),
        "fre" | "fra" => "fr".to_owned(),
        _ => code.to_owned(),
    };
    let name = crate::settings_view::language_name(&tag);
    let mut chars = name.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => code.to_owned(),
    }
}

/// The JVM `emitCaptionLines`: the cues on screen at `position` seconds, each as one block of lines.
pub fn active_lines(cues: &[Cue], position: f64) -> Vec<(i64, String)> {
    cues.iter()
        .filter(|cue| position + 0.05 >= cue.start && position < cue.end)
        .filter_map(|cue| {
            let rows: Vec<&str> = cue.text.split('\n').map(str::trim).filter(|row| !row.is_empty()).collect();
            (!rows.is_empty()).then(|| (i64::from(cue.id), rows.join("\n")))
        })
        .collect()
}
