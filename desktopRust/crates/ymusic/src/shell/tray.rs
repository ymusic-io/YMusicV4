//! The tray icon's mini player popup: opened and closed by tray clicks, closed by itself when it
//! loses focus.

use std::time::{Duration, Instant};

use gpui_kit::*;

use super::Shell;
use crate::graph::AppGraph;
use crate::tray_popup::{Closed, TrayPopup};

/// A popup that closed this recently lost its focus to the tray click now arriving.
const REOPEN_GRACE: Duration = Duration::from_millis(400);

impl Shell {
    pub(super) fn toggle_tray_popup(&mut self, anchor: Bounds<Pixels>, cx: &mut Context<Self>) {
        if self.close_tray_popup(cx) {
            return;
        }
        if self.tray_popup_closed_at.is_some_and(|closed| closed.elapsed() < REOPEN_GRACE) {
            return;
        }
        let Some((display, popup_bounds)) = TrayPopup::placement(anchor, cx) else { return };
        let options = WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(popup_bounds)),
            titlebar: None,
            focus: true,
            show: true,
            kind: WindowKind::PopUp,
            is_movable: false,
            is_resizable: false,
            is_minimizable: false,
            display_id: Some(display),
            window_background: WindowBackgroundAppearance::Transparent,
            ..Default::default()
        };
        let (playback, commands) = (self.playback.clone(), self.commands.clone());
        let shell = cx.entity().downgrade();
        let opened = cx.open_window(options, move |window, cx| {
            let popup = cx.new(|cx| TrayPopup::new(playback, commands, window, cx));
            cx.subscribe(&popup, move |_, _: &Closed, cx| {
                shell
                    .update(cx, |shell, _| {
                        shell.tray_popup = None;
                        shell.tray_popup_closed_at = Some(Instant::now());
                    })
                    .ok();
            })
            .detach();
            cx.new(|cx| gpui_kit::base::Root::new(popup, window, cx).bg(transparent_black()))
        });
        match opened {
            Ok(handle) => self.tray_popup = Some(handle),
            Err(error) => cx.global::<AppGraph>().log.warn("tray popup could not open", Some(error.as_ref())),
        }
    }

    /// Closes the popup; false when none was open.
    pub(super) fn close_tray_popup(&mut self, cx: &mut Context<Self>) -> bool {
        let Some(handle) = self.tray_popup.take() else { return false };
        handle.update(cx, |_, window, _| window.remove_window()).is_ok()
    }

    /// Debug: `YMUSIC_DEV_TRAY_POPUP=1` opens the popup after 6 s as if the tray icon sat at the
    /// primary display's top-right corner (`hidden` hides the window to the tray first).
    pub(super) fn dev_tray_popup(&mut self, mode: String, window: &mut Window, cx: &mut Context<Self>) {
        let executor = cx.background_executor().clone();
        cx.spawn_in(window, async move |this, cx| {
            executor.timer(Duration::from_secs(3)).await;
            if mode == "hidden" {
                this.update_in(cx, |shell, window, cx| shell.hide_to_tray(window, cx)).ok();
            }
            executor.timer(Duration::from_secs(3)).await;
            this.update(cx, |shell, cx| {
                let Some(display) = cx.primary_display() else { return };
                let area = display.bounds();
                let anchor = bounds(point(area.right() - px(120.), area.top()), size(px(30.), px(24.)));
                shell.toggle_tray_popup(anchor, cx);
            })
            .ok();
        })
        .detach();
    }
}
