//! Video pictures for gpui's `surface` element: the payload of a [`gpui::VideoFrame`] and how the
//! renderer draws it. CPU planes upload into textures each stream keeps; a decoder's Vulkan image
//! on the render device is sampled where it lies, with no copy.

use bytemuck::{Pod, Zeroable};
use collections::FxHashMap;
use gpui::{Bounds, ScaledPixels, Scene};
use std::num::NonZeroU64;
use std::ops::Range;
use std::sync::Arc;

/// The payload of a [`gpui::VideoFrame`] this renderer draws.
pub struct WgpuVideoFrame {
    /// Visible size in pixels.
    pub width: u32,
    pub height: u32,
    pub color: VideoColor,
    pub source: VideoSource,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VideoMatrix {
    Bt601,
    Bt709,
    Bt2020,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VideoColor {
    pub matrix: VideoMatrix,
    pub full_range: bool,
}

pub enum VideoSource {
    /// Decoded on the CPU: uploaded once per `serial` into textures kept for `stream`.
    Planes {
        stream: u64,
        serial: u64,
        layout: PlaneLayout,
        planes: Arc<dyn VideoPlanes>,
    },
    /// A decoder's image on the render device, see `vulkan_device_share`.
    #[cfg(all(
        not(target_family = "wasm"),
        any(target_os = "linux", target_os = "freebsd")
    ))]
    Vulkan(VulkanVideoImage),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlaneLayout {
    /// Y, then interleaved CbCr at half size.
    Nv12,
    /// Y, Cb, Cr, the chroma at half size.
    I420,
    /// As [`PlaneLayout::I420`], in 16-bit little-endian samples of 10 significant bits.
    I420P10,
}

/// The planes of a CPU picture.
pub trait VideoPlanes: Send + Sync {
    /// Plane `index`: its rows, `stride` bytes apart.
    fn plane(&self, index: usize) -> (&[u8], usize);
}

#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VulkanVideoFormat {
    /// `VK_FORMAT_G8_B8R8_2PLANE_420_UNORM`.
    Nv12,
    /// `VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16`.
    P010,
}

/// A two-plane image of a decoder working on the render device.
#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
pub struct VulkanVideoImage {
    /// The decoding device's `VulkanDeviceShare::generation`.
    pub generation: u64,
    pub format: VulkanVideoFormat,
    pub coded_width: u32,
    pub coded_height: u32,
    pub access: Arc<dyn VulkanImageAccess>,
}

/// The decoder's side of an image: it locks the image for a draw and keeps it alive as long as
/// the renderer holds this.
#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
pub trait VulkanImageAccess: Send + Sync + 'static {
    /// Locks the image against its decoder until [`VulkanImageAccess::release`].
    fn acquire(&self) -> VulkanImageState;
    /// Unlocks it. `Some(layout)`: a submission waits for the acquired semaphore value, signals
    /// the next one and leaves the image in `layout`. `None`: nothing was submitted.
    fn release(&self, layout: Option<i32>);
}

#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
#[derive(Clone, Copy, Debug)]
pub struct VulkanImageState {
    pub image: u64,
    /// `VkImageLayout`.
    pub layout: i32,
    /// A timeline semaphore that reaches `wait_value` when the decoder is done with the image.
    pub semaphore: u64,
    pub wait_value: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
pub(crate) struct SurfaceParams {
    bounds: [f32; 4],
    content_mask: [f32; 4],
    color: [[f32; 4]; 4],
    uv_scale: [f32; 2],
    planes: u32,
    pad: u32,
}

pub(crate) const SURFACE_PARAMS_SIZE: u64 = std::mem::size_of::<SurfaceParams>() as u64;

fn pod_bounds(bounds: &Bounds<ScaledPixels>) -> [f32; 4] {
    [
        bounds.origin.x.0,
        bounds.origin.y.0,
        bounds.size.width.0,
        bounds.size.height.0,
    ]
}

/// (Y, Cb, Cr, 1) → R'G'B', column-major; `sample_scale` brings a sample to 0..1.
fn color_matrix(color: VideoColor, sample_scale: f32) -> [[f32; 4]; 4] {
    let (kr, kb) = match color.matrix {
        VideoMatrix::Bt601 => (0.299, 0.114),
        VideoMatrix::Bt709 => (0.2126, 0.0722),
        VideoMatrix::Bt2020 => (0.2627, 0.0593),
    };
    let kg = 1.0 - kr - kb;
    let (luma_scale, luma_offset, chroma_scale) = if color.full_range {
        (1.0, 0.0, 1.0)
    } else {
        (255.0 / 219.0, -16.0 / 219.0, 255.0 / 224.0)
    };
    let chroma_offset = -128.0 / 255.0 * chroma_scale;
    let (cr_to_r, cb_to_b) = (2.0 * (1.0 - kr), 2.0 * (1.0 - kb));
    let (cb_to_g, cr_to_g) = (cb_to_b * kb / kg, cr_to_r * kr / kg);
    let luma = luma_scale * sample_scale;
    let chroma = chroma_scale * sample_scale;
    [
        [luma, luma, luma, 0.0],
        [0.0, -cb_to_g * chroma, cb_to_b * chroma, 0.0],
        [cr_to_r * chroma, -cr_to_g * chroma, 0.0, 0.0],
        [
            luma_offset + cr_to_r * chroma_offset,
            luma_offset - (cb_to_g + cr_to_g) * chroma_offset,
            luma_offset + cb_to_b * chroma_offset,
            1.0,
        ],
    ]
}

struct StreamTextures {
    layout: PlaneLayout,
    size: (u32, u32),
    textures: Vec<wgpu::Texture>,
    views: Vec<wgpu::TextureView>,
    serial: Option<u64>,
    drawn: bool,
}

impl StreamTextures {
    fn new(device: &wgpu::Device, layout: PlaneLayout, width: u32, height: u32) -> Self {
        let chroma = (width.div_ceil(2), height.div_ceil(2));
        let planes: &[(wgpu::TextureFormat, (u32, u32))] = match layout {
            PlaneLayout::Nv12 => &[
                (wgpu::TextureFormat::R8Unorm, (width, height)),
                (wgpu::TextureFormat::Rg8Unorm, chroma),
            ],
            PlaneLayout::I420 => &[
                (wgpu::TextureFormat::R8Unorm, (width, height)),
                (wgpu::TextureFormat::R8Unorm, chroma),
                (wgpu::TextureFormat::R8Unorm, chroma),
            ],
            PlaneLayout::I420P10 => &[
                (wgpu::TextureFormat::R16Unorm, (width, height)),
                (wgpu::TextureFormat::R16Unorm, chroma),
                (wgpu::TextureFormat::R16Unorm, chroma),
            ],
        };
        let textures: Vec<_> = planes
            .iter()
            .map(|&(format, (width, height))| {
                device.create_texture(&wgpu::TextureDescriptor {
                    label: Some("video_plane"),
                    size: wgpu::Extent3d {
                        width,
                        height,
                        depth_or_array_layers: 1,
                    },
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format,
                    usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                    view_formats: &[],
                })
            })
            .collect();
        let views = textures
            .iter()
            .map(|texture| texture.create_view(&wgpu::TextureViewDescriptor::default()))
            .collect();
        Self {
            layout,
            size: (width, height),
            textures,
            views,
            serial: None,
            drawn: false,
        }
    }

    fn upload(&mut self, queue: &wgpu::Queue, serial: u64, planes: &dyn VideoPlanes) {
        if self.serial == Some(serial) {
            return;
        }
        let texel = if self.layout == PlaneLayout::I420P10 { 2 } else { 1 };
        for (index, texture) in self.textures.iter().enumerate() {
            let (data, stride) = planes.plane(index);
            let (width, height) = (texture.width(), texture.height());
            let components = if self.layout == PlaneLayout::Nv12 && index == 1 { 2 } else { 1 };
            let row = (width * texel * components) as usize;
            if stride < row || data.len() < stride * (height as usize - 1) + row {
                log::error!(
                    "video plane {index} too small: {} bytes, stride {stride}, {width}x{height}",
                    data.len()
                );
                return;
            }
            queue.write_texture(
                texture.as_image_copy(),
                data,
                wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(stride as u32),
                    rows_per_image: None,
                },
                wgpu::Extent3d {
                    width,
                    height,
                    depth_or_array_layers: 1,
                },
            );
        }
        self.serial = Some(serial);
    }
}

#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
struct Acquired {
    access: Arc<dyn VulkanImageAccess>,
    state: VulkanImageState,
    views: (wgpu::TextureView, wgpu::TextureView),
    submitted: bool,
}

/// One draw's surfaces: a bind group per `Scene::surfaces` entry that can be drawn, and the
/// decoder images locked for it. Dropped unsubmitted, the images go back unchanged.
pub(crate) struct PreparedSurfaces {
    bind_groups: Vec<Option<wgpu::BindGroup>>,
    #[cfg(all(
        not(target_family = "wasm"),
        any(target_os = "linux", target_os = "freebsd")
    ))]
    acquired: Vec<Acquired>,
}

impl PreparedSurfaces {
    pub(crate) fn bind_group(&self, index: usize) -> Option<&wgpu::BindGroup> {
        self.bind_groups.get(index).and_then(Option::as_ref)
    }
}

#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
impl Drop for PreparedSurfaces {
    fn drop(&mut self) {
        for acquired in self.acquired.drain(..) {
            let layout = acquired
                .submitted
                .then_some(ash::vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL.as_raw());
            acquired.access.release(layout);
        }
    }
}

/// The renderer's video state: textures per CPU stream, kept while the stream is drawn.
#[derive(Default)]
pub(crate) struct VideoSurfaces {
    streams: FxHashMap<u64, StreamTextures>,
}

pub(crate) struct SurfaceContext<'a> {
    pub device: &'a Arc<wgpu::Device>,
    pub queue: &'a wgpu::Queue,
    pub layout: &'a wgpu::BindGroupLayout,
    pub sampler: &'a wgpu::Sampler,
    #[cfg(all(
        not(target_family = "wasm"),
        any(target_os = "linux", target_os = "freebsd")
    ))]
    pub share: Option<&'a crate::VulkanDeviceShare>,
}

impl VideoSurfaces {
    pub(crate) fn prepare(&mut self, context: &SurfaceContext<'_>, scene: &Scene) -> PreparedSurfaces {
        let mut prepared = PreparedSurfaces {
            bind_groups: Vec::with_capacity(scene.surfaces.len()),
            #[cfg(all(
                not(target_family = "wasm"),
                any(target_os = "linux", target_os = "freebsd")
            ))]
            acquired: Vec::new(),
        };
        for surface in &scene.surfaces {
            let bind_group = surface
                .frame
                .payload()
                .downcast_ref::<WgpuVideoFrame>()
                .and_then(|frame| {
                    self.bind_frame(context, &mut prepared, frame, &surface.bounds, &surface.content_mask.bounds)
                });
            prepared.bind_groups.push(bind_group);
        }
        self.streams.retain(|_, stream| std::mem::take(&mut stream.drawn));
        prepared
    }

    fn bind_frame(
        &mut self,
        context: &SurfaceContext<'_>,
        prepared: &mut PreparedSurfaces,
        frame: &WgpuVideoFrame,
        bounds: &Bounds<ScaledPixels>,
        content_mask: &Bounds<ScaledPixels>,
    ) -> Option<wgpu::BindGroup> {
        if frame.width == 0 || frame.height == 0 {
            return None;
        }
        let (views, sample_scale, uv_scale, planes): (Vec<wgpu::TextureView>, f32, [f32; 2], u32) =
            match &frame.source {
                VideoSource::Planes {
                    stream,
                    serial,
                    layout,
                    planes,
                } => {
                    if *layout == PlaneLayout::I420P10
                        && !context
                            .device
                            .features()
                            .contains(wgpu::Features::TEXTURE_FORMAT_16BIT_NORM)
                    {
                        return None;
                    }
                    let textures = self
                        .streams
                        .entry(*stream)
                        .and_modify(|textures| {
                            if textures.layout != *layout || textures.size != (frame.width, frame.height) {
                                *textures = StreamTextures::new(context.device, *layout, frame.width, frame.height);
                            }
                        })
                        .or_insert_with(|| StreamTextures::new(context.device, *layout, frame.width, frame.height));
                    textures.upload(context.queue, *serial, planes.as_ref());
                    textures.drawn = true;
                    let scale = if *layout == PlaneLayout::I420P10 { 65535.0 / 1023.0 } else { 1.0 };
                    let count = if *layout == PlaneLayout::Nv12 { 2 } else { 3 };
                    (textures.views.clone(), scale, [1.0, 1.0], count)
                }
                #[cfg(all(
                    not(target_family = "wasm"),
                    any(target_os = "linux", target_os = "freebsd")
                ))]
                VideoSource::Vulkan(image) => {
                    let (luma, chroma) = acquire_vulkan(context, prepared, image)?;
                    (
                        vec![luma, chroma],
                        1.0,
                        [
                            frame.width as f32 / image.coded_width as f32,
                            frame.height as f32 / image.coded_height as f32,
                        ],
                        2,
                    )
                }
            };
        let params = SurfaceParams {
            bounds: pod_bounds(bounds),
            content_mask: pod_bounds(content_mask),
            color: color_matrix(frame.color, sample_scale),
            uv_scale,
            planes,
            pad: 0,
        };
        let uniform = context.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("surface_params"),
            size: SURFACE_PARAMS_SIZE,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        context.queue.write_buffer(&uniform, 0, bytemuck::bytes_of(&params));
        let cr = views.get(2).unwrap_or(&views[1]);
        Some(context.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("surface_bind_group"),
            layout: context.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: &uniform,
                        offset: 0,
                        size: NonZeroU64::new(SURFACE_PARAMS_SIZE),
                    }),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&views[0]),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::TextureView(&views[1]),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: wgpu::BindingResource::Sampler(context.sampler),
                },
                wgpu::BindGroupEntry {
                    binding: 4,
                    resource: wgpu::BindingResource::TextureView(cr),
                },
            ],
        }))
    }
}

/// Locks `image` for this draw and wraps it as a texture (no copy), once per draw however many
/// surfaces show it. The texture holds `image.access` until wgpu has finished with it.
#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
fn acquire_vulkan(
    context: &SurfaceContext<'_>,
    prepared: &mut PreparedSurfaces,
    image: &VulkanVideoImage,
) -> Option<(wgpu::TextureView, wgpu::TextureView)> {
    use ash::vk::{self, Handle as _};
    use wgpu::hal::api::Vulkan;

    let share = context.share?;
    if image.generation != share.generation {
        return None;
    }
    let (format, luma, chroma) = match image.format {
        VulkanVideoFormat::Nv12 => (
            wgpu::TextureFormat::NV12,
            wgpu::TextureFormat::R8Unorm,
            wgpu::TextureFormat::Rg8Unorm,
        ),
        VulkanVideoFormat::P010 => (
            wgpu::TextureFormat::P010,
            wgpu::TextureFormat::R16Unorm,
            wgpu::TextureFormat::Rg16Unorm,
        ),
    };
    let features = context.device.features();
    if !features.contains(format.required_features())
        || (image.format == VulkanVideoFormat::P010
            && !features.contains(wgpu::Features::TEXTURE_FORMAT_16BIT_NORM))
    {
        return None;
    }
    if let Some(acquired) = prepared
        .acquired
        .iter()
        .find(|acquired| Arc::ptr_eq(&acquired.access, &image.access))
    {
        return Some(acquired.views.clone());
    }
    // SAFETY: wraps one image below; the guard ends before wgpu-core takes the texture.
    let hal_device = unsafe { context.device.as_hal::<Vulkan>() }?;
    let state = image.access.acquire();
    let size = wgpu::Extent3d {
        width: image.coded_width,
        height: image.coded_height,
        depth_or_array_layers: 1,
    };
    let keep = Arc::clone(&image.access);
    // SAFETY: the share's device decoded `state.image`, an NV12/P010 image of `size` made with
    // SAMPLED usage and MUTABLE_FORMAT; `keep` holds it (and its memory) until the drop callback.
    let texture = unsafe {
        let hal_texture = hal_device.texture_from_raw(
            vk::Image::from_raw(state.image),
            &wgpu::hal::TextureDescriptor {
                label: Some("video_frame"),
                size,
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format,
                usage: wgpu::TextureUses::RESOURCE,
                memory_flags: wgpu::hal::MemoryFlags::empty(),
                view_formats: Vec::new(),
            },
            Some(Box::new(move || drop(keep))),
            wgpu::hal::vulkan::TextureMemory::External,
        );
        drop(hal_device);
        // RESOURCE: the barrier `record_barriers` submits ahead of the draw leaves it there.
        context.device.create_texture_from_hal::<Vulkan>(
            hal_texture,
            &wgpu::TextureDescriptor {
                label: Some("video_frame"),
                size,
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format,
                usage: wgpu::TextureUsages::TEXTURE_BINDING,
                view_formats: &[],
            },
            wgpu::TextureUses::RESOURCE,
        )
    };
    let view = |format, aspect| {
        texture.create_view(&wgpu::TextureViewDescriptor {
            label: Some("video_plane"),
            format: Some(format),
            aspect,
            ..Default::default()
        })
    };
    let views = (
        view(luma, wgpu::TextureAspect::Plane0),
        view(chroma, wgpu::TextureAspect::Plane1),
    );
    prepared.acquired.push(Acquired {
        access: Arc::clone(&image.access),
        state,
        views: views.clone(),
        submitted: false,
    });
    Some(views)
}

#[cfg(all(
    not(target_family = "wasm"),
    any(target_os = "linux", target_os = "freebsd")
))]
impl PreparedSurfaces {
    /// The layout transitions of this draw's decoder images, submitted ahead of the draw: from
    /// wherever the decoder left each image to sampling.
    pub(crate) fn record_barriers(&self, device: &wgpu::Device) -> Option<wgpu::CommandBuffer> {
        use ash::vk::{self, Handle as _};
        use wgpu::hal::api::Vulkan;

        if self.acquired.is_empty() {
            return None;
        }
        // SAFETY: reads the device's function table; the device outlives this call.
        let raw = unsafe { device.as_hal::<Vulkan>() }?.raw_device().clone();
        let barriers: Vec<_> = self
            .acquired
            .iter()
            .map(|acquired| {
                vk::ImageMemoryBarrier::default()
                    .old_layout(vk::ImageLayout::from_raw(acquired.state.layout))
                    .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                    .src_access_mask(vk::AccessFlags::empty())
                    .dst_access_mask(vk::AccessFlags::SHADER_READ)
                    .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .image(vk::Image::from_raw(acquired.state.image))
                    .subresource_range(vk::ImageSubresourceRange {
                        aspect_mask: vk::ImageAspectFlags::COLOR,
                        base_mip_level: 0,
                        level_count: 1,
                        base_array_layer: 0,
                        layer_count: 1,
                    })
            })
            .collect();
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("video_barriers"),
        });
        // SAFETY: a raw-only encoder; the semaphore waits `before_submit` stages order the
        // transitions after the decoder's last use of each image (ALL_COMMANDS).
        let recorded = unsafe {
            encoder.as_hal_mut::<Vulkan, _, _>(|encoder| {
                let encoder = encoder?;
                raw.cmd_pipeline_barrier(
                    encoder.raw_handle(),
                    vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::PipelineStageFlags::FRAGMENT_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &[],
                    &barriers,
                );
                Some(())
            })
        };
        recorded.map(|()| encoder.finish())
    }

    /// Stages the decoder semaphores on the submission that follows: it waits for each image's
    /// acquired value and signals the next.
    pub(crate) fn before_submit(&mut self, queue: &wgpu::Queue) {
        use ash::vk::{self, Handle as _};
        use wgpu::hal::api::Vulkan;

        if self.acquired.is_empty() {
            return;
        }
        // SAFETY: stages semaphores on the hal queue; the caller submits next, under the queue lock.
        let Some(hal_queue) = (unsafe { queue.as_hal::<Vulkan>() }) else {
            return;
        };
        for acquired in &mut self.acquired {
            let semaphore = vk::Semaphore::from_raw(acquired.state.semaphore);
            hal_queue.add_wait_semaphore(
                semaphore,
                Some(acquired.state.wait_value),
                vk::PipelineStageFlags::ALL_COMMANDS,
            );
            hal_queue.add_signal_semaphore(semaphore, Some(acquired.state.wait_value + 1));
            acquired.submitted = true;
        }
    }
}

pub(crate) fn draw_surfaces(
    prepared: &PreparedSurfaces,
    range: Range<usize>,
    pipeline: &wgpu::RenderPipeline,
    globals: &wgpu::BindGroup,
    pass: &mut wgpu::RenderPass<'_>,
) {
    let mut bound = false;
    for index in range {
        let Some(bind_group) = prepared.bind_group(index) else {
            continue;
        };
        if !bound {
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, globals, &[]);
            bound = true;
        }
        pass.set_bind_group(1, bind_group, &[]);
        pass.draw(0..4, 0..1);
    }
}
