//! Now Playing (the JVM app's `MacNowPlayingSession`): publishes to `MPNowPlayingInfoCenter` (the
//! Control Center widget, the lock screen) and takes `MPRemoteCommandCenter`'s transport commands
//! (media keys, the widget, AirPods). Commands arrive on the main thread and only forward a
//! [`Command`].

use std::ptr::NonNull;

use block2::RcBlock;
use futures::channel::mpsc::UnboundedSender;
use objc2::rc::Retained;
use objc2::runtime::{AnyClass, AnyObject, Bool};
use objc2::{class, msg_send};
use objc2_foundation::{NSNumber, NSString};
use ym_common::log::Logger;

use super::{Command, Snapshot};

// MPNowPlayingPlaybackState.
const STATE_PLAYING: usize = 1;
const STATE_PAUSED: usize = 2;
const STATE_STOPPED: usize = 3;
/// MPRemoteCommandHandlerStatusSuccess.
const HANDLED: isize = 0;

#[link(name = "MediaPlayer", kind = "framework")]
unsafe extern "C" {
    static MPMediaItemPropertyTitle: &'static NSString;
    static MPMediaItemPropertyArtist: &'static NSString;
    static MPMediaItemPropertyPlaybackDuration: &'static NSString;
    static MPNowPlayingInfoPropertyElapsedPlaybackTime: &'static NSString;
    static MPNowPlayingInfoPropertyPlaybackRate: &'static NSString;
}

pub(super) struct Backend {
    /// The commands and the handler tokens they returned, removed at drop.
    targets: Vec<(Retained<AnyObject>, Retained<AnyObject>)>,
}

impl Backend {
    pub(super) fn start(commands: UnboundedSender<Command>, _log: Logger) -> Result<Self, String> {
        let center_class = AnyClass::get(c"MPRemoteCommandCenter").ok_or("no MPRemoteCommandCenter")?;
        // SAFETY: MediaPlayer's documented class method, on the main thread.
        let center: Retained<AnyObject> = unsafe { msg_send![center_class, sharedCommandCenter] };
        // SAFETY: MPRemoteCommandCenter's documented command properties.
        let simple: [(Option<Retained<AnyObject>>, &str, Command); 5] = unsafe {
            [
                (msg_send![&center, togglePlayPauseCommand], "togglePlayPauseCommand", Command::Toggle),
                (msg_send![&center, playCommand], "playCommand", Command::Play),
                (msg_send![&center, pauseCommand], "pauseCommand", Command::Pause),
                (msg_send![&center, nextTrackCommand], "nextTrackCommand", Command::Next),
                (msg_send![&center, previousTrackCommand], "previousTrackCommand", Command::Previous),
            ]
        };
        let mut targets = Vec::new();
        for (remote, name, command) in simple {
            let sender = commands.clone();
            let handler = RcBlock::new(move |_event: NonNull<AnyObject>| -> isize {
                // The app outlives its session; at quit a press has nowhere to go.
                let _ = sender.unbounded_send(command);
                HANDLED
            });
            targets.push(hook(remote.ok_or_else(|| format!("no {name}"))?, &handler));
        }
        let sender = commands;
        let seek = RcBlock::new(move |event: NonNull<AnyObject>| -> isize {
            // SAFETY: changePlaybackPositionCommand hands an MPChangePlaybackPositionCommandEvent.
            let seconds: f64 = unsafe { msg_send![event.as_ptr(), positionTime] };
            let _ = sender.unbounded_send(Command::SeekTo((seconds * 1000.) as i64));
            HANDLED
        });
        // SAFETY: as above.
        let position: Option<Retained<AnyObject>> = unsafe { msg_send![&center, changePlaybackPositionCommand] };
        targets.push(hook(position.ok_or("no changePlaybackPositionCommand")?, &seek));
        Ok(Self { targets })
    }

    pub(super) fn update(&self, snapshot: Snapshot) {
        // SAFETY: MPNowPlayingInfoCenter's documented API with values of the documented types.
        unsafe {
            let center: Retained<AnyObject> = msg_send![class!(MPNowPlayingInfoCenter), defaultCenter];
            if !snapshot.has_item {
                let _: () = msg_send![&center, setNowPlayingInfo: std::ptr::null::<AnyObject>()];
                let _: () = msg_send![&center, setPlaybackState: STATE_STOPPED];
                return;
            }
            let info: Retained<AnyObject> = msg_send![class!(NSMutableDictionary), dictionary];
            let put = |key: &NSString, value: &AnyObject| {
                let _: () = msg_send![&info, setObject: value, forKey: key];
            };
            if let Some(title) = &snapshot.title {
                put(MPMediaItemPropertyTitle, &NSString::from_str(title));
            }
            if let Some(artist) = &snapshot.artist {
                put(MPMediaItemPropertyArtist, &NSString::from_str(artist));
            }
            if let Some(duration_ms) = snapshot.duration_ms {
                put(MPMediaItemPropertyPlaybackDuration, &NSNumber::new_f64(duration_ms as f64 / 1000.));
            }
            put(MPNowPlayingInfoPropertyElapsedPlaybackTime, &NSNumber::new_f64(snapshot.position_ms as f64 / 1000.));
            let rate = if snapshot.playing { f64::from(snapshot.speed) } else { 0. };
            put(MPNowPlayingInfoPropertyPlaybackRate, &NSNumber::new_f64(rate));
            let _: () = msg_send![&center, setNowPlayingInfo: &*info];
            let state = if snapshot.playing { STATE_PLAYING } else { STATE_PAUSED };
            let _: () = msg_send![&center, setPlaybackState: state];
        }
    }
}

impl Drop for Backend {
    fn drop(&mut self) {
        // SAFETY: the targets `hook` added, removed from the commands they were added to.
        unsafe {
            for (command, target) in &self.targets {
                let _: () = msg_send![command, removeTarget: &**target];
            }
            let center: Retained<AnyObject> = msg_send![class!(MPNowPlayingInfoCenter), defaultCenter];
            let _: () = msg_send![&center, setNowPlayingInfo: std::ptr::null::<AnyObject>()];
            let _: () = msg_send![&center, setPlaybackState: STATE_STOPPED];
        }
    }
}

/// Enables `command` and hands it `handler`, returning the pair to remove at drop.
fn hook(
    command: Retained<AnyObject>,
    handler: &RcBlock<dyn Fn(NonNull<AnyObject>) -> isize>,
) -> (Retained<AnyObject>, Retained<AnyObject>) {
    // SAFETY: an MPRemoteCommand takes a handler block of this signature and returns its target token.
    unsafe {
        let _: () = msg_send![&command, setEnabled: Bool::YES];
        let target: Retained<AnyObject> = msg_send![&command, addTargetWithHandler: &**handler];
        (command, target)
    }
}
