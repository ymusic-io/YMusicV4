//! Whether the previous launch ended cleanly. A marker file names the running launch from start to a
//! clean quit; a native crash, a stack overflow or a kill leaves it behind, none of which the panic
//! hook sees, and the next launch reports it.

use std::io::ErrorKind;
use std::path::PathBuf;

const PANICKED: &str = "panicked";

/// A launch that never reached a clean quit.
pub struct UncleanExit {
    pub launch: String,
    /// Its panic hook ran, so the panic is reported already.
    pub panicked: bool,
}

pub struct ExitMarker {
    path: PathBuf,
}

impl ExitMarker {
    /// Blocking: what the previous launch left, then `launch` named as running.
    pub fn arm(path: PathBuf, launch: &str) -> std::io::Result<(Self, Option<UncleanExit>)> {
        let previous = match std::fs::read_to_string(&path) {
            Ok(text) => parse(&text),
            Err(error) if error.kind() == ErrorKind::NotFound => None,
            Err(error) => return Err(error),
        };
        std::fs::write(&path, launch)?;
        Ok((Self { path }, previous))
    }

    /// From the panic hook: the panic is reported, so the next launch must not report this one again.
    pub fn mark_panicked(&self) {
        // Fire-and-forget: a panic hook has nowhere to report a failed write.
        if let Ok(launch) = std::fs::read_to_string(&self.path) {
            let _ = std::fs::write(&self.path, format!("{}\n{PANICKED}", launch.lines().next().unwrap_or_default()));
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

fn parse(text: &str) -> Option<UncleanExit> {
    let mut lines = text.lines();
    let launch = lines.next()?.trim();
    (!launch.is_empty())
        .then(|| UncleanExit { launch: launch.to_owned(), panicked: lines.any(|line| line.trim() == PANICKED) })
}
