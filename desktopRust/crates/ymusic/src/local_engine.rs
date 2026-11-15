//! Local file playback: `file_av` on one player thread that runs commands and steps while playing.

use std::path::PathBuf;
use std::sync::mpsc::{self, RecvTimeoutError, TryRecvError};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ym_mse::{LocalAv, Presenter};

use crate::audio_effects::AudioEffects;

pub enum LocalCommand {
    Load {
        path: PathBuf,
        video: bool,
    },
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
}

pub struct LocalEngine {
    commands: mpsc::Sender<LocalCommand>,
    pub status: Arc<Mutex<LocalStatus>>,
}

const IDLE_WAIT: Duration = Duration::from_millis(50);
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

fn run(mut player: LocalAv, commands: mpsc::Receiver<LocalCommand>, status: Arc<Mutex<LocalStatus>>) {
    let mut loaded = false;
    let mut playing = false;
    let mut effects = AudioEffects::default();
    loop {
        let next = if playing {
            commands.try_recv().map_err(|error| error == TryRecvError::Disconnected)
        } else {
            commands.recv_timeout(IDLE_WAIT).map_err(|error| error == RecvTimeoutError::Disconnected)
        };
        match next {
            Ok(command) => match command {
                LocalCommand::Load { path, video } => {
                    player.close();
                    player.set_video_enabled(video);
                    let mut status = status.lock().unwrap();
                    status.generation += 1;
                    status.ended = false;
                    status.position_ms = 0;
                    status.video_track = None;
                    match player.open(&path) {
                        Ok(()) => {
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
                    player.seek(position_ms as f64 / 1000.0);
                    let mut status = status.lock().unwrap();
                    status.position_ms = position_ms;
                    status.ended = false;
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
                    loaded = false;
                    playing = false;
                    status.lock().unwrap().playing = false;
                }
                _ => {}
            },
            Err(true) => return,
            Err(false) => {}
        }
        if playing && loaded {
            player.step();
            let mut status = status.lock().unwrap();
            status.position_ms = (player.position().max(0.0) * 1000.0) as i64;
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
