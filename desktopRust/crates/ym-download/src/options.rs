//! What one video can be downloaded as, read from its player response: the options dialog's input.
//! Sizes are estimates: stream copies use `contentLength`, MP3 its target bitrate over the duration.

use serde_json::Value;

use crate::error::DownloadError;
use crate::model::{AudioQuality, DownloadFormat};
use crate::selection::{AudioCodec, audio_tiers, best_audio, video_nearest};
use crate::spec::{adaptive_formats, caption_entries, caption_key, check_processed};

#[derive(Clone, Debug)]
pub struct VideoOption {
    pub height: i32,
    pub fps: i32,
    pub mp4_bytes: i64,
    /// None when the video has no Opus audio.
    pub webm_bytes: Option<i64>,
}

#[derive(Clone, Debug)]
pub struct AudioQualityOption {
    pub quality: AudioQuality,
    pub bytes: i64,
    /// MP3's encoding bitrate; none for stream copies.
    pub kbps: Option<u32>,
}

#[derive(Clone, Debug)]
pub struct AudioOption {
    pub format: DownloadFormat,
    /// Lowest first.
    pub qualities: Vec<AudioQualityOption>,
}

impl AudioOption {
    /// `quality`, else the next one up, else the highest offered.
    pub fn pick(&self, quality: AudioQuality) -> Option<&AudioQualityOption> {
        self.qualities.iter().find(|option| option.quality >= quality).or(self.qualities.last())
    }
}

#[derive(Clone, Debug)]
pub struct AudioTrackOption {
    pub id: String,
    pub name: String,
    pub is_default: bool,
}

#[derive(Clone, Debug)]
pub struct CaptionOption {
    pub language_code: String,
    pub name: String,
    pub is_asr: bool,
    pub is_default: bool,
    pub is_translated: bool,
}

impl CaptionOption {
    pub fn key(&self) -> String {
        caption_key(&self.language_code, self.is_asr)
    }
}

#[derive(Clone, Debug)]
pub struct DownloadOptions {
    pub duration_ms: i64,
    /// Highest first; empty without ffmpeg.
    pub video: Vec<VideoOption>,
    pub audio: Vec<AudioOption>,
    /// Empty unless the video carries more than one audio language.
    pub audio_tracks: Vec<AudioTrackOption>,
    pub captions: Vec<CaptionOption>,
}

impl DownloadOptions {
    pub fn audio_bytes(&self, format: DownloadFormat, quality: AudioQuality) -> Option<i64> {
        self.audio.iter().find(|option| option.format == format)?.pick(quality).map(|option| option.bytes)
    }

    pub fn parse(data: &Value, ffmpeg_available: bool) -> Result<Self, DownloadError> {
        let streaming = data
            .get("streamingData")
            .ok_or_else(|| DownloadError::technical("R70011", "download options: no streamingData"))?;
        check_processed(data)?;
        let formats = adaptive_formats(data);
        let raw: Vec<&Value> =
            streaming.get("adaptiveFormats").and_then(Value::as_array).into_iter().flatten().collect();
        let length_seconds = data
            .get("videoDetails")
            .and_then(|details| details.get("lengthSeconds"))
            .and_then(|value| value.as_i64().or_else(|| value.as_str().and_then(|text| text.parse().ok())))
            .unwrap_or(0);
        let duration_ms = formats
            .iter()
            .map(|format| format.approx_duration_ms)
            .max()
            .filter(|duration| *duration > 0)
            .unwrap_or(length_seconds * 1000);

        let stream_option = |format: DownloadFormat, codec: AudioCodec| {
            let qualities: Vec<AudioQualityOption> = audio_tiers(&formats, codec, None, None)
                .into_iter()
                .map(|(quality, stream)| AudioQualityOption { quality, bytes: stream.clen, kbps: None })
                .collect();
            (!qualities.is_empty()).then_some(AudioOption { format, qualities })
        };
        let m4a = stream_option(DownloadFormat::M4a, AudioCodec::Aac);
        let audio: Vec<AudioOption> = if ffmpeg_available {
            let mp3 = AudioOption {
                format: DownloadFormat::Mp3,
                qualities: AudioQuality::ALL
                    .into_iter()
                    .map(|quality| AudioQualityOption {
                        quality,
                        bytes: i64::from(quality.mp3_kbps()) * 1000 / 8 * duration_ms / 1000,
                        kbps: Some(quality.mp3_kbps()),
                    })
                    .collect(),
            };
            [stream_option(DownloadFormat::AudioWebm, AudioCodec::Opus), m4a, Some(mp3)].into_iter().flatten().collect()
        } else {
            m4a.into_iter().collect()
        };
        if audio.is_empty() {
            return Err(DownloadError::technical("R70012", "download options: no audio format this platform can save"));
        }

        let video = if ffmpeg_available {
            let mut heights: Vec<i32> = formats
                .iter()
                .filter(|format| format.is_video() && format.clen > 0)
                .filter_map(|format| format.height)
                .collect();
            heights.sort_unstable_by(|left, right| right.cmp(left));
            heights.dedup();
            let aac_bytes = best_audio(&formats, AudioCodec::Aac, None, None).map_or(0, |format| format.clen);
            let opus = best_audio(&formats, AudioCodec::Opus, None, None);
            heights
                .into_iter()
                .map(|height| {
                    let at_height: Vec<_> = formats
                        .iter()
                        .filter(|format| format.is_video() && format.height == Some(height))
                        .cloned()
                        .collect();
                    let mp4 = video_nearest(&at_height, Some(height), DownloadFormat::VideoMp4);
                    let webm = video_nearest(&formats, Some(height), DownloadFormat::VideoWebm);
                    VideoOption {
                        height,
                        fps: at_height.iter().filter_map(|format| format.fps).max().unwrap_or(0),
                        mp4_bytes: mp4.map_or(0, |format| format.clen) + aac_bytes,
                        webm_bytes: opus.and_then(|opus| webm.map(|video| video.clen + opus.clen)),
                    }
                })
                .collect()
        } else {
            Vec::new()
        };

        let mut audio_tracks: Vec<AudioTrackOption> = Vec::new();
        for format in &raw {
            let Some(track) = format.get("audioTrack") else { continue };
            let Some(id) = track.get("id").and_then(Value::as_str) else { continue };
            if audio_tracks.iter().any(|existing| existing.id == id) {
                continue;
            }
            audio_tracks.push(AudioTrackOption {
                id: id.to_owned(),
                name: track.get("displayName").and_then(Value::as_str).unwrap_or(id).to_owned(),
                is_default: track.get("audioIsDefault").and_then(Value::as_bool).unwrap_or(false),
            });
        }
        if audio_tracks.len() > 1 {
            audio_tracks.sort_by_key(|track| !track.is_default);
        } else {
            audio_tracks.clear();
        }

        Ok(Self {
            duration_ms,
            video,
            audio,
            audio_tracks,
            captions: caption_entries(data)
                .into_iter()
                .map(|entry| CaptionOption {
                    language_code: entry.language_code,
                    name: entry.name,
                    is_asr: entry.is_asr,
                    is_default: entry.is_default,
                    is_translated: entry.is_translated,
                })
                .collect(),
        })
    }
}
