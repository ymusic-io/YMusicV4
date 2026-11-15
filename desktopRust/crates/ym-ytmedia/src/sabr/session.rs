//! The SABR download loop: POST what we hold, stream back segments, append each format's bytes to
//! its file in order, until every file reaches its `clen`. The file is the format's contiguous
//! stream (init segment at 0), byte-identical to the progressive file.

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::StreamExt;
use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use url::Url;
use ym_common::log::Logger;

use super::SabrStreamSpec;
use super::messages::{
    self, AbrRequest, AdaptiveFormat, BufferedRange, ClientAbrState, MediaHeader, NextRequestPolicy, SabrContextUpdate,
    VideoPlaybackAbrRequest,
};
use crate::cache::{MediaCache, SabrServe, reader_stream, serve_sabr};
use crate::proto::{Message, ProtoError};
use crate::segment_index::SegmentIndex;
use crate::ump::{ByteStream, UmpReader, part};

const VIEWPORT: (i64, i64) = (640, 360);
const MAX_CONSECUTIVE_EMPTY_ROUNDS: u32 = 3;
const PROGRESS_INTERVAL: Duration = Duration::from_millis(150);
const MIN_EMPTY_ROUND_BACKOFF: Duration = Duration::from_secs(3);
const ROUND_TRAIL_LINES: usize = 60;

#[derive(Debug, thiserror::Error)]
pub enum SabrError {
    /// googlevideo answered STREAM_PROTECTION_STATUS 3; the caller re-attests.
    #[error("PO token invalid (StreamProtectionStatus=3)")]
    PoTokenInvalid,
    #[error("SABR withheld media while attestation stayed pending (StreamProtectionStatus=2)")]
    AttestationPending,
    #[error("SABR error part: {0}")]
    Server(String),
    #[error("SABR request failed: {0}")]
    Network(#[from] reqwest::Error),
    #[error("SABR response body failed: {0}")]
    Body(#[source] io::Error),
    #[error("SABR response HTTP {0}")]
    Status(u16),
    #[error("SABR protocol: {0}")]
    Protocol(String),
    #[error("SABR exceeded max retries, last: {0}")]
    Stalled(String),
    #[error("download file: {0}")]
    File(#[source] io::Error),
    #[error("segment index: {0}")]
    Index(#[from] rusqlite::Error),
}

impl From<ProtoError> for SabrError {
    fn from(error: ProtoError) -> Self {
        SabrError::Protocol(error.to_string())
    }
}

/// How SABR requests go out. `client` must not follow redirects: the session handles them.
#[derive(Clone)]
pub struct SabrHttp {
    pub client: reqwest::Client,
    /// Must match the User-Agent of every other request of this session (engine, player).
    pub user_agent: String,
    pub cookies: Option<CookieHeader>,
}

/// The `Cookie` header value to send to a URL.
pub type CookieHeader = Arc<dyn Fn(&Url) -> Option<String> + Send + Sync>;

/// An append-only file whose length is the resume point.
pub struct DownloadFile {
    path: PathBuf,
    file: File,
    length: u64,
}

impl DownloadFile {
    pub fn open(path: &Path) -> io::Result<Self> {
        let file = OpenOptions::new().create(true).append(true).open(path)?;
        let length = file.metadata()?.len();
        Ok(Self { path: path.to_owned(), file, length })
    }

    pub fn len(&self) -> u64 {
        self.length
    }

    pub fn is_empty(&self) -> bool {
        self.length == 0
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Small page-cache writes, done inline: one network chunk at a time.
    fn append(&mut self, bytes: &[u8]) -> io::Result<()> {
        self.file.write_all(bytes)?;
        self.length += bytes.len() as u64;
        Ok(())
    }
}

pub struct SabrTrack<'a> {
    pub format: &'a AdaptiveFormat,
    pub file: &'a mut DownloadFile,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Progress {
    pub audio_done: u64,
    pub audio_length: u64,
    pub video_done: u64,
    pub video_length: u64,
}

impl Progress {
    pub fn done(&self) -> u64 {
        self.audio_done + self.video_done
    }

    pub fn total(&self) -> u64 {
        self.audio_length + self.video_length
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Audio,
    Video,
}

struct Tracks<'a, 'b> {
    audio: &'b mut SabrTrack<'a>,
    video: Option<&'b mut SabrTrack<'a>>,
}

impl<'a> Tracks<'a, '_> {
    fn kind_of(&self, header: &MediaHeader) -> Option<Kind> {
        let key = header.cache_key();
        if key == self.audio.format.cache_key() {
            Some(Kind::Audio)
        } else if self.video.as_ref().is_some_and(|video| video.format.cache_key() == key) {
            Some(Kind::Video)
        } else {
            None
        }
    }

    fn get(&mut self, kind: Kind) -> &mut SabrTrack<'a> {
        match kind {
            Kind::Audio => self.audio,
            Kind::Video => self.video.as_deref_mut().expect("video kind without a video track"),
        }
    }

    fn file_len(&self, kind: Kind) -> u64 {
        match kind {
            Kind::Audio => self.audio.file.len(),
            Kind::Video => self.video.as_ref().map_or(0, |video| video.file.len()),
        }
    }

    fn progress(&self) -> Progress {
        Progress {
            audio_done: self.audio.file.len(),
            audio_length: self.audio.format.clen.max(0) as u64,
            video_done: self.video.as_ref().map_or(0, |video| video.file.len()),
            video_length: self.video.as_ref().map_or(0, |video| video.format.clen.max(0) as u64),
        }
    }

    fn is_complete(&self) -> bool {
        let done = |track: &SabrTrack| track.file.len() as i64 >= track.format.clen;
        done(self.audio) && self.video.as_deref().is_none_or(done)
    }
}

struct PartState {
    header: MediaHeader,
    kind: Kind,
    total_read: i64,
    writing: bool,
    /// Its data starts past the file end: skipped, and the cursor re-seeded from the file.
    beyond_file_end: bool,
}

struct RoundOutcome {
    completed_segments: u32,
    redirect: Option<String>,
    backoff_ms: u64,
    protection_status: Option<u64>,
}

struct ProgressGate<'p> {
    callback: &'p mut (dyn FnMut(Progress) + Send),
    last: Option<Instant>,
}

impl ProgressGate<'_> {
    fn emit(&mut self, progress: Progress, force: bool) {
        if !force && self.last.is_some_and(|last| last.elapsed() < PROGRESS_INTERVAL) {
            return;
        }
        self.last = Some(Instant::now());
        (self.callback)(progress);
    }
}

/// One download session (cpn, request number, cursor, playback cookie): one video, not reusable.
pub struct SabrSession {
    cpn: String,
    rn: u32,
    playback_cookie: Vec<u8>,
    contexts: BTreeMap<i64, SabrContextUpdate>,
    po_token_valid: bool,
    audio_segment: Option<MediaHeader>,
    video_segment: Option<MediaHeader>,
    trail: VecDeque<String>,
    rng: StdRng,
    /// Segments it holds are read from it, never written into it.
    player_cache: Option<Arc<MediaCache>>,
    log: Logger,
}

impl SabrSession {
    pub fn new(log: Logger) -> Self {
        let mut rng = StdRng::from_os_rng();
        Self {
            cpn: generate_cpn(&mut rng),
            rn: 0,
            playback_cookie: Vec::new(),
            contexts: BTreeMap::new(),
            po_token_valid: true,
            audio_segment: None,
            video_segment: None,
            trail: VecDeque::new(),
            rng,
            player_cache: None,
            log,
        }
    }

    /// Serves rounds from the player cache when it holds the next segments: a track played before
    /// downloads without the network. Read-only, so a download never fills the player cache.
    pub fn reuse_player_cache(mut self, cache: Option<Arc<MediaCache>>) -> Self {
        self.player_cache = cache;
        self
    }

    /// Drives the tracks to completion. With `index`, segments are recorded as they arrive and the
    /// cursor resumes from each file's length, so a paused or re-attested download continues.
    pub async fn download(
        &mut self,
        http: &SabrHttp,
        spec: &SabrStreamSpec,
        mut audio: SabrTrack<'_>,
        mut video: Option<SabrTrack<'_>>,
        index: Option<&SegmentIndex>,
        progress: &mut (dyn FnMut(Progress) + Send),
    ) -> Result<(), SabrError> {
        let mut tracks = Tracks { audio: &mut audio, video: video.as_mut() };
        let result = self.run(http, spec, &mut tracks, index, progress).await;
        if let Err(error) = &result {
            self.log.warn(&format!("SABR session failed: {error}\n{}", self.trail_text()), None);
        }
        result
    }

    async fn run(
        &mut self,
        http: &SabrHttp,
        spec: &SabrStreamSpec,
        tracks: &mut Tracks<'_, '_>,
        index: Option<&SegmentIndex>,
        progress: &mut (dyn FnMut(Progress) + Send),
    ) -> Result<(), SabrError> {
        self.audio_segment = seed_cursor(index, tracks.audio)?;
        self.video_segment = match tracks.video.as_deref() {
            Some(video) => seed_cursor(index, video)?,
            None => None,
        };
        if let Some(index) = index {
            index.mark_download_time(tracks.audio.format.itag, tracks.audio.format.lmt)?;
            if let Some(video) = tracks.video.as_deref() {
                index.mark_download_time(video.format.itag, video.format.lmt)?;
            }
        }
        let started = Instant::now();
        let mut url = set_query_param(&spec.sabr_url, "alr", "yes");
        let mut gate = ProgressGate { callback: progress, last: None };
        gate.emit(tracks.progress(), true);
        if tracks.is_complete() {
            return Ok(());
        }
        // A video format must be offered even for audio only, or the request fails.
        let fallback_video = match tracks.video.as_deref() {
            Some(video) => Some(video.format.clone()),
            None => spec.formats.iter().rev().find(|format| format.is_video()).cloned(),
        };

        let mut empty_rounds = 0;
        let mut last_error = String::new();
        let mut last_protection: Option<u64> = None;
        loop {
            if empty_rounds > MAX_CONSECUTIVE_EMPTY_ROUNDS {
                if !self.po_token_valid {
                    return Err(SabrError::PoTokenInvalid);
                }
                if last_protection == Some(2) {
                    return Err(SabrError::AttestationPending);
                }
                return Err(SabrError::Stalled(last_error));
            }
            let (body, cache_request) = self.next_request(spec, tracks, fallback_video.as_ref(), started)?;
            if let Some(request) = cache_request
                && let Some(stream) = self.serve_from_player_cache(request).await
            {
                match self.read_response(UmpReader::new(stream), tracks, index, &mut gate, true).await {
                    Ok(outcome) if outcome.completed_segments == 0 => {
                        empty_rounds += 1;
                        last_error = "cache served zero segments".to_owned();
                    }
                    Ok(_) => empty_rounds = 0,
                    // A cache read failing mid-round leaves at most a partial segment past the cursor:
                    // the network resends it and the prefix already written is skipped.
                    Err(error @ (SabrError::Body(_) | SabrError::Protocol(_))) => {
                        self.log.warn("cache-served round failed, continuing from network", Some(&error));
                        self.player_cache = None;
                    }
                    Err(error) => return Err(error),
                }
                if tracks.is_complete() {
                    gate.emit(tracks.progress(), true);
                    return Ok(());
                }
                continue;
            }
            self.rn += 1;
            let request_url = set_query_param(&set_query_param(&url, "cpn", &self.cpn), "rn", &self.rn.to_string());
            let player_time = self.player_time(tracks.video.is_some());
            self.log.debug(|| {
                format!(
                    "round rn={} playerTime={player_time} audioSeg={:?} videoSeg={:?} cookieLen={}",
                    self.rn,
                    self.audio_segment.as_ref().map(|segment| segment.segment_index),
                    self.video_segment.as_ref().map(|segment| segment.segment_index),
                    self.playback_cookie.len()
                )
            });
            let parsed_url =
                Url::parse(&request_url).map_err(|error| SabrError::Protocol(format!("bad SABR url: {error}")))?;
            let mut request = http
                .client
                .post(parsed_url.clone())
                .header(reqwest::header::USER_AGENT, &http.user_agent)
                .header(reqwest::header::CONTENT_TYPE, "application/x-protobuf")
                .body(body);
            if let Some(cookie) = http.cookies.as_ref().and_then(|cookies| cookies(&parsed_url)) {
                request = request.header(reqwest::header::COOKIE, cookie);
            }
            let response = request.send().await?;
            let status = response.status();
            if status.is_redirection() {
                let location = response.headers().get(reqwest::header::LOCATION).and_then(|value| value.to_str().ok());
                match location {
                    Some(location) if location.contains("/videoplayback") => url = https_only(location),
                    other => return Err(SabrError::Protocol(format!("invalid SABR redirect: {other:?}"))),
                }
                continue;
            }
            if !status.is_success() {
                return Err(SabrError::Status(status.as_u16()));
            }
            let stream: ByteStream = response.bytes_stream().map(|chunk| chunk.map_err(io::Error::other)).boxed();
            let outcome = self.read_response(UmpReader::new(stream), tracks, index, &mut gate, false).await?;
            if let Some(redirect) = outcome.redirect {
                url = redirect;
                continue;
            }
            if tracks.is_complete() {
                gate.emit(tracks.progress(), true);
                return Ok(());
            }
            if outcome.completed_segments == 0 {
                empty_rounds += 1;
                last_error = "received zero segments".to_owned();
                last_protection = outcome.protection_status;
                tokio::time::sleep(Duration::from_millis(outcome.backoff_ms).max(MIN_EMPTY_ROUND_BACKOFF)).await;
            } else {
                empty_rounds = 0;
            }
        }
    }

    /// The player cache's answer to this round, when it holds the next segments.
    async fn serve_from_player_cache(&self, request: AbrRequest) -> Option<ByteStream> {
        let cache = self.player_cache.clone()?;
        match tokio::task::spawn_blocking(move || serve_sabr(&cache, &request)).await {
            Ok(Ok(SabrServe::Hit { body, segment_count, cached_bytes, .. })) => {
                self.log.debug(|| format!("cache-serve rn={} segments={segment_count} bytes={cached_bytes}", self.rn));
                // A read error reaches the round as a body error.
                Some(reader_stream(body, &tokio::runtime::Handle::current(), |_| {}))
            }
            Ok(Ok(_)) => None,
            Ok(Err(error)) => {
                self.log.warn("cache serve failed, using network", Some(&error));
                None
            }
            Err(error) => {
                self.log.warn("cache serve failed, using network", Some(&error));
                None
            }
        }
    }

    fn player_time(&self, with_video: bool) -> i64 {
        let audio = buffered_end(self.audio_segment.as_ref());
        if with_video { audio.min(buffered_end(self.video_segment.as_ref())) } else { audio }
    }

    fn next_request(
        &mut self,
        spec: &SabrStreamSpec,
        tracks: &Tracks,
        fallback_video: Option<&AdaptiveFormat>,
        started: Instant,
    ) -> Result<(Vec<u8>, Option<AbrRequest>), SabrError> {
        let with_video = tracks.video.is_some();
        let audio_buffered = buffered_range(self.audio_segment.as_ref())?;
        let video_buffered = if with_video { buffered_range(self.video_segment.as_ref())? } else { None };
        let time_since_last_seek = self.rng.random_range(1..=50);
        let audio_format = tracks.audio.format;
        let video_format = tracks.video.as_ref().map(|video| video.format);
        let mut selected = Vec::new();
        if self.audio_segment.is_some() {
            selected.push(audio_format.format_id());
        }
        if let (Some(video), Some(_)) = (video_format, &self.video_segment) {
            selected.push(video.format_id());
        }
        let buffered: Vec<BufferedRange> = audio_buffered.into_iter().chain(video_buffered).collect();
        let summary = buffered
            .iter()
            .map(|range| format!("{}:{}-{}", range.format_id.itag, range.start_segment_index, range.end_segment_index))
            .collect::<Vec<_>>()
            .join(", ");
        self.note(format!(
            "request playerTime={} cursor a={:?} v={:?} buffered=[{summary}] file a={} v={:?}",
            self.player_time(with_video),
            self.audio_segment.as_ref().map(|segment| segment.segment_index),
            self.video_segment.as_ref().map(|segment| segment.segment_index),
            tracks.audio.file.len(),
            tracks.video.as_ref().map(|video| video.file.len()),
        ));
        let selected_video: Vec<messages::FormatId> =
            video_format.or(fallback_video).map(AdaptiveFormat::format_id).into_iter().collect();
        let cache_request = self.player_cache.is_some().then(|| AbrRequest {
            player_time_ms: self.player_time(with_video),
            enabled_track_types_bitfield: Some(if with_video { 0 } else { 1 }),
            drc_enabled: audio_format.is_drc,
            selected_formats: selected.clone(),
            buffered_ranges: buffered.clone(),
            selected_audio_formats: vec![audio_format.format_id()],
            selected_video_formats: selected_video.clone(),
            playback_cookie: Some(self.playback_cookie.clone()),
            ..AbrRequest::default()
        });
        let request = VideoPlaybackAbrRequest {
            client_abr_state: ClientAbrState {
                player_time_ms: self.player_time(with_video),
                enabled_track_types_bitfield: Some(if with_video { 0 } else { 1 }),
                drc_enabled: audio_format.is_drc,
                viewport_width: Some(VIEWPORT.0),
                viewport_height: Some(VIEWPORT.1),
                time_since_last_seek: Some(time_since_last_seek),
                visibility: Some(0),
                elapsed_wall_time_ms: Some(started.elapsed().as_millis() as i64),
                time_since_last_action: Some(self.rng.random_range(0..time_since_last_seek)),
                ..ClientAbrState::default()
            },
            selected_formats: selected,
            buffered_ranges: buffered,
            ustreamer_config: &spec.ustreamer_config,
            selected_audio_formats: vec![audio_format.format_id()],
            selected_video_formats: selected_video,
            client_info: &spec.client_info,
            po_token: spec.po_token.as_deref(),
            playback_cookie: &self.playback_cookie,
            sabr_contexts: self.contexts.values().collect(),
        };
        Ok((request.encode(), cache_request))
    }

    async fn read_response(
        &mut self,
        mut body: UmpReader<ByteStream>,
        tracks: &mut Tracks<'_, '_>,
        index: Option<&SegmentIndex>,
        gate: &mut ProgressGate<'_>,
        from_cache: bool,
    ) -> Result<RoundOutcome, SabrError> {
        self.note(format!("  {} response", if from_cache { "cache" } else { "network" }));
        let mut open: HashMap<u8, PartState> = HashMap::new();
        let mut outcome =
            RoundOutcome { completed_segments: 0, redirect: None, backoff_ms: 0, protection_status: None };
        while let Some((part_id, size)) = body.part_header().await.map_err(SabrError::Body)? {
            match part_id {
                part::SABR_ERROR => {
                    let payload = body.read_bytes(size).await.map_err(SabrError::Body)?;
                    return Err(SabrError::Server(messages::string_field_1(&payload).unwrap_or_default()));
                }
                part::SABR_REDIRECT => {
                    let payload = body.read_bytes(size).await.map_err(SabrError::Body)?;
                    match messages::string_field_1(&payload) {
                        Some(redirect) if redirect.contains("/videoplayback") => {
                            outcome.redirect = Some(https_only(&redirect));
                            break;
                        }
                        other => return Err(SabrError::Protocol(format!("invalid SABR redirect part: {other:?}"))),
                    }
                }
                part::MEDIA_HEADER => {
                    let payload = body.read_bytes(size).await.map_err(SabrError::Body)?;
                    let header = MediaHeader::parse(&payload)?;
                    if let Some(index) = index {
                        index.save(&header)?;
                    }
                    if open.contains_key(&header.header_id) {
                        return Err(SabrError::Protocol(format!("duplicate UMP header id {}", header.header_id)));
                    }
                    let Some(kind) = tracks.kind_of(&header) else {
                        return Err(SabrError::Protocol(format!(
                            "unexpected media header {} {}",
                            header.video_id,
                            header.describe()
                        )));
                    };
                    open.insert(
                        header.header_id,
                        PartState { header, kind, total_read: 0, writing: false, beyond_file_end: false },
                    );
                }
                part::MEDIA_DATA => {
                    let header_id = body.read_byte().await.map_err(SabrError::Body)?;
                    let data_size = size.saturating_sub(1);
                    let Some(state) = open.get_mut(&header_id) else {
                        return Err(SabrError::Protocol(format!("media data for unknown header {header_id}")));
                    };
                    let was_beyond = state.beyond_file_end;
                    write_media_data(&mut body, state, tracks.get(state.kind).file, data_size).await?;
                    state.total_read += data_size as i64;
                    if state.beyond_file_end && !was_beyond {
                        let (kind, description) = (state.kind, state.header.describe());
                        self.reseed_cursor(kind, &description, tracks, index)?;
                    }
                    gate.emit(tracks.progress(), false);
                }
                part::MEDIA_END => {
                    let header_id = body.read_byte().await.map_err(SabrError::Body)?;
                    body.skip(size.saturating_sub(1)).await.map_err(SabrError::Body)?;
                    let Some(state) = open.remove(&header_id) else {
                        return Err(SabrError::Protocol(format!("media end for unknown header {header_id}")));
                    };
                    let file_len = tracks.file_len(state.kind) as i64;
                    let length = if state.header.content_length > 0 {
                        state.header.content_length
                    } else {
                        state.header.data_offset + state.total_read
                    };
                    let reached_end = !state.beyond_file_end && file_len >= state.header.start_range + length;
                    let advanced = reached_end && self.advance_if_contiguous(&state.header, state.kind);
                    if advanced {
                        outcome.completed_segments += 1;
                    }
                    let verdict = if state.beyond_file_end {
                        "beyond-file-end"
                    } else if advanced {
                        "advanced"
                    } else {
                        "held"
                    };
                    self.note(format!(
                        "    {} got={} file={file_len} {verdict}",
                        state.header.describe(),
                        state.total_read
                    ));
                }
                part::NEXT_REQUEST_POLICY => {
                    let payload = body.read_bytes(size).await.map_err(SabrError::Body)?;
                    let policy = NextRequestPolicy::parse(&payload)?;
                    if let Some(cookie) = policy.playback_cookie {
                        self.playback_cookie = cookie;
                    }
                    outcome.backoff_ms = policy.backoff_time_ms.unwrap_or(0);
                }
                part::STREAM_PROTECTION_STATUS => {
                    let payload = body.read_bytes(size).await.map_err(SabrError::Body)?;
                    let status = messages::stream_protection_status(&payload);
                    outcome.protection_status = status;
                    if let Some(status) = status.filter(|status| *status != 2) {
                        self.po_token_valid = status != 3;
                    }
                }
                part::SABR_CONTEXT_UPDATE => {
                    let payload = body.read_bytes(size).await.map_err(SabrError::Body)?;
                    match Message::parse(&payload).and_then(|message| SabrContextUpdate::from_ump(&message)) {
                        Ok(update) => {
                            self.contexts.insert(update.context_type, update);
                        }
                        // A dropped update changes what the server does next round.
                        Err(error) => self.log.warn("SABR_CONTEXT_UPDATE parse failed, dropped", Some(&error)),
                    }
                }
                _ => body.skip(size).await.map_err(SabrError::Body)?,
            }
        }
        self.note(format!(
            "  completed={} backoffMs={} sps={:?} redirect={}",
            outcome.completed_segments,
            outcome.backoff_ms,
            outcome.protection_status,
            outcome.redirect.is_some()
        ));
        Ok(outcome)
    }

    /// Data arrived past the file end, so the cursor claims bytes the file lacks: rebuild it.
    fn reseed_cursor(
        &mut self,
        kind: Kind,
        description: &str,
        tracks: &mut Tracks,
        index: Option<&SegmentIndex>,
    ) -> Result<(), SabrError> {
        let track = tracks.get(kind);
        let seeded = seed_cursor(index, track)?;
        let cursor = if kind == Kind::Audio { &mut self.audio_segment } else { &mut self.video_segment };
        let previous = cursor.as_ref().map(|segment| segment.segment_index);
        let next = seeded.as_ref().map(|segment| segment.segment_index);
        *cursor = seeded;
        let message = format!(
            "{description} past file end {}; cursor seg {previous:?} reseeded to {next:?}\n{}",
            track.file.len(),
            self.trail_text()
        );
        self.log.warn(&message, None);
        Ok(())
    }

    fn advance_if_contiguous(&mut self, header: &MediaHeader, kind: Kind) -> bool {
        let cursor = if kind == Kind::Audio { &mut self.audio_segment } else { &mut self.video_segment };
        let expected = cursor.as_ref().map_or(0, |segment| segment.segment_index + 1);
        if header.segment_index != expected {
            return false;
        }
        *cursor = Some(header.clone());
        true
    }

    fn note(&mut self, line: String) {
        if self.trail.len() == ROUND_TRAIL_LINES {
            self.trail.pop_front();
        }
        self.trail.push_back(line);
    }

    fn trail_text(&self) -> String {
        self.trail.iter().map(String::as_str).collect::<Vec<_>>().join("\n")
    }
}

/// Writes one MEDIA_DATA chunk, skipping the prefix the file already holds (the server may resend
/// bytes inside our buffered range).
async fn write_media_data(
    body: &mut UmpReader<ByteStream>,
    state: &mut PartState,
    file: &mut DownloadFile,
    data_size: u64,
) -> Result<(), SabrError> {
    if state.beyond_file_end {
        return body.skip(data_size).await.map_err(SabrError::Body);
    }
    let mut remaining = data_size;
    if !state.writing {
        let file_end = file.len() as i64;
        let part_start = state.header.start_range + state.header.data_offset + state.total_read;
        let part_end = part_start + data_size as i64;
        if !(part_start..part_end).contains(&file_end) {
            if file_end < part_start {
                state.beyond_file_end = true;
            }
            return body.skip(data_size).await.map_err(SabrError::Body);
        }
        let skip = (file_end - part_start) as u64;
        body.skip(skip).await.map_err(SabrError::Body)?;
        remaining -= skip;
        state.writing = true;
    }
    while remaining > 0 {
        let chunk = body.chunk(remaining).await.map_err(SabrError::Body)?;
        file.append(&chunk).map_err(SabrError::File)?;
        remaining -= chunk.len() as u64;
    }
    Ok(())
}

fn buffered_end(segment: Option<&MediaHeader>) -> i64 {
    match segment {
        Some(header) if !header.is_init_segment => header.start_ms.unwrap_or(0) + header.duration_ms.unwrap_or(0),
        _ => 0,
    }
}

fn buffered_range(segment: Option<&MediaHeader>) -> Result<Option<BufferedRange>, SabrError> {
    let Some(header) = segment.filter(|header| !header.is_init_segment) else { return Ok(None) };
    let (Some(start_ms), Some(duration_ms)) = (header.start_ms, header.duration_ms) else {
        return Err(SabrError::Protocol(format!("segment without a time window: {}", header.describe())));
    };
    Ok(Some(BufferedRange {
        format_id: messages::FormatId {
            itag: header.itag,
            lmt: header.lmt,
            xtag: header.xtag.clone().unwrap_or_default(),
        },
        start_ms: 0,
        duration_ms: start_ms + duration_ms,
        start_segment_index: 1,
        end_segment_index: header.segment_index,
    }))
}

/// The last whole segment covering the file's end, or none for an empty or un-indexed download.
fn seed_cursor(index: Option<&SegmentIndex>, track: &SabrTrack) -> Result<Option<MediaHeader>, SabrError> {
    let length = track.file.len() as i64;
    match index {
        Some(index) if length > 0 => Ok(index.find_by_byte_offset(track.format.itag, track.format.lmt, length - 1)?),
        _ => Ok(None),
    }
}

/// Edits the raw query: re-serializing it through a URL type could re-encode the signed params.
pub fn set_query_param(url: &str, key: &str, value: &str) -> String {
    let encoded = format!("{key}={value}");
    let Some((prefix, query)) = url.split_once('?') else { return format!("{url}?{encoded}") };
    let mut kept: Vec<&str> =
        query.split('&').filter(|pair| !pair.is_empty() && pair.split('=').next() != Some(key)).collect();
    kept.push(&encoded);
    format!("{prefix}?{}", kept.join("&"))
}

// googlevideo sometimes redirects to http:// on hosts that also serve https.
fn https_only(url: &str) -> String {
    match url.get(..7) {
        Some(scheme) if scheme.eq_ignore_ascii_case("http://") => format!("https://{}", &url[7..]),
        _ => url.to_owned(),
    }
}

const CPN_ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// 16-char client playback nonce in YouTube's alphabet.
fn generate_cpn(rng: &mut impl Rng) -> String {
    (0..16).map(|_| CPN_ALPHABET[rng.random_range(0..CPN_ALPHABET.len())] as char).collect()
}
