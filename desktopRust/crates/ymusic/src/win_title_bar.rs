//! The Windows title bar, drawn in gpui's client area (the native caption is hidden): the app icon
//! and name, the always-on-top pin, and minimize / maximize / close as Windows 11 draws them. Windows
//! hit-tests the bar through gpui's `WindowControlArea`s, so dragging, double-click to maximize,
//! Aero Snap, the Snap Layouts flyout and the system menu stay the system's own.

use std::sync::OnceLock;

use gpui_kit::component::button::{Button, ButtonCustomVariant, ButtonRounded, ButtonVariants};
use gpui_kit::component::{ActiveTheme, FocusableExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t};

/// Windows 11 caption metrics in logical pixels.
const HEIGHT: f32 = 32.;
const BUTTON_WIDTH: f32 = 46.;
const GLYPH_SIZE: f32 = 10.;
const PIN_GLYPH_SIZE: f32 = 12.;
const ICON_SIZE: f32 = 16.;
const ICON_START: f32 = 8.;
const TITLE_GAP: f32 = 8.;
const TITLE_SIZE: f32 = 12.;
const CLOSE_RED: u32 = 0xC42B1C;

/// Segoe Fluent Icons / MDL2 Assets code points: ChromeMinimize, ChromeMaximize, ChromeRestore,
/// ChromeClose, Pin.
const MINIMIZE: &str = "\u{E921}";
const MAXIMIZE: &str = "\u{E922}";
const RESTORE: &str = "\u{E923}";
const CLOSE: &str = "\u{E8BB}";
const PIN: &str = "\u{E718}";

/// The window content under the title bar; a fullscreen window has none. The bar is deferred above
/// a dialog's input-blocking layer, so minimize, maximize and close keep working while a dialog is
/// open, as the system caption's did.
pub fn frame(content: AnyElement, window: &mut Window, cx: &mut App) -> AnyElement {
    if window.is_fullscreen() {
        return content;
    }
    v_flex()
        .size_full()
        .child(deferred(title_bar(window, cx)))
        .child(div().flex_1().min_h_0().child(content))
        .into_any_element()
}

/// The caption's ink and fills (WinUI's caption button resources), dimmed while the window is
/// inactive.
struct Ink {
    rest: Hsla,
    strong: Hsla,
    hover: Hsla,
    pressed: Hsla,
}

impl Ink {
    fn new(dark: bool, active: bool) -> Self {
        let base = if dark { white() } else { black() };
        let strong = base.opacity(if dark { 1. } else { 0.894 });
        Self {
            rest: if active { strong } else { base.opacity(0.36) },
            strong,
            hover: base.opacity(if dark { 0.059 } else { 0.035 }),
            pressed: base.opacity(if dark { 0.039 } else { 0.024 }),
        }
    }
}

fn title_bar(window: &mut Window, cx: &mut App) -> GrayscaleText {
    let pin_focus = window.use_keyed_state("title-bar-pin-focus", cx, |_, cx| cx.focus_handle()).read(cx).clone();
    let theme = cx.theme();
    let ink = Ink::new(theme.mode.is_dark(), window.is_window_active());
    let glyphs = glyph_font(cx);
    let pinned = crate::desktop::always_on_top(window);
    let logo_px = (ICON_SIZE * window.scale_factor()).round() as u32;
    let caption = h_flex()
        .id("title-bar-caption")
        .flex_1()
        .min_w_0()
        .h_full()
        .pl(px(ICON_START))
        .gap(px(TITLE_GAP))
        .window_control_area(WindowControlArea::Drag)
        .child(img(crate::assets::logo_path(logo_px)).size(px(ICON_SIZE)).flex_shrink_0().rounded_full())
        .child(
            div()
                .min_w_0()
                .truncate()
                .font_family("Segoe UI")
                .text_size(px(TITLE_SIZE))
                .text_color(ink.rest)
                .child(t(Str::AppName)),
        );
    let pin = Button::new("always-on-top")
        .custom(
            ButtonCustomVariant::new(cx)
                .color(transparent_black())
                .foreground(ink.rest)
                .hover(ink.hover)
                .active(ink.pressed),
        )
        .rounded(ButtonRounded::None)
        .w(px(BUTTON_WIDTH))
        .h_full()
        .px_0()
        .toggled(pinned)
        .accessibility_label(t(Str::CdAlwaysOnTop))
        .tooltip(t(Str::CdAlwaysOnTop))
        .track_focus(&pin_focus)
        // gpui-kit's ring sits outside the button, where the window edge and the content clip it.
        .focus_ring(false)
        .child(
            div()
                .font_family(glyphs)
                .text_size(px(PIN_GLYPH_SIZE))
                .line_height(px(PIN_GLYPH_SIZE))
                .when(pinned, |glyph| glyph.text_color(theme.primary))
                .child(PIN),
        )
        .when(pin_focus.is_focused(window), |pin| {
            pin.child(div().absolute().inset_0().border_2().border_color(theme.ring))
        })
        .on_click(|_, window, _| {
            crate::desktop::set_always_on_top(window, !crate::desktop::always_on_top(window));
            window.refresh();
        });
    let maximize = if window.is_maximized() { RESTORE } else { MAXIMIZE };
    GrayscaleText(
        h_flex()
            .h(px(HEIGHT))
            .flex_shrink_0()
            .bg(theme.title_bar)
            .child(caption)
            .child(pin)
            .child(caption_button("title-bar-minimize", MINIMIZE, WindowControlArea::Min, &ink, glyphs))
            .child(caption_button("title-bar-maximize", maximize, WindowControlArea::Max, &ink, glyphs))
            .child(caption_button("title-bar-close", CLOSE, WindowControlArea::Close, &ink, glyphs))
            .into_any_element(),
    )
}

/// One of the system's caption buttons: Windows presses it (the hit test answers its area), so it
/// only paints.
fn caption_button(
    id: &'static str,
    glyph: &'static str,
    area: WindowControlArea,
    ink: &Ink,
    font: &'static str,
) -> impl IntoElement {
    let close = matches!(area, WindowControlArea::Close);
    let (hover_bg, hover_fg, pressed_bg, pressed_fg) = if close {
        let red = rgb(CLOSE_RED);
        (red.into(), white(), Hsla::from(red).opacity(0.9), white().opacity(0.7))
    } else {
        (ink.hover, ink.strong, ink.pressed, ink.strong.opacity(0.7))
    };
    div()
        .id(id)
        .w(px(BUTTON_WIDTH))
        .h_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .font_family(font)
        .text_size(px(GLYPH_SIZE))
        .line_height(px(GLYPH_SIZE))
        .text_color(ink.rest)
        .window_control_area(area)
        .hover(move |style| style.bg(hover_bg).text_color(hover_fg))
        .active(move |style| style.bg(pressed_bg).text_color(pressed_fg))
        .child(glyph)
}

/// Windows 11's caption glyph font, else Windows 10's, which has the same code points.
fn glyph_font(cx: &App) -> &'static str {
    static FAMILY: OnceLock<&'static str> = OnceLock::new();
    FAMILY.get_or_init(|| {
        const FLUENT: &str = "Segoe Fluent Icons";
        if cx.text_system().all_font_names().iter().any(|name| name == FLUENT) { FLUENT } else { "Segoe MDL2 Assets" }
    })
}

/// Paints its child's text with grayscale antialiasing, as Windows paints a caption: ClearType
/// fringes the caption glyphs' strokes with color.
struct GrayscaleText(AnyElement);

impl IntoElement for GrayscaleText {
    type Element = Self;

    fn into_element(self) -> Self {
        self
    }
}

impl Element for GrayscaleText {
    type RequestLayoutState = ();
    type PrepaintState = ();

    fn id(&self) -> Option<ElementId> {
        None
    }

    fn source_location(&self) -> Option<&'static std::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        (self.0.request_layout(window, cx), ())
    }

    fn prepaint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        _: Bounds<Pixels>,
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        self.0.prepaint(window, cx);
    }

    fn paint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        _: Bounds<Pixels>,
        _: &mut (),
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        // The mode is app-wide: set for this subtree's glyphs only, then put back.
        let mode = cx.text_rendering_mode();
        cx.set_text_rendering_mode(TextRenderingMode::Grayscale);
        self.0.paint(window, cx);
        cx.set_text_rendering_mode(mode);
    }
}
