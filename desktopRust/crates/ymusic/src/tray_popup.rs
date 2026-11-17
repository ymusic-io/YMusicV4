//! The tray icon's mini player (JVM `MiniPlayer.kt`): a small card under the icon with what is
//! playing, open the app, close, a seek bar and the transport. It closes when it loses focus, like
//! a system notification.

use futures::channel::mpsc::UnboundedSender;
use gpui_kit::base::slider::{SliderEvent, SliderState};
use gpui_kit::component::slider::Slider;
use gpui_kit::component::{ActiveTheme, Disableable, Icon, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t};
use ym_image_loader::ImageSource;
use ym_image_loader::async_image::async_image;

use crate::action_icon::{auto_mirrored, glyph_action_icon, hit_target};
use crate::assets::MaterialIcon;
use crate::desktop::TrayCommand;
use crate::mini_player::PlayClock;
use crate::playback::Playback;
use crate::spinner::Spinner;

pub const WIDTH: f32 = 340.;
pub const HEIGHT: f32 = 132.;
const MARGIN: f32 = 8.;
const COVER: f32 = 44.;
const PLACEHOLDER_ICON: f32 = 24.;
const HEADER_ICON: f32 = 16.;
const HEADER_HIT: f32 = 30.;
const SKIP_ICON: f32 = 20.;
const SKIP_HIT: f32 = 32.;
const PLAY_ICON: f32 = 26.;
const PLAY_HIT: f32 = 36.;
/// The JVM app's `DefaultSkipSegmentColor`.
const SKIP_SEGMENT_COLOR: u32 = 0xFFC107;
/// gpui-component's slider: a 6 high bar centered in its 24 high track, a 16 wide thumb.
const SLIDER_BAR_TOP: f32 = 9.;
const SLIDER_BAR_HEIGHT: f32 = 6.;
const THUMB_RADIUS: f32 = 8.;

/// The popup closed itself (focus went elsewhere, or Esc).
pub struct Closed;

pub struct TrayPopup {
    playback: Entity<Playback>,
    commands: UnboundedSender<TrayCommand>,
    seek: Entity<SliderState>,
    dragging_seek: bool,
    focus: FocusHandle,
    _subscriptions: Vec<Subscription>,
}

impl EventEmitter<Closed> for TrayPopup {}

impl TrayPopup {
    /// Beside the tray icon at `anchor` (logical screen bounds): under it when the icon sits in
    /// the top half of its display (the macOS menu bar), above it otherwise (a bottom taskbar),
    /// centered on it and kept inside the display. The origin is relative to the returned display.
    pub fn placement(anchor: Bounds<Pixels>, cx: &App) -> Option<(DisplayId, Bounds<Pixels>)> {
        let center = anchor.center();
        let display = cx
            .displays()
            .into_iter()
            .find(|display| display.bounds().contains(&center))
            .or_else(|| cx.primary_display())?;
        let area = display.bounds();
        let (width, height, margin) = (px(WIDTH), px(HEIGHT), px(MARGIN));
        let x = (center.x - width / 2.).max(area.left() + margin).min(area.right() - width - margin);
        let y = if center.y < area.center().y { anchor.bottom() + margin } else { anchor.top() - height - margin };
        let origin = point(x - area.origin.x, y - area.origin.y);
        Some((display.id(), Bounds::new(origin, size(width, height))))
    }

    pub fn new(
        playback: Entity<Playback>,
        commands: UnboundedSender<TrayCommand>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let seek = cx.new(|_| SliderState::new().min(0.).max(1.).step(0.001));
        let focus = cx.focus_handle();
        focus.focus(window, cx);
        let subscriptions = vec![
            cx.observe_in(&playback, window, |popup, playback, window, cx| {
                if !popup.dragging_seek {
                    let playback = playback.read(cx);
                    let fraction = if playback.duration_ms > 0 {
                        (playback.position_ms as f32 / playback.duration_ms as f32).clamp(0., 1.)
                    } else {
                        0.
                    };
                    popup.seek.update(cx, |slider, cx| slider.set_value(fraction, window, cx));
                }
                cx.notify();
            }),
            cx.subscribe(&seek, |popup, _, event: &SliderEvent, cx| match event {
                // A redraw moves the skip marks' gap with the dragged thumb.
                SliderEvent::Change(_) => {
                    popup.dragging_seek = true;
                    cx.notify();
                }
                SliderEvent::Release(value) => {
                    popup.dragging_seek = false;
                    let fraction = value.end();
                    popup.playback.update(cx, |playback, cx| {
                        let target = (playback.duration_ms as f32 * fraction) as i64;
                        playback.seek(target, cx);
                    });
                }
            }),
            cx.observe_window_activation(window, |popup, window, cx| {
                if !window.is_window_active() {
                    popup.close(window, cx);
                }
            }),
        ];
        Self { playback, commands, seek, dragging_seek: false, focus, _subscriptions: subscriptions }
    }

    fn close(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        cx.emit(Closed);
        window.remove_window();
    }

    fn command(&self, command: TrayCommand) {
        // The shell's command loop ends only with the shell itself, and this window with it.
        let _ = self.commands.unbounded_send(command);
    }
}

/// SponsorBlock spans as track fractions (the JVM app's `skipSegmentFractions`): one ending within a
/// second of the end runs to it.
fn segment_fractions(segments: &[(i64, i64)], duration_ms: i64) -> Vec<(f32, f32)> {
    if duration_ms <= 0 {
        return Vec::new();
    }
    let fraction = |ms: i64| (ms as f32 / duration_ms as f32).clamp(0., 1.);
    segments
        .iter()
        .map(|&(start, end)| (fraction(start), if end >= duration_ms - 1_000 { 1. } else { fraction(end) }))
        .filter(|(start, end)| end > start)
        .collect()
}

impl Render for TrayPopup {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let playback = self.playback.read(cx);
        let item = playback.current().cloned();
        let (playing, loading) = (playback.state.is_playing(), item.is_some() && playback.is_buffering_or_loading());
        let (position, duration) = (playback.position_ms, playback.duration_ms);
        let (live, seekable) = (playback.live, playback.seekable);
        let segments = segment_fractions(playback.skip_segments(), duration);
        let title =
            item.as_ref().map_or_else(|| t(Str::MiniPlayerNothingPlaying).to_owned(), |item| item.title.clone());
        let artist = item.as_ref().and_then(|item| item.artist.clone());
        let cover = div()
            .size(px(COVER))
            .flex_shrink_0()
            .rounded(px(5.))
            .overflow_hidden()
            .bg(theme.secondary)
            .flex()
            .items_center()
            .justify_center()
            .child(Icon::new(MaterialIcon::MusicNote).size(px(PLACEHOLDER_ICON)).text_color(theme.muted_foreground))
            .when_some(item.as_ref().and_then(|item| item.display_thumbnail_url()), |frame, url| {
                let identity = ym_image_loader::image_cache_key(&url);
                frame.child(
                    async_image(Some(identity), vec![ImageSource::new(url.to_string())])
                        .absolute()
                        .inset_0()
                        .size_full()
                        .rounded(px(5.))
                        .object_fit(ObjectFit::Cover),
                )
            });
        let play_label = t(if playing { Str::CdPause } else { Str::CdPlay });
        let play_clock = PlayClock::new(position, duration, live);
        let elapsed = play_clock.elapsed.clone().unwrap_or_default();
        let total_color = play_clock.total_color(theme.primary, theme.muted_foreground);
        let total = match play_clock.live_button("tray-live", seekable, &self.playback) {
            Some(button) => button.h(px(20.)).px(px(4.)).text_xs().text_color(total_color).into_any_element(),
            None => div().text_xs().text_color(total_color).child(play_clock.total.clone()).into_any_element(),
        };
        // Over the played fill, as the JVM app paints them; the thumb above them is the gap they leave.
        let thumb = self.seek.read(cx).percentage().end;
        let skip_marks = canvas(
            |_, _, _| {},
            move |bounds, _, window, _| {
                let width = f32::from(bounds.size.width);
                let thumb_x = width * thumb;
                let radius = px(SLIDER_BAR_HEIGHT / 2.);
                for &(start, end) in &segments {
                    let (left, right) = (width * start, width * end);
                    for (from, to) in
                        [(left, right.min(thumb_x - THUMB_RADIUS)), (left.max(thumb_x + THUMB_RADIUS), right)]
                    {
                        if to <= from {
                            continue;
                        }
                        let (left_round, right_round) = (from <= 0., to >= width);
                        let corners = Corners {
                            top_left: if left_round { radius } else { px(0.) },
                            bottom_left: if left_round { radius } else { px(0.) },
                            top_right: if right_round { radius } else { px(0.) },
                            bottom_right: if right_round { radius } else { px(0.) },
                        };
                        let mark = Bounds::new(
                            point(bounds.left() + px(from), bounds.top()),
                            size(px(to - from), bounds.size.height),
                        );
                        window.paint_quad(fill(mark, rgb(SKIP_SEGMENT_COLOR)).corner_radii(corners));
                    }
                }
            },
        )
        .absolute()
        .left_0()
        .right_0()
        .top(px(SLIDER_BAR_TOP))
        .h(px(SLIDER_BAR_HEIGHT));
        v_flex()
            .id("tray-popup")
            .track_focus(&self.focus)
            .size_full()
            .px_3()
            .py(px(10.))
            .gap_1()
            .rounded(px(8.))
            .border_1()
            .border_color(theme.border)
            .bg(theme.popover)
            .text_color(theme.popover_foreground)
            .on_key_down(cx.listener(|popup, event: &KeyDownEvent, window, cx| {
                if event.keystroke.key == "escape" {
                    popup.close(window, cx);
                }
            }))
            .child(
                h_flex()
                    .gap(px(10.))
                    .items_center()
                    .child(cover.relative())
                    .child(v_flex().flex_1().min_w_0().child(div().text_sm().truncate().child(title)).when_some(
                        artist,
                        |column, artist| {
                            column.child(div().text_xs().truncate().text_color(theme.muted_foreground).child(artist))
                        },
                    ))
                    .child(
                        glyph_action_icon(
                            "tray-open-app",
                            auto_mirrored(MaterialIcon::OpenInNew),
                            t(Str::CdOpenApp),
                            HEADER_ICON,
                            HEADER_HIT,
                        )
                        .on_click(cx.listener(|popup, _, _, _| popup.command(TrayCommand::Open(None)))),
                    )
                    .child(
                        glyph_action_icon(
                            "tray-dismiss",
                            MaterialIcon::Close,
                            t(Str::CdClose),
                            HEADER_ICON,
                            HEADER_HIT,
                        )
                        .on_click(cx.listener(|popup, _, window, cx| popup.close(window, cx))),
                    ),
            )
            .child(
                h_flex()
                    .gap_2()
                    .items_center()
                    .layout_direction(LayoutDirection::Ltr)
                    .child(div().text_xs().text_color(theme.muted_foreground).child(elapsed))
                    .child(
                        div()
                            .flex_1()
                            .relative()
                            .child(
                                Slider::new(&self.seek)
                                    .accessibility_label(t(Str::A11yPlaybackPosition))
                                    .disabled(!seekable || duration <= 0),
                            )
                            .child(skip_marks),
                    )
                    .child(total),
            )
            .child(
                h_flex()
                    .justify_center()
                    .items_center()
                    .gap(px(14.))
                    .layout_direction(LayoutDirection::Ltr)
                    .child(
                        glyph_action_icon(
                            "tray-previous",
                            MaterialIcon::SkipPrevious,
                            t(Str::CdPrevious),
                            SKIP_ICON,
                            SKIP_HIT,
                        )
                        .disabled(item.is_none())
                        .on_click(cx.listener(|popup, _, _, cx| {
                            popup.playback.update(cx, |playback, cx| playback.previous(cx))
                        })),
                    )
                    .child(
                        hit_target("tray-play-pause", PLAY_HIT)
                            .when(loading, |button| button.child(Spinner::new().small()))
                            .when(!loading, |button| {
                                button.child(
                                    Icon::new(if playing { MaterialIcon::Pause } else { MaterialIcon::PlayArrow })
                                        .size(px(PLAY_ICON)),
                                )
                            })
                            .accessibility_label(play_label)
                            .tooltip(play_label)
                            .disabled(item.is_none())
                            .on_click(cx.listener(|popup, _, _, cx| {
                                popup.playback.update(cx, |playback, cx| playback.toggle(cx))
                            })),
                    )
                    .child(
                        glyph_action_icon("tray-next", MaterialIcon::SkipNext, t(Str::CdNext), SKIP_ICON, SKIP_HIT)
                            .on_click(cx.listener(|popup, _, _, cx| {
                                popup.playback.update(cx, |playback, cx| playback.next(cx))
                            })),
                    ),
            )
    }
}
