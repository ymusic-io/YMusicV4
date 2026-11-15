//! The desktop self-updater, over the JVM app's feed: the config service's signed `appUpdate`
//! entry, a resumable verified installer download, and the platform installers (macOS DMG swap,
//! Windows Inno Setup, Linux .deb, a Linux AppImage replacing its own file) in the shapes the JVM
//! app uses.

mod download;
mod install;
mod service;

pub use download::{DownloadError, download_resumable, sha256_of};
pub use install::{
    Blocked, InstallError, Installer, LinuxAppImageInstaller, LinuxDebInstaller, MacDmgInstaller,
    WindowsSetupInstaller, appimage_file, arch_key, installer_for_current, os_key, package_key, platform_key,
};
pub use service::{Failure, UpdateConfig, UpdatePrefs, UpdateService, UpdateState};
