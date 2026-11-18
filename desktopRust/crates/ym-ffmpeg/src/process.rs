//! One blocking ffmpeg run: stderr (and stdout when captured) drained on their own threads so a
//! chatty child never stalls on a full pipe, a deadline, and the child killed and reaped on every
//! way out.

use std::ffi::OsString;
use std::io::Read;
use std::path::Path;
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use crate::FfmpegError;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub(crate) struct Finished {
    pub status: ExitStatus,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
}

/// Kills and reaps the child when dropped; once it has been waited for, both are no-ops.
struct ChildGuard(Child);

impl Drop for ChildGuard {
    fn drop(&mut self) {
        // Cleanup: the run's own result already says why it ended.
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

enum Pipe {
    Stdout,
    Stderr,
}

fn drain(
    mut reader: impl Read + Send + 'static,
    limit: Option<u64>,
    pipe: Pipe,
    sender: mpsc::Sender<(Pipe, std::io::Result<Vec<u8>>)>,
) {
    std::thread::spawn(move || {
        let mut bytes = Vec::new();
        let read = match limit {
            Some(limit) => reader.take(limit).read_to_end(&mut bytes),
            None => reader.read_to_end(&mut bytes),
        };
        // The receiver is gone only when the run already gave up on this child.
        let _ = sender.send((pipe, read.map(|_| bytes)));
    });
}

fn timed_out(timeout: Duration) -> FfmpegError {
    FfmpegError::new("R40003", format!("ffmpeg ran past {} ms and was killed", timeout.as_millis()))
}

/// `stdout_limit` captures stdout up to that many bytes (more fails the run); `None` discards it.
pub(crate) fn run(
    binary: &Path,
    args: &[OsString],
    stdout_limit: Option<usize>,
    timeout: Duration,
) -> Result<Finished, FfmpegError> {
    let mut command = Command::new(binary);
    command
        .args(args)
        .env_clear()
        .stdin(Stdio::null())
        .stdout(if stdout_limit.is_some() { Stdio::piped() } else { Stdio::null() })
        .stderr(Stdio::piped());
    // A console child of a GUI process would open a console window of its own.
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    let deadline = Instant::now() + timeout;
    let mut child = ChildGuard(
        command.spawn().map_err(|error| FfmpegError::new("R40002", format!("ffmpeg {}: {error}", binary.display())))?,
    );
    let (sender, receiver) = mpsc::channel();
    let mut pipes = 1;
    drain(child.0.stderr.take().expect("piped stderr"), None, Pipe::Stderr, sender.clone());
    if let Some(limit) = stdout_limit {
        pipes += 1;
        drain(child.0.stdout.take().expect("piped stdout"), Some(limit as u64 + 1), Pipe::Stdout, sender);
    }

    let (mut stdout, mut stderr) = (Vec::new(), Vec::new());
    for _ in 0..pipes {
        let (pipe, read) = match receiver.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
            Ok(message) => message,
            Err(mpsc::RecvTimeoutError::Timeout) => return Err(timed_out(timeout)),
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                (Pipe::Stderr, Err(std::io::Error::other("a pipe reader stopped")))
            }
        };
        let read = read.map_err(|error| FfmpegError::new("R40004", format!("reading ffmpeg's output: {error}")))?;
        match pipe {
            Pipe::Stdout if stdout_limit.is_some_and(|limit| read.len() > limit) => {
                return Err(FfmpegError::new(
                    "R40006",
                    format!("ffmpeg wrote more than {} bytes", stdout_limit.unwrap_or_default()),
                ));
            }
            Pipe::Stdout => stdout = read,
            Pipe::Stderr => stderr = read,
        }
    }

    let status = loop {
        match child.0.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() >= deadline => return Err(timed_out(timeout)),
            Ok(None) => std::thread::sleep(Duration::from_millis(2)),
            Err(error) => return Err(FfmpegError::new("R40005", format!("waiting for ffmpeg: {error}"))),
        }
    };
    Ok(Finished { status, stdout, stderr })
}
