//! The Equalizer dialog (the JVM app's `EqualizerFxSheet`): a mixing-desk bank of the preamp and ten
//! band faders, the preset menu in the title bar, and bass and virtualizer knobs. Every change
//! applies at once and is saved by Playback.

use std::cell::Cell;
use std::f32::consts::PI;
use std::rc::Rc;

use gpui_kit::base::slider::SliderState;
use gpui_kit::component::button::Button;
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};
use ym_mse::Tone;

use crate::a11y::{FocusList, SliderStop};
use crate::audio_effects::{AudioEffects, EQ_PRESETS, GAIN_RANGE, VIRTUALIZER_RANGE, preset_name, preset_of};
use crate::elevated_dialog::{self, button_bar, chrome, press, scroll_body, text_button, title_bar_with};
use crate::playback::Playback;
use crate::settings_view::ChromeTones;
use crate::speed_pitch::trimmed;

const BAND_LABELS: [&str; 10] = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"];
/// The master fader's mark, as on a mixing desk; the JVM app does not translate it either.
const PRE_LABEL: &str = "PRE";
const GAIN_MAX: f32 = GAIN_RANGE.1;
const VIRTUALIZER_STEPS_PER_UNIT: f32 = 20.;
const VIRTUALIZER_KEY_STEP: f32 = 0.1;

const BANK_CORNER: f32 = 14.;
const LABEL_GAP: f32 = 8.;
/// The faders' height under the value labels, less the gap above them.
const FADER_AREA: f32 = 180. - LABEL_GAP;
const BAND_TRACK: f32 = 4.;
const BAND_CAP: (f32, f32) = (22., 14.);
const MASTER_COLUMN: f32 = 40.;
const MASTER_TRACK: f32 = 6.;
const MASTER_CAP: (f32, f32) = (28., 16.);
/// The ±12 dB ends sit this far inside the area, so a cap at either end stays whole.
const CAP_INSET: f32 = MASTER_CAP.1 / 2. + 2.;
const KNOB_SIZE: f32 = 104.;
const KNOB_START: f32 = 135.;
const KNOB_SWEEP: f32 = 270.;
/// Near the hub the angle is unstable: there an up/right drag raises instead.
const KNOB_HUB_FRACTION: f32 = 0.15;
const KNOB_STROKE: f32 = 7.;
const MENU_CURVE: (f32, f32) = (26., 12.);

const STOP_PRESET: usize = 0;
const STOP_PREAMP: usize = 1;
const STOP_BASS: usize = STOP_PREAMP + 11;
const STOP_VIRTUALIZER: usize = STOP_BASS + 1;
const STOP_RESET: usize = STOP_VIRTUALIZER + 1;
const STOP_OK: usize = STOP_RESET + 1;

/// Two linear halves, so the neutral 1× sits at the knob's top.
fn virtualizer_fraction(width: f32) -> f32 {
    let (min, max) = VIRTUALIZER_RANGE;
    if width <= 1. { (width - min) / (1. - min) / 2. } else { 0.5 + (width - 1.) / (max - 1.) / 2. }
}

fn virtualizer_width(fraction: f32) -> f32 {
    let (min, max) = VIRTUALIZER_RANGE;
    let width =
        if fraction <= 0.5 { min + fraction * 2. * (1. - min) } else { 1. + (fraction - 0.5) * 2. * (max - 1.) };
    (width * VIRTUALIZER_STEPS_PER_UNIT).round() / VIRTUALIZER_STEPS_PER_UNIT
}

/// `+6`, `−4`, `0`: whole dB, with a true minus.
fn db_label(db: f32) -> String {
    let value = db.round() as i32;
    match value.signum() {
        1 => format!("+{value}"),
        -1 => format!("−{}", -value),
        _ => "0".to_owned(),
    }
}

/// A fader's cap centre in the area, for `db`.
fn y_of(db: f32) -> f32 {
    CAP_INSET + (GAIN_MAX - db) / (2. * GAIN_MAX) * (FADER_AREA - 2. * CAP_INSET)
}

/// The whole dB at `y` in the area.
fn db_at(y: f32) -> f32 {
    ((1. - (y - CAP_INSET) / (FADER_AREA - 2. * CAP_INSET)) * 2. * GAIN_MAX - GAIN_MAX)
        .clamp(-GAIN_MAX, GAIN_MAX)
        .round()
}

/// Screen-space angle in degrees; y points down, so clockwise is positive.
fn angle_of(offset: Point<Pixels>) -> f32 {
    f32::from(offset.y).atan2(f32::from(offset.x)) * 180. / PI
}

#[derive(Clone, Copy, PartialEq)]
enum Control {
    Preamp,
    Band(usize),
    Bass,
    Virtualizer,
}

impl Control {
    fn model(self) -> usize {
        match self {
            Control::Preamp => 0,
            Control::Band(index) => 1 + index,
            Control::Bass => 11,
            Control::Virtualizer => 12,
        }
    }

    fn of(self, tone: &Tone) -> f32 {
        match self {
            Control::Preamp => tone.preamp_db,
            Control::Band(index) => tone.eq_db[index],
            Control::Bass => tone.bass_db,
            Control::Virtualizer => tone.virtualizer,
        }
    }

    fn set(self, tone: &mut Tone, value: f32) {
        match self {
            Control::Preamp => tone.preamp_db = value,
            Control::Band(index) => tone.eq_db[index] = value,
            Control::Bass => tone.bass_db = value,
            Control::Virtualizer => tone.virtualizer = value,
        }
    }

    /// Where a knob's value sits on its sweep, 0..=1.
    fn fraction(self, value: f32) -> f32 {
        match self {
            Control::Virtualizer => virtualizer_fraction(value),
            _ => (value + GAIN_MAX) / (2. * GAIN_MAX),
        }
    }

    fn at_fraction(self, fraction: f32) -> f32 {
        let fraction = fraction.clamp(0., 1.);
        match self {
            Control::Virtualizer => virtualizer_width(fraction),
            _ => (fraction * 2. * GAIN_MAX - GAIN_MAX).round(),
        }
    }

    /// One arrow press: a whole dB, or a tenth of the virtualizer's sweep.
    fn stepped(self, value: f32, delta: f32) -> f32 {
        match self {
            Control::Virtualizer => self.at_fraction(self.fraction(value) + delta * VIRTUALIZER_KEY_STEP),
            _ => (value + delta).round().clamp(-GAIN_MAX, GAIN_MAX),
        }
    }
}

enum Drag {
    /// Up and down the fader area sets the dB under the pointer.
    Fader(Control),
    /// Around the dial by the angle swept, never jumping to the pointer.
    Knob { control: Control, fraction: f32, angle: f32, last: Point<Pixels> },
}

/// The JVM palette's inks and lines this dialog draws with that the theme does not carry.
struct Inks {
    outline_soft: Hsla,
    faint: Hsla,
    muted: Hsla,
    track: Hsla,
}

impl Inks {
    fn of(cx: &App) -> Self {
        let hex = |dark: u32, light: u32| -> Hsla { rgb(if cx.theme().mode.is_dark() { dark } else { light }).into() };
        Self {
            outline_soft: hex(0x525252, 0xd8d8d8),
            faint: hex(0x7e7e7e, 0x9a9a9a),
            muted: hex(0xc6c6c6, 0x555555),
            track: hex(0x1a1a1a, 0xd4d4d4),
        }
    }
}

pub struct EqualizerDialog {
    playback: Entity<Playback>,
    /// What screen readers and the arrows read per control, kept at Playback's values.
    models: Vec<Entity<SliderState>>,
    drag: Option<Drag>,
    /// The fader area and the two knobs as last painted.
    area: Rc<Cell<Bounds<Pixels>>>,
    knobs: [Rc<Cell<Bounds<Pixels>>>; 2],
    focus: FocusList,
    scroll: ScrollHandle,
    _subscriptions: Vec<Subscription>,
}

impl EqualizerDialog {
    pub fn open(playback: Entity<Playback>, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| Self::new(playback, window, cx));
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, elevated_dialog::DEFAULT_WIDTH, window).child(view.clone())
        });
    }

    fn new(playback: Entity<Playback>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let tone = playback.read(cx).effects.tone;
        let model = |control: Control, cx: &mut Context<Self>| {
            let (min, max, step) = match control {
                Control::Virtualizer => (VIRTUALIZER_RANGE.0, VIRTUALIZER_RANGE.1, 1. / VIRTUALIZER_STEPS_PER_UNIT),
                _ => (-GAIN_MAX, GAIN_MAX, 1.),
            };
            cx.new(|_| SliderState::new().min(min).max(max).step(step).default_value(control.of(&tone)))
        };
        let models = Self::controls().into_iter().map(|control| model(control, cx)).collect();
        let subscriptions = vec![cx.observe_in(&playback, window, |view, _, window, cx| view.sync(window, cx))];
        let scroll = ScrollHandle::new();
        Self {
            playback,
            models,
            drag: None,
            area: Rc::default(),
            knobs: [Rc::default(), Rc::default()],
            focus: FocusList::modal(Some(scroll.clone()), cx),
            scroll,
            _subscriptions: subscriptions,
        }
    }

    fn controls() -> Vec<Control> {
        let bands = (0..10).map(Control::Band);
        std::iter::once(Control::Preamp).chain(bands).chain([Control::Bass, Control::Virtualizer]).collect()
    }

    fn sync(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let tone = self.playback.read(cx).effects.tone;
        for control in Self::controls() {
            let state = &self.models[control.model()];
            if state.read(cx).value().end() != control.of(&tone) {
                state.update(cx, |slider, cx| slider.set_value(control.of(&tone), window, cx));
            }
        }
        cx.notify();
    }

    fn set_tone(&mut self, tone: Tone, cx: &mut Context<Self>) {
        let effects = AudioEffects { tone, ..self.playback.read(cx).effects };
        self.playback.update(cx, |playback, cx| playback.set_effects(effects, cx));
    }

    fn set(&mut self, control: Control, value: f32, cx: &mut Context<Self>) {
        let mut tone = self.playback.read(cx).effects.tone;
        control.set(&mut tone, value);
        self.set_tone(tone, cx);
    }

    fn step(&mut self, control: Control, delta: f32, cx: &mut Context<Self>) {
        let value = control.of(&self.playback.read(cx).effects.tone);
        self.set(control, control.stepped(value, delta), cx);
    }

    fn reset(&mut self, cx: &mut Context<Self>) {
        self.set_tone(Tone::default(), cx);
    }

    fn press_fader(&mut self, control: Control, position: Point<Pixels>, cx: &mut Context<Self>) {
        self.drag = Some(Drag::Fader(control));
        self.set(control, db_at(f32::from(position.y - self.area.get().top())), cx);
        cx.notify();
    }

    fn press_knob(&mut self, control: Control, position: Point<Pixels>, cx: &mut Context<Self>) {
        let hub = self.knob_bounds(control).center();
        let fraction = control.fraction(control.of(&self.playback.read(cx).effects.tone));
        self.drag = Some(Drag::Knob { control, fraction, angle: angle_of(position - hub), last: position });
        cx.notify();
    }

    fn knob_bounds(&self, control: Control) -> Bounds<Pixels> {
        self.knobs[usize::from(control == Control::Virtualizer)].get()
    }

    fn drag_to(&mut self, event: &MouseMoveEvent, cx: &mut Context<Self>) {
        if event.pressed_button != Some(MouseButton::Left) {
            self.drag = None;
            cx.notify();
            return;
        }
        let position = event.position;
        match self.drag {
            Some(Drag::Fader(control)) => {
                self.set(control, db_at(f32::from(position.y - self.area.get().top())), cx);
            }
            Some(Drag::Knob { control, fraction, angle, last }) => {
                let bounds = self.knob_bounds(control);
                let from_hub = position - bounds.center();
                let size = f32::from(bounds.size.width);
                let turned = angle_of(from_hub);
                let distance = f32::from(from_hub.x).hypot(f32::from(from_hub.y));
                let step = if distance < size * KNOB_HUB_FRACTION {
                    let moved = position - last;
                    f32::from(moved.x - moved.y) / (f32::from(bounds.size.height) * 1.5)
                } else {
                    ((turned - angle + 540.) % 360. - 180.) / KNOB_SWEEP
                };
                let fraction = (fraction + step).clamp(0., 1.);
                self.drag = Some(Drag::Knob { control, fraction, angle: turned, last: position });
                self.set(control, control.at_fraction(fraction), cx);
            }
            None => {}
        }
    }

    /// While a drag is on, follows the pointer anywhere in the window until the button comes up.
    fn drag_listener(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let view = cx.entity().downgrade();
        canvas(
            |_, _, _| {},
            move |_, _, window, _| {
                let moves = view.clone();
                window.on_mouse_event(move |event: &MouseMoveEvent, phase, _, cx| {
                    if phase == DispatchPhase::Bubble {
                        moves.update(cx, |view, cx| view.drag_to(event, cx)).ok();
                    }
                });
                let ups = view.clone();
                window.on_mouse_event(move |event: &MouseUpEvent, phase, _, cx| {
                    if phase == DispatchPhase::Bubble && event.button == MouseButton::Left {
                        ups.update(cx, |view, cx| {
                            view.drag = None;
                            cx.notify();
                        })
                        .ok();
                    }
                });
            },
        )
        .absolute()
        .size_0()
    }

    /// The fader or knob drawn as held: the one dragged, else the one the keyboard is on.
    fn lit(&self, window: &Window, cx: &App) -> Option<Control> {
        match self.drag {
            Some(Drag::Fader(control) | Drag::Knob { control, .. }) => return Some(control),
            None => {}
        }
        if !window.last_input_was_keyboard() {
            return None;
        }
        Self::controls().into_iter().find(|control| self.focus.handle(Self::stop(*control), cx).is_focused(window))
    }

    fn stop(control: Control) -> usize {
        match control {
            Control::Preamp => STOP_PREAMP,
            Control::Band(index) => STOP_PREAMP + 1 + index,
            Control::Bass => STOP_BASS,
            Control::Virtualizer => STOP_VIRTUALIZER,
        }
    }

    fn preset_menu(&self, tone: &Tone, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let current = preset_of(&tone.eq_db);
        let name = preset_name(&tone.eq_db);
        let view = cx.entity().downgrade();
        let (accent, ink, secondary) = (theme.primary, theme.foreground, theme.muted_foreground);
        let button = Button::new("eq-preset")
            .outline()
            .small()
            .rounded(px(8.))
            .h_auto()
            .px(px(10.))
            .py(px(6.))
            .bg(theme.secondary)
            .border_color(Inks::of(cx).outline_soft)
            .text_size(px(13.))
            .font_semibold()
            .text_color(ink)
            .label(name)
            .dropdown_caret(true)
            .accessibility_label(format!("{}, {name}", t(Str::SoundEq)))
            .track_focus(&self.focus.handle(STOP_PRESET, cx))
            .dropdown_menu(move |mut menu, _, _| {
                for (index, (label, bands)) in EQ_PRESETS.into_iter().enumerate() {
                    let selected = current == Some(index);
                    let view = view.clone();
                    let row = move |_: &mut Window, _: &mut App| {
                        h_flex()
                            .gap_3()
                            .items_center()
                            .child(mini_curve(bands, if selected { accent } else { secondary }))
                            .child(
                                div()
                                    .text_size(px(14.))
                                    .when(selected, |text| text.font_semibold())
                                    .text_color(if selected { accent } else { ink })
                                    .child(t(label)),
                            )
                    };
                    menu = menu.item(PopupMenuItem::element(row).checked(selected).on_click(move |_, _, cx| {
                        view.update(cx, |view, cx| {
                            let mut tone = view.playback.read(cx).effects.tone;
                            tone.eq_db = bands;
                            view.set_tone(tone, cx);
                        })
                        .ok();
                    }));
                }
                menu
            });
        self.focus.wrap(STOP_PRESET, button, cx)
    }

    fn fader_bank(&self, tone: &Tone, lit: Option<Control>, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let inks = Inks::of(cx);
        let (accent, master) = (theme.primary, theme.foreground);
        let label = |text: String, color: Hsla, bold: bool| {
            div().text_size(px(10.)).text_center().when(bold, |text| text.font_bold()).text_color(color).child(text)
        };
        let labels = |master: Div, bands: Vec<Div>| {
            h_flex()
                .w_full()
                .child(master.w(px(MASTER_COLUMN)).flex_shrink_0())
                .children(bands.into_iter().map(|band| band.flex_1().min_w_0()))
        };
        let preamp = tone.preamp_db;
        let values = labels(
            label(
                db_label(preamp),
                if preamp == 0. && lit != Some(Control::Preamp) { theme.muted_foreground } else { master },
                true,
            ),
            tone.eq_db
                .iter()
                .enumerate()
                .map(|(index, db)| {
                    let quiet = *db == 0. && lit != Some(Control::Band(index));
                    label(db_label(*db), if quiet { theme.muted_foreground } else { accent }, true)
                })
                .collect(),
        );
        let names = labels(
            label(PRE_LABEL.to_owned(), if lit == Some(Control::Preamp) { master } else { inks.muted }, true),
            BAND_LABELS
                .iter()
                .enumerate()
                .map(|(index, hz)| {
                    let held = lit == Some(Control::Band(index));
                    label((*hz).to_owned(), if held { accent } else { theme.muted_foreground }, held)
                })
                .collect(),
        );

        let rule = inks.outline_soft;
        let line = |y: f32, thickness: f32, color: Hsla| {
            div().absolute().left(px(MASTER_COLUMN)).right_0().top(px(y - thickness / 2.)).h(px(thickness)).bg(color)
        };
        let master_look = FaderLook {
            track_width: MASTER_TRACK,
            cap: MASTER_CAP,
            track: inks.track,
            fill: master.opacity(0.55),
            cap_fill: linear_gradient(180., linear_color_stop(rgb(0x3a3a3a), 0.), linear_color_stop(rgb(0x1f1f1f), 1.)),
            cap_border: Some(inks.faint),
            mark: master,
            glow: (lit == Some(Control::Preamp)).then(|| master.opacity(0.2)),
        };
        let mut columns = h_flex().absolute().inset_0().child(
            self.fader_column(Control::Preamp, preamp, &master_look, cx).w(px(MASTER_COLUMN)).flex_shrink_0().h_full(),
        );
        for (index, db) in tone.eq_db.iter().enumerate() {
            let held = lit == Some(Control::Band(index));
            let look = FaderLook {
                track_width: BAND_TRACK,
                cap: BAND_CAP,
                track: inks.track,
                fill: accent,
                cap_fill: linear_gradient(
                    180.,
                    linear_color_stop(rgb(0xededed), 0.),
                    linear_color_stop(rgb(0xa9a9a9), 1.),
                ),
                cap_border: None,
                mark: if *db == 0. && !held { inks.faint } else { accent },
                glow: held.then(|| accent.opacity(0.25)),
            };
            columns =
                columns.child(self.fader_column(Control::Band(index), *db, &look, cx).flex_1().min_w_0().h_full());
        }
        let area = self.area.clone();
        let faders = div()
            .relative()
            .w_full()
            .h(px(FADER_AREA))
            .child(canvas(move |bounds, _, _| area.set(bounds), |_, _, _, _| {}).absolute().size_full())
            .child(line(y_of(0.), 1.5, rule))
            .child(line(y_of(GAIN_MAX), 1., rule.opacity(0.5)))
            .child(line(y_of(-GAIN_MAX), 1., rule.opacity(0.5)))
            .child(div().absolute().left(px(MASTER_COLUMN - 0.5)).top_0().bottom_0().w(px(1.)).bg(rule))
            .child(columns);
        v_flex()
            .w_full()
            .rounded(px(BANK_CORNER))
            .overflow_hidden()
            .bg(linear_gradient(180., linear_color_stop(theme.secondary, 0.), linear_color_stop(theme.background, 1.)))
            .border_1()
            .border_color(rule)
            .px(px(4.))
            .py(px(16.))
            .child(values)
            .child(div().w_full().pt(px(LABEL_GAP)).child(faders))
            .child(names.pt(px(LABEL_GAP)))
    }

    /// One fader's column: its stop for the keyboard and screen readers, pressed to set it.
    fn fader_column(&self, control: Control, db: f32, look: &FaderLook, cx: &mut Context<Self>) -> Stateful<Div> {
        let (label, value) = match control {
            Control::Band(index) => {
                (t(Str::SoundEq).to_owned(), tf(Str::SoundEqBand, &[&BAND_LABELS[index], &(db.round() as i32)]))
            }
            _ => (t(Str::A11yEqPreamp).to_owned(), tf(Str::SoundDb, &[&db_label(db)])),
        };
        let column = div().relative().size_full().children(look.parts(db)).on_mouse_down(
            MouseButton::Left,
            cx.listener(move |view, event: &MouseDownEvent, _, cx| view.press_fader(control, event.position, cx)),
        );
        self.slider_stop(control, label, value, column, cx)
    }

    fn slider_stop(
        &self,
        control: Control,
        label: String,
        value: String,
        element: impl IntoElement,
        cx: &mut Context<Self>,
    ) -> Stateful<Div> {
        let view = cx.entity().downgrade();
        let stop = SliderStop {
            value_text: Some(value.into()),
            vertical: true,
            on_step: Some(Rc::new(move |delta, _, cx| {
                view.update(cx, |view, cx| view.step(control, delta, cx)).ok();
            })),
            ..SliderStop::new(("eq-control", control.model()), label)
        };
        self.focus.slider(Self::stop(control), &self.models[control.model()], stop, element, cx)
    }

    fn knob(&self, control: Control, value: f32, lit: bool, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let inks = Inks::of(cx);
        let (title, value_text) = match control {
            Control::Virtualizer => (t(Str::SoundVirtualizerTitle), format!("{}×", trimmed(value))),
            _ => (t(Str::SoundBass), tf(Str::SoundDb, &[&db_label(value)])),
        };
        let fraction = control.fraction(value).clamp(0., 1.);
        let colors = KnobColors {
            accent: theme.primary,
            track: inks.outline_soft,
            face_light: cx.global::<ChromeTones>().lifted,
            face_dark: theme.secondary,
        };
        let bounds = self.knobs[usize::from(control == Control::Virtualizer)].clone();
        let dial = div()
            .relative()
            .size(px(KNOB_SIZE))
            .child(
                canvas(
                    |_, _, _| {},
                    move |area, _, window, _| {
                        bounds.set(area);
                        paint_knob(area, fraction, lit, &colors, window);
                    },
                )
                .absolute()
                .size_full(),
            )
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(px(14.))
                    .font_bold()
                    .text_color(theme.foreground)
                    .child(value_text.clone()),
            )
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |view, event: &MouseDownEvent, _, cx| view.press_knob(control, event.position, cx)),
            );
        v_flex()
            .items_center()
            .child(self.slider_stop(control, title.to_owned(), value_text, dial, cx).rounded_full())
            .child(div().pt(px(4.)).text_size(px(12.)).font_semibold().text_color(theme.muted_foreground).child(title))
    }
}

impl Render for EqualizerDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let tone = self.playback.read(cx).effects.tone;
        let lit = self.lit(window, cx);
        let body = v_flex()
            .w_full()
            .py(px(16.))
            .gap(px(16.))
            .child(
                v_flex().w_full().px(px(16.)).child(self.fader_bank(&tone, lit, cx)).child(
                    div()
                        .w_full()
                        .pt(px(8.))
                        .text_size(px(11.))
                        .text_center()
                        .text_color(theme.muted_foreground)
                        .child(t(Str::SoundEqHint)),
                ),
            )
            .child(
                h_flex()
                    .w_full()
                    .px(px(24.))
                    .justify_evenly()
                    .child(self.knob(Control::Bass, tone.bass_db, lit == Some(Control::Bass), cx))
                    .child(self.knob(Control::Virtualizer, tone.virtualizer, lit == Some(Control::Virtualizer), cx)),
            );
        let reset = text_button("eq-reset", t(Str::SoundReset), false, true, cx);
        let ok = text_button("eq-ok", t(Str::SoundOk), true, true, cx);
        let buttons = h_flex()
            .child(press(&self.focus, STOP_RESET, reset, true, cx.listener(|view, _, _, cx| view.reset(cx)), cx))
            .child(press(&self.focus, STOP_OK, ok, true, |_, window, cx| window.close_dialog(cx), cx));
        let preset = self.preset_menu(&tone, cx);
        v_flex()
            .w_full()
            .child(title_bar_with(t(Str::SoundEq), Some(preset), cx))
            .child(scroll_body("equalizer-body", &self.scroll, body, window, cx))
            .child(button_bar(buttons, cx))
            .when(self.drag.is_some(), |dialog| dialog.child(self.drag_listener(cx)))
    }
}

/// How one fader draws: the master's dark cap and ink fill, or a band's light cap and accent fill.
struct FaderLook {
    track_width: f32,
    cap: (f32, f32),
    track: Hsla,
    fill: Hsla,
    cap_fill: Background,
    cap_border: Option<Hsla>,
    mark: Hsla,
    glow: Option<Hsla>,
}

impl FaderLook {
    /// The track, the fill from the 0 dB line to `db`, and the cap at `db`, centred in the column.
    fn parts(&self, db: f32) -> Vec<Div> {
        let centred = |top: f32, part: Div| {
            div().absolute().left_0().right_0().top(px(top)).flex().justify_center().child(part.flex_shrink_0())
        };
        let (top, bottom, y, zero) = (y_of(GAIN_MAX), y_of(-GAIN_MAX), y_of(db), y_of(0.));
        let (width, (cap_width, cap_height)) = (self.track_width, self.cap);
        let mut parts =
            vec![centred(top, div().w(px(width)).h(px(bottom - top)).rounded(px(width / 2.)).bg(self.track))];
        if db != 0. {
            let fill = div().w(px(width)).h(px((y - zero).abs())).rounded(px(width / 2.)).bg(self.fill);
            parts.push(centred(y.min(zero), fill));
        }
        if let Some(glow) = self.glow {
            let radius = cap_width * 0.9;
            parts.push(centred(y - radius, div().size(px(2. * radius)).rounded_full().bg(glow)));
        }
        let cap = || div().w(px(cap_width)).h(px(cap_height)).rounded(px(4.));
        parts.push(centred(y - cap_height / 2. + 2., cap().bg(black().opacity(0.45))));
        parts.push(centred(
            y - cap_height / 2.,
            cap().bg(self.cap_fill).when_some(self.cap_border, |cap, border| cap.border_1().border_color(border)),
        ));
        parts.push(centred(y - 1., div().w(px(cap_width - 8.)).h(px(2.)).bg(self.mark)));
        parts
    }
}

struct KnobColors {
    accent: Hsla,
    track: Hsla,
    face_light: Hsla,
    face_dark: Hsla,
}

/// The 270° dial: the track, the value arc from the lower-left stop with its glow, the face and the
/// dot pointing at the value.
fn paint_knob(area: Bounds<Pixels>, fraction: f32, lit: bool, colors: &KnobColors, window: &mut Window) {
    let diameter = f32::from(area.size.width.min(area.size.height));
    let center = area.center();
    let pad = KNOB_STROKE / 2. + 2.;
    let radius = diameter / 2. - pad;
    if let Some(track) = arc_band(center, radius, KNOB_STROKE, KNOB_START, KNOB_SWEEP) {
        window.paint_path(track, colors.track);
    }
    let sweep = KNOB_SWEEP * fraction;
    if sweep > 0. {
        if let Some(glow) = arc_band(center, radius, KNOB_STROKE * 2.2, KNOB_START, sweep) {
            window.paint_path(glow, colors.accent.opacity(if lit { 0.35 } else { 0.2 }));
        }
        if let Some(value) = arc_band(center, radius, KNOB_STROKE, KNOB_START, sweep) {
            window.paint_path(value, colors.accent);
        }
    }
    let face = diameter / 2. - pad - KNOB_STROKE - 6.;
    window.paint_quad(quad(
        Bounds::centered_at(center, size(px(2. * face), px(2. * face))),
        px(face),
        linear_gradient(180., linear_color_stop(colors.face_light, 0.), linear_color_stop(colors.face_dark, 1.)),
        px(1.),
        colors.track,
        BorderStyle::Solid,
    ));
    let angle = (KNOB_START + sweep).to_radians();
    let reach = face - 9.;
    let dot = point(center.x + px(angle.cos() * reach), center.y + px(angle.sin() * reach));
    window.paint_quad(quad(
        Bounds::centered_at(dot, size(px(7.), px(7.))),
        px(3.5),
        colors.accent,
        px(0.),
        transparent_black(),
        BorderStyle::Solid,
    ));
}

/// An arc `width` wide around `center`, from `start` clockwise through `sweep` degrees, with round
/// ends: filled as one outline so a translucent arc does not darken where its caps overlap.
fn arc_band(center: Point<Pixels>, radius: f32, width: f32, start: f32, sweep: f32) -> Option<Path<Pixels>> {
    let (outer, inner, cap) = (radius + width / 2., radius - width / 2., width / 2.);
    let at = |distance: f32, degrees: f32| {
        let angle = degrees.to_radians();
        point(center.x + px(angle.cos() * distance), center.y + px(angle.sin() * distance))
    };
    let radii = |radius: f32| point(px(radius), px(radius));
    let (end, large) = (start + sweep, sweep > 180.);
    let mut path = PathBuilder::fill();
    path.move_to(at(outer, start));
    path.arc_to(radii(outer), px(0.), large, true, at(outer, end));
    path.arc_to(radii(cap), px(0.), false, true, at(inner, end));
    path.arc_to(radii(inner), px(0.), large, false, at(inner, start));
    path.arc_to(radii(cap), px(0.), false, true, at(outer, start));
    path.close();
    path.build().ok()
}

/// A preset's curve for its menu row: a Catmull-Rom spline through the ten bands.
fn mini_curve(bands: [f32; 10], color: Hsla) -> impl IntoElement {
    canvas(
        |_, _, _| {},
        move |area, _, window, _| {
            let (width, height) = (f32::from(area.size.width), f32::from(area.size.height));
            let points: Vec<Point<Pixels>> = bands
                .iter()
                .enumerate()
                .map(|(index, db)| {
                    let x = width * index as f32 / (bands.len() - 1) as f32;
                    let y = height / 2. - db / GAIN_MAX * height / 2.;
                    point(area.left() + px(x), area.top() + px(y))
                })
                .collect();
            let mut path = PathBuilder::stroke(px(1.5));
            path.move_to(points[0]);
            for index in 0..points.len() - 1 {
                let before = points[index.saturating_sub(1)];
                let (from, to) = (points[index], points[index + 1]);
                let after = points[(index + 2).min(points.len() - 1)];
                let control_a = point(from.x + (to.x - before.x) / 6., from.y + (to.y - before.y) / 6.);
                let control_b = point(to.x - (after.x - from.x) / 6., to.y - (after.y - from.y) / 6.);
                path.cubic_bezier_to(to, control_a, control_b);
            }
            if let Ok(path) = path.build() {
                window.paint_path(path, color);
            }
        },
    )
    .w(px(MENU_CURVE.0))
    .h(px(MENU_CURVE.1))
    .flex_shrink_0()
}
