use std::path::{Path, PathBuf};
use std::process::Command;

use ym_remote_config::AppUpdate;

use super::{Blocked, InstallError, Installer, exec, tail};

const PKEXEC: &str = "/usr/bin/pkexec";
const APT_GET: &str = "/usr/bin/apt-get";
const DPKG_QUERY: &str = "/usr/bin/dpkg-query";
const DPKG_LOCK_WAIT_S: u32 = 60;
/// The JVM app's .deb (jpackage's lowercased packageName); the Rust .deb keeps it.
const DEB_PACKAGE: &str = "ymusic";

/// Installs the .deb through apt behind a polkit password prompt, then relaunches.
pub struct LinuxDebInstaller {
    launcher: Option<PathBuf>,
}

impl LinuxDebInstaller {
    pub fn new(executable: Option<PathBuf>) -> Self {
        Self { launcher: executable }
    }
}

impl Installer for LinuxDebInstaller {
    fn extension(&self) -> &'static str {
        "deb"
    }

    fn blocked(&self) -> Option<Blocked> {
        let Some(launcher) = &self.launcher else { return Some(Blocked::NotInstalled) };
        if !launcher.starts_with("/opt/") {
            return Some(Blocked::NotDeb);
        }
        if !executable(Path::new(PKEXEC)) || !executable(Path::new(APT_GET)) {
            return Some(Blocked::NoPkexec);
        }
        None
    }

    fn install(&self, file: &Path, update: &AppUpdate, before_relaunch: &dyn Fn()) -> Result<bool, InstallError> {
        let launcher = self.launcher.as_deref().ok_or(InstallError::NotInstalled)?;
        let file = std::path::absolute(file)?;
        let file = file.to_str().ok_or_else(|| InstallError::Failed("installer path is not UTF-8".into()))?;
        // Waits for a background updater (PackageKit, unattended-upgrades) to release the dpkg lock
        // instead of failing at once; an apt without the option ignores it.
        let lock_wait = format!("DPkg::Lock::Timeout={DPKG_LOCK_WAIT_S}");
        let result = exec(PKEXEC, &[APT_GET, "-o", &lock_wait, "install", "-y", file])?;
        // pkexec: 126 = the user dismissed the prompt, 127 = not authorized.
        if result.code == 126 || result.code == 127 {
            return Ok(false);
        }
        // apt also finishes whatever else is pending and exits 100 when an unrelated package fails
        // to configure, after ours is already installed.
        if result.code != 0 && !is_installed(&update.version_name)? {
            let failure = format!("apt-get exited {}: {}", result.code, tail(&result.text));
            if result.text.contains("Could not get lock") {
                return Err(InstallError::PackageManagerBusy(failure));
            }
            return Err(InstallError::Failed(failure));
        }
        before_relaunch();
        Command::new(launcher).spawn()?;
        Ok(true)
    }
}

fn is_installed(version_name: &str) -> Result<bool, InstallError> {
    let result = exec(DPKG_QUERY, &["-W", "-f=${Status} ${Version}", DEB_PACKAGE])?;
    let Some(version) = result.text.trim().strip_prefix("install ok installed ").filter(|_| result.code == 0) else {
        return Ok(false);
    };
    Ok(version == version_name || version.starts_with(&format!("{version_name}-")))
}

#[cfg(unix)]
fn executable(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path).is_ok_and(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn executable(_path: &Path) -> bool {
    false
}
