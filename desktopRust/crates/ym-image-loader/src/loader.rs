//! The JVM `ImageLoader.kt`: resolve an [`ImageRequest`] through the fetchers, decode scaled to the view
//! size, cache.
//!
//! - **Identity**: every cache layer is keyed by [`ImageRequest::key`] plus the 64px size bucket (and
//!   the scale), never by a url.
//! - **Memory**: images decode at (or just above) the requested size; the full-size original is never
//!   kept. Decoded bitmaps live in a bounded LRU.
//! - **Fetchers** are asked in order per source; the first that handles the url wins. A request with
//!   several sources tries the smallest that covers the size first and falls back across the rest.
//! - **Scaled cache** (optional) persists the scaled image when it is smaller than its source.
//! - **Priority is LIFO** over two pools (http(s) sources and everything else), and a load whose every
//!   caller dropped frees its slot.
//! - **One load per image**: concurrent requests for one key and size share one fetch.
//! - **Misses are remembered**: a source that answered NotFound is not asked again for 10 minutes.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use futures::FutureExt;
use futures::future::{BoxFuture, Shared};
use ym_common::log::Logger;

use crate::bitmap::Bitmap;
use crate::codec::DecodedImage;
use crate::fetchers::ImageFetcher;
use crate::gate::LifoGate;
use crate::memory_cache::MemoryImageCache;
use crate::order::{memory_key, order_sources, request_bucket, scale_suffix};
use crate::request::{ImageLoadError, ImageRequest, ImageScale, ImageSource, Reason, SourceCrop, image_cache_key};
use crate::scaled_cache::ScaledImageCache;
use crate::source_cache::sha256_hex;
use crate::variant_index::{MemoryVariantIndex, VariantIndex};

/// Bump when the scaling rule changes: entries scaled the old way must miss.
const SCALED_CACHE_VERSION: u32 = 3;
const NOT_FOUND_TTL: Duration = Duration::from_secs(10 * 60);

pub struct ImageLoaderConfig {
    pub fetchers: Vec<Arc<dyn ImageFetcher>>,
    pub scaled_cache: Option<Arc<dyn ScaledImageCache>>,
    pub logger: Logger,
    pub memory_cache_bytes: u64,
    pub network_parallelism: usize,
    pub local_parallelism: usize,
    pub variant_index: Arc<dyn VariantIndex>,
}

impl ImageLoaderConfig {
    /// The JVM constructor's defaults: no scaled cache, 32 MB memory, 4 network and 2 local slots, an
    /// in-process variant index.
    pub fn new(fetchers: Vec<Arc<dyn ImageFetcher>>) -> Self {
        Self {
            fetchers,
            scaled_cache: None,
            logger: Logger::none(),
            memory_cache_bytes: 32 * 1024 * 1024,
            network_parallelism: 4,
            local_parallelism: 2,
            variant_index: Arc::new(MemoryVariantIndex::default()),
        }
    }
}

pub struct ImageLoader<B: Bitmap> {
    inner: Arc<Inner<B>>,
}

impl<B: Bitmap> Clone for ImageLoader<B> {
    fn clone(&self) -> Self {
        Self { inner: self.inner.clone() }
    }
}

struct Inner<B: Bitmap> {
    fetchers: Vec<Arc<dyn ImageFetcher>>,
    scaled_cache: Option<Arc<dyn ScaledImageCache>>,
    log: Logger,
    memory: MemoryImageCache<B>,
    memory_budget: AtomicU64,
    network_gate: LifoGate,
    local_gate: LifoGate,
    tokio: tokio::runtime::Handle,
    in_flight: Mutex<InFlight<B>>,
    not_found: Mutex<HashMap<String, Instant>>,
    variant_index: Arc<dyn VariantIndex>,
}

type LoadFuture<B> = Shared<BoxFuture<'static, Result<B, ImageLoadError>>>;

struct InFlight<B> {
    next_id: u64,
    flights: HashMap<String, Flight<B>>,
}

struct Flight<B> {
    id: u64,
    load: LoadFuture<B>,
    waiters: usize,
    abort: tokio::task::AbortHandle,
}

/// One caller of a shared load; the last one gone retires it, cancelling it when unfinished.
struct Waiter<B: Bitmap> {
    inner: Arc<Inner<B>>,
    key: String,
    flight: u64,
}

impl<B: Bitmap> Drop for Waiter<B> {
    fn drop(&mut self) {
        let mut in_flight = lock(&self.inner.in_flight);
        let Some(flight) = in_flight.flights.get_mut(&self.key).filter(|flight| flight.id == self.flight) else {
            return;
        };
        flight.waiters -= 1;
        if flight.waiters == 0
            && let Some(flight) = in_flight.flights.remove(&self.key)
        {
            flight.abort.abort();
        }
    }
}

impl<B: Bitmap> ImageLoader<B> {
    /// `tokio` runs the loads; fetches and decodes hop to its blocking pool.
    pub fn new(config: ImageLoaderConfig, tokio: tokio::runtime::Handle) -> Self {
        Self {
            inner: Arc::new(Inner {
                fetchers: config.fetchers,
                scaled_cache: config.scaled_cache,
                log: config.logger.fork("image-loader"),
                memory: MemoryImageCache::new(config.memory_cache_bytes),
                memory_budget: AtomicU64::new(config.memory_cache_bytes),
                network_gate: LifoGate::new(config.network_parallelism),
                local_gate: LifoGate::new(config.local_parallelism),
                tokio,
                in_flight: Mutex::new(InFlight { next_id: 0, flights: HashMap::new() }),
                not_found: Mutex::new(HashMap::new()),
                variant_index: config.variant_index,
            }),
        }
    }

    pub fn logger(&self) -> &Logger {
        &self.inner.log
    }

    /// New steady-state memory-cache budget; evicts to it now.
    pub fn set_memory_cache_budget(&self, bytes: u64) {
        self.inner.memory_budget.store(bytes, Ordering::Relaxed);
        self.inner.memory.set_budget(bytes);
    }

    /// Memory pressure: evict now down to `keep_fraction` of the budget (0 clears) without changing
    /// the steady-state budget.
    pub fn trim_memory_cache(&self, keep_fraction: f32) {
        let budget = self.inner.memory_budget.load(Ordering::Relaxed);
        self.inner.memory.trim_to((budget as f64 * f64::from(keep_fraction.clamp(0., 1.))) as u64);
    }

    /// The memory-cached bitmap for `request`, or `None`: cheap enough for a frame, so an already
    /// cached image draws on its first frame with no placeholder flash.
    pub fn peek(&self, request: &ImageRequest) -> Option<B> {
        self.inner.memory.peek(&memory_key(request))
    }

    /// [`ImageLoader::load`], or `None` on a failed fetch or decode, logged here.
    pub async fn load_or_null(&self, request: &ImageRequest) -> Option<B> {
        match self.load(request, true).await {
            Ok(bitmap) => Some(bitmap),
            Err(error) => {
                self.inner.log.warn(&format!("load failed: {}", request.key), Some(&error));
                None
            }
        }
    }

    /// The image for `request`, scaled down to cover (or fit) its size, aspect kept, never upscaled.
    /// The error is the last source's reason, `NotFound` only when every source said so.
    ///
    /// `cache_in_memory` false keeps the result out of the memory LRU (an existing entry is still
    /// used), for callers that hand the bitmap off once instead of drawing it repeatedly.
    pub async fn load(&self, request: &ImageRequest, cache_in_memory: bool) -> Result<B, ImageLoadError> {
        let key = memory_key(request);
        if let Some(hit) = self.inner.memory.peek(&key) {
            return Ok(hit);
        }
        if let Some(hit) = self.peek_covering(request) {
            return Ok(hit);
        }
        let (load, _waiter) = {
            let mut in_flight = lock(&self.inner.in_flight);
            if !in_flight.flights.contains_key(&key) {
                in_flight.next_id += 1;
                let id = in_flight.next_id;
                let task = self.inner.tokio.spawn(load_uncached(
                    self.inner.clone(),
                    request.clone(),
                    key.clone(),
                    cache_in_memory,
                ));
                let abort = task.abort_handle();
                let load = async move {
                    task.await.unwrap_or_else(|error| {
                        Err(ImageLoadError::with_cause(Reason::Undecodable, "image load task failed", error))
                    })
                }
                .boxed()
                .shared();
                in_flight.flights.insert(key.clone(), Flight { id, load, waiters: 0, abort });
            }
            let flight = in_flight.flights.get_mut(&key).expect("flight inserted above");
            flight.waiters += 1;
            (flight.load.clone(), Waiter { inner: self.inner.clone(), key: key.clone(), flight: flight.id })
        };
        load.await
    }

    fn peek_covering(&self, request: &ImageRequest) -> Option<B> {
        let (width, height) = request_bucket(request);
        self.inner.memory.peek_covering(&request.key, width, height, scale_suffix(request.scale))
    }

    /// Loads `request` in the background so a later [`ImageLoader::load`] finds it in memory. Best
    /// effort: a failure is logged.
    pub fn prefetch(&self, request: ImageRequest) {
        if self.inner.memory.peek(&memory_key(&request)).is_some() {
            return;
        }
        let loader = self.clone();
        self.inner.tokio.spawn(async move {
            loader.load_or_null(&request).await;
        });
    }

    /// The scaled image as a committed file in the scaled disk cache, or `None` without one. For
    /// consumers that hand bytes to another process and never draw (the OS media session): the memory
    /// LRU is untouched, and the entry is persisted even when the source already fit the request. The
    /// path is a live LRU entry that can be evicted later: call again on a missing file.
    pub async fn load_file(&self, request: &ImageRequest) -> Result<Option<PathBuf>, ImageLoadError> {
        self.inner.tokio.spawn(load_file_uncached(self.inner.clone(), request.clone())).await.unwrap_or_else(|error| {
            Err(ImageLoadError::with_cause(Reason::Undecodable, "image file load task failed", error))
        })
    }
}

async fn load_file_uncached<B: Bitmap>(
    inner: Arc<Inner<B>>,
    request: ImageRequest,
) -> Result<Option<PathBuf>, ImageLoadError> {
    let Some(cache) = inner.scaled_cache.clone() else { return Ok(None) };
    let key = memory_key(&request);
    let disk = disk_key(&key);
    if let Some(path) = scaled_file(&cache, &disk).await? {
        return Ok(Some(path));
    }
    let _permit = inner.gate_for(&request).acquire().await;
    if let Some(path) = scaled_file(&cache, &disk).await? {
        return Ok(Some(path));
    }
    let image = match inner.memory.peek(&key) {
        Some(bitmap) => blocking_image(move || Ok(bitmap.to_rgba())).await?,
        None => inner.fetch_from_sources(&request).await?.image,
    };
    let written = {
        let (cache, disk) = (cache.clone(), disk.clone());
        tokio::task::spawn_blocking(move || cache.put(&disk, &image)).await
    };
    match written {
        Ok(Ok(())) => {}
        Ok(Err(error)) => {
            return Err(ImageLoadError::with_cause(
                Reason::Undecodable,
                format!("scaled entry write failed: {}", request.key),
                error,
            ));
        }
        Err(error) => {
            return Err(ImageLoadError::with_cause(
                Reason::Undecodable,
                format!("scaled entry write failed: {}", request.key),
                error,
            ));
        }
    }
    scaled_file(&cache, &disk).await?.map(Some).ok_or_else(|| {
        ImageLoadError::new(Reason::Undecodable, format!("scaled entry vanished after write: {}", request.key))
    })
}

async fn load_uncached<B: Bitmap>(
    inner: Arc<Inner<B>>,
    request: ImageRequest,
    key: String,
    cache_in_memory: bool,
) -> Result<B, ImageLoadError> {
    let _permit = inner.gate_for(&request).acquire().await;
    // A load that finished while this one waited.
    if let Some(hit) = inner.memory.peek(&key) {
        return Ok(hit);
    }
    let bitmap = match inner.read_scaled_cache(&key).await {
        Some(bitmap) => bitmap,
        None => match inner.fetch_and_decode(&request, &key).await {
            Ok(bitmap) => bitmap,
            // Offline or every variant gone: any size of this identity beats nothing; returned, not
            // cached under this size, so a sharp decode replaces it once a source answers.
            Err(error) => return inner.memory.peek_largest(&request.key).ok_or(error),
        },
    };
    if cache_in_memory {
        inner.memory.put(key, bitmap.clone());
    }
    Ok(bitmap)
}

impl<B: Bitmap> Inner<B> {
    fn gate_for(&self, request: &ImageRequest) -> &LifoGate {
        if request.sources.iter().any(|source| source.url.starts_with("http://") || source.url.starts_with("https://"))
        {
            &self.network_gate
        } else {
            &self.local_gate
        }
    }

    async fn read_scaled_cache(&self, key: &str) -> Option<B> {
        let cache = self.scaled_cache.clone()?;
        let disk = disk_key(key);
        let read = tokio::task::spawn_blocking(move || cache.get(&disk).map(|image| image.map(B::from_rgba))).await;
        match read {
            Ok(Ok(bitmap)) => bitmap,
            // A corrupt or unreadable entry falls through to a fresh fetch (which overwrites it).
            Ok(Err(error)) => {
                self.log.warn(&format!("scaled cache read failed: {error}"), Some(&error));
                None
            }
            Err(error) => {
                self.log.warn("scaled cache read task failed", Some(&error));
                None
            }
        }
    }

    async fn fetch_and_decode(&self, request: &ImageRequest, key: &str) -> Result<B, ImageLoadError> {
        let decoded = self.fetch_from_sources(request).await?;
        // Only a genuinely downscaled result is worth persisting: an un-shrunk image re-encoded is
        // often bigger than the original its fetcher already holds, and saves no decode.
        let cache = self.scaled_cache.clone().filter(|_| decoded.downscaled());
        let disk = disk_key(key);
        let (bitmap, written) = blocking_image(move || {
            let written = cache.map(|cache| cache.put(&disk, &decoded.image));
            Ok((B::from_rgba(decoded.image), written))
        })
        .await?;
        if let Some(Err(error)) = written {
            self.log.warn(&format!("scaled cache write failed: {error}"), Some(&error));
        }
        Ok(bitmap)
    }

    /// The request's sources in [`order_sources`] order until one decodes; failures fall through.
    /// What each attempt learns (the measured size, or NotFound) goes to the variant index.
    async fn fetch_from_sources(&self, request: &ImageRequest) -> Result<DecodedImage, ImageLoadError> {
        let size = request_bucket(request);
        let record = {
            let (index, key) = (self.variant_index.clone(), request.key.clone());
            variant_io(move || index.get(&key)).await?
        };
        let mut failures: Vec<ImageLoadError> = Vec::new();
        for source in order_sources(&request.sources, size, request.scale, &record) {
            let source_key = image_cache_key(&source.url);
            if record.missing.contains(&source_key) {
                failures.push(ImageLoadError::new(Reason::NotFound, format!("known missing: {}", source.url)));
                continue;
            }
            match self.fetch_source(&source, size, request.scale).await {
                Ok(decoded) => {
                    let (index, key, width, height) =
                        (self.variant_index.clone(), request.key.clone(), decoded.source_width, decoded.source_height);
                    variant_io(move || index.record_fetched(&key, &source_key, width, height)).await?;
                    return Ok(decoded);
                }
                Err(error) => {
                    if error.reason == Reason::NotFound {
                        let (index, key) = (self.variant_index.clone(), request.key.clone());
                        variant_io(move || index.record_missing(&key, &source_key)).await?;
                    }
                    self.log.debug(|| format!("source failed ({:?}): {}: {error}", error.reason, source.url));
                    failures.push(error);
                }
            }
        }
        // The most telling failure: anything beats NotFound, which only wins if unanimous.
        let chosen = failures.iter().position(|failure| failure.reason != Reason::NotFound).unwrap_or(0);
        if failures.is_empty() {
            return Err(ImageLoadError::new(Reason::Unsupported, format!("no sources: {}", request.key)));
        }
        let mut failure = failures.remove(chosen);
        failure.suppressed = failures;
        Err(failure)
    }

    async fn fetch_source(
        &self,
        source: &ImageSource,
        size: (u32, u32),
        scale: ImageScale,
    ) -> Result<DecodedImage, ImageLoadError> {
        let source_key = image_cache_key(&source.url);
        {
            let mut not_found = lock(&self.not_found);
            if let Some(missing_since) = not_found.get(&source_key) {
                if missing_since.elapsed() < NOT_FOUND_TTL {
                    return Err(ImageLoadError::new(Reason::NotFound, format!("known missing: {}", source.url)));
                }
                not_found.remove(&source_key);
            }
        }
        let fetch =
            self.fetchers.iter().find_map(|fetcher| fetcher.fetch(&source.url, size, scale)).ok_or_else(|| {
                ImageLoadError::new(Reason::Unsupported, format!("no fetcher handles: {}", source.url))
            })?;
        let decoded = match fetch.await {
            Ok(decoded) => decoded,
            Err(error) => {
                if error.reason == Reason::NotFound {
                    lock(&self.not_found).insert(source_key, Instant::now());
                }
                return Err(error);
            }
        };
        if source.crop == SourceCrop::None {
            return Ok(decoded);
        }
        let crop = source.crop;
        blocking_image(move || Ok(decoded.cropped(crop))).await
    }
}

async fn scaled_file(cache: &Arc<dyn ScaledImageCache>, disk: &str) -> Result<Option<PathBuf>, ImageLoadError> {
    let (cache, disk) = (cache.clone(), disk.to_owned());
    match tokio::task::spawn_blocking(move || cache.file_path(&disk)).await {
        Ok(Ok(path)) => Ok(path),
        Ok(Err(error)) => Err(ImageLoadError::with_cause(Reason::Undecodable, "scaled cache unreadable", error)),
        Err(error) => Err(ImageLoadError::with_cause(Reason::Undecodable, "scaled cache task failed", error)),
    }
}

/// CPU-bound image work on tokio's blocking pool.
async fn blocking_image<T: Send + 'static>(
    work: impl FnOnce() -> Result<T, ImageLoadError> + Send + 'static,
) -> Result<T, ImageLoadError> {
    tokio::task::spawn_blocking(work)
        .await
        .map_err(|error| ImageLoadError::with_cause(Reason::Undecodable, "image task failed", error))?
}

/// Variant-index I/O on tokio's blocking pool; a failure fails the load, as on the JVM.
async fn variant_io<T: Send + 'static>(
    work: impl FnOnce() -> std::io::Result<T> + Send + 'static,
) -> Result<T, ImageLoadError> {
    match tokio::task::spawn_blocking(work).await {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(error)) => Err(ImageLoadError::with_cause(Reason::Network, "image variant index failed", error)),
        Err(error) => Err(ImageLoadError::with_cause(Reason::Network, "image variant index task failed", error)),
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn disk_key(key: &str) -> String {
    sha256_hex(&format!("{SCALED_CACHE_VERSION}:{key}"))
}
