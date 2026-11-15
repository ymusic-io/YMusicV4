//! The loading spinner and line. The app runs with reduce motion on (no enter/exit transitions),
//! which also freezes gpui's animations, but these are progress indicators, not decoration: they
//! move by the clock and ask for the next frame themselves.

use std::time::{SystemTime, UNIX_EPOCH};

use gpui_kit::component::button::Button;
use gpui_kit::component::{ActiveTheme, Icon, IconName, Sizable, Size};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

const TURN_MS: u128 = 900;
const SWEEP_MS: u128 = 1400;
/// The sweeping segment's width, as a fraction of the line.
const SEGMENT: f32 = 0.4;

/// How far through its current `period_ms` cycle the clock is, in `0..1`.
fn cycle(period_ms: u128) -> f32 {
    let millis = SystemTime::now().duration_since(UNIX_EPOCH).map(|elapsed| elapsed.as_millis()).unwrap_or_default();
    (millis % period_ms) as f32 / period_ms as f32
}

#[derive(IntoElement)]
pub struct Spinner {
    size: Size,
}

impl Spinner {
    pub fn new() -> Self {
        Self { size: Size::Medium }
    }

    pub fn small(mut self) -> Self {
        self.size = Size::Small;
        self
    }

    pub fn large(mut self) -> Self {
        self.size = Size::Large;
        self
    }
}

impl Default for Spinner {
    fn default() -> Self {
        Self::new()
    }
}

impl RenderOnce for Spinner {
    fn render(self, window: &mut Window, _: &mut App) -> impl IntoElement {
        let turn = cycle(TURN_MS);
        window.request_animation_frame();
        div()
            .child(Icon::new(IconName::Loader).with_size(self.size).transform(Transformation::rotate(percentage(turn))))
    }
}

/// The JVM sign-in page's load bar for work with no known progress: a 2 px accent line whose
/// segment sweeps across, exposed as an indeterminate progress indicator named `label`.
#[derive(IntoElement)]
pub struct LoadingLine {
    id: ElementId,
    label: SharedString,
}

impl LoadingLine {
    pub fn new(id: impl Into<ElementId>, label: impl Into<SharedString>) -> Self {
        Self { id: id.into(), label: label.into() }
    }
}

impl RenderOnce for LoadingLine {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let head = cycle(SWEEP_MS) * (1. + SEGMENT);
        let (start, end) = ((head - SEGMENT).max(0.), head.min(1.));
        window.request_animation_frame();
        div().id(self.id).role(Role::ProgressIndicator).aria_label(self.label).relative().w_full().h(px(2.)).child(
            div()
                .absolute()
                .top_0()
                .h_full()
                .left(relative(start))
                .w(relative(end - start))
                .bg(cx.theme().progress_bar),
        )
    }
}

/// A busy button: inert like gpui-kit's `loading`, with the clock-driven spinner after its label.
/// Never pair it with `.icon(..)` while busy: gpui-kit would draw its own, frozen, spinner there.
pub trait BusyButton {
    fn busy(self, busy: bool) -> Self;
}

impl BusyButton for Button {
    fn busy(self, busy: bool) -> Self {
        self.loading(busy).when(busy, |button| button.child(Spinner::new().small()))
    }
}
