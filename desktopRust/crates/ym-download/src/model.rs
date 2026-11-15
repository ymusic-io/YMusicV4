/// The container a download is delivered as. Only MP3 re-encodes: every source is lossy, so the
/// rest stream-copy the original tracks.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DownloadFormat {
    Mp3,
    M4a,
    /// Opus in Ogg. The name is persisted, so it outlives the container it was named for.
    AudioWebm,
    VideoWebm,
    VideoMp4,
}

impl DownloadFormat {
    pub const ALL: [DownloadFormat; 5] = [Self::Mp3, Self::M4a, Self::AudioWebm, Self::VideoWebm, Self::VideoMp4];

    pub fn is_video(self) -> bool {
        matches!(self, Self::VideoWebm | Self::VideoMp4)
    }

    pub fn extension(self) -> &'static str {
        match self {
            Self::Mp3 => "mp3",
            Self::M4a => "m4a",
            Self::AudioWebm => "opus",
            Self::VideoWebm => "webm",
            Self::VideoMp4 => "mp4",
        }
    }

    /// The container has no subtitle tracks, so captions go in as LRC lyrics.
    pub fn captions_as_lyrics(self) -> bool {
        matches!(self, Self::Mp3 | Self::AudioWebm)
    }

    /// The persisted name, shared with the Kotlin app's rows.
    pub fn name(self) -> &'static str {
        match self {
            Self::Mp3 => "MP3",
            Self::M4a => "M4A",
            Self::AudioWebm => "AUDIO_WEBM",
            Self::VideoWebm => "VIDEO_WEBM",
            Self::VideoMp4 => "VIDEO_MP4",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|format| format.name() == name)
    }
}

/// An audio-only download's size/quality tier, lowest first.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum AudioQuality {
    Save,
    Medium,
    Best,
}

impl AudioQuality {
    pub const ALL: [AudioQuality; 3] = [Self::Save, Self::Medium, Self::Best];

    pub fn name(self) -> &'static str {
        match self {
            Self::Save => "SAVE",
            Self::Medium => "MEDIUM",
            Self::Best => "BEST",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|quality| quality.name() == name)
    }

    pub fn mp3_kbps(self) -> u32 {
        match self {
            Self::Save => 128,
            Self::Medium => 256,
            Self::Best => 320,
        }
    }
}

/// QUEUED, PAUSED and FAILED are the resumable entry points; a worker drives a task through
/// PREPARING → DOWNLOADING → PROCESSING → COMPLETED. COMPLETED and CANCELED are terminal.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DownloadState {
    Queued,
    Preparing,
    Downloading,
    Processing,
    Completed,
    Paused,
    Failed,
    Canceled,
}

impl DownloadState {
    pub const ALL: [DownloadState; 8] = [
        Self::Queued,
        Self::Preparing,
        Self::Downloading,
        Self::Processing,
        Self::Completed,
        Self::Paused,
        Self::Failed,
        Self::Canceled,
    ];

    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Canceled)
    }

    /// In flight from the user's view: waiting for a slot or being worked.
    pub fn is_active(self) -> bool {
        matches!(self, Self::Queued | Self::Preparing | Self::Downloading | Self::Processing)
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::Queued => "QUEUED",
            Self::Preparing => "PREPARING",
            Self::Downloading => "DOWNLOADING",
            Self::Processing => "PROCESSING",
            Self::Completed => "COMPLETED",
            Self::Paused => "PAUSED",
            Self::Failed => "FAILED",
            Self::Canceled => "CANCELED",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|state| state.name() == name)
    }
}

/// What the caller asks for: the video plus display metadata and the output.
#[derive(Clone, Debug)]
pub struct DownloadRequest {
    pub video_id: String,
    pub title: String,
    pub artist: Option<String>,
    pub thumbnail_url: Option<String>,
    pub format: DownloadFormat,
    /// Video outputs: the target height; the nearest available is chosen. None = best.
    pub requested_height: Option<i32>,
    /// The output's stem, already user-edited; none = derived from the title.
    pub file_name: Option<String>,
    /// The `audioTrack.id` to download (a dub); none = the default track.
    pub audio_track_id: Option<String>,
    /// The caption keys to fetch; none = `caption_language`.
    pub captions: Option<Vec<String>>,
    /// Preferred audio language, resolved when the download runs.
    pub audio_language: Option<String>,
    /// Preferred caption language, resolved when the download runs.
    pub caption_language: Option<String>,
    pub audio_quality: AudioQuality,
}

/// One exact stream of a video: `lmt` changes when YouTube re-encodes an itag.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct StreamId {
    pub itag: i32,
    pub lmt: i64,
}

/// A failed task's error as the user sees it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TaskError {
    pub code: String,
    pub user_message: Option<String>,
}

/// One row of the queue: an immutable snapshot.
#[derive(Clone, Debug)]
pub struct DownloadTask {
    pub id: String,
    pub video_id: String,
    pub title: String,
    pub artist: Option<String>,
    pub thumbnail_url: Option<String>,
    pub format: DownloadFormat,
    pub requested_height: Option<i32>,
    pub state: DownloadState,
    pub downloaded_bytes: i64,
    pub total_bytes: i64,
    /// The finished file's `file://` URI once completed.
    pub file_uri: Option<String>,
    pub error: Option<TaskError>,
    pub created_at: i64,
    pub updated_at: i64,
    pub file_name: Option<String>,
    pub audio_track_id: Option<String>,
    pub captions: Option<Vec<String>>,
    pub audio_language: Option<String>,
    pub caption_language: Option<String>,
    pub audio_quality: AudioQuality,
    /// The streams the scratches hold: their bytes can only continue those streams.
    pub audio_stream: Option<StreamId>,
    pub video_stream: Option<StreamId>,
    /// dB from the file's measured loudness to its target.
    pub loudness_gain_db: Option<f64>,
}

impl DownloadTask {
    /// 0..=1, or none before the total is known.
    pub fn progress(&self) -> Option<f32> {
        (self.total_bytes > 0).then(|| (self.downloaded_bytes as f32 / self.total_bytes as f32).clamp(0., 1.))
    }
}

/// One change to the queue, in the order the service applied it.
#[derive(Clone, Debug)]
pub enum DownloadChange {
    Added(Box<DownloadTask>),
    StateChanged { id: String, state: DownloadState, error: Option<TaskError> },
    ProgressChanged { id: String, done: i64, total: i64 },
    FormatChanged { id: String, format: DownloadFormat },
    Completed { id: String, file_uri: String, bytes: i64 },
    Removed { id: String },
}
