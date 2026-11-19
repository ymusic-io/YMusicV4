//! An item's ⋮ menu, which a right click anywhere on the item also opens, at the pointer.

use std::rc::Rc;

use gpui_kit::component::button::Button;
use gpui_kit::component::menu::{ContextMenu, ContextMenuExt, DropdownMenu, PopupMenu};
use gpui_kit::*;

type Build = dyn Fn(PopupMenu, &mut Window, &mut Context<PopupMenu>) -> PopupMenu;

#[derive(Clone)]
pub struct ItemMenu(Rc<Build>);

impl ItemMenu {
    pub fn new(build: impl Fn(PopupMenu, &mut Window, &mut Context<PopupMenu>) -> PopupMenu + 'static) -> Self {
        Self(Rc::new(build))
    }

    /// The ⋮ `button` opening the menu beside it, at `anchor`.
    pub fn dropdown(&self, button: Button, anchor: Anchor) -> impl IntoElement + use<> {
        let build = self.0.clone();
        button.dropdown_menu_with_anchor(anchor, move |menu, window, cx| build(menu, window, cx))
    }
}

/// `item` opening `menu` at the pointer on a right click. Without one (a selecting list's items have
/// no ⋮) a right click does nothing, and the item keeps its element id either way.
#[track_caller]
pub fn on_right_click<E>(item: E, menu: Option<&ItemMenu>) -> ContextMenu<E>
where
    E: InteractiveElement + ParentElement + Styled,
{
    let build = menu.map(|menu| menu.0.clone());
    let item = if build.is_some() {
        item
    } else {
        // Bubbles before the context menu's own listener, which sits outside the item.
        item.on_mouse_down(MouseButton::Right, |_, _, cx| cx.stop_propagation())
    };
    item.context_menu(move |popup, window, cx| match &build {
        Some(build) => build(popup, window, cx),
        None => popup,
    })
}
