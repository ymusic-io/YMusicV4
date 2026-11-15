//! The Speed & pitch dialog (the JVM app's `PlaybackFxSheet`): volume, then speed and pitch, each as
//! preset chips over a bar between − and + steppers. Every change applies at once and is saved by
//! Playback.

use std::rc::Rc;
use std::time::Duration;

use gpui_kit::base::slider::{SliderEvent, SliderState};
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::slider::Slider;
use gpui_kit::component::{ActiveTheme, Selectable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::*;
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, SliderStop};
use crate::audio_effects::{AudioEffects, PITCH_RANGE, SPEED_RANGE};
use crate::elevated_dialog::{self, button_bar, chrome, press, scroll_body, text_button, title_bar};
use crate::playback::Playback;

/// The same chips for speed and pitch.
const RATE_CHIPS: [f32; 7] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0];
/// The speed bar's span; a saved speed above it (up to 4×) pins the thumb at the end.
const SPEED_BAR: (f32, f32) = (0.25, 3.0);
const RATE_STEPS_PER_UNIT: f32 = 100.;
const RATE_STEP: f32 = 1. / RATE_STEPS_PER_UNIT;
const RATE_KEY_STEP: f32 = 0.05;
const VOLUME_KEY_STEP: f32 = 0.05;
const HOLD_DELAY: Duration = Duration::from_millis(400);
const HOLD_REPEAT: Duration = Duration::from_millis(60);
const PAD: f32 = 24.;

const STOP_VOLUME: usize = 0;
/// A rate block's stops from its base: the chips, −, the bar, +.
const RATE_STOPS: usize = RATE_CHIPS.len() + 3;
const STOP_RESET: usize = 1 + 2 * RATE_STOPS;
const STOP_OK: usize = STOP_RESET + 1;

#[derive(Clone, Copy, PartialEq)]
enum Rate {
    Speed,
    Pitch,
}

impl Rate {
    fn index(self) -> usize {
        self as usize
    }

    fn bar(self) -> (f32, f32) {
        match self {
            Rate::Speed => SPEED_BAR,
            Rate::Pitch => PITCH_RANGE,
        }
    }

    fn title(self) -> String {
        match self {
            Rate::Speed => t(Str::SoundSpeed).to_owned(),
            Rate::Pitch => tf(Str::SoundPitch, &[&""]).trim().to_owned(),
        }
    }

    fn of(self, effects: &AudioEffects) -> f32 {
        match self {
            Rate::Speed => effects.speed,
            Rate::Pitch => effects.pitch,
        }
    }
}

fn snap(value: f32) -> f32 {
    (value * RATE_STEPS_PER_UNIT).round() / RATE_STEPS_PER_UNIT
}

/// `1`, `1.25`: at most two decimals, none for a whole number.
pub fn trimmed(value: f32) -> String {
    format!("{}", (value * 100.).round() / 100.)
}

pub struct SpeedPitchDialog {
    playback: Entity<Playback>,
    volume: Entity<SliderState>,
    rates: [Entity<SliderState>; 2],
    /// A rate bar's value under the pointer, shown until it lands on release.
    scrubbing: [Option<f32>; 2],
    /// A held stepper's repeat.
    hold: Option<Task<()>>,
    /// The held stepper repeated: the click that ends the hold must not step once more.
    repeated: bool,
    focus: FocusList,
    scroll: ScrollHandle,
    _subscriptions: Vec<Subscription>,
}

impl SpeedPitchDialog {
    pub fn open(playback: Entity<Playback>, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| Self::new(playback, window, cx));
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, elevated_dialog::DEFAULT_WIDTH, window).child(view.clone())
        });
    }

    fn new(playback: Entity<Playback>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let (volume, effects) = {
            let playback = playback.read(cx);
            (playback.volume, playback.effects)
        };
        let volume = cx.new(|_| SliderState::new().min(0.).max(1.).step(0.01).default_value(volume));
        let rate_state = |rate: Rate, cx: &mut Context<Self>| {
            let (min, max) = rate.bar();
            cx.new(|_| SliderState::new().min(min).max(max).step(RATE_STEP).default_value(rate.of(&effects)))
        };
        let rates = [rate_state(Rate::Speed, cx), rate_state(Rate::Pitch, cx)];
        let mut subscriptions = vec![
            cx.observe_in(&playback, window, |view, _, window, cx| view.sync(window, cx)),
            cx.subscribe(&volume, |view, _, event: &SliderEvent, cx| {
                let (SliderEvent::Change(value) | SliderEvent::Release(value)) = event;
                let level = value.end();
                view.playback.update(cx, |playback, cx| playback.set_volume(level, cx));
            }),
        ];
        for rate in [Rate::Speed, Rate::Pitch] {
            subscriptions.push(cx.subscribe(&rates[rate.index()], move |view, _, event: &SliderEvent, cx| {
                match event {
                    SliderEvent::Change(value) => view.scrubbing[rate.index()] = Some(snap(value.end())),
                    SliderEvent::Release(value) => {
                        view.scrubbing[rate.index()] = None;
                        view.set_rate(rate, snap(value.end()), cx);
                    }
                }
                cx.notify();
            }));
        }
        let scroll = ScrollHandle::new();
        Self {
            playback,
            volume,
            rates,
            scrubbing: [None; 2],
            hold: None,
            repeated: false,
            focus: FocusList::modal(Some(scroll.clone()), cx),
            scroll,
            _subscriptions: subscriptions,
        }
    }

    /// Moves the bars to what Playback holds, but not one under the pointer.
    fn sync(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let (volume, effects) = {
            let playback = self.playback.read(cx);
            (playback.volume, playback.effects)
        };
        if self.volume.read(cx).value().end() != volume {
            self.volume.update(cx, |slider, cx| slider.set_value(volume, window, cx));
        }
        for rate in [Rate::Speed, Rate::Pitch] {
            let state = &self.rates[rate.index()];
            if self.scrubbing[rate.index()].is_none() && state.read(cx).value().end() != rate.of(&effects) {
                state.update(cx, |slider, cx| slider.set_value(rate.of(&effects), window, cx));
            }
        }
        cx.notify();
    }

    fn set_rate(&mut self, rate: Rate, value: f32, cx: &mut Context<Self>) {
        let mut effects = self.playback.read(cx).effects;
        match rate {
            Rate::Speed => effects.speed = value.clamp(SPEED_RANGE.0, SPEED_RANGE.1),
            Rate::Pitch => effects.pitch = value.clamp(PITCH_RANGE.0, PITCH_RANGE.1),
        }
        self.playback.update(cx, |playback, cx| playback.set_effects(effects, cx));
    }

    /// Moves `rate` by `delta`, on the bar's grid and within its span.
    fn step_rate(&mut self, rate: Rate, delta: f32, cx: &mut Context<Self>) {
        let (min, max) = rate.bar();
        let value = rate.of(&self.playback.read(cx).effects);
        self.set_rate(rate, snap(value + delta).clamp(min, max), cx);
    }

    fn start_hold(&mut self, rate: Rate, delta: f32, cx: &mut Context<Self>) {
        self.repeated = false;
        let executor = cx.background_executor().clone();
        self.hold = Some(cx.spawn(async move |view, cx| {
            executor.timer(HOLD_DELAY).await;
            loop {
                let stepped = view.update(cx, |view, cx| {
                    view.repeated = true;
                    view.step_rate(rate, delta, cx);
                });
                if stepped.is_err() {
                    return;
                }
                executor.timer(HOLD_REPEAT).await;
            }
        }));
    }

    fn reset(&mut self, cx: &mut Context<Self>) {
        let effects = AudioEffects { speed: 1.0, pitch: 1.0, ..self.playback.read(cx).effects };
        self.playback.update(cx, |playback, cx| {
            playback.set_volume(1.0, cx);
            playback.set_effects(effects, cx);
        });
    }

    fn label(text: String, cx: &App) -> Div {
        div()
            .w_full()
            .px(px(PAD))
            .pb(px(2.))
            .text_size(px(14.))
            .font_semibold()
            .text_color(cx.theme().foreground)
            .child(text)
    }

    /// A tap steps once; holding repeats.
    fn stepper(&self, rate: Rate, delta: f32, stop: usize, cx: &mut Context<Self>) -> Div {
        let sign = if delta < 0. { "−" } else { "+" };
        let button = Button::new(("rate-step", stop))
            .ghost()
            .rounded_full()
            .h_auto()
            .px(px(10.))
            .py(px(4.))
            .text_color(cx.theme().primary)
            .child(div().text_size(px(18.)).line_height(px(24.)).font_semibold().child(sign))
            .accessibility_label(format!("{} {sign}", rate.title()))
            .capture_any_mouse_down(cx.listener(move |view, _, _, cx| view.start_hold(rate, delta * RATE_STEP, cx)))
            .capture_any_mouse_up(cx.listener(|view, _, _, _| view.hold = None))
            .on_mouse_up_out(MouseButton::Left, cx.listener(|view, _, _, _| view.hold = None));
        let on_press = cx.listener(move |view, _, _, cx| {
            if !std::mem::take(&mut view.repeated) {
                view.step_rate(rate, delta * RATE_STEP, cx);
            }
        });
        press(&self.focus, stop, button, true, on_press, cx)
    }

    fn rate_block(&self, rate: Rate, effects: &AudioEffects, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let base = 1 + rate.index() * RATE_STOPS;
        let value = rate.of(effects);
        let shown = self.scrubbing[rate.index()].unwrap_or(value);
        let title = rate.title();
        let mut chips = h_flex().w_full().flex_wrap().gap(px(8.)).px(px(PAD)).pt(px(8.)).pb(px(10.));
        for (index, chip) in RATE_CHIPS.into_iter().enumerate() {
            let selected = (value - chip).abs() < RATE_STEP / 2.;
            let button = Button::new(("rate-chip", base + index))
                .rounded_full()
                .h(px(32.))
                .px(px(12.))
                .text_size(px(13.5))
                .label(format!("{}×", trimmed(chip)))
                .selected(selected);
            let button =
                if selected { button.primary() } else { button.outline().text_color(theme.foreground.opacity(0.6)) };
            let on_press = cx.listener(move |view, _, _, cx| view.set_rate(rate, chip, cx));
            chips = chips.child(press(&self.focus, base + index, button, true, on_press, cx));
        }
        let state = &self.rates[rate.index()];
        let view = cx.entity().downgrade();
        let stop = SliderStop {
            value_text: Some(format!("×{}", trimmed(shown)).into()),
            on_step: Some(Rc::new(move |delta, _, cx| {
                view.update(cx, |view, cx| view.step_rate(rate, delta * RATE_KEY_STEP, cx)).ok();
            })),
            ..SliderStop::new(("rate-bar", rate.index()), title.clone())
        };
        let bar = self.focus.slider(
            base + RATE_CHIPS.len() + 1,
            state,
            stop,
            Slider::new(state).accessibility_label(title.clone()),
            cx,
        );
        v_flex().w_full().child(Self::label(format!("{title} ×{}", trimmed(shown)), cx)).child(chips).child(
            h_flex()
                .w_full()
                .px(px(PAD))
                .items_center()
                .child(self.stepper(rate, -1., base + RATE_CHIPS.len(), cx))
                .child(div().flex_1().px(px(8.)).child(bar))
                .child(self.stepper(rate, 1., base + RATE_CHIPS.len() + 2, cx)),
        )
    }
}

impl Render for SpeedPitchDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (volume, effects) = {
            let playback = self.playback.read(cx);
            (playback.volume, playback.effects)
        };
        let volume_label = tf(Str::SoundVolume, &[&((volume * 100.).round() as i32)]);
        let volume_stop =
            SliderStop { step: Some(VOLUME_KEY_STEP), ..SliderStop::new("sound-volume", volume_label.clone()) };
        let volume_bar = Slider::new(&self.volume).accessibility_label(volume_label.clone());
        let body = v_flex()
            .w_full()
            .py(px(8.))
            .child(Self::label(volume_label, cx))
            .child(div().w_full().px(px(PAD)).child(self.focus.slider(
                STOP_VOLUME,
                &self.volume,
                volume_stop,
                volume_bar,
                cx,
            )))
            .child(div().h(px(16.)))
            .child(self.rate_block(Rate::Speed, &effects, cx))
            .child(div().h(px(20.)))
            .child(self.rate_block(Rate::Pitch, &effects, cx));
        let reset = text_button("sound-reset", t(Str::SoundReset), false, true, cx);
        let ok = text_button("sound-ok", t(Str::SoundOk), true, true, cx);
        let buttons = h_flex()
            .child(press(&self.focus, STOP_RESET, reset, true, cx.listener(|view, _, _, cx| view.reset(cx)), cx))
            .child(press(&self.focus, STOP_OK, ok, true, |_, window, cx| window.close_dialog(cx), cx));
        v_flex()
            .w_full()
            .child(title_bar(t(Str::SoundPlayback), cx))
            .child(scroll_body("speed-pitch-body", &self.scroll, body, window, cx))
            .child(button_bar(buttons, cx))
    }
}
