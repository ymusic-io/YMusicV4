//! Runs a downloaded, verified installer over the running copy. Every platform installs the whole
//! build, in the shapes the JVM app's installers use, so either app can update into the other:
//! macOS swaps the .app, Windows and Linux hand the package to the OS installer, which asks for
//! admin rights.

mod linux;
mod mac;
mod windows;

use std::path::Path;
use std::process::Command;
use std::sync::Arc;

pub use linux::LinuxDebInstaller;
pub use mac::MacDmgInstaller;
pub use windows::WindowsSetupInstaller;
use ym_remote_config::AppUpdate;

/// Why this copy cannot update in place; the UI names it next to the manual download.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Blocked {
    NotInstalled,
    FolderReadOnly,
    NotDeb,
    NoPkexec,
}

#[derive(Debug, thiserror::Error)]
pub enum InstallError {
    #[error("the app is not running from an installed copy")]
    NotInstalled,
    #[error("installer: {0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Failed(String),
    /// Another package manager held the lock past the wait: the device's state, retried later.
    #[error("the package manager is busy: {0}")]
    PackageManagerBusy(String),
}

pub trait Installer: Send + Sync {
    /// The installer file this platform publishes.
    fn extension(&self) -> &'static str;

    /// `None` when an in-place update is possible here.
    fn blocked(&self) -> Option<Blocked>;

    /// Installs `file`, already checked against `update`'s signed hash. Blocking. `before_relaunch`
    /// runs once the install is certain to go ahead, before the new version can start. `Ok(true)`:
    /// the new version is starting and this process must exit now. `Ok(false)`: the user declined
    /// the admin prompt and nothing changed.
    fn install(&self, file: &Path, update: &AppUpdate, before_relaunch: &dyn Fn()) -> Result<bool, InstallError>;
}

/// `macos-arm64`, `windows-x64`, `linux-x64`: the key the config service publishes releases by.
pub fn platform_key() -> String {
    format!("{}-{}", os_key(), arch_key())
}

pub fn os_key() -> &'static str {
    match std::env::consts::OS {
        "macos" => "macos",
        "windows" => "windows",
        _ => "linux",
    }
}

pub fn arch_key() -> &'static str {
    if cfg!(target_arch = "aarch64") { "arm64" } else { "x64" }
}

/// The installer for this OS, around the running executable. `relaunch_env` (`NAME=VALUE`) is
/// handed to the relaunched app where the OS launcher would drop it (macOS `open`).
pub fn installer_for_current(relaunch_env: Vec<String>) -> Arc<dyn Installer> {
    // Not canonical on Windows: that spells the path `\\?\C:\…`, which the setup step cannot launch.
    let executable =
        std::env::current_exe().ok().map(|path| if cfg!(windows) { path } else { path.canonicalize().unwrap_or(path) });
    match os_key() {
        "macos" => Arc::new(MacDmgInstaller::new(executable.as_deref(), relaunch_env)),
        "windows" => Arc::new(WindowsSetupInstaller::new(executable)),
        _ => Arc::new(LinuxDebInstaller::new(executable)),
    }
}

struct Output {
    code: i32,
    text: String,
}

/// Runs to completion; stdout and stderr together.
fn exec(program: &str, args: &[&str]) -> std::io::Result<Output> {
    let output = Command::new(program).args(args).output()?;
    let mut text = String::from_utf8_lossy(&output.stdout).into_owned();
    text.push_str(&String::from_utf8_lossy(&output.stderr));
    Ok(Output { code: output.status.code().unwrap_or(-1), text })
}

fn run(program: &str, args: &[&str]) -> Result<(), InstallError> {
    let output = exec(program, args)?;
    if output.code != 0 {
        return Err(InstallError::Failed(format!("{program} exited {}: {}", output.code, tail(&output.text))));
    }
    Ok(())
}

fn tail(text: &str) -> &str {
    let start = text.len().saturating_sub(2000);
    let start = (start..text.len()).find(|index| text.is_char_boundary(*index)).unwrap_or(text.len());
    &text[start..]
}
