//! The JVM `YoutubeThumbnails.kt`: one art url's fetchable variants, and the JVM image loader's
//! `orderSources` choosing among them for a box.

use std::sync::LazyLock;

use gpui_kit::SharedString;
use regex::Regex;

/// The JVM `NOW_PLAYING_ART_PX`: Now Playing's art floor, in device pixels.
pub const NOW_PLAYING_ART_PX: u32 = 640;

const GOOGLE_ART_SIZES: [u32; 4] = [120, 240, 480, 720];

static GOOGLE_SIZED_ART: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^(https?://[^/]+\.googleusercontent\.com/[^=]+=w)(\d+)(.*?-h)(\d+)(.*)$").unwrap());
static YTIMG_VIDEO: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^https?://i\d*\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/").unwrap());

/// One fetchable variant; `width`/`height` are its declared size after the 16:9 letterbox trim,
/// 0 when unknown.
#[derive(Clone, Debug, PartialEq)]
pub struct ArtSource {
    pub url: SharedString,
    pub width: u32,
    pub height: u32,
}

/// The video id of an `i.ytimg.com/vi/<id>/…` (or `vi_webp`) thumbnail url.
pub fn youtube_video_id_of(url: &str) -> Option<&str> {
    YTIMG_VIDEO.captures(url).and_then(|found| found.get(1)).map(|found| found.as_str())
}

/// mqdefault (320×180), hqdefault (480×360) and sddefault (640×480), the last two letterboxed 4:3
/// frames of a 16:9 picture; sddefault is missing for some uploads.
fn youtube_thumbnail_sources(video_id: &str) -> Vec<ArtSource> {
    let source = |name: &str, width: u32, height: u32| ArtSource {
        url: format!("https://i.ytimg.com/vi/{video_id}/{name}.jpg").into(),
        width,
        height: height.min(width * 9 / 16),
    };
    vec![source("mqdefault", 320, 180), source("hqdefault", 480, 360), source("sddefault", 640, 480)]
}

/// Google image-server art (`…googleusercontent.com/…=wN-hN…`, YT Music) at the JVM sizes; the
/// server scales the same image to any `=wN-hN`.
fn google_art_sources(url: &str) -> Option<Vec<ArtSource>> {
    let found = GOOGLE_SIZED_ART.captures(url)?;
    let part = |index: usize| found.get(index).map_or("", |part| part.as_str());
    let width: u32 = part(2).parse().ok().filter(|width| *width > 0)?;
    let height: u32 = part(4).parse().ok().filter(|height| *height > 0)?;
    Some(
        GOOGLE_ART_SIZES
            .iter()
            .map(|size| {
                let sized_height = (u64::from(*size) * u64::from(height) / u64::from(width)) as u32;
                ArtSource {
                    url: format!("{}{size}{}{sized_height}{}", part(1), part(3), part(5)).into(),
                    width: *size,
                    height: sized_height,
                }
            })
            .collect(),
    )
}

/// The JVM `artRequestSources`: a YouTube video thumbnail stands for its video's standard sizes,
/// Google art for its sizes, anything else for the url alone.
pub fn art_request_sources(url: &str) -> Vec<ArtSource> {
    if let Some(sources) = google_art_sources(url) {
        return sources;
    }
    match youtube_video_id_of(url) {
        Some(video_id) => youtube_thumbnail_sources(video_id),
        None => vec![ArtSource { url: url.to_owned().into(), width: 0, height: 0 }],
    }
}

/// The JVM `orderSources` for a cover-fit `width`×`height` box, with nothing fetched yet: the
/// covering ones smallest first, then those of unknown size, then the smaller ones largest first.
pub fn order_sources(sources: Vec<ArtSource>, width: u32, height: u32) -> Vec<SharedString> {
    let (mut known, unknown): (Vec<_>, Vec<_>) =
        sources.into_iter().partition(|source| source.width > 0 && source.height > 0);
    let (mut covering, mut smaller): (Vec<_>, Vec<_>) =
        known.drain(..).partition(|source| source.width >= width && source.height >= height);
    covering.sort_by_key(|source| source.width);
    smaller.sort_by_key(|source| std::cmp::Reverse(source.width));
    covering.into_iter().chain(unknown).chain(smaller).map(|source| source.url).collect()
}
