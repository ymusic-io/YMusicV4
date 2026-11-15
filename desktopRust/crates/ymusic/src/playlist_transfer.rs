//! Playlist backup and restore for the Library's Playlists tab (JVM `PlaylistTransfer.kt`; the
//! desktop shell has no device playlists to import). The backup is the JVM app's JSON file, written
//! byte for byte as its `JsonWriter` does, so either app restores the other's.

use std::cell::Cell;
use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::rc::Rc;

use gpui_kit::component::radio::Radio;
use gpui_kit::component::{ActiveTheme, Disableable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use serde_json::Value;
use ym_i18n::{Plural, Str, plural, t, tf};
use ym_store::{MediaStore, PlaylistSnapshot, PlaylistSnapshotItem, StoreError};

use crate::a11y::FocusList;
use crate::elevated_dialog::{self, button_bar, chrome, text_button, title_bar};
use crate::graph::{APP_VERSION_NAME, AppGraph, on_core};
use crate::library_playlist::Changed;
use crate::settings_view::HOVER_ALPHA;

const BACKUP_FORMAT: &str = "ymusic.playlists";
const BACKUP_VERSION: i64 = 1;

#[derive(Debug, thiserror::Error)]
pub enum TransferError {
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error(transparent)]
    Io(#[from] std::io::Error),
    /// Not a backup this app reads: the user's to act on, not a crash.
    #[error("not a playlist backup: {0}")]
    Invalid(String),
    #[error("backup version {0} is newer than this app's")]
    Newer(i64),
    #[error("the core runtime stopped")]
    Stopped,
}

impl TransferError {
    /// The user's category for a file they picked; None for a technical failure.
    fn user_message(&self) -> Option<&'static str> {
        match self {
            TransferError::Invalid(_) => Some(t(Str::ErrorPlaylistBackupInvalid)),
            TransferError::Newer(_) => Some(t(Str::ErrorPlaylistBackupNewer)),
            _ => None,
        }
    }
}

pub struct PlaylistBackup {
    pub exported_at: i64,
    pub playlists: Vec<PlaylistSnapshot>,
}

impl PlaylistBackup {
    fn song_count(&self) -> usize {
        self.playlists.iter().map(|playlist| playlist.items.len()).sum()
    }
}

fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

fn json_string(out: &mut impl Write, value: &str) -> std::io::Result<()> {
    serde_json::to_writer(out, value).map_err(std::io::Error::from)
}

/// Writes every user playlist to `path`, members that resolve to nothing here included; title and
/// artist ride along only so the file reads meaningfully, restore keys on `code`.
pub fn write_backup(store: &MediaStore, path: &Path, app_version: &str) -> Result<usize, TransferError> {
    let playlists = store.playlists()?;
    let contents =
        playlists.iter().map(|playlist| store.playlist_members(playlist.id)).collect::<Result<Vec<_>, _>>()?;
    let exported_at = now_millis();
    let mut out = BufWriter::new(File::create(path)?);
    out.write_all(b"{\"format\":")?;
    json_string(&mut out, BACKUP_FORMAT)?;
    write!(out, ",\"version\":{BACKUP_VERSION},\"exportedAt\":{exported_at},\"app\":")?;
    json_string(&mut out, app_version)?;
    out.write_all(b",\"playlists\":[")?;
    for (index, (playlist, members)) in playlists.iter().zip(&contents).enumerate() {
        if index > 0 {
            out.write_all(b",")?;
        }
        out.write_all(b"{\"name\":")?;
        json_string(&mut out, &playlist.name)?;
        write!(out, ",\"createdAt\":{},\"items\":[", playlist.created_at)?;
        for (position, member) in members.iter().enumerate() {
            if position > 0 {
                out.write_all(b",")?;
            }
            out.write_all(b"{\"code\":")?;
            json_string(&mut out, &member.code)?;
            write!(out, ",\"addedAt\":{}", member.added_at)?;
            if let Some(item) = &member.item {
                out.write_all(b",\"title\":")?;
                json_string(&mut out, &item.title)?;
                if let Some(artist) = &item.artist {
                    out.write_all(b",\"artist\":")?;
                    json_string(&mut out, artist)?;
                }
            }
            out.write_all(b"}")?;
        }
        out.write_all(b"]}")?;
    }
    out.write_all(b"]}")?;
    out.into_inner().map_err(|error| error.into_error())?.sync_all()?;
    Ok(playlists.len())
}

/// A number, or a string holding one, as the JVM's `JsonPrimitive.longOrNull` reads it.
fn long_of(value: Option<&Value>) -> Option<i64> {
    match value? {
        Value::Number(number) => number.as_i64(),
        Value::String(text) => text.parse().ok(),
        _ => None,
    }
}

fn objects<'a>(value: &'a Value, key: &str) -> impl Iterator<Item = &'a serde_json::Map<String, Value>> {
    value.get(key).and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_object)
}

pub fn read_backup(path: &Path) -> Result<PlaylistBackup, TransferError> {
    let file = File::open(path)?;
    let root: Value = serde_json::from_reader(BufReader::new(file)).map_err(|error| {
        if error.is_io() {
            TransferError::Io(error.into())
        } else {
            TransferError::Invalid(format!("not JSON: {error}"))
        }
    })?;
    if !root.is_object() {
        return Err(TransferError::Invalid("root is not an object".to_owned()));
    }
    if root.get("format").and_then(Value::as_str) != Some(BACKUP_FORMAT) {
        return Err(TransferError::Invalid(format!("format is not {BACKUP_FORMAT}")));
    }
    let version = long_of(root.get("version"))
        .filter(|version| i32::try_from(*version).is_ok())
        .ok_or_else(|| TransferError::Invalid("no version".to_owned()))?;
    if version > BACKUP_VERSION {
        return Err(TransferError::Newer(version));
    }
    let exported_at = long_of(root.get("exportedAt")).unwrap_or(0);
    let mut playlists = Vec::new();
    for playlist in objects(&root, "playlists") {
        let name = playlist
            .get("name")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .ok_or_else(|| TransferError::Invalid("playlist without a name".to_owned()))?;
        let items = playlist
            .get("items")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(Value::as_object)
            .filter_map(|item| {
                let code = item.get("code").and_then(Value::as_str).filter(|code| !code.is_empty())?;
                Some(PlaylistSnapshotItem {
                    code: code.to_owned(),
                    added_at: long_of(item.get("addedAt")).unwrap_or(exported_at),
                })
            })
            .collect();
        playlists.push(PlaylistSnapshot {
            name: name.to_owned(),
            created_at: long_of(playlist.get("createdAt")).unwrap_or(exported_at),
            items,
        });
    }
    Ok(PlaylistBackup { exported_at, playlists })
}

fn backup_file_name() -> String {
    format!("YMusic-playlists-{}.json", chrono::Utc::now().format("%Y-%m-%d"))
}

/// Runs `work` on a blocking worker, keeping its typed error.
fn blocking<T, F>(cx: &App, work: F) -> impl Future<Output = Result<T, TransferError>> + use<T, F>
where
    T: Send + 'static,
    F: FnOnce(&MediaStore) -> Result<T, TransferError> + Send + 'static,
{
    let store = cx.global::<AppGraph>().media.clone();
    let task = on_core(cx, async move { tokio::task::spawn_blocking(move || work(&store)).await });
    async move {
        match task.await {
            Some(Ok(result)) => result,
            Some(Err(error)) => Err(TransferError::Io(std::io::Error::other(error))),
            None => Err(TransferError::Stopped),
        }
    }
}

/// The code a failed job shows; a technical failure is recorded once under `code`.
fn classify(code: &'static str, what: &str, error: &TransferError, cx: &App) -> String {
    let log = cx.global::<AppGraph>().log.fork("playlist-transfer");
    match error.user_message() {
        Some(message) => {
            log.info(&format!("{what}: {error}"));
            message.to_owned()
        }
        None => {
            log.capture_error(&format!("{code} {what}"), error);
            tf(Str::ErrorGeneric, &[&code])
        }
    }
}

/// Backup and restore from the Playlists tab; one job at a time.
#[derive(Clone, Default)]
pub struct PlaylistTransfer {
    busy: Rc<Cell<bool>>,
}

impl PlaylistTransfer {
    pub fn busy(&self) -> bool {
        self.busy.get()
    }

    /// Writes the backup to `target`, or where the save panel says (nothing on cancel).
    pub fn backup(&self, target: Option<PathBuf>, window: &mut Window, cx: &mut App) {
        if self.busy.replace(true) {
            return;
        }
        let busy = self.busy.clone();
        let pick = target.is_none().then(|| {
            let dialog = rfd::AsyncFileDialog::new()
                .set_title(t(Str::PlaylistMenuBackup))
                .set_file_name(backup_file_name())
                .save_file();
            crate::desktop::file_dialog(dialog, cx)
        });
        window
            .spawn(cx, async move |cx| {
                let path = match (target, pick) {
                    (Some(path), _) => Some(path),
                    (None, Some(pick)) => pick.await.map(|handle| handle.path().to_path_buf()),
                    (None, None) => None,
                };
                let Some(path) = path else {
                    busy.set(false);
                    return;
                };
                let Ok(request) =
                    cx.update(|_, cx| blocking(cx, move |store| write_backup(store, &path, APP_VERSION_NAME)))
                else {
                    busy.set(false);
                    return;
                };
                let result = request.await;
                cx.update(|window, cx| {
                    busy.set(false);
                    let message = match result {
                        Ok(count) => plural(Plural::PlaylistBackupResult, count as i64, &[&count]),
                        Err(error) => classify("R90800", "playlist backup failed", &error, cx),
                    };
                    window.push_notification(message, cx);
                })
                .ok();
            })
            .detach();
    }

    /// Reads the backup at `source`, or the one the open panel picks, and previews it.
    pub fn open_restore(
        &self,
        source: Option<PathBuf>,
        user_playlists: usize,
        changed: Changed,
        window: &mut Window,
        cx: &mut App,
    ) {
        if self.busy.get() {
            return;
        }
        let transfer = self.clone();
        let pick = source.is_none().then(|| {
            let dialog =
                rfd::AsyncFileDialog::new().set_title(t(Str::PlaylistMenuRestore)).add_filter("JSON", &["json"]).pick_file();
            crate::desktop::file_dialog(dialog, cx)
        });
        window
            .spawn(cx, async move |cx| {
                let path = match (source, pick) {
                    (Some(path), _) => Some(path),
                    (None, Some(pick)) => pick.await.map(|handle| handle.path().to_path_buf()),
                    (None, None) => None,
                };
                let Some(path) = path else { return };
                let Ok(request) = cx.update(|_, cx| blocking(cx, move |_| read_backup(&path))) else { return };
                let result = request.await;
                cx.update(|window, cx| match result {
                    Ok(backup) => RestorePlaylists::open(backup, user_playlists, transfer, changed, window, cx),
                    Err(error) => {
                        let message = classify("R90801", "playlist backup failed to read", &error, cx);
                        restore_failed(message, window, cx);
                    }
                })
                .ok();
            })
            .detach();
    }
}

/// "Can't restore": why, and OK.
fn restore_failed(message: String, window: &mut Window, cx: &mut App) {
    let focus = Rc::new(FocusList::modal(None, cx));
    let first = focus.handle(0, cx);
    window.open_dialog(cx, move |dialog, window, cx| {
        let ok = focus.button(
            0,
            text_button("restore-failed-ok", t(Str::DialogOk), true, true, cx)
                .on_click(|_, window, cx| window.close_dialog(cx)),
            cx,
        );
        chrome(dialog, elevated_dialog::DEFAULT_WIDTH, window).on_ok(|_, _, _| true).child(
            v_flex()
                .w_full()
                .child(title_bar(t(Str::PlaylistRestoreFailedTitle), cx))
                .child(
                    div()
                        .id("restore-failed-message")
                        .role(Role::Alert)
                        .aria_label(message.clone())
                        .w_full()
                        .px(px(24.))
                        .py(px(16.))
                        .text_size(px(14.))
                        .line_height(px(20.))
                        .text_color(cx.theme().foreground)
                        .child(message.clone()),
                )
                .child(button_bar(ok, cx)),
        )
    });
    window.defer(cx, move |window, cx| window.focus(&first, cx));
}

const STOP_MERGE: usize = 0;
const STOP_REPLACE: usize = 1;
const STOP_CANCEL: usize = 2;
const STOP_RESTORE: usize = 3;

/// The backup's summary and Merge / Replace; Restore writes it.
struct RestorePlaylists {
    backup: PlaylistBackup,
    user_playlists: usize,
    replace_all: bool,
    transfer: PlaylistTransfer,
    changed: Changed,
    focus: FocusList,
    focused_first: bool,
}

impl RestorePlaylists {
    fn open(
        backup: PlaylistBackup,
        user_playlists: usize,
        transfer: PlaylistTransfer,
        changed: Changed,
        window: &mut Window,
        cx: &mut App,
    ) {
        let view = cx.new(|cx| Self {
            backup,
            user_playlists,
            replace_all: false,
            transfer,
            changed,
            focus: FocusList::modal(None, cx),
            focused_first: false,
        });
        window.open_dialog(cx, move |dialog, window, cx| {
            let busy = view.read(cx).transfer.busy.clone();
            let enter = view.downgrade();
            chrome(dialog, elevated_dialog::DEFAULT_WIDTH, window)
                .on_ok(move |_, window, cx| {
                    enter.update(cx, |view, cx| view.activate_focused(window, cx)).ok();
                    false
                })
                .on_cancel(move |_, _, _| !busy.get())
                .child(view.clone())
        });
    }

    /// The dialog's Enter presses the focused button; a focused row takes its own.
    fn activate_focused(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.focus.handle(STOP_CANCEL, cx).is_focused(window) && !self.transfer.busy() {
            window.close_dialog(cx);
        } else if self.focus.handle(STOP_RESTORE, cx).is_focused(window) {
            self.restore(window, cx);
        }
    }

    fn set_mode(&mut self, replace_all: bool, cx: &mut Context<Self>) {
        if !self.transfer.busy() {
            self.replace_all = replace_all;
            cx.notify();
        }
    }

    fn restore(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.transfer.busy.replace(true) {
            return;
        }
        let (snapshots, replace_all) = (self.backup.playlists.clone(), self.replace_all);
        let count = snapshots.len();
        let request = blocking(cx, move |store| Ok(store.restore_playlists(&snapshots, replace_all)?));
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.transfer.busy.set(false);
                match result {
                    Ok(()) => {
                        window.close_dialog(cx);
                        (view.changed)(window, cx);
                        window.push_notification(plural(Plural::PlaylistRestoreResult, count as i64, &[&count]), cx);
                    }
                    Err(error) => {
                        let message = classify("R90802", "playlist restore failed", &error, cx);
                        window.push_notification(message, cx);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn mode_row(&self, stop: usize, replace_all: bool, cx: &mut Context<Self>) -> Stateful<Div> {
        let theme = cx.theme().clone();
        let selected = self.replace_all == replace_all;
        let busy = self.transfer.busy();
        let accent = if replace_all { theme.danger } else { theme.primary };
        let (title, description) = if replace_all {
            let description = if self.user_playlists > 0 {
                plural(Plural::PlaylistRestoreReplaceDesc, self.user_playlists as i64, &[&self.user_playlists])
            } else {
                t(Str::PlaylistRestoreReplaceDescNone).to_owned()
            };
            (t(Str::PlaylistRestoreReplace), description)
        } else {
            (t(Str::PlaylistRestoreMerge), t(Str::PlaylistRestoreMergeDesc).to_owned())
        };
        let row = h_flex()
            .id(("restore-mode", stop))
            .role(Role::RadioButton)
            .aria_label(format!("{title}, {description}"))
            .aria_toggled(if selected { Toggled::True } else { Toggled::False })
            .w_full()
            .px(px(16.))
            .py(px(8.))
            .gap(px(8.))
            .items_start()
            .when(!busy, |row| row.cursor_pointer().hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA))))
            .child(div().pt(px(2.)).child(Radio::new(("restore-radio", stop)).checked(selected).tab_stop(false)))
            .child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .child(
                        div()
                            .text_size(px(14.))
                            .font_medium()
                            .text_color(if selected { accent } else { theme.foreground })
                            .child(title),
                    )
                    .child(div().text_size(px(12.5)).text_color(theme.muted_foreground).child(description)),
            )
            .on_click(cx.listener(move |view, _, _, cx| view.set_mode(replace_all, cx)))
            .on_action(
                cx.listener(move |view, _: &gpui_kit::base::actions::Confirm, _, cx| view.set_mode(replace_all, cx)),
            );
        self.focus.item(stop, row, cx)
    }
}

impl Render for RestorePlaylists {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(STOP_MERGE, cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let theme = cx.theme().clone();
        let busy = self.transfer.busy();
        let songs = self.backup.song_count();
        let playlists = self.backup.playlists.len();
        let summary = [
            Some(plural(Plural::NPlaylists, playlists as i64, &[&playlists])),
            Some(plural(Plural::NSongs, songs as i64, &[&songs])),
            (self.backup.exported_at > 0).then(|| crate::downloads_view::short_date(self.backup.exported_at)).flatten(),
        ]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" · ");
        let confirm_label =
            if self.replace_all { t(Str::PlaylistRestoreReplace) } else { t(Str::PlaylistRestoreConfirm) };
        let mut confirm = text_button("restore-confirm", confirm_label, true, !busy, cx)
            .disabled(busy)
            .on_click(cx.listener(|view, _, window, cx| view.restore(window, cx)));
        if self.replace_all && !busy {
            confirm = confirm.text_color(theme.danger);
        }
        let buttons = h_flex()
            .child(
                self.focus.button(
                    STOP_CANCEL,
                    text_button("restore-cancel", t(Str::DialogCancel), false, !busy, cx)
                        .disabled(busy)
                        .on_click(|_, window, cx| window.close_dialog(cx)),
                    cx,
                ),
            )
            .child(self.focus.button(STOP_RESTORE, confirm, cx));
        let body = v_flex()
            .w_full()
            .py(px(8.))
            .child(div().px(px(24.)).py(px(8.)).text_size(px(14.)).text_color(theme.foreground).child(summary))
            .child(
                v_flex()
                    .id("restore-modes")
                    .role(Role::RadioGroup)
                    .child(self.mode_row(STOP_MERGE, false, cx))
                    .child(self.mode_row(STOP_REPLACE, true, cx)),
            );
        let content = v_flex()
            .w_full()
            .child(title_bar(t(Str::PlaylistRestoreTitle), cx))
            .child(body)
            .child(button_bar(buttons, cx));
        self.focus.container(div().w_full(), STOP_MERGE..STOP_RESTORE + 1, cx).child(content)
    }
}
