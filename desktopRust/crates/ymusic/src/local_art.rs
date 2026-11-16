//! Library artwork. A local item's art is a `local-art/…` image path that [`AppAssets`] resolves on
//! gpui's background executor into a small JPEG thumbnail, cached on disk per file version.
//!
//! [`AppAssets`]: crate::assets::AppAssets

use std::borrow::Cow;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use anyhow::{Context as _, anyhow, bail};
use gpui_kit::SharedString;
use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, RgbaImage};
use ym_common::log::Logger;
use ym_engine::yt::YtThumb;
use ym_image_loader::{
    DEFAULT_MAX_IMAGE_BYTES, DecodedImage, ImageFetcher, ImageLoadError, ImageScale, ImageSourceCache, Reason,
    decode_scaled_file, source_cache_key,
};
use ym_store::MediaItem;

const PREFIX: &str = "local-art/";
const JPEG_QUALITY: u8 = 85;

/// Longest side, in device pixels, of a list row's thumbnail.
pub const ROW_PX: u32 = 128;
/// Longest side of a grid card's art.
pub const CARD_PX: u32 = 400;
/// Longest side of the Now Playing artwork.
pub const PLAYER_PX: u32 = 800;

/// The item's artwork at `px`: its embedded cover, else its YouTube video's thumbnail, else a
/// decoded frame of its video; `None` for a file with none of those.
pub fn art_url(item: &MediaItem, px: u32) -> Option<SharedString> {
    if let Some(stream) = item.thumbnail_stream_index {
        Some(local_path("cover", stream, px, item))
    } else if let Some(video_id) = &item.youtube_vid {
        Some(format!("https://i.ytimg.com/vi/{video_id}/mqdefault.jpg").into())
    } else if item.video_codec.is_some() {
        Some(local_path("frame", 0, px, item))
    } else {
        None
    }
}

/// The item's artwork as queue thumbnails: a row size and a player size.
pub fn queue_thumbs(item: &MediaItem) -> Vec<YtThumb> {
    [ROW_PX, PLAYER_PX]
        .into_iter()
        .filter_map(|px| art_url(item, px).map(|url| YtThumb { url: url.to_string(), w: Some(px as i32), h: None }))
        .collect()
}

fn local_path(kind: &str, stream: i32, px: u32, item: &MediaItem) -> SharedString {
    format!("{PREFIX}{kind}/{stream}/{px}/{}/{}/{}", item.file_size, item.last_modified, item.uri).into()
}

/// The JVM app's form of an artwork URL, for rows it may read back: a local file's art becomes
/// `art://file?kind=<cover|frame>&uri=<file URI>`; anything else is kept.
pub fn to_jvm_art(url: &str) -> String {
    let Some(rest) = url.strip_prefix(PREFIX) else { return url.to_owned() };
    let mut parts = rest.splitn(6, '/');
    let (Some(kind), Some(file)) = (parts.next(), parts.nth(4)) else { return url.to_owned() };
    let file_uri = ym_common::file_uri::to_file_uri(file);
    format!("art://file?kind={}&uri={}", query_value(kind), query_value(&file_uri))
}

/// This app's form of the JVM app's `art://` URLs (`pic/<media id>`, `yt/<video id>`,
/// `file?kind=&uri=`); `None` when the file is gone or has no art. Other URLs are kept. Blocking: a
/// Library lookup, and a probe for a file outside it.
pub fn from_jvm_art(url: &str, media: &ym_store::MediaStore, px: u32) -> Option<String> {
    let Some(rest) = url.strip_prefix("art://") else { return Some(url.to_owned()) };
    let (place, query) = rest.split_once('?').unwrap_or((rest, ""));
    let item = match place.split_once('/').unwrap_or((place, "")) {
        ("pic", id) => media.item(id.parse().ok()?).ok()??,
        ("yt", video_id) => return Some(format!("https://i.ytimg.com/vi/{video_id}/mqdefault.jpg")),
        ("file", _) => {
            let file_uri = query
                .split('&')
                .filter_map(|pair| pair.split_once('='))
                .find(|(name, _)| *name == "uri")
                .map(|(_, value)| ym_common::file_uri::percent_decode(value))?;
            let path = ym_common::file_uri::file_uri_path(&file_uri)?;
            match media.find_item_by_uri(&path).ok()? {
                Some((item, false)) => item,
                _ => ym_store::describe_file(Path::new(&path)).ok()?.item,
            }
        }
        _ => return None,
    };
    art_url(&item, px).map(String::from)
}

/// A query value as the JVM app's `UriBuilder.appendQueryParam` encodes it.
fn query_value(value: &str) -> String {
    let mut encoded = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~!$'()*+,;=:@/?".contains(&byte) {
            encoded.push(byte as char);
        } else {
            encoded.push_str(&format!("%{byte:02X}"));
        }
    }
    encoded
}

enum Kind {
    Cover(i32),
    Frame,
}

struct ArtKey<'a> {
    kind: Kind,
    px: u32,
    file_size: &'a str,
    modified: &'a str,
    file: &'a Path,
}

fn parse(path: &str) -> anyhow::Result<ArtKey<'_>> {
    let rest = path.strip_prefix(PREFIX).ok_or_else(|| anyhow!("not a local art path"))?;
    let mut parts = rest.splitn(6, '/');
    let mut next = || parts.next().ok_or_else(|| anyhow!("malformed local art path"));
    let (kind, stream, px) = (next()?, next()?, next()?);
    let (file_size, modified, file) = (next()?, next()?, next()?);
    let stream: i32 = stream.parse().context("local art stream index")?;
    let kind = match kind {
        "cover" => Kind::Cover(stream),
        "frame" => Kind::Frame,
        other => bail!("unknown local art kind {other:?}"),
    };
    Ok(ArtKey { kind, px: px.parse().context("local art size")?, file_size, modified, file: Path::new(file) })
}

/// The JVM `mediaStoreArtFetcher` for this app's `local-art/` paths: the embedded cover or a decoded
/// frame, kept in the JVM app's `image-cover-cache` keyed as there (kind, file URI, size, mtime: a
/// changed file extracts again), so every view size decodes from that file instead of extracting again.
pub struct LocalArtFetcher {
    pub cover_cache: Arc<ImageSourceCache>,
}

impl ImageFetcher for LocalArtFetcher {
    fn fetch(
        &self,
        url: &str,
        size: (u32, u32),
        scale: ImageScale,
    ) -> Option<futures::future::BoxFuture<'static, Result<DecodedImage, ImageLoadError>>> {
        if !LocalArt::handles(url) {
            return None;
        }
        let (cache, url) = (self.cover_cache.clone(), url.to_owned());
        Some(Box::pin(async move {
            let label = url.clone();
            tokio::task::spawn_blocking(move || extract_cached(&cache, &url, size, scale)).await.map_err(|error| {
                ImageLoadError::with_cause(Reason::Undecodable, format!("local art task failed: {label}"), error)
            })?
        }))
    }
}

/// Blocking: the cover cache's file for `url`, extracted on a miss, decoded to `size`.
fn extract_cached(
    cache: &ImageSourceCache,
    url: &str,
    size: (u32, u32),
    scale: ImageScale,
) -> Result<DecodedImage, ImageLoadError> {
    let key = parse(url)
        .map_err(|error| ImageLoadError::new(Reason::Unsupported, format!("malformed art url: {url}: {error:#}")))?;
    let kind = match key.kind {
        Kind::Cover(_) => "cover",
        Kind::Frame => "frame",
    };
    let file_uri = ym_store::file_uri(&key.file.to_string_lossy());
    let cache_key = source_cache_key(&format!("{kind}:{file_uri}:{}:{}", key.file_size, key.modified));
    let cached = cache.file_path(&cache_key).map_err(|error| {
        ImageLoadError::with_cause(Reason::Undecodable, format!("cover cache unreadable: {url}"), error)
    })?;
    let file = match cached {
        Some(file) => file,
        None => {
            if !key.file.exists() {
                return Err(ImageLoadError::new(Reason::NotFound, format!("no such file: {url}")));
            }
            let encoded = extract_encoded(&key).map_err(|error| {
                ImageLoadError::new(Reason::Undecodable, format!("local art extraction failed: {url}: {error:#}"))
            })?;
            cache.write_blocking(&cache_key, std::io::Cursor::new(encoded), DEFAULT_MAX_IMAGE_BYTES, url)?
        }
    };
    decode_scaled_file(&file, size, scale, url)
}

/// The JVM extractors' output: the embedded picture as stored (`-c copy`), a frame as a full-size JPEG.
/// Whole in memory: `ym_attached_pic` and `ym_video_frame` hand back one buffer.
fn extract_encoded(key: &ArtKey) -> anyhow::Result<Vec<u8>> {
    match key.kind {
        Kind::Cover(stream) => {
            ym_mse::attached_picture(key.file, stream).map_err(|code| anyhow!("attached picture: libav error {code}"))
        }
        Kind::Frame => {
            let frame = ym_mse::video_frame(key.file, 0).map_err(|code| anyhow!("video frame: libav error {code}"))?;
            let rgba = RgbaImage::from_raw(frame.width, frame.height, frame.rgba)
                .ok_or_else(|| anyhow!("video frame buffer does not match its size"))?;
            let mut jpeg = Cursor::new(Vec::new());
            DynamicImage::ImageRgba8(rgba)
                .to_rgb8()
                .write_with_encoder(JpegEncoder::new_with_quality(&mut jpeg, JPEG_QUALITY))
                .context("encoding the video frame")?;
            Ok(jpeg.into_inner())
        }
    }
}

/// The app's [`LocalArt`], for code that needs a thumbnail's file rather than an image element.
pub struct SharedLocalArt(pub std::sync::Arc<LocalArt>);

impl gpui_kit::Global for SharedLocalArt {}

pub struct LocalArt {
    cache_dir: PathBuf,
    log: Logger,
}

impl LocalArt {
    pub fn new(cache_dir: PathBuf, log: Logger) -> Self {
        Self { cache_dir, log }
    }

    pub fn handles(path: &str) -> bool {
        path.starts_with(PREFIX)
    }

    /// Blocking: the thumbnail's JPEG bytes, extracted on a cache miss.
    pub fn load(&self, path: &str) -> anyhow::Result<Cow<'static, [u8]>> {
        let result = self.load_or_extract(path);
        if let Err(error) = &result {
            self.log.warn("local artwork failed", Some(error.as_ref()));
        }
        result
    }

    /// The thumbnail's cache file when it is already extracted.
    pub fn cached_file(&self, path: &str) -> Option<PathBuf> {
        Self::handles(path).then(|| self.cache_file(path)).filter(|file| file.is_file())
    }

    fn cache_file(&self, path: &str) -> PathBuf {
        let mut hasher = DefaultHasher::new();
        path.hash(&mut hasher);
        self.cache_dir.join(format!("{:016x}.jpg", hasher.finish()))
    }

    fn load_or_extract(&self, path: &str) -> anyhow::Result<Cow<'static, [u8]>> {
        let key = parse(path)?;
        let file = self.cache_file(path);
        match std::fs::read(&file) {
            Ok(bytes) => return Ok(bytes.into()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error).context("reading cached artwork"),
        }
        let jpeg = extract(&key)?;
        std::fs::create_dir_all(&self.cache_dir).context("creating the artwork cache")?;
        let partial = file.with_extension("part");
        std::fs::write(&partial, &jpeg).context("writing cached artwork")?;
        std::fs::rename(&partial, &file).context("committing cached artwork")?;
        Ok(jpeg.into())
    }
}

fn extract(key: &ArtKey) -> anyhow::Result<Vec<u8>> {
    let image = match key.kind {
        Kind::Cover(stream) => {
            let bytes = ym_mse::attached_picture(key.file, stream)
                .map_err(|code| anyhow!("attached picture: libav error {code}"))?;
            let image = image::load_from_memory(&bytes).context("decoding the attached picture")?;
            if image.width() > key.px || image.height() > key.px { image.thumbnail(key.px, key.px) } else { image }
        }
        Kind::Frame => {
            let frame =
                ym_mse::video_frame(key.file, key.px).map_err(|code| anyhow!("video frame: libav error {code}"))?;
            let rgba = RgbaImage::from_raw(frame.width, frame.height, frame.rgba)
                .ok_or_else(|| anyhow!("video frame buffer does not match its size"))?;
            DynamicImage::ImageRgba8(rgba)
        }
    };
    let mut jpeg = Cursor::new(Vec::new());
    image
        .to_rgb8()
        .write_with_encoder(JpegEncoder::new_with_quality(&mut jpeg, JPEG_QUALITY))
        .context("encoding the artwork thumbnail")?;
    Ok(jpeg.into_inner())
}
