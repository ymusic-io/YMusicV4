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
use crate::local_engine::{LocalCommand, LocalEngine, LocalLevelTap};
use crate::queue_nav::{LoopMode, QueueNav, moved_index};
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

#[derive(Debug, Clone, PartialEq)]
pub enum PlayState {
    Idle,
    Buffering,
    Playing,
    Paused,
    Ended,
    Failed(PlaybackFailure),
}

/// Why playback stopped, as the user sees it: a known category, the text the source gave (YouTube's
/// own words for a playability verdict), or the code to report.
#[derive(Debug, Clone, PartialEq)]
pub struct PlaybackFailure {
    pub code: String,
    pub user_message: Option<String>,
    /// Loading the item again may work.
    pub retry: bool,
}

const SIGN_IN_STATUSES: [&str; 3] = ["LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED"];
const FILE_MISSING: &str = "file_missing";
const PO_TOKEN_REJECTED: &str = "E60065";

impl PlaybackFailure {
    pub fn code(code: &str) -> Self {
        Self { code: code.to_owned(), user_message: None, retry: true }
    }

    fn engine(error: &EngineError) -> Self {
        let code = if matches!(error.kind, ym_engine::ErrorKind::Network) { "network" } else { error.code.as_str() };
        let user_message = error.details.as_ref().and_then(|details| details.user_message.clone());
        Self { code: code.to_owned(), user_message: user_message.filter(|message| !message.is_empty()), retry: true }
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
    /// A local file's play-history key: its YouTube id when tagged, else size and length.
    pub history_code: Option<String>,
    /// A local file downloaded from YouTube (its `composer` tag).
    pub youtube_vid: Option<String>,
    /// A local file's dB to its loudness target.
    pub loudness_gain_db: Option<f64>,
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
            history_code: None,
            youtube_vid: None,
            loudness_gain_db: None,
        }
    }

    pub fn from_local(item: &ym_store::MediaItem) -> Self {
        Self {
            source: Source::Local(PathBuf::from(&item.uri)),
            title: item.title.clone(),
            artist: item.artist.clone(),
            duration_ms: item.duration_ms,
            duration_label: item.duration_ms.map(crate::mini_player::clock),
            thumbs: crate::local_art::queue_thumbs(item),
            history_code: Some(
                item.youtube_vid
                    .clone()
                    .unwrap_or_else(|| format!("@{}:{}", item.file_size, item.duration_ms.unwrap_or(0) / 1000)),
            ),
            youtube_vid: item.youtube_vid.clone(),
            loudness_gain_db: item.loudness_gain_db,
        }
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

pub struct Playback {
    session: Option<Arc<YoutubeSession>>,
    booting: bool,
    /// What to load once the YouTube session is up.
    pending: Option<String>,
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
    /// The span last skipped, so a late position report doesn't skip it twice.
    skipped: Option<(String, i64)>,
    queue_continuation: Option<String>,
    loading_queue: bool,
    pub state: PlayState,
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
    gain: f32,
    /// The local item's attenuate-only loudness gain.
    local_gain: f32,
    pub volume: f32,
    pub effects: AudioEffects,
    pub nav: QueueNav,
    /// The loaded video's channel, once the player reports it.
    details: Option<MediaDetails>,
    /// The next load starts paused, once.
    start_paused: bool,
    /// The next YouTube load stops the player first, in the same command.
    stop_before_load: bool,
    sleep_deadline: Option<Instant>,
    pub sleep_after_current: bool,
    pub captions: bool,
    pub caption_tracks: Vec<CaptionTrack>,
    /// The track the bundle shows (a vssId or a language code), from the catalog stream.
    pub caption_selected: Option<String>,
    /// The cues on screen now, oldest first: (cue id, text with hard breaks).
    pub caption_lines: Vec<(i64, String)>,
    caption_stream: Option<(tokio::task::JoinHandle<()>, Task<()>)>,
    _catalog: Option<Task<()>>,
    _sleep: Option<Task<()>>,
    /// Video on: loads request pictures and the presenter shows them; off: audio only.
    pub video: bool,
    /// The video's qualities (token, label) from the catalog, best first; empty until it reports.
    pub quality_options: Vec<(String, String)>,
    /// The quality playing now, as the catalog reports it.
    pub quality_actual: Option<String>,
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
            booting: false,
            pending: None,
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
            queue_continuation: None,
            loading_queue: false,
            state: PlayState::Idle,
            position_ms: 0,
            duration_ms: 0,
            buffered_ms: 0,
            live: false,
            seekable: true,
            loading_video: None,
            quality_options: Vec::new(),
            viewport: None,
            quality_actual: None,
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
            start_paused: false,
            stop_before_load: false,
            sleep_deadline: None,
            sleep_after_current: false,
            captions: false,
            caption_tracks: Vec::new(),
            caption_selected: None,
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

    pub fn is_playing(&self) -> bool {
        matches!(self.state, PlayState::Playing | PlayState::Buffering)
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
            (Active::YouTube, Source::YouTube(_)) => match self.quality_actual.as_deref() {
                Some(QUALITY_AUDIO_ONLY) => Some(false),
                None if self.quality_options.is_empty() => None,
                _ => Some(true),
            },
            _ => None,
        }
    }

    /// Plays a feed video. A `section` queue plays in order; without one the engine's radio for
    /// the video becomes the queue.
    pub fn play_feed(&mut self, item: YtFeedItem, section: Option<Vec<YtFeedItem>>, cx: &mut Context<Self>) {
        self.queue_continuation = None;
        match section {
            Some(items) => {
                self.index = items.iter().position(|candidate| candidate.id == item.id).unwrap_or(0);
                self.queue = items.iter().map(QueueItem::from_feed).collect();
            }
            None => {
                self.queue = vec![QueueItem::from_feed(&item)];
                self.index = 0;
                self.fetch_radio(item.id.clone(), None, cx);
            }
        }
        self.new_queue();
        self.load_current(cx);
    }

    /// Plays a linked video from `start_ms`: the link's playlist continues after it, else its radio.
    pub fn play_link(
        &mut self,
        item: YtFeedItem,
        playlist_url: Option<String>,
        start_ms: Option<i64>,
        cx: &mut Context<Self>,
    ) {
        self.queue_continuation = None;
        self.queue = vec![QueueItem::from_feed(&item)];
        self.index = 0;
        self.fetch_radio(item.id.clone(), playlist_url, cx);
        self.new_queue();
        self.start_position_ms = start_ms.unwrap_or(0).max(0);
        self.load_current(cx);
    }

    fn new_queue(&mut self) {
        self.queue_id = uuid::Uuid::new_v4().to_string();
        self.queue_revision += 1;
        self.nav.reset(self.index);
    }

    /// Resumes a saved queue as the same queue, `start_ms` into its current item.
    pub fn resume_queue(
        &mut self,
        queue_id: String,
        items: Vec<QueueItem>,
        index: usize,
        start_ms: i64,
        cx: &mut Context<Self>,
    ) {
        if items.is_empty() {
            return;
        }
        self.queue_continuation = None;
        self.index = index.min(items.len() - 1);
        self.queue = items;
        self.queue_id = queue_id;
        self.queue_revision += 1;
        self.nav.reset(self.index);
        self.start_position_ms = start_ms.max(0);
        self.load_current(cx);
    }

    /// Plays `item` right after the current one; starts it when nothing is queued.
    pub fn queue_next(&mut self, item: QueueItem, cx: &mut Context<Self>) {
        if self.queue.is_empty() {
            self.play_queue(vec![item], 0, cx);
            return;
        }
        self.queue.insert(self.index + 1, item);
        self.nav.inserted_at(self.index + 1);
        self.queue_revision += 1;
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
        cx.notify();
    }

    /// Replaces the queue with `item` and its radio.
    pub fn start_radio(&mut self, item: QueueItem, cx: &mut Context<Self>) {
        let Some(video_id) = item.video_id().map(str::to_owned) else { return };
        self.queue_continuation = None;
        self.queue = vec![item];
        self.index = 0;
        self.fetch_radio(video_id, None, cx);
        self.new_queue();
        self.load_current(cx);
    }

    /// Stops the current item's engine and loads the item again where it was, keeping play or pause:
    /// recovers a wedged player.
    pub fn reload(&mut self, cx: &mut Context<Self>) {
        if self.current().is_none() {
            return;
        }
        self.start_paused = !self.is_playing() && !matches!(self.state, PlayState::Failed(_));
        self.start_position_ms = self.position_ms.max(0);
        match self.active {
            Some(Active::Local) => self.send_local(LocalCommand::Stop),
            Some(Active::YouTube) => self.stop_before_load = true,
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
        self.load_current(cx);
    }

    /// Stops playback and empties the queue.
    pub fn clear_queue(&mut self, cx: &mut Context<Self>) {
        if self.queue.is_empty() {
            return;
        }
        self.queue.clear();
        self.queue_revision += 1;
        self.index = 0;
        self.send_local(LocalCommand::Stop);
        self.command(cx, |engine, log| async move { engine.player_stop(&log).await });
        self.state = PlayState::Idle;
        self.nav.reset(self.index);
        cx.notify();
    }

    /// Takes the item at `index` out of the queue; removing the playing one moves on.
    pub fn remove_at(&mut self, index: usize, cx: &mut Context<Self>) {
        if index >= self.queue.len() {
            return;
        }
        self.queue.remove(index);
        self.queue_revision += 1;
        if index < self.index {
            self.index -= 1;
        } else if index == self.index {
            if self.queue.is_empty() {
                self.index = 0;
                self.send_local(LocalCommand::Stop);
                self.command(cx, |engine, log| async move { engine.player_stop(&log).await });
                self.state = PlayState::Idle;
            } else {
                self.index = self.index.min(self.queue.len() - 1);
                self.load_current(cx);
            }
        }
        self.nav.reset(self.index);
        cx.notify();
    }

    /// Seeks by `delta_ms` from where playback is, within the item.
    pub fn seek_by(&mut self, delta_ms: i64, cx: &mut Context<Self>) {
        let target = (self.position_ms + delta_ms).max(0);
        let target = if self.duration_ms > 0 { target.min(self.duration_ms) } else { target };
        self.seek(target, cx);
    }

    /// YouTube captions on or off; the bundle keeps the choice across videos.
    pub fn set_captions(&mut self, visible: bool, cx: &mut Context<Self>) {
        self.captions = visible;
        self.command(cx, move |engine, log| async move { engine.player_set_caption(None, visible, &log).await });
        self.sync_caption_stream(cx);
        cx.notify();
    }

    /// Shows captions from the track with this vssId (or language code).
    pub fn select_caption(&mut self, id: String, cx: &mut Context<Self>) {
        self.captions = true;
        self.caption_selected = Some(id.clone());
        self.command(cx, move |engine, log| async move { engine.player_set_caption(Some(&id), true, &log).await });
        self.sync_caption_stream(cx);
        cx.notify();
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
                    if playback.captions {
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
        self.quality_options = catalog
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
        self.quality_actual = catalog.get("quality").and_then(Value::as_str).map(str::to_owned);
        cx.notify();
    }

    /// Plays `items` in order from `index`.
    pub fn play_queue(&mut self, items: Vec<QueueItem>, index: usize, cx: &mut Context<Self>) {
        self.queue_continuation = None;
        self.index = index.min(items.len().saturating_sub(1));
        self.queue = items;
        self.new_queue();
        self.load_current(cx);
    }

    /// Nothing queued: a no-op, as the YouTube engine keeps a stopped video that play would revive unseen.
    pub fn toggle(&mut self, cx: &mut Context<Self>) {
        if self.current().is_none() {
            return;
        }
        let playing = self.is_playing();
        match self.active {
            Some(Active::Local) => {
                self.send_local(if playing { LocalCommand::Pause } else { LocalCommand::Play });
                self.state = if playing { PlayState::Paused } else { PlayState::Playing };
                cx.notify();
            }
            _ => self.command(cx, move |engine, log| async move {
                if playing { engine.player_pause(&log).await } else { engine.player_play(&log).await }
            }),
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
        self.nav.peek_next(true, self.index, self.queue.len()).is_some() || self.queue_continuation.is_some()
    }

    /// A user skip.
    pub fn next(&mut self, cx: &mut Context<Self>) {
        if let Some(next) = self.nav.peek_next(true, self.index, self.queue.len()) {
            self.nav.commit(next, self.queue.len());
            self.index = next;
            self.load_current(cx);
        }
        self.extend_queue_if_near_end(cx);
    }

    /// Restarts the current item when it has played more than ~3 s.
    pub fn previous(&mut self, cx: &mut Context<Self>) {
        if self.position_ms > 3_000 {
            self.seek(0, cx);
            return;
        }
        match self.nav.previous(self.index, self.queue.len()) {
            Some(previous) => {
                self.index = previous;
                self.load_current(cx);
            }
            None => self.seek(0, cx),
        }
    }

    /// The current item finished on its own.
    fn track_ended(&mut self, cx: &mut Context<Self>) {
        if self.sleep_after_current {
            self.sleep_after_current = false;
            self.state = PlayState::Ended;
            return;
        }
        match self.nav.peek_next(false, self.index, self.queue.len()) {
            Some(next) if next == self.index => {
                self.seek(0, cx);
                self.resume(cx);
            }
            Some(next) => {
                self.nav.commit(next, self.queue.len());
                self.index = next;
                self.load_current(cx);
            }
            None => self.state = PlayState::Ended,
        }
        self.extend_queue_if_near_end(cx);
    }

    fn resume(&mut self, cx: &mut Context<Self>) {
        match self.active {
            Some(Active::Local) => self.send_local(LocalCommand::Play),
            _ => self.command(cx, |engine, log| async move { engine.player_play(&log).await }),
        }
    }

    fn pause(&mut self, cx: &mut Context<Self>) {
        match self.active {
            Some(Active::Local) => {
                self.send_local(LocalCommand::Pause);
                self.state = PlayState::Paused;
            }
            _ => self.command(cx, |engine, log| async move { engine.player_pause(&log).await }),
        }
        cx.notify();
    }

    pub fn set_loop_mode(&mut self, mode: LoopMode, cx: &mut Context<Self>) {
        self.nav.loop_mode = mode;
        self.save_setting(LOOP_MODE_KEY, mode.name().to_owned(), cx);
        cx.notify();
    }

    pub fn set_shuffle(&mut self, shuffle: bool, cx: &mut Context<Self>) {
        self.nav.set_shuffle(shuffle, self.index);
        self.save_setting(SHUFFLE_KEY, if shuffle { "1" } else { "0" }.to_owned(), cx);
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
        if index < self.queue.len() && index != self.index {
            self.nav.commit(index, self.queue.len());
            self.index = index;
            self.load_current(cx);
            self.extend_queue_if_near_end(cx);
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
        if speed_changed {
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

    fn apply_youtube_effects(&mut self, cx: &mut Context<Self>) {
        let Some(session) = &self.session else { return };
        session.host.set_tone(&self.effects.tone);
        let (speed, pitch) = (self.effects.speed, self.effects.pitch);
        if (speed, pitch) != (1.0, 1.0) {
            self.command(cx, move |engine, log| async move { engine.player_set_speed_pitch(speed, pitch, &log).await });
        }
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

    /// Spans to skip in the item with `key`; spans under a second are ignored.
    pub fn set_skip_segments(&mut self, key: String, segments: Vec<(i64, i64)>) {
        let usable: Vec<(i64, i64)> = segments.into_iter().filter(|(start, end)| end - start >= MIN_SKIP_MS).collect();
        self.skip_segments = (!usable.is_empty()).then_some((key, usable));
    }

    /// Seeks past a span the play-head entered; a span running out the track ends it.
    fn check_skip(&mut self, cx: &mut Context<Self>) {
        if self.state != PlayState::Playing {
            return;
        }
        let Some(key) = self.current().map(QueueItem::key) else { return };
        let Some((owner, segments)) = &self.skip_segments else { return };
        if *owner != key {
            return;
        }
        let position = self.position_ms;
        let Some(&(start, end)) = segments.iter().find(|(start, end)| position >= *start && position < *end) else {
            return;
        };
        if self.skipped.as_ref() == Some(&(key.clone(), start)) {
            return;
        }
        self.skipped = Some((key, start));
        if self.duration_ms > 0 && end >= self.duration_ms - OUTRO_TOLERANCE_MS {
            self.state = PlayState::Ended;
            self.track_ended(cx);
        } else {
            self.seek(end, cx);
        }
    }

    /// Takes the pending start position.
    fn load_request(&mut self, video_id: &str) -> LoadRequest {
        let mut request = LoadRequest::audio(video_id);
        request.start_position_ms = std::mem::take(&mut self.start_position_ms);
        request.viewport = self.viewport;
        request.play_when_ready = !std::mem::take(&mut self.start_paused);
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
        self.state = PlayState::Buffering;
        self.position_ms = 0;
        self.buffered_ms = 0;
        self.duration_ms = item.duration_ms.unwrap_or(0);
        self.live = false;
        self.seekable = true;
        cx.notify();
        match item.source {
            Source::YouTube(video_id) => {
                if self.active == Some(Active::Local) {
                    self.send_local(LocalCommand::Stop);
                }
                self.caption_lines.clear();
                self.caption_tracks.clear();
                self.quality_options.clear();
                self.quality_actual = None;
                self.active = Some(Active::YouTube);
                self.bind_presenter();
                self.loading_video = Some(video_id.clone());
                if self.session.is_none() {
                    self.pending = Some(video_id);
                    self.boot(cx);
                    return;
                }
                let request = self.load_request(&video_id);
                let stop_first = std::mem::take(&mut self.stop_before_load);
                self.command(cx, move |engine, log| async move {
                    if stop_first {
                        engine.player_stop(&log).await?;
                    }
                    engine.player_load(&request, &log).await.map(|_| ())
                });
            }
            Source::Local(path) => {
                if self.active == Some(Active::YouTube) {
                    self.command(cx, |engine, log| async move { engine.player_stop(&log).await });
                }
                if self.local.is_none() {
                    self.local = LocalEngine::start();
                    self.apply_volume();
                    self.send_local(LocalCommand::Effects(self.effects));
                }
                if self.local.is_none() {
                    self.state = PlayState::Failed(PlaybackFailure::code("R90007"));
                    return;
                }
                self.active = Some(Active::Local);
                self.loading_video = None;
                self.local_gain = item.loudness_gain_db.map_or(1.0, |db| 10f64.powf(db.min(0.0) / 20.0) as f32);
                self.apply_volume();
                self.local_generation += 1;
                self.bind_presenter();
                self.send_local(LocalCommand::Load { path, video: self.local_video() });
                let start = std::mem::take(&mut self.start_position_ms);
                if start > 0 {
                    self.send_local(LocalCommand::Seek(start));
                }
                if std::mem::take(&mut self.start_paused) {
                    self.send_local(LocalCommand::Pause);
                    self.state = PlayState::Paused;
                }
                self.poll_local(cx);
            }
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
        self.position_ms = status.position_ms;
        if status.duration_ms > 0 {
            self.duration_ms = status.duration_ms;
        }
        if let Some(message) = status.error {
            let log = cx.global::<AppGraph>().log.fork("playback");
            log.warn(&format!("R90007 local file failed: {message}"), None);
            let missing =
                matches!(self.current().map(|item| &item.source), Some(Source::Local(path)) if !path.exists());
            self.state = PlayState::Failed(if missing {
                PlaybackFailure { code: FILE_MISSING.to_owned(), user_message: None, retry: false }
            } else {
                PlaybackFailure::code("R90007")
            });
            cx.notify();
            return false;
        }
        if status.ended {
            self.state = PlayState::Ended;
            self.track_ended(cx);
            cx.notify();
            return self.active == Some(Active::Local) && generation == self.local_generation;
        }
        self.state = if status.playing { PlayState::Playing } else { PlayState::Paused };
        self.check_skip(cx);
        cx.notify();
        true
    }

    fn command<F, Fut>(&mut self, cx: &mut Context<Self>, run: F)
    where
        F: FnOnce(Arc<DynamicEngine>, ym_common::log::Logger) -> Fut + Send + 'static,
        Fut: Future<Output = Result<(), EngineError>> + Send + 'static,
    {
        let Some(session) = self.session.clone() else { return };
        let log = cx.global::<AppGraph>().log.fork("playback");
        let done = on_core(cx, run(session.engine.clone(), log.clone()));
        cx.spawn(async move |this, cx| {
            if let Some(Err(error)) = done.await {
                log.capture_error("player command failed", &error);
                this.update(cx, |playback, cx| {
                    playback.state = PlayState::Failed(PlaybackFailure::engine(&error));
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    fn boot(&mut self, cx: &mut Context<Self>) {
        if self.booting {
            return;
        }
        self.booting = true;
        let sessions = cx.global::<AppGraph>().youtube.clone();
        let log = cx.global::<AppGraph>().log.fork("playback");
        let boot = on_core(cx, async move { sessions.get().await });
        cx.spawn(async move |this, cx| {
            let result = boot.await;
            this.update(cx, |playback, cx| {
                playback.booting = false;
                match result {
                    Some(Ok(session)) => {
                        playback.session = Some(session);
                        playback.apply_volume();
                        playback.apply_youtube_effects(cx);
                        playback.apply_youtube_video_hidden(cx);
                        playback.bind_presenter();
                        playback.listen(cx);
                        if let Some(video_id) = playback.pending.take()
                            && playback.active == Some(Active::YouTube)
                        {
                            playback.loading_video = Some(video_id.clone());
                            let request = playback.load_request(&video_id);
                            playback.command(cx, move |engine, log| async move {
                                engine.player_load(&request, &log).await.map(|_| ())
                            });
                        }
                    }
                    Some(Err(error)) => {
                        log.capture_error("the player session did not boot", &error);
                        playback.state = PlayState::Failed(PlaybackFailure::engine(&error));
                    }
                    None => {}
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
        let current = self.current().and_then(QueueItem::video_id).map(str::to_owned);
        if let Some(progress) = status.get("progress") {
            let video_id = progress.get("videoId").and_then(Value::as_str).map(str::to_owned);
            let failed = matches!(self.state, PlayState::Failed(_));
            if video_id.is_some() && video_id == current && !failed {
                self.loading_video = None;
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
            self.state = match state.get("newState").and_then(Value::as_str) {
                Some("buffering") => PlayState::Buffering,
                Some("ready") if play_when_ready => PlayState::Playing,
                Some("ready") => PlayState::Paused,
                Some("ended") => PlayState::Ended,
                Some("idle") if self.loading_video.is_some() => PlayState::Buffering,
                Some("idle") => PlayState::Idle,
                Some("error") => PlayState::Failed(PlaybackFailure {
                    code: state.get("code").and_then(Value::as_str).unwrap_or("R90003").to_owned(),
                    user_message: state
                        .get("userMessage")
                        .and_then(Value::as_str)
                        .filter(|message| !message.trim().is_empty())
                        .map(str::to_owned),
                    retry: state.get("canRetry").and_then(Value::as_bool).unwrap_or(true),
                }),
                _ => self.state.clone(),
            };
            if self.state == PlayState::Ended {
                self.track_ended(cx);
            }
            // A failed video has no play-head; its last report (an unplayable recording's length)
            // must not stay up, and later ones are ignored until the next load.
            if matches!(self.state, PlayState::Failed(_)) {
                self.position_ms = 0;
                self.duration_ms = 0;
                self.buffered_ms = 0;
                self.live = false;
            }
        }
        if let Some(details) = status.get("details") {
            let text = |name: &str| details.get(name).and_then(Value::as_str).map(str::to_owned);
            if let Some(video_id) = text("videoId") {
                self.details = Some(MediaDetails {
                    video_id,
                    channel_title: text("channelTitle"),
                    channel_url: text("channelUrl"),
                });
            }
        }
        self.check_skip(cx);
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

    /// The queue after `video_id`: `playlist`'s items when it came from one, else its radio.
    fn fetch_radio(&mut self, video_id: String, playlist: Option<String>, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("queue"));
        self.loading_queue = true;
        let fetch = on_core(cx, async move {
            match playlist {
                Some(url) => engine.playlist_queue(&url, &video_id, 0, &log).await,
                None => engine.start_radio_queue(&video_id, &log).await,
            }
        });
        cx.spawn(async move |this, cx| {
            let result = fetch.await;
            this.update(cx, |playback, cx| {
                playback.loading_queue = false;
                if let Some(Ok(page)) = result {
                    let current = playback.current().map(QueueItem::key);
                    let items = page
                        .sections
                        .iter()
                        .flat_map(|section| section.items.iter())
                        .filter(|item| item.kind == "video" && Some(&item.id) != current.as_ref())
                        .map(QueueItem::from_feed);
                    playback.queue.extend(items);
                    playback.queue_revision += 1;
                    playback.queue_continuation = page.continuation;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    fn extend_queue_if_near_end(&mut self, cx: &mut Context<Self>) {
        if self.loading_queue || self.index + 3 < self.queue.len() {
            return;
        }
        let Some(continuation) = self.queue_continuation.take() else { return };
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("queue"));
        self.loading_queue = true;
        let fetch = on_core(cx, async move { engine.queue_continuation(&continuation, &log).await });
        cx.spawn(async move |this, cx| {
            let result = fetch.await;
            this.update(cx, |playback, cx| {
                playback.loading_queue = false;
                if let Some(Ok(page)) = result {
                    let items = page
                        .sections
                        .iter()
                        .flat_map(|section| section.items.iter())
                        .filter(|item| item.kind == "video")
                        .map(QueueItem::from_feed);
                    playback.queue.extend(items);
                    playback.queue_revision += 1;
                    playback.queue_continuation = page.continuation;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }
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
