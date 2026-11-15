//! The sleep timer dialog (JVM `SleepTimerDialog.kt`): a running timer's card (time left, when it
//! stops, + minutes, turn off), then a duration from 5 min to 3 h on a slider or a preset, or the
//! end of the track. The last duration started is remembered.

use std::time::Duration;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::base::slider::{SliderEvent, SliderState};
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::slider::Slider;
use gpui_kit::component::{ActiveTheme, Icon, Selectable, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, SliderStop};
use crate::elevated_dialog::{DEFAULT_WIDTH, ROW_HORIZONTAL, button_bar, chrome, press, text_button, title_bar};
use crate::graph::AppGraph;
use crate::mini_player::clock;
use crate::playback::Playback;

/// The JVM app's key.
const LAST_MINUTES_KEY: &str = "playback.sleepTimerMinutes";
const DEFAULT_MINUTES: i32 = 30;
const MIN_MINUTES: u64 = 5;
const MAX_MINUTES: u64 = 180;
const STEP_MINUTES: u64 = 5;
const EXTEND_MINUTES: u64 = 5;
const PRESET_MINUTES: [u64; 5] = [15, 30, 45, 60, 90];

const STOP_EXTEND: usize = 0;
const STOP_TURN_OFF: usize = 1;
const STOP_CHIPS: usize = 2;
const STOP_CANCEL: usize = STOP_CHIPS + PRESET_MINUTES.len() + 1;
const STOP_START: usize = STOP_CANCEL + 1;

enum Choice {
    Timer(u64),
    EndOfTrack,
    Off,
}

pub struct SleepTimerDialog {
    playback: Entity<Playback>,
    minutes: u64,
    end_of_track: bool,
    slider: Entity<SliderState>,
    focus: FocusList,
    focused_first: bool,
    _subscriptions: Vec<Subscription>,
    _tick: Task<()>,
}

impl SleepTimerDialog {
    pub fn open(playback: Entity<Playback>, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| Self::new(playback, cx));
        window.open_dialog(cx, move |dialog, window, _| {
            let confirm = view.downgrade();
            chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()).on_ok(move |_, window, cx| {
                confirm.update(cx, |view, cx| view.activate_focused(window, cx)).ok();
                false
            })
        });
    }

    fn new(playback: Entity<Playback>, cx: &mut Context<Self>) -> Self {
        let graph = cx.global::<AppGraph>();
        let saved = match graph.settings.int(LAST_MINUTES_KEY, DEFAULT_MINUTES) {
            Ok(minutes) => minutes,
            Err(error) => {
                graph.log.fork("sleep-timer").warn("the last sleep duration could not be read", Some(&*error));
                DEFAULT_MINUTES
            }
        };
        let minutes = (saved.max(0) as u64).clamp(MIN_MINUTES, MAX_MINUTES);
        let slider = cx.new(|_| {
            SliderState::new()
                .min(MIN_MINUTES as f32)
                .max(MAX_MINUTES as f32)
                .step(STEP_MINUTES as f32)
                .default_value(minutes as f32)
        });
        let subscriptions = vec![
            cx.observe(&playback, |_, _, cx| cx.notify()),
            cx.subscribe(&slider, |view, _, event: &SliderEvent, cx| {
                let (SliderEvent::Change(value) | SliderEvent::Release(value)) = event;
                view.minutes = (value.end().round() as u64).clamp(MIN_MINUTES, MAX_MINUTES);
                view.end_of_track = false;
                cx.notify();
            }),
        ];
        let executor = cx.background_executor().clone();
        let tick = cx.spawn(async move |this, cx| {
            loop {
                executor.timer(Duration::from_secs(1)).await;
                if this.update(cx, |_, cx| cx.notify()).is_err() {
                    return;
                }
            }
        });
        Self {
            playback,
            minutes,
            end_of_track: false,
            slider,
            focus: FocusList::modal(None, cx),
            focused_first: false,
            _subscriptions: subscriptions,
            _tick: tick,
        }
    }

    /// Enter is the dialog's Confirm binding, which a focused button never sees: act for that button,
    /// with Start as the default (the bar's buttons take Enter themselves).
    fn activate_focused(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let focused = (0..=STOP_START).find(|stop| self.focus.handle(*stop, cx).is_focused(window));
        let remaining = self.playback.read(cx).sleep_remaining();
        match focused {
            Some(STOP_EXTEND) if let Some(left) = remaining => {
                self.choose(Choice::Timer(left.as_secs().div_ceil(60) + EXTEND_MINUTES), window, cx)
            }
            Some(STOP_TURN_OFF) => self.choose(Choice::Off, window, cx),
            Some(stop) if (STOP_CHIPS..STOP_CHIPS + PRESET_MINUTES.len()).contains(&stop) => {
                self.pick(PRESET_MINUTES[stop - STOP_CHIPS], window, cx)
            }
            Some(stop) if stop == STOP_CHIPS + PRESET_MINUTES.len() => {
                self.end_of_track = true;
                cx.notify();
            }
            _ => self.start(window, cx),
        }
    }

    fn start(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let choice = if self.end_of_track { Choice::EndOfTrack } else { Choice::Timer(self.minutes) };
        self.choose(choice, window, cx);
    }

    fn pick(&mut self, minutes: u64, window: &mut Window, cx: &mut Context<Self>) {
        self.minutes = minutes;
        self.end_of_track = false;
        self.slider.update(cx, |slider, cx| slider.set_value(minutes as f32, window, cx));
        cx.notify();
    }

    fn choose(&mut self, choice: Choice, window: &mut Window, cx: &mut Context<Self>) {
        match choice {
            Choice::Timer(minutes) => {
                self.playback
                    .update(cx, |playback, cx| playback.set_sleep_timer(Some(Duration::from_secs(minutes * 60)), cx));
                let graph = cx.global::<AppGraph>();
                let (settings, log) = (graph.settings.clone(), graph.log.fork("sleep-timer"));
                graph.tokio.spawn_blocking(move || {
                    if let Err(error) = settings.put_raw(LAST_MINUTES_KEY, minutes.to_string()) {
                        log.warn("the last sleep duration could not be saved", Some(&*error));
                    }
                });
            }
            Choice::EndOfTrack => self.playback.update(cx, |playback, cx| playback.set_sleep_after_current(true, cx)),
            Choice::Off => self.playback.update(cx, |playback, cx| playback.set_sleep_timer(None, cx)),
        }
        window.close_dialog(cx);
    }

    /// The running timer: what is left and when it stops, with + minutes and Turn off.
    fn armed_card(&self, remaining: Option<Duration>, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let headline = match remaining {
            Some(left) => tf(Str::SleepPausingIn, &[&clock(left.as_millis() as i64)]),
            None => t(Str::SleepPausingAfterTrack).to_owned(),
        };
        let action =
            |id: &'static str, label: String| Button::new(id).ghost().small().label(label).text_color(theme.primary);
        let mut actions = h_flex().justify_end().gap_1();
        if let Some(left) = remaining {
            let extended = left.as_secs().div_ceil(60) + EXTEND_MINUTES;
            actions =
                actions.child(self.focus.button(
                    STOP_EXTEND,
                    action("sleep-extend", tf(Str::SleepAddMinutes, &[&EXTEND_MINUTES])).on_click(
                        cx.listener(move |view, _, window, cx| view.choose(Choice::Timer(extended), window, cx)),
                    ),
                    cx,
                ));
        }
        actions = actions.child(
            self.focus.button(
                STOP_TURN_OFF,
                action("sleep-off", t(Str::SleepTurnOff).to_owned())
                    .on_click(cx.listener(|view, _, window, cx| view.choose(Choice::Off, window, cx))),
                cx,
            ),
        );
        let container = *cx.global::<crate::settings_view::AccentTones>();
        v_flex()
            .rounded(px(12.))
            .bg(container.ground)
            .text_color(container.ink)
            .pl_4()
            .pr_1()
            .pt_4()
            .pb_1()
            .child(h_flex().gap_2().items_center().child(Icon::new(Lucide::Timer).text_color(theme.primary)).child(
                div().id("sleep-armed").role(Role::Status).aria_label(headline.clone()).font_semibold().child(headline),
            ))
            .when_some(remaining, |card, left| {
                card.child(div().pl(px(24.)).text_xs().child(tf(Str::SleepStopsAt, &[&time_of_day(left)])))
            })
            .child(actions)
    }
}

/// The local wall-clock time `after` from now, hours and minutes.
fn time_of_day(after: Duration) -> String {
    let at = chrono::Local::now() + chrono::Duration::from_std(after).unwrap_or_default();
    at.format("%H:%M").to_string()
}

fn duration_label(minutes: u64) -> String {
    let (hours, rest) = (minutes / 60, minutes % 60);
    match (hours, rest) {
        (0, _) => tf(Str::SleepMinutes, &[&minutes]),
        (_, 0) => tf(Str::SleepHours, &[&hours]),
        _ => tf(Str::SleepHoursMinutes, &[&hours, &rest]),
    }
}

impl Render for SleepTimerDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(STOP_START, cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let theme = cx.theme().clone();
        let playback = self.playback.read(cx);
        let remaining = playback.sleep_remaining();
        let armed = remaining.is_some() || playback.sleep_after_current;
        let (minutes, end_of_track) = (self.minutes, self.end_of_track);
        let title = if end_of_track { t(Str::SleepEndOfTrack).to_owned() } else { duration_label(minutes) };
        let subtitle = if end_of_track {
            t(Str::SleepPausingAfterTrack).to_owned()
        } else {
            tf(Str::SleepStopsAt, &[&time_of_day(Duration::from_secs(minutes * 60))])
        };

        let chip = |id: ElementId, label: String, selected: bool| {
            let chip = crate::action_icon::chip(Button::new(id)).label(label).selected(selected);
            if selected { chip.primary() } else { chip.outline() }
        };
        let mut chips = h_flex().flex_wrap().gap_2();
        for (offset, preset) in PRESET_MINUTES.into_iter().enumerate() {
            chips = chips.child(
                self.focus.button(
                    STOP_CHIPS + offset,
                    chip(
                        ("sleep-preset", offset).into(),
                        tf(Str::SleepMinutes, &[&preset]),
                        !end_of_track && minutes == preset,
                    )
                    .on_click(cx.listener(move |view, _, window, cx| view.pick(preset, window, cx))),
                    cx,
                ),
            );
        }
        chips = chips.child(self.focus.button(
            STOP_CHIPS + PRESET_MINUTES.len(),
            chip("sleep-end-of-track".into(), t(Str::SleepEndOfTrack).to_owned(), end_of_track).on_click(cx.listener(
                |view, _, _, cx| {
                    view.end_of_track = true;
                    cx.notify();
                },
            )),
            cx,
        ));

        let buttons = h_flex()
            .child(press(
                &self.focus,
                STOP_CANCEL,
                text_button("sleep-cancel", t(Str::DialogCancel), false, true, cx),
                true,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(press(
                &self.focus,
                STOP_START,
                text_button("sleep-start", t(Str::SleepStart), true, true, cx),
                true,
                cx.listener(|view, _, window, cx| view.start(window, cx)),
                cx,
            ));
        let body = v_flex()
            .w_full()
            .px(px(ROW_HORIZONTAL))
            .py_4()
            .gap_4()
            .when(armed, |column| column.child(self.armed_card(remaining, cx)))
            .child(
                v_flex()
                    .items_center()
                    .child(div().text_size(px(34.)).font_semibold().child(title.clone()))
                    .child(div().text_sm().text_color(theme.muted_foreground).child(subtitle)),
            )
            .child(div().when(end_of_track, |frame| frame.opacity(0.5)).child(self.focus.slider(
                STOP_START + 1,
                &self.slider,
                SliderStop {
                    value_text: Some(title.clone().into()),
                    ..SliderStop::new("sleep-minutes", t(Str::CdSleepTimer))
                },
                Slider::new(&self.slider).accessibility_label(t(Str::CdSleepTimer)),
                cx,
            )))
            .child(chips);
        v_flex().w_full().child(title_bar(t(Str::CdSleepTimer), cx)).child(body).child(button_bar(buttons, cx))
    }
}
