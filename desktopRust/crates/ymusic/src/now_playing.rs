//! Now Playing, laid out like the JVM desktop app. Wide (W > H and W >= 640): the video on the left
//! with overlay controls, the queue pane on the right (`clamp(0.3W, 320, 480)`) under its top bar.
//! Narrow: top bar, video full width, title, then the queue.
//! The video box is painted by `video_layer`: a native view behind the window on macOS, CPU pictures elsewhere.

use std::cell::Cell;
use std::rc::Rc;
use std::time::{Duration, Instant};

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::base::slider::{SliderEvent, SliderState};
use gpui_kit::component::button::{Button, ButtonCustomVariant, ButtonVariants};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::slider::Slider;
use gpui_kit::component::{ActiveTheme, Icon, IconName, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, SliderStop, alert, heading, list_item};
use crate::action_icon::{HIT, glyph_action_icon, lucide_size, sized_action_icon};
use crate::audio_only::AudioOnlyArt;
use crate::download_dialog::DownloadTarget;
use crate::graph::{AppGraph, on_core};
use crate::mini_player::{clock, duration_clock};
use crate::playback::{
    AUTO_TRANSLATE_KIND, CaptionTrack, PlayState, Playback, QUALITY_AUDIO_ONLY, QUALITY_AUTO, QueueItem, Source,
};
use crate::queue_nav::LoopMode;
use crate::reorder::{Reorder, ReorderEvent};
use crate::sponsor_block::SponsorBlock;
#[cfg(not(target_os = "windows"))]
use crate::video_layer::VideoLayer;
#[cfg(target_os = "windows")]
use crate::win_video_layer::VideoLayer;

const CONTROLS_HIDE_AFTER: Duration = Duration::from_secs(3);
const SKIP_MS: i64 = 10_000;

const STOP_CLOSE: usize = 0;
const STOP_DOWNLOAD: usize = 1;
const STOP_SPONSOR_BLOCK: usize = 2;
const STOP_SLEEP: usize = 3;
const STOP_QUALITY: usize = 4;
const STOP_MORE: usize = 5;
const STOP_REPEAT: usize = 6;
const STOP_SHUFFLE: usize = 7;
const STOP_CHANNEL: usize = 8;
const STOP_CHANNEL_OVERLAY: usize = 9;
/// The video controls, as arrow stops of their own list.
const OVERLAY_REWIND: usize = 0;
const OVERLAY_PREVIOUS: usize = 1;
const OVERLAY_PLAY: usize = 2;
const OVERLAY_NEXT: usize = 3;
const OVERLAY_FORWARD: usize = 4;
const OVERLAY_QUALITY: usize = 5;
const OVERLAY_DETAILS: usize = 6;
const OVERLAY_SPEED: usize = 7;
const OVERLAY_CAPTIONS: usize = 8;
const OVERLAY_FULLSCREEN: usize = 9;
const OVERLAY_SEEK: usize = 10;
const OVERLAY_SIGN_IN: usize = 11;
const OVERLAY_RETRY: usize = 12;
const MENU_CAPTIONS: u8 = 1;
const MENU_QUALITY: u8 = 2;
const MENU_MORE: u8 = 4;
/// The overlay's light ink for its time, chip and labels, and the artist line's.
const OVERLAY_INK: u32 = 0xEEEEEE;
const OVERLAY_ARTIST: u32 = 0xDADADA;
/// `ActionIcon`'s glyph and hit target.
const ICON: f32 = 24.;
const PLAY_BUTTON: f32 = 58.;
const PLAY_ICON: f32 = 26.;
/// Fixed: the top bar is a cached view, laid out without measuring its content.
const TOP_BAR: f32 = 40.;

pub enum NowPlayingEvent {
    Close,
    Download(DownloadTarget),
    OpenSpeedPitch,
    OpenEqualizer,
    /// A playback failure wants a signed-in viewer.
    SignIn,
    /// Open the video's details and comments.
    Details(String),
    /// Open a channel page (an app link).
    OpenChannel {
        url: String,
        title: String,
    },
    /// Open the Library's artist (or album) `name`; its tab when the file names none.
    LibraryGroup {
        album: bool,
        name: Option<String>,
    },
}

pub struct NowPlaying {
    playback: Entity<Playback>,
    sponsor_block: Entity<SponsorBlock>,
    queue_scroll: ScrollHandle,
    queue_focus: FocusList,
    /// The top bar's and queue header's buttons, for the arrow keys.
    bar_focus: FocusList,
    reorder: Reorder,
    video_layer: Option<Rc<VideoLayer>>,
    /// The video box as last painted, to report viewport changes to the player.
    video_box: Rc<Cell<(i32, i32)>>,
    audio_only_art: Entity<AudioOnlyArt>,
    /// The top bar and the queue, made at the first render (which can observe this view).
    parts: [Option<Entity<CachedPart>>; 2],
    /// Audio only as last drawn: it holds between items until the next one's tracks are known.
    audio_only: Cell<bool>,
    caption_strip: std::cell::RefCell<CaptionStrip>,
    reported_box: (i32, i32),
    seek: Entity<SliderState>,
    dragging_seek: bool,
    /// The video controls are up: a click on the picture toggles them, a key or the seek bar shows
    /// them, and they hide `CONTROLS_HIDE_AFTER` past `shown_at` while playing.
    controls_shown: bool,
    shown_at: Rc<Cell<Instant>>,
    /// The overlay's open menus, a bit each: hiding the controls would close them.
    held_menus: Rc<Cell<u8>>,
    /// A press on the bare picture, toggling the controls on its release.
    picture_pressed: bool,
    caption_focus: FocusHandle,
    play_focus: FocusHandle,
    overlay_focus: FocusList,
    /// A video control held keyboard focus at the last render: the controls stay up meanwhile.
    keyboard_in_controls: Cell<bool>,
    fullscreen: bool,
    _subscriptions: Vec<Subscription>,
    _controls_timer: Task<()>,
}

impl EventEmitter<NowPlayingEvent> for NowPlaying {}

#[derive(Clone, Copy, PartialEq)]
enum PanePart {
    TopBar,
    /// The queue header and list.
    Queue {
        large: bool,
    },
}

/// A part of Now Playing as a cached view: it redraws when Now Playing changes, not with every
/// frame of the audio-only bars.
struct CachedPart {
    now_playing: WeakEntity<NowPlaying>,
    part: PanePart,
    _observe: Subscription,
}

impl Render for CachedPart {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let part = self.part;
        self.now_playing
            .update(cx, |view, cx| match part {
                PanePart::TopBar => match view.playback.read(cx).current().cloned() {
                    Some(current) => view.render_top_bar(&current, cx),
                    None => div().into_any_element(),
                },
                PanePart::Queue { large } => v_flex()
                    .size_full()
                    .child(view.render_queue_header(cx))
                    .child(view.render_queue(large, window, cx))
                    .into_any_element(),
            })
            .unwrap_or_else(|_| div().into_any_element())
    }
}

/// A caption text row.
const CAPTION_LINE: f32 = 24.;
/// A one-row cue: its row, the box's padding and the gap under it.
const CAPTION_ROW: f32 = CAPTION_LINE + 4. + CAPTION_GAP;
const CAPTION_GAP: f32 = 4.;
/// A cue joining the window scrolls in over this long, as in the JVM overlay.
const CAPTION_SCROLL: Duration = Duration::from_millis(280);
/// Average advance of the 16 px caption font, for estimating where rows wrap.
const CAPTION_CHAR_WIDTH: f32 = 8.5;

/// A cue's height at `width`: its hard rows, each wrapped.
fn cue_height(text: &str, width: f32) -> f32 {
    let per_row = (width / CAPTION_CHAR_WIDTH).max(1.);
    let rows: f32 = text.split('\n').map(|row| (row.chars().count() as f32 / per_row).ceil().max(1.)).sum();
    rows * CAPTION_LINE + 4. + CAPTION_GAP
}

/// The cues to draw: the bundle's window plus a cue that just left it, kept long enough to scroll
/// out above the box. `tail_count` cues joined in the last change.
struct CaptionStrip {
    held: std::collections::HashMap<i64, String>,
    last_ids: Vec<i64>,
    entry_ids: Vec<i64>,
    tail_count: usize,
    changed_at: std::time::Instant,
}

impl CaptionStrip {
    fn new() -> Self {
        Self {
            held: Default::default(),
            last_ids: Vec::new(),
            entry_ids: Vec::new(),
            tail_count: 0,
            changed_at: std::time::Instant::now(),
        }
    }

    /// Same input, same output: only a change of the window's cue ids moves the strip.
    fn advance(&mut self, lines: &[(i64, String)]) -> Vec<(i64, String)> {
        let ids: Vec<i64> = lines.iter().map(|(id, _)| *id).collect();
        if ids != self.last_ids {
            let sequential = !self.last_ids.is_empty() && ids.iter().any(|id| self.last_ids.contains(id));
            if sequential {
                let tail: Vec<i64> = ids.iter().copied().filter(|id| !self.entry_ids.contains(id)).collect();
                let mut entries: Vec<i64> = self.entry_ids.iter().copied().filter(|id| !tail.contains(id)).collect();
                entries.extend(&tail);
                let keep = ids.len() + 1;
                self.entry_ids = entries.split_off(entries.len().saturating_sub(keep));
                self.tail_count = tail.len();
            } else {
                self.entry_ids = ids.clone();
                self.tail_count = 0;
            }
            self.last_ids = ids;
            self.changed_at = std::time::Instant::now();
        }
        let entries: Vec<(i64, String)> = self
            .entry_ids
            .iter()
            .filter_map(|id| {
                lines
                    .iter()
                    .find(|(line, _)| line == id)
                    .map(|(_, text)| text.clone())
                    .or_else(|| self.held.get(id).cloned())
                    .map(|text| (*id, text))
            })
            .collect();
        self.held = entries.iter().cloned().collect();
        entries
    }
}
/// Clears the video controls' bottom strip, at 1× overlay scale.
const CAPTION_ABOVE_CONTROLS: f32 = 72.;
/// Two caption rows: 24 line height plus the box's 2 + 2 padding, each.
const CAPTION_TWO_ROWS: f32 = 56.;
/// The picture's shape until the stream reports its own.
const VIDEO_RATIO: f32 = 16. / 9.;

/// A control drawn over the video: white whatever the theme, since the picture is behind it. A
/// press on it never reaches the picture under it, which toggles the controls.
fn on_video(button: Button, cx: &App) -> Button {
    on_video_menu(button, cx).block_mouse_except_scroll()
}

/// [`on_video`] for a dropdown's trigger, which must not block the mouse: the menu opens from a
/// press handler behind the trigger, and that handler already stops the press from reaching the
/// picture.
fn on_video_menu(button: Button, cx: &App) -> Button {
    button.custom(
        ButtonCustomVariant::new(cx)
            .color(transparent_black())
            .foreground(white())
            .hover(white().opacity(0.15))
            .active(white().opacity(0.25)),
    )
}

/// A menu row with its current `value` at the end, in the secondary ink.
fn menu_value_item(label: &'static str, value: String) -> PopupMenuItem {
    PopupMenuItem::element(move |_, cx| {
        h_flex().w_full().gap_4().items_center().child(div().flex_1().min_w_0().child(label)).child(
            div()
                .max_w(px(96.))
                .truncate()
                .text_size(px(13.))
                .text_color(cx.theme().muted_foreground)
                .child(value.clone()),
        )
    })
}

/// How much the video overlay's chrome grows with the video box: 1× up to a 400 px short side, then
/// up to 1.3×.
fn overlay_scale((width, height): (i32, i32)) -> f32 {
    (width.min(height) as f32 / 400.).clamp(1., 1.3)
}

/// Rounds to an even pixel count, so centered boxes split their difference exactly.
fn even(size: f32) -> f32 {
    (size / 2.).round() * 2.
}

impl NowPlaying {
    pub fn new(
        playback: Entity<Playback>,
        sponsor_block: Entity<SponsorBlock>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let seek = cx.new(|_| SliderState::new().min(0.).max(1.).step(0.001));
        let subscriptions = vec![
            cx.observe(&sponsor_block, |_, _, cx| cx.notify()),
            cx.observe_in(&playback, window, |view, playback, window, cx| {
                if !view.dragging_seek {
                    let playback = playback.read(cx);
                    let fraction = if playback.duration_ms > 0 {
                        (playback.position_ms as f32 / playback.duration_ms as f32).clamp(0., 1.)
                    } else {
                        0.
                    };
                    view.seek.update(cx, |slider, cx| slider.set_value(fraction, window, cx));
                }
                cx.notify();
            }),
            cx.subscribe(&seek, |view, _, event: &SliderEvent, cx| match event {
                SliderEvent::Change(_) => {
                    view.dragging_seek = true;
                    view.show_controls(cx);
                }
                SliderEvent::Release(value) => {
                    view.dragging_seek = false;
                    let fraction = value.end();
                    view.playback.update(cx, |playback, cx| {
                        let target = (playback.duration_ms as f32 * fraction) as i64;
                        playback.seek(target, cx);
                    });
                }
            }),
        ];
        let executor = cx.background_executor().clone();
        let controls_timer = cx.spawn(async move |this, cx| {
            loop {
                executor.timer(Duration::from_millis(500)).await;
                if this.update(cx, |view, cx| view.auto_hide(cx)).is_err() {
                    return;
                }
            }
        });
        Self::dev_hook(window, cx);
        let queue_scroll = ScrollHandle::new();
        let video_box = Rc::new(Cell::new((0, 0)));
        let audio_only_art = cx.new(|_| AudioOnlyArt::new(playback.clone(), video_box.clone()));
        Self {
            playback,
            sponsor_block,
            queue_scroll: queue_scroll.clone(),
            queue_focus: FocusList::new(Some(queue_scroll.clone())),
            bar_focus: FocusList::new(None),
            reorder: Reorder::new(queue_scroll),
            video_layer: None,
            video_box,
            audio_only_art,
            parts: [None, None],
            audio_only: Cell::new(false),
            caption_strip: std::cell::RefCell::new(CaptionStrip::new()),
            reported_box: (0, 0),
            seek,
            dragging_seek: false,
            controls_shown: true,
            shown_at: Rc::new(Cell::new(Instant::now())),
            held_menus: Rc::default(),
            picture_pressed: false,
            caption_focus: cx.focus_handle(),
            play_focus: cx.focus_handle(),
            overlay_focus: FocusList::new(None),
            keyboard_in_controls: Cell::new(false),
            fullscreen: false,
            _subscriptions: subscriptions,
            _controls_timer: controls_timer,
        }
    }

    /// Debug: `YMUSIC_DEV_NOWPLAYING=captions[:key,key*n,…]` opens the caption picker once the video's
    /// tracks are known, `fullscreen[:…]` enters full screen once it plays; then those keys are
    /// pressed (`*n`: n times) through the window's key dispatch.
    fn dev_hook(window: &mut Window, cx: &mut Context<Self>) {
        let Some(spec) = std::env::var("YMUSIC_DEV_NOWPLAYING").ok().filter(|_| cfg!(debug_assertions)) else {
            return;
        };
        let (surface, keys) = spec.split_once(':').unwrap_or((spec.as_str(), ""));
        let captions = match surface {
            "captions" => true,
            "fullscreen" => false,
            _ => return,
        };
        let mut presses: Vec<&'static str> = if captions { vec!["enter"] } else { Vec::new() };
        for key in keys.split(',').filter(|key| !key.is_empty()) {
            let (key, times) = key.split_once('*').unwrap_or((key, "1"));
            let key: &'static str = Box::leak(key.trim().to_owned().into_boxed_str());
            presses.extend(std::iter::repeat_n(key, times.parse().unwrap_or(1)));
        }
        let executor = cx.background_executor().clone();
        cx.spawn_in(window, async move |this, cx| {
            loop {
                executor.timer(Duration::from_millis(500)).await;
                let ready = this.update(cx, |view, cx| {
                    view.show_controls(cx);
                    let playback = view.playback.read(cx);
                    if captions { !playback.caption_tracks.is_empty() } else { playback.is_playing() }
                });
                match ready {
                    Ok(true) => break,
                    Ok(false) => {}
                    Err(_) => return,
                }
            }
            if !captions && this.update_in(cx, |view, window, cx| view.toggle_fullscreen(window, cx)).is_err() {
                return;
            }
            executor.timer(Duration::from_millis(500)).await;
            let focused = this.update_in(cx, |view, window, cx| {
                window.focus(if captions { &view.caption_focus } else { &view.play_focus }, cx)
            });
            if focused.is_err() {
                return;
            }
            for key in presses {
                executor.timer(Duration::from_millis(400)).await;
                let pressed = cx.update(|window, cx| match Keystroke::parse(key) {
                    Ok(keystroke) => {
                        window.dispatch_keystroke(keystroke.clone(), cx);
                        window.dispatch_event(PlatformInput::KeyUp(KeyUpEvent { keystroke }), cx);
                    }
                    Err(error) => eprintln!("YMUSIC_DEV_NOWPLAYING: {key}: {error}"),
                });
                if pressed.is_err() {
                    return;
                }
            }
        })
        .detach();
    }

    pub fn hide_video(&self) {
        if let Some(layer) = &self.video_layer {
            layer.hide();
        }
    }

    fn ensure_video_layer(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.video_layer.is_some() {
            return;
        }
        let Some(layer) = VideoLayer::attach(window) else { return };
        let layer = Rc::new(layer);
        #[cfg(target_os = "macos")]
        {
            let pointer = layer.as_ptr();
            // SAFETY: the layer lives in this view for the app's lifetime, past Playback's use of it.
            self.playback.update(cx, |playback, _| unsafe { playback.attach_video_layer(pointer) });
        }
        #[cfg(not(target_os = "macos"))]
        {
            use futures::StreamExt;
            let (sender, mut pictures) = futures::channel::mpsc::unbounded::<()>();
            if let Some(presenter) = layer.presenter(move || {
                let _ = sender.unbounded_send(());
            }) {
                cx.spawn(async move |view, cx| {
                    while pictures.next().await.is_some() {
                        if view.update(cx, |_, cx| cx.notify()).is_err() {
                            break;
                        }
                    }
                })
                .detach();
                self.playback.update(cx, |playback, _| playback.attach_presenter(presenter));
            }
        }
        self.video_layer = Some(layer);
    }

    /// Shows the video controls and restarts their hide delay.
    fn show_controls(&mut self, cx: &mut Context<Self>) {
        self.controls_shown = true;
        self.shown_at.set(Instant::now());
        cx.notify();
    }

    /// Shows the controls and puts keyboard focus on Play, as Now Playing opens.
    pub fn take_focus(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.show_controls(cx);
        window.focus(&self.play_focus, cx);
    }

    /// A click on the bare picture: hides the controls when they show, else shows them.
    fn toggle_controls(&mut self, cx: &mut Context<Self>) {
        if self.controls_shown {
            self.controls_shown = false;
            cx.notify();
        } else {
            self.show_controls(cx);
        }
    }

    /// Hides the controls once they have shown for `CONTROLS_HIDE_AFTER` while playing, unless the
    /// seek bar is held or a menu of theirs is open.
    fn auto_hide(&mut self, cx: &mut Context<Self>) {
        if self.controls_shown
            && self.playback.read(cx).is_playing()
            && !self.dragging_seek
            && self.held_menus.get() == 0
            && !self.keyboard_in_controls.get()
            && self.shown_at.get().elapsed() >= CONTROLS_HIDE_AFTER
        {
            self.controls_shown = false;
            cx.notify();
        }
    }

    /// Any key brings hidden controls back, so Tab can reach them again.
    fn key_shows_controls(&mut self, _: &KeyDownEvent, _: &mut Window, cx: &mut Context<Self>) {
        self.show_controls(cx);
    }

    /// Keeps the controls up while the menu `bit` is open, and for the usual delay after it closes.
    fn hold_while_open(&self, bit: u8) -> impl Fn(&bool, &mut Window, &mut App) + 'static {
        let (held, shown_at) = (self.held_menus.clone(), self.shown_at.clone());
        move |open, _, _| {
            held.set(if *open { held.get() | bit } else { held.get() & !bit });
            shown_at.set(Instant::now());
        }
    }

    fn toggle_fullscreen(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.fullscreen = !self.fullscreen;
        window.toggle_fullscreen();
        self.show_controls(cx);
    }

    fn render_play_button(
        &self,
        playing: bool,
        buffering: bool,
        scale: f32,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let label = t(if playing { Str::CdPause } else { Str::CdPlay });
        let button = div().id("np-play").role(Role::Button).aria_label(label);
        self.overlay_focus
            .item_for(OVERLAY_PLAY, &self.play_focus, button, cx)
            .block_mouse_except_scroll()
            .size(px(even(PLAY_BUTTON * scale)))
            .flex_shrink_0()
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .cursor_pointer()
            .bg(black().opacity(0.42))
            .text_color(white())
            .opacity(if buffering { 0.6 } else { 1. })
            .child(
                Icon::new(if playing { Lucide::Pause } else { Lucide::Play })
                    .with_size(px(lucide_size(PLAY_ICON * scale))),
            )
            .on_click(cx.listener(|view, _, _, cx| view.playback.update(cx, |playback, cx| playback.toggle(cx))))
    }

    /// Audio only by the user's pick or because the item has no picture.
    fn shows_audio_only(&self, cx: &App) -> bool {
        let playback = self.playback.read(cx);
        let track = playback.has_video_track();
        let shown = !playback.video || track == Some(false) || (track.is_none() && self.audio_only.get());
        self.audio_only.set(shown);
        shown
    }

    fn render_video(&mut self, current: &QueueItem, fill: bool, cx: &mut Context<Self>) -> AnyElement {
        let audio_only = self.shows_audio_only(cx);
        let playback = self.playback.read(cx);
        let playing = playback.is_playing();
        let buffering = playback.state == PlayState::Buffering;
        let (position, duration) = (playback.position_ms, playback.duration_ms);
        let layer = self.video_layer.clone();
        let video_box = self.video_box.clone();
        let show_video = !audio_only && layer.is_some();
        let controls = self.controls_shown;
        let overlay = controls.then(|| self.render_overlay(current, playing, buffering, (position, duration), cx));

        div()
            .id("video-area")
            .relative()
            .overflow_hidden()
            .map(|frame| if fill { frame.size_full() } else { frame.w_full().aspect_ratio(16. / 9.) })
            .when_some(layer.clone().filter(|_| show_video), |frame, layer| layer.underlay(frame))
            .when(!show_video, |frame| frame.bg(black()).child(self.audio_only_art.clone()))
            .child(
                canvas(
                    |_, _, _| {},
                    move |bounds, _, _, _| {
                        if let Some(layer) = &layer {
                            layer.place(bounds, show_video);
                        }
                        let width = f32::from(bounds.size.width) as i32;
                        let height = f32::from(bounds.size.height) as i32;
                        video_box.set((width, height));
                    },
                )
                .absolute()
                .top_0()
                .left_0()
                .size_full(),
            )
            .when_some(self.caption_overlay(controls, audio_only, cx), |frame, captions| frame.child(captions))
            .when(!audio_only, |frame| frame.children(crate::fps_badge::badge(&self.playback, cx)))
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .on_mouse_down(MouseButton::Left, cx.listener(|view, _, _, _| view.picture_pressed = true))
                    .on_mouse_up_out(MouseButton::Left, cx.listener(|view, _, _, _| view.picture_pressed = false))
                    .on_mouse_up(
                        MouseButton::Left,
                        cx.listener(|view, _, _, cx| {
                            if std::mem::take(&mut view.picture_pressed) {
                                view.toggle_controls(cx);
                            }
                        }),
                    ),
            )
            .children(overlay)
            .when_some(self.failure_panel(controls, cx), |frame, panel| frame.child(panel))
            .into_any_element()
    }

    /// `VideoOverlayControls`: a scrim, the title top left, the transport centered, and the time,
    /// actions and seek bar along the bottom, all growing with the video box.
    fn render_overlay(
        &self,
        current: &QueueItem,
        playing: bool,
        buffering: bool,
        (position, duration): (i64, i64),
        cx: &mut Context<Self>,
    ) -> Div {
        let fullscreen = self.fullscreen;
        let scale = overlay_scale(self.video_box.get());
        let (glyph, hit) = (ICON * scale, HIT * scale);
        let transport_icon = |id: &'static str, icon: Lucide, label: &'static str, cx: &App| {
            on_video(sized_action_icon(id, icon, label, glyph, even(hit)), cx)
        };
        let transport =
            h_flex()
                .w_full()
                .max_w(px(480. * scale))
                .px(px(20.))
                .py(px(14.))
                .justify_evenly()
                .items_center()
                .child(self.overlay_focus.button(
                    OVERLAY_REWIND,
                    transport_icon("np-rewind", Lucide::Rewind, t(Str::CdRewind10), cx).on_click(cx.listener(
                        |view, _, _, cx| view.playback.update(cx, |playback, cx| playback.seek_by(-SKIP_MS, cx)),
                    )),
                    cx,
                ))
                .child(self.overlay_focus.button(
                    OVERLAY_PREVIOUS,
                    transport_icon("np-previous", Lucide::SkipBack, t(Str::CdPrevious), cx).on_click(
                        cx.listener(|view, _, _, cx| view.playback.update(cx, |playback, cx| playback.previous(cx))),
                    ),
                    cx,
                ))
                .child(self.render_play_button(playing, buffering, scale, cx))
                .child(self.overlay_focus.button(
                    OVERLAY_NEXT,
                    transport_icon("np-next", Lucide::SkipForward, t(Str::CdNext), cx).on_click(
                        cx.listener(|view, _, _, cx| view.playback.update(cx, |playback, cx| playback.next(cx))),
                    ),
                    cx,
                ))
                .child(self.overlay_focus.button(
                    OVERLAY_FORWARD,
                    transport_icon("np-forward", Lucide::FastForward, t(Str::CdForward10), cx).on_click(cx.listener(
                        |view, _, _, cx| view.playback.update(cx, |playback, cx| playback.seek_by(SKIP_MS, cx)),
                    )),
                    cx,
                ));

        let actions = if fullscreen { self.overlay_player_actions(current, scale, cx) } else { Vec::new() };
        let time = format!("{} / {}", clock(position), duration_clock(duration));
        let bottom_row = h_flex()
            .gap(px(4.))
            .items_center()
            .child(div().text_size(px(10. * scale)).font_bold().text_color(rgb(OVERLAY_INK)).child(time))
            .child(div().flex_1())
            .children(actions)
            .when_some(self.caption_button(glyph, hit, cx), |row, button| {
                row.child(self.overlay_focus.wrap_for(OVERLAY_CAPTIONS, &self.caption_focus, button, cx))
            })
            .child(
                self.overlay_focus.button(
                    OVERLAY_FULLSCREEN,
                    on_video(
                        sized_action_icon(
                            "np-fullscreen",
                            if fullscreen { Lucide::Minimize } else { Lucide::Maximize },
                            t(if fullscreen { Str::CdExitFullscreen } else { Str::CdFullscreen }),
                            glyph,
                            hit,
                        ),
                        cx,
                    )
                    .on_click(cx.listener(|view, _, window, cx| view.toggle_fullscreen(window, cx))),
                    cx,
                ),
            );
        let playback = self.playback.clone();
        let seek_stop = SliderStop {
            value_text: Some(format!("{} / {}", clock(position), duration_clock(duration)).into()),
            on_step: Some(Rc::new(move |delta, _, cx| {
                playback.update(cx, |playback, cx| playback.seek_by(delta as i64 * SKIP_MS, cx))
            })),
            ..SliderStop::new("np-seek", t(Str::A11yPlaybackPosition))
        };
        let seek = self
            .overlay_focus
            .slider(
                OVERLAY_SEEK,
                &self.seek,
                seek_stop,
                div()
                    .relative()
                    .child(crate::playback::buffered_band(
                        self.playback.read(cx).buffered_fraction(),
                        cx.theme().primary,
                    ))
                    .child(Slider::new(&self.seek).h(px(20.)).accessibility_label(t(Str::A11yPlaybackPosition))),
                cx,
            )
            .block_mouse_except_scroll();

        let artist = self.artist_line(STOP_CHANNEL_OVERLAY, current, cx);
        let title = v_flex()
            .absolute()
            .top_0()
            .left_0()
            .right_0()
            .items_start()
            .px(px(16. * scale))
            .pt(px(11. * scale))
            .child(div().max_w_full().text_size(px(13.5 * scale)).font_bold().truncate().child(current.title.clone()))
            .when_some(artist, |column, artist| {
                column.child(
                    artist.max_w_full().pt(px(3. * scale)).text_size(px(11. * scale)).text_color(rgb(OVERLAY_ARTIST)),
                )
            });

        div()
            .absolute()
            .inset_0()
            .bg(black().opacity(0.24))
            .text_color(white())
            .child(title)
            .child(div().absolute().inset_0().flex().items_center().justify_center().child(transport))
            .child(
                v_flex()
                    .absolute()
                    .bottom_0()
                    .left_0()
                    .right_0()
                    .px(px(11. * scale))
                    .pb(px(8. * scale))
                    .child(bottom_row)
                    .child(seek),
            )
    }

    /// The CC control, absent when the video has no caption tracks: Off, each track, and YouTube's
    /// auto-translations in a submenu; a pick turns captions on.
    fn caption_button(&self, glyph: f32, hit: f32, cx: &App) -> Option<AnyElement> {
        let playback = self.playback.read(cx);
        if playback.caption_tracks.is_empty() {
            return None;
        }
        let (visible, selected) = (playback.captions, playback.caption_selected.clone());
        let is_on = move |track: &CaptionTrack| {
            visible && selected.as_deref().is_some_and(|id| id == track.vss_id || id == track.language_code)
        };
        let (translations, tracks): (Vec<CaptionTrack>, Vec<CaptionTrack>) =
            playback.caption_tracks.iter().cloned().partition(|track| track.kind == AUTO_TRANSLATE_KIND);
        let on_track =
            tracks.iter().chain(&translations).find(|track| is_on(track)).map(|track| track.display_name.clone());
        let state = on_track.unwrap_or_else(|| t(Str::CaptionsOff).to_owned());
        let label = format!("{}, {state}", t(Str::CdCaptions));
        let target = self.playback.clone();
        Some(
            on_video_menu(sized_action_icon("np-captions", Lucide::Captions, label, glyph, hit), cx)
                .track_focus(&self.caption_focus)
                .when(visible, |button| button.text_color(cx.theme().primary))
                .dropdown_menu_with_anchor(Anchor::BottomRight, move |mut menu, window, cx| {
                    let off = target.clone();
                    menu = menu.item(
                        PopupMenuItem::new(t(Str::CaptionsOff))
                            .checked(!visible)
                            .on_click(move |_, _, cx| off.update(cx, |playback, cx| playback.set_captions(false, cx))),
                    );
                    let pick = |track: &CaptionTrack| {
                        let (playback, id) = (target.clone(), track.vss_id.clone());
                        PopupMenuItem::new(track.display_name.clone()).checked(is_on(track)).on_click(
                            move |_, _, cx| playback.update(cx, |playback, cx| playback.select_caption(id.clone(), cx)),
                        )
                    };
                    for track in &tracks {
                        menu = menu.item(pick(track));
                    }
                    if !translations.is_empty() {
                        let current =
                            translations.iter().find(|track| is_on(track)).map(|track| track.display_name.clone());
                        let title = match current {
                            Some(name) => format!("{} · {name}", t(Str::CaptionsAutoTranslate)),
                            None => t(Str::CaptionsAutoTranslate).to_owned(),
                        };
                        let items: Vec<PopupMenuItem> = translations.iter().map(&pick).collect();
                        let items = std::rc::Rc::new(std::cell::RefCell::new(Some(items)));
                        menu = menu.submenu(title, window, cx, move |mut submenu, _, _| {
                            for item in items.borrow_mut().take().into_iter().flatten() {
                                submenu = submenu.item(item);
                            }
                            submenu.min_w(px(240.)).max_w(px(240.)).max_h(px(360.)).scrollable(true)
                        });
                    }
                    menu.scrollable(true)
                })
                .on_open_change(self.hold_while_open(MENU_CAPTIONS))
                .into_any_element(),
        )
    }

    /// The failure on a dark panel at the picture's bottom, above the controls strip while it shows:
    /// the message, then Sign in and Retry when they apply.
    fn failure_panel(&self, controls: bool, cx: &mut Context<Self>) -> Option<Div> {
        let PlayState::Failed(failure) = &self.playback.read(cx).state else { return None };
        let failure = failure.clone();
        let accent = cx.theme().primary;
        let action = |id: &'static str, label: &'static str| {
            Button::new(id).ghost().small().label(label).text_color(accent).font_semibold().block_mouse_except_scroll()
        };
        let strip = CAPTION_ABOVE_CONTROLS * overlay_scale(self.video_box.get());
        let mut actions = h_flex().gap_1().items_center();
        if failure.needs_sign_in() {
            actions = actions.child(
                self.overlay_focus.button(
                    OVERLAY_SIGN_IN,
                    action("np-error-signin", t(Str::SigninButton))
                        .on_click(cx.listener(|_, _, _, cx| cx.emit(NowPlayingEvent::SignIn))),
                    cx,
                ),
            );
        }
        if failure.retry {
            actions = actions.child(self.overlay_focus.button(
                OVERLAY_RETRY,
                action("np-error-retry", t(Str::Retry)).on_click(
                    cx.listener(|view, _, _, cx| view.playback.update(cx, |playback, cx| playback.retry(cx))),
                ),
                cx,
            ));
        }
        Some(
            div()
                .absolute()
                .left_0()
                .right_0()
                .bottom(px(if controls { strip } else { 44. }))
                .flex()
                .justify_center()
                .px_4()
                .child(
                    v_flex()
                        .items_center()
                        .max_w(px(560.))
                        .px(px(14.))
                        .py_2()
                        .rounded(px(8.))
                        .bg(black().opacity(0.8))
                        .child(
                            alert("np-error", failure.text())
                                .text_color(white())
                                .text_size(px(13.))
                                .text_center()
                                .line_clamp(2),
                        )
                        .child(actions),
                ),
        )
    }

    /// The cues on the picture's bottom, left-aligned at YouTube's inset, newest at the bottom;
    /// above the controls strip while it shows.
    fn caption_overlay(&self, controls: bool, audio_only: bool, cx: &App) -> Option<Div> {
        let playback = self.playback.read(cx);
        if !playback.captions || playback.caption_lines.is_empty() {
            return None;
        }
        let look = crate::caption_style::CaptionLook::load(cx.global::<crate::graph::AppGraph>().settings.as_ref());
        let row = CAPTION_ROW * look.scale;
        let (width, height) = self.video_box.get();
        let (width, height) = (width as f32, height as f32);
        let inset = if width.min(height) < 600. {
            if height > width { 0.03 } else { 0.05 }
        } else {
            0.1 + 0.1 * ((width - 600.) / (1920. - 600.)).clamp(0., 1.)
        };
        // The picture is contain-fit: captions go in the bar under it when two rows fit there clear
        // of the controls, else over its bottom.
        let (picture_width, picture_height) = if audio_only {
            (width, height)
        } else if width / height.max(1.) <= VIDEO_RATIO {
            (width, width / VIDEO_RATIO)
        } else {
            (height * VIDEO_RATIO, height)
        };
        let bar = (height - picture_height) / 2.;
        let controls_strip = CAPTION_ABOVE_CONTROLS * overlay_scale(self.video_box.get());
        let under_picture = bar >= CAPTION_TWO_ROWS * look.scale + controls_strip + 8.;
        let side = (width - picture_width) / 2. + picture_width * inset;
        let cue_width = (width - side * 2.).max(1.);
        let mut strip = self.caption_strip.borrow_mut();
        let entries = strip.advance(&playback.caption_lines);
        // Cue heights are estimated from the text (gpui lays text out later), with a box per cue.
        let heights: Vec<f32> =
            entries.iter().map(|(_, text)| cue_height(text, cue_width / look.scale) * look.scale).collect();
        let total: f32 = heights.iter().sum();
        let newest = heights.last().copied().unwrap_or(0.);
        let box_height = total.min((row * 2.).max(newest));
        let tail: f32 = heights.iter().rev().take(strip.tail_count).sum();
        // A single row joining the window scrolls in; anything taller swaps.
        let roll_up = if tail < row * 1.5 { tail } else { 0. };
        let progress = (strip.changed_at.elapsed().as_secs_f32() / CAPTION_SCROLL.as_secs_f32()).min(1.);
        let eased = 1. - (1. - progress).powi(3);
        let offset = (1. - eased) * roll_up;
        let animating = roll_up > 0. && progress < 1.;
        let mut column = v_flex().items_start().absolute().left_0().right_0().bottom(px(-offset));
        for (_, text) in &entries {
            column = column.child(div().pb(px(CAPTION_GAP)).child(crate::caption_style::cue(text.clone(), look)));
        }
        let frames = canvas(
            |_, _, _| {},
            move |_, _, window, _| {
                if animating {
                    window.request_animation_frame();
                }
            },
        )
        .absolute()
        .size_0();
        let window_box = div().relative().h(px(box_height)).overflow_hidden().child(column).child(frames);
        let placed = div().absolute().left_0().right_0().px(px(side));
        Some(if under_picture {
            placed.top(px(bar + picture_height + 8.)).child(window_box)
        } else {
            let bottom = (bar + 12.).max(if controls { controls_strip } else { 12. });
            placed.bottom(px(bottom)).child(window_box)
        })
    }

    /// The artist line; a YouTube video's opens its channel once the player names it.
    fn artist_line(&self, stop: usize, current: &QueueItem, cx: &mut Context<Self>) -> Option<Stateful<Div>> {
        let details = self.playback.read(cx).current_details().cloned();
        let text =
            current.artist.clone().or_else(|| details.as_ref().and_then(|details| details.channel_title.clone()))?;
        let line = div().id(("np-channel", stop)).truncate().child(text.clone());
        let Some(url) = details.and_then(|details| details.channel_url) else { return Some(line) };
        Some(
            self.bar_focus
                .item(stop, line.role(Role::Link).aria_label(text.clone()), cx)
                .cursor_pointer()
                .block_mouse_except_scroll()
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.emit(NowPlayingEvent::OpenChannel { url: url.clone(), title: text.clone() })
                })),
        )
    }

    /// The quality menu's choices (token, label), the preferred token and the playing quality's label.
    fn quality_choices(&self, cx: &App) -> (Vec<(String, String)>, String, String) {
        let playback = self.playback.read(cx);
        let label = |quality: &str, fallback: &str| match quality {
            QUALITY_AUTO => t(Str::VideoQualityAuto).to_owned(),
            QUALITY_AUDIO_ONLY => t(Str::AudioOnly).to_owned(),
            _ => fallback.to_owned(),
        };
        let mut choices = vec![(QUALITY_AUDIO_ONLY.to_owned(), t(Str::AudioOnly).to_owned())];
        if playback.quality_options.is_empty() {
            choices.push((QUALITY_AUTO.to_owned(), t(Str::VideoQualityAuto).to_owned()));
        } else {
            choices
                .extend(playback.quality_options.iter().map(|(quality, text)| (quality.clone(), label(quality, text))));
        }
        let selected = if playback.video { playback.quality_preferred.clone() } else { QUALITY_AUDIO_ONLY.to_owned() };
        let current = if !playback.video {
            t(Str::AudioOnly).to_owned()
        } else {
            let actual = playback.quality_actual.clone().unwrap_or_else(|| QUALITY_AUTO.to_owned());
            choices
                .iter()
                .find(|(quality, _)| *quality == actual)
                .map(|(_, text)| text.clone())
                .unwrap_or_else(|| label(&actual, &actual))
        };
        (choices, selected, current)
    }

    /// The quality chip and its menu; over the video (`Some(scale)`) it is drawn in the overlay's ink at
    /// the overlay's scale.
    fn quality_chip(&self, focus: Option<FocusHandle>, over_video: Option<f32>, cx: &App) -> AnyElement {
        let (choices, selected, current) = self.quality_choices(cx);
        let playback = self.playback.clone();
        let ink: Hsla = rgb(OVERLAY_INK).into();
        Button::new(if over_video.is_some() { "np-quality-overlay" } else { "np-quality" })
            .when_some(focus, |chip, focus| chip.track_focus(&focus))
            .outline()
            .xsmall()
            .rounded(px(6.))
            .h_auto()
            .px(px(8.))
            .py(px(2.))
            .when(over_video.is_some(), |chip| on_video_menu(chip, cx).border_color(ink.opacity(0.6)).text_color(ink))
            .child(div().text_size(px(11. * over_video.unwrap_or(1.))).font_bold().child(current.clone()))
            .accessibility_label(format!("{}, {current}", t(Str::DownloadQuality)))
            .dropdown_menu_with_anchor(
                if over_video.is_some() { Anchor::BottomRight } else { Anchor::TopRight },
                move |mut menu, _, _| {
                    for (quality, text) in &choices {
                        let (playback, pick) = (playback.clone(), quality.clone());
                        menu = menu.item(PopupMenuItem::new(text.clone()).checked(*quality == selected).on_click(
                            move |_, _, cx| playback.update(cx, |playback, cx| playback.set_quality(pick.clone(), cx)),
                        ));
                    }
                    menu
                },
            )
            .on_open_change(self.hold_while_open(MENU_QUALITY))
            .into_any_element()
    }

    /// Full screen has no top bar, so its quality chip, details and speed come onto the controls.
    fn overlay_player_actions(&self, current: &QueueItem, scale: f32, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let (glyph, hit) = (ICON * scale, HIT * scale);
        let quality = self.quality_chip(Some(self.overlay_focus.handle(OVERLAY_QUALITY, cx)), Some(scale), cx);
        let mut actions = vec![self.overlay_focus.wrap(OVERLAY_QUALITY, quality, cx).into_any_element()];
        if let Some(video_id) = current.video_id().map(str::to_owned) {
            let details =
                on_video(sized_action_icon("np-details", Lucide::Info, t(Str::MenuDetailsComments), glyph, hit), cx)
                    .on_click(cx.listener(move |_, _, _, cx| cx.emit(NowPlayingEvent::Details(video_id.clone()))));
            actions.push(self.overlay_focus.button(OVERLAY_DETAILS, details, cx).into_any_element());
        }
        let speed = self.playback.read(cx).effects.speed;
        let speed_value = (speed != 1.).then(|| format!("{}×", (speed * 100.).round() / 100.));
        let label = match &speed_value {
            Some(value) => format!("{}, {value}", t(Str::MenuSpeedPitch)),
            None => t(Str::MenuSpeedPitch).to_owned(),
        };
        let speed = on_video(sized_action_icon("np-speed", Lucide::Gauge, label, glyph, hit), cx)
            .on_click(cx.listener(|_, _, _, cx| cx.emit(NowPlayingEvent::OpenSpeedPitch)));
        actions.push(self.overlay_focus.button(OVERLAY_SPEED, speed, cx).into_any_element());
        if let Some(value) = speed_value {
            actions.push(
                div()
                    .text_size(px(10. * scale))
                    .font_bold()
                    .text_color(rgb(OVERLAY_INK))
                    .child(value)
                    .into_any_element(),
            );
        }
        actions
    }

    /// Close, then download · SponsorBlock · sleep · quality · ⋮ on the right.
    fn render_top_bar(&self, current: &QueueItem, cx: &mut Context<Self>) -> AnyElement {
        let playback = self.playback.read(cx);
        let sleep_remaining = playback.sleep_remaining();
        let sleep_after_current = playback.sleep_after_current;
        let sleep_armed = sleep_remaining.is_some() || sleep_after_current;
        let sponsor_whitelisted = self.sponsor_block.read(cx).current.as_ref().map(|(_, whitelisted)| *whitelisted);
        let target = current.video_id().map(|video_id| DownloadTarget {
            video_id: video_id.to_owned(),
            title: current.title.clone(),
            artist: current.artist.clone(),
            thumbnail_url: current.thumb(640).map(|url| url.to_string()),
        });

        let sleep_tooltip = match sleep_remaining {
            Some(left) => tf(Str::SleepPausingIn, &[&clock(left.as_millis() as i64)]),
            None if sleep_after_current => t(Str::SleepPausingAfterTrack).to_owned(),
            None => t(Str::CdSleepTimer).to_owned(),
        };
        let accent = cx.theme().primary;
        let sleep = self.bar_focus.button(
            STOP_SLEEP,
            sized_action_icon("np-sleep", Lucide::Timer, t(Str::CdSleepTimer), ICON, HIT)
                .toggled(sleep_armed)
                .when(sleep_armed, |button| button.text_color(accent))
                .tooltip(sleep_tooltip)
                .on_click(cx.listener(|view, _, window, cx| {
                    crate::sleep_timer::SleepTimerDialog::open(view.playback.clone(), window, cx)
                })),
            cx,
        );

        let quality = self.quality_chip(Some(self.bar_focus.handle(STOP_QUALITY, cx)), None, cx);

        let view = cx.entity().downgrade();
        let more_target = target.clone();
        let queue_playback = self.playback.clone();
        let more = sized_action_icon("np-more", IconName::EllipsisVertical, t(Str::CdMore), ICON, HIT)
            .track_focus(&self.bar_focus.handle(STOP_MORE, cx))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, cx| {
                let icon = |name: Lucide| Icon::new(name);
                if let Some(target) = &more_target {
                    let (details, video_id) = (view.clone(), target.video_id.clone());
                    menu =
                        menu.item(PopupMenuItem::new(t(Str::MenuDetailsComments)).icon(icon(Lucide::Info)).on_click(
                            move |_, _, cx| {
                                details.update(cx, |_, cx| cx.emit(NowPlayingEvent::Details(video_id.clone()))).ok();
                            },
                        ));
                    let video_id = target.video_id.clone();
                    menu =
                        menu.item(PopupMenuItem::new(t(Str::CdAddToPlaylist)).icon(icon(Lucide::ListPlus)).on_click(
                            move |_, window, cx| crate::yt_playlist::AddToPlaylist::open(video_id.clone(), window, cx),
                        ));
                    let link = format!("https://youtu.be/{}", target.video_id);
                    menu = menu.item(PopupMenuItem::new(t(Str::CdShare)).icon(icon(Lucide::Share2)).on_click(
                        move |_, window, cx| {
                            cx.write_to_clipboard(ClipboardItem::new_string(link.clone()));
                            window.push_notification(t(Str::LinkCopied), cx);
                        },
                    ));
                }
                let queue = queue_playback.clone();
                menu = menu.item(
                    PopupMenuItem::new(t(Str::QueueSaveAsYtPlaylist)).icon(icon(Lucide::ListMusic)).on_click(
                        move |_, window, cx| {
                            let video_ids: Vec<String> = queue
                                .read(cx)
                                .queue
                                .iter()
                                .filter_map(|item| item.video_id().map(str::to_owned))
                                .collect();
                            if video_ids.is_empty() {
                                window.push_notification(t(Str::YtPlaylistQueueEmpty), cx);
                            } else {
                                crate::yt_playlist::CreatePlaylist::open(video_ids, true, window, cx);
                            }
                        },
                    ),
                );
                if let Some(target) = &more_target {
                    let query = format!("{} lyrics", target.title);
                    menu =
                        menu.item(PopupMenuItem::new(t(Str::MenuSearchLyrics)).icon(icon(Lucide::MicVocal)).on_click(
                            move |_, _, cx| {
                                let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
                                cx.open_url(&format!("https://www.google.com/search?q={encoded}"));
                            },
                        ));
                }
                menu = menu.separator();
                let effects = queue_playback.read(cx).effects;
                let speed = format!("{}×", crate::speed_pitch::trimmed(effects.speed));
                let speed_pitch = view.clone();
                menu = menu.item(menu_value_item(t(Str::MenuSpeedPitch), speed).icon(icon(Lucide::Gauge)).on_click(
                    move |_, _, cx| {
                        speed_pitch.update(cx, |_, cx| cx.emit(NowPlayingEvent::OpenSpeedPitch)).ok();
                    },
                ));
                let preset = crate::audio_effects::preset_name(&effects.tone.eq_db).to_owned();
                let equalizer = view.clone();
                menu =
                    menu.item(menu_value_item(t(Str::SoundEq), preset).icon(icon(Lucide::SlidersVertical)).on_click(
                        move |_, _, cx| {
                            equalizer.update(cx, |_, cx| cx.emit(NowPlayingEvent::OpenEqualizer)).ok();
                        },
                    ));
                let reload = queue_playback.clone();
                menu.separator().item(
                    PopupMenuItem::new(t(Str::MenuReloadPlayer))
                        .icon(icon(Lucide::RefreshCw))
                        .on_click(move |_, _, cx| reload.update(cx, |playback, cx| playback.reload(cx))),
                )
            })
            .on_open_change(self.hold_while_open(MENU_MORE));

        h_flex()
            .w_full()
            .h(px(TOP_BAR))
            .px_2()
            .gap(px(5.))
            .items_center()
            .child(
                self.bar_focus.button(
                    STOP_CLOSE,
                    sized_action_icon("np-close", IconName::ChevronDown, t(Str::CdMinimize), ICON, HIT)
                        .on_click(cx.listener(|_, _, _, cx| cx.emit(NowPlayingEvent::Close))),
                    cx,
                ),
            )
            .child(div().flex_1())
            .when_some(target, |bar, target| {
                bar.child(
                    self.bar_focus.button(
                        STOP_DOWNLOAD,
                        sized_action_icon("np-download", Lucide::Download, t(Str::CdDownload), ICON, HIT).on_click(
                            cx.listener(move |_, _, _, cx| cx.emit(NowPlayingEvent::Download(target.clone()))),
                        ),
                        cx,
                    ),
                )
            })
            .when_some(sponsor_whitelisted, |bar, whitelisted| {
                let state = t(if whitelisted { Str::SponsorBlockExcluded } else { Str::SponsorBlockIncluded });
                let mark = Icon::empty().path(crate::assets::SPONSOR_BLOCK_ICON).text_color(if whitelisted {
                    cx.theme().muted_foreground
                } else {
                    accent
                });
                bar.child(
                    self.bar_focus.button(
                        STOP_SPONSOR_BLOCK,
                        glyph_action_icon("np-sponsorblock", mark, t(Str::SettingsSponsorBlock), ICON, HIT)
                            .tooltip(state)
                            .on_click(cx.listener(move |view, _, _, cx| {
                                view.sponsor_block.update(cx, |sponsor, cx| sponsor.set_whitelisted(!whitelisted, cx))
                            })),
                        cx,
                    ),
                )
            })
            .child(sleep)
            .child(self.bar_focus.wrap(STOP_QUALITY, quality, cx))
            .child(self.bar_focus.wrap(STOP_MORE, more, cx))
            .into_any_element()
    }

    /// "Up next", n of total, then repeat and shuffle.
    fn render_queue_header(&self, cx: &mut Context<Self>) -> AnyElement {
        let playback = self.playback.read(cx);
        let (loop_mode, shuffle) = (playback.nav.loop_mode, playback.nav.shuffle);
        let position = tf(Str::A11yXOfY, &[&(playback.index + 1), &playback.queue.len()]);
        let theme = cx.theme();
        let (accent, repeating) = (theme.primary, loop_mode != LoopMode::None);
        h_flex()
            .px(px(20.))
            .py(px(6.))
            .gap_2()
            .items_center()
            .border_b_1()
            .border_color(theme.border)
            .child(heading("np-up-next", 2, t(Str::UpNext)).font_semibold())
            .child(div().text_sm().text_color(theme.muted_foreground).child(position))
            .child(div().flex_1())
            .child(
                self.bar_focus.button(
                    STOP_REPEAT,
                    sized_action_icon(
                        "np-repeat",
                        if loop_mode == LoopMode::One { Lucide::Repeat1 } else { Lucide::Repeat },
                        t(match loop_mode {
                            LoopMode::None => Str::CdRepeatOff,
                            LoopMode::All => Str::CdRepeatAll,
                            LoopMode::One => Str::CdRepeatOne,
                        }),
                        ICON,
                        HIT,
                    )
                    .toggled(repeating)
                    .when(repeating, |button| button.text_color(accent))
                    .on_click(cx.listener(move |view, _, _, cx| {
                        view.playback.update(cx, |playback, cx| playback.set_loop_mode(loop_mode.cycled(), cx))
                    })),
                    cx,
                ),
            )
            .child(
                self.bar_focus.button(
                    STOP_SHUFFLE,
                    sized_action_icon("np-shuffle", Lucide::Shuffle, t(Str::CdShuffle), ICON, HIT)
                        .toggled(shuffle)
                        .when(shuffle, |button| button.text_color(accent))
                        .on_click(cx.listener(move |view, _, _, cx| {
                            view.playback.update(cx, |playback, cx| playback.set_shuffle(!shuffle, cx))
                        })),
                    cx,
                ),
            )
            .into_any_element()
    }

    /// A queue row's ⋮ menu: radio, play next, the video's or the local file's actions, remove.
    fn queue_item_menu(
        &self,
        position: usize,
        active: bool,
        item: &QueueItem,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let (playback, view, item) = (self.playback.clone(), cx.entity().downgrade(), item.clone());
        let more =
            sized_action_icon(("np-queue-more", position), IconName::EllipsisVertical, t(Str::CdMore), ICON, HIT);
        self.queue_focus
            .trailing_button(position, 0, more, cx)
            .text_color(cx.theme().muted_foreground)
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, _| {
                let video_id = item.video_id().map(str::to_owned);
                if video_id.is_some() {
                    let (radio, item) = (playback.clone(), item.clone());
                    menu = menu.item(PopupMenuItem::new(t(Str::MenuStartRadio)).on_click(move |_, _, cx| {
                        radio.update(cx, |playback, cx| playback.start_radio(item.clone(), cx))
                    }));
                }
                if !active {
                    let (next, item) = (playback.clone(), item.clone());
                    menu = menu.item(PopupMenuItem::new(t(Str::QueueNext)).on_click(move |_, _, cx| {
                        next.update(cx, |playback, cx| {
                            playback.remove_at(position, cx);
                            playback.queue_next(item.clone(), cx);
                        })
                    }));
                }
                match (&video_id, &item.source) {
                    (Some(video_id), _) => {
                        let id = video_id.clone();
                        menu =
                            menu.item(PopupMenuItem::new(t(Str::MenuAddToPlaylist)).on_click(move |_, window, cx| {
                                crate::yt_playlist::AddToPlaylist::open(id.clone(), window, cx)
                            }));
                        let target = DownloadTarget {
                            video_id: video_id.clone(),
                            title: item.title.clone(),
                            artist: item.artist.clone(),
                            thumbnail_url: item.thumb(640).map(|url| url.to_string()),
                        };
                        let download = view.clone();
                        menu = menu.item(PopupMenuItem::new(t(Str::CdDownload)).on_click(move |_, _, cx| {
                            download.update(cx, |_, cx| cx.emit(NowPlayingEvent::Download(target.clone()))).ok();
                        }));
                        let link = format!("https://youtu.be/{video_id}");
                        menu = menu.item(PopupMenuItem::new(t(Str::CdShare)).on_click(move |_, window, cx| {
                            cx.write_to_clipboard(ClipboardItem::new_string(link.clone()));
                            window.push_notification(t(Str::LinkCopied), cx);
                        }));
                    }
                    (None, Source::Local(path)) => {
                        if let Some(code) = item.history_code.clone().or_else(|| item.youtube_vid.clone()) {
                            menu = menu.item(PopupMenuItem::new(t(Str::MenuAddToPlaylist)).on_click(
                                move |_, window, cx| {
                                    crate::library_playlist::AddToLocalPlaylist::open(
                                        vec![code.clone()],
                                        None,
                                        window,
                                        cx,
                                    )
                                },
                            ));
                        }
                        let uri = path.to_string_lossy().into_owned();
                        for (album, label) in [(false, Str::MenuGoToArtist), (true, Str::MenuGoToAlbum)] {
                            let (group, uri) = (view.clone(), uri.clone());
                            menu = menu.item(PopupMenuItem::new(t(label)).on_click(move |_, window, cx| {
                                group.update(cx, |view, cx| view.go_to_group(album, uri.clone(), window, cx)).ok();
                            }));
                        }
                    }
                    (None, Source::YouTube(_)) => {}
                }
                let remove = playback.clone();
                menu.item(
                    PopupMenuItem::new(t(Str::RemoveFromQueue))
                        .on_click(move |_, _, cx| remove.update(cx, |playback, cx| playback.remove_at(position, cx))),
                )
            })
    }

    /// Looks up the local file at `uri` and opens its artist (or album) in the Library.
    fn go_to_group(&mut self, album: bool, uri: String, window: &mut Window, cx: &mut Context<Self>) {
        let media = cx.global::<AppGraph>().media.clone();
        let lookup =
            on_core(cx, async move { tokio::task::spawn_blocking(move || media.find_item_by_uri(&uri)).await });
        cx.spawn_in(window, async move |this, cx| {
            let result = lookup.await;
            this.update_in(cx, |_, window, cx| {
                let error: Box<dyn std::error::Error> = match result {
                    Some(Ok(Ok(found))) => {
                        let name = found
                            .and_then(|(item, _)| if album { item.album } else { item.artist })
                            .filter(|name| !name.trim().is_empty());
                        cx.emit(NowPlayingEvent::LibraryGroup { album, name });
                        return;
                    }
                    Some(Ok(Err(error))) => Box::new(error),
                    Some(Err(error)) => Box::new(error),
                    None => return,
                };
                cx.global::<AppGraph>()
                    .log
                    .fork("now-playing")
                    .warn("R95000 queue item lookup failed", Some(error.as_ref()));
                window.push_notification(tf(Str::ErrorGeneric, &[&"R95000"]), cx);
            })
            .ok();
        })
        .detach();
    }

    fn cached_part(&mut self, part: PanePart, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let slot = match part {
            PanePart::TopBar => 0,
            PanePart::Queue { .. } => 1,
        };
        let entity = match self.parts[slot].clone() {
            Some(entity) => {
                entity.update(cx, |cached, cx| {
                    if cached.part != part {
                        cached.part = part;
                        cx.notify();
                    }
                });
                entity
            }
            None => {
                let now_playing = cx.entity();
                let entity = cx.new(|cx| CachedPart {
                    now_playing: now_playing.downgrade(),
                    part,
                    _observe: cx.observe(&now_playing, |_, _, cx| cx.notify()),
                });
                self.parts[slot] = Some(entity.clone());
                entity
            }
        };
        let mut style = StyleRefinement::default();
        style.size.width = Some(relative(1.).into());
        if slot == 0 {
            style.size.height = Some(px(TOP_BAR).into());
            style.flex_shrink = Some(0.);
        } else {
            style.flex_grow = Some(1.);
            style.flex_shrink = Some(1.);
            style.flex_basis = Some(relative(0.).into());
            style.min_size.height = Some(px(0.).into());
        }
        crate::a11y::cached_view(entity, style, window)
    }

    fn render_queue(&self, large: bool, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let playback = self.playback.read(cx);
        let (queue, index) = (playback.queue.clone(), playback.index);
        let theme = cx.theme().clone();
        let thumb = if large { 60. } else { 48. };
        let lifted = self.reorder.lifted();
        self.queue_focus.set_entry((index < queue.len()).then_some(index), cx);
        let stops = self.queue_focus.list_stops(0..queue.len(), window, cx);
        let mut list = v_flex().id("np-queue-list");
        for (position, item) in queue.iter().enumerate() {
            let active = position == index;
            let label = [Some(item.title.clone()), item.artist.clone(), item.duration_label.clone()]
                .into_iter()
                .flatten()
                .collect::<Vec<_>>()
                .join(", ");
            let detail = [item.duration_label.clone(), item.artist.clone()]
                .into_iter()
                .flatten()
                .collect::<Vec<_>>()
                .join(" · ");
            let handle = div()
                .id(("np-queue-handle", position))
                .flex_shrink_0()
                .cursor_grab()
                .text_color(if active { theme.primary } else { theme.input })
                .child(Icon::new(Lucide::GripVertical).small())
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |view, event: &MouseDownEvent, window, cx| {
                        cx.stop_propagation();
                        view.reorder.start(position, event.position.y, window, cx);
                    }),
                );
            let row =
                list_item(h_flex().id(("np-queue-item", position)), position).aria_label(label).aria_selected(active);
            let queue_len = queue.len();
            let row = self.reorder.row(
                position,
                self.queue_focus
                    .item(position, row, cx)
                    .pl_2()
                    .pr_4()
                    .py(px(if large { 10. } else { 8. }))
                    .gap(px(if large { 12. } else { 8. }))
                    .items_center()
                    .cursor_pointer()
                    .when(active, |row| row.bg(theme.primary.opacity(0.1)))
                    .map(|row| {
                        if lifted == Some(position) {
                            crate::reorder::lift(row, cx)
                        } else {
                            row.hover(|style| style.bg(theme.primary.opacity(crate::settings_view::HOVER_ALPHA)))
                        }
                    })
                    .child(handle)
                    .child(
                        div()
                            .size(px(thumb))
                            .flex_shrink_0()
                            .rounded(px(6.))
                            .overflow_hidden()
                            .bg(theme.secondary)
                            .when_some(item.thumb(120), |frame, url| {
                                frame.child(img(url).size_full().rounded(px(6.)).object_fit(ObjectFit::Cover))
                            }),
                    )
                    .child(
                        v_flex()
                            .flex_1()
                            .min_w_0()
                            .child(
                                div()
                                    .text_sm()
                                    .truncate()
                                    .font_semibold()
                                    .when(active, |text| text.text_color(theme.primary))
                                    .child(item.title.clone()),
                            )
                            .when(!detail.is_empty(), |column| {
                                column
                                    .child(div().text_xs().text_color(theme.muted_foreground).truncate().child(detail))
                            }),
                    )
                    .child(self.queue_item_menu(position, active, item, cx))
                    .on_key_down(cx.listener(move |view, event: &KeyDownEvent, window, cx| {
                        let modifiers = &event.keystroke.modifiers;
                        if !modifiers.alt || modifiers.platform || modifiers.control || modifiers.shift {
                            return;
                        }
                        let target = match event.keystroke.key.as_str() {
                            "up" => position.checked_sub(1),
                            "down" => Some(position + 1).filter(|target| *target < queue_len),
                            _ => return,
                        };
                        cx.stop_propagation();
                        let Some(target) = target else { return };
                        view.reorder.moved(position, target, cx);
                        view.playback.update(cx, |playback, cx| playback.move_item(position, target, cx));
                        window.focus(&view.queue_focus.handle(target, cx), cx);
                    }))
                    .on_click(cx.listener(move |view, _, _, cx| {
                        view.playback.update(cx, |playback, cx| playback.jump_to(position, cx))
                    })),
            );
            list = list.child(row);
        }
        let list = stops.wrap(list, Some(t(Str::UpNext).into()), cx);
        let tracker = self.reorder.tracker(
            queue.len(),
            cx.listener(|view, event: &ReorderEvent, _, cx| {
                if let ReorderEvent::Moved { from, to } = *event {
                    view.playback.update(cx, |playback, cx| playback.move_item(from, to, cx));
                }
            }),
        );
        div()
            .id("np-queue")
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .track_scroll(&self.queue_scroll)
            .child(list)
            .child(tracker)
            .into_any_element()
    }
}

impl Render for NowPlaying {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.ensure_video_layer(window, cx);
        #[cfg(not(target_os = "macos"))]
        if let Some(layer) = &self.video_layer
            && let Err(error) = layer.refresh(window)
        {
            let log = &cx.global::<crate::graph::AppGraph>().log;
            log.warn("video picture could not leave the atlas", Some(error.as_ref()));
        }
        let painted = self.video_box.get();
        if painted.0 > 0 && painted != self.reported_box {
            self.reported_box = painted;
            self.playback.update(cx, |playback, cx| playback.set_viewport(painted.0, painted.1, cx));
        }
        self.keyboard_in_controls.set(window.last_input_was_keyboard() && self.overlay_focus.has_focus(window));
        let Some(current) = self.playback.read(cx).current().cloned() else { return div().into_any_element() };
        if self.fullscreen && !window.is_fullscreen() {
            self.fullscreen = false;
        }
        if self.fullscreen {
            return div()
                .size_full()
                .capture_key_down(cx.listener(Self::key_shows_controls))
                .child(self.render_video(&current, true, cx))
                .into_any_element();
        }
        let viewport = window.viewport_size();
        let (width, height) = (f32::from(viewport.width), f32::from(viewport.height));
        let theme = cx.theme().clone();
        let top_bar = self.cached_part(PanePart::TopBar, window, cx);
        if width > height && width >= 640. {
            let pane = (0.3 * width).clamp(320., 480.);
            let queue = self.cached_part(PanePart::Queue { large: pane >= 380. }, window, cx);
            let video = self.render_video(&current, true, cx);
            return h_flex()
                .size_full()
                .capture_key_down(cx.listener(Self::key_shows_controls))
                .child(div().flex_1().min_w_0().h_full().child(video))
                .child(
                    v_flex()
                        .w(px(pane))
                        .h_full()
                        .flex_shrink_0()
                        .bg(theme.background)
                        .border_l_1()
                        .border_color(theme.border)
                        .child(top_bar)
                        .child(queue),
                )
                .into_any_element();
        }
        let video = self.render_video(&current, false, cx);
        let queue = self.cached_part(PanePart::Queue { large: false }, window, cx);
        let artist = self.artist_line(STOP_CHANNEL, &current, cx);
        v_flex()
            .size_full()
            .capture_key_down(cx.listener(Self::key_shows_controls))
            .child(div().bg(theme.background).child(top_bar))
            .child(video)
            .child(
                v_flex()
                    .flex_1()
                    .min_h_0()
                    .bg(theme.background)
                    .child(
                        v_flex()
                            .items_center()
                            .px(px(20.))
                            .pt(px(18.))
                            .pb_1()
                            .child(div().font_semibold().truncate().child(current.title.clone()))
                            .when_some(artist, |column, artist| {
                                column.child(artist.pt_1().text_sm().text_color(theme.muted_foreground))
                            }),
                    )
                    .child(queue),
            )
            .into_any_element()
    }
}
