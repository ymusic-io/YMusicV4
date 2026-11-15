//! Which YouTube videos are already saved (Library files tagged with their id), for the
//! "downloaded" mark on feed items. One set per app, re-read when the media store changes (a
//! finished download lands there) and when a surface asks (a new feed).

use std::collections::HashSet;

use gpui_kit::*;

use crate::downloads::offline_ids;

pub struct OfflineMarks {
    ids: HashSet<String>,
    generation: u64,
}

struct Shared(Entity<OfflineMarks>);

impl Global for Shared {}

impl OfflineMarks {
    /// The app's set, created (and first read) on the first call.
    pub fn shared(cx: &mut App) -> Entity<Self> {
        if let Some(shared) = cx.try_global::<Shared>() {
            return shared.0.clone();
        }
        let marks = cx.new(|cx| {
            let mut marks = Self { ids: HashSet::new(), generation: 0 };
            crate::library::on_library_change(cx, Self::reload);
            marks.reload(cx);
            marks
        });
        cx.set_global(Shared(marks.clone()));
        marks
    }

    /// Re-reads the set if it exists; nothing shows it otherwise.
    pub fn refresh(cx: &mut App) {
        if let Some(shared) = cx.try_global::<Shared>() {
            shared.0.clone().update(cx, |marks, cx| marks.reload(cx));
        }
    }

    pub fn contains(&self, video_id: &str) -> bool {
        self.ids.contains(video_id)
    }

    fn reload(&mut self, cx: &mut Context<Self>) {
        self.generation += 1;
        let generation = self.generation;
        let load = offline_ids(cx);
        cx.spawn(async move |this, cx| {
            let ids = load.await;
            this.update(cx, |marks, cx| {
                if marks.generation == generation && marks.ids != ids {
                    marks.ids = ids;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }
}

/// The mark itself: an accent triangle in the thumbnail's top-left corner (JVM `AvailableOfflineMark`),
/// rounded off as a thumbnail with `corner_radius` clips it.
pub fn offline_mark(color: Hsla, corner_radius: f32) -> impl IntoElement {
    canvas(
        |_, _, _| {},
        move |bounds, _, window, _| {
            let radius = px(corner_radius);
            let mut corner = PathBuilder::fill();
            corner.move_to(point(bounds.left(), bounds.top() + radius));
            corner.arc_to(point(radius, radius), px(0.), false, true, point(bounds.left() + radius, bounds.top()));
            corner.line_to(point(bounds.right(), bounds.top()));
            corner.line_to(point(bounds.left(), bounds.bottom()));
            corner.close();
            if let Ok(corner) = corner.build() {
                window.paint_path(corner, color);
            }
        },
    )
    .absolute()
    .top_0()
    .left_0()
    .size(px(20.))
}
