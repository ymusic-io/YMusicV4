//! The OS media surface (the JVM app's `DesktopMediaSession`): Now Playing info and transport
//! commands, per platform — SMTC on Windows (the media flyout, media keys), MPNowPlayingInfoCenter
//! and MPRemoteCommandCenter on macOS, an MPRIS player on Linux — mirrored from [`Playback`].

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "windows")]
mod windows;

#[cfg(target_os = "linux")]
use linux::Backend;
#[cfg(target_os = "macos")]
use macos::Backend;
#[cfg(target_os = "windows")]
use windows::Backend;

use std::time::Instant;

use futures::StreamExt;
use futures::channel::mpsc;
use gpui_kit::*;

use crate::graph::AppGraph;
use crate::playback::Playback;

/// Position drift beyond normal tick jitter: a seek the OS must be told about.
const SEEK_DRIFT_MS: i64 = 2_000;

/// One snapshot of what the OS surface shows (the JVM app's `MediaSessionState`). The OS
/// extrapolates the position from it at `speed`, so routine ticks need no new snapshot.
#[derive(Debug, Clone, PartialEq)]
struct Snapshot {
    title: Option<String>,
    artist: Option<String>,
    /// An http(s) or `file:` URL.
    artwork_url: Option<String>,
    playing: bool,
    position_ms: i64,
    duration_ms: Option<i64>,
    speed: f32,
    has_item: bool,
}

impl Snapshot {
    /// The snapshot as an observer compares it: everything but the position.
    fn identity(&self) -> Snapshot {
        Snapshot { position_ms: 0, ..self.clone() }
    }

    /// Where the OS puts the play-head `elapsed_ms` after this snapshot.
    fn position_after(&self, elapsed_ms: i64) -> i64 {
        if self.playing {
            self.position_ms + (elapsed_ms as f64 * f64::from(self.speed)) as i64
        } else {
            self.position_ms
        }
    }
}

/// A transport command from the OS (media keys, the OS widget), sent from an OS thread.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Command {
    /// SMTC has no toggle button: Windows sends Play and Pause.
    #[cfg_attr(target_os = "windows", allow(dead_code))]
    Toggle,
    Play,
    Pause,
    Next,
    Previous,
    SeekTo(i64),
    /// The OS widget was clicked: bring the window back, from the tray too.
    Raise,
}

pub struct MediaSession {
    backend: Option<Backend>,
    published: Option<(Snapshot, Instant)>,
    _observe: Subscription,
    _commands: Task<()>,
}

impl MediaSession {
    pub fn new(playback: Entity<Playback>, cx: &mut Context<Self>) -> Self {
        let (sender, mut receiver) = mpsc::unbounded::<Command>();
        let log = cx.global::<AppGraph>().log.fork("media-session");
        // zbus runs on Tokio once a dependency enables its tokio feature: connecting outside the
        // runtime panics ("there is no reactor running").
        let runtime = cx.global::<AppGraph>().tokio.enter();
        let started = Backend::start(sender, log.clone());
        drop(runtime);
        let backend = match started {
            Ok(backend) => Some(backend),
            Err(error) => {
                log.warn(&format!("R90004 media controls unavailable: {error}"), None);
                None
            }
        };
        let observe = cx.observe(&playback, |session, playback, cx| session.mirror(&playback, cx));
        let target = playback.downgrade();
        let commands = cx.spawn(async move |_, cx| {
            while let Some(command) = receiver.next().await {
                if command == Command::Raise {
                    cx.update(|cx| {
                        let shell = cx.try_global::<crate::shell::ShellHandle>().and_then(|handle| handle.0.upgrade());
                        if let Some(shell) = shell {
                            shell.update(cx, |shell, cx| shell.show_window(cx));
                        }
                    });
                    continue;
                }
                let applied = target.update(cx, |playback, cx| match command {
                    Command::Toggle => playback.toggle(cx),
                    Command::Play if !playback.is_playing() => playback.toggle(cx),
                    Command::Pause if playback.is_playing() => playback.toggle(cx),
                    Command::Next => playback.next(cx),
                    Command::Previous => playback.previous(cx),
                    Command::SeekTo(position_ms) => playback.seek(position_ms.max(0), cx),
                    Command::Play | Command::Pause | Command::Raise => {}
                });
                if applied.is_err() {
                    return;
                }
            }
        });
        Self { backend, published: None, _observe: observe, _commands: commands }
    }

    /// Publishes on every change but the position, and on a position jump the OS cannot
    /// extrapolate (a seek), as the JVM app does.
    fn mirror(&mut self, playback: &Entity<Playback>, cx: &mut Context<Self>) {
        let Some(backend) = &self.backend else { return };
        let snapshot = snapshot(playback.read(cx), cx);
        let changed = match &self.published {
            None => true,
            Some((previous, at)) => {
                let expected = previous.position_after(at.elapsed().as_millis() as i64);
                previous.identity() != snapshot.identity() || (snapshot.position_ms - expected).abs() > SEEK_DRIFT_MS
            }
        };
        if changed {
            backend.update(snapshot.clone());
            self.published = Some((snapshot, Instant::now()));
        }
    }
}

fn snapshot(playback: &Playback, cx: &App) -> Snapshot {
    let item = playback.current();
    let artwork_url = item.and_then(|item| item.thumb(i32::MAX)).and_then(|url| {
        if !crate::local_art::LocalArt::handles(&url) {
            return Some(url.to_string());
        }
        // The OS cannot read the app's art scheme: the loader's cached file stands in.
        let art = cx.try_global::<crate::local_art::SharedLocalArt>()?;
        art.0.cached_file(&url).and_then(|file| url::Url::from_file_path(file).ok()).map(String::from)
    });
    // A video queued without its channel gets it once the player names it.
    let artist = item
        .and_then(|item| item.artist.clone())
        .or_else(|| playback.current_details().and_then(|details| details.channel_title.clone()));
    Snapshot {
        title: item.map(|item| item.title.clone()),
        artist,
        artwork_url,
        playing: playback.is_playing(),
        position_ms: playback.position_ms.max(0),
        duration_ms: (playback.duration_ms > 0).then_some(playback.duration_ms),
        speed: playback.effects.speed,
        has_item: item.is_some(),
    }
}
