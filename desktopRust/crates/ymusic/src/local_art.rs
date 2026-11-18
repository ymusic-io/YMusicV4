//! Library artwork as the JVM `LocalArt.kt` names and resolves it: `art://pic/<media id>` for a
//! Library item, `art://file?kind=…&uri=…` and `art://yt/<video id>` for a file outside it, resolved by
//! [`MediaStoreArtFetcher`] through the image loader.

use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};

use futures::future::BoxFuture;
use ym_ffmpeg::{FfmpegError, FfmpegMetadataGetter};
use ym_image_loader::async_image::LoadedImage;
use ym_image_loader::{
    Bitmap, DEFAULT_MAX_IMAGE_BYTES, DecodedImage, ImageFetcher, ImageLoadError, ImageLoader, ImageRequest, ImageScale,
    ImageSourceCache, Reason, decode_scaled, decode_scaled_file, source_cache_key,
};
use ym_store::{MediaItem, MediaStore};

use crate::youtube_thumbnails::{youtube_image_key, youtube_thumbnail_sources};

const ART_KIND_COVER: &str = "cover";
const ART_KIND_FRAME: &str = "frame";

/// The JVM `localArtUrl`: `art://pic/<media id>`, the one url every consumer carries for a Library
/// item, minted only when the store knows an artwork source (embedded cover, YouTube id, video).
pub fn local_art_url(item: &MediaItem) -> Option<String> {
    (item.thumbnail_stream_index.is_some() || item.video_codec.is_some() || item.youtube_vid.is_some())
        .then(|| format!("art://pic/{}", item.id))
}

/// The JVM `youtubeArtUrl`: a video's best thumbnail at the view size, for a file not in the store.
pub fn youtube_art_url(video_id: &str) -> String {
    format!("art://yt/{video_id}")
}

/// The JVM `fileArtUrl`: art for a file not in the store (`file_uri` is its `file://` URI): its
/// embedded cover, else its video's thumbnail, else a decoded frame; `None` without any of those.
pub fn file_art_url(file_uri: &str, has_cover: bool, youtube_vid: Option<&str>, has_video: bool) -> Option<String> {
    let kind = match (has_cover, youtube_vid, has_video) {
        (true, _, _) => ART_KIND_COVER,
        (false, Some(video_id), _) => return Some(youtube_art_url(video_id)),
        (false, None, true) => ART_KIND_FRAME,
        (false, None, false) => return None,
    };
    Some(format!("art://file?kind={}&uri={}", query_value(kind), query_value(file_uri)))
}

/// An art url as this app's earlier releases saved it in queue rows (`local-art/<kind>/<stream>/<px>/
/// <size>/<mtime>/<path>`) in its `art://file` form; any other url as it is.
pub fn adopt_legacy_art_url(url: String) -> String {
    let Some(rest) = url.strip_prefix(LEGACY_PREFIX) else { return url };
    let mut parts = rest.splitn(6, '/');
    let (Some(kind), Some(file)) = (parts.next(), parts.nth(4)) else { return url };
    let file_uri = ym_common::file_uri::to_file_uri(file);
    format!("art://file?kind={}&uri={}", query_value(kind), query_value(&file_uri))
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

static MEDIA: OnceLock<Arc<MediaStore>> = OnceLock::new();

/// Gives the art fetcher the Library once the app's graph has opened it.
pub fn attach_media(media: Arc<MediaStore>) {
    // A second graph (after an unlock) opens the same database; the first store keeps serving.
    let _ = MEDIA.set(media);
}

/// The JVM `mediaStoreArtFetcher`: `art:` urls to artwork decoded at the view size. A Library item's
/// embedded cover or decoded frame is kept in the JVM app's `image-cover-cache`, keyed as there (kind,
/// file URI, size, mtime: a changed file extracts again), so every size decodes from that file; a file
/// tagged with a YouTube id (and `art://yt/…`) loads its video's thumbnail through `loader`, sharing
/// that identity's caches. The outer `art:` load holds a local slot and the inner one a network slot,
/// so the nesting cannot starve.
pub struct MediaStoreArtFetcher {
    pub cover_cache: Arc<ImageSourceCache>,
    pub loader: Arc<OnceLock<ImageLoader<LoadedImage>>>,
}

enum ArtUrl {
    Picture(i64),
    YouTube(String),
    File { kind: String, path: PathBuf },
}

fn parse_art(url: &str) -> Option<Result<ArtUrl, ImageLoadError>> {
    let rest = url.strip_prefix("art:")?;
    let malformed = || ImageLoadError::new(Reason::Unsupported, format!("malformed art uri: {url}"));
    let rest = rest.strip_prefix("//").unwrap_or(rest);
    let end = rest.find(['/', '?']).unwrap_or(rest.len());
    let (authority, tail) = rest.split_at(end);
    let (path, query) = tail.split_once('?').unwrap_or((tail, ""));
    let last_segment = path.rsplit('/').next().filter(|segment| !segment.is_empty());
    Some(match authority {
        "yt" => last_segment.map(|video_id| ArtUrl::YouTube(video_id.to_owned())).ok_or_else(malformed),
        "file" => {
            let param = |name: &str| {
                query
                    .split('&')
                    .filter_map(|pair| pair.split_once('='))
                    .find(|(key, _)| *key == name)
                    .map(|(_, value)| ym_common::file_uri::percent_decode(value))
            };
            let path = param("uri").and_then(|uri| ym_common::file_uri::file_uri_path(&uri)).map(PathBuf::from);
            match path {
                Some(path) => Ok(ArtUrl::File { kind: param("kind").unwrap_or_default(), path }),
                None => Err(malformed()),
            }
        }
        "pic" => last_segment.and_then(|id| id.parse().ok()).map(ArtUrl::Picture).ok_or_else(malformed),
        _ => return None,
    })
}

impl ImageFetcher for MediaStoreArtFetcher {
    fn fetch(
        &self,
        url: &str,
        size: (u32, u32),
        scale: ImageScale,
    ) -> Option<BoxFuture<'static, Result<DecodedImage, ImageLoadError>>> {
        let parsed = parse_art(url)?;
        let (cache, loader, url) = (self.cover_cache.clone(), self.loader.clone(), url.to_owned());
        Some(Box::pin(async move {
            let art = parsed?;
            if let ArtUrl::YouTube(video_id) = &art {
                return youtube_art(&loader, video_id, size, scale).await;
            }
            let label = url.clone();
            let resolved =
                tokio::task::spawn_blocking(move || resolve(&cache, art, &url, size, scale)).await.map_err(
                    |error| ImageLoadError::with_cause(Reason::Undecodable, format!("art task failed: {label}"), error),
                )??;
            match resolved {
                Resolved::Decoded(decoded) => Ok(decoded),
                Resolved::YouTube(video_id) => youtube_art(&loader, &video_id, size, scale).await,
            }
        }))
    }
}

enum Resolved {
    Decoded(DecodedImage),
    YouTube(String),
}

/// The JVM `loaderYoutubeArt`: the video's identity request through the loader.
async fn youtube_art(
    loader: &OnceLock<ImageLoader<LoadedImage>>,
    video_id: &str,
    size: (u32, u32),
    scale: ImageScale,
) -> Result<DecodedImage, ImageLoadError> {
    let loader =
        loader.get().ok_or_else(|| ImageLoadError::new(Reason::Unsupported, "art fetcher without its loader"))?;
    let request =
        ImageRequest { key: youtube_image_key(video_id), sources: youtube_thumbnail_sources(video_id), size, scale };
    let bitmap = loader.load(&request, true).await?;
    let (width, height) = (bitmap.width(), bitmap.height());
    let image = tokio::task::spawn_blocking(move || bitmap.to_rgba()).await.map_err(|error| {
        ImageLoadError::with_cause(Reason::Undecodable, format!("art copy failed: {video_id}"), error)
    })?;
    Ok(DecodedImage { image, source_width: width, source_height: height })
}

/// Blocking: the art a store row or a loose file has, decoded to `size`, or the video id to load.
fn resolve(
    cache: &ImageSourceCache,
    art: ArtUrl,
    url: &str,
    size: (u32, u32),
    scale: ImageScale,
) -> Result<Resolved, ImageLoadError> {
    match art {
        ArtUrl::YouTube(video_id) => Ok(Resolved::YouTube(video_id)),
        ArtUrl::File { kind, path } => {
            if !path.exists() {
                return Err(ImageLoadError::new(Reason::NotFound, format!("no such file: {url}")));
            }
            let extract_failed = |error: FfmpegError| {
                ImageLoadError::new(Reason::Undecodable, format!("art extraction failed: {url}: {error}"))
            };
            let bytes = if kind == ART_KIND_COVER { attached_picture(&path) } else { video_frame(&path) }
                .map_err(extract_failed)?;
            decode_scaled(&bytes, size, scale, url).map(Resolved::Decoded)
        }
        ArtUrl::Picture(id) => {
            let media = MEDIA
                .get()
                .ok_or_else(|| ImageLoadError::new(Reason::Unsupported, format!("Library not open yet: {url}")))?;
            let item = media
                .item(id)
                .map_err(|error| {
                    ImageLoadError::new(Reason::Undecodable, format!("Library lookup failed: {url}: {error}"))
                })?
                .ok_or_else(|| ImageLoadError::new(Reason::NotFound, format!("no media item for art uri: {url}")))?;
            let kind = match (item.thumbnail_stream_index, &item.youtube_vid) {
                (Some(_), _) => ART_KIND_COVER,
                (None, Some(video_id)) => return Ok(Resolved::YouTube(video_id.clone())),
                (None, None) if item.video_codec.is_some() => ART_KIND_FRAME,
                (None, None) => {
                    return Err(ImageLoadError::new(
                        Reason::NotFound,
                        format!("media item {id} has no artwork source: {url}"),
                    ));
                }
            };
            let path = PathBuf::from(&item.uri);
            let file_uri = ym_store::file_uri(&item.uri);
            let identity = format!("{kind}:{file_uri}:{}:{}", item.file_size, item.last_modified);
            let key = source_cache_key(&identity);
            let cached = cache.file_path(&key).map_err(|error| {
                ImageLoadError::with_cause(Reason::Undecodable, format!("cover cache unreadable: {url}"), error)
            })?;
            let file = match cached {
                Some(file) => file,
                None => {
                    if !path.exists() {
                        return Err(ImageLoadError::new(Reason::NotFound, format!("no such file: {url}")));
                    }
                    let failure_key = source_cache_key(&format!("failed:{}:{identity}", crate::graph::APP_VERSION));
                    if let Some(failed_at) = recent_failure(cache, &failure_key, now_millis()) {
                        return Err(ImageLoadError::new(
                            Reason::RecentlyFailed,
                            format!("art extraction failed at {failed_at}, retried after 1d: {url}"),
                        ));
                    }
                    let encoded =
                        match if kind == ART_KIND_COVER { attached_picture(&path) } else { video_frame(&path) } {
                            Ok(encoded) => encoded,
                            Err(error) => {
                                let mut message = format!("art extraction failed: {url}: {error}");
                                if error.is_extraction_failure()
                                    && let Err(marker_error) = record_failure(cache, &failure_key, now_millis(), url)
                                {
                                    message.push_str(&format!("; failure not remembered: {marker_error}"));
                                }
                                return Err(ImageLoadError::new(Reason::Undecodable, message));
                            }
                        };
                    cache.write_blocking(&key, Cursor::new(encoded), DEFAULT_MAX_IMAGE_BYTES, url)?
                }
            };
            decode_scaled_file(&file, size, scale, url).map(Resolved::Decoded)
        }
    }
}

/// The JVM `EXTRACTION_FAILURE_TTL`: how long a file's failed extraction is not retried.
const EXTRACTION_FAILURE_TTL_MS: i64 = 24 * 60 * 60 * 1000;

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |elapsed| i64::try_from(elapsed.as_millis()).unwrap_or(i64::MAX))
}

/// The JVM `recentFailure`: when the remembered failure under `key` happened, if younger than the TTL.
/// An unreadable marker counts as none: the extraction runs again.
fn recent_failure(cache: &ImageSourceCache, key: &str, now: i64) -> Option<i64> {
    let marker = cache.file_path(key).ok()??;
    let failed_at: i64 = std::fs::read_to_string(marker).ok()?.trim().parse().ok()?;
    let age = now - failed_at;
    (0..EXTRACTION_FAILURE_TTL_MS).contains(&age).then_some(failed_at)
}

/// The JVM `recordFailure`: the failure's epoch millis as an entry of the cover cache itself, so it is
/// bounded by that cache's LRU budget and shared with the JVM app, which reads the same directory.
fn record_failure(cache: &ImageSourceCache, key: &str, now: i64, url: &str) -> Result<(), ImageLoadError> {
    cache.write_blocking(key, Cursor::new(now.to_string()), u64::MAX, url).map(|_| ())
}

/// The embedded picture as stored (the JVM `getAttachedPic`). Whole in memory: `decode_scaled` takes
/// bytes, and a bounded buffer is what lets the child be capped and timed out.
fn attached_picture(path: &Path) -> Result<Vec<u8>, FfmpegError> {
    FfmpegMetadataGetter::shared().get_attached_pic(path, DEFAULT_MAX_IMAGE_BYTES as usize)
}

/// A representative frame as a JPEG (the JVM `extractThumbnail`), held as [`attached_picture`] is.
fn video_frame(path: &Path) -> Result<Vec<u8>, FfmpegError> {
    FfmpegMetadataGetter::shared().extract_thumbnail(path, DEFAULT_MAX_IMAGE_BYTES as usize)
}

/// How earlier releases named local art in saved queue rows.
const LEGACY_PREFIX: &str = "local-art/";
