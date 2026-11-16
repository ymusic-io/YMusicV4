//! The JVM `VariantIndex.kt`: what the loader has learned about one image identity's sources, keyed
//! by [`image_cache_key`](crate::image_cache_key) of each source url: the size it decoded at (after
//! crop; declared sizes can be wrong) and the sources that answered NotFound. Lets a request prefer a
//! variant it already fetched (on disk in the fetcher's cache) and skip known-missing ones across
//! restarts. Blocking: the disk index reads and writes files.

use std::collections::{HashMap, HashSet};
use std::io::{self, Read, Write};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use indexmap::IndexMap;
use ym_common::log::Logger;

use crate::disk_lru::DiskLruCache;
use crate::source_cache::sha256_hex;

/// The JVM `missingTtl` default: a NotFound is trusted for 3 days.
pub const MISSING_TTL: Duration = Duration::from_secs(3 * 24 * 60 * 60);
const LOADED_LIMIT: usize = 2048;

pub trait VariantIndex: Send + Sync {
    fn get(&self, key: &str) -> io::Result<VariantRecord>;

    fn record_fetched(&self, key: &str, source_key: &str, width: u32, height: u32) -> io::Result<()>;

    fn record_missing(&self, key: &str, source_key: &str) -> io::Result<()>;
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct VariantRecord {
    /// sourceKey → measured (width, height).
    pub fetched: HashMap<String, (u32, u32)>,
    /// sourceKeys that answered NotFound recently enough to still trust.
    pub missing: HashSet<String>,
}

#[derive(Default)]
struct Entry {
    fetched: HashMap<String, (u32, u32)>,
    missing: HashMap<String, i64>,
}

/// A process-lifetime [`VariantIndex`]: learns within a session, forgets on restart.
pub struct MemoryVariantIndex {
    missing_ttl: Duration,
    entries: Mutex<HashMap<String, Entry>>,
}

impl MemoryVariantIndex {
    pub fn new(missing_ttl: Duration) -> Self {
        Self { missing_ttl, entries: Mutex::new(HashMap::new()) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Entry>> {
        self.entries.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl Default for MemoryVariantIndex {
    fn default() -> Self {
        Self::new(MISSING_TTL)
    }
}

impl VariantIndex for MemoryVariantIndex {
    fn get(&self, key: &str) -> io::Result<VariantRecord> {
        let entries = self.lock();
        let Some(entry) = entries.get(key) else { return Ok(VariantRecord::default()) };
        let cutoff = ym_common::log::now_millis() - self.missing_ttl.as_millis() as i64;
        Ok(VariantRecord {
            fetched: entry.fetched.clone(),
            missing: entry.missing.iter().filter(|(_, at)| **at >= cutoff).map(|(source, _)| source.clone()).collect(),
        })
    }

    fn record_fetched(&self, key: &str, source_key: &str, width: u32, height: u32) -> io::Result<()> {
        let mut entries = self.lock();
        let entry = entries.entry(key.to_owned()).or_default();
        entry.fetched.insert(source_key.to_owned(), (width, height));
        entry.missing.remove(source_key);
        Ok(())
    }

    fn record_missing(&self, key: &str, source_key: &str) -> io::Result<()> {
        let mut entries = self.lock();
        let entry = entries.entry(key.to_owned()).or_default();
        entry.missing.insert(source_key.to_owned(), ym_common::log::now_millis());
        entry.fetched.remove(source_key);
        Ok(())
    }
}

/// [`VariantIndex`] persisted in a journaled [`DiskLruCache`] under a directory: one small text entry
/// per identity (`ok <sourceKey> <w> <h>` / `miss <sourceKey> <epochMillis>` lines), LRU-bounded, with
/// recently used entries mirrored in memory. The JVM app's `image-variants` format.
pub struct DiskVariantIndex {
    cache: Arc<DiskLruCache>,
    memory: MemoryVariantIndex,
    missing_ttl: Duration,
    loaded: Mutex<IndexMap<String, ()>>,
}

impl DiskVariantIndex {
    /// The JVM default `maxBytes`: 2 MB.
    pub const DEFAULT_MAX_BYTES: u64 = 2 * 1024 * 1024;

    pub fn new(directory: PathBuf, max_bytes: u64, log: Logger, tokio: tokio::runtime::Handle) -> Self {
        Self {
            cache: DiskLruCache::new(directory, 1, max_bytes, log, tokio),
            memory: MemoryVariantIndex::new(MISSING_TTL),
            missing_ttl: MISSING_TTL,
            loaded: Mutex::new(IndexMap::new()),
        }
    }

    fn ensure_loaded(&self, key: &str) -> io::Result<()> {
        if self.loaded.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).contains_key(key) {
            return Ok(());
        }
        let mut text = String::new();
        if let Some(mut snapshot) = self.cache.get(&sha256_hex(key))? {
            snapshot.files[0].read_to_string(&mut text)?;
        }
        let cutoff = ym_common::log::now_millis() - self.missing_ttl.as_millis() as i64;
        for line in text.lines() {
            let parts: Vec<&str> = line.split(' ').collect();
            match parts.as_slice() {
                ["ok", source, width, height] => {
                    let (Ok(width), Ok(height)) = (width.parse(), height.parse()) else { continue };
                    self.memory.record_fetched(key, source, width, height)?;
                }
                ["miss", source, at] => {
                    let Ok(at) = at.parse::<i64>() else { continue };
                    if at >= cutoff {
                        self.memory.record_missing(key, source)?;
                    }
                }
                _ => {}
            }
        }
        let mut loaded = self.loaded.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        loaded.insert(key.to_owned(), ());
        // Bound the "already read from disk" set; a dropped key just re-reads its entry once.
        if loaded.len() > LOADED_LIMIT {
            loaded.shift_remove_index(0);
        }
        Ok(())
    }

    fn persist(&self, key: &str) -> io::Result<()> {
        let record = self.memory.get(key)?;
        let mut text = String::new();
        for (source, (width, height)) in &record.fetched {
            text.push_str(&format!("ok {source} {width} {height}\n"));
        }
        let now = ym_common::log::now_millis();
        for source in &record.missing {
            text.push_str(&format!("miss {source} {now}\n"));
        }
        let Some(mut editor) = self.cache.edit(&sha256_hex(key))? else { return Ok(()) };
        let (_, mut file) = editor.new_file(0)?;
        file.write_all(text.as_bytes())?;
        drop(file);
        editor.commit();
        Ok(())
    }
}

impl VariantIndex for DiskVariantIndex {
    fn get(&self, key: &str) -> io::Result<VariantRecord> {
        self.ensure_loaded(key)?;
        self.memory.get(key)
    }

    fn record_fetched(&self, key: &str, source_key: &str, width: u32, height: u32) -> io::Result<()> {
        self.ensure_loaded(key)?;
        self.memory.record_fetched(key, source_key, width, height)?;
        self.persist(key)
    }

    fn record_missing(&self, key: &str, source_key: &str) -> io::Result<()> {
        self.ensure_loaded(key)?;
        self.memory.record_missing(key, source_key)?;
        self.persist(key)
    }
}
