//! The ffmpeg operations a download needs, as argv for the packed ffmpeg binary. The container is
//! named with `-f`, since scratch names carry no extension; paths go as `file:` specs, which ffmpeg
//! does not percent-decode.

use std::path::{Path, PathBuf};
use std::process::Stdio;

use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};
use tokio::process::Command;

use crate::error::DownloadError;
use crate::model::AudioQuality;

/// One subtitle track to mux: its file, ISO-639-1 language and display title.
pub struct SubtitleInput {
    pub path: PathBuf,
    pub language: String,
    pub title: Option<String>,
}

pub type Metadata = Vec<(String, String)>;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub struct Ffmpeg {
    binary: PathBuf,
}

impl Ffmpeg {
    pub fn new(binary: PathBuf) -> Self {
        Self { binary }
    }

    /// Runs with `-hide_banner -loglevel error -y` prepended; with `on_progress`, each tick's output
    /// time (µs) is reported. Dropping the future kills ffmpeg.
    pub async fn run(
        &self,
        args: Vec<String>,
        mut on_progress: Option<&mut (dyn FnMut(i64) + Send)>,
    ) -> Result<(), DownloadError> {
        let mut command = Command::new(&self.binary);
        command.args(["-hide_banner", "-loglevel", "error", "-y"]);
        if on_progress.is_some() {
            command.args(["-progress", "pipe:1", "-nostats"]);
        }
        command
            .args(&args)
            .env_clear()
            .stdin(Stdio::null())
            .stdout(if on_progress.is_some() { Stdio::piped() } else { Stdio::null() })
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        // A console child of a GUI process would open a console window of its own.
        #[cfg(windows)]
        command.creation_flags(CREATE_NO_WINDOW);
        let mut child = command.spawn().map_err(|error| {
            DownloadError::technical("R70013", format!("ffmpeg {}: {error}", self.binary.display()))
        })?;
        let mut stderr = child.stderr.take().expect("piped stderr");
        let stderr_text = async move {
            let mut text = String::new();
            stderr.read_to_string(&mut text).await.map(|_| text)
        };
        let stdout = child.stdout.take();
        let progress = async {
            let (Some(stdout), Some(callback)) = (stdout, on_progress.as_mut()) else { return Ok(()) };
            let mut lines = BufReader::new(stdout).lines();
            while let Some(line) = lines.next_line().await? {
                if let Some(value) = line.strip_prefix("out_time_us=").and_then(|value| value.trim().parse().ok()) {
                    callback(value);
                }
            }
            Ok::<(), std::io::Error>(())
        };
        let (status, stderr_text, progress) = tokio::join!(child.wait(), stderr_text, progress);
        let status = status.map_err(|error| DownloadError::technical("R70014", format!("ffmpeg wait: {error}")))?;
        let log = match (stderr_text, progress) {
            (Ok(text), Ok(())) => text,
            (Ok(text), Err(error)) => format!("{text}\nprogress reader failed: {error}"),
            (Err(error), _) => format!("stderr reader failed: {error}"),
        };
        if status.success() {
            return Ok(());
        }
        let detail = format!("ffmpeg exited {status}:\n{log}");
        if log.contains("No space left on device") {
            return Err(DownloadError::DiskFull(std::io::Error::new(std::io::ErrorKind::StorageFull, detail)));
        }
        Err(DownloadError::technical("R70015", detail))
    }
}

fn spec(path: &Path) -> String {
    format!("file:{}", path.display())
}

fn push(args: &mut Vec<String>, items: &[&str]) {
    args.extend(items.iter().map(|item| (*item).to_owned()));
}

fn metadata_args(args: &mut Vec<String>, metadata: &Metadata) {
    for (key, value) in metadata.iter().filter(|(_, value)| !value.trim().is_empty()) {
        args.push("-metadata".to_owned());
        args.push(format!("{key}={value}"));
    }
}

fn subtitle_metadata_args(args: &mut Vec<String>, captions: &[SubtitleInput]) {
    for (index, caption) in captions.iter().enumerate() {
        args.push(format!("-metadata:s:s:{index}"));
        args.push(format!("language={}", iso639_2(&caption.language)));
        if let Some(title) = caption.title.as_deref().filter(|title| !title.trim().is_empty()) {
            args.push(format!("-metadata:s:s:{index}"));
            args.push(format!("title={title}"));
        }
    }
}

/// The cover is an extension-less scratch: name its demuxer and codec instead of probing.
fn cover_input_args(args: &mut Vec<String>, cover: &Path) {
    push(args, &["-f", "image2", "-c:v", "mjpeg", "-i"]);
    args.push(spec(cover));
}

fn cover_output_args(args: &mut Vec<String>, input_index: usize) {
    args.push("-map".to_owned());
    args.push(format!("{input_index}:v:0"));
    push(
        args,
        &[
            "-c:v",
            "copy",
            "-disposition:v:0",
            "attached_pic",
            "-metadata:s:v:0",
            "title=Album cover",
            "-metadata:s:v:0",
            "comment=Cover (Front)",
        ],
    );
}

/// Any audio → MP3 via libmp3lame, tagged ID3v2.3 (the version lyrics insertion extends).
pub fn mp3_args(
    audio: &Path,
    output: &Path,
    quality: AudioQuality,
    cover: Option<&Path>,
    metadata: &Metadata,
) -> Vec<String> {
    let mut args = vec!["-i".to_owned(), spec(audio)];
    if let Some(cover) = cover {
        cover_input_args(&mut args, cover);
    }
    push(&mut args, &["-map", "0:a:0"]);
    if cover.is_some() {
        cover_output_args(&mut args, 1);
    }
    push(&mut args, &["-c:a", "libmp3lame"]);
    match quality {
        AudioQuality::Save => push(&mut args, &["-abr", "1", "-b:a", "128k"]),
        AudioQuality::Medium => push(&mut args, &["-q:a", "0"]),
        AudioQuality::Best => push(&mut args, &["-abr", "1", "-b:a", "320k"]),
    }
    metadata_args(&mut args, metadata);
    push(&mut args, &["-id3v2_version", "3", "-f", "mp3"]);
    args.push(spec(output));
    args
}

/// AAC → m4a (the `ipod` muxer), stream-copied, captions as mov_text.
pub fn m4a_args(
    audio: &Path,
    output: &Path,
    captions: &[SubtitleInput],
    cover: Option<&Path>,
    metadata: &Metadata,
) -> Vec<String> {
    let mut args = vec!["-i".to_owned(), spec(audio)];
    for caption in captions {
        args.push("-i".to_owned());
        args.push(spec(&caption.path));
    }
    if let Some(cover) = cover {
        cover_input_args(&mut args, cover);
    }
    push(&mut args, &["-map", "0:a:0"]);
    for index in 0..captions.len() {
        args.push("-map".to_owned());
        args.push(format!("{}:0", index + 1));
    }
    if cover.is_some() {
        cover_output_args(&mut args, captions.len() + 1);
    }
    push(&mut args, &["-c:a", "copy"]);
    if !captions.is_empty() {
        push(&mut args, &["-c:s", "mov_text"]);
    }
    subtitle_metadata_args(&mut args, captions);
    metadata_args(&mut args, metadata);
    push(&mut args, &["-f", "ipod"]);
    args.push(spec(output));
    args
}

/// Opus → Ogg, stream-copied. Cover and lyrics are added afterward.
pub fn opus_args(audio: &Path, output: &Path, metadata: &Metadata) -> Vec<String> {
    let mut args = vec!["-i".to_owned(), spec(audio)];
    push(&mut args, &["-map", "0:a:0", "-c:a", "copy"]);
    metadata_args(&mut args, metadata);
    push(&mut args, &["-f", "opus"]);
    args.push(spec(output));
    args
}

/// Video + audio stream-copied into MP4 (captions as mov_text) or WebM (captions as WebVTT).
pub fn mux_args(
    video: &Path,
    audio: &Path,
    output: &Path,
    captions: &[SubtitleInput],
    metadata: &Metadata,
    webm: bool,
) -> Vec<String> {
    let mut args = vec!["-i".to_owned(), spec(video), "-i".to_owned(), spec(audio)];
    for caption in captions {
        args.push("-i".to_owned());
        args.push(spec(&caption.path));
    }
    push(&mut args, &["-map", "0:v:0", "-map", "1:a:0"]);
    for index in 0..captions.len() {
        args.push("-map".to_owned());
        args.push(format!("{}:0", index + 2));
    }
    push(&mut args, &["-c:v", "copy", "-c:a", "copy"]);
    if !captions.is_empty() {
        push(&mut args, &["-c:s", if webm { "webvtt" } else { "mov_text" }]);
    }
    subtitle_metadata_args(&mut args, captions);
    if !webm {
        push(&mut args, &["-movflags", "+faststart"]);
    }
    metadata_args(&mut args, metadata);
    push(&mut args, &["-f", if webm { "webm" } else { "mp4" }]);
    args.push(spec(output));
    args
}

/// YouTube's 2-letter code → the 3-letter one container language atoms expect; regional variants
/// use the base language (`pt-BR` → `por`), unknowns pass through.
pub fn iso639_2(code: &str) -> String {
    let base = code.split('-').next().unwrap_or(code).to_ascii_lowercase();
    let mapped = match base.as_str() {
        "en" => "eng",
        "es" => "spa",
        "pt" => "por",
        "fr" => "fre",
        "de" => "ger",
        "it" => "ita",
        "nl" => "dut",
        "ru" => "rus",
        "uk" => "ukr",
        "pl" => "pol",
        "cs" => "cze",
        "ro" => "rum",
        "el" => "gre",
        "tr" => "tur",
        "ar" => "ara",
        "he" => "heb",
        "fa" => "per",
        "hi" => "hin",
        "bn" => "ben",
        "ta" => "tam",
        "te" => "tel",
        "ur" => "urd",
        "th" => "tha",
        "vi" => "vie",
        "id" => "ind",
        "ms" => "may",
        "ja" => "jpn",
        "ko" => "kor",
        "zh" => "chi",
        "sv" => "swe",
        "da" => "dan",
        "nb" => "nob",
        "fi" => "fin",
        "hu" => "hun",
        "sk" => "slo",
        "bg" => "bul",
        "hr" => "hrv",
        "sr" => "srp",
        "sl" => "slv",
        "et" => "est",
        "lv" => "lav",
        "lt" => "lit",
        "ca" => "cat",
        "sw" => "swa",
        "af" => "afr",
        _ => return base,
    };
    mapped.to_owned()
}
