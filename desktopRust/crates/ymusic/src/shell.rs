//! The app shell, laid out like the JVM desktop app: a navigation rail (72 collapsed, 280
//! expanded) from 750 wide, a modal drawer below that; a top bar per section (title, Discover's
//! icon tabs, search); pushed layers (search, results, detail pages) that bring their own back bar;
//! and the mini player along the bottom.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::time::{Duration, Instant};

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::{ActiveTheme, Icon, IconName, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};

use crate::a11y::{DragRow, FocusList, focusable};
use crate::account_view::AccountView;
use crate::action_icon::action_icon;
use crate::download_dialog::{DownloadPicker, DownloadTarget};
use crate::downloads_view::{DownloadsView, RevealInLibrary};
use crate::feed::{FeedAction, FeedSource, FeedView, ItemAction, Navigate, Play, RequestSearch};
use crate::feedback_view::FeedbackView;
use crate::graph::{AppGraph, on_core};
use crate::library::{GroupKind, LibraryView, PlayLocal, QueueLocal};
use crate::library_search::{LibrarySearchEvent, LibrarySearchView};
use crate::media_session::MediaSession;
use crate::mini_player::{MiniPlayer, ToggleNowPlaying};
use crate::now_playing::{NowPlaying, NowPlayingEvent};
use crate::play_again::{PlayAgainView, ResumeQueue};
use crate::play_history::HistoryRecorder;
use crate::playback::{Playback, QueueItem};
use crate::search_view::{SearchEvent, SearchMode, SearchView};
use crate::settings_view::HOVER_ALPHA;
use crate::settings_view::{LocaleChanged, SettingsView};
use crate::signin::AccountChanged;
use crate::signin_dialog::{SignInEvent, SignInView};
use crate::sponsor_block::SponsorBlock;
use crate::tab_row::tab_row;

mod incoming;
mod tray;

const RAIL_COLLAPSED: f32 = 72.;
const DRAWER_ICON_START: f32 = 20.;
/// Centres the expanded rail's section icons on the collapsed rail's centre line.
const RAIL_ICON_START: f32 = RAIL_COLLAPSED / 2. - SECTION_ICON / 2.;
const SECTION_ICON: f32 = 16.;
const RAIL_EXPANDED: f32 = 280.;
/// The rail's logo, larger than the JVM rail's 24 so the mark reads at a glance.
const RAIL_LOGO: f32 = 32.;
const DRAWER_WIDTH: f32 = 300.;
const WEBSITE: &str = "https://ymusic.io";
/// The bars' and the rail's buttons, as arrow stops.
const CHROME_RAIL_TOGGLE: usize = 0;
const CHROME_BACK: usize = 1;
const CHROME_FILTER: usize = 2;
const CHROME_LAYER_SEARCH: usize = 3;
const CHROME_DRAWER: usize = 4;
const CHROME_SEARCH: usize = 5;

/// The visible page's scroll container: the bars lift while it is scrolled from its top (the JVM
/// app's `ScrollHost`).
#[derive(Default)]
pub struct ScrollHost {
    active: RefCell<Option<ScrollHandle>>,
    lifted: Cell<bool>,
}

impl Global for ScrollHost {}

/// Called by a page as it renders: the bars follow `scroll`.
pub fn track_scroll(scroll: &ScrollHandle, cx: &App) {
    *cx.global::<ScrollHost>().active.borrow_mut() = Some(scroll.clone());
}

/// The bars' ground: the elevated tone while the page under them is scrolled.
pub fn chrome_tone(cx: &App) -> Hsla {
    let tones = cx.global::<crate::settings_view::ChromeTones>();
    if cx.global::<ScrollHost>().lifted.get() { tones.lifted } else { tones.flat }
}

/// The shadow a lifted bar casts on the page below it, laid over the page's top edge.
pub fn bar_shadow(cx: &App) -> Option<Div> {
    cx.global::<ScrollHost>().lifted.get().then(|| elevation_shadow(false))
}

/// The JVM app's painted elevation falloff, darkest at the casting edge: along its parent's top,
/// or `upward` from its bottom.
pub fn elevation_shadow(upward: bool) -> Div {
    let (near, mid) = (black().opacity(0.20), black().opacity(0.07));
    let angle = if upward { 0. } else { 180. };
    let band = |height: f32, from: Hsla, to: Hsla| {
        div().h(px(height)).bg(linear_gradient(angle, linear_color_stop(from, 0.), linear_color_stop(to, 1.)))
    };
    let (edge, tail) = (band(SHADOW_REACH * 0.4, near, mid), band(SHADOW_REACH * 0.6, mid, transparent_black()));
    let layer = v_flex().absolute().left_0().right_0();
    if upward { layer.bottom_0().child(tail).child(edge) } else { layer.top_0().child(edge).child(tail) }
}

const SHADOW_REACH: f32 = 12.;
const BAR_PAD_TOP: f32 = 2.;
const BAR_PAD_BOTTOM: f32 = 3.;

/// Shared with the JVM app: the section and Discover tab a restart resumes, by their stable ids.
const LAST_SECTION_KEY: &str = "app.lastSection";
const LAST_DISCOVER_TAB_KEY: &str = "app.lastDiscoverTab";
const SEEK_STEP_MS: i64 = 10_000;
const VOLUME_STEP: f32 = 0.05;
const DOUBLE_ESCAPE: Duration = Duration::from_millis(500);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum Section {
    Discover,
    YtMusic,
    Library,
    PlayAgain,
    Downloads,
    Settings,
    Donate,
    Telegram,
    Feedback,
}

impl Section {
    const GROUPS: [&'static [Section]; 3] = [
        &[Section::Discover, Section::YtMusic, Section::Library],
        &[Section::PlayAgain, Section::Downloads, Section::Settings],
        &[Section::Telegram, Section::Feedback],
    ];

    const ALL: [Section; 9] = [
        Section::Discover,
        Section::YtMusic,
        Section::Library,
        Section::PlayAgain,
        Section::Downloads,
        Section::Settings,
        Section::Donate,
        Section::Telegram,
        Section::Feedback,
    ];

    /// A guest keeps no YouTube play history, so it has no Play again.
    fn groups(guest: bool) -> Vec<Vec<Section>> {
        Self::GROUPS
            .iter()
            .map(|group| group.iter().copied().filter(|section| !guest || *section != Section::PlayAgain).collect())
            .collect()
    }

    /// The persisted id (the Compose `AppSection.id`): stable, never reused.
    fn id(self) -> i32 {
        Self::ALL.iter().position(|section| *section == self).unwrap_or(0) as i32 + 1
    }

    fn from_id(id: i32) -> Option<Self> {
        Self::ALL.get(usize::try_from(id - 1).ok()?).copied()
    }

    fn label(self) -> &'static str {
        t(match self {
            Section::Discover => Str::SectionDiscover,
            Section::YtMusic => Str::SectionYtMusic,
            Section::Library => Str::SectionLibrary,
            Section::PlayAgain => Str::SectionPlayAgain,
            Section::Downloads => Str::SectionDownloads,
            Section::Settings => Str::SectionSettings,
            Section::Donate => Str::SectionDonate,
            Section::Telegram => Str::SectionTelegram,
            Section::Feedback => Str::SectionFeedback,
        })
    }

    fn icon(self) -> Lucide {
        match self {
            Section::Discover => Lucide::MonitorPlay,
            Section::YtMusic => Lucide::CirclePlay,
            Section::Library => Lucide::LibraryBig,
            Section::PlayAgain => Lucide::RotateCcwClock,
            Section::Downloads => Lucide::Download,
            Section::Settings => Lucide::Settings,
            Section::Donate => Lucide::Heart,
            Section::Telegram => Lucide::Send,
            Section::Feedback => Lucide::MessageCircle,
        }
    }
}

/// YT Music's text tabs.
#[derive(Clone, Copy, PartialEq)]
enum MusicTab {
    Home,
    Explore,
    NewReleases,
    Charts,
    Library,
}

impl MusicTab {
    const ALL: [MusicTab; 5] =
        [MusicTab::Home, MusicTab::Explore, MusicTab::NewReleases, MusicTab::Charts, MusicTab::Library];

    /// A guest has no account library.
    fn listed(guest: bool) -> Vec<MusicTab> {
        Self::ALL.into_iter().filter(|tab| !guest || *tab != MusicTab::Library).collect()
    }

    fn label(self) -> &'static str {
        t(match self {
            MusicTab::Home => Str::YtmusicTabHome,
            MusicTab::Explore => Str::YtmusicTabExplore,
            MusicTab::NewReleases => Str::YtmusicTabNewReleases,
            MusicTab::Charts => Str::YtmusicTabCharts,
            MusicTab::Library => Str::YtmusicTabLibrary,
        })
    }

    fn source(self) -> FeedSource {
        match self {
            MusicTab::Home => FeedSource::MusicHome,
            MusicTab::Explore => FeedSource::MusicExplore,
            MusicTab::NewReleases => FeedSource::MusicNewReleases,
            MusicTab::Charts => FeedSource::MusicCharts,
            MusicTab::Library => FeedSource::MusicLibrary,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum DiscoverTab {
    Home,
    Trending,
    Recommended,
    TopTracks,
    Account,
}

impl DiscoverTab {
    const ALL: [DiscoverTab; 5] = [
        DiscoverTab::Home,
        DiscoverTab::Trending,
        DiscoverTab::Recommended,
        DiscoverTab::TopTracks,
        DiscoverTab::Account,
    ];

    /// A guest has no account and keeps no YouTube play history: no Account, no Top tracks.
    fn listed(guest: bool) -> Vec<DiscoverTab> {
        Self::ALL
            .into_iter()
            .filter(|tab| !guest || !matches!(tab, DiscoverTab::Account | DiscoverTab::TopTracks))
            .collect()
    }

    /// The persisted id (the Compose `DiscoverTab.id`).
    fn id(self) -> i32 {
        match self {
            DiscoverTab::Home => 1,
            DiscoverTab::Trending => 2,
            DiscoverTab::Recommended => 3,
            DiscoverTab::TopTracks => 4,
            DiscoverTab::Account => 5,
        }
    }

    fn icon(self) -> Lucide {
        match self {
            DiscoverTab::Home => Lucide::House,
            DiscoverTab::Trending => Lucide::Music,
            DiscoverTab::Recommended => Lucide::Star,
            DiscoverTab::TopTracks => Lucide::TrendingUp,
            DiscoverTab::Account => Lucide::User,
        }
    }

    fn from_id(id: i32) -> Option<Self> {
        Self::ALL.into_iter().find(|tab| tab.id() == id)
    }

    fn label(self) -> &'static str {
        t(match self {
            DiscoverTab::Home => Str::TabHome,
            DiscoverTab::Trending => Str::TabMusic,
            DiscoverTab::Recommended => Str::TabRecommended,
            DiscoverTab::TopTracks => Str::TabTopTracks,
            DiscoverTab::Account => Str::TabAccountLibrary,
        })
    }

    fn source(self) -> FeedSource {
        match self {
            DiscoverTab::Home => FeedSource::Home,
            DiscoverTab::Trending => FeedSource::Trending,
            DiscoverTab::Recommended => FeedSource::Recommended,
            DiscoverTab::TopTracks => FeedSource::TopTracks,
            DiscoverTab::Account => FeedSource::Account,
        }
    }
}

/// Debug: `YMUSIC_DEV_KEYS=secondary-n,escape` presses those keys 4 s apart, through the window's
/// own key dispatch (down, then up: a focused button clicks on the release), to check shortcuts
/// without driving the real keyboard. Once per launch, from
/// whichever root comes up first.
pub fn dev_keys(window: &mut Window, cx: &mut App) {
    static STARTED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    let Ok(keys) = std::env::var("YMUSIC_DEV_KEYS") else { return };
    if !cfg!(debug_assertions) || STARTED.swap(true, std::sync::atomic::Ordering::Relaxed) {
        return;
    }
    let keys: Vec<&'static str> =
        keys.split(',').map(|key| &*Box::leak(key.trim().to_owned().into_boxed_str())).collect();
    let executor = cx.background_executor().clone();
    window
        .spawn(cx, async move |cx| {
            for key in keys {
                executor.timer(Duration::from_secs(4)).await;
                cx.update(|window, cx| match Keystroke::parse(key) {
                    Ok(keystroke) => {
                        window.dispatch_keystroke(keystroke.clone(), cx);
                        window.dispatch_event(PlatformInput::KeyUp(KeyUpEvent { keystroke }), cx);
                    }
                    Err(error) => eprintln!("YMUSIC_DEV_KEYS: {key}: {error}"),
                })
                .ok();
            }
        })
        .detach();
}

/// A sound dialog to open on the next frame (opening needs the window).
#[derive(Clone, Copy)]
enum SoundDialog {
    SpeedPitch,
    Equalizer,
}

/// A surface pushed over a section's root; each draws its own back bar.
enum Layer {
    Feed { view: Entity<FeedView>, title: SharedString },
    Search(Entity<SearchView>),
    LibrarySearch(Entity<LibrarySearchView>),
    Results { view: Entity<FeedView>, query: String },
    SignIn(Entity<SignInView>),
}

#[derive(Default)]
struct Stack {
    pushed: Vec<Layer>,
}

/// The shell, for app-level callbacks (the Dock's reopen) that have no window context.
pub struct ShellHandle(pub WeakEntity<Shell>);

impl Global for ShellHandle {}

pub struct Shell {
    section: Section,
    discover_tab: DiscoverTab,
    music_tab: MusicTab,
    roots: HashMap<FeedSource, Entity<FeedView>>,
    stacks: HashMap<Section, Stack>,
    /// The rail's state the user chose; cleared when a width breakpoint flips.
    rail_manual: Option<bool>,
    /// The (rail mode, expanded-by-width) the window last had.
    rail_auto: Option<(bool, bool)>,
    drawer_open: bool,
    /// Holds keyboard focus when nothing else does, so the shortcuts always reach the Shell.
    focus: FocusHandle,
    nav_focus: FocusList,
    tab_focus: FocusList,
    music_tab_focus: FocusList,
    chrome_focus: FocusList,
    music_tab_drag: DragRow,
    /// The last Esc that had nothing to close; a second one soon after goes back.
    idle_escape_at: Option<Instant>,
    playback: Entity<Playback>,
    mini_player: Entity<MiniPlayer>,
    now_playing: Entity<NowPlaying>,
    now_playing_open: bool,
    /// Now Playing as last drawn, and what held focus when it opened, which gets it back on close.
    now_playing_shown: bool,
    now_playing_opener: Option<WeakFocusHandle>,
    /// The page last drawn, by section, depth and tab: a new one drops the old page's scroll.
    shown_page: (i32, usize, i32, &'static str),
    library: Option<Entity<LibraryView>>,
    account: Option<Entity<AccountView>>,
    music_library: Option<Entity<AccountView>>,
    play_again: Option<Entity<PlayAgainView>>,
    downloads: Option<Entity<DownloadsView>>,
    settings: Option<Entity<SettingsView>>,
    feedback: Option<Entity<FeedbackView>>,
    /// Debug: open the sign-in dialog on the next frame.
    pending_signin: bool,
    /// The empty Home asked for the search field, which opens with the window at the next render.
    pending_search: bool,
    /// The graph's guest launch: no sign-in, account or YouTube play history surfaces.
    guest: bool,
    /// A sound dialog to open on the next frame.
    pending_sound: Option<SoundDialog>,
    /// A download dialog to open on the next frame (opening needs the window).
    pending_download: Option<DownloadTarget>,
    /// The video whose details dialog opens on the next frame.
    pending_details: Option<String>,
    tray: Option<crate::desktop::Tray>,
    /// The window's device pixels per logical pixel, as of the last frame.
    scale_factor: f32,
    window: AnyWindowHandle,
    dock_menu: crate::desktop::DockMenu,
    /// Where the Dock menu and the tray popup send their commands.
    commands: futures::channel::mpsc::UnboundedSender<crate::desktop::TrayCommand>,
    tray_popup: Option<WindowHandle<gpui_kit::base::Root>>,
    /// When the popup last closed itself: the tray click that took its focus must not reopen it.
    tray_popup_closed_at: Option<Instant>,
    /// The window was closed with a queue: only the tray icon is left.
    hidden_to_tray: bool,
    /// The pending write of the window's bounds; replaced on every resize or move.
    _save_bounds: Option<Task<()>>,
    _media_session: Entity<MediaSession>,
    _history: Entity<HistoryRecorder>,
    _sponsor_block: Entity<SponsorBlock>,
    _subscriptions: Vec<Subscription>,
}

impl Shell {
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let playback = cx.new(|cx| Playback::new(cx));
        let mini_player = cx.new(|cx| MiniPlayer::new(playback.clone(), window, cx));
        let sponsor_block = cx.new(|cx| SponsorBlock::new(playback.clone(), cx));
        let now_playing = cx.new(|cx| NowPlaying::new(playback.clone(), sponsor_block.clone(), window, cx));
        let media_session = cx.new(|cx| MediaSession::new(playback.clone(), cx));
        let history = cx.new(|cx| HistoryRecorder::new(playback.clone(), cx));
        let toggle = cx.subscribe(&mini_player, |shell, _, _: &ToggleNowPlaying, cx| {
            shell.now_playing_open = !shell.now_playing_open;
            cx.notify();
        });
        let now_playing_events = cx.subscribe(&now_playing, |shell, _, event: &NowPlayingEvent, cx| {
            match event {
                NowPlayingEvent::Close => shell.now_playing_open = false,
                NowPlayingEvent::Download(target) => shell.pending_download = Some(target.clone()),
                NowPlayingEvent::OpenSpeedPitch => shell.pending_sound = Some(SoundDialog::SpeedPitch),
                NowPlayingEvent::OpenEqualizer => shell.pending_sound = Some(SoundDialog::Equalizer),
                NowPlayingEvent::Details(video_id) => shell.pending_details = Some(video_id.clone()),
                NowPlayingEvent::OpenChannel { url, title } => {
                    let source = FeedSource::from_link(url).unwrap_or_else(|| FeedSource::Channel(url.clone()));
                    shell.push(source, Some(SharedString::from(title.clone())), cx);
                }
                NowPlayingEvent::LibraryGroup { album, name } => {
                    shell.select_section(Section::Library, cx);
                    let view = shell.library_view(cx);
                    let (album, name) = (*album, name.clone());
                    view.update(cx, |library, cx| match name {
                        Some(name) => {
                            let kind = if album { GroupKind::Album } else { GroupKind::Artist };
                            library.reveal_group(kind, name, cx)
                        }
                        None => library.select_tab_index(if album { 4 } else { 3 }, cx),
                    });
                }
                NowPlayingEvent::SignIn => {
                    shell.now_playing_open = false;
                    shell.pending_signin = true;
                }
            }
            cx.notify();
        });
        let bounds = cx.observe_window_bounds(window, |shell, window, cx| {
            let log = cx.global::<AppGraph>().log.fork("window");
            let executor = cx.background_executor().clone();
            shell._save_bounds = Some(cx.spawn_in(window, async move |_, cx| {
                executor.timer(crate::desktop::BOUNDS_SAVE_DELAY).await;
                cx.update(|window, cx| crate::desktop::save_bounds(window, log, cx)).ok();
            }));
        });
        let shell_handle = cx.entity().downgrade();
        window.on_window_should_close(cx, move |window, cx| {
            let Some(shell) = shell_handle.upgrade() else { return true };
            shell.update(cx, |shell, cx| shell.close_requested(window, cx))
        });
        cx.set_global(ShellHandle(cx.entity().downgrade()));
        let graph = cx.global::<AppGraph>();
        let tray = match crate::desktop::Tray::new(&graph.tokio, graph.log.fork("tray")) {
            Ok((tray, mut commands)) => {
                cx.spawn_in(window, async move |this, cx| {
                    use futures::StreamExt;
                    while let Some(command) = commands.next().await {
                        if this.update_in(cx, |shell, window, cx| shell.tray_command(command, window, cx)).is_err() {
                            break;
                        }
                    }
                })
                .detach();
                Some(tray)
            }
            Err(error) => {
                cx.global::<AppGraph>().log.warn("tray icon could not be created", Some(error.as_ref()));
                None
            }
        };
        let (dock_commands, mut dock_receiver) = futures::channel::mpsc::unbounded();
        let dock_menu = crate::desktop::DockMenu::new(dock_commands.clone(), cx);
        cx.spawn_in(window, async move |this, cx| {
            use futures::StreamExt;
            while let Some(command) = dock_receiver.next().await {
                if this.update_in(cx, |shell, window, cx| shell.tray_command(command, window, cx)).is_err() {
                    break;
                }
            }
        })
        .detach();
        let tray_state = cx.observe(&playback, |shell, _, cx| shell.update_tray(cx));
        Self::listen_incoming(window, cx);
        crate::app_update::start(window, cx);
        window.defer(cx, crate::app_update::show_upgrade_notice);
        crate::video_layer::forward_accessibility_focus(window);
        window.on_next_frame(|window, _| crate::video_layer::sync_accessibility_focus(window));
        // A window minimized to the tray can come back from the dock too.
        let activation = cx.observe_window_activation(window, |shell, window, cx| {
            if shell.hidden_to_tray && window.is_window_active() {
                shell.window_shown(cx);
            }
        });
        let visibility = cx.observe_window_visibility(window, |shell, visibility, window, cx| {
            let hidden = !visibility.is_visible() && crate::video_layer::window_out_of_sight(window);
            shell.playback.update(cx, |playback, cx| playback.set_window_hidden(hidden, cx));
        });
        let (settings, guest) = (cx.global::<AppGraph>().settings.clone(), cx.global::<AppGraph>().guest);
        let section = settings
            .int(LAST_SECTION_KEY, 1)
            .ok()
            .and_then(Section::from_id)
            .filter(|section| !guest || *section != Section::PlayAgain)
            .unwrap_or(Section::Discover);
        let discover_tab = settings
            .int(LAST_DISCOVER_TAB_KEY, 1)
            .ok()
            .and_then(DiscoverTab::from_id)
            .filter(|tab| DiscoverTab::listed(guest).contains(tab))
            .unwrap_or(DiscoverTab::Home);
        let focus = cx.focus_handle();
        crate::a11y::track_root(&focus, cx);
        cx.set_global(ScrollHost::default());
        // Focus on a control that left the screen would leave shortcuts, Tab and the arrows nowhere to go.
        let focus_lost = cx.on_focus_lost(window, |shell, window, cx| shell.focus.focus(window, cx));
        Self {
            playback,
            mini_player,
            now_playing,
            now_playing_open: false,
            now_playing_shown: false,
            now_playing_opener: None,
            shown_page: (0, 0, 0, ""),
            library: None,
            account: None,
            music_library: None,
            play_again: None,
            downloads: None,
            settings: None,
            feedback: None,
            pending_signin: false,
            pending_search: false,
            guest,
            pending_sound: None,
            pending_download: None,
            pending_details: None,
            _media_session: media_session,
            _history: history,
            _sponsor_block: sponsor_block,
            section,
            discover_tab,
            music_tab: MusicTab::Home,
            roots: HashMap::new(),
            stacks: HashMap::new(),
            rail_manual: None,
            rail_auto: None,
            drawer_open: false,
            focus,
            nav_focus: FocusList::new(None),
            tab_focus: FocusList::new(None),
            music_tab_focus: FocusList::new(None),
            chrome_focus: FocusList::new(None),
            music_tab_drag: DragRow::default(),
            idle_escape_at: None,
            tray,
            scale_factor: window.scale_factor(),
            window: window.window_handle(),
            dock_menu,
            commands: dock_commands,
            tray_popup: None,
            tray_popup_closed_at: None,
            hidden_to_tray: false,
            _save_bounds: None,
            _subscriptions: vec![toggle, now_playing_events, visibility, activation, bounds, tray_state, focus_lost],
        }
    }

    /// The close button: with music queued and a tray icon up the window goes to the tray, else the
    /// app quits. True when the window may close.
    pub(crate) fn close_requested(&mut self, window: &mut Window, cx: &mut Context<Self>) -> bool {
        // Without a tray icon a hidden window could never come back.
        let tray_up = self.tray.as_ref().is_some_and(|tray| tray.available());
        if !tray_up || self.playback.read(cx).queue.is_empty() {
            Self::quit(window, cx);
            return true;
        }
        self.hide_to_tray(window, cx);
        false
    }

    /// The window was closed with music still queued: it goes away, playback and the tray icon stay.
    fn hide_to_tray(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.hidden_to_tray = true;
        crate::desktop::set_dock_visible(false);
        // gpui cannot hide a window on Linux (`hide` is a no-op there), only minimize it.
        if cfg!(target_os = "linux") {
            window.minimize_window();
        } else {
            crate::desktop::hide_main_window(cx);
        }
        self.update_tray(cx);
    }

    pub fn show_window(&mut self, cx: &mut Context<Self>) {
        if cfg!(target_os = "linux") {
            // The compositor may refuse to raise the minimized window (GNOME on Wayland without a
            // fresh input serial), so it counts as back only once it is active.
            let window = self.window;
            // The caller may be inside this window's update; activating it has to wait for that.
            cx.defer(move |cx| {
                window.update(cx, |_, window, _| window.activate_window()).ok();
            });
            return;
        }
        crate::desktop::show_main_window();
        cx.activate(true);
        self.window_shown(cx);
    }

    fn window_shown(&mut self, cx: &mut Context<Self>) {
        if self.hidden_to_tray {
            self.hidden_to_tray = false;
            crate::desktop::set_dock_visible(true);
        }
        self.update_tray(cx);
    }

    fn update_tray(&mut self, cx: &mut Context<Self>) {
        let playback = self.playback.read(cx);
        let (has_queue, playing) = (!playback.queue.is_empty(), playback.is_playing());
        self.dock_menu.update(has_queue, playing, cx);
        let Some(tray) = self.tray.as_mut() else { return };
        if let Err(error) = tray.update(has_queue, playing, self.hidden_to_tray) {
            cx.global::<AppGraph>().log.warn("tray icon could not be updated", Some(error.as_ref()));
        }
    }

    fn quit(window: &Window, cx: &mut App) {
        let log = cx.global::<AppGraph>().log.fork("window");
        crate::desktop::save_bounds(window, log, cx);
        cx.quit();
    }

    fn tray_command(&mut self, command: crate::desktop::TrayCommand, window: &mut Window, cx: &mut Context<Self>) {
        use crate::desktop::TrayCommand;
        match command {
            TrayCommand::Popup(anchor) => self.toggle_tray_popup(anchor, cx),
            TrayCommand::Open => {
                self.close_tray_popup(cx);
                self.show_window(cx);
            }
            TrayCommand::TogglePlay => self.playback.update(cx, |playback, cx| playback.toggle(cx)),
            TrayCommand::Stop => {
                self.close_tray_popup(cx);
                self.playback.update(cx, |playback, cx| playback.clear_queue(cx));
                // Nothing left to play and no window: the listening session is over.
                if self.hidden_to_tray {
                    Self::quit(window, cx);
                }
            }
            TrayCommand::Quit => Self::quit(window, cx),
            TrayCommand::Lost => {
                if self.hidden_to_tray {
                    self.show_window(cx);
                }
            }
        }
    }

    /// Debug builds only: `YMUSIC_DEV_ROUTE` opens a surface at start (`trending`, `music`,
    /// `search:<query>`, `searchfilter:<n>:<query>` (the filter dialog once the results land; `n` > 0 first applies that option, counted across groups from 1), `open:<app link>`, `musictab:<n>`, `play:<youtube url>`, `nowplaying:<url>`, `library:<dir>`, `localplay:<dir>`, `localnowplaying:<dir>` (with Now Playing open), `libraryact:<a;b>` (`LibraryView::dev_action`), `details:<videoId>`, `addtoplaylist:<videoId>`, `feedback:message|send:<text>` (with `YMUSIC_DEV_FEEDBACK_ENVELOPE=<file>` the report is written there, not sent), `sleeptimer`, `signin-paste:<file>`, `devicelink`, `signout`, `protect` (the password step after a sign-in), `signin:paste`,
    /// `download:<FORMAT>:<videoId>` into `YMUSIC_DEV_DOWNLOAD_DIR` or the chosen folder), for
    /// screenshots and checks without driving the UI. `YMUSIC_DEV_CAPTIONS=1|<vssId>` turns captions on (with that track), `YMUSIC_DEV_QUALITY=<token>` picks a quality, `YMUSIC_DEV_TRAY_POPUP=1|hidden` opens the tray popup.
    pub fn apply_dev_route(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !cfg!(debug_assertions) {
            return;
        }
        match std::env::var("YMUSIC_DEV_CAPTIONS").ok().filter(|value| !value.is_empty()) {
            Some(track) if track != "1" => self.playback.update(cx, |playback, cx| playback.select_caption(track, cx)),
            Some(_) => self.playback.update(cx, |playback, cx| playback.set_captions(true, cx)),
            None => {}
        }
        if let Ok(quality) = std::env::var("YMUSIC_DEV_QUALITY") {
            self.playback.update(cx, |playback, cx| playback.set_quality(quality, cx));
        }
        if let Some(seconds) = std::env::var("YMUSIC_DEV_QUIT_AFTER").ok().and_then(|value| value.parse().ok()) {
            let executor = cx.background_executor().clone();
            cx.spawn(async move |_, cx| {
                executor.timer(std::time::Duration::from_secs(seconds)).await;
                cx.update(|cx| cx.quit());
            })
            .detach();
        }
        // `YMUSIC_DEV_CLOSE_AFTER=<s>` presses the window's close button then.
        if let Some(seconds) = std::env::var("YMUSIC_DEV_CLOSE_AFTER").ok().and_then(|value| value.parse().ok()) {
            let executor = cx.background_executor().clone();
            cx.spawn_in(window, async move |this, cx| {
                executor.timer(std::time::Duration::from_secs(seconds)).await;
                let close = this.update_in(cx, |shell, window, cx| shell.close_requested(window, cx));
                if close.unwrap_or(false) {
                    cx.update(|window, _| window.remove_window()).ok();
                }
            })
            .detach();
        }
        dev_keys(window, cx);
        crate::a11y::dev_dump_tree(window, cx);
        // `YMUSIC_DEV_DRAG=x0,y,x1` presses at (x0, y) after 8 s and drags to x1 (`x0,y0,x1,y1`: to
        // (x1, y1)) and releases a second later, to check dragging without driving the real mouse.
        if let Some((from, y, to, to_y)) = std::env::var("YMUSIC_DEV_DRAG").ok().and_then(|spec| {
            let values: Vec<f32> = spec.split(',').filter_map(|value| value.trim().parse().ok()).collect();
            match values[..] {
                [from, y, to] => Some((from, y, to, y)),
                [from, y, to, to_y] => Some((from, y, to, to_y)),
                _ => None,
            }
        }) {
            let executor = cx.background_executor().clone();
            cx.spawn_in(window, async move |_, cx| {
                executor.timer(Duration::from_secs(8)).await;
                let press = MouseDownEvent {
                    button: MouseButton::Left,
                    position: point(px(from), px(y)),
                    modifiers: Modifiers::default(),
                    click_count: 1,
                    first_mouse: false,
                };
                cx.update(|window, cx| window.dispatch_event(PlatformInput::MouseDown(press), cx)).ok();
                for step in 1..=20 {
                    executor.timer(Duration::from_millis(16)).await;
                    let motion = MouseMoveEvent {
                        position: point(
                            px(from + (to - from) * step as f32 / 20.),
                            px(y + (to_y - y) * step as f32 / 20.),
                        ),
                        pressed_button: Some(MouseButton::Left),
                        modifiers: Modifiers::default(),
                    };
                    cx.update(|window, cx| window.dispatch_event(PlatformInput::MouseMove(motion), cx)).ok();
                }
                // Held a moment before the release, so edge autoscroll has time to run.
                executor.timer(Duration::from_secs(1)).await;
                let release = MouseUpEvent {
                    button: MouseButton::Left,
                    position: point(px(to), px(to_y)),
                    modifiers: Modifiers::default(),
                    click_count: 1,
                };
                cx.update(|window, cx| window.dispatch_event(PlatformInput::MouseUp(release), cx)).ok();
            })
            .detach();
        }
        if let Ok(mode) = std::env::var("YMUSIC_DEV_TRAY_POPUP") {
            self.dev_tray_popup(mode, window, cx);
        }
        if let Ok(spec) = std::env::var("YMUSIC_DEV_PLAYER") {
            self.dev_player(&spec, cx);
        }
        let Ok(route) = std::env::var("YMUSIC_DEV_ROUTE") else { return };
        match route.split_once(':') {
            Some(("search", query)) => self.show_results(query.to_owned(), cx),
            Some(("searchfilter", spec)) => self.dev_search_filter(spec, window, cx),
            Some(("musictab", index)) => {
                self.section = Section::YtMusic;
                let tabs = MusicTab::listed(self.guest);
                self.music_tab = tabs[index.parse::<usize>().unwrap_or(0).min(tabs.len() - 1)];
            }
            Some(("open", link)) => {
                if let Some(source) = FeedSource::from_link(link) {
                    self.push(source, None, cx);
                }
            }
            Some(("play", url)) => self.play_link(url.to_owned(), cx),
            Some((route @ ("library" | "localplay" | "localnowplaying"), path)) => {
                self.section = Section::Library;
                self.now_playing_open = route == "localnowplaying";
                let view = self.library_view(cx);
                let play_first = route != "library";
                view.update(cx, |library, cx| library.scan_path(path.into(), play_first, cx));
            }
            Some(("librarytab", index)) => {
                self.section = Section::Library;
                let view = self.library_view(cx);
                let index = index.parse().unwrap_or(0);
                view.update(cx, |library, cx| library.select_tab_index(index, cx));
            }
            Some(("libraryact", actions)) => {
                self.section = Section::Library;
                let view = self.library_view(cx);
                view.update(cx, |library, cx| library.queue_dev_actions(actions, cx));
            }
            Some(("signin-paste", path)) => self.dev_sign_in_paste(path.into(), cx),
            Some(("playlistdialog", spec)) => {
                let (dialog, link) = spec.split_once(':').unwrap_or(("download", spec));
                if let Some(source) = FeedSource::from_link(link) {
                    let view = self.feed_view(source, None, cx);
                    self.push_layer(Layer::Feed { view: view.clone(), title: t(Str::A11yKindPlaylist).into() }, cx);
                    let (local, executor) = (dialog == "local", cx.background_executor().clone());
                    cx.spawn_in(window, async move |_, cx| {
                        for _ in 0..60 {
                            executor.timer(Duration::from_millis(500)).await;
                            let opened =
                                view.update_in(cx, |view, window, cx| view.open_playlist_dialog(local, window, cx));
                            if opened.unwrap_or(true) {
                                return;
                            }
                        }
                    })
                    .detach();
                }
            }
            Some(("downloadsaction", spec)) => {
                self.section = Section::Downloads;
                let (action, video_id) = spec.split_once(':').unwrap_or((spec, ""));
                let (action, video_id) = (action.to_owned(), video_id.to_owned());
                let (view, executor) = (self.downloads_view(cx), cx.background_executor().clone());
                cx.spawn_in(window, async move |_, cx| {
                    for _ in 0..20 {
                        executor.timer(Duration::from_millis(500)).await;
                        let done =
                            view.update_in(cx, |view, window, cx| view.dev_action(&action, &video_id, window, cx));
                        if done.unwrap_or(true) {
                            return;
                        }
                    }
                })
                .detach();
            }
            Some(("quickdownload", video_id)) => {
                self.section = Section::Downloads;
                let target = DownloadTarget {
                    video_id: video_id.to_owned(),
                    title: video_id.to_owned(),
                    artist: None,
                    thumbnail_url: None,
                };
                window.defer(cx, move |window, cx| crate::quick_download::launch(target, window, cx));
            }
            _ if route == "quickpreset" => {
                self.section = Section::Settings;
                window.defer(cx, crate::quick_download::QuickPresetEditor::open);
            }
            Some(("downloaddialog", video_id)) => {
                self.pending_download = Some(DownloadTarget {
                    video_id: video_id.to_owned(),
                    title: video_id.to_owned(),
                    artist: None,
                    thumbnail_url: Some(format!("https://i.ytimg.com/vi/{video_id}/hqdefault.jpg")),
                });
            }
            Some(("download", spec)) => {
                // `YMUSIC_DEV_DOWNLOAD_LIBRARY_TAB=<n>` watches that Library tab instead of the queue.
                match std::env::var("YMUSIC_DEV_DOWNLOAD_LIBRARY_TAB").ok().and_then(|tab| tab.parse().ok()) {
                    Some(index) => {
                        self.section = Section::Library;
                        let view = self.library_view(cx);
                        view.update(cx, |library, cx| library.select_tab_index(index, cx));
                    }
                    None => self.section = Section::Downloads,
                }
                self.dev_download(spec, cx);
            }
            _ if route == "devicelink" => self.dev_device_link(cx),
            _ if route == "signout" => self.dev_sign_out(cx),
            Some(("nowplaying", url)) => {
                self.now_playing_open = true;
                self.play_link(url.to_owned(), cx);
            }
            _ if route == "trending" => self.discover_tab = DiscoverTab::Trending,
            Some(("details", video_id)) => self.pending_details = Some(video_id.to_owned()),
            Some(("addtoplaylist", video_id)) => {
                let video_id = video_id.to_owned();
                window.defer(cx, move |window, cx| crate::yt_playlist::AddToPlaylist::open(video_id, window, cx));
            }
            Some(("discovertab", index)) => {
                self.section = Section::Discover;
                let tabs = DiscoverTab::listed(self.guest);
                self.discover_tab = tabs[index.parse::<usize>().unwrap_or(0).min(tabs.len() - 1)];
            }
            _ if route == "searchinput" => self.open_search(String::new(), window, cx),
            _ if route == "drawer" => self.drawer_open = true,
            _ if route == "music" => self.section = Section::YtMusic,
            _ if route == "playagain" => self.section = Section::PlayAgain,
            _ if route == "downloads" => self.section = Section::Downloads,
            _ if route == "settings" => self.section = Section::Settings,
            Some(("settings", dialog)) => {
                self.section = Section::Settings;
                let view = self.settings_view(cx);
                let dialog = dialog.to_owned();
                window.defer(cx, move |window, cx| match dialog.as_str() {
                    "accent" => crate::accent::AccentPicker::open("", |_, _| {}, window, cx),
                    "captions" => crate::caption_style::CaptionStyleDialog::open(window, cx),
                    "region" => crate::settings_lists::RegionPicker::open(view, window, cx),
                    "licenses" => crate::settings_lists::Licenses::open(window, cx),
                    other => match other.strip_prefix("playercache=").and_then(|mb| mb.parse().ok()) {
                        Some(max_mb) => view.update(cx, |view, cx| view.set_cache_max(max_mb, cx)),
                        None => view.read(cx).scroll_to_end(),
                    },
                });
            }
            _ if route == "shortcuts" => window.defer(cx, crate::shortcuts_help::ShortcutsHelp::open),
            _ if route == "checkupdate" => window.defer(cx, crate::app_update::CheckUpdateDialog::open),
            _ if route == "feedback" => self.section = Section::Feedback,
            Some(("feedback", spec)) => {
                self.section = Section::Feedback;
                let (mode, text) = spec.split_once(':').unwrap_or((spec, ""));
                let (send, text) = (mode == "send", text.to_owned());
                let view = self.feedback.get_or_insert_with(|| cx.new(|_| FeedbackView::new())).clone();
                window.defer(cx, move |window, cx| {
                    view.update(cx, |view, cx| view.dev_message(text, send, window, cx));
                });
            }
            _ if route == "speedpitch" => self.pending_sound = Some(SoundDialog::SpeedPitch),
            _ if route == "equalizer" => self.pending_sound = Some(SoundDialog::Equalizer),
            _ if route == "sleeptimer" => {
                let playback = self.playback.clone();
                window.defer(cx, move |window, cx| crate::sleep_timer::SleepTimerDialog::open(playback, window, cx));
            }
            _ if route == "signin" => {
                self.section = Section::Settings;
                self.pending_signin = true;
            }
            _ if route == "protect" || route == "signin:paste" => {
                self.section = Section::Settings;
                self.open_sign_in(window, cx);
                if let Some(Layer::SignIn(view)) = self.stacks.get(&self.section).and_then(|stack| stack.pushed.last())
                {
                    let protect = route == "protect";
                    view.update(cx, |view, _| if protect { view.dev_protect() } else { view.dev_paste() });
                }
            }
            Some(("playagain", "resume")) => {
                self.section = Section::PlayAgain;
                let view = self.play_again_view(cx);
                view.update(cx, |view, cx| view.resume_newest(cx));
            }
            _ => {}
        }
    }

    fn settings_view(&mut self, cx: &mut Context<Self>) -> Entity<SettingsView> {
        if let Some(view) = &self.settings {
            return view.clone();
        }
        let playback = self.playback.clone();
        let view = cx.new(|cx| SettingsView::new(playback, cx));
        let locale = cx.subscribe(&view, |shell, _, _: &LocaleChanged, cx| {
            for root in shell.roots.values() {
                root.update(cx, |root, cx| root.reload(cx));
            }
        });
        let account = cx.subscribe(&view, |shell, _, _: &AccountChanged, cx| shell.account_changed(cx));
        self._subscriptions.extend([locale, account]);
        self.settings = Some(view.clone());
        view
    }

    fn downloads_view(&mut self, cx: &mut Context<Self>) -> Entity<DownloadsView> {
        if let Some(view) = &self.downloads {
            return view.clone();
        }
        let service = cx.global::<AppGraph>().downloads.clone();
        let view = cx.new(|cx| DownloadsView::new(service, cx));
        let play = cx.subscribe(&view, |shell, _, event: &PlayLocal, cx| {
            let (items, index) = (event.items.clone(), event.index);
            shell.playback.update(cx, |playback, cx| playback.play_queue(items, index, cx));
        });
        let reveal = cx.subscribe(&view, |shell, _, event: &RevealInLibrary, cx| {
            shell.stacks.entry(Section::Library).or_default().pushed.clear();
            shell.select_section(Section::Library, cx);
            let library = shell.library_view(cx);
            let file = event.file.clone();
            library.update(cx, |library, cx| library.reveal_file(file, cx));
        });
        self._subscriptions.extend([play, reveal]);
        self.downloads = Some(view.clone());
        view
    }

    fn play_again_view(&mut self, cx: &mut Context<Self>) -> Entity<PlayAgainView> {
        if let Some(view) = &self.play_again {
            return view.clone();
        }
        let history = cx.global::<AppGraph>().history.clone();
        let view = cx.new(|cx| PlayAgainView::new(history, cx));
        let resume = cx.subscribe(&view, |shell, _, event: &ResumeQueue, cx| {
            let (queue_id, items, index, start_ms) =
                (event.queue_id.clone(), event.items.clone(), event.index, event.start_ms);
            shell.playback.update(cx, |playback, cx| playback.resume_queue(queue_id, items, index, start_ms, cx));
        });
        self._subscriptions.push(resume);
        self.play_again = Some(view.clone());
        view
    }

    fn library_view(&mut self, cx: &mut Context<Self>) -> Entity<LibraryView> {
        if let Some(view) = &self.library {
            return view.clone();
        }
        let store = cx.global::<AppGraph>().media.clone();
        let view = cx.new(|cx| LibraryView::new(store, cx));
        let play = cx.subscribe(&view, |shell, _, event: &PlayLocal, cx| {
            let (items, index) = (event.items.clone(), event.index);
            shell.playback.update(cx, |playback, cx| playback.play_queue(items, index, cx));
        });
        let queue =
            cx.subscribe(&view, |shell, _, event: &QueueLocal, cx| {
                let (item, next) = (event.item.clone(), event.next);
                shell.playback.update(cx, |playback, cx| {
                    if next { playback.queue_next(item, cx) } else { playback.queue_last(item, cx) }
                });
            });
        self._subscriptions.extend([play, queue]);
        self.library = Some(view.clone());
        view
    }

    /// Debug route: signs in with a cookie paste read from a file; the outcome goes to the log.
    fn dev_sign_in_paste(&mut self, path: std::path::PathBuf, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (jar, engines, log) = (graph.cookies.clone(), graph.engines.clone(), graph.log.fork("signin"));
        let done = on_core(cx, async move {
            let text = tokio::fs::read_to_string(&path).await.unwrap_or_default();
            let cookies = ym_signin::parse_cookie_paste(&text);
            log.info(&format!("paste parsed {} cookies", cookies.len()));
            match crate::signin::sign_in_with_cookies(cookies, jar, engines, &log).await {
                Ok(account) => log.info(&format!("signed in as {}", account.name.unwrap_or_default())),
                Err(error) => log.warn("sign-in failed", Some(&error)),
            }
        });
        cx.spawn(async move |this, cx| {
            done.await;
            this.update(cx, |shell, cx| shell.account_changed(cx)).ok();
        })
        .detach();
    }

    /// Debug: `speed=2,pitch=1,preset=1,loop=One,shuffle=1,sleep=<seconds>,video=0,boot=1` applied at start;
    /// `seek=<after s>@<to s>` (repeatable) seeks once that many seconds have passed;
    /// `speedpitch=<s>` / `equalizer=<s>` open that dialog after `s` seconds.
    fn dev_player(&mut self, spec: &str, cx: &mut Context<Self>) {
        let mut effects = self.playback.read(cx).effects;
        for (key, value) in spec.split(',').filter_map(|pair| pair.split_once('=')) {
            let number = value.parse::<f32>().ok();
            match key {
                "speed" => effects.speed = number.unwrap_or(effects.speed),
                "pitch" => effects.pitch = number.unwrap_or(effects.pitch),
                "preset" => {
                    if let Some((_, bands)) =
                        value.parse::<usize>().ok().and_then(|index| crate::audio_effects::EQ_PRESETS.get(index))
                    {
                        effects.tone.eq_db = *bands;
                    }
                }
                "loop" => {
                    let mode = crate::queue_nav::LoopMode::from_name(value);
                    self.playback.update(cx, |playback, cx| playback.set_loop_mode(mode, cx));
                }
                "shuffle" => self.playback.update(cx, |playback, cx| playback.set_shuffle(value == "1", cx)),
                "video" => self.playback.update(cx, |playback, cx| playback.set_video(value != "0", cx)),
                "speedpitch" | "equalizer" => {
                    let dialog = if key == "equalizer" { SoundDialog::Equalizer } else { SoundDialog::SpeedPitch };
                    let seconds = value.parse().unwrap_or(0);
                    let executor = cx.background_executor().clone();
                    cx.spawn(async move |shell, cx| {
                        executor.timer(std::time::Duration::from_secs(seconds)).await;
                        shell
                            .update(cx, |shell, cx| {
                                shell.pending_sound = Some(dialog);
                                cx.notify();
                            })
                            .ok();
                    })
                    .detach();
                }
                "boot" => {
                    let graph = cx.global::<AppGraph>();
                    let (sessions, log) = (graph.youtube.clone(), graph.log.fork("dev-player"));
                    graph.tokio.spawn(async move {
                        match sessions.get().await {
                            Ok(_) => log.info("youtube session booted"),
                            Err(error) => log.warn("youtube session boot failed", Some(&error)),
                        }
                    });
                }
                "reload" => {
                    let seconds = value.parse().unwrap_or(0);
                    let executor = cx.background_executor().clone();
                    cx.spawn(async move |shell, cx| {
                        executor.timer(std::time::Duration::from_secs(seconds)).await;
                        shell
                            .update(cx, |shell, cx| shell.playback.update(cx, |playback, cx| playback.reload(cx)))
                            .ok();
                    })
                    .detach();
                }
                "seek" => {
                    let Some((after, to)) = value.split_once('@') else { continue };
                    let (after, to) = (after.parse().unwrap_or(0), to.parse::<i64>().unwrap_or(0));
                    let executor = cx.background_executor().clone();
                    cx.spawn(async move |shell, cx| {
                        executor.timer(std::time::Duration::from_secs(after)).await;
                        shell
                            .update(cx, |shell, cx| {
                                shell.playback.update(cx, |playback, cx| playback.seek(to * 1000, cx))
                            })
                            .ok();
                    })
                    .detach();
                }
                "sleep" => {
                    let duration = value.parse().ok().map(std::time::Duration::from_secs);
                    self.playback.update(cx, |playback, cx| playback.set_sleep_timer(duration, cx));
                }
                _ => {}
            }
        }
        self.playback.update(cx, |playback, cx| playback.set_effects(effects, cx));
    }

    /// Debug route: probes and downloads one video, logging every queue change. A
    /// `YMUSIC_DEV_DOWNLOAD_DIR` is added to the Library as a picked download folder is.
    fn dev_download(&mut self, spec: &str, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (downloads, settings, media, log) =
            (graph.downloads.clone(), graph.settings.clone(), graph.media.clone(), graph.log.fork("dev-download"));
        let (format, video_id) = spec.split_once(':').unwrap_or(("M4A", spec));
        let Some(format) = ym_download::DownloadFormat::from_name(format) else {
            log.warn(&format!("unknown download format {format}"), None);
            return;
        };
        let video_id = video_id.to_owned();
        graph.tokio.spawn(async move {
            if let Some(dir) = std::env::var_os("YMUSIC_DEV_DOWNLOAD_DIR")
                && let Err(error) = settings.put_raw(
                    crate::downloads::DOWNLOAD_LOCATION_KEY,
                    crate::downloads::folder_uri(std::path::Path::new(&dir)),
                )
            {
                log.warn(&format!("the dev download folder could not be saved: {error}"), None);
            }
            if let Some(dir) = std::env::var_os("YMUSIC_DEV_DOWNLOAD_DIR").map(std::path::PathBuf::from) {
                let (media, register_log) = (media.clone(), log.clone());
                let registered =
                    tokio::task::spawn_blocking(move || {
                        match crate::downloads::register_download_folder(&media, &dir) {
                            Ok(()) => crate::downloads::scan_download_folder(&media, &dir, &register_log),
                            Err(error) => register_log
                                .warn("the dev download folder could not be added to the Library", Some(&error)),
                        }
                    });
                if let Err(error) = registered.await {
                    log.warn("the dev download folder could not be added to the Library", Some(&error));
                }
            }
            let mut changes = downloads.subscribe();
            match downloads.probe(&video_id).await {
                Ok(options) => log.info(&format!(
                    "options: {}ms, {} video heights, audio {:?}, {} captions",
                    options.duration_ms,
                    options.video.len(),
                    options.audio.iter().map(|option| option.format).collect::<Vec<_>>(),
                    options.captions.len()
                )),
                Err(error) => log.warn("download probe failed", Some(&error)),
            }
            let id = downloads.enqueue(ym_download::DownloadRequest {
                video_id: video_id.clone(),
                title: video_id.clone(),
                artist: None,
                thumbnail_url: None,
                format,
                requested_height: format.is_video().then_some(360),
                file_name: None,
                audio_track_id: None,
                captions: None,
                audio_language: None,
                caption_language: None,
                audio_quality: ym_download::AudioQuality::Best,
            });
            while let Ok(change) = changes.recv().await {
                match change {
                    ym_download::DownloadChange::ProgressChanged { .. } => {}
                    ym_download::DownloadChange::Completed { id: done, file_uri, bytes } if done == id => {
                        log.info(&format!("download completed: {file_uri} ({bytes} bytes)"));
                        return;
                    }
                    ym_download::DownloadChange::StateChanged { id: changed, state, error } if changed == id => {
                        log.info(&format!("download {state:?} {error:?}"));
                        if state == ym_download::DownloadState::Failed {
                            return;
                        }
                    }
                    _ => {}
                }
            }
        });
    }

    /// Debug route: opens a device link and logs the QR payload and code, then waits for the phone.
    fn dev_device_link(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (service, jar, engines, log) =
            (graph.remote.clone(), graph.cookies.clone(), graph.engines.clone(), graph.log.fork("devicelink"));
        graph.tokio.spawn(async move {
            match crate::signin::start_device_link(&service).await {
                Ok(offer) => {
                    log.info(&format!("scan {} and confirm {}", offer.qr_payload(), offer.confirmation_code()));
                    match crate::signin::await_device_link(service, offer, jar, engines, &log).await {
                        Ok(account) => log.info(&format!("signed in as {}", account.name.unwrap_or_default())),
                        Err(error) => log.warn("device link failed", Some(&error)),
                    }
                }
                Err(error) => log.warn("device link unavailable", Some(&error)),
            }
        });
    }

    fn dev_sign_out(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (jar, engines, log) = (graph.cookies.clone(), graph.engines.clone(), graph.log.fork("signout"));
        let done = on_core(cx, async move {
            let result = crate::signin::sign_out(jar, engines, &log).await;
            match &result {
                Ok(()) => log.info("signed out"),
                Err(error) => log.warn("sign-out failed", Some(error)),
            }
            result
        });
        cx.spawn(async move |this, cx| {
            done.await;
            this.update(cx, |shell, cx| shell.account_changed(cx)).ok();
        })
        .detach();
    }

    /// Resolves a shared YouTube link and plays it when it names a video.
    fn play_link(&mut self, url: String, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("link"));
        let resolve = on_core(cx, async move { engine.resolve_link(&url, &log).await });
        cx.spawn(async move |this, cx| {
            if let Some(Ok(Some(ym_engine::yt::YtLinkTarget::Video { item, .. }))) = resolve.await {
                this.update(cx, |shell, cx| {
                    shell.playback.update(cx, |playback, cx| playback.play_feed(*item, None, cx))
                })
                .ok();
            }
        })
        .detach();
    }

    fn feed_view(
        &mut self,
        source: FeedSource,
        title: Option<SharedString>,
        cx: &mut Context<Self>,
    ) -> Entity<FeedView> {
        let view = cx.new(|cx| FeedView::new(source, title, cx));
        let navigate = cx.subscribe(&view, |shell, _, event: &Navigate, cx| {
            shell.push(event.source.clone(), event.title.clone(), cx);
        });
        let search = cx.subscribe(&view, |shell, _, _: &RequestSearch, cx| {
            shell.pending_search = true;
            cx.notify();
        });
        let play = cx.subscribe(&view, |shell, _, event: &Play, cx| {
            let (item, queue, shuffle) = (event.item.clone(), event.queue.clone(), event.shuffle);
            shell.playback.update(cx, |playback, cx| {
                playback.play_feed(item, queue, cx);
                if shuffle {
                    playback.set_shuffle(true, cx);
                }
            });
        });
        let action = cx.subscribe(&view, |shell, _, event: &ItemAction, cx| {
            let item = event.item.clone();
            match event.action {
                FeedAction::QueueNext => {
                    shell.playback.update(cx, |playback, cx| playback.queue_next(QueueItem::from_feed(&item), cx))
                }
                FeedAction::QueueLast => {
                    shell.playback.update(cx, |playback, cx| playback.queue_last(QueueItem::from_feed(&item), cx))
                }
                FeedAction::Radio => shell.playback.update(cx, |playback, cx| playback.play_feed(item, None, cx)),
                FeedAction::Download => {
                    shell.pending_download = Some(DownloadTarget {
                        video_id: item.id.clone(),
                        title: item.title.clone(),
                        artist: item.channel_title.clone().or_else(|| item.subtitle_line1.clone()),
                        thumbnail_url: item.thumbs.last().map(|thumb| {
                            if thumb.url.starts_with("//") { format!("https:{}", thumb.url) } else { thumb.url.clone() }
                        }),
                    });
                    cx.notify();
                }
            }
        });
        self._subscriptions.extend([navigate, search, play, action]);
        view
    }

    fn root(&mut self, source: FeedSource, cx: &mut Context<Self>) -> Entity<FeedView> {
        if let Some(view) = self.roots.get(&source) {
            return view.clone();
        }
        let view = self.feed_view(source.clone(), None, cx);
        self.roots.insert(source, view.clone());
        view
    }

    /// The feed on screen: the top layer's, else the section root's.
    fn visible_feed(&self) -> Option<Entity<FeedView>> {
        match self.stacks.get(&self.section).and_then(|stack| stack.pushed.last()) {
            Some(Layer::Feed { view, .. } | Layer::Results { view, .. }) => Some(view.clone()),
            Some(_) => None,
            None => match self.section {
                Section::Discover => self.roots.get(&self.discover_tab.source()).cloned(),
                Section::YtMusic => self.roots.get(&self.music_tab.source()).cloned(),
                _ => None,
            },
        }
    }

    fn push_layer(&mut self, layer: Layer, cx: &mut Context<Self>) {
        self.now_playing_open = false;
        self.stacks.entry(self.section).or_default().pushed.push(layer);
        cx.notify();
    }

    fn push(&mut self, source: FeedSource, title: Option<SharedString>, cx: &mut Context<Self>) {
        let title = title.unwrap_or_else(|| match source {
            FeedSource::Playlist(_) | FeedSource::MusicBrowse(_) => t(Str::A11yKindPlaylist).into(),
            _ => SharedString::default(),
        });
        let view = self.feed_view(source, None, cx);
        self.push_layer(Layer::Feed { view, title }, cx);
    }

    fn back(&mut self, cx: &mut Context<Self>) {
        if let Some(Layer::LibrarySearch(view)) = self.stacks.get(&self.section).and_then(|stack| stack.pushed.last())
            && view.clone().update(cx, |view, cx| view.back(cx))
        {
            return;
        }
        if self.stacks.entry(self.section).or_default().pushed.pop().is_some() {
            cx.notify();
        } else if self.section == Section::Library
            && let Some(library) = self.library.clone()
        {
            library.update(cx, |library, cx| library.back(cx));
        }
    }

    fn open_sign_in(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if matches!(self.stacks.get(&self.section).and_then(|stack| stack.pushed.last()), Some(Layer::SignIn(_))) {
            return;
        }
        let view = cx.new(|cx| SignInView::new(window, cx));
        let events = cx.subscribe(&view, |shell, view, event: &SignInEvent, cx| {
            for stack in shell.stacks.values_mut() {
                stack.pushed.retain(|layer| !matches!(layer, Layer::SignIn(open) if *open == view));
            }
            if matches!(event, SignInEvent::Done) {
                shell.account_changed(cx);
            }
            cx.notify();
        });
        self._subscriptions.push(events);
        self.push_layer(Layer::SignIn(view), cx);
    }

    /// Everything that shows who is signed in re-checks, and every feed reloads under the new
    /// identity (the Account and YT Music Library feeds reload through their views' re-check).
    fn account_changed(&mut self, cx: &mut Context<Self>) {
        for (source, root) in &self.roots {
            if !matches!(source, FeedSource::Account | FeedSource::MusicLibrary) {
                root.update(cx, |root, cx| root.reload(cx));
            }
        }
        for view in [self.account.clone(), self.music_library.clone()].into_iter().flatten() {
            view.update(cx, |view, cx| view.refresh(cx));
        }
        if let Some(settings) = &self.settings {
            settings.update(cx, |settings, cx| settings.refresh_account(cx));
        }
        if let Some(downloads) = &self.downloads {
            downloads.update(cx, |downloads, _| downloads.signed_in());
        }
    }

    /// The section's own search: YouTube on Discover, YouTube Music on YT Music, local files on Library.
    fn open_search(&mut self, query: String, window: &mut Window, cx: &mut Context<Self>) {
        let mode = match self.section {
            Section::Library => return self.open_library_search(window, cx),
            Section::YtMusic => SearchMode::Music,
            _ => SearchMode::YouTube,
        };
        let view = cx.new(|cx| SearchView::new(&query, mode, window, cx));
        let events = cx.subscribe(&view, |shell, _, event: &SearchEvent, cx| match event {
            SearchEvent::Submit(query) => shell.show_results(query.clone(), cx),
            SearchEvent::Back => shell.back(cx),
        });
        self._subscriptions.push(events);
        self.push_layer(Layer::Search(view), cx);
    }

    fn open_library_search(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let store = cx.global::<AppGraph>().media.clone();
        let view = cx.new(|cx| LibrarySearchView::new(store, window, cx));
        let events = cx.subscribe(&view, |shell, _, event: &LibrarySearchEvent, cx| match event {
            LibrarySearchEvent::Back => shell.back(cx),
            LibrarySearchEvent::SearchOnline(query) => {
                shell.select_section(Section::Discover, cx);
                shell.show_results(query.clone(), cx);
            }
            LibrarySearchEvent::GoTo(kind, name) => {
                shell.back(cx);
                let (kind, name) = (*kind, name.clone());
                shell.library_view(cx).update(cx, |library, cx| library.reveal_group(kind, name, cx));
            }
            LibrarySearchEvent::ShowInFolder(bucket_id) => {
                shell.back(cx);
                let bucket_id = *bucket_id;
                shell.library_view(cx).update(cx, |library, cx| library.reveal_folder(bucket_id, cx));
            }
        });
        let play = cx.subscribe(&view, |shell, _, event: &PlayLocal, cx| {
            let (items, index) = (event.items.clone(), event.index);
            shell.playback.update(cx, |playback, cx| playback.play_queue(items, index, cx));
        });
        let queue =
            cx.subscribe(&view, |shell, _, event: &QueueLocal, cx| {
                let (item, next) = (event.item.clone(), event.next);
                shell.playback.update(cx, |playback, cx| {
                    if next { playback.queue_next(item, cx) } else { playback.queue_last(item, cx) }
                });
            });
        self._subscriptions.extend([events, play, queue]);
        self.push_layer(Layer::LibrarySearch(view), cx);
    }

    fn dev_search_filter(&mut self, spec: &str, window: &mut Window, cx: &mut Context<Self>) {
        let (option, query) = spec.split_once(':').unwrap_or(("0", spec));
        let option = option.parse::<usize>().unwrap_or(0);
        self.show_results(query.to_owned(), cx);
        let Some(Layer::Results { view, .. }) = self.stacks.get(&self.section).and_then(|stack| stack.pushed.last())
        else {
            return;
        };
        let (view, executor) = (view.clone(), cx.background_executor().clone());
        cx.spawn_in(window, async move |_, cx| {
            let wait_for_groups = async |cx: &mut AsyncWindowContext| {
                for _ in 0..60 {
                    executor.timer(Duration::from_millis(500)).await;
                    if view.read_with(cx, |view, _| !view.filter_groups().is_empty()) {
                        return;
                    }
                }
            };
            wait_for_groups(cx).await;
            if option > 0 {
                let params = view.read_with(cx, |view, _| {
                    view.filter_groups().iter().flat_map(|group| &group.options).nth(option - 1)?.params.clone()
                });
                if let Some(params) = params {
                    view.update(cx, |view, cx| view.apply_filter(params, cx));
                    executor.timer(Duration::from_millis(500)).await;
                    wait_for_groups(cx).await;
                }
            }
            cx.update(|window, cx| open_search_filter(&view, window, cx)).ok();
        })
        .detach();
    }

    /// Results replace the search input they came from, and search the section's service.
    fn show_results(&mut self, query: String, cx: &mut Context<Self>) {
        let stack = self.stacks.entry(self.section).or_default();
        if matches!(stack.pushed.last(), Some(Layer::Search(_))) {
            stack.pushed.pop();
        }
        let source = if self.section == Section::YtMusic {
            FeedSource::MusicSearch(query.clone(), None)
        } else {
            FeedSource::Search(query.clone(), None)
        };
        let view = self.feed_view(source, None, cx);
        self.push_layer(Layer::Results { view, query }, cx);
    }

    fn select_section(&mut self, section: Section, cx: &mut Context<Self>) {
        if section == Section::Telegram {
            let url =
                crate::feedback_view::link(cx.global::<AppGraph>(), "telegramUrl", crate::feedback_view::TELEGRAM_URL);
            cx.open_url(&url);
            return;
        }
        self.now_playing_open = false;
        self.drawer_open = false;
        if self.section == section {
            // Re-selecting the current section returns to its root.
            self.stacks.entry(section).or_default().pushed.clear();
        }
        self.section = section;
        if section == Section::PlayAgain
            && let Some(view) = &self.play_again
        {
            view.update(cx, |view, cx| view.refresh(cx));
        }
        persist_setting(cx, LAST_SECTION_KEY, section.id().to_string());
        cx.notify();
    }

    /// The section's root surface (below its top bar).
    fn root_content(&mut self, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        match self.section {
            Section::Discover if self.discover_tab == DiscoverTab::Account => page(self.account_view(cx), window),
            Section::Discover => page(self.root(self.discover_tab.source(), cx), window),
            Section::YtMusic if self.music_tab == MusicTab::Library => page(self.music_library_view(cx), window),
            Section::YtMusic => page(self.root(self.music_tab.source(), cx), window),
            Section::Library => page(self.library_view(cx), window),
            Section::PlayAgain => page(self.play_again_view(cx), window),
            Section::Downloads => page(self.downloads_view(cx), window),
            Section::Feedback => {
                page(self.feedback.get_or_insert_with(|| cx.new(|_| FeedbackView::new())).clone(), window)
            }
            Section::Settings => page(self.settings_view(cx), window),
            Section::Donate | Section::Telegram => div().into_any_element(),
        }
    }

    /// The top layer with its own bar, or the section's bar over its root.
    fn render_main(&mut self, rail_mode: bool, wide: bool, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let top = self.stacks.get(&self.section).and_then(|stack| stack.pushed.last());
        match top {
            Some(Layer::Search(view)) => return page(view.clone(), window),
            Some(Layer::LibrarySearch(view)) => return page(view.clone(), window),
            Some(Layer::SignIn(view)) => return page(view.clone(), window),
            Some(Layer::Feed { view, title }) => {
                let view = view.clone();
                // A channel's name arrives with its page header.
                let title = if view.read(cx).is_playlist_page() {
                    t(Str::DetailPlaylist).into()
                } else if title.is_empty() {
                    view.read(cx).title().unwrap_or_default()
                } else {
                    title.clone()
                };
                return v_flex()
                    .size_full()
                    .child(self.detail_bar(title, None, None, cx))
                    .child(div().flex_1().min_h_0().relative().child(page(view, window)).children(bar_shadow(cx)))
                    .into_any_element();
            }
            Some(Layer::Results { view, query }) => {
                let (view, query) = (view.clone(), query.clone());
                let filter = (!view.read(cx).filter_groups().is_empty()).then(|| view.clone());
                return v_flex()
                    .size_full()
                    .child(self.detail_bar(query.clone().into(), Some(query), filter, cx))
                    .child(div().flex_1().min_h_0().relative().child(page(view, window)).children(bar_shadow(cx)))
                    .into_any_element();
            }
            None => {}
        }
        let content = self.root_content(window, cx);
        let tab_row = match self.section {
            Section::Discover if !wide => Some(self.discover_tabs(false, cx)),
            Section::YtMusic => Some(self.music_tabs(cx)),
            _ => None,
        };
        // Library's own tab row, under this bar, casts the shadow.
        let shadow = (self.section != Section::Library).then(|| bar_shadow(cx)).flatten();
        v_flex()
            .size_full()
            .child(self.root_bar(rail_mode, wide, cx))
            .children(tab_row)
            .child(div().flex_1().min_h_0().relative().child(content).children(shadow))
            .into_any_element()
    }

    /// Back, title and search, for a pushed page. `search_query` reopens the input with it;
    /// `filter` adds the search filter button for those results.
    fn detail_bar(
        &self,
        title: SharedString,
        search_query: Option<String>,
        filter: Option<Entity<FeedView>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        h_flex()
            .gap_2()
            .px_2()
            .pt(px(2.))
            .pb(px(3.))
            .bg(chrome_tone(cx))
            .child(
                self.chrome_focus.button(
                    CHROME_BACK,
                    action_icon("layer-back", Icon::new(IconName::ArrowLeft), t(Str::CdBack))
                        .on_click(cx.listener(|shell, _, _, cx| shell.back(cx))),
                    cx,
                ),
            )
            .child(
                crate::a11y::heading("layer-title", 1, title).flex_1().min_w_0().truncate().text_lg().font_semibold(),
            )
            .when_some(filter, |bar, view| {
                bar.child(
                    self.chrome_focus.button(
                        CHROME_FILTER,
                        action_icon("layer-filter", Icon::new(Lucide::Funnel), t(Str::CdFilter))
                            .on_click(move |_, window, cx| open_search_filter(&view, window, cx)),
                        cx,
                    ),
                )
            })
            .child(self.chrome_focus.button(
                CHROME_LAYER_SEARCH,
                action_icon("layer-search", Icon::new(IconName::Search), t(Str::CdSearch)).on_click(cx.listener(
                    move |shell, _, window, cx| shell.open_search(search_query.clone().unwrap_or_default(), window, cx),
                )),
                cx,
            ))
            .into_any_element()
    }

    /// Hamburger (drawer mode), the section title, Discover's inline tabs when wide, search.
    fn root_bar(&self, rail_mode: bool, wide: bool, cx: &mut Context<Self>) -> AnyElement {
        let discover = self.section == Section::Discover;
        let title = if discover { self.discover_tab.label() } else { self.section.label() };
        let searchable = matches!(self.section, Section::Discover | Section::YtMusic | Section::Library);
        // The title slot keeps the widest tab label's room, so the inline tabs never shift.
        let reserve = DiscoverTab::ALL.iter().map(|tab| tab.label().chars().count()).max().unwrap_or(0) as f32 * 9.5;
        h_flex()
            .gap_2()
            .px_2()
            .pt(px(BAR_PAD_TOP))
            .pb(px(BAR_PAD_BOTTOM))
            .items_center()
            .bg(chrome_tone(cx))
            .when(!rail_mode, |bar| {
                bar.child(self.chrome_focus.button(
                    CHROME_DRAWER,
                    action_icon("open-drawer", Icon::new(Lucide::Menu), t(Str::CdMenu)).on_click(cx.listener(
                        |shell, _, _, cx| {
                            shell.drawer_open = true;
                            cx.notify();
                        },
                    )),
                    cx,
                ))
            })
            .when(rail_mode, |bar| bar.child(div().w(px(8.))))
            .child(
                crate::a11y::heading("page-title", 1, title)
                    .text_lg()
                    .font_semibold()
                    .truncate()
                    .when(discover && wide, |slot| slot.min_w(px(reserve)))
                    .when(!(discover && wide), |slot| slot.flex_1().min_w_0()),
            )
            .when(discover && wide, |bar| {
                // Over the bar's padding, so the underline sits on the bar's bottom edge.
                let slot = div().pl_4().flex_1().flex().self_stretch().mt(px(-BAR_PAD_TOP)).mb(px(-BAR_PAD_BOTTOM));
                bar.child(slot.child(self.discover_tabs(true, cx)))
            })
            .when(searchable, |bar| {
                bar.child(
                    self.chrome_focus.button(
                        CHROME_SEARCH,
                        action_icon("open-search", Icon::new(IconName::Search), t(Str::CdSearch))
                            .on_click(cx.listener(|shell, _, window, cx| shell.open_search(String::new(), window, cx))),
                        cx,
                    ),
                )
            })
            .into_any_element()
    }

    /// Discover's icon tabs: inline in the bar (88 each at most) or a full-width row under it.
    fn discover_tabs(&self, inline: bool, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme();
        let mut row = h_flex().id("discover-tabs").role(Role::TabList).aria_label(t(Str::SectionDiscover));
        row = if inline {
            row.items_stretch()
        } else {
            row.w_full().bg(chrome_tone(cx)).border_b_1().border_color(theme.border)
        };
        let tabs = DiscoverTab::listed(self.guest);
        for (index, tab) in tabs.iter().copied().enumerate() {
            let selected = tab == self.discover_tab;
            let cell = div()
                .id(("discover-tab", index))
                .role(Role::Tab)
                .aria_label(tab.label())
                .aria_selected(selected)
                .map(|cell| if inline { cell.rounded_t(px(10.)) } else { cell.h(px(44.)).rounded(px(10.)) })
                .flex()
                .items_center()
                .justify_center()
                .relative()
                .cursor_pointer()
                .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                .child(Icon::new(tab.icon()).text_color(if selected { theme.primary } else { theme.muted_foreground }))
                .when(selected, |cell| {
                    cell.child(div().absolute().bottom_0().left_2().right_2().h(px(2.)).bg(theme.primary))
                })
                .on_click(cx.listener(move |shell, _, _, cx| shell.set_discover_tab(tab, cx)));
            let cell = self.tab_focus.item(index, cell, cx);
            row = row.child(if inline { cell.w(px(88.)) } else { cell.flex_1() });
        }
        self.tab_focus.container(row, 0..tabs.len(), cx).into_any_element()
    }

    /// YT Music's text tabs with a sliding underline, pinned under the section bar.
    fn music_tabs(&self, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme();
        let tabs = MusicTab::listed(self.guest);
        let selected = tabs.iter().position(|tab| *tab == self.music_tab).unwrap_or(0);
        let shell = cx.entity().downgrade();
        div()
            .w_full()
            .px_3()
            .bg(chrome_tone(cx))
            .border_b_1()
            .border_color(theme.border)
            .child(tab_row(
                "music-tabs",
                tabs.iter().map(|tab| SharedString::from(tab.label())).collect(),
                selected,
                &self.music_tab_focus,
                &self.music_tab_drag,
                move |index, _, cx| {
                    if let Some(tab) = tabs.get(index).copied() {
                        shell
                            .update(cx, |shell, cx| {
                                shell.music_tab = tab;
                                cx.notify();
                            })
                            .ok();
                    }
                },
                cx,
            ))
            .into_any_element()
    }

    fn toggle_rail(&mut self, expanded: bool, cx: &mut Context<Self>) {
        self.rail_manual = Some(!expanded);
        cx.notify();
    }

    /// The app mark, name and version, beside the rail toggle or atop the drawer.
    fn brand(&self, logo: f32, cx: &App) -> impl IntoElement + use<> {
        let theme = cx.theme();
        h_flex()
            .gap_2()
            .items_center()
            .child(div().size(px(logo)).flex_shrink_0().child(
                img(crate::assets::logo_path((logo * self.scale_factor).round() as u32)).size_full().rounded_full(),
            ))
            .child(
                v_flex().min_w_0().child(div().font_semibold().child(t(Str::AppName))).child(
                    div()
                        .text_xs()
                        .text_color(theme.muted_foreground)
                        .child(format!("v{}", crate::graph::APP_VERSION_NAME)),
                ),
            )
    }

    /// One section entry: an icon-only 48 square collapsed, else a full-width icon-and-label row whose
    /// icon starts `icon_start` in.
    fn section_entry(&self, section: Section, labelled: bool, icon_start: f32, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme();
        let selected = self.section == section;
        let container = *cx.global::<crate::settings_view::AccentTones>();
        let hover = theme.primary.opacity(HOVER_ALPHA);
        let entry = div()
            .id(("section", section.id() as usize))
            .role(Role::Tab)
            .aria_label(section.label())
            .aria_selected(selected)
            .cursor_pointer()
            .relative()
            .when(selected, |entry| entry.bg(container.ground))
            .hover(move |style| style.bg(if selected { container.ground.blend(hover) } else { hover }))
            .on_click(cx.listener(move |shell, _, _, cx| shell.select_section(section, cx)));
        let position = Section::groups(self.guest).iter().flatten().position(|entry| *entry == section);
        let entry = self.nav_focus.item(position.unwrap_or(0), entry, cx);
        let tint = if selected { theme.primary } else { theme.foreground };
        if !labelled {
            return entry
                .size(px(48.))
                .rounded(px(8.))
                .flex()
                .items_center()
                .justify_center()
                .child(Icon::new(section.icon()).text_color(tint))
                .into_any_element();
        }
        entry
            .w_full()
            .child(
                h_flex()
                    .gap_4()
                    .pl(px(icon_start))
                    .pr(px(DRAWER_ICON_START))
                    .py(px(11.))
                    .items_center()
                    .child(Icon::new(section.icon()).text_color(tint))
                    .child(
                        div()
                            .flex_1()
                            .truncate()
                            .text_color(tint)
                            .when(selected, |label| label.font_extrabold())
                            .child(section.label()),
                    ),
            )
            .when(selected, |entry| {
                entry.child(
                    div()
                        .absolute()
                        .left_0()
                        .top_0()
                        .bottom_0()
                        .flex()
                        .items_center()
                        .child(div().w(px(3.)).h(px(33.)).rounded_r(px(3.)).bg(theme.primary)),
                )
            })
            .into_any_element()
    }

    fn section_groups(&self, labelled: bool, icon_start: f32, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let mut list = v_flex().gap_1().items_center().w_full();
        let groups = Section::groups(self.guest);
        for (index, group) in groups.iter().enumerate() {
            if index > 0 {
                list = list.child(
                    div()
                        .w_full()
                        .py_1()
                        .when(!labelled, |divider| divider.px_2())
                        .child(div().h(px(1.)).w_full().bg(theme.border)),
                );
            }
            for section in group.iter().copied() {
                list = list.child(self.section_entry(section, labelled, icon_start, cx));
            }
        }
        let count = groups.iter().map(|group| group.len()).sum();
        let list = self.nav_focus.container(list, 0..count, cx);
        list.into_any_element()
    }

    fn render_rail(&self, expanded: bool, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let update_waiting = !expanded
            && cx.try_global::<crate::app_update::UpdatesHandle>().is_some_and(|handle| handle.0.read(cx).waiting());
        let toggle = action_icon("rail-toggle", Icon::new(Lucide::Menu), t(Str::CdMenu))
            .when(update_waiting, |toggle| toggle.tooltip(t(Str::UpdateAvailableTitle)))
            .on_click(cx.listener(move |shell, _, _, cx| shell.toggle_rail(expanded, cx)));
        let toggle = self.chrome_focus.button(CHROME_RAIL_TOGGLE, toggle, cx).when(update_waiting, |toggle| {
            toggle.child(div().absolute().top(px(4.)).right(px(4.)).size(px(8.)).rounded_full().bg(theme.primary))
        });
        // The toggle and every section icon share the collapsed rail's centre line, so expanding moves
        // none of them.
        let toggle = div().w(px(RAIL_COLLAPSED)).flex_shrink_0().flex().justify_center().child(toggle);
        let header = h_flex()
            .min_h(px(48.))
            .items_center()
            .child(toggle)
            .when(expanded, |header| header.pr_4().child(self.brand(RAIL_LOGO, cx)))
            .into_any_element();
        let website = expanded.then(|| {
            focusable(div().id("website").role(Role::Link).aria_label("ymusic.io"), cx)
                .ml(px(RAIL_ICON_START - 4.))
                .p_1()
                .text_xs()
                .text_color(theme.muted_foreground)
                .cursor_pointer()
                .child("ymusic.io")
                .on_click(|_, _, cx| cx.open_url(WEBSITE))
        });
        let check_update = expanded
            .then(|| crate::app_update::check_link("rail-check-update", cx))
            .flatten()
            .map(|link| div().ml(px(RAIL_ICON_START - 4.)).child(link));
        v_flex()
            .id("rail")
            .role(Role::Navigation)
            .w(px(if expanded { RAIL_EXPANDED } else { RAIL_COLLAPSED }))
            .h_full()
            .flex_shrink_0()
            .bg(chrome_tone(cx))
            .border_r_1()
            .border_color(theme.border)
            .child(div().pt_1().child(header))
            .child(
                div()
                    .id("rail-scroll")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .when(!expanded, |list| list.px_2())
                    .py_1()
                    .child(self.section_groups(expanded, RAIL_ICON_START, cx))
                    .children(website)
                    .children(check_update),
            )
            .into_any_element()
    }

    fn render_drawer(&self, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let panel = v_flex()
            .id("drawer")
            .role(Role::Navigation)
            .w(px(DRAWER_WIDTH))
            .h_full()
            .bg(theme.background)
            .border_r_1()
            .border_color(theme.border)
            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
            .child(
                div()
                    .pl(px(18.))
                    .pr(px(18.))
                    .pt_4()
                    .pb(px(13.))
                    .child(self.brand(46., cx))
                    .children(crate::app_update::check_link("drawer-check-update", cx)),
            )
            .child(div().h(px(1.)).w_full().bg(theme.border))
            .child(div().id("drawer-scroll").flex_1().overflow_y_scroll().py_2().child(self.section_groups(
                true,
                DRAWER_ICON_START,
                cx,
            )));
        div()
            .absolute()
            .inset_0()
            .bg(black().opacity(0.4))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|shell, _, _, cx| {
                    shell.drawer_open = false;
                    cx.notify();
                }),
            )
            .child(panel)
            .into_any_element()
    }
}

impl Shell {
    fn step_volume(&mut self, steps: f32, window: &mut Window, cx: &mut Context<Self>) {
        let volume = self.playback.read(cx).volume;
        let snapped = (((volume / VOLUME_STEP).round() + steps) * VOLUME_STEP).clamp(0., 1.);
        self.playback.update(cx, |playback, cx| playback.set_volume(snapped, cx));
        window.push_notification(tf(Str::SoundVolume, &[&((snapped * 100.).round() as i32)]), cx);
    }

    fn account_view(&mut self, cx: &mut Context<Self>) -> Entity<AccountView> {
        if let Some(view) = &self.account {
            return view.clone();
        }
        let feed = self.root(FeedSource::Account, cx);
        let view = cx.new(|cx| AccountView::new(feed, true, cx));
        self._subscriptions.push(cx.subscribe(&view, |shell, _, _: &AccountChanged, cx| shell.account_changed(cx)));
        self.account = Some(view.clone());
        view
    }

    fn music_library_view(&mut self, cx: &mut Context<Self>) -> Entity<AccountView> {
        if let Some(view) = &self.music_library {
            return view.clone();
        }
        let feed = self.root(FeedSource::MusicLibrary, cx);
        let view = cx.new(|cx| AccountView::new(feed, false, cx));
        self._subscriptions.push(cx.subscribe(&view, |shell, _, _: &AccountChanged, cx| shell.account_changed(cx)));
        self.music_library = Some(view.clone());
        view
    }

    /// Recommended and Top tracks are built from local play, so they rebuild on every visit; the
    /// Account tab re-checks who is signed in.
    fn set_discover_tab(&mut self, tab: DiscoverTab, cx: &mut Context<Self>) {
        self.discover_tab = tab;
        persist_setting(cx, LAST_DISCOVER_TAB_KEY, tab.id().to_string());
        if tab == DiscoverTab::Account
            && let Some(view) = &self.account
        {
            view.update(cx, |view, cx| view.refresh(cx));
        }
        if matches!(tab, DiscoverTab::Recommended | DiscoverTab::TopTracks)
            && let Some(view) = self.roots.get(&tab.source())
        {
            view.update(cx, |view, cx| view.reload(cx));
        }
        cx.notify();
    }

    fn select_tab(&mut self, index: usize, cx: &mut Context<Self>) {
        match self.section {
            Section::Discover => {
                if let Some(tab) = DiscoverTab::listed(self.guest).get(index).copied() {
                    self.set_discover_tab(tab, cx);
                }
            }
            Section::YtMusic => {
                if let Some(tab) = MusicTab::listed(self.guest).get(index).copied() {
                    self.music_tab = tab;
                }
            }
            Section::Library => {
                let view = self.library_view(cx);
                view.update(cx, |library, cx| library.select_tab_index(index, cx));
            }
            _ => return,
        }
        cx.notify();
    }

    /// Peels back full screen, Now Playing, then the drawer; with none open, a second Esc within
    /// the double-click window goes back.
    fn escape(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if window.is_fullscreen() {
            window.toggle_fullscreen();
        } else if self.now_playing_open {
            self.now_playing_open = false;
        } else if self.drawer_open {
            self.drawer_open = false;
        } else if let Some(Layer::SignIn(view)) = self.stacks.get(&self.section).and_then(|stack| stack.pushed.last()) {
            view.clone().update(cx, |view, cx| view.back(cx));
            return;
        } else if self.idle_escape_at.take().is_some_and(|at| at.elapsed() <= DOUBLE_ESCAPE) {
            self.back(cx);
            return;
        } else {
            self.idle_escape_at = Some(Instant::now());
            return;
        }
        cx.notify();
    }

    fn shortcut_listeners(&self, root: Div, cx: &mut Context<Self>) -> Div {
        use crate::shortcuts::*;
        crate::a11y::root_arrows(root.track_focus(&self.focus), self.focus.clone())
            .key_context(CONTEXT)
            .on_action(cx.listener(|shell, _: &TogglePlay, _, cx| shell.playback.update(cx, |p, cx| p.toggle(cx))))
            .on_action(cx.listener(|shell, _: &SeekBackward, _, cx| {
                shell.playback.update(cx, |p, cx| p.seek_by(-SEEK_STEP_MS, cx))
            }))
            .on_action(cx.listener(|shell, _: &SeekForward, _, cx| {
                shell.playback.update(cx, |p, cx| p.seek_by(SEEK_STEP_MS, cx))
            }))
            .on_action(cx.listener(|shell, _: &PreviousTrack, _, cx| shell.playback.update(cx, |p, cx| p.previous(cx))))
            .on_action(cx.listener(|shell, _: &NextTrack, _, cx| shell.playback.update(cx, |p, cx| p.next(cx))))
            .on_action(cx.listener(|shell, _: &VolumeUp, window, cx| shell.step_volume(1., window, cx)))
            .on_action(cx.listener(|shell, _: &VolumeDown, window, cx| shell.step_volume(-1., window, cx)))
            .on_action(cx.listener(|shell, _: &ToggleNowPlaying, _, cx| {
                if shell.playback.read(cx).current().is_some() {
                    shell.now_playing_open = !shell.now_playing_open;
                    cx.notify();
                }
            }))
            .on_action(cx.listener(|shell, _: &ToggleDrawer, window, cx| {
                let viewport = window.viewport_size();
                let rail_mode = f32::from(viewport.width) >= 750. && f32::from(viewport.height) >= 480.;
                if !rail_mode {
                    shell.drawer_open = !shell.drawer_open;
                    cx.notify();
                }
            }))
            .on_action(cx.listener(|shell, _: &Refresh, window, cx| {
                if let Some(feed) = shell.visible_feed() {
                    feed.update(cx, |feed, cx| feed.refresh(window, cx));
                }
            }))
            .on_action(cx.listener(|shell, _: &OpenSearch, window, cx| {
                let searchable = matches!(shell.section, Section::Discover | Section::YtMusic | Section::Library);
                let searching = matches!(
                    shell.stacks.get(&shell.section).and_then(|stack| stack.pushed.last()),
                    Some(Layer::Search(_) | Layer::LibrarySearch(_))
                );
                if searchable && !searching {
                    shell.open_search(String::new(), window, cx);
                }
            }))
            .on_action(cx.listener(|_, _: &ToggleFullscreen, window, _| window.toggle_fullscreen()))
            .on_action(cx.listener(|shell, _: &Escape, window, cx| shell.escape(window, cx)))
            .on_action(cx.listener(|shell, _: &OpenSignIn, window, cx| shell.open_sign_in(window, cx)))
            .on_action(
                cx.listener(|_, _: &ShowShortcuts, window, cx| crate::shortcuts_help::ShortcutsHelp::open(window, cx)),
            )
            .on_action(cx.listener(|shell, _: &SelectTab1, _, cx| shell.select_tab(0, cx)))
            .on_action(cx.listener(|shell, _: &SelectTab2, _, cx| shell.select_tab(1, cx)))
            .on_action(cx.listener(|shell, _: &SelectTab3, _, cx| shell.select_tab(2, cx)))
            .on_action(cx.listener(|shell, _: &SelectTab4, _, cx| shell.select_tab(3, cx)))
            .on_action(cx.listener(|shell, _: &SelectTab5, _, cx| shell.select_tab(4, cx)))
            .on_action(cx.listener(|shell, _: &SelectTab6, _, cx| shell.select_tab(5, cx)))
    }
}

impl Render for Shell {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.scale_factor = window.scale_factor();
        if window.focused(cx).is_none() {
            self.focus.focus(window, cx);
        }
        if let Some(target) = self.pending_download.take() {
            DownloadPicker::open(target, window, cx);
        }
        match self.pending_sound.take() {
            Some(SoundDialog::SpeedPitch) => {
                crate::speed_pitch::SpeedPitchDialog::open(self.playback.clone(), window, cx)
            }
            Some(SoundDialog::Equalizer) => crate::equalizer::EqualizerDialog::open(self.playback.clone(), window, cx),
            None => {}
        }
        if std::mem::take(&mut self.pending_signin) {
            self.open_sign_in(window, cx);
        }
        if std::mem::take(&mut self.pending_search) {
            self.open_search(String::new(), window, cx);
        }
        if let Some(video_id) = self.pending_details.take() {
            let view = crate::video_details::VideoDetailsView::open(video_id, window, cx);
            let navigate = cx.subscribe(&view, |shell, _, event: &Navigate, cx| {
                shell.now_playing_open = false;
                shell.push(event.source.clone(), event.title.clone(), cx);
            });
            self._subscriptions.push(navigate);
        }
        crate::app_update::sync_prompt(window, cx);
        let viewport = window.viewport_size();
        let (width, height) = (f32::from(viewport.width), f32::from(viewport.height));
        let rail_mode = width >= 750. && height >= 480.;
        let auto_expanded = width >= 1200.;
        if self.rail_auto != Some((rail_mode, auto_expanded)) {
            self.rail_auto = Some((rail_mode, auto_expanded));
            self.rail_manual = None;
        }
        if rail_mode {
            self.drawer_open = false;
        }
        let expanded = self.rail_manual.unwrap_or(auto_expanded);
        // Measured as if the rail were expanded, so toggling it never moves the tabs.
        let shell_width = if rail_mode { width - RAIL_EXPANDED } else { width };
        let wide = (shell_width > height && shell_width >= 600.) || shell_width >= 650.;

        let now_playing = self.now_playing_open && self.playback.read(cx).current().is_some();
        if !now_playing {
            self.now_playing.read(cx).hide_video();
        }
        let depth = self.stacks.get(&self.section).map_or(0, |stack| stack.pushed.len());
        let shown_page = (self.section.id(), depth, self.discover_tab.id(), self.music_tab.label());
        if shown_page != self.shown_page {
            self.shown_page = shown_page;
            cx.global::<ScrollHost>().active.borrow_mut().take();
        }
        let host = cx.global::<ScrollHost>();
        let lifted = !now_playing && host.active.borrow().as_ref().is_some_and(|scroll| scroll.offset().y < px(0.));
        host.lifted.set(lifted);
        if now_playing != self.now_playing_shown {
            self.now_playing_shown = now_playing;
            if now_playing {
                self.now_playing_opener = window.focused(cx).map(|opener| opener.downgrade());
                self.now_playing.update(cx, |view, cx| view.take_focus(window, cx));
            } else if let Some(opener) = self.now_playing_opener.take().and_then(|opener| opener.upgrade()) {
                window.focus(&opener, cx);
            }
        }
        self.playback.update(cx, |playback, cx| playback.set_video_hidden(!now_playing, cx));
        // Sign-in covers the whole window, rail and player bar included.
        let signing_in = match self.stacks.get(&self.section).and_then(|stack| stack.pushed.last()) {
            Some(Layer::SignIn(view)) if !now_playing => Some(view.clone()),
            _ => None,
        };
        let covered = now_playing || signing_in.is_some();
        let body = if now_playing {
            self.now_playing.clone().into_any_element()
        } else if let Some(view) = signing_in {
            view.into_any_element()
        } else {
            let main = self.render_main(rail_mode, wide, window, cx);
            h_flex()
                .size_full()
                .when(rail_mode, |row| row.child(self.render_rail(expanded, cx)))
                .child(div().flex_1().min_w_0().h_full().child(main))
                .into_any_element()
        };
        let drawer = (self.drawer_open && !covered).then(|| self.render_drawer(cx));
        let root = self.shortcut_listeners(v_flex(), cx);
        root.relative()
            .size_full()
            .when(!now_playing, |shell| shell.bg(cx.theme().background))
            .child(crate::a11y::frame_marker())
            .child(div().flex_1().min_h_0().w_full().child(body))
            .when(!covered, |shell| shell.child(self.mini_player.clone()))
            .children(drawer)
    }
}

/// A page view drawn from its last frame until it notifies: the player bar's progress ticks redraw
/// the shell around it four times a second.
fn page<V: Render>(view: Entity<V>, window: &Window) -> AnyElement {
    let mut style = StyleRefinement::default();
    style.size.width = Some(relative(1.).into());
    style.size.height = Some(relative(1.).into());
    crate::a11y::cached_view(view, style, window)
}

/// Writes a setting off the UI thread; a failed write is logged, the UI state stands.
fn persist_setting(cx: &App, key: &'static str, value: String) {
    let graph = cx.global::<AppGraph>();
    let (settings, log) = (graph.settings.clone(), graph.log.clone());
    graph.tokio.spawn_blocking(move || {
        if let Err(error) = settings.put_raw(key, value) {
            log.warn(&format!("R90006 setting {key} not saved"), Some(error.as_ref()));
        }
    });
}

impl std::hash::Hash for FeedSource {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        format!("{self:?}").hash(state);
    }
}

impl Eq for FeedSource {}

fn open_search_filter(view: &Entity<FeedView>, window: &mut Window, cx: &mut App) {
    let groups = view.read(cx).filter_groups().to_vec();
    let target = view.downgrade();
    crate::search_filter::SearchFilter::open(
        groups,
        move |params, _, cx| {
            target.update(cx, |view, cx| view.apply_filter(params, cx)).ok();
        },
        window,
        cx,
    );
}
