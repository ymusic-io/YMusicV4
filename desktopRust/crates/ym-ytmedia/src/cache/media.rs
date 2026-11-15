//! The segment layer over the blob store: one stored copy of a segment's bytes answers any later
//! SABR request for it. Segment rows are a queryable hint; the blobs are the truth.

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

use ym_common::log::Logger;

use super::blob::{BlobWriter, DiskMediaBlobCache};
use super::check::{SegmentCheckFailure, StreamingSegmentCheck, check_stored_segment};
use super::{CacheError, storage};
use crate::sabr::messages::MediaHeader;
use crate::segment_index::SegmentIndex;

/// Segments whose stored bytes passed the check, by coverage id; walked again only once it changes.
const INTACT_MAX: usize = 4096;
pub const CACHE_FOLDER: &str = "player-cache";
pub const INDEX_FILE: &str = "cache-index.db";

pub struct MediaCache {
    pub blobs: Arc<DiskMediaBlobCache>,
    pub index: Arc<SegmentIndex>,
    hit_bytes: AtomicI64,
    intact: Mutex<VecDeque<(String, String)>>,
    log: Logger,
}

impl MediaCache {
    /// The JVM app's player cache: `{data_dir}/player-cache` with `cache-index.db` holding both the
    /// blob spans and the segment rows. Blocking.
    pub fn open(data_dir: &std::path::Path, log: Logger) -> Result<Self, CacheError> {
        let root: PathBuf = data_dir.join(CACHE_FOLDER);
        std::fs::create_dir_all(&root).map_err(storage("cache folder"))?;
        let database = root.join(INDEX_FILE);
        let index = Arc::new(SegmentIndex::open(&database)?);
        let blobs = DiskMediaBlobCache::open(root, &database, log.clone())?;
        let hit_bytes = AtomicI64::new(index.hit_bytes()?);
        Ok(Self { blobs, index, hit_bytes, intact: Mutex::new(VecDeque::new()), log })
    }

    /// Lifetime bytes served from cache hits.
    pub fn hit_bytes(&self) -> i64 {
        self.hit_bytes.load(Ordering::Relaxed)
    }

    pub fn add_hit_bytes(&self, bytes: i64) -> Result<(), CacheError> {
        if bytes <= 0 {
            return Ok(());
        }
        self.index.add_hit_bytes(bytes)?;
        self.hit_bytes.store(self.index.hit_bytes()?, Ordering::Relaxed);
        Ok(())
    }

    /// Starts mirroring one segment. Its row is saved now (on the MEDIA_HEADER, a hint) and its
    /// bytes commit on [`SegmentFill::commit`] (the MEDIA_END, the truth): a crash in between leaves
    /// a row that `cleanup_orphans` prunes. Only the bytes not cached yet are written.
    pub fn open_segment_fill(&self, header: &MediaHeader) -> Result<SegmentFill, CacheError> {
        self.index.save(header)?;
        let key = header.cache_key();
        let (start, length) = (header.start_range, header.content_length);
        let mut gaps = Vec::new();
        let mut position = start;
        let end = start + length;
        while position < end {
            let cached = self.blobs.cached_length(&key, position, end - position)?;
            if cached > 0 {
                position += cached;
                continue;
            }
            if cached == 0 {
                break;
            }
            let hole = -cached;
            gaps.push(GapWriter {
                start: position,
                end: position + hole,
                writer: self.blobs.writer(&key, position, hole, true)?,
            });
            position += hole;
        }
        Ok(SegmentFill {
            index: self.index.clone(),
            row: (header.itag, header.lmt, header.segment_index),
            check: StreamingSegmentCheck::new(length, header.is_init_segment),
            gaps,
            position: start,
            gap: 0,
        })
    }

    /// Whether the segment's stored bytes pass the structural check. A failing segment is purged
    /// (its spans and its row) so planning treats it as a miss; false too when it is not fully
    /// cached right now.
    pub fn check_stored(&self, segment: &MediaHeader) -> Result<bool, CacheError> {
        let key = segment.cache_key();
        let id = format!("{key} {} {}", segment.start_range, segment.content_length);
        let Some(coverage) = self.blobs.coverage_id(&key, segment.start_range, segment.content_length)? else {
            return Ok(false);
        };
        if self.intact.lock().unwrap().iter().any(|(known, known_coverage)| *known == id && *known_coverage == coverage)
        {
            return Ok(true);
        }
        let Some(mut stored) = self.blobs.read(&key, segment.start_range, segment.content_length)? else {
            return Ok(false);
        };
        let failure = check_stored_segment(segment.content_length, segment.is_init_segment, |position, buf| {
            stored.read_at(position, buf)
        })
        .map_err(storage("span read"))?;
        drop(stored);
        let Some(failure) = failure else {
            let mut intact = self.intact.lock().unwrap();
            intact.retain(|(known, _)| *known != id);
            intact.push_back((id, coverage));
            if intact.len() > INTACT_MAX {
                intact.pop_front();
            }
            return Ok(true);
        };
        self.log.warn(
            &format!(
                "corrupt cached segment purged (itag={} seg={} bytes={}..{}) {}",
                segment.itag,
                segment.segment_index,
                segment.start_range,
                segment.start_range + segment.content_length - 1,
                failure.describe(segment.start_range)
            ),
            None,
        );
        self.blobs.invalidate(&key, segment.start_range, segment.content_length)?;
        self.index.delete(segment.itag, segment.lmt, segment.segment_index)?;
        Ok(false)
    }
}

struct GapWriter {
    start: i64,
    end: i64,
    writer: BlobWriter,
}

/// One segment's mirror: take its whole byte stream through [`Self::write`], then
/// [`Self::commit`]. Bytes falling in already-cached runs are dropped, so a refetch never stores
/// overlapping spans; every byte also feeds the structural check, and the gaps publish only when
/// the whole segment passed it. Dropping it uncommitted aborts.
pub struct SegmentFill {
    index: Arc<SegmentIndex>,
    row: (i32, i64, i64),
    check: StreamingSegmentCheck,
    gaps: Vec<GapWriter>,
    position: i64,
    gap: usize,
}

impl SegmentFill {
    pub fn write(&mut self, mut bytes: &[u8]) -> Result<(), CacheError> {
        self.check.write(bytes);
        while !bytes.is_empty() {
            while self.gap < self.gaps.len() && self.position >= self.gaps[self.gap].end {
                self.gap += 1;
            }
            let Some(gap) = self.gaps.get_mut(self.gap) else {
                self.position += bytes.len() as i64;
                return Ok(());
            };
            let len = bytes.len() as i64;
            if self.position < gap.start {
                let skip = (gap.start - self.position).min(len) as usize;
                bytes = &bytes[skip..];
                self.position += skip as i64;
            } else {
                let take = (gap.end - self.position).min(len) as usize;
                gap.writer.write(&bytes[..take])?;
                bytes = &bytes[take..];
                self.position += take as i64;
            }
        }
        Ok(())
    }

    /// Publishes the mirrored gaps, or, when the bytes failed the check, drops them (and the row
    /// saved for them, unless another writer's copy owns it) and says why.
    pub fn commit(self) -> Result<Option<SegmentCheckFailure>, CacheError> {
        let failure = self.check.result();
        if failure.is_none() {
            for gap in self.gaps {
                gap.writer.commit()?;
            }
            return Ok(None);
        }
        let stored_here = self.gaps.iter().any(|gap| gap.writer.stores_bytes());
        for gap in self.gaps {
            gap.writer.abort();
        }
        if stored_here {
            let (itag, lmt, segment) = self.row;
            self.index.delete(itag, lmt, segment)?;
        }
        Ok(failure)
    }

    pub fn abort(self) {
        for gap in self.gaps {
            gap.writer.abort();
        }
    }
}
