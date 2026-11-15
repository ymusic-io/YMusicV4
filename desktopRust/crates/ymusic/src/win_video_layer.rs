//! Windows video: the presenter draws D3D11 decoder output straight into a composition swap chain
//! that DirectComposition shows under gpui's visual in the same window (gpui's `underlay`); gpui
//! leaves the video box unpainted (a hole), so its controls, captions and menus draw over the
//! video, as on macOS. Without D3D11 or DirectComposition, CPU pictures painted by gpui.

use std::cell::{Cell, RefCell};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use gpui_kit::{Bounds, Div, Pixels, Stateful, Window, point, size};
use gpui_windows::underlay::{self, Placement};
use ym_mse::{CompositionSink, Presenter, SwapChain};

use crate::video_layer::VideoLayer as CpuLayer;

pub struct VideoLayer {
    hwnd: isize,
    cpu: CpuLayer,
    /// The composition content's size, packed `width << 32 | height`; 0 before any.
    content: Arc<AtomicU64>,
    /// A swap chain that replaced the attached one (a device loss), for the next render.
    replaced: Arc<Mutex<Option<SwapChain>>>,
    composited: Cell<bool>,
    scale: Cell<f32>,
    placed: RefCell<Placement>,
}

impl VideoLayer {
    pub fn attach(window: &Window) -> Option<Self> {
        let hwnd = crate::desktop::window_hwnd(window)? as isize;
        Some(Self {
            hwnd,
            cpu: CpuLayer::attach(window)?,
            content: Arc::default(),
            replaced: Arc::default(),
            composited: Cell::new(false),
            scale: Cell::new(window.scale_factor()),
            placed: RefCell::default(),
        })
    }

    /// The composition presenter under the window, else a CPU one; `wake` runs on a decode thread
    /// when the picture needs a render (a CPU picture, a new content aspect or swap chain).
    pub fn presenter(&self, wake: impl Fn() + Send + Sync + 'static) -> Option<Arc<Presenter>> {
        let wake = Arc::new(wake);
        let (content, size_wake) = (self.content.clone(), wake.clone());
        let (replaced, swap_wake) = (self.replaced.clone(), wake.clone());
        let composition = Presenter::d3d11_composition(CompositionSink {
            size: Box::new(move |width, height| {
                content.store(u64::from(width) << 32 | u64::from(height), Ordering::Release);
                size_wake();
            }),
            swap_chain: Box::new(move |swap_chain| {
                *replaced.lock().unwrap_or_else(PoisonError::into_inner) = Some(swap_chain);
                swap_wake();
            }),
        });
        if let Some((presenter, swap_chain)) = composition {
            // SAFETY: a live composition swap chain; the window takes its own reference.
            if unsafe { underlay::attach(self.hwnd, swap_chain.as_ptr()) } {
                self.composited.set(true);
                return Some(Arc::new(presenter));
            }
        }
        self.cpu.presenter(move || wake())
    }

    pub fn refresh(&self, window: &mut Window) -> anyhow::Result<()> {
        self.scale.set(window.scale_factor());
        if !self.composited.get() {
            return self.cpu.refresh(window);
        }
        let replaced = self.replaced.lock().unwrap_or_else(PoisonError::into_inner).take();
        if let Some(swap_chain) = replaced {
            // SAFETY: a live composition swap chain; the window takes its own reference.
            unsafe { underlay::attach(self.hwnd, swap_chain.as_ptr()) };
        }
        Ok(())
    }

    /// The composition shows through the unpainted box.
    pub fn underlay(&self, frame: Stateful<Div>) -> Stateful<Div> {
        if self.composited.get() { frame } else { self.cpu.underlay(frame) }
    }

    /// Window-relative logical bounds (top-left origin); hidden when `visible` is false.
    pub fn place(&self, bounds: Bounds<Pixels>, visible: bool) {
        if !self.composited.get() {
            return self.cpu.place(bounds, visible);
        }
        let scale = self.scale.get();
        let device = Bounds {
            origin: point(f32::from(bounds.origin.x) * scale, f32::from(bounds.origin.y) * scale),
            size: size(f32::from(bounds.size.width) * scale, f32::from(bounds.size.height) * scale),
        };
        let packed = self.content.load(Ordering::Acquire);
        let content = size((packed >> 32) as f32, (packed & u64::from(u32::MAX)) as f32);
        self.show(Placement { bounds: device, content, clip: device, corner_radius: 0., visible });
    }

    pub fn hide(&self) {
        if !self.composited.get() {
            return self.cpu.hide();
        }
        let placed = *self.placed.borrow();
        self.show(Placement { visible: false, ..placed });
    }

    fn show(&self, placement: Placement) {
        *self.placed.borrow_mut() = placement;
        underlay::place(self.hwnd, placement);
    }
}

impl Drop for VideoLayer {
    fn drop(&mut self) {
        if self.composited.get() {
            underlay::detach(self.hwnd);
        }
    }
}
