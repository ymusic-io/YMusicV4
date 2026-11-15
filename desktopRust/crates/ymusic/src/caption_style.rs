//! Video caption style: text size, text color, box color and box opacity (the JVM app's keys), and
//! the Settings dialog that edits them.

use gpui_kit::base::actions::Confirm;
use gpui_kit::base::slider::{SliderEvent, SliderState};
use gpui_kit::component::slider::Slider;
use gpui_kit::component::{ActiveTheme, Icon, IconName, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t};
use ym_settings::SettingsStore;

use crate::a11y::{FocusList, OnStep, SliderStop};
use crate::accent::Rgb;
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, button_bar, chrome, press, scroll_body, text_button, title_bar,
};
use crate::settings_view::save_setting;

pub const SCALE_KEY: &str = "captions.scale";
pub const TEXT_COLOR_KEY: &str = "captions.textColor";
pub const BACKGROUND_COLOR_KEY: &str = "captions.backgroundColor";
pub const BACKGROUND_OPACITY_KEY: &str = "captions.backgroundOpacity";
const DEFAULT_SCALE: f32 = 1.;
const DEFAULT_TEXT: &str = "FFFFFF";
const DEFAULT_BACKGROUND: &str = "000000";
const DEFAULT_OPACITY: f32 = 0.55;
const MIN_SCALE: f32 = 0.5;
const MAX_SCALE: f32 = 2.;
const TEXT_COLORS: [&str; 6] = ["FFFFFF", "FFEB3B", "76FF03", "18FFFF", "FF4081", "000000"];
const BACKGROUND_COLORS: [&str; 5] = ["000000", "FFFFFF", "616161", "0D47A1", "FFEB3B"];
/// The caption font at scale 1, and its line height.
pub const BASE_TEXT: f32 = 16.;
pub const BASE_LINE: f32 = 24.;

#[derive(Clone, Copy, PartialEq)]
pub struct CaptionLook {
    pub scale: f32,
    pub text: Hsla,
    pub background: Hsla,
}

struct Saved {
    scale: f32,
    text: String,
    background: String,
    opacity: f32,
}

impl Saved {
    fn load(settings: &dyn SettingsStore) -> Self {
        let text = |key: &str, default: &str| {
            settings
                .get_raw(key)
                .ok()
                .flatten()
                .filter(|value| Rgb::parse(value).is_some())
                .unwrap_or(default.to_owned())
        };
        let float = |key: &str, default: f32| {
            settings.get_raw(key).ok().flatten().and_then(|value| value.trim().parse::<f32>().ok()).unwrap_or(default)
        };
        Self {
            scale: float(SCALE_KEY, DEFAULT_SCALE).clamp(MIN_SCALE, MAX_SCALE),
            text: text(TEXT_COLOR_KEY, DEFAULT_TEXT),
            background: text(BACKGROUND_COLOR_KEY, DEFAULT_BACKGROUND),
            opacity: float(BACKGROUND_OPACITY_KEY, DEFAULT_OPACITY).clamp(0., 1.),
        }
    }

    fn look(&self) -> CaptionLook {
        let color = |hex: &str| Rgb::parse(hex).map(Rgb::hsla).unwrap_or(white());
        CaptionLook {
            scale: self.scale,
            text: color(&self.text),
            background: color(&self.background).opacity(self.opacity),
        }
    }
}

impl CaptionLook {
    pub fn load(settings: &dyn SettingsStore) -> Self {
        Saved::load(settings).look()
    }
}

/// `value` snapped to `1 / steps_per_unit`, written the way Kotlin's `Float.toString` reads back.
/// An arrow press on a caption slider: one step, saved at once (a drag saves on release).
fn saved_step(state: &Entity<SliderState>, key: &'static str, steps_per_unit: f32) -> OnStep {
    let state = state.clone();
    std::rc::Rc::new(move |delta, window, cx| {
        state.update(cx, |slider, cx| {
            let step = slider.accessibility_step();
            slider.step_thumb(false, delta * step, window, cx);
        });
        save_setting(key, float_text(state.read(cx).value().end(), steps_per_unit).1, cx);
    })
}

fn float_text(value: f32, steps_per_unit: f32) -> (f32, String) {
    let snapped = (value * steps_per_unit).round() / steps_per_unit;
    let text = snapped.to_string();
    let text = if text.contains('.') { text } else { format!("{text}.0") };
    (snapped, text)
}

fn scale_label(scale: f32) -> String {
    format!("{}×", (scale * 10.).round() / 10.)
}

fn opacity_label(opacity: f32) -> String {
    format!("{}%", (opacity * 100.).round() as i32)
}

/// A cue drawn the way the Now Playing overlay draws it.
pub fn cue(text: impl IntoElement, look: CaptionLook) -> Div {
    div()
        .px_2()
        .py(px(2.))
        .rounded(px(4.))
        .bg(look.background)
        .text_color(look.text)
        .text_size(px(BASE_TEXT * look.scale))
        .line_height(px(BASE_LINE * look.scale))
        .child(text)
}

/// The Settings row's preview chip.
pub fn sample(look: CaptionLook) -> Div {
    div()
        .px_2()
        .py(px(2.))
        .rounded(px(4.))
        .bg(look.background)
        .text_color(look.text)
        .text_sm()
        .font_semibold()
        .child("Aa")
}

pub struct CaptionStyleDialog {
    saved: Saved,
    scale: Entity<SliderState>,
    opacity: Entity<SliderState>,
    focus: FocusList,
    scroll: ScrollHandle,
    _subscriptions: Vec<Subscription>,
}

impl CaptionStyleDialog {
    pub fn open(window: &mut Window, cx: &mut App) {
        let view = cx.new(Self::new);
        window.open_dialog(cx, move |dialog, window, _| chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()));
    }

    fn new(cx: &mut Context<Self>) -> Self {
        let saved = Saved::load(cx.global::<crate::graph::AppGraph>().settings.as_ref());
        let scale = cx.new(|_| SliderState::new().min(MIN_SCALE).max(MAX_SCALE).step(0.1).default_value(saved.scale));
        let opacity = cx.new(|_| SliderState::new().min(0.).max(1.).step(0.05).default_value(saved.opacity));
        let scale_events = cx.subscribe(&scale, |view, _, event: &SliderEvent, cx| match event {
            SliderEvent::Change(value) => {
                view.saved.scale = float_text(value.end(), 10.).0;
                cx.notify();
            }
            SliderEvent::Release(value) => {
                let (scale, text) = float_text(value.end(), 10.);
                view.saved.scale = scale;
                save_setting(SCALE_KEY, text, cx);
            }
        });
        let opacity_events = cx.subscribe(&opacity, |view, _, event: &SliderEvent, cx| match event {
            SliderEvent::Change(value) => {
                view.saved.opacity = float_text(value.end(), 20.).0;
                cx.notify();
            }
            SliderEvent::Release(value) => {
                let (opacity, text) = float_text(value.end(), 20.);
                view.saved.opacity = opacity;
                save_setting(BACKGROUND_OPACITY_KEY, text, cx);
            }
        });
        let scroll = ScrollHandle::new();
        Self {
            saved,
            scale,
            opacity,
            focus: FocusList::modal(Some(scroll.clone()), cx),
            scroll,
            _subscriptions: vec![scale_events, opacity_events],
        }
    }

    fn pick(&mut self, text: bool, hex: &'static str, cx: &mut Context<Self>) {
        if text {
            self.saved.text = hex.to_owned();
            save_setting(TEXT_COLOR_KEY, hex.to_owned(), cx);
        } else {
            self.saved.background = hex.to_owned();
            save_setting(BACKGROUND_COLOR_KEY, hex.to_owned(), cx);
        }
        cx.notify();
    }

    fn reset(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.saved = Saved {
            scale: DEFAULT_SCALE,
            text: DEFAULT_TEXT.into(),
            background: DEFAULT_BACKGROUND.into(),
            opacity: DEFAULT_OPACITY,
        };
        self.scale.update(cx, |slider, cx| slider.set_value(DEFAULT_SCALE, window, cx));
        self.opacity.update(cx, |slider, cx| slider.set_value(DEFAULT_OPACITY, window, cx));
        save_setting(SCALE_KEY, float_text(DEFAULT_SCALE, 10.).1, cx);
        save_setting(TEXT_COLOR_KEY, DEFAULT_TEXT.into(), cx);
        save_setting(BACKGROUND_COLOR_KEY, DEFAULT_BACKGROUND.into(), cx);
        save_setting(BACKGROUND_OPACITY_KEY, float_text(DEFAULT_OPACITY, 20.).1, cx);
        cx.notify();
    }

    fn swatches(&self, text: bool, first_stop: usize, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let (colors, selected): (&[&'static str], &str) =
            if text { (&TEXT_COLORS, &self.saved.text) } else { (&BACKGROUND_COLORS, &self.saved.background) };
        let mut row = h_flex().flex_wrap().gap_2();
        for (index, hex) in colors.iter().copied().enumerate() {
            let Some(color) = Rgb::parse(hex) else { continue };
            let is_selected = hex.eq_ignore_ascii_case(selected);
            let check = if color.luminance() > 0.5 { black() } else { white() };
            let swatch = div()
                .id((if text { "caption-text" } else { "caption-box" }, index))
                .role(Role::RadioButton)
                .aria_label(format!("#{hex}"))
                .aria_toggled(if is_selected { Toggled::True } else { Toggled::False })
                .size(px(32.))
                .p(px(if is_selected { 3. } else { 0. }))
                .rounded_full()
                .border_2()
                .border_color(if is_selected { theme.primary } else { theme.border })
                .cursor_pointer()
                .child(
                    div()
                        .size_full()
                        .rounded_full()
                        .border_1()
                        .border_color(theme.border)
                        .bg(color.hsla())
                        .flex()
                        .items_center()
                        .justify_center()
                        .when(is_selected, |dot| dot.child(Icon::new(IconName::Check).size(px(16.)).text_color(check))),
                )
                .on_click(cx.listener(move |view, _, _, cx| view.pick(text, hex, cx)))
                .on_action(cx.listener(move |view, _: &Confirm, _, cx| view.pick(text, hex, cx)));
            row = row.child(self.focus.item(first_stop + index, swatch, cx));
        }
        row
    }
}

impl Render for CaptionStyleDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let look = self.saved.look();
        let title = |text: &'static str| div().text_sm().font_semibold().text_color(theme.muted_foreground).child(text);
        let value_label = |text: String| {
            div().w(px(56.)).text_right().text_sm().font_semibold().text_color(theme.primary).child(text)
        };
        // Tall enough for two rows at the largest size, so the dialog doesn't resize while dragging.
        let preview_height = 2. * (BASE_LINE * MAX_SCALE + 4.) + 32.;
        let text_stops = 0;
        let box_stops = TEXT_COLORS.len();
        let button_stops = box_stops + BACKGROUND_COLORS.len();
        let buttons = h_flex()
            .child(press(
                &self.focus,
                button_stops,
                text_button("caption-reset", t(Str::SoundReset), false, true, cx),
                true,
                cx.listener(|view, _, window, cx| view.reset(window, cx)),
                cx,
            ))
            .child(press(
                &self.focus,
                button_stops + 1,
                text_button("caption-ok", t(Str::SoundOk), true, true, cx),
                true,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ));
        let body = v_flex()
            .w_full()
            .px(px(ROW_HORIZONTAL))
            .py_4()
            .gap_4()
            .child(
                v_flex()
                    .h(px(preview_height))
                    .p_4()
                    .rounded(px(8.))
                    .bg(rgb(0x6B7B8C))
                    .justify_end()
                    .items_center()
                    .child(cue(t(Str::CaptionSizePreview), look)),
            )
            .child(title(t(Str::SettingsCaptionSize)))
            .child(
                h_flex()
                    .gap_4()
                    .items_center()
                    .child(
                        self.focus
                            .slider(
                                button_stops + 2,
                                &self.scale,
                                SliderStop {
                                    value_text: Some(scale_label(self.saved.scale).into()),
                                    on_step: Some(saved_step(&self.scale, SCALE_KEY, 10.)),
                                    ..SliderStop::new("caption-scale", t(Str::SettingsCaptionSize))
                                },
                                Slider::new(&self.scale).accessibility_label(t(Str::SettingsCaptionSize)),
                                cx,
                            )
                            .flex_1(),
                    )
                    .child(value_label(scale_label(self.saved.scale))),
            )
            .child(title(t(Str::CaptionTextColor)))
            .child(self.swatches(true, text_stops, cx))
            .child(title(t(Str::CaptionBackground)))
            .child(self.swatches(false, box_stops, cx))
            .child(title(t(Str::CaptionBackgroundOpacity)))
            .child(
                h_flex()
                    .gap_4()
                    .items_center()
                    .child(
                        self.focus
                            .slider(
                                button_stops + 3,
                                &self.opacity,
                                SliderStop {
                                    value_text: Some(opacity_label(self.saved.opacity).into()),
                                    on_step: Some(saved_step(&self.opacity, BACKGROUND_OPACITY_KEY, 20.)),
                                    ..SliderStop::new("caption-opacity", t(Str::CaptionBackgroundOpacity))
                                },
                                Slider::new(&self.opacity).accessibility_label(t(Str::CaptionBackgroundOpacity)),
                                cx,
                            )
                            .flex_1(),
                    )
                    .child(value_label(opacity_label(self.saved.opacity))),
            );
        self.focus.container(
            v_flex()
                .w_full()
                .child(title_bar(t(Str::SettingsCaptionStyle), cx))
                .child(scroll_body("caption-style-body", &self.scroll, body, window, cx))
                .child(button_bar(buttons, cx)),
            0..button_stops + 2,
            cx,
        )
    }
}
