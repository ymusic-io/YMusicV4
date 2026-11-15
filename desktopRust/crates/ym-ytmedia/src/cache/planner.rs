//! Answers a SABR request from cached segments when it can: walks the segment index forward from
//! the request's player time until the readahead window is covered, checks every segment against
//! the blobs, prepends each format's init segment, then re-acquires the bytes (a span evicted in
//! between turns the whole answer into a miss).

use std::collections::{HashMap, HashSet};
use std::io::{self, Read};

use super::CacheError;
use super::blob::PinnedSource;
use super::media::MediaCache;
use crate::sabr::messages::{AbrRequest, AdaptiveFormat, BufferedRange, FormatId, MediaHeader, NextRequestPolicy};
use crate::ump::{self, part};

/// The policy advertised back.
const TARGET_AUDIO_READAHEAD_MS: i64 = 60_000;
const TARGET_VIDEO_READAHEAD_MS: i64 = 60_000;
/// Filled until covered.
const MIN_AUDIO_READAHEAD_MS: i64 = TARGET_AUDIO_READAHEAD_MS + 45_000;
const MIN_VIDEO_READAHEAD_MS: i64 = TARGET_VIDEO_READAHEAD_MS + 45_000;

pub enum SabrServe {
    /// A UMP body of cached segments, streamed from the pinned spans.
    Hit {
        body: UmpBody,
        cached_bytes: i64,
        segment_count: usize,
        served_ranges: String,
    },
    /// The requester is buffered far enough ahead: a policy-only body tells it to back off.
    Backoff {
        body: Vec<u8>,
    },
    Miss {
        reason: String,
    },
}

enum CheckResult {
    Miss,
    Buffered(i64),
    Cached(Vec<MediaHeader>),
}

pub fn serve_sabr(cache: &MediaCache, request: &AbrRequest) -> Result<SabrServe, CacheError> {
    let player_time_ms = request.player_time_ms;
    let audio_only = request.enabled_track_types_bitfield == Some(1);
    let by_cache_key: HashMap<String, &AdaptiveFormat> =
        request.adaptive_formats.iter().map(|format| (format.cache_key(), format)).collect();
    let itag_mime: HashMap<i32, &str> =
        request.adaptive_formats.iter().map(|format| (format.itag, format.mime_without_codec())).collect();
    let selected_mimes: HashSet<&str> =
        request.selected_formats.iter().filter_map(|format| itag_mime.get(&format.itag).copied()).collect();
    let mime_of = |format: &FormatId| itag_mime.get(&format.itag).copied().unwrap_or_default();

    // Webm first, then formats the client already selected, then DRC ones when DRC is on. A
    // request without a catalog scores everything 0 and keeps its order.
    let score = |format: &FormatId| {
        let mime = mime_of(format);
        let mut score = 0;
        if mime.contains("webm") {
            score += 1;
        }
        if selected_mimes.contains(mime) {
            score += 2;
        }
        if request.drc_enabled && by_cache_key.get(&format.cache_key()).is_some_and(|format| format.is_drc) {
            score += 4;
        }
        score
    };

    // An empty selection is server-side format selection: the catalog is the ladder.
    let audio_ladder = ladder(&request.selected_audio_formats, &request.adaptive_formats, false);
    let mut audio_formats = audio_ladder.clone();
    audio_formats.sort_by_key(|format| std::cmp::Reverse(score(format)));
    let audio_formats = narrow_audio_formats(audio_formats, request, &by_cache_key);
    let audio_itags: HashSet<i32> = audio_ladder.iter().map(|format| format.itag).collect();
    let audio_buffered: Vec<&BufferedRange> =
        request.buffered_ranges.iter().filter(|range| audio_itags.contains(&range.format_id.itag)).collect();
    let audio = find_cached_segments(cache, player_time_ms, &audio_formats, &audio_buffered, MIN_AUDIO_READAHEAD_MS)?;

    // Audio-only: googlevideo sends no video, so neither does the cache.
    let mut video = CheckResult::Miss;
    if !audio_only {
        let mut video_ladder = ladder(&request.selected_video_formats, &request.adaptive_formats, true);
        let short_side = |format: &FormatId| {
            by_cache_key
                .get(&format.cache_key())
                .map_or(0, |format| format.width.unwrap_or(0).min(format.height.unwrap_or(0)))
        };
        if let Some(requested) = request.requested_video_resolution.filter(|value| *value > 0)
            && !by_cache_key.is_empty()
        {
            video_ladder.retain(|format| i64::from(short_side(format)) >= requested);
        }
        let mut video_formats = video_ladder.clone();
        video_formats.sort_by_key(|format| {
            let mime = mime_of(format);
            let mut score = short_side(format);
            if mime.contains("webm") {
                score += 10_000;
            }
            if selected_mimes.contains(mime) {
                score += 20_000;
            }
            std::cmp::Reverse(score)
        });
        let video_itags: HashSet<i32> = video_ladder.iter().map(|format| format.itag).collect();
        let video_buffered: Vec<&BufferedRange> =
            request.buffered_ranges.iter().filter(|range| video_itags.contains(&range.format_id.itag)).collect();
        video = find_cached_segments(cache, player_time_ms, &video_formats, &video_buffered, MIN_VIDEO_READAHEAD_MS)?;
    }

    let mut segments: Vec<MediaHeader> = Vec::new();
    if let CheckResult::Cached(found) = &audio {
        segments.extend(found.iter().cloned());
    }
    if let CheckResult::Cached(found) = &video {
        segments.extend(found.iter().cloned());
    }
    segments.sort_by_key(|segment| segment.start_ms.unwrap_or(-1));

    if segments.is_empty() {
        if let CheckResult::Buffered(audio_position) = audio
            && (audio_only || matches!(video, CheckResult::Buffered(_)))
        {
            let mut backoff = audio_position - player_time_ms;
            if let CheckResult::Buffered(video_position) = video {
                backoff = backoff.min(video_position - player_time_ms);
            }
            if backoff - TARGET_AUDIO_READAHEAD_MS.min(TARGET_VIDEO_READAHEAD_MS) > 2000 {
                let policy = NextRequestPolicy::encode_backoff(
                    TARGET_AUDIO_READAHEAD_MS,
                    TARGET_VIDEO_READAHEAD_MS,
                    2000,
                    request.playback_cookie.as_deref(),
                );
                let mut body = Vec::new();
                ump::write_part(&mut body, part::NEXT_REQUEST_POLICY as u32, &policy);
                return Ok(SabrServe::Backoff { body });
            }
        }
        let reason = if matches!(audio, CheckResult::Buffered(_)) {
            "buffered ahead but below the backoff threshold".to_owned()
        } else {
            format!("no cached segments cover playerTime={player_time_ms}ms")
        };
        return Ok(SabrServe::Miss { reason });
    }

    let Some(body) = synthesize_ump(cache, &segments)? else {
        return Ok(SabrServe::Miss {
            reason: format!("{} segments planned but evicted before re-acquire", segments.len()),
        });
    };
    let cached_bytes = segments.iter().map(|segment| segment.content_length).sum();
    Ok(SabrServe::Hit { body, cached_bytes, segment_count: segments.len(), served_ranges: served_ranges(&segments) })
}

fn ladder(selected: &[FormatId], catalog: &[AdaptiveFormat], video: bool) -> Vec<FormatId> {
    if !selected.is_empty() {
        return selected.to_vec();
    }
    catalog.iter().filter(|format| format.is_video() == video).map(AdaptiveFormat::format_id).collect()
}

/// `itag@startMs-endMs[segA-segB]` per format, the shape of the request's buffered ranges.
fn served_ranges(segments: &[MediaHeader]) -> String {
    let mut by_itag: Vec<(i32, i64, i64, i64, i64)> = Vec::new();
    for segment in segments {
        let Some(start) = segment.start_ms else { continue };
        let end = start + segment.duration_ms.unwrap_or(0);
        match by_itag.iter_mut().find(|entry| entry.0 == segment.itag) {
            Some(entry) => {
                entry.1 = entry.1.min(start);
                entry.2 = entry.2.max(end);
                entry.3 = entry.3.min(segment.segment_index);
                entry.4 = entry.4.max(segment.segment_index);
            }
            None => by_itag.push((segment.itag, start, end, segment.segment_index, segment.segment_index)),
        }
    }
    by_itag
        .iter()
        .map(|(itag, start, end, first, last)| format!("{itag}@{start}-{end}ms[seg{first}-{last}]"))
        .collect::<Vec<_>>()
        .join(",")
}

/// Multi-audio narrowing: a requested track id, then DRC, then the default track.
fn narrow_audio_formats(
    formats: Vec<FormatId>,
    request: &AbrRequest,
    by_cache_key: &HashMap<String, &AdaptiveFormat>,
) -> Vec<FormatId> {
    let requested_track = request.request_audio_track_id.as_deref();
    if requested_track.is_none() && formats.len() <= 1 {
        return formats;
    }
    let catalog = |format: &FormatId| by_cache_key.get(&format.cache_key()).copied();
    let mut out = formats;
    if let Some(track) = requested_track {
        let matching: Vec<FormatId> = out
            .iter()
            .filter(|format| catalog(format).and_then(|entry| entry.audio_track_id.as_deref()) == Some(track))
            .cloned()
            .collect();
        if !matching.is_empty() {
            out = matching;
        }
    }
    if request.drc_enabled && out.len() > 1 {
        let drc: Vec<FormatId> =
            out.iter().filter(|format| catalog(format).is_some_and(|entry| entry.is_drc)).cloned().collect();
        if !drc.is_empty() {
            out = drc;
        }
    }
    if out.len() > 1 {
        let default = out
            .iter()
            .find(|format| catalog(format).and_then(|entry| entry.audio_track_is_default) == Some(true))
            .or_else(|| {
                out.iter().find(|format| {
                    request.selected_formats.iter().any(|selected| selected.cache_key() == format.cache_key())
                })
            })
            .cloned();
        if let Some(default) = default {
            out = vec![default];
        }
    }
    out
}

/// The first format holding a cached, intact segment found by `find`, with its init segment first
/// when the requester has not initialized that format.
fn add_next(
    cache: &MediaCache,
    formats: &[FormatId],
    initialized: &mut HashSet<FormatId>,
    result: &mut Vec<MediaHeader>,
    mut find: impl FnMut(&FormatId) -> Result<Option<MediaHeader>, CacheError>,
) -> Result<Option<MediaHeader>, CacheError> {
    for format in formats {
        let Some(segment) = find(format)? else { continue };
        if !cache.blobs.is_cached(&segment.cache_key(), segment.start_range, segment.content_length)? {
            continue;
        }
        if !cache.check_stored(&segment)? {
            continue;
        }
        if !initialized.contains(format) {
            let Some(init) = cache.index.find_by_segment_index(format.itag, format.lmt, 0)? else { continue };
            if !cache.blobs.is_cached(&init.cache_key(), init.start_range, init.content_length)? {
                continue;
            }
            if !cache.check_stored(&init)? {
                continue;
            }
            result.push(init);
            initialized.insert(format.clone());
        }
        result.push(segment.clone());
        return Ok(Some(segment));
    }
    Ok(None)
}

fn window_end(segment: &MediaHeader) -> Result<i64, CacheError> {
    match (segment.start_ms, segment.duration_ms) {
        (Some(start), Some(duration)) => Ok(start + duration - 1),
        _ => Err(CacheError::Invalid(format!("cached segment without a time window ({})", segment.describe()))),
    }
}

/// Walks the cached segments of the first covered format forward from `player_time_ms` until
/// `min_buffer_ms` is covered.
fn find_cached_segments(
    cache: &MediaCache,
    player_time_ms: i64,
    formats: &[FormatId],
    buffered: &[&BufferedRange],
    min_buffer_ms: i64,
) -> Result<CheckResult, CacheError> {
    let mut sorted: Vec<&BufferedRange> = buffered.to_vec();
    sorted.sort_by_key(|range| range.start_segment_index);
    let mut initialized: HashSet<FormatId> = buffered.iter().map(|range| range.format_id.clone()).collect();
    let mut result = Vec::new();
    let holds = |segment: i64| {
        sorted.iter().any(|range| range.start_segment_index <= segment && range.end_segment_index >= segment)
    };

    let mut buffered_position = -1;
    let mut next_segment;
    let mut new_buffered_position;
    let inside = sorted.iter().find(|range| range.start_ms <= player_time_ms && player_time_ms < range.end_ms());
    if let Some(inside) = inside {
        next_segment = inside.start_segment_index;
        for range in &sorted {
            if range.start_segment_index <= next_segment && range.end_segment_index >= next_segment {
                next_segment = range.end_segment_index + 1;
                buffered_position = range.start_ms + range.duration_ms - 1;
            }
        }
        new_buffered_position = buffered_position;
    } else {
        let found = add_next(cache, formats, &mut initialized, &mut result, |format| {
            let segment = cache.index.find_by_start_time(format.itag, format.lmt, player_time_ms)?;
            Ok(segment.filter(|segment| {
                !sorted.iter().any(|range| {
                    range.format_id == *format
                        && (range.start_segment_index..=range.end_segment_index).contains(&segment.segment_index)
                })
            }))
        })?;
        let Some(segment) = found else { return Ok(CheckResult::Miss) };
        next_segment = segment.segment_index + 1;
        new_buffered_position = window_end(&segment)?;
    }

    while new_buffered_position - player_time_ms < min_buffer_ms {
        if holds(next_segment) {
            break;
        }
        let wanted = next_segment;
        let found = add_next(cache, formats, &mut initialized, &mut result, |format| {
            Ok(cache.index.find_by_segment_index(format.itag, format.lmt, wanted)?)
        })?;
        let Some(segment) = found else { break };
        next_segment += 1;
        new_buffered_position = window_end(&segment)?;
    }

    if result.is_empty() {
        return Ok(if buffered_position - player_time_ms >= min_buffer_ms {
            CheckResult::Buffered(buffered_position)
        } else {
            CheckResult::Miss
        });
    }
    Ok(CheckResult::Cached(result))
}

/// A hit's body: per segment, its format's FORMAT_INITIALIZATION_METADATA (only before an init
/// segment, as the server sends it), then MEDIA_HEADER / MEDIA_DATA / MEDIA_END with the media
/// streamed from the pinned spans. `None` when a segment's bytes were evicted since planning.
fn synthesize_ump(cache: &MediaCache, segments: &[MediaHeader]) -> Result<Option<UmpBody>, CacheError> {
    let mut pieces = Vec::with_capacity(segments.len() * 3);
    for (index, segment) in segments.iter().enumerate() {
        let Some(pinned) = cache.blobs.read(&segment.cache_key(), segment.start_range, segment.content_length)? else {
            return Ok(None);
        };
        let header_id = (index & 0xFF) as u8;
        let mut head = Vec::new();
        if segment.is_init_segment
            && let Some(metadata) = cache.index.format_init(segment.itag, segment.lmt)?
        {
            ump::write_part(&mut head, part::FORMAT_INITIALIZATION_METADATA as u32, &metadata);
        }
        let mut header = segment.clone();
        header.header_id = header_id;
        ump::write_part(&mut head, part::MEDIA_HEADER as u32, &header.encode().into_bytes());
        ump::write_part_header(&mut head, part::MEDIA_DATA as u32, (segment.content_length + 1) as u32);
        head.push(header_id);
        pieces.push(Piece::Bytes(io::Cursor::new(head)));
        pieces.push(Piece::Span(pinned));
        let mut end = Vec::new();
        ump::write_part(&mut end, part::MEDIA_END as u32, &[header_id]);
        pieces.push(Piece::Bytes(io::Cursor::new(end)));
    }
    Ok(Some(UmpBody { pieces, index: 0 }))
}

enum Piece {
    Bytes(io::Cursor<Vec<u8>>),
    Span(PinnedSource),
}

/// The synthesized body, read in order; each pinned span is released once read past, the rest
/// when the body drops. Segment bytes are never held in memory.
pub struct UmpBody {
    pieces: Vec<Piece>,
    index: usize,
}

impl Read for UmpBody {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        while self.index < self.pieces.len() {
            let read = match &mut self.pieces[self.index] {
                Piece::Bytes(bytes) => bytes.read(buf)?,
                Piece::Span(span) => span.read(buf)?,
            };
            if read > 0 || buf.is_empty() {
                return Ok(read);
            }
            self.pieces[self.index] = Piece::Bytes(io::Cursor::new(Vec::new()));
            self.index += 1;
        }
        Ok(0)
    }
}
