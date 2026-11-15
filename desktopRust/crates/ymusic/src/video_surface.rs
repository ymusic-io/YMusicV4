//! Linux: pictures drawn by gpui inside its own scene, as a video `surface` its controls and
//! captions paint over. A picture decoded on gpui's Vulkan device is sampled where the decoder
//! left it; a software-decoded one has its planes uploaded.

use std::cell::RefCell;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::{
    Bounds, DevicePixels, Div, ObjectFit, ParentElement, Pixels, Stateful, Styled, VideoFrame, Window, black, size,
    surface,
};
use gpui_wgpu::{
    VideoColor, VideoMatrix, VideoPlanes, VideoSource, VulkanDeviceShare, VulkanImageAccess, VulkanImageState,
    VulkanVideoFormat, VulkanVideoImage, WgpuVideoFrame,
};
use ym_common::log::Logger;
use ym_mse::{Matrix, Picture, PictureKind, PlaneLayout, Presenter, VulkanFormat};

/// Hands gpui's render device to the decoder once the first window has opened it, and again
/// after each device recovery; a renderer without Vulkan video (GL, no decode queue) leaves
/// decoding in software.
pub fn follow_render_device(log: Logger) {
    gpui_wgpu::on_vulkan_device_share(move |share| share_render_device(share, &log));
}

fn share_render_device(share: Option<Arc<VulkanDeviceShare>>, log: &Logger) {
    let Some(share) = share else {
        log.info("video: the renderer has no Vulkan decode, software decoding");
        ym_mse::decode_on_vulkan(None);
        return;
    };
    let device = ym_mse::VulkanDevice {
        get_instance_proc_addr: share.get_instance_proc_addr,
        instance: share.instance,
        physical_device: share.physical_device,
        device: share.device,
        instance_extensions: share.instance_extensions.clone(),
        device_extensions: share.device_extensions.clone(),
        video_decode_vp9: share.video_decode_vp9,
        queue_families: share
            .queue_families
            .iter()
            .map(|family| ym_mse::QueueFamily {
                index: family.index,
                queue_count: family.queue_count,
                flags: family.flags,
                video_codec_operations: family.video_codec_operations,
            })
            .collect(),
        render_queue_family: share.render_queue_family,
        render_queue_index: share.render_queue_index,
        generation: share.generation,
        owner: Arc::new(RenderDevice(share)),
    };
    if !ym_mse::decode_on_vulkan(Some(device)) {
        log.info("video: the decoder refused the renderer's device, software decoding");
    }
}

struct RenderDevice(Arc<VulkanDeviceShare>);

impl ym_mse::QueueLock for RenderDevice {
    fn lock(&self) {
        self.0.lock_queue();
    }

    unsafe fn unlock(&self) {
        // SAFETY: the decoder pairs this with its own `lock`.
        unsafe { self.0.unlock_queue() }
    }
}

/// A decoded picture as gpui's renderer takes it.
struct Decoded(Picture);

impl VulkanImageAccess for Decoded {
    fn acquire(&self) -> VulkanImageState {
        let state = self.0.vulkan_acquire();
        VulkanImageState {
            image: state.image,
            layout: state.layout,
            semaphore: state.semaphore,
            wait_value: state.wait_value,
        }
    }

    fn release(&self, layout: Option<i32>) {
        self.0.vulkan_release(layout);
    }
}

impl VideoPlanes for Decoded {
    fn plane(&self, index: usize) -> (&[u8], usize) {
        self.0.plane(index).unwrap_or((&[], 0))
    }
}

static STREAMS: AtomicU64 = AtomicU64::new(0);

fn video_frame(picture: Picture, stream: u64, serial: u64) -> VideoFrame {
    let (width, height) = (picture.width, picture.height);
    let color = VideoColor {
        matrix: match picture.matrix {
            Matrix::Bt601 => VideoMatrix::Bt601,
            Matrix::Bt709 => VideoMatrix::Bt709,
            Matrix::Bt2020 => VideoMatrix::Bt2020,
        },
        full_range: picture.full_range,
    };
    let kind = picture.kind;
    let decoded = Arc::new(Decoded(picture));
    let source = match kind {
        PictureKind::Vulkan { coded_width, coded_height, format, generation } => {
            VideoSource::Vulkan(VulkanVideoImage {
                generation,
                format: match format {
                    VulkanFormat::Nv12 => VulkanVideoFormat::Nv12,
                    VulkanFormat::P010 => VulkanVideoFormat::P010,
                },
                coded_width,
                coded_height,
                access: decoded,
            })
        }
        PictureKind::Planes(layout) => VideoSource::Planes {
            stream,
            serial,
            layout: match layout {
                PlaneLayout::Nv12 => gpui_wgpu::PlaneLayout::Nv12,
                PlaneLayout::I420 => gpui_wgpu::PlaneLayout::I420,
                PlaneLayout::I420P10 => gpui_wgpu::PlaneLayout::I420P10,
            },
            planes: decoded,
        },
    };
    let display = size(DevicePixels(width as i32), DevicePixels(height as i32));
    VideoFrame::new(display, Arc::new(WgpuVideoFrame { width, height, color, source }))
}

pub struct VideoLayer {
    /// The newest picture, written on a decode thread.
    latest: Arc<Mutex<Option<VideoFrame>>>,
    /// A wake is outstanding; cleared when a render takes the picture.
    pending: Arc<AtomicBool>,
    shown: RefCell<Option<VideoFrame>>,
}

impl VideoLayer {
    pub fn attach(_window: &Window) -> Option<Self> {
        Some(Self { latest: Arc::default(), pending: Arc::default(), shown: RefCell::default() })
    }

    /// A presenter whose pictures this layer shows; `wake` runs on a decode thread when a picture
    /// arrives (or the picture goes) and no render has taken the last change yet.
    pub fn presenter(&self, wake: impl Fn() + Send + Sync + 'static) -> Option<Arc<Presenter>> {
        let wake = Arc::new(wake);
        let stream = STREAMS.fetch_add(1, Ordering::Relaxed);
        let serial = AtomicU64::new(0);
        let (latest, pending) = (self.latest.clone(), self.pending.clone());
        let (cleared, clear_pending, clear_wake) = (self.latest.clone(), self.pending.clone(), wake.clone());
        let presenter = Presenter::frames(
            move |picture| {
                let frame = video_frame(picture, stream, serial.fetch_add(1, Ordering::Relaxed));
                *latest.lock().unwrap_or_else(PoisonError::into_inner) = Some(frame);
                if !pending.swap(true, Ordering::AcqRel) {
                    wake();
                }
            },
            move || {
                cleared.lock().unwrap_or_else(PoisonError::into_inner).take();
                if !clear_pending.swap(true, Ordering::AcqRel) {
                    clear_wake();
                }
            },
        )?;
        Some(Arc::new(presenter))
    }

    /// Takes the newest picture for this render.
    pub fn refresh(&self, _window: &mut Window) -> anyhow::Result<()> {
        self.pending.store(false, Ordering::Release);
        let latest = self.latest.lock().unwrap_or_else(PoisonError::into_inner).clone();
        self.shown.replace(latest);
        Ok(())
    }

    /// Paints the current picture into the video box, under whatever the box's children paint.
    pub fn underlay(&self, frame: Stateful<Div>) -> Stateful<Div> {
        frame.bg(black()).when_some(self.shown.borrow().clone(), |frame, picture| {
            frame.child(surface(picture).absolute().inset_0().size_full().object_fit(ObjectFit::Contain))
        })
    }

    pub fn place(&self, _bounds: Bounds<Pixels>, _visible: bool) {}

    pub fn hide(&self) {}
}
