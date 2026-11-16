//! Playback: the play queue and the state the UI renders, over two engines: YouTube (`player.js`
//! on its own engine over a native MSE host) and local files (`file_av`). The video presenter
//! is bound to whichever engine plays the current item.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::StreamExt;
use futures::channel::mpsc;
use gpui_kit::*;
use serde_json::Value;
use ym_engine::player::LoadRequest;
use ym_engine::yt::{YtFeedItem, YtThumb};
use ym_engine::{DynamicEngine, EngineError};
use ym_i18n::{Str, t, tf};
use ym_mse::Presenter;

use crate::audio_effects::AudioEffects;
use crate::graph::{AppGraph, on_core};
use crate::local_engine::{CaptionPrefs, LocalCommand, LocalEngine, LocalLevelTap};
use crate::queue_nav::{LoopMode, QueueNav, moved_index};
use crate::queue_source::{NextQueueSource, QUEUE_PAGE_UNCODED, QueueMore, QueueSourcePage, error_code};
use crate::youtube_session::YoutubeSession;

/// The JVM app's keys.
const VOLUME_KEY: &str = "player.volume";
/// `"1"` audio only, `"0"` video.
const AUDIO_ONLY_KEY: &str = "player.audioOnly";
const LOOP_MODE_KEY: &str = "player.loopMode";
const SHUFFLE_KEY: &str = "player.shuffle";
const QUALITY_KEY: &str = "player.preferVideoQuality";
pub const QUALITY_AUTO: &str = "auto";
/// The bundle's audio-only quality.
pub const QUALITY_AUDIO_ONLY: &str = "zero";
const LOCAL_POLL: Duration = Duration::from_millis(250);
const MIN_SKIP_MS: i64 = 1_000;
const OUTRO_TOLERANCE_MS: i64 = 1_000;
/// Cue the next item this close to the end (the JVM `PRELOAD_LEAD_MS`).
const PRELOAD_LEAD_MS: i64 = 10_000;

/// The JVM `PlaybackState`: the bound engine's session and media lifecycle. Buffering and ended are
/// orthogonal to the engine's own play intent, which can differ from the user's
/// ([`Playback::play_when_ready`]) across a load.
#[derive(Debug, Clone, PartialEq)]
pub enum PlaybackState {
    /// The engine has no usable session yet.
    Uninitialized { is_initializing: bool },
    /// A failed load or session.
    Error(PlaybackFailure),
    /// The engine is up with no media loaded.
    Idle,
    /// Media is loaded.
    Prepared { play_when_ready: bool, buffering: bool, ended: bool },
}

impl PlaybackState {
    /// The JVM `PlaybackState.isPlaying`: the engine holds media and means to play it, buffering or not.
    pub fn is_playing(&self) -> bool {
        matches!(self, Self::Prepared { play_when_ready: true, .. })
    }

    pub fn is_ended(&self) -> bool {
        matches!(self, Self::Prepared { ended: true, .. })
    }

    /// An ordinary load can be sent to the engine's session as it is; not that media is ready to play.
    pub fn ready_to_load(&self) -> bool {
        match self {
            Self::Uninitialized { .. } => false,
            Self::Error(failure) => !failure.need_reset,
            Self::Idle | Self::Prepared { .. } => true,
        }
    }
}

/// Why playback stopped, as the user sees it: a known category, the text the source gave (YouTube's
/// own words for a playability verdict), or the code to report.
#[derive(Debug, Clone, PartialEq)]
pub struct PlaybackFailure {
    pub code: String,
    pub user_message: Option<String>,
    /// Loading the item again may work.
    pub retry: bool,
    /// The engine's session is broken: it is reset before the next load.
    pub need_reset: bool,
}

const SIGN_IN_STATUSES: [&str; 3] = ["LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED"];
const FILE_MISSING: &str = "file_missing";
const PO_TOKEN_REJECTED: &str = "E60065";
/// Previous restarts the item when it has played longer than this (the JVM `SMART_PREV_THRESHOLD_MS`).
const SMART_PREVIOUS_MS: i64 = 3_000;
/// A YouTube load whose player answered but shows neither progress nor details by then has stalled
/// (the JVM MsePlayer's LOAD_TIMEOUT_MS).
const LOAD_TIMEOUT: Duration = Duration::from_secs(5);
const LOAD_STALLED: &str = "R90032";
const WARM_UP_AFTER: Duration = Duration::from_secs(3);
/// A growing queue loads its next page once the current item is within this many of its end (the
/// JVM PlayerImpl.LOAD_AHEAD).
const LOAD_AHEAD: usize = 10;

type PlayerJob = Box<dyn FnOnce(Arc<DynamicEngine>) -> futures::future::BoxFuture<'static, ()> + Send>;

impl PlaybackFailure {
    pub fn code(code: &str) -> Self {
        Self { code: code.to_owned(), user_message: None, retry: true, need_reset: false }
    }

    /// A session boot or player load that failed: the JVM MsePlayer fails both with `needReset`.
    fn engine(error: &EngineError) -> Self {
        let code = if matches!(error.kind, ym_engine::ErrorKind::Network) { "network" } else { error.code.as_str() };
        let user_message = error.details.as_ref().and_then(|details| details.user_message.clone());
        Self {
            code: code.to_owned(),
            user_message: user_message.filter(|message| !message.is_empty()),
            retry: true,
            need_reset: true,
        }
    }

    /// YouTube wants a signed-in viewer for this video.
    pub fn needs_sign_in(&self) -> bool {
        SIGN_IN_STATUSES.contains(&self.code.as_str())
    }

    pub fn text(&self) -> String {
        // A playabilityStatus is all caps, unlike site codes (E#####, R#####) and categories.
        let playability = !self.code.is_empty() && self.code.chars().all(|c| c.is_ascii_uppercase() || c == '_');
        match self.code.as_str() {
            "captcha" => t(Str::ErrorCaptcha).to_owned(),
            "network" => t(Str::ErrorNetwork).to_owned(),
            "supervised_account" => t(Str::ErrorSupervisedAccount).to_owned(),
            FILE_MISSING => t(Str::PlaybackFileMissing).to_owned(),
            PO_TOKEN_REJECTED => t(Str::PlaybackBotguardFailed).to_owned(),
            _ => match &self.user_message {
                Some(message) => message.clone(),
                None if self.needs_sign_in() => t(Str::PlaybackSignInRequired).to_owned(),
                None if self.code == "ERROR" => t(Str::PlaybackRetryLater).to_owned(),
                None if playability => t(Str::PlaybackUnavailable).to_owned(),
                None => tf(Str::PlaybackFailed, &[&self.code]),
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Source {
    YouTube(String),
    Local(PathBuf),
}

/// One entry of the play queue, whatever plays it.
#[derive(Debug, Clone, PartialEq)]
pub struct QueueItem {
    pub source: Source,
    pub title: String,
    pub artist: Option<String>,
    pub duration_ms: Option<i64>,
    pub duration_label: Option<String>,
    pub thumbs: Vec<YtThumb>,
    /// The Library row a local file plays as; `None` for a file outside the Library.
    pub media_id: Option<i64>,
    /// A local file's play-history key: its YouTube id when tagged, else size and length.
    pub history_code: Option<String>,
    /// A local file downloaded from YouTube (its `composer` tag).
    pub youtube_vid: Option<String>,
    /// A local file's dB to its loudness target.
    pub loudness_gain_db: Option<f64>,
    /// A YouTube live stream, as its feed item said.
    pub live: bool,
}

/// A local item's one art url (the JVM `LocalMedia.thumbnailUrl`) as its thumbnail list.
fn art_thumb(url: Option<String>) -> Vec<YtThumb> {
    url.map(|url| vec![YtThumb { url, w: None, h: None }]).unwrap_or_default()
}

impl QueueItem {
    pub fn from_feed(item: &YtFeedItem) -> Self {
        Self {
            source: Source::YouTube(item.id.clone()),
            title: item.title.clone(),
            artist: item.channel_title.clone().or_else(|| item.subtitle_line1.clone()),
            duration_ms: item.duration_ms,
            duration_label: item.duration.clone(),
            thumbs: item.thumbs.clone(),
            media_id: None,
            history_code: None,
            youtube_vid: None,
            loudness_gain_db: None,
            live: item.live,
        }
    }

    /// A Library item.
    pub fn from_local(item: &ym_store::MediaItem) -> Self {
        let thumbs = art_thumb(crate::local_art::local_art_url(item));
        Self { media_id: Some(item.id), thumbs, ..Self::from_file(item) }
    }

    /// A file outside the Library, described by its own tags (the JVM `looseFile`).
    pub fn from_file(item: &ym_store::MediaItem) -> Self {
        Self {
            source: Source::Local(PathBuf::from(&item.uri)),
            title: item.title.clone(),
            artist: item.artist.clone(),
            duration_ms: item.duration_ms,
            duration_label: item.duration_ms.map(crate::mini_player::clock),
            thumbs: art_thumb(crate::local_art::file_art_url(
                &ym_store::file_uri(&item.uri),
                item.thumbnail_stream_index.is_some(),
                item.youtube_vid.as_deref(),
                item.video_codec.is_some(),
            )),
            media_id: None,
            history_code: Some(
                item.youtube_vid
                    .clone()
                    .unwrap_or_else(|| format!("@{}:{}", item.file_size, item.duration_ms.unwrap_or(0) / 1000)),
            ),
            youtube_vid: item.youtube_vid.clone(),
            loudness_gain_db: item.loudness_gain_db,
            live: false,
        }
    }

    /// The JVM `PlayableItem.displayThumbnailUrl`: a video's first listed thumbnail
    /// (`YtFeedItem.toYtMedia`), a local item's one art url.
    pub fn display_thumbnail_url(&self) -> Option<SharedString> {
        self.thumbs.first().map(|thumb| {
            if thumb.url.starts_with("//") { format!("https:{}", thumb.url) } else { thumb.url.clone() }.into()
        })
    }

    /// The thumbnail at least `width` px wide, else the largest.
    pub fn thumb(&self, width: i32) -> Option<SharedString> {
        self.thumbs.iter().find(|thumb| thumb.w.is_some_and(|w| w >= width)).or(self.thumbs.last()).map(|thumb| {
            if thumb.url.starts_with("//") { format!("https:{}", thumb.url) } else { thumb.url.clone() }.into()
        })
    }

    pub fn key(&self) -> String {
        match &self.source {
            Source::YouTube(video_id) => video_id.clone(),
            Source::Local(path) => path.to_string_lossy().into_owned(),
        }
    }

    pub fn video_id(&self) -> Option<&str> {
        match &self.source {
            Source::YouTube(video_id) => Some(video_id),
            Source::Local(_) => None,
        }
    }

    /// The JVM `PlaybackCoordinator.downloadable`: the video id of a non-live YouTube item.
    pub fn downloadable(&self) -> Option<&str> {
        self.video_id().filter(|_| !self.live)
    }
}

/// Reads the spectrum of what plays now; [`LevelTap::read`] blocks, so call it off the UI thread.
#[derive(Clone)]
pub enum LevelTap {
    YouTube(Arc<ym_mse::MseHost>),
    Local(LocalLevelTap),
}

impl LevelTap {
    /// Bars in 0..=1, bass first; empty while nothing plays.
    pub fn read(&self, count: usize) -> Vec<f32> {
        match self {
            Self::YouTube(host) => host.levels(count),
            Self::Local(tap) => tap.read(count),
        }
    }
}

/// Where Settings → Show FPS reads the shown video's picture counts.
pub enum FrameCounter {
    YouTube(Arc<ym_mse::MseHost>),
    Local(LocalLevelTap),
}

impl FrameCounter {
    /// Pictures shown, pictures dropped and the video decoder; blocking. The local player counts
    /// no drops and names no decoder, as in the JVM app.
    pub fn read(&self) -> Option<(i32, i32, Option<String>)> {
        match self {
            Self::YouTube(host) => {
                let (shown, dropped) = host.video_quality();
                Some((shown, dropped, host.video_decoder()))
            }
            Self::Local(tap) => tap.frames().map(|shown| (shown, 0, None)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Active {
    YouTube,
    Local,
}

/// The JVM `VideoQualityState`: the chip's choices (token, label), empty while the item offers none,
/// the quality playing and the preferred one.
#[derive(Clone, Debug, PartialEq)]
pub struct VideoQualityState {
    pub options: Vec<(String, String)>,
    pub actual: String,
    pub preferred: String,
}

impl VideoQualityState {
    fn empty(actual: &str, preferred: &str) -> Self {
        Self { options: Vec::new(), actual: actual.to_owned(), preferred: preferred.to_owned() }
    }
}

/// The JVM `VideoQualityState.LADDER`: tiers keyed by a picture's smaller side, ascending.
const QUALITY_LADDER: [(&str, &str, i32); 10] = [
    ("tiny", "144p", 144),
    ("small", "240p", 240),
    ("medium", "360p", 360),
    ("large", "480p", 480),
    ("hd720", "720p", 720),
    ("hd1080", "1080p", 1080),
    ("hd1440", "1440p", 1440),
    ("hd2160", "4K", 2160),
    ("hd2880", "5K", 2880),
    ("highres", "8K", 4320),
];

/// The JVM `optionForHeight`: the nearest tier at or below `side`.
fn quality_for_side(side: i32) -> (String, String) {
    let (quality, label, _) =
        QUALITY_LADDER.iter().rev().find(|(_, _, height)| side >= *height).unwrap_or(&QUALITY_LADDER[0]);
    ((*quality).to_owned(), (*label).to_owned())
}

/// An audio track (dub language) the item offers.
#[derive(Clone, Debug, PartialEq)]
pub struct AudioTrack {
    pub id: String,
    pub display_name: String,
}

/// The JVM `AudioTrackState`: the item's audio tracks and the selected one's id.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct AudioTrackState {
    pub tracks: Vec<AudioTrack>,
    pub selected: Option<String>,
}

/// A caption track the playing video offers.
#[derive(Clone, Debug, PartialEq)]
pub struct CaptionTrack {
    pub vss_id: String,
    pub language_code: String,
    pub display_name: String,
    /// `"asrt"` for YouTube's auto-translations of the video's main track.
    pub kind: String,
}

pub const AUTO_TRANSLATE_KIND: &str = "asrt";

/// A YouTube video's owner channel, as its player response names it.
#[derive(Clone, Debug, PartialEq)]
pub struct MediaDetails {
    pub video_id: String,
    pub channel_title: Option<String>,
    /// An app link to the channel page.
    pub channel_url: Option<String>,
}

/// Ends when the sender is dropped.
fn start_commands(session: &YoutubeSession, cx: &App) -> mpsc::UnboundedSender<PlayerJob> {
    let (sender, mut receiver) = mpsc::unbounded::<PlayerJob>();
    let engine = session.engine.clone();
    cx.global::<AppGraph>().tokio.spawn(async move {
        while let Some(job) = receiver.next().await {
            job(engine.clone()).await;
        }
    });
    sender
}

pub struct Playback {
    session: Option<Arc<YoutubeSession>>,
    /// The session's player commands, run one at a time in call order.
    commands: Option<mpsc::UnboundedSender<PlayerJob>>,
    booting: bool,
    /// Bumped by every boot; a boot that a later reset superseded is discarded when it lands.
    boot_generation: u64,
    /// The YouTube video a load waits to send until the engine is ready to load (the JVM
    /// PlayerImpl.prepareJob); a newer load or a stop cancels it.
    prepare_job: Option<String>,
    /// The Error the YouTube engine holds while it is not the bound engine (the JVM MsePlayer keeps
    /// its Error status across a stop).
    youtube_error: Option<PlaybackFailure>,
    local: Option<LocalEngine>,
    local_generation: u64,
    active: Option<Active>,
    pub queue: Vec<QueueItem>,
    pub index: usize,
    /// Names the queue the last play started: Play again saves it under this.
    pub queue_id: String,
    /// Bumped whenever the queue's items change.
    pub queue_revision: u64,
    /// Where the next load starts, once.
    start_position_ms: i64,
    /// SponsorBlock spans (start, end ms) for the item with this key.
    skip_segments: Option<(String, Vec<(i64, i64)>)>,
    /// The span last skipped and the item it was skipped in, until the play-head leaves its start
    /// second (the JVM skipSponsorLoop's skippedSeg and lastItem).
    skipped: Option<(String, (i64, i64))>,
    /// A skip waiting for its span's start; position reports wait with it.
    skip_timer: Option<Task<()>>,
    /// The item an auto-advance plays: peeked once per load or queue change, so a shuffle pick is
    /// drawn once and the preloaded item is the one that plays (the JVM prepareNextIndex).
    prepare_next_index: Option<usize>,
    /// The preload for `prepare_next_index` ran or was ruled out.
    prepare_next_armed: bool,
    /// `prepare_next_index` is a shuffle pick from a page just loaded, which a skip plays too (the
    /// JVM nextFromPage).
    next_from_page: bool,
    /// Where the queue grows from (the JVM PlayerImpl.source).
    source: Option<NextQueueSource>,
    /// The source's side of the queue (the JVM QueueMore); None without a source.
    pub more: Option<QueueMore>,
    _source_page: Option<Task<()>>,
    /// The load whose track change last cleared a failed page (the JVM rolledGeneration).
    rolled_generation: Option<u64>,
    pub state: PlaybackState,
    pub position_ms: i64,
    pub duration_ms: i64,
    /// How far a streamed item is loaded; 0 for a local file, which needs no loading line.
    pub buffered_ms: i64,
    /// A live stream: the player maps its DVR window onto position and duration, the end being the
    /// live head.
    pub live: bool,
    /// Whether the play-head may move; a live head without DVR may not.
    pub seekable: bool,
    loading_video: Option<String>,
    /// The YouTube video whose progress the player last confirmed; None after a stop (the JVM
    /// MsePlayer's loadedVideoId).
    loaded_video: Option<String>,
    /// The next YouTube load resets the engine first (the JVM PlayerImpl.ytResetOnNext, set by
    /// `refreshOnNext` after a sign-in change).
    yt_reset_on_next: bool,
    /// Bumped by every load; `ended_generation` is the load whose end was already handled (the JVM
    /// loadGeneration / advancedGeneration).
    load_generation: u64,
    ended_generation: Option<u64>,
    /// The video whose acknowledged load still waits for progress or details.
    load_timeout_video: Option<String>,
    _load_timeout: Option<Task<()>>,
    gain: f32,
    /// The local item's attenuate-only loudness gain.
    local_gain: f32,
    pub volume: f32,
    pub effects: AudioEffects,
    pub nav: QueueNav,
    /// The loaded video's channel, once the player reports it.
    details: Option<MediaDetails>,
    /// Whether a loaded item should play: what the user last asked for (the JVM PlayerImpl's
    /// playWhenReadyIntent). Every load reads it.
    play_when_ready: bool,
    sleep_deadline: Option<Instant>,
    pub sleep_after_current: bool,
    pub captions: bool,
    pub caption_tracks: Vec<CaptionTrack>,
    /// The track the bundle shows (a vssId or a language code), from the catalog stream.
    pub caption_selected: Option<String>,
    /// The track the user last picked, any engine's (the JVM `PlaybackConfig.preferCaptionLanguage`);
    /// a local file shows it when it has one.
    caption_language: String,
    /// The cues on screen now, oldest first: (cue id, text with hard breaks).
    pub caption_lines: Vec<(i64, String)>,
    caption_stream: Option<(tokio::task::JoinHandle<()>, Task<()>)>,
    _catalog: Option<Task<()>>,
    _sleep: Option<Task<()>>,
    /// Video on: loads request pictures and the presenter shows them; off: audio only.
    pub video: bool,
    /// The YouTube engine's qualities from its catalog (the JVM `MsePlayer.applyCatalog`); no options
    /// until it reports.
    youtube_quality: VideoQualityState,
    /// The YouTube engine's audio tracks from its catalog, kept until the next catalog.
    youtube_audio_tracks: AudioTrackState,
    /// The quality loads ask for while video is on.
    pub quality_preferred: String,
    /// The last video box Now Playing reported, sent again once a session starts.
    viewport: Option<(i32, i32)>,
    /// No view shows the picture: the engines rest their video decoders, keeping the chosen quality.
    /// True until the shell first draws.
    video_hidden: bool,
    /// Video was switched to audio only because the window went out of sight; never saved.
    demoted: bool,
    /// Presents into the Now Playing video layer, which outlives it.
    presenter: Option<Arc<Presenter>>,
    _status: Option<Task<()>>,
    _local_poll: Option<Task<()>>,
}

impl Playback {
    pub fn new(cx: &App) -> Self {
        // Debug runs can be silenced for automated checks.
        let muted = cfg!(debug_assertions) && std::env::var_os("YMUSIC_DEV_MUTE").is_some();
        let saved_volume =
            cx.global::<AppGraph>().settings.string(VOLUME_KEY, "1").ok().and_then(|raw| raw.parse::<f32>().ok());
        Self {
            session: None,
            commands: None,
            booting: false,
            boot_generation: 0,
            prepare_job: None,
            youtube_error: None,
            local: None,
            local_generation: 0,
            active: None,
            queue: Vec::new(),
            index: 0,
            queue_id: String::new(),
            queue_revision: 0,
            start_position_ms: 0,
            skip_segments: None,
            skipped: None,
            skip_timer: None,
            prepare_next_index: None,
            prepare_next_armed: false,
            next_from_page: false,
            source: None,
            more: None,
            _source_page: None,
            rolled_generation: None,
            state: PlaybackState::Uninitialized { is_initializing: false },
            position_ms: 0,
            duration_ms: 0,
            buffered_ms: 0,
            live: false,
            seekable: true,
            loading_video: None,
            loaded_video: None,
            yt_reset_on_next: false,
            load_generation: 0,
            ended_generation: None,
            load_timeout_video: None,
            _load_timeout: None,
            youtube_quality: VideoQualityState::empty(QUALITY_AUTO, QUALITY_AUTO),
            youtube_audio_tracks: AudioTrackState::default(),
            viewport: None,
            quality_preferred: cx
                .global::<AppGraph>()
                .settings
                .string(QUALITY_KEY, QUALITY_AUTO)
                .ok()
                .filter(|quality| quality != QUALITY_AUDIO_ONLY && !quality.is_empty())
                .unwrap_or_else(|| QUALITY_AUTO.to_owned()),
            gain: 1.0,
            local_gain: 1.0,
            volume: if muted { 0.0 } else { saved_volume.unwrap_or(1.0).clamp(0.0, 1.0) },
            effects: AudioEffects::load(cx.global::<AppGraph>().settings.as_ref()),
            nav: {
                let settings = &cx.global::<AppGraph>().settings;
                let saved = |key: &str| settings.get_raw(key).ok().flatten().unwrap_or_default();
                QueueNav::new(LoopMode::from_name(&saved(LOOP_MODE_KEY)), saved(SHUFFLE_KEY) == "1")
            },
            details: None,
            play_when_ready: true,
            sleep_deadline: None,
            sleep_after_current: false,
            captions: false,
            caption_tracks: Vec::new(),
            caption_selected: None,
            caption_language: crate::local_captions::UNDETERMINED_LANGUAGE.to_owned(),
            caption_lines: Vec::new(),
            caption_stream: None,
            _catalog: None,
            _sleep: None,
            video: cx.global::<AppGraph>().settings.string(AUDIO_ONLY_KEY, "1").is_ok_and(|raw| raw == "0"),
            video_hidden: true,
            demoted: false,
            presenter: None,
            _status: None,
            _local_poll: None,
        }
    }

    pub fn current(&self) -> Option<&QueueItem> {
        self.queue.get(self.index)
    }

    /// The MSE host while it shows the current item's video.
    /// The counter of the video on screen, for either engine.
    pub fn frame_counter(&self) -> Option<FrameCounter> {
        if !self.video || self.video_hidden {
            return None;
        }
        match self.active? {
            Active::YouTube => self.session.as_ref().map(|session| FrameCounter::YouTube(session.host.clone())),
            Active::Local => self.local.as_ref().map(|local| FrameCounter::Local(local.level_tap())),
        }
    }

    /// A YouTube video waits for the engine to be ready to load: there is no player to command yet,
    /// and the waiting load takes [Self::play_when_ready] when it is sent.
    fn awaiting_session(&self) -> bool {
        self.active == Some(Active::YouTube) && self.prepare_job.is_some()
    }

    /// The YouTube engine's own state (the JVM MsePlayer.status): the bound state while it is bound;
    /// otherwise the Error it was stopped in, else what its session gives.
    fn youtube_state(&self) -> PlaybackState {
        if self.active == Some(Active::YouTube) {
            return self.state.clone();
        }
        if let Some(failure) = &self.youtube_error {
            return PlaybackState::Error(failure.clone());
        }
        if self.session.is_some() {
            PlaybackState::Idle
        } else {
            PlaybackState::Uninitialized { is_initializing: self.booting }
        }
    }

    /// What the play/pause controls and the OS session show: the user's intent, not the engine's
    /// buffering (the JVM `showsPause`, `playWhenReady && !ended`).
    pub fn is_playing(&self) -> bool {
        self.play_when_ready && !self.state.is_ended()
    }

    /// The user's play intent (the JVM `PlaybackStatus.playWhenReady`), true across load gaps.
    pub fn play_when_ready(&self) -> bool {
        self.play_when_ready
    }

    /// The JVM Now Playing `isBufferingOrLoading`: the engine buffers, boots, or holds no media yet
    /// while the user wants playback.
    pub fn is_buffering_or_loading(&self) -> bool {
        match &self.state {
            PlaybackState::Prepared { buffering, .. } => *buffering,
            PlaybackState::Uninitialized { is_initializing } => *is_initializing,
            PlaybackState::Idle => self.play_when_ready,
            PlaybackState::Error(_) => false,
        }
    }

    /// The JVM `MsePlayer.stop` / `JvmLocalPlayer.stop` state: the bound engine stops into Idle, the
    /// YouTube engine staying as it is while it has no session or has failed; with no engine bound,
    /// the JVM PlayerImpl.stopActive's fresh status.
    fn stopped_state(&self) -> PlaybackState {
        match (self.active, &self.state) {
            (None, _) => PlaybackState::Uninitialized { is_initializing: false },
            (Some(Active::YouTube), PlaybackState::Uninitialized { .. } | PlaybackState::Error(_)) => {
                self.state.clone()
            }
            _ => PlaybackState::Idle,
        }
    }

    /// The JVM PlayerImpl.stopActive: a load waiting for the YouTube engine is dropped and the
    /// engines stop. A YouTube session in Error stays up, its Error with it, so a broken one is reset
    /// by the next load.
    fn stop_active(&mut self, cx: &mut Context<Self>) {
        self.prepare_job = None;
        self.send_local(LocalCommand::Stop);
        self.loaded_video = None;
        self.command(cx, |engine, log| async move { engine.player_stop(&log).await });
        self.state = self.stopped_state();
    }

    pub fn level_tap(&self) -> Option<LevelTap> {
        match self.active? {
            Active::YouTube => self.session.as_ref().map(|session| LevelTap::YouTube(session.host.clone())),
            Active::Local => self.local.as_ref().map(|local| LevelTap::Local(local.level_tap())),
        }
    }

    /// Whether the current item has a picture to show; `None` until its engine reports.
    pub fn has_video_track(&self) -> Option<bool> {
        match (self.active?, &self.current()?.source) {
            (Active::Local, Source::Local(path)) => {
                let status = self.local.as_ref()?.status.lock().unwrap();
                status.video_track.as_ref().filter(|(open, _)| open == path).map(|(_, video)| *video)
            }
            (Active::YouTube, Source::YouTube(_)) if self.youtube_quality.options.is_empty() => None,
            (Active::YouTube, Source::YouTube(_)) => Some(self.youtube_quality.actual != QUALITY_AUDIO_ONLY),
            _ => None,
        }
    }

    /// The current engine's qualities (the JVM `Player.videoQuality`): a local file with a picture
    /// offers its resolution tier, audio only and Auto.
    pub fn video_quality(&self) -> VideoQualityState {
        let preferred = self.quality_preferred.as_str();
        match (self.active, self.current().map(|item| &item.source)) {
            (Some(Active::YouTube), Some(Source::YouTube(_))) => self.youtube_quality.clone(),
            (Some(Active::Local), Some(Source::Local(path))) => {
                let side = self.local.as_ref().and_then(|local| {
                    let status = local.status.lock().unwrap();
                    status.video_track.as_ref().filter(|(open, _)| open == path).map(|_| status.video_side)
                });
                match side {
                    None => VideoQualityState::empty(QUALITY_AUTO, preferred),
                    Some(side) if side <= 0 => VideoQualityState::empty(QUALITY_AUDIO_ONLY, preferred),
                    Some(side) => {
                        let tier = quality_for_side(side);
                        let actual = if self.video { tier.0.clone() } else { QUALITY_AUDIO_ONLY.to_owned() };
                        VideoQualityState {
                            options: vec![
                                tier,
                                (QUALITY_AUDIO_ONLY.to_owned(), "Audio-only".to_owned()),
                                (QUALITY_AUTO.to_owned(), "Auto".to_owned()),
                            ],
                            actual,
                            preferred: preferred.to_owned(),
                        }
                    }
                }
            }
            _ => VideoQualityState::empty(QUALITY_AUTO, QUALITY_AUTO),
        }
    }

    /// The current engine's audio tracks (the JVM `Player.audioTracks`).
    pub fn audio_tracks(&self) -> AudioTrackState {
        match (self.active, self.current().map(|item| &item.source)) {
            (Some(Active::YouTube), Some(Source::YouTube(_))) => self.youtube_audio_tracks.clone(),
            (Some(Active::Local), Some(Source::Local(path))) => self
                .local
                .as_ref()
                .and_then(|local| {
                    let status = local.status.lock().unwrap();
                    status.video_track.as_ref().filter(|(open, _)| open == path).map(|_| AudioTrackState {
                        tracks: status.audio_tracks.clone(),
                        selected: status.audio_track_selected.clone(),
                    })
                })
                .unwrap_or_default(),
            _ => AudioTrackState::default(),
        }
    }

    /// Switches the current item to this audio track: a one-shot pick, never saved (the JVM
    /// `Player.selectAudioTrack`).
    pub fn select_audio_track(&mut self, id: String, cx: &mut Context<Self>) {
        match self.active {
            Some(Active::YouTube) => {
                self.youtube_audio_tracks.selected = Some(id.clone());
                self.command(cx, move |engine, log| async move { engine.player_set_audio_track(&id, &log).await });
            }
            Some(Active::Local) => {
                let Ok(index) = id.parse::<i32>() else { return };
                if let Some(local) = &self.local {
                    local.status.lock().unwrap().audio_track_selected = Some(id);
                }
                self.send_local(LocalCommand::AudioTrack(index));
            }
            None => return,
        }
        cx.notify();
    }

    /// A playlist page's videos from `item`, the rest of the list (`total_count` long when the page
    /// says) loading as the queue goes (the JVM playAll with NextQueueSource.playlist).
    pub fn play_playlist(
        &mut self,
        item: YtFeedItem,
        videos: Vec<YtFeedItem>,
        url: String,
        total_count: Option<i32>,
        cx: &mut Context<Self>,
    ) {
        let videos: Vec<YtFeedItem> = videos.into_iter().filter(|video| video.kind == "video").collect();
        if videos.is_empty() {
            return;
        }
        let source = NextQueueSource::playlist(url, &videos, total_count, queue_source_log(cx));
        self.index = videos.iter().position(|candidate| candidate.id == item.id).unwrap_or(0);
        self.queue = videos.iter().map(QueueItem::from_feed).collect();
        self.start_queue(source, cx);
    }

    /// Plays a feed video. A `section` queue plays in order; without one the engine's radio for
    /// the video becomes the queue.
    pub fn play_feed(&mut self, item: YtFeedItem, section: Option<Vec<YtFeedItem>>, cx: &mut Context<Self>) {
        let source = match section {
            Some(items) => {
                self.index = items.iter().position(|candidate| candidate.id == item.id).unwrap_or(0);
                self.queue = items.iter().map(QueueItem::from_feed).collect();
                None
            }
            None => {
                self.queue = vec![QueueItem::from_feed(&item)];
                self.index = 0;
                Some(NextQueueSource::radio(&item.id, queue_source_log(cx)))
            }
        };
        self.start_queue(source, cx);
    }

    /// Plays a linked video from `start_ms`: the link's playlist continues after it, else its radio.
    pub fn play_link(
        &mut self,
        item: YtFeedItem,
        playlist_url: Option<String>,
        start_ms: Option<i64>,
        cx: &mut Context<Self>,
    ) {
        let log = queue_source_log(cx);
        let source = match playlist_url {
            Some(url) => NextQueueSource::playlist(url, std::slice::from_ref(&item), None, log),
            None => Some(NextQueueSource::radio(&item.id, log)),
        };
        self.queue = vec![QueueItem::from_feed(&item)];
        self.index = 0;
        self.start_position_ms = start_ms.unwrap_or(0).max(0);
        self.start_queue(source, cx);
    }

    /// The JVM setQueue for the queue just set: a new queue id, `source` attached, then the current
    /// item loads, playing.
    fn start_queue(&mut self, source: Option<NextQueueSource>, cx: &mut Context<Self>) {
        self.attach_source(source.filter(|_| !self.queue.is_empty()));
        self.new_queue();
        self.play_when_ready = true;
        self.publish_queue(false, cx);
        self.load_current(cx);
    }

    fn new_queue(&mut self) {
        self.queue_id = uuid::Uuid::new_v4().to_string();
        self.queue_revision += 1;
        self.nav.reset(self.index);
    }

    /// Resumes a saved queue as the same queue, `start_ms` into its current item, its playlist or
    /// radio continuing where `source_token` says.
    pub fn resume_queue(
        &mut self,
        queue_id: String,
        items: Vec<QueueItem>,
        index: usize,
        start_ms: i64,
        source_token: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if items.is_empty() {
            return;
        }
        let source = source_token.and_then(|token| NextQueueSource::resume(&token, &items, queue_source_log(cx)));
        self.attach_source(source);
        self.index = index.min(items.len() - 1);
        self.queue = items;
        self.queue_id = queue_id;
        self.queue_revision += 1;
        self.nav.reset(self.index);
        self.start_position_ms = start_ms.max(0);
        self.play_when_ready = true;
        self.publish_queue(false, cx);
        self.load_current(cx);
    }

    /// Plays `item` right after the current one; starts it when nothing is queued.
    pub fn queue_next(&mut self, item: QueueItem, cx: &mut Context<Self>) {
        if self.queue.is_empty() {
            self.play_queue(vec![item], 0, cx);
            return;
        }
        let at = self.nav.add_next(self.index);
        self.queue.insert(at, item);
        self.queue_revision += 1;
        self.publish_queue(true, cx);
        cx.notify();
    }

    /// Play next for a queued item: behind the songs already queued with Play next (the JVM moveNext).
    pub fn move_next(&mut self, from: usize, cx: &mut Context<Self>) {
        if from >= self.queue.len() {
            return;
        }
        let Some(to) = self.nav.move_next(from, self.index) else { return };
        if from != to {
            let item = self.queue.remove(from);
            self.queue.insert(to, item);
            self.index = moved_index(self.index, from, to);
        }
        self.queue_revision += 1;
        self.publish_queue(true, cx);
        cx.notify();
    }

    /// Adds `item` at the end of the queue; starts it when nothing is queued.
    pub fn queue_last(&mut self, item: QueueItem, cx: &mut Context<Self>) {
        if self.queue.is_empty() {
            self.play_queue(vec![item], 0, cx);
            return;
        }
        self.queue.push(item);
        self.queue_revision += 1;
        self.publish_queue(true, cx);
        cx.notify();
    }

    /// Moves the item at `from` to `to`; the playing item keeps playing wherever it lands.
    pub fn move_item(&mut self, from: usize, to: usize, cx: &mut Context<Self>) {
        if from >= self.queue.len() || to >= self.queue.len() || from == to {
            return;
        }
        let item = self.queue.remove(from);
        self.queue.insert(to, item);
        self.index = moved_index(self.index, from, to);
        self.nav.moved(from, to);
        self.queue_revision += 1;
        self.publish_queue(true, cx);
        cx.notify();
    }

    /// Replaces the queue with `item` and its radio.
    pub fn start_radio(&mut self, item: QueueItem, cx: &mut Context<Self>) {
        let Some(video_id) = item.video_id().map(str::to_owned) else { return };
        self.queue = vec![item];
        self.index = 0;
        self.start_queue(Some(NextQueueSource::radio(&video_id, queue_source_log(cx))), cx);
    }

    /// Stops the current item's engine and loads the item again where it was, playing: recovers a
    /// wedged player.
    pub fn reload(&mut self, cx: &mut Context<Self>) {
        if self.current().is_none() {
            return;
        }
        self.play_when_ready = true;
        self.start_position_ms = self.position_ms.max(0);
        match self.active {
            Some(Active::Local) => self.send_local(LocalCommand::Stop),
            Some(Active::YouTube) => self.reset_youtube(cx),
            None => {}
        }
        self.load_current(cx);
    }

    /// The current video's channel title and app link, when the player has named them.
    pub fn current_details(&self) -> Option<&MediaDetails> {
        let video_id = self.current().and_then(QueueItem::video_id)?;
        self.details.as_ref().filter(|details| details.video_id == video_id)
    }

    /// Loads the current item again after a failure.
    pub fn retry(&mut self, cx: &mut Context<Self>) {
        self.play_when_ready = true;
        self.load_current(cx);
    }

    /// Stops playback and empties the queue.
    pub fn clear_queue(&mut self, cx: &mut Context<Self>) {
        if self.queue.is_empty() {
            return;
        }
        self.attach_source(None);
        self.queue.clear();
        self.queue_revision += 1;
        self.index = 0;
        self.stop_active(cx);
        self.nav.reset(self.index);
        self.publish_queue(true, cx);
        cx.notify();
    }

    /// Takes the item at `index` out of the queue; removing the playing one moves on.
    pub fn remove_at(&mut self, index: usize, cx: &mut Context<Self>) {
        if index >= self.queue.len() {
            return;
        }
        self.queue.remove(index);
        self.queue_revision += 1;
        let was_current = index == self.index;
        if index < self.index {
            self.index -= 1;
        } else if was_current && !self.queue.is_empty() {
            self.index = self.index.min(self.queue.len() - 1);
        }
        let current = (!self.queue.is_empty()).then_some(self.index);
        self.nav.removed_at(index, was_current, current);
        self.publish_queue(!was_current, cx);
        if was_current {
            if self.queue.is_empty() {
                self.index = 0;
                self.stop_active(cx);
                self.refresh_prepare_next(cx);
            } else {
                self.load_current(cx);
            }
        }
        cx.notify();
    }

    /// Seeks by `delta_ms` from where playback is, within the item.
    pub fn seek_by(&mut self, delta_ms: i64, cx: &mut Context<Self>) {
        let target = (self.position_ms + delta_ms).max(0);
        let target = if self.duration_ms > 0 { target.min(self.duration_ms) } else { target };
        self.seek(target, cx);
    }

    /// Captions on or off, on the active engine; the YouTube bundle keeps the choice across videos.
    pub fn set_captions(&mut self, visible: bool, cx: &mut Context<Self>) {
        self.captions = visible;
        if self.active == Some(Active::Local) {
            self.send_local(LocalCommand::Captions(self.local_caption_prefs()));
        } else {
            self.command(cx, move |engine, log| async move { engine.player_set_caption(None, visible, &log).await });
        }
        self.sync_caption_stream(cx);
        cx.notify();
    }

    /// Shows captions from the track with this id (a YouTube vssId or language code, a local track id).
    pub fn select_caption(&mut self, id: String, cx: &mut Context<Self>) {
        self.captions = true;
        self.caption_selected = Some(id.clone());
        self.caption_language = id.clone();
        if self.active == Some(Active::Local) {
            self.send_local(LocalCommand::Captions(self.local_caption_prefs()));
        } else {
            self.command(cx, move |engine, log| async move { engine.player_set_caption(Some(&id), true, &log).await });
        }
        self.sync_caption_stream(cx);
        cx.notify();
    }

    fn local_caption_prefs(&self) -> CaptionPrefs {
        CaptionPrefs { visible: self.captions, language: self.caption_language.clone() }
    }

    /// The cue stream runs only while captions are on: closing it pauses the bundle's caption work.
    fn sync_caption_stream(&mut self, cx: &mut Context<Self>) {
        let wanted = self.captions && self.session.is_some();
        if !wanted {
            if let Some((handle, _)) = self.caption_stream.take() {
                handle.abort();
            }
            self.caption_lines.clear();
            return;
        }
        if self.caption_stream.is_some() {
            return;
        }
        let Some(session) = self.session.clone() else { return };
        let (sender, mut receiver) = mpsc::unbounded::<Value>();
        let (engine, log) = (session.engine.clone(), cx.global::<AppGraph>().log.fork("captions"));
        let handle = cx.global::<AppGraph>().tokio.spawn(async move {
            if let Err(error) = engine
                .listen_player("player/caption", move |value| {
                    let _ = sender.unbounded_send(value);
                })
                .await
            {
                log.warn("caption stream failed", Some(&error));
            }
        });
        let task = cx.spawn(async move |this, cx| {
            while let Some(value) = receiver.next().await {
                let lines = value
                    .get("lines")
                    .and_then(Value::as_array)
                    .map(|lines| {
                        lines
                            .iter()
                            .filter_map(|line| {
                                let id = line.get("id").and_then(Value::as_i64)?;
                                Some((id, line.get("text").and_then(Value::as_str).unwrap_or_default().to_owned()))
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                let applied = this.update(cx, |playback, cx| {
                    if playback.captions && playback.active == Some(Active::YouTube) {
                        playback.caption_lines = lines;
                        cx.notify();
                    }
                });
                if applied.is_err() {
                    return;
                }
            }
        });
        self.caption_stream = Some((handle, task));
    }

    fn apply_catalog(&mut self, catalog: &Value, cx: &mut Context<Self>) {
        let tracks: Vec<CaptionTrack> = catalog
            .get("captionTracks")
            .and_then(Value::as_array)
            .map(|rows| {
                rows.iter()
                    .filter_map(|row| {
                        let vss_id = row.get("vssId").and_then(Value::as_str)?.to_owned();
                        let text = |key: &str| row.get(key).and_then(Value::as_str).map(str::to_owned);
                        Some(CaptionTrack {
                            display_name: text("displayName").unwrap_or_else(|| vss_id.clone()),
                            language_code: text("languageCode").unwrap_or_default(),
                            kind: text("kind").unwrap_or_default(),
                            vss_id,
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        self.caption_tracks = tracks;
        self.caption_selected = catalog.get("caption").and_then(Value::as_str).map(str::to_owned);
        let audio_only = catalog.get("audioOnly").and_then(Value::as_bool).unwrap_or(false);
        let text = |key: &str| catalog.get(key).and_then(Value::as_str);
        let (actual, preferred) = if audio_only {
            (QUALITY_AUDIO_ONLY, QUALITY_AUDIO_ONLY)
        } else {
            (text("quality").unwrap_or(QUALITY_AUTO), text("preferQuality").unwrap_or(&self.quality_preferred))
        };
        let engine_options: Vec<(String, String)> = catalog
            .get("qualities")
            .and_then(Value::as_array)
            .map(|rows| {
                rows.iter()
                    .filter_map(|row| {
                        let quality = row.get("quality").and_then(Value::as_str)?;
                        if quality == QUALITY_AUDIO_ONLY || quality == "tiny" {
                            return None;
                        }
                        let label = row.get("label").and_then(Value::as_str).unwrap_or(quality);
                        Some((quality.to_owned(), label.to_owned()))
                    })
                    .collect()
            })
            .unwrap_or_default();
        let mut options = vec![(QUALITY_AUDIO_ONLY.to_owned(), "Audio-only".to_owned())];
        if engine_options.is_empty() {
            options.push((QUALITY_AUTO.to_owned(), "Auto".to_owned()));
        } else {
            options.extend(engine_options);
        }
        self.youtube_quality =
            VideoQualityState { options, actual: actual.to_owned(), preferred: preferred.to_owned() };
        let audio_tracks = catalog
            .get("audioTracks")
            .and_then(Value::as_array)
            .map(|rows| {
                rows.iter()
                    .filter_map(|row| {
                        let id = row.get("id").and_then(Value::as_str)?;
                        let name = row.get("displayName").and_then(Value::as_str).unwrap_or(id);
                        Some(AudioTrack { id: id.to_owned(), display_name: name.to_owned() })
                    })
                    .collect()
            })
            .unwrap_or_default();
        self.youtube_audio_tracks =
            AudioTrackState { tracks: audio_tracks, selected: text("audioTrack").map(str::to_owned) };
        cx.notify();
    }

    /// Plays `items` in order from `index`.
    pub fn play_queue(&mut self, items: Vec<QueueItem>, index: usize, cx: &mut Context<Self>) {
        self.index = index.min(items.len().saturating_sub(1));
        self.queue = items;
        self.start_queue(None, cx);
    }

    /// Nothing queued: a no-op, as the YouTube engine keeps a stopped video that play would revive unseen.
    /// The JVM PlayerImpl.togglePlay: an ended item restarts from 0, otherwise the intent flips.
    pub fn toggle(&mut self, cx: &mut Context<Self>) {
        if self.current().is_none() {
            return;
        }
        if self.state.is_ended() {
            self.seek(0, cx);
            self.play(cx);
        } else if self.play_when_ready {
            self.pause(cx);
        } else {
            self.play(cx);
        }
    }

    pub fn seek(&mut self, position_ms: i64, cx: &mut Context<Self>) {
        if self.current().is_none() {
            return;
        }
        self.position_ms = position_ms;
        match self.active {
            Some(Active::Local) => self.send_local(LocalCommand::Seek(position_ms)),
            _ => self.command(cx, move |engine, log| async move { engine.player_seek(position_ms, &log).await }),
        }
        cx.notify();
    }

    pub fn has_next(&self) -> bool {
        self.nav.peek_next(true, self.index, self.queue.len()).is_some()
            || self.more.as_ref().is_some_and(|more| more.has_more)
    }

    /// A user skip.
    pub fn next(&mut self, cx: &mut Context<Self>) {
        let page_pick = self.prepare_next_index.filter(|_| self.next_from_page);
        if let Some(next) = page_pick.or_else(|| self.nav.peek_next(true, self.index, self.queue.len())) {
            self.nav.commit(next, self.queue.len());
            self.index = next;
            self.publish_queue(false, cx);
            self.load_current(cx);
        }
    }

    /// Restarts the current item past its intro span when it has played more than ~3 s beyond it.
    pub fn previous(&mut self, cx: &mut Context<Self>) {
        let intro_end = self.skip_restart_ms();
        if self.position_ms > SMART_PREVIOUS_MS + intro_end {
            self.seek(intro_end, cx);
            return;
        }
        match self.nav.previous(self.index, self.queue.len()) {
            Some(previous) => {
                self.index = previous;
                self.publish_queue(false, cx);
                self.load_current(cx);
            }
            None => self.seek(0, cx),
        }
    }

    /// The current item finished on its own.
    /// The JVM PlayerImpl.onTrackEnded: each load ends at most once. "Stop after current" leaves the
    /// next item loaded and paused (with Loop One, the same item at 0:00).
    fn track_ended(&mut self, cx: &mut Context<Self>) {
        if self.ended_generation == Some(self.load_generation) {
            return;
        }
        if self.sleep_after_current {
            self.sleep_after_current = false;
            self.play_when_ready = false;
            if self.nav.loop_mode == LoopMode::One {
                self.ended_generation = Some(self.load_generation);
                self.load_current(cx);
                return;
            }
        }
        match self.prepare_next_index {
            Some(next) if next == self.index && self.play_when_ready => {
                self.seek(0, cx);
                self.play(cx);
            }
            Some(next) => {
                self.ended_generation = Some(self.load_generation);
                self.nav.commit(next, self.queue.len());
                self.index = next;
                self.publish_queue(false, cx);
                self.load_current(cx);
            }
            None => self.ended_generation = Some(self.load_generation),
        }
    }

    /// The JVM PlayerImpl.play: the intent goes to playing; Prepared resumes in place, a retryable
    /// failure or an engine without the item loads it again where it was. A load waiting for the
    /// session takes the intent when it is sent.
    pub fn play(&mut self, cx: &mut Context<Self>) {
        self.play_when_ready = true;
        match &self.state {
            PlaybackState::Prepared { .. } => match self.active {
                Some(Active::Local) => {
                    self.send_local(LocalCommand::Play);
                    if let PlaybackState::Prepared { play_when_ready, ended, .. } = &mut self.state {
                        *play_when_ready = true;
                        *ended = false;
                    }
                }
                _ => self.command(cx, |engine, log| async move { engine.player_play(&log).await }),
            },
            PlaybackState::Error(failure) => {
                if failure.retry && self.current().is_some() {
                    self.start_position_ms = self.position_ms.max(0);
                    self.load_current(cx);
                }
                return;
            }
            PlaybackState::Idle | PlaybackState::Uninitialized { .. } => {
                if self.current().is_some() && !self.awaiting_session() {
                    self.start_position_ms = self.position_ms.max(0);
                    self.load_current(cx);
                    return;
                }
            }
        }
        cx.notify();
    }

    /// The JVM PlayerImpl.pause: the intent goes to paused whatever state the engine is in.
    pub fn pause(&mut self, cx: &mut Context<Self>) {
        self.play_when_ready = false;
        match self.active {
            Some(Active::Local) => {
                self.send_local(LocalCommand::Pause);
                if let PlaybackState::Prepared { play_when_ready, .. } = &mut self.state {
                    *play_when_ready = false;
                }
            }
            _ if self.awaiting_session() => {}
            _ => self.command(cx, |engine, log| async move { engine.player_pause(&log).await }),
        }
        cx.notify();
    }

    pub fn set_loop_mode(&mut self, mode: LoopMode, cx: &mut Context<Self>) {
        self.nav.loop_mode = mode;
        self.save_setting(LOOP_MODE_KEY, mode.name().to_owned(), cx);
        self.publish_queue(true, cx);
        cx.notify();
    }

    pub fn set_shuffle(&mut self, shuffle: bool, cx: &mut Context<Self>) {
        self.nav.set_shuffle(shuffle, self.index);
        self.save_setting(SHUFFLE_KEY, if shuffle { "1" } else { "0" }.to_owned(), cx);
        self.publish_queue(true, cx);
        cx.notify();
    }

    /// Pauses after `duration`; none disarms. Disarms the end-of-track sleep.
    pub fn set_sleep_timer(&mut self, duration: Option<Duration>, cx: &mut Context<Self>) {
        self.sleep_after_current = false;
        self.sleep_deadline = duration.map(|duration| Instant::now() + duration);
        self._sleep = duration.map(|_| {
            let executor = cx.background_executor().clone();
            cx.spawn(async move |this, cx| {
                loop {
                    executor.timer(Duration::from_secs(1)).await;
                    let done = this.update(cx, |playback, cx| {
                        let expired = playback.sleep_deadline.is_none_or(|deadline| Instant::now() >= deadline);
                        if expired {
                            playback.sleep_deadline = None;
                            playback.pause(cx);
                        }
                        cx.notify();
                        expired
                    });
                    if !matches!(done, Ok(false)) {
                        return;
                    }
                }
            })
        });
        cx.notify();
    }

    /// Stops when the current item ends; arming disarms a running timer.
    pub fn set_sleep_after_current(&mut self, enabled: bool, cx: &mut Context<Self>) {
        if enabled {
            self.set_sleep_timer(None, cx);
        }
        self.sleep_after_current = enabled;
        cx.notify();
    }

    /// What is left of the sleep timer, ticking each second.
    pub fn sleep_remaining(&self) -> Option<Duration> {
        self.sleep_deadline.map(|deadline| deadline.saturating_duration_since(Instant::now()))
    }

    fn save_setting(&self, key: &'static str, value: String, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (settings, log) = (graph.settings.clone(), graph.log.fork("playback"));
        graph.tokio.spawn_blocking(move || {
            if let Err(error) = settings.put_raw(key, value) {
                log.warn(&format!("{key} could not be saved: {error}"), None);
            }
        });
    }

    pub fn jump_to(&mut self, index: usize, cx: &mut Context<Self>) {
        if index < self.queue.len() {
            self.nav.jump(index, self.queue.len());
            self.index = index;
            self.publish_queue(false, cx);
            self.play_when_ready = true;
            self.load_current(cx);
        }
    }

    /// Hands over the video layer once a window has one.
    ///
    /// # Safety
    /// `layer` must be a live `AVSampleBufferDisplayLayer` that outlives this `Playback`.
    #[cfg(target_os = "macos")]
    pub unsafe fn attach_video_layer(&mut self, layer: *mut std::ffi::c_void) {
        if self.presenter.is_some() {
            return;
        }
        // SAFETY: this function's contract is `on_layer`'s.
        if let Some(presenter) = unsafe { Presenter::on_layer(layer) } {
            self.attach_presenter(Arc::new(presenter));
        }
    }

    pub fn attach_presenter(&mut self, presenter: Arc<Presenter>) {
        if self.presenter.is_some() {
            return;
        }
        self.presenter = Some(presenter);
        self.bind_presenter();
    }

    /// Pictures go to the engine playing the current item; the other one is unbound.
    fn bind_presenter(&self) {
        let Some(presenter) = &self.presenter else { return };
        let local = self.active == Some(Active::Local);
        if let Some(session) = &self.session {
            session.host.bind_presenter(if local { None } else { Some(presenter) });
        }
        if local {
            self.send_local(LocalCommand::Presenter(Some(presenter.clone())));
        } else {
            self.send_local(LocalCommand::Presenter(None));
        }
    }

    pub fn set_video(&mut self, video: bool, cx: &mut Context<Self>) {
        if self.video == video {
            return;
        }
        self.video = video;
        self.demoted = false;
        self.send_local(LocalCommand::Video(self.local_video()));
        if self.active == Some(Active::YouTube) {
            let (quality, audio_only) =
                if video { (self.quality_preferred.clone(), false) } else { (QUALITY_AUDIO_ONLY.to_owned(), true) };
            self.command(
                cx,
                move |engine, log| async move { engine.player_set_quality(&quality, audio_only, &log).await },
            );
        }
        cx.notify();
    }

    /// A quality from the menu: audio only, or video at that quality (remembered for next time).
    pub fn set_quality(&mut self, quality: String, cx: &mut Context<Self>) {
        if quality == QUALITY_AUDIO_ONLY {
            self.save_setting(AUDIO_ONLY_KEY, "1".to_owned(), cx);
            self.set_video(false, cx);
            return;
        }
        self.save_setting(QUALITY_KEY, quality.clone(), cx);
        self.save_setting(AUDIO_ONLY_KEY, "0".to_owned(), cx);
        self.quality_preferred = quality;
        if !self.video {
            self.set_video(true, cx);
            return;
        }
        self.demoted = false;
        if self.active == Some(Active::YouTube) {
            let quality = self.quality_preferred.clone();
            self.command(cx, move |engine, log| async move { engine.player_set_quality(&quality, false, &log).await });
        }
        cx.notify();
    }

    fn local_video(&self) -> bool {
        self.video && !self.video_hidden
    }

    /// Only Now Playing shows the picture; elsewhere the decoders rest and the cover stands in.
    pub fn set_video_hidden(&mut self, hidden: bool, cx: &mut Context<Self>) {
        if self.video_hidden == hidden {
            return;
        }
        self.video_hidden = hidden;
        self.send_local(LocalCommand::Video(self.local_video()));
        self.apply_youtube_video_hidden(cx);
    }

    /// Resting can wait on the decoder stopping, so it runs off the UI thread.
    fn apply_youtube_video_hidden(&self, cx: &App) {
        let Some(session) = &self.session else { return };
        let (host, hidden) = (session.host.clone(), self.video_hidden);
        cx.global::<AppGraph>().tokio.spawn_blocking(move || host.set_video_hidden(hidden));
    }

    /// Out of sight, video plays as audio only; it comes back with the window.
    pub fn set_window_hidden(&mut self, hidden: bool, cx: &mut Context<Self>) {
        if hidden && self.video {
            self.set_video(false, cx);
            self.demoted = true;
        } else if !hidden && self.demoted {
            self.set_video(true, cx);
        }
    }

    /// The video box in CSS pixels; YouTube's ABR picks the quality for it.
    /// The video box in logical pixels: YouTube's Auto picks the quality from it, so loads carry it too.
    pub fn set_viewport(&mut self, width: i32, height: i32, cx: &mut Context<Self>) {
        self.viewport = Some((width, height));
        self.command(cx, move |engine, log| async move { engine.player_set_viewport(width, height, &log).await });
    }

    pub fn set_volume(&mut self, volume: f32, cx: &mut Context<Self>) {
        self.volume = volume.clamp(0.0, 1.0);
        self.apply_volume();
        let settings = cx.global::<AppGraph>().settings.clone();
        let raw = ym_settings::kotlin_float(self.volume);
        let saved =
            on_core(cx, async move { tokio::task::spawn_blocking(move || settings.put_raw(VOLUME_KEY, raw)).await });
        cx.background_executor().spawn(saved).detach();
        cx.notify();
    }

    /// Applies and saves speed, pitch and tone for both engines.
    pub fn set_effects(&mut self, effects: AudioEffects, cx: &mut Context<Self>) {
        if self.effects == effects {
            return;
        }
        let speed_changed = (self.effects.speed, self.effects.pitch) != (effects.speed, effects.pitch);
        self.effects = effects;
        self.send_local(LocalCommand::Effects(effects));
        if let Some(session) = &self.session {
            session.host.set_tone(&effects.tone);
        }
        if speed_changed && self.active == Some(Active::YouTube) {
            let (speed, pitch) = (effects.speed, effects.pitch);
            self.command(cx, move |engine, log| async move { engine.player_set_speed_pitch(speed, pitch, &log).await });
        }
        let graph = cx.global::<AppGraph>();
        let (settings, log) = (graph.settings.clone(), graph.log.fork("audio-effects"));
        graph.tokio.spawn_blocking(move || {
            for (key, value) in effects.rows() {
                if let Err(error) = settings.put_raw(key, value) {
                    log.warn(&format!("{key} could not be saved: {error}"), None);
                }
            }
        });
        cx.notify();
    }

    fn apply_youtube_tone(&self) {
        let Some(session) = &self.session else { return };
        session.host.set_tone(&self.effects.tone);
    }

    fn apply_volume(&self) {
        if let Some(session) = &self.session {
            session.host.set_volume(self.volume * self.gain);
        }
        self.send_local(LocalCommand::Volume(self.volume * self.local_gain));
    }

    fn send_local(&self, command: LocalCommand) {
        if let Some(local) = &self.local {
            local.send(command);
        }
    }

    /// The current item's SponsorBlock spans (start, end ms); none while they are another item's.
    pub fn skip_segments(&self) -> &[(i64, i64)] {
        let key = self.current().map(QueueItem::key);
        match &self.skip_segments {
            Some((owner, segments)) if key.as_ref() == Some(owner) => segments,
            _ => &[],
        }
    }

    /// The span starting in this second; a later one of the same second wins (the JVM segments map,
    /// keyed by start second).
    fn skip_span_at(&self, second: i64) -> Option<(i64, i64)> {
        self.skip_segments().iter().rev().find(|(start, _)| start / 1000 == second).copied()
    }

    /// The JVM `skipRestartMs`: where a restart lands, past a skip span starting in the first second.
    fn skip_restart_ms(&self) -> i64 {
        self.skip_span_at(0).map_or(0, |(_, end)| end)
    }

    /// Spans to skip in the item with `key`; spans under a second are ignored.
    pub fn set_skip_segments(&mut self, key: String, segments: Vec<(i64, i64)>) {
        let usable: Vec<(i64, i64)> = segments.into_iter().filter(|(start, end)| end - start >= MIN_SKIP_MS).collect();
        self.skip_segments = (!usable.is_empty()).then_some((key, usable));
    }

    /// The JVM skipSponsorLoop: a span is skipped when the play-head is in its start second (or
    /// position / 3000, so a late fetch still catches an intro), the seek timed to its start; it is
    /// remembered until the play-head leaves that second, so seeking back or a replay skips it again.
    fn check_skip(&mut self, cx: &mut Context<Self>) {
        if self.skip_timer.is_some() {
            return;
        }
        let Some(key) = self.current().map(QueueItem::key) else {
            self.skipped = None;
            return;
        };
        if self.skipped.as_ref().is_some_and(|(item, _)| *item != key) || self.skip_segments.is_none() {
            self.skipped = None;
        }
        if !matches!(&self.skip_segments, Some((owner, _)) if *owner == key) {
            return;
        }
        let position = self.position_ms;
        let nearest = self.skip_span_at(position / 1000).or_else(|| self.skip_span_at(position / 3000));
        if let Some(span) = nearest
            && self.skipped == Some((key.clone(), span))
        {
            return;
        }
        self.skipped = None;
        let Some((start, end)) = nearest else { return };
        if position >= end {
            return;
        }
        if start > position {
            let executor = cx.background_executor().clone();
            self.skip_timer = Some(cx.spawn(async move |this, cx| {
                executor.timer(Duration::from_millis((start - position) as u64)).await;
                this.update(cx, |playback, cx| {
                    playback.skip_timer = None;
                    playback.skip_span((start, end), cx);
                })
                .ok();
            }));
            return;
        }
        self.skip_span((start, end), cx);
    }

    fn skip_span(&mut self, span: (i64, i64), cx: &mut Context<Self>) {
        let Some(key) = self.current().map(QueueItem::key) else { return };
        if !self.state.is_playing() || !matches!(&self.skip_segments, Some((owner, _)) if *owner == key) {
            return;
        }
        let duration = self.duration_ms;
        if self.finishes_track(span, duration) {
            self.track_ended(cx);
        } else if self.loops_track(span, duration) {
            self.seek(self.skip_restart_ms(), cx);
        } else {
            self.seek(span.1, cx);
        }
        self.skipped = Some((key, span));
    }

    /// The JVM `contentEndMs`: the duration pulled in to the start of a chain of spans running out
    /// the track.
    fn content_end_ms(&self, duration_ms: i64) -> i64 {
        let segments = self.skip_segments();
        let mut spans: Vec<(i64, i64)> = segments
            .iter()
            .enumerate()
            .filter(|(at, (start, _))| !segments[at + 1..].iter().any(|(later, _)| later / 1000 == start / 1000))
            .map(|(_, span)| *span)
            .collect();
        spans.sort_by_key(|(start, _)| std::cmp::Reverse(*start));
        let mut end = duration_ms;
        for (start, stop) in spans {
            if stop < end - OUTRO_TOLERANCE_MS {
                break;
            }
            end = end.min(start);
        }
        end
    }

    /// The JVM `finishesTrack`: an outro span advances to the queued next item.
    fn finishes_track(&self, (_, end): (i64, i64), duration_ms: i64) -> bool {
        if duration_ms <= 0 || end < duration_ms - OUTRO_TOLERANCE_MS {
            return false;
        }
        self.nav.loop_mode != LoopMode::One && !self.live && self.prepare_next_index.is_some()
    }

    /// The JVM `loopsTrack`: Loop One restarts at an outro span, since a seek onto the MSE tail stalls
    /// paused instead of ending.
    fn loops_track(&self, (_, end): (i64, i64), duration_ms: i64) -> bool {
        if duration_ms <= 0 || end < duration_ms - OUTRO_TOLERANCE_MS || self.live {
            return false;
        }
        self.nav.loop_mode == LoopMode::One && !self.sleep_after_current
    }

    /// The JVM refreshPrepareNext: peeks the item an auto-advance plays and re-arms its preload; on a
    /// new track a failed page is cleared (retried once per track, not in a loop) and a shuffle pick
    /// may land on the queue's unloaded part.
    fn refresh_prepare_next(&mut self, cx: &mut Context<Self>) {
        self.prepare_next_index = self.nav.peek_next(false, self.index, self.queue.len());
        self.prepare_next_armed = false;
        self.next_from_page = false;
        if self.rolled_generation != Some(self.load_generation) {
            self.rolled_generation = Some(self.load_generation);
            if let Some(more) = &mut self.more {
                more.error = None;
            }
            if self.shuffle_pick_lands_unloaded() {
                self.start_load(true, cx);
            }
        }
        self.maybe_preload_next(cx);
    }

    /// Known-length shuffle: the pick lands on the unloaded part with probability unloaded /
    /// (unplayed + unloaded), and then plays from the one next page (the JVM
    /// shufflePickLandsUnloaded; later pages are under-weighted there too).
    fn shuffle_pick_lands_unloaded(&self) -> bool {
        let Some(more) = &self.more else { return false };
        let Some(total) = more.total_count else { return false };
        if !self.nav.shuffle || self.nav.has_up_next() || !more.has_more || more.loading || more.error.is_some() {
            return false;
        }
        let unloaded = total.max(0) as usize;
        let Some(unloaded) = unloaded.checked_sub(self.queue.len()).filter(|unloaded| *unloaded > 0) else {
            return false;
        };
        let unplayed = self.nav.unplayed_count(self.queue.len());
        rand::Rng::random_range(&mut rand::rng(), 0..unplayed + unloaded) < unloaded
    }

    /// The JVM maybePreloadNext: in the last 10 s of the content, playing, cue the next item on its
    /// engine, once; never for a live item or under Loop One. A YouTube item is cued with the bundle's
    /// `player/preload`, booting the session first when there is none; the local player has no
    /// second-item cue (the JVM JvmLocalPlayer's is a no-op), so it is only started.
    fn maybe_preload_next(&mut self, cx: &mut Context<Self>) {
        if self.prepare_next_armed {
            return;
        }
        let Some(next_index) = self.prepare_next_index else { return };
        if self.live || self.nav.loop_mode == LoopMode::One {
            self.prepare_next_armed = true;
            return;
        }
        let playing = PlaybackState::Prepared { play_when_ready: true, buffering: false, ended: false };
        if self.active.is_none() || self.state != playing {
            return;
        }
        let duration = self.duration_ms;
        if duration <= 0 {
            return;
        }
        if self.position_ms > duration || self.content_end_ms(duration) - self.position_ms > PRELOAD_LEAD_MS {
            return;
        }
        let Some(next) = self.queue.get(next_index) else { return };
        match next.source.clone() {
            Source::YouTube(video_id) => {
                if self.youtube_state().ready_to_load() {
                    self.command(cx, move |engine, log| async move {
                        engine.player_preload(&video_id, &log).await.map(|_| ())
                    });
                } else {
                    self.init_youtube(cx);
                }
            }
            Source::Local(_) => self.start_local(),
        }
        self.prepare_next_armed = true;
    }

    /// Takes the pending start position.
    fn load_request(&mut self, video_id: &str) -> LoadRequest {
        let mut request = LoadRequest::audio(video_id);
        request.start_position_ms = std::mem::take(&mut self.start_position_ms);
        request.viewport = self.viewport;
        request.play_when_ready = self.play_when_ready;
        request.speed = self.effects.speed;
        request.pitch = self.effects.pitch;
        if self.video {
            request.quality = Some(self.quality_preferred.clone());
            request.audio_only = false;
        }
        request
    }

    fn load_current(&mut self, cx: &mut Context<Self>) {
        let Some(item) = self.current().cloned() else { return };
        self.load_generation += 1;
        self.position_ms = self.start_position_ms.max(0);
        self.buffered_ms = 0;
        self.duration_ms = item.duration_ms.unwrap_or(0);
        self.live = false;
        self.seekable = true;
        self.refresh_prepare_next(cx);
        cx.notify();
        match item.source {
            Source::YouTube(video_id) => {
                let bound = self.active == Some(Active::YouTube);
                if self.active == Some(Active::Local) {
                    self.send_local(LocalCommand::Stop);
                }
                self.caption_lines.clear();
                self.caption_tracks.clear();
                if !bound {
                    self.reset_unshown_error(cx);
                    // A bound engine keeps its state until it reports; binding one shows the state it holds.
                    self.state = self.youtube_state();
                    self.youtube_error = None;
                }
                self.active = Some(Active::YouTube);
                self.bind_presenter();
                // base.js pushes the new video's gain once it picks a format; until then play at the user's volume.
                if self.loaded_video.as_deref() != Some(video_id.as_str()) && self.gain != 1.0 {
                    self.gain = 1.0;
                    self.apply_volume();
                }
                self.loading_video = Some(video_id.clone());
                self.request_load(video_id, cx);
            }
            Source::Local(path) => {
                self.prepare_job = None;
                if self.active == Some(Active::YouTube) {
                    if let PlaybackState::Error(failure) = &self.state {
                        self.youtube_error = Some(failure.clone());
                    }
                    self.loaded_video = None;
                    self.command(cx, |engine, log| async move { engine.player_stop(&log).await });
                }
                self.active = Some(Active::Local);
                self.start_local();
                if self.local.is_none() {
                    self.state = PlaybackState::Error(PlaybackFailure::code("R90007"));
                    return;
                }
                self.loading_video = None;
                self.caption_lines.clear();
                self.caption_tracks.clear();
                self.caption_selected = None;
                self.local_gain = item.loudness_gain_db.map_or(1.0, |db| 10f64.powf(db.min(0.0) / 20.0) as f32);
                self.apply_volume();
                self.local_generation += 1;
                self.bind_presenter();
                let captions = self.local_caption_prefs();
                let start_ms = std::mem::take(&mut self.start_position_ms);
                let play_when_ready = self.play_when_ready;
                self.send_local(LocalCommand::Load {
                    path,
                    video: self.local_video(),
                    captions,
                    start_ms,
                    play_when_ready,
                });
                self.state = PlaybackState::Prepared { play_when_ready, buffering: true, ended: false };
                self.poll_local(cx);
            }
        }
    }

    /// The JVM PlayerImpl.resetUnshownError for the YouTube engine about to be bound: a broken
    /// session it failed in unbound (a warm-up boot) is reset rather than shown.
    fn reset_unshown_error(&mut self, cx: &mut Context<Self>) {
        let PlaybackState::Error(failure) = self.youtube_state() else { return };
        if !failure.retry || !failure.need_reset {
            return;
        }
        self.yt_reset_on_next = false;
        self.reset_youtube(cx);
    }

    /// The JVM PlayerImpl.requestLoad for the bound YouTube engine: loads at once when it is ready to
    /// load and no reset is due, else once [`Self::try_make_player_ready`] has made it ready.
    fn request_load(&mut self, video_id: String, cx: &mut Context<Self>) {
        self.prepare_job = None;
        if self.state.ready_to_load() && !self.yt_reset_on_next {
            self.load_on(video_id, cx);
            return;
        }
        self.prepare_job = Some(video_id);
        self.try_make_player_ready(cx);
    }

    /// The JVM PlayerImpl.tryMakePlayerReady: resets when a refresh is due or the session is broken,
    /// boots a cold engine; the session's boot then runs [`Self::prepare_job`] or drops it on failure.
    fn try_make_player_ready(&mut self, cx: &mut Context<Self>) {
        if self.yt_reset_on_next {
            self.yt_reset_on_next = false;
            self.reset_youtube(cx);
        }
        match &self.state {
            state if state.ready_to_load() => {
                if let Some(video_id) = self.prepare_job.take() {
                    self.load_on(video_id, cx);
                }
            }
            PlaybackState::Uninitialized { is_initializing } => {
                if !is_initializing {
                    self.init_youtube(cx);
                }
            }
            PlaybackState::Error(_) => self.reset_youtube(cx),
            _ => {}
        }
    }

    /// The JVM PlayerImpl.loadOn + MsePlayer.load: sends the load, refused while the engine is not
    /// ready to load.
    fn load_on(&mut self, video_id: String, cx: &mut Context<Self>) {
        if !self.state.ready_to_load() {
            cx.global::<AppGraph>()
                .log
                .fork("playback")
                .warn(&format!("load({video_id}) while {:?}", self.state), None);
            return;
        }
        self.loading_video = Some(video_id.clone());
        let request = self.load_request(&video_id);
        self.load_command(cx, video_id.clone(), move |engine, log| async move {
            engine.player_load(&request, &log).await.map(|_| ())
        });
    }

    /// The JVM MsePlayer.init: boots a session unless one is up or booting.
    fn init_youtube(&mut self, cx: &mut Context<Self>) {
        if self.session.is_some() || self.booting {
            return;
        }
        self.boot(false, cx);
        self.set_youtube_initializing();
    }

    /// The JVM MsePlayer.reset: closes the session and boots a fresh one.
    fn reset_youtube(&mut self, cx: &mut Context<Self>) {
        self.drop_session();
        self.boot(true, cx);
        self.set_youtube_initializing();
    }

    /// The JVM MsePlayer.startSession's `Uninitialized(isInitializing = true)`.
    fn set_youtube_initializing(&mut self) {
        if self.active == Some(Active::YouTube) {
            self.state = PlaybackState::Uninitialized { is_initializing: true };
        } else {
            self.youtube_error = None;
        }
    }

    fn start_local(&mut self) {
        if self.local.is_none() {
            self.local = LocalEngine::start();
            self.apply_volume();
            self.send_local(LocalCommand::Effects(self.effects));
        }
    }

    /// Follows the local player's status while it is the active engine.
    fn poll_local(&mut self, cx: &mut Context<Self>) {
        let generation = self.local_generation;
        let executor = cx.background_executor().clone();
        self._local_poll = Some(cx.spawn(async move |this, cx| {
            loop {
                executor.timer(LOCAL_POLL).await;
                let keep = this.update(cx, |playback, cx| playback.apply_local_status(generation, cx));
                if !matches!(keep, Ok(true)) {
                    return;
                }
            }
        }));
    }

    /// `false` stops the poll.
    fn apply_local_status(&mut self, generation: u64, cx: &mut Context<Self>) -> bool {
        if self.active != Some(Active::Local) || generation != self.local_generation {
            return false;
        }
        let Some(local) = &self.local else { return false };
        let status = local.status.lock().unwrap().clone();
        // The player thread has not opened this load yet: what it reports is the previous file's.
        if status.generation != self.local_generation {
            return true;
        }
        self.position_ms = status.position_ms;
        self.caption_tracks = status.caption_tracks;
        self.caption_selected = status.caption_selected;
        self.caption_lines = status.caption_lines;
        if status.duration_ms > 0 {
            self.duration_ms = status.duration_ms;
        }
        if let Some(message) = status.error {
            let log = cx.global::<AppGraph>().log.fork("playback");
            log.warn(&format!("R90007 local file failed: {message}"), None);
            let missing =
                matches!(self.current().map(|item| &item.source), Some(Source::Local(path)) if !path.exists());
            self.state = PlaybackState::Error(if missing {
                PlaybackFailure { code: FILE_MISSING.to_owned(), user_message: None, retry: false, need_reset: false }
            } else {
                PlaybackFailure::code("R90007")
            });
            cx.notify();
            return false;
        }
        if status.ended {
            // An engine stays ended until the next load; only entering Ended ends a track.
            if !self.state.is_ended() {
                self.state = PlaybackState::Prepared { play_when_ready: false, buffering: false, ended: true };
                self.track_ended(cx);
            }
            cx.notify();
            return self.active == Some(Active::Local) && generation == self.local_generation;
        }
        if let PlaybackState::Prepared { buffering, .. } = &mut self.state {
            *buffering = false;
        }
        self.check_skip(cx);
        self.maybe_preload_next(cx);
        cx.notify();
        true
    }

    /// A player command whose failure is only logged, as the JVM MsePlayer's command loop does.
    fn command<F, Fut>(&mut self, cx: &mut Context<Self>, run: F)
    where
        F: FnOnce(Arc<DynamicEngine>, ym_common::log::Logger) -> Fut + Send + 'static,
        Fut: Future<Output = Result<(), EngineError>> + Send + 'static,
    {
        self.queue_command(cx, None, run);
    }

    /// A load: its failure fails the item, and once the player takes it the load must show progress
    /// or details within [`LOAD_TIMEOUT`], as the JVM MsePlayer.load's does.
    fn load_command<F, Fut>(&mut self, cx: &mut Context<Self>, video_id: String, run: F)
    where
        F: FnOnce(Arc<DynamicEngine>, ym_common::log::Logger) -> Fut + Send + 'static,
        Fut: Future<Output = Result<(), EngineError>> + Send + 'static,
    {
        self.queue_command(cx, Some(video_id), run);
    }

    /// `load` names the video of a load command; None for any other command.
    fn queue_command<F, Fut>(&mut self, cx: &mut Context<Self>, load: Option<String>, run: F)
    where
        F: FnOnce(Arc<DynamicEngine>, ym_common::log::Logger) -> Fut + Send + 'static,
        Fut: Future<Output = Result<(), EngineError>> + Send + 'static,
    {
        cx.global::<AppGraph>().youtube.check_update();
        let Some(commands) = &self.commands else { return };
        let log = cx.global::<AppGraph>().log.fork("playback");
        let (result, done) = futures::channel::oneshot::channel();
        let job_log = log.clone();
        let job: PlayerJob = Box::new(move |engine| {
            Box::pin(async move {
                let _ = result.send(run(engine, job_log).await);
            })
        });
        if commands.unbounded_send(job).is_err() {
            return;
        }
        let issued_on = self.session.as_ref().map(Arc::downgrade).unwrap_or_default();
        cx.spawn(async move |this, cx| {
            let result = done.await;
            if let (Ok(Ok(())), Some(video_id)) = (&result, &load) {
                let video_id = video_id.clone();
                this.update(cx, |playback, cx| {
                    if playback.holds_session(&issued_on) {
                        playback.arm_load_timeout(video_id, cx);
                    }
                })
                .ok();
                return;
            }
            if let Ok(Err(error)) = result {
                if load.is_none() {
                    log.warn("player command failed", Some(&error));
                    return;
                }
                this.update(cx, |playback, cx| {
                    // A closed session's load ends with it, as the JVM MsePlayer's cancelled commands do.
                    if !playback.holds_session(&issued_on) {
                        return;
                    }
                    log.capture_error("player load failed", &error);
                    let failure = PlaybackFailure::engine(&error);
                    if playback.active == Some(Active::YouTube) {
                        playback.state = PlaybackState::Error(failure);
                        cx.notify();
                    } else {
                        playback.youtube_error = Some(failure);
                    }
                })
                .ok();
            }
        })
        .detach();
    }

    fn holds_session(&self, session: &std::sync::Weak<YoutubeSession>) -> bool {
        self.session.as_ref().is_some_and(|held| std::ptr::eq(Arc::as_ptr(held), session.as_ptr()))
    }

    /// Boots the YouTube session shortly after launch, so the first play does not wait for the whole
    /// player boot (the JVM PlayerImpl.init's prepareYoutubeEngine after 3 s).
    pub fn warm_up_later(cx: &mut Context<Self>) {
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            executor.timer(WARM_UP_AFTER).await;
            this.update(cx, |playback, cx| playback.init_youtube(cx)).ok();
        })
        .detach();
    }

    /// The next YouTube load starts a new session, under the current sign-in (the JVM
    /// PlayerImpl.refreshOnNext, called after a sign-in or sign-out).
    pub fn refresh_on_next(&mut self) {
        self.yt_reset_on_next = true;
    }

    /// Lets go of the session; `boot` brings up the next.
    fn drop_session(&mut self) {
        self.session = None;
        self.commands = None;
        self.loaded_video = None;
        self.load_timeout_video = None;
        self._load_timeout = None;
        self._status = None;
        self._catalog = None;
        if let Some((handle, _)) = self.caption_stream.take() {
            handle.abort();
        }
    }

    /// A session that can no longer play: the item fails with `needReset`, so the next load starts a
    /// new session.
    fn fail_session(&mut self, code: &str, cx: &mut Context<Self>) {
        self.command(cx, |engine, log| async move { engine.player_stop(&log).await });
        self.loading_video = None;
        self.load_timeout_video = None;
        self.position_ms = 0;
        self.duration_ms = 0;
        self.buffered_ms = 0;
        self.state = PlaybackState::Error(PlaybackFailure { need_reset: true, ..PlaybackFailure::code(code) });
        cx.notify();
    }

    fn arm_load_timeout(&mut self, video_id: String, cx: &mut Context<Self>) {
        if self.loading_video.as_deref() != Some(video_id.as_str()) {
            return;
        }
        self.load_timeout_video = Some(video_id.clone());
        let executor = cx.background_executor().clone();
        self._load_timeout = Some(cx.spawn(async move |this, cx| {
            executor.timer(LOAD_TIMEOUT).await;
            this.update(cx, |playback, cx| {
                if playback.active != Some(Active::YouTube)
                    || playback.load_timeout_video.as_deref() != Some(video_id.as_str())
                {
                    return;
                }
                cx.global::<AppGraph>()
                    .log
                    .fork("playback")
                    .warn(&format!("{LOAD_STALLED} load stalled: no progress or details for {video_id}"), None);
                playback.fail_session(LOAD_STALLED, cx);
            })
            .ok();
        }));
    }

    /// Boots the session; `reset` first closes the one the app holds, so the boot is a fresh one, and
    /// supersedes a boot in flight (the JVM MsePlayer.reset tears down a session still starting).
    fn boot(&mut self, reset: bool, cx: &mut Context<Self>) {
        if self.booting && !reset {
            return;
        }
        self.booting = true;
        self.boot_generation += 1;
        let generation = self.boot_generation;
        let sessions = cx.global::<AppGraph>().youtube.clone();
        let log = cx.global::<AppGraph>().log.fork("playback");
        let boot = on_core(cx, async move {
            if reset {
                sessions.reset().await;
            }
            sessions.get().await
        });
        cx.spawn(async move |this, cx| {
            let result = boot.await;
            this.update(cx, |playback, cx| {
                if playback.boot_generation != generation {
                    return;
                }
                playback.booting = false;
                match result {
                    Some(Ok(session)) => {
                        playback.commands = Some(start_commands(&session, cx));
                        playback.session = Some(session);
                        playback.apply_volume();
                        playback.apply_youtube_tone();
                        playback.apply_youtube_video_hidden(cx);
                        playback.bind_presenter();
                        playback.listen(cx);
                        if playback.active == Some(Active::YouTube)
                            && matches!(playback.state, PlaybackState::Uninitialized { .. })
                        {
                            playback.state = PlaybackState::Idle;
                        }
                        if let Some(video_id) = playback.prepare_job.take()
                            && playback.active == Some(Active::YouTube)
                        {
                            playback.load_on(video_id, cx);
                        }
                    }
                    Some(Err(error)) => {
                        log.capture_error("the player session did not boot", &error);
                        playback.prepare_job = None;
                        let failure = PlaybackFailure::engine(&error);
                        if playback.active == Some(Active::YouTube) {
                            playback.state = PlaybackState::Error(failure);
                        } else {
                            playback.youtube_error = Some(failure);
                        }
                    }
                    None => {
                        if playback.active == Some(Active::YouTube)
                            && matches!(playback.state, PlaybackState::Uninitialized { .. })
                        {
                            playback.state = PlaybackState::Uninitialized { is_initializing: false };
                        }
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn listen(&mut self, cx: &mut Context<Self>) {
        let Some(session) = self.session.clone() else { return };
        let (sender, mut receiver) = mpsc::unbounded::<Value>();
        let engine = session.engine.clone();
        let stream = on_core(cx, async move {
            engine
                .listen_player("listen/player/status", move |value| {
                    let _ = sender.unbounded_send(value);
                })
                .await
        });
        cx.background_executor().spawn(stream).detach();
        self._status = Some(cx.spawn(async move |this, cx| {
            while let Some(value) = receiver.next().await {
                if this.update(cx, |playback, cx| playback.apply_status(&value, cx)).is_err() {
                    return;
                }
            }
        }));
        let (sender, mut receiver) = mpsc::unbounded::<Value>();
        let (engine, log) = (session.engine.clone(), cx.global::<AppGraph>().log.fork("catalog"));
        cx.global::<AppGraph>().tokio.spawn(async move {
            if let Err(error) = engine
                .listen_player("listen/player/catalog", move |value| {
                    let _ = sender.unbounded_send(value);
                })
                .await
            {
                log.warn("catalog stream failed", Some(&error));
            }
        });
        self._catalog = Some(cx.spawn(async move |this, cx| {
            while let Some(value) = receiver.next().await {
                if this.update(cx, |playback, cx| playback.apply_catalog(&value, cx)).is_err() {
                    return;
                }
            }
        }));
        if let Some((width, height)) = self.viewport {
            self.command(cx, move |engine, log| async move { engine.player_set_viewport(width, height, &log).await });
        }
        if self.captions {
            let selected = self.caption_selected.clone();
            self.command(cx, move |engine, log| async move {
                engine.player_set_caption(selected.as_deref(), true, &log).await
            });
        }
        self.sync_caption_stream(cx);
    }

    fn apply_status(&mut self, status: &Value, cx: &mut Context<Self>) {
        if self.active != Some(Active::YouTube) {
            return;
        }
        if status.get("poRejected").and_then(Value::as_bool) == Some(true) {
            // googlevideo rejected this session's PO token: only a fresh session re-attests.
            cx.global::<AppGraph>()
                .log
                .fork("playback")
                .warn("googlevideo rejected the PO token (StreamProtectionStatus=3)", None);
            self.fail_session(PO_TOKEN_REJECTED, cx);
            return;
        }
        let current = self.current().and_then(QueueItem::video_id).map(str::to_owned);
        if let Some(progress) = status.get("progress") {
            let video_id = progress.get("videoId").and_then(Value::as_str).map(str::to_owned);
            // A failed video has no play-head until it reports a new state, but its load is no longer stalled.
            let for_current = video_id.is_some() && video_id == current;
            if for_current {
                if self.load_timeout_video == video_id {
                    self.load_timeout_video = None;
                }
                self.loading_video = None;
                self.loaded_video = video_id;
            }
            if for_current && !matches!(self.state, PlaybackState::Error(_)) {
                self.position_ms = field_i64(progress, &["progress", "positionMs"]);
                self.buffered_ms = field_i64(progress, &["bufferedMs"]);
                let duration = field_i64(progress, &["duration", "durationMs"]);
                if duration > 0 {
                    self.duration_ms = duration;
                }
                self.live = progress.get("live").and_then(Value::as_bool).unwrap_or(self.live);
                self.seekable = progress.get("seekable").and_then(Value::as_bool).unwrap_or(duration > 0);
            }
        }
        if let Some(state) = status.get("state") {
            if let Some(live) = state.get("live").and_then(Value::as_bool) {
                self.live = live;
            }
            let play_when_ready = state.get("playWhenReady").and_then(Value::as_bool).unwrap_or(false);
            let was_ended = self.state.is_ended();
            self.state = match state.get("newState").and_then(Value::as_str) {
                Some("idle") => PlaybackState::Idle,
                Some("buffering") => PlaybackState::Prepared { play_when_ready, buffering: true, ended: false },
                Some("ready") => PlaybackState::Prepared { play_when_ready, buffering: false, ended: false },
                Some("ended") => PlaybackState::Prepared { play_when_ready: false, buffering: false, ended: true },
                Some("error") => PlaybackState::Error(PlaybackFailure {
                    code: state.get("code").and_then(Value::as_str).unwrap_or("R90003").to_owned(),
                    user_message: state
                        .get("userMessage")
                        .and_then(Value::as_str)
                        .filter(|message| !message.trim().is_empty())
                        .map(str::to_owned),
                    retry: state.get("canRetry").and_then(Value::as_bool).unwrap_or(true),
                    need_reset: state.get("needReset").and_then(Value::as_bool).unwrap_or(false),
                }),
                _ => self.state.clone(),
            };
            if matches!(self.state, PlaybackState::Error(_)) {
                self.load_timeout_video = None;
            }
            // An engine stays ended until the next load reaches it; only entering Ended ends a track.
            if self.state.is_ended() && !was_ended {
                self.track_ended(cx);
            }
            // A failed video has no play-head; its last report (an unplayable recording's length)
            // must not stay up, and later ones are ignored until the next load.
            if matches!(self.state, PlaybackState::Error(_)) {
                self.position_ms = 0;
                self.duration_ms = 0;
                self.buffered_ms = 0;
                self.live = false;
            }
        }
        if let Some(details) = status.get("details") {
            let text = |name: &str| details.get(name).and_then(Value::as_str).map(str::to_owned);
            if let Some(video_id) = text("videoId") {
                if self.load_timeout_video.as_deref() == Some(video_id.as_str()) {
                    self.load_timeout_video = None;
                }
                self.details = Some(MediaDetails {
                    video_id,
                    channel_title: text("channelTitle"),
                    channel_url: text("channelUrl"),
                });
            }
        }
        self.check_skip(cx);
        if !self.state.is_ended() {
            self.maybe_preload_next(cx);
        }
        if let Some(watch_next) = status.get("watchNext") {
            self.harvest_watch_next(watch_next, cx);
        }
        if let Some(gain) = status.get("gain").and_then(|gain| gain.get("value")).and_then(Value::as_f64) {
            self.gain = (gain as f32).clamp(0.0, 1.0);
            self.apply_volume();
        }
        cx.notify();
    }

    /// The playing page's related videos, handed verbatim to the engine: Recommended is built from
    /// them. A failed hand-off is logged and never disturbs playback.
    fn harvest_watch_next(&self, watch_next: &Value, cx: &App) {
        let (Some(video_id), Some(contents)) = (
            watch_next.get("videoId").and_then(Value::as_str).map(str::to_owned),
            watch_next.get("contents").and_then(Value::as_str).map(str::to_owned),
        ) else {
            return;
        };
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("watch-next-harvest"));
        graph.tokio.spawn(async move {
            if let Err(error) = engine.harvest_watch_next(&video_id, &contents, &log).await {
                log.warn("watch-next harvest hand-off failed", Some(&error));
            }
        });
    }

    /// The JVM PlayerImpl.attachSource: a replaced source's page in flight is dropped.
    fn attach_source(&mut self, source: Option<NextQueueSource>) {
        self._source_page = None;
        self.more = source.as_ref().map(|source| QueueMore {
            total_count: source.total_count(),
            has_more: true,
            loading: false,
            error: None,
            resume_token: source.resume_token(),
        });
        self.source = source;
    }

    /// The JVM publishQueue: `refresh_next` for a change that keeps the current item (a load
    /// refreshes on its own).
    fn publish_queue(&mut self, refresh_next: bool, cx: &mut Context<Self>) {
        if refresh_next {
            self.refresh_prepare_next(cx);
        }
        self.maybe_load_ahead(cx);
    }

    /// Loads the queue's next page now: Load more, or Retry after a failed page.
    pub fn load_more(&mut self, cx: &mut Context<Self>) {
        if let Some(more) = &mut self.more {
            more.error = None;
        }
        self.start_load(false, cx);
    }

    /// Any mode: the current item sits within the queue's last [`LOAD_AHEAD`] items.
    fn maybe_load_ahead(&mut self, cx: &mut Context<Self>) {
        let Some(more) = &self.more else { return };
        if !more.has_more || more.loading || more.error.is_some() || self.queue.is_empty() {
            return;
        }
        if self.queue.len() - 1 - self.index.min(self.queue.len() - 1) < LOAD_AHEAD {
            self.start_load(false, cx);
        }
    }

    /// `pick_next`: a shuffle pick that landed on the unloaded part plays from this page.
    fn start_load(&mut self, pick_next: bool, cx: &mut Context<Self>) {
        let (Some(source), Some(more)) = (&self.source, &mut self.more) else { return };
        if !more.has_more || more.loading {
            return;
        }
        more.loading = true;
        more.error = None;
        cx.notify();
        let Some(fetch) = source.fetch(cx.global::<AppGraph>().engine()) else {
            self.add_page(QueueSourcePage { items: Vec::new(), has_more: false }, pick_next, cx);
            return;
        };
        let fetch = on_core(cx, fetch);
        self._source_page = Some(cx.spawn(async move |this, cx| {
            let result = fetch.await;
            this.update(cx, |playback, cx| {
                let code = match result {
                    Some(Ok(page)) => {
                        if let Some(source) = &mut playback.source {
                            let page = source.accept(page);
                            playback.add_page(page, pick_next, cx);
                        }
                        return;
                    }
                    Some(Err(error)) => error_code(&error),
                    None => {
                        let log = &cx.global::<AppGraph>().log;
                        log.warn(&format!("{QUEUE_PAGE_UNCODED} queue page ended without a result"), None);
                        QUEUE_PAGE_UNCODED.to_owned()
                    }
                };
                if let Some(more) = &mut playback.more {
                    more.loading = false;
                    more.error = Some(code);
                }
                cx.notify();
            })
            .ok();
        }));
    }

    fn add_page(&mut self, page: QueueSourcePage, pick_next: bool, cx: &mut Context<Self>) {
        let total_count = self.source.as_ref().and_then(NextQueueSource::total_count);
        let resume_token = self.source.as_ref().and_then(NextQueueSource::resume_token);
        let (first_new, page_len) = (self.queue.len(), page.items.len());
        if page_len > 0 {
            self.queue.extend(page.items);
            self.queue_revision += 1;
        }
        let loaded = self.queue.len() as i32;
        if let Some(more) = &mut self.more {
            more.loading = false;
            more.has_more = page.has_more;
            more.total_count = if page.has_more { total_count.or(more.total_count) } else { Some(loaded) };
            more.resume_token = resume_token.filter(|_| page.has_more);
        }
        if pick_next && page_len > 0 && self.nav.shuffle {
            self.prepare_next_index = Some(first_new + rand::Rng::random_range(&mut rand::rng(), 0..page_len));
            self.prepare_next_armed = false;
            self.next_from_page = true;
            self.publish_queue(false, cx);
            self.maybe_preload_next(cx);
        } else {
            self.publish_queue(true, cx);
        }
        cx.notify();
    }
}

fn queue_source_log(cx: &App) -> ym_common::log::Logger {
    cx.global::<AppGraph>().log.fork("queue-source")
}

fn field_i64(object: &Value, names: &[&str]) -> i64 {
    names.iter().find_map(|name| object.get(*name).and_then(Value::as_i64)).unwrap_or(0)
}

impl Playback {
    /// The loaded share of a streamed item, for the seek bars' loading band.
    pub fn buffered_fraction(&self) -> f32 {
        if self.duration_ms > 0 { (self.buffered_ms as f32 / self.duration_ms as f32).clamp(0., 1.) } else { 0. }
    }
}

/// A seek bar's loaded band: the accent at the JVM SlimSeekBar's 35% under the slider's track,
/// `fraction` of the way along.
pub fn buffered_band(fraction: f32, accent: Hsla) -> Div {
    div()
        .absolute()
        .inset_0()
        .flex()
        .items_center()
        .child(div().h(px(6.)).rounded_full().w(relative(fraction)).bg(accent.opacity(0.35)))
}
