//! The download service over the app's YouTube session, ffmpeg and stores.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use futures::future::BoxFuture;
use gpui_kit::component::WindowExt;
use gpui_kit::{App, AppContext, Window};
use serde_json::Value;
use ym_common::log::Logger;
use ym_download::spec::ResolvedVideo;
use ym_download::streams::{SpecRemint, SpecResolver};
use ym_download::{DownloadConfig, DownloadError, DownloadService, DownloadState};
use ym_engine::CookieJar;
use ym_engine::player::ContentPoRemint;
use ym_i18n::{Str, t, tf};
use ym_settings::SettingsStore;
use ym_store::MediaStore;
use ym_ytmedia::sabr::SabrHttp;

use crate::elevated_dialog::ConfirmDialog;
use crate::graph::{AppGraph, on_core};
use crate::youtube_session::YoutubeSessions;

/// Where finished downloads go: a `file://` folder URI, shared with the JVM app's key.
pub const DOWNLOAD_LOCATION_KEY: &str = "download.location";
const MAX_CONCURRENT: usize = 3;
/// Every googlevideo request must carry the User-Agent player.js presents.
const DESKTOP_CHROME_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 \
    (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";

struct EngineResolver {
    sessions: Arc<YoutubeSessions>,
    log: Logger,
}

impl SpecResolver for EngineResolver {
    fn resolve<'a>(&'a self, video_id: &'a str) -> BoxFuture<'a, Result<ResolvedVideo, DownloadError>> {
        Box::pin(async move {
            let engine = self.sessions.get().await?.engine.clone();
            let response = engine.player_response(video_id, &self.log).await?;
            let po_token = engine.mint_content_po_token(video_id, &self.log).await?;
            ym_download::spec::resolve(video_id, &response, Some(&po_token))
        })
    }

    fn player_response<'a>(&'a self, video_id: &'a str) -> BoxFuture<'a, Result<Value, DownloadError>> {
        Box::pin(async move {
            let engine = self.sessions.get().await?.engine.clone();
            Ok(engine.player_response(video_id, &self.log).await?)
        })
    }

    fn remint<'a>(&'a self, video_id: &'a str) -> BoxFuture<'a, Result<SpecRemint, DownloadError>> {
        Box::pin(async move {
            let engine = self.sessions.get().await?.engine.clone();
            Ok(match engine.remint_content_po_token(video_id, &self.log).await? {
                ContentPoRemint::Minted(token) => {
                    let response = engine.player_response(video_id, &self.log).await?;
                    SpecRemint::Minted(Box::new(ym_download::spec::resolve(video_id, &response, Some(&token))?))
                }
                ContentPoRemint::PlayerAccepted => SpecRemint::PlayerAccepted,
                ContentPoRemint::Unavailable => SpecRemint::Unavailable,
            })
        })
    }
}

pub struct DownloadInputs {
    pub tokio: tokio::runtime::Handle,
    pub data_dir: PathBuf,
    pub http: ym_common::http::HttpClients,
    pub cookies: Arc<dyn CookieJar>,
    pub settings: Arc<dyn SettingsStore>,
    pub media: Arc<MediaStore>,
    pub sessions: Arc<YoutubeSessions>,
    pub player_cache: Option<Arc<ym_ytmedia::cache::MediaCache>>,
    pub log: Logger,
}

/// Blocking (opens SQLite): call from a worker.
pub fn open(inputs: DownloadInputs) -> Result<DownloadService, String> {
    let log = inputs.log;
    let store = ym_download::store::DownloadStore::open(&inputs.data_dir.join("downloads.db"), log.clone())
        .map_err(|error| format!("downloads.db: {error}"))?;
    let index = ym_ytmedia::segment_index::SegmentIndex::open(&inputs.data_dir.join("download-segments.db"))
        .map_err(|error| format!("download-segments.db: {error}"))?;
    if let Err(error) = index.cleanup() {
        log.warn("old download segments could not be pruned", Some(&error));
    }
    let ffmpeg = ffmpeg_binary();
    if ffmpeg.is_none() {
        log.warn("no ffmpeg binary: downloads offer M4A only", None);
    }
    let cookies = inputs.cookies;
    let settings = inputs.settings;
    let folder_settings = settings.clone();
    let media = inputs.media;
    let completion_log = log.fork("download-library");
    let completion_tokio = inputs.tokio.clone();
    Ok(DownloadService::new(DownloadConfig {
        tokio: inputs.tokio,
        store: Arc::new(store),
        storage: Arc::new(ym_download::storage::DownloadStorage::new(inputs.data_dir.join("download-work"))),
        resolver: Arc::new(EngineResolver { sessions: inputs.sessions, log: log.fork("download-resolver") }),
        sabr: SabrHttp {
            client: inputs.http.not_following.clone(),
            user_agent: DESKTOP_CHROME_UA.to_owned(),
            cookies: Some(Arc::new(move |url| {
                let pairs = cookies.load_for_request(url).ok()?;
                (!pairs.is_empty())
                    .then(|| pairs.iter().map(|(name, value)| format!("{name}={value}")).collect::<Vec<_>>().join("; "))
            })),
        },
        client: inputs.http.following.clone(),
        ffmpeg: ffmpeg.map(ym_download::ffmpeg::Ffmpeg::new),
        segment_index: Some(Arc::new(index)),
        player_cache: inputs.player_cache,
        max_concurrent: MAX_CONCURRENT,
        folder: Arc::new(move || download_folder(folder_settings.as_ref())),
        on_completed: Some(Arc::new(move |_, result| {
            let Some(file) = url::Url::parse(&result.uri).ok().and_then(|uri| uri.to_file_path().ok()) else {
                completion_log.warn(&format!("a finished download has no file path: {}", result.uri), None);
                return;
            };
            let (media, log) = (media.clone(), completion_log.clone());
            completion_tokio.spawn_blocking(move || add_to_library(&media, &file, &log));
        })),
        log,
    }))
}

/// The chosen download folder; none until the user picks one.
pub fn download_folder(settings: &dyn SettingsStore) -> Option<PathBuf> {
    let uri = settings.get_raw(DOWNLOAD_LOCATION_KEY).ok().flatten().filter(|uri| !uri.is_empty())?;
    // Rebuilt from components: a URI saved with a trailing slash still names the folder's Library entry.
    Some(url::Url::parse(&uri).ok()?.to_file_path().ok()?.components().collect())
}

/// The download location as the JVM app keeps it: the folder's `file://` URI, no trailing slash.
pub fn folder_uri(folder: &Path) -> String {
    ym_common::file_uri::to_file_uri(&folder.to_string_lossy())
}

/// Saves `folder` as the download location, off the UI thread.
pub fn save_download_folder(folder: &Path, cx: &App) {
    let graph = cx.global::<AppGraph>();
    let (settings, log, uri) = (graph.settings.clone(), graph.log.fork("downloads"), folder_uri(folder));
    graph.tokio.spawn_blocking(move || {
        if let Err(error) = settings.put_raw(DOWNLOAD_LOCATION_KEY, uri) {
            log.warn(&format!("the download folder could not be saved: {error}"), None);
        }
    });
}

/// Why a folder cannot take downloads, from a test write.
pub fn probe_download_folder(folder: &Path) -> Result<(), Str> {
    if !folder.is_dir() {
        return Err(Str::DownloadLocationMissing);
    }
    let probe = folder.join(".ymusic-write-test");
    match std::fs::write(&probe, b"") {
        Ok(()) => {
            let _ = std::fs::remove_file(&probe);
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => Err(Str::DownloadLocationReadOnly),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Err(Str::DownloadLocationMissing),
        Err(_) => Err(Str::DownloadLocationCannotCreate),
    }
}

/// Blocking: makes `folder` a Library folder (a new one becomes a root, an excluded one is included
/// again), the JVM app's `pickAndRegisterFolder`. Finished downloads are filed under its bucket.
pub fn register_download_folder(media: &MediaStore, folder: &Path) -> Result<(), ym_store::StoreError> {
    let path = folder.to_string_lossy();
    match media.find_bucket_by_uri(&path)? {
        Some(bucket) if bucket.excluded => media.set_bucket_excluded(bucket.id, false),
        Some(_) => Ok(()),
        None => {
            let name = folder
                .file_name()
                .map_or_else(|| path.clone().into_owned(), |name| name.to_string_lossy().into_owned());
            media.upsert_bucket(&path, &name, None).map(|_| ())
        }
    }
}

/// Blocking and long: what is already in the download folder shows up in the Library.
pub fn scan_download_folder(media: &MediaStore, folder: &Path, log: &Logger) {
    match ym_store::scan_folder(media, folder, &AtomicBool::new(false)) {
        Ok(report) => {
            for error in &report.errors {
                log.warn(&format!("scan skipped {}: {}", error.path.display(), error.message), None);
            }
        }
        Err(error) => log.warn("scanning the download folder failed", Some(&error)),
    }
}

/// After a test write, registers `folder` in the Library and saves it as the download location;
/// the folder's scan then runs on its own. The error is the message to show.
pub fn assign_download_folder(folder: PathBuf, cx: &App) -> impl Future<Output = Result<(), String>> + use<> {
    let graph = cx.global::<AppGraph>();
    let (media, settings, log) = (graph.media.clone(), graph.settings.clone(), graph.log.fork("download-folder"));
    let assign = on_core(cx, async move {
        tokio::task::spawn_blocking(move || {
            probe_download_folder(&folder).map_err(|problem| t(problem).to_owned())?;
            if let Err(error) = register_download_folder(&media, &folder) {
                log.warn("R90600 the download folder could not be added to the Library", Some(&error));
                return Err(tf(Str::ErrorGeneric, &[&"R90600"]));
            }
            if let Err(error) = settings.put_raw(DOWNLOAD_LOCATION_KEY, folder_uri(&folder)) {
                log.warn(&format!("the download folder could not be saved: {error}"), None);
            }
            tokio::task::spawn_blocking(move || scan_download_folder(&media, &folder, &log));
            Ok(())
        })
        .await
    });
    async move {
        match assign.await {
            Some(Ok(result)) => result,
            _ => Err(tf(Str::ErrorGeneric, &[&"R90600"])),
        }
    }
}

/// The OS folder picker; a pick that can hold downloads becomes the download location (and a
/// Library folder). None when canceled or refused, the reason shown in `window`.
pub fn pick_download_folder(window: &mut Window, cx: &mut App) -> impl Future<Output = Option<PathBuf>> + use<> {
    let pick = crate::desktop::file_dialog(rfd::AsyncFileDialog::new().set_title(t(Str::FolderPickerTitle)).pick_folder(), cx);
    let (handle, mut async_cx) = (window.window_handle(), cx.to_async());
    async move {
        let folder = pick.await?.path().to_path_buf();
        let assign = async_cx.update(|cx| assign_download_folder(folder.clone(), cx));
        match assign.await {
            Ok(()) => Some(folder),
            Err(problem) => {
                let _ = async_cx.update_window(handle, |_, window, cx| window.push_notification(problem, cx));
                None
            }
        }
    }
}

/// Blocking: the download folder while it still exists as a Library folder that is not excluded.
fn usable_folder(settings: &dyn SettingsStore, media: &MediaStore, log: &Logger) -> Option<PathBuf> {
    let folder = download_folder(settings).filter(|folder| folder.is_dir())?;
    match media.find_bucket_by_uri(&folder.to_string_lossy()) {
        Ok(Some(bucket)) if !bucket.excluded => Some(folder),
        Ok(_) => None,
        Err(error) => {
            log.warn("the download folder's Library entry could not be read", Some(&error));
            None
        }
    }
}

/// The download folder while it is usable, else the picker first (a cancel gives none): the JVM
/// app's start-time gate, so a removed or excluded Library folder is picked again.
pub fn usable_download_folder(window: &mut Window, cx: &mut App) -> impl Future<Output = Option<PathBuf>> + use<> {
    let graph = cx.global::<AppGraph>();
    let (settings, media, log) = (graph.settings.clone(), graph.media.clone(), graph.log.fork("downloads"));
    let check = on_core(cx, async move {
        tokio::task::spawn_blocking(move || usable_folder(settings.as_ref(), &media, &log)).await
    });
    let (handle, mut async_cx) = (window.window_handle(), cx.to_async());
    async move {
        if let Some(Ok(Some(folder))) = check.await {
            return Some(folder);
        }
        async_cx.update_window(handle, |_, window, cx| pick_download_folder(window, cx)).ok()?.await
    }
}

/// The Library's first folder, when it can hold downloads (a test write); blocking.
pub fn location_offer(media: &MediaStore, log: &Logger) -> Result<Option<ym_store::Bucket>, ym_store::StoreError> {
    let Some(root) = media.root_buckets()?.into_iter().next() else { return Ok(None) };
    let probe = Path::new(&root.uri).join(".ymusic-write-test");
    let writable = std::fs::write(&probe, b"").is_ok();
    if writable && let Err(error) = std::fs::remove_file(&probe) {
        log.warn("the download folder write test file could not be removed", Some(&error));
    }
    Ok(writable.then_some(root))
}

/// Offers `folder`, just added as the first Library folder, as the download location.
pub fn offer_download_location(folder: ym_store::Bucket, window: &mut Window, cx: &mut App) {
    let message = tf(Str::DownloadLocationPromptMessage, &[&folder.alias_name.clone().unwrap_or(folder.name.clone())]);
    let path = PathBuf::from(&folder.uri);
    ConfirmDialog::new(t(Str::MenuDownloadLocation), message, t(Str::DownloadLocationPromptSetDefault))
        .cancel(t(Str::DownloadLocationPromptNotNow))
        .open(window, cx, move |_, cx| save_download_folder(&path, cx));
}

/// Video ids already saved: Library files tagged with their YouTube id, and finished downloads.
pub fn offline_ids(cx: &App) -> impl Future<Output = HashSet<String>> + use<> {
    let graph = cx.global::<AppGraph>();
    let (media, service, log) = (graph.media.clone(), graph.downloads.clone(), graph.log.fork("available-offline"));
    let load = on_core(cx, async move {
        let mut ids = HashSet::new();
        match tokio::task::spawn_blocking(move || media.youtube_video_ids()).await {
            Ok(Ok(library)) => ids.extend(library),
            Ok(Err(error)) => log.warn("library video ids could not be read", Some(&error)),
            Err(error) => log.warn("library video ids could not be read", Some(&error)),
        }
        match service.snapshot().await {
            Ok(tasks) => ids.extend(
                tasks.into_iter().filter(|task| task.state == DownloadState::Completed).map(|task| task.video_id),
            ),
            Err(error) => log.warn("finished downloads could not be read", Some(&error)),
        }
        ids
    });
    async move { load.await.unwrap_or_default() }
}

/// Files a finished download under its folder's Library bucket, probed like a scanned file; a
/// folder outside the Library adds nothing (the JVM app's `registerCompletedDownload`).
fn add_to_library(media: &MediaStore, file: &Path, log: &Logger) {
    let Some(folder) = file.parent() else { return };
    let bucket = match media.find_bucket_by_uri(&folder.to_string_lossy()) {
        Ok(Some(bucket)) if !bucket.excluded => bucket,
        Ok(_) => {
            log.info("a finished download is outside the Library folders");
            return;
        }
        Err(error) => {
            log.capture_error("registering a finished download in the library failed", &error);
            return;
        }
    };
    match ym_store::scan_file(media, file, bucket.id) {
        Ok(report) => {
            for error in &report.errors {
                log.warn(&format!("a finished download was not indexed: {}", error.message), None);
            }
        }
        Err(error) => log.capture_error("registering a finished download in the library failed", &error),
    }
}

/// `YMUSIC_FFMPEG`, else next to the executable (packaged), else the dev build's.
fn ffmpeg_binary() -> Option<PathBuf> {
    let candidates = [
        std::env::var_os("YMUSIC_FFMPEG").map(PathBuf::from),
        std::env::current_exe()
            .ok()
            .and_then(|exe| exe.parent().map(|dir| dir.join(format!("ffmpeg{}", std::env::consts::EXE_SUFFIX)))),
        option_env!("YM_FFMPEG_BIN").map(PathBuf::from),
    ];
    candidates.into_iter().flatten().find(|path| path.is_file())
}
