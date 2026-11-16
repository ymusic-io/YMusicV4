//! The JVM `ImageFetcher.kt` + `Fetchers.kt`: everything is a fetcher. One resolves its url scheme,
//! fetching AND decoding to the requested size however suits its storage best.

use std::path::PathBuf;
use std::sync::Arc;

use futures::future::BoxFuture;

use crate::codec::{DecodedImage, decode_scaled_file};
use crate::request::{ImageLoadError, ImageScale, Reason, image_cache_key};
use crate::source_cache::{ImageSourceCache, blocking, sha256_hex};

/// One uri-scheme resolver. `None` when `url`'s scheme isn't handled here; the loader asks its
/// fetchers in order and the first `Some` wins. `size` never asks for an upscale. Failures are
/// classified [`ImageLoadError`]s (the loader remembers `NotFound`); guarding (byte caps, a vanished
/// cache file) is the fetcher's own.
pub trait ImageFetcher: Send + Sync {
    fn fetch(
        &self,
        url: &str,
        size: (u32, u32),
        scale: ImageScale,
    ) -> Option<BoxFuture<'static, Result<DecodedImage, ImageLoadError>>>;
}

/// The JVM default `maxImageBytes`: 32 MB for one body.
pub const DEFAULT_MAX_IMAGE_BYTES: u64 = 32 * 1024 * 1024;

/// http/https over `client` with the downloaded originals owned on disk (the Coil/Glide model): the
/// body streams into an [`ImageSourceCache`], and decoding happens from the cached file, keyed by url
/// identity so every display size shares one download. A cached file that vanishes mid-decode
/// (eviction race) is downloaded again once; one that decodes as garbage fails as is.
pub struct NetworkFetcher {
    client: reqwest::Client,
    cache: Arc<ImageSourceCache>,
    max_image_bytes: u64,
    found_despite_404: Option<fn(&str) -> bool>,
}

impl NetworkFetcher {
    pub fn new(client: reqwest::Client, cache: Arc<ImageSourceCache>, max_image_bytes: u64) -> Self {
        Self { client, cache, max_image_bytes, found_despite_404: None }
    }

    /// Urls for which a 404 still carries the image (the JVM app's image-client interceptor).
    pub fn found_despite_404(mut self, rule: fn(&str) -> bool) -> Self {
        self.found_despite_404 = Some(rule);
        self
    }
}

fn is_http(url: &str) -> bool {
    url.starts_with("http://") || url.starts_with("https://")
}

impl ImageFetcher for NetworkFetcher {
    fn fetch(
        &self,
        url: &str,
        size: (u32, u32),
        scale: ImageScale,
    ) -> Option<BoxFuture<'static, Result<DecodedImage, ImageLoadError>>> {
        if !is_http(url) {
            return None;
        }
        let (client, cache, max_image_bytes, found_despite_404) =
            (self.client.clone(), self.cache.clone(), self.max_image_bytes, self.found_despite_404);
        let url = url.to_owned();
        Some(Box::pin(async move {
            let key = sha256_hex(&image_cache_key(&url));
            let cached = {
                let (cache, key) = (cache.clone(), key.clone());
                blocking(move || cache.file_path(&key)).await
            };
            match cached {
                Ok(Some(file)) => match decode_file(file.clone(), size, scale, &url).await {
                    Ok(decoded) => return Ok(decoded),
                    // Still on disk but undecodable = permanent; vanished = eviction race, refetch.
                    Err(error) if file.exists() => return Err(error),
                    Err(_) => {}
                },
                Ok(None) => {}
                Err(error) => {
                    return Err(ImageLoadError::with_cause(
                        Reason::Network,
                        format!("image cache unreadable: {url}"),
                        error,
                    ));
                }
            }
            let response = client.get(&url).send().await.map_err(|error| {
                ImageLoadError::with_cause(Reason::Network, format!("image request failed: {url}"), error)
            })?;
            let status = response.status();
            let found = status.is_success()
                || (status == reqwest::StatusCode::NOT_FOUND && found_despite_404.is_some_and(|rule| rule(&url)));
            if !found {
                let reason = if status == reqwest::StatusCode::NOT_FOUND || status == reqwest::StatusCode::GONE {
                    Reason::NotFound
                } else {
                    Reason::Network
                };
                return Err(ImageLoadError::new(reason, format!("image HTTP {}: {url}", status.as_u16())));
            }
            if let Some(declared) = response.content_length().filter(|declared| *declared > max_image_bytes) {
                return Err(ImageLoadError::new(
                    Reason::Undecodable,
                    format!("image body {declared} bytes exceeds the {max_image_bytes}-byte cap: {url}"),
                ));
            }
            let file = cache.write(&key, response.bytes_stream(), max_image_bytes, &url).await?;
            decode_file(file, size, scale, &url).await
        }))
    }
}

/// `file://` urls straight from disk: no copy, decoded from the file. The path is percent-decoded from
/// the url, so an encoded name resolves to the real file.
pub struct FileFetcher;

impl ImageFetcher for FileFetcher {
    fn fetch(
        &self,
        url: &str,
        size: (u32, u32),
        scale: ImageScale,
    ) -> Option<BoxFuture<'static, Result<DecodedImage, ImageLoadError>>> {
        if !url.starts_with("file:") {
            return None;
        }
        let url = url.to_owned();
        Some(Box::pin(async move {
            let path = ym_common::file_uri::file_uri_path(&url)
                .map(PathBuf::from)
                .ok_or_else(|| ImageLoadError::new(Reason::Unsupported, format!("malformed file url: {url}")))?;
            if !path.exists() {
                return Err(ImageLoadError::new(Reason::NotFound, format!("no such file: {url}")));
            }
            decode_file(path, size, scale, &url).await
        }))
    }
}

/// [`decode_scaled_file`] on tokio's blocking pool.
pub async fn decode_file(
    path: PathBuf,
    size: (u32, u32),
    scale: ImageScale,
    url: &str,
) -> Result<DecodedImage, ImageLoadError> {
    let label = url.to_owned();
    tokio::task::spawn_blocking(move || decode_scaled_file(&path, size, scale, &label)).await.map_err(|error| {
        ImageLoadError::with_cause(Reason::Undecodable, format!("image decode task failed: {url}"), error)
    })?
}
