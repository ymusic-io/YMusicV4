//! Reconciles a directory tree into the store: each directory holding media at or below it is a
//! bucket, each media file an item; unchanged files (same size and mtime) are not probed again,
//! vanished ones soft-delete, folders left without media are pruned, excluded folders are kept but
//! not entered.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::UNIX_EPOCH;

use ym_ffmpeg::{FfmpegError, FfmpegMetadataGetter, MediaFileMetadata, MetadataMap, StreamKind};

use crate::{MediaItem, MediaStore, StoreError};

/// What the bundled ffmpeg demuxes: the Open With types of the JVM app.
const MEDIA_EXTENSIONS: &[&str] = &[
    "3g2", "3gp", "aac", "avi", "flac", "flv", "m2v", "m4a", "m4b", "m4v", "mkv", "mov", "mp2", "mp3", "mp4", "mpeg",
    "mpg", "oga", "ogg", "ogv", "opus", "wav", "webm", "wmv", "wv",
];

pub fn is_media_file(path: &Path) -> bool {
    path.extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| MEDIA_EXTENSIONS.contains(&extension.to_ascii_lowercase().as_str()))
}

#[derive(Debug)]
pub struct ScanError {
    pub path: PathBuf,
    pub message: String,
}

impl ScanReport {
    pub fn scanned(&self) -> usize {
        self.added + self.updated + self.unchanged + self.errors.len()
    }
}

#[derive(Debug, Default)]
pub struct ScanReport {
    pub root_bucket: Option<i64>,
    pub added: usize,
    pub updated: usize,
    pub unchanged: usize,
    pub removed: usize,
    pub errors: Vec<ScanError>,
}

/// A file ffmpeg could not read, typed by its name.
fn mime_type(path: &Path) -> String {
    let extension = path.extension().and_then(|e| e.to_str()).unwrap_or_default().to_ascii_lowercase();
    let subtype = match extension.as_str() {
        "mp3" => "mpeg",
        "m4a" | "m4b" | "mp4" | "m4v" | "3gp" | "3g2" => "mp4",
        "mkv" => "x-matroska",
        "webm" => "webm",
        "ogg" | "oga" | "ogv" | "opus" => "ogg",
        "flac" => "flac",
        "wav" => "wav",
        "mov" => "quicktime",
        "avi" => "x-msvideo",
        "aac" => "aac",
        other => other,
    };
    format!("audio/{subtype}")
}

fn file_name(path: &Path) -> String {
    path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default()
}

fn file_stem(path: &Path) -> String {
    path.file_stem().map_or_else(|| file_name(path), |stem| stem.to_string_lossy().into_owned())
}

/// The scanner's `toMediaItemDraft`: tags from the container's bag, then each stream's in order.
fn item_from_metadata(path: &Path, uri: String, metadata: &MediaFileMetadata) -> MediaItem {
    let name = file_name(path);
    let tag_bags: Vec<&MetadataMap> = std::iter::once(&metadata.container.metadata)
        .chain(metadata.streams.iter().map(|stream| &stream.metadata))
        .collect();
    let first_tag = |select: fn(&MetadataMap) -> Option<&str>| {
        tag_bags.iter().find_map(|bag| select(bag).filter(|value| !value.trim().is_empty()).map(str::to_owned))
    };
    let audio = metadata.streams.iter().find(|stream| matches!(stream.kind, StreamKind::Audio { .. }));
    let video = metadata
        .streams
        .iter()
        .find(|stream| matches!(stream.kind, StreamKind::Video { .. }) && !stream.is_attached_thumbnail());
    let subtitle = metadata.streams.iter().find(|stream| stream.kind == StreamKind::Text);
    let (width, height) = match video.map(|stream| &stream.kind) {
        Some(StreamKind::Video { width, height, .. }) => (*width, *height),
        _ => (None, None),
    };
    MediaItem {
        title: first_tag(MetadataMap::title)
            .unwrap_or_else(|| name.rsplit_once('.').map_or_else(|| name.clone(), |(stem, _)| stem.to_owned())),
        mime_type: metadata.mime_type(),
        artist: first_tag(MetadataMap::artist),
        album: first_tag(MetadataMap::album),
        genre: first_tag(MetadataMap::genre),
        track_number: first_tag(MetadataMap::track)
            .and_then(|track| track.split('/').next().and_then(|number| number.trim().parse().ok())),
        year: first_tag(MetadataMap::year).and_then(|year| year_in(&year)),
        duration_ms: metadata.container.duration_ms,
        audio_codec: audio.map(|stream| stream.codec.clone()),
        video_codec: video.map(|stream| stream.codec.clone()),
        subtitle_codec: subtitle.map(|stream| stream.codec.clone()),
        bitrate: metadata.container.bitrate,
        width,
        height,
        thumbnail_stream_index: metadata
            .streams
            .iter()
            .find(|stream| stream.is_attached_thumbnail())
            .map(|stream| stream.index),
        youtube_vid: tag_bags
            .iter()
            .find_map(|bag| bag.composer().and_then(video_id_from_composer))
            .or_else(|| video_id_from_file_name(&name)),
        loudness_gain_db: tag_bags.iter().find_map(|bag| bag.composer().and_then(gain_from_composer)),
        uri,
        ..MediaItem::default()
    }
}

/// The first `[12][0-9]{3}` in a year or date tag.
fn year_in(text: &str) -> Option<i32> {
    text.as_bytes()
        .windows(4)
        .find(|window| matches!(window[0], b'1' | b'2') && window[1..].iter().all(u8::is_ascii_digit))
        .and_then(|window| std::str::from_utf8(window).ok()?.parse().ok())
}

/// A file outside the Library, as an unsaved item.
pub struct DescribedFile {
    pub item: MediaItem,
    /// Why the file could not be probed: the item then carries only its name.
    pub probe_error: Option<FfmpegError>,
}

/// Blocking: describes `path` by its own tags, for playing a file that is not in the Library.
pub fn describe_file(path: &Path, ffmpeg: &FfmpegMetadataGetter) -> std::io::Result<DescribedFile> {
    let metadata = std::fs::metadata(path)?;
    let uri = path.to_string_lossy().into_owned();
    let (item, probe_error) = match ffmpeg.get_metadata(path) {
        Ok(metadata) => (item_from_metadata(path, uri, &metadata), None),
        Err(error) => {
            let item = MediaItem { title: file_stem(path), mime_type: mime_type(path), uri, ..MediaItem::default() };
            (item, Some(error))
        }
    };
    let modified = metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |elapsed| elapsed.as_millis() as i64);
    Ok(DescribedFile {
        item: MediaItem { file_size: metadata.len() as i64, last_modified: modified, ..item },
        probe_error,
    })
}

/// A folder's bucket, written only once media is found at or below it (`Walk::materialize`).
struct LazyBucket {
    path: String,
    name: String,
    id: Option<i64>,
}

/// What a folder's walk concluded, for its parent's prune: `has_media` keeps its bucket, and so
/// does `had_error` (something below could not be read).
#[derive(Clone, Copy)]
struct FolderScan {
    has_media: bool,
    had_error: bool,
}

struct Walk<'a> {
    store: &'a MediaStore,
    ffmpeg: &'a FfmpegMetadataGetter,
    cancel: &'a AtomicBool,
    excluded: HashSet<String>,
    root_parent: Option<i64>,
    report: ScanReport,
}

impl Walk<'_> {
    fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::Relaxed)
    }

    /// The id of the chain's last folder, writing every folder of the chain not written yet.
    fn materialize(&self, chain: &mut [LazyBucket]) -> Result<i64, StoreError> {
        let mut parent_id = self.root_parent;
        let mut last = None;
        for bucket in chain.iter_mut() {
            let id = match bucket.id {
                Some(id) => id,
                None => {
                    let id = self.store.upsert_bucket(&bucket.path, &bucket.name, parent_id)?;
                    bucket.id = Some(id);
                    id
                }
            };
            parent_id = Some(id);
            last = Some(id);
        }
        Ok(last.expect("a walk's chain holds at least its root"))
    }

    /// A folder it could not read is never pruned: the status quo wins over a false "empty".
    fn scan_directory(&mut self, directory: &Path, chain: &mut Vec<LazyBucket>) -> Result<FolderScan, StoreError> {
        let materialized = |chain: &[LazyBucket]| chain.last().is_some_and(|bucket| bucket.id.is_some());
        let entries = match std::fs::read_dir(directory) {
            Ok(entries) => entries,
            Err(error) => {
                self.report.errors.push(ScanError { path: directory.to_path_buf(), message: error.to_string() });
                return Ok(FolderScan { has_media: materialized(chain), had_error: true });
            }
        };
        let mut entries: Vec<_> = entries.flatten().collect();
        entries.sort_by_key(|entry| entry.file_name());
        let mut sub_folders = Vec::new();
        let mut media_files = Vec::new();
        for entry in entries {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with('.') {
                continue;
            }
            let Ok(metadata) = entry.metadata() else { continue };
            let path = entry.path();
            if metadata.is_dir() {
                sub_folders.push((path, name));
            } else if metadata.len() > 0 && is_media_file(&path) {
                media_files.push((path, metadata));
            }
        }

        if !media_files.is_empty() {
            let bucket_id = self.materialize(chain)?;
            let mut present = Vec::new();
            for (path, metadata) in &media_files {
                if self.cancelled() {
                    return Ok(FolderScan { has_media: true, had_error: true });
                }
                present.push(path.to_string_lossy().into_owned());
                index_file(self.store, self.ffmpeg, path.clone(), metadata, bucket_id, &mut self.report)?;
            }
            self.report.removed += self.store.delete_missing_items(bucket_id, &present)?;
        }

        let present_sub_uris: HashSet<String> =
            sub_folders.iter().map(|(path, _)| crate::file_uri(&path.to_string_lossy())).collect();
        let mut child_results = HashMap::new();
        for (path, name) in sub_folders {
            if self.cancelled() {
                return Ok(FolderScan { has_media: materialized(chain), had_error: true });
            }
            let uri = crate::file_uri(&path.to_string_lossy());
            let result = if self.excluded.contains(&uri) {
                // Not entered, but this folder is kept so an ancestor's prune does not cascade it away.
                self.materialize(chain)?;
                FolderScan { has_media: true, had_error: false }
            } else {
                chain.push(LazyBucket { path: path.to_string_lossy().into_owned(), name, id: None });
                let result = self.scan_directory(&path, chain);
                chain.pop();
                result?
            };
            child_results.insert(uri, result);
        }
        if self.cancelled() {
            return Ok(FolderScan { has_media: materialized(chain), had_error: true });
        }
        let subtree_error = child_results.values().any(|result| result.had_error);

        if let Some(bucket_id) = chain.last().and_then(|bucket| bucket.id) {
            if media_files.is_empty() {
                self.report.removed += self.store.delete_missing_items(bucket_id, &[])?;
            }
            for child in self.store.child_buckets(bucket_id)? {
                let uri = crate::file_uri(&child.uri);
                let keep = present_sub_uris.contains(&uri)
                    && child_results.get(&uri).is_none_or(|result| result.has_media || result.had_error);
                if !keep {
                    self.store.remove_bucket(child.id)?;
                    self.report.removed += 1;
                }
            }
        }
        Ok(FolderScan { has_media: materialized(chain), had_error: subtree_error })
    }
}

/// Blocking and long: run on a worker. A folder gets a bucket only when media is found at or
/// below it, the scanned folder always; a folder that vanished or read clean without media is
/// pruned with its subtree, and a known scanned folder that is gone or unreadable is removed.
/// `cancel` stops between files and prunes nothing; per-file failures are collected in the report,
/// a store failure aborts the scan.
pub fn scan_folder(
    store: &MediaStore,
    ffmpeg: &FfmpegMetadataGetter,
    root: &Path,
    cancel: &AtomicBool,
) -> Result<ScanReport, StoreError> {
    let mut report = ScanReport::default();
    let root_path = root.to_string_lossy();
    let known = store.find_bucket_by_uri(&root_path)?;
    let Ok(metadata) = std::fs::metadata(root) else {
        if let Some(bucket) = known.filter(|bucket| !bucket.excluded) {
            store.remove_bucket(bucket.id)?;
            report.removed += 1;
        }
        return Ok(report);
    };
    if !metadata.is_dir() || known.as_ref().is_some_and(|bucket| bucket.excluded) {
        return Ok(report);
    }
    let root_name =
        root.file_name().map_or_else(|| root_path.clone().into_owned(), |name| name.to_string_lossy().into_owned());
    let mut walk = Walk {
        store,
        ffmpeg,
        cancel,
        excluded: store.excluded_bucket_uris()?,
        root_parent: known.and_then(|bucket| bucket.parent_id),
        report,
    };
    let mut chain = vec![LazyBucket { path: root_path.into_owned(), name: root_name, id: None }];
    walk.report.root_bucket = Some(walk.materialize(&mut chain)?);
    walk.scan_directory(root, &mut chain)?;
    Ok(walk.report)
}

/// Blocking: indexes the one media file at `path` into the folder bucket `bucket_id` (a finished
/// download landing in its Library folder), without walking the rest of the folder.
pub fn scan_file(
    store: &MediaStore,
    ffmpeg: &FfmpegMetadataGetter,
    path: &Path,
    bucket_id: i64,
) -> Result<ScanReport, StoreError> {
    let mut report = ScanReport { root_bucket: Some(bucket_id), ..ScanReport::default() };
    match std::fs::metadata(path) {
        Ok(metadata) if metadata.is_file() && metadata.len() > 0 && is_media_file(path) => {
            index_file(store, ffmpeg, path.to_path_buf(), &metadata, bucket_id, &mut report)?
        }
        Ok(_) => report.errors.push(ScanError { path: path.to_path_buf(), message: "not a media file".into() }),
        Err(error) => report.errors.push(ScanError { path: path.to_path_buf(), message: error.to_string() }),
    }
    Ok(report)
}

/// Unchanged files (same size, mtime and folder) are not probed again.
fn index_file(
    store: &MediaStore,
    ffmpeg: &FfmpegMetadataGetter,
    path: PathBuf,
    metadata: &std::fs::Metadata,
    bucket_id: i64,
    report: &mut ScanReport,
) -> Result<(), StoreError> {
    let uri = path.to_string_lossy().into_owned();
    let size = metadata.len() as i64;
    let modified = metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |elapsed| elapsed.as_millis() as i64);
    let existing = store.find_item_by_uri(&uri)?;
    if let Some((item, deleted)) = &existing
        && !deleted
        && item.file_size == size
        && item.last_modified == modified
        && item.bucket_id == Some(bucket_id)
    {
        report.unchanged += 1;
        return Ok(());
    }
    match ffmpeg.get_metadata(&path) {
        Ok(info) if has_audio(&info) || info.has_video() => {
            store.save_item(&MediaItem {
                bucket_id: Some(bucket_id),
                file_size: size,
                last_modified: modified,
                ..item_from_metadata(&path, uri, &info)
            })?;
            if existing.is_some() {
                report.updated += 1;
            } else {
                report.added += 1;
            }
        }
        Ok(_) => report.errors.push(ScanError { path, message: "no audio or video stream".into() }),
        Err(error) => report.errors.push(ScanError { path, message: error.to_string() }),
    }
    Ok(())
}

fn has_audio(metadata: &MediaFileMetadata) -> bool {
    metadata.streams.iter().any(|stream| matches!(stream.kind, StreamKind::Audio { .. }))
}

fn is_video_id(text: &str) -> bool {
    text.len() == 11 && text.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

/// Downloads tag `composer` as `videoId` or `videoId|gain=-6.69`.
fn video_id_from_composer(composer: &str) -> Option<String> {
    let id = composer.split('|').next()?;
    is_video_id(id).then(|| id.to_owned())
}

fn gain_from_composer(composer: &str) -> Option<f64> {
    let (id, gain) = composer.split_once("|gain=")?;
    if !is_video_id(id) {
        return None;
    }
    let digits = gain.strip_prefix('-').unwrap_or(gain);
    let valid = !digits.is_empty()
        && digits.split('.').count() <= 2
        && digits.split('.').all(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()));
    if valid { gain.parse().ok() } else { None }
}

/// Files named like `Title [videoId].ext`, optionally with a `(1)`-style suffix.
fn video_id_from_file_name(name: &str) -> Option<String> {
    let close = name.rfind(']')?;
    let open = name[..close].rfind('[')?;
    let id = &name[open + 1..close];
    let rest = &name[close + 1..];
    let stem_rest = rest.rsplit_once('.').map_or(rest, |(before, _)| before);
    let suffix_ok = stem_rest.trim().chars().all(|c| c.is_ascii_digit() || "-_()[]".contains(c));
    (is_video_id(id) && suffix_ok).then(|| id.to_owned())
}
