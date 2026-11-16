//! The JVM `YoutubeThumbnails.kt` + `Thumbnails.kt`: image identities and sources for art urls and
//! engine thumbnail lists, for the image loader.

use std::sync::LazyLock;

use regex::Regex;
use ym_engine::yt::YtThumb;
use ym_image_loader::{ImageRequest, ImageScale, ImageSource, SourceCrop, image_cache_key};

/// The JVM `NOW_PLAYING_ART_PX`: Now Playing's art floor, in device pixels.
pub const NOW_PLAYING_ART_PX: u32 = 640;

const GOOGLE_ART_SIZES: [u32; 4] = [120, 240, 480, 720];

static GOOGLE_SIZED_ART: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^(https?://[^/]+\.googleusercontent\.com/[^=]+=w)(\d+)(.*?-h)(\d+)(.*)$").unwrap());
static YTIMG_VIDEO: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^https?://i\d*\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/").unwrap());

/// One image identity per video, shared by feed thumbs, the queue, Now Playing and local art.
pub fn youtube_image_key(video_id: &str) -> String {
    format!("yt:{video_id}")
}

/// The video id of an `i.ytimg.com/vi/<id>/…` (or `vi_webp`) thumbnail url.
pub fn youtube_video_id_of(url: &str) -> Option<&str> {
    YTIMG_VIDEO.captures(url).and_then(|found| found.get(1)).map(|found| found.as_str())
}

/// The JVM `youtubeThumbUrl`: mqdefault (320×180), the one variant every video has, with no rotating
/// query params.
pub fn youtube_thumb_url(video_id: &str) -> String {
    format!("https://i.ytimg.com/vi/{video_id}/mqdefault.jpg")
}

/// The thumbnails every video has, by size: mqdefault (320×180, 16:9), hqdefault (480×360) and
/// sddefault (640×480), the last two 4:3 frames with the picture letterboxed, trimmed to 16:9.
/// sddefault isn't generated for every upload; the loader's variant index remembers which miss.
pub fn youtube_thumbnail_sources(video_id: &str) -> Vec<ImageSource> {
    vec![
        ImageSource::sized(youtube_thumb_url(video_id), 320, 180),
        ImageSource::cropped(
            format!("https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"),
            480,
            360,
            SourceCrop::Letterbox16x9,
        ),
        ImageSource::cropped(
            format!("https://i.ytimg.com/vi/{video_id}/sddefault.jpg"),
            640,
            480,
            SourceCrop::Letterbox16x9,
        ),
    ]
}

/// Identity + sources for a single art url: a YouTube video thumbnail becomes its video's identity
/// with the standard sizes; Google art its sizes; anything else is the url alone.
pub fn art_request_sources(url: &str) -> (String, Vec<ImageSource>) {
    let url = absolute_url(url);
    if let Some(found) = google_art_sources(&url) {
        return found;
    }
    match youtube_video_id_of(&url) {
        Some(video_id) => (youtube_image_key(video_id), youtube_thumbnail_sources(video_id)),
        None => (image_cache_key(&url), vec![ImageSource::new(url)]),
    }
}

/// A single art url as an [`ImageRequest`] for a `width`×`height` box, with the variants
/// [`art_request_sources`] derives: for surfaces that are not an `AsyncImage`.
pub fn art_image_request(url: &str, width: u32, height: u32) -> ImageRequest {
    let (key, sources) = art_request_sources(url);
    ImageRequest { key, sources, size: (width, height), scale: ImageScale::Cover }
}

/// Identity + sources for an engine thumbnail list: a video's list joins its video identity (with the
/// standard sizes added), each entry keeping its declared size; other lists (avatars, playlist art)
/// are keyed by their largest url.
pub fn thumb_request_sources(thumbs: &[YtThumb]) -> Option<(String, Vec<ImageSource>)> {
    let last = thumbs.last()?;
    let listed: Vec<ImageSource> = thumbs
        .iter()
        .map(|thumb| {
            let declared = |side: Option<i32>| side.map_or(0, |side| side.max(0) as u32);
            ImageSource::sized(absolute_url(&thumb.url), declared(thumb.w), declared(thumb.h))
        })
        .collect();
    let largest = absolute_url(largest_thumb_url(thumbs).unwrap_or(&last.url));
    if let Some((key, mut sized)) = google_art_sources(&largest) {
        for source in listed {
            if !sized.iter().any(|known| known.url == source.url) {
                sized.push(source);
            }
        }
        return Some((key, sized));
    }
    match thumbs.iter().find_map(|thumb| youtube_video_id_of(&thumb.url)) {
        Some(video_id) => {
            let mut sources = youtube_thumbnail_sources(video_id);
            sources.extend(listed);
            Some((youtube_image_key(video_id), sources))
        }
        None => Some((image_cache_key(&largest), listed)),
    }
}

/// Identity + sizes for Google image-server art (`…googleusercontent.com/…=wN-hN…`, YT Music): the
/// server scales the same image to any `=wN-hN` (capped at the original), so one url offers every view
/// its fit.
fn google_art_sources(url: &str) -> Option<(String, Vec<ImageSource>)> {
    let found = GOOGLE_SIZED_ART.captures(url)?;
    let part = |index: usize| found.get(index).map_or("", |part| part.as_str());
    let width: u32 = part(2).parse().ok().filter(|width| *width > 0)?;
    let height: u32 = part(4).parse().ok().filter(|height| *height > 0)?;
    let sources = GOOGLE_ART_SIZES
        .iter()
        .map(|size| {
            let sized_height = (u64::from(*size) * u64::from(height) / u64::from(width)) as u32;
            ImageSource::sized(format!("{}{size}{}{sized_height}{}", part(1), part(3), part(5)), *size, sized_height)
        })
        .collect();
    Some((format!("{}{}{}", part(1), part(3), part(5)), sources))
}

/// The JVM `largestThumbUrl`: the largest variant by declared area when sizes are known, else the last
/// (source order ascends).
pub fn largest_thumb_url(thumbs: &[YtThumb]) -> Option<&str> {
    let area = |thumb: &YtThumb| i64::from(thumb.w.unwrap_or(0)) * i64::from(thumb.h.unwrap_or(0));
    thumbs
        .iter()
        .reduce(|best, thumb| if area(thumb) > area(best) { thumb } else { best })
        .filter(|thumb| thumb.w.unwrap_or(0) > 0)
        .or_else(|| thumbs.last())
        .map(|thumb| thumb.url.as_str())
}

/// Engine urls can be protocol-relative.
fn absolute_url(url: &str) -> String {
    if url.starts_with("//") { format!("https:{url}") } else { url.to_owned() }
}
