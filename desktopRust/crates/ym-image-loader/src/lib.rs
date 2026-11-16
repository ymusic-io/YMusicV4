//! YMusicV4's `:image-loader`, tuned for YouTube thumbnails: requests keyed by image identity with
//! several sized sources, scaled decode (never a full-size image resident), the JVM app's disk caches
//! (originals, scaled WebP copies, the variant index) in their on-disk formats.

mod bitmap;
mod codec;
mod disk_lru;
mod order;
mod request;
mod scaled_cache;
mod source_cache;
mod variant_index;

pub use bitmap::Bitmap;
pub use codec::{
    DecodedImage, SCALED_WEBP_QUALITY, decode_scaled, decode_scaled_file, decode_scaled_reader, encode_webp,
};
pub use order::{bucket_px, covers, declared_size, memory_key, order_sources, request_bucket, scale_suffix};
pub use request::{ImageLoadError, ImageRequest, ImageScale, ImageSource, Reason, SourceCrop, image_cache_key};
pub use scaled_cache::{DiskScaledImageCache, ScaledImageCache};
pub use source_cache::{ImageSourceCache, source_cache_key};
pub use variant_index::{DiskVariantIndex, MISSING_TTL, MemoryVariantIndex, VariantIndex, VariantRecord};
