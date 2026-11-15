//! Drag-to-reorder for a list of rows, as the JVM app's `ReorderDragState`: a press on a row's
//! handle lifts the row, it follows the pointer above the others, rows swap as its center crosses a
//! neighbour, and the list scrolls while the pointer is held near its top or bottom edge. A row that
//! changes places (a swap, a key, a drop) eases from where it was drawn to its new slot, as Compose's
//! `animateItem`, unless the system asks for reduced motion.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::{Duration, Instant};

use gpui_kit::component::ActiveTheme;
use gpui_kit::*;

use crate::queue_nav::moved_index;

/// The pointer this near the list's top or bottom edge scrolls it while dragging a row.
const EDGE: f32 = 32.;
const SCROLL_MAX: f32 = 24.;
/// Above the dialogs (`10 + layer`), so a row lifted in one draws over its siblings; under popups.
const LIFTED_PRIORITY: usize = gpui_kit::base::POPUP_PRIORITY - 1;
/// How long a row takes to reach its new slot.
const GLIDE: Duration = Duration::from_millis(180);

/// The operating system's reduced-motion preference. The app turns gpui's own flag on for every
/// widget; the reorder glide is the one motion it keeps, and only while the system allows it.
pub struct SystemMotion {
    pub reduce: bool,
}

impl Global for SystemMotion {}

impl SystemMotion {
    /// Keeps the preference gpui-kit's init read into gpui's flag (macOS, Windows): call before the
    /// app overrides that flag.
    pub fn init(cx: &mut App) {
        let reduce = cx.reduce_motion();
        cx.set_global(Self { reduce });
    }

    /// Follows the XDG desktop portal's preference: it answers after start, when gpui's flag is the
    /// app's already, so gpui-kit's reading of it never lands.
    #[cfg(target_os = "linux")]
    pub fn follow_portal(log: ym_common::log::Logger, cx: &mut App) {
        cx.spawn(async move |cx| {
            if let Err(error) = follow_portal(cx).await {
                log.warn("the desktop portal gives no reduced-motion preference", Some(&error));
            }
        })
        .detach();
    }
}

#[cfg(target_os = "linux")]
async fn follow_portal(cx: &mut AsyncApp) -> Result<(), ashpd::Error> {
    use ashpd::desktop::settings::{ReducedMotion, Settings};
    use futures::StreamExt as _;
    let apply = |preference: ReducedMotion, cx: &mut AsyncApp| {
        let reduce = matches!(preference, ReducedMotion::ReducedMotion);
        cx.update(|cx| cx.set_global(SystemMotion { reduce }));
    };
    let settings = Settings::new().await?;
    apply(settings.reduced_motion().await?, cx);
    let mut changes = std::pin::pin!(settings.receive_reduced_motion_changed().await?);
    while let Some(preference) = changes.next().await {
        apply(preference, cx);
    }
    Ok(())
}

fn reduce_motion(cx: &App) -> bool {
    cx.try_global::<SystemMotion>().is_some_and(|motion| motion.reduce)
}

pub enum ReorderEvent {
    /// The dragged row moved from `from` to `to`.
    Moved { from: usize, to: usize },
    /// The drag ended: the row that started at `from` lies at `to`.
    Dropped { from: usize, to: usize },
}

type OnEvent = Rc<dyn Fn(&ReorderEvent, &mut Window, &mut App)>;

/// One list's reorder state, shared by the view holding it, its rows ([`Self::row`]) and its
/// [`Self::tracker`].
#[derive(Clone)]
pub struct Reorder(Rc<State>);

struct State {
    scroll: ScrollHandle,
    drag: Cell<Option<Drag>>,
    /// The row just dropped or [moved](Reorder::moved), drawn lifted until it reaches its slot.
    settling: Cell<Option<usize>>,
    rows: RefCell<Vec<Row>>,
    /// The view drawing the list, redrawn as the drag moves.
    view: Cell<Option<EntityId>>,
    on_event: RefCell<Option<OnEvent>>,
    autoscroll: RefCell<Option<Task<()>>>,
}

#[derive(Clone, Copy)]
struct Drag {
    start: usize,
    index: usize,
    pointer_y: Pixels,
    /// The pointer's distance below the row's top at the press.
    grab: Pixels,
}

#[derive(Default)]
struct Row {
    /// The row's slot in window coordinates, as last laid out.
    slot: Bounds<Pixels>,
    /// The slot's top with scrolling left out, as last laid out.
    layout_top: Option<Pixels>,
    /// How far below its slot the row was last drawn.
    offset: Pixels,
    /// Where the row was drawn (in `layout_top`'s terms) before it changed places: it eases from
    /// there once laid out in its new slot.
    moved_from: Option<Pixels>,
    glide: Option<Glide>,
}

#[derive(Clone, Copy)]
struct Glide {
    /// The offset the row starts from.
    from: Pixels,
    started: Instant,
}

impl Row {
    fn drawn_top(&self) -> Option<Pixels> {
        self.layout_top.map(|top| top + self.offset)
    }

    /// How far below its slot the row draws at `now`, laid out at `layout_top`.
    fn glide_offset(&mut self, layout_top: Pixels, now: Instant) -> Pixels {
        if let Some(from) = self.moved_from.take() {
            let distance = from - layout_top;
            self.glide = (distance != px(0.)).then_some(Glide { from: distance, started: now });
        }
        let Some(glide) = self.glide else { return px(0.) };
        let progress = now.duration_since(glide.started).as_secs_f32() / GLIDE.as_secs_f32();
        if progress >= 1. {
            self.glide = None;
            return px(0.);
        }
        glide.from * (1. - progress).powi(3)
    }
}

impl Reorder {
    /// A list scrolled by `scroll`.
    pub fn new(scroll: ScrollHandle) -> Self {
        Self(Rc::new(State {
            scroll,
            drag: Cell::new(None),
            settling: Cell::new(None),
            rows: RefCell::default(),
            view: Cell::new(None),
            on_event: RefCell::new(None),
            autoscroll: RefCell::new(None),
        }))
    }

    /// The row drawn lifted (dragged, or still gliding to where it was dropped or moved), for its look.
    pub fn lifted(&self) -> Option<usize> {
        self.0.drag.get().map(|drag| drag.index).or(self.0.settling.get())
    }

    /// Lifts row `index` under a press at `pointer_y` (window coordinates) on its handle.
    pub fn start(&self, index: usize, pointer_y: Pixels, window: &mut Window, cx: &mut App) {
        let top = self.0.rows.borrow_mut().get_mut(index).map(|row| {
            row.moved_from = None;
            row.glide = None;
            row.slot.top() + row.offset
        });
        let Some(top) = top else { return };
        self.0.settling.set(None);
        self.0.drag.set(Some(Drag { start: index, index, pointer_y, grab: pointer_y - top }));
        self.start_autoscroll(window, cx);
        self.redraw(cx);
    }

    /// Row `from` moved to `to` other than by the drag (a key, Move to top): it glides to its new
    /// slot lifted over the rows it passes, which ease to theirs. Call before the list's own move.
    pub fn moved(&self, from: usize, to: usize, cx: &App) {
        if self.shift(from, to, cx) && !reduce_motion(cx) && self.0.drag.get().is_none() {
            self.0.settling.set(Some(to));
        }
    }

    /// Carries row `from`'s state to `to`; the rows between, and `from` unless dragged, ease from
    /// where they were drawn. False when there is no such move.
    fn shift(&self, from: usize, to: usize, cx: &App) -> bool {
        let mut rows = self.0.rows.borrow_mut();
        if from == to || from >= rows.len() || to >= rows.len() {
            return false;
        }
        if !reduce_motion(cx) {
            let dragged = self.0.drag.get().map(|drag| drag.index);
            for index in from.min(to)..=from.max(to) {
                if Some(index) != dragged {
                    rows[index].moved_from = rows[index].drawn_top();
                }
            }
        }
        let row = rows.remove(from);
        rows.insert(to, row);
        if let Some(settling) = self.0.settling.get() {
            self.0.settling.set(Some(moved_index(settling, from, to)));
        }
        true
    }

    /// Row `index` of the list: drawn where the drag or its glide puts it.
    pub fn row(&self, index: usize, row: impl IntoElement) -> ReorderRow {
        ReorderRow { reorder: self.clone(), index, child: Some(row.into_any_element()) }
    }

    /// Follows the pointer while a row is dragged; `on_event` hears each move and the drop. Draw it
    /// once per frame beside a list of `len` rows.
    pub fn tracker(
        &self,
        len: usize,
        on_event: impl Fn(&ReorderEvent, &mut Window, &mut App) + 'static,
    ) -> impl IntoElement {
        self.0.rows.borrow_mut().resize_with(len, Row::default);
        if self.0.settling.get().is_some_and(|index| index >= len) {
            self.0.settling.set(None);
        }
        *self.0.on_event.borrow_mut() = Some(Rc::new(on_event));
        let reorder = self.clone();
        canvas(
            |_, _, _| {},
            move |_, _, window, _| {
                reorder.0.view.set(Some(window.current_view()));
                if reorder.0.drag.get().is_none() {
                    return;
                }
                let moved = reorder.clone();
                window.on_mouse_event(move |event: &MouseMoveEvent, phase, window, cx| {
                    if phase != DispatchPhase::Capture {
                        return;
                    }
                    let Some(mut drag) = moved.0.drag.get() else { return };
                    if event.pressed_button != Some(MouseButton::Left) {
                        moved.end(window, cx);
                    } else {
                        drag.pointer_y = event.position.y;
                        moved.0.drag.set(Some(drag));
                        moved.follow(window, cx);
                        moved.redraw(cx);
                    }
                    cx.stop_propagation();
                });
                let released = reorder.clone();
                window.on_mouse_event(move |_: &MouseUpEvent, phase, window, cx| {
                    if phase != DispatchPhase::Capture || released.0.drag.get().is_none() {
                        return;
                    }
                    released.end(window, cx);
                    cx.stop_propagation();
                });
            },
        )
        .absolute()
        .top_0()
        .left_0()
        .size_0()
    }

    /// Moves the dragged row to the slot its center is over.
    fn follow(&self, window: &mut Window, cx: &mut App) {
        let Some(mut drag) = self.0.drag.get() else { return };
        let target = {
            let rows = self.0.rows.borrow();
            let Some(dragged) = rows.get(drag.index) else { return };
            let center = dragged_top(&drag, &rows, self.0.scroll.bounds()) + dragged.slot.size.height / 2.;
            rows.iter().position(|row| center >= row.slot.top() && center < row.slot.bottom())
        };
        let Some(to) = target.filter(|to| *to != drag.index) else { return };
        let from = drag.index;
        self.shift(from, to, cx);
        drag.index = to;
        self.0.drag.set(Some(drag));
        self.emit(&ReorderEvent::Moved { from, to }, window, cx);
    }

    fn end(&self, window: &mut Window, cx: &mut App) {
        let Some(drag) = self.0.drag.take() else { return };
        self.0.autoscroll.borrow_mut().take();
        if !reduce_motion(cx)
            && let Some(row) = self.0.rows.borrow_mut().get_mut(drag.index)
        {
            row.moved_from = row.drawn_top();
            self.0.settling.set(Some(drag.index));
        }
        self.emit(&ReorderEvent::Dropped { from: drag.start, to: drag.index }, window, cx);
        self.redraw(cx);
    }

    fn emit(&self, event: &ReorderEvent, window: &mut Window, cx: &mut App) {
        let on_event = self.0.on_event.borrow().clone();
        if let Some(on_event) = on_event {
            on_event(event, window, cx);
        }
    }

    fn redraw(&self, cx: &mut App) {
        if let Some(view) = self.0.view.get() {
            cx.notify(view);
        }
    }

    /// Scrolls the list while the dragged row is held near its top or bottom edge, until the drag ends.
    fn start_autoscroll(&self, window: &Window, cx: &App) {
        let state = Rc::downgrade(&self.0);
        let executor = cx.background_executor().clone();
        let task = window.spawn(cx, async move |cx| {
            loop {
                executor.timer(Duration::from_millis(16)).await;
                let Some(state) = state.upgrade() else { return };
                let reorder = Reorder(state);
                if !matches!(cx.update(|window, cx| reorder.autoscroll_step(window, cx)), Ok(true)) {
                    return;
                }
            }
        });
        *self.0.autoscroll.borrow_mut() = Some(task);
    }

    fn autoscroll_step(&self, window: &mut Window, cx: &mut App) -> bool {
        let Some(drag) = self.0.drag.get() else { return false };
        let scroll = &self.0.scroll;
        let viewport = scroll.bounds();
        let pointer = f32::from(drag.pointer_y);
        let (top, bottom) = (f32::from(viewport.top()), f32::from(viewport.bottom()));
        let overhang = if pointer > bottom - EDGE {
            pointer - (bottom - EDGE)
        } else if pointer < top + EDGE {
            pointer - (top + EDGE)
        } else {
            return true;
        };
        let step = (overhang * 0.5).clamp(-SCROLL_MAX, SCROLL_MAX);
        let offset = scroll.offset();
        let scrolled = (offset.y - px(step)).clamp(-scroll.max_offset().y, px(0.));
        if scrolled == offset.y {
            return true;
        }
        scroll.set_offset(point(offset.x, scrolled));
        for row in self.0.rows.borrow_mut().iter_mut() {
            row.slot.origin.y += scrolled - offset.y;
        }
        self.follow(window, cx);
        self.redraw(cx);
        true
    }
}

/// The dragged row's top: under the pointer, kept within the rows and the viewport.
fn dragged_top(drag: &Drag, rows: &[Row], viewport: Bounds<Pixels>) -> Pixels {
    let top = drag.pointer_y - drag.grab;
    let (Some(first), Some(last), Some(dragged)) = (rows.first(), rows.last(), rows.get(drag.index)) else {
        return top;
    };
    let highest = first.slot.top().max(viewport.top());
    let lowest = last.slot.bottom().min(viewport.bottom()) - dragged.slot.size.height;
    if lowest < highest { top } else { top.clamp(highest, lowest) }
}

/// A lifted row's look: raised on the popover ground with a shadow.
pub fn lift<E: Styled>(row: E, cx: &App) -> E {
    row.bg(cx.theme().popover).shadow(vec![BoxShadow {
        color: black().opacity(0.25),
        offset: point(px(0.), px(2.)),
        blur_radius: px(8.),
        spread_radius: px(0.),
        inset: false,
    }])
}

/// A row of a [`Reorder`] list; the lifted one draws over its siblings, clipped to the list. While
/// any row glides, the view drawing the list redraws every frame.
pub struct ReorderRow {
    reorder: Reorder,
    index: usize,
    child: Option<AnyElement>,
}

impl IntoElement for ReorderRow {
    type Element = Self;

    fn into_element(self) -> Self {
        self
    }
}

impl Element for ReorderRow {
    type RequestLayoutState = ();
    type PrepaintState = ();

    fn id(&self) -> Option<ElementId> {
        None
    }

    fn source_location(&self) -> Option<&'static std::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        let child = self.child.as_mut().expect("a row is laid out once");
        (child.request_layout(window, cx), ())
    }

    fn prepaint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        let state = &self.reorder.0;
        let drag = state.drag.get().filter(|drag| drag.index == self.index);
        let layout_top = bounds.origin.y - window.element_offset().y;
        let (offset, gliding) = {
            let mut rows = state.rows.borrow_mut();
            if let Some(row) = rows.get_mut(self.index) {
                row.slot = bounds;
            }
            let offset = match drag {
                Some(drag) => dragged_top(&drag, &rows, state.scroll.bounds()) - bounds.origin.y,
                None => rows.get_mut(self.index).map_or(px(0.), |row| row.glide_offset(layout_top, Instant::now())),
            };
            let row = rows.get_mut(self.index);
            let gliding = row.as_ref().is_some_and(|row| row.glide.is_some());
            if let Some(row) = row {
                row.layout_top = Some(layout_top);
                row.offset = offset;
            }
            (offset, gliding)
        };
        let lifted = drag.is_some() || state.settling.get() == Some(self.index);
        let settled = lifted && drag.is_none() && !gliding;
        if settled {
            state.settling.set(None);
        }
        if gliding || settled {
            window.request_animation_frame();
        }
        let Some(mut child) = self.child.take() else { return };
        let shift = point(px(0.), offset);
        if lifted {
            window.defer_draw(child, window.element_offset() + shift, LIFTED_PRIORITY, Some(window.content_mask()));
        } else {
            window.with_element_offset(shift, |window| child.prepaint(window, cx));
            self.child = Some(child);
        }
    }

    fn paint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        _: Bounds<Pixels>,
        _: &mut (),
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        if let Some(child) = self.child.as_mut() {
            child.paint(window, cx);
        }
    }
}
