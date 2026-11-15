//! The keyboard shortcuts help (F1): every Shell binding with what it does.

use gpui_kit::component::kbd::Kbd;
use gpui_kit::component::{ActiveTheme, WindowExt, h_flex, v_flex};
use gpui_kit::*;
use ym_i18n::{Str, t};

use crate::a11y::{FocusList, list_item};
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, ROW_VERTICAL, button_bar, chrome, press, scroll_body, text_button, title_bar,
};
use crate::shortcuts::HELP;

pub struct ShortcutsHelp {
    focus: FocusList,
    scroll: ScrollHandle,
    focused_first: bool,
}

impl ShortcutsHelp {
    /// Opens the help unless a dialog is already up; F1 again closes it.
    pub fn open(window: &mut Window, cx: &mut App) {
        if window.has_active_dialog(cx) {
            return;
        }
        let view = cx.new(|cx| {
            let scroll = ScrollHandle::new();
            Self { focus: FocusList::modal(Some(scroll.clone()), cx), scroll, focused_first: false }
        });
        window.open_dialog(cx, move |dialog, window, _| chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()));
    }
}

/// The platform's label for `stroke`; Esc spelled out, as its ⎋ glyph is little known.
fn key_label(stroke: &Keystroke) -> String {
    Kbd::format(stroke).replace('⎋', "Esc")
}

/// Arrow glyphs are spoken inconsistently (or not at all) across screen readers.
fn spoken(keys: &str) -> String {
    [
        ("←", Str::A11yKeyArrowLeft),
        ("→", Str::A11yKeyArrowRight),
        ("↑", Str::A11yKeyArrowUp),
        ("↓", Str::A11yKeyArrowDown),
    ]
    .iter()
    .fold(keys.to_owned(), |text, (glyph, name)| text.replace(glyph, &format!(" {} ", t(*name))))
    .split_whitespace()
    .collect::<Vec<_>>()
    .join(" ")
}

impl Render for ShortcutsHelp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(0, cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let theme = cx.theme().clone();
        let stops = self.focus.list_stops(0..HELP.len(), window, cx);
        let mut rows = v_flex().id("shortcut-list").w_full().py_2();
        for (index, row) in HELP.iter().enumerate() {
            let mut keys = h_flex().w(px(200.)).flex_shrink_0().flex_wrap().gap_1().items_center();
            let mut spoken_keys = Vec::new();
            for (position, combo) in row.keys.iter().enumerate() {
                if position > 0 {
                    keys = keys.child(div().text_xs().text_color(theme.muted_foreground).child(if row.range {
                        "…"
                    } else {
                        ","
                    }));
                }
                let strokes: Vec<Keystroke> =
                    combo.split(' ').filter_map(|stroke| Keystroke::parse(stroke).ok()).collect();
                spoken_keys.push(strokes.iter().map(|stroke| spoken(&key_label(stroke))).collect::<Vec<_>>().join(" "));
                for stroke in &strokes {
                    keys = keys.child(
                        div()
                            .px_1p5()
                            .py_0p5()
                            .min_w(px(22.))
                            .text_center()
                            .rounded(px(4.))
                            .border_1()
                            .border_color(theme.border)
                            .text_xs()
                            .text_color(theme.muted_foreground)
                            .child(key_label(stroke)),
                    );
                }
            }
            let label = t(row.label);
            let line = list_item(h_flex().id(("shortcut", index)), index)
                .aria_label(format!("{label}, {}", spoken_keys.join(if row.range { " … " } else { ", " })))
                .w_full()
                .gap_4()
                .px(px(ROW_HORIZONTAL))
                .py(px(ROW_VERTICAL))
                .items_center()
                .child(keys)
                .child(div().flex_1().min_w_0().text_size(px(14.)).child(label));
            rows = rows.child(self.focus.item(index, line, cx));
        }
        let close = HELP.len();
        let footer = press(
            &self.focus,
            close,
            text_button("shortcuts-close", t(Str::CdClose), true, true, cx),
            true,
            |_, window, cx| window.close_dialog(cx),
            cx,
        );
        self.focus
            .container(
                v_flex().w_full().on_key_down(|event: &KeyDownEvent, window, cx| {
                    if event.keystroke.key == "f1" {
                        window.close_dialog(cx);
                        cx.stop_propagation();
                    }
                }),
                0..close + 1,
                cx,
            )
            .child(title_bar(t(Str::ShortcutsTitle), cx))
            .child(scroll_body(
                "shortcuts",
                &self.scroll,
                stops.wrap(rows, Some(t(Str::ShortcutsTitle).into()), cx),
                window,
                cx,
            ))
            .child(button_bar(footer, cx))
    }
}
