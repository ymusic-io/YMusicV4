//! An MPRIS player on the session bus (the JVM app's `LinuxMprisSession`), what GNOME's and KDE's
//! Now Playing widgets, the lock screen and the desktop's media keys talk to. Method calls arrive on
//! zbus's executor and read the published snapshot; one outbox thread writes it and emits the
//! signals.

use std::collections::HashMap;
use std::sync::mpsc;
use std::time::Instant;

use futures::channel::mpsc::UnboundedSender;
use ym_common::log::Logger;
use zbus::blocking::Connection;
use zbus::blocking::object_server::InterfaceRef;
use zbus::fdo::{RequestNameFlags, RequestNameReply};
use zbus::interface;
use zbus::object_server::SignalEmitter;
use zbus::zvariant::{ObjectPath, Value};

use super::{Command, Snapshot};

const BUS_NAME: &str = "org.mpris.MediaPlayer2.ymusic";
const OBJECT_PATH: &str = "/org/mpris/MediaPlayer2";
const NO_TRACK: &str = "/org/mpris/MediaPlayer2/TrackList/NoTrack";
const TRACK_PATH_PREFIX: &str = "/io/ymusic/track/";
/// The app's speed range.
const MINIMUM_RATE: f64 = 0.25;
const MAXIMUM_RATE: f64 = 4.0;
/// A position this far off the extrapolated one is a seek, announced as `Seeked`.
const SEEK_TOLERANCE_MS: i64 = 1_500;
/// The JVM app's `.deb` names its desktop entry after the package; this one keeps that name.
const DEB_DESKTOP_ENTRY: &str = "ymusic-YMusic";

pub(super) struct Backend {
    outbox: mpsc::Sender<Snapshot>,
    _connection: Connection,
}

impl Backend {
    pub(super) fn start(commands: UnboundedSender<Command>, log: Logger) -> Result<Self, String> {
        let connection = Connection::session().map_err(|error| format!("session bus: {error}"))?;
        let desktop_entry = if std::env::current_exe().is_ok_and(|exe| exe.starts_with("/opt")) {
            DEB_DESKTOP_ENTRY
        } else {
            crate::desktop::APP_ID
        };
        let player = {
            let server = connection.object_server();
            server.at(OBJECT_PATH, Root { desktop_entry }).map_err(|error| error.to_string())?;
            server
                .at(OBJECT_PATH, Player { published: Published::idle(), commands })
                .map_err(|error| error.to_string())?;
            server.interface::<_, Player>(OBJECT_PATH).map_err(|error| error.to_string())?
        };
        request_name(&connection)?;
        let (outbox, inbox) = mpsc::channel::<Snapshot>();
        let runtime = tokio::runtime::Handle::current();
        std::thread::Builder::new()
            .name("mpris-outbox".into())
            .spawn(move || {
                let _runtime = runtime.enter();
                let mut track_counter = 0_u64;
                for snapshot in inbox {
                    if let Err(error) = publish(&player, snapshot, &mut track_counter) {
                        log.warn("R90030 media session not published to MPRIS", Some(&error));
                    }
                }
            })
            .map_err(|error| format!("the MPRIS outbox did not start: {error}"))?;
        Ok(Self { outbox, _connection: connection })
    }

    pub(super) fn update(&self, snapshot: Snapshot) {
        // The outbox ends only with this backend.
        let _ = self.outbox.send(snapshot);
    }
}

/// The JVM app's name, else one of this process's own when another player holds it.
fn request_name(connection: &Connection) -> Result<(), String> {
    let candidates = [BUS_NAME.to_owned(), format!("{BUS_NAME}.instance{}", std::process::id())];
    for name in &candidates {
        let reply = connection
            .request_name_with_flags(name.as_str(), RequestNameFlags::DoNotQueue.into())
            .map_err(|error| format!("{name}: {error}"))?;
        if matches!(reply, RequestNameReply::PrimaryOwner | RequestNameReply::AlreadyOwner) {
            return Ok(());
        }
    }
    Err(format!("MPRIS bus names already taken: {candidates:?}"))
}

#[derive(Debug, Clone)]
struct Published {
    snapshot: Option<Snapshot>,
    at: Instant,
    track_id: String,
}

impl Published {
    fn idle() -> Self {
        Self { snapshot: None, at: Instant::now(), track_id: NO_TRACK.to_owned() }
    }

    fn has_item(&self) -> bool {
        self.snapshot.as_ref().is_some_and(|snapshot| snapshot.has_item)
    }

    fn duration_ms(&self) -> Option<i64> {
        self.snapshot.as_ref().and_then(|snapshot| snapshot.duration_ms)
    }

    /// The play-head now, extrapolated from the snapshot at its speed, within the duration.
    fn position_ms(&self) -> i64 {
        let Some(snapshot) = &self.snapshot else { return 0 };
        let position = snapshot.position_after(self.at.elapsed().as_millis() as i64);
        snapshot.duration_ms.map_or(position, |duration| position.min(duration))
    }
}

struct Root {
    desktop_entry: &'static str,
}

#[interface(name = "org.mpris.MediaPlayer2")]
impl Root {
    fn raise(&self) {}

    fn quit(&self) {}

    #[zbus(property)]
    fn can_quit(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn can_raise(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn has_track_list(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn identity(&self) -> &str {
        "YMusic"
    }

    #[zbus(property)]
    fn desktop_entry(&self) -> &str {
        self.desktop_entry
    }

    #[zbus(property)]
    fn supported_uri_schemes(&self) -> Vec<String> {
        Vec::new()
    }

    #[zbus(property)]
    fn supported_mime_types(&self) -> Vec<String> {
        Vec::new()
    }
}

struct Player {
    published: Published,
    commands: UnboundedSender<Command>,
}

impl Player {
    fn send(&self, command: Command) {
        // The app outlives its session; at quit a call has nowhere to go.
        let _ = self.commands.unbounded_send(command);
    }
}

#[interface(name = "org.mpris.MediaPlayer2.Player")]
impl Player {
    fn next(&self) {
        self.send(Command::Next);
    }

    fn previous(&self) {
        self.send(Command::Previous);
    }

    fn pause(&self) {
        self.send(Command::Pause);
    }

    fn play_pause(&self) {
        self.send(Command::Toggle);
    }

    fn stop(&self) {
        self.send(Command::Pause);
    }

    fn play(&self) {
        self.send(Command::Play);
    }

    /// A seek past the end moves on, as the spec asks.
    fn seek(&self, offset: i64) {
        if self.published.snapshot.is_none() {
            return;
        }
        let target = self.published.position_ms() + offset / 1000;
        match self.published.duration_ms() {
            Some(duration) if target >= duration => self.send(Command::Next),
            _ => self.send(Command::SeekTo(target.max(0))),
        }
    }

    fn set_position(&self, track_id: ObjectPath<'_>, position: i64) {
        let position_ms = position / 1000;
        let in_range = position_ms >= 0 && self.published.duration_ms().is_none_or(|duration| position_ms <= duration);
        if track_id.as_str() == self.published.track_id && in_range {
            self.send(Command::SeekTo(position_ms));
        }
    }

    fn open_uri(&self, _uri: String) -> zbus::fdo::Result<()> {
        Err(zbus::fdo::Error::NotSupported("OpenUri is not supported".to_owned()))
    }

    #[zbus(signal)]
    async fn seeked(emitter: &SignalEmitter<'_>, position: i64) -> zbus::Result<()>;

    #[zbus(property)]
    fn playback_status(&self) -> &str {
        playback_status(&self.published)
    }

    #[zbus(property)]
    fn rate(&self) -> f64 {
        rate(&self.published)
    }

    #[zbus(property)]
    fn metadata(&self) -> HashMap<&str, Value<'_>> {
        let mut metadata = HashMap::new();
        if let Ok(track) = ObjectPath::try_from(self.published.track_id.as_str()) {
            metadata.insert("mpris:trackid", Value::new(track));
        }
        let Some(snapshot) = self.published.snapshot.as_ref().filter(|snapshot| snapshot.has_item) else {
            return metadata;
        };
        if let Some(duration) = snapshot.duration_ms {
            metadata.insert("mpris:length", Value::new(duration * 1000));
        }
        if let Some(title) = &snapshot.title {
            metadata.insert("xesam:title", Value::new(title.as_str()));
        }
        if let Some(artist) = &snapshot.artist {
            metadata.insert("xesam:artist", Value::new(vec![artist.as_str()]));
        }
        if let Some(artwork) = &snapshot.artwork_url {
            metadata.insert("mpris:artUrl", Value::new(artwork.as_str()));
        }
        metadata
    }

    #[zbus(property)]
    fn volume(&self) -> f64 {
        1.
    }

    #[zbus(property(emits_changed_signal = "false"))]
    fn position(&self) -> i64 {
        self.published.position_ms() * 1000
    }

    #[zbus(property)]
    fn minimum_rate(&self) -> f64 {
        MINIMUM_RATE
    }

    #[zbus(property)]
    fn maximum_rate(&self) -> f64 {
        MAXIMUM_RATE
    }

    #[zbus(property)]
    fn can_go_next(&self) -> bool {
        self.published.has_item()
    }

    #[zbus(property)]
    fn can_go_previous(&self) -> bool {
        self.published.has_item()
    }

    #[zbus(property)]
    fn can_play(&self) -> bool {
        self.published.has_item()
    }

    #[zbus(property)]
    fn can_pause(&self) -> bool {
        self.published.has_item()
    }

    #[zbus(property)]
    fn can_seek(&self) -> bool {
        self.published.has_item() && self.published.duration_ms().is_some()
    }

    #[zbus(property)]
    fn can_control(&self) -> bool {
        true
    }
}

fn playback_status(published: &Published) -> &'static str {
    match &published.snapshot {
        Some(snapshot) if snapshot.has_item && snapshot.playing => "Playing",
        Some(snapshot) if snapshot.has_item => "Paused",
        _ => "Stopped",
    }
}

fn rate(published: &Published) -> f64 {
    let speed = published.snapshot.as_ref().map_or(1., |snapshot| f64::from(snapshot.speed));
    ((speed * 100.).round() / 100.).clamp(MINIMUM_RATE, MAXIMUM_RATE)
}

/// What a property-change signal reports, to emit only the properties that changed.
#[derive(PartialEq)]
struct Announced {
    status: &'static str,
    rate: f64,
    track: (String, Option<String>, Option<String>, Option<String>, Option<i64>),
    has_item: bool,
    can_seek: bool,
}

impl Announced {
    fn of(published: &Published) -> Self {
        let snapshot = published.snapshot.as_ref().filter(|snapshot| snapshot.has_item);
        Self {
            status: playback_status(published),
            rate: rate(published),
            track: (
                published.track_id.clone(),
                snapshot.and_then(|snapshot| snapshot.title.clone()),
                snapshot.and_then(|snapshot| snapshot.artist.clone()),
                snapshot.and_then(|snapshot| snapshot.artwork_url.clone()),
                snapshot.and_then(|snapshot| snapshot.duration_ms),
            ),
            has_item: published.has_item(),
            can_seek: published.has_item() && published.duration_ms().is_some(),
        }
    }
}

fn publish(player: &InterfaceRef<Player>, snapshot: Snapshot, track_counter: &mut u64) -> zbus::Result<()> {
    let (before, after, seeked) = {
        let mut iface = player.get_mut();
        let previous = iface.published.clone();
        let same_track = snapshot.has_item
            && previous.snapshot.as_ref().is_some_and(|earlier| {
                earlier.has_item
                    && earlier.title == snapshot.title
                    && earlier.artist == snapshot.artist
                    && earlier.artwork_url == snapshot.artwork_url
            });
        let track_id = if !snapshot.has_item {
            NO_TRACK.to_owned()
        } else if same_track {
            previous.track_id.clone()
        } else {
            *track_counter += 1;
            format!("{TRACK_PATH_PREFIX}{track_counter}")
        };
        let expected = previous.position_ms();
        let seeked = (same_track && (snapshot.position_ms - expected).abs() > SEEK_TOLERANCE_MS)
            .then_some(snapshot.position_ms * 1000);
        iface.published = Published { snapshot: Some(snapshot), at: Instant::now(), track_id };
        (Announced::of(&previous), Announced::of(&iface.published), seeked)
    };
    let iface = player.get();
    let emitter = player.signal_emitter();
    zbus::block_on(async {
        if before.status != after.status {
            iface.playback_status_changed(emitter).await?;
        }
        if before.rate != after.rate {
            iface.rate_changed(emitter).await?;
        }
        if before.track != after.track {
            iface.metadata_changed(emitter).await?;
        }
        if before.has_item != after.has_item {
            iface.can_go_next_changed(emitter).await?;
            iface.can_go_previous_changed(emitter).await?;
            iface.can_play_changed(emitter).await?;
            iface.can_pause_changed(emitter).await?;
        }
        if before.can_seek != after.can_seek {
            iface.can_seek_changed(emitter).await?;
        }
        if let Some(position) = seeked {
            Player::seeked(emitter, position).await?;
        }
        Ok(())
    })
}
