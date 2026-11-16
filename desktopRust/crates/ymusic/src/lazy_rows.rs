//! A long list built only where it shows, as the JVM app's lazy lists: rows within [`OVERDRAW`] of
//! the scroll viewport, plus the pinned ones (the list's Tab stop, a dragged row), are built; the
//! rest are space of their last measured height. The list lives inside its page's own scroll
//! container, so the page's header scrolls with it, and everything that works on that
//! [`ScrollHandle`] (focus reveal, the bars' lift, drag autoscroll) works unchanged.

use std::cell::{Cell, RefCell};
use std::ops::Range;
use std::rc::Rc;

use gpui_kit::*;

/// Built past each edge of the viewport, so the row an arrow key or Tab moves to is already built.
const OVERDRAW: f32 = 400.;
const TOLERANCE: f32 = 0.5;

/// One list's measured rows, kept across frames by the view drawing it.
#[derive(Clone)]
pub struct LazyRows(Rc<State>);

struct State {
    scroll: ScrollHandle,
    /// A row's height until one has been measured.
    estimate: Pixels,
    axis: Axis,
    heights: RefCell<Vec<Option<Pixels>>>,
    /// Each row's start in the list as last rendered, then the list's length plus one gap.
    starts: RefCell<Vec<Pixels>>,
    /// The list's start in the scroll container's content, as last laid out.
    content_start: Cell<Option<Pixels>>,
}

impl LazyRows {
    /// A vertical list scrolled by `scroll`, its rows about `estimate` tall.
    pub fn new(scroll: ScrollHandle, estimate: f32) -> Self {
        Self::along(scroll, estimate, Axis::Vertical)
    }

    /// A horizontal row of items scrolled by `scroll`, each about `estimate` wide. The items lie in
    /// the row's flow, which takes the tallest one's height.
    pub fn horizontal(scroll: ScrollHandle, estimate: f32) -> Self {
        Self::along(scroll, estimate, Axis::Horizontal)
    }

    fn along(scroll: ScrollHandle, estimate: f32, axis: Axis) -> Self {
        Self(Rc::new(State {
            scroll,
            estimate: px(estimate),
            axis,
            heights: RefCell::default(),
            starts: RefCell::default(),
            content_start: Cell::new(None),
        }))
    }

    /// Rows `0..count`, `gap` apart, building `row(index)` for the rows near the viewport and the
    /// `pinned` ones. The rows are positioned in the returned element.
    pub fn render(
        &self,
        count: usize,
        gap: f32,
        pinned: impl IntoIterator<Item = usize>,
        window: &Window,
        mut row: impl FnMut(usize) -> AnyElement,
    ) -> Div {
        let state = &self.0;
        let geometry = Geometry { axis: state.axis, rtl: window.layout_direction().is_rtl() };
        let gap = px(gap);
        let starts = {
            let mut heights = state.heights.borrow_mut();
            heights.resize(count, None);
            let (sum, measured) = heights
                .iter()
                .flatten()
                .fold((px(0.), 0usize), |(sum, measured), height| (sum + *height, measured + 1));
            let estimate = if measured > 0 { sum / measured as f32 } else { state.estimate };
            let mut starts = Vec::with_capacity(count + 1);
            let mut start = px(0.);
            starts.push(start);
            for height in heights.iter() {
                start += height.unwrap_or(estimate) + gap;
                starts.push(start);
            }
            starts
        };
        let length = if count == 0 { px(0.) } else { starts[count] - gap };

        let viewport = state.scroll.bounds();
        let viewport_length = match geometry.length(viewport.size) {
            length if length > px(0.) => length,
            _ => geometry.length(window.viewport_size()),
        };
        let mut position = px(0.);
        if let Some(content_start) = state.content_start.get() {
            let offset = state.scroll.offset();
            position = geometry.scrolled(offset) - content_start;
            // Keeps the row at the viewport's start in place when rows before it change length.
            let old = state.starts.borrow();
            if position > px(0.) && old.len() > 1 {
                let last = (old.len() - 1).min(count);
                let anchor = old[..=last].partition_point(|start| *start <= position).saturating_sub(1);
                let moved = starts[anchor] + (position - old[anchor]);
                if (moved - position).abs() > px(TOLERANCE) {
                    state.scroll.set_offset(geometry.with_scrolled(offset, content_start + moved));
                    position = moved;
                }
            }
        }
        let laid_out = visible(&starts, gap, count, position - px(OVERDRAW)..position + viewport_length + px(OVERDRAW));
        let mut built: Vec<usize> = laid_out.clone().chain(pinned.into_iter().filter(|index| *index < count)).collect();
        built.sort_unstable();
        built.dedup();

        let vertical = geometry.axis == Axis::Vertical;
        let mut list = div().relative().flex_shrink_0();
        list = if vertical { list.w_full().h(length) } else { list.flex().flex_row().items_start().w(length) };
        // The first child spans the list, for its bounds.
        list = list.child(div().absolute().top_0().left_0().size_full());
        let mut flow_end = px(0.);
        for index in &built {
            let start = starts[*index];
            let slot = if vertical {
                div().absolute().left_0().right_0().top(start)
            } else {
                let slot = div().flex_shrink_0().ml(start - flow_end);
                flow_end = starts[*index + 1] - gap;
                slot
            };
            list = list.child(slot.child(row(*index)));
        }
        let shared = self.0.clone();
        list.on_children_prepainted(move |bounds, window, _| {
            let Some(frame) = bounds.first() else { return };
            let state = &shared;
            let mut stale = false;
            {
                let mut heights = state.heights.borrow_mut();
                for (index, bounds) in built.iter().zip(bounds.iter().skip(1)) {
                    let measured = geometry.length(bounds.size);
                    let assumed = starts[*index + 1] - starts[*index] - gap;
                    stale |= (measured - assumed).abs() > px(TOLERANCE);
                    if let Some(height) = heights.get_mut(*index) {
                        *height = Some(measured);
                    }
                }
            }
            let viewport = state.scroll.bounds();
            let lead = geometry.lead_gap(*frame, viewport);
            state.content_start.set(Some(lead + geometry.scrolled(state.scroll.offset())));
            let needed = visible(&starts, gap, count, -lead..geometry.length(viewport.size) - lead);
            stale |= !needed.is_empty() && (needed.start < laid_out.start || needed.end > laid_out.end);
            *state.starts.borrow_mut() = starts.clone();
            if stale {
                window.request_animation_frame();
            }
        })
    }
}

/// Lengths along a list's axis, measured from its reading start (the right edge of a horizontal
/// list laid out right to left).
#[derive(Clone, Copy)]
struct Geometry {
    axis: Axis,
    rtl: bool,
}

impl Geometry {
    fn length(self, size: Size<Pixels>) -> Pixels {
        match self.axis {
            Axis::Vertical => size.height,
            Axis::Horizontal => size.width,
        }
    }

    /// How far the scroll container has scrolled along the list.
    fn scrolled(self, offset: Point<Pixels>) -> Pixels {
        match (self.axis, self.rtl) {
            (Axis::Vertical, _) => -offset.y,
            (Axis::Horizontal, false) => -offset.x,
            (Axis::Horizontal, true) => offset.x,
        }
    }

    fn with_scrolled(self, offset: Point<Pixels>, scrolled: Pixels) -> Point<Pixels> {
        match (self.axis, self.rtl) {
            (Axis::Vertical, _) => point(offset.x, -scrolled),
            (Axis::Horizontal, false) => point(-scrolled, offset.y),
            (Axis::Horizontal, true) => point(scrolled, offset.y),
        }
    }

    /// How far `frame`'s start lies past `viewport`'s.
    fn lead_gap(self, frame: Bounds<Pixels>, viewport: Bounds<Pixels>) -> Pixels {
        match (self.axis, self.rtl) {
            (Axis::Vertical, _) => frame.top() - viewport.top(),
            (Axis::Horizontal, false) => frame.left() - viewport.left(),
            (Axis::Horizontal, true) => viewport.right() - frame.right(),
        }
    }
}

/// The rows overlapping `shown`, in list coordinates.
fn visible(starts: &[Pixels], gap: Pixels, count: usize, shown: Range<Pixels>) -> Range<usize> {
    let first = starts[1..].partition_point(|end| *end - gap <= shown.start);
    let end = starts[..count].partition_point(|start| *start < shown.end).max(first);
    first..end
}
