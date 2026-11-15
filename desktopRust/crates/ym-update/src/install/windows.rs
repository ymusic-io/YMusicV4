use std::path::{Path, PathBuf};
use std::process::Command;

use ym_remote_config::AppUpdate;

use super::{Blocked, InstallError, Installer};

const SCRIPT: &str = r#"param([int]$WaitPid, [string]$Setup, [string]$Launcher)
Wait-Process -Id $WaitPid -ErrorAction SilentlyContinue
try {
    $setupArgs = @('/SILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-')
    Start-Process -FilePath $Setup -Verb RunAs -Wait -ArgumentList $setupArgs
} catch {}
Start-Process -FilePath $Launcher
"#;

/// Runs the Inno Setup installer once this process has exited (a running app's files are locked):
/// a detached PowerShell step waits for our exit, runs the setup elevated (the UAC prompt) showing
/// progress only, then starts YMusic again unelevated, whether or not the user accepted.
pub struct WindowsSetupInstaller {
    launcher: Option<PathBuf>,
}

impl WindowsSetupInstaller {
    /// An installed copy sits beside Inno's uninstaller (`unins000.exe`).
    pub fn new(executable: Option<PathBuf>) -> Self {
        let launcher = executable.filter(|path| {
            path.parent()
                .and_then(|folder| std::fs::read_dir(folder).ok())
                .into_iter()
                .flatten()
                .filter_map(Result::ok)
                .any(|entry| {
                    let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
                    name.starts_with("unins") && name.ends_with(".exe")
                })
        });
        Self { launcher }
    }
}

impl Installer for WindowsSetupInstaller {
    fn extension(&self) -> &'static str {
        "exe"
    }

    fn blocked(&self) -> Option<Blocked> {
        self.launcher.is_none().then_some(Blocked::NotInstalled)
    }

    fn install(&self, file: &Path, _update: &AppUpdate, before_relaunch: &dyn Fn()) -> Result<bool, InstallError> {
        let launcher = self.launcher.as_deref().ok_or(InstallError::NotInstalled)?;
        let script = file.parent().ok_or(InstallError::NotInstalled)?.join("install-update.ps1");
        std::fs::write(&script, SCRIPT)?;
        let mut command = Command::new("powershell.exe");
        command
            .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File"])
            .arg(&script)
            .arg("-WaitPid")
            .arg(std::process::id().to_string())
            .arg("-Setup")
            .arg(file)
            .arg("-Launcher")
            .arg(launcher);
        detach(&mut command);
        command.spawn()?;
        // The step waits for this process to exit, so nothing starts before the hook has run.
        before_relaunch();
        Ok(true)
    }
}

#[cfg(target_os = "windows")]
fn detach(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW);
}

#[cfg(not(target_os = "windows"))]
fn detach(_command: &mut Command) {}
