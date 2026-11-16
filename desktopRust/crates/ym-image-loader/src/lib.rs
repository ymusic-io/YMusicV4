//! YMusicV4's `:image-loader`, tuned for YouTube thumbnails: requests keyed by image identity with
//! several sized sources, scaled decode (never a full-size image resident), a memory LRU of the UI's
//! own bitmaps, the JVM app's disk caches (originals, scaled WebP copies, the variant index) in their
//! on-disk formats, two newest-first gates, in-flight sharing and cancellation. With the `gpui`
//! feature, `AsyncImage` draws a request at its own measured size.

mod bitmap;
mod codec;
mod disk_lru;
mod fetchers;
mod gate;
mod loader;
mod memory_budget;
mod memory_cache;
mod order;
mod request;
mod scaled_cache;
mod source_cache;
mod variant_index;

#[cfg(feature = "gpui")]
pub mod async_image;

pub use bitmap::Bitmap;
pub use codec::{
    DecodedImage, SCALED_WEBP_QUALITY, decode_scaled, decode_scaled_file, decode_scaled_reader, encode_webp,
};
pub use fetchers::{DEFAULT_MAX_IMAGE_BYTES, FileFetcher, ImageFetcher, NetworkFetcher, decode_file};
pub use loader::{ImageLoader, ImageLoaderConfig};
pub use memory_budget::default_memory_cache_budget;
pub use order::{bucket_px, covers, declared_size, memory_key, order_sources, request_bucket, scale_suffix};
pub use request::{ImageLoadError, ImageRequest, ImageScale, ImageSource, Reason, SourceCrop, image_cache_key};
pub use scaled_cache::{DiskScaledImageCache, ScaledImageCache};
pub use source_cache::{ImageSourceCache, source_cache_key};
pub use variant_index::{DiskVariantIndex, MISSING_TTL, MemoryVariantIndex, VariantIndex, VariantRecord};
