//! Settings dialogs that are lists: the content region picker and the open source licenses.

use gpui_kit::component::{ActiveTheme, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use std::rc::Rc;

use gpui_kit::base::actions::Confirm;
use gpui_kit::*;
use ym_i18n::{Str, t};

use crate::a11y::FocusList;
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, ROW_VERTICAL, button_bar, chrome, press, scroll_body, text_button, title_bar,
};
use crate::settings_view::{HOVER_ALPHA, SettingsView};
use crate::spinner::Spinner;

fn close_button(index: usize, focus: &FocusList, label: &'static str, cx: &App) -> Div {
    press(
        focus,
        index,
        text_button("list-close", label, false, true, cx),
        true,
        |_, window, cx| window.close_dialog(cx),
        cx,
    )
}

/// The list dialog's column: the title, the scrolling `list` and the bar with `dismiss`.
fn list_dialog<V: 'static>(
    title: &'static str,
    list: impl IntoElement,
    dismiss: Div,
    scroll: &ScrollHandle,
    window: &Window,
    cx: &mut Context<V>,
) -> Div {
    v_flex()
        .w_full()
        .child(title_bar(title, cx))
        .child(scroll_body("dialog-list", scroll, list, window, cx))
        .child(button_bar(dismiss, cx))
}

/// The regions YouTube offers, labelled in the content language; the current one is marked.
pub struct RegionPicker {
    settings: Entity<SettingsView>,
    focus: FocusList,
    scroll: ScrollHandle,
    focused_first: bool,
    _observe: Subscription,
}

impl RegionPicker {
    pub fn open(settings: Entity<SettingsView>, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| {
            let scroll = ScrollHandle::new();
            let observe = cx.observe(&settings, |_, _, cx| cx.notify());
            Self {
                settings,
                focus: FocusList::modal(Some(scroll.clone()), cx),
                scroll,
                focused_first: false,
                _observe: observe,
            }
        });
        window.open_dialog(cx, move |dialog, window, _| chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()));
    }
}

impl Render for RegionPicker {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let (current, regions, loading) = {
            let settings = self.settings.read(cx);
            let locales = settings.locales.as_ref();
            (
                locales.map(|locales| locales.gl.clone()).unwrap_or_default(),
                locales.map(|locales| locales.regions.clone()).unwrap_or_default(),
                settings.locales_loading,
            )
        };
        let selected = regions.iter().position(|region| region.code.eq_ignore_ascii_case(&current));
        if !self.focused_first && !regions.is_empty() {
            self.focused_first = true;
            let first = self.focus.handle(selected.unwrap_or(0), cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let mut list = v_flex();
        for (index, region) in regions.iter().enumerate() {
            let is_selected = selected == Some(index);
            let (code, settings) = (region.code.clone(), self.settings.clone());
            let pick = Rc::new(move |window: &mut Window, cx: &mut App| {
                settings.update(cx, |settings, cx| settings.pick_region(code.clone(), window, cx));
                window.close_dialog(cx);
            });
            let on_enter = pick.clone();
            let row = div()
                .id(("region", index))
                .role(Role::RadioButton)
                .aria_label(region.label.clone())
                .aria_toggled(if is_selected { Toggled::True } else { Toggled::False })
                .w_full()
                .px(px(ROW_HORIZONTAL))
                .py(px(ROW_VERTICAL))
                .cursor_pointer()
                .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                .text_size(px(14.))
                .text_color(theme.foreground)
                .when(is_selected, |row| row.font_semibold().text_color(theme.primary))
                .child(region.label.clone())
                .on_click(move |_, window, cx| pick(window, cx))
                // The dialog binds Enter to closing; a focused row takes it first.
                .on_action(move |_: &Confirm, window, cx| on_enter(window, cx));
            list = list.child(self.focus.item(index, row, cx));
        }
        if loading && regions.len() <= 1 {
            list = list.child(h_flex().justify_center().py_6().child(Spinner::new()));
        }
        let close = regions.len();
        let dismiss = close_button(close, &self.focus, t(Str::DialogCancel), cx);
        let column = list_dialog(t(Str::SettingsContentRegion), list, dismiss, &self.scroll, window, cx);
        self.focus.container(column, 0..close + 1, cx)
    }
}

struct Component {
    name: &'static str,
    license: &'static str,
    url: &'static str,
}

const fn component(name: &'static str, license: &'static str, url: &'static str) -> Component {
    Component { name, license, url }
}

/// The shipped third-party components, hand-maintained alongside the workspace manifests: build-only
/// crates (cc, build scripts) are not distributed and stay out. FFmpeg is built LGPL (no
/// `--enable-gpl`), from github.com/ymusic-io/ffmpeg-build.
const COMPONENTS: &[Component] = &[
    component("gpui (Zed Industries)", "Apache-2.0", "https://github.com/zed-industries/zed"),
    component("gpui-kit", "Apache-2.0", "https://github.com/longbridge/gpui-kit"),
    component("Lucide icons", "ISC", "https://lucide.dev"),
    component("Tokio", "MIT", "https://github.com/tokio-rs/tokio"),
    component("reqwest", "MIT / Apache-2.0", "https://github.com/seanmonstar/reqwest"),
    component("rustls", "Apache-2.0 / ISC / MIT", "https://github.com/rustls/rustls"),
    component("Serde, serde_json", "MIT / Apache-2.0", "https://github.com/serde-rs/serde"),
    component("rusqlite", "MIT", "https://github.com/rusqlite/rusqlite"),
    component("SQLite", "Public Domain", "https://sqlite.org"),
    component("QuickJS (Fabrice Bellard, Charlie Gordon)", "MIT", "https://bellard.org/quickjs/"),
    component("FFmpeg (libav* libraries, ffmpeg CLI)", "LGPL-2.1+", "https://github.com/ymusic-io/ffmpeg-build"),
    component("libvpx (linked into FFmpeg)", "BSD-3-Clause", "https://chromium.googlesource.com/webm/libvpx"),
    component("dav1d (linked into FFmpeg)", "BSD-2-Clause", "https://code.videolan.org/videolan/dav1d"),
    component("LAME (linked into FFmpeg)", "LGPL-2.0+", "https://lame.sourceforge.io"),
    component("Shine (linked into FFmpeg)", "LGPL-2.0+", "https://github.com/toots/shine"),
    component("Sentry SDK for Rust", "MIT", "https://github.com/getsentry/sentry-rust"),
    component("RustCrypto (aes-gcm, p256, hmac, sha2, pbkdf2)", "MIT / Apache-2.0", "https://github.com/RustCrypto"),
    component("image", "MIT / Apache-2.0", "https://github.com/image-rs/image"),
    component("qrcode", "MIT / Apache-2.0", "https://github.com/kennytm/qrcode-rust"),
    component("cookie_store", "MIT / Apache-2.0", "https://github.com/pfernie/cookie_store"),
    component("regex", "MIT / Apache-2.0", "https://github.com/rust-lang/regex"),
    component("flate2", "MIT / Apache-2.0", "https://github.com/rust-lang/flate2-rs"),
    component("url", "MIT / Apache-2.0", "https://github.com/servo/rust-url"),
    component("intl_pluralrules, unic-langid", "MIT / Apache-2.0", "https://github.com/zbraniecki/pluralrules"),
    component("zbus", "MIT", "https://github.com/dbus2/zbus"),
    component("rfd", "MIT", "https://github.com/PolyMeilex/rfd"),
    component("tray-icon", "MIT / Apache-2.0", "https://github.com/tauri-apps/tray-icon"),
    component("objc2", "MIT", "https://github.com/madsmtm/objc2"),
    component("security-framework", "MIT / Apache-2.0", "https://github.com/kornelski/rust-security-framework"),
];

pub struct Licenses {
    focus: FocusList,
    scroll: ScrollHandle,
    focused_first: bool,
}

impl Licenses {
    pub fn open(window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| {
            let scroll = ScrollHandle::new();
            Self { focus: FocusList::modal(Some(scroll.clone()), cx), scroll, focused_first: false }
        });
        window.open_dialog(cx, move |dialog, window, _| chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()));
    }
}

impl Render for Licenses {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(0, cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let theme = cx.theme().clone();
        let mut list = v_flex().w_full().child(
            div()
                .px(px(ROW_HORIZONTAL))
                .py(px(ROW_VERTICAL))
                .text_size(px(12.))
                .text_color(theme.muted_foreground)
                .child(t(Str::SettingsLicensesIntro)),
        );
        for (index, component) in COMPONENTS.iter().enumerate() {
            let url = component.url;
            let row = h_flex()
                .id(("license", index))
                .role(Role::Link)
                .aria_label(format!("{}, {}", component.name, component.license))
                .w_full()
                .gap_4()
                .px(px(ROW_HORIZONTAL))
                .py_2()
                .items_center()
                .cursor_pointer()
                .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                .child(div().flex_1().min_w_0().text_size(px(13.)).child(component.name))
                .child(
                    div()
                        .flex_shrink_0()
                        .text_size(px(12.))
                        .text_color(theme.muted_foreground)
                        .child(component.license),
                )
                .on_click(move |_, _, cx| cx.open_url(url))
                .on_action(move |_: &Confirm, _, cx| cx.open_url(url));
            list = list.child(self.focus.item(index, row, cx));
        }
        let close = COMPONENTS.len();
        let dismiss = close_button(close, &self.focus, t(Str::CdClose), cx);
        let column = list_dialog(t(Str::SettingsLicenses), list, dismiss, &self.scroll, window, cx);
        self.focus.container(column, 0..close + 1, cx)
    }
}
