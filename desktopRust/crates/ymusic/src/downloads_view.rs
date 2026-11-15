//! The download manager: every task with its state and progress, filtered by state, with per-task
//! and whole-queue controls. The list is the service's snapshot, kept current from its changes.

use std::path::{Path, PathBuf};

use futures::StreamExt;
use futures::channel::mpsc;
use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::progress::Progress;
use gpui_kit::component::{ActiveTheme, Icon, IconName, Selectable, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_download::{DownloadChange, DownloadService, DownloadState, DownloadTask, TaskError};
use ym_i18n::{Str, t, tf};

use crate::a11y::{DragRow, FocusList, announced, announced_as, heading, list_item};
use crate::action_icon::action_icon;
use crate::download_dialog::{DownloadPicker, DownloadTarget};
use crate::downloads::pick_download_folder;
use crate::file_rename::RenameFileDialog;
use crate::graph::{AppGraph, on_core};
use crate::library::PlayLocal;
use crate::library_playlist::AddToLocalPlaylist;
use crate::playback::{QueueItem, Source};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Filter {
    All,
    Running,
    Waiting,
    Paused,
    Completed,
    Error,
}

impl Filter {
    const ALL: [Filter; 6] =
        [Filter::All, Filter::Running, Filter::Waiting, Filter::Paused, Filter::Completed, Filter::Error];

    fn label(self) -> &'static str {
        t(match self {
            Filter::All => Str::DownloadsFilterAll,
            Filter::Running => Str::DownloadsFilterRunning,
            Filter::Waiting => Str::DownloadsFilterWaiting,
            Filter::Paused => Str::DownloadsFilterPaused,
            Filter::Completed => Str::DownloadsFilterCompleted,
            Filter::Error => Str::DownloadsFilterError,
        })
    }

    fn empty(self) -> &'static str {
        t(match self {
            Filter::All => Str::DownloadsEmptyAll,
            Filter::Running => Str::DownloadsEmptyRunning,
            Filter::Waiting => Str::DownloadsEmptyWaiting,
            Filter::Paused => Str::DownloadsEmptyPaused,
            Filter::Completed => Str::DownloadsEmptyCompleted,
            Filter::Error => Str::DownloadsEmptyError,
        })
    }

    fn matches(self, state: DownloadState) -> bool {
        match self {
            Filter::All => state != DownloadState::Canceled,
            Filter::Running => {
                matches!(state, DownloadState::Preparing | DownloadState::Downloading | DownloadState::Processing)
            }
            Filter::Waiting => state == DownloadState::Queued,
            Filter::Paused => state == DownloadState::Paused,
            Filter::Completed => state == DownloadState::Completed,
            Filter::Error => state == DownloadState::Failed,
        }
    }
}

pub struct DownloadsView {
    service: DownloadService,
    tasks: Vec<DownloadTask>,
    filter: Filter,
    scroll: ScrollHandle,
    focus: FocusList,
    /// The filter chips, then the empty filter's message.
    chip_focus: FocusList,
    fab_focus: FocusList,
    /// The no-downloads page's title and message.
    chip_drag: DragRow,
    /// Sign-in-required failures to re-run once the account signs in.
    retry_after_sign_in: Vec<String>,
    _changes: Task<()>,
}

/// Open the Library at the folder holding a finished download.
pub struct RevealInLibrary {
    pub file: PathBuf,
}

impl EventEmitter<PlayLocal> for DownloadsView {}
impl EventEmitter<RevealInLibrary> for DownloadsView {}

impl DownloadsView {
    pub fn new(service: DownloadService, cx: &mut Context<Self>) -> Self {
        let (sender, mut receiver) = mpsc::unbounded::<DownloadChange>();
        let mut changes = service.subscribe();
        let forward = on_core(cx, async move {
            loop {
                match changes.recv().await {
                    Ok(change) => {
                        if sender.unbounded_send(change).is_err() {
                            return;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
                }
            }
        });
        cx.background_executor().spawn(forward).detach();
        let changes = cx.spawn(async move |this, cx| {
            while let Some(change) = receiver.next().await {
                if this.update(cx, |view, cx| view.apply(change, cx)).is_err() {
                    return;
                }
            }
        });
        let scroll = ScrollHandle::new();
        let mut view = Self {
            service,
            tasks: Vec::new(),
            filter: Filter::All,
            scroll: scroll.clone(),
            focus: FocusList::new(Some(scroll.clone())),
            chip_focus: FocusList::new(Some(scroll)),
            fab_focus: FocusList::new(None),
            chip_drag: DragRow::default(),
            retry_after_sign_in: Vec::new(),
            _changes: changes,
        };
        view.refresh(cx);
        view
    }

    pub fn refresh(&mut self, cx: &mut Context<Self>) {
        let service = self.service.clone();
        let load = on_core(cx, async move { service.snapshot().await });
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(mut tasks)) => {
                        tasks.reverse();
                        view.tasks = tasks;
                    }
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.warn("the download list could not be read", Some(&error));
                    }
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn apply(&mut self, change: DownloadChange, cx: &mut Context<Self>) {
        let task = |tasks: &mut Vec<DownloadTask>, id: &str| tasks.iter_mut().position(|task| task.id == id);
        match change {
            DownloadChange::Added(added) => self.tasks.insert(0, *added),
            DownloadChange::Removed { id } => self.tasks.retain(|task| task.id != id),
            DownloadChange::StateChanged { id, state, error } => {
                if let Some(index) = task(&mut self.tasks, &id) {
                    self.tasks[index].state = state;
                    self.tasks[index].error = error;
                }
            }
            DownloadChange::ProgressChanged { id, done, total } => {
                if let Some(index) = task(&mut self.tasks, &id) {
                    self.tasks[index].downloaded_bytes = done;
                    self.tasks[index].total_bytes = total;
                }
            }
            DownloadChange::FormatChanged { id, format } => {
                if let Some(index) = task(&mut self.tasks, &id) {
                    self.tasks[index].format = format;
                }
            }
            DownloadChange::Completed { id, file_uri, bytes } => {
                if let Some(index) = task(&mut self.tasks, &id) {
                    let task = &mut self.tasks[index];
                    task.state = DownloadState::Completed;
                    task.file_uri = Some(file_uri);
                    task.downloaded_bytes = bytes;
                    task.total_bytes = bytes;
                    task.error = None;
                }
            }
        }
        cx.notify();
    }

    /// A Retry that a playability verdict answers first: signed out on a sign-in verdict, the
    /// sign-in dialog (the task re-runs once signed in); an unavailable verdict offers removal; a
    /// lost folder re-picks it.
    fn retry(&mut self, task: &DownloadTask, window: &mut Window, cx: &mut Context<Self>) {
        let code = task.error.as_ref().map(|error| error.code.clone()).unwrap_or_default();
        let id = task.id.clone();
        match playability(&code) {
            Some(Playability::SignInRequired) => {
                let graph = cx.global::<AppGraph>();
                let (engine, log) = (graph.engine(), graph.log.fork("downloads"));
                let account = on_core(cx, async move { engine.account_info(false, &log).await });
                cx.spawn_in(window, async move |this, cx| {
                    let signed_in = matches!(account.await, Some(Ok(account)) if account.signed_in);
                    this.update_in(cx, |view, window, cx| {
                        if signed_in {
                            view.service.retry(&id);
                        } else {
                            view.sign_in_dialog(id, window, cx);
                        }
                    })
                    .ok();
                })
                .detach();
            }
            Some(Playability::Unavailable) => self.unavailable_dialog(id, window, cx),
            None if code == "folder" => {
                let pick = pick_download_folder(window, cx);
                cx.spawn(async move |this, cx| {
                    if pick.await.is_some() {
                        this.update(cx, |view, _| view.service.retry(&id)).ok();
                    }
                })
                .detach();
            }
            _ => self.service.retry(&id),
        }
    }

    fn sign_in_dialog(&mut self, id: String, window: &mut Window, cx: &mut Context<Self>) {
        let view = cx.entity().downgrade();
        retry_dialog(
            t(Str::DownloadsSignInTitle),
            t(Str::DownloadsSignInMessage),
            t(Str::SigninButton),
            window,
            cx,
            move |window, cx| {
                view.update(cx, |view, _| view.retry_after_sign_in.push(id.clone())).ok();
                window.dispatch_action(Box::new(crate::shortcuts::OpenSignIn), cx);
            },
        );
    }

    fn unavailable_dialog(&mut self, id: String, window: &mut Window, cx: &mut Context<Self>) {
        let service = self.service.clone();
        retry_dialog(
            t(Str::DownloadsUnavailableTitle),
            t(Str::DownloadsUnavailableMessage),
            t(Str::MenuRemove),
            window,
            cx,
            move |_, _| service.remove(&id),
        );
    }

    /// Debug routes: `retry`, `reveal`, `format`, `addto` or `rename` the newest task of `video_id`.
    pub fn dev_action(&mut self, action: &str, video_id: &str, window: &mut Window, cx: &mut Context<Self>) -> bool {
        let Some(task) = self.tasks.iter().find(|task| task.video_id == video_id).cloned() else { return false };
        match action {
            "retry" => self.retry(&task, window, cx),
            "reveal" => {
                if let Some(file) = file_path(&task) {
                    cx.emit(RevealInLibrary { file });
                }
            }
            "format" => Self::other_format(&task, window, cx),
            "addto" => AddToLocalPlaylist::open(vec![task.video_id.clone()], None, window, cx),
            "removedisk" => self.remove_from_disk(task, window, cx),
            "rename" => {
                if let Some(file) = file_path(&task) {
                    RenameFileDialog::open(file, None, window, cx);
                }
            }
            _ => {}
        }
        true
    }

    /// The account signed in: the downloads that were waiting on it run again.
    pub fn signed_in(&mut self) {
        for id in std::mem::take(&mut self.retry_after_sign_in) {
            self.service.retry(&id);
        }
    }

    fn other_format(task: &DownloadTask, window: &mut Window, cx: &mut App) {
        DownloadPicker::open(
            DownloadTarget {
                video_id: task.video_id.clone(),
                title: task.title.clone(),
                artist: task.artist.clone(),
                thumbnail_url: task.thumbnail_url.clone(),
            },
            window,
            cx,
        );
    }

    fn play(&mut self, task: &DownloadTask, cx: &mut Context<Self>) {
        let Some(path) = file_path(task) else { return };
        let media = cx.global::<AppGraph>().media.clone();
        let lookup_path = path.to_string_lossy().into_owned();
        let lookup =
            on_core(cx, async move { tokio::task::spawn_blocking(move || media.find_item_by_uri(&lookup_path)).await });
        let fallback = QueueItem {
            source: Source::Local(path),
            title: task.title.clone(),
            artist: task.artist.clone(),
            duration_ms: None,
            duration_label: None,
            thumbs: Vec::new(),
            history_code: None,
            youtube_vid: Some(task.video_id.clone()),
            loudness_gain_db: task.loudness_gain_db,
        };
        cx.spawn(async move |this, cx| {
            let item = match lookup.await {
                Some(Ok(Ok(Some((item, _))))) => QueueItem::from_local(&item),
                _ => fallback,
            };
            this.update(cx, |_, cx| cx.emit(PlayLocal { items: vec![item], index: 0 })).ok();
        })
        .detach();
    }
}

/// A `file://` URI, or the bare path older JVM app versions stored.
fn file_path(task: &DownloadTask) -> Option<PathBuf> {
    let stored = task.file_uri.as_deref()?;
    if Path::new(stored).is_absolute() {
        return Some(PathBuf::from(stored));
    }
    url::Url::parse(stored).ok()?.to_file_path().ok()
}

pub(crate) enum Playability {
    SignInRequired,
    Unavailable,
}

/// A YouTube playabilityStatus verdict in a failure code: all caps, unlike site codes and categories.
/// ERROR is transient, so a plain re-run.
pub(crate) fn playability(code: &str) -> Option<Playability> {
    match code {
        "LOGIN_REQUIRED" | "AGE_CHECK_REQUIRED" | "AGE_VERIFICATION_REQUIRED" => Some(Playability::SignInRequired),
        "ERROR" => None,
        _ if !code.is_empty() && code.chars().all(|c| c.is_ascii_uppercase() || c == '_') => {
            Some(Playability::Unavailable)
        }
        _ => None,
    }
}

fn retry_dialog(
    title: &'static str,
    message: &'static str,
    action: &'static str,
    window: &mut Window,
    cx: &mut App,
    on_action: impl Fn(&mut Window, &mut App) + 'static,
) {
    crate::elevated_dialog::ConfirmDialog::new(title, message, action).open(window, cx, on_action);
}

/// "Jul 31, 2026", in the local time zone.
pub fn short_date(epoch_ms: i64) -> Option<String> {
    use chrono::{Datelike, TimeZone};
    const MONTHS: [Str; 12] = [
        Str::MonthAbbr0,
        Str::MonthAbbr1,
        Str::MonthAbbr2,
        Str::MonthAbbr3,
        Str::MonthAbbr4,
        Str::MonthAbbr5,
        Str::MonthAbbr6,
        Str::MonthAbbr7,
        Str::MonthAbbr8,
        Str::MonthAbbr9,
        Str::MonthAbbr10,
        Str::MonthAbbr11,
    ];
    let date = chrono::Local.timestamp_millis_opt(epoch_ms).single()?;
    Some(tf(Str::DownloadsCompletedDate, &[&t(MONTHS[date.month0() as usize]), &date.day(), &date.year()]))
}

pub fn size_label(bytes: i64) -> String {
    let bytes = bytes.max(0) as f64;
    if bytes >= 1e9 {
        tf(Str::DownloadSizeGb, &[&format!("{:.1}", bytes / 1e9)])
    } else if bytes >= 1e6 {
        tf(Str::DownloadSizeMb, &[&format!("{:.1}", bytes / 1e6)])
    } else {
        tf(Str::DownloadSizeKb, &[&format!("{:.0}", bytes / 1e3)])
    }
}

/// A failure as the user reads it: a category they can act on, else the code.
pub fn task_error_text(error: &TaskError) -> String {
    match playability(&error.code) {
        Some(Playability::SignInRequired) => return t(Str::PlaybackSignInRequired).to_owned(),
        Some(Playability::Unavailable) => return t(Str::PlaybackUnavailable).to_owned(),
        None if error.code == "ERROR" => return t(Str::PlaybackRetryLater).to_owned(),
        None => {}
    }
    if let Some(message) = &error.user_message {
        return format!("{message} ({})", error.code);
    }
    match error.code.as_str() {
        "network" => t(Str::ErrorNetwork).to_owned(),
        "disk_full" => t(Str::ErrorDiskFull).to_owned(),
        "folder" => t(Str::DownloadsErrorFolderAccess).to_owned(),
        "live" => t(Str::DownloadLiveUnsupported).to_owned(),
        "processing" => t(Str::ErrorLiveStillProcessing).to_owned(),
        code => tf(Str::ErrorGeneric, &[&code]),
    }
}

fn state_label(state: DownloadState) -> &'static str {
    t(match state {
        DownloadState::Queued => Str::DownloadsStateQueued,
        DownloadState::Preparing => Str::DownloadsStatePreparing,
        DownloadState::Downloading => Str::DownloadsStateDownloading,
        DownloadState::Processing => Str::DownloadsStateProcessing,
        DownloadState::Completed => Str::DownloadsStateDone,
        DownloadState::Paused => Str::DownloadsFilterPaused,
        DownloadState::Failed => Str::DownloadsStateFailed,
        DownloadState::Canceled => Str::DownloadsStateCanceled,
    })
}

const CARD_MIN: f32 = 340.;
const THUMB: f32 = 56.;

impl DownloadsView {
    /// Deletes the finished file and its Library row, then the task; a failed delete keeps the task
    /// and says so.
    fn remove_from_disk(&mut self, task: DownloadTask, window: &mut Window, cx: &mut Context<Self>) {
        let Some(path) = file_path(&task) else {
            self.service.remove(&task.id);
            return;
        };
        let graph = cx.global::<AppGraph>();
        let (media, log) = (graph.media.clone(), graph.log.fork("downloads"));
        let delete = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                let removed = std::fs::remove_file(&path);
                if removed.as_ref().is_ok()
                    || removed.as_ref().is_err_and(|error| error.kind() == std::io::ErrorKind::NotFound)
                {
                    let row = media.find_item_by_uri(&path.to_string_lossy());
                    if let Err(error) = row.and_then(|row| row.map_or(Ok(()), |(item, _)| media.delete_item(item.id))) {
                        log.warn("the deleted download's Library row could not be removed", Some(&error));
                    }
                }
                removed
            })
            .await
        });
        cx.spawn_in(window, async move |this, cx| {
            let result = delete.await;
            this.update_in(cx, |view, window, cx| match result {
                Some(Ok(Ok(()))) => view.service.remove(&task.id),
                Some(Ok(Err(error))) if error.kind() == std::io::ErrorKind::NotFound => view.service.remove(&task.id),
                Some(Ok(Err(error))) => {
                    cx.global::<AppGraph>().log.warn("R90022 downloaded file not deleted", Some(&error));
                    window.push_notification(tf(Str::ErrorGeneric, &[&"R90022"]), cx);
                }
                Some(Err(error)) => {
                    cx.global::<AppGraph>().log.warn("R90022 downloaded file not deleted", Some(&error));
                    window.push_notification(tf(Str::ErrorGeneric, &[&"R90022"]), cx);
                }
                None => {}
            })
            .ok();
        })
        .detach();
    }

    fn menu(&self, row: usize, task: &DownloadTask, cx: &mut Context<Self>) -> impl IntoElement {
        let view = cx.entity().downgrade();
        let task = task.clone();
        let more = action_icon(("download-more", row), IconName::EllipsisVertical, t(Str::CdMore));
        self.focus.trailing_button(row, 0, more, cx).dropdown_menu_with_anchor(
            Anchor::TopRight,
            move |mut menu, _, _| {
                let id = task.id.clone();
                let act = |label: &'static str, action: fn(&DownloadService, &str)| {
                    let (view, id) = (view.clone(), id.clone());
                    PopupMenuItem::new(label).on_click(move |_, _, cx| {
                        view.update(cx, |view, _| action(&view.service, &id)).ok();
                    })
                };
                let other_format = || {
                    let task = task.clone();
                    PopupMenuItem::new(t(Str::DownloadOtherFormat))
                        .on_click(move |_, window, cx| DownloadsView::other_format(&task, window, cx))
                };
                match task.state {
                    DownloadState::Queued
                    | DownloadState::Preparing
                    | DownloadState::Downloading
                    | DownloadState::Processing => {
                        menu = menu
                            .item(act(t(Str::DownloadsActionPause), DownloadService::pause))
                            .item(act(t(Str::DialogCancel), DownloadService::cancel));
                    }
                    DownloadState::Paused => {
                        menu = menu
                            .item(act(t(Str::DownloadsActionResume), DownloadService::resume))
                            .item(act(t(Str::DialogCancel), DownloadService::cancel));
                    }
                    DownloadState::Failed => {
                        let (retry_view, retry_task) = (view.clone(), task.clone());
                        menu = menu
                            .item(PopupMenuItem::new(t(Str::Retry)).on_click(move |_, window, cx| {
                                retry_view.update(cx, |view, cx| view.retry(&retry_task, window, cx)).ok();
                            }))
                            .item(other_format())
                            .item(act(t(Str::MenuRemove), DownloadService::remove));
                    }
                    DownloadState::Completed => {
                        let (play_view, play_task) = (view.clone(), task.clone());
                        menu = menu.item(PopupMenuItem::new(t(Str::MenuPlay)).on_click(move |_, _, cx| {
                            play_view.update(cx, |view, cx| view.play(&play_task, cx)).ok();
                        }));
                        let code = task.video_id.clone();
                        menu = menu
                            .item(PopupMenuItem::new(t(Str::MenuAddToPlaylist)).on_click(move |_, window, cx| {
                                AddToLocalPlaylist::open(vec![code.clone()], None, window, cx)
                            }))
                            .item(other_format());
                        if let Some(path) = file_path(&task) {
                            let (library_view, file, renamed) = (view.clone(), path.clone(), path.clone());
                            menu = menu
                                .item(PopupMenuItem::new(t(Str::DownloadsActionRevealLibrary)).on_click(
                                    move |_, _, cx| {
                                        let file = file.clone();
                                        library_view.update(cx, |_, cx| cx.emit(RevealInLibrary { file })).ok();
                                    },
                                ))
                                .item(
                                    PopupMenuItem::new(crate::library::reveal_label())
                                        .on_click(move |_, _, cx| cx.reveal_path(&path)),
                                )
                                .item(PopupMenuItem::new(t(Str::MenuRename)).on_click(move |_, window, cx| {
                                    RenameFileDialog::open(renamed.clone(), None, window, cx)
                                }));
                        }
                        let (disk_view, disk_task) = (view.clone(), task.clone());
                        menu = menu.separator().item(act(t(Str::MenuRemove), DownloadService::remove)).item(
                            PopupMenuItem::new(t(Str::MenuRemoveFromDisk)).on_click(move |_, window, cx| {
                                disk_view
                                    .update(cx, |view, cx| view.remove_from_disk(disk_task.clone(), window, cx))
                                    .ok();
                            }),
                        );
                    }
                    DownloadState::Canceled => {
                        menu = menu.item(other_format()).item(act(t(Str::MenuRemove), DownloadService::remove));
                    }
                }
                menu
            },
        )
    }

    fn card(&self, row: usize, task: &DownloadTask, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let thumb = task
            .thumbnail_url
            .clone()
            .unwrap_or_else(|| format!("https://i.ytimg.com/vi/{}/mqdefault.jpg", task.video_id));
        let active = task.state.is_active() || task.state == DownloadState::Paused;
        let fraction = task.progress();
        let percent =
            fraction.filter(|_| task.state.is_active()).map(|value| format!("{}%", (value * 100.).round() as i32));
        let show_bar = matches!(task.state, DownloadState::Preparing)
            || (matches!(task.state, DownloadState::Downloading | DownloadState::Processing | DownloadState::Paused)
                && fraction.is_some());
        let failed = task.state == DownloadState::Failed;
        let (pill_bg, pill_fg) = match task.state {
            DownloadState::Failed => (theme.danger, theme.danger_foreground),
            DownloadState::Completed => (theme.secondary, theme.foreground),
            _ if active => (theme.primary, theme.primary_foreground),
            _ => (theme.secondary, theme.muted_foreground),
        };
        let status = state_label(task.state);
        let detail: AnyElement = match task.state {
            DownloadState::Downloading | DownloadState::Paused if task.total_bytes > 0 => div()
                .text_xs()
                .text_color(theme.muted_foreground)
                .child(format!("{} / {}", size_label(task.downloaded_bytes), size_label(task.total_bytes)))
                .into_any_element(),
            DownloadState::Completed => h_flex()
                .gap_3()
                .text_xs()
                .text_color(theme.muted_foreground)
                .child(size_label(task.total_bytes))
                .when_some(short_date(task.updated_at), |row, date| row.child(date))
                .into_any_element(),
            DownloadState::Failed => {
                let id = task.id.clone();
                h_flex()
                    .flex_1()
                    .gap_2()
                    .items_center()
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_xs()
                            .text_color(theme.danger)
                            .line_clamp(2)
                            .child(task.error.as_ref().map(task_error_text).unwrap_or_default()),
                    )
                    .child(
                        self.focus
                            .trailing_button(
                                row,
                                1,
                                Button::new(("retry", row)).outline().xsmall().rounded_full().label(t(Str::Retry)),
                                cx,
                            )
                            .on_click(cx.listener(move |view, _, window, cx| {
                                if let Some(task) = view.tasks.iter().find(|task| task.id == id).cloned() {
                                    view.retry(&task, window, cx);
                                }
                            })),
                    )
                    .into_any_element()
            }
            _ => div().into_any_element(),
        };
        let label =
            [task.title.clone(), status.to_owned()].into_iter().chain(percent.clone()).collect::<Vec<_>>().join(", ");
        self.focus
            .item(row, list_item(v_flex().id(("download", row)), row).aria_label(label), cx)
            .rounded(px(6.))
            .border_1()
            .border_color(theme.border)
            .p(px(14.))
            .gap_2()
            .child(
                h_flex()
                    .gap_3()
                    .items_start()
                    .child(
                        div()
                            .size(px(THUMB))
                            .flex_shrink_0()
                            .rounded(px(6.))
                            .overflow_hidden()
                            .bg(theme.secondary)
                            .child(
                                img(SharedString::from(thumb)).size_full().rounded(px(6.)).object_fit(ObjectFit::Cover),
                            ),
                    )
                    .child(
                        v_flex()
                            .flex_1()
                            .min_w_0()
                            .gap_1()
                            .child(div().text_sm().font_medium().line_clamp(2).child(task.title.clone()))
                            .child(
                                h_flex()
                                    .gap_2()
                                    .items_center()
                                    .child(
                                        div()
                                            .px_2()
                                            .py(px(2.))
                                            .rounded_full()
                                            .bg(pill_bg)
                                            .text_color(pill_fg)
                                            .text_xs()
                                            .child(status),
                                    )
                                    .when_some(percent, |row, percent| {
                                        row.child(div().text_xs().text_color(theme.muted_foreground).child(percent))
                                    }),
                            ),
                    )
                    .child(self.menu(row, task, cx)),
            )
            .when(show_bar, |card| {
                card.child(
                    div()
                        .pl(px(THUMB + 12.))
                        .child(Progress::new(("progress", row)).value(fraction.unwrap_or(0.) * 100.)),
                )
            })
            .child(
                h_flex()
                    .gap_3()
                    .items_center()
                    .child(
                        div().w(px(THUMB)).flex().justify_center().child(
                            div()
                                .px_1()
                                .rounded(px(3.))
                                .border_1()
                                .border_color(theme.border)
                                .text_xs()
                                .text_color(theme.muted_foreground)
                                .child(task.format.extension().to_uppercase()),
                        ),
                    )
                    .child(if failed { detail } else { div().flex_1().child(detail).into_any_element() }),
            )
            .into_any_element()
    }
}

impl Render for DownloadsView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::shell::track_scroll(&self.scroll, cx);
        let theme = cx.theme().clone();
        if self.tasks.iter().all(|task| task.state == DownloadState::Canceled) {
            return v_flex()
                .size_full()
                .items_center()
                .justify_center()
                .gap_2()
                .child(Icon::new(Lucide::Download).size(px(56.)).text_color(theme.muted_foreground))
                .child(heading("downloads-none-title", 2, t(Str::DownloadsNoneTitle)).text_lg())
                .child(
                    announced_as(
                        "downloads-none-message",
                        t(Str::DownloadsNoneMessage),
                        format!("{}. {}", t(Str::DownloadsNoneTitle), t(Str::DownloadsNoneMessage)),
                    )
                    .text_sm()
                    .text_color(theme.muted_foreground),
                )
                .into_any_element();
        }
        let filter = self.filter;
        let visible: Vec<DownloadTask> = self.tasks.iter().filter(|task| filter.matches(task.state)).cloned().collect();
        let any_active = self.tasks.iter().any(|task| task.state.is_active());
        let any_paused = self.tasks.iter().any(|task| task.state == DownloadState::Paused);

        let mut chips = h_flex()
            .id("download-filters")
            .role(Role::TabList)
            .gap_2()
            .track_scroll(self.chip_drag.scroll())
            .overflow_x_scroll()
            .restrict_scroll_to_axis();
        for (index, candidate) in Filter::ALL.into_iter().enumerate() {
            let count = self.tasks.iter().filter(|task| candidate.matches(task.state)).count();
            let label = if candidate == Filter::All {
                candidate.label().to_owned()
            } else {
                format!("{} {count}", candidate.label())
            };
            let selected = candidate == filter;
            let chip = crate::action_icon::chip(Button::new(("filter", index))).label(label).selected(selected);
            let chip = if selected { chip.primary() } else { chip.outline() };
            let chip = chip.on_click(cx.listener(move |view, _, _, cx| {
                view.filter = candidate;
                cx.notify();
            }));
            chips = chips.child(self.chip_focus.button(index, chip, cx));
        }

        let width = crate::feed::feed_width(window);
        let columns = ((width - 32. + 16.) / (CARD_MIN + 16.)).floor().max(1.) as usize;
        let mut grid = v_flex().w_full().px_4().pt_2().pb(px(88.)).gap(px(12.)).child(self.chip_drag.wrap(chips));
        let stops = self.focus.list_stops(0..visible.len(), window, cx);
        let mut cards = v_flex().id("download-cards").w_full().gap(px(12.));
        if visible.is_empty() {
            grid = grid.child(
                div()
                    .py_16()
                    .flex()
                    .justify_center()
                    .child(announced("downloads-empty", filter.empty()).text_color(theme.muted_foreground)),
            );
        }
        for (row_index, row) in visible.chunks(columns).enumerate() {
            let mut cells = h_flex().w_full().gap_4().items_start();
            for (offset, task) in row.iter().enumerate() {
                cells = cells.child(div().flex_1().min_w_0().child(self.card(row_index * columns + offset, task, cx)));
            }
            for _ in row.len()..columns {
                cells = cells.child(div().flex_1());
            }
            cards = cards.child(cells);
        }
        let grid = grid.child(stops.wrap(cards, Some(t(Str::SectionDownloads).into()), cx));
        let fab = if any_active {
            Some((t(Str::DownloadsPauseAll), Lucide::Pause, true))
        } else if any_paused {
            Some((t(Str::DownloadsResumeAll), Lucide::Play, false))
        } else {
            None
        };
        div()
            .relative()
            .size_full()
            .child(div().id("downloads-scroll").size_full().overflow_y_scroll().track_scroll(&self.scroll).child(grid))
            .when_some(fab, |frame, (label, icon, pause)| {
                let fab =
                    Button::new("downloads-fab").primary().large().rounded(px(16.)).icon(icon).label(label).on_click(
                        cx.listener(
                            move |view, _, _, _| {
                                if pause { view.service.pause_all() } else { view.service.resume_all() }
                            },
                        ),
                    );
                frame.child(div().absolute().right_4().bottom_4().child(self.fab_focus.button(0, fab, cx)))
            })
            .into_any_element()
    }
}
