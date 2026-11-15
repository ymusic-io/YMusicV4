use std::path::{Path, PathBuf};
use std::process::Command;

use ym_remote_config::AppUpdate;

use super::{Blocked, InstallError, Installer, exec, run};

/// Copies the .app out of the DMG beside the installed one, checks it is signed by the installed
/// one's team, swaps the two atomically and relaunches. Nothing is ever written inside a bundle:
/// a signed .app seals every file it contains.
pub struct MacDmgInstaller {
    bundle: Option<PathBuf>,
    relaunch_env: Vec<String>,
}

impl MacDmgInstaller {
    /// `executable` is the running binary; the install is the `.app` around it.
    pub fn new(executable: Option<&Path>, relaunch_env: Vec<String>) -> Self {
        let bundle = executable
            .and_then(|path| path.ancestors().find(|ancestor| ancestor.extension().is_some_and(|ext| ext == "app")))
            .map(Path::to_path_buf);
        Self { bundle, relaunch_env }
    }
}

impl Installer for MacDmgInstaller {
    fn extension(&self) -> &'static str {
        "dmg"
    }

    fn blocked(&self) -> Option<Blocked> {
        let Some(bundle) = &self.bundle else { return Some(Blocked::NotInstalled) };
        // The swap renames inside the folder holding the .app.
        if !bundle.parent().is_some_and(writable) {
            return Some(Blocked::FolderReadOnly);
        }
        None
    }

    fn install(&self, file: &Path, update: &AppUpdate, before_relaunch: &dyn Fn()) -> Result<bool, InstallError> {
        let bundle = self.bundle.as_deref().ok_or(InstallError::NotInstalled)?;
        let parent = bundle.parent().ok_or(InstallError::NotInstalled)?;
        let name = bundle.file_name().ok_or(InstallError::NotInstalled)?.to_string_lossy();
        // Same volume as the install, so the exchange is one rename.
        let staged = parent.join(format!(".{name}.new-{}", update.version_code));
        remove_tree(&staged)?;
        let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos();
        let mount = std::env::temp_dir().join(format!("ymusic-update-{}-{stamp}", std::process::id()));
        std::fs::create_dir_all(&mount)?;
        let (mount_arg, file_arg, staged_arg) = (path_str(&mount)?, path_str(file)?, path_str(&staged)?);
        run("hdiutil", &["attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount_arg, file_arg])?;
        let copied = copy_app(&mount, staged_arg);
        let detached = run("hdiutil", &["detach", "-force", mount_arg]);
        // The mount point is an empty temp folder once detached; a leftover is harmless.
        let _ = std::fs::remove_dir(&mount);
        copied?;
        detached?;

        let swapped = (|| {
            // A signed install only ever becomes a bundle signed by the same team; an unsigned or
            // ad-hoc signed (local) build has no team to hold the update to.
            if let Some(team) = team_id(bundle)? {
                run("codesign", &["--verify", "--deep", "--strict", staged_arg])?;
                if team_id(&staged)?.as_deref() != Some(team.as_str()) {
                    return Err(InstallError::Failed(format!("the update is not signed by team {team}")));
                }
            }
            exchange(bundle, &staged)
        })();
        if let Err(error) = swapped {
            remove_tree(&staged)?;
            return Err(error);
        }
        // Now the superseded version; a leftover hidden copy costs disk only.
        let _ = remove_tree(&staged);
        // `open -n`: the new app must not be a child that dies with this process.
        let mut open = Command::new("open");
        open.arg("-n");
        for variable in &self.relaunch_env {
            open.arg("--env").arg(variable);
        }
        before_relaunch();
        open.arg(bundle).spawn()?;
        Ok(true)
    }
}

fn copy_app(mount: &Path, staged: &str) -> Result<(), InstallError> {
    let app = std::fs::read_dir(mount)?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .find(|path| path.is_dir() && path.extension().is_some_and(|ext| ext == "app"))
        .ok_or_else(|| InstallError::Failed("the disk image holds no .app".into()))?;
    run("ditto", &[path_str(&app)?, staged])
}

fn team_id(app: &Path) -> Result<Option<String>, InstallError> {
    let output = exec("codesign", &["-dv", "--verbose=2", path_str(app)?])?;
    if output.code != 0 {
        return Ok(None);
    }
    Ok(output
        .text
        .lines()
        .find_map(|line| line.strip_prefix("TeamIdentifier="))
        .filter(|team| *team != "not set")
        .map(str::to_owned))
}

fn path_str(path: &Path) -> Result<&str, InstallError> {
    path.to_str().ok_or_else(|| InstallError::Failed(format!("path is not UTF-8: {}", path.display())))
}

fn remove_tree(path: &Path) -> std::io::Result<()> {
    match std::fs::remove_dir_all(path) {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

#[cfg(target_os = "macos")]
fn writable(folder: &Path) -> bool {
    use std::os::unix::ffi::OsStrExt;
    let Ok(path) = std::ffi::CString::new(folder.as_os_str().as_bytes()) else { return false };
    // SAFETY: `path` is a valid NUL-terminated string for the call's duration.
    unsafe { libc::access(path.as_ptr(), libc::W_OK) == 0 }
}

#[cfg(not(target_os = "macos"))]
fn writable(_folder: &Path) -> bool {
    false
}

/// Trades `installed` and `staged` atomically (`renamex_np` with `RENAME_SWAP`, macOS 10.12+): there
/// is no instant where the install path is missing or half-written.
#[cfg(target_os = "macos")]
fn exchange(installed: &Path, staged: &Path) -> Result<(), InstallError> {
    use std::os::unix::ffi::OsStrExt;
    let path = |path: &Path| {
        std::ffi::CString::new(path.as_os_str().as_bytes())
            .map_err(|_| InstallError::Failed(format!("path holds a NUL: {}", path.display())))
    };
    let (from, to) = (path(installed)?, path(staged)?);
    // SAFETY: both are valid NUL-terminated strings for the call's duration.
    if unsafe { libc::renamex_np(from.as_ptr(), to.as_ptr(), libc::RENAME_SWAP) } != 0 {
        let error = std::io::Error::last_os_error();
        return Err(InstallError::Failed(format!(
            "renamex_np failed ({error}) exchanging {} and {}",
            installed.display(),
            staged.display()
        )));
    }
    Ok(())
}

#[cfg(not(target_os = "macos"))]
fn exchange(_installed: &Path, _staged: &Path) -> Result<(), InstallError> {
    Err(InstallError::Failed("an app bundle swap needs macOS".into()))
}
