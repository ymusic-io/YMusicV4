//! The JVM `ImageRequest.kt`: what to show at what size, its variants, and the classified failure.

use std::fmt;
use std::sync::Arc;

/// What to show and at what size. `key` is the image's identity: every cache layer is keyed by it,
/// never by a url, so one picture published at several sizes or under rotating signed urls is one
/// entry family. `sources` are the variants it can be fetched from; with more than one, the loader
/// picks the smallest that covers `size` and falls back across the rest.
#[derive(Clone, Debug, PartialEq)]
pub struct ImageRequest {
    pub key: String,
    pub sources: Vec<ImageSource>,
    /// Device pixels: (width, height).
    pub size: (u32, u32),
    pub scale: ImageScale,
}

impl ImageRequest {
    /// `None` without sources (the JVM constructor's `require`).
    pub fn new(key: impl Into<String>, sources: Vec<ImageSource>, size: (u32, u32), scale: ImageScale) -> Option<Self> {
        if sources.is_empty() {
            return None;
        }
        Some(Self { key: key.into(), sources, size, scale })
    }

    /// A single-url image; its identity is [`image_cache_key`] of the url.
    pub fn of(url: &str, width: u32, height: u32, scale: ImageScale) -> Self {
        Self { key: image_cache_key(url), sources: vec![ImageSource::new(url)], size: (width, height), scale }
    }
}

/// One fetchable variant. `width`/`height` are the declared pixel size (0 when unknown), used only to
/// choose a variant; declared sizes can be wrong. `crop` runs on the decoded image, so every variant
/// of one identity shows the same picture.
#[derive(Clone, Debug, PartialEq)]
pub struct ImageSource {
    pub url: String,
    pub width: u32,
    pub height: u32,
    pub crop: SourceCrop,
}

impl ImageSource {
    pub fn new(url: impl Into<String>) -> Self {
        Self { url: url.into(), width: 0, height: 0, crop: SourceCrop::None }
    }

    pub fn sized(url: impl Into<String>, width: u32, height: u32) -> Self {
        Self { url: url.into(), width, height, crop: SourceCrop::None }
    }

    pub fn cropped(url: impl Into<String>, width: u32, height: u32, crop: SourceCrop) -> Self {
        Self { url: url.into(), width, height, crop }
    }
}

/// How the decode relates to the requested box: `Cover` fills it (for cropped views), `Fit` fits inside.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum ImageScale {
    #[default]
    Cover,
    Fit,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum SourceCrop {
    #[default]
    None,
    /// The middle 16:9 band of a 4:3 frame: the picture inside YouTube's letterboxed hqdefault/sddefault.
    Letterbox16x9,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reason {
    /// The source says the image does not exist (HTTP 404/410, a file or embedded picture that is absent).
    NotFound,
    /// Transport failure or a server error: worth retrying later.
    Network,
    /// Bytes arrived but are not a decodable image (or exceed the size cap).
    Undecodable,
    /// No fetcher handles the url's scheme.
    Unsupported,
}

/// The JVM `ImageLoadException`: a failed load, classified so callers can tell a missing image from
/// a transient failure. Never shown to the user; it goes to the log.
#[derive(Clone, Debug)]
pub struct ImageLoadError {
    pub reason: Reason,
    pub message: String,
    pub cause: Option<Arc<dyn std::error::Error + Send + Sync>>,
    /// The other sources' failures when every source failed.
    pub suppressed: Vec<ImageLoadError>,
}

impl ImageLoadError {
    pub fn new(reason: Reason, message: impl Into<String>) -> Self {
        Self { reason, message: message.into(), cause: None, suppressed: Vec::new() }
    }

    pub fn with_cause(
        reason: Reason,
        message: impl Into<String>,
        cause: impl std::error::Error + Send + Sync + 'static,
    ) -> Self {
        Self { reason, message: message.into(), cause: Some(Arc::new(cause)), suppressed: Vec::new() }
    }
}

impl fmt::Display for ImageLoadError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{:?}: {}", self.reason, self.message)?;
        for other in &self.suppressed {
            write!(formatter, "; {other}")?;
        }
        Ok(())
    }
}

impl std::error::Error for ImageLoadError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        self.cause.as_deref().map(|cause| cause as &(dyn std::error::Error + 'static))
    }
}

/// ytimg's `rs` is a per-response signature over the other params; it changes, the image doesn't.
const ROTATING_QUERY_PARAMS: [&str; 1] = ["rs"];

/// A stable cache identity for an image `url`: the url minus its rotating signature params. Only those
/// go: other params select the image (ytimg's `sqp` encodes the size and crop).
pub fn image_cache_key(url: &str) -> String {
    let Some((base, query)) = url.split_once('?') else { return url.to_owned() };
    if query.is_empty() {
        return url.to_owned();
    }
    let kept: Vec<&str> = query
        .split('&')
        .filter(|param| !ROTATING_QUERY_PARAMS.contains(&param.split_once('=').map_or(*param, |(name, _)| name)))
        .collect();
    if kept.is_empty() { base.to_owned() } else { format!("{base}?{}", kept.join("&")) }
}
