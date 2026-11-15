//! The JVM desktop app's keyboard shortcuts, bound in the Shell's key context so a focused text
//! field or an open dialog gets keys first.

use gpui_kit::{App, KeyBinding, actions};
use ym_i18n::Str;

pub const CONTEXT: &str = "Shell";

actions!(
    ymusic,
    [
        TogglePlay,
        SeekBackward,
        SeekForward,
        PreviousTrack,
        NextTrack,
        VolumeUp,
        VolumeDown,
        ToggleNowPlaying,
        ToggleDrawer,
        OpenSearch,
        ToggleFullscreen,
        Escape,
        SelectTab1,
        SelectTab2,
        SelectTab3,
        SelectTab4,
        SelectTab5,
        SelectTab6,
        ShowShortcuts,
        /// Reload the feed on screen (F5, or Ctrl / Cmd+R as in the JVM app).
        Refresh,
        /// Open the sign-in screen (dispatched by the sign-in buttons, no key).
        OpenSignIn,
    ]
);

/// Cmd on macOS, Ctrl elsewhere.
#[cfg(target_os = "macos")]
macro_rules! secondary {
    ($key:literal) => {
        concat!("cmd-", $key)
    };
}
#[cfg(not(target_os = "macos"))]
macro_rules! secondary {
    ($key:literal) => {
        concat!("ctrl-", $key)
    };
}

/// One row of the shortcuts help: alternative keystroke sequences (space-separated keystrokes),
/// or with `range` the first and last of a run, and what they do.
pub struct HelpRow {
    pub keys: &'static [&'static str],
    pub range: bool,
    pub label: Str,
}

const fn row(keys: &'static [&'static str], label: Str) -> HelpRow {
    HelpRow { keys, range: false, label }
}

/// The bindings below, as the shortcuts help lists them.
pub const HELP: &[HelpRow] = &[
    row(&["f8"], Str::ShortcutsPlayPause),
    row(&[secondary!("left")], Str::CdPrevious),
    row(&[secondary!("right")], Str::CdNext),
    row(&[secondary!("shift-left"), "f7"], Str::CdRewind10),
    row(&[secondary!("shift-right"), "f9"], Str::CdForward10),
    row(&[secondary!("up")], Str::ShortcutsVolumeUp),
    row(&[secondary!("down")], Str::ShortcutsVolumeDown),
    #[cfg(target_os = "macos")]
    row(&["f11", "ctrl-cmd-f"], Str::CdFullscreen),
    #[cfg(not(target_os = "macos"))]
    row(&["f11"], Str::CdFullscreen),
    row(&[secondary!("n")], Str::ShortcutsNowPlaying),
    row(&[secondary!("m")], Str::ShortcutsNavMenu),
    row(&[secondary!("f")], Str::CdSearch),
    HelpRow { keys: &[secondary!("1"), secondary!("6")], range: true, label: Str::ShortcutsSwitchTabs },
    row(&["escape"], Str::ShortcutsEscape),
    row(&["escape escape"], Str::CdBack),
    row(&["f1"], Str::ShortcutsHelp),
];

pub fn bind(cx: &mut App) {
    let context = Some(CONTEXT);
    cx.bind_keys([
        KeyBinding::new("f8", TogglePlay, context),
        KeyBinding::new("f7", SeekBackward, context),
        KeyBinding::new("f9", SeekForward, context),
        KeyBinding::new("f11", ToggleFullscreen, context),
        KeyBinding::new("f1", ShowShortcuts, context),
        KeyBinding::new("f5", Refresh, context),
        KeyBinding::new("ctrl-r", Refresh, context),
        #[cfg(target_os = "macos")]
        KeyBinding::new("cmd-r", Refresh, context),
        // macOS's standard full screen.
        #[cfg(target_os = "macos")]
        KeyBinding::new("ctrl-cmd-f", ToggleFullscreen, context),
        KeyBinding::new(secondary!("n"), ToggleNowPlaying, context),
        KeyBinding::new(secondary!("m"), ToggleDrawer, context),
        KeyBinding::new(secondary!("f"), OpenSearch, context),
        KeyBinding::new(secondary!("left"), PreviousTrack, context),
        KeyBinding::new(secondary!("right"), NextTrack, context),
        KeyBinding::new(secondary!("shift-left"), SeekBackward, context),
        KeyBinding::new(secondary!("shift-right"), SeekForward, context),
        KeyBinding::new(secondary!("up"), VolumeUp, context),
        KeyBinding::new(secondary!("down"), VolumeDown, context),
        KeyBinding::new("escape", Escape, context),
        KeyBinding::new(secondary!("1"), SelectTab1, context),
        KeyBinding::new(secondary!("2"), SelectTab2, context),
        KeyBinding::new(secondary!("3"), SelectTab3, context),
        KeyBinding::new(secondary!("4"), SelectTab4, context),
        KeyBinding::new(secondary!("5"), SelectTab5, context),
        KeyBinding::new(secondary!("6"), SelectTab6, context),
    ]);
}
