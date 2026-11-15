//! Linux: pictures handed over as decoded, for an app that draws them in its own renderer, and
//! the renderer's Vulkan device for decoding on (its images then draw without a copy).

use std::ffi::{CString, c_char, c_int, c_void};
use std::ptr::NonNull;
use std::sync::Arc;

use crate::{Presenter, contain_panic};

const MAX_QUEUE_FAMILIES: usize = 8;
const AVCOL_SPC_BT709: c_int = 1;
const AVCOL_SPC_BT2020_NCL: c_int = 9;
const VK_FORMAT_P010: c_int = 1_000_156_027;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct MseVkQueueFamily {
    index: u32,
    count: u32,
    flags: u32,
    video_ops: u32,
}

#[repr(C)]
struct MseVkDevice {
    get_instance_proc_addr: *mut c_void,
    instance: u64,
    physical_device: u64,
    device: u64,
    instance_extensions: *const *const c_char,
    instance_extension_count: c_int,
    device_extensions: *const *const c_char,
    device_extension_count: c_int,
    video_decode_vp9: c_int,
    families: [MseVkQueueFamily; MAX_QUEUE_FAMILIES],
    family_count: c_int,
    render_family: u32,
    render_index: u32,
    generation: u64,
    lock_queue: Option<unsafe extern "C" fn(user: *mut c_void)>,
    unlock_queue: Option<unsafe extern "C" fn(user: *mut c_void)>,
    release: Option<unsafe extern "C" fn(user: *mut c_void)>,
    user: *mut c_void,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct VpColor {
    matrix: c_int,
    full_range: c_int,
}

#[repr(C)]
struct VpPictureInfo {
    kind: c_int,
    width: c_int,
    height: c_int,
    color: VpColor,
    coded_width: c_int,
    coded_height: c_int,
    vk_format: c_int,
    generation: u64,
    data: [*const u8; 3],
    linesize: [c_int; 3],
}

#[repr(C)]
struct VpFrameSink {
    on_picture: Option<unsafe extern "C" fn(user: *mut c_void, picture: *mut c_void)>,
    on_clear: Option<unsafe extern "C" fn(user: *mut c_void)>,
    release: Option<unsafe extern "C" fn(user: *mut c_void)>,
    user: *mut c_void,
}

unsafe extern "C" {
    fn mse_hwdev_use_vulkan(device: *const MseVkDevice) -> c_int;
    fn vpresent_create_frames(sink: *const VpFrameSink) -> *mut c_void;
    fn vp_picture_info(picture: *const c_void, out: *mut VpPictureInfo);
    fn vp_picture_vk_acquire(
        picture: *mut c_void,
        image: *mut u64,
        layout: *mut i32,
        semaphore: *mut u64,
        wait_value: *mut u64,
    );
    fn vp_picture_vk_release(picture: *mut c_void, submitted: c_int, layout: i32);
    fn vp_picture_free(picture: *mut c_void);
}

/// Serializes the decoder's submissions to the renderer's queue with the renderer's own.
pub trait QueueLock: Send + Sync {
    fn lock(&self);
    /// # Safety
    /// Only after [`QueueLock::lock`] on the same thread.
    unsafe fn unlock(&self);
}

#[derive(Clone, Copy, Debug)]
pub struct QueueFamily {
    pub index: u32,
    pub queue_count: u32,
    /// `VkQueueFlags` the decoder may use it for.
    pub flags: u32,
    /// `VkVideoCodecOperationFlagsKHR` of a decode family.
    pub video_codec_operations: u32,
}

/// The renderer's Vulkan device, opened with video decode.
pub struct VulkanDevice {
    /// `PFN_vkGetInstanceProcAddr`.
    pub get_instance_proc_addr: usize,
    pub instance: u64,
    pub physical_device: u64,
    pub device: u64,
    pub instance_extensions: Vec<CString>,
    pub device_extensions: Vec<CString>,
    pub video_decode_vp9: bool,
    /// Preferred first; at most 8.
    pub queue_families: Vec<QueueFamily>,
    pub render_queue_family: u32,
    pub render_queue_index: u32,
    pub generation: u64,
    /// Locks the render queue, and holds the device alive while the decoder uses it.
    pub owner: Arc<dyn QueueLock>,
}

unsafe extern "C" fn lock_owner(user: *mut c_void) {
    // SAFETY: `user` is the box `decode_on_vulkan` handed over, freed only by `release_owner`.
    let owner = unsafe { &*user.cast::<Arc<dyn QueueLock>>() };
    contain_panic(|| owner.lock());
}

unsafe extern "C" fn unlock_owner(user: *mut c_void) {
    // SAFETY: as in `lock_owner`.
    let owner = unsafe { &*user.cast::<Arc<dyn QueueLock>>() };
    // SAFETY: libav pairs every unlock with its own earlier lock on the same thread.
    contain_panic(|| unsafe { owner.unlock() });
}

unsafe extern "C" fn release_owner(user: *mut c_void) {
    // SAFETY: the C side's last use of `user`, the box from `decode_on_vulkan`.
    let owner = unsafe { Box::from_raw(user.cast::<Arc<dyn QueueLock>>()) };
    contain_panic(|| drop(owner));
}

/// Decodes video on `device` from now on (hardware frames for [`Presenter::frames`], no copy);
/// `None` keeps every decoder in software. True when the decoder took the device.
pub fn decode_on_vulkan(device: Option<VulkanDevice>) -> bool {
    let Some(device) = device else {
        // SAFETY: NULL asks for software decode.
        return unsafe { mse_hwdev_use_vulkan(std::ptr::null()) } == 0;
    };
    let instance_extensions: Vec<*const c_char> = device.instance_extensions.iter().map(|name| name.as_ptr()).collect();
    let device_extensions: Vec<*const c_char> = device.device_extensions.iter().map(|name| name.as_ptr()).collect();
    let mut families = [MseVkQueueFamily::default(); MAX_QUEUE_FAMILIES];
    for (slot, family) in families.iter_mut().zip(&device.queue_families) {
        *slot = MseVkQueueFamily {
            index: family.index,
            count: family.queue_count,
            flags: family.flags,
            video_ops: family.video_codec_operations,
        };
    }
    let user = Box::into_raw(Box::new(device.owner)).cast::<c_void>();
    let raw = MseVkDevice {
        get_instance_proc_addr: device.get_instance_proc_addr as *mut c_void,
        instance: device.instance,
        physical_device: device.physical_device,
        device: device.device,
        instance_extensions: instance_extensions.as_ptr(),
        instance_extension_count: instance_extensions.len() as c_int,
        device_extensions: device_extensions.as_ptr(),
        device_extension_count: device_extensions.len() as c_int,
        video_decode_vp9: device.video_decode_vp9.into(),
        families,
        family_count: device.queue_families.len().min(MAX_QUEUE_FAMILIES) as c_int,
        render_family: device.render_queue_family,
        render_index: device.render_queue_index,
        generation: device.generation,
        lock_queue: Some(lock_owner),
        unlock_queue: Some(unlock_owner),
        release: Some(release_owner),
        user,
    };
    // SAFETY: the strings outlive the call (libav copies them); the C side owns `user` from here
    // and hands it back through `release` exactly once, also when it refuses the device.
    unsafe { mse_hwdev_use_vulkan(&raw) == 0 }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Matrix {
    Bt601,
    Bt709,
    Bt2020,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlaneLayout {
    Nv12,
    I420,
    /// I420 in 16-bit little-endian samples of 10 significant bits.
    I420P10,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VulkanFormat {
    Nv12,
    P010,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PictureKind {
    /// A two-plane image on the device given to [`decode_on_vulkan`] with this `generation`.
    Vulkan {
        coded_width: u32,
        coded_height: u32,
        format: VulkanFormat,
        generation: u64,
    },
    Planes(PlaneLayout),
}

#[derive(Clone, Copy, Debug)]
pub struct VulkanImageState {
    pub image: u64,
    /// `VkImageLayout`.
    pub layout: i32,
    /// A timeline semaphore the decoder is done with the image at.
    pub semaphore: u64,
    pub wait_value: u64,
}

/// One decoded picture, held from the decoder until dropped.
pub struct Picture {
    raw: NonNull<c_void>,
    pub width: u32,
    pub height: u32,
    pub matrix: Matrix,
    pub full_range: bool,
    pub kind: PictureKind,
    /// Per plane: its first row, the stride, the row count.
    planes: [(*const u8, usize, usize); 3],
}

// SAFETY: the C picture is a reference to an immutable decoded frame; the Vulkan lock and release
// go through libav's own per-frame lock, from any thread.
unsafe impl Send for Picture {}
// SAFETY: as for Send.
unsafe impl Sync for Picture {}

impl Picture {
    /// # Safety
    /// `raw` is a picture from the frames presenter, owned by the caller.
    unsafe fn new(raw: NonNull<c_void>) -> Option<Self> {
        let mut info = std::mem::MaybeUninit::<VpPictureInfo>::uninit();
        // SAFETY: `raw` is a live picture; the C side fills every field.
        let info = unsafe {
            vp_picture_info(raw.as_ptr(), info.as_mut_ptr());
            info.assume_init()
        };
        let kind = match info.kind {
            1 => PictureKind::Vulkan {
                coded_width: info.coded_width as u32,
                coded_height: info.coded_height as u32,
                format: if info.vk_format == VK_FORMAT_P010 { VulkanFormat::P010 } else { VulkanFormat::Nv12 },
                generation: info.generation,
            },
            2 => PictureKind::Planes(PlaneLayout::Nv12),
            3 => PictureKind::Planes(PlaneLayout::I420),
            4 => PictureKind::Planes(PlaneLayout::I420P10),
            _ => return None,
        };
        let chroma_rows = (info.height as usize).div_ceil(2);
        let rows = [info.height as usize, chroma_rows, chroma_rows];
        let mut planes = [(std::ptr::null(), 0, 0); 3];
        for (index, plane) in planes.iter_mut().enumerate() {
            if !info.data[index].is_null() && info.linesize[index] > 0 {
                *plane = (info.data[index], info.linesize[index] as usize, rows[index]);
            }
        }
        Some(Self {
            raw,
            width: info.width.max(0) as u32,
            height: info.height.max(0) as u32,
            matrix: match info.color.matrix {
                AVCOL_SPC_BT709 => Matrix::Bt709,
                AVCOL_SPC_BT2020_NCL => Matrix::Bt2020,
                _ => Matrix::Bt601,
            },
            full_range: info.color.full_range != 0,
            kind,
            planes,
        })
    }

    /// A CPU picture's plane: its bytes and the stride between rows.
    pub fn plane(&self, index: usize) -> Option<(&[u8], usize)> {
        let (data, stride, rows) = *self.planes.get(index)?;
        if data.is_null() {
            return None;
        }
        // SAFETY: the frame this picture references holds `rows` rows of `stride` bytes in this
        // plane until the picture is dropped.
        Some((unsafe { std::slice::from_raw_parts(data, stride * rows) }, stride))
    }

    /// Locks a Vulkan picture's image against its decoder until [`Picture::vulkan_release`].
    pub fn vulkan_acquire(&self) -> VulkanImageState {
        let mut state = VulkanImageState { image: 0, layout: 0, semaphore: 0, wait_value: 0 };
        // SAFETY: a live Vulkan picture; the C side locks the frame and fills the outputs.
        unsafe {
            vp_picture_vk_acquire(
                self.raw.as_ptr(),
                &mut state.image,
                &mut state.layout,
                &mut state.semaphore,
                &mut state.wait_value,
            );
        }
        state
    }

    /// Unlocks it. `Some(layout)`: a submission waits for the acquired value, signals the next
    /// one and leaves the image in `layout`; `None`: nothing was submitted.
    pub fn vulkan_release(&self, layout: Option<i32>) {
        // SAFETY: pairs with the caller's `vulkan_acquire` on this live picture.
        unsafe { vp_picture_vk_release(self.raw.as_ptr(), layout.is_some().into(), layout.unwrap_or(0)) }
    }
}

impl Drop for Picture {
    fn drop(&mut self) {
        // SAFETY: this picture's one owner lets go of it.
        unsafe { vp_picture_free(self.raw.as_ptr()) }
    }
}

struct FramesSink {
    on_picture: Box<dyn Fn(Picture) + Send + Sync>,
    on_clear: Box<dyn Fn() + Send + Sync>,
}

unsafe extern "C" fn on_picture(user: *mut c_void, picture: *mut c_void) {
    let Some(raw) = NonNull::new(picture) else { return };
    // SAFETY: the sink owns every picture it is handed.
    let Some(picture) = (unsafe { Picture::new(raw) }) else {
        // SAFETY: an unknown kind still belongs to the sink.
        unsafe { vp_picture_free(raw.as_ptr()) };
        return;
    };
    // SAFETY: `user` is the box `Presenter::frames` handed over, freed only by `release_frames`.
    let sink = unsafe { &*user.cast::<FramesSink>() };
    contain_panic(|| (sink.on_picture)(picture));
}

unsafe extern "C" fn on_clear(user: *mut c_void) {
    // SAFETY: as in `on_picture`.
    let sink = unsafe { &*user.cast::<FramesSink>() };
    contain_panic(|| (sink.on_clear)());
}

unsafe extern "C" fn release_frames(user: *mut c_void) {
    // SAFETY: the sink's last call (or a failed create's cleanup): the box from `Presenter::frames`.
    let sink = unsafe { Box::from_raw(user.cast::<FramesSink>()) };
    contain_panic(|| drop(sink));
}

impl Presenter {
    /// Hands every picture to `on_picture` as decoded, on a decode thread: a Vulkan image on the
    /// device given to [`decode_on_vulkan`], or CPU planes. `on_clear`: nothing shows any more.
    pub fn frames(
        on_picture: impl Fn(Picture) + Send + Sync + 'static,
        on_clear: impl Fn() + Send + Sync + 'static,
    ) -> Option<Self> {
        let user =
            Box::into_raw(Box::new(FramesSink { on_picture: Box::new(on_picture), on_clear: Box::new(on_clear) }))
                .cast::<c_void>();
        let sink = VpFrameSink {
            on_picture: Some(self::on_picture),
            on_clear: Some(self::on_clear),
            release: Some(release_frames),
            user,
        };
        // SAFETY: the C side copies the sink and owns `user` from here, handing it back through
        // `release` as its last call.
        let raw = unsafe { vpresent_create_frames(&sink) };
        if raw.is_null() {
            // SAFETY: a failed create never calls `release`, so `user` is still this box.
            unsafe { release_frames(user) };
            return None;
        }
        Some(Self { raw })
    }
}
