//! Settings: appearance, playback, downloads, account, privacy and about.

use std::cell::Cell;
use std::path::PathBuf;

use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::switch::Switch;
use gpui_kit::component::{
    ActiveTheme, Colorize, FocusableExt, Icon, IconName, StyledExt, Theme, ThemeMode, WindowExt, h_flex, v_flex,
};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_engine::yt::{YtAccountInfo, YtLocales};
use ym_i18n::{Str, t, tf};
use ym_settings::SettingsStore;

use crate::a11y::{FocusList, heading};
use crate::accent::{ACCENT_KEY, AccentPicker, accent_color, oklab_lerp};
use crate::caption_style::{CaptionLook, CaptionStyleDialog};
use crate::downloads::download_folder;
use crate::feedback_view::link;
use crate::graph::{AppGraph, on_core};
use crate::settings_lists::{Licenses, RegionPicker};
use crate::signin::AccountChanged;
use crate::spinner::BusyButton;
use crate::sponsor_block::SPONSOR_BLOCK_KEY;
use crate::telemetry::STATS_KEY;

pub const THEME_KEY: &str = "app.theme";
/// The JVM app's hover: the accent at Material's hover alpha (its `AccentRipple`).
pub const HOVER_ALPHA: f32 = 0.08;
pub const LANGUAGE_KEY: &str = "app.uiLanguage";
const CRASH_REPORTS_KEY: &str = "telemetry.crashReports";
const AUTO_UPDATE_KEY: &str = "update.autoDownload";
pub const SHOW_FPS_KEY: &str = "debug.showFps";
const SOURCE_CODE_URL: &str = "https://github.com/ymusic-io/YMusicV4";
const TERMS_URL: &str = "https://github.com/ymusic-io/YMusicV4/blob/master/TERMS.md";
pub const CHANGELOG_URL: &str = "https://github.com/ymusic-io/YMusicV4/blob/master/CHANGELOG.md";

/// The saved theme: unset is dark (as in the JVM app), blank follows the system.
fn saved_theme(settings: &dyn SettingsStore) -> String {
    settings.get_raw(THEME_KEY).ok().flatten().unwrap_or_else(|| "dark".to_owned())
}

pub fn apply_saved_theme(settings: &dyn SettingsStore, cx: &mut App) {
    apply_theme(&saved_theme(settings), &saved(settings, ACCENT_KEY), cx);
}

/// `light`, `dark`, `amoled` (dark on pure black) or anything else for the system's, with the
/// accent (`RRGGBB`, blank = default) on every primary surface.
fn apply_theme(choice: &str, accent: &str, cx: &mut App) {
    match choice {
        "light" => Theme::change(ThemeMode::Light, None, cx),
        "dark" | "amoled" => Theme::change(ThemeMode::Dark, None, cx),
        _ => Theme::sync_system_appearance(None, cx),
    }
    let accent = accent_color(accent);
    crate::desktop::set_pin_accent(accent);
    let on_accent = white();
    let amoled = choice == "amoled";
    let ui_font = linux_ui_font(cx);
    let mut tones = ChromeTones { flat: black(), lifted: black(), dialog: black() };
    let mut stack = StackTones { near: black(), far: black(), tab: black() };
    let mut container = AccentTones { ground: black(), ink: black() };
    Theme::update(cx, |theme| {
        if let Some(font) = ui_font {
            theme.font_family = font;
        }
        theme.primary = accent;
        theme.primary_hover = accent.lighten(0.1);
        theme.primary_active = accent.darken(0.1);
        theme.primary_foreground = on_accent;
        theme.button_primary = accent;
        theme.button_primary_hover = accent.lighten(0.1);
        theme.button_primary_active = accent.darken(0.1);
        theme.button_primary_foreground = on_accent;
        theme.sidebar_primary = accent;
        theme.sidebar_primary_foreground = on_accent;
        theme.progress_bar = accent;
        theme.slider_bar = accent;
        theme.slider_thumb = accent;
        theme.switch_thumb = on_accent;
        // Material 3's extra-small shape, the JVM app's menus.
        theme.radius = px(4.);
        let palette = Palette::for_mode(theme.mode.is_dark(), amoled);
        theme.background = palette.surface;
        theme.foreground = palette.ink;
        theme.muted_foreground = palette.ink_secondary;
        theme.border = palette.divider;
        theme.sidebar = palette.surface;
        theme.sidebar_border = palette.divider;
        theme.title_bar = palette.surface_elevated;
        theme.title_bar_border = palette.divider;
        theme.tab_bar = palette.surface;
        theme.colors.list = palette.surface;
        theme.accordion = palette.surface;
        theme.popover = palette.surface_dim;
        theme.popover_foreground = palette.ink;
        theme.secondary = palette.settings_card;
        theme.secondary_foreground = palette.ink;
        theme.muted = palette.placeholder;
        theme.input = palette.outline;
        // The kit halves this token on a dark ghost button.
        theme.accent = accent.opacity(if theme.mode.is_dark() { 2. * HOVER_ALPHA } else { HOVER_ALPHA });
        theme.accent_foreground = palette.ink;
        // Adwaita's faint window outline. Corners stay square: gpui clips content to rectangles, so
        // a rounded frame needs every surface touching a bottom corner rounded too.
        if cfg!(target_os = "linux") {
            theme.window_border = if theme.mode.is_dark() { white().opacity(0.07) } else { black().opacity(0.15) };
        }
        // On dark the full elevated lift reads too strongly against the body (ElevatedBarDialog).
        let dialog = if theme.mode.is_dark() { palette.surface_dim } else { palette.surface_elevated };
        tones = ChromeTones { flat: palette.surface, lifted: palette.surface_elevated, dialog };
        stack = StackTones { near: palette.stack_near, far: palette.stack_far, tab: palette.stack_tab };
        container = if theme.mode.is_dark() {
            AccentTones { ground: oklab_lerp(palette.surface, accent, 0.3), ink: oklab_lerp(accent, white(), 0.35) }
        } else {
            AccentTones { ground: oklab_lerp(palette.surface, accent, 0.15), ink: oklab_lerp(accent, black(), 0.4) }
        };
    });
    cx.set_global(tones);
    cx.set_global(stack);
    cx.set_global(container);
}

/// The JVM app's `primaryContainer` / `onPrimaryContainer`: a selected rail or drawer entry, the
/// armed sleep timer card.
#[derive(Clone, Copy)]
pub struct AccentTones {
    pub ground: Hsla,
    pub ink: Hsla,
}

impl Global for AccentTones {}

/// The bars' grounds: flat over a page at its top, lifted while the page is scrolled from it, and a
/// dialog's title and button bars.
#[derive(Clone, Copy)]
pub struct ChromeTones {
    pub flat: Hsla,
    pub lifted: Hsla,
    pub dialog: Hsla,
}

impl Global for ChromeTones {}

/// The cards stacked behind a playlist's thumbnail: the two edges behind a list row's, the tab over
/// a grid card's.
#[derive(Clone, Copy)]
pub struct StackTones {
    pub near: Hsla,
    pub far: Hsla,
    pub tab: Hsla,
}

impl Global for StackTones {}

/// The JVM app's `DesignPalette` grounds and inks (commonUi Theme.kt), per mode.
struct Palette {
    surface: Hsla,
    surface_dim: Hsla,
    surface_elevated: Hsla,
    settings_card: Hsla,
    placeholder: Hsla,
    stack_near: Hsla,
    stack_far: Hsla,
    stack_tab: Hsla,
    outline: Hsla,
    divider: Hsla,
    ink: Hsla,
    ink_secondary: Hsla,
}

impl Palette {
    fn for_mode(dark: bool, amoled: bool) -> Self {
        let palette = if dark {
            Self {
                surface: rgb(0x272727).into(),
                surface_dim: rgb(0x313131).into(),
                surface_elevated: rgb(0x292929).into(),
                settings_card: rgb(0x333333).into(),
                placeholder: rgb(0x454545).into(),
                stack_near: rgb(0x454545).into(),
                stack_far: rgb(0x3d3d3d).into(),
                stack_tab: rgb(0x424242).into(),
                outline: rgb(0x5c5c5c).into(),
                divider: rgb(0x2a2a2a).into(),
                ink: rgb(0xe2e2e2).into(),
                ink_secondary: rgb(0x9a9a9a).into(),
            }
        } else {
            Self {
                surface: rgb(0xffffff).into(),
                surface_dim: rgb(0xfafafa).into(),
                surface_elevated: rgb(0xf5f5f5).into(),
                settings_card: rgb(0xf2f2f2).into(),
                placeholder: rgb(0xe3e3e3).into(),
                stack_near: rgb(0xececec).into(),
                stack_far: rgb(0xf3f3f3).into(),
                stack_tab: rgb(0xededed).into(),
                outline: rgb(0xcfcfcf).into(),
                divider: rgb(0xe6e6e6).into(),
                ink: rgb(0x333333).into(),
                ink_secondary: rgb(0x8a8a8a).into(),
            }
        };
        if !amoled {
            return palette;
        }
        let ground: Hsla = black();
        Self { surface: ground, surface_dim: ground, surface_elevated: ground, settings_card: ground, ..palette }
    }
}

/// Linux desktops' UI fonts, in the order GNOME and fontconfig fall back. gpui's system font there
/// is IBM Plex Sans, rarely installed, which leaves each glyph to a different fallback face and
/// breaks Vietnamese diacritics.
const LINUX_UI_FONTS: [&str; 5] = ["Adwaita Sans", "Cantarell", "Noto Sans", "Ubuntu Sans", "DejaVu Sans"];

fn linux_ui_font(cx: &App) -> Option<SharedString> {
    if !cfg!(target_os = "linux") {
        return None;
    }
    let installed = cx.text_system().all_font_names();
    LINUX_UI_FONTS.into_iter().find(|font| installed.iter().any(|name| name == font)).map(SharedString::from)
}

/// Writes a setting off the UI thread, then redraws every window so its readers see it.
pub fn save_setting(key: &'static str, value: String, cx: &mut App) {
    if key == crate::telemetry::CRASH_REPORTS_KEY {
        crate::telemetry::crash_reports_changed(&value);
    }
    let graph = cx.global::<AppGraph>();
    let (settings, log) = (graph.settings.clone(), graph.log.fork("settings"));
    let write = graph.tokio.spawn_blocking(move || settings.put_raw(key, value));
    cx.spawn(async move |cx| {
        match write.await {
            Ok(Ok(())) => {}
            Ok(Err(error)) => log.warn(&format!("R93000 setting {key} not saved"), Some(error.as_ref())),
            Err(error) => log.warn(&format!("R93000 setting {key} not saved"), Some(&error)),
        }
        cx.update(|cx| cx.refresh_windows());
    })
    .detach();
}

/// The saved UI language, else the system's.
pub fn apply_language(settings: &dyn SettingsStore) {
    match settings.get_raw(LANGUAGE_KEY).ok().flatten().filter(|tag| !tag.is_empty()) {
        Some(tag) => {
            ym_i18n::set_locale(&tag);
        }
        None => {
            ym_i18n::use_system_locale();
        }
    }
}

fn saved(settings: &dyn SettingsStore, key: &str) -> String {
    settings.get_raw(key).ok().flatten().unwrap_or_default()
}

/// The content locale changed: locale-shaped feeds refetch.
pub struct LocaleChanged;

pub struct SettingsView {
    playback: Entity<crate::playback::Playback>,
    account: Option<YtAccountInfo>,
    signing_out: bool,
    /// The working region and the region menu, from the engine; None until the first answer.
    pub locales: Option<YtLocales>,
    pub locales_loading: bool,
    /// Bytes the player cache holds, read off the UI thread; 0 until the first answer.
    cache_used: i64,
    scroll: ScrollHandle,
    focus: FocusList,
}

impl EventEmitter<LocaleChanged> for SettingsView {}
impl EventEmitter<AccountChanged> for SettingsView {}

impl SettingsView {
    pub fn new(playback: Entity<crate::playback::Playback>, cx: &mut Context<Self>) -> Self {
        let scroll = ScrollHandle::new();
        let mut view = Self {
            playback,
            account: None,
            signing_out: false,
            locales: None,
            locales_loading: false,
            cache_used: 0,
            scroll: scroll.clone(),
            focus: FocusList::new(Some(scroll)),
        };
        view.refresh_account(cx);
        view.load_locales(cx);
        view.refresh_cache_used(None, cx);
        view
    }

    /// Applies a new cap first when `max_mb` is set (evicting down to it), then re-reads the
    /// player cache's size.
    fn refresh_cache_used(&mut self, max_mb: Option<i32>, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let Some(cache) = graph.player_cache.clone() else { return };
        let log = graph.log.fork("player-cache");
        let read = on_core(cx, async move {
            let result = tokio::task::spawn_blocking(move || {
                if let Some(max_mb) = max_mb {
                    crate::player_cache::apply_max_size(&cache, max_mb, &log);
                }
                let used = cache.blobs.total_size();
                if let Err(error) = &used {
                    log.warn("player cache size not read", Some(error));
                }
                used.ok()
            })
            .await;
            result.ok().flatten()
        });
        cx.spawn(async move |this, cx| {
            if let Some(Some(used)) = read.await {
                this.update(cx, |view, cx| {
                    view.cache_used = used;
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    pub fn set_cache_max(&mut self, max_mb: i32, cx: &mut Context<Self>) {
        self.put(crate::player_cache::MAX_MB_KEY, max_mb.to_string(), cx);
        self.refresh_cache_used(Some(max_mb), cx);
    }

    fn load_locales(&mut self, cx: &mut Context<Self>) {
        self.locales_loading = true;
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("settings"));
        let load = on_core(cx, async move {
            let result = engine.locale_options(&log).await;
            if let Err(error) = &result {
                log.warn("locale options fetch failed", Some(error));
            }
            result
        });
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                view.locales_loading = false;
                if let Some(Ok(locales)) = result {
                    view.locales = Some(locales);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Tells the engine about a region pick (`Some`) or a language change (`None`), then re-asks for
    /// the menus (their labels follow the locale) and has the feeds refetch. A failed notify still
    /// refreshes, as the engine may have applied part of it.
    fn apply_locale(&mut self, gl: Option<String>, window: Option<AnyWindowHandle>, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("settings"));
        let apply = on_core(cx, async move {
            let result = engine.apply_locale(gl.as_deref(), &log).await;
            if let Err(error) = &result {
                log.warn("applying the content locale failed", Some(error));
            }
            result
        });
        cx.spawn(async move |this, cx| {
            let result = apply.await;
            this.update(cx, |view, cx| {
                if let (Some(Err(error)), Some(window)) = (&result, window) {
                    let message = crate::feed::error_text(error);
                    window.update(cx, |_, window, cx| window.push_notification(message, cx)).ok();
                }
                view.load_locales(cx);
                cx.emit(LocaleChanged);
            })
            .ok();
        })
        .detach();
    }

    /// Debug: shows the last rows.
    pub fn scroll_to_end(&self) {
        self.scroll.scroll_to_bottom();
    }

    pub fn pick_region(&mut self, gl: String, window: &mut Window, cx: &mut Context<Self>) {
        if let Some(locales) = &mut self.locales {
            locales.gl = gl.clone();
        }
        cx.notify();
        self.apply_locale(Some(gl), Some(window.window_handle()), cx);
    }

    pub fn refresh_account(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("settings"));
        let load = on_core(cx, async move { engine.account_info(false, &log).await });
        cx.spawn(async move |this, cx| {
            if let Some(Ok(account)) = load.await {
                this.update(cx, |view, cx| {
                    view.account = Some(account);
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    fn put(&self, key: &'static str, value: String, cx: &mut Context<Self>) {
        save_setting(key, value, cx);
        cx.notify();
    }

    fn set_theme(&mut self, choice: &'static str, cx: &mut Context<Self>) {
        let accent = saved(cx.global::<AppGraph>().settings.as_ref(), ACCENT_KEY);
        apply_theme(choice, &accent, cx);
        self.put(THEME_KEY, choice.to_owned(), cx);
    }

    fn set_accent(&mut self, hex: String, cx: &mut Context<Self>) {
        let theme = saved_theme(cx.global::<AppGraph>().settings.as_ref());
        apply_theme(&theme, &hex, cx);
        self.put(ACCENT_KEY, hex, cx);
    }

    fn set_language(&mut self, tag: String, window: &mut Window, cx: &mut Context<Self>) {
        if tag.is_empty() {
            ym_i18n::use_system_locale();
        } else {
            ym_i18n::set_locale(&tag);
        }
        // Saved before the notify: the engine re-reads the language from settings.
        let graph = cx.global::<AppGraph>();
        let (settings, log) = (graph.settings.clone(), graph.log.fork("settings"));
        let write = graph.tokio.spawn_blocking(move || settings.put_raw(LANGUAGE_KEY, tag));
        let handle = window.window_handle();
        cx.spawn(async move |this, cx| {
            match write.await {
                Ok(Ok(())) => {}
                Ok(Err(error)) => log.warn("R93001 UI language not saved", Some(error.as_ref())),
                Err(error) => log.warn("R93001 UI language not saved", Some(&error)),
            }
            this.update(cx, |view, cx| view.apply_locale(None, Some(handle), cx)).ok();
        })
        .detach();
        cx.refresh_windows();
    }

    fn choose_folder(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let pick = crate::downloads::pick_download_folder(window, cx);
        cx.spawn(async move |this, cx| {
            if pick.await.is_some() {
                this.update(cx, |_, cx| cx.notify()).ok();
            }
        })
        .detach();
    }

    fn sign_out(&mut self, cx: &mut Context<Self>) {
        self.signing_out = true;
        let graph = cx.global::<AppGraph>();
        let (jar, engines, log) = (graph.cookies.clone(), graph.engines.clone(), graph.log.fork("signout"));
        let done = on_core(cx, async move { crate::signin::sign_out(jar, engines, &log).await });
        cx.spawn(async move |this, cx| {
            let result = done.await;
            this.update(cx, |view, cx| {
                view.signing_out = false;
                if let Some(Err(error)) = result {
                    cx.global::<AppGraph>().log.warn("sign-out failed", Some(&error));
                }
                view.refresh_account(cx);
                cx.emit(AccountChanged);
            })
            .ok();
        })
        .detach();
    }
}

/// A header inset 32 (card margin + row padding) over a rounded card of borderless rows.
fn section(label: &'static str, rows: Vec<AnyElement>, cx: &App) -> impl IntoElement {
    let theme = cx.theme();
    v_flex()
        .child(
            heading(label, 2, label).px_8().pt_6().pb_2().text_sm().font_semibold().text_color(theme.muted_foreground),
        )
        .child(v_flex().mx_4().rounded(px(12.)).bg(theme.secondary).overflow_hidden().children(rows))
}

fn row(title: impl Into<SharedString>, hint: Option<String>, control: impl IntoElement, cx: &App) -> AnyElement {
    row_body(title, hint, control, cx).into_any_element()
}

fn row_body(title: impl Into<SharedString>, hint: Option<String>, control: impl IntoElement, cx: &App) -> Div {
    let theme = cx.theme();
    h_flex()
        .gap_4()
        .px_4()
        .py(px(14.))
        .items_center()
        .child(
            v_flex().flex_1().min_w_0().child(div().text_sm().child(title.into())).when_some(hint, |column, hint| {
                column.child(div().pt(px(2.)).text_xs().text_color(theme.muted_foreground).child(hint))
            }),
        )
        .child(div().flex_shrink_0().child(control))
}

/// A row that is one button: its ring, click target and spoken name are the whole row, with `value`
/// (what it is set to, or where it leads) at its end.
fn button_row(
    id: &'static str,
    title: impl Into<SharedString>,
    hint: Option<String>,
    value: impl IntoElement,
    cx: &App,
) -> Button {
    let theme = cx.theme();
    Button::new(id).ghost().focus_ring(false).w_full().h_auto().px_4().py(px(14.)).rounded(px(0.)).child(
        h_flex()
            .w_full()
            .gap_4()
            .items_center()
            .child(
                v_flex().flex_1().min_w_0().items_start().child(div().text_sm().child(title.into())).when_some(
                    hint,
                    |column, hint| {
                        column.child(div().pt(px(2.)).text_xs().text_color(theme.muted_foreground).child(hint))
                    },
                ),
            )
            .child(div().flex_shrink_0().text_sm().child(value)),
    )
}

/// The end of a row that opens a panel or a page.
fn chevron(cx: &App) -> Icon {
    Icon::new(IconName::ChevronRight).text_color(cx.theme().muted_foreground)
}

/// A control's spoken name: the row's title, then the value it shows.
fn spoken(title: &str, value: &str) -> String {
    if value.is_empty() || value == title { title.to_owned() } else { format!("{title}, {value}") }
}

fn language_name(tag: &str) -> String {
    let base = tag.split(['-', '_']).next().unwrap_or(tag);
    let name = match base {
        "en" => "English",
        "vi" => "Tiếng Việt",
        "es" => "Español",
        "pt" => "Português",
        "fr" => "Français",
        "de" => "Deutsch",
        "it" => "Italiano",
        "ru" => "Русский",
        "uk" => "Українська",
        "pl" => "Polski",
        "tr" => "Türkçe",
        "ar" => "العربية",
        "fa" => "فارسی",
        "hi" => "हिन्दी",
        "id" => "Bahasa Indonesia",
        "ms" => "Bahasa Melayu",
        "th" => "ไทย",
        "ja" => "日本語",
        "ko" => "한국어",
        "zh" => "中文",
        "nl" => "Nederlands",
        "sv" => "Svenska",
        "cs" => "Čeština",
        "ro" => "Română",
        "hu" => "Magyar",
        "el" => "Ελληνικά",
        "he" => "עברית",
        "bn" => "বাংলা",
        _ => return tag.to_owned(),
    };
    if tag.len() > base.len() { format!("{name} ({})", &tag[base.len() + 1..]) } else { name.to_owned() }
}

impl SettingsView {
    fn link_row(&self, index: usize, id: &'static str, title: &'static str, url: String, cx: &App) -> AnyElement {
        let link = Icon::new(IconName::ExternalLink).text_color(cx.theme().muted_foreground);
        let button = button_row(id, title, None, link, cx)
            .accessibility_label(title)
            .tooltip(url.clone())
            .on_click(move |_, _, cx| cx.open_url(&url));
        self.wide(self.focus.row_button(index, button, cx))
    }

    fn wide(&self, stop: Div) -> AnyElement {
        stop.w_full().into_any_element()
    }

    fn toggle(
        &self,
        index: usize,
        key: &'static str,
        title: &'static str,
        hint: &'static str,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let checked = saved(cx.global::<AppGraph>().settings.as_ref(), key) != "false";
        self.toggle_with(index, key, title, hint, checked, cx)
    }

    /// A row that is the switch: the whole row is the stop and the click target.
    fn toggle_with(
        &self,
        index: usize,
        key: &'static str,
        title: &'static str,
        hint: &'static str,
        checked: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let body = row_body(
            title,
            Some(hint.to_owned()),
            Switch::new(key).checked(checked).tab_stop(false).accessibility_label(title),
            cx,
        )
        .id(SharedString::from(format!("{key}-row")))
        .role(Role::Switch)
        .aria_label(title)
        .aria_description(hint)
        .aria_toggled(if checked { Toggled::True } else { Toggled::False })
        .cursor_pointer()
        .on_click(cx.listener(move |view, _, _, cx| view.put(key, (!checked).to_string(), cx)));
        self.focus.item(index, body, cx).into_any_element()
    }
}

impl Render for SettingsView {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::shell::track_scroll(&self.scroll, cx);
        let graph = cx.global::<AppGraph>();
        let settings = graph.settings.clone();
        let theme_choice = saved_theme(settings.as_ref());
        let accent = saved(settings.as_ref(), ACCENT_KEY);
        let language = saved(settings.as_ref(), LANGUAGE_KEY);
        let show_fps = saved(settings.as_ref(), SHOW_FPS_KEY) == "true";
        let caption_look = CaptionLook::load(settings.as_ref());
        let folder: Option<PathBuf> = download_folder(settings.as_ref());
        let versions = format!(
            "{} · {} · {}",
            tf(Str::SettingsVersionsApp, &[&crate::graph::APP_VERSION_NAME, &crate::graph::APP_VERSION]),
            tf(Str::SettingsVersionsEngine, &[&graph.engines.backend_version()]),
            tf(Str::SettingsVersionsPlayer, &[&graph.player_source.embedded().1]),
        );
        let changelog_url = link(graph, "changelogUrl", CHANGELOG_URL);
        let source_url = link(graph, "sourceCodeUrl", SOURCE_CODE_URL);
        let terms_url = link(graph, "termsUrl", TERMS_URL);
        let can_update = graph.updates.is_some();
        let cache_saved = graph.player_cache.as_ref().map(|cache| cache.hit_bytes());
        let cache_max_mb = crate::player_cache::saved_max_mb(settings.as_ref());

        let stops = Cell::new(0);
        let next = || stops.replace(stops.get() + 1);
        let theme_label = t(match theme_choice.as_str() {
            "light" => Str::SettingsThemeLight,
            "dark" => Str::SettingsThemeDark,
            "amoled" => Str::SettingsThemeAmoled,
            _ => Str::SettingsThemeSystem,
        });
        let entity = cx.entity().downgrade();
        let theme_stop = next();
        let theme_row = button_row("theme", t(Str::SettingsTheme), None, theme_label, cx)
            .accessibility_label(spoken(t(Str::SettingsTheme), theme_label))
            .track_focus(&self.focus.handle(theme_stop, cx))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, _| {
                for (choice, label) in [
                    ("", Str::SettingsThemeSystem),
                    ("light", Str::SettingsThemeLight),
                    ("dark", Str::SettingsThemeDark),
                    ("amoled", Str::SettingsThemeAmoled),
                ] {
                    let view = entity.clone();
                    menu = menu.item(PopupMenuItem::new(t(label)).on_click(move |_, _, cx| {
                        view.update(cx, |view, cx| view.set_theme(choice, cx)).ok();
                    }));
                }
                menu
            });
        let theme_row = self.wide(self.focus.row(theme_stop, theme_row, cx));

        let entity = cx.entity().downgrade();
        let swatch = div().size(px(20.)).rounded_full().bg(accent_color(&accent));
        let accent_row = button_row("accent", t(Str::SettingsAccentColor), None, swatch, cx)
            .accessibility_label(t(Str::SettingsAccentColor))
            .on_click(move |_, window, cx| {
                let view = entity.clone();
                AccentPicker::open(
                    &accent,
                    move |hex, cx| {
                        view.update(cx, |view, cx| view.set_accent(hex, cx)).ok();
                    },
                    window,
                    cx,
                );
            });
        let accent_row = self.wide(self.focus.row_button(next(), accent_row, cx));

        let language_label =
            if language.is_empty() { t(Str::SettingsThemeSystem).to_owned() } else { language_name(&language) };
        let entity = cx.entity().downgrade();
        let language_stop = next();
        let language_row = button_row("language", t(Str::SettingsLanguage), None, language_label.clone(), cx)
            .accessibility_label(spoken(t(Str::SettingsLanguage), &language_label))
            .track_focus(&self.focus.handle(language_stop, cx))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, _| {
                menu = menu.scrollable(true).max_h(px(360.));
                let system = entity.clone();
                menu = menu.item(PopupMenuItem::new(t(Str::SettingsThemeSystem)).on_click(move |_, window, cx| {
                    system.update(cx, |view, cx| view.set_language(String::new(), window, cx)).ok();
                }));
                for tag in ym_i18n::available_locales() {
                    let view = entity.clone();
                    menu = menu.item(PopupMenuItem::new(language_name(tag)).on_click(move |_, window, cx| {
                        view.update(cx, |view, cx| view.set_language(tag.to_owned(), window, cx)).ok();
                    }));
                }
                menu
            });
        let language_row = self.wide(self.focus.row(language_stop, language_row, cx));

        let region_label = self
            .locales
            .as_ref()
            .map(|locales| {
                locales
                    .regions
                    .iter()
                    .find(|region| region.code.eq_ignore_ascii_case(&locales.gl))
                    .map(|region| region.label.clone())
                    .unwrap_or_else(|| locales.gl.clone())
            })
            .filter(|label| !label.is_empty())
            .unwrap_or_else(|| t(Str::SettingsContentRegion).to_owned());
        let region_row = button_row(
            "region",
            t(Str::SettingsContentRegion),
            Some(t(Str::SettingsContentLocaleHint).to_owned()),
            region_label.clone(),
            cx,
        )
        .accessibility_label(spoken(t(Str::SettingsContentRegion), &region_label))
        .busy(self.locales.is_none() && self.locales_loading)
        .on_click(cx.listener(|_, _, window, cx| RegionPicker::open(cx.entity(), window, cx)));
        let region_row = self.wide(self.focus.row_button(next(), region_row, cx));

        let appearance = vec![theme_row, accent_row, language_row];
        let content = vec![region_row];
        let speed_row =
            button_row("speed-pitch", t(Str::SoundSpeed), Some(t(Str::SettingsSpeedHint).to_owned()), chevron(cx), cx)
                .accessibility_label(t(Str::SoundSpeed))
                .on_click(cx.listener(|view, _, window, cx| {
                    crate::speed_pitch::SpeedPitchDialog::open(view.playback.clone(), window, cx)
                }));
        let equalizer_row =
            button_row("equalizer", t(Str::SoundEq), Some(t(Str::SettingsEqHint).to_owned()), chevron(cx), cx)
                .accessibility_label(t(Str::SoundEq))
                .on_click(cx.listener(|view, _, window, cx| {
                    crate::equalizer::EqualizerDialog::open(view.playback.clone(), window, cx)
                }));
        let caption_row = button_row(
            "caption-style",
            t(Str::SettingsCaptionStyle),
            Some(t(Str::SettingsCaptionStyleHint).to_owned()),
            crate::caption_style::sample(caption_look),
            cx,
        )
        .accessibility_label(t(Str::SettingsCaptionStyle))
        .on_click(|_, window, cx| CaptionStyleDialog::open(window, cx));
        let mut playback = vec![
            self.wide(self.focus.row_button(next(), speed_row, cx)),
            self.wide(self.focus.row_button(next(), equalizer_row, cx)),
            self.wide(self.focus.row_button(next(), caption_row, cx)),
            self.toggle(next(), SPONSOR_BLOCK_KEY, t(Str::SettingsSponsorBlock), t(Str::SettingsSponsorBlockHint), cx),
        ];
        if let Some(saved) = cache_saved {
            let summary = if cache_max_mb > 0 {
                tf(
                    Str::SettingsPlayerCacheSummary,
                    &[
                        &crate::player_cache::max_label(cache_max_mb),
                        &crate::player_cache::size_label(self.cache_used),
                        &crate::player_cache::size_label(saved),
                    ],
                )
            } else {
                t(Str::SettingsPlayerCacheOff).to_owned()
            };
            let entity = cx.entity().downgrade();
            let cache_stop = next();
            let cache_row = button_row(
                "player-cache",
                t(Str::SettingsPlayerCache),
                Some(summary.clone()),
                crate::player_cache::max_label(cache_max_mb),
                cx,
            )
            .accessibility_label(spoken(t(Str::SettingsPlayerCache), &summary))
            .track_focus(&self.focus.handle(cache_stop, cx))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, _| {
                for max_mb in crate::player_cache::SIZE_CHOICES_MB {
                    let view = entity.clone();
                    menu = menu.item(
                        PopupMenuItem::new(crate::player_cache::max_label(max_mb))
                            .checked(max_mb == cache_max_mb)
                            .on_click(move |_, _, cx| {
                                view.update(cx, |view, cx| view.set_cache_max(max_mb, cx)).ok();
                            }),
                    );
                }
                menu
            });
            playback.push(self.wide(self.focus.row(cache_stop, cache_row, cx)));
        }
        let folder = folder
            .map(|folder| folder.display().to_string())
            .unwrap_or_else(|| t(Str::DownloadLocationNotSet).to_owned());
        let folder_row =
            button_row("download-folder", t(Str::MenuDownloadLocation), Some(folder.clone()), chevron(cx), cx)
                .accessibility_label(spoken(t(Str::MenuDownloadLocation), &folder))
                .on_click(cx.listener(|view, _, window, cx| view.choose_folder(window, cx)));
        let quick_preset = crate::quick_download::saved_preset(cx).label();
        let quick_row = button_row("quick-download", t(Str::SettingsQuickDownload), None, quick_preset.clone(), cx)
            .accessibility_label(spoken(t(Str::SettingsQuickDownload), &quick_preset))
            .on_click(|_, window, cx| crate::quick_download::QuickPresetEditor::open(window, cx));
        let downloads = vec![
            self.wide(self.focus.row_button(next(), folder_row, cx)),
            self.wide(self.focus.row_button(next(), quick_row, cx)),
        ];

        let (signed_in, account_title, account_hint) = match &self.account {
            Some(account) if account.signed_in => (
                true,
                account.name.clone().or_else(|| account.email.clone()).unwrap_or_default(),
                account.email.clone().or_else(|| account.handle.clone()),
            ),
            _ => (false, t(Str::SigninPrefaceTitle).to_owned(), Some(t(Str::SigninPrefaceSubtitle).to_owned())),
        };
        let action = t(if signed_in { Str::AccountSignOut } else { Str::SigninButton });
        let theme = cx.theme();
        let action_label =
            div().font_semibold().text_color(if signed_in { theme.foreground } else { theme.primary }).child(action);
        let account_row = button_row(
            if signed_in { "sign-out" } else { "sign-in" },
            account_title.clone(),
            account_hint,
            action_label,
            cx,
        )
        .accessibility_label(spoken(action, &account_title));
        let account_row = if signed_in {
            account_row.busy(self.signing_out).on_click(cx.listener(|view, _, _, cx| view.sign_out(cx)))
        } else {
            account_row.on_click(|_, window, cx| window.dispatch_action(Box::new(crate::shortcuts::OpenSignIn), cx))
        };
        let account = vec![self.wide(self.focus.row_button(next(), account_row, cx))];
        let privacy = vec![
            self.toggle(next(), CRASH_REPORTS_KEY, t(Str::SettingsCrashReports), t(Str::SettingsCrashReportsHint), cx),
            self.toggle(next(), STATS_KEY, t(Str::SettingsStats), t(Str::SettingsStatsHint), cx),
        ];
        let mut about = vec![row(t(Str::SettingsVersions), Some(versions), div(), cx)];
        // Installing still waits for a tap.
        if can_update {
            about.push(self.toggle(
                next(),
                AUTO_UPDATE_KEY,
                t(Str::SettingsAutoUpdate),
                t(Str::SettingsAutoUpdateHint),
                cx,
            ));
        }
        about.extend([
            self.link_row(next(), "changelog", t(Str::SettingsChangelog), changelog_url, cx),
            self.link_row(next(), "source", t(Str::SettingsSourceCode), source_url, cx),
            self.link_row(next(), "terms", t(Str::SettingsTerms), terms_url, cx),
            self.wide(
                self.focus.row_button(
                    next(),
                    button_row("licenses", t(Str::SettingsLicenses), None, chevron(cx), cx)
                        .accessibility_label(t(Str::SettingsLicenses))
                        .on_click(|_, window, cx| Licenses::open(window, cx)),
                    cx,
                ),
            ),
        ]);
        let debug = vec![self.toggle_with(
            next(),
            SHOW_FPS_KEY,
            t(Str::SettingsShowFps),
            t(Str::SettingsShowFpsHint),
            show_fps,
            cx,
        )];
        let content = v_flex()
            .w_full()
            .pb_6()
            .child(section(t(Str::SettingsSectionAppearance), appearance, cx))
            .child(section(t(Str::SettingsSectionContent), content, cx))
            .child(section(t(Str::SettingsSectionPlayback), playback, cx))
            .child(section(t(Str::SettingsSectionDownloads), downloads, cx))
            .child(section(t(Str::SettingsSectionDevices), account, cx))
            .child(section(t(Str::SettingsSectionPrivacy), privacy, cx))
            .child(section(t(Str::SettingsSectionAbout), about, cx))
            .child(section(t(Str::SettingsDebug), debug, cx));
        let content = self.focus.container(content, 0..stops.get(), cx);

        div().id("settings-scroll").size_full().overflow_y_scroll().track_scroll(&self.scroll).child(content)
    }
}
