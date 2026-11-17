//! Whether the previous launch ended cleanly. A marker file names the running launch from start to a
//! clean quit; a native crash, a stack overflow or a kill leaves it behind, none of which the panic
//! hook sees, and the next launch reports it. A launch the OS booted after was ended by a shutdown
//! or restart, not the app.

use std::io::ErrorKind;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

const PANICKED: &str = "panicked";
/// The launch's start, wall-clock milliseconds since the epoch; markers of earlier builds lack it.
const STARTED: &str = "started=";

/// A launch that never reached a clean quit.
pub struct UncleanExit {
    pub launch: String,
    /// Its panic hook ran, so the panic is reported already.
    pub panicked: bool,
    /// The OS booted after it started: a shutdown or restart ended it.
    pub before_boot: bool,
}

pub struct ExitMarker {
    path: PathBuf,
}

impl ExitMarker {
    /// Blocking: what the previous launch left, then `launch` named as running.
    pub fn arm(path: PathBuf, launch: &str) -> std::io::Result<(Self, Option<UncleanExit>)> {
        let previous = match std::fs::read_to_string(&path) {
            Ok(text) => parse(&text, boot_time_ms()),
            Err(error) if error.kind() == ErrorKind::NotFound => None,
            Err(error) => return Err(error),
        };
        std::fs::write(&path, format!("{launch}\n{STARTED}{}", now_ms()))?;
        Ok((Self { path }, previous))
    }

    /// From the panic hook: the panic is reported, so the next launch must not report this one again.
    pub fn mark_panicked(&self) {
        // Fire-and-forget: a panic hook has nowhere to report a failed write.
        if let Ok(text) = std::fs::read_to_string(&self.path) {
            let _ = std::fs::write(&self.path, format!("{}\n{PANICKED}", text.trim_end()));
        }
    }

    /// Blocking: a clean quit.
    pub fn disarm(&self) -> std::io::Result<()> {
        match std::fs::remove_file(&self.path) {
            Err(error) if error.kind() != ErrorKind::NotFound => Err(error),
            _ => Ok(()),
        }
    }
}

fn parse(text: &str, boot_ms: Option<u64>) -> Option<UncleanExit> {
    let mut lines = text.lines();
    let launch = lines.next()?.trim();
    if launch.is_empty() {
        return None;
    }
    let (mut panicked, mut started_ms) = (false, None);
    for line in lines.map(str::trim) {
        if line == PANICKED {
            panicked = true;
        } else if let Some(started) = line.strip_prefix(STARTED) {
            started_ms = started.parse::<u64>().ok();
        }
    }
    let before_boot = matches!((started_ms, boot_ms), (Some(started), Some(boot)) if started < boot);
    Some(UncleanExit { launch: launch.to_owned(), panicked, before_boot })
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |elapsed| elapsed.as_millis() as u64)
}

/// When the OS booted, wall-clock milliseconds since the epoch.
#[cfg(target_os = "macos")]
fn boot_time_ms() -> Option<u64> {
    let mut boot_time = libc::timeval { tv_sec: 0, tv_usec: 0 };
    let mut size = std::mem::size_of::<libc::timeval>();
    // SAFETY: kern.boottime fills a timeval of the size given.
    let read = unsafe {
        libc::sysctlbyname(
            c"kern.boottime".as_ptr(),
            (&mut boot_time as *mut libc::timeval).cast(),
            &mut size,
            std::ptr::null_mut(),
            0,
        )
    };
    (read == 0 && boot_time.tv_sec > 0).then(|| boot_time.tv_sec as u64 * 1000 + boot_time.tv_usec as u64 / 1000)
}

#[cfg(target_os = "linux")]
fn boot_time_ms() -> Option<u64> {
    let stat = std::fs::read_to_string("/proc/stat").ok()?;
    let seconds = stat.lines().find_map(|line| line.strip_prefix("btime "))?.trim().parse::<u64>().ok()?;
    Some(seconds * 1000)
}

#[cfg(target_os = "windows")]
fn boot_time_ms() -> Option<u64> {
    // SAFETY: no arguments, no preconditions.
    let since_boot = unsafe { windows::Win32::System::SystemInformation::GetTickCount64() };
    now_ms().checked_sub(since_boot)
}

#[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
fn boot_time_ms() -> Option<u64> {
    None
}
