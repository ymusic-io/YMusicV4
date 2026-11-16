//! The JVM `ImageSourceCache.kt`: compressed originals (the Coil/Glide model). A body streams to disk,
//! the heap holds one chunk and never the image, and decoding happens from the file. Keyed by source
//! identity only (no size), so every size bucket shares one copy.

use std::io::{self, Read, Write};
use std::path::PathBuf;
use std::sync::Arc;

use bytes::Bytes;
use futures::{Stream, StreamExt};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use ym_common::log::Logger;

use crate::disk_lru::DiskLruCache;
use crate::request::{ImageLoadError, Reason};

const SEGMENT: usize = 64 * 1024;

pub struct ImageSourceCache {
    cache: Arc<DiskLruCache>,
}

impl ImageSourceCache {
    /// The JVM default `maxBytes`: 128 MB.
    pub const DEFAULT_MAX_BYTES: u64 = 128 * 1024 * 1024;

    pub fn new(directory: PathBuf, max_bytes: u64, log: Logger, tokio: tokio::runtime::Handle) -> Self {
        Self { cache: DiskLruCache::new(directory, 1, max_bytes, log, tokio) }
    }

    /// Blocking: the committed entry's file (LRU-refreshed), or `None` on a miss. `key` is a
    /// [`source_cache_key`].
    pub fn file_path(&self, key: &str) -> io::Result<Option<PathBuf>> {
        Ok(self.cache.get(key)?.map(|snapshot| snapshot.paths[0].clone()))
    }

    /// Streams `body` into the entry and returns the committed file. The byte cap is enforced while
    /// streaming (Content-Length can lie or be absent); whether the bytes are an image is the decoder's
    /// verdict. Nothing is committed on failure. I/O failures, the body's included, are `Network`.
    pub async fn write<E>(
        &self,
        key: &str,
        mut body: impl Stream<Item = Result<Bytes, E>> + Unpin,
        max_image_bytes: u64,
        url: &str,
    ) -> Result<PathBuf, ImageLoadError>
    where
        E: std::error::Error + Send + Sync + 'static,
    {
        let failed = |error: io::Error| {
            ImageLoadError::with_cause(Reason::Network, format!("image download failed: {url}"), error)
        };
        let cache = self.cache.clone();
        let owned_key = key.to_owned();
        let mut editor = blocking(move || cache.edit(&owned_key))
            .await
            .map_err(failed)?
            .ok_or_else(|| failed(io::Error::other(format!("image source cache uneditable: {key}"))))?;
        let (_, file) = editor.new_file(0).map_err(failed)?;
        let mut file = tokio::fs::File::from_std(file);
        let mut total = 0u64;
        while let Some(chunk) = body.next().await {
            let chunk = chunk.map_err(|error| {
                ImageLoadError::with_cause(Reason::Network, format!("image download failed: {url}"), error)
            })?;
            total += chunk.len() as u64;
            if total > max_image_bytes {
                return Err(ImageLoadError::new(
                    Reason::Undecodable,
                    format!("image exceeds the {max_image_bytes}-byte cap: {url}"),
                ));
            }
            file.write_all(&chunk).await.map_err(failed)?;
        }
        file.flush().await.map_err(failed)?;
        drop(file);
        self.commit(editor, key).await.map_err(failed)
    }

    /// Blocking: [`ImageSourceCache::write`] for a blocking `body` (an extracted cover).
    pub fn write_blocking(
        &self,
        key: &str,
        mut body: impl Read,
        max_image_bytes: u64,
        url: &str,
    ) -> Result<PathBuf, ImageLoadError> {
        let failed = |error: io::Error| {
            ImageLoadError::with_cause(Reason::Network, format!("image source write failed: {url}"), error)
        };
        let mut editor = self
            .cache
            .edit(key)
            .map_err(failed)?
            .ok_or_else(|| failed(io::Error::other(format!("image source cache uneditable: {key}"))))?;
        let (_, mut file) = editor.new_file(0).map_err(failed)?;
        let mut buffer = vec![0u8; SEGMENT];
        let mut total = 0u64;
        loop {
            let read = body.read(&mut buffer).map_err(failed)?;
            if read == 0 {
                break;
            }
            total += read as u64;
            if total > max_image_bytes {
                return Err(ImageLoadError::new(
                    Reason::Undecodable,
                    format!("image exceeds the {max_image_bytes}-byte cap: {url}"),
                ));
            }
            file.write_all(&buffer[..read]).map_err(failed)?;
        }
        drop(file);
        editor.commit();
        self.file_path(key)
            .map_err(failed)?
            .ok_or_else(|| failed(io::Error::other(format!("image source entry vanished after commit: {key}"))))
    }

    async fn commit(&self, editor: crate::disk_lru::Editor, key: &str) -> io::Result<PathBuf> {
        let cache = self.cache.clone();
        let key = key.to_owned();
        blocking(move || {
            editor.commit();
            cache
                .get(&key)?
                .map(|snapshot| snapshot.paths[0].clone())
                .ok_or_else(|| io::Error::other(format!("image source entry vanished after commit: {key}")))
        })
        .await
    }

    /// Blocking: evict down to the budget now instead of waiting for the throttled background cleanup.
    pub fn trim_now(&self) -> io::Result<()> {
        self.cache.trim_now()
    }

    pub fn close(&self) {
        self.cache.close();
    }
}

/// The disk key for a source identity (any string: a url key, or an app's own fingerprint).
pub fn source_cache_key(identity: &str) -> String {
    sha256_hex(identity)
}

pub(crate) fn sha256_hex(text: &str) -> String {
    Sha256::digest(text.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Runs blocking cache work on tokio's blocking pool; a panicked task is an I/O error.
pub(crate) async fn blocking<T: Send + 'static>(
    work: impl FnOnce() -> io::Result<T> + Send + 'static,
) -> io::Result<T> {
    tokio::task::spawn_blocking(work).await.map_err(io::Error::other)?
}
