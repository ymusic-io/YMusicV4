//! The JVM `ScaledImageCache.kt`: already-scaled images on disk, so re-showing a thumbnail skips both
//! the network fetch and the full-size decode. The cache owns the storage codec (lossy WebP); keys are
//! opaque (the loader passes a hash of identity + target size). Blocking.

use std::io::{self, BufReader, BufWriter, Write};
use std::path::PathBuf;
use std::sync::Arc;

use image::RgbaImage;
use ym_common::log::Logger;

use crate::codec::{decode_scaled_reader, encode_webp};
use crate::disk_lru::DiskLruCache;
use crate::request::{ImageLoadError, ImageScale};

pub trait ScaledImageCache: Send + Sync {
    /// The cached image, or `None` on a miss; an error for an undecodable (corrupt) entry.
    fn get(&self, key: &str) -> Result<Option<RgbaImage>, ImageLoadError>;

    fn put(&self, key: &str, image: &RgbaImage) -> io::Result<()>;

    /// The committed entry's file (LRU-refreshed), or `None` on a miss: for consumers that hand bytes
    /// to another process instead of drawing (see `ImageLoader::load_file`).
    fn file_path(&self, _key: &str) -> io::Result<Option<PathBuf>> {
        Ok(None)
    }
}

/// [`ScaledImageCache`] on the journaled [`DiskLruCache`]: true LRU, crash-safe journaled writes,
/// bounded to its byte cap. The JVM app's `image-scaled-cache` format.
pub struct DiskScaledImageCache {
    cache: Arc<DiskLruCache>,
}

impl DiskScaledImageCache {
    /// The JVM default `maxBytes`: 64 MB.
    pub const DEFAULT_MAX_BYTES: u64 = 64 * 1024 * 1024;

    pub fn new(directory: PathBuf, max_bytes: u64, log: Logger, tokio: tokio::runtime::Handle) -> Self {
        Self { cache: DiskLruCache::new(directory, 1, max_bytes, log, tokio) }
    }

    /// Bytes currently accounted by the journal.
    pub fn size_bytes(&self) -> u64 {
        self.cache.current_size()
    }

    pub fn trim_now(&self) -> io::Result<()> {
        self.cache.trim_now()
    }

    pub fn close(&self) {
        self.cache.close();
    }
}

impl ScaledImageCache for DiskScaledImageCache {
    fn get(&self, key: &str) -> Result<Option<RgbaImage>, ImageLoadError> {
        let snapshot = self.cache.get(key).map_err(|error| {
            ImageLoadError::with_cause(crate::Reason::Undecodable, "scaled cache unreadable", error)
        })?;
        let Some(mut snapshot) = snapshot else { return Ok(None) };
        let file = snapshot.files.remove(0);
        // Entries are pre-scaled: MAX/MAX clamps to nothing.
        let decoded = decode_scaled_reader(BufReader::new(file), (u32::MAX, u32::MAX), ImageScale::Fit, key)?;
        Ok(Some(decoded.image))
    }

    fn put(&self, key: &str, image: &RgbaImage) -> io::Result<()> {
        let Some(mut editor) = self.cache.edit(key)? else { return Ok(()) };
        let (_, file) = editor.new_file(0)?;
        let mut writer = BufWriter::new(file);
        encode_webp(image, &mut writer)?;
        writer.flush()?;
        drop(writer);
        editor.commit();
        Ok(())
    }

    fn file_path(&self, key: &str) -> io::Result<Option<PathBuf>> {
        Ok(self.cache.get(key)?.map(|snapshot| snapshot.paths[0].clone()))
    }
}
