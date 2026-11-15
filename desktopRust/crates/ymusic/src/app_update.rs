//! The self-update UI (JVM `UpdateDialog.kt` / `DesktopAppUpdates.kt` / `UpgradeNotice.kt`) over
//! `ym_update`: the update prompt, "Check for update", install-and-restart, and the "updated" notice.
//!
//! The prompt shows the version, its brief notes, the full changelog, and what the user's own
//! download is doing. A background download stays invisible until it is ready; "Download" joins,
//! retries or starts it, and its progress and failure show once the user tapped it. "Ask later"
//! hides the release for this run; a download the user started brings it back once ready, and
//! "Check for update" asks again.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use futures::channel::mpsc;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::notification::Notification;
use gpui_kit::component::progress::Progress;
use gpui_kit::component::{ActiveTheme, Icon, IconName, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};
use ym_settings::{AppDb, SettingsStore};
use ym_update::{Blocked, Failure, UpdatePrefs, UpdateService, UpdateState};

use crate::a11y::{FocusList, focusable};
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, ROW_VERTICAL, button_bar, chrome, press, text_button, title_bar,
};
use crate::graph::{AppGraph, on_core};
use crate::spinner::Spinner;

type OnPress = Box<dyn Fn(&ClickEvent, &mut Window, &mut App)>;

/// The JVM app's keys.
const AUTO_DOWNLOAD_KEY: &str = "update.autoDownload";
const AUTO_DOWNLOAD_FAILED_KEY: &str = "update.autoDownloadFailed";
/// The versionCode of the last launch (0 = none recorded).
const LAST_RUN_VERSION_KEY: &str = "update.lastRunVersionCode";
/// How long "Check for update" waits for the config service.
const CHECK_TIMEOUT: Duration = Duration::from_secs(3);

/// The updater's settings in app.db, as the JVM app keeps them.
pub fn prefs(settings: Arc<dyn SettingsStore>, log: ym_common::log::Logger) -> UpdatePrefs {
    let (read, failed, write) = (settings.clone(), settings.clone(), settings);
    UpdatePrefs {
        auto_download: Box::new(move || read.boolean(AUTO_DOWNLOAD_KEY, true).unwrap_or(true)),
        auto_download_failed: Box::new(move || failed.string(AUTO_DOWNLOAD_FAILED_KEY, "").unwrap_or_default()),
        remember_auto_download_failed: Box::new(move |sha256| {
            if let Err(error) = write.put_raw(AUTO_DOWNLOAD_FAILED_KEY, sha256) {
                log.warn("R90006 setting update.autoDownloadFailed not saved", Some(error.as_ref()));
            }
        }),
    }
}

/// Debug: the throwaway data dir and dev hooks, carried over to the relaunched app (macOS `open`
/// starts it with a clean environment).
pub fn relaunch_env() -> Vec<String> {
    if !cfg!(debug_assertions) {
        return Vec::new();
    }
    std::env::vars()
        .filter(|(name, _)| name == "YMUSIC_DATA_DIR" || name.starts_with("YMUSIC_DEV_"))
        .map(|(name, value)| format!("{name}={value}"))
        .collect()
}

/// Whether this launch is the first of a newer version, decided once per process from the last
/// recorded launch: a fresh install (settings that started empty) is never "updated", while an
/// install that predates the record is.
pub struct UpgradeNotice {
    previous: i32,
    pending: bool,
}

impl Global for UpgradeNotice {}

impl UpgradeNotice {
    /// Before anything this launch writes a setting.
    pub fn read(settings: &AppDb) -> Self {
        let previous = settings.int(LAST_RUN_VERSION_KEY, 0).unwrap_or(0);
        Self { previous, pending: crate::graph::APP_VERSION > previous && settings.has_settings() }
    }
}

/// Records this launch's versionCode, and on the first launch after an update says "YMusic updated
/// to X" with View changelog. Once per process.
pub fn show_upgrade_notice(window: &mut Window, cx: &mut App) {
    if !cx.has_global::<UpgradeNotice>() {
        return;
    }
    let notice = cx.global_mut::<UpgradeNotice>();
    let (previous, pending) = (notice.previous, std::mem::take(&mut notice.pending));
    notice.previous = crate::graph::APP_VERSION;
    let graph = cx.global::<AppGraph>();
    if previous != crate::graph::APP_VERSION {
        let (settings, log) = (graph.settings.clone(), graph.log.fork("update"));
        graph.tokio.spawn_blocking(move || {
            if let Err(error) = settings.put_raw(LAST_RUN_VERSION_KEY, crate::graph::APP_VERSION.to_string()) {
                log.warn("R90306 setting update.lastRunVersionCode not saved", Some(error.as_ref()));
            }
        });
    }
    if !pending {
        return;
    }
    let changelog = crate::feedback_view::link(graph, "changelogUrl", crate::settings_view::CHANGELOG_URL);
    let notification = Notification::new()
        .message(tf(Str::UpgradedMessage, &[&crate::graph::APP_VERSION_NAME]))
        .action(move |_, _, cx| {
            let changelog = changelog.clone();
            Button::new("upgrade-changelog").primary().label(t(Str::UpgradedViewChangelog)).on_click(cx.listener(
                move |notification, _, window, cx| {
                    cx.open_url(&changelog);
                    notification.dismiss(window, cx);
                },
            ))
        })
        // `action` turns autohide off; a hover or keyboard focus on the toast still pauses it.
        .autohide(true);
    window.push_notification(notification, cx);
}

/// The update state the UI renders, with this run's "Ask later" and "Download" taps.
pub struct Updates {
    service: Arc<UpdateService>,
    state: UpdateState,
    /// The versionCode the user asked about later.
    dismissed: Option<i32>,
    /// The versionCode whose download the user started (its progress and failure show).
    requested: Option<i32>,
    /// "Check for update" found a release: a background download in flight prompts too.
    rechecked: bool,
    dialog: Option<WeakEntity<UpdateDialog>>,
    /// Debug: install as soon as the release is ready.
    install_when_ready: bool,
}

pub struct UpdatesHandle(pub Entity<Updates>);

impl Global for UpdatesHandle {}

impl Updates {
    fn apply(&mut self, state: UpdateState) {
        if matches!(state, UpdateState::Ready { .. })
            && state.update().is_some_and(|update| self.requested == Some(update.version_code))
        {
            self.dismissed = None;
        }
        self.state = state;
    }

    /// The versionCode the prompt should show now, if any.
    fn prompt(&self) -> Option<i32> {
        let code = self.state.update()?.version_code;
        if self.dismissed == Some(code) {
            return None;
        }
        let requested = self.requested == Some(code);
        if !requested && !self.rechecked && matches!(self.state, UpdateState::Downloading { .. }) {
            return None;
        }
        Some(code)
    }

    /// Whether the rail's "Check for update" is highlighted: a release is waiting.
    pub fn waiting(&self) -> bool {
        self.state.update().is_some()
    }
}

/// Starts following the updater; nothing at all without one (no update key in this build).
pub fn start(window: &mut Window, cx: &mut App) {
    let Some(service) = cx.global::<AppGraph>().updates.clone() else { return };
    let install_when_ready =
        cfg!(debug_assertions) && std::env::var("YMUSIC_DEV_ROUTE").is_ok_and(|route| route == "installupdate");
    let entity = cx.new(|_| Updates {
        state: service.state(),
        service: service.clone(),
        dismissed: None,
        requested: None,
        rechecked: false,
        dialog: None,
        install_when_ready,
    });
    cx.set_global(UpdatesHandle(entity.clone()));
    let (sender, mut receiver) = mpsc::unbounded::<UpdateState>();
    let mut states = service.subscribe();
    let forward = on_core(cx, async move {
        while states.changed().await.is_ok() {
            let state = states.borrow_and_update().clone();
            if sender.unbounded_send(state).is_err() {
                return;
            }
        }
    });
    cx.background_executor().spawn(forward).detach();
    let (weak, window_handle) = (entity.downgrade(), window.window_handle());
    cx.spawn(async move |cx| {
        while let Some(state) = receiver.next().await {
            let install = weak.update(cx, |updates, cx| {
                updates.apply(state);
                cx.notify();
                updates.install_when_ready && matches!(updates.state, UpdateState::Ready { .. })
            });
            match install {
                Ok(true) => cx.update(install_and_restart),
                Ok(false) => {}
                Err(_) => return,
            }
            let _ = cx.update_window(window_handle, |_, window, cx| sync_prompt(window, cx));
        }
    })
    .detach();
    window.defer(cx, sync_prompt);
}

/// Opens the prompt when the state asks for it and no other dialog is up; closes it when the
/// release is gone or dismissed.
pub fn sync_prompt(window: &mut Window, cx: &mut App) {
    let Some(updates) = cx.try_global::<UpdatesHandle>().map(|handle| handle.0.clone()) else { return };
    let (show, open) = {
        let updates = updates.read(cx);
        (updates.prompt().is_some(), updates.dialog.as_ref().is_some_and(|dialog| dialog.upgrade().is_some()))
    };
    if show && !open && !window.has_active_dialog(cx) {
        UpdateDialog::open(updates, window, cx);
    } else if !show && open {
        window.close_dialog(cx);
    }
}

/// Runs the ready installer and quits for the new version (two copies would fight over the data
/// dir, and the Windows setup waits for this process to exit). Hand-offs stop before the new version
/// starts: it must wait for this process to exit, not hand its launch to it.
fn install_and_restart(cx: &mut App) {
    let Some(service) = cx.global::<AppGraph>().updates.clone() else { return };
    let started = on_core(cx, async move { service.install(crate::instance::stop_accepting).await });
    cx.spawn(async move |cx| {
        if started.await == Some(true) {
            cx.update(|cx| cx.quit());
        }
    })
    .detach();
}

/// The official download site the config names, for an entry that cannot install in place.
fn download_site(cx: &App) -> Option<String> {
    let config = cx.global::<AppGraph>().remote.current();
    config.feature("downloadPageUrl").and_then(serde_json::Value::as_str).map(str::to_owned)
}

fn blocked_reason(blocked: Blocked) -> &'static str {
    t(match blocked {
        Blocked::NotInstalled => Str::UpdateBlockedNotInstalled,
        Blocked::FolderReadOnly => Str::UpdateBlockedFolderReadOnly,
        Blocked::NotDeb => Str::UpdateBlockedNotDeb,
        Blocked::NoPkexec => Str::UpdateBlockedNoPkexec,
    })
}

/// A failure the user can act on as its own message, any other as the code to report.
fn failure_text(failure: &Failure) -> String {
    match failure {
        Failure::Network => t(Str::ErrorNetwork).to_owned(),
        Failure::DiskFull => t(Str::ErrorDiskFull).to_owned(),
        Failure::Code(code) => tf(Str::UpdateFailed, &[code]),
    }
}

fn format_bytes(bytes: u64) -> String {
    match bytes {
        bytes if bytes >= 1024 * 1024 => format!("{} MB", bytes / (1024 * 1024)),
        bytes if bytes >= 1024 => format!("{} KB", bytes / 1024),
        bytes => format!("{bytes} B"),
    }
}

/// "Check for update" under the rail / drawer logo: accent and a trailing dot while a release waits.
pub fn check_link(id: &'static str, cx: &App) -> Option<AnyElement> {
    let updates = cx.try_global::<UpdatesHandle>()?.0.clone();
    let theme = cx.theme();
    let waiting = updates.read(cx).waiting();
    let color = if waiting { theme.primary } else { theme.muted_foreground };
    let link = focusable(div().id(id).role(Role::Link).aria_label(t(Str::UpdateCheck)), cx)
        .p_1()
        .flex()
        .items_center()
        .gap_1()
        .text_xs()
        .text_color(color)
        .cursor_pointer()
        .child(t(Str::UpdateCheck))
        .when(waiting, |link| link.child(div().size(px(6.)).rounded_full().bg(color)))
        .on_click(|_, window, cx| CheckUpdateDialog::open(window, cx));
    Some(link.into_any_element())
}

pub struct UpdateDialog {
    updates: Entity<Updates>,
    focus: FocusList,
    focused_first: bool,
    _observe: Subscription,
}

impl UpdateDialog {
    fn open(updates: Entity<Updates>, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| Self {
            _observe: cx.observe(&updates, |_, _, cx| cx.notify()),
            updates: updates.clone(),
            focus: FocusList::modal(None, cx),
            focused_first: false,
        });
        updates.update(cx, |updates, _| updates.dialog = Some(view.downgrade()));
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, DEFAULT_WIDTH, window).overlay_closable(false).keyboard(false).child(view.clone())
        });
    }

    fn ask_later(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.updates.update(cx, |updates, cx| {
            updates.dismissed = updates.state.update().map(|update| update.version_code);
            cx.notify();
        });
        window.close_dialog(cx);
    }
}

impl Render for UpdateDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let (state, service, requested_code) = {
            let updates = self.updates.read(cx);
            (updates.state.clone(), updates.service.clone(), updates.requested)
        };
        let Some(update) = state.update().cloned() else { return div().id("update-dialog") };
        let requested = requested_code == Some(update.version_code);
        let trusted = !matches!(state, UpdateState::Failed { trusted: false, .. });
        let blocked = service.blocked();
        let in_place = blocked.is_none() && trusted && update.install_in_app;
        // An untrusted entry's own links are never followed: only the official site is offered.
        let download_page = if trusted {
            update.download_page.clone().or_else(|| download_site(cx)).or_else(|| Some(update.url.clone()))
        } else {
            download_site(cx)
        };
        let changelog = update.changelog_url.clone().filter(|_| trusted);

        let mut stops = 0;
        let mut next_stop = || {
            stops += 1;
            stops - 1
        };
        let link = |id: &'static str, label: &'static str, url: String, index: usize, cx: &App| {
            self.focus.button(
                index,
                Button::new(id).small().link().label(label).on_click(move |_, _, cx| cx.open_url(&url)),
                cx,
            )
        };

        let mut body = v_flex().w_full().px(px(ROW_HORIZONTAL)).py(px(ROW_VERTICAL)).gap_2();
        body = body.child(
            h_flex()
                .gap_2()
                .items_center()
                .child(div().text_sm().font_semibold().child(tf(Str::UpdateVersion, &[&update.version_name])))
                .when(in_place && matches!(state, UpdateState::Ready { .. }), |row| {
                    row.child(div().text_sm().text_color(theme.primary).child(t(Str::UpdateReady)))
                }),
        );
        if !update.notes.trim().is_empty() {
            body = body
                .child(div().text_sm().text_color(theme.muted_foreground).line_clamp(7).child(update.notes.clone()));
        }
        if let Some(url) = changelog {
            body =
                body.child(h_flex().child(link("update-changelog", t(Str::UpdateFullChangelog), url, next_stop(), cx)));
        }
        if let (true, UpdateState::Downloading { done, total, .. }) = (requested, &state) {
            let fraction = if *total > 0 { *done as f32 / *total as f32 } else { 0. };
            body = body
                .child(
                    div()
                        .text_sm()
                        .text_color(theme.muted_foreground)
                        .child(tf(Str::UpdateDownloading, &[&format_bytes(*done), &format_bytes(*total)])),
                )
                .child(Progress::new("update-progress").value(fraction * 100.));
        }
        if matches!(state, UpdateState::Installing(_)) {
            body = body.child(
                h_flex()
                    .gap_2()
                    .items_center()
                    .child(Spinner::new().small())
                    .child(div().text_sm().text_color(theme.muted_foreground).child(t(Str::UpdateInstalling))),
            );
        }
        let failure = match &state {
            UpdateState::Failed { failure, trusted: true, .. } if requested => Some(failure),
            UpdateState::InstallFailed { failure, .. } => Some(failure),
            _ => None,
        };
        if let Some(failure) = failure {
            body = body.child(div().text_sm().text_color(theme.muted_foreground).child(failure_text(failure)));
            if let Some(url) = download_page.clone() {
                body =
                    body.child(h_flex().child(link("update-site", t(Str::UpdateOfficialSite), url, next_stop(), cx)));
            }
        }
        if let Some(blocked) = blocked {
            body = body.child(
                div()
                    .text_xs()
                    .text_color(theme.muted_foreground)
                    .child(tf(Str::UpdateManualHint, &[&blocked_reason(blocked)])),
            );
        }

        let later_stop = next_stop();
        let later = press(
            &self.focus,
            later_stop,
            text_button("update-later", t(Str::UpdateAskLater), false, true, cx),
            true,
            cx.listener(|view, _, window, cx| view.ask_later(window, cx)),
            cx,
        );
        let primary_stop = next_stop();
        let code = update.version_code;
        let idle: OnPress = Box::new(|_, _, _| {});
        let (label, enabled, on_press): (&'static str, bool, OnPress) = if !in_place {
            let page = download_page.clone();
            let enabled = page.is_some();
            let open: OnPress = Box::new(move |_, _, cx| {
                if let Some(url) = &page {
                    cx.open_url(url);
                }
            });
            (t(Str::UpdateDownload), enabled, open)
        } else {
            match &state {
                UpdateState::Ready { .. } | UpdateState::InstallFailed { .. } => {
                    (t(Str::UpdateInstallNow), true, Box::new(|_, _, cx| install_and_restart(cx)))
                }
                UpdateState::Installing(_) => (t(Str::UpdateInstallNow), false, idle),
                UpdateState::Downloading { .. } if requested => (t(Str::UpdateInstallNow), false, idle),
                _ => {
                    let updates = self.updates.clone();
                    let download: OnPress = Box::new(move |_, _, cx| {
                        updates.update(cx, |updates, cx| {
                            updates.requested = Some(code);
                            updates.service.download();
                            cx.notify();
                        });
                    });
                    (t(Str::UpdateDownload), true, download)
                }
            }
        };
        let primary = press(
            &self.focus,
            primary_stop,
            text_button("update-primary", label, true, enabled, cx),
            enabled,
            on_press,
            cx,
        );
        if !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(primary_stop, cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let footer = h_flex().child(later).child(primary);
        // Esc acts as "Ask later"; a click outside does nothing, only the buttons close it.
        let escape = cx.listener(|view, event: &KeyDownEvent, window, cx| {
            if event.keystroke.key == "escape" {
                cx.stop_propagation();
                view.ask_later(window, cx);
            }
        });
        self.focus.container(div().id("update-dialog").w_full().on_key_down(escape), 0..stops, cx).child(
            v_flex()
                .w_full()
                .child(title_bar(t(Str::UpdateAvailableTitle), cx))
                .child(body)
                .child(button_bar(footer, cx)),
        )
    }
}

enum CheckResult {
    UpToDate,
    Failed,
}

/// "Check for update": checking, then up to date or failed. A found update closes this and the
/// update prompt takes over.
pub struct CheckUpdateDialog {
    result: Option<CheckResult>,
    focus: FocusList,
    focused_first: bool,
}

impl CheckUpdateDialog {
    pub fn open(window: &mut Window, cx: &mut App) {
        if window.has_active_dialog(cx) {
            return;
        }
        let graph = cx.global::<AppGraph>();
        let (Some(service), remote) = (graph.updates.clone(), graph.remote.clone()) else { return };
        let view = cx.new(|cx| Self { result: None, focus: FocusList::modal(None, cx), focused_first: false });
        window.open_dialog(cx, {
            let view = view.clone();
            move |dialog, window, _| chrome(dialog, DEFAULT_WIDTH, window).child(view.clone())
        });
        // The fetch outlives a timed-out wait: a late config still lands through the subscription.
        let fetch = on_core(cx, async move {
            let fetched = tokio::spawn(async move { remote.fetch_now().await });
            tokio::time::timeout(CHECK_TIMEOUT, fetched).await.ok().and_then(Result::ok).flatten()
        });
        let weak = view.downgrade();
        let window_handle = window.window_handle();
        cx.spawn(async move |cx| {
            let config = fetch.await.flatten();
            let found = config.map(|config| {
                service.accept(config.app_update);
                service.has_offer()
            });
            if found == Some(true) {
                let _ = cx.update_window(window_handle, |_, window, cx| {
                    if weak.upgrade().is_some() {
                        window.close_dialog(cx);
                    }
                    if let Some(updates) = cx.try_global::<UpdatesHandle>().map(|handle| handle.0.clone()) {
                        updates.update(cx, |updates, cx| {
                            updates.dismissed = None;
                            updates.rechecked = true;
                            cx.notify();
                        });
                    }
                    sync_prompt(window, cx);
                });
                return;
            }
            let result = if found.is_some() { CheckResult::UpToDate } else { CheckResult::Failed };
            let _ = weak.update(cx, |view, cx| {
                view.result = Some(result);
                cx.notify();
            });
        })
        .detach();
    }
}

impl Render for CheckUpdateDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(0, cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let theme = cx.theme().clone();
        let line = match self.result {
            None => h_flex()
                .gap_4()
                .items_center()
                .child(Spinner::new().small())
                .child(div().text_size(px(14.)).child(t(Str::UpdateChecking))),
            Some(CheckResult::UpToDate) => h_flex()
                .gap_4()
                .items_center()
                .child(Icon::new(IconName::CircleCheck).text_color(theme.success))
                .child(div().text_size(px(14.)).child(t(Str::UpdateUpToDate))),
            Some(CheckResult::Failed) => h_flex()
                .child(div().text_size(px(14.)).text_color(theme.muted_foreground).child(t(Str::UpdateCheckFailed))),
        };
        let close = press(
            &self.focus,
            0,
            text_button("check-update-close", t(Str::UpdateClose), false, true, cx),
            true,
            |_, window, cx| window.close_dialog(cx),
            cx,
        );
        self.focus
            .container(v_flex().w_full(), 0..1, cx)
            .child(title_bar(t(Str::UpdateCheck), cx))
            .child(div().id("check-update").role(Role::Status).w_full().px(px(ROW_HORIZONTAL)).py(px(20.)).child(line))
            .child(button_bar(close, cx))
    }
}
