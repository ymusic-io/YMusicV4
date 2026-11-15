//! Local playlists' dialogs (JVM `LibraryScreen.kt` / `ArrangePlaylistDialog.kt`): add songs to a
//! playlist, name a new one or rename it, the delete confirms (playlist, file on disk) and Arrange
//! songs, which edits a working copy with undo and writes it on Save.

use std::rc::Rc;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::Button;
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, Disableable, Icon, IconName, Sizable, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};
use ym_store::{MediaItem, MediaStore, Playlist, PlaylistItemDetail};

use crate::a11y::FocusList;
use crate::action_icon::{HIT, sized_action_icon};
use crate::elevated_dialog::{
    ConfirmDialog, DEFAULT_WIDTH, ROW_HORIZONTAL, ROW_VERTICAL, bar_spinner, button_bar, chrome, message, press,
    scroll_body, text_button, title_bar, title_bar_with,
};
use crate::graph::{AppGraph, on_core};
use crate::reorder::{Reorder, ReorderEvent};
use crate::settings_view::HOVER_ALPHA;
use crate::spinner::Spinner;

/// Runs after a dialog changed the library, so the opener reloads.
pub type Changed = Rc<dyn Fn(&mut Window, &mut App)>;

/// Runs `work` against the media store on a blocking worker.
pub fn store_call<T, E, F>(cx: &App, work: F) -> impl Future<Output = Result<T, String>> + use<T, E, F>
where
    T: Send + 'static,
    E: std::fmt::Display + Send + 'static,
    F: FnOnce(&MediaStore) -> Result<T, E> + Send + 'static,
{
    let store = cx.global::<AppGraph>().media.clone();
    let task = on_core(cx, async move { tokio::task::spawn_blocking(move || work(&store)).await });
    async move {
        match task.await {
            Some(Ok(Ok(value))) => Ok(value),
            Some(Ok(Err(error))) => Err(error.to_string()),
            Some(Err(error)) => Err(error.to_string()),
            None => Err("the core runtime stopped".to_owned()),
        }
    }
}

/// Logs a failed library change and tells the user its code.
pub fn report(code: &'static str, what: &str, error: &str, window: &mut Window, cx: &mut App) {
    cx.global::<AppGraph>().log.fork("library").warn(&format!("{code} {what}: {error}"), None);
    window.push_notification(tf(Str::ErrorGeneric, &[&code]), cx);
}

fn notify_changed(changed: &Option<Changed>, window: &mut Window, cx: &mut App) {
    if let Some(changed) = changed {
        changed(window, cx);
    }
}

fn pick_row(id: impl Into<ElementId>, icon: Icon, label: String, accent: bool, cx: &App) -> Stateful<Div> {
    let theme = cx.theme();
    h_flex()
        .id(id)
        .role(Role::Button)
        .aria_label(label.clone())
        .w_full()
        .gap_4()
        .px(px(ROW_HORIZONTAL))
        .py(px(ROW_VERTICAL))
        .items_center()
        .cursor_pointer()
        .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
        .text_color(if accent { theme.primary } else { theme.foreground })
        .child(icon.size(px(22.)))
        .child(div().flex_1().min_w_0().truncate().text_size(px(14.)).child(label))
}

/// The user's playlists plus New playlist…; picking one appends `codes` and closes.
pub struct AddToLocalPlaylist {
    codes: Vec<String>,
    playlists: Option<Vec<Playlist>>,
    failed: bool,
    changed: Option<Changed>,
    focus: FocusList,
    scroll: ScrollHandle,
    focused_first: bool,
    busy: bool,
}

impl AddToLocalPlaylist {
    pub fn open(codes: Vec<String>, changed: Option<Changed>, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| {
            let scroll = ScrollHandle::new();
            let mut view = Self {
                codes,
                playlists: None,
                failed: false,
                changed,
                focus: FocusList::modal(Some(scroll.clone()), cx),
                scroll,
                focused_first: false,
                busy: false,
            };
            view.load(cx);
            view
        });
        window.open_dialog(cx, move |dialog, window, _| {
            let confirm = view.clone();
            chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()).on_ok(move |_, window, cx| {
                confirm.update(cx, |view, cx| view.activate_focused(window, cx));
                false
            })
        });
    }

    /// The dialog's Enter: picks the focused row.
    fn activate_focused(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if let Some(row) = (0..self.focus_count()).find(|row| self.focus.handle(*row, cx).is_focused(window)) {
            self.activate(row, window, cx);
        }
    }

    /// Row 0 is New playlist…, then the playlists in order.
    fn activate(&mut self, row: usize, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(playlists) = self.playlists.clone() else { return };
        match row.checked_sub(1) {
            None => {
                window.close_dialog(cx);
                let names = playlists.into_iter().map(|playlist| playlist.name).collect();
                let mode = NameMode::Create { codes: self.codes.clone(), name: String::new() };
                PlaylistNameDialog::open(mode, names, self.changed.clone(), window, cx);
            }
            Some(index) => {
                if let Some(playlist) = playlists.get(index) {
                    self.add(playlist.id, window, cx);
                }
            }
        }
    }

    fn load(&mut self, cx: &mut Context<Self>) {
        let request = store_call(cx, |store| store.playlists());
        cx.spawn(async move |this, cx| {
            let result = request.await;
            this.update(cx, |view, cx| {
                match result {
                    Ok(playlists) => view.playlists = Some(playlists),
                    Err(error) => {
                        cx.global::<AppGraph>()
                            .log
                            .fork("library")
                            .warn(&format!("R94000 playlists read failed: {error}"), None);
                        view.failed = true;
                        view.playlists = Some(Vec::new());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn add(&mut self, playlist_id: i64, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.busy = true;
        let codes = self.codes.clone();
        let request = store_call(cx, move |store| store.add_codes_to_playlist(playlist_id, &codes));
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match result {
                    Ok(()) => {
                        window.close_dialog(cx);
                        notify_changed(&view.changed, window, cx);
                    }
                    Err(error) => report("R94001", "adding to a playlist failed", &error, window, cx),
                }
            })
            .ok();
        })
        .detach();
    }
}

impl Render for AddToLocalPlaylist {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let count = self.focus_count();
        let body = match self.playlists.clone() {
            None => h_flex().w_full().justify_center().py_6().child(Spinner::new()).into_any_element(),
            Some(_) if self.failed => {
                message(tf(Str::ErrorGeneric, &[&"R94000"]), cx).text_color(theme.muted_foreground).into_any_element()
            }
            Some(playlists) => {
                if !self.focused_first {
                    self.focused_first = true;
                    let first = self.focus.handle(0, cx);
                    window.defer(cx, move |window, cx| window.focus(&first, cx));
                }
                let new_row = pick_row(
                    "new-local-playlist",
                    Icon::new(IconName::Plus),
                    t(Str::AddToPlaylistNew).to_owned(),
                    true,
                    cx,
                )
                .on_click(cx.listener(|view, _, window, cx| view.activate(0, window, cx)));
                let mut list = v_flex().w_full().child(self.focus.item(0, new_row, cx));
                for (index, playlist) in playlists.into_iter().enumerate() {
                    let row = pick_row(
                        ("local-playlist", index),
                        Icon::new(Lucide::ListMusic),
                        playlist.name.clone(),
                        false,
                        cx,
                    )
                    .on_click(cx.listener(move |view, _, window, cx| view.activate(index + 1, window, cx)));
                    list = list.child(self.focus.item(index + 1, row, cx));
                }
                scroll_body("local-playlists", &self.scroll, list, window, cx).into_any_element()
            }
        };
        let cancel = press(
            &self.focus,
            count,
            text_button("local-playlist-cancel", t(Str::DialogCancel), false, true, cx),
            true,
            |_, window, cx| window.close_dialog(cx),
            cx,
        );
        self.focus
            .container(v_flex().w_full(), 0..count + 1, cx)
            .child(title_bar(t(Str::CdAddToPlaylist), cx))
            .child(body)
            .child(button_bar(cancel, cx))
    }
}

impl AddToLocalPlaylist {
    fn focus_count(&self) -> usize {
        self.playlists.as_ref().map_or(0, Vec::len) + 1
    }
}

pub enum NameMode {
    /// A new playlist holding `codes`, its name field starting at `name`.
    Create {
        codes: Vec<String>,
        name: String,
    },
    Rename(Playlist),
}

/// Create or rename: confirm stays off while the name is blank or taken (case-insensitive).
pub struct PlaylistNameDialog {
    mode: NameMode,
    existing: Vec<String>,
    name: Entity<InputState>,
    changed: Option<Changed>,
    busy: bool,
    focused: bool,
    focus: FocusList,
    _subscription: Subscription,
}

impl PlaylistNameDialog {
    pub fn open(mode: NameMode, existing: Vec<String>, changed: Option<Changed>, window: &mut Window, cx: &mut App) {
        let initial = match &mode {
            NameMode::Create { name, .. } => name.clone(),
            NameMode::Rename(playlist) => playlist.name.clone(),
        };
        let existing = match &mode {
            NameMode::Rename(playlist) => {
                let own = playlist.name.to_lowercase();
                existing.into_iter().filter(|name| name.to_lowercase() != own).collect()
            }
            NameMode::Create { .. } => existing,
        };
        let view = cx.new(|cx| {
            let name =
                cx.new(|cx| InputState::new(window, cx).placeholder(t(Str::PlaylistNameHint)).default_value(initial));
            let subscription = cx.subscribe_in(&name, window, |view: &mut Self, _, event: &InputEvent, window, cx| {
                if matches!(event, InputEvent::PressEnter { .. }) {
                    view.submit(window, cx);
                }
                cx.notify();
            });
            Self {
                mode,
                existing,
                name,
                changed,
                busy: false,
                focused: false,
                focus: FocusList::modal(None, cx),
                _subscription: subscription,
            }
        });
        window.open_dialog(cx, move |dialog, window, _| {
            let submit = view.clone();
            chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()).on_ok(move |_, window, cx| {
                submit.update(cx, |view, cx| view.submit(window, cx));
                false
            })
        });
    }

    fn trimmed(&self, cx: &App) -> String {
        self.name.read(cx).value().trim().to_owned()
    }

    fn duplicate(&self, cx: &App) -> bool {
        let name = self.trimmed(cx).to_lowercase();
        !name.is_empty() && self.existing.iter().any(|existing| existing.to_lowercase() == name)
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let name = self.trimmed(cx);
        if self.busy || name.is_empty() || self.duplicate(cx) {
            return;
        }
        self.busy = true;
        let target = match &self.mode {
            NameMode::Create { codes, .. } => Ok(codes.clone()),
            NameMode::Rename(playlist) => Err(playlist.id),
        };
        let request = store_call(cx, move |store| match target {
            Ok(codes) => {
                let playlist = store.create_playlist(&name)?;
                store.add_codes_to_playlist(playlist.id, &codes)
            }
            Err(id) => store.rename_playlist(id, &name),
        });
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match result {
                    Ok(()) => {
                        window.close_dialog(cx);
                        notify_changed(&view.changed, window, cx);
                    }
                    Err(error) => report("R94001", "saving a playlist failed", &error, window, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

impl Render for PlaylistNameDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused {
            self.focused = true;
            let name = self.name.clone();
            window.defer(cx, move |window, cx| {
                name.update(cx, |input, cx| {
                    input.focus(window, cx);
                    let end = input.value().len();
                    input.set_selected_range(end..end, cx);
                })
            });
        }
        let theme = cx.theme().clone();
        let duplicate = self.duplicate(cx);
        let can_submit = !self.busy && !self.trimmed(cx).is_empty() && !duplicate;
        let (title, confirm) = match self.mode {
            NameMode::Create { .. } => (Str::PlaylistCreateTitle, Str::PlaylistCreateConfirm),
            NameMode::Rename(_) => (Str::PlaylistRenameTitle, Str::PlaylistRenameConfirm),
        };
        let field = Input::new(&self.name)
            .aria_label(t(Str::PlaylistNameHint))
            .when(duplicate, |input| input.border_color(theme.danger));
        let submit = if self.busy {
            bar_spinner()
        } else {
            press(
                &self.focus,
                2,
                text_button("playlist-name-confirm", t(confirm), true, can_submit, cx),
                can_submit,
                cx.listener(|view, _, window, cx| view.submit(window, cx)),
                cx,
            )
        };
        let buttons = h_flex()
            .child(press(
                &self.focus,
                1,
                text_button("playlist-name-cancel", t(Str::DialogCancel), false, !self.busy, cx),
                !self.busy,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(submit);
        v_flex()
            .w_full()
            .child(title_bar(t(title), cx))
            .child(
                v_flex()
                    .w_full()
                    .px(px(ROW_HORIZONTAL))
                    .py_2()
                    .gap_1()
                    .child(self.focus.field(0, &self.name.focus_handle(cx), field, cx))
                    .when(duplicate, |column| {
                        column.child(
                            div()
                                .id("playlist-name-taken")
                                .px_4()
                                .text_size(px(12.))
                                .text_color(theme.danger)
                                .role(Role::Alert)
                                .aria_label(t(Str::PlaylistNameExists))
                                .child(t(Str::PlaylistNameExists)),
                        )
                    }),
            )
            .child(button_bar(buttons, cx))
    }
}

/// "Delete playlist?": the playlist goes, its songs stay in the library.
pub fn confirm_delete_playlist(playlist: Playlist, changed: Option<Changed>, window: &mut Window, cx: &mut App) {
    let id = playlist.id;
    ConfirmDialog::new(
        t(Str::PlaylistDeleteTitle),
        tf(Str::PlaylistDeleteMessage, &[&playlist.name]),
        t(Str::DeleteFromDiskConfirm),
    )
    .destructive()
    .open(window, cx, move |window, cx| {
        let changed = changed.clone();
        let request = store_call(cx, move |store| store.delete_playlist(id));
        window
            .spawn(cx, async move |cx| {
                let result = request.await;
                cx.update(|window, cx| match result {
                    Ok(()) => notify_changed(&changed, window, cx),
                    Err(error) => report("R94001", "deleting a playlist failed", &error, window, cx),
                })
                .ok();
            })
            .detach();
    });
}

/// "Delete file?": the file is removed from disk (one already gone counts as removed), then its row.
pub fn confirm_delete_from_disk(item: MediaItem, changed: Option<Changed>, window: &mut Window, cx: &mut App) {
    let (item_id, path) = (item.id, std::path::PathBuf::from(&item.uri));
    ConfirmDialog::new(
        t(Str::DeleteFromDiskTitle),
        tf(Str::DeleteFromDiskMessage, &[&item.title]),
        t(Str::DeleteFromDiskConfirm),
    )
    .destructive()
    .open(window, cx, move |window, cx| {
        let (changed, path) = (changed.clone(), path.clone());
        let request = store_call(cx, move |store| {
            match std::fs::remove_file(&path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(format!("{}: {error}", path.display())),
            }
            store.delete_item(item_id).map_err(|error| error.to_string())
        });
        window
            .spawn(cx, async move |cx| {
                let result = request.await;
                cx.update(|window, cx| match result {
                    Ok(()) => notify_changed(&changed, window, cx),
                    Err(error) => report("R94003", "deleting a file from disk failed", &error, window, cx),
                })
                .ok();
            })
            .detach();
    });
}

/// The working copy: the first `stack` rows are the stack Move to top builds, and a move across its
/// edge grows or shrinks it. Every edit but a stack reset can be undone.
struct ArrangeState {
    original: Vec<String>,
    items: Vec<PlaylistItemDetail>,
    stack: usize,
    history: Vec<(Vec<PlaylistItemDetail>, usize)>,
    /// The rows and stack as a drag found them: the drop records them as one edit.
    drag_start: Option<(Vec<PlaylistItemDetail>, usize)>,
}

impl ArrangeState {
    fn record(&mut self) {
        self.history.push((self.items.clone(), self.stack));
    }

    fn move_to_top(&mut self, index: usize) {
        if index < self.stack || index >= self.items.len() {
            return;
        }
        self.record();
        let item = self.items.remove(index);
        self.items.insert(self.stack, item);
        self.stack += 1;
    }

    fn remove(&mut self, index: usize) {
        if index >= self.items.len() {
            return;
        }
        self.record();
        self.items.remove(index);
        if index < self.stack {
            self.stack -= 1;
        }
    }

    fn move_item(&mut self, from: usize, to: usize) {
        if from == to || from >= self.items.len() || to >= self.items.len() {
            return;
        }
        self.record();
        let item = self.items.remove(from);
        self.items.insert(to, item);
        self.cross_stack_edge(from, to);
    }

    /// A row moved from `from` to `to` leaves the stack when it lands below its edge and joins it
    /// when it lands above.
    fn cross_stack_edge(&mut self, from: usize, to: usize) {
        if from < self.stack && to >= self.stack {
            self.stack -= 1;
        } else if from >= self.stack && to < self.stack {
            self.stack += 1;
        }
    }

    fn drag_started(&mut self) {
        self.drag_start = Some((self.items.clone(), self.stack));
    }

    fn drag_moved(&mut self, from: usize, to: usize) {
        if from < self.items.len() && to < self.items.len() {
            let item = self.items.remove(from);
            self.items.insert(to, item);
        }
    }

    /// The drop: one undoable edit, the stack judged against its size before the drag.
    fn drag_ended(&mut self, from: usize, to: usize) {
        let Some(start) = self.drag_start.take() else { return };
        if from != to {
            self.history.push(start);
            self.cross_stack_edge(from, to);
        }
    }

    fn undo(&mut self) {
        if let Some((items, stack)) = self.history.pop() {
            self.items = items;
            self.stack = stack;
        }
    }

    fn removed_codes(&self) -> Vec<String> {
        let kept: std::collections::HashSet<&String> = self.items.iter().map(|item| &item.code).collect();
        self.original.iter().filter(|code| !kept.contains(code)).cloned().collect()
    }
}

/// Reorder (drag the handle, Alt+Up/Down, Move to top) and remove a playlist's songs; nothing is
/// written until Save.
pub struct ArrangePlaylist {
    playlist_id: i64,
    state: ArrangeState,
    changed: Option<Changed>,
    busy: bool,
    /// The footer and stack chip at `CONTROL_*`, then per row its item, Move to top and Remove.
    focus: FocusList,
    scroll: ScrollHandle,
    reorder: Reorder,
    focused_first: bool,
}

const ARRANGE_ICON: f32 = 22.;
const CONTROL_UNDO: usize = 0;
const CONTROL_CANCEL: usize = 1;
const CONTROL_SAVE: usize = 2;
const CONTROL_STACK: usize = 3;
const CONTROL_ROWS: usize = 4;

fn row_stop(index: usize) -> usize {
    CONTROL_ROWS + index * 3
}

impl ArrangePlaylist {
    pub fn open(
        playlist_id: i64,
        items: Vec<PlaylistItemDetail>,
        changed: Option<Changed>,
        window: &mut Window,
        cx: &mut App,
    ) {
        let view = cx.new(|cx| {
            let scroll = ScrollHandle::new();
            Self {
                playlist_id,
                state: ArrangeState {
                    original: items.iter().map(|item| item.code.clone()).collect(),
                    items,
                    stack: 0,
                    history: Vec::new(),
                    drag_start: None,
                },
                changed,
                busy: false,
                focus: FocusList::modal(Some(scroll.clone()), cx),
                reorder: Reorder::new(scroll.clone()),
                scroll,
                focused_first: false,
            }
        });
        window.open_dialog(cx, move |dialog, window, _| {
            let confirm = view.clone();
            chrome(dialog, DEFAULT_WIDTH, window).overlay_closable(false).child(view.clone()).on_ok(
                move |_, window, cx| {
                    confirm.update(cx, |view, cx| view.activate_focused(window, cx));
                    false
                },
            )
        });
    }

    /// The dialog's Enter: presses the focused control; on a row it does nothing.
    fn activate_focused(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let count = row_stop(self.state.items.len());
        let Some(control) = (0..count).find(|control| self.focus.handle(*control, cx).is_focused(window)) else {
            return;
        };
        match control {
            CONTROL_UNDO => self.edit(cx, ArrangeState::undo),
            CONTROL_CANCEL if !self.busy => window.close_dialog(cx),
            CONTROL_SAVE => self.save(window, cx),
            CONTROL_STACK => self.edit(cx, |state| state.stack = 0),
            CONTROL_CANCEL => {}
            row_control => {
                let index = (row_control - CONTROL_ROWS) / 3;
                match (row_control - CONTROL_ROWS) % 3 {
                    1 => self.move_to_top(index, cx),
                    2 => self.edit(cx, |state| state.remove(index)),
                    _ => {}
                }
            }
        }
    }

    fn start_drag(&mut self, index: usize, pointer_y: Pixels, window: &mut Window, cx: &mut Context<Self>) {
        if !self.busy {
            self.state.drag_started();
            self.reorder.start(index, pointer_y, window, cx);
        }
    }

    fn edit(&mut self, cx: &mut Context<Self>, change: impl FnOnce(&mut ArrangeState)) {
        if !self.busy {
            change(&mut self.state);
            cx.notify();
        }
    }

    /// Moves a row with the keyboard and keeps focus on it.
    fn shift(&mut self, index: usize, down: bool, window: &mut Window, cx: &mut Context<Self>) {
        let target = if down { index + 1 } else { index.wrapping_sub(1) };
        if target >= self.state.items.len() {
            return;
        }
        if !self.busy {
            self.reorder.moved(index, target, cx);
        }
        self.edit(cx, |state| state.move_item(index, target));
        let handle = self.focus.handle(row_stop(target), cx);
        window.focus(&handle, cx);
    }

    fn move_to_top(&mut self, index: usize, cx: &mut Context<Self>) {
        if !self.busy && index >= self.state.stack {
            self.reorder.moved(index, self.state.stack, cx);
        }
        self.edit(cx, |state| state.move_to_top(index));
    }

    fn save(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.busy = true;
        let playlist_id = self.playlist_id;
        let removed = self.state.removed_codes();
        let arranged: Vec<String> = self.state.items.iter().map(|item| item.code.clone()).collect();
        let request = store_call(cx, move |store| {
            for code in &removed {
                store.remove_from_playlist(playlist_id, code)?;
            }
            let shown: std::collections::HashSet<&String> = arranged.iter().collect();
            let unresolved: Vec<String> =
                store.playlist_codes(playlist_id)?.into_iter().filter(|code| !shown.contains(code)).collect();
            let order: Vec<String> = arranged.iter().cloned().chain(unresolved).collect();
            store.reorder_playlist(playlist_id, &order)
        });
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match result {
                    Ok(()) => {
                        window.close_dialog(cx);
                        notify_changed(&view.changed, window, cx);
                    }
                    Err(error) => {
                        cx.global::<AppGraph>().log.fork("arrange-playlist").warn(
                            &format!("R94002 saving the arrangement of playlist {playlist_id} failed: {error}"),
                            None,
                        );
                        window.push_notification(tf(Str::ErrorGeneric, &[&"R94002"]), cx);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn row(&self, index: usize, item: &PlaylistItemDetail, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let song = &item.item;
        let stacked = index < self.state.stack;
        let detail = [song.artist.clone(), song.album.clone()].into_iter().flatten().collect::<Vec<_>>().join(" · ");
        let handle = div()
            .id(("arrange-handle", index))
            .size(px(40.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .justify_center()
            .cursor_grab()
            .tooltip(|window, cx| gpui_kit::component::tooltip::Tooltip::new(t(Str::CdDragToReorder)).build(window, cx))
            .child(Icon::new(Lucide::GripVertical).size(px(20.)).text_color(theme.muted_foreground))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |view, event: &MouseDownEvent, window, cx| {
                    cx.stop_propagation();
                    view.start_drag(index, event.position.y, window, cx);
                }),
            );
        let thumb = div()
            .relative()
            .size(px(44.))
            .flex_shrink_0()
            .child(
                div()
                    .size_full()
                    .rounded(px(6.))
                    .overflow_hidden()
                    .bg(theme.secondary)
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(crate::library::art_or_icon(
                        Lucide::Music,
                        crate::local_art::art_url(song, crate::local_art::ROW_PX),
                        px(18.),
                        6.,
                        cx,
                    )),
            )
            .when(stacked, |thumb| {
                thumb.child(
                    div()
                        .absolute()
                        .top(px(-4.))
                        .left(px(-4.))
                        .size(px(18.))
                        .rounded_full()
                        .bg(theme.primary)
                        .text_color(theme.primary_foreground)
                        .text_size(px(11.))
                        .font_weight(FontWeight::BOLD)
                        .flex()
                        .items_center()
                        .justify_center()
                        .child((index + 1).to_string()),
                )
            });
        let top =
            sized_action_icon(("arrange-top", index), Lucide::ArrowUpToLine, t(Str::CdMoveToTop), ARRANGE_ICON, HIT)
                .disabled(stacked || self.busy)
                .on_click(cx.listener(move |view, _, _, cx| view.move_to_top(index, cx)));
        let remove = sized_action_icon(
            ("arrange-remove", index),
            Lucide::CircleMinus,
            t(Str::MenuRemoveFromPlaylist),
            ARRANGE_ICON,
            HIT,
        )
        .disabled(self.busy)
        .on_click(cx.listener(move |view, _, _, cx| view.edit(cx, |state| state.remove(index))));
        let label = [Some(song.title.clone()), (!detail.is_empty()).then(|| detail.clone())]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(", ");
        let stop = row_stop(index);
        let row = crate::a11y::list_item(h_flex().id(("arrange-row", index)), index)
            .aria_label(label)
            .w_full()
            .gap_3()
            .px_2()
            .py_1()
            .items_center()
            .when(stacked, |row| row.bg(theme.primary.opacity(0.08)))
            .when(self.reorder.lifted() == Some(index), |row| crate::reorder::lift(row, cx))
            .on_key_down(cx.listener(move |view, event: &KeyDownEvent, window, cx| {
                let keystroke = &event.keystroke;
                if keystroke.modifiers.alt && matches!(keystroke.key.as_str(), "up" | "down") {
                    view.shift(index, keystroke.key == "down", window, cx);
                    cx.stop_propagation();
                }
            }))
            .child(handle)
            .child(thumb)
            .child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .pl_1()
                    .child(div().text_sm().truncate().child(song.title.clone()))
                    .when(!detail.is_empty(), |column| {
                        column.child(div().text_xs().text_color(theme.muted_foreground).truncate().child(detail))
                    }),
            )
            .child(self.focus.button(stop + 1, top, cx))
            .child(self.focus.button(stop + 2, remove, cx));
        self.reorder.row(index, self.focus.item(stop, row, cx)).into_any_element()
    }
}

impl Render for ArrangePlaylist {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused_first && !self.state.items.is_empty() {
            self.focused_first = true;
            let first = self.focus.handle(row_stop(0), cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let items = self.state.items.clone();
        let rows: Vec<AnyElement> = items.iter().enumerate().map(|(index, item)| self.row(index, item, cx)).collect();
        let stacking = (self.state.stack > 0).then(|| {
            let label = tf(Str::ArrangeStacking, &[&self.state.stack]);
            let chip = Button::new("arrange-reset-stacking")
                .small()
                .outline()
                .rounded_full()
                .label(label.clone())
                .icon(IconName::Close)
                .accessibility_label(format!("{label}, {}", t(Str::CdResetStacking)))
                .tooltip(t(Str::CdResetStacking))
                .on_click(cx.listener(|view, _, _, cx| view.edit(cx, |state| state.stack = 0)));
            self.focus.button(CONTROL_STACK, chip, cx)
        });
        let tracker = self.reorder.tracker(
            items.len(),
            cx.listener(|view, event: &ReorderEvent, _, cx| {
                match *event {
                    ReorderEvent::Moved { from, to } => view.state.drag_moved(from, to),
                    ReorderEvent::Dropped { from, to } => view.state.drag_ended(from, to),
                }
                cx.notify();
            }),
        );
        let list = v_flex()
            .id("arrange-list")
            .role(Role::List)
            .aria_label(t(Str::ArrangeSongs))
            .aria_size_of_set(items.len())
            .w_full()
            .py_1()
            .gap_1()
            .children(rows)
            .child(tracker);
        let can_undo = !self.busy && !self.state.history.is_empty();
        let footer = h_flex()
            .w_full()
            .child(
                self.focus.button(
                    CONTROL_UNDO,
                    text_button("arrange-undo", t(Str::DialogUndo), false, can_undo, cx)
                        .disabled(!can_undo)
                        .on_click(cx.listener(|view, _, _, cx| view.edit(cx, ArrangeState::undo))),
                    cx,
                ),
            )
            .child(div().flex_1())
            .child(
                self.focus.button(
                    CONTROL_CANCEL,
                    text_button("arrange-cancel", t(Str::DialogCancel), false, !self.busy, cx)
                        .disabled(self.busy)
                        .on_click(|_, window, cx| window.close_dialog(cx)),
                    cx,
                ),
            )
            .child(if self.busy {
                bar_spinner()
            } else {
                self.focus.button(
                    CONTROL_SAVE,
                    text_button("arrange-save", t(Str::DialogSave), true, true, cx)
                        .on_click(cx.listener(|view, _, window, cx| view.save(window, cx))),
                    cx,
                )
            });
        v_flex()
            .w_full()
            .child(title_bar_with(t(Str::ArrangeSongs), stacking, cx))
            .child(scroll_body("arrange-rows", &self.scroll, list, window, cx))
            .child(button_bar(footer, cx))
    }
}
