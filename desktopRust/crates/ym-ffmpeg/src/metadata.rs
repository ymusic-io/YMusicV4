//! A media file's metadata as `ffmpeg -i` prints it: kmpFfmpeg's `MediaFileMetadata`,
//! `ContainerMetadata`, `StreamMetadata` and `MetadataMap`.

use std::collections::BTreeSet;

pub const DISPOSITION_ATTACHED_PIC: &str = "attached_pic";

#[derive(Debug, Clone, PartialEq)]
pub struct MediaFileMetadata {
    pub container: ContainerMetadata,
    pub streams: Vec<StreamMetadata>,
}

impl MediaFileMetadata {
    /// A picture stream that is not cover art.
    pub fn has_video(&self) -> bool {
        self.streams
            .iter()
            .any(|stream| matches!(stream.kind, StreamKind::Video { .. }) && !stream.is_attached_thumbnail())
    }

    /// kmpFfmpeg's `getMimeType`, from the demuxer name.
    pub fn mime_type(&self) -> String {
        let name = self.container.name.as_str();
        let first_name = name.split(',').next().unwrap_or_default();
        if self.has_video() {
            if name.contains("webm") {
                "video/webm".to_owned()
            } else if name.contains("mp4") {
                "video/mp4".to_owned()
            } else if name.contains("flv") {
                "video/x-flv".to_owned()
            } else if name.contains("avi") {
                "video/x-msvideo".to_owned()
            } else {
                format!("video/{first_name}")
            }
        } else if name.contains("mp3") {
            "audio/mpeg".to_owned()
        } else if name.contains("webm") {
            "audio/webm".to_owned()
        } else {
            format!("audio/{first_name}")
        }
    }
}

/// The demuxer name as ffmpeg reports it (`mp3`, `mov,mp4,m4a,3gp,3g2,mj2`, `matroska,webm`, …).
#[derive(Debug, Clone, PartialEq)]
pub struct ContainerMetadata {
    pub name: String,
    /// Centisecond precision, as printed.
    pub duration_ms: Option<i64>,
    /// Bits per second, scaled from the printed kb/s.
    pub bitrate: Option<i64>,
    pub metadata: MetadataMap,
}

/// One stream of the first input; Data and Attachment streams are not modelled.
#[derive(Debug, Clone, PartialEq)]
pub struct StreamMetadata {
    /// The `N` of `Stream #0:N`, what `-map 0:N` takes.
    pub index: i32,
    pub codec: String,
    pub metadata: MetadataMap,
    pub disposition: BTreeSet<String>,
    pub kind: StreamKind,
}

#[derive(Debug, Clone, PartialEq)]
pub enum StreamKind {
    Audio { sample: Option<i64>, channels: Option<i32>, bitrate: Option<i64> },
    Video { width: Option<i32>, height: Option<i32>, fps: Option<f64> },
    Text,
}

impl StreamMetadata {
    /// Cover art: a picture stream marked attached, or one an ID3 picture comment names a cover.
    pub fn is_attached_thumbnail(&self) -> bool {
        matches!(self.kind, StreamKind::Video { .. })
            && (self.disposition.contains(DISPOSITION_ATTACHED_PIC)
                || self.metadata.comment().unwrap_or_default().starts_with("Cover"))
    }
}

/// A container's or stream's tags in printed order. Each accessor takes the first tag whose key
/// matches one of its synonyms, ignoring case, so `ARTIST` (Matroska) and `artist` (MP4) both count.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct MetadataMap {
    entries: Vec<(String, String)>,
}

const TITLE_KEYS: &[&str] = &["title", "title-sort"];
const ARTIST_KEYS: &[&str] = &["artist", "author", "album_artist", "performer", "artist-sort"];
const ALBUM_KEYS: &[&str] = &["album", "album-sort", "show"];
const GENRE_KEYS: &[&str] = &["genre"];
const YEAR_KEYS: &[&str] = &["year", "date"];
const COMPOSER_KEYS: &[&str] = &["composer"];
const TRACK_KEYS: &[&str] = &["track"];
const COMMENT_KEYS: &[&str] = &["comment"];

impl MetadataMap {
    /// A repeated key keeps its first position and takes the new value.
    pub fn insert(&mut self, key: String, value: String) {
        match self.entries.iter_mut().find(|(existing, _)| *existing == key) {
            Some(entry) => entry.1 = value,
            None => self.entries.push((key, value)),
        }
    }

    pub fn entries(&self) -> &[(String, String)] {
        &self.entries
    }

    fn first_of(&self, keys: &[&str]) -> Option<&str> {
        self.entries
            .iter()
            .find(|(key, _)| {
                let key = key.to_lowercase();
                keys.contains(&key.as_str())
            })
            .map(|(_, value)| value.as_str())
    }

    pub fn title(&self) -> Option<&str> {
        self.first_of(TITLE_KEYS)
    }

    pub fn artist(&self) -> Option<&str> {
        self.first_of(ARTIST_KEYS)
    }

    pub fn album(&self) -> Option<&str> {
        self.first_of(ALBUM_KEYS)
    }

    pub fn genre(&self) -> Option<&str> {
        self.first_of(GENRE_KEYS)
    }

    pub fn year(&self) -> Option<&str> {
        self.first_of(YEAR_KEYS)
    }

    pub fn composer(&self) -> Option<&str> {
        self.first_of(COMPOSER_KEYS)
    }

    pub fn track(&self) -> Option<&str> {
        self.first_of(TRACK_KEYS)
    }

    pub fn comment(&self) -> Option<&str> {
        self.first_of(COMMENT_KEYS)
    }
}
