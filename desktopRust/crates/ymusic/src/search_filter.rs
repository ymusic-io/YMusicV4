//! The search filter dialog: the server's filter groups as rows of pills. Picking an option hands
//! back its params, which carry the whole resulting filter state (the server composes them).

use std::rc::Rc;

use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::{ActiveTheme, Selectable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::*;
use ym_engine::yt::YtFilterGroup;
use ym_i18n::{Str, t};

use crate::a11y::FocusList;
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, button_bar, chrome, press, scroll_body, text_button, title_bar,
};

type OnSelect = Rc<dyn Fn(String, &mut Window, &mut App)>;

pub struct SearchFilter {
    groups: Vec<YtFilterGroup>,
    on_select: OnSelect,
    focus: FocusList,
    scroll: ScrollHandle,
    focused_first: bool,
}

impl SearchFilter {
    pub fn open(
        groups: Vec<YtFilterGroup>,
        on_select: impl Fn(String, &mut Window, &mut App) + 'static,
        window: &mut Window,
        cx: &mut App,
    ) {
        let view = cx.new(|cx| {
            let scroll = ScrollHandle::new();
            Self {
                groups,
                on_select: Rc::new(on_select),
                focus: FocusList::modal(Some(scroll.clone()), cx),
                scroll,
                focused_first: false,
            }
        });
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, DEFAULT_WIDTH, window).on_ok(|_, _, _| false).child(view.clone())
        });
    }
}

impl Render for SearchFilter {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let mut stop = 0;
        let mut first_enabled = None;
        let mut column = v_flex().w_full().px(px(ROW_HORIZONTAL)).pt_2().pb_4();
        for (group_index, group) in self.groups.iter().enumerate() {
            column = column.child(
                div()
                    .id(("filter-group", group_index))
                    .role(Role::Heading)
                    .aria_level(3)
                    .aria_label(group.title.clone())
                    .pt_4()
                    .pb_2()
                    .text_size(px(12.))
                    .font_bold()
                    .text_color(theme.muted_foreground)
                    .child(group.title.to_uppercase()),
            );
            let mut pills = h_flex().flex_wrap().gap_2();
            for option in &group.options {
                let params = option.params.clone();
                let on_select = self.on_select.clone();
                let pill = crate::action_icon::chip(Button::new(("filter-option", stop)))
                    .label(option.label.clone())
                    .selected(option.selected);
                let pill = if option.selected { pill.primary() } else { pill.outline() };
                let select = move |_: &ClickEvent, window: &mut Window, cx: &mut App| {
                    window.close_dialog(cx);
                    if let Some(params) = params.clone() {
                        on_select(params, window, cx);
                    }
                };
                if first_enabled.is_none() && !option.disabled {
                    first_enabled = Some(stop);
                }
                pills = pills.child(press(&self.focus, stop, pill, !option.disabled, select, cx));
                stop += 1;
            }
            column = column.child(pills);
        }
        let cancel = stop;
        if !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(first_enabled.unwrap_or(cancel), cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let dismiss = press(
            &self.focus,
            cancel,
            text_button("filter-cancel", t(Str::DialogCancel), false, true, cx),
            true,
            |_, window, cx| window.close_dialog(cx),
            cx,
        );
        self.focus
            .container(v_flex().w_full(), 0..cancel + 1, cx)
            .child(title_bar(t(Str::SearchFilterTitle), cx))
            .child(scroll_body("filter-groups", &self.scroll, column, window, cx))
            .child(button_bar(dismiss, cx))
    }
}
