//! Content under gpui's own DirectComposition visual, in the same window: a composition swap chain
//! another D3D11 device presents into shows wherever gpui leaves its frame transparent, under
//! gpui's controls and with no airspace. While one shows, gpui clears to transparent and draws
//! text in grayscale (subpixel text writes no alpha).

use std::{cell::RefCell, ffi::c_void};

use anyhow::Result;
use gpui::{Bounds, Size};
use windows::{
    Win32::{
        Foundation::{HWND, POINT, RECT},
        Graphics::{
            Direct3D11::{
                ID3D11Device, ID3D11DeviceContext1, ID3D11RenderTargetView, ID3D11Texture2D,
            },
            DirectComposition::*,
            Dxgi::{Common::*, IDXGISwapChain1},
        },
    },
    core::Interface,
};
use windows_numerics::Matrix3x2;

use crate::window_from_hwnd;

/// Where the underlay shows, in device pixels from the client area's top-left.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Placement {
    /// The box: black, with the content fitted and centered in it.
    pub bounds: Bounds<f32>,
    /// The content's size in its own pixels (after the swap chain's matrix transform, if any).
    pub content: Size<f32>,
    /// Only this part of the box shows.
    pub clip: Bounds<f32>,
    pub corner_radius: f32,
    pub visible: bool,
}

/// Shows `swap_chain` under gpui's visual in `hwnd`'s window, hidden until [`place`]d, in place
/// of what was attached. Takes effect with the window's next frame. False when `hwnd` is not a
/// gpui window or the window does not composite (`GPUI_DISABLE_DIRECT_COMPOSITION`).
///
/// # Safety
/// `swap_chain` is a live `IDXGISwapChain1` from `CreateSwapChainForComposition`; the window
/// takes its own reference.
pub unsafe fn attach(hwnd: isize, swap_chain: *mut c_void) -> bool {
    let Some(window) = window_from_hwnd(HWND(hwnd as *mut c_void)) else {
        return false;
    };
    // SAFETY: the caller vouches for the pointer; `cloned` adds the window's reference.
    let Some(swap_chain) = (unsafe { IDXGISwapChain1::from_raw_borrowed(&swap_chain) }).cloned()
    else {
        return false;
    };
    let mut underlay = window.state.underlay.0.borrow_mut();
    if !underlay.composited {
        return false;
    }
    underlay.swap_chain = Some(swap_chain);
    underlay.dirty = true;
    true
}

/// Moves, clips or hides the underlay; applied with the window's next frame, which this call is
/// expected to be painting.
pub fn place(hwnd: isize, placement: Placement) {
    let Some(window) = window_from_hwnd(HWND(hwnd as *mut c_void)) else {
        return;
    };
    let mut underlay = window.state.underlay.0.borrow_mut();
    if underlay.placement != placement {
        underlay.placement = placement;
        underlay.dirty = true;
    }
}

/// Takes the underlay out of `hwnd`'s window with its next frame.
pub fn detach(hwnd: isize) {
    let Some(window) = window_from_hwnd(HWND(hwnd as *mut c_void)) else {
        return;
    };
    let mut underlay = window.state.underlay.0.borrow_mut();
    if underlay.swap_chain.take().is_some() {
        underlay.dirty = true;
    }
}

#[derive(Default)]
struct Requested {
    composited: bool,
    swap_chain: Option<IDXGISwapChain1>,
    placement: Placement,
    dirty: bool,
}

/// A window's requested underlay, written by the public calls, read by its renderer.
#[derive(Default)]
pub(crate) struct UnderlayState(RefCell<Requested>);

impl UnderlayState {
    pub(crate) fn new(composited: bool) -> Self {
        Self(RefCell::new(Requested {
            composited,
            ..Default::default()
        }))
    }

    /// Something shows under gpui this frame, so gpui must leave its box transparent.
    pub(crate) fn shown(&self) -> bool {
        let underlay = self.0.borrow();
        underlay.swap_chain.is_some() && underlay.placement.visible
    }

    /// Forces the next frame to rebuild the visuals (a new DirectComposition device).
    pub(crate) fn invalidate(&self) {
        self.0.borrow_mut().dirty = true;
    }

    /// The request when it changed since the last call.
    pub(crate) fn take_change(&self) -> Option<(Option<IDXGISwapChain1>, Placement)> {
        let mut underlay = self.0.borrow_mut();
        if !std::mem::take(&mut underlay.dirty) {
            return None;
        }
        Some((underlay.swap_chain.clone(), underlay.placement))
    }
}

/// The visuals under gpui's: a container (offset to the box, clipped) holding a black backing
/// and the content.
pub(crate) struct UnderlayVisuals {
    container: IDCompositionVisual,
    clip: IDCompositionRectangleClip,
    backing: IDCompositionVisual,
    _black: IDCompositionSurface,
    content: IDCompositionVisual,
    swap_chain: Option<IDXGISwapChain1>,
    in_tree: bool,
}

impl UnderlayVisuals {
    pub(crate) fn new(
        comp_device: &IDCompositionDevice,
        device: &ID3D11Device,
        device_context: &ID3D11DeviceContext1,
    ) -> Result<Self> {
        unsafe {
            let container = comp_device.CreateVisual()?;
            let clip = comp_device.CreateRectangleClip()?;
            container.SetClip(&clip)?;
            let black = comp_device.CreateSurface(
                1,
                1,
                DXGI_FORMAT_B8G8R8A8_UNORM,
                DXGI_ALPHA_MODE_IGNORE,
            )?;
            let mut offset = POINT::default();
            let texture: ID3D11Texture2D = black.BeginDraw(None, &mut offset)?;
            let mut view: Option<ID3D11RenderTargetView> = None;
            let cleared = device
                .CreateRenderTargetView(&texture, None, Some(&mut view))
                .map(|()| {
                    if let Some(view) = &view {
                        let rect = RECT {
                            left: offset.x,
                            top: offset.y,
                            right: offset.x + 1,
                            bottom: offset.y + 1,
                        };
                        device_context.ClearView(view, &[0.0, 0.0, 0.0, 1.0], Some(&[rect]));
                    }
                });
            black.EndDraw()?;
            cleared?;
            let backing = comp_device.CreateVisual()?;
            backing.SetContent(&black)?;
            backing.SetBitmapInterpolationMode(
                DCOMPOSITION_BITMAP_INTERPOLATION_MODE_NEAREST_NEIGHBOR,
            )?;
            backing.SetBorderMode(DCOMPOSITION_BORDER_MODE_HARD)?;
            let content = comp_device.CreateVisual()?;
            content.SetBitmapInterpolationMode(DCOMPOSITION_BITMAP_INTERPOLATION_MODE_LINEAR)?;
            container.AddVisual(&backing, true, None::<&IDCompositionVisual>)?;
            container.AddVisual(&content, true, &backing)?;
            Ok(Self {
                container,
                clip,
                backing,
                _black: black,
                content,
                swap_chain: None,
                in_tree: false,
            })
        }
    }

    /// Applies a request; the caller commits.
    pub(crate) fn apply(
        &mut self,
        root: &IDCompositionVisual,
        gpui_visual: &IDCompositionVisual,
        swap_chain: Option<IDXGISwapChain1>,
        placement: Placement,
    ) -> Result<()> {
        unsafe {
            if self.swap_chain != swap_chain {
                match &swap_chain {
                    Some(swap_chain) => self.content.SetContent(swap_chain)?,
                    None => self.content.SetContent(None::<&windows::core::IUnknown>)?,
                }
                self.swap_chain = swap_chain;
            }
            let bounds = snap(placement.bounds);
            let show = self.swap_chain.is_some()
                && placement.visible
                && bounds.size.width >= 1.0
                && bounds.size.height >= 1.0;
            if show {
                let (width, height) = (bounds.size.width, bounds.size.height);
                self.container.SetOffsetX2(bounds.origin.x)?;
                self.container.SetOffsetY2(bounds.origin.y)?;
                let clip = snap(placement.clip);
                self.clip.SetLeft2(clip.origin.x - bounds.origin.x)?;
                self.clip.SetTop2(clip.origin.y - bounds.origin.y)?;
                self.clip
                    .SetRight2(clip.origin.x + clip.size.width - bounds.origin.x)?;
                self.clip
                    .SetBottom2(clip.origin.y + clip.size.height - bounds.origin.y)?;
                let radius = placement.corner_radius.max(0.0);
                self.clip.SetTopLeftRadiusX2(radius)?;
                self.clip.SetTopLeftRadiusY2(radius)?;
                self.clip.SetTopRightRadiusX2(radius)?;
                self.clip.SetTopRightRadiusY2(radius)?;
                self.clip.SetBottomLeftRadiusX2(radius)?;
                self.clip.SetBottomLeftRadiusY2(radius)?;
                self.clip.SetBottomRightRadiusX2(radius)?;
                self.clip.SetBottomRightRadiusY2(radius)?;
                self.backing
                    .SetTransform2(&Matrix3x2::scale(width, height))?;
                let content = placement.content;
                let scale = if content.width > 0.0 && content.height > 0.0 {
                    (width / content.width).min(height / content.height)
                } else {
                    0.0
                };
                let fitted = (content.width * scale, content.height * scale);
                self.content.SetTransform2(&Matrix3x2 {
                    M11: scale,
                    M12: 0.0,
                    M21: 0.0,
                    M22: scale,
                    M31: ((width - fitted.0) / 2.0).round(),
                    M32: ((height - fitted.1) / 2.0).round(),
                })?;
            }
            if show != self.in_tree {
                if show {
                    root.AddVisual(&self.container, false, gpui_visual)?;
                } else {
                    root.RemoveVisual(&self.container)?;
                }
                self.in_tree = show;
            }
        }
        Ok(())
    }
}

/// Whole pixels covering `bounds`: the black backing also fills the pixels gpui's antialiased
/// edges leave partly transparent around the box.
fn snap(bounds: Bounds<f32>) -> Bounds<f32> {
    let left = bounds.origin.x.floor();
    let top = bounds.origin.y.floor();
    let right = (bounds.origin.x + bounds.size.width).ceil();
    let bottom = (bounds.origin.y + bounds.size.height).ceil();
    Bounds {
        origin: gpui::point(left, top),
        size: gpui::size(right - left, bottom - top),
    }
}
