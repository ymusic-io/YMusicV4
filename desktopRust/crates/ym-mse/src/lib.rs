//! YMusicV4's C Media Source host for the player bundle's `__mse*` natives: demux, decode, the
//! A/V clock and audio output all live in C; this crate owns one host and its process hooks.

#[cfg(target_os = "linux")]
mod frames;
mod local;

#[cfg(target_os = "linux")]
pub use frames::{
    Matrix, Picture, PictureKind, PlaneLayout, QueueFamily, QueueLock, VulkanDevice, VulkanFormat, VulkanImageState,
    decode_on_vulkan,
};
pub use local::{Frame, LocalAv, Probe, attached_picture, probe, video_frame};

/// EQ bands' centre frequencies, Hz.
pub const EQ_BANDS_HZ: [u32; 10] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

/// The in-app tone shaping both engines apply (`audio_fx.c`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Tone {
    /// Per band, dB.
    pub eq_db: [f32; 10],
    pub bass_db: f32,
    /// Stereo width; 1 is unchanged.
    pub virtualizer: f32,
    /// Level trim on top of the automatic loudness pre-gain, -12..=12 dB.
    pub preamp_db: f32,
}

impl Default for Tone {
    fn default() -> Self {
        Self { eq_db: [0.0; 10], bass_db: 0.0, virtualizer: 1.0, preamp_db: 0.0 }
    }
}

use std::ffi::{CStr, c_char, c_int, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, PoisonError, RwLock};

unsafe extern "C" {
    fn mse_host_create() -> *mut c_void;
    fn mse_host_destroy(host: *mut c_void);
    fn mse_host_vtable() -> *const c_void;
    fn mse_host_set_js_event(callback: Option<unsafe extern "C" fn(name: *const c_char)>);
    fn mse_set_log_callback(callback: Option<unsafe extern "C" fn(level: c_int, message: *const c_char)>);
    fn mse_host_set_volume(host: *mut c_void, volume: f32);
    fn mse_host_set_speed(host: *mut c_void, speed: f32, tempo: f32);
    fn mse_host_set_eq(host: *mut c_void, bands_db: *const f32);
    fn mse_host_set_bass(host: *mut c_void, db: f32);
    fn mse_host_set_virtualizer(host: *mut c_void, width: f32);
    fn mse_host_set_preamp(host: *mut c_void, db: f32);
    fn mse_host_set_video_hidden(host: *mut c_void, hidden: c_int);
    fn mse_host_pcm_levels(host: *mut c_void, out: *mut f32, count: c_int) -> c_int;
    fn mse_host_samples_played(host: *mut c_void) -> i64;
    fn mse_host_video_quality(host: *mut c_void, total: *mut c_int, dropped: *mut c_int);
    fn mse_host_video_decoder(host: *mut c_void, out: *mut c_char, cap: c_int);
    fn mse_host_bind_presenter(host: *mut c_void, presenter: *mut c_void);
    #[cfg(target_os = "macos")]
    fn vpresent_create_vt_layer(layer: *mut c_void, sink: *const VpSink) -> *mut c_void;
    fn vpresent_create_cpu(sink: *const VpSink) -> *mut c_void;
    #[cfg(target_os = "windows")]
    fn vpresent_create_d3d11_composition(sink: *const VpSink, swap_chain: *mut *mut c_void) -> *mut c_void;
    #[cfg(target_os = "windows")]
    fn vpresent_d3d11_swapchain_release(swap_chain: *mut c_void);
    fn vpresent_set_size(presenter: *mut c_void, width: c_int, height: c_int);
    fn vpresent_release(presenter: *mut c_void);
    fn vpresent_mode(presenter: *mut c_void) -> c_int;
}

#[repr(C)]
struct VpSink {
    on_frame: Option<unsafe extern "C" fn(user: *mut c_void, width: c_int, height: c_int, bgra: *const u8)>,
    on_mode: Option<unsafe extern "C" fn(user: *mut c_void, mode: c_int)>,
    release: Option<unsafe extern "C" fn(user: *mut c_void)>,
    user: *mut c_void,
    on_size: Option<unsafe extern "C" fn(user: *mut c_void, width: c_int, height: c_int)>,
    on_swapchain: Option<unsafe extern "C" fn(user: *mut c_void, swap_chain: *mut c_void)>,
}

/// Receives each CPU-mode picture as tightly packed BGRA rows on a decode thread; the pixels are
/// only valid during the call.
pub type FrameSink = Box<dyn Fn(u32, u32, &[u8]) + Send + Sync>;

/// What a composition presenter tells its owner, on a decode thread.
#[cfg(target_os = "windows")]
pub struct CompositionSink {
    /// The content's size (width, height), which changes only with the picture's aspect.
    pub size: Box<dyn Fn(u32, u32) + Send + Sync>,
    /// The swap chain that replaces the one in use after a D3D11 device loss.
    pub swap_chain: Box<dyn Fn(SwapChain) + Send + Sync>,
}

/// Video output: an `AVSampleBufferDisplayLayer` (zero-copy VideoToolbox frames on macOS),
/// pictures as decoded for the app's renderer (Linux), or BGRA pictures handed to a [`FrameSink`].
pub struct Presenter {
    /// One reference to a live C presenter, released in `Drop`.
    raw: *mut c_void,
}

// SAFETY: the C presenter counts its references atomically and locks its own state (present_mu),
// so any thread may call or release it.
unsafe impl Send for Presenter {}
// SAFETY: as for Send; every method takes the C side's own locks.
unsafe impl Sync for Presenter {}

impl Presenter {
    /// # Safety
    /// `layer` must be a live `AVSampleBufferDisplayLayer` that outlives the presenter.
    #[cfg(target_os = "macos")]
    pub unsafe fn on_layer(layer: *mut c_void) -> Option<Self> {
        let user = std::ptr::null_mut();
        let sink = VpSink { on_frame: None, on_mode: None, release: None, user, on_size: None, on_swapchain: None };
        // SAFETY: the caller vouches for `layer`; the C side copies the sink.
        let raw = unsafe { vpresent_create_vt_layer(layer, &sink) };
        (!raw.is_null()).then_some(Self { raw })
    }

    /// Converts every picture to BGRA, fitted into [`Presenter::set_size`], for `sink`.
    pub fn cpu(sink: FrameSink) -> Option<Self> {
        let user = Box::into_raw(Box::new(sink)).cast::<c_void>();
        let sink = VpSink {
            on_frame: Some(on_cpu_frame),
            on_mode: None,
            release: Some(release_cpu_sink),
            user,
            on_size: None,
            on_swapchain: None,
        };
        // SAFETY: the C side copies the sink and owns `user` from here, handing it back through
        // `release` as its last call.
        let raw = unsafe { vpresent_create_cpu(&sink) };
        if raw.is_null() {
            // SAFETY: a failed create never calls `release`, so `user` is still this box.
            unsafe { release_cpu_sink(user) };
            return None;
        }
        Some(Self { raw })
    }

    /// Draws into a composition swap chain for a DirectComposition visual to show, D3D11 frames
    /// without a copy. After a device loss it moves to a device of its own (software pictures)
    /// and hands `sink` the new swap chain.
    #[cfg(target_os = "windows")]
    pub fn d3d11_composition(sink: CompositionSink) -> Option<(Self, SwapChain)> {
        let user = Box::into_raw(Box::new(sink)).cast::<c_void>();
        let sink = VpSink {
            on_frame: None,
            on_mode: None,
            release: Some(release_composition_sink),
            user,
            on_size: Some(on_content_size),
            on_swapchain: Some(on_swap_chain),
        };
        let mut swap_chain = std::ptr::null_mut();
        // SAFETY: the C side copies the sink and owns `user` from here, handing it back through
        // `release` as its last call; `swap_chain` is a valid out-pointer.
        let raw = unsafe { vpresent_create_d3d11_composition(&sink, &mut swap_chain) };
        if raw.is_null() {
            // SAFETY: a failed create never calls `release`, so `user` is still this box.
            unsafe { release_composition_sink(user) };
            return None;
        }
        Some((Self { raw }, SwapChain(swap_chain)))
    }

    /// The CPU fit box in pixels: min-fit, never upscaled; 0 on a side leaves it unconstrained.
    pub fn set_size(&self, width: u32, height: u32) {
        // SAFETY: `raw` is live until Drop.
        unsafe { vpresent_set_size(self.raw, width as c_int, height as c_int) }
    }

    fn as_raw(&self) -> *mut c_void {
        self.raw
    }

    /// 1 = GPU (the layer shows frames), 2 = CPU, 0 = detached.
    pub fn mode(&self) -> i32 {
        // SAFETY: `raw` is live until Drop.
        unsafe { vpresent_mode(self.raw) }
    }
}

/// One reference to a composition presenter's `IDXGISwapChain1`.
#[cfg(target_os = "windows")]
pub struct SwapChain(*mut c_void);

// SAFETY: a COM reference; IDXGISwapChain1 counts references atomically, so any thread may drop it.
#[cfg(target_os = "windows")]
unsafe impl Send for SwapChain {}

#[cfg(target_os = "windows")]
impl SwapChain {
    /// The `IDXGISwapChain1`, live while this reference is.
    pub fn as_ptr(&self) -> *mut c_void {
        self.0
    }
}

#[cfg(target_os = "windows")]
impl Drop for SwapChain {
    fn drop(&mut self) {
        // SAFETY: gives back the one reference the presenter handed over.
        unsafe { vpresent_d3d11_swapchain_release(self.0) }
    }
}

impl Drop for Presenter {
    fn drop(&mut self) {
        // SAFETY: gives back this presenter's one reference; hosts bound to it hold their own.
        unsafe { vpresent_release(self.raw) }
    }
}

/// Runs Rust code on a C caller's stack, where a panic must not unwind: the panic hook has
/// already reported it, and only this call's work is lost.
fn contain_panic(callback: impl FnOnce()) {
    let _ = catch_unwind(AssertUnwindSafe(callback));
}

unsafe extern "C" fn on_cpu_frame(user: *mut c_void, width: c_int, height: c_int, bgra: *const u8) {
    if bgra.is_null() || width <= 0 || height <= 0 {
        return;
    }
    // SAFETY: `user` is the box `Presenter::cpu` handed over, freed only by the later `release`.
    let sink = unsafe { &*user.cast::<FrameSink>() };
    // SAFETY: CPU mode passes tightly packed BGRA rows that stay valid through this call.
    let pixels = unsafe { std::slice::from_raw_parts(bgra, width as usize * height as usize * 4) };
    contain_panic(|| sink(width as u32, height as u32, pixels));
}

unsafe extern "C" fn release_cpu_sink(user: *mut c_void) {
    // SAFETY: the sink's last call (or a failed create's cleanup): `user` is the box from
    // `Presenter::cpu`, reclaimed exactly once.
    let sink = unsafe { Box::from_raw(user.cast::<FrameSink>()) };
    contain_panic(|| drop(sink));
}

#[cfg(target_os = "windows")]
unsafe extern "C" fn on_content_size(user: *mut c_void, width: c_int, height: c_int) {
    if width <= 0 || height <= 0 {
        return;
    }
    // SAFETY: `user` is the box `Presenter::d3d11_composition` handed over, freed only by the
    // later `release`.
    let sink = unsafe { &*user.cast::<CompositionSink>() };
    contain_panic(|| (sink.size)(width as u32, height as u32));
}

#[cfg(target_os = "windows")]
unsafe extern "C" fn on_swap_chain(user: *mut c_void, swap_chain: *mut c_void) {
    if swap_chain.is_null() {
        return;
    }
    let swap_chain = SwapChain(swap_chain);
    // SAFETY: `user` is the box `Presenter::d3d11_composition` handed over, freed only by the
    // later `release`.
    let sink = unsafe { &*user.cast::<CompositionSink>() };
    contain_panic(|| (sink.swap_chain)(swap_chain));
}

#[cfg(target_os = "windows")]
unsafe extern "C" fn release_composition_sink(user: *mut c_void) {
    // SAFETY: the sink's last call (or a failed create's cleanup): `user` is the box from
    // `Presenter::d3d11_composition`, reclaimed exactly once.
    let sink = unsafe { Box::from_raw(user.cast::<CompositionSink>()) };
    contain_panic(|| drop(sink));
}

/// One native MSE host. Attach it to the player's QuickJS runtime before the bundle evaluates,
/// handing the runtime a reference to hold: JS calls straight into it.
pub struct MseHost {
    /// A live C host from `mse_host_create`, destroyed in `Drop`.
    raw: *mut c_void,
}

// SAFETY: the C host serializes its own state across the QuickJS, decode and audio threads.
unsafe impl Send for MseHost {}
// SAFETY: as for Send.
unsafe impl Sync for MseHost {}

impl MseHost {
    pub fn create() -> Option<Self> {
        // SAFETY: no arguments; a null result is handled.
        let raw = unsafe { mse_host_create() };
        (!raw.is_null()).then_some(Self { raw })
    }

    /// The host pointer and `__mse*` vtable to hand to `QuickJsRuntime::attach_mse_host`.
    pub fn attach_pointers(&self) -> (usize, usize) {
        // SAFETY: returns a pointer to a static table.
        (self.raw as usize, unsafe { mse_host_vtable() } as usize)
    }

    /// App volume times the bundle's loudness gain, 0..=1.
    pub fn set_volume(&self, volume: f32) {
        // SAFETY: `raw` is live until Drop.
        unsafe { mse_host_set_volume(self.raw, volume.clamp(0.0, 1.0)) }
    }

    pub fn set_speed(&self, speed: f32, tempo: f32) {
        // SAFETY: `raw` is live until Drop.
        unsafe { mse_host_set_speed(self.raw, speed, tempo) }
    }

    pub fn set_tone(&self, tone: &Tone) {
        // SAFETY: `raw` is live until Drop; `eq_db` is the 10 bands the C side reads.
        unsafe {
            mse_host_set_eq(self.raw, tone.eq_db.as_ptr());
            mse_host_set_bass(self.raw, tone.bass_db);
            mse_host_set_virtualizer(self.raw, tone.virtualizer);
            mse_host_set_preamp(self.raw, tone.preamp_db);
        }
    }

    /// No view shows the picture: rests the video decoder and keeps buffering the chosen quality.
    /// Can wait on the decoder stopping, so call it off the UI thread.
    pub fn set_video_hidden(&self, hidden: bool) {
        // SAFETY: `raw` is live until Drop.
        unsafe { mse_host_set_video_hidden(self.raw, c_int::from(hidden)) }
    }

    /// Spectrum bars in 0..=1, bass first, for what is playing now.
    pub fn levels(&self, count: usize) -> Vec<f32> {
        let mut out = vec![0.0; count];
        // SAFETY: `raw` is live; `out` has room for the `count` floats C writes at most.
        let filled = unsafe { mse_host_pcm_levels(self.raw, out.as_mut_ptr(), count as c_int) };
        out.truncate(filled.max(0) as usize);
        out
    }

    /// Sends this host's pictures to `presenter` (`None` unbinds). The host keeps its own reference.
    pub fn bind_presenter(&self, presenter: Option<&Presenter>) {
        // SAFETY: both are live; the host takes its own presenter reference.
        unsafe { mse_host_bind_presenter(self.raw, presenter.map_or(std::ptr::null_mut(), |presenter| presenter.raw)) }
    }

    pub fn samples_played(&self) -> i64 {
        // SAFETY: `raw` is live until Drop.
        unsafe { mse_host_samples_played(self.raw) }
    }

    /// Pictures shown and pictures dropped for being late, since the last load. Takes the decoder
    /// lock, so it can block briefly.
    pub fn video_quality(&self) -> (i32, i32) {
        let (mut total, mut dropped) = (0, 0);
        // SAFETY: `raw` is live; both out-pointers are valid for the call.
        unsafe { mse_host_video_quality(self.raw, &mut total, &mut dropped) };
        (total, dropped)
    }

    /// The open video decoder's kind and codec (`hw h264`, `sw vp9`); `None` with none open or
    /// while the host is busy. Never blocks.
    pub fn video_decoder(&self) -> Option<String> {
        let mut out = [0u8; 48];
        // SAFETY: `raw` is live; the host writes a NUL-terminated string of at most `cap` bytes.
        unsafe { mse_host_video_decoder(self.raw, out.as_mut_ptr().cast(), out.len() as c_int) };
        let decoder = CStr::from_bytes_until_nul(&out).ok()?.to_string_lossy();
        (!decoder.is_empty()).then(|| decoder.into_owned())
    }
}

impl Drop for MseHost {
    fn drop(&mut self) {
        // SAFETY: the last use of `raw`; a runtime it is attached to holds a reference until its
        // C engine is closed (`attach_mse_host`).
        unsafe { mse_host_destroy(self.raw) }
    }
}

type EventSink = Arc<dyn Fn(&str) + Send + Sync>;
type LogSink = Arc<dyn Fn(i32, &str) + Send + Sync>;

static EVENT_SINK: RwLock<Option<EventSink>> = RwLock::new(None);
static LOG_SINK: RwLock<Option<LogSink>> = RwLock::new(None);

/// A clone of the current sink, so it runs without the lock held.
fn current<T: ?Sized>(sink: &RwLock<Option<Arc<T>>>) -> Option<Arc<T>> {
    sink.read().unwrap_or_else(PoisonError::into_inner).clone()
}

unsafe extern "C" fn on_event(name: *const c_char) {
    if name.is_null() {
        return;
    }
    // SAFETY: C passes a NUL-terminated name that stays valid through this call.
    let name = unsafe { CStr::from_ptr(name) }.to_string_lossy();
    if let Some(sink) = current(&EVENT_SINK) {
        contain_panic(|| sink(&name));
    }
}

unsafe extern "C" fn on_log(level: c_int, message: *const c_char) {
    if message.is_null() {
        return;
    }
    // SAFETY: C passes a NUL-terminated line that stays valid through this call.
    let message = unsafe { CStr::from_ptr(message) }.to_string_lossy();
    if let Some(sink) = current(&LOG_SINK) {
        contain_panic(|| sink(level, &message));
    }
}

/// Process-wide: where C's host→JS events (`mse_ended`) go. The last setter wins.
pub fn set_event_sink(sink: impl Fn(&str) + Send + Sync + 'static) {
    *EVENT_SINK.write().unwrap_or_else(PoisonError::into_inner) = Some(Arc::new(sink));
    // SAFETY: `on_event` has the callback's C signature and lives for the process.
    unsafe { mse_host_set_js_event(Some(on_event)) };
}

/// Process-wide: C's notable lines, level 0 debug … 3 error. The last setter wins.
pub fn set_log_sink(sink: impl Fn(i32, &str) + Send + Sync + 'static) {
    *LOG_SINK.write().unwrap_or_else(PoisonError::into_inner) = Some(Arc::new(sink));
    // SAFETY: `on_log` has the callback's C signature and lives for the process.
    unsafe { mse_set_log_callback(Some(on_log)) };
}
