use std::path::{Path, PathBuf};
use std::process::Command;

use ym_remote_config::AppUpdate;

use super::{Blocked, InstallError, Installer, writable};

/// Writes the new AppImage over the one this copy was launched from and relaunches it. No admin
/// rights: the user owns the file. The running copy keeps its mount of the replaced file.
pub struct LinuxAppImageInstaller {
    image: PathBuf,
}

impl LinuxAppImageInstaller {
    /// `image` is the AppImage file, not its mount.
    pub fn new(image: PathBuf) -> Self {
        Self { image }
    }
}

impl Installer for LinuxAppImageInstaller {
    fn extension(&self) -> &'static str {
        "AppImage"
    }

    fn blocked(&self) -> Option<Blocked> {
        // The replace is a rename inside the folder holding the image.
        if !self.image.parent().is_some_and(writable) {
            return Some(Blocked::FolderReadOnly);
        }
        None
    }

    fn install(&self, file: &Path, update: &AppUpdate, before_relaunch: &dyn Fn()) -> Result<bool, InstallError> {
        let parent = self.image.parent().ok_or(InstallError::NotInstalled)?;
        let name = self.image.file_name().ok_or(InstallError::NotInstalled)?.to_string_lossy();
        // Same folder as the image, so the replace is one rename and never a half-written file.
        let staged = parent.join(format!(".{name}.new-{}", update.version_code));
        if let Err(error) = stage(file, &staged).and_then(|()| std::fs::rename(&staged, &self.image)) {
            // A leftover hidden copy costs disk only; the install error is what the user hears about.
            let _ = std::fs::remove_file(&staged);
            return Err(error.into());
        }
        before_relaunch();
        Command::new(&self.image).spawn()?;
        Ok(true)
    }
}

fn stage(file: &Path, staged: &Path) -> std::io::Result<()> {
    std::fs::copy(file, staged)?;
    set_executable(staged)
}

#[cfg(unix)]
fn set_executable(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755))
}

#[cfg(not(unix))]
fn set_executable(_path: &Path) -> std::io::Result<()> {
    Ok(())
}
