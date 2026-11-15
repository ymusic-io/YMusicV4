//! Rename file (JVM `RenameDownloadDialog` + `DesktopFileRenaming`): renames a local media file in
//! its own folder, then re-points its Library row and any finished download at the new path.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_download::{DownloadService, names};
use ym_i18n::{Str, t, tf};
use ym_store::MediaStore;

use crate::a11y::FocusList;
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, bar_spinner, button_bar, chrome, press, text_button, title_bar,
};
use crate::graph::{AppGraph, on_core};
use crate::library_playlist::Changed;

#[derive(Debug, thiserror::Error)]
enum RenameError {
    #[error("R99010 a file named {0} already exists")]
    Taken(String),
    #[error("R99011 renaming the file failed: {0}")]
    Disk(#[from] std::io::Error),
    #[error("R99012 the Library row was not moved to the new name: {0}")]
    Store(#[from] ym_store::StoreError),
    #[error("R99013 the download was not moved to the new name: {0}")]
    Downloads(#[from] ym_download::DownloadError),
    #[error("R99014 the core runtime stopped")]
    Stopped,
}

impl RenameError {
    fn code(&self) -> &'static str {
        match self {
            RenameError::Taken(_) => "R99010",
            RenameError::Disk(_) => "R99011",
            RenameError::Store(_) => "R99012",
            RenameError::Downloads(_) => "R99013",
            RenameError::Stopped => "R99014",
        }
    }
}

fn same_file(first: &Path, second: &Path) -> std::io::Result<bool> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let (first, second) = (std::fs::metadata(first)?, std::fs::metadata(second)?);
        Ok(first.dev() == second.dev() && first.ino() == second.ino())
    }
    // Windows volumes are case-insensitive: a name that differs only in case is the file itself.
    #[cfg(not(unix))]
    {
        Ok(first.to_string_lossy().to_lowercase() == second.to_string_lossy().to_lowercase())
    }
}

/// Renames `from` to `file_name` beside it; never replaces another file.
fn rename_on_disk(from: &Path, file_name: &str) -> Result<PathBuf, RenameError> {
    let target = from.with_file_name(file_name);
    if target == from {
        return Ok(target);
    }
    match std::fs::symlink_metadata(&target) {
        // A case-only rename on a case-insensitive volume finds the source itself "already there".
        Ok(_) if !same_file(from, &target)? => return Err(RenameError::Taken(file_name.to_owned())),
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => return Err(error.into()),
        _ => {}
    }
    std::fs::rename(from, &target)?;
    Ok(target)
}

async fn rename_file(
    media: Arc<MediaStore>,
    downloads: DownloadService,
    from: PathBuf,
    file_name: String,
) -> Result<PathBuf, RenameError> {
    let source = from.clone();
    let target = tokio::task::spawn_blocking(move || {
        let target = rename_on_disk(&source, &file_name)?;
        let (old_uri, new_uri) = (source.to_string_lossy().into_owned(), target.to_string_lossy().into_owned());
        if let Some((item, _)) = media.find_item_by_uri(&old_uri)? {
            media.relocate_item(item.id, &new_uri)?;
        }
        Ok::<_, RenameError>(target)
    })
    .await
    .map_err(|_| RenameError::Stopped)??;
    let file_uri = |path: &std::path::Path| ym_common::file_uri::to_file_uri(&path.to_string_lossy());
    downloads.relocate(&file_uri(&from), &file_uri(&target)).await?;
    Ok(target)
}

/// The file name field with its extension fixed beside it; Save stays off while the name is blank.
pub struct RenameFileDialog {
    path: PathBuf,
    extension: String,
    name: Entity<InputState>,
    /// The last edit dropped a character no file name may hold.
    stripped: bool,
    /// The next change is the stripped value written back, not an edit.
    stripping: bool,
    error: Option<String>,
    busy: bool,
    focused: bool,
    changed: Option<Changed>,
    focus: FocusList,
    _subscription: Subscription,
}

impl RenameFileDialog {
    pub fn open(path: PathBuf, changed: Option<Changed>, window: &mut Window, cx: &mut App) {
        let file_name = path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
        let dot = file_name.rfind('.').filter(|index| *index > 0).unwrap_or(file_name.len());
        let (stem, extension) = (file_name[..dot].to_owned(), file_name[dot..].to_owned());
        let view = cx.new(|cx| {
            let name =
                cx.new(|cx| InputState::new(window, cx).placeholder(t(Str::DownloadFileName)).default_value(stem));
            let subscription =
                cx.subscribe_in(&name, window, |view: &mut Self, input, event: &InputEvent, window, cx| {
                    match event {
                        InputEvent::PressEnter { .. } => view.submit(window, cx),
                        InputEvent::Change => {
                            let raw = input.read(cx).value().to_string();
                            if names::has_forbidden(&raw) {
                                let clean = names::strip_forbidden(&raw);
                                input.update(cx, |input, cx| input.set_value(clean, window, cx));
                                view.stripped = true;
                                view.stripping = true;
                            } else if !std::mem::take(&mut view.stripping) {
                                view.stripped = false;
                            }
                            view.error = None;
                        }
                        _ => {}
                    }
                    cx.notify();
                });
            Self {
                path,
                extension,
                name,
                stripped: false,
                stripping: false,
                error: None,
                busy: false,
                focused: false,
                changed,
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

    fn finalized(&self, cx: &App) -> String {
        names::finalize(&self.name.read(cx).value())
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let stem = self.finalized(cx);
        if self.busy || stem.is_empty() {
            return;
        }
        self.busy = true;
        self.error = None;
        let graph = cx.global::<AppGraph>();
        let (media, downloads) = (graph.media.clone(), graph.downloads.clone());
        let (from, file_name) = (self.path.clone(), format!("{stem}{}", self.extension));
        let request = on_core(cx, rename_file(media, downloads, from, file_name));
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await.unwrap_or(Err(RenameError::Stopped));
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match result {
                    Ok(_) => {
                        window.close_dialog(cx);
                        if let Some(changed) = &view.changed {
                            changed(window, cx);
                        }
                    }
                    Err(error) => {
                        cx.global::<AppGraph>().log.fork("file-rename").warn("renaming a file failed", Some(&error));
                        view.error = Some(tf(Str::ErrorGeneric, &[&error.code()]));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

impl Render for RenameFileDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused {
            self.focused = true;
            let name = self.name.clone();
            window.defer(cx, move |window, cx| {
                name.update(cx, |input, cx| {
                    input.focus(window, cx);
                    let end = input.value().len();
                    input.set_selected_range(0..end, cx);
                })
            });
        }
        let theme = cx.theme().clone();
        let blank = self.finalized(cx).is_empty();
        let (hint, alert) = match &self.error {
            Some(error) => (error.clone(), true),
            None if blank => (t(Str::DownloadNameEmpty).to_owned(), true),
            None => (tf(Str::DownloadForbiddenHint, &[&names::FORBIDDEN_DISPLAY]), self.stripped),
        };
        let tone = if alert { theme.danger } else { theme.muted_foreground };
        let field = Input::new(&self.name)
            .aria_label(t(Str::DownloadFileName))
            .when(alert, |input| input.border_color(theme.danger))
            .suffix(div().text_color(theme.muted_foreground).child(self.extension.clone()));
        let save = if self.busy {
            bar_spinner()
        } else {
            press(
                &self.focus,
                2,
                text_button("rename-save", t(Str::DownloadSave), true, !blank, cx),
                !blank,
                cx.listener(|view, _, window, cx| view.submit(window, cx)),
                cx,
            )
        };
        let buttons = h_flex()
            .child(press(
                &self.focus,
                1,
                text_button("rename-cancel", t(Str::DialogCancel), false, !self.busy, cx),
                !self.busy,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(save);
        v_flex()
            .w_full()
            .child(title_bar(t(Str::DownloadRename), cx))
            .child(
                v_flex()
                    .w_full()
                    .px(px(ROW_HORIZONTAL))
                    .py_2()
                    .gap_1()
                    .child(div().text_size(px(12.)).text_color(tone).child(t(Str::DownloadFileName)))
                    .child(self.focus.field(0, &self.name.focus_handle(cx), field, cx))
                    .child(
                        div()
                            .id("rename-hint")
                            .px_4()
                            .text_size(px(12.))
                            .text_color(tone)
                            .when(alert, |hint_line| hint_line.role(Role::Alert).aria_label(hint.clone()))
                            .child(hint),
                    ),
            )
            .child(button_bar(buttons, cx))
    }
}
