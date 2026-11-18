//! kmpFfmpeg's `FfmpegMetadataGetter` over the packed ffmpeg binary, run as a child process so a
//! file that crashes libav cannot take the app down: metadata (`ffmpeg -i`, stderr parsed), the
//! attached cover and a representative video frame. Blocking: call from a worker.

mod info_parser;
pub mod metadata;
mod process;

use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::time::Duration;

pub use metadata::{ContainerMetadata, MediaFileMetadata, MetadataMap, StreamKind, StreamMetadata};

/// kmpFfmpeg's default probe cap.
pub const METADATA_TIMEOUT: Duration = Duration::from_secs(3);
/// An image run decodes up to the `thumbnail` filter's batch of frames before it writes.
pub const IMAGE_TIMEOUT: Duration = Duration::from_secs(15);
/// The `thumbnail` filter holds 100 frames and the decoder some per thread: on full-size frames a 4K
/// video's run takes 1.7 GB. Same argv as kmpFfmpeg's `extractThumbnail`.
const THUMBNAIL_INPUT_OPTIONS: [&str; 4] = ["-threads", "1", "-skip_frame", "nokey"];
const THUMBNAIL_FILTER: &str = "scale=w='min(1280,iw)':h='min(1280,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2:flags=fast_bilinear,thumbnail";

/// A failed ffmpeg run, with its site code.
#[derive(Debug, thiserror::Error)]
#[error("{code} {message}")]
pub struct FfmpegError {
    pub code: &'static str,
    pub message: String,
    /// The run was killed by SIGKILL or SIGTERM (the low-memory killer, or a teardown).
    killed: bool,
}

impl FfmpegError {
    pub(crate) fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self { code, message: message.into(), killed: false }
    }

    /// ffmpeg ran to its end and wrote no image: the file's own fault, so the same run soon fails the
    /// same way. Every other failure (no binary, spawn, timeout, I/O, byte cap, a killed run) is not.
    pub fn is_extraction_failure(&self) -> bool {
        matches!(self.code, "R40008" | "R40009") && !self.killed
    }
}

#[cfg(unix)]
fn killed(status: &std::process::ExitStatus) -> bool {
    use std::os::unix::process::ExitStatusExt;
    matches!(status.signal(), Some(9 | 15))
}

#[cfg(not(unix))]
fn killed(_status: &std::process::ExitStatus) -> bool {
    false
}

/// `YMUSIC_FFMPEG`, else beside the executable (packaged), else the dev build's.
pub fn find_binary() -> Option<PathBuf> {
    let candidates = [
        std::env::var_os("YMUSIC_FFMPEG").map(PathBuf::from),
        std::env::current_exe()
            .ok()
            .and_then(|exe| exe.parent().map(|dir| dir.join(format!("ffmpeg{}", std::env::consts::EXE_SUFFIX)))),
        option_env!("YM_FFMPEG_BIN").map(PathBuf::from),
    ];
    candidates.into_iter().flatten().find(|path| path.is_file())
}

/// ffmpeg's `file:` protocol takes the path literally, without percent-decoding.
fn file_spec(path: &Path) -> OsString {
    let mut spec = OsString::from("file:");
    spec.push(path.as_os_str());
    spec
}

fn args(items: &[&str], input: &Path, rest: &[&str]) -> Vec<OsString> {
    let mut args: Vec<OsString> = items.iter().map(OsString::from).collect();
    args.push("-i".into());
    args.push(file_spec(input));
    args.extend(rest.iter().map(OsString::from));
    args
}

pub struct FfmpegMetadataGetter {
    binary: Option<PathBuf>,
}

impl FfmpegMetadataGetter {
    /// Without a binary every call fails with its code.
    pub fn new(binary: Option<PathBuf>) -> Self {
        Self { binary }
    }

    /// The process-wide getter over [`find_binary`], looked up once.
    pub fn shared() -> &'static Self {
        static SHARED: OnceLock<FfmpegMetadataGetter> = OnceLock::new();
        SHARED.get_or_init(|| Self::new(find_binary()))
    }

    fn binary(&self) -> Result<&Path, FfmpegError> {
        self.binary.as_deref().ok_or_else(|| {
            FfmpegError::new("R40001", "no ffmpeg binary (YMUSIC_FFMPEG, beside the executable or the dev build)")
        })
    }

    /// `ffmpeg -hide_banner -i <path>`, killed after [`METADATA_TIMEOUT`]. ffmpeg always exits
    /// non-zero without an output file, so success is a dump with an `Input #` block.
    pub fn get_metadata(&self, path: &Path) -> Result<MediaFileMetadata, FfmpegError> {
        let finished = process::run(self.binary()?, &args(&["-hide_banner"], path, &[]), None, METADATA_TIMEOUT)?;
        info_parser::parse(&String::from_utf8_lossy(&finished.stderr))
    }

    /// The first attached picture's bytes as stored (`-map 0:disp:attached_pic -c copy`). Whole in
    /// memory, at most `max_bytes`, so the run is bounded by size and [`IMAGE_TIMEOUT`].
    pub fn get_attached_pic(&self, path: &Path, max_bytes: usize) -> Result<Vec<u8>, FfmpegError> {
        let rest =
            ["-map", "0:disp:attached_pic", "-c", "copy", "-frames:v", "1", "-update", "1", "-f", "image2", "pipe:1"];
        let finished = self.single_image(path, &[], &rest, max_bytes)?;
        if finished.stdout.is_empty() {
            return Err(FfmpegError {
                killed: killed(&finished.status),
                ..FfmpegError::new(
                    "R40008",
                    format!("ffmpeg produced no attached picture ({})\n{}", finished.status, stderr_text(&finished)),
                )
            });
        }
        Ok(finished.stdout)
    }

    /// A representative key frame (ffmpeg's `thumbnail` filter, past black lead-ins) as a JPEG
    /// within 640x640; bounded as [`Self::get_attached_pic`].
    pub fn extract_thumbnail(&self, path: &Path, max_bytes: usize) -> Result<Vec<u8>, FfmpegError> {
        let rest = [
            "-vf",
            THUMBNAIL_FILTER,
            "-frames:v",
            "1",
            "-an",
            "-c:v",
            "mjpeg",
            "-update",
            "1",
            "-f",
            "image2",
            "pipe:1",
        ];
        let finished = self.single_image(path, &THUMBNAIL_INPUT_OPTIONS, &rest, max_bytes)?;
        if finished.stdout.is_empty() {
            return Err(FfmpegError {
                killed: killed(&finished.status),
                ..FfmpegError::new(
                    "R40009",
                    format!("ffmpeg produced no thumbnail frame ({})\n{}", finished.status, stderr_text(&finished)),
                )
            });
        }
        Ok(finished.stdout)
    }

    fn single_image(
        &self,
        path: &Path,
        input_options: &[&str],
        rest: &[&str],
        max_bytes: usize,
    ) -> Result<process::Finished, FfmpegError> {
        let args = args(&[&["-hide_banner", "-loglevel", "error"], input_options].concat(), path, rest);
        process::run(self.binary()?, &args, Some(max_bytes), IMAGE_TIMEOUT)
    }
}

fn stderr_text(finished: &process::Finished) -> String {
    String::from_utf8_lossy(&finished.stderr).trim().to_owned()
}
