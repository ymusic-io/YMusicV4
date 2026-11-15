//! Local file playback: `file_av` on one player thread that runs commands and steps while playing.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, RecvTimeoutError, TryRecvError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use ym_mse::{AvStream, Cue, LocalAv, Presenter, StreamKind};

use crate::audio_effects::AudioEffects;
use crate::local_captions::{SidecarTrack, active_lines, find_sidecars, lang_key, track_label};
use crate::playback::CaptionTrack;

/// The JVM `PlaybackConfig.captionVisible` and `preferCaptionLanguage`.
#[derive(Clone, Debug, PartialEq)]
pub struct CaptionPrefs {
    pub visible: bool,
    pub language: String,
}

pub enum LocalCommand {
    Load {
        path: PathBuf,
        video: bool,
        captions: CaptionPrefs,
    },
    Captions(CaptionPrefs),
    Play,
    Pause,
    Seek(i64),
    Volume(f32),
    Effects(AudioEffects),
    Video(bool),
    Presenter(Option<Arc<Presenter>>),
    /// The spectrum of what plays now, `count` bars.
    Levels(usize, mpsc::Sender<Vec<f32>>),
    /// Pictures shown since the file opened.
    Frames(mpsc::Sender<i32>),
    Stop,
}

#[derive(Debug, Clone, Default)]
pub struct LocalStatus {
    /// Bumped by every load, so a reader can tell which file the rest describes.
    pub generation: u64,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub playing: bool,
    pub ended: bool,
    pub error: Option<String>,
    /// The open file and whether it has a picture stream.
    pub video_track: Option<(PathBuf, bool)>,
    pub caption_tracks: Vec<CaptionTrack>,
    /// The shown track's id while captions are on.
    pub caption_selected: Option<String>,
    /// The cues on screen now.
    pub caption_lines: Vec<(i64, String)>,
}

pub struct LocalEngine {
    commands: mpsc::Sender<LocalCommand>,
    pub status: Arc<Mutex<LocalStatus>>,
}

const IDLE_WAIT: Duration = Duration::from_millis(50);
/// How often the cues on screen are recomputed while playing (the JVM app follows its ~100 ms progress).
const CAPTION_TICK: Duration = Duration::from_millis(100);
/// Live cues further than this behind the play position are dropped.
const LIVE_CUE_KEEP_S: f64 = 30.0;
const LEVELS_WAIT: Duration = Duration::from_millis(100);

impl LocalEngine {
    pub fn start() -> Option<Self> {
        let (commands, receiver) = mpsc::channel();
        let status = Arc::new(Mutex::new(LocalStatus::default()));
        let (ready_tx, ready_rx) = mpsc::channel();
        let thread_status = status.clone();
        std::thread::Builder::new()
            .name("local-player".into())
            .spawn(move || {
                let Some(player) = LocalAv::create() else {
                    let _ = ready_tx.send(false);
                    return;
                };
                let _ = ready_tx.send(true);
                run(player, receiver, thread_status);
            })
            .ok()?;
        ready_rx.recv().ok().filter(|ready| *ready)?;
        Some(Self { commands, status })
    }

    pub fn send(&self, command: LocalCommand) {
        let _ = self.commands.send(command);
    }

    pub fn level_tap(&self) -> LocalLevelTap {
        LocalLevelTap { commands: self.commands.clone() }
    }
}

/// Reads the local player's spectrum from any thread.
#[derive(Clone)]
pub struct LocalLevelTap {
    commands: mpsc::Sender<LocalCommand>,
}

impl LocalLevelTap {
    /// Blocks until the player thread answers; empty when it is gone or busy past `LEVELS_WAIT`.
    pub fn read(&self, count: usize) -> Vec<f32> {
        let (reply, levels) = mpsc::channel();
        if self.commands.send(LocalCommand::Levels(count, reply)).is_err() {
            return Vec::new();
        }
        levels.recv_timeout(LEVELS_WAIT).unwrap_or_default()
    }

    /// Pictures shown since the file opened; None when the player thread is gone or busy.
    pub fn frames(&self) -> Option<i32> {
        let (reply, frames) = mpsc::channel();
        self.commands.send(LocalCommand::Frames(reply)).ok()?;
        frames.recv_timeout(LEVELS_WAIT).ok()
    }
}

/// The JVM `JvmLocalPlayer`'s caption state for the open file.
struct Captions {
    prefs: CaptionPrefs,
    text_streams: Vec<AvStream>,
    sidecars: Vec<SidecarTrack>,
    /// Every cue of each embedded text stream, by stream index.
    dumped: HashMap<i32, Vec<Cue>>,
    /// Cues the decoded subtitle stream delivered as playback reached them.
    live: Vec<Cue>,
    /// The sidecar or dumped cues shown; None shows `live`.
    shown: Option<Vec<Cue>>,
    last_tick: Option<Instant>,
}

impl Captions {
    fn new(prefs: CaptionPrefs) -> Self {
        Self {
            prefs,
            text_streams: Vec::new(),
            sidecars: Vec::new(),
            dumped: HashMap::new(),
            live: Vec::new(),
            shown: None,
            last_tick: None,
        }
    }

    fn open(&mut self, player: &mut LocalAv, path: &Path) {
        self.text_streams = player.streams().into_iter().filter(|stream| stream.kind == StreamKind::Text).collect();
        self.sidecars = find_sidecars(path);
        self.dumped = self
            .text_streams
            .iter()
            .map(|stream| (stream.index, player.dump_captions(stream.index)))
            .filter(|(_, cues)| !cues.is_empty())
            .collect();
        self.live.clear();
        self.apply(player);
    }

    fn clear(&mut self, player: &mut LocalAv) {
        self.text_streams.clear();
        self.sidecars.clear();
        self.dumped.clear();
        self.live.clear();
        self.shown = None;
        player.set_subtitle_index(None);
    }

    /// The JVM `applyNativeCaption`: a matching sidecar, else the preferred (or first) embedded
    /// stream's dumped cues, else that stream decoded live.
    fn apply(&mut self, player: &mut LocalAv) {
        if !self.prefs.visible {
            self.shown = None;
            player.set_subtitle_index(None);
            return;
        }
        let key = self.prefs.language.as_str();
        let key_norm = lang_key(key);
        if let Some(side) =
            self.sidecars.iter().find(|side| lang_key(&side.id) == key_norm || lang_key(&side.lang) == key_norm)
        {
            self.shown = Some(side.cues.clone());
            player.set_subtitle_index(None);
            return;
        }
        let chosen = self
            .text_streams
            .iter()
            .find(|stream| stream.lang.eq_ignore_ascii_case(key) || stream.index.to_string() == key)
            .or_else(|| self.text_streams.first());
        if let Some(dumped) = chosen.and_then(|stream| self.dumped.get(&stream.index)) {
            self.shown = Some(dumped.clone());
            player.set_subtitle_index(None);
            return;
        }
        self.shown = None;
        player.set_subtitle_index(chosen.map(|stream| stream.index));
    }

    /// The JVM `publishCaptions`: embedded streams, then sidecars replacing an embedded track of the
    /// same language in place; the shown one is the preferred track, else the first.
    fn tracks(&self) -> (Vec<CaptionTrack>, Option<String>) {
        let track = |id: String, label: String| CaptionTrack {
            vss_id: id.clone(),
            language_code: id,
            display_name: label,
            kind: String::new(),
        };
        let mut keyed: Vec<(String, CaptionTrack)> = Vec::new();
        let embedded = self.text_streams.iter().map(|stream| {
            let lang = if stream.lang.is_empty() { stream.index.to_string() } else { stream.lang.clone() };
            track(lang, track_label(&stream.lang, stream.index))
        });
        let sides = self.sidecars.iter().map(|side| track(side.id.clone(), side.label.clone()));
        for entry in embedded.chain(sides) {
            let key = lang_key(&entry.vss_id);
            match keyed.iter_mut().find(|(known, _)| *known == key) {
                Some((_, slot)) => *slot = entry,
                None => keyed.push((key, entry)),
            }
        }
        let list: Vec<CaptionTrack> = keyed.into_iter().map(|(_, entry)| entry).collect();
        let language = self.prefs.language.as_str();
        let selected = self
            .prefs
            .visible
            .then(|| {
                list.iter()
                    .find(|entry| entry.vss_id == language || entry.language_code.eq_ignore_ascii_case(language))
                    .or_else(|| list.first())
                    .map(|entry| entry.vss_id.clone())
            })
            .flatten();
        (list, selected)
    }

    fn take_live(&mut self, player: &mut LocalAv, position: f64) {
        let arrived = player.take_live_cues();
        if arrived.is_empty() {
            return;
        }
        self.live.extend(arrived);
        self.live.retain(|cue| cue.end >= position - LIVE_CUE_KEEP_S);
    }

    fn lines(&self, position: f64) -> Vec<(i64, String)> {
        if !self.prefs.visible {
            return Vec::new();
        }
        active_lines(self.shown.as_deref().unwrap_or(&self.live), position)
    }

    fn publish(&self, status: &mut LocalStatus, position: f64) {
        let (tracks, selected) = self.tracks();
        status.caption_tracks = tracks;
        status.caption_selected = selected;
        status.caption_lines = self.lines(position);
    }
}

fn run(mut player: LocalAv, commands: mpsc::Receiver<LocalCommand>, status: Arc<Mutex<LocalStatus>>) {
    let mut loaded = false;
    let mut playing = false;
    let mut effects = AudioEffects::default();
    let mut captions = Captions::new(CaptionPrefs {
        visible: false,
        language: crate::local_captions::UNDETERMINED_LANGUAGE.to_owned(),
    });
    loop {
        let next = if playing {
            commands.try_recv().map_err(|error| error == TryRecvError::Disconnected)
        } else {
            commands.recv_timeout(IDLE_WAIT).map_err(|error| error == RecvTimeoutError::Disconnected)
        };
        match next {
            Ok(command) => match command {
                LocalCommand::Load { path, video, captions: prefs } => {
                    player.close();
                    player.set_video_enabled(video);
                    captions.clear(&mut player);
                    captions.prefs = prefs;
                    let mut status = status.lock().unwrap();
                    status.generation += 1;
                    status.ended = false;
                    status.position_ms = 0;
                    status.video_track = None;
                    status.caption_tracks.clear();
                    status.caption_selected = None;
                    status.caption_lines.clear();
                    match player.open(&path) {
                        Ok(()) => {
                            captions.open(&mut player, &path);
                            captions.publish(&mut status, 0.0);
                            status.video_track = Some((path, player.has_video()));
                            player.set_speed_pitch(f64::from(effects.speed), f64::from(effects.pitch));
                            player.set_tone(&effects.tone);
                            loaded = true;
                            playing = true;
                            player.play();
                            status.duration_ms = (player.duration() * 1000.0) as i64;
                            status.playing = true;
                            status.error = None;
                        }
                        Err(message) => {
                            loaded = false;
                            playing = false;
                            status.playing = false;
                            status.error = Some(message);
                        }
                    }
                }
                LocalCommand::Captions(prefs) => {
                    captions.prefs = prefs;
                    if loaded {
                        captions.apply(&mut player);
                        let position = player.position().max(0.0);
                        if captions.prefs.visible && captions.shown.is_none() {
                            captions.live.clear();
                            player.seek(position);
                        }
                        captions.publish(&mut status.lock().unwrap(), position);
                    }
                }
                LocalCommand::Play if loaded => {
                    player.play();
                    playing = true;
                    status.lock().unwrap().playing = true;
                }
                LocalCommand::Pause if loaded => {
                    player.pause();
                    playing = false;
                    status.lock().unwrap().playing = false;
                }
                LocalCommand::Seek(position_ms) if loaded => {
                    captions.live.clear();
                    player.seek(position_ms as f64 / 1000.0);
                    let mut status = status.lock().unwrap();
                    status.position_ms = position_ms;
                    status.ended = false;
                    status.caption_lines = captions.lines(position_ms as f64 / 1000.0);
                }
                LocalCommand::Volume(volume) => player.set_volume(f64::from(volume)),
                LocalCommand::Effects(next) => {
                    effects = next;
                    player.set_speed_pitch(f64::from(effects.speed), f64::from(effects.pitch));
                    player.set_tone(&effects.tone);
                }
                LocalCommand::Video(on) => player.set_video_enabled(on),
                LocalCommand::Presenter(presenter) => player.bind_presenter(presenter.as_deref()),
                LocalCommand::Levels(count, reply) => {
                    let _ = reply.send(if loaded { player.levels(count) } else { Vec::new() });
                }
                LocalCommand::Frames(reply) => {
                    let _ = reply.send(if loaded { player.frames() } else { 0 });
                }
                LocalCommand::Stop => {
                    player.pause();
                    player.close();
                    captions.clear(&mut player);
                    loaded = false;
                    playing = false;
                    let mut status = status.lock().unwrap();
                    status.playing = false;
                    status.caption_tracks.clear();
                    status.caption_selected = None;
                    status.caption_lines.clear();
                }
                _ => {}
            },
            Err(true) => return,
            Err(false) => {}
        }
        if playing && loaded {
            player.step();
            let position = player.position().max(0.0);
            captions.take_live(&mut player, position);
            let mut status = status.lock().unwrap();
            status.position_ms = (position * 1000.0) as i64;
            if captions.last_tick.is_none_or(|tick| tick.elapsed() >= CAPTION_TICK) {
                captions.last_tick = Some(Instant::now());
                let lines = captions.lines(position);
                if lines != status.caption_lines {
                    status.caption_lines = lines;
                }
            }
            if player.ended() {
                status.ended = true;
                status.playing = false;
                playing = false;
            }
            drop(status);
            std::thread::sleep(Duration::from_millis(2));
        }
    }
}
