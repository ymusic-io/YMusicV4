//! A launch that cannot start says why, with the code to report, and quits when the user closes it:
//! a window of its own before the app's, or the main window's content after the unlock screen.

use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::{ActiveTheme, StyledExt, v_flex};
use gpui_kit::*;
use ym_common::log::Logger;
use ym_i18n::{Str, t};

use crate::a11y::{FocusList, alert, heading};

const FORM_MAX: f32 = 420.;
const STOP_QUIT: usize = 0;

pub struct StartupFailure {
    message: SharedString,
    /// Draws the window frame itself: the window holds nothing else.
    framed: bool,
    focus: FocusList,
    focused_first: bool,
}

impl StartupFailure {
    pub fn new(message: impl Into<SharedString>, framed: bool) -> Self {
        Self { message: message.into(), framed, focus: FocusList::new(None), focused_first: false }
    }
}

impl Render for StartupFailure {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused_first {
            self.focused_first = true;
            let quit = self.focus.handle(STOP_QUIT, cx);
            window.defer(cx, move |window, cx| window.focus(&quit, cx));
        }
        let theme = cx.theme();
        let quit = Button::new("startup-failure-quit").primary().label(t(Str::TrayQuit)).on_click(|_, _, cx| cx.quit());
        let escape = |event: &KeyDownEvent, _: &mut Window, cx: &mut App| {
            if event.keystroke.key == "escape" {
                cx.quit();
            }
        };
        let form = v_flex()
            .id("startup-failure")
            .size_full()
            .bg(theme.background)
            .justify_center()
            .items_center()
            .on_key_down(escape)
            .child(
                v_flex()
                    .w_full()
                    .max_w(px(FORM_MAX))
                    .p_4()
                    .gap_4()
                    .items_center()
                    .child(heading("startup-failure-title", 1, t(Str::AppName)).text_center().font_semibold())
                    .child(
                        alert("startup-failure-message", self.message.clone())
                            .text_center()
                            .text_sm()
                            .text_color(theme.muted_foreground),
                    )
                    .child(self.focus.button(STOP_QUIT, quit, cx)),
            );
        let content = div().size_full().relative().child(crate::a11y::frame_marker()).child(form);
        if self.framed {
            crate::desktop::frame(content.into_any_element(), window, cx)
        } else {
            content.into_any_element()
        }
    }
}

/// Before the app's window: runs a window showing only `message` and exits the process once it
/// closes.
pub fn show_and_exit(message: String, log: &Logger) -> ! {
    let log = log.fork("startup-failure");
    application().with_assets(crate::assets::AppAssets).run(move |cx| {
        crate::settings_view::apply_layout_direction(cx);
        init(cx);
        crate::a11y::init(cx);
        cx.set_reduce_motion(true);
        crate::settings_view::apply_saved_theme(&ym_settings::MemorySettings::default(), cx);
        let options = WindowOptions {
            window_bounds: Some(WindowBounds::centered(size(px(480.), px(280.)), cx)),
            titlebar: Some(TitlebarOptions {
                title: Some(crate::dev_instance::window_title().into()),
                ..Default::default()
            }),
            app_id: Some(crate::desktop::app_id().into()),
            icon: crate::desktop::window_icon(&log),
            ..Default::default()
        };
        let opened = cx.open_window(options, |window, cx| {
            window.on_window_should_close(cx, |_, cx| {
                cx.quit();
                true
            });
            let view = cx.new(|_| StartupFailure::new(message, true));
            cx.new(|cx| gpui_kit::base::Root::new(view, window, cx))
        });
        match opened {
            Ok(_) => crate::desktop::activate_app(cx),
            Err(error) => {
                log.warn("the startup failure window did not open", Some(error.as_ref()));
                cx.quit();
            }
        }
    });
    std::process::exit(1);
}
