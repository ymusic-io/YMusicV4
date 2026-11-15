//! Settings → Show FPS: pictures shown per second over the Now Playing video, the late pictures
//! dropped since the item started and the video decoder. Samples once a second, only while that
//! video is on screen and playing.

use std::time::Duration;

use gpui_kit::*;

use crate::graph::AppGraph;
use crate::playback::Playback;
use crate::settings_view::SHOW_FPS_KEY;

struct Badge(Entity<FpsBadge>);

impl Global for Badge {}

struct FpsBadge {
    label: Option<String>,
    _sampler: Task<()>,
}

/// The badge for the video frame, or nothing while it is off or has nothing to show.
pub fn badge(playback: &Entity<Playback>, cx: &mut App) -> Option<impl IntoElement + use<>> {
    let entity = match cx.try_global::<Badge>() {
        Some(badge) => badge.0.clone(),
        None => {
            let entity = cx.new(|cx| FpsBadge::new(playback.clone(), cx));
            cx.set_global(Badge(entity.clone()));
            entity
        }
    };
    let label = entity.read(cx).label.clone()?;
    // A developer readout, not localized.
    Some(
        div()
            .absolute()
            .top_2()
            .left_2()
            .px(px(6.))
            .py(px(2.))
            .bg(black().opacity(0.55))
            .text_size(px(13.))
            .text_color(white())
            .child(label),
    )
}

/// The item a dropped count belongs to; the host's counter restarts with every load (a quality
/// switch too), so it is summed over the item.
#[derive(Default)]
struct Counts {
    item: Option<String>,
    last_shown: Option<i32>,
    last_dropped: i32,
    dropped: i32,
    decoder: Option<String>,
}

impl FpsBadge {
    fn new(playback: Entity<Playback>, cx: &mut Context<Self>) -> Self {
        let sampler = cx.spawn(async move |this, cx| {
            let mut counts = Counts::default();
            loop {
                cx.background_executor().timer(Duration::from_secs(1)).await;
                let sample = cx.update(|cx| {
                    let enabled = cx.global::<AppGraph>().settings.get_raw(SHOW_FPS_KEY).ok().flatten().as_deref()
                        == Some("true");
                    let playback = playback.read(cx);
                    let counter = playback.frame_counter().filter(|_| enabled && playback.is_playing());
                    let item = playback.current().map(|item| format!("{:?}", item.source));
                    let graph = cx.global::<AppGraph>();
                    counter.map(|counter| (counter, item, graph.tokio.clone(), graph.log.fork("fps")))
                });
                let label = match sample {
                    None => {
                        counts.last_shown = None;
                        None
                    }
                    Some((counter, item, tokio, log)) => {
                        match tokio.spawn_blocking(move || counter.read()).await {
                            Ok(Some((shown, dropped, decoder))) => counts.add(item, shown, dropped, decoder),
                            // A busy player answers nothing: the shown label stands.
                            Ok(None) => continue,
                            Err(error) => {
                                log.warn("video quality read failed", Some(&error));
                                None
                            }
                        }
                    }
                };
                if this
                    .update(cx, |badge, cx| {
                        if badge.label != label {
                            badge.label = label;
                            cx.refresh_windows();
                        }
                    })
                    .is_err()
                {
                    return;
                }
            }
        });
        Self { label: None, _sampler: sampler }
    }
}

impl Counts {
    fn add(&mut self, item: Option<String>, shown: i32, dropped: i32, decoder: Option<String>) -> Option<String> {
        if self.item != item {
            *self = Counts { item, last_dropped: dropped, ..Counts::default() };
        }
        // The host answers nothing while it is busy: the last answer stands.
        if decoder.is_some() {
            self.decoder = decoder;
        }
        self.dropped += if dropped >= self.last_dropped { dropped - self.last_dropped } else { dropped };
        self.last_dropped = dropped;
        let rate = self.last_shown.map(|last| (shown - last).max(0));
        self.last_shown = Some(shown);
        let rate = rate?;
        if rate <= 0 && self.dropped <= 0 {
            return None;
        }
        let mut parts = vec![format!("{rate} fps")];
        if self.dropped > 0 {
            parts.push(format!("{} dropped", self.dropped));
        }
        parts.extend(self.decoder.clone());
        Some(parts.join(" - "))
    }
}
