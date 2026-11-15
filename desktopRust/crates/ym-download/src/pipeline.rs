//! One task end to end: streams to scratch, ffmpeg mux/convert with tags, publish into the user's
//! folder. The finished scratch is marked ready before publishing, so a run that failed only to
//! publish retries from publish alone.

use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use ym_common::log::Logger;
use ym_ytmedia::sabr::AdaptiveFormat;

use crate::error::DownloadError;
use crate::ffmpeg::{self, Ffmpeg, Metadata, SubtitleInput, iso639_2};
use crate::model::{DownloadFormat, DownloadTask};
use crate::names;
use crate::storage::DownloadStorage;
use crate::streams::{AUDIO_SCRATCH, DownloadedCaption, DownloadedStreams, RunObserver, StreamDownloader};
use crate::tags::{Lyrics, insert_id3_lyrics, insert_opus_comments, opus_picture_comment, vtt_to_lrc};

const OUT_SCRATCH: &str = "out";
const OUT_TAGGED_SCRATCH: &str = "out-tagged";
const READY_SCRATCH: &str = "ready";
const COVER_SCRATCH: &str = "cover";

/// A published download: its file and the media facts library ingest uses.
#[derive(Clone, Debug)]
pub struct PipelineResult {
    pub uri: String,
    pub bytes: i64,
    pub duration_ms: Option<i64>,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub loudness_gain_db: Option<f64>,
}

struct ReadyOutput {
    scratch_name: String,
    duration_ms: Option<i64>,
    width: Option<i32>,
    height: Option<i32>,
    loudness_gain_db: Option<f64>,
}

pub struct Pipeline {
    pub downloader: StreamDownloader,
    pub ffmpeg: Option<Ffmpeg>,
    pub storage: Arc<DownloadStorage>,
    pub log: Logger,
}

impl Pipeline {
    pub async fn run(
        &self,
        task: &DownloadTask,
        folder: &Path,
        observer: &mut dyn RunObserver,
    ) -> Result<PipelineResult, DownloadError> {
        if let Some(ready) = self.ready_output(&task.id)? {
            observer.processing();
            return self.publish(task, task.format, &ready, folder);
        }
        let streams = self.downloader.download(task, observer).await?;
        self.assemble(task, &streams, folder, observer).await
    }

    async fn assemble(
        &self,
        task: &DownloadTask,
        streams: &DownloadedStreams,
        folder: &Path,
        observer: &mut dyn RunObserver,
    ) -> Result<PipelineResult, DownloadError> {
        let format = streams.format;
        let audio_format = &streams.audio.format;
        let video_format = streams.video.as_ref().map(|video| &video.format);
        observer.processing();
        let gain = streams.loudness_gain_db();

        // The raw best-AAC stream is a playable .m4a.
        let Some(ffmpeg) = &self.ffmpeg else {
            if format != DownloadFormat::M4a {
                return Err(DownloadError::technical("R70028", format!("ffmpeg unavailable: {format:?} needs a mux")));
            }
            let ready = self.mark_ready(&task.id, AUDIO_SCRATCH, audio_format, None, gain)?;
            return self.publish(task, format, &ready, folder);
        };

        let duration_ms = audio_format.approx_duration_ms.max(video_format.map_or(0, |video| video.approx_duration_ms));
        if duration_ms > 0 {
            observer.progress(0, duration_ms);
        }
        let mut on_progress = |microseconds: i64| {
            if duration_ms > 0 {
                observer.progress((microseconds / 1000).clamp(0, duration_ms), duration_ms);
            }
        };
        let audio = &streams.audio.path;
        let out = self.storage.scratch(&task.id, OUT_SCRATCH)?;
        let metadata = tags(task, streams.artist.as_deref(), gain);
        let subtitles: Vec<SubtitleInput> = streams
            .captions
            .iter()
            .map(|caption| SubtitleInput {
                path: caption.path.clone(),
                language: caption.language_code.clone(),
                title: Some(caption.name.clone()),
            })
            .collect();
        let mut out_name = OUT_SCRATCH;
        match format {
            DownloadFormat::Mp3 => {
                let cover = self.cover_art(task).await?;
                let args = ffmpeg::mp3_args(audio, &out, task.audio_quality, cover.as_deref(), &metadata);
                self.with_cover_retry(ffmpeg, args, cover.is_some(), &mut on_progress, || {
                    ffmpeg::mp3_args(audio, &out, task.audio_quality, None, &metadata)
                })
                .await?;
                if !streams.captions.is_empty() && self.with_lyrics(&task.id, &out, &streams.captions)? {
                    out_name = OUT_TAGGED_SCRATCH;
                }
            }
            DownloadFormat::M4a => {
                let cover = self.cover_art(task).await?;
                let args = ffmpeg::m4a_args(audio, &out, &subtitles, cover.as_deref(), &metadata);
                self.with_cover_retry(ffmpeg, args, cover.is_some(), &mut on_progress, || {
                    ffmpeg::m4a_args(audio, &out, &subtitles, None, &metadata)
                })
                .await?;
            }
            DownloadFormat::AudioWebm => {
                ffmpeg.run(ffmpeg::opus_args(audio, &out, &metadata), Some(&mut on_progress)).await?;
                if self.with_opus_tags(task, &out, &streams.captions).await? {
                    out_name = OUT_TAGGED_SCRATCH;
                }
            }
            DownloadFormat::VideoWebm | DownloadFormat::VideoMp4 => {
                let video = streams
                    .video
                    .as_ref()
                    .ok_or_else(|| DownloadError::technical("R70029", "video output without a video stream"))?;
                let webm = format == DownloadFormat::VideoWebm;
                let args = ffmpeg::mux_args(&video.path, audio, &out, &subtitles, &metadata, webm);
                ffmpeg.run(args, Some(&mut on_progress)).await?;
            }
        }
        let ready = self.mark_ready(&task.id, out_name, audio_format, video_format, gain)?;
        self.publish(task, format, &ready, folder)
    }

    /// A cover ffmpeg rejects is dropped and the conversion rerun without it: cover art is
    /// best-effort, the song still publishes.
    async fn with_cover_retry(
        &self,
        ffmpeg: &Ffmpeg,
        args: Vec<String>,
        has_cover: bool,
        on_progress: &mut (dyn FnMut(i64) + Send),
        without_cover: impl FnOnce() -> Vec<String>,
    ) -> Result<(), DownloadError> {
        match ffmpeg.run(args, Some(&mut *on_progress)).await {
            Err(error @ DownloadError::Technical { .. }) if has_cover => {
                self.log.warn("ffmpeg failed with cover art; retrying without it", Some(&error));
                ffmpeg.run(without_cover(), Some(on_progress)).await
            }
            result => result,
        }
    }

    /// The largest JPEG thumbnail (the packed ffmpeg has no WebP decoder), or none.
    async fn cover_art(&self, task: &DownloadTask) -> Result<Option<PathBuf>, DownloadError> {
        let path = self.storage.scratch(&task.id, COVER_SCRATCH)?;
        let mut candidates = vec![format!("https://i.ytimg.com/vi/{}/maxresdefault.jpg", task.video_id)];
        candidates.extend(task.thumbnail_url.clone());
        candidates.push(format!("https://i.ytimg.com/vi/{}/hqdefault.jpg", task.video_id));
        candidates.dedup();
        for url in candidates {
            match self.fetch_jpeg(&url).await {
                Ok(Some(bytes)) => {
                    fs::write(&path, bytes).map_err(|error| DownloadError::file("R70030", "write cover art", error))?;
                    return Ok(Some(path));
                }
                Ok(None) => {}
                Err(error) => self.log.warn(&format!("cover art fetch failed: {url}"), Some(&error)),
            }
        }
        self.log.warn(&format!("no JPEG cover art for {}; publishing without one", task.video_id), None);
        Ok(None)
    }

    async fn fetch_jpeg(&self, url: &str) -> Result<Option<Vec<u8>>, reqwest::Error> {
        let downloader = &self.downloader;
        let response =
            downloader.client.get(url).header(reqwest::header::USER_AGENT, &downloader.sabr.user_agent).send().await?;
        if !response.status().is_success() {
            return Ok(None);
        }
        let bytes = response.bytes().await?;
        Ok(bytes.starts_with(&[0xFF, 0xD8]).then(|| bytes.to_vec()))
    }

    /// Writes the captions into the MP3's tag as LRC lyrics; false keeps the untagged file
    /// (nothing to write, or a tag it can't extend). Best-effort like the captions themselves.
    fn with_lyrics(&self, task_id: &str, mp3: &Path, captions: &[DownloadedCaption]) -> Result<bool, DownloadError> {
        let tagged = self.storage.scratch(task_id, OUT_TAGGED_SCRATCH)?;
        let attempt = || -> std::io::Result<bool> {
            let mut lyrics = Vec::new();
            for caption in captions {
                let lrc = vtt_to_lrc(&fs::read_to_string(&caption.path)?);
                if !lrc.is_empty() {
                    lyrics.push(Lyrics {
                        language: iso639_2(&caption.language_code),
                        description: caption.name.clone(),
                        text: lrc,
                    });
                }
            }
            if lyrics.is_empty() {
                return Ok(false);
            }
            let mut sink = BufWriter::new(File::create(&tagged)?);
            let written = insert_id3_lyrics(&mut BufReader::new(File::open(mp3)?), &mut sink, &lyrics)?;
            sink.flush()?;
            Ok(written)
        };
        match attempt() {
            Ok(true) => return Ok(true),
            Ok(false) => {}
            Err(error) => self.log.warn("embedding lyrics failed; publishing without them", Some(&error)),
        }
        remove_if_present(&tagged)?;
        Ok(false)
    }

    /// Cover art and captions (as LRC) into the Opus tags; false keeps the untagged file.
    async fn with_opus_tags(
        &self,
        task: &DownloadTask,
        opus: &Path,
        captions: &[DownloadedCaption],
    ) -> Result<bool, DownloadError> {
        let mut comments = Vec::new();
        if let Some(cover) = self.cover_art(task).await? {
            match fs::read(&cover) {
                Ok(jpeg) => comments.push(opus_picture_comment(&jpeg)),
                Err(error) => self.log.warn("opus cover failed; publishing without it", Some(&error)),
            }
        }
        for caption in captions {
            match fs::read_to_string(&caption.path) {
                Ok(vtt) => {
                    let lrc = vtt_to_lrc(&vtt);
                    if !lrc.is_empty() {
                        comments.push(format!("LYRICS={lrc}"));
                    }
                }
                Err(error) => self.log.warn(&format!("opus lyrics {} failed", caption.language_code), Some(&error)),
            }
        }
        if comments.is_empty() {
            return Ok(false);
        }
        let tagged = self.storage.scratch(&task.id, OUT_TAGGED_SCRATCH)?;
        let attempt = || -> std::io::Result<()> {
            let mut sink = BufWriter::new(File::create(&tagged)?);
            insert_opus_comments(&mut BufReader::new(File::open(opus)?), &mut sink, &comments)?;
            sink.flush()
        };
        match attempt() {
            Ok(()) => Ok(true),
            Err(error) => {
                self.log.warn("opus tags failed; publishing without them", Some(&error));
                remove_if_present(&tagged)?;
                Ok(false)
            }
        }
    }

    fn mark_ready(
        &self,
        task_id: &str,
        scratch_name: &str,
        audio: &AdaptiveFormat,
        video: Option<&AdaptiveFormat>,
        loudness_gain_db: Option<f64>,
    ) -> Result<ReadyOutput, DownloadError> {
        let ready = ReadyOutput {
            scratch_name: scratch_name.to_owned(),
            duration_ms: Some(audio.approx_duration_ms).filter(|duration| *duration > 0),
            width: video.and_then(|video| video.width),
            height: video.and_then(|video| video.height),
            loudness_gain_db,
        };
        let text = |value: Option<String>| value.unwrap_or_default();
        let marker = [
            ready.scratch_name.clone(),
            text(ready.duration_ms.map(|value| value.to_string())),
            text(ready.width.map(|value| value.to_string())),
            text(ready.height.map(|value| value.to_string())),
            text(ready.loudness_gain_db.map(|value| value.to_string())),
        ]
        .join("\n");
        fs::write(self.storage.scratch(task_id, READY_SCRATCH)?, marker)
            .map_err(|error| DownloadError::file("R70031", "write the ready marker", error))?;
        Ok(ready)
    }

    /// The task's ready marker, or none when there is none or the file it names is gone.
    fn ready_output(&self, task_id: &str) -> Result<Option<ReadyOutput>, DownloadError> {
        let marker = self.storage.task_dir(task_id).join(READY_SCRATCH);
        let text = match fs::read_to_string(&marker) {
            Ok(text) => text,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(DownloadError::file("R70032", "read the ready marker", error)),
        };
        let fields: Vec<&str> = text.split('\n').collect();
        let field = |index: usize| fields.get(index).copied().unwrap_or_default();
        let ready = ReadyOutput {
            scratch_name: field(0).to_owned(),
            duration_ms: field(1).parse().ok(),
            width: field(2).parse().ok(),
            height: field(3).parse().ok(),
            loudness_gain_db: field(4).parse().ok(),
        };
        let output = self.storage.task_dir(task_id).join(&ready.scratch_name);
        if fs::metadata(&output).is_ok_and(|metadata| metadata.len() > 0) {
            return Ok(Some(ready));
        }
        remove_if_present(&marker)?;
        Ok(None)
    }

    /// Blocking with no await: once publishing starts, a pause or cancel can't land until the file
    /// is out and the caller has recorded it.
    fn publish(
        &self,
        task: &DownloadTask,
        format: DownloadFormat,
        ready: &ReadyOutput,
        folder: &Path,
    ) -> Result<PipelineResult, DownloadError> {
        let scratch = self.storage.task_dir(&task.id).join(&ready.scratch_name);
        let bytes = fs::metadata(&scratch)
            .map_err(|error| DownloadError::file("R70033", "stat the finished file", error))?
            .len() as i64;
        let uri = self.storage.publish(&scratch, &format!("{}.{}", base_name(task), format.extension()), folder)?;
        if let Err(error) = self.storage.delete_scratch(&task.id) {
            self.log.warn(&format!("scratch cleanup failed for {} (pruned on next start)", task.id), Some(&error));
        }
        Ok(PipelineResult {
            uri,
            bytes,
            duration_ms: ready.duration_ms,
            width: ready.width,
            height: ready.height,
            loudness_gain_db: ready.loudness_gain_db,
        })
    }
}

fn remove_if_present(path: &Path) -> Result<(), DownloadError> {
    match fs::remove_file(path) {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => {
            Err(DownloadError::file("R70034", "remove a scratch file", error))
        }
        _ => Ok(()),
    }
}

/// The videoId goes in `composer` (it survives in ID3 TCOM and MP4 ©wrt, and the library's
/// metadata reader takes it), with the loudness gain after it: `videoId|gain=-6.69`.
fn tags(task: &DownloadTask, resolved_artist: Option<&str>, loudness_gain_db: Option<f64>) -> Metadata {
    let mut metadata = vec![("title".to_owned(), task.title.clone())];
    if let Some(artist) = task.artist.as_deref().or(resolved_artist) {
        metadata.push(("artist".to_owned(), artist.to_owned()));
    }
    let composer = match loudness_gain_db {
        Some(gain) => format!("{}|gain={gain}", task.video_id),
        None => task.video_id.clone(),
    };
    metadata.push(("composer".to_owned(), composer));
    metadata
}

/// The user's file name when set (the folder de-duplicates a clash), else `Title-videoId`.
fn base_name(task: &DownloadTask) -> String {
    if let Some(name) = task.file_name.as_deref().map(names::finalize).filter(|name| !name.is_empty()) {
        return name;
    }
    let title: String = task
        .title
        .chars()
        .map(
            |character| {
                if character.is_alphanumeric() || matches!(character, ' ' | '-' | '_') { character } else { '_' }
            },
        )
        .collect();
    let title = names::cap_bytes(title.trim(), names::MAX_STEM_BYTES - task.video_id.len() - 1);
    let title = if title.trim().is_empty() { task.video_id.clone() } else { title };
    format!("{title}-{}", task.video_id)
}
