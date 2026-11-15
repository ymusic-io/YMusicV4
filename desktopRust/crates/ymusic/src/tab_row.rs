//! A row of text tabs with a 2 underline under the selected one (the JVM's UnderlineTabRow). Each
//! tab is a focus stop, so Tab and the arrows reach it and the screen reader announces it.

use std::rc::Rc;

use gpui_kit::component::{ActiveTheme, StyledExt, h_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

use crate::a11y::{DragRow, FocusList};
use crate::settings_view::HOVER_ALPHA;

pub fn tab_row(
    id: &'static str,
    labels: Vec<SharedString>,
    selected: usize,
    focus: &FocusList,
    drag: &DragRow,
    on_select: impl Fn(usize, &mut Window, &mut App) + 'static,
    cx: &App,
) -> impl IntoElement {
    let theme = cx.theme();
    let on_select = Rc::new(on_select);
    let count = labels.len();
    let mut row =
        h_flex().id(id).role(Role::TabList).track_scroll(drag.scroll()).overflow_x_scroll().restrict_scroll_to_axis();
    for (index, label) in labels.into_iter().enumerate() {
        let active = index == selected;
        let on_select = on_select.clone();
        let tab = div().id((id, index)).role(Role::Tab).aria_label(label.clone()).aria_selected(active);
        row = row.child(
            focus
                .item_in(index, tab, Some(drag.scroll().clone()), cx)
                .flex_shrink_0()
                .px_3()
                .py_2()
                .cursor_pointer()
                .rounded(px(6.))
                .text_sm()
                .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                .when(active, |tab| tab.font_semibold().text_color(theme.foreground))
                .when(!active, |tab| tab.text_color(theme.muted_foreground))
                .child(label)
                .when(active, |tab| {
                    tab.child(div().absolute().bottom_0().left_3().right_3().h(px(2.)).bg(theme.foreground))
                })
                .on_click(move |_, window, cx| on_select(index, window, cx)),
        );
    }
    drag.wrap(focus.container(row, 0..count, cx))
}
