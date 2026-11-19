//! The JVM `AsyncImage.kt` for gpui, and the texture-backed bitmap the loader caches for it.
//!
//! A decoded [`RenderImage`] lives in each window's sprite atlas from its first paint until it is
//! dropped there. [`LoadedImage`] counts its holders (the memory LRU, the elements showing it, any
//! caller); when the last one lets go, the picture is dropped from every window's atlas.

use std::cell::RefCell;
use std::panic::Location;
use std::rc::{Rc, Weak};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use futures::StreamExt;
use futures::channel::mpsc::{UnboundedSender, unbounded};
use gpui_kit::{
    AnyElement, App, Bounds, ElementId, GlobalElementId, Hitbox, InspectorElementId, Interactivity, IntoElement,
    LayoutId, ObjectFit, Pixels, RenderImage, StyleRefinement, Styled, Task, Window,
};
use image::{Frame, RgbaImage};

use crate::{Bitmap, ImageLoader, ImageRequest, ImageScale, ImageSource};

/// The JVM `RELOAD_GROWTH_PX`: a resize reloads only on this much growth in a dimension.
const RELOAD_GROWTH_PX: u32 = 100;
/// The JVM `RESIZE_SETTLE_MS`: a growing resize reloads once it has settled this long.
const RESIZE_SETTLE: Duration = Duration::from_millis(400);

/// A decoded picture as gpui draws it (BGRA). Clones share one texture.
#[derive(Clone)]
pub struct LoadedImage(Arc<Texture>);

struct Texture(Arc<RenderImage>);

impl Drop for Texture {
    fn drop(&mut self) {
        if let Some(released) = RELEASED.get() {
            // No receiver means no window was ever opened to hold it.
            let _ = released.unbounded_send(self.0.clone());
        }
    }
}

impl LoadedImage {
    pub fn render_image(&self) -> Arc<RenderImage> {
        self.0.0.clone()
    }
}

impl Bitmap for LoadedImage {
    fn from_rgba(mut image: RgbaImage) -> Self {
        for pixel in image.as_chunks_mut::<4>().0 {
            pixel.swap(0, 2);
        }
        Self(Arc::new(Texture(Arc::new(RenderImage::new(vec![Frame::new(image)])))))
    }

    fn to_rgba(&self) -> RgbaImage {
        let render = &self.0.0;
        let dimensions = render.size(0);
        let mut bytes = render.as_bytes(0).map(<[u8]>::to_vec).unwrap_or_default();
        for pixel in bytes.as_chunks_mut::<4>().0 {
            pixel.swap(0, 2);
        }
        RgbaImage::from_raw(dimensions.width.0 as u32, dimensions.height.0 as u32, bytes).unwrap_or_default()
    }

    fn width(&self) -> u32 {
        self.0.0.size(0).width.0 as u32
    }

    fn height(&self) -> u32 {
        self.0.0.size(0).height.0 as u32
    }
}

/// The app's loader, read by every [`AsyncImage`]; without it every image stays its placeholder.
pub struct GlobalImageLoader(pub ImageLoader<LoadedImage>);

impl gpui_kit::Global for GlobalImageLoader {}

static RELEASED: OnceLock<UnboundedSender<Arc<RenderImage>>> = OnceLock::new();

/// Installs `loader` for [`AsyncImage`] and starts dropping released pictures from every window's
/// atlas.
pub fn init(loader: ImageLoader<LoadedImage>, cx: &mut App) {
    let log = loader.logger().clone();
    cx.set_global(GlobalImageLoader(loader));
    let (sender, mut receiver) = unbounded::<Arc<RenderImage>>();
    if RELEASED.set(sender).is_err() {
        log.warn("image atlas release already installed", None);
        return;
    }
    cx.spawn(async move |cx| {
        while let Some(first) = receiver.next().await {
            let mut released = vec![first];
            while let Ok(more) = receiver.try_recv() {
                released.push(more);
            }
            cx.update(|cx| {
                for window in cx.windows() {
                    // A window that closed meanwhile took its atlas with it.
                    let _ = window.update(cx, |_, window, _| {
                        for image in &released {
                            if let Err(error) = window.drop_image(image.clone()) {
                                log.warn("image not dropped from the atlas", Some(error.as_ref()));
                            }
                        }
                    });
                }
            });
        }
    })
    .detach();
}

/// An image with identity `key` and its `sources`, loaded at this element's own measured device
/// pixel size: the loader picks the smallest source that covers it. A missing key or no sources
/// shows the placeholder.
///
/// Shows `placeholder` while loading and `error` (falling back to `placeholder`) when the load
/// fails; the failure is logged. A memory-cached image draws on the first frame. On resize the shown
/// bitmap stays; only a growth of at least 100px in a dimension warrants a sharper decode, after the
/// size has settled for 400ms. `ObjectFit::Contain`/`ScaleDown` decode to fit the box, everything
/// else to cover it.
#[track_caller]
pub fn async_image(key: Option<String>, sources: Vec<ImageSource>) -> AsyncImage {
    let location = Location::caller();
    let id =
        ElementId::NamedChild(Arc::new(ElementId::CodeLocation(*location)), key.clone().unwrap_or_default().into());
    let mut interactivity = Interactivity::new();
    interactivity.element_id = Some(id);
    AsyncImage { interactivity, key, sources, object_fit: ObjectFit::Cover, placeholder: None, error: None }
}

/// [`async_image`] for a request built elsewhere, `None` showing the placeholder.
#[track_caller]
pub fn async_image_of(request: Option<(String, Vec<ImageSource>)>) -> AsyncImage {
    let (key, sources) = request.map_or((None, Vec::new()), |(key, sources)| (Some(key), sources));
    async_image(key, sources)
}

pub struct AsyncImage {
    interactivity: Interactivity,
    key: Option<String>,
    sources: Vec<ImageSource>,
    object_fit: ObjectFit,
    placeholder: Option<AnyElement>,
    error: Option<AnyElement>,
}

impl AsyncImage {
    /// A distinct id where one call site shows the same image twice under one parent.
    pub fn id(mut self, id: impl Into<ElementId>) -> Self {
        self.interactivity.element_id = Some(id.into());
        self
    }

    pub fn object_fit(mut self, object_fit: ObjectFit) -> Self {
        self.object_fit = object_fit;
        self
    }

    pub fn placeholder(mut self, placeholder: impl IntoElement) -> Self {
        self.placeholder = Some(placeholder.into_any_element());
        self
    }

    pub fn error(mut self, error: impl IntoElement) -> Self {
        self.error = Some(error.into_any_element());
        self
    }

    fn scale(&self) -> ImageScale {
        match self.object_fit {
            ObjectFit::Contain | ObjectFit::ScaleDown => ImageScale::Fit,
            _ => ImageScale::Cover,
        }
    }
}

type EffectKey = (Option<String>, (u32, u32));

#[derive(Default)]
struct Inner {
    key: Option<String>,
    /// The bitmap showing and the size it was decoded for (the growth baseline).
    loaded: Option<(LoadedImage, (u32, u32))>,
    failed: bool,
    /// The running load, keyed like the JVM `LaunchedEffect(key, sizePx)`.
    effect: Option<(EffectKey, Option<Task<()>>)>,
}

struct AsyncImageState(Rc<RefCell<Inner>>);

pub struct AsyncImageLayout {
    child: Option<AnyElement>,
}

pub struct AsyncImagePrepaint {
    hitbox: Option<Hitbox>,
    shown: Option<LoadedImage>,
}

impl gpui_kit::Element for AsyncImage {
    type RequestLayoutState = AsyncImageLayout;
    type PrepaintState = AsyncImagePrepaint;

    fn id(&self) -> Option<ElementId> {
        self.interactivity.element_id.clone()
    }

    fn source_location(&self) -> Option<&'static Location<'static>> {
        self.interactivity.source_location()
    }

    fn request_layout(
        &mut self,
        global_id: Option<&GlobalElementId>,
        inspector_id: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, Self::RequestLayoutState) {
        let key = self.key.clone();
        let failed = window.with_optional_element_state::<AsyncImageState, _>(global_id, |state, _| {
            let state = state.flatten().unwrap_or_else(|| AsyncImageState(Rc::default()));
            let failed = state.0.borrow().key == key && state.0.borrow().failed;
            (failed, Some(state))
        });
        let mut child =
            if failed { self.error.take().or_else(|| self.placeholder.take()) } else { self.placeholder.take() };
        let layout_id = self.interactivity.request_layout(global_id, inspector_id, window, cx, |style, window, cx| {
            let children: Vec<LayoutId> = child.iter_mut().map(|child| child.request_layout(window, cx)).collect();
            window.request_layout(style, children, cx)
        });
        (layout_id, AsyncImageLayout { child })
    }

    fn prepaint(
        &mut self,
        global_id: Option<&GlobalElementId>,
        inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        layout: &mut Self::RequestLayoutState,
        window: &mut Window,
        cx: &mut App,
    ) -> Self::PrepaintState {
        let scale_factor = window.scale_factor();
        let size_px = (
            (f32::from(bounds.size.width) * scale_factor).round().max(0.) as u32,
            (f32::from(bounds.size.height) * scale_factor).round().max(0.) as u32,
        );
        let loader = cx.try_global::<GlobalImageLoader>().map(|global| global.0.clone());
        let request = match (&loader, &self.key) {
            (Some(_), Some(key)) if size_px.0 > 0 && size_px.1 > 0 => {
                ImageRequest::new(key.clone(), self.sources.clone(), size_px, self.scale())
            }
            _ => None,
        };
        let key = self.key.clone();
        let shown = window.with_optional_element_state::<AsyncImageState, _>(global_id, |state, window| {
            let Some(state) = state.flatten() else { return (None, None) };
            let shown = sync(&state, key, size_px, request, loader, window, cx);
            (shown, Some(state))
        });
        let hitbox = self.interactivity.prepaint(
            global_id,
            inspector_id,
            bounds,
            bounds.size,
            window,
            cx,
            |_, _, hitbox, window, cx| {
                if shown.is_none()
                    && let Some(child) = &mut layout.child
                {
                    child.prepaint(window, cx);
                }
                hitbox
            },
        );
        AsyncImagePrepaint { hitbox, shown }
    }

    fn paint(
        &mut self,
        global_id: Option<&GlobalElementId>,
        inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        layout: &mut Self::RequestLayoutState,
        prepaint: &mut Self::PrepaintState,
        window: &mut Window,
        cx: &mut App,
    ) {
        let Self { interactivity, object_fit, .. } = self;
        let shown = prepaint.shown.clone();
        interactivity.paint(
            global_id,
            inspector_id,
            bounds,
            prepaint.hitbox.as_ref(),
            window,
            cx,
            |style, window, cx| match &shown {
                Some(image) => {
                    let render = image.render_image();
                    let fitted = object_fit.get_bounds(bounds, render.size(0));
                    let corner_radii = style.corner_radii.to_pixels(window.rem_size());
                    if let Err(error) = window.paint_image(bounds, fitted, corner_radii, render, 0, false)
                        && let Some(global) = cx.try_global::<GlobalImageLoader>()
                    {
                        global.0.logger().warn("image paint failed", Some(error.as_ref()));
                    }
                }
                None => {
                    if let Some(child) = &mut layout.child {
                        child.paint(window, cx);
                    }
                }
            },
        );
    }
}

/// The JVM `AsyncImage` body for one frame: the bitmap to draw, and the load effect restarted when the
/// key or the measured size changed.
fn sync(
    state: &AsyncImageState,
    key: Option<String>,
    size_px: (u32, u32),
    request: Option<ImageRequest>,
    loader: Option<ImageLoader<LoadedImage>>,
    window: &mut Window,
    cx: &mut App,
) -> Option<LoadedImage> {
    let mut inner = state.0.borrow_mut();
    if inner.key != key {
        *inner = Inner { key: key.clone(), ..Inner::default() };
    }
    let (Some(request), Some(loader)) = (request, loader) else {
        inner.effect = None;
        return inner.loaded.as_ref().map(|(image, _)| image.clone());
    };
    if inner.loaded.is_none()
        && let Some(hit) = loader.peek(&request)
    {
        // The same bitmap the load would return from memory, drawn on this frame.
        inner.loaded = Some((hit, size_px));
        inner.failed = false;
    }
    let shown = inner.loaded.as_ref().map(|(image, _)| image.clone());
    let effect_key = (key, size_px);
    if inner.effect.as_ref().is_some_and(|(running, _)| *running == effect_key) {
        return shown;
    }
    // The previous effect is cancelled before the next one starts.
    let previous = inner.effect.take();
    let delay = match &inner.loaded {
        None => Some(Duration::ZERO),
        Some((_, loaded_for)) => {
            let grows_width = size_px.0.saturating_sub(loaded_for.0) >= RELOAD_GROWTH_PX;
            let grows_height = size_px.1.saturating_sub(loaded_for.1) >= RELOAD_GROWTH_PX;
            (grows_width || grows_height).then_some(RESIZE_SETTLE)
        }
    };
    drop(inner);
    drop(previous);
    let task = delay.map(|delay| spawn_load(Rc::downgrade(&state.0), request, size_px, delay, loader, window, cx));
    state.0.borrow_mut().effect = Some((effect_key, task));
    shown
}

fn spawn_load(
    state: Weak<RefCell<Inner>>,
    request: ImageRequest,
    size_px: (u32, u32),
    delay: Duration,
    loader: ImageLoader<LoadedImage>,
    window: &mut Window,
    cx: &mut App,
) -> Task<()> {
    let view = window.current_view();
    let executor = cx.background_executor().clone();
    window.spawn(cx, async move |cx| {
        if !delay.is_zero() {
            executor.timer(delay).await;
        }
        let Some(inner) = state.upgrade() else { return };
        if let Some((_, loaded_for)) = inner.borrow_mut().loaded.as_mut() {
            *loaded_for = size_px;
        }
        drop(inner);
        let result = loader.load(&request, true).await;
        let Some(inner) = state.upgrade() else { return };
        {
            let mut inner = inner.borrow_mut();
            match result {
                Ok(image) => {
                    inner.loaded = Some((image, size_px));
                    inner.failed = false;
                }
                Err(error) => {
                    crate::request::log_load_failure(loader.logger(), &request.key, &error);
                    // A failed sharper re-decode keeps the bitmap already showing.
                    inner.failed = inner.loaded.is_none();
                }
            }
        }
        // The view may be gone by now; nothing is left to redraw then.
        let _ = cx.update(|_, cx| cx.notify(view));
    })
}

impl Styled for AsyncImage {
    fn style(&mut self) -> &mut StyleRefinement {
        &mut self.interactivity.base_style
    }
}

impl IntoElement for AsyncImage {
    type Element = Self;

    fn into_element(self) -> Self::Element {
        self
    }
}
