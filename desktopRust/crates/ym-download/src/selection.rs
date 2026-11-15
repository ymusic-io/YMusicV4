//! Picks the concrete streams to download. `clen` stands in for bitrate: "best" is the largest.

use std::cmp::Reverse;

use ym_ytmedia::sabr::AdaptiveFormat;

use crate::model::{AudioQuality, DownloadFormat, StreamId};

/// The audio codecs an output takes: AAC for M4A/MP4, Opus for WebM, any for MP3 (decoded).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AudioCodec {
    Aac,
    Opus,
    Any,
}

impl AudioCodec {
    pub fn of(format: DownloadFormat) -> Self {
        match format {
            DownloadFormat::Mp3 => Self::Any,
            DownloadFormat::M4a | DownloadFormat::VideoMp4 => Self::Aac,
            DownloadFormat::AudioWebm | DownloadFormat::VideoWebm => Self::Opus,
        }
    }

    fn accepts(self, mime_type: &str) -> bool {
        let codecs = codecs(mime_type);
        match self {
            Self::Aac => codecs.starts_with("mp4a"),
            Self::Opus => codecs.starts_with("opus"),
            Self::Any => true,
        }
    }
}

fn codecs(mime_type: &str) -> &str {
    mime_type.split_once("codecs=").map_or("", |(_, codecs)| codecs.trim_matches(|c| c == '"' || c == ' '))
}

/// VP8/VP9/AV1: what ffmpeg's `webm` muxer accepts.
pub fn is_webm_video(mime_type: &str) -> bool {
    let codecs = codecs(mime_type);
    ["vp8", "vp9", "vp09", "av01"].iter().any(|prefix| codecs.starts_with(prefix))
}

/// `en` of `en.4`, `es-US` of `es-US.3`.
pub fn audio_track_language(track_id: &str) -> &str {
    track_id.split('.').next().unwrap_or(track_id)
}

/// Same language, regional variants included (`pt` ~ `pt-BR`).
pub fn same_language(left: &str, right: &str) -> bool {
    let base = |code: &str| code.split('-').next().unwrap_or(code).to_ascii_lowercase();
    left.eq_ignore_ascii_case(right) || base(left) == base(right)
}

pub fn stream_id(format: &AdaptiveFormat) -> StreamId {
    StreamId { itag: format.itag, lmt: format.lmt }
}

fn audio_streams(formats: &[AdaptiveFormat], codec: AudioCodec) -> Vec<&AdaptiveFormat> {
    formats
        .iter()
        .filter(|format| {
            format.mime_type.starts_with("audio")
                && format.clen > 0
                && !format.is_drc
                && !format.is_vb
                && codec.accepts(&format.mime_type)
        })
        .collect()
}

/// The requested dub wins, then a track in `language`, then default (non-dubbed) tracks over
/// alternates, then size.
pub fn best_audio<'a>(
    formats: &'a [AdaptiveFormat],
    codec: AudioCodec,
    track_id: Option<&str>,
    language: Option<&str>,
) -> Option<&'a AdaptiveFormat> {
    // `rev` makes ties go to the first listed, as YouTube orders them.
    audio_streams(formats, codec).into_iter().rev().max_by_key(|format| {
        let wanted_track = track_id.is_some() && format.audio_track_id.as_deref() == track_id;
        let wanted_language = match (language, format.audio_track_id.as_deref()) {
            (Some(language), Some(track)) => same_language(audio_track_language(track), language),
            _ => false,
        };
        (wanted_track, wanted_language, format.audio_track_is_default != Some(false), format.clen)
    })
}

/// The tiers of the track [`best_audio`] picks, lowest first.
pub fn audio_tiers<'a>(
    formats: &'a [AdaptiveFormat],
    codec: AudioCodec,
    track_id: Option<&str>,
    language: Option<&str>,
) -> Vec<(AudioQuality, &'a AdaptiveFormat)> {
    let Some(best) = best_audio(formats, codec, track_id, language) else { return Vec::new() };
    let mut ladder: Vec<&AdaptiveFormat> = audio_streams(formats, codec)
        .into_iter()
        .filter(|format| format.audio_track_id == best.audio_track_id)
        .collect();
    ladder.sort_by_key(|format| format.clen);
    ladder.dedup_by_key(|format| format.itag);
    match ladder.len() {
        1 => vec![(AudioQuality::Best, best)],
        2 => vec![(AudioQuality::Save, ladder[0]), (AudioQuality::Best, best)],
        count => {
            vec![(AudioQuality::Save, ladder[0]), (AudioQuality::Medium, ladder[count / 2]), (AudioQuality::Best, best)]
        }
    }
}

/// The tier at `quality`, else the next one up; a video with fewer tiers lands on its best.
pub fn audio_at<'a>(
    formats: &'a [AdaptiveFormat],
    codec: AudioCodec,
    quality: AudioQuality,
    track_id: Option<&str>,
    language: Option<&str>,
) -> Option<&'a AdaptiveFormat> {
    let tiers = audio_tiers(formats, codec, track_id, language);
    tiers.iter().find(|(tier, _)| *tier >= quality).or(tiers.last()).map(|(_, format)| *format)
}

/// `stream` on the track [`best_audio`] picks; none when the video no longer offers it.
pub fn remembered_audio<'a>(
    formats: &'a [AdaptiveFormat],
    codec: AudioCodec,
    stream: StreamId,
    track_id: Option<&str>,
    language: Option<&str>,
) -> Option<&'a AdaptiveFormat> {
    let best = best_audio(formats, codec, track_id, language)?;
    audio_streams(formats, codec)
        .into_iter()
        .find(|format| stream_id(format) == stream && format.audio_track_id == best.audio_track_id)
}

fn video_streams(formats: &[AdaptiveFormat], container: DownloadFormat) -> Vec<&AdaptiveFormat> {
    formats
        .iter()
        .filter(|format| format.is_video() && format.clen > 0 && format.height.is_some())
        .filter(|format| container != DownloadFormat::VideoWebm || is_webm_video(&format.mime_type))
        .collect()
}

pub fn remembered_video(
    formats: &[AdaptiveFormat],
    stream: StreamId,
    container: DownloadFormat,
) -> Option<&AdaptiveFormat> {
    video_streams(formats, container).into_iter().find(|format| stream_id(format) == stream)
}

/// The video nearest `height` (highest when none), biased to the codec that stream-copies into
/// `container`, then resolution, then size.
pub fn video_nearest(
    formats: &[AdaptiveFormat],
    height: Option<i32>,
    container: DownloadFormat,
) -> Option<&AdaptiveFormat> {
    let prefer_mp4 = container == DownloadFormat::VideoMp4;
    video_streams(formats, container).into_iter().min_by_key(|format| {
        let format_height = format.height.unwrap_or(0);
        let distance = height.map_or(0, |wanted| (format_height - wanted).abs());
        let codec_fits = if prefer_mp4 { format.mime_type.contains("mp4") } else { format.mime_type.contains("webm") };
        (distance, Reverse(codec_fits), Reverse(format_height), Reverse(format.clen))
    })
}
