//! A list's multi-select (the download manager, the Library's Songs tab), as the JVM app's
//! `MultiSelection`: a long press or the item's ⋮ → Select starts it, a click toggles, the item's ⋮
//! gives way to a checkbox, and a bar of close · "N selected" · Select all · actions stands in for
//! the list's own bar while anything is selected.

use std::collections::HashSet;
use std::hash::Hash;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use gpui_kit::component::{ActiveTheme, Icon, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Plural, Str, plural, t, tf};

use crate::a11y::{FocusList, heading};
use crate::action_icon::{HIT, action_icon, hit_target};
use crate::assets::MaterialIcon;
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, bar_spinner, button_bar, chrome, destructive_button, press, text_button, title_bar,
};
use crate::graph::{AppGraph, on_core};
use crate::library_playlist::Changed;

/// Compose's `ViewConfiguration.longPressTimeoutMillis`.
const LONG_PRESS: Duration = Duration::from_millis(500);
/// How far a held mouse may move and still be a long press.
const TOUCH_SLOP: f32 = 8.;
/// The bar's close button, then Select all; a list's own actions follow from [`BAR_ACTIONS`].
const BAR_CLOSE: usize = 0;
const BAR_SELECT_ALL: usize = 1;
pub const BAR_ACTIONS: usize = 2;

pub struct Selection<K> {
    selected: HashSet<K>,
    /// What Select all restores when pressed again; only valid until the selection or the listed
    /// items change otherwise.
    before_select_all: Option<HashSet<K>>,
    /// The item that started the mode or was toggled last: focus returns to it when the mode ends and
    /// its bar, holding focus, goes away (the JVM `selectionOrigin`).
    origin: Option<K>,
    /// The mode just ended: the next render focuses [`Self::origin`], else the first item.
    refocus: bool,
    long_press: Option<LongPress<K>>,
    /// The item a long press just selected: its release is not a click.
    long_pressed: Option<K>,
}

/// A mouse press held on an item outside selection mode (Compose's `detectTapGestures` long press):
/// once it lasts [`LONG_PRESS`], selection mode starts with the item selected.
struct LongPress<K> {
    id: K,
    /// Where the press went down; moving past [`TOUCH_SLOP`] lets it go.
    origin: Point<Pixels>,
    _timer: Task<()>,
}

impl<K> Default for Selection<K> {
    fn default() -> Self {
        Self {
            selected: HashSet::new(),
            before_select_all: None,
            origin: None,
            refocus: false,
            long_press: None,
            long_pressed: None,
        }
    }
}

impl<K: Eq + Hash + Clone + 'static> Selection<K> {
    pub fn active(&self) -> bool {
        !self.selected.is_empty()
    }

    pub fn contains(&self, id: &K) -> bool {
        self.selected.contains(id)
    }

    /// Applies `change`; true when the mode began or ended.
    pub fn change(&mut self, change: impl FnOnce(&mut HashSet<K>)) -> bool {
        let was_active = self.active();
        self.before_select_all = None;
        change(&mut self.selected);
        let toggled = was_active != self.active();
        if toggled {
            self.long_press = None;
            self.refocus = was_active;
        }
        toggled
    }

    /// Selection mode, with only `id` selected.
    pub fn start(&mut self, id: K) -> bool {
        self.origin = Some(id.clone());
        self.change(|selected| *selected = HashSet::from([id]))
    }

    pub fn toggle(&mut self, id: &K) -> bool {
        self.origin = Some(id.clone());
        self.change(|selected| {
            if !selected.remove(id) {
                selected.insert(id.clone());
            }
        })
    }

    pub fn clear(&mut self) -> bool {
        self.change(HashSet::clear)
    }

    /// Ends the mode because its list is going away (a section or tab switch, a reveal elsewhere):
    /// focus does not come back later.
    pub fn abandon(&mut self) -> bool {
        self.origin = None;
        let toggled = self.clear();
        self.refocus = false;
        toggled
    }

    pub fn all_selected(&self, listed: &[K]) -> bool {
        !listed.is_empty() && listed.iter().all(|id| self.selected.contains(id))
    }

    /// Adds every `listed` item, or, once all are, restores the selection from before (without one,
    /// deselects them).
    pub fn toggle_all(&mut self, listed: &[K]) -> bool {
        if self.all_selected(listed) {
            let restored = self.before_select_all.take().unwrap_or_else(|| {
                let listed: HashSet<&K> = listed.iter().collect();
                self.selected.iter().filter(|id| !listed.contains(id)).cloned().collect()
            });
            self.change(|selected| *selected = restored)
        } else if !listed.is_empty() {
            let before = self.selected.clone();
            let toggled = self.change(|selected| selected.extend(listed.iter().cloned()));
            self.before_select_all = Some(before);
            toggled
        } else {
            false
        }
    }

    /// Drops the picks `present` says are gone; true when that ended the mode.
    pub fn retain(&mut self, present: impl Fn(&K) -> bool) -> bool {
        if self.selected.iter().all(&present) {
            return false;
        }
        self.change(|selected| selected.retain(|id| present(id)))
    }

    /// The listed items changed: Select all no longer restores.
    pub fn forget_select_all(&mut self) {
        self.before_select_all = None;
    }

    /// Once after the mode ended: the item focus goes back to (`None`: the first item).
    pub fn take_refocus(&mut self) -> Option<Option<K>> {
        std::mem::take(&mut self.refocus).then(|| self.origin.take())
    }

    /// A press went down on `id`; `lasted` runs on the view once it is held long enough.
    pub fn start_long_press<V: 'static>(
        &mut self,
        id: K,
        origin: Point<Pixels>,
        cx: &mut Context<V>,
        lasted: fn(&mut V, &mut Context<V>),
    ) {
        self.long_pressed = None;
        if self.active() {
            return;
        }
        let timer = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(LONG_PRESS).await;
            this.update(cx, lasted).ok();
        });
        self.long_press = Some(LongPress { id, origin, _timer: timer });
    }

    pub fn release_long_press(&mut self) {
        self.long_press = None;
    }

    pub fn long_press_moved(&mut self, position: Point<Pixels>) {
        let moved = self.long_press.as_ref().is_some_and(|press| {
            let delta = position - press.origin;
            f32::from(delta.x).hypot(f32::from(delta.y)) > TOUCH_SLOP
        });
        if moved {
            self.long_press = None;
        }
    }

    /// The held press's item, which the caller now starts selection with.
    pub fn long_press_lasted(&mut self) -> Option<K> {
        let press = self.long_press.take()?;
        self.long_pressed = Some(press.id.clone());
        Some(press.id)
    }

    /// The click that ends a long press on `id`: not a click.
    pub fn swallow_click(&mut self, id: &K) -> bool {
        self.long_pressed.take().is_some_and(|pressed| &pressed == id)
    }
}

/// A selected item's fill: what a hovered item shows.
pub fn selected_fill(cx: &App) -> Hsla {
    cx.theme().primary.opacity(crate::settings_view::HOVER_ALPHA)
}

/// A control inside an item: its press is not the item's long press.
pub fn own_press(control: impl IntoElement) -> Div {
    div().flex_shrink_0().on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation()).child(control)
}

/// The item's checkbox in its ⋮ slot while selecting: `toggle` runs as a click on the item does.
pub fn check<V: 'static>(
    focus: &FocusList,
    row: usize,
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    checked: bool,
    toggle: impl Fn(&mut V, &mut Context<V>) + 'static,
    cx: &mut Context<V>,
) -> Div {
    let theme = cx.theme();
    let (primary, on_primary, border) = (theme.primary, theme.primary_foreground, theme.muted_foreground);
    let mark = div()
        .size(px(18.))
        .flex()
        .items_center()
        .justify_center()
        .rounded(px(4.))
        .border_2()
        .border_color(if checked { primary } else { border })
        .when(checked, |mark| {
            mark.bg(primary).child(Icon::new(MaterialIcon::Check).size(px(14.)).text_color(on_primary))
        });
    let button = hit_target(id, HIT)
        .role(Role::CheckBox)
        .toggled(checked)
        .accessibility_label(label.into())
        .child(mark)
        .on_click(cx.listener(move |view, _, _, cx| toggle(view, cx)));
    own_press(focus.trailing_button(row, 0, button, cx))
}

/// The selection bar's start: close · "N selected" (the page's heading) · Select all; the caller adds
/// its actions, their focus indices from [`BAR_ACTIONS`].
pub fn bar<V: 'static>(
    focus: &FocusList,
    count: usize,
    all_selected: bool,
    close: impl Fn(&mut V, &mut Context<V>) + 'static,
    select_all: impl Fn(&mut V, &mut Context<V>) + 'static,
    cx: &mut Context<V>,
) -> Div {
    let accent = cx.theme().primary;
    let count = count as i64;
    let close = action_icon("selection-close", MaterialIcon::Close, t(Str::CdClose))
        .on_click(cx.listener(move |view, _, _, cx| close(view, cx)));
    let select_all = action_icon("selection-all", MaterialIcon::SelectAll, t(Str::SelectAll))
        .toggled(all_selected)
        .when(all_selected, |button| button.text_color(accent))
        .on_click(cx.listener(move |view, _, _, cx| select_all(view, cx)));
    crate::shell::top_bar(cx)
        .child(focus.button(BAR_CLOSE, close, cx))
        .child(
            heading("selection-count", 1, plural(Plural::DownloadsSelectedCount, count, &[&count]))
                .flex_1()
                .min_w_0()
                .truncate()
                .text_lg()
                .font_semibold(),
        )
        .child(focus.button(BAR_SELECT_ALL, select_all, cx))
}

/// A selected file with its Library row; `key` names it to the caller (a download task, a song).
#[derive(Clone)]
pub struct FileTarget {
    pub key: String,
    pub item_id: i64,
    pub path: PathBuf,
}

/// How a [`DeleteSelectedFiles`] reports and finishes.
pub struct DeleteSelectedSpec {
    /// The code a failed delete shows and is captured under.
    pub error_code: &'static str,
    /// The log trail's tag, and the prop that names a target's key on it.
    pub log_tag: &'static str,
    pub key_prop: &'static str,
    /// Runs on the blocking thread for each target whose file and row are gone.
    pub after_delete: Arc<dyn Fn(&str) + Send + Sync>,
    /// Runs once every target is gone and the dialog closed.
    pub done: Changed,
}

/// Remove from disk on a selection bar (the JVM `DeleteSelectedFilesDialog`): deletes each target's
/// file and Library row; a failed one stays, counted in the title with the error shown. The dialog
/// cannot be dismissed while it runs.
pub struct DeleteSelectedFiles {
    remaining: Vec<FileTarget>,
    spec: DeleteSelectedSpec,
    busy: bool,
    error_code: Option<&'static str>,
    focus: FocusList,
}

impl DeleteSelectedFiles {
    pub fn open(targets: Vec<FileTarget>, spec: DeleteSelectedSpec, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| Self {
            remaining: targets,
            spec,
            busy: false,
            error_code: None,
            focus: FocusList::modal(None, cx),
        });
        window.open_dialog(cx, move |dialog, window, cx| {
            let busy = view.read(cx).busy;
            let delete = view.clone();
            chrome(dialog, DEFAULT_WIDTH, window).overlay_closable(!busy).keyboard(!busy).child(view.clone()).on_ok(
                move |_, window, cx| {
                    delete.update(cx, |view, cx| view.delete(window, cx));
                    false
                },
            )
        });
    }

    fn delete(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy || self.remaining.is_empty() {
            return;
        }
        self.busy = true;
        self.error_code = None;
        let graph = cx.global::<AppGraph>();
        let (media, log) = (graph.media.clone(), graph.log.clone());
        let (code, tag, key_prop, after_delete) =
            (self.spec.error_code, self.spec.log_tag, self.spec.key_prop, self.spec.after_delete.clone());
        let targets = self.remaining.clone();
        // The deletes and their follow-ups finish on the core runtime whatever becomes of the dialog.
        let run = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                targets
                    .into_iter()
                    .filter(|target| {
                        let deleted = (|| -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
                            match std::fs::remove_file(&target.path) {
                                Err(error) if error.kind() != std::io::ErrorKind::NotFound => return Err(error.into()),
                                _ => {}
                            }
                            media.delete_item(target.item_id)?;
                            Ok(())
                        })();
                        match deleted {
                            Ok(()) => {
                                after_delete(&target.key);
                                false
                            }
                            Err(error) => {
                                let mut props = serde_json::Map::new();
                                props.insert(key_prop.into(), target.key.clone().into());
                                log.fork_with(tag, props)
                                    .capture_error(&format!("{code} delete from storage failed"), error.as_ref());
                                true
                            }
                        }
                    })
                    .collect::<Vec<_>>()
            })
            .await
        });
        window.refresh();
        cx.spawn_in(window, async move |this, cx| {
            let outcome = run.await;
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match outcome {
                    Some(Ok(failed)) => view.remaining = failed,
                    Some(Err(error)) => cx
                        .global::<AppGraph>()
                        .log
                        .fork(tag)
                        .capture_error(&format!("{code} delete from storage failed"), &error),
                    None => {}
                }
                if view.remaining.is_empty() {
                    window.close_dialog(cx);
                    (view.spec.done)(window, cx);
                } else {
                    view.error_code = Some(code);
                }
                window.refresh();
            })
            .ok();
        })
        .detach();
    }
}

impl Render for DeleteSelectedFiles {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let count = self.remaining.len() as i64;
        let delete = if self.busy {
            bar_spinner()
        } else {
            press(
                &self.focus,
                1,
                destructive_button("delete-selected-ok", t(Str::DeleteFromDiskConfirm), true, cx),
                true,
                cx.listener(|view, _, window, cx| view.delete(window, cx)),
                cx,
            )
        };
        let buttons = h_flex()
            .child(press(
                &self.focus,
                0,
                text_button("delete-selected-cancel", t(Str::DialogCancel), false, !self.busy, cx),
                !self.busy,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(delete);
        v_flex()
            .w_full()
            .child(title_bar(plural(Plural::DownloadsDeleteSelectedTitle, count, &[&count]), cx))
            .child(
                v_flex()
                    .w_full()
                    .px(px(ROW_HORIZONTAL))
                    .py(px(16.))
                    .gap_2()
                    .child(
                        div()
                            .text_size(px(14.))
                            .line_height(px(20.))
                            .text_color(theme.foreground)
                            .child(t(Str::DownloadsDeleteSelectedMessage)),
                    )
                    .when_some(self.error_code, |body, code| {
                        body.child(
                            crate::a11y::alert("delete-selected-error", tf(Str::ErrorGeneric, &[&code]))
                                .text_size(px(13.))
                                .text_color(theme.danger),
                        )
                    }),
            )
            .child(button_bar(buttons, cx))
    }
}
