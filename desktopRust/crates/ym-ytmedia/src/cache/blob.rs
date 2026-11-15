//! The positional byte store under the player cache: "bytes `[position, position+length)` of
//! stream `key`". Committed bytes live in immutable span files `{root}/{key}/{start}_{len}.span`,
//! indexed by the `blob_span` table (coverage, total size, LRU recency), exactly as the JVM app's
//! `DiskMediaBlobCache` keeps them, so both apps share one cache folder.
//!
//! Concurrency, as media3's `SimpleCache`: a block streams to `{blockStart}__{writerId}.tmp` and is
//! renamed to its span before it gets a row (readers never see a torn write); a region is cut into
//! blocks of at most 5 MB that commit independently (a `publish_on_commit` writer holds them until
//! commit); a known-length region has one writer at a time; a read pins its spans so eviction skips
//! them and an invalidated span's file goes only after the last unpin; coverage is a hint that
//! [`DiskMediaBlobCache::read`] re-plans under the lock; eviction drops least-recently-used unpinned
//! spans until the total fits the budget. Every call is blocking file and SQLite work.

use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{self, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::{Connection, params};
use ym_common::log::Logger;

use super::{CacheError, storage};

const DEFAULT_BUDGET_BYTES: i64 = 512 * 1024 * 1024;
const FREE_SPACE_RESERVE_BYTES: i64 = 100 * 1024 * 1024;
const MAX_SPAN_BYTES: i64 = 5 * 1024 * 1024;
/// Span lists kept in memory: about the current and queued videos.
const SPAN_CACHE_MAX_KEYS: usize = 10;

#[derive(Clone, Debug)]
struct BlobSpan {
    start: i64,
    len: i64,
}

impl BlobSpan {
    fn end(&self) -> i64 {
        self.start + self.len
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
struct SpanId {
    key: String,
    start: i64,
    len: i64,
}

impl SpanId {
    fn new(key: &str, start: i64, len: i64) -> Self {
        Self { key: key.to_owned(), start, len }
    }
}

/// `[skip, skip+take)` of one span.
#[derive(Clone, Debug)]
struct Chunk {
    span: SpanId,
    skip: i64,
    take: i64,
}

fn span_path(root: &Path, key: &str, start: i64, len: i64) -> PathBuf {
    root.join(key).join(format!("{start}_{len}.span"))
}

/// The `blob_span` queries (`BlobSpan.sq`).
struct BlobIndex {
    connection: Connection,
}

impl BlobIndex {
    /// Sorted by start: the cover sweep depends on it.
    fn spans_for_key(&self, key: &str) -> rusqlite::Result<Vec<BlobSpan>> {
        self.connection
            .prepare_cached("SELECT start, len FROM blob_span WHERE key = ? ORDER BY start")?
            .query_map([key], |row| Ok(BlobSpan { start: row.get(0)?, len: row.get(1)? }))?
            .collect()
    }

    fn insert(&self, span: &SpanId) -> rusqlite::Result<()> {
        self.connection
            .prepare_cached("INSERT OR IGNORE INTO blob_span(key, start, len, last_used) VALUES (?, ?, ?, ?)")?
            .execute(params![span.key, span.start, span.len, ym_common::log::now_millis()])?;
        Ok(())
    }

    fn touch(&self, span: &SpanId) -> rusqlite::Result<()> {
        self.connection
            .prepare_cached("UPDATE blob_span SET last_used = ? WHERE key = ? AND start = ? AND len = ?")?
            .execute(params![ym_common::log::now_millis(), span.key, span.start, span.len])?;
        Ok(())
    }

    fn delete(&self, span: &SpanId) -> rusqlite::Result<()> {
        self.connection
            .prepare_cached("DELETE FROM blob_span WHERE key = ? AND start = ? AND len = ?")?
            .execute(params![span.key, span.start, span.len])?;
        Ok(())
    }

    fn total_size(&self) -> rusqlite::Result<i64> {
        self.connection.query_row("SELECT coalesce(sum(len), 0) FROM blob_span", [], |row| row.get(0))
    }

    fn distinct_keys(&self) -> rusqlite::Result<HashSet<String>> {
        self.connection.prepare_cached("SELECT DISTINCT key FROM blob_span")?.query_map([], |row| row.get(0))?.collect()
    }

    /// Least recently used first: eviction order.
    fn lru_order(&self) -> rusqlite::Result<Vec<SpanId>> {
        self.connection
            .prepare_cached("SELECT key, start, len FROM blob_span ORDER BY last_used ASC")?
            .query_map([], |row| Ok(SpanId { key: row.get(0)?, start: row.get(1)?, len: row.get(2)? }))?
            .collect()
    }
}

struct State {
    index: BlobIndex,
    budget: i64,
    pins: HashMap<SpanId, u32>,
    /// Invalidated spans still pinned: their files go on the last unpin.
    doomed: HashSet<SpanId>,
    /// A new generation on every commit of a span, for [`DiskMediaBlobCache::coverage_id`].
    generations: HashMap<SpanId, u64>,
    last_generation: u64,
    /// Unindexed span files whose delete runs outside the lock.
    retired: HashSet<PathBuf>,
    /// Known-length regions being written.
    in_flight: HashSet<SpanId>,
    writer_id: u64,
    /// Most recently used last; each list sorted by start and kept in sync with the index.
    span_cache: Vec<(String, Vec<BlobSpan>)>,
}

impl State {
    /// The key's spans, loaded on a miss and each checked to hold exactly its length on disk: a row
    /// whose file is gone, or a torn span (a power loss can keep the rename without the bytes),
    /// is dropped as it loads.
    fn spans(&mut self, root: &Path, log: &Logger, key: &str) -> Result<&[BlobSpan], CacheError> {
        if let Some(position) = self.span_cache.iter().position(|(cached, _)| cached == key) {
            let entry = self.span_cache.remove(position);
            self.span_cache.push(entry);
        } else {
            let mut loaded = Vec::new();
            for span in self.index.spans_for_key(key)? {
                let path = span_path(root, key, span.start, span.len);
                let size = match fs::metadata(&path) {
                    Ok(metadata) => Some(metadata.len() as i64),
                    Err(error) if error.kind() == io::ErrorKind::NotFound => None,
                    Err(error) => return Err(storage("span stat")(error)),
                };
                if size == Some(span.len) {
                    loaded.push(span);
                    continue;
                }
                let id = SpanId::new(key, span.start, span.len);
                self.index.delete(&id)?;
                self.generations.remove(&id);
                if let Some(size) = size {
                    log.warn(&format!("span {}_{} holds {size} bytes, dropped", span.start, span.len), None);
                    remove_file_logged(&path, log);
                }
            }
            self.span_cache.push((key.to_owned(), loaded));
            while self.span_cache.len() > SPAN_CACHE_MAX_KEYS {
                self.span_cache.remove(0);
            }
        }
        Ok(&self.span_cache.last().expect("just placed").1)
    }

    fn cover(
        &mut self,
        root: &Path,
        log: &Logger,
        key: &str,
        position: i64,
        length: i64,
    ) -> Result<Option<Vec<Chunk>>, CacheError> {
        Ok(cover(key, self.spans(root, log, key)?, position, length))
    }

    fn add_span_to_cache(&mut self, span: &SpanId) {
        let Some((_, list)) = self.span_cache.iter_mut().find(|(key, _)| *key == span.key) else { return };
        if list.iter().any(|cached| cached.start == span.start && cached.len == span.len) {
            return;
        }
        let at = list.iter().position(|cached| cached.start > span.start).unwrap_or(list.len());
        list.insert(at, BlobSpan { start: span.start, len: span.len });
    }

    fn remove_span_from_cache(&mut self, span: &SpanId) {
        if let Some((_, list)) = self.span_cache.iter_mut().find(|(key, _)| *key == span.key) {
            list.retain(|cached| !(cached.start == span.start && cached.len == span.len));
        }
        self.generations.remove(span);
    }

    fn pin(&mut self, span: &SpanId) {
        *self.pins.entry(span.clone()).or_insert(0) += 1;
    }

    fn is_pinned(&self, span: &SpanId) -> bool {
        self.pins.get(span).is_some_and(|count| *count > 0)
    }

    /// The span's file when that was the last pin of an invalidated span, to delete outside the lock.
    fn unpin(&mut self, root: &Path, span: &SpanId) -> Option<PathBuf> {
        let count = self.pins.get_mut(span)?;
        if *count > 1 {
            *count -= 1;
            return None;
        }
        self.pins.remove(span);
        self.doomed.remove(span).then(|| self.retire(span_path(root, &span.key, span.start, span.len)))
    }

    fn retire(&mut self, path: PathBuf) -> PathBuf {
        self.retired.insert(path.clone());
        path
    }

    /// Unindexes unpinned LRU spans until `total + incoming` fits the budget; returns their files.
    fn plan_eviction(&mut self, root: &Path, incoming: i64) -> Result<Vec<PathBuf>, CacheError> {
        let mut total = self.index.total_size()?;
        if total + incoming <= self.budget {
            return Ok(Vec::new());
        }
        let mut victims = Vec::new();
        for span in self.index.lru_order()? {
            if total + incoming <= self.budget {
                break;
            }
            if self.is_pinned(&span) {
                continue;
            }
            self.index.delete(&span)?;
            self.remove_span_from_cache(&span);
            total -= span.len;
            victims.push(self.retire(span_path(root, &span.key, span.start, span.len)));
        }
        Ok(victims)
    }
}

/// Chunks exactly covering `[position, position+length)`, or `None` on a gap. One greedy sweep over
/// spans sorted by start: at each cursor the reachable span reaching furthest wins; overlap is
/// fine (the bytes are identical, a format stream being contiguous).
fn cover(key: &str, spans: &[BlobSpan], position: i64, length: i64) -> Option<Vec<Chunk>> {
    if length <= 0 {
        return Some(Vec::new());
    }
    let end = position + length;
    let mut cursor = position;
    let mut index = 0;
    let mut chunks = Vec::new();
    while cursor < end {
        let mut best: Option<&BlobSpan> = None;
        let mut best_end = cursor;
        while index < spans.len() && spans[index].start <= cursor {
            if spans[index].end() > best_end {
                best_end = spans[index].end();
                best = Some(&spans[index]);
            }
            index += 1;
        }
        let best = best?;
        let take = (best_end - cursor).min(end - cursor);
        chunks.push(Chunk { span: SpanId::new(key, best.start, best.len), skip: cursor - best.start, take });
        cursor += take;
    }
    Some(chunks)
}

fn remove_file_logged(path: &Path, log: &Logger) {
    match fs::remove_file(path) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => log.warn(&format!("cache file {} not deleted", path.display()), Some(&error)),
    }
}

pub struct DiskMediaBlobCache {
    root: PathBuf,
    log: Logger,
    state: Mutex<State>,
}

impl DiskMediaBlobCache {
    /// `database` is the cache's `cache-index.db`, already holding the `SegmentDatabase` schema or
    /// created with it.
    pub fn open(root: PathBuf, database: &Path, log: Logger) -> Result<Arc<Self>, CacheError> {
        fs::create_dir_all(&root).map_err(storage("cache folder"))?;
        let index = BlobIndex { connection: crate::segment_index::open_database(database)? };
        Ok(Arc::new(Self {
            root,
            log,
            state: Mutex::new(State {
                index,
                budget: DEFAULT_BUDGET_BYTES,
                pins: HashMap::new(),
                doomed: HashSet::new(),
                generations: HashMap::new(),
                last_generation: 0,
                retired: HashSet::new(),
                in_flight: HashSet::new(),
                writer_id: 0,
                span_cache: Vec::new(),
            }),
        }))
    }

    fn state(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Whether committed spans cover the range right now: a planning hint, never a lock.
    pub fn is_cached(&self, key: &str, position: i64, length: i64) -> Result<bool, CacheError> {
        Ok(self.state().cover(&self.root, &self.log, key, position, length)?.is_some())
    }

    /// As media3's `getCachedLength`: a positive `n` means `[position, position+n)` is cached; a
    /// negative `-m` means `position` starts a hole of `m` bytes; 0 only for an empty range.
    pub fn cached_length(&self, key: &str, position: i64, length: i64) -> Result<i64, CacheError> {
        if length <= 0 {
            return Ok(0);
        }
        let mut state = self.state();
        let spans = state.spans(&self.root, &self.log, key)?;
        let end = position + length;
        let mut cursor = position;
        let mut index = 0;
        while cursor < end {
            let mut best_end = cursor;
            while index < spans.len() && spans[index].start <= cursor {
                best_end = best_end.max(spans[index].end());
                index += 1;
            }
            if best_end <= cursor {
                break;
            }
            cursor = best_end;
        }
        if cursor > position {
            return Ok(cursor.min(end) - position);
        }
        let next_start = spans.iter().find(|span| span.start > position).map_or(end, |span| span.start);
        Ok(-(next_start.min(end) - position))
    }

    /// Pins the spans covering the range and returns a reader over exactly those bytes, or `None`
    /// when the range is not fully covered now (including a span file deleted behind the index).
    /// The spans stay unevictable until the reader drops.
    pub fn read(self: &Arc<Self>, key: &str, position: i64, length: i64) -> Result<Option<PinnedSource>, CacheError> {
        let chunks = {
            let mut state = self.state();
            let Some(plan) = state.cover(&self.root, &self.log, key, position, length)? else { return Ok(None) };
            for chunk in &plan {
                state.index.touch(&chunk.span)?;
            }
            for chunk in &plan {
                state.pin(&chunk.span);
            }
            plan
        };
        // Every span opens up front: a missing file is a miss here rather than a failure mid-stream,
        // and an open file keeps its bytes readable even if it is deleted meanwhile.
        let mut files = Vec::with_capacity(chunks.len());
        for chunk in &chunks {
            let span = &chunk.span;
            match File::open(span_path(&self.root, &span.key, span.start, span.len)) {
                Ok(file) => files.push(Some(file)),
                Err(error) => {
                    drop(files);
                    let missing = error.kind() == io::ErrorKind::NotFound;
                    let (released, dropped) = {
                        let mut state = self.state();
                        let released: Vec<PathBuf> =
                            chunks.iter().filter_map(|chunk| state.unpin(&self.root, &chunk.span)).collect();
                        let dropped = if missing {
                            // The key's next load re-checks every row against the disk.
                            state.span_cache.retain(|(cached, _)| *cached != span.key);
                            state.generations.remove(span);
                            state.index.delete(span)
                        } else {
                            Ok(())
                        };
                        (released, dropped)
                    };
                    self.delete_retired(released);
                    dropped?;
                    if !missing {
                        return Err(storage("span open")(error));
                    }
                    self.log.warn("span file missing, cache miss", Some(&error));
                    return Ok(None);
                }
            }
        }
        Ok(Some(PinnedSource { cache: self.clone(), chunks, files, next: 0, current: None, remaining: 0 }))
    }

    /// An id of the spans covering the range now, or `None` when it is not covered. It changes
    /// whenever one of them is dropped or committed again.
    pub fn coverage_id(&self, key: &str, position: i64, length: i64) -> Result<Option<String>, CacheError> {
        let mut state = self.state();
        let Some(plan) = state.cover(&self.root, &self.log, key, position, length)? else { return Ok(None) };
        let mut ids = Vec::with_capacity(plan.len());
        for chunk in plan {
            let generation = match state.generations.get(&chunk.span) {
                Some(generation) => *generation,
                None => {
                    state.last_generation += 1;
                    let generation = state.last_generation;
                    state.generations.insert(chunk.span, generation);
                    generation
                }
            };
            ids.push(generation.to_string());
        }
        Ok(Some(ids.join(",")))
    }

    /// A writer for bytes from `position`. A non-negative `length` names the exact region: a no-op
    /// writer comes back when it is already committed or being written. A negative one is a stream
    /// of unknown length (no region dedupe). With `publish_on_commit` nothing is readable before
    /// [`BlobWriter::commit`] and an abort leaves nothing behind.
    pub fn writer(
        self: &Arc<Self>,
        key: &str,
        position: i64,
        length: i64,
        publish_on_commit: bool,
    ) -> Result<BlobWriter, CacheError> {
        let region = (length >= 0).then(|| SpanId::new(key, position, length));
        let id = {
            let mut state = self.state();
            if let Some(region) = &region {
                if state.cover(&self.root, &self.log, key, position, length)?.is_some()
                    || state.in_flight.contains(region)
                {
                    return Ok(BlobWriter { real: None });
                }
                state.in_flight.insert(region.clone());
            }
            state.writer_id += 1;
            state.writer_id
        };
        Ok(BlobWriter {
            real: Some(RealWriter {
                cache: self.clone(),
                key: key.to_owned(),
                dir: self.root.join(key),
                region,
                id,
                publish_on_commit,
                block_start: position,
                block_bytes: 0,
                temp: None,
                sealed: Vec::new(),
            }),
        })
    }

    /// Drops every span overlapping the range (bytes found corrupt): it is a miss at once, and a
    /// reader holding one keeps reading it until it drops.
    pub fn invalidate(&self, key: &str, position: i64, length: i64) -> Result<(), CacheError> {
        let end = position + length;
        let files = {
            let mut state = self.state();
            let overlapping: Vec<SpanId> = state
                .spans(&self.root, &self.log, key)?
                .iter()
                .filter(|span| span.start < end && span.end() > position)
                .map(|span| SpanId::new(key, span.start, span.len))
                .collect();
            let mut files = Vec::new();
            for span in overlapping {
                state.index.delete(&span)?;
                state.remove_span_from_cache(&span);
                if state.is_pinned(&span) {
                    state.doomed.insert(span);
                } else {
                    files.push(state.retire(span_path(&self.root, key, span.start, span.len)));
                }
            }
            files
        };
        self.delete_retired(files);
        Ok(())
    }

    /// Every stream key with a committed span.
    pub fn keys(&self) -> Result<HashSet<String>, CacheError> {
        Ok(self.state().index.distinct_keys()?)
    }

    /// The bytes of all committed spans, which the budget is measured against.
    pub fn total_size(&self) -> Result<i64, CacheError> {
        Ok(self.state().index.total_size()?)
    }

    /// The LRU budget in bytes; 0 turns caching off.
    pub fn max_size(&self) -> i64 {
        self.state().budget
    }

    /// Sets the budget, capped so the cache never takes the volume's last 100 MB, and evicts down
    /// to it now.
    pub fn set_max_size(&self, bytes: i64) -> Result<(), CacheError> {
        let capped = match usable_space(&self.root) {
            Some(free) => bytes.min(free + self.total_size()? - FREE_SPACE_RESERVE_BYTES),
            None => bytes,
        };
        let victims = {
            let mut state = self.state();
            state.budget = capped.max(0);
            state.plan_eviction(&self.root, 0)?
        };
        self.delete_retired(victims);
        Ok(())
    }

    fn delete_retired(&self, paths: Vec<PathBuf>) {
        if paths.is_empty() {
            return;
        }
        for path in &paths {
            remove_file_logged(path, &self.log);
        }
        let mut state = self.state();
        for path in &paths {
            state.retired.remove(path);
        }
    }
}

/// Discards bytes when the region was already committed or is being written elsewhere. Dropping
/// a writer without [`Self::commit`] aborts it.
pub struct BlobWriter {
    real: Option<RealWriter>,
}

impl BlobWriter {
    /// False for the no-op writer.
    pub fn stores_bytes(&self) -> bool {
        self.real.is_some()
    }

    pub fn write(&mut self, bytes: &[u8]) -> Result<(), CacheError> {
        match &mut self.real {
            Some(writer) => writer.write(bytes),
            None => Ok(()),
        }
    }

    /// Commits the trailing block (and a `publish_on_commit` writer's held ones) and releases the
    /// region. Blocks committed mid-stream stay either way.
    pub fn commit(mut self) -> Result<(), CacheError> {
        match self.real.take() {
            Some(writer) => writer.finish(true),
            None => Ok(()),
        }
    }

    /// Drops the uncommitted temp block (and every block a `publish_on_commit` writer held) and
    /// releases the region; a failing cleanup is logged.
    pub fn abort(mut self) {
        self.abort_now();
    }

    fn abort_now(&mut self) {
        if let Some(writer) = self.real.take() {
            let log = writer.cache.log.clone();
            if let Err(error) = writer.finish(false) {
                log.warn("cache writer abort failed", Some(&error));
            }
        }
    }
}

impl Drop for BlobWriter {
    fn drop(&mut self) {
        self.abort_now();
    }
}

struct SealedBlock {
    temp: PathBuf,
    start: i64,
    len: i64,
}

enum Existing {
    Committed,
    BeingDeleted,
    None,
}

struct RealWriter {
    cache: Arc<DiskMediaBlobCache>,
    key: String,
    dir: PathBuf,
    region: Option<SpanId>,
    /// Keeps temp names unique when two writers' blocks start at one offset.
    id: u64,
    publish_on_commit: bool,
    block_start: i64,
    block_bytes: i64,
    temp: Option<(PathBuf, BufWriter<File>)>,
    sealed: Vec<SealedBlock>,
}

impl RealWriter {
    fn write(&mut self, mut bytes: &[u8]) -> Result<(), CacheError> {
        while !bytes.is_empty() {
            if self.temp.is_none() {
                self.open_block()?;
            }
            let take = ((MAX_SPAN_BYTES - self.block_bytes) as usize).min(bytes.len());
            let (_, file) = self.temp.as_mut().expect("opened above");
            file.write_all(&bytes[..take]).map_err(storage("span write"))?;
            self.block_bytes += take as i64;
            bytes = &bytes[take..];
            if self.block_bytes >= MAX_SPAN_BYTES {
                self.seal_block()?;
            }
        }
        Ok(())
    }

    fn open_block(&mut self) -> Result<(), CacheError> {
        fs::create_dir_all(&self.dir).map_err(storage("span open"))?;
        let path = self.dir.join(format!("{}__{}.tmp", self.block_start, self.id));
        let file = File::create(&path).map_err(storage("span open"))?;
        self.temp = Some((path, BufWriter::with_capacity(64 * 1024, file)));
        Ok(())
    }

    /// Closes the current temp as a block, published now or held for commit.
    fn seal_block(&mut self) -> Result<(), CacheError> {
        let Some((path, file)) = self.temp.take() else { return Ok(()) };
        if let Err(error) = file.into_inner() {
            remove_file_logged(&path, &self.cache.log);
            return Err(storage("span commit")(error.into_error()));
        }
        let len = std::mem::take(&mut self.block_bytes);
        let start = self.block_start;
        self.block_start += len;
        if len == 0 {
            remove_file_logged(&path, &self.cache.log);
            return Ok(());
        }
        let block = SealedBlock { temp: path, start, len };
        if self.publish_on_commit {
            self.sealed.push(block);
            Ok(())
        } else {
            self.publish(block)
        }
    }

    /// Renames a sealed block to its span and indexes it.
    fn publish(&self, block: SealedBlock) -> Result<(), CacheError> {
        let cache = &self.cache;
        let span = SpanId::new(&self.key, block.start, block.len);
        let span_file = self.dir.join(format!("{}_{}.span", block.start, block.len));
        // An invalidated span a reader still holds: un-doomed so its last unpin keeps the refill.
        let replaces_corrupt = cache.state().doomed.remove(&span);
        if replaces_corrupt {
            if let Err(error) = fs::rename(&block.temp, &span_file) {
                // Windows does not replace a file that is open: stay uncached, the old file goes
                // on the last unpin.
                remove_file_logged(&block.temp, &cache.log);
                let orphan = {
                    let mut state = cache.state();
                    if state.is_pinned(&span) {
                        state.doomed.insert(span);
                        None
                    } else {
                        Some(state.retire(span_file))
                    }
                };
                cache.delete_retired(orphan.into_iter().collect());
                cache.log.info(&format!("refill of an invalidated span not stored, a reader holds it: {error}"));
                return Ok(());
            }
        } else {
            // Decided under the lock, not by the file's presence: a purged span's file stays until
            // its deferred delete, and indexing it again would serve the corrupt bytes.
            let existing = {
                let mut state = cache.state();
                if state
                    .spans(&cache.root, &cache.log, &self.key)?
                    .iter()
                    .any(|cached| cached.start == block.start && cached.len == block.len)
                {
                    Existing::Committed
                } else if state.retired.contains(&span_file) {
                    Existing::BeingDeleted
                } else {
                    Existing::None
                }
            };
            match existing {
                Existing::Committed => {
                    remove_file_logged(&block.temp, &cache.log);
                    return Ok(());
                }
                Existing::BeingDeleted => {
                    remove_file_logged(&block.temp, &cache.log);
                    cache.log.info(&format!(
                        "refill of a span being deleted not stored ({} {}_{})",
                        self.key, block.start, block.len
                    ));
                    return Ok(());
                }
                Existing::None => fs::rename(&block.temp, &span_file).map_err(storage("span commit"))?,
            }
        }
        let victims = {
            let mut state = cache.state();
            state.index.insert(&span)?;
            state.add_span_to_cache(&span);
            state.last_generation += 1;
            let generation = state.last_generation;
            state.generations.insert(span, generation);
            state.plan_eviction(&cache.root, 0)?
        };
        cache.log.debug(|| format!("commit span {} {}_{} writer={}", self.key, block.start, block.len, self.id));
        cache.delete_retired(victims);
        Ok(())
    }

    fn commit_blocks(&mut self) -> Result<(), CacheError> {
        self.seal_block()?;
        while !self.sealed.is_empty() {
            let block = self.sealed.remove(0);
            self.publish(block)?;
        }
        Ok(())
    }

    fn finish(mut self, commit: bool) -> Result<(), CacheError> {
        let result = if commit { self.commit_blocks() } else { Ok(()) };
        if let Some((path, file)) = self.temp.take() {
            drop(file);
            remove_file_logged(&path, &self.cache.log);
        }
        for block in self.sealed.drain(..) {
            remove_file_logged(&block.temp, &self.cache.log);
        }
        // Released even when the commit failed, or the region would stay unwritable.
        let cache = self.cache.clone();
        let victims = {
            let mut state = cache.state();
            if let Some(region) = &self.region {
                state.in_flight.remove(region);
            }
            state.plan_eviction(&cache.root, 0)
        };
        match victims {
            Ok(victims) => cache.delete_retired(victims),
            Err(error) if result.is_ok() => return Err(error),
            Err(error) => cache.log.warn("eviction after a failed commit failed", Some(&error)),
        }
        result
    }
}

/// A read over pinned, immutable spans; dropping it unpins them. Sequential reads close each
/// span's file as they pass it.
pub struct PinnedSource {
    cache: Arc<DiskMediaBlobCache>,
    chunks: Vec<Chunk>,
    files: Vec<Option<File>>,
    next: usize,
    current: Option<usize>,
    remaining: i64,
}

impl PinnedSource {
    /// Up to `buf.len()` bytes at `position` (relative to the first byte); 0 at the end. For a
    /// source not read sequentially.
    pub fn read_at(&mut self, position: i64, buf: &mut [u8]) -> io::Result<usize> {
        let mut chunk_start = 0;
        for (index, chunk) in self.chunks.iter().enumerate() {
            if position < chunk_start + chunk.take {
                let within = position - chunk_start;
                let want = (buf.len() as i64).min(chunk.take - within) as usize;
                let file = self.files[index].as_mut().ok_or_else(|| io::Error::other("span read past"))?;
                file.seek(SeekFrom::Start((chunk.skip + within) as u64))?;
                return file.read(&mut buf[..want]);
            }
            chunk_start += chunk.take;
        }
        Ok(0)
    }
}

impl Read for PinnedSource {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        loop {
            if let Some(index) = self.current {
                if self.remaining > 0 {
                    let want = (buf.len() as i64).min(self.remaining) as usize;
                    let file = self.files[index].as_mut().ok_or_else(|| io::Error::other("span closed"))?;
                    let read = file.read(&mut buf[..want])?;
                    if read == 0 {
                        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "span file ended early"));
                    }
                    self.remaining -= read as i64;
                    return Ok(read);
                }
                self.files[index] = None;
                self.current = None;
            }
            if self.next >= self.chunks.len() {
                return Ok(0);
            }
            let index = self.next;
            self.next += 1;
            let skip = self.chunks[index].skip;
            let file = self.files[index].as_mut().ok_or_else(|| io::Error::other("span closed"))?;
            file.seek(SeekFrom::Start(skip as u64))?;
            self.current = Some(index);
            self.remaining = self.chunks[index].take;
        }
    }
}

impl Drop for PinnedSource {
    fn drop(&mut self) {
        self.files.clear();
        let released: Vec<PathBuf> = {
            let mut state = self.cache.state();
            self.chunks.iter().filter_map(|chunk| state.unpin(&self.cache.root, &chunk.span)).collect()
        };
        self.cache.delete_retired(released);
    }
}

/// Free bytes on the volume holding `dir`, or `None` when the platform cannot tell.
#[cfg(unix)]
fn usable_space(dir: &Path) -> Option<i64> {
    use std::os::unix::ffi::OsStrExt;
    let path = std::ffi::CString::new(dir.as_os_str().as_bytes()).ok()?;
    // SAFETY: `statvfs` is plain data; zeroed is a valid value for the out-parameter.
    let mut stat: libc::statvfs = unsafe { std::mem::zeroed() };
    // SAFETY: `path` is NUL-terminated and `stat` is a valid, exclusive out-pointer.
    if unsafe { libc::statvfs(path.as_ptr(), &mut stat) } != 0 {
        return None;
    }
    #[allow(clippy::unnecessary_cast)]
    let free = (stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64);
    Some(free.min(i64::MAX as u64) as i64)
}

#[cfg(windows)]
fn usable_space(dir: &Path) -> Option<i64> {
    use std::os::windows::ffi::OsStrExt;
    let wide: Vec<u16> = dir.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut available = 0u64;
    // SAFETY: `wide` is NUL-terminated; the out-pointers are valid or null.
    let ok = unsafe {
        windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW(
            wide.as_ptr(),
            &mut available,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        )
    };
    (ok != 0).then(|| available.min(i64::MAX as u64) as i64)
}
