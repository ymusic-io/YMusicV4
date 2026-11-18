//! Local files: the in-process libav player (`file_av.c`).

use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::path::Path;

use crate::{Presenter, Tone};

unsafe extern "C" {
    fn file_av_create() -> *mut c_void;
    fn file_av_destroy(player: *mut c_void);
    fn file_av_open(player: *mut c_void, path: *const c_char) -> c_int;
    fn file_av_close(player: *mut c_void);
    fn file_av_play(player: *mut c_void);
    fn file_av_pause(player: *mut c_void);
    fn file_av_seek(player: *mut c_void, seconds: f64);
    fn file_av_step(player: *mut c_void) -> c_int;
    fn file_av_set_volume(player: *mut c_void, volume: f64);
    fn file_av_set_video_enabled(player: *mut c_void, on: c_int);
    fn file_av_set_subtitle_index(player: *mut c_void, stream_index: c_int);
    fn file_av_set_audio_index(player: *mut c_void, stream_index: c_int);
    fn file_av_set_sinks(
        player: *mut c_void,
        on_pcm: *const c_void,
        on_progress: *const c_void,
        on_event: *const c_void,
        on_caption: Option<CaptionFn>,
        user: *mut c_void,
    );
    fn file_av_dump_captions(player: *mut c_void, stream_index: c_int, on_cue: CaptionFn, user: *mut c_void) -> c_int;
    fn file_av_set_speed(player: *mut c_void, speed: f64);
    fn file_av_set_pitch(player: *mut c_void, pitch: f64);
    fn file_av_set_eq(player: *mut c_void, bands_db: *const f32);
    fn file_av_set_bass(player: *mut c_void, db: f32);
    fn file_av_set_virtualizer(player: *mut c_void, width: f32);
    fn file_av_set_preamp(player: *mut c_void, db: f32);
    fn file_av_pcm_levels(player: *mut c_void, out: *mut f32, count: c_int) -> c_int;
    fn file_av_frames(player: *mut c_void) -> c_int;
    fn file_av_bind_presenter(player: *mut c_void, presenter: *mut c_void);
    fn file_av_duration(player: *mut c_void) -> f64;
    fn file_av_position(player: *mut c_void) -> f64;
    fn file_av_ended(player: *mut c_void) -> c_int;
    fn file_av_last_error(player: *mut c_void) -> *const c_char;
    fn file_av_stream_count(player: *mut c_void) -> c_int;
    fn file_av_stream_at(
        player: *mut c_void,
        position: c_int,
        kind: *mut c_int,
        index: *mut c_int,
        width: *mut c_int,
        height: *mut c_int,
        lang: *mut c_char,
        lang_len: c_int,
        codec: *mut c_char,
        codec_len: c_int,
    ) -> c_int;
}

type CaptionFn = unsafe extern "C" fn(user: *mut c_void, id: c_int, start: f64, end: f64, text: *const c_char);

/// One timed caption, in seconds.
#[derive(Clone, Debug, PartialEq)]
pub struct Cue {
    pub id: i32,
    pub start: f64,
    pub end: f64,
    pub text: String,
}

/// `file_av_stream_at`'s kinds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StreamKind {
    Audio,
    Video,
    Text,
    Other,
}

/// One stream of the open file.
#[derive(Clone, Debug)]
pub struct AvStream {
    pub kind: StreamKind,
    /// The container's stream index, what `set_subtitle_index` and `dump_captions` take.
    pub index: i32,
    pub width: i32,
    pub height: i32,
    pub lang: String,
    pub codec: String,
}

/// Appends a cue to the `Vec<Cue>` behind `user`.
unsafe extern "C" fn push_cue(user: *mut c_void, id: c_int, start: f64, end: f64, text: *const c_char) {
    if user.is_null() || text.is_null() {
        return;
    }
    // SAFETY: `user` is the `Mutex<Vec<Cue>>` its registration passed, alive while the C side may call;
    // `text` is NUL-terminated for the call.
    let (cues, text) = unsafe { (&*(user as *const std::sync::Mutex<Vec<Cue>>), CStr::from_ptr(text)) };
    let text = text.to_string_lossy().trim().to_owned();
    if !text.is_empty() {
        cues.lock().unwrap().push(Cue { id, start, end, text });
    }
}

/// The in-process file player. Every call comes from one player thread, which also calls
/// [`LocalAv::step`] in a loop while playing; decode and audio output run on file_av's own threads.
pub struct LocalAv {
    /// A live C player from `file_av_create`, destroyed in `Drop`.
    raw: *mut c_void,
    /// Cues of the decoded subtitle stream, which `file_av_step` delivers on the calling thread. Boxed
    /// so its address, the C side's `user`, stays put; dropped after `raw` is destroyed.
    live_cues: Box<std::sync::Mutex<Vec<Cue>>>,
}

// SAFETY: file_av is not tied to a thread; one owner drives it at a time (not Sync), and the C side
// locks what its own decode and audio threads share.
unsafe impl Send for LocalAv {}

/// `file_av_stream_at`'s kind for a picture stream.
const STREAM_VIDEO: c_int = 1;

impl LocalAv {
    pub fn create() -> Option<Self> {
        // SAFETY: no arguments; a null result is handled.
        let raw = unsafe { file_av_create() };
        if raw.is_null() {
            return None;
        }
        let live_cues = Box::new(std::sync::Mutex::new(Vec::new()));
        let user = (&*live_cues as *const std::sync::Mutex<Vec<Cue>>).cast_mut().cast();
        let null = std::ptr::null();
        // SAFETY: `raw` is live; null sinks are skipped by the C side; `user` outlives `raw` (Drop order).
        unsafe { file_av_set_sinks(raw, null, null, null, Some(push_cue), user) };
        Some(Self { raw, live_cues })
    }

    /// The audio stream the next `open` plays; None (or a stream it lacks) plays the first.
    pub fn set_audio_index(&mut self, stream_index: Option<i32>) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_set_audio_index(self.raw, stream_index.unwrap_or(-1)) }
    }

    /// Decodes this text stream's cues as playback reaches them (`take_live_cues`); None turns it off.
    pub fn set_subtitle_index(&mut self, stream_index: Option<i32>) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_set_subtitle_index(self.raw, stream_index.unwrap_or(-1)) }
    }

    /// The cues the subtitle stream delivered since the last call.
    pub fn take_live_cues(&mut self) -> Vec<Cue> {
        std::mem::take(&mut *self.live_cues.lock().unwrap())
    }

    /// Blocking: every cue of this text stream, read from a second open of the file.
    pub fn dump_captions(&self, stream_index: i32) -> Vec<Cue> {
        let cues = std::sync::Mutex::new(Vec::new());
        let user = (&cues as *const std::sync::Mutex<Vec<Cue>>).cast_mut().cast();
        // SAFETY: `raw` is live; `cues` outlives the call, which delivers every cue before it returns.
        unsafe { file_av_dump_captions(self.raw, stream_index, push_cue, user) };
        cues.into_inner().unwrap()
    }

    /// The open file's streams.
    pub fn streams(&self) -> Vec<AvStream> {
        // SAFETY: `raw` is live until Drop.
        let count = unsafe { file_av_stream_count(self.raw) };
        (0..count)
            .filter_map(|position| {
                let (mut kind, mut index, mut width, mut height) = (-1, -1, 0, 0);
                let (mut lang, mut codec) = ([0 as c_char; 64], [0 as c_char; 64]);
                // SAFETY: `raw` is live; every out-pointer is valid and the buffers' lengths are passed.
                let found = unsafe {
                    file_av_stream_at(
                        self.raw,
                        position,
                        &mut kind,
                        &mut index,
                        &mut width,
                        &mut height,
                        lang.as_mut_ptr(),
                        lang.len() as c_int,
                        codec.as_mut_ptr(),
                        codec.len() as c_int,
                    )
                };
                (found == 0).then(|| AvStream {
                    kind: match kind {
                        0 => StreamKind::Audio,
                        STREAM_VIDEO => StreamKind::Video,
                        2 => StreamKind::Text,
                        _ => StreamKind::Other,
                    },
                    index,
                    width,
                    height,
                    lang: text(&lang).unwrap_or_default(),
                    codec: text(&codec).unwrap_or_default(),
                })
            })
            .collect()
    }

    pub fn open(&mut self, path: &Path) -> Result<(), String> {
        let path = CString::new(path.to_string_lossy().as_bytes()).map_err(|_| "path contains NUL".to_owned())?;
        // SAFETY: `raw` is live; `path` is NUL-terminated and outlives the call.
        if unsafe { file_av_open(self.raw, path.as_ptr()) } == 0 { Ok(()) } else { Err(self.last_error()) }
    }

    pub fn close(&mut self) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_close(self.raw) }
    }

    pub fn play(&mut self) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_play(self.raw) }
    }

    pub fn pause(&mut self) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_pause(self.raw) }
    }

    pub fn seek(&mut self, seconds: f64) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_seek(self.raw, seconds) }
    }

    /// One present tick; paces itself to the next due picture.
    pub fn step(&mut self) -> i32 {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_step(self.raw) }
    }

    pub fn set_volume(&mut self, volume: f64) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_set_volume(self.raw, volume.clamp(0.0, 1.0)) }
    }

    pub fn set_video_enabled(&mut self, on: bool) {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_set_video_enabled(self.raw, c_int::from(on)) }
    }

    pub fn set_speed_pitch(&mut self, speed: f64, pitch: f64) {
        // SAFETY: `raw` is live until Drop.
        unsafe {
            file_av_set_speed(self.raw, speed);
            file_av_set_pitch(self.raw, pitch);
        }
    }

    pub fn set_tone(&mut self, tone: &Tone) {
        // SAFETY: `raw` is live until Drop; `eq_db` is the 10 bands the C side reads.
        unsafe {
            file_av_set_eq(self.raw, tone.eq_db.as_ptr());
            file_av_set_bass(self.raw, tone.bass_db);
            file_av_set_virtualizer(self.raw, tone.virtualizer);
            file_av_set_preamp(self.raw, tone.preamp_db);
        }
    }

    /// Spectrum bars in 0..=1, bass first, for what is playing now.
    pub fn levels(&self, count: usize) -> Vec<f32> {
        let mut out = vec![0.0; count];
        // SAFETY: `raw` is live; `out` has room for the `count` floats C writes at most.
        let filled = unsafe { file_av_pcm_levels(self.raw, out.as_mut_ptr(), count as c_int) };
        out.truncate(filled.max(0) as usize);
        out
    }

    /// Pictures the presenter showed.
    pub fn frames(&self) -> i32 {
        // SAFETY: `raw` is live for as long as `self`.
        unsafe { file_av_frames(self.raw) }
    }

    pub fn bind_presenter(&mut self, presenter: Option<&Presenter>) {
        // SAFETY: both are live; the player takes its own presenter reference.
        unsafe { file_av_bind_presenter(self.raw, presenter.map_or(std::ptr::null_mut(), Presenter::as_raw)) }
    }

    pub fn duration(&self) -> f64 {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_duration(self.raw) }
    }

    pub fn position(&self) -> f64 {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_position(self.raw) }
    }

    pub fn ended(&self) -> bool {
        // SAFETY: `raw` is live until Drop.
        unsafe { file_av_ended(self.raw) != 0 }
    }

    /// The open file has a picture stream (attached cover art does not count).
    pub fn has_video(&self) -> bool {
        self.streams().iter().any(|stream| stream.kind == StreamKind::Video && stream.width.min(stream.height) > 0)
    }

    /// Why the last `open` failed.
    fn last_error(&self) -> String {
        // SAFETY: `raw` is live until Drop.
        let raw = unsafe { file_av_last_error(self.raw) };
        if raw.is_null() {
            return String::new();
        }
        // SAFETY: a NUL-terminated buffer inside the player, copied before any other call on it.
        unsafe { CStr::from_ptr(raw) }.to_string_lossy().into_owned()
    }
}

impl Drop for LocalAv {
    fn drop(&mut self) {
        // SAFETY: the last use of `raw`.
        unsafe { file_av_destroy(self.raw) }
    }
}

fn text(field: &[c_char]) -> Option<String> {
    let bytes: Vec<u8> = field.iter().take_while(|&&c| c != 0).map(|&c| c as u8).collect();
    let text = String::from_utf8_lossy(&bytes).trim().to_owned();
    (!text.is_empty()).then_some(text)
}
