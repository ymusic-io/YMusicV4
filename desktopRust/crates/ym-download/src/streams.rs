//! The download step: resolve the SABR inputs, pick the streams, fetch each to its scratch file
//! (resumable from the scratch length, re-attesting once if the PO token is rejected), then the
//! captions over plain HTTP.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde_json::Value;
use tokio::sync::Mutex;
use ym_common::log::Logger;
use ym_ytmedia::sabr::{AdaptiveFormat, CaptionTrack, DownloadFile, SabrError, SabrHttp, SabrSession, SabrTrack};
use ym_ytmedia::segment_index::SegmentIndex;

use crate::error::{DownloadError, sabr_failure};
use crate::model::{DownloadFormat, DownloadTask, StreamId};
use crate::selection::{
    AudioCodec, audio_at, best_audio, remembered_audio, remembered_video, same_language, stream_id, video_nearest,
};
use crate::spec::{ResolvedVideo, caption_key};
use crate::storage::DownloadStorage;
use crate::tags::cap_cue_duration;

pub const AUDIO_SCRATCH: &str = "audio";
const VIDEO_SCRATCH: &str = "video";
const PLAYER_GATE_INTERVAL: Duration = Duration::from_secs(3);

/// The SABR inputs for a video, from the app's player engine.
pub trait SpecResolver: Send + Sync {
    /// The player response plus a content PO token, mapped (see [`crate::spec::resolve`]).
    fn resolve<'a>(&'a self, video_id: &'a str) -> BoxFuture<'a, Result<ResolvedVideo, DownloadError>>;
    /// The player response alone: what the options dialog reads; no PO token minted.
    fn player_response<'a>(&'a self, video_id: &'a str) -> BoxFuture<'a, Result<Value, DownloadError>>;
    /// The spec on a re-minted PO token, after googlevideo withheld or rejected the download's.
    fn remint<'a>(&'a self, _video_id: &'a str) -> BoxFuture<'a, Result<SpecRemint, DownloadError>> {
        Box::pin(async { Ok(SpecRemint::Unavailable) })
    }
}

/// What [`SpecResolver::remint`] got.
pub enum SpecRemint {
    Minted(Box<ResolvedVideo>),
    /// googlevideo accepts the player's current token: the download's own requests are at fault.
    PlayerAccepted,
    /// No re-mint: the player session's are spent, or the engine bundle predates them.
    Unavailable,
}

/// Every fetch drives the single player engine and hits /player, so they go one at a time, each at
/// least a few seconds after the previous one started.
pub struct PlayerGate {
    last_start: Mutex<Option<Instant>>,
}

impl PlayerGate {
    pub fn new() -> Self {
        Self { last_start: Mutex::new(None) }
    }

    pub async fn run<T>(&self, fetch: impl Future<Output = T>) -> T {
        let mut last_start = self.last_start.lock().await;
        if let Some(wait) = last_start.and_then(|last| PLAYER_GATE_INTERVAL.checked_sub(last.elapsed())) {
            tokio::time::sleep(wait).await;
        }
        *last_start = Some(Instant::now());
        fetch.await
    }
}

impl Default for PlayerGate {
    fn default() -> Self {
        Self::new()
    }
}

/// What a run reports as it goes; called from the download task.
pub trait RunObserver: Send {
    fn preparing(&mut self);
    fn downloading(&mut self);
    fn processing(&mut self);
    fn progress(&mut self, done: i64, total: i64);
    /// The output or streams differ from the task's: persist them before any byte lands.
    fn streams(
        &mut self,
        format: DownloadFormat,
        audio: StreamId,
        video: Option<StreamId>,
    ) -> Result<(), DownloadError>;
}

pub struct DownloadedStream {
    pub format: AdaptiveFormat,
    pub path: PathBuf,
}

pub struct DownloadedCaption {
    pub language_code: String,
    pub is_asr: bool,
    pub name: String,
    pub path: PathBuf,
}

pub struct DownloadedStreams {
    pub audio: DownloadedStream,
    pub video: Option<DownloadedStream>,
    pub captions: Vec<DownloadedCaption>,
    /// From the player response, for when the request carried none.
    pub artist: Option<String>,
    loudness_target_lkfs: Option<f64>,
    /// The task's, or MP4 when it asked for WebM video and the video has no WebM stream.
    pub format: DownloadFormat,
}

impl DownloadedStreams {
    /// dB from this audio's measured loudness to the video's target.
    pub fn loudness_gain_db(&self) -> Option<f64> {
        let measured = self.audio.format.loudness_lkfs?;
        let target = self.loudness_target_lkfs?;
        Some(((target - measured) * 100.).round() / 100.)
    }
}

/// The captions to fetch: the ones the task names, else the track in the preferred language (or
/// the default one), else none.
pub fn captions_to_download<'a>(
    tracks: &'a [CaptionTrack],
    choice: Option<&[String]>,
    preferred_language: Option<&str>,
) -> Vec<&'a CaptionTrack> {
    if let Some(keys) = choice {
        return keys
            .iter()
            .filter_map(|key| tracks.iter().find(|track| caption_key(&track.language_code, track.is_asr) == *key))
            .collect();
    }
    let Some(language) = preferred_language else { return Vec::new() };
    tracks
        .iter()
        .find(|track| track.language_code.eq_ignore_ascii_case(language))
        .or_else(|| tracks.iter().find(|track| same_language(&track.language_code, language)))
        .or_else(|| tracks.iter().find(|track| track.is_default))
        .into_iter()
        .collect()
}

pub struct StreamDownloader {
    pub resolver: Arc<dyn SpecResolver>,
    pub gate: Arc<PlayerGate>,
    pub sabr: SabrHttp,
    /// Follows redirects: captions and cover art.
    pub client: reqwest::Client,
    pub storage: Arc<DownloadStorage>,
    pub index: Option<Arc<SegmentIndex>>,
    pub player_cache: Option<Arc<ym_ytmedia::cache::MediaCache>>,
    pub log: Logger,
}

impl StreamDownloader {
    async fn resolve(&self, video_id: &str) -> Result<ResolvedVideo, DownloadError> {
        self.gate.run(self.resolver.resolve(video_id)).await
    }

    pub async fn download(
        &self,
        task: &DownloadTask,
        observer: &mut dyn RunObserver,
    ) -> Result<DownloadedStreams, DownloadError> {
        observer.preparing();
        let resolved = self.resolve(&task.video_id).await?;
        let formats = &resolved.spec.formats;
        let format = self.output_format(task, formats);
        let (audio, video) = select_formats(task, format, formats)?;
        let (audio, video) = (audio.clone(), video.cloned());
        let captions: Vec<CaptionTrack> = captions_to_download(
            &resolved.spec.caption_tracks,
            task.captions.as_deref(),
            task.caption_language.as_deref(),
        )
        .into_iter()
        .cloned()
        .collect();

        let audio_path = self.storage.scratch(&task.id, AUDIO_SCRATCH)?;
        let video_path = match &video {
            Some(_) => Some(self.storage.scratch(&task.id, VIDEO_SCRATCH)?),
            None => None,
        };
        self.drop_other_stream(task.audio_stream, &audio, &audio_path)?;
        if let (Some(video), Some(path)) = (&video, &video_path) {
            self.drop_other_stream(task.video_stream, video, path)?;
        }
        let audio_id = stream_id(&audio);
        let video_id = video.as_ref().map(stream_id);
        if format != task.format || Some(audio_id) != task.audio_stream || video_id != task.video_stream {
            observer.streams(format, audio_id, video_id)?;
        }

        observer.downloading();
        self.download_tracks(
            &task.video_id,
            resolved.spec.clone(),
            &audio,
            video.as_ref(),
            &audio_path,
            video_path.as_deref(),
            observer,
        )
        .await?;

        // KB-scale, fetched whole after the media; a caption that fails is skipped, not fatal.
        let mut caption_files = Vec::new();
        for track in &captions {
            match self.download_caption(&task.id, track).await {
                Ok(caption) => caption_files.push(caption),
                Err(error) => self.log.warn(&format!("caption {} failed: {error}", track.language_code), Some(&error)),
            }
        }
        Ok(DownloadedStreams {
            audio: DownloadedStream { format: audio, path: audio_path },
            video: video.zip(video_path).map(|(format, path)| DownloadedStream { format, path }),
            captions: caption_files,
            artist: resolved.artist,
            loudness_target_lkfs: resolved.loudness_target_lkfs,
            format,
        })
    }

    /// Runs before the new pick is recorded, so a row never names a stream its scratch doesn't hold.
    fn drop_other_stream(
        &self,
        remembered: Option<StreamId>,
        picked: &AdaptiveFormat,
        path: &Path,
    ) -> Result<(), DownloadError> {
        match remembered {
            Some(stream) if stream != stream_id(picked) => {
                self.log.info(&format!("stream {stream:?} is gone; restarting its scratch on itag {}", picked.itag));
                match std::fs::remove_file(path) {
                    Err(error) if error.kind() != std::io::ErrorKind::NotFound => {
                        Err(DownloadError::file("R70021", "drop the stale scratch", error))
                    }
                    _ => Ok(()),
                }
            }
            _ => Ok(()),
        }
    }

    fn output_format(&self, task: &DownloadTask, formats: &[AdaptiveFormat]) -> DownloadFormat {
        match task.format {
            DownloadFormat::AudioWebm if best_audio(formats, AudioCodec::Opus, None, None).is_none() => {
                self.log.info(&format!("{} has no Opus audio stream; downloading M4A", task.video_id));
                DownloadFormat::M4a
            }
            DownloadFormat::VideoWebm
                if video_nearest(formats, task.requested_height, DownloadFormat::VideoWebm).is_none() =>
            {
                self.log.info(&format!("{} has no WebM video stream; downloading MP4", task.video_id));
                DownloadFormat::VideoMp4
            }
            format => format,
        }
    }

    #[allow(clippy::too_many_arguments)]
    async fn download_tracks(
        &self,
        video_id: &str,
        mut spec: ym_ytmedia::sabr::SabrStreamSpec,
        audio: &AdaptiveFormat,
        video: Option<&AdaptiveFormat>,
        audio_path: &Path,
        video_path: Option<&Path>,
        observer: &mut dyn RunObserver,
    ) -> Result<(), DownloadError> {
        let mut retried = false;
        loop {
            let mut audio_file = DownloadFile::open(audio_path)
                .map_err(|error| DownloadError::file("R70022", "open audio scratch", error))?;
            let mut video_file = match video_path {
                Some(path) => Some(
                    DownloadFile::open(path)
                        .map_err(|error| DownloadError::file("R70037", "open video scratch", error))?,
                ),
                None => None,
            };
            let mut session = SabrSession::new(self.log.fork("sabr")).reuse_player_cache(self.player_cache.clone());
            let mut report = |progress: ym_ytmedia::sabr::Progress| {
                observer.progress(progress.done() as i64, progress.total() as i64)
            };
            let result = session
                .download(
                    &self.sabr,
                    &spec,
                    SabrTrack { format: audio, file: &mut audio_file },
                    video.zip(video_file.as_mut()).map(|(format, file)| SabrTrack { format, file }),
                    self.index.as_deref(),
                    &mut report,
                )
                .await;
            match result {
                Ok(()) => return Ok(()),
                Err(error @ (SabrError::PoTokenInvalid | SabrError::AttestationPending)) if !retried => {
                    retried = true;
                    spec = self.spec_on_new_token(video_id, error).await?;
                }
                Err(error) => return Err(sabr_failure(error, retried)),
            }
        }
    }

    /// The spec for the one retry: the player re-mints its token, unless googlevideo accepts the
    /// token it has, in which case the download itself is at fault.
    async fn spec_on_new_token(
        &self,
        video_id: &str,
        refused: SabrError,
    ) -> Result<ym_ytmedia::sabr::SabrStreamSpec, DownloadError> {
        match self.gate.run(self.resolver.remint(video_id)).await? {
            SpecRemint::Minted(video) => {
                self.log.warn(&format!("PO token refused, retrying on a re-minted one: {refused}"), None);
                Ok(video.spec)
            }
            SpecRemint::PlayerAccepted => Err(DownloadError::technical(
                "R70038",
                format!("googlevideo refused the download while it accepts the player's PO token: {refused}"),
            )),
            SpecRemint::Unavailable => match refused {
                SabrError::PoTokenInvalid => {
                    self.log.warn("PO token invalid, re-attesting", None);
                    Ok(self.resolve(video_id).await?.spec)
                }
                other => Err(sabr_failure(other, false)),
            },
        }
    }

    /// The caption's WebVTT into a scratch file. A body without cues is a failure, never an empty track.
    async fn download_caption(&self, task_id: &str, track: &CaptionTrack) -> Result<DownloadedCaption, DownloadError> {
        let path =
            self.storage.scratch(task_id, &format!("cc.{}.vtt", caption_key(&track.language_code, track.is_asr)))?;
        let separator = if track.base_url.contains('?') { '&' } else { '?' };
        let response = self
            .client
            .get(format!("{}{separator}fmt=vtt", track.base_url))
            .header(reqwest::header::USER_AGENT, &self.sabr.user_agent)
            .send()
            .await
            .map_err(|error| DownloadError::Network(error.to_string()))?;
        if !response.status().is_success() {
            return Err(DownloadError::technical(
                "R70023",
                format!("caption {} HTTP {}", track.language_code, response.status()),
            ));
        }
        let vtt = response.text().await.map_err(|error| DownloadError::Network(error.to_string()))?;
        if !vtt.contains("-->") {
            return Err(DownloadError::technical(
                "R70024",
                format!("caption {} has no cues ({} bytes)", track.language_code, vtt.len()),
            ));
        }
        std::fs::write(&path, cap_cue_duration(&vtt))
            .map_err(|error| DownloadError::file("R70025", "write caption", error))?;
        Ok(DownloadedCaption {
            language_code: track.language_code.clone(),
            is_asr: track.is_asr,
            name: track.name.clone(),
            path,
        })
    }
}

fn select_formats<'a>(
    task: &DownloadTask,
    format: DownloadFormat,
    formats: &'a [AdaptiveFormat],
) -> Result<(&'a AdaptiveFormat, Option<&'a AdaptiveFormat>), DownloadError> {
    let codec = AudioCodec::of(format);
    let track = task.audio_track_id.as_deref();
    let language = task.audio_language.as_deref();
    let audio = task
        .audio_stream
        .and_then(|stream| remembered_audio(formats, codec, stream, track, language))
        .or_else(|| match format {
            DownloadFormat::M4a | DownloadFormat::AudioWebm => {
                audio_at(formats, codec, task.audio_quality, track, language)
            }
            // A video keeps its best audio, and MP3 sets its quality when it encodes.
            _ => best_audio(formats, codec, track, language),
        })
        .ok_or_else(|| {
            DownloadError::technical("R70026", format!("no {codec:?} audio for {} ({format:?})", task.video_id))
        })?;
    let video = if format.is_video() {
        let picked = task
            .video_stream
            .and_then(|stream| remembered_video(formats, stream, format))
            .or_else(|| video_nearest(formats, task.requested_height, format))
            .ok_or_else(|| DownloadError::technical("R70027", format!("no video format for {}", task.video_id)))?;
        Some(picked)
    } else {
        None
    };
    Ok((audio, video))
}
