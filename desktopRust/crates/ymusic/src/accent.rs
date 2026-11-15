//! The accent color (`theme.accent`, `RRGGBB`, blank = the default orange) and its picker: preset
//! swatches, a hue slider and a hex field.

use gpui_kit::base::actions::Confirm;
use gpui_kit::base::slider::{SliderEvent, SliderState};
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::slider::Slider;
use gpui_kit::component::{ActiveTheme, Sizable, WindowExt, h_flex, v_flex};
use gpui_kit::*;
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, SliderStop};
use crate::elevated_dialog::{DEFAULT_WIDTH, ROW_HORIZONTAL, button_bar, chrome, press, text_button, title_bar};

pub const ACCENT_KEY: &str = "theme.accent";
const DEFAULT_ACCENT: Rgb = Rgb::hex(0xE8732A);
/// The lightest an accent may be, so white text on it stays readable. The default sits right at it.
const MAX_ACCENT_LUMINANCE: f32 = 0.3;
const PRESETS: [Rgb; 8] = [
    DEFAULT_ACCENT,
    Rgb::hex(0xD32F2F),
    Rgb::hex(0xC2185B),
    Rgb::hex(0x7B1FA2),
    Rgb::hex(0x341C9A),
    Rgb::hex(0x1565C0),
    Rgb::hex(0x00796B),
    Rgb::hex(0x2E7D32),
];
const HUE_MAX: f32 = 359.99;

type OnPick = Box<dyn Fn(String, &mut App)>;

/// Gamma-encoded sRGB channels in 0..1.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Rgb {
    red: f32,
    green: f32,
    blue: f32,
}

impl Rgb {
    const fn hex(value: u32) -> Self {
        Self {
            red: ((value >> 16) & 0xFF) as f32 / 255.,
            green: ((value >> 8) & 0xFF) as f32 / 255.,
            blue: (value & 0xFF) as f32 / 255.,
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        let text = text.trim_start_matches('#');
        if text.len() != 6 {
            return None;
        }
        u32::from_str_radix(text, 16).ok().map(Self::hex)
    }

    pub fn to_hex(self) -> String {
        let channel = |value: f32| ((value * 255. + 0.5) as i32).clamp(0, 255);
        format!("{:02X}{:02X}{:02X}", channel(self.red), channel(self.green), channel(self.blue))
    }

    pub fn hsla(self) -> Hsla {
        Rgba { r: self.red, g: self.green, b: self.blue, a: 1. }.into()
    }

    fn scaled(self, factor: f32) -> Self {
        Self { red: self.red * factor, green: self.green * factor, blue: self.blue * factor }
    }

    /// Relative luminance (sRGB transfer, Rec. 709 weights), as Compose's `Color.luminance()`.
    pub fn luminance(self) -> f32 {
        let linear = |value: f32| {
            if value <= 0.04045 { value / 12.92 } else { ((value + 0.055) / 1.055).powf(2.4) }
        };
        0.2126 * linear(self.red) + 0.7152 * linear(self.green) + 0.0722 * linear(self.blue)
    }

    /// Same hue and saturation, darkened until the luminance is at most [`MAX_ACCENT_LUMINANCE`].
    fn darkened(self) -> Self {
        if self.luminance() <= MAX_ACCENT_LUMINANCE {
            return self;
        }
        let (mut low, mut high) = (0f32, 1f32);
        for _ in 0..16 {
            let mid = (low + high) / 2.;
            if self.scaled(mid).luminance() <= MAX_ACCENT_LUMINANCE {
                low = mid;
            } else {
                high = mid;
            }
        }
        self.scaled(low)
    }

    /// `(hue 0..360, saturation 0..1, value 0..1)`.
    fn hsv(self) -> (f32, f32, f32) {
        let max = self.red.max(self.green).max(self.blue);
        let min = self.red.min(self.green).min(self.blue);
        let delta = max - min;
        let hue = if delta == 0. {
            0.
        } else if max == self.red {
            60. * ((self.green - self.blue) / delta).rem_euclid(6.)
        } else if max == self.green {
            60. * ((self.blue - self.red) / delta + 2.)
        } else {
            60. * ((self.red - self.green) / delta + 4.)
        };
        let saturation = if max == 0. { 0. } else { delta / max };
        (hue.clamp(0., HUE_MAX), saturation, max)
    }

    fn from_hsv(hue: f32, saturation: f32, value: f32) -> Self {
        let channel = |offset: f32| {
            let k = (offset + hue / 60.) % 6.;
            value - value * saturation * k.min(4. - k).clamp(0., 1.)
        };
        Self { red: channel(5.), green: channel(3.), blue: channel(1.) }
    }
}

/// The saved accent (`RRGGBB`) as drawn; blank or malformed is the default.
pub fn accent_color(saved: &str) -> Hsla {
    accent_rgb(saved).hsla()
}

/// Compose's `lerp(start, stop, fraction)`: interpolated in Oklab, opaque.
pub fn oklab_lerp(start: Hsla, stop: Hsla, fraction: f32) -> Hsla {
    let (from, to) = (oklab(start.to_rgb()), oklab(stop.to_rgb()));
    let fraction = f64::from(fraction);
    let mixed: [f64; 3] = std::array::from_fn(|axis| from[axis] + (to[axis] - from[axis]) * fraction);
    from_oklab(mixed).into()
}

fn oklab(color: Rgba) -> [f64; 3] {
    let linear = |value: f32| {
        let value = f64::from(value);
        if value <= 0.04045 { value / 12.92 } else { ((value + 0.055) / 1.055).powf(2.4) }
    };
    let (red, green, blue) = (linear(color.r), linear(color.g), linear(color.b));
    let long = (0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue).cbrt();
    let medium = (0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue).cbrt();
    let short = (0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue).cbrt();
    [
        0.2104542553 * long + 0.7936177850 * medium - 0.0040720468 * short,
        1.9779984951 * long - 2.4285922050 * medium + 0.4505937099 * short,
        0.0259040371 * long + 0.7827717662 * medium - 0.8086757660 * short,
    ]
}

fn from_oklab([lightness, green_red, blue_yellow]: [f64; 3]) -> Rgba {
    let long = (lightness + 0.3963377774 * green_red + 0.2158037573 * blue_yellow).powi(3);
    let medium = (lightness - 0.1055613458 * green_red - 0.0638541728 * blue_yellow).powi(3);
    let short = (lightness - 0.0894841775 * green_red - 1.2914855480 * blue_yellow).powi(3);
    let encoded = |value: f64| {
        let value = if value <= 0.0031308 { value * 12.92 } else { 1.055 * value.powf(1. / 2.4) - 0.055 };
        value.clamp(0., 1.) as f32
    };
    Rgba {
        r: encoded(4.0767416621 * long - 3.3077115913 * medium + 0.2309699292 * short),
        g: encoded(-1.2684380046 * long + 2.6097574011 * medium - 0.3413193965 * short),
        b: encoded(-0.0041960863 * long - 0.7034186147 * medium + 1.7076147010 * short),
        a: 1.,
    }
}

fn accent_rgb(saved: &str) -> Rgb {
    Rgb::parse(saved).map(Rgb::darkened).unwrap_or(DEFAULT_ACCENT)
}

/// The highest value [`Rgb::darkened`] allows at this hue and saturation.
fn max_value(hue: f32, saturation: f32) -> f32 {
    Rgb::from_hsv(hue, saturation, 1.).darkened().hsv().2
}

pub struct AccentPicker {
    hue: f32,
    saturation: f32,
    value: f32,
    hue_slider: Entity<SliderState>,
    hex: Entity<InputState>,
    focus: FocusList,
    on_pick: OnPick,
    _subscriptions: Vec<Subscription>,
}

impl AccentPicker {
    /// `on_pick` gets the hex to save: blank when the pick is the default.
    pub fn open(saved: &str, on_pick: impl Fn(String, &mut App) + 'static, window: &mut Window, cx: &mut App) {
        let start = accent_rgb(saved);
        let view = cx.new(|cx| Self::new(start, Box::new(on_pick), window, cx));
        window.open_dialog(cx, move |dialog, window, _| {
            let enter = view.downgrade();
            chrome(dialog, DEFAULT_WIDTH, window)
                .on_ok(move |_, window, cx| {
                    enter.update(cx, |view, cx| view.confirm(window, cx)).ok();
                    false
                })
                .child(view.clone())
        });
    }

    fn new(start: Rgb, on_pick: OnPick, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let (hue, saturation, value) = start.hsv();
        let hue_slider = cx.new(|_| SliderState::new().min(0.).max(HUE_MAX).step(1.).default_value(hue));
        let hex = cx.new(|cx| {
            let mut state = InputState::new(window, cx);
            state.set_value(start.to_hex(), window, cx);
            state
        });
        let slider_events = cx.subscribe_in(&hue_slider, window, |view, _, event: &SliderEvent, window, cx| {
            let (SliderEvent::Change(hue) | SliderEvent::Release(hue)) = event;
            view.hue = hue.end();
            let hex = view.picked().to_hex();
            view.hex.update(cx, |state, cx| state.set_value(hex, window, cx));
            cx.notify();
        });
        let hex_events = cx.subscribe_in(&hex, window, |view, input, event: &InputEvent, window, cx| {
            if !matches!(event, InputEvent::Change) {
                return;
            }
            let typed = input.read(cx).value().to_string();
            if let Some(color) = Rgb::parse(&typed)
                && color.darkened().to_hex() != view.picked().to_hex()
            {
                view.set_color(color, window, cx);
            }
        });
        Self {
            hue,
            saturation,
            value,
            hue_slider,
            hex,
            focus: FocusList::modal(None, cx),
            on_pick,
            _subscriptions: vec![slider_events, hex_events],
        }
    }

    fn picked(&self) -> Rgb {
        Rgb::from_hsv(self.hue, self.saturation, self.value.min(max_value(self.hue, self.saturation)))
    }

    fn set_color(&mut self, color: Rgb, window: &mut Window, cx: &mut Context<Self>) {
        let (hue, saturation, value) = color.darkened().hsv();
        (self.hue, self.saturation, self.value) = (hue, saturation, value);
        self.hue_slider.update(cx, |slider, cx| slider.set_value(hue, window, cx));
        cx.notify();
    }

    fn set_color_and_hex(&mut self, color: Rgb, window: &mut Window, cx: &mut Context<Self>) {
        self.set_color(color, window, cx);
        let hex = self.picked().to_hex();
        self.hex.update(cx, |state, cx| state.set_value(hex, window, cx));
    }

    fn confirm(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let hex = self.picked().to_hex();
        let saved = if hex == DEFAULT_ACCENT.to_hex() { String::new() } else { hex };
        (self.on_pick)(saved, cx);
        window.close_dialog(cx);
    }
}

impl Render for AccentPicker {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let picked = self.picked();
        let selected_hex = picked.to_hex();
        let name = t(Str::SettingsAccentColor);
        let mut swatches = h_flex().flex_wrap().gap_2();
        for (index, preset) in PRESETS.iter().enumerate() {
            let color = preset.darkened();
            let selected = color.to_hex() == selected_hex;
            let preset = *preset;
            let swatch = div()
                .id(("accent-preset", index))
                .role(Role::RadioButton)
                .aria_label(format!("{name}, {}", tf(Str::PlayAgainTrackOf, &[&(index + 1), &PRESETS.len()])))
                .aria_toggled(if selected { Toggled::True } else { Toggled::False })
                .size(px(32.))
                .p(px(4.))
                .rounded_full()
                .border_2()
                .border_color(if selected { theme.foreground } else { transparent_black() })
                .cursor_pointer()
                .child(div().size_full().rounded_full().bg(color.hsla()))
                .on_click(cx.listener(move |view, _, window, cx| view.set_color_and_hex(preset, window, cx)))
                .on_action(
                    cx.listener(move |view, _: &Confirm, window, cx| view.set_color_and_hex(preset, window, cx)),
                );
            swatches = swatches.child(self.focus.item(index, swatch, cx));
        }
        let hue_stops: Vec<Hsla> =
            (0..=6).map(|step| Rgb::from_hsv((step as f32 * 60.).min(HUE_MAX), 1., 1.).hsla()).collect();
        let mut hue_bar = h_flex().h(px(10.)).rounded(px(5.)).overflow_hidden();
        for pair in hue_stops.windows(2) {
            hue_bar = hue_bar.child(div().flex_1().h_full().bg(linear_gradient(
                90.,
                linear_color_stop(pair[0], 0.),
                linear_color_stop(pair[1], 1.),
            )));
        }
        let stops = PRESETS.len();
        let buttons = h_flex()
            .child(press(
                &self.focus,
                stops,
                text_button("accent-reset", t(Str::SoundReset), false, true, cx),
                true,
                cx.listener(|view, _, window, cx| view.set_color_and_hex(DEFAULT_ACCENT, window, cx)),
                cx,
            ))
            .child(press(
                &self.focus,
                stops + 1,
                text_button("accent-ok", t(Str::SoundOk), true, true, cx).text_color(picked.hsla()),
                true,
                cx.listener(|view, _, window, cx| view.confirm(window, cx)),
                cx,
            ));
        let body = self.focus.container(
            v_flex()
                .w_full()
                .px(px(ROW_HORIZONTAL))
                .py_4()
                .gap_4()
                .child(swatches)
                .child(v_flex().gap_2().child(hue_bar).child(self.focus.slider(
                    stops + 2,
                    &self.hue_slider,
                    SliderStop::new("accent-hue", name),
                    Slider::new(&self.hue_slider).bg(picked.hsla()).accessibility_label(name),
                    cx,
                )))
                .child(
                    h_flex()
                        .gap_3()
                        .items_center()
                        .child(
                            div().size(px(36.)).rounded_full().border_1().border_color(theme.border).bg(picked.hsla()),
                        )
                        .child(div().text_color(theme.muted_foreground).font_family("monospace").child("#"))
                        .child(div().w(px(120.)).child(Input::new(&self.hex).small()))
                        .child(div().flex_1()),
                ),
            0..stops + 2,
            cx,
        );
        v_flex().w_full().child(title_bar(name, cx)).child(body).child(button_bar(buttons, cx))
    }
}
