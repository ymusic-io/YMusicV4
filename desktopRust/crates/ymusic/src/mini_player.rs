//! The persistent mini-player bar: what's playing, transport controls, seek and volume.

use std::rc::Rc;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::base::slider::{SliderEvent, SliderState};
use gpui_kit::component::slider::Slider;
use gpui_kit::component::{ActiveTheme, Disableable, Icon, StyledExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, SliderStop};
use crate::action_icon::{HIT, action_icon, lucide_size, sized_action_icon};
use crate::playback::{PlayState, Playback, QueueItem};
use crate::queue_nav::LoopMode;
use crate::spinner::Spinner;

/// The user asked to open (or close) Now Playing.
pub struct ToggleNowPlaying;

pub struct MiniPlayer {
    playback: Entity<Playback>,
    seek: Entity<SliderState>,
    volume: Entity<SliderState>,
    dragging_seek: bool,
    /// What the bar last drew from playback.
    shown: Option<Shown>,
    /// Arrow keys move between the bar's controls.
    focus: FocusList,
    _subscriptions: Vec<Subscription>,
}

/// What the bar shows from playback, at the resolution it shows it: the time to the second, the
/// seek bar moving with it. Each redraw of the bar redraws the shell around it, so a progress tick
/// that changes none of this draws nothing.
#[derive(PartialEq)]
struct Shown {
    item: Option<QueueItem>,
    state: PlayState,
    second: i64,
    duration_ms: i64,
    buffered_second: i64,
    has_next: bool,
    loop_mode: LoopMode,
    shuffle: bool,
    sleep_second: Option<u64>,
    sleep_after_current: bool,
    volume: f32,
    tuned: bool,
}

impl Shown {
    fn of(playback: &Playback) -> Self {
        Self {
            item: playback.current().cloned(),
            state: playback.state.clone(),
            second: playback.position_ms / 1000,
            duration_ms: playback.duration_ms,
            buffered_second: playback.buffered_ms / 1000,
            has_next: playback.has_next(),
            loop_mode: playback.nav.loop_mode,
            shuffle: playback.nav.shuffle,
            sleep_second: playback.sleep_remaining().map(|left| left.as_secs()),
            sleep_after_current: playback.sleep_after_current,
            volume: playback.volume,
            tuned: (playback.effects.speed, playback.effects.pitch) != (1.0, 1.0),
        }
    }
}

impl EventEmitter<ToggleNowPlaying> for MiniPlayer {}

impl MiniPlayer {
    pub fn new(playback: Entity<Playback>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let seek = cx.new(|_| SliderState::new().min(0.).max(1.).step(0.001));
        let initial_volume = playback.read(cx).volume;
        let volume = cx.new(|_| SliderState::new().min(0.).max(1.).step(0.01).default_value(initial_volume));
        let subscriptions = vec![
            cx.observe_in(&playback, window, |player, playback, window, cx| {
                let playback = playback.read(cx);
                let shown = Shown::of(playback);
                if player.shown.as_ref() == Some(&shown) {
                    return;
                }
                if !player.dragging_seek {
                    let fraction = if playback.duration_ms > 0 {
                        (playback.position_ms as f32 / playback.duration_ms as f32).clamp(0., 1.)
                    } else {
                        0.
                    };
                    player.seek.update(cx, |slider, cx| slider.set_value(fraction, window, cx));
                }
                if player.volume.read(cx).value().end() != shown.volume {
                    let volume = shown.volume;
                    player.volume.update(cx, |slider, cx| slider.set_value(volume, window, cx));
                }
                player.shown = Some(shown);
                cx.notify();
            }),
            cx.subscribe(&seek, |player, _, event: &SliderEvent, cx| match event {
                SliderEvent::Change(_) => player.dragging_seek = true,
                SliderEvent::Release(value) => {
                    player.dragging_seek = false;
                    let fraction = value.end();
                    player.playback.update(cx, |playback, cx| {
                        let target = (playback.duration_ms as f32 * fraction) as i64;
                        playback.seek(target, cx);
                    });
                }
            }),
            cx.subscribe(&volume, |player, _, event: &SliderEvent, cx| {
                let (SliderEvent::Change(value) | SliderEvent::Release(value)) = event;
                let level = value.end();
                player.playback.update(cx, |playback, cx| playback.set_volume(level, cx));
            }),
        ];
        Self {
            playback,
            seek,
            volume,
            dragging_seek: false,
            shown: None,
            focus: FocusList::new(None),
            _subscriptions: subscriptions,
        }
    }
}

const STOP_INFO: usize = 0;
const STOP_SHUFFLE: usize = 1;
const STOP_PREVIOUS: usize = 2;
const STOP_PLAY: usize = 3;
const STOP_NEXT: usize = 4;
const STOP_REPEAT: usize = 5;
const STOP_EQUALIZER: usize = 6;
const STOP_SPEED: usize = 7;
const STOP_SLEEP: usize = 8;
const STOP_SEEK: usize = 9;
const STOP_VOLUME: usize = 10;
/// One arrow press on the bar's seek slider and volume.
const SEEK_STEP_MS: i64 = 10_000;
const VOLUME_STEP: f32 = 0.05;

impl MiniPlayer {
    fn sleep_button(&self, remaining: Option<std::time::Duration>, after_current: bool, cx: &mut Context<Self>) -> Div {
        let tooltip = match remaining {
            Some(left) => tf(Str::SleepPausingIn, &[&clock(left.as_millis() as i64)]),
            None if after_current => t(Str::SleepPausingAfterTrack).to_owned(),
            None => t(Str::CdSleepTimer).to_owned(),
        };
        let armed = remaining.is_some() || after_current;
        self.focus.button(
            STOP_SLEEP,
            action_icon("sleep-timer", Lucide::Timer, t(Str::CdSleepTimer))
                .toggled(armed)
                .when(armed, |button| button.text_color(cx.theme().primary))
                .tooltip(tooltip)
                .on_click(cx.listener(|player, _, window, cx| {
                    crate::sleep_timer::SleepTimerDialog::open(player.playback.clone(), window, cx)
                })),
            cx,
        )
    }
}

pub fn clock(ms: i64) -> String {
    let seconds = ms.max(0) / 1000;
    let (hours, minutes, seconds) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    if hours > 0 { format!("{hours}:{minutes:02}:{seconds:02}") } else { format!("{minutes}:{seconds:02}") }
}

/// A duration as the JVM app shows it: `--:--` until it is known.
pub fn duration_clock(ms: i64) -> String {
    if ms > 0 { clock(ms) } else { "--:--".to_owned() }
}

/// The track info takes this share of the bar once that beats `BAR_INFO_MIN`.
const BAR_INFO_SHARE: f32 = 0.3;
const BAR_INFO_MIN: f32 = 300.;
const BAR_SEEK_MAX: f32 = 560.;
const BAR_VOLUME_WIDTH: f32 = 120.;
/// Below this the side controls drop out; Now Playing still has them.
const BAR_SIDE_MIN: f32 = 900.;
/// The sleep timer joins the side controls from this width.
const BAR_SLEEP_MIN: f32 = 1100.;
const BAR_PLAY: f32 = 36.;
const BAR_SMALL_ICON: f32 = 20.;
const BAR_SMALL_HIT: f32 = 36.;

impl MiniPlayer {
    fn now_playing_label(item: &QueueItem) -> String {
        [Some(t(Str::A11yNowPlaying).to_owned()), Some(item.title.clone()), item.artist.clone()]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(", ")
    }

    /// The artist, or the failure in its place.
    fn subtitle(item: &QueueItem, state: &PlayState, cx: &App) -> Option<(String, Hsla)> {
        let theme = cx.theme();
        match state {
            PlayState::Failed(failure) => Some((failure.text(), theme.danger)),
            _ => item.artist.clone().map(|artist| (artist, theme.muted_foreground)),
        }
    }

    fn cover(item: &QueueItem, corner_radius: f32, cx: &App) -> Div {
        div()
            .overflow_hidden()
            .rounded(px(corner_radius))
            .bg(cx.theme().secondary)
            .flex_shrink_0()
            .when_some(item.thumb(320), |frame, url| {
                frame.child(img(url).size_full().rounded(px(corner_radius)).object_fit(ObjectFit::Cover))
            })
    }

    fn play_label(state: &PlayState) -> &'static str {
        t(if matches!(state, PlayState::Playing | PlayState::Buffering) { Str::CdPause } else { Str::CdPlay })
    }

    /// The bar's filled play circle.
    fn play_circle(&self, state: &PlayState, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme();
        let playing = matches!(state, PlayState::Playing | PlayState::Buffering);
        let circle = div().id("play-pause").role(Role::Button).aria_label(Self::play_label(state));
        self.focus
            .item(STOP_PLAY, circle, cx)
            .size(px(BAR_PLAY))
            .flex_shrink_0()
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .cursor_pointer()
            .bg(theme.foreground)
            .text_color(theme.background)
            .child(if *state == PlayState::Buffering {
                Spinner::new().small().into_any_element()
            } else {
                Icon::new(if playing { Lucide::Pause } else { Lucide::Play })
                    .size(px(lucide_size(BAR_SMALL_ICON)))
                    .into_any_element()
            })
            .on_click(cx.listener(|player, _, _, cx| {
                cx.stop_propagation();
                player.playback.update(cx, |playback, cx| playback.toggle(cx));
            }))
    }

    /// Rail windows: a full-width bar under the rail and the content. What is playing on the left
    /// (opens Now Playing), transport over the seek bar in the middle, sound · speed · sleep · volume
    /// on the right; a click on the bar's background opens Now Playing too.
    fn render_bar(&mut self, item: QueueItem, width: f32, cx: &mut Context<Self>) -> AnyElement {
        let playback = self.playback.read(cx);
        let theme = cx.theme().clone();
        let state = playback.state.clone();
        let (position, duration) = (playback.position_ms, playback.duration_ms);
        let buffered = playback.buffered_fraction();
        let has_next = playback.has_next();
        let (loop_mode, shuffle) = (playback.nav.loop_mode, playback.nav.shuffle);
        let sleep_remaining = playback.sleep_remaining();
        let sleep_after_current = playback.sleep_after_current;
        let volume = playback.volume;
        let tuned = (playback.effects.speed, playback.effects.pitch) != (1.0, 1.0);
        let subtitle = Self::subtitle(&item, &state, cx);
        let side = width >= BAR_SIDE_MIN;
        let sleep = width >= BAR_SLEEP_MIN;
        let info_width = (width * BAR_INFO_SHARE).max(BAR_INFO_MIN);
        let repeat_label = t(match loop_mode {
            LoopMode::None => Str::CdRepeatOff,
            LoopMode::All => Str::CdRepeatAll,
            LoopMode::One => Str::CdRepeatOne,
        });
        let info = h_flex().id("now-playing-toggle").role(Role::Button).aria_label(Self::now_playing_label(&item));
        let info = self
            .focus
            .item(STOP_INFO, info, cx)
            .w(px(info_width))
            .flex_shrink_0()
            .gap_2()
            .min_w_0()
            .rounded(px(4.))
            .cursor_pointer()
            .on_click(cx.listener(|_, _, _, cx| {
                cx.stop_propagation();
                cx.emit(ToggleNowPlaying);
            }))
            .child(Self::cover(&item, 4., cx).size(px(48.)))
            .child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .child(div().text_sm().font_semibold().truncate().child(item.title.clone()))
                    .when_some(subtitle, |column, (line, color)| {
                        column.child(div().text_xs().text_color(color).truncate().child(line))
                    }),
            );
        let transport = v_flex()
            .flex_1()
            .min_w_0()
            .items_center()
            .child(
                h_flex()
                    .gap_2()
                    .items_center()
                    .child(
                        self.focus.button(
                            STOP_SHUFFLE,
                            sized_action_icon(
                                "shuffle",
                                Lucide::Shuffle,
                                t(Str::CdShuffle),
                                BAR_SMALL_ICON,
                                BAR_SMALL_HIT,
                            )
                            .toggled(shuffle)
                            .when(shuffle, |button| button.text_color(cx.theme().primary))
                            .on_click(cx.listener(move |player, _, _, cx| {
                                player.playback.update(cx, |playback, cx| playback.set_shuffle(!shuffle, cx))
                            })),
                            cx,
                        ),
                    )
                    .child(self.focus.button(
                        STOP_PREVIOUS,
                        action_icon("previous", Lucide::SkipBack, t(Str::CdPrevious)).on_click(cx.listener(
                            |player, _, _, cx| player.playback.update(cx, |playback, cx| playback.previous(cx)),
                        )),
                        cx,
                    ))
                    .child(self.play_circle(&state, cx))
                    .child(self.focus.button(
                        STOP_NEXT,
                        action_icon("next", Lucide::SkipForward, t(Str::CdNext)).disabled(!has_next).on_click(
                            cx.listener(|player, _, _, cx| {
                                player.playback.update(cx, |playback, cx| playback.next(cx))
                            }),
                        ),
                        cx,
                    ))
                    .child(
                        self.focus.button(
                            STOP_REPEAT,
                            sized_action_icon(
                                "repeat",
                                if loop_mode == LoopMode::One { Lucide::Repeat1 } else { Lucide::Repeat },
                                repeat_label,
                                BAR_SMALL_ICON,
                                BAR_SMALL_HIT,
                            )
                            .toggled(loop_mode != LoopMode::None)
                            .when(loop_mode != LoopMode::None, |button| button.text_color(cx.theme().primary))
                            .on_click(cx.listener(move |player, _, _, cx| {
                                player
                                    .playback
                                    .update(cx, |playback, cx| playback.set_loop_mode(loop_mode.cycled(), cx))
                            })),
                            cx,
                        ),
                    ),
            )
            .child(
                h_flex()
                    .w_full()
                    .max_w(px(BAR_SEEK_MAX))
                    .gap_2()
                    .items_center()
                    .child(div().text_xs().font_semibold().text_color(theme.muted_foreground).child(clock(position)))
                    .child({
                        let playback = self.playback.clone();
                        let stop = SliderStop {
                            value_text: Some(format!("{} / {}", clock(position), duration_clock(duration)).into()),
                            on_step: Some(Rc::new(move |delta, _, cx| {
                                playback.update(cx, |playback, cx| playback.seek_by(delta as i64 * SEEK_STEP_MS, cx))
                            })),
                            ..SliderStop::new("bar-seek", t(Str::A11yPlaybackPosition))
                        };
                        let slider = div()
                            .relative()
                            .child(crate::playback::buffered_band(buffered, theme.primary))
                            .child(Slider::new(&self.seek).accessibility_label(t(Str::A11yPlaybackPosition)));
                        self.focus.slider(STOP_SEEK, &self.seek, stop, slider, cx).flex_1()
                    })
                    .child(
                        div()
                            .text_xs()
                            .font_semibold()
                            .text_color(theme.muted_foreground)
                            .child(duration_clock(duration)),
                    ),
            );
        let side_controls = side.then(|| {
            h_flex()
                .flex_shrink_0()
                .items_center()
                .child(
                    self.focus.button(
                        STOP_EQUALIZER,
                        sized_action_icon("equalizer", Lucide::SlidersHorizontal, t(Str::SoundEq), BAR_SMALL_ICON, HIT)
                            .on_click(cx.listener(|player, _, window, cx| {
                                crate::equalizer::EqualizerDialog::open(player.playback.clone(), window, cx)
                            })),
                        cx,
                    ),
                )
                .child(
                    self.focus.button(
                        STOP_SPEED,
                        sized_action_icon("speed-pitch", Lucide::Gauge, t(Str::MenuSpeedPitch), BAR_SMALL_ICON, HIT)
                            .toggled(tuned)
                            .when(tuned, |button| button.text_color(cx.theme().primary))
                            .on_click(cx.listener(|player, _, window, cx| {
                                crate::speed_pitch::SpeedPitchDialog::open(player.playback.clone(), window, cx)
                            })),
                        cx,
                    ),
                )
                .when(sleep, |row| row.child(self.sleep_button(sleep_remaining, sleep_after_current, cx)))
                .child(
                    div().px_1().child(
                        Icon::new(if volume <= 0. {
                            Lucide::VolumeX
                        } else if volume < 0.5 {
                            Lucide::Volume1
                        } else {
                            Lucide::Volume2
                        })
                        .size(px(lucide_size(BAR_SMALL_ICON)))
                        .text_color(theme.muted_foreground),
                    ),
                )
                .child({
                    let label = tf(Str::SoundVolume, &[&((volume * 100.0) as i32)]);
                    let stop = SliderStop { step: Some(VOLUME_STEP), ..SliderStop::new("bar-volume", label.clone()) };
                    let slider = Slider::new(&self.volume).accessibility_label(label);
                    self.focus.slider(STOP_VOLUME, &self.volume, stop, slider, cx).w(px(BAR_VOLUME_WIDTH))
                })
        });
        h_flex()
            .id("player-bar")
            .w_full()
            .gap_4()
            .px_4()
            .py_2()
            .items_center()
            .border_t_1()
            .border_color(theme.border)
            .bg(crate::shell::chrome_tone(cx))
            .on_click(cx.listener(|_, _, _, cx| cx.emit(ToggleNowPlaying)))
            .child(info)
            .child(transport)
            .children(side_controls)
            .into_any_element()
    }

    /// Drawer windows: the phone mini player. The 16:9 cover, then a thin progress line over the
    /// title with play/pause and next; a click anywhere else opens Now Playing.
    fn render_phone(&mut self, item: QueueItem, cx: &mut Context<Self>) -> AnyElement {
        let playback = self.playback.read(cx);
        let theme = cx.theme().clone();
        let state = playback.state.clone();
        let fraction = if playback.duration_ms > 0 {
            (playback.position_ms as f32 / playback.duration_ms as f32).clamp(0., 1.)
        } else {
            0.
        };
        let has_next = playback.has_next();
        let playing = matches!(state, PlayState::Playing | PlayState::Buffering);
        let subtitle = Self::subtitle(&item, &state, cx);
        let toggle = h_flex().id("now-playing-toggle").role(Role::Button).aria_label(Self::now_playing_label(&item));
        self.focus
            .item(STOP_INFO, toggle, cx)
            .w_full()
            .h(px(56.))
            .border_t_1()
            .border_color(theme.border)
            .cursor_pointer()
            .on_click(cx.listener(|_, _, _, cx| cx.emit(ToggleNowPlaying)))
            .child(Self::cover(&item, 0., cx).h_full().aspect_ratio(16. / 9.))
            .child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .h_full()
                    .bg(crate::shell::chrome_tone(cx))
                    .child(
                        div()
                            .w_full()
                            .h(px(4.))
                            .bg(theme.border)
                            .child(div().h_full().w(relative(fraction)).bg(theme.primary)),
                    )
                    .child(
                        h_flex()
                            .flex_1()
                            .pl_3()
                            .pr_2()
                            .items_center()
                            .child(
                                v_flex()
                                    .flex_1()
                                    .min_w_0()
                                    .child(div().text_sm().font_semibold().truncate().child(item.title.clone()))
                                    .when_some(subtitle, |column, (line, color)| {
                                        column.child(div().text_xs().text_color(color).truncate().child(line))
                                    }),
                            )
                            .child(if state == PlayState::Buffering {
                                div()
                                    .size(px(HIT))
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .child(Spinner::new())
                                    .into_any_element()
                            } else {
                                self.focus
                                    .button(
                                        STOP_PLAY,
                                        action_icon(
                                            "play-pause",
                                            if playing { Lucide::Pause } else { Lucide::Play },
                                            Self::play_label(&state),
                                        )
                                        .on_click(cx.listener(
                                            |player, _, _, cx| {
                                                player.playback.update(cx, |playback, cx| playback.toggle(cx))
                                            },
                                        )),
                                        cx,
                                    )
                                    .into_any_element()
                            })
                            .child(self.focus.button(
                                STOP_NEXT,
                                action_icon("next", Lucide::SkipForward, t(Str::CdNext)).disabled(!has_next).on_click(
                                    cx.listener(|player, _, _, cx| {
                                        player.playback.update(cx, |playback, cx| playback.next(cx))
                                    }),
                                ),
                                cx,
                            )),
                    ),
            )
            .into_any_element()
    }
}

impl Render for MiniPlayer {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let Some(item) = self.playback.read(cx).current().cloned() else { return div().into_any_element() };
        let viewport = window.viewport_size();
        let (width, height) = (f32::from(viewport.width), f32::from(viewport.height));
        if width >= 750. && height >= 480. { self.render_bar(item, width, cx) } else { self.render_phone(item, cx) }
    }
}
