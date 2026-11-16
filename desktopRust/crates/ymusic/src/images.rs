//! The app's image loader, wired as the JVM desktop app's `Main.kt` builds it: http on the app's
//! reqwest client (originals stream into `image-source-cache`, decoded from the file), `file://`, and
//! the Library's local art (`image-cover-cache`), with the scaled WebP copies in `image-scaled-cache`
//! and the variant index in `image-variants`. The same folders as the JVM app's, in its formats.

use std::path::Path;
use std::sync::{Arc, OnceLock};

use ym_common::log::Logger;
use ym_image_loader::async_image::LoadedImage;
use ym_image_loader::{
    DiskScaledImageCache, DiskVariantIndex, FileFetcher, ImageFetcher, ImageLoader, ImageLoaderConfig,
    ImageSourceCache, NetworkFetcher,
};

/// The JVM app's `image-cover-cache` cap.
const COVER_CACHE_BYTES: u64 = 64 * 1024 * 1024;

pub fn image_loader(
    data_dir: &Path,
    client: reqwest::Client,
    tokio: tokio::runtime::Handle,
    log: &Logger,
) -> ImageLoader<LoadedImage> {
    let cache_log = log.fork("image-cache");
    let source_cache = Arc::new(ImageSourceCache::new(
        data_dir.join("image-source-cache"),
        ImageSourceCache::DEFAULT_MAX_BYTES,
        cache_log.clone(),
        tokio.clone(),
    ));
    let cover_cache = Arc::new(ImageSourceCache::new(
        data_dir.join("image-cover-cache"),
        COVER_CACHE_BYTES,
        cache_log.clone(),
        tokio.clone(),
    ));
    // The art fetcher loads a video's thumbnail through the loader it is part of.
    let art_loader = Arc::new(OnceLock::new());
    let fetchers: Vec<Arc<dyn ImageFetcher>> = vec![
        Arc::new(
            NetworkFetcher::new(client, source_cache, ym_image_loader::DEFAULT_MAX_IMAGE_BYTES)
                .found_despite_404(is_no_thumbnail_url),
        ),
        Arc::new(FileFetcher),
        Arc::new(crate::local_art::MediaStoreArtFetcher { cover_cache, loader: art_loader.clone() }),
    ];
    let mut config = ImageLoaderConfig::new(fetchers);
    config.scaled_cache = Some(Arc::new(DiskScaledImageCache::new(
        data_dir.join("image-scaled-cache"),
        DiskScaledImageCache::DEFAULT_MAX_BYTES,
        cache_log.clone(),
        tokio.clone(),
    )));
    config.logger = log.clone();
    config.memory_cache_bytes = ym_image_loader::default_memory_cache_budget();
    config.variant_index = Arc::new(DiskVariantIndex::new(
        data_dir.join("image-variants"),
        DiskVariantIndex::DEFAULT_MAX_BYTES,
        cache_log,
        tokio.clone(),
    ));
    let loader = ImageLoader::new(config, tokio);
    art_loader.get_or_init(|| loader.clone());
    loader
}

/// `i.ytimg.com/img/no_thumbnail.jpg`, YouTube's thumbnail for a playlist with nothing to show, is a
/// real placeholder JPEG served as 404 (the JVM image client's interceptor answers it as 200).
fn is_no_thumbnail_url(url: &str) -> bool {
    url.contains("/img/no_thumbnail")
}
