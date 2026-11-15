//! The JVM app's `ElevatedBarDialog` chrome on a gpui-kit dialog: the title and the button bar on a
//! raised ground behind dividers, a full-bleed body between them that scrolls under the bars (a
//! shadow edge shows there is more), the surface's 4px corners and no close button. Every dialog
//! of the app wears it.

use std::rc::Rc;

use gpui_kit::base::actions::Confirm as EnterKey;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::dialog::Dialog;
use gpui_kit::component::{ActiveTheme, Disableable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t};

use crate::a11y::{FocusList, heading};
use crate::spinner::Spinner;

/// The picker width; anything wider keeps a 24px gutter to the window edge.
pub const DEFAULT_WIDTH: f32 = 560.;
/// A full-bleed body row's insets: aligned with the title.
pub const ROW_HORIZONTAL: f32 = 24.;
pub const ROW_VERTICAL: f32 = 12.;
const WIDE_GUTTER: f32 = 24.;
const CORNER: f32 = 4.;

/// `dialog` as the chrome for a body at most `max_width` wide; the view draws the bars itself.
pub fn chrome(dialog: Dialog, max_width: f32, window: &Window) -> Dialog {
    let viewport = f32::from(window.viewport_size().width);
    let width = if max_width > DEFAULT_WIDTH { max_width.min(viewport - 2. * WIDE_GUTTER) } else { max_width };
    dialog
        .p_0()
        .gap_0()
        .border_0()
        .rounded(px(CORNER))
        .overflow_hidden()
        .close_button(false)
        .centered()
        .w(px(width.max(280.)))
}

/// The body's height cap: 85% of the window less the bars, never under 240.
pub fn body_max_height(window: &Window) -> Pixels {
    px((f32::from(window.viewport_size().height) * 0.85 - 112.).max(240.))
}

pub fn bar_color(cx: &App) -> Hsla {
    cx.global::<crate::settings_view::ChromeTones>().dialog
}

pub fn title_bar(title: impl Into<SharedString>, cx: &App) -> Div {
    title_bar_with(title, None::<Div>, cx)
}

/// [`title_bar`] with `action` at its end: a control that acts on the whole dialog.
pub fn title_bar_with(title: impl Into<SharedString>, action: Option<impl IntoElement>, cx: &App) -> Div {
    let theme = cx.theme();
    let end = if action.is_some() { 16. } else { 24. };
    v_flex()
        .w_full()
        .flex_shrink_0()
        .rounded_t(px(CORNER))
        .bg(bar_color(cx))
        .child(
            h_flex()
                .w_full()
                .pl(px(24.))
                .pr(px(end))
                .gap_2()
                .items_center()
                .child(
                    heading("dialog-title", 2, title)
                        .flex_1()
                        .min_w_0()
                        .py(px(16.))
                        .text_size(px(17.))
                        .line_height(px(22.))
                        .font_bold()
                        .text_color(theme.foreground),
                )
                .children(action),
        )
        .child(div().h(px(1.)).w_full().bg(theme.border))
}

/// The bottom bar: `content` right-aligned after a divider.
pub fn button_bar(content: impl IntoElement, cx: &App) -> Div {
    let theme = cx.theme();
    v_flex()
        .w_full()
        .flex_shrink_0()
        .rounded_b(px(CORNER))
        .bg(bar_color(cx))
        .child(div().h(px(1.)).w_full().bg(theme.border))
        .child(h_flex().w_full().px(px(12.)).py(px(2.)).justify_end().items_center().child(content))
}

/// A bar action as a text button: the primary one in the accent, the rest in secondary ink.
pub fn text_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    primary: bool,
    enabled: bool,
    cx: &App,
) -> Button {
    let theme = cx.theme();
    let color = match (primary, enabled) {
        (_, false) => theme.muted_foreground.opacity(0.6),
        (true, true) => theme.primary,
        (false, true) => theme.muted_foreground,
    };
    bar_text(id, label, color)
}

/// A bar action that destroys something (Delete, Sign out), in the danger ink.
pub fn destructive_button(id: impl Into<ElementId>, label: impl Into<SharedString>, enabled: bool, cx: &App) -> Button {
    let theme = cx.theme();
    bar_text(id, label, if enabled { theme.danger } else { theme.muted_foreground.opacity(0.6) })
}

fn bar_text(id: impl Into<ElementId>, label: impl Into<SharedString>, color: Hsla) -> Button {
    Button::new(id).ghost().h(px(40.)).px(px(12.)).text_color(color).font_medium().label(label)
}

/// `button` as the `index`th stop of `focus`, running `on_press` on a click and on Enter: the
/// dialog's Enter binding takes the key before a focused button sees it.
pub fn press(
    focus: &FocusList,
    index: usize,
    button: Button,
    enabled: bool,
    on_press: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
    cx: &App,
) -> Div {
    let on_press = Rc::new(on_press);
    let click = on_press.clone();
    focus
        .button(index, button.disabled(!enabled).on_click(move |event, window, cx| click(event, window, cx)), cx)
        .on_action(move |_: &EnterKey, window, cx| {
            if enabled {
                on_press(&ClickEvent::default(), window, cx);
            }
        })
}

/// What stands in a bar action's place while it runs.
pub fn bar_spinner() -> Div {
    h_flex().size(px(40.)).flex_shrink_0().justify_center().items_center().child(Spinner::new().small())
}

/// A body of one paragraph.
pub fn message(text: impl Into<SharedString>, cx: &App) -> Div {
    div()
        .w_full()
        .px(px(ROW_HORIZONTAL))
        .py(px(16.))
        .text_size(px(14.))
        .line_height(px(20.))
        .text_color(cx.theme().foreground)
        .child(text.into())
}

/// `content` as a body capped at [`body_max_height`] that scrolls under the bars, which cast their
/// shadow edges on it; the view redraws on a scroll so the edges follow.
pub fn scroll_body<V: 'static>(
    id: impl Into<ElementId>,
    scroll: &ScrollHandle,
    content: impl IntoElement,
    window: &Window,
    cx: &mut Context<V>,
) -> Div {
    div()
        .relative()
        .w_full()
        .min_h_0()
        .child(
            div()
                .id(id)
                .w_full()
                .max_h(body_max_height(window))
                .overflow_y_scroll()
                .track_scroll(scroll)
                .on_scroll(cx.listener(|_, _, _, cx| cx.notify()))
                .child(content),
        )
        .child(div().absolute().inset_0().child(scroll_shadows(scroll)))
}

/// The edges a scrolled body casts under the bars: drawn over its top and bottom.
pub fn scroll_shadows(scroll: &ScrollHandle) -> impl IntoElement {
    let (offset, max) = (scroll.offset().y, scroll.max_offset().y);
    div()
        .size_full()
        .when(offset < px(0.), |layer| layer.child(crate::shell::elevation_shadow(false)))
        .when(offset > -max && max > px(0.), |layer| layer.child(crate::shell::elevation_shadow(true)))
}

/// The JVM app's message dialog: a paragraph (and a smaller note) under the title, a dismissing
/// button and the action.
pub struct ConfirmDialog {
    title: SharedString,
    message: SharedString,
    note: Option<SharedString>,
    action: SharedString,
    cancel: Option<SharedString>,
    alternative: Option<(SharedString, OnPress)>,
    destructive: bool,
}

type OnPress = Rc<dyn Fn(&mut Window, &mut App)>;

impl ConfirmDialog {
    pub fn new(
        title: impl Into<SharedString>,
        message: impl Into<SharedString>,
        action: impl Into<SharedString>,
    ) -> Self {
        Self {
            title: title.into(),
            message: message.into(),
            note: None,
            action: action.into(),
            cancel: Some(t(Str::DialogCancel).into()),
            alternative: None,
            destructive: false,
        }
    }

    /// The dismissing button's label in place of Cancel.
    pub fn cancel(mut self, label: impl Into<SharedString>) -> Self {
        self.cancel = Some(label.into());
        self
    }

    /// Only the action: a notice to acknowledge.
    pub fn without_cancel(mut self) -> Self {
        self.cancel = None;
        self
    }

    /// A second action in secondary ink, before the main one; it closes the dialog too.
    pub fn alternative(
        mut self,
        label: impl Into<SharedString>,
        on_press: impl Fn(&mut Window, &mut App) + 'static,
    ) -> Self {
        self.alternative = Some((label.into(), Rc::new(on_press)));
        self
    }

    /// A second, smaller paragraph under the message.
    pub fn note(mut self, note: impl Into<SharedString>) -> Self {
        self.note = Some(note.into());
        self
    }

    /// The action in the danger ink.
    pub fn destructive(mut self) -> Self {
        self.destructive = true;
        self
    }

    /// Opens it; `on_confirm` runs once it has closed on the action (or Enter on no other button).
    pub fn open(self, window: &mut Window, cx: &mut App, on_confirm: impl Fn(&mut Window, &mut App) + 'static) {
        let on_confirm = Rc::new(on_confirm);
        let focus = Rc::new(FocusList::modal(None, cx));
        window.open_dialog(cx, move |dialog, window, cx| {
            let (enter, click) = (on_confirm.clone(), on_confirm.clone());
            let theme = cx.theme();
            let action = if self.destructive {
                destructive_button("confirm-ok", self.action.clone(), true, cx)
            } else {
                text_button("confirm-ok", self.action.clone(), true, true, cx)
            };
            let buttons = h_flex()
                .when_some(self.cancel.clone(), |bar, cancel| {
                    bar.child(press(
                        &focus,
                        0,
                        text_button("confirm-cancel", cancel, false, true, cx),
                        true,
                        |_, window, cx| window.close_dialog(cx),
                        cx,
                    ))
                })
                .when_some(self.alternative.clone(), |bar, (label, on_press)| {
                    bar.child(press(
                        &focus,
                        1,
                        text_button("confirm-alternative", label, false, true, cx),
                        true,
                        move |_, window, cx| {
                            window.close_dialog(cx);
                            on_press(window, cx);
                        },
                        cx,
                    ))
                })
                .child(press(
                    &focus,
                    2,
                    action,
                    true,
                    move |_, window, cx| {
                        window.close_dialog(cx);
                        click(window, cx);
                    },
                    cx,
                ));
            let body = v_flex()
                .w_full()
                .px(px(ROW_HORIZONTAL))
                .py(px(16.))
                .gap_2()
                .child(
                    div()
                        .text_size(px(14.))
                        .line_height(px(20.))
                        .text_color(theme.foreground)
                        .child(self.message.clone()),
                )
                .when_some(self.note.clone(), |body, note| {
                    body.child(
                        div().text_size(px(13.)).line_height(px(18.)).text_color(theme.muted_foreground).child(note),
                    )
                });
            chrome(dialog, DEFAULT_WIDTH, window)
                .on_ok(move |_, window, cx| {
                    enter(window, cx);
                    true
                })
                .child(
                    v_flex()
                        .w_full()
                        .child(title_bar(self.title.clone(), cx))
                        .child(body)
                        .child(button_bar(buttons, cx)),
                )
        });
    }
}

/// The `ConfirmDialog` of the JVM app: `message` under `title`, Cancel and the accent `confirm`.
pub fn confirm(
    title: &'static str,
    message: String,
    confirm: &'static str,
    window: &mut Window,
    cx: &mut App,
    on_confirm: impl Fn(&mut Window, &mut App) + 'static,
) {
    ConfirmDialog::new(title, message, confirm).open(window, cx, on_confirm);
}
