use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::runtime::Handle;
use tokio::sync::watch;
use tokio::task::AbortHandle;
use ym_common::log::Logger;
use ym_remote_config::{AppUpdate, RemoteConfigData, verify_signature};

use crate::download::{DownloadError, download_resumable, sha256_of};
use crate::install::{Blocked, InstallError, Installer};

const PROGRESS_INTERVAL: Duration = Duration::from_millis(100);

/// What a failed step shows: a category the user can act on, or the code to report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Failure {
    Network,
    DiskFull,
    Code(&'static str),
}

/// What the UI shows. A verified download survives a restart, so `Ready` can be the first state.
#[derive(Debug, Clone, PartialEq)]
pub enum UpdateState {
    Idle,
    Available(AppUpdate),
    Downloading {
        update: AppUpdate,
        done: u64,
        total: u64,
    },
    Ready {
        update: AppUpdate,
        installer: PathBuf,
    },
    Installing(AppUpdate),
    /// The installer is still downloaded and verified; installing can be retried.
    InstallFailed {
        update: AppUpdate,
        installer: PathBuf,
        failure: Failure,
    },
    /// `trusted` false: the entry's signature did not verify, so nothing of it may be downloaded
    /// or opened; only its version and notes are shown.
    Failed {
        update: AppUpdate,
        failure: Failure,
        trusted: bool,
    },
}

impl UpdateState {
    pub fn update(&self) -> Option<&AppUpdate> {
        match self {
            Self::Idle => None,
            Self::Available(update) | Self::Installing(update) => Some(update),
            Self::Downloading { update, .. }
            | Self::Ready { update, .. }
            | Self::InstallFailed { update, .. }
            | Self::Failed { update, .. } => Some(update),
        }
    }
}

/// The user's update settings, read and written through the app's settings store (blocking).
pub struct UpdatePrefs {
    /// Download as soon as an update is offered, without asking.
    pub auto_download: Box<dyn Fn() -> bool + Send + Sync>,
    /// The sha256 of the release whose automatic download failed ("" for none).
    pub auto_download_failed: Box<dyn Fn() -> String + Send + Sync>,
    pub remember_auto_download_failed: Box<dyn Fn(String) + Send + Sync>,
}

pub struct UpdateConfig {
    pub client: reqwest::Client,
    /// The pinned update key (base64 SPKI); blank disables updating altogether.
    pub public_key: String,
    pub current_version_code: i32,
    /// `macos-arm64`, …: part of the signed message, so one platform's build cannot pass as another's.
    pub platform: String,
    /// Where installers are downloaded; nothing else lives here.
    pub download_dir: PathBuf,
    pub installer: Arc<dyn Installer>,
    pub prefs: UpdatePrefs,
    pub log: Logger,
}

struct Offer {
    update: AppUpdate,
    trusted: bool,
}

#[derive(Clone)]
enum Step {
    Idle,
    Downloading(u64, u64),
    Ready(PathBuf),
    InstallFailed(PathBuf, Failure),
    Failed(Failure),
}

/// The offered release's tasks: downloads run in them and die with them when the config moves on.
struct Following {
    release: String,
    tasks: Vec<AbortHandle>,
}

#[derive(Default)]
struct Inner {
    /// The newer release the config offers now.
    offer: Option<Offer>,
    /// One release's download; shown only while that release is still offered.
    tracked: Option<(String, Step)>,
    /// The release whose installer is running: shown whatever the config says meanwhile.
    installing: Option<AppUpdate>,
    /// An entry that failed verification: reported once, not on every config refresh.
    rejected: Option<AppUpdate>,
    following: Option<Following>,
}

/// Turns the config service's `appUpdate` into an installed new version: verify the entry's
/// signature, download the installer, check its hash, hand it to the platform [`Installer`].
///
/// The config document is unsigned and cached on disk, so the entry is trusted only through the
/// pinned key: an installer runs only if its sha256 is the one signed for this platform and
/// version. The config is the one source of truth: every download belongs to one release
/// (versionCode + sha256), and a config that moves on cancels it. Only a running install holds
/// still until it ends.
pub struct UpdateService {
    config: UpdateConfig,
    inner: Mutex<Inner>,
    state: watch::Sender<UpdateState>,
    tokio: Handle,
    log: Logger,
}

/// One published build: a re-published version is a different release.
fn release_of(update: &AppUpdate) -> String {
    format!("{}-{}", update.version_code, update.sha256)
}

impl UpdateService {
    /// Follows `configs` from now on; `None` without a pinned key (nothing could be verified).
    pub fn start(config: UpdateConfig, configs: watch::Receiver<RemoteConfigData>, tokio: Handle) -> Option<Arc<Self>> {
        if config.public_key.trim().is_empty() {
            return None;
        }
        let log = config.log.fork("update");
        let service = Arc::new(Self {
            config,
            inner: Mutex::new(Inner::default()),
            state: watch::channel(UpdateState::Idle).0,
            tokio: tokio.clone(),
            log,
        });
        let follower = service.clone();
        tokio.spawn(async move {
            let pruned = follower.clone();
            if let Ok(Err(error)) = tokio::task::spawn_blocking(move || pruned.prune_downloads()).await {
                follower.log.warn("old update downloads could not be removed", Some(&error));
            }
            let mut configs = configs;
            loop {
                let update = configs.borrow_and_update().app_update.clone();
                follower.accept(update);
                if configs.changed().await.is_err() {
                    return;
                }
            }
        });
        Some(service)
    }

    pub fn subscribe(&self) -> watch::Receiver<UpdateState> {
        self.state.subscribe()
    }

    pub fn state(&self) -> UpdateState {
        self.state.borrow().clone()
    }

    pub fn blocked(&self) -> Option<Blocked> {
        self.config.installer.blocked()
    }

    /// Whether the config offers a newer release right now, trusted or not.
    pub fn has_offer(&self) -> bool {
        self.inner.lock().unwrap().offer.is_some()
    }

    /// Takes the config's latest answer; only a newer, correctly signed entry is ever downloaded.
    pub fn accept(self: &Arc<Self>, update: Option<AppUpdate>) {
        let mut inner = self.inner.lock().unwrap();
        let update = update
            .map(|mut update| {
                update.sha256 = update.sha256.to_ascii_lowercase();
                update
            })
            .filter(|update| update.version_code > self.config.current_version_code);
        let Some(update) = update else {
            inner.offer = None;
            self.refresh(&mut inner);
            return;
        };
        if inner.offer.as_ref().is_some_and(|offer| offer.update == update) {
            return;
        }
        let signed = format!("app-{}", self.config.platform);
        let rejected = inner.rejected.as_ref() == Some(&update);
        let trusted = !rejected
            && verify_signature(
                &self.config.public_key,
                &signed,
                i64::from(update.version_code),
                &update.sha256,
                &update.signature,
            );
        if !trusted && !rejected {
            self.log.capture_error(
                "R90300 update entry rejected",
                &UpdateError(format!("{signed} {}: signature does not match the pinned key", update.version_code)),
            );
            inner.rejected = Some(update.clone());
        }
        inner.offer = Some(Offer { update, trusted });
        self.refresh(&mut inner);
    }

    /// Fetches the offered release's installer: the "Download" button.
    pub fn download(self: &Arc<Self>) {
        let update = {
            let inner = self.inner.lock().unwrap();
            inner.offer.as_ref().filter(|offer| offer.trusted).map(|offer| offer.update.clone())
        };
        if let Some(update) = update {
            self.start_download(update, false);
        }
    }

    /// Runs the downloaded installer. `before_relaunch` runs (blocking pool) once the install is
    /// certain to go ahead, before the new version can start. `true`: the new version is starting
    /// and the caller must exit at once. `false`: nothing changed (not ready, or the admin prompt was
    /// declined), and the state is again whatever the config offers now.
    pub async fn install(self: &Arc<Self>, before_relaunch: impl Fn() + Send + 'static) -> bool {
        let (update, file) = {
            let mut inner = self.inner.lock().unwrap();
            if inner.installing.is_some() {
                return false;
            }
            let Some(update) = inner.offer.as_ref().filter(|offer| offer.trusted).map(|offer| offer.update.clone())
            else {
                return false;
            };
            let release = release_of(&update);
            let file = match inner.tracked.as_ref().filter(|(tracked, _)| *tracked == release) {
                Some((_, Step::Ready(file) | Step::InstallFailed(file, _))) => file.clone(),
                _ => return false,
            };
            inner.installing = Some(update.clone());
            self.publish(&inner);
            (update, file)
        };
        let release = release_of(&update);
        let (installer, installing, target) = (self.config.installer.clone(), update.clone(), file.clone());
        let result = tokio::task::spawn_blocking(move || installer.install(&target, &installing, &before_relaunch))
            .await
            .unwrap_or_else(|error| Err(InstallError::Failed(format!("installer task failed: {error}"))));
        let failure = match result {
            Ok(true) => return true,
            Ok(false) => {
                self.set(&release, Step::Ready(file), true);
                return false;
            }
            Err(InstallError::Io(error)) if is_disk_full(&error) => {
                self.log
                    .warn(&format!("installing update {} failed: storage is full", update.version_code), Some(&error));
                Failure::DiskFull
            }
            Err(error @ InstallError::PackageManagerBusy(_)) => {
                self.log.warn(
                    &format!("R90305 installing update {} failed: the package manager is busy", update.version_code),
                    Some(&error),
                );
                Failure::Code("R90305")
            }
            Err(error) => {
                self.log.capture_error(&format!("R90303 installing update {} failed", update.version_code), &error);
                Failure::Code("R90303")
            }
        };
        self.set(&release, Step::InstallFailed(file, failure), true);
        false
    }

    /// Recomputes the state, and follows the trusted offered release when it changed.
    fn refresh(self: &Arc<Self>, inner: &mut Inner) {
        self.publish(inner);
        let wanted = inner.offer.as_ref().filter(|offer| offer.trusted).map(|offer| release_of(&offer.update));
        if inner.following.as_ref().map(|following| &following.release) == wanted.as_ref() {
            return;
        }
        if let Some(previous) = inner.following.take() {
            previous.tasks.iter().for_each(AbortHandle::abort);
        }
        let Some(release) = wanted else { return };
        let service = self.clone();
        let task = self.tokio.spawn({
            let release = release.clone();
            async move { service.follow(release).await }
        });
        inner.following = Some(Following { release, tasks: vec![task.abort_handle()] });
    }

    fn publish(&self, inner: &Inner) {
        let state = Self::state_of(inner);
        self.state.send_if_modified(|current| {
            if *current == state {
                return false;
            }
            *current = state;
            true
        });
    }

    fn state_of(inner: &Inner) -> UpdateState {
        if let Some(update) = &inner.installing {
            return UpdateState::Installing(update.clone());
        }
        let Some(offer) = &inner.offer else { return UpdateState::Idle };
        let update = offer.update.clone();
        if !offer.trusted {
            return UpdateState::Failed { update, failure: Failure::Code("R90300"), trusted: false };
        }
        let release = release_of(&update);
        let step = inner.tracked.as_ref().filter(|(tracked, _)| *tracked == release).map(|(_, step)| step.clone());
        match step.unwrap_or(Step::Idle) {
            Step::Idle => UpdateState::Available(update),
            Step::Downloading(done, total) => UpdateState::Downloading { update, done, total },
            Step::Ready(installer) => UpdateState::Ready { update, installer },
            Step::InstallFailed(installer, failure) => UpdateState::InstallFailed { update, installer, failure },
            Step::Failed(failure) => UpdateState::Failed { update, failure, trusted: true },
        }
    }

    /// Writes `step` only while `release` is still offered; `done_installing` also ends an install.
    fn set(&self, release: &str, step: Step, done_installing: bool) {
        let mut inner = self.inner.lock().unwrap();
        if done_installing {
            inner.installing = None;
        }
        if inner.offer.as_ref().is_some_and(|offer| release_of(&offer.update) == release) {
            inner.tracked = Some((release.to_owned(), step));
        }
        self.publish(&inner);
    }

    /// While `release` is offered: show whether it is already downloaded, then fetch it automatically,
    /// once per release; a release whose automatic download failed waits for a tap.
    async fn follow(self: Arc<Self>, release: String) {
        let update = {
            let inner = self.inner.lock().unwrap();
            inner
                .offer
                .as_ref()
                .filter(|offer| offer.trusted && release_of(&offer.update) == release)
                .map(|offer| offer.update.clone())
        };
        let Some(update) = update else { return };
        let checks = self.clone();
        let checked = update.clone();
        let (file, wants_auto) = tokio::task::spawn_blocking(move || {
            let file = if checked.install_in_app { checks.verified_installer(&checked) } else { None };
            let prefs = &checks.config.prefs;
            let wants_auto = file.is_none()
                && checked.install_in_app
                && checks.blocked().is_none()
                && (prefs.auto_download)()
                && checked.sha256 != (prefs.auto_download_failed)();
            (file, wants_auto)
        })
        .await
        .unwrap_or((None, false));
        self.set(&release, file.map(Step::Ready).unwrap_or(Step::Idle), false);
        if wants_auto {
            self.start_download(update, true);
        }
    }

    /// Fetches `update`'s installer and verifies it against the signed hash. A failure deletes the
    /// partial file; a cancelled one (the config moved on) keeps it, to resume if the release comes
    /// back. An `auto`matic download that fails is remembered and the release offered the normal
    /// way; one the user started reports `Failed`.
    fn start_download(self: &Arc<Self>, update: AppUpdate, auto: bool) {
        if !update.install_in_app {
            return;
        }
        let release = release_of(&update);
        let mut inner = self.inner.lock().unwrap();
        if inner.following.as_ref().is_none_or(|following| following.release != release) {
            return;
        }
        let step = inner.tracked.as_ref().filter(|(tracked, _)| *tracked == release).map(|(_, step)| step);
        let busy = matches!(step, Some(Step::Downloading(..) | Step::Ready(_) | Step::InstallFailed(..)))
            || inner.installing.is_some();
        if busy {
            return;
        }
        inner.tracked = Some((release.clone(), Step::Downloading(0, update.size.max(0) as u64)));
        self.publish(&inner);
        let service = self.clone();
        let task = self.tokio.spawn(async move { service.run_download(update, release, auto).await });
        if let Some(following) = inner.following.as_mut() {
            following.tasks.push(task.abort_handle());
        }
    }

    async fn run_download(self: Arc<Self>, update: AppUpdate, release: String, auto: bool) {
        let checks = self.clone();
        let checked = update.clone();
        let ready = tokio::task::spawn_blocking(move || checks.verified_installer(&checked)).await.ok().flatten();
        let result = match ready {
            Some(file) => Ok(file),
            None => self.fetch(&update, &release).await,
        };
        let error = match result {
            Ok(file) => {
                self.set(&release, Step::Ready(file), false);
                self.log.info(&format!("update {} downloaded and verified", update.version_code));
                return;
            }
            Err(error) => error,
        };
        let part = self.part_file(&update);
        if let Err(cleanup) = tokio::fs::remove_file(&part).await
            && cleanup.kind() != std::io::ErrorKind::NotFound
        {
            self.log.warn("a failed update download could not be removed", Some(&cleanup));
        }
        let code = update.version_code;
        if auto {
            self.log.warn(&format!("automatic download of update {code} failed; offering it instead"), Some(&error));
            let prefs = self.clone();
            let sha256 = update.sha256.clone();
            let _ =
                tokio::task::spawn_blocking(move || (prefs.config.prefs.remember_auto_download_failed)(sha256)).await;
            self.set(&release, Step::Idle, false);
            return;
        }
        let failure = match &error {
            DownloadError::Mismatch(_) => {
                self.log.capture_error(&format!("R90301 update {code} failed verification"), &error);
                Failure::Code("R90301")
            }
            DownloadError::Status(_) => {
                self.log.warn(&format!("R90304 downloading update {code} refused by the server"), Some(&error));
                Failure::Code("R90304")
            }
            DownloadError::Io(io) if is_disk_full(io) => {
                self.log.warn(&format!("downloading update {code} failed: storage is full"), Some(&error));
                Failure::DiskFull
            }
            DownloadError::Network(_) => {
                self.log.warn(&format!("downloading update {code} hit a network failure"), Some(&error));
                Failure::Network
            }
            DownloadError::Io(_) => {
                self.log.capture_error(&format!("R90302 downloading update {code} failed"), &error);
                Failure::Code("R90302")
            }
        };
        self.set(&release, Step::Failed(failure), false);
    }

    async fn fetch(self: &Arc<Self>, update: &AppUpdate, release: &str) -> Result<PathBuf, DownloadError> {
        let (target, part) = (self.installer_file(update), self.part_file(update));
        let (dir, keep) = (self.config.download_dir.clone(), part.clone());
        tokio::task::spawn_blocking(move || -> std::io::Result<()> {
            std::fs::create_dir_all(&dir)?;
            // Another release's leftovers can never be resumed into this one.
            for entry in std::fs::read_dir(&dir)?.filter_map(Result::ok) {
                let path = entry.path();
                if path != keep && path.extension().is_some_and(|ext| ext == "part") {
                    remove_any(&path)?;
                }
            }
            Ok(())
        })
        .await
        .map_err(std::io::Error::other)??;
        let size = update.size.max(0) as u64;
        let mut last_progress: Option<Instant> = None;
        download_resumable(&self.config.client, &update.url, &part, size, &update.sha256, |done| {
            if done < size && last_progress.is_some_and(|at| at.elapsed() < PROGRESS_INTERVAL) {
                return;
            }
            last_progress = Some(Instant::now());
            self.set(release, Step::Downloading(done, size), false);
        })
        .await?;
        // A stale installer of the same versionCode (a re-published build) may sit at `target`.
        tokio::fs::rename(&part, &target).await?;
        Ok(target)
    }

    /// Removes installers and partial downloads this version has caught up with; a newer release's
    /// partial download stays for the next attempt to resume. Blocking.
    fn prune_downloads(&self) -> std::io::Result<()> {
        let entries = match std::fs::read_dir(&self.config.download_dir) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error),
        };
        for entry in entries.filter_map(Result::ok) {
            let name = entry.file_name().to_string_lossy().into_owned();
            let version_code = name
                .strip_prefix("YMusic-")
                .and_then(|rest| rest.split(['.', '-']).next())
                .and_then(|code| code.parse::<i32>().ok());
            if version_code.is_none_or(|code| code <= self.config.current_version_code) {
                remove_any(&entry.path())?;
            }
        }
        Ok(())
    }

    /// Named after the version AND hash: a re-published release must not continue the old bytes.
    fn part_file(&self, update: &AppUpdate) -> PathBuf {
        let hash: String = update.sha256.chars().take(16).collect();
        self.config.download_dir.join(format!(
            "YMusic-{}-{hash}.{}.part",
            update.version_code,
            self.config.installer.extension()
        ))
    }

    fn installer_file(&self, update: &AppUpdate) -> PathBuf {
        self.config.download_dir.join(format!("YMusic-{}.{}", update.version_code, self.config.installer.extension()))
    }

    /// `update`'s installer when it is already downloaded and matches the signed hash. Blocking.
    fn verified_installer(&self, update: &AppUpdate) -> Option<PathBuf> {
        let file = self.installer_file(update);
        (file.is_file() && sha256_of(&file).is_ok_and(|hash| hash == update.sha256)).then_some(file)
    }
}

#[derive(Debug, thiserror::Error)]
#[error("{0}")]
struct UpdateError(String);

fn is_disk_full(error: &std::io::Error) -> bool {
    matches!(error.kind(), std::io::ErrorKind::StorageFull | std::io::ErrorKind::QuotaExceeded)
}

fn remove_any(path: &Path) -> std::io::Result<()> {
    if path.is_dir() { std::fs::remove_dir_all(path) } else { std::fs::remove_file(path) }
}
