//! The download manager: every task with its state and progress, filtered by state, with per-task
//! and whole-queue controls. The list is the service's snapshot, kept current from its changes.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::time::Duration;

use futures::StreamExt;
use futures::channel::mpsc;
use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::progress::Progress;
use gpui_kit::component::{ActiveTheme, Icon, IconName, Selectable, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_download::{DownloadChange, DownloadFormat, DownloadService, DownloadState, DownloadTask, TaskError};
use ym_i18n::{Plural, Str, plural, t, tf};
use ym_image_loader::async_image::async_image_of;

use crate::a11y::{DragRow, FocusList, announced, announced_as, heading, list_item};
use crate::account::AccountController;
use crate::action_icon::{HIT, action_icon, hit_target};
use crate::download_dialog::{DownloadPicker, DownloadTarget};
use crate::downloads::pick_download_folder;
use crate::file_rename::RenameFileDialog;
use crate::graph::{AppGraph, on_core};
use crate::lazy_rows::LazyRows;
use crate::library::PlayLocal;
use crate::library_playlist::AddToLocalPlaylist;
use crate::playback::{QueueItem, Source};
use crate::youtube_thumbnails::art_request_sources;

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
    rows: LazyRows,
    focus: FocusList,
    /// The filter chips, then the empty filter's message.
    chip_focus: FocusList,
    fab_focus: FocusList,
    /// The no-downloads page's title and message.
    chip_drag: DragRow,
    /// Sign-in-required failures to re-run once the account signs in.
    retry_after_sign_in: Vec<String>,
    /// The list could not be read; the screen says so with Retry (the JVM ErrorState).
    load_error: Option<&'static str>,
    /// The tasks picked on the selection bar; selection mode lasts while it holds any.
    selected_ids: HashSet<String>,
    long_press: Option<LongPress>,
    /// The card a long press just selected: its release is not a click.
    long_pressed: Option<String>,
    /// The selection bar's buttons.
    bar_focus: FocusList,
    _changes: Task<()>,
}

/// A press held on a card, mouse or Enter/Space (Compose `combinedClickable`'s long click): once it
/// lasts [`LONG_PRESS`], the card is selected.
struct LongPress {
    id: String,
    /// Where a mouse press went down; moving past [`TOUCH_SLOP`] lets it go.
    origin: Option<Point<Pixels>>,
    _timer: Task<()>,
}

type SelectionAction = fn(&mut DownloadsView, &mut Window, &mut Context<DownloadsView>);

/// Selection mode began or ended: the shell swaps its bar for the selection bar.
pub struct SelectionModeChanged;

/// What a failed task's Retry does first.
enum RetryRoute {
    SignInDialog,
    UnavailableDialog,
    PickFolder,
    Plain,
}

/// Open the Library at the folder holding a finished download.
pub struct RevealInLibrary {
    pub file: PathBuf,
}

impl EventEmitter<PlayLocal> for DownloadsView {}
impl EventEmitter<RevealInLibrary> for DownloadsView {}
impl EventEmitter<SelectionModeChanged> for DownloadsView {}

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
            chip_focus: FocusList::new(Some(scroll.clone())),
            rows: LazyRows::new(scroll, CARD_ROW_ESTIMATE),
            fab_focus: FocusList::new(None),
            chip_drag: DragRow::default(),
            retry_after_sign_in: Vec::new(),
            load_error: None,
            selected_ids: HashSet::new(),
            long_press: None,
            long_pressed: None,
            bar_focus: FocusList::new(None),
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
                        view.load_error = None;
                        view.drop_gone_selection(cx);
                    }
                    Some(Err(error)) => {
                        cx.global::<AppGraph>()
                            .log
                            .warn(&format!("{LOAD_FAILED} the download list could not be read"), Some(&error));
                        view.load_error = Some(LOAD_FAILED);
                        view.clear_selection(cx);
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
        self.drop_gone_selection(cx);
        cx.notify();
    }

    pub fn selecting(&self) -> bool {
        !self.selected_ids.is_empty()
    }

    pub fn clear_selection(&mut self, cx: &mut Context<Self>) {
        self.change_selection(cx, HashSet::clear);
    }

    fn change_selection(&mut self, cx: &mut Context<Self>, change: impl FnOnce(&mut HashSet<String>)) {
        let was_selecting = self.selecting();
        change(&mut self.selected_ids);
        if was_selecting != self.selecting() {
            self.long_press = None;
            cx.emit(SelectionModeChanged);
        }
        cx.notify();
    }

    fn toggle_selected(&mut self, id: &str, cx: &mut Context<Self>) {
        self.change_selection(cx, |selected| {
            if !selected.remove(id) {
                selected.insert(id.to_owned());
            }
        });
    }

    /// Exactly the tasks the filter shows.
    fn select_all(&mut self, cx: &mut Context<Self>) {
        let filter = self.filter;
        let shown: HashSet<String> =
            self.tasks.iter().filter(|task| filter.matches(task.state)).map(|task| task.id.clone()).collect();
        self.change_selection(cx, |selected| *selected = shown);
    }

    /// Drops the picks whose task is gone from the screen (removed, or canceled: no filter shows it).
    fn drop_gone_selection(&mut self, cx: &mut Context<Self>) {
        if !self.selecting() {
            return;
        }
        let shown: HashSet<&str> =
            self.tasks.iter().filter(|task| Filter::All.matches(task.state)).map(|task| task.id.as_str()).collect();
        if self.selected_ids.iter().all(|id| shown.contains(id.as_str())) {
            return;
        }
        let shown: HashSet<String> = shown.into_iter().map(str::to_owned).collect();
        self.change_selection(cx, |selected| selected.retain(|id| shown.contains(id)));
    }

    fn selected_tasks(&self) -> impl Iterator<Item = &DownloadTask> {
        self.tasks.iter().filter(|task| self.selected_ids.contains(&task.id))
    }

    fn start_long_press(&mut self, id: String, origin: Option<Point<Pixels>>, cx: &mut Context<Self>) {
        self.long_pressed = None;
        let timer = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(LONG_PRESS).await;
            this.update(cx, |view, cx| view.long_press_lasted(cx)).ok();
        });
        self.long_press = Some(LongPress { id, origin, _timer: timer });
    }

    fn release_long_press(&mut self) {
        self.long_press = None;
    }

    fn long_press_moved(&mut self, position: Point<Pixels>) {
        let moved = self.long_press.as_ref().and_then(|press| press.origin).is_some_and(|origin| {
            let delta = position - origin;
            f32::from(delta.x).hypot(f32::from(delta.y)) > TOUCH_SLOP
        });
        if moved {
            self.long_press = None;
        }
    }

    /// A long press selects its card; while selecting, it toggles the card as a click does.
    fn long_press_lasted(&mut self, cx: &mut Context<Self>) {
        let Some(press) = self.long_press.take() else { return };
        if self.selecting() {
            self.toggle_selected(&press.id, cx);
        } else {
            self.change_selection(cx, |selected| {
                selected.insert(press.id.clone());
            });
        }
        self.long_pressed = Some(press.id);
    }

    /// A card is not clickable outside selection mode; in it, a click toggles the card.
    fn card_clicked(&mut self, id: &str, cx: &mut Context<Self>) {
        if self.long_pressed.take().is_some_and(|pressed| pressed == id) {
            return;
        }
        if self.selecting() {
            self.toggle_selected(id, cx);
        }
    }

    /// A Retry that a playability verdict answers first: signed out on a sign-in verdict, the
    /// sign-in dialog (the task re-runs once signed in); an unavailable verdict offers removal; a
    /// lost folder re-picks it.
    fn retry(&mut self, task: &DownloadTask, window: &mut Window, cx: &mut Context<Self>) {
        let id = task.id.clone();
        match retry_route(task, cx) {
            RetryRoute::SignInDialog => self.sign_in_dialog(id, window, cx),
            RetryRoute::UnavailableDialog => self.unavailable_dialog(id, window, cx),
            RetryRoute::PickFolder => {
                let pick = pick_download_folder(window, cx);
                cx.spawn(async move |this, cx| {
                    if pick.await.is_some() {
                        this.update(cx, |view, _| view.service.retry(&id)).ok();
                    }
                })
                .detach();
            }
            RetryRoute::Plain => self.service.retry(&id),
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
            "removedisk" => self.remove_from_disk(vec![task], window, cx),
            "rename" => {
                if let Some(file) = file_path(&task) {
                    RenameFileDialog::open(file, window, cx);
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
                thumbnail_url: task.thumbnail_url.clone(),
            },
            window,
            cx,
        );
    }

    /// The JVM PlaybackCoordinator.playDownloads: the finished downloads the filter shows, in order,
    /// from `task`; each is its Library item when the store has indexed it, else the file.
    fn play(&mut self, task: &DownloadTask, cx: &mut Context<Self>) {
        let filter = self.filter;
        let finished: Vec<(DownloadTask, PathBuf)> = self
            .tasks
            .iter()
            .filter(|shown| filter.matches(shown.state) && shown.state == DownloadState::Completed)
            .filter_map(|shown| file_path(shown).map(|path| (shown.clone(), path)))
            .collect();
        let Some(index) = finished.iter().position(|(shown, _)| shown.id == task.id) else { return };
        let graph = cx.global::<AppGraph>();
        let (media, log) = (graph.media.clone(), graph.log.fork("play-downloads"));
        let lookup = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                finished
                    .into_iter()
                    .map(|(task, path)| match media.find_item_by_uri(&path.to_string_lossy()) {
                        Ok(Some((item, _))) => QueueItem::from_local(&item),
                        Ok(None) => download_item(&task, path),
                        Err(error) => {
                            log.warn("library lookup failed", Some(&error));
                            download_item(&task, path)
                        }
                    })
                    .collect::<Vec<_>>()
            })
            .await
        });
        cx.spawn(async move |this, cx| {
            let Some(Ok(items)) = lookup.await else { return };
            this.update(cx, |_, cx| cx.emit(PlayLocal { items, index, shuffle: None })).ok();
        })
        .detach();
    }
}

/// A finished download the Library has not indexed: the file, named as the task.
fn download_item(task: &DownloadTask, path: PathBuf) -> QueueItem {
    QueueItem {
        source: Source::Local(path),
        title: task.title.clone(),
        artist: task.artist.clone(),
        duration_ms: None,
        duration_label: None,
        thumbs: vec![ym_engine::yt::YtThumb {
            url: crate::local_art::youtube_art_url(&task.video_id),
            w: None,
            h: None,
        }],
        media_id: None,
        history_code: None,
        youtube_vid: Some(task.video_id.clone()),
        loudness_gain_db: task.loudness_gain_db,
        live: false,
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

/// `control` keeps its presses: the card around it neither long-presses nor clicks with them.
fn own_press(control: impl IntoElement) -> Div {
    div().flex_shrink_0().on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation()).child(control)
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

fn retry_route(task: &DownloadTask, cx: &App) -> RetryRoute {
    let code = task.error.as_ref().map_or("", |error| error.code.as_str());
    match playability(code) {
        Some(Playability::SignInRequired)
            if !cx.global::<AppGraph>().guest && !AccountController::global(cx).read(cx).signed_in =>
        {
            RetryRoute::SignInDialog
        }
        Some(Playability::Unavailable) => RetryRoute::UnavailableDialog,
        None if code == ym_download::error::FOLDER_ACCESS_CODE
            || code == ym_download::error::LEGACY_FOLDER_ACCESS_CODE =>
        {
            RetryRoute::PickFolder
        }
        _ => RetryRoute::Plain,
    }
}

/// Pause on the selection bar: what the card's menu pauses.
fn can_pause(task: &DownloadTask) -> bool {
    task.state.is_active()
}

/// Resume on the selection bar: a paused task, or a failed one whose Retry asks nothing first.
fn can_resume(task: &DownloadTask, cx: &App) -> bool {
    match task.state {
        DownloadState::Paused => true,
        DownloadState::Failed => matches!(retry_route(task, cx), RetryRoute::Plain),
        _ => false,
    }
}

/// Remove on the selection bar: the states whose card menu has Remove.
fn can_remove(task: &DownloadTask) -> bool {
    matches!(task.state, DownloadState::Failed | DownloadState::Completed | DownloadState::Canceled)
}

/// Remove from disk on the selection bar: the state whose card menu has it.
fn can_delete_file(task: &DownloadTask) -> bool {
    task.state == DownloadState::Completed
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

/// A failure as the user reads it (the JVM `downloadErrorUiText`): no code reads its message or
/// "Download failed"; then the folder category, a playability verdict, the message with its code,
/// else the code.
pub fn task_error_text(error: Option<&TaskError>) -> String {
    let Some(error) = error.filter(|error| !error.code.is_empty()) else {
        return error
            .and_then(|error| error.user_message.clone())
            .unwrap_or_else(|| t(Str::DownloadsFailed).to_owned());
    };
    if error.code == ym_download::error::FOLDER_ACCESS_CODE
        || error.code == ym_download::error::LEGACY_FOLDER_ACCESS_CODE
    {
        return t(Str::DownloadsErrorFolderAccess).to_owned();
    }
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
        "live" => t(Str::DownloadLiveUnsupported).to_owned(),
        "processing" => t(Str::ErrorLiveStillProcessing).to_owned(),
        code => tf(Str::ErrorGeneric, &[&code]),
    }
}

/// A distinct tint per format so the type reads at a glance; white text sits on all of them.
fn format_badge_color(format: DownloadFormat) -> Hsla {
    rgb(match format {
        DownloadFormat::Mp3 => 0x2e7d32,
        DownloadFormat::M4a => 0x1565c0,
        DownloadFormat::AudioWebm => 0x00695c,
        DownloadFormat::VideoMp4 => 0x6a1b9a,
        DownloadFormat::VideoWebm => 0xb23c17,
    })
    .into()
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

const LOAD_FAILED: &str = "R90034";
const FILE_NOT_DELETED: &str = "R90022";
/// Compose's `ViewConfiguration.longPressTimeoutMillis`.
const LONG_PRESS: Duration = Duration::from_millis(500);
/// How far a held mouse may move and still be a long press.
const TOUCH_SLOP: f32 = 8.;
const CARD_MIN: f32 = 340.;
const CARD_ROW_ESTIMATE: f32 = 120.;
const THUMB: f32 = 56.;

impl DownloadsView {
    /// Deletes each finished file and its Library row, then its task; a failed delete keeps its task,
    /// and one notice says so.
    fn remove_from_disk(&mut self, tasks: Vec<DownloadTask>, window: &mut Window, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (media, log) = (graph.media.clone(), graph.log.fork("downloads"));
        let delete = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                tasks
                    .into_iter()
                    .map(|task| {
                        let Some(path) = file_path(&task) else { return (task.id, Ok(())) };
                        let removed = std::fs::remove_file(&path);
                        if removed.as_ref().is_ok()
                            || removed.as_ref().is_err_and(|error| error.kind() == std::io::ErrorKind::NotFound)
                        {
                            let row = media.find_item_by_uri(&path.to_string_lossy());
                            if let Err(error) =
                                row.and_then(|row| row.map_or(Ok(()), |(item, _)| media.delete_item(item.id)))
                            {
                                log.warn("the deleted download's Library row could not be removed", Some(&error));
                            }
                        }
                        (task.id, removed)
                    })
                    .collect::<Vec<_>>()
            })
            .await
        });
        cx.spawn_in(window, async move |this, cx| {
            let Some(outcome) = delete.await else { return };
            this.update_in(cx, |view, window, cx| {
                let failures: Vec<std::io::Error> = match outcome {
                    Ok(results) => results
                        .into_iter()
                        .filter_map(|(id, result)| match result {
                            Err(error) if error.kind() != std::io::ErrorKind::NotFound => Some(error),
                            _ => {
                                view.service.remove(&id);
                                None
                            }
                        })
                        .collect(),
                    Err(error) => vec![std::io::Error::other(error)],
                };
                for error in &failures {
                    cx.global::<AppGraph>()
                        .log
                        .warn(&format!("{FILE_NOT_DELETED} downloaded file not deleted"), Some(error));
                }
                if !failures.is_empty() {
                    window.push_notification(tf(Str::ErrorGeneric, &[&FILE_NOT_DELETED]), cx);
                }
            })
            .ok();
        })
        .detach();
    }

    fn pause_selected(&mut self, cx: &mut Context<Self>) {
        for task in self.selected_tasks().filter(|task| can_pause(task)) {
            self.service.pause(&task.id);
        }
        self.clear_selection(cx);
    }

    fn resume_selected(&mut self, cx: &mut Context<Self>) {
        for task in self.selected_tasks().filter(|task| can_resume(task, cx)) {
            if task.state == DownloadState::Paused {
                self.service.resume(&task.id);
            } else {
                self.service.retry(&task.id);
            }
        }
        self.clear_selection(cx);
    }

    fn remove_selected(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let ids: Vec<String> =
            self.selected_tasks().filter(|task| can_remove(task)).map(|task| task.id.clone()).collect();
        let count = ids.len() as i64;
        let view = cx.entity().downgrade();
        crate::elevated_dialog::ConfirmDialog::new(
            plural(Plural::DownloadsRemoveSelectedTitle, count, &[&count]),
            t(Str::DownloadsRemoveSelectedMessage),
            t(Str::MenuRemove),
        )
        .open(window, cx, move |_, cx| {
            view.update(cx, |view, cx| {
                for id in &ids {
                    view.service.remove(id);
                }
                view.clear_selection(cx);
            })
            .ok();
        });
    }

    fn delete_selected(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let tasks: Vec<DownloadTask> = self.selected_tasks().filter(|task| can_delete_file(task)).cloned().collect();
        let count = tasks.len() as i64;
        let view = cx.entity().downgrade();
        crate::elevated_dialog::ConfirmDialog::new(
            plural(Plural::DownloadsDeleteSelectedTitle, count, &[&count]),
            t(Str::DownloadsDeleteSelectedMessage),
            t(Str::DeleteFromDiskConfirm),
        )
        .destructive()
        .open(window, cx, move |window, cx| {
            view.update(cx, |view, cx| {
                view.remove_from_disk(tasks.clone(), window, cx);
                view.clear_selection(cx);
            })
            .ok();
        });
    }

    /// The bar that stands in for the section's while selecting: leave, the count, Select all, then
    /// each action that some selected task can take.
    fn selection_bar(&self, cx: &mut Context<Self>) -> AnyElement {
        let selected: Vec<&DownloadTask> = self.selected_tasks().collect();
        let count = selected.len() as i64;
        let actions: [(bool, &'static str, Lucide, SelectionAction); 4] = [
            (
                selected.iter().any(|task| can_pause(task)),
                t(Str::DownloadsActionPause),
                Lucide::Pause,
                |view, _, cx| view.pause_selected(cx),
            ),
            (
                selected.iter().any(|task| can_resume(task, cx)),
                t(Str::DownloadsActionResume),
                Lucide::Play,
                |view, _, cx| view.resume_selected(cx),
            ),
            (
                selected.iter().any(|task| can_remove(task)),
                t(Str::MenuRemoveFolder),
                Lucide::ListX,
                Self::remove_selected,
            ),
            (
                selected.iter().any(|task| can_delete_file(task)),
                t(Str::MenuRemoveFromDisk),
                Lucide::Trash,
                Self::delete_selected,
            ),
        ];
        let close = action_icon("selection-close", Icon::new(Lucide::X), t(Str::CdClose))
            .on_click(cx.listener(|view, _, _, cx| view.clear_selection(cx)));
        let select_all = action_icon("selection-all", Icon::new(Lucide::ListChecks), t(Str::PlaylistImportSelectAll))
            .on_click(cx.listener(|view, _, _, cx| view.select_all(cx)));
        let mut bar = crate::shell::top_bar(cx)
            .child(self.bar_focus.button(0, close, cx))
            .child(
                heading("selection-count", 1, plural(Plural::DownloadsSelectedCount, count, &[&count]))
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_lg()
                    .font_semibold(),
            )
            .child(self.bar_focus.button(1, select_all, cx));
        for (index, (shown, label, icon, act)) in actions.into_iter().enumerate() {
            if shown {
                let button = action_icon(("selection-action", index), Icon::new(icon), label)
                    .on_click(cx.listener(move |view, _, window, cx| act(view, window, cx)));
                bar = bar.child(self.bar_focus.button(2 + index, button, cx));
            }
        }
        bar.into_any_element()
    }

    /// The card's checkbox while selecting: toggles it, as a click on the card does.
    fn check(&self, row: usize, task: &DownloadTask, checked: bool, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme();
        let (primary, on_primary, border) = (theme.primary, theme.primary_foreground, theme.muted_foreground);
        let id = task.id.clone();
        let mark = div()
            .size(px(18.))
            .flex()
            .items_center()
            .justify_center()
            .rounded(px(4.))
            .border_2()
            .border_color(if checked { primary } else { border })
            .when(checked, |mark| {
                mark.bg(primary).child(Icon::new(IconName::Check).size(px(14.)).text_color(on_primary))
            });
        let button = hit_target(("download-check", row), HIT)
            .role(Role::CheckBox)
            .toggled(checked)
            .accessibility_label(task.title.clone())
            .child(mark)
            .on_click(cx.listener(move |view, _, _, cx| view.toggle_selected(&id, cx)));
        own_press(self.focus.trailing_button(row, 0, button, cx))
    }

    fn menu(&self, row: usize, task: &DownloadTask, cx: &mut Context<Self>) -> impl IntoElement {
        let view = cx.entity().downgrade();
        let task = task.clone();
        let more = action_icon(("download-more", row), IconName::EllipsisVertical, t(Str::CdMore));
        let menu = self.focus.trailing_button(row, 0, more, cx).dropdown_menu_with_anchor(
            Anchor::TopRight,
            move |mut menu, _, _| {
                let id = task.id.clone();
                let act = |label: &'static str, action: fn(&DownloadService, &str)| {
                    let (view, id) = (view.clone(), id.clone());
                    PopupMenuItem::new(label).on_click(move |_, _, cx| {
                        view.update(cx, |view, _| action(&view.service, &id)).ok();
                    })
                };
                let share = || {
                    let video_id = task.video_id.clone();
                    PopupMenuItem::new(t(Str::CdShare)).on_click(move |_, window, cx| {
                        if let Some(link) = crate::feed::youtube_share_link("video", &video_id) {
                            cx.write_to_clipboard(ClipboardItem::new_string(link));
                            window.push_notification(t(Str::LinkCopied), cx);
                        }
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
                            .item(share())
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
                                    RenameFileDialog::open(renamed.clone(), window, cx)
                                }));
                        }
                        menu = menu.item(share());
                        let (disk_view, disk_task) = (view.clone(), task.clone());
                        menu = menu
                            .item(crate::settings_view::menu_divider())
                            .item(act(t(Str::MenuRemove), DownloadService::remove))
                            .item(PopupMenuItem::new(t(Str::MenuRemoveFromDisk)).on_click(move |_, window, cx| {
                                let (view, task) = (disk_view.clone(), disk_task.clone());
                                crate::elevated_dialog::ConfirmDialog::new(
                                    t(Str::DeleteFromDiskTitle),
                                    tf(Str::DeleteFromDiskMessage, &[&task.title]),
                                    t(Str::DeleteFromDiskConfirm),
                                )
                                .destructive()
                                .open(window, cx, move |window, cx| {
                                    view.update(cx, |view, cx| view.remove_from_disk(vec![task.clone()], window, cx))
                                        .ok();
                                });
                            }));
                    }
                    DownloadState::Canceled => {
                        menu = menu
                            .item(other_format())
                            .item(share())
                            .item(act(t(Str::MenuRemove), DownloadService::remove));
                    }
                }
                menu
            },
        );
        own_press(menu)
    }

    fn card(&self, row: usize, task: &DownloadTask, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let thumb = task
            .thumbnail_url
            .clone()
            .unwrap_or_else(|| format!("https://i.ytimg.com/vi/{}/mqdefault.jpg", task.video_id));
        let fraction = task.progress();
        let percent =
            fraction.filter(|_| task.state.is_active()).map(|value| format!("{}%", (value * 100.).round() as i32));
        let show_bar = matches!(task.state, DownloadState::Preparing)
            || (matches!(task.state, DownloadState::Downloading | DownloadState::Processing | DownloadState::Paused)
                && fraction.is_some());
        let failed = task.state == DownloadState::Failed;
        let tones = *cx.global::<crate::settings_view::CardTones>();
        let dark = theme.mode.is_dark();
        let soft = |color: Hsla| color.opacity(if dark { 0.16 } else { 0.14 });
        let (pill_bg, pill_fg) = match task.state {
            DownloadState::Queued | DownloadState::Canceled => (theme.muted, tones.ink),
            DownloadState::Paused => {
                let amber: Hsla = rgb(if dark { 0xe3a63e } else { 0xb5720f }).into();
                (soft(amber), amber)
            }
            DownloadState::Completed => {
                let green: Hsla = rgb(if dark { 0x57c983 } else { 0x288a50 }).into();
                (soft(green), green)
            }
            DownloadState::Failed => (theme.danger.opacity(0.12), theme.danger),
            _ => (theme.primary.opacity(0.16), theme.primary),
        };
        let mono = theme.mono_font_family.clone();
        let status = state_label(task.state);
        let detail: AnyElement = match task.state {
            DownloadState::Downloading | DownloadState::Paused if task.total_bytes > 0 => div()
                .text_xs()
                .text_color(tones.ink)
                .font_family(mono.clone())
                .child(format!("{} / {}", size_label(task.downloaded_bytes), size_label(task.total_bytes)))
                .into_any_element(),
            DownloadState::Completed => h_flex()
                .gap_2()
                .text_xs()
                .text_color(tones.ink)
                .child(div().font_family(mono.clone()).child(size_label(task.total_bytes)))
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
                            .child(task_error_text(task.error.as_ref())),
                    )
                    .child(own_press(
                        self.focus
                            .trailing_button(
                                row,
                                1,
                                Button::new(("retry", row)).primary().xsmall().rounded_full().label(t(Str::Retry)),
                                cx,
                            )
                            .on_click(cx.listener(move |view, _, window, cx| {
                                if let Some(task) = view.tasks.iter().find(|task| task.id == id).cloned() {
                                    view.retry(&task, window, cx);
                                }
                            })),
                    ))
                    .into_any_element()
            }
            _ => div().into_any_element(),
        };
        let label =
            [task.title.clone(), status.to_owned()].into_iter().chain(percent.clone()).collect::<Vec<_>>().join(", ");
        let selecting = self.selecting();
        let checked = self.selected_ids.contains(&task.id);
        let card_focus = self.focus.handle(row, cx);
        let (click_id, press_id, key_press_id) = (task.id.clone(), task.id.clone(), task.id.clone());
        let trailing = if selecting {
            self.check(row, task, checked, cx).into_any_element()
        } else {
            self.menu(row, task, cx).into_any_element()
        };
        self.focus
            .item(
                row,
                list_item(v_flex().id(("download", row)), row)
                    .aria_label(label)
                    .when(selecting, |card| card.aria_selected(checked)),
                cx,
            )
            .on_click(cx.listener(move |view, _, _, cx| view.card_clicked(&click_id, cx)))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |view, event: &MouseDownEvent, _, cx| {
                    view.start_long_press(press_id.clone(), Some(event.position), cx)
                }),
            )
            .on_mouse_move(cx.listener(|view, event: &MouseMoveEvent, _, _| {
                if event.pressed_button == Some(MouseButton::Left) {
                    view.long_press_moved(event.position);
                }
            }))
            .on_mouse_up(MouseButton::Left, cx.listener(|view, _, _, _| view.release_long_press()))
            .on_mouse_up_out(MouseButton::Left, cx.listener(|view, _, _, _| view.release_long_press()))
            .on_key_down(cx.listener(move |view, event: &KeyDownEvent, window, cx| {
                let keystroke = &event.keystroke;
                if matches!(keystroke.key.as_str(), "enter" | "space")
                    && !keystroke.modifiers.modified()
                    && !event.is_held
                    && card_focus.is_focused(window)
                {
                    view.start_long_press(key_press_id.clone(), None, cx);
                }
            }))
            .on_key_up(cx.listener(|view, _: &KeyUpEvent, _, _| view.release_long_press()))
            .when(selecting, |card| card.cursor_pointer())
            .rounded(px(6.))
            .bg(if checked {
                theme.primary.opacity(0.12)
            } else if failed {
                theme.danger.opacity(0.12)
            } else {
                tones.ground
            })
            .border_1()
            .border_color(if checked {
                theme.primary
            } else if failed {
                theme.danger.opacity(0.4)
            } else {
                theme.border
            })
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
                                async_image_of(Some(art_request_sources(&thumb)))
                                    .size_full()
                                    .rounded(px(6.))
                                    .object_fit(ObjectFit::Cover),
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
                                        row.child(
                                            div()
                                                .text_xs()
                                                .text_color(tones.ink)
                                                .font_family(mono.clone())
                                                .child(percent),
                                        )
                                    }),
                            ),
                    )
                    .child(trailing),
            )
            .when(show_bar, |card| {
                card.child(
                    div().pl(px(THUMB + 12.)).child(
                        Progress::new(("progress", row))
                            .value(fraction.unwrap_or(0.) * 100.)
                            .when(task.state == DownloadState::Paused, |bar| bar.color(tones.ink)),
                    ),
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
                                .py(px(2.))
                                .rounded(px(3.))
                                .bg(format_badge_color(task.format))
                                .text_xs()
                                .text_color(white())
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
        if let Some(code) = self.load_error {
            return v_flex()
                .size_full()
                .items_center()
                .justify_center()
                .gap_3()
                .p_6()
                .child(crate::a11y::alert("downloads-error", tf(Str::ErrorGeneric, &[&code])).text_center())
                .child(
                    Button::new("downloads-retry")
                        .label(t(Str::Retry))
                        .on_click(cx.listener(|view, _, _, cx| view.refresh(cx))),
                )
                .into_any_element();
        }
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
        if visible.is_empty() {
            grid = grid.child(
                div()
                    .py_16()
                    .flex()
                    .justify_center()
                    .child(announced("downloads-empty", filter.empty()).text_color(theme.muted_foreground)),
            );
        }
        let kept: Vec<usize> = stops.kept().into_iter().map(|position| position / columns).collect();
        let cards = self
            .rows
            .render(visible.len().div_ceil(columns), 12., kept, window, |row_index| {
                let row = &visible[row_index * columns..((row_index + 1) * columns).min(visible.len())];
                let mut cells = h_flex().w_full().gap_4().items_start();
                for (offset, task) in row.iter().enumerate() {
                    cells =
                        cells.child(div().flex_1().min_w_0().child(self.card(row_index * columns + offset, task, cx)));
                }
                for _ in row.len()..columns {
                    cells = cells.child(div().flex_1());
                }
                cells.into_any_element()
            })
            .id("download-cards");
        let grid = grid.child(stops.wrap(cards, Some(t(Str::SectionDownloads).into()), cx));
        let selecting = self.selecting();
        let fab = if selecting {
            None
        } else if any_active {
            Some((t(Str::DownloadsPauseAll), Lucide::Pause, true))
        } else if any_paused {
            Some((t(Str::DownloadsResumeAll), Lucide::Play, false))
        } else {
            None
        };
        let page = div()
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
            });
        if !selecting {
            return page.into_any_element();
        }
        v_flex()
            .size_full()
            .child(self.selection_bar(cx))
            .child(div().flex_1().min_h_0().relative().child(page).children(crate::shell::bar_shadow(cx)))
            .into_any_element()
    }
}
