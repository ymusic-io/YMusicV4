//! Keyboard reach the way the JVM app's Compose focus works: every list item, card, tab and chip is
//! a real focus stop (Enter or Space activates it, the screen reader announces it), and an arrow key
//! moves focus geometrically, to the nearest stop on screen in that direction, preferring one that
//! lines up (Android's FocusFinder rule), so focus enters and leaves lists in screen order. Tab enters
//! a list ([`FocusList::list_stops`]) on its item focused last, then steps item by item, and leaves it
//! past the last; any other stop is its own. An item's ⋮ is not a Tab stop: Shift+F10 or the Menu key
//! opens it ([`FocusList::menu_button`]). The focused stop is ringed and scrolled into view.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ops::Range;
use std::rc::{Rc, Weak};

use gpui_kit::base::actions::Confirm;
use gpui_kit::base::slider::SliderState;
use gpui_kit::component::ActiveTheme;
use gpui_kit::component::button::Button;
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

const RING: f32 = 2.;
/// Room kept between a revealed item and the scroll viewport's edge.
const REVEAL_MARGIN: f32 = 12.;
/// FocusFinder's weight of the distance along the arrow against the sideways offset.
const MAJOR_WEIGHT: f32 = 13.;
const READING_ROW: f32 = 24.;

struct Slot {
    handle: FocusHandle,
    /// Arrows move between stops of one group: 0 is the window, a dialog has its own.
    group: u64,
    /// The list the stop belongs to, whose entry stop an arrow coming from elsewhere lands on.
    entry: Rc<RefCell<Option<FocusHandle>>>,
    was_focused: Cell<bool>,
    /// Window bounds as last painted, and the frame that painted it.
    bounds: Cell<Bounds<Pixels>>,
    painted: Cell<u64>,
    /// The visible part of the list's scroll container then, which clips what the stop shows.
    viewport: Cell<Option<Bounds<Pixels>>>,
    /// The item's ⋮, which Shift+F10 and the Menu key open, kept across frames for its dropdown state.
    menu: RefCell<Option<FocusHandle>>,
    /// Set by this build's [`FocusList::menu_button`], taken by the item's: a later item at this position
    /// without a ⋮ must not expose the old one.
    menu_armed: Cell<bool>,
}

impl Slot {
    /// The ⋮ this build's item has, consuming [`Self::menu_armed`].
    fn take_menu(&self) -> Option<FocusHandle> {
        if self.menu_armed.replace(false) { self.menu.borrow().clone() } else { None }
    }

    /// What shows of the stop, `None` when it is scrolled out of its list's viewport.
    fn visible(&self) -> Option<Bounds<Pixels>> {
        let bounds = self.bounds.get();
        let Some(viewport) = self.viewport.get() else { return Some(bounds) };
        let shown = bounds.intersect(&viewport);
        (shown.size.width > px(0.) && shown.size.height > px(0.)).then_some(shown)
    }
}

/// Every focus stop on screen, for geometric arrow moves.
#[derive(Default)]
pub struct RovingItems {
    slots: RefCell<Vec<Weak<Slot>>>,
    frame: Cell<u64>,
    last_group: Cell<u64>,
    /// Handles window roots track: a cached view replays its stops into the rendered frame under
    /// one without painting them again.
    roots: RefCell<Vec<WeakFocusHandle>>,
}

impl Global for RovingItems {}

impl RovingItems {
    fn register(cx: &App, slot: &Rc<Slot>) {
        let mut slots = cx.global::<RovingItems>().slots.borrow_mut();
        slots.retain(|slot| slot.strong_count() > 0);
        slots.push(Rc::downgrade(slot));
    }

    /// The stops on screen: painted by the latest frame, or replayed in it by a cached view, whose
    /// bounds are then the ones last painted.
    fn on_screen(window: &Window, cx: &App) -> Vec<Rc<Slot>> {
        let items = cx.global::<RovingItems>();
        let frame = items.frame.get();
        let roots: Vec<FocusHandle> = items.roots.borrow().iter().filter_map(WeakFocusHandle::upgrade).collect();
        items
            .slots
            .borrow()
            .iter()
            .filter_map(Weak::upgrade)
            .filter(|slot| slot.painted.get() == frame || roots.iter().any(|root| root.contains(&slot.handle, window)))
            .collect()
    }
}

/// Registers `handle`, tracked by a window's root element, so stops in cached views under it count
/// as on screen.
pub fn track_root(handle: &FocusHandle, cx: &App) {
    let mut roots = cx.global::<RovingItems>().roots.borrow_mut();
    roots.retain(|root| root.upgrade().is_some());
    roots.push(handle.downgrade());
}

pub fn init(cx: &mut App) {
    cx.set_global(RovingItems::default());
}

/// `view` laid out at `style` and drawn from its last frame until it notifies. While an assistive
/// client is connected it draws every frame instead: gpui builds the accessibility tree in prepaint,
/// which a reused view skips, so its nodes and the focus would drop out of the tree.
pub fn cached_view(view: impl Into<AnyView>, style: StyleRefinement, window: &Window) -> AnyElement {
    let view = view.into();
    if window.is_a11y_active() {
        let mut frame = div();
        *frame.style() = style;
        frame.child(view).into_any_element()
    } else {
        view.cached(style).into_any_element()
    }
}

/// Paint this first in the window (before any stop) so stops from earlier frames drop out.
pub fn frame_marker() -> impl IntoElement {
    canvas(
        |_, _, _| {},
        |_, _, _, cx| {
            let items = cx.global::<RovingItems>();
            items.frame.set(items.frame.get() + 1);
        },
    )
    .absolute()
    .top_0()
    .left_0()
    .size_0()
}

#[derive(Clone, Copy, PartialEq)]
enum Direction {
    Left,
    Right,
    Up,
    Down,
}

/// FocusFinder's ranking of `to` as the next stop from `from` (lower is better); `None` when it is
/// not that way. A stop lined up with `from` ranks by its distance along the arrow alone, its centre's
/// offset only breaking ties, so a full-width row is not passed over for a button further on that
/// shares the focused control's centre line.
///
/// Next after lining up with the stop, a target lining up with the stop's whole list (`list`) wins: Down
/// from a tab goes into the page under the tab row, not to the rail beside it.
fn rank(
    from: Bounds<Pixels>,
    to: Bounds<Pixels>,
    list: Bounds<Pixels>,
    direction: Direction,
) -> Option<(bool, bool, f32, f32)> {
    let (fl, fr, ft, fb) =
        (f32::from(from.left()), f32::from(from.right()), f32::from(from.top()), f32::from(from.bottom()));
    let (tl, tr, tt, tb) = (f32::from(to.left()), f32::from(to.right()), f32::from(to.top()), f32::from(to.bottom()));
    let (center_x, center_y) = ((fl + fr) / 2., (ft + fb) / 2.);
    // Past the centre line too, so a neighbour in the same row a few pixels taller is not "below".
    let candidate = match direction {
        Direction::Left => (fr > tr || fl >= tr) && fl > tl && tr <= center_x,
        Direction::Right => (fl < tl || fr <= tl) && fr < tr && tl >= center_x,
        Direction::Up => (fb > tb || ft >= tb) && ft > tt && tb <= center_y,
        Direction::Down => (ft < tt || fb <= tt) && fb < tb && tt >= center_y,
    };
    if !candidate {
        return None;
    }
    let (major, minor, in_beam) = match direction {
        Direction::Left => ((fl - tr).max(0.), ((ft + fb) - (tt + tb)).abs() / 2., tb > ft && tt < fb),
        Direction::Right => ((tl - fr).max(0.), ((ft + fb) - (tt + tb)).abs() / 2., tb > ft && tt < fb),
        Direction::Up => ((ft - tb).max(0.), ((fl + fr) - (tl + tr)).abs() / 2., tr > fl && tl < fr),
        Direction::Down => ((tt - fb).max(0.), ((fl + fr) - (tl + tr)).abs() / 2., tr > fl && tl < fr),
    };
    let in_list_beam = match direction {
        Direction::Left | Direction::Right => to.bottom() > list.top() && to.top() < list.bottom(),
        Direction::Up | Direction::Down => to.right() > list.left() && to.left() < list.right(),
    };
    let sideways = if in_beam { 0. } else { minor };
    Some((!in_beam, !in_list_beam, MAJOR_WEIGHT * major * major + sideways * sideways, minor))
}

/// Moves focus to the best stop in `direction`, from the focused stop or the innermost stop holding
/// the focused control (a card's menu button); false when there is none.
fn step(direction: Direction, window: &mut Window, cx: &mut App) -> bool {
    let slots = RovingItems::on_screen(window, cx);
    let area = |slot: &&Rc<Slot>| {
        let size = slot.bounds.get().size;
        f32::from(size.width) * f32::from(size.height)
    };
    let current = slots.iter().find(|slot| slot.handle.is_focused(window)).or_else(|| {
        slots
            .iter()
            .filter(|slot| slot.handle.contains_focused(window, cx))
            .min_by(|left, right| area(left).total_cmp(&area(right)))
    });
    let Some(current) = current else { return false };
    let from = current.visible().unwrap_or_else(|| current.bounds.get());
    // A stop scrolled out of view is reached only from its own list, which then scrolls to it.
    let reachable = |slot: &Rc<Slot>| match slot.visible() {
        Some(shown) => Some(shown),
        None => (slot.viewport.get() == current.viewport.get()).then(|| slot.bounds.get()),
    };
    let list = slots
        .iter()
        .filter(|slot| Rc::ptr_eq(&slot.entry, &current.entry))
        .filter_map(|slot| slot.visible())
        .fold(from, |span, shown| span.union(&shown));
    let best = slots
        .iter()
        .filter(|slot| !Rc::ptr_eq(slot, current) && slot.group == current.group)
        .filter_map(|slot| reachable(slot).and_then(|to| rank(from, to, list, direction)).map(|rank| (rank, slot)))
        .min_by(|(left, _), (right, _)| left.partial_cmp(right).unwrap_or(std::cmp::Ordering::Equal));
    let Some((_, target)) = best else { return false };
    let mut handle = target.handle.clone();
    if !Rc::ptr_eq(&target.entry, &current.entry) {
        let entry = target.entry.borrow().clone();
        if let Some(entry) = entry.filter(|entry| slots.iter().any(|slot| slot.handle == *entry)) {
            handle = entry;
        }
    }
    window.focus(&handle, cx);
    true
}

/// Arrow keys move focus geometrically from any stop inside `element`.
fn on_arrows<E: InteractiveElement>(element: E) -> E {
    arrows(element, true)
}

/// [`on_arrows`], or only Up and Down when `sideways` is false (a text field's caret keeps the rest).
fn arrows<E: InteractiveElement>(element: E, sideways: bool) -> E {
    element.on_key_down(move |event: &KeyDownEvent, window, cx| {
        let modifiers = &event.keystroke.modifiers;
        if modifiers.platform || modifiers.control || modifiers.alt || modifiers.shift {
            return;
        }
        let direction = match event.keystroke.key.as_str() {
            "left" if sideways => Direction::Left,
            "right" if sideways => Direction::Right,
            "up" => Direction::Up,
            "down" => Direction::Down,
            _ => return,
        };
        if step(direction, window, cx) {
            cx.stop_propagation();
        }
    })
}

/// [`on_arrows`], and Shift+F10 or the Menu key while `slot` itself holds focus opens its item's ⋮, `menu`.
fn on_menu_key<E: InteractiveElement>(slot: &Rc<Slot>, menu: Option<FocusHandle>, element: E) -> E {
    let slot = slot.clone();
    on_arrows(element).on_key_down(move |event: &KeyDownEvent, window, cx| {
        let modifiers = &event.keystroke.modifiers;
        let pressed = match event.keystroke.key.as_str() {
            "f10" => modifiers.shift,
            "menu" => !modifiers.shift,
            _ => false,
        };
        if !pressed || modifiers.platform || modifiers.control || modifiers.alt || !slot.handle.is_focused(window) {
            return;
        }
        if let Some(menu) = &menu {
            open_menu(menu, window, cx);
            cx.stop_propagation();
        }
    })
}

/// Opens the dropdown menu of the button tracking `menu`, as Enter on it would, leaving focus where it
/// is so that closing the menu returns it there.
fn open_menu(menu: &FocusHandle, window: &mut Window, cx: &mut App) {
    menu.dispatch_action(&Confirm { secondary: false }, window, cx);
}

/// Arrow keys while `root` itself holds focus (nothing on screen does) move it to the first stop on
/// screen, or the last for Up and Left.
pub fn root_arrows<E: InteractiveElement>(element: E, root: FocusHandle) -> E {
    element.on_key_down(move |event: &KeyDownEvent, window, cx| {
        let modifiers = &event.keystroke.modifiers;
        if !root.is_focused(window) || modifiers.platform || modifiers.control || modifiers.alt || modifiers.shift {
            return;
        }
        let forward = match event.keystroke.key.as_str() {
            "down" | "right" => true,
            "up" | "left" => false,
            _ => return,
        };
        let slots = RovingItems::on_screen(window, cx);
        // Reading order, in rows of `READING_ROW`: a stop a few pixels higher is still on the same line.
        let reading = |slot: &&Rc<Slot>| {
            let bounds = slot.bounds.get();
            ((f32::from(bounds.top()) / READING_ROW).floor(), f32::from(bounds.left()))
        };
        let order = |left: &&Rc<Slot>, right: &&Rc<Slot>| {
            reading(left).partial_cmp(&reading(right)).unwrap_or(std::cmp::Ordering::Equal)
        };
        let visible = slots.iter().filter(|slot| slot.group == 0 && !slot.bounds.get().size.is_zero());
        let target = if forward { visible.min_by(order) } else { visible.max_by(order) };
        if let Some(target) = target {
            let handle = target.handle.clone();
            window.focus(&handle, cx);
            cx.stop_propagation();
        }
    })
}

/// The focus handles of one list's items, kept across frames by item position.
pub struct FocusList {
    slots: RefCell<Vec<Rc<Slot>>>,
    /// The controls inside each item, by item, each with whether it is a Tab stop.
    trailing: RefCell<HashMap<usize, Vec<(FocusHandle, bool)>>>,
    /// The item focused last in each list, by the list's first item.
    tab_items: RefCell<HashMap<usize, Rc<Cell<Option<usize>>>>>,
    scroll: Option<ScrollHandle>,
    group: u64,
    entry: Rc<RefCell<Option<FocusHandle>>>,
}

impl FocusList {
    /// `scroll` is the scroll container the items live in, to reveal a focused item.
    pub fn new(scroll: Option<ScrollHandle>) -> Self {
        Self::in_group(scroll, 0)
    }

    fn in_group(scroll: Option<ScrollHandle>, group: u64) -> Self {
        Self {
            slots: RefCell::default(),
            trailing: RefCell::default(),
            tab_items: RefCell::default(),
            scroll,
            group,
            entry: Rc::default(),
        }
    }

    /// A dialog's stops: arrows stay among them, never reaching the window behind.
    pub fn modal(scroll: Option<ScrollHandle>, cx: &App) -> Self {
        let items = cx.global::<RovingItems>();
        items.last_group.set(items.last_group.get() + 1);
        Self::in_group(scroll, items.last_group.get())
    }

    fn slot(&self, index: usize, cx: &App) -> Rc<Slot> {
        let mut slots = self.slots.borrow_mut();
        while slots.len() <= index {
            let slot = Rc::new(Slot {
                handle: cx.focus_handle().tab_stop(true),
                group: self.group,
                entry: self.entry.clone(),
                was_focused: Cell::new(false),
                bounds: Cell::new(Bounds::default()),
                painted: Cell::new(0),
                viewport: Cell::new(None),
                menu: RefCell::default(),
                menu_armed: Cell::new(false),
            });
            RovingItems::register(cx, &slot);
            slots.push(slot);
        }
        slots[index].clone()
    }

    /// The `index`th slot, focused through `handle` (a text input's own).
    fn slot_for(&self, index: usize, handle: &FocusHandle, cx: &App) -> Rc<Slot> {
        let current = self.slot(index, cx);
        if current.handle == *handle {
            return current;
        }
        let slot = Rc::new(Slot {
            handle: handle.clone(),
            group: self.group,
            entry: self.entry.clone(),
            was_focused: Cell::new(false),
            bounds: Cell::new(Bounds::default()),
            painted: Cell::new(0),
            viewport: Cell::new(None),
            menu: RefCell::default(),
            menu_armed: Cell::new(false),
        });
        RovingItems::register(cx, &slot);
        self.slots.borrow_mut()[index] = slot.clone();
        slot
    }

    /// An arrow coming into the list from another lands on the `index`th stop (the playing item),
    /// not the nearest one.
    pub fn set_entry(&self, index: Option<usize>, cx: &App) {
        let handle = index.map(|index| self.handle(index, cx));
        *self.entry.borrow_mut() = handle;
    }

    pub fn handle(&self, index: usize, cx: &App) -> FocusHandle {
        self.slot(index, cx).handle.clone()
    }

    /// Records the stop's bounds each frame and reveals it when it gains focus, in `inner` (a
    /// horizontal row) and then in the list's own scroll container.
    fn tracker(&self, slot: Rc<Slot>, inner: Option<ScrollHandle>) -> impl IntoElement {
        let scroll = self.scroll.clone();
        canvas(
            |_, _, _| {},
            move |bounds, _, window, cx| {
                slot.bounds.set(bounds);
                slot.painted.set(cx.global::<RovingItems>().frame.get());
                slot.viewport.set(scroll.as_ref().map(ScrollHandle::bounds));
                let focused = slot.handle.is_focused(window);
                if focused && !slot.was_focused.get() {
                    for scroll in inner.iter().chain(scroll.iter()) {
                        reveal(scroll, bounds, window);
                    }
                }
                slot.was_focused.set(focused);
            },
        )
        .absolute()
        .top_0()
        .left_0()
        .size_full()
    }

    /// Makes `element` the list's `index`th stop. It needs an id and a role of its own.
    pub fn item(&self, index: usize, element: Stateful<Div>, cx: &App) -> Stateful<Div> {
        self.item_in(index, element, None, cx)
    }

    /// [`Self::item`] inside a horizontally scrolling row, `row`.
    pub fn item_in(&self, index: usize, element: Stateful<Div>, row: Option<ScrollHandle>, cx: &App) -> Stateful<Div> {
        self.ringed(self.slot(index, cx), element, row, cx)
    }

    /// [`Self::item`] focused through `handle`, one the view holds to focus it by.
    pub fn item_for(&self, index: usize, handle: &FocusHandle, element: Stateful<Div>, cx: &App) -> Stateful<Div> {
        self.ringed(self.slot_for(index, handle, cx), element, None, cx)
    }

    fn ringed(&self, slot: Rc<Slot>, element: Stateful<Div>, row: Option<ScrollHandle>, cx: &App) -> Stateful<Div> {
        let ring = cx.theme().ring;
        let menu = slot.take_menu();
        let element = element.when_some(menu.clone(), |element, menu| {
            element.on_a11y_action(AccessibleAction::ShowContextMenu, move |_, window, cx| open_menu(&menu, window, cx))
        });
        on_menu_key(
            &slot,
            menu,
            element
                .track_focus(&slot.handle)
                .relative()
                .focus_visible(move |style| {
                    style.shadow(vec![BoxShadow {
                        color: ring,
                        offset: point(px(0.), px(0.)),
                        blur_radius: px(0.),
                        spread_radius: px(RING),
                        inset: true,
                    }])
                })
                .child(self.tracker(slot.clone(), row)),
        )
    }

    /// Makes `button` the list's `index`th stop (a chip, a pill), reachable by the arrows too.
    pub fn button(&self, index: usize, button: Button, cx: &App) -> Div {
        self.button_in(index, button, None, cx)
    }

    /// [`Self::button`] inside a horizontally scrolling row, `row`.
    pub fn button_in(&self, index: usize, button: Button, row: Option<ScrollHandle>, cx: &App) -> Div {
        let handle = self.handle(index, cx);
        // The button writes its own Tab flag to the handle; a list's stop keeps the list's.
        let button = button.tab_stop(handle.tab_stop).track_focus(&handle);
        let slot = self.slot(index, cx);
        // A press on the button is the button's: a clickable row or bar around it must not take it too.
        on_arrows(
            div()
                .relative()
                .flex_shrink_0()
                .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                .child(button)
                .child(self.tracker(slot, row)),
        )
    }

    /// Makes `element`, already tracking [`Self::handle`]`(index)`, the list's `index`th stop.
    pub fn wrap(&self, index: usize, element: impl IntoElement, cx: &App) -> Div {
        let slot = self.slot(index, cx);
        let menu = slot.take_menu();
        on_menu_key(
            &slot,
            menu,
            div().relative().flex_shrink_0().child(element).child(self.tracker(slot.clone(), None)),
        )
    }

    /// [`Self::wrap`] for `element` already tracking `handle`, one the view holds to focus it by.
    pub fn wrap_for(&self, index: usize, handle: &FocusHandle, element: impl IntoElement, cx: &App) -> Div {
        let slot = self.slot_for(index, handle, cx);
        let menu = slot.take_menu();
        on_menu_key(
            &slot,
            menu,
            div().relative().flex_shrink_0().child(element).child(self.tracker(slot.clone(), None)),
        )
    }

    /// Scrolls the `index`th stop into view on the next paint, as when it gains focus: a toggle
    /// that keeps focus while the rows above it go away.
    pub fn reveal(&self, index: usize, cx: &App) {
        self.slot(index, cx).was_focused.set(false);
    }

    /// Whether one of the list's stops holds focus.
    pub fn has_focus(&self, window: &Window) -> bool {
        self.slots.borrow().iter().any(|slot| slot.handle.is_focused(window))
    }

    /// The stop holding focus.
    pub fn focused(&self, window: &Window) -> Option<usize> {
        self.slots.borrow().iter().position(|slot| slot.handle.is_focused(window))
    }

    /// The positions a lazy list of `items`, each its own Tab stop, keeps built wherever it is
    /// scrolled: the first and last (Tab, Shift+Tab, Home and End reach them) and the focused one.
    pub fn kept_stops(&self, items: Range<usize>, window: &Window) -> Vec<usize> {
        let mut kept: Vec<usize> = [Some(0), items.len().checked_sub(1)].into_iter().flatten().collect();
        kept.extend(self.focused(window).filter(|stop| items.contains(stop)).map(|stop| stop - items.start));
        kept
    }

    /// Makes `element`, a full-width row already tracking [`Self::handle`]`(index)`, the list's `index`th
    /// stop, ringed inside: a control's own ring is drawn outside it, where the row's card clips it.
    pub fn row(&self, index: usize, element: impl IntoElement, cx: &App) -> Div {
        let slot = self.slot(index, cx);
        let (handle, ring) = (slot.handle.clone(), cx.theme().ring);
        let ring = canvas(
            |_, _, _| {},
            move |bounds, _, window, _| {
                if handle.is_focused(window) && window.last_input_was_keyboard() {
                    window.paint_quad(quad(bounds, px(0.), transparent_black(), px(RING), ring, BorderStyle::Solid));
                }
            },
        )
        .absolute()
        .top_0()
        .left_0()
        .size_full();
        let menu = slot.take_menu();
        on_menu_key(
            &slot,
            menu,
            div().relative().w_full().child(element).child(self.tracker(slot.clone(), None)).child(ring),
        )
    }

    /// [`Self::row`] for a row that is one button.
    pub fn row_button(&self, index: usize, button: Button, cx: &App) -> Div {
        let handle = self.handle(index, cx);
        self.row(index, button.track_focus(&handle), cx)
    }

    /// Makes the text field `element`, whose input focuses through `input`, the list's `index`th stop:
    /// Up and Down move on from it, Left and Right stay with the caret.
    pub fn field(&self, index: usize, input: &FocusHandle, element: impl IntoElement, cx: &App) -> Div {
        let slot = self.slot_for(index, input, cx);
        arrows(div().relative().child(element).child(self.tracker(slot, None)), false)
    }

    /// Makes `slider`, driven by `state`, the list's `index`th stop as one control: the arrows along it
    /// (and Home, End) step it, the arrows across it move on. `keys` says how it steps.
    pub fn slider(
        &self,
        index: usize,
        state: &Entity<SliderState>,
        spec: SliderStop,
        slider: impl IntoElement,
        cx: &App,
    ) -> Stateful<Div> {
        let slot = self.slot(index, cx);
        let press_focus = slot.handle.clone();
        let model = state.read(cx);
        let (value, min, max) = (model.value().end(), model.min_value(), model.max_value());
        let step = spec.step.unwrap_or_else(|| model.accessibility_step());
        let vertical = spec.vertical;
        let act = move |delta: f32, state: &Entity<SliderState>, window: &mut Window, cx: &mut App| {
            state.update(cx, |state, cx| state.step_thumb(false, delta, window, cx));
        };
        let on_step = spec.on_step.clone();
        let key_state = state.clone();
        let element = div()
            .id(spec.id)
            .role(Role::Slider)
            .layout_direction(LayoutDirection::Ltr)
            .aria_label(spec.label)
            .aria_numeric_value(value as f64)
            .aria_min_numeric_value(min as f64)
            .aria_max_numeric_value(max as f64)
            .aria_numeric_value_step(step as f64)
            .when_some(spec.value_text, |element, text| element.aria_value(text))
            .aria_orientation(if vertical { Orientation::Vertical } else { Orientation::Horizontal })
            .on_key_down(move |event: &KeyDownEvent, window, cx| {
                let modifiers = &event.keystroke.modifiers;
                if modifiers.platform || modifiers.control || modifiers.alt || modifiers.shift {
                    return;
                }
                let delta = match (event.keystroke.key.as_str(), vertical) {
                    ("right", false) | ("up", true) => 1.,
                    ("left", false) | ("down", true) => -1.,
                    ("home", _) if on_step.is_none() => min - max,
                    ("end", _) if on_step.is_none() => max - min,
                    _ => return,
                };
                match &on_step {
                    Some(on_step) => on_step(delta, window, cx),
                    None => act(if delta.abs() == 1. { delta * step } else { delta }, &key_state, window, cx),
                }
                cx.stop_propagation();
            });
        let (increment, decrement) = (spec.on_step.clone(), spec.on_step);
        let (up_state, down_state) = (state.clone(), state.clone());
        let element = element
            .on_a11y_action(AccessibleAction::Increment, move |_, window, cx| match &increment {
                Some(on_step) => on_step(1., window, cx),
                None => act(step, &up_state, window, cx),
            })
            .on_a11y_action(AccessibleAction::Decrement, move |_, window, cx| match &decrement {
                Some(on_step) => on_step(-1., window, cx),
                None => act(-step, &down_state, window, cx),
            })
            .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                window.focus(&press_focus, cx);
                cx.stop_propagation();
            })
            .child(slider);
        self.ringed(slot, element, None, cx)
    }

    /// Makes `button` the `index`th item's ⋮: not a Tab stop; Shift+F10 or the Menu key on the item
    /// opens it, and so does the screen reader's show-menu action. `button` carries the dropdown menu.
    /// Call it before the item's own builder ([`Self::item`], [`Self::wrap`], [`Self::row`]) in every build.
    pub fn menu_button(&self, index: usize, button: Button, cx: &App) -> Button {
        let slot = self.slot(index, cx);
        let handle = slot.menu.borrow_mut().get_or_insert_with(|| cx.focus_handle()).clone();
        slot.menu_armed.set(true);
        button.tab_stop(false).track_focus(&handle)
    }

    /// Makes `button` the `control`th control inside the `index`th item, one whose action the item or
    /// its ⋮ also offers: not a Tab stop.
    pub fn trailing_button(&self, index: usize, control: usize, button: Button, cx: &App) -> Button {
        let handle = self.trailing(index, control, false, cx);
        button.tab_stop(false).track_focus(&handle)
    }

    /// Makes `button` the `control`th control inside the `index`th item, one nothing else reaches by
    /// keyboard: in the Tab order right after its item, and while focus is outside a
    /// [`Self::list_stops`] list only with the item holding its stop.
    pub fn trailing_stop(&self, index: usize, control: usize, button: Button, cx: &App) -> Button {
        let handle = self.trailing(index, control, true, cx);
        button.tab_stop(handle.tab_stop).track_focus(&handle)
    }

    fn trailing(&self, index: usize, control: usize, stop: bool, cx: &App) -> FocusHandle {
        let item_stop = stop && self.slots.borrow().get(index).is_none_or(|slot| slot.handle.clone().tab_stop);
        let mut trailing = self.trailing.borrow_mut();
        let controls = trailing.entry(index).or_default();
        while controls.len() <= control {
            controls.push((cx.focus_handle().tab_stop(item_stop), stop));
        }
        controls[control].0.clone()
    }

    /// The stops in `items` as one list. While focus is outside it, Tab and Shift+Tab enter on one
    /// item, the one focused last (until then the [`Self::set_entry`] one, else the first); while it
    /// holds focus every item (and its [`Self::trailing_stop`]s) is a Tab stop in index order, so Tab
    /// past the last item or Shift+Tab before the first leaves the list.
    /// Call it before rendering the items, whose focus handles copy the Tab order then;
    /// [`ListStops::wrap`] makes their container the list.
    pub fn list_stops(&self, items: Range<usize>, window: &Window, cx: &App) -> ListStops<'_> {
        let held = self.tab_items.borrow_mut().entry(items.start).or_default().clone();
        let trailing = self.trailing.borrow();
        let controls = |index: usize| trailing.get(&index).into_iter().flatten();
        let focused = items.clone().position(|index| {
            let slot = self.slot(index, cx);
            let menu_focused = slot.menu.borrow().as_ref().is_some_and(|menu| menu.is_focused(window));
            slot.handle.is_focused(window)
                || menu_focused
                || controls(index).any(|(handle, _)| handle.is_focused(window))
        });
        if focused.is_some() {
            held.set(focused);
        }
        let entry = self.entry.borrow().clone();
        let entry = entry.and_then(|entry| items.clone().position(|index| self.handle(index, cx) == entry));
        let active = held.get().or(entry).unwrap_or(0).min(items.len().saturating_sub(1));
        for (position, index) in items.clone().enumerate() {
            let stop = focused.is_some() || position == active;
            // Sets the shared flag, which each element's copy of the handle reads.
            self.handle(index, cx).tab_stop(stop);
            for (handle, _) in controls(index).filter(|(_, tab_stop)| *tab_stop) {
                handle.clone().tab_stop(stop);
            }
        }
        ListStops { list: self, items, active, focused: focused.is_some() }
    }

    /// Home and End over the stops in `items` that are on screen.
    pub fn container<E: InteractiveElement>(&self, element: E, items: Range<usize>, cx: &App) -> E {
        let slots: Vec<Rc<Slot>> = items.map(|index| self.slot(index, cx)).collect();
        element.on_key_down(move |event: &KeyDownEvent, window, cx| {
            if !slots.iter().any(|slot| slot.handle.is_focused(window)) {
                return;
            }
            let on_screen = RovingItems::on_screen(window, cx);
            let mut shown = slots.iter().filter(|slot| on_screen.iter().any(|painted| Rc::ptr_eq(painted, slot)));
            let target = match event.keystroke.key.as_str() {
                "home" => shown.next(),
                "end" => shown.next_back(),
                _ => None,
            };
            if let Some(target) = target {
                let handle = target.handle.clone();
                window.focus(&handle, cx);
                cx.stop_propagation();
            }
        })
    }
}

/// Keeps keyboard and screen-reader focus in place across a "load more", as the JVM app's
/// `LoadMoreFocus`: activating the control ([`Self::start`]) focuses the [`Self::indicator`] shown
/// while the page loads; once it settles ([`Self::settle`], called from the view's render before its
/// items) focus moves to the first new item, else to the control that is back (Show more again,
/// Retry), else to the last item, else to the view's fallback, unless the user moved focus off the
/// indicator meanwhile.
pub struct LoadMoreFocus {
    indicator: FocusHandle,
    from: Option<usize>,
}

impl LoadMoreFocus {
    pub fn new(cx: &App) -> Self {
        Self { indicator: cx.focus_handle().tab_stop(true), from: None }
    }

    /// The control was activated with `count` items loaded.
    pub fn start(&mut self, count: usize, window: &mut Window, cx: &mut App) {
        self.from = Some(count);
        window.focus(&self.indicator, cx);
    }

    /// A load the user started has not settled yet.
    pub fn pending(&self) -> bool {
        self.from.is_some()
    }

    /// The spinner of a load in progress (`content`), read as `label`: focusable while a load the
    /// user started is pending.
    pub fn indicator(
        &self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
        content: impl IntoElement,
        cx: &App,
    ) -> Stateful<Div> {
        let ring = cx.theme().ring;
        div()
            .id(id)
            .role(Role::ProgressIndicator)
            .aria_label(label.into())
            .rounded_full()
            .when(self.from.is_some(), |indicator| {
                indicator.track_focus(&self.indicator).focus_visible(move |style| {
                    style.shadow(vec![BoxShadow {
                        color: ring,
                        offset: point(px(0.), px(0.)),
                        blur_radius: px(0.),
                        spread_radius: px(RING),
                        inset: true,
                    }])
                })
            })
            .child(content)
    }

    /// Takes the pending start once the load is over (`loading` false) with `count` items: `None`
    /// while it loads, with nothing pending, or once focus has left the indicator.
    pub fn settle(&mut self, loading: bool, count: usize, window: &Window) -> Option<Settled> {
        let from = self.from?;
        if loading {
            return None;
        }
        self.from = None;
        self.indicator.is_focused(window).then_some(Settled { from, count })
    }
}

/// A load [`LoadMoreFocus::settle`] found over, while its indicator held focus.
pub struct Settled {
    from: usize,
    count: usize,
}

impl Settled {
    /// The first there is of: the first new item, `control`, the last item, `fallback`. `item` gives
    /// the focus stop of the item at an index, `None` for an item without one.
    pub fn target<Stop>(
        &self,
        item: impl Fn(usize) -> Option<Stop>,
        control: Option<Stop>,
        fallback: Option<Stop>,
    ) -> Option<Stop> {
        (self.count > self.from)
            .then(|| item(self.from))
            .flatten()
            .or(control)
            .or_else(|| self.from.checked_sub(1).and_then(&item))
            .or(fallback)
    }
}

/// A list's items from [`FocusList::list_stops`].
pub struct ListStops<'a> {
    list: &'a FocusList,
    items: Range<usize>,
    active: usize,
    focused: bool,
}

impl ListStops<'_> {
    /// The positions a lazy list keeps built wherever it is scrolled: the item Tab enters on, and
    /// while the list holds focus its first and last, for Home and End and for Tab to leave past them.
    pub fn kept(&self) -> Vec<usize> {
        let mut kept = vec![self.active];
        if self.focused && !self.items.is_empty() {
            kept.extend([0, self.items.len() - 1]);
        }
        kept
    }

    /// Makes `element`, holding the items, the list: named `label`, its size the items' count, Home
    /// and End over them. Each item is a [`list_item`].
    pub fn wrap(self, element: Stateful<Div>, label: Option<SharedString>, cx: &App) -> Stateful<Div> {
        let element = element
            .role(Role::List)
            .aria_size_of_set(self.items.len())
            .when_some(label, |list, label| list.aria_label(label));
        self.list.container(element, self.items, cx)
    }
}

/// Marks `element` the item at `position` (from 0) of the [`ListStops::wrap`] list around it, which
/// the screen reader reads as "3 of 20".
pub fn list_item(element: Stateful<Div>, position: usize) -> Stateful<Div> {
    element.role(Role::ListItem).aria_position_in_set(position)
}

/// `element` read as dimmed while `disabled`, as gpui-base marks a disabled control's node: gpui's
/// div has no disabled setter. It adds no layout or accessibility node, and leaves focus and
/// listeners as they are.
pub fn disabled<E: Element>(element: E, disabled: bool) -> A11yDisabled<E> {
    A11yDisabled { inner: element, disabled }
}

pub struct A11yDisabled<E> {
    inner: E,
    disabled: bool,
}

impl<E: Element> IntoElement for A11yDisabled<E> {
    type Element = Self;

    fn into_element(self) -> Self {
        self
    }
}

impl<E: Element> Element for A11yDisabled<E> {
    type RequestLayoutState = E::RequestLayoutState;
    type PrepaintState = E::PrepaintState;

    fn id(&self) -> Option<ElementId> {
        self.inner.id()
    }

    fn source_location(&self) -> Option<&'static std::panic::Location<'static>> {
        self.inner.source_location()
    }

    fn request_layout(
        &mut self,
        id: Option<&GlobalElementId>,
        inspector: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, Self::RequestLayoutState) {
        self.inner.request_layout(id, inspector, window, cx)
    }

    fn prepaint(
        &mut self,
        id: Option<&GlobalElementId>,
        inspector: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        layout: &mut Self::RequestLayoutState,
        window: &mut Window,
        cx: &mut App,
    ) -> Self::PrepaintState {
        self.inner.prepaint(id, inspector, bounds, layout, window, cx)
    }

    fn paint(
        &mut self,
        id: Option<&GlobalElementId>,
        inspector: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        layout: &mut Self::RequestLayoutState,
        prepaint: &mut Self::PrepaintState,
        window: &mut Window,
        cx: &mut App,
    ) {
        self.inner.paint(id, inspector, bounds, layout, prepaint, window, cx)
    }

    fn a11y_role(&self) -> Option<Role> {
        self.inner.a11y_role()
    }

    fn write_a11y_info(&self, node: &mut accesskit::Node) {
        self.inner.write_a11y_info(node);
        if self.disabled {
            node.set_disabled();
        }
    }

    fn a11y_synthetic_children(&mut self, prepaint: &mut Self::PrepaintState, builder: &mut A11ySubtreeBuilder) {
        self.inner.a11y_synthetic_children(prepaint, builder);
    }
}

/// A heading named by `text`, at `level`: 1 is the page's title, 2 a section of it. A string child
/// has no accessibility node of its own, so the name is set as well.
pub fn heading(id: impl Into<ElementId>, level: usize, text: impl Into<SharedString>) -> Stateful<Div> {
    let text = text.into();
    div().id(id).role(Role::Heading).aria_level(level).aria_label(text.clone()).child(text)
}

/// `text` drawn and read as static text: the screen reader reads it with its reading keys, and Tab
/// passes it by.
pub fn label(id: impl Into<ElementId>, text: impl Into<SharedString>) -> Stateful<Div> {
    let text = text.into();
    // AccessKit names a label by its value, not its label, on every platform.
    div().id(id).role(Role::Label).aria_value(text.clone()).child(text)
}

/// A [`label`] the screen reader announces when it appears and whenever it changes: a status, the
/// message of an empty page. AccessKit announces nodes that appear together in no set order, so a
/// screen announces one: [`announced_as`] speaks the lines before it too.
pub fn announced(id: impl Into<ElementId>, text: impl Into<SharedString>) -> Stateful<Div> {
    label(id, text).aria_live(Live::Polite)
}

/// An [`announced`] label showing `shown` that speaks `spoken`: the screen's whole announcement
/// (its title, then this line), in order.
pub fn announced_as(
    id: impl Into<ElementId>,
    shown: impl Into<SharedString>,
    spoken: impl Into<SharedString>,
) -> Stateful<Div> {
    div().id(id).role(Role::Label).aria_value(spoken.into()).aria_live(Live::Polite).child(shown.into())
}

/// An [`announced`] label that interrupts the screen reader: an error that stops what the user did.
pub fn alert(id: impl Into<ElementId>, text: impl Into<SharedString>) -> Stateful<Div> {
    label(id, text).aria_live(Live::Assertive)
}

/// A slider's arrow press, called with -1 or 1.
pub type OnStep = Rc<dyn Fn(f32, &mut Window, &mut App)>;

/// How a [`FocusList::slider`] reads and steps.
pub struct SliderStop {
    pub id: ElementId,
    pub label: SharedString,
    /// What the value means to a listener ("1:05 / 3:20"); the number alone when `None`.
    pub value_text: Option<SharedString>,
    /// One press of an arrow along it; the slider's own step when `None`.
    pub step: Option<f32>,
    pub vertical: bool,
    /// Called with -1 or 1 per press in place of stepping the value: a seek bar, which acts only
    /// when a drag ends.
    pub on_step: Option<OnStep>,
}

impl SliderStop {
    pub fn new(id: impl Into<ElementId>, label: impl Into<SharedString>) -> Self {
        Self { id: id.into(), label: label.into(), value_text: None, step: None, vertical: false, on_step: None }
    }
}

/// Scrolls `scroll` the least that shows `item` (both in window coordinates).
fn reveal(scroll: &ScrollHandle, item: Bounds<Pixels>, window: &mut Window) {
    let viewport = scroll.bounds();
    let offset = scroll.offset();
    let max = scroll.max_offset();
    let margin = px(REVEAL_MARGIN);
    let mut y = offset.y;
    if item.bottom() + margin > viewport.bottom() {
        y -= item.bottom() + margin - viewport.bottom();
    }
    if item.top() - margin < viewport.top() {
        y += viewport.top() - (item.top() - margin);
    }
    let mut x = offset.x;
    if item.right() + margin > viewport.right() {
        x -= item.right() + margin - viewport.right();
    }
    if item.left() - margin < viewport.left() {
        x += viewport.left() - (item.left() - margin);
    }
    let y = y.clamp(-max.y, px(0.));
    let (low, high) = scroll.offset_range_x();
    let x = x.clamp(low, high);
    if (x, y) != (offset.x, offset.y) {
        scroll.set_offset(point(x, y));
        // Runs during paint, where a refresh is ignored.
        window.on_next_frame(|window, _| window.refresh());
    }
}

/// A single control drawn as a div: a tab stop with the focus ring. It needs an id and a role.
pub trait KeyboardFocusable {
    fn keyboard_focusable(self, ring: Hsla) -> Self;
}

impl KeyboardFocusable for Stateful<Div> {
    fn keyboard_focusable(self, ring: Hsla) -> Self {
        self.tab_index(0).focus_visible(move |style| {
            style.shadow(vec![BoxShadow {
                color: ring,
                offset: point(px(0.), px(0.)),
                blur_radius: px(0.),
                spread_radius: px(RING),
                inset: true,
            }])
        })
    }
}

pub fn focusable(element: Stateful<Div>, cx: &App) -> Stateful<Div> {
    element.keyboard_focusable(cx.theme().ring)
}

/// Debug builds: gpui's own warnings (an unannounced focus, a node without a role) go to stderr.
pub fn install_debug_log() {
    struct Stderr;
    impl log::Log for Stderr {
        fn enabled(&self, metadata: &log::Metadata) -> bool {
            metadata.level() <= log::Level::Info
        }
        fn log(&self, record: &log::Record) {
            if self.enabled(record.metadata()) {
                ym_common::log::stderr_line(format_args!("[{} {}] {}", record.level(), record.target(), record.args()));
            }
        }
        fn flush(&self) {}
    }
    static LOGGER: Stderr = Stderr;
    if cfg!(debug_assertions) && log::set_logger(&LOGGER).is_ok() {
        log::set_max_level(log::LevelFilter::Info);
    }
}

/// Debug: `YMUSIC_DEV_A11Y_DUMP=<file>` writes the accessibility tree there every second while an
/// assistive client is connected.
pub fn dev_dump_tree<V: 'static>(window: &mut Window, cx: &mut Context<V>) {
    let Some(path) = std::env::var_os("YMUSIC_DEV_A11Y_DUMP").filter(|_| cfg!(debug_assertions)) else { return };
    let executor = cx.background_executor().clone();
    cx.spawn_in(window, async move |_, cx| {
        loop {
            executor.timer(std::time::Duration::from_secs(1)).await;
            let dump = cx.update(|window, _| (window.is_a11y_active(), window.debug_a11y_tree_json()));
            let Ok((active, json)) = dump else { return };
            let text = format!("active={active}\nappkit_focus={}\n{}", appkit_focus(), json.unwrap_or_default());
            if let Err(error) = std::fs::write(&path, text) {
                eprintln!("YMUSIC_DEV_A11Y_DUMP: {error}");
                return;
            }
        }
    })
    .detach();
}

/// What AppKit answers when VoiceOver asks the app for its focused element.
#[cfg(target_os = "macos")]
fn appkit_focus() -> String {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::{AnyClass, AnyObject};

    fn text(object: Option<Retained<AnyObject>>) -> String {
        let Some(object) = object else { return "nil".into() };
        // SAFETY: an NSString (or nil), read on the main thread.
        unsafe {
            let utf8: *const std::ffi::c_char = msg_send![&*object, UTF8String];
            if utf8.is_null() { "?".into() } else { std::ffi::CStr::from_ptr(utf8).to_string_lossy().into_owned() }
        }
    }
    // SAFETY: NSApplication / NSAccessibility queries on the main thread.
    unsafe {
        let Some(app_class) = AnyClass::get(c"NSApplication") else { return "no NSApplication".into() };
        let app: Retained<AnyObject> = msg_send![app_class, sharedApplication];
        let key_window: Option<Retained<AnyObject>> = msg_send![&*app, keyWindow];
        let responder = match &key_window {
            Some(window) => {
                let responder: Option<Retained<AnyObject>> = msg_send![&**window, firstResponder];
                responder.map(|responder| {
                    let class: Retained<AnyObject> = msg_send![&*responder, className];
                    text(Some(class))
                })
            }
            None => None,
        };
        let focused: Option<Retained<AnyObject>> = msg_send![&*app, accessibilityFocusedUIElement];
        let (role, label) = match &focused {
            Some(element) => {
                let role: Option<Retained<AnyObject>> = msg_send![&**element, accessibilityRole];
                let label: Option<Retained<AnyObject>> = msg_send![&**element, accessibilityTitle];
                (text(role), text(label))
            }
            None => ("nil".into(), "nil".into()),
        };
        let content = match &key_window {
            Some(window) => {
                let view: Option<Retained<AnyObject>> = msg_send![&**window, contentView];
                view.map(|view| {
                    let class: Retained<AnyObject> = msg_send![&*view, className];
                    let subviews: Retained<AnyObject> = msg_send![&*view, subviews];
                    let count: usize = msg_send![&*subviews, count];
                    let focused: Option<Retained<AnyObject>> = msg_send![&*view, accessibilityFocusedUIElement];
                    let focused_role = focused.map(|element| {
                        let role: Option<Retained<AnyObject>> = msg_send![&*element, accessibilityRole];
                        text(role)
                    });
                    format!(
                        "{}({count} subviews, ax_focus={})",
                        text(Some(class)),
                        focused_role.unwrap_or_else(|| "nil".into())
                    )
                })
            }
            None => None,
        };
        format!(
            "content_view={} key_window={} first_responder={} focused_role={role} focused_label={label}",
            content.unwrap_or_else(|| "nil".into()),
            key_window.is_some(),
            responder.unwrap_or_else(|| "nil".into())
        )
    }
}

#[cfg(not(target_os = "macos"))]
fn appkit_focus() -> String {
    String::new()
}

/// Pointer travel before a press on a row becomes a drag rather than a click.
const DRAG_THRESHOLD: f32 = 4.;

/// A horizontal row the mouse can drag sideways, like a touch screen. A press that moves past
/// the threshold scrolls the row with the pointer and does not click what it started on.
#[derive(Clone, Default)]
pub struct DragRow {
    scroll: ScrollHandle,
    /// Where the drag started: pointer x and the row's offset x then.
    start: Rc<Cell<Option<(Pixels, Pixels)>>>,
    dragging: Rc<Cell<bool>>,
}

impl DragRow {
    pub fn scroll(&self) -> &ScrollHandle {
        &self.scroll
    }

    /// Wraps `row`, which tracks [`Self::scroll`], with the drag handling.
    pub fn wrap(&self, row: impl IntoElement) -> Div {
        let state = self.clone();
        div().relative().child(row).child(
            canvas(
                |_, _, _| {},
                move |bounds, _, window, _| {
                    let down = state.clone();
                    window.on_mouse_event(move |event: &MouseDownEvent, phase, _, _| {
                        if phase == DispatchPhase::Capture
                            && event.button == MouseButton::Left
                            && bounds.contains(&event.position)
                        {
                            down.start.set(Some((event.position.x, down.scroll.offset().x)));
                            down.dragging.set(false);
                        }
                    });
                    let moved = state.clone();
                    window.on_mouse_event(move |event: &MouseMoveEvent, phase, window, cx| {
                        if phase != DispatchPhase::Capture {
                            return;
                        }
                        let Some((start_x, start_offset)) = moved.start.get() else { return };
                        if event.pressed_button != Some(MouseButton::Left) {
                            moved.start.set(None);
                            moved.dragging.set(false);
                            return;
                        }
                        let travel = event.position.x - start_x;
                        if f32::from(travel).abs() > DRAG_THRESHOLD {
                            moved.dragging.set(true);
                        }
                        if moved.dragging.get() {
                            let (low, high) = moved.scroll.offset_range_x();
                            let offset = moved.scroll.offset();
                            moved.scroll.set_offset(point((start_offset + travel).clamp(low, high), offset.y));
                            window.refresh();
                            cx.stop_propagation();
                        }
                    });
                    let up = state.clone();
                    window.on_mouse_event(move |_: &MouseUpEvent, phase, _, cx| {
                        if phase != DispatchPhase::Capture || up.start.take().is_none() {
                            return;
                        }
                        if up.dragging.replace(false) {
                            cx.stop_propagation();
                        }
                    });
                },
            )
            .absolute()
            .top_0()
            .left_0()
            .size_full(),
        )
    }
}
