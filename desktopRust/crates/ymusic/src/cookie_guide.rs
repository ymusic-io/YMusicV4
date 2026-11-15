//! How to copy a signed-in request out of a browser, the JVM's CookieCopyGuide: a schematic
//! browser window that steps through youtube.com → developer tools → Network → a Fetch/XHR request
//! → Copy as cURL, a caption per step, step marks to jump to one, and pause. Each step is a still
//! frame (the app runs with reduce motion); the steps advance on their own until paused.

use std::time::Duration;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::{ActiveTheme, Icon, Selectable, StyledExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t};

use crate::a11y::{FocusList, label};
use crate::action_icon::sized_action_icon;

const WINDOW_MAX: f32 = 380.;
const WINDOW_HEIGHT: f32 = 220.;
const TABS_HEIGHT: f32 = 22.;
const TOOLBAR_HEIGHT: f32 = 28.;
const TOOLS_HEIGHT: f32 = 140.;
const TOOLS_TAB_ROW: f32 = 24.;
const NETWORK_HEAD: f32 = 17.;
const NETWORK_ROW: f32 = 16.;
const MENU_WIDTH: f32 = 128.;
/// Children are not clipped to the frame's rounding, so the edge ones round themselves.
const INNER_RADIUS: f32 = 9.;
const PAUSE_ICON: f32 = 20.;
const PAUSE_HIT: f32 = 36.;

/// Light schematic chrome: it stays light in the dark theme so it still reads as a browser window.
mod chrome {
    pub const FRAME: u32 = 0xE4E6EA;
    pub const SURFACE: u32 = 0xFFFFFF;
    pub const TAB_IDLE: u32 = 0xD5D8DE;
    pub const ADDRESS: u32 = 0xF1F3F4;
    pub const PAGE: u32 = 0xF6F6F6;
    pub const HEADER: u32 = 0xC62828;
    pub const TOOLS: u32 = 0xEEF0F3;
    pub const TOOLS_LINE: u32 = 0xD0D4DA;
    pub const TEXT: u32 = 0x3C4043;
    pub const TEXT_MUTED: u32 = 0x80868B;
}

#[derive(Clone, Copy, PartialEq)]
enum Browser {
    Chrome,
    Firefox,
    Safari,
}

impl Browser {
    const ALL: [Browser; 3] = [Browser::Chrome, Browser::Firefox, Browser::Safari];

    fn label(self) -> &'static str {
        t(match self {
            Browser::Chrome => Str::SigninPasteBrowserChrome,
            Browser::Firefox => Str::SigninPasteBrowserFirefox,
            Browser::Safari => Str::SigninPasteBrowserSafari,
        })
    }

    fn hint(self) -> &'static str {
        t(match self {
            Browser::Chrome => Str::SigninPasteHint,
            Browser::Firefox => Str::SigninPasteHintFirefox,
            Browser::Safari => Str::SigninPasteHintSafari,
        })
    }

    fn devtools_key(self) -> &'static str {
        if self == Browser::Safari { "⌥⌘I" } else { "F12" }
    }
}

#[derive(Clone, Copy, PartialEq, PartialOrd)]
enum Step {
    Address,
    DevTools,
    Network,
    Request,
    Copy,
}

impl Step {
    const ALL: [Step; 5] = [Step::Address, Step::DevTools, Step::Network, Step::Request, Step::Copy];

    fn duration(self) -> Duration {
        Duration::from_millis(match self {
            Step::Address => 4000,
            Step::DevTools | Step::Network => 3600,
            Step::Request => 4400,
            Step::Copy => 5200,
        })
    }

    fn caption(self, browser: Browser) -> &'static str {
        t(match self {
            Step::Address => Str::SigninPasteGuideAddress,
            Step::DevTools if browser == Browser::Safari => Str::SigninPasteGuideInspector,
            Step::DevTools => Str::SigninPasteGuideF12,
            Step::Network => Str::SigninPasteGuideNetwork,
            Step::Request => Str::SigninPasteGuideRequest,
            Step::Copy => Str::SigninPasteGuideCopy,
        })
    }

    /// Where the pointer rests, from the window's top left.
    fn pointer(self) -> Point<Pixels> {
        let tools_top = WINDOW_HEIGHT - TOOLS_HEIGHT;
        match self {
            Step::Address | Step::DevTools => point(px(150.), px(TABS_HEIGHT + TOOLBAR_HEIGHT / 2.)),
            Step::Network => point(px(96.), px(tools_top + TOOLS_TAB_ROW / 2.)),
            Step::Request | Step::Copy => {
                point(px(70.), px(tools_top + TOOLS_TAB_ROW + 1. + NETWORK_HEAD + 1. + NETWORK_ROW / 2.))
            }
        }
    }
}

pub struct CookieGuide {
    browser: Browser,
    step: Step,
    paused: bool,
    focus: FocusList,
    _advance: Option<Task<()>>,
}

impl CookieGuide {
    pub fn new(cx: &mut Context<Self>) -> Self {
        let browser = if cfg!(target_os = "macos") { Browser::Safari } else { Browser::Chrome };
        let mut guide =
            Self { browser, step: Step::Address, paused: false, focus: FocusList::new(None), _advance: None };
        guide.go_to(Step::Address, cx);
        guide
    }

    /// Shows `step` and, unless paused, moves on after its time.
    fn go_to(&mut self, step: Step, cx: &mut Context<Self>) {
        self.step = step;
        self._advance = (!self.paused).then(|| {
            let executor = cx.background_executor().clone();
            cx.spawn(async move |this, cx| {
                executor.timer(step.duration()).await;
                this.update(cx, |guide, cx| {
                    let next = Step::ALL[(Step::ALL.iter().position(|each| *each == step).unwrap_or(0) + 1) % 5];
                    guide.go_to(next, cx);
                })
                .ok();
            })
        });
        cx.notify();
    }

    fn set_paused(&mut self, paused: bool, cx: &mut Context<Self>) {
        self.paused = paused;
        self.go_to(self.step, cx);
    }

    fn window_frame(&self, cx: &App) -> AnyElement {
        let accent = cx.theme().primary;
        let step = self.step;
        let tools_open = step >= Step::DevTools;
        let tabs = h_flex()
            .h(px(TABS_HEIGHT))
            .px_2()
            .pt_1()
            .gap(px(6.))
            .items_end()
            .rounded_t(px(INNER_RADIUS))
            .bg(rgb(chrome::FRAME))
            .child(
                h_flex()
                    .h_full()
                    .px_2()
                    .gap(px(6.))
                    .items_center()
                    .rounded_t(px(6.))
                    .bg(rgb(chrome::SURFACE))
                    .child(div().size(px(8.)).rounded_full().bg(rgb(chrome::HEADER)))
                    .child(div().text_size(px(8.)).text_color(rgb(chrome::TEXT)).child("YouTube")),
            )
            .child(div().w(px(56.)).h_full().rounded_t(px(6.)).bg(rgb(chrome::TAB_IDLE)));
        let glyph = |text: &'static str| div().text_size(px(10.)).text_color(rgb(chrome::TEXT_MUTED)).child(text);
        let address_focused = step == Step::Address;
        let toolbar = h_flex()
            .h(px(TOOLBAR_HEIGHT))
            .px_2()
            .gap(px(6.))
            .items_center()
            .bg(rgb(chrome::SURFACE))
            .child(glyph("←"))
            .child(glyph("→"))
            .child(glyph("↻"))
            .child(
                h_flex()
                    .flex_1()
                    .h(px(18.))
                    .px_2()
                    .items_center()
                    .rounded(px(9.))
                    .bg(rgb(chrome::ADDRESS))
                    .border(px(if address_focused { 1.5 } else { 1. }))
                    .border_color(if address_focused { accent } else { rgb(chrome::TOOLS_LINE).into() })
                    .child(div().text_size(px(9.)).text_color(rgb(chrome::TEXT)).child("youtube.com")),
            );
        let page = v_flex()
            .flex_1()
            .when(!tools_open, |page| page.rounded_b(px(INNER_RADIUS)))
            .bg(rgb(chrome::PAGE))
            .child(div().h(px(8.)).bg(rgb(chrome::HEADER)))
            .child(
                div()
                    .flex_1()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(Icon::new(Lucide::Play).size(px(20.)).text_color(rgb(chrome::HEADER))),
            );
        let mut frame = div()
            .relative()
            .w_full()
            .h(px(WINDOW_HEIGHT))
            .overflow_hidden()
            .rounded(px(INNER_RADIUS + 1.))
            .border_1()
            .border_color(cx.theme().border)
            .bg(rgb(chrome::SURFACE))
            .child(
                v_flex()
                    .size_full()
                    .child(tabs)
                    .child(toolbar)
                    .child(page)
                    .when(tools_open, |column| column.child(self.devtools(accent))),
            );
        if step == Step::Copy {
            let at = step.pointer();
            frame = frame.child(
                v_flex()
                    .absolute()
                    .left(at.x + px(6.))
                    .top(at.y + px(6.))
                    .w(px(MENU_WIDTH))
                    .py(px(3.))
                    .rounded(px(4.))
                    .border_1()
                    .border_color(rgb(chrome::TOOLS_LINE))
                    .bg(rgb(chrome::SURFACE))
                    .child(menu_row("Copy", false, accent))
                    .child(menu_row("Copy as cURL", true, accent))
                    .child(menu_row("Copy as fetch", false, accent)),
            );
        }
        let at = step.pointer();
        frame = frame.child(
            div()
                .absolute()
                .left(at.x - px(2.))
                .top(at.y - px(2.))
                .child(Icon::new(Lucide::MousePointer2).size(px(18.)).text_color(accent)),
        );
        let key = match step {
            Step::DevTools => Some((self.browser.devtools_key(), true)),
            Step::Request => Some(("F5", true)),
            Step::Copy => Some((t(Str::SigninPasteGuideRightClick), false)),
            _ => None,
        };
        if let Some((label, top)) = key {
            let chip = div()
                .absolute()
                .right(px(12.))
                .px_2()
                .py_1()
                .rounded(px(6.))
                .bg(accent)
                .text_size(px(11.))
                .font_bold()
                .font_family("Menlo")
                .text_color(white())
                .child(label);
            frame = frame.child(if top { chip.top(px(52.)) } else { chip.bottom(px(28.)) });
        }
        frame.into_any_element()
    }

    fn devtools(&self, accent: Hsla) -> AnyElement {
        let network = self.step >= Step::Network;
        let request = self.step >= Step::Request;
        let tools_tab = |label: &'static str, selected: bool| {
            v_flex()
                .px(px(5.))
                .pt(px(2.))
                .gap(px(2.))
                .rounded(px(3.))
                .when(selected, |tab| tab.bg(accent.opacity(0.22)))
                .child(
                    div()
                        .text_size(px(10.))
                        .when(selected, |text| text.font_semibold())
                        .text_color(if selected { accent } else { rgb(chrome::TEXT_MUTED).into() })
                        .child(label),
                )
                .child(div().h(px(2.)).w_full().when(selected, |line| line.bg(accent)))
        };
        let line = || div().h(px(1.)).w_full().bg(rgb(chrome::TOOLS_LINE));
        let head = |text: &'static str| {
            div().text_size(px(8.)).font_semibold().text_color(rgb(chrome::TEXT_MUTED)).child(text)
        };
        v_flex()
            .h(px(TOOLS_HEIGHT))
            .w_full()
            .rounded_b(px(INNER_RADIUS))
            .bg(rgb(chrome::TOOLS))
            .child(
                h_flex()
                    .h(px(TOOLS_TAB_ROW))
                    .px(px(6.))
                    .gap_2()
                    .items_center()
                    .child(tools_tab("Elements", !network))
                    .child(tools_tab("Network", network)),
            )
            .child(line())
            .child(
                v_flex()
                    .flex_1()
                    .rounded_b(px(INNER_RADIUS))
                    .bg(rgb(chrome::SURFACE))
                    .child(
                        h_flex()
                            .h(px(NETWORK_HEAD))
                            .px(px(6.))
                            .gap_2()
                            .items_center()
                            .child(div().flex_1().child(head("Name")))
                            .child(head("Status"))
                            .child(head("Type")),
                    )
                    .child(line())
                    .child(network_row("generate_204", "204", "xhr", request, accent))
                    .child(network_row("getDatasyncIdsEndpoint", "200", "fetch", false, accent))
                    .child(network_row("base.js", "200", "script", false, accent)),
            )
            .into_any_element()
    }
}

fn network_row(name: &'static str, status: &'static str, kind: &'static str, selected: bool, accent: Hsla) -> Div {
    let cell = |text: &'static str| {
        div()
            .text_size(px(8.))
            .when(selected, |cell| cell.font_semibold())
            .text_color(if selected { accent } else { rgb(chrome::TEXT).into() })
            .child(text)
    };
    h_flex()
        .h(px(NETWORK_ROW))
        .px(px(6.))
        .gap_2()
        .items_center()
        .when(selected, |row| row.bg(accent.opacity(0.22)))
        .child(div().flex_1().min_w_0().truncate().child(cell(name)))
        .child(cell(status))
        .child(cell(kind))
}

fn menu_row(label: &'static str, selected: bool, accent: Hsla) -> Div {
    div()
        .px_2()
        .py(px(3.))
        .text_size(px(8.))
        .when(selected, |row| row.font_semibold().bg(accent.opacity(0.22)))
        .text_color(if selected { accent } else { rgb(chrome::TEXT).into() })
        .child(label)
}

impl Render for CookieGuide {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let mut browsers = h_flex().gap_2().justify_center();
        for (index, browser) in Browser::ALL.into_iter().enumerate() {
            let selected = browser == self.browser;
            let chip = crate::action_icon::chip(Button::new(("signin-browser", index)))
                .label(browser.label())
                .selected(selected);
            let chip = if selected { chip.primary() } else { chip.outline() };
            let chip = chip.on_click(cx.listener(move |guide, _, _, cx| {
                guide.browser = browser;
                guide.paused = false;
                guide.go_to(Step::Address, cx);
            }));
            browsers = browsers.child(self.focus.button(index, chip, cx));
        }
        let schematic = div()
            .id("signin-paste-guide")
            .role(Role::Image)
            .aria_label(t(Str::CdSigninPasteGuide))
            .w_full()
            .child(self.window_frame(cx));
        let pause_label = t(if self.paused { Str::CdSigninPastePlay } else { Str::CdSigninPastePause });
        let pause = sized_action_icon(
            "signin-paste-pause",
            if self.paused { Lucide::Play } else { Lucide::Pause },
            pause_label,
            PAUSE_ICON,
            PAUSE_HIT,
        )
        .on_click(cx.listener(|guide, _, _, cx| guide.set_paused(!guide.paused, cx)));
        let mut marks = h_flex().flex_1().gap_1().items_center();
        let current = Step::ALL.iter().position(|each| *each == self.step).unwrap_or(0);
        for (index, step) in Step::ALL.into_iter().enumerate() {
            let mark = div()
                .id(("signin-paste-step", index))
                .role(Role::Button)
                .aria_label(step.caption(self.browser))
                .aria_selected(index == current)
                .flex_1()
                .h(px(24.))
                .flex()
                .items_center()
                .cursor_pointer()
                .rounded(px(4.))
                .child(div().w_full().h(px(3.)).rounded_full().bg(if index <= current {
                    theme.primary
                } else {
                    theme.border
                }))
                .on_click(cx.listener(move |guide, _, _, cx| guide.go_to(step, cx)));
            marks = marks.child(self.focus.item(Browser::ALL.len() + 1 + index, mark, cx).flex_1());
        }
        v_flex()
            .w_full()
            .items_center()
            .gap_2()
            .child(browsers)
            .child(
                v_flex()
                    .w_full()
                    .max_w(px(WINDOW_MAX))
                    .gap_1()
                    .child(schematic)
                    .child(div().w_full().text_center().text_sm().font_medium().child(self.step.caption(self.browser)))
                    .child(
                        h_flex()
                            .w_full()
                            .gap_2()
                            .items_center()
                            .child(self.focus.button(Browser::ALL.len(), pause, cx))
                            .child(marks),
                    ),
            )
            .child(
                label("signin-paste-hint", self.browser.hint())
                    .text_center()
                    .text_sm()
                    .text_color(theme.muted_foreground),
            )
    }
}
