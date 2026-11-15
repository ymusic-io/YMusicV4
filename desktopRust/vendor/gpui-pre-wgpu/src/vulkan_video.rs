//! Vulkan video decode on gpui's own device. When the adapter has a video decode queue, the
//! device opens with the decode extensions plus decode and compute queues of its own, and a
//! [`VulkanDeviceShare`] describes it for a decoder to adopt (FFmpeg's `AVVulkanDeviceContext`):
//! its pictures then draw straight from the decoder's images.

use ash::vk;
use ash::vk::Handle as _;
use parking_lot::Mutex;
use parking_lot::lock_api::RawMutex as _;
use std::ffi::{CStr, CString, c_void};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use wgpu::hal::api::Vulkan;

const VIDEO_DECODE_VP9: &CStr = c"VK_KHR_video_decode_vp9";
/// `VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VIDEO_DECODE_VP9_FEATURES_KHR`, newer than ash's headers.
const VP9_FEATURES: vk::StructureType = vk::StructureType::from_raw(1_000_514_000);
const QUEUE_PRIORITY: [f32; 1] = [1.0];

/// `VkPhysicalDeviceVideoDecodeVP9FeaturesKHR`.
#[repr(C)]
struct Vp9Features {
    s_type: vk::StructureType,
    p_next: *mut c_void,
    video_decode_vp9: vk::Bool32,
}

impl Vp9Features {
    fn new(enabled: bool) -> Self {
        Self {
            s_type: VP9_FEATURES,
            p_next: std::ptr::null_mut(),
            video_decode_vp9: enabled.into(),
        }
    }
}

/// A queue family the decoder may use, in its order of preference.
#[derive(Clone, Copy, Debug)]
pub struct VideoQueueFamily {
    pub index: u32,
    /// Queues `0..queue_count` of the family exist on the device.
    pub queue_count: u32,
    /// `VkQueueFlags` the decoder may use the family for.
    pub flags: u32,
    /// `VkVideoCodecOperationFlagsKHR` of a decode family.
    pub video_codec_operations: u32,
}

/// gpui's Vulkan device, opened with video decode, for a decoder to create its own context on.
/// Holds the device alive: a decoder keeps this as long as it uses the handles.
pub struct VulkanDeviceShare {
    /// Distinguishes devices: a recovered renderer draws only pictures of its own device.
    pub generation: u64,
    /// `PFN_vkGetInstanceProcAddr`.
    pub get_instance_proc_addr: usize,
    pub instance: u64,
    pub physical_device: u64,
    pub device: u64,
    pub instance_extensions: Vec<CString>,
    pub device_extensions: Vec<CString>,
    /// `videoDecodeVP9` is enabled; `timelineSemaphore` and `synchronization2` always are.
    pub video_decode_vp9: bool,
    pub queue_families: Vec<VideoQueueFamily>,
    /// The queue gpui renders and presents on; see [`VulkanDeviceShare::lock_queue`].
    pub render_queue_family: u32,
    pub render_queue_index: u32,
    queue_lock: parking_lot::RawMutex,
    device_handle: Arc<wgpu::Device>,
    _queue: Arc<wgpu::Queue>,
}

impl VulkanDeviceShare {
    /// Serializes a submission to the render queue with gpui's own submits and presents.
    pub fn lock_queue(&self) {
        self.queue_lock.lock();
    }

    /// # Safety
    /// Only after a [`VulkanDeviceShare::lock_queue`] by the same caller.
    pub unsafe fn unlock_queue(&self) {
        // SAFETY: the caller holds the lock.
        unsafe { self.queue_lock.unlock() }
    }

    pub(crate) fn queue_guard(&self) -> QueueGuard<'_> {
        self.lock_queue();
        QueueGuard(self)
    }

    pub(crate) fn owns(&self, device: &Arc<wgpu::Device>) -> bool {
        Arc::ptr_eq(&self.device_handle, device)
    }
}

pub(crate) struct QueueGuard<'a>(&'a VulkanDeviceShare);

impl Drop for QueueGuard<'_> {
    fn drop(&mut self) {
        // SAFETY: taken in `queue_guard`.
        unsafe { self.0.unlock_queue() }
    }
}

type ShareListener = Box<dyn Fn(Option<Arc<VulkanDeviceShare>>) + Send + Sync>;

static SHARE: Mutex<Option<Arc<VulkanDeviceShare>>> = Mutex::new(None);
static LISTENER: Mutex<Option<ShareListener>> = Mutex::new(None);
static GENERATION: AtomicU64 = AtomicU64::new(0);

/// The current device gpui renders with, when it decodes video.
pub fn vulkan_device_share() -> Option<Arc<VulkanDeviceShare>> {
    SHARE.lock().clone()
}

/// Calls `listener` with the current [`vulkan_device_share`] now, then with each device gpui
/// opens later (a recovery after a device loss), on the thread that opens it.
pub fn on_vulkan_device_share(
    listener: impl Fn(Option<Arc<VulkanDeviceShare>>) + Send + Sync + 'static,
) {
    let mut slot = LISTENER.lock();
    listener(vulkan_device_share());
    *slot = Some(Box::new(listener));
}

struct Plan {
    decode_family: u32,
    decode_operations: vk::VideoCodecOperationFlagsKHR,
    /// A compute family other than the render family's, where the decoder works alone.
    compute_family: Option<u32>,
    extensions: Vec<&'static CStr>,
    vp9: bool,
}

/// wgpu-hal renders on queue 0 of family 0.
const RENDER_FAMILY: u32 = 0;

fn plan(adapter: &wgpu::Adapter) -> Option<Plan> {
    // SAFETY: only reads through the hal adapter while the guard lives.
    let hal = unsafe { adapter.as_hal::<Vulkan>() }?;
    let shared = hal.shared_instance();
    if shared.instance_api_version() < vk::API_VERSION_1_3 {
        return None;
    }
    let instance = shared.raw_instance();
    let physical = hal.raw_physical_device();
    // SAFETY: `physical` belongs to `instance`.
    let properties = unsafe { instance.get_physical_device_properties(physical) };
    if properties.api_version < vk::API_VERSION_1_3 {
        return None;
    }
    // SAFETY: as above.
    let supported = unsafe { instance.enumerate_device_extension_properties(physical) }.ok()?;
    let has = |name: &CStr| {
        supported
            .iter()
            .any(|extension| extension.extension_name_as_c_str() == Ok(name))
    };
    if !has(ash::khr::video_queue::NAME) || !has(ash::khr::video_decode_queue::NAME) {
        return None;
    }

    let mut timeline = vk::PhysicalDeviceTimelineSemaphoreFeatures::default();
    let mut sync2 = vk::PhysicalDeviceSynchronization2Features::default();
    let mut vp9_features = Vp9Features::new(false);
    let mut features = vk::PhysicalDeviceFeatures2::default()
        .push_next(&mut timeline)
        .push_next(&mut sync2);
    if has(VIDEO_DECODE_VP9) {
        vp9_features.p_next = features.p_next;
        features.p_next = (&raw mut vp9_features).cast();
    }
    // SAFETY: a well-formed chain of structs that outlive the call.
    unsafe { instance.get_physical_device_features2(physical, &mut features) };
    let vp9 = has(VIDEO_DECODE_VP9) && vp9_features.video_decode_vp9 == vk::TRUE;
    if timeline.timeline_semaphore != vk::TRUE || sync2.synchronization2 != vk::TRUE {
        return None;
    }

    let codecs = [
        (ash::khr::video_decode_h264::NAME, vk::VideoCodecOperationFlagsKHR::DECODE_H264),
        (ash::khr::video_decode_h265::NAME, vk::VideoCodecOperationFlagsKHR::DECODE_H265),
        (ash::khr::video_decode_av1::NAME, vk::VideoCodecOperationFlagsKHR::DECODE_AV1),
        (VIDEO_DECODE_VP9, vk::VideoCodecOperationFlagsKHR::from_raw(0x8)),
    ];
    let usable: Vec<_> = codecs
        .into_iter()
        .filter(|(name, _)| has(name) && (*name != VIDEO_DECODE_VP9 || vp9))
        .collect();
    let codec_operations = usable
        .iter()
        .fold(vk::VideoCodecOperationFlagsKHR::empty(), |all, (_, operation)| all | *operation);

    // SAFETY: as above.
    let count = unsafe { instance.get_physical_device_queue_family_properties2_len(physical) };
    let mut video = vec![vk::QueueFamilyVideoPropertiesKHR::default(); count];
    let flags: Vec<vk::QueueFlags> = {
        let mut families: Vec<_> = video
            .iter_mut()
            .map(|video| vk::QueueFamilyProperties2::default().push_next(video))
            .collect();
        // SAFETY: `families` has the length the driver reported, each with its own chain.
        unsafe { instance.get_physical_device_queue_family_properties2(physical, &mut families) };
        families
            .iter()
            .map(|family| family.queue_family_properties.queue_flags)
            .collect()
    };
    let decode_family = (0..count).find(|&index| {
        flags[index].contains(vk::QueueFlags::VIDEO_DECODE_KHR)
            && video[index].video_codec_operations.intersects(codec_operations)
    })?;
    let compute_family = (0..count).find(|&index| {
        index as u32 != RENDER_FAMILY
            && flags[index].contains(vk::QueueFlags::COMPUTE)
            && !flags[index]
                .intersects(vk::QueueFlags::VIDEO_DECODE_KHR | vk::QueueFlags::VIDEO_ENCODE_KHR)
    });
    let decode_operations = video[decode_family].video_codec_operations & codec_operations;

    let mut extensions = vec![
        ash::khr::video_queue::NAME,
        ash::khr::video_decode_queue::NAME,
    ];
    extensions.extend(
        usable
            .iter()
            .filter(|(_, operation)| decode_operations.contains(*operation))
            .map(|(name, _)| *name),
    );
    if has(ash::khr::video_maintenance1::NAME) {
        extensions.push(ash::khr::video_maintenance1::NAME);
    }
    Some(Plan {
        decode_family: decode_family as u32,
        decode_operations,
        compute_family: compute_family.map(|index| index as u32),
        extensions,
        vp9: vp9 && decode_operations.contains(vk::VideoCodecOperationFlagsKHR::from_raw(0x8)),
    })
}

/// Opens the device with video decode when the adapter has a decode queue; `None` when it has
/// none, so the caller opens a plain device.
pub(crate) fn open_device(
    adapter: &wgpu::Adapter,
    descriptor: &wgpu::DeviceDescriptor<'_>,
) -> anyhow::Result<Option<(wgpu::Device, wgpu::Queue)>> {
    let Some(plan) = plan(adapter) else {
        return Ok(None);
    };
    let required_features = descriptor.required_features
        | (adapter.features()
            & (wgpu::Features::TEXTURE_FORMAT_NV12 | wgpu::Features::TEXTURE_FORMAT_P010));
    let mut sync2 = vk::PhysicalDeviceSynchronization2Features::default().synchronization2(true);
    let mut vp9 = Vp9Features::new(true);
    let open = {
        // SAFETY: the hal adapter is only used to open the device below.
        let hal = unsafe { adapter.as_hal::<Vulkan>() }
            .ok_or_else(|| anyhow::anyhow!("not a Vulkan adapter"))?;
        let sync2_ptr = &raw mut sync2;
        let vp9_ptr = &raw mut vp9;
        let Plan {
            decode_family,
            compute_family,
            extensions,
            vp9: vp9_enabled,
            ..
        } = plan;
        let callback = move |args: wgpu::hal::vulkan::CreateDeviceCallbackArgs<'_, '_, '_>| {
            for extension in extensions {
                if !args.extensions.contains(&extension) {
                    args.extensions.push(extension);
                }
            }
            for family in std::iter::once(decode_family).chain(compute_family) {
                args.queue_create_infos.push(
                    vk::DeviceQueueCreateInfo::default()
                        .queue_family_index(family)
                        .queue_priorities(&QUEUE_PRIORITY),
                );
            }
            // SAFETY: both structs outlive device creation, which happens inside
            // `open_with_callback`; wgpu chains neither of their types itself.
            unsafe {
                (*sync2_ptr).p_next = args.create_info.p_next.cast_mut();
                args.create_info.p_next = sync2_ptr.cast_const().cast();
                if vp9_enabled {
                    (*vp9_ptr).p_next = args.create_info.p_next.cast_mut();
                    args.create_info.p_next = vp9_ptr.cast_const().cast();
                }
            }
        };
        // SAFETY: every requested extension and feature was checked against the adapter.
        unsafe {
            hal.open_with_callback(
                required_features,
                &descriptor.required_limits,
                &descriptor.memory_hints,
                Some(Box::new(callback)),
            )
        }?
    };
    let descriptor = wgpu::DeviceDescriptor {
        required_features,
        ..descriptor.clone()
    };
    // SAFETY: `open` is this adapter's device, opened with `descriptor`'s features and limits.
    let (device, queue) = unsafe { adapter.create_device_from_hal::<Vulkan>(open, &descriptor) }?;
    Ok(Some((device, queue)))
}

/// Makes `device` the current [`vulkan_device_share`], or clears it when it has no video.
pub(crate) fn publish(adapter: &wgpu::Adapter, device: &Arc<wgpu::Device>, queue: &Arc<wgpu::Queue>) {
    let share = describe(adapter, device, queue);
    if let Some(share) = &share {
        log::info!(
            "Vulkan video decode on the render device: families {:?}",
            share.queue_families
        );
    }
    let share = share.map(Arc::new);
    *SHARE.lock() = share.clone();
    if let Some(listener) = LISTENER.lock().as_ref() {
        listener(share);
    }
}

fn describe(
    adapter: &wgpu::Adapter,
    device: &Arc<wgpu::Device>,
    queue: &Arc<wgpu::Queue>,
) -> Option<VulkanDeviceShare> {
    let plan = plan(adapter)?;
    // SAFETY: only reads handles and lists; the device outlives the share, which holds it.
    let hal = unsafe { device.as_hal::<Vulkan>() }?;
    let enabled = hal.enabled_device_extensions();
    if !enabled.contains(&ash::khr::video_decode_queue::NAME) {
        return None;
    }
    let instance = hal.shared_instance();
    let render_queue_family = hal.queue_family_index();
    let compute_flags = (vk::QueueFlags::COMPUTE | vk::QueueFlags::TRANSFER).as_raw();
    let mut queue_families = Vec::with_capacity(3);
    if let Some(index) = plan.compute_family {
        queue_families.push(VideoQueueFamily {
            index,
            queue_count: 1,
            flags: compute_flags,
            video_codec_operations: 0,
        });
    }
    queue_families.push(VideoQueueFamily {
        index: plan.decode_family,
        queue_count: 1,
        flags: vk::QueueFlags::VIDEO_DECODE_KHR.as_raw(),
        video_codec_operations: plan.decode_operations.as_raw(),
    });
    queue_families.push(VideoQueueFamily {
        index: render_queue_family,
        queue_count: 1,
        flags: if plan.compute_family.is_some() {
            vk::QueueFlags::GRAPHICS.as_raw()
        } else {
            vk::QueueFlags::GRAPHICS.as_raw() | compute_flags
        },
        video_codec_operations: 0,
    });
    Some(VulkanDeviceShare {
        generation: GENERATION.fetch_add(1, Ordering::Relaxed) + 1,
        get_instance_proc_addr: instance.entry().static_fn().get_instance_proc_addr as usize,
        instance: instance.raw_instance().handle().as_raw(),
        physical_device: hal.raw_physical_device().as_raw(),
        device: hal.raw_device().handle().as_raw(),
        instance_extensions: instance
            .extensions()
            .iter()
            .map(|name| (*name).to_owned())
            .collect(),
        device_extensions: enabled.iter().map(|name| (*name).to_owned()).collect(),
        video_decode_vp9: plan.vp9,
        queue_families,
        render_queue_family,
        render_queue_index: hal.queue_index(),
        queue_lock: parking_lot::RawMutex::INIT,
        device_handle: Arc::clone(device),
        _queue: Arc::clone(queue),
    })
}
