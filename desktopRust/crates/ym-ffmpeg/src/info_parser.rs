//! kmpFfmpeg's `FfmpegInfoParser`: the human-readable dump `ffmpeg -i <input>` prints to stderr
//! (`Input #0 … / Metadata: / Duration: / Stream #…`), read into a [`MediaFileMetadata`]. The packed
//! build has no ffprobe. Only the first input is read; per-stream durations are not printed, a
//! stream's header language is folded into its tags as `language`, and bitrates are the printed kb/s.

use std::collections::BTreeSet;
use std::sync::LazyLock;

use regex::Regex;

use crate::FfmpegError;
use crate::metadata::{ContainerMetadata, MediaFileMetadata, MetadataMap, StreamKind, StreamMetadata};

fn regex(pattern: &str) -> Regex {
    Regex::new(pattern).expect("a valid pattern")
}

static INPUT: LazyLock<Regex> = LazyLock::new(|| regex(r"^Input #[0-9]+, (.+), from '(.*)':\s*$"));
static DURATION: LazyLock<Regex> = LazyLock::new(|| regex(r"Duration:\s*([0-9]+):([0-9]+):([0-9]+)(?:\.([0-9]+))?"));
static CONTAINER_BITRATE: LazyLock<Regex> = LazyLock::new(|| regex(r"bitrate:\s*([0-9]+)\s*kb/s"));
static STREAM: LazyLock<Regex> = LazyLock::new(|| {
    regex(r"^Stream #[0-9]+:([0-9]+)(?:\[[^\]]*\])?(?:\(([^)]+)\))?:\s*(Audio|Video|Subtitle|Data|Attachment):\s*(.*)$")
});
static HZ: LazyLock<Regex> = LazyLock::new(|| regex(r"([0-9]+)\s*Hz"));
static KBPS: LazyLock<Regex> = LazyLock::new(|| regex(r"([0-9]+)\s*kb/s"));
// Anchored: a hex tag like 0x6134706D is not a size.
static DIMENSIONS: LazyLock<Regex> = LazyLock::new(|| regex(r"^([0-9]+)x([0-9]+)"));
static FPS: LazyLock<Regex> = LazyLock::new(|| regex(r"([0-9.]+)\s*fps"));
static CHANNELS_N: LazyLock<Regex> = LazyLock::new(|| regex(r"^([0-9]+)\s+channels"));
static PAREN: LazyLock<Regex> = LazyLock::new(|| regex(r"\(([^)]+)\)"));

const CHANNEL_LAYOUTS: &[(&str, i32)] = &[
    ("mono", 1),
    ("stereo", 2),
    ("downmix", 2),
    ("2.1", 3),
    ("3.0", 3),
    ("3.0(back)", 3),
    ("quad", 4),
    ("quad(side)", 4),
    ("4.0", 4),
    ("3.1", 4),
    ("5.0", 5),
    ("5.0(side)", 5),
    ("4.1", 5),
    ("5.1", 6),
    ("5.1(side)", 6),
    ("6.0", 6),
    ("6.0(front)", 6),
    ("hexagonal", 6),
    ("6.1", 7),
    ("6.1(back)", 7),
    ("6.1(front)", 7),
    ("7.0", 7),
    ("7.0(front)", 7),
    ("7.1", 8),
    ("7.1(wide)", 8),
    ("7.1(wide-side)", 8),
    ("octagonal", 8),
];

/// av_disposition flag names ffmpeg prints in trailing parens, spelled as ffprobe keys them.
const DISPOSITIONS: &[&str] = &[
    "default",
    "dub",
    "original",
    "comment",
    "lyrics",
    "karaoke",
    "forced",
    "hearing_impaired",
    "visual_impaired",
    "clean_effects",
    "attached_pic",
    "timed_thumbnails",
    "captions",
    "descriptions",
    "metadata",
    "dependent",
    "still_image",
    "non_diegetic",
    "multilayer",
];

#[derive(Clone, Copy, PartialEq)]
enum TagTarget {
    None,
    Container,
    LastStream,
}

/// Fails when the dump has no `Input #` block: ffmpeg could not open the file as media.
pub fn parse(stderr: &str) -> Result<MediaFileMetadata, FfmpegError> {
    let lines: Vec<&str> = stderr.split(['\n', '\r']).collect();
    let Some(input_index) = lines.iter().position(|line| INPUT.is_match(line.trim_end())) else {
        let tail: Vec<&str> = lines.iter().copied().filter(|line| !line.trim().is_empty()).collect();
        let tail = tail[tail.len().saturating_sub(5)..].join("\n");
        return Err(FfmpegError::new("R40007", format!("no 'Input #' block in ffmpeg output\n{tail}")));
    };
    let container_name = INPUT.captures(lines[input_index].trim_end()).expect("matched above")[1].to_owned();

    let mut duration_ms = None;
    let mut bitrate = None;
    let mut container_tags = MetadataMap::default();
    let mut streams: Vec<StreamMetadata> = Vec::new();
    let mut target = TagTarget::None;

    for raw in &lines[input_index + 1..] {
        if raw.trim().is_empty() {
            continue;
        }
        let indent = raw.chars().take_while(|character| *character == ' ').count();
        let content = raw.trim();
        if INPUT.is_match(raw.trim_end()) {
            break;
        }
        if indent <= 2 && content == "Metadata:" {
            target = TagTarget::Container;
        } else if indent <= 2 && content.starts_with("Duration:") {
            target = TagTarget::None;
            duration_ms = parse_duration_ms(content);
            bitrate = CONTAINER_BITRATE
                .captures(content)
                .and_then(|captures| captures[1].parse::<i64>().ok())
                .map(|kbps| kbps * 1000);
        } else if indent <= 2 && content.starts_with("Stream #") {
            target = TagTarget::None;
            if let Some(stream) = parse_stream(content) {
                streams.push(stream);
                target = TagTarget::LastStream;
            }
        } else if (3..=4).contains(&indent) && content == "Metadata:" {
            // A stream's tag block: the target was set by its Stream line.
        } else if target != TagTarget::None && indent >= 4 && content.contains(':') {
            let (key, value) = content.split_once(':').expect("contains ':'");
            let key = key.trim();
            if !key.is_empty() {
                let tags = match target {
                    TagTarget::Container => &mut container_tags,
                    _ => &mut streams.last_mut().expect("a stream is the target").metadata,
                };
                tags.insert(key.to_owned(), value.trim().to_owned());
            }
        } else if indent <= 4 {
            // Any other header (Side data:, Chapters:, …) closes the open tag block.
            target = TagTarget::None;
        }
    }

    Ok(MediaFileMetadata {
        container: ContainerMetadata { name: container_name, duration_ms, bitrate, metadata: container_tags },
        streams,
    })
}

fn parse_stream(content: &str) -> Option<StreamMetadata> {
    let captures = STREAM.captures(content)?;
    let index = captures[1].parse().ok()?;
    let language = captures.get(2).map(|language| language.as_str()).filter(|language| *language != "und");
    let rest = &captures[4];
    let params = split_params(rest);
    let codec =
        params.first().map(|first| first.split(' ').next().unwrap_or_default().trim().to_owned()).unwrap_or_default();
    let disposition: BTreeSet<String> = PAREN
        .captures_iter(rest)
        .map(|flag| flag[1].trim().replace(' ', "_"))
        .filter(|flag| DISPOSITIONS.contains(&flag.as_str()))
        .collect();
    let mut metadata = MetadataMap::default();
    if let Some(language) = language {
        metadata.insert("language".to_owned(), language.to_owned());
    }
    let number = |pattern: &Regex| -> Option<i64> {
        params.iter().find_map(|param| pattern.captures(param)).and_then(|found| found[1].parse().ok())
    };
    let kind = match &captures[3] {
        "Audio" => StreamKind::Audio {
            sample: number(&HZ),
            channels: params.iter().find_map(|param| parse_channels(param)),
            bitrate: number(&KBPS).map(|kbps| kbps * 1000),
        },
        "Video" => {
            let dimensions = params.iter().find_map(|param| DIMENSIONS.captures(param));
            StreamKind::Video {
                width: dimensions.as_ref().and_then(|found| found[1].parse().ok()),
                height: dimensions.as_ref().and_then(|found| found[2].parse().ok()),
                fps: params.iter().find_map(|param| FPS.captures(param)).and_then(|found| found[1].parse().ok()),
            }
        }
        "Subtitle" => StreamKind::Text,
        _ => return None,
    };
    Some(StreamMetadata { index, codec, metadata, disposition, kind })
}

/// `HH:MM:SS.ff` in milliseconds, at whatever precision ffmpeg printed.
fn parse_duration_ms(content: &str) -> Option<i64> {
    let captures = DURATION.captures(content)?;
    let field = |group: usize| captures[group].parse::<i64>().ok();
    let (hours, minutes, seconds) = (field(1)?, field(2)?, field(3)?);
    let fraction_ms = match captures.get(4) {
        Some(fraction) => (format!("0.{}", fraction.as_str()).parse::<f64>().ok()? * 1000.0) as i64,
        None => 0,
    };
    Some((hours * 3600 + minutes * 60 + seconds) * 1000 + fraction_ms)
}

fn parse_channels(param: &str) -> Option<i32> {
    let param = param.trim();
    CHANNEL_LAYOUTS
        .iter()
        .find(|(layout, _)| *layout == param)
        .map(|(_, channels)| *channels)
        .or_else(|| CHANNELS_N.captures(param).and_then(|found| found[1].parse().ok()))
}

/// Splits on top-level commas only: `rgb24(pc, gbr/unknown/unknown)` and `[SAR 1:1 DAR 1:1]` hold their own.
fn split_params(text: &str) -> Vec<String> {
    let mut params = Vec::new();
    let mut current = String::new();
    let mut depth = 0usize;
    for character in text.chars() {
        match character {
            '(' | '[' => {
                depth += 1;
                current.push(character);
            }
            ')' | ']' => {
                depth = depth.saturating_sub(1);
                current.push(character);
            }
            ',' if depth == 0 => {
                params.push(current.trim().to_owned());
                current.clear();
            }
            _ => current.push(character),
        }
    }
    if !current.trim().is_empty() {
        params.push(current.trim().to_owned());
    }
    params.retain(|param| !param.is_empty());
    params
}
