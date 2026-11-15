//! Now Playing's audio-only picture (JVM `AudioOnlyHole.kt`): the art blurred and dimmed as the
//! backdrop, the sharp square art on the left and the live spectrum bars on the right.

use std::cell::Cell;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use image::imageops::{self, FilterType};
use image::{Frame, RgbaImage};

use crate::playback::Playback;

const WAVE_BARS: usize = 40;
const SAMPLE_EVERY: Duration = Duration::from_millis(33);
/// After a pause the last bars fall by this factor per sample, for at most `FALL_STEPS`.
const FALL: f32 = 0.78;
const FALL_STEPS: usize = 12;
const SILENT: f32 = 0.012;
/// Bars that moved less than this are not redrawn.
const UNCHANGED: f32 = 0.002;
/// Sampling stops when nothing drew the view for this long (it is not on screen); a render restarts it.
const UNSEEN: Duration = Duration::from_secs(2);
/// Compose's `blur(28.dp)` as a Gaussian sigma in logical pixels (Skia: 0.57735 × radius + 0.5).
const BLUR_SIGMA: f32 = 16.7;
/// Logical pixels per backdrop pixel: the GPU's linear upscale of the blurred picture is smooth at this.
const BACKDROP_SPAN: f32 = 4.;
const BACKDROP_DIM: f32 = 0.36;
/// The backdrop darkens by this much more while the next song's art is on its way.
const BACKDROP_WAIT_DIM: f32 = 0.3;
const SQUARE_MAX_PX: u32 = 1024;
const ART_RADIUS: f32 = 8.;
/// A box this much wider or taller than the one the backdrop was blurred for blurs it again.
const REBLUR_RATIO: f32 = 1.25;

/// The square and the blurred backdrop made from one decoded picture.
struct ArtPictures {
    url: SharedString,
    source: ImageId,
    blurred_for: (f32, f32),
    square: Arc<RenderImage>,
    backdrop: Arc<RenderImage>,
}

#[derive(Clone, Copy, PartialEq)]
enum WaveMode {
    Sampling,
    Falling,
}

pub struct AudioOnlyArt {
    playback: Entity<Playback>,
    /// The video box as last painted, in logical pixels.
    video_box: Rc<Cell<(i32, i32)>>,
    art: Option<ArtPictures>,
    /// The picture being prepared and the box it is blurred for.
    preparing: Option<(ImageId, (f32, f32), Task<()>)>,
    levels: Vec<f32>,
    wave_mode: Option<WaveMode>,
    last_render: Instant,
    _wave: Option<Task<()>>,
}

impl AudioOnlyArt {
    pub fn new(playback: Entity<Playback>, video_box: Rc<Cell<(i32, i32)>>) -> Self {
        Self {
            playback,
            video_box,
            art: None,
            preparing: None,
            levels: vec![0.; WAVE_BARS],
            wave_mode: None,
            last_render: Instant::now(),
            _wave: None,
        }
    }

    /// Starts preparing the current art once its picture is decoded and the box is known.
    fn update_art(
        &mut self,
        url: Option<SharedString>,
        box_size: (f32, f32),
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(url) = url else { return };
        if box_size.0 < 1. || box_size.1 < 1. {
            return;
        }
        let ImageSource::Resource(resource) = ImageSource::from(url.clone()) else { return };
        let Some(Ok(source)) = window.use_asset::<ImgResourceLoader>(&resource, cx) else { return };
        let current = self.art.as_ref().filter(|art| art.source == source.id);
        if current.is_some_and(|art| !needs_reblur(art.blurred_for, box_size)) {
            return;
        }
        if self.preparing.as_ref().is_some_and(|(id, size, _)| *id == source.id && !needs_reblur(*size, box_size)) {
            return;
        }
        let letterboxed = url.contains("i.ytimg.com/vi");
        let id = source.id;
        let work = cx.background_executor().spawn(async move { prepare(&source, letterboxed, box_size) });
        let task = cx.spawn_in(window, async move |this, cx| {
            let Some((square, backdrop)) = work.await else { return };
            this.update_in(cx, |view, window, cx| {
                let next = ArtPictures {
                    url,
                    source: id,
                    blurred_for: box_size,
                    square: Arc::new(square),
                    backdrop: Arc::new(backdrop),
                };
                if let Some(old) = view.art.replace(next) {
                    // The atlas keeps a picture until it is dropped.
                    window.drop_image(old.square).ok();
                    window.drop_image(old.backdrop).ok();
                }
                view.preparing = None;
                cx.notify();
            })
            .ok();
        });
        self.preparing = Some((id, box_size, task));
    }

    /// Samples while playing, lets the bars fall after a pause, then rests.
    fn update_wave(&mut self, playing: bool, cx: &mut Context<Self>) {
        let wanted = if playing {
            Some(WaveMode::Sampling)
        } else if self.wave_mode == Some(WaveMode::Falling) || self.levels.iter().any(|level| *level > SILENT) {
            Some(WaveMode::Falling)
        } else {
            None
        };
        if wanted == self.wave_mode {
            return;
        }
        self.wave_mode = wanted;
        self._wave = wanted.map(|mode| match mode {
            WaveMode::Sampling => self.sample(cx),
            WaveMode::Falling => self.fall(cx),
        });
    }

    /// Redraws only when the bars move; stops once a redraw it asked for stays undone for `UNSEEN`
    /// (Now Playing is not on screen). A slow frame or a quiet passage keeps it sampling.
    fn sample(&self, cx: &mut Context<Self>) -> Task<()> {
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            let mut notified_at: Option<Instant> = None;
            loop {
                executor.timer(SAMPLE_EVERY).await;
                let tap = this.update(cx, |view, cx| {
                    let unseen = notified_at.is_some_and(|at| view.last_render < at && at.elapsed() > UNSEEN);
                    if unseen {
                        view.wave_mode = None;
                        return None;
                    }
                    Some(view.playback.read(cx).level_tap())
                });
                let Ok(Some(tap)) = tap else { return };
                let mut levels = match tap {
                    Some(tap) => executor.spawn(async move { tap.read(WAVE_BARS) }).await,
                    None => Vec::new(),
                };
                if levels.is_empty() {
                    continue;
                }
                levels.resize(WAVE_BARS, 0.);
                let applied = this.update(cx, |view, cx| {
                    let moved = view.levels.iter().zip(&levels).any(|(shown, level)| (shown - level).abs() > UNCHANGED);
                    let waiting = notified_at.is_some_and(|at| view.last_render < at);
                    if moved && !waiting {
                        notified_at = Some(Instant::now());
                    }
                    if moved {
                        view.levels = levels;
                        cx.notify();
                    }
                });
                if applied.is_err() {
                    return;
                }
            }
        })
    }

    fn fall(&self, cx: &mut Context<Self>) -> Task<()> {
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            for step in 0..=FALL_STEPS {
                executor.timer(SAMPLE_EVERY).await;
                let applied = this.update(cx, |view, cx| {
                    let silent = !view.levels.iter().any(|level| *level > SILENT);
                    if step == FALL_STEPS || silent {
                        view.levels.fill(0.);
                        view.wave_mode = None;
                    } else {
                        view.levels.iter_mut().for_each(|level| *level *= FALL);
                    }
                    cx.notify();
                    view.wave_mode.is_some()
                });
                if !matches!(applied, Ok(true)) {
                    return;
                }
            }
        })
    }
}

fn needs_reblur(blurred_for: (f32, f32), box_size: (f32, f32)) -> bool {
    let ratio = |was: f32, now: f32| now.max(was) / now.min(was).max(1.);
    ratio(blurred_for.0, box_size.0) > REBLUR_RATIO || ratio(blurred_for.1, box_size.1) > REBLUR_RATIO
}

/// The square crop and the blurred backdrop of `source` (BGRA). YouTube's 4:3 frames letterbox a
/// 16:9 picture, so `letterboxed` art is trimmed to it first.
fn prepare(source: &RenderImage, letterboxed: bool, box_size: (f32, f32)) -> Option<(RenderImage, RenderImage)> {
    let size = source.size(0);
    let (width, height) = (u32::try_from(size.width.0).ok()?, u32::try_from(size.height.0).ok()?);
    let picture = RgbaImage::from_raw(width, height, source.as_bytes(0)?.to_vec())?;
    let (mut top, mut content_height) = (0, height);
    if letterboxed && width * 3 == height * 4 {
        content_height = width * 9 / 16;
        top = (height - content_height) / 2;
    }
    if width == 0 || content_height == 0 {
        return None;
    }
    let content = imageops::crop_imm(&picture, 0, top, width, content_height).to_image();

    let side = width.min(content_height);
    let mut square =
        imageops::crop_imm(&content, (width - side) / 2, (content_height - side) / 2, side, side).to_image();
    if side > SQUARE_MAX_PX {
        square = imageops::resize(&square, SQUARE_MAX_PX, SQUARE_MAX_PX, FilterType::Triangle);
    }

    // Cover-fit, as the backdrop is drawn: the blur is sized in the box's logical pixels.
    let scale = (box_size.0 / width as f32).max(box_size.1 / content_height as f32);
    let shown_width = width as f32 * scale;
    let small_width = ((shown_width / BACKDROP_SPAN).ceil() as u32).clamp(8, width.max(8));
    let small_height = ((content_height as f32 * small_width as f32 / width as f32).round() as u32).max(1);
    let small = imageops::resize(&content, small_width, small_height, FilterType::Triangle);
    let sigma = BLUR_SIGMA / (shown_width / small_width as f32);
    let backdrop = imageops::fast_blur(&small, sigma.max(0.5));

    Some((RenderImage::new(vec![Frame::new(square)]), RenderImage::new(vec![Frame::new(backdrop)])))
}

impl Render for AudioOnlyArt {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.last_render = Instant::now();
        let (url, playing) = {
            let playback = self.playback.read(cx);
            (playback.current().and_then(|item| item.thumb(640)), playback.is_playing())
        };
        let (box_width, box_height) = self.video_box.get();
        let box_size = (box_width as f32, box_height as f32);
        self.update_art(url.clone(), box_size, window, cx);
        self.update_wave(playing, cx);

        let side = (box_size.1 * 0.86).min(box_size.0 * 0.32).max(0.);
        let shown = self.art.as_ref().filter(|art| Some(&art.url) == url.as_ref());
        let backdrop = self.art.as_ref().map(|art| art.backdrop.clone());
        let waiting = shown.is_none() && backdrop.is_some();
        let square = shown.map(|art| art.square.clone());
        let levels = self.levels.clone();

        let wave = canvas(
            |_, _, _| {},
            move |bounds, _, window, _| {
                let count = levels.len().max(1) as f32;
                let (width, height) = (f32::from(bounds.size.width), f32::from(bounds.size.height));
                let gap = width * 0.014;
                let bar = ((width - gap * (count - 1.)) / count).max(1.5);
                let max_height = (height * 0.92).min(side * 2.);
                for (index, level) in levels.iter().enumerate() {
                    let bar_height = (level.clamp(0., 1.) * max_height).max(2.);
                    let origin = point(
                        bounds.origin.x + px(index as f32 * (bar + gap)),
                        bounds.origin.y + px((height - bar_height) * 0.5),
                    );
                    window.paint_quad(
                        fill(Bounds::new(origin, size(px(bar), px(bar_height))), white().opacity(0.92))
                            .corner_radii(px(bar * 0.45)),
                    );
                }
            },
        )
        .size_full();

        let art = div()
            .size(px(side))
            .flex_shrink_0()
            .rounded(px(ART_RADIUS))
            .bg(rgb(0x121212))
            .shadow(vec![
                BoxShadow {
                    color: black().opacity(0.14),
                    offset: point(px(0.), px(2.)),
                    blur_radius: px(6.),
                    spread_radius: px(0.),
                    inset: false,
                },
                BoxShadow {
                    color: black().opacity(0.3),
                    offset: point(px(0.), px(6.)),
                    blur_radius: px(16.),
                    spread_radius: px(0.),
                    inset: false,
                },
            ])
            .when_some(square, |frame, square| {
                frame.child(img(square).size_full().rounded(px(ART_RADIUS)).object_fit(ObjectFit::Cover))
            });

        div()
            .absolute()
            .inset_0()
            .overflow_hidden()
            .bg(black())
            .when_some(backdrop, |frame, backdrop| {
                frame.child(img(backdrop).absolute().inset_0().size_full().object_fit(ObjectFit::Cover))
            })
            .child(div().absolute().inset_0().bg(black().opacity(if waiting {
                1. - (1. - BACKDROP_DIM) * (1. - BACKDROP_WAIT_DIM)
            } else {
                BACKDROP_DIM
            })))
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .flex()
                    .items_center()
                    .pl(px(12.))
                    .pr(px(14.))
                    .py(px(10.))
                    .gap(px(14.))
                    .child(art)
                    .child(div().flex_1().h_full().child(wave)),
            )
    }
}
