//! The surface video presents into. macOS: an `AVSampleBufferDisplayLayer` hosted by a native
//! view placed BEHIND the gpui view, inside the transparent window; gpui leaves the video box
//! unpainted (a hole), so its controls, captions and dialogs draw over the video. Linux: a surface
//! in gpui's scene (`video_surface.rs`). Elsewhere: CPU pictures painted by gpui as the box's
//! background image.

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use std::cell::{Cell, RefCell};
#[cfg(target_os = "macos")]
use std::ffi::c_void;
#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use std::sync::{Arc, Mutex, PoisonError};

#[cfg(target_os = "linux")]
pub use crate::video_surface::VideoLayer;
use gpui_kit::Window;
#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use gpui_kit::prelude::FluentBuilder;
#[cfg(not(target_os = "linux"))]
use gpui_kit::{Bounds, Pixels};
#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use gpui_kit::{Div, ObjectFit, ParentElement, RenderImage, Stateful, Styled, StyledImage, black, img};
#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use ym_mse::Presenter;

#[cfg(target_os = "macos")]
#[repr(C)]
struct CGColor {
    _opaque: [u8; 0],
}

// SAFETY: matches the `^{CGColor=}` type AppKit declares for `-[NSColor CGColor]`.
#[cfg(target_os = "macos")]
unsafe impl objc2::RefEncode for CGColor {
    const ENCODING_REF: objc2::Encoding = objc2::Encoding::Pointer(&objc2::Encoding::Struct("CGColor", &[]));
}

#[cfg(target_os = "macos")]
pub struct VideoLayer {
    layer: objc2::rc::Retained<objc2::runtime::AnyObject>,
    view: objc2::rc::Retained<objc2::runtime::AnyObject>,
    /// gpui's view, whose coordinates the box arrives in.
    gpui_view: objc2::rc::Retained<objc2::runtime::AnyObject>,
}

#[cfg(target_os = "macos")]
impl VideoLayer {
    pub fn attach(window: &Window) -> Option<Self> {
        use objc2::msg_send;
        use objc2::rc::Retained;
        use objc2::runtime::{AnyClass, AnyObject};
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};

        let handle = HasWindowHandle::window_handle(window).ok()?;
        let RawWindowHandle::AppKit(appkit) = handle.as_raw() else { return None };
        let gpui_view = appkit.ns_view.as_ptr().cast::<AnyObject>();
        // SAFETY: gpui's live NSView, a subview of the window's content view; NSView and
        // AVSampleBufferDisplayLayer (AVFoundation, linked by ym-mse) are used on the main thread.
        unsafe {
            let gpui_view: Retained<AnyObject> = Retained::retain(gpui_view)?;
            let superview: Option<Retained<AnyObject>> = msg_send![&*gpui_view, superview];
            let superview = superview?;
            let layer_class = AnyClass::get(c"AVSampleBufferDisplayLayer")?;
            let layer: Retained<AnyObject> = msg_send![layer_class, new];
            let color_class = AnyClass::get(c"NSColor")?;
            let black: Retained<AnyObject> = msg_send![color_class, blackColor];
            let cg_black: *const CGColor = msg_send![&*black, CGColor];
            let _: () = msg_send![&*layer, setBackgroundColor: cg_black];
            let view_class = AnyClass::get(c"NSView")?;
            let view: Retained<AnyObject> = msg_send![view_class, new];
            // A layer-hosting view: set the layer, then ask for it.
            let _: () = msg_send![&*view, setLayer: &*layer];
            let _: () = msg_send![&*view, setWantsLayer: true];
            let _: () = msg_send![&*view, setHidden: true];
            // NSWindowBelow = -1: under gpui's view.
            let _: () = msg_send![&*superview, addSubview: &*view, positioned: -1isize, relativeTo: &*gpui_view];
            Some(Self { layer, view, gpui_view })
        }
    }

    pub fn as_ptr(&self) -> *mut c_void {
        objc2::rc::Retained::as_ptr(&self.layer) as *mut c_void
    }

    /// The native view shows through the unpainted box.
    pub fn underlay(&self, frame: gpui_kit::Stateful<gpui_kit::Div>) -> gpui_kit::Stateful<gpui_kit::Div> {
        frame
    }

    /// Window-relative logical bounds (top-left origin); hidden when `visible` is false.
    pub fn place(&self, bounds: Bounds<Pixels>, visible: bool) {
        use objc2::msg_send;
        use objc2::rc::Retained;
        use objc2::runtime::AnyObject;
        use objc2_core_foundation::{CGPoint, CGRect, CGSize};

        // SAFETY: NSView geometry on the main thread.
        unsafe {
            let superview: Option<Retained<AnyObject>> = msg_send![&*self.gpui_view, superview];
            let Some(superview) = superview else { return };
            let gpui_bounds: CGRect = msg_send![&*self.gpui_view, bounds];
            let flipped: bool = msg_send![&*self.gpui_view, isFlipped];
            let (x, y, width, height) = (
                f64::from(f32::from(bounds.origin.x)),
                f64::from(f32::from(bounds.origin.y)),
                f64::from(f32::from(bounds.size.width)),
                f64::from(f32::from(bounds.size.height)),
            );
            let y = if flipped { y } else { gpui_bounds.size.height - y - height };
            let local = CGRect { origin: CGPoint { x, y }, size: CGSize { width, height } };
            let frame: CGRect = msg_send![&*self.gpui_view, convertRect: local, toView: &*superview];
            let _: () = msg_send![&*self.view, setFrame: frame];
            let _: () = msg_send![&*self.view, setHidden: !visible];
        }
    }

    pub fn hide(&self) {
        use objc2::msg_send;
        // SAFETY: an NSView property on the main thread.
        unsafe {
            let _: () = msg_send![&*self.view, setHidden: true];
        }
    }
}

#[cfg(target_os = "macos")]
impl Drop for VideoLayer {
    fn drop(&mut self) {
        use objc2::msg_send;
        // SAFETY: detaching our own view.
        unsafe {
            let _: () = msg_send![&*self.view, removeFromSuperview];
        }
    }
}

/// CPU pictures drawn by gpui as an image: Wayland cannot embed a child surface under gpui's.
#[cfg(not(any(target_os = "macos", target_os = "linux")))]
pub struct VideoLayer {
    /// The newest picture, written on a decode thread.
    latest: Arc<Mutex<Option<Arc<RenderImage>>>>,
    /// A wake is outstanding; cleared when a render takes the picture.
    pending: Arc<AtomicBool>,
    shown: RefCell<Option<Arc<RenderImage>>>,
    presenter: RefCell<Option<Arc<Presenter>>>,
    scale: Cell<f32>,
    fit: Cell<(u32, u32)>,
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
impl VideoLayer {
    pub fn attach(window: &Window) -> Option<Self> {
        Some(Self {
            latest: Arc::default(),
            pending: Arc::default(),
            shown: RefCell::default(),
            presenter: RefCell::default(),
            scale: Cell::new(window.scale_factor()),
            fit: Cell::new((0, 0)),
        })
    }

    /// A presenter whose pictures this layer shows; `wake` runs on a decode thread when a picture
    /// arrives and no render has taken the last one yet.
    pub fn presenter(&self, wake: impl Fn() + Send + Sync + 'static) -> Option<Arc<Presenter>> {
        let (latest, pending) = (self.latest.clone(), self.pending.clone());
        let presenter = Arc::new(Presenter::cpu(Box::new(move |width, height, bgra| {
            // gpui's RenderImage holds BGRA bytes in an RgbaImage.
            let Some(buffer) = image::RgbaImage::from_raw(width, height, bgra.to_vec()) else { return };
            let picture = Arc::new(RenderImage::new(vec![image::Frame::new(buffer)]));
            *latest.lock().unwrap_or_else(PoisonError::into_inner) = Some(picture);
            if !pending.swap(true, Ordering::AcqRel) {
                wake();
            }
        }))?);
        *self.presenter.borrow_mut() = Some(presenter.clone());
        Some(presenter)
    }

    /// Takes the newest picture for this render; the one it replaces leaves the sprite atlas.
    pub fn refresh(&self, window: &mut Window) -> anyhow::Result<()> {
        self.scale.set(window.scale_factor());
        self.pending.store(false, Ordering::Release);
        let latest = self.latest.lock().unwrap_or_else(PoisonError::into_inner).clone();
        let old = self.shown.replace(latest.clone());
        match old {
            Some(old) if latest.as_ref().is_none_or(|new| new.id != old.id) => window.drop_image(old),
            _ => Ok(()),
        }
    }

    /// Paints the current picture into the video box.
    pub fn underlay(&self, frame: Stateful<Div>) -> Stateful<Div> {
        frame.bg(black()).when_some(self.shown.borrow().clone(), |frame, picture| {
            frame.child(img(picture).absolute().inset_0().size_full().object_fit(ObjectFit::Contain))
        })
    }

    /// Fits decoded pictures to the box in device pixels.
    pub fn place(&self, bounds: Bounds<Pixels>, visible: bool) {
        if !visible {
            return;
        }
        let scale = self.scale.get();
        let fit = (
            (f32::from(bounds.size.width) * scale).round() as u32,
            (f32::from(bounds.size.height) * scale).round() as u32,
        );
        if fit.0 == 0 || fit.1 == 0 || fit == self.fit.get() {
            return;
        }
        self.fit.set(fit);
        if let Some(presenter) = self.presenter.borrow().as_ref() {
            presenter.set_size(fit.0, fit.1);
        }
    }

    pub fn hide(&self) {}
}

/// Minimized, or ordered out (hidden or closed to the Dock), as opposed to merely covered.
#[cfg(target_os = "macos")]
pub fn window_out_of_sight(window: &Window) -> bool {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::AnyObject;
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};

    let Ok(handle) = HasWindowHandle::window_handle(window) else { return false };
    let RawWindowHandle::AppKit(appkit) = handle.as_raw() else { return false };
    let view = appkit.ns_view.as_ptr().cast::<AnyObject>();
    // SAFETY: gpui's live NSView; NSWindow state queries on the main thread.
    unsafe {
        let ns_window: Option<Retained<AnyObject>> = msg_send![&*view, window];
        let Some(ns_window) = ns_window else { return false };
        let minimized: bool = msg_send![&*ns_window, isMiniaturized];
        let visible: bool = msg_send![&*ns_window, isVisible];
        minimized || !visible
    }
}

#[cfg(target_os = "windows")]
pub fn window_out_of_sight(window: &Window) -> bool {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{IsIconic, IsWindowVisible};
    let Some(hwnd) = crate::desktop::window_hwnd(window) else { return false };
    // SAFETY: gpui's live HWND; state queries on its thread.
    unsafe { IsIconic(HWND(hwnd)).as_bool() || !IsWindowVisible(HWND(hwnd)).as_bool() }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
pub fn window_out_of_sight(_: &Window) -> bool {
    true
}

/// Hack: gpui creates the window's AccessKit adapter after the window first became key, so the
/// adapter believes its view is unfocused and VoiceOver hears only the window until the next key
/// change. Replaying `windowDidBecomeKey:` (the window is its own delegate) tells it.
#[cfg(target_os = "macos")]
pub fn sync_accessibility_focus(window: &Window) {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::AnyObject;
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};

    let Ok(handle) = HasWindowHandle::window_handle(window) else { return };
    let RawWindowHandle::AppKit(appkit) = handle.as_raw() else { return };
    let view = appkit.ns_view.as_ptr().cast::<AnyObject>();
    // SAFETY: gpui's live NSView and its window, on the main thread; gpui's own delegate method.
    unsafe {
        let ns_window: Option<Retained<AnyObject>> = msg_send![&*view, window];
        let Some(ns_window) = ns_window else { return };
        let key: bool = msg_send![&*ns_window, isKeyWindow];
        if key {
            let _: () = msg_send![&*ns_window, windowDidBecomeKey: std::ptr::null_mut::<AnyObject>()];
        }
    }
}

#[cfg(not(target_os = "macos"))]
pub fn sync_accessibility_focus(_: &Window) {}

/// Hack: gpui puts its AccessKit adapter on the window's content view, but the first responder is
/// the GPUIView inside it, and AppKit asks the first responder for the focused element. Without
/// this, VoiceOver is told the window itself has focus and never reads the focused control.
#[cfg(target_os = "macos")]
pub fn forward_accessibility_focus(window: &Window) {
    use std::sync::Once;

    use objc2::runtime::{AnyClass, AnyObject, Imp, Sel};
    use objc2::{msg_send, sel};
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};

    unsafe extern "C-unwind" fn focused_element(this: *mut AnyObject, _: Sel) -> *mut AnyObject {
        // SAFETY: `this` is a live GPUIView receiving an AppKit accessibility message.
        unsafe {
            let superview: *mut AnyObject = msg_send![this, superview];
            if superview.is_null() {
                return std::ptr::null_mut();
            }
            msg_send![superview, accessibilityFocusedUIElement]
        }
    }

    static ONCE: Once = Once::new();
    let Ok(handle) = HasWindowHandle::window_handle(window) else { return };
    let RawWindowHandle::AppKit(appkit) = handle.as_raw() else { return };
    let view = appkit.ns_view.as_ptr().cast::<AnyObject>();
    ONCE.call_once(|| {
        // SAFETY: adds a method to gpui's own NSView class, whose superclass only inherits
        // NSView's; the signature matches `-(id)accessibilityFocusedUIElement`.
        unsafe {
            let class = (*view).class() as *const AnyClass as *mut AnyClass;
            let imp: Imp = std::mem::transmute::<
                unsafe extern "C-unwind" fn(*mut AnyObject, Sel) -> *mut AnyObject,
                Imp,
            >(focused_element);
            objc2::ffi::class_addMethod(class, sel!(accessibilityFocusedUIElement), imp, c"@@:".as_ptr());
        }
    });
}

#[cfg(not(target_os = "macos"))]
pub fn forward_accessibility_focus(_: &Window) {}
