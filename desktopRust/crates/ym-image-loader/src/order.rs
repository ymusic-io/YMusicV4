//! The JVM `ImageLoader.kt` request arithmetic: the 64px size buckets, memory keys, and which of an
//! identity's sources to try first for a box.

use crate::request::{ImageRequest, ImageScale, ImageSource, SourceCrop, image_cache_key};
use crate::variant_index::VariantRecord;

/// Round the target up to a 64px step: stable cache keys across ±layout-pixel jitter.
pub fn bucket_px(px: u32) -> u32 {
    ((px.clamp(1, 1 << 20) - 1) / 64 + 1) * 64
}

pub fn request_bucket(request: &ImageRequest) -> (u32, u32) {
    (bucket_px(request.size.0), bucket_px(request.size.1))
}

/// `key@WxH`, plus `~fit` for a fit decode (smaller than the cover decode of the same box).
pub fn memory_key(request: &ImageRequest) -> String {
    let (width, height) = request_bucket(request);
    format!("{}@{width}x{height}{}", request.key, scale_suffix(request.scale))
}

pub fn scale_suffix(scale: ImageScale) -> &'static str {
    if scale == ImageScale::Fit { "~fit" } else { "" }
}

/// Sources to try for a `size` box. A source's size is what `record` measured when it was fetched,
/// else its declared size after crop. Order: those that cover the box, smallest first (already fetched
/// ones before untried ones: likely on disk); then those of unknown size, as given; then the smaller
/// ones, the best available when nothing covers: a fetched one that covers at least half the box first
/// (close enough to skip a download), then largest first.
pub fn order_sources(
    sources: &[ImageSource],
    size: (u32, u32),
    scale: ImageScale,
    record: &VariantRecord,
) -> Vec<ImageSource> {
    if sources.len() < 2 {
        return sources.to_vec();
    }
    let measured: Vec<Option<(u32, u32)>> = sources
        .iter()
        .map(|source| record.fetched.get(&image_cache_key(&source.url)).copied().or_else(|| declared_size(source)))
        .collect();
    let fetched: Vec<bool> =
        sources.iter().map(|source| record.fetched.contains_key(&image_cache_key(&source.url))).collect();
    let (known, unknown): (Vec<usize>, Vec<usize>) = (0..sources.len()).partition(|index| measured[*index].is_some());
    let (mut covering, mut smaller): (Vec<usize>, Vec<usize>) =
        known.into_iter().partition(|index| measured[*index].is_some_and(|measure| covers(measure, size, scale)));
    let half = (size.0.div_ceil(2), size.1.div_ceil(2));
    let width = |index: usize| measured[index].map_or(0, |(width, _)| width);
    covering.sort_by_key(|index| (!fetched[*index], width(*index)));
    smaller.sort_by_key(|index| {
        let close = fetched[*index] && measured[*index].is_some_and(|measure| covers(measure, half, scale));
        (!close, std::cmp::Reverse(width(*index)))
    });
    covering.into_iter().chain(unknown).chain(smaller).map(|index| sources[index].clone()).collect()
}

/// The declared size after crop, or `None` when not declared.
pub fn declared_size(source: &ImageSource) -> Option<(u32, u32)> {
    if source.width == 0 || source.height == 0 {
        return None;
    }
    let height = match source.crop {
        SourceCrop::None => source.height,
        SourceCrop::Letterbox16x9 => source.height.min(source.width * 9 / 16),
    };
    Some((source.width, height))
}

/// An image of `source_size` decoded for `size` needs no upscale.
pub fn covers(source_size: (u32, u32), size: (u32, u32), scale: ImageScale) -> bool {
    let (width, height) = source_size;
    if width == 0 || height == 0 {
        return false;
    }
    match scale {
        ImageScale::Cover => width >= size.0 && height >= size.1,
        ImageScale::Fit => width >= size.0 || height >= size.1,
    }
}
