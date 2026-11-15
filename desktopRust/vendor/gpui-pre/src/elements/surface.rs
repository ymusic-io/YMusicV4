use crate::{
    App, Bounds, Element, ElementId, GlobalElementId, InspectorElementId, IntoElement, LayoutId,
    ObjectFit, Pixels, Style, StyleRefinement, Styled, Window,
};
#[cfg(any(target_os = "macos", target_os = "ios"))]
use objc2_core_foundation::CFRetained;
#[cfg(any(target_os = "macos", target_os = "ios"))]
use objc2_core_video::{CVPixelBuffer, CVPixelBufferGetHeight, CVPixelBufferGetWidth};
use refineable::Refineable;
#[cfg(not(any(target_os = "macos", target_os = "ios")))]
use std::{any::Any, fmt, sync::Arc};

/// A source of a surface's content.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SurfaceSource {
    /// A CoreVideo image buffer
    #[cfg(any(target_os = "macos", target_os = "ios"))]
    Surface(CFRetained<CVPixelBuffer>),
    /// A decoded video picture
    #[cfg(not(any(target_os = "macos", target_os = "ios")))]
    Video(VideoFrame),
}

/// A decoded video picture: `size` is its display size, and the payload a type the platform
/// renderer knows how to draw (it skips any other).
#[cfg(not(any(target_os = "macos", target_os = "ios")))]
#[derive(Clone)]
pub struct VideoFrame {
    size: crate::Size<crate::DevicePixels>,
    payload: Arc<dyn Any + Send + Sync>,
}

#[cfg(not(any(target_os = "macos", target_os = "ios")))]
impl VideoFrame {
    /// A picture of `size` device pixels carrying `payload` to the renderer.
    pub fn new(size: crate::Size<crate::DevicePixels>, payload: Arc<dyn Any + Send + Sync>) -> Self {
        Self { size, payload }
    }

    /// The display size in device pixels.
    pub fn size(&self) -> crate::Size<crate::DevicePixels> {
        self.size
    }

    /// What the renderer downcasts to its own picture type.
    pub fn payload(&self) -> &(dyn Any + Send + Sync) {
        &*self.payload
    }
}

#[cfg(not(any(target_os = "macos", target_os = "ios")))]
impl PartialEq for VideoFrame {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.payload, &other.payload)
    }
}

#[cfg(not(any(target_os = "macos", target_os = "ios")))]
impl Eq for VideoFrame {}

#[cfg(not(any(target_os = "macos", target_os = "ios")))]
impl fmt::Debug for VideoFrame {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VideoFrame").field("size", &self.size).finish_non_exhaustive()
    }
}

#[cfg(not(any(target_os = "macos", target_os = "ios")))]
impl From<VideoFrame> for SurfaceSource {
    fn from(value: VideoFrame) -> Self {
        SurfaceSource::Video(value)
    }
}

#[cfg(any(target_os = "macos", target_os = "ios"))]
impl From<CFRetained<CVPixelBuffer>> for SurfaceSource {
    fn from(value: CFRetained<CVPixelBuffer>) -> Self {
        SurfaceSource::Surface(value)
    }
}

/// A surface element.
pub struct Surface {
    source: SurfaceSource,
    object_fit: ObjectFit,
    style: StyleRefinement,
}

/// Create a new surface element.
pub fn surface(source: impl Into<SurfaceSource>) -> Surface {
    Surface {
        source: source.into(),
        object_fit: ObjectFit::Contain,
        style: Default::default(),
    }
}

impl Surface {
    /// Set the object fit for the image.
    pub fn object_fit(mut self, object_fit: ObjectFit) -> Self {
        self.object_fit = object_fit;
        self
    }
}

impl Element for Surface {
    type RequestLayoutState = ();
    type PrepaintState = ();

    fn id(&self) -> Option<ElementId> {
        None
    }

    fn source_location(&self) -> Option<&'static core::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _global_id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, Self::RequestLayoutState) {
        let mut style = Style::default();
        style.refine(&self.style);
        let layout_id = window.request_layout(style, [], cx);
        (layout_id, ())
    }

    fn prepaint(
        &mut self,
        _global_id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        _bounds: Bounds<Pixels>,
        _request_layout: &mut Self::RequestLayoutState,
        _window: &mut Window,
        _cx: &mut App,
    ) -> Self::PrepaintState {
    }

    fn paint(
        &mut self,
        _global_id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut Self::RequestLayoutState,
        _: &mut Self::PrepaintState,
        window: &mut Window,
        _: &mut App,
    ) {
        match &self.source {
            #[cfg(any(target_os = "macos", target_os = "ios"))]
            SurfaceSource::Surface(surface) => {
                let size = crate::size(
                    CVPixelBufferGetWidth(surface).into(),
                    CVPixelBufferGetHeight(surface).into(),
                );
                let new_bounds = self.object_fit.get_bounds(bounds, size);
                // TODO: Add support for corner_radii
                window.paint_surface(new_bounds, surface.clone());
            }
            #[cfg(not(any(target_os = "macos", target_os = "ios")))]
            SurfaceSource::Video(frame) => {
                let new_bounds = self.object_fit.get_bounds(bounds, frame.size());
                window.paint_surface(new_bounds, frame.clone());
            }
            #[allow(unreachable_patterns)]
            _ => {}
        }
    }
}

impl IntoElement for Surface {
    type Element = Self;

    fn into_element(self) -> Self::Element {
        self
    }
}

impl Styled for Surface {
    fn style(&mut self) -> &mut StyleRefinement {
        &mut self.style
    }
}
