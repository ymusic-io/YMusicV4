//! The app's long-lived services, shared by every view through a gpui global.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use gpui_kit::{App, Global};
use tokio::runtime::Handle;
use ym_common::http::HttpClients;
use ym_common::log::Logger;
use ym_download::DownloadService;
use ym_engine::{
    CookieJar, DEFAULT_HTTP_CACHE_BYTES, DiskCacheStore, DynamicEngine, EngineConfig, EngineError, EngineManager,
    HttpCache,
};
use ym_remote_config::{ApiIdentity, BundleSource, RemoteConfig, StatUsage, VerifiedBundle};
use ym_settings::{AppDb, SettingsStore};
use ym_store::MediaStore;

use crate::cookie_session::{CookieSession, ENGINE_CACHE_DIR, HTTP_CACHE_DIR, SealedJar};
use crate::downloads::DownloadInputs;
use crate::youtube_session::{CandidateSource, YoutubeSessions};

const ENGINE_JS: &str = include_str!(concat!(env!("YMUSIC_V4_DIR"), "/engine-js/build/dist/engine.js"));
const PLAYER_JS: &str = include_str!(concat!(env!("YMUSIC_V4_DIR"), "/engine-js/build/dist/player.js"));
const ENGINE_JS_VERSION: &str = env!("YM_ENGINE_JS_VERSION");
const PLAYER_JS_VERSION: &str = env!("YM_PLAYER_JS_VERSION");
/// The JVM desktop app's versionCode line (build.rs): the update feed compares it, and the bundles
/// gate compatibility on it.
pub const APP_VERSION: i32 = version_code(env!("YM_VERSION_CODE"));
pub const APP_VERSION_NAME: &str = env!("YM_VERSION_NAME");
/// A bundle in use refreshes a config older than this.
const BUNDLE_CONFIG_MAX_AGE: Duration = Duration::from_secs(10 * 60);

pub struct AppGraph {
    pub engines: Arc<EngineManager>,
    pub tokio: Handle,
    pub log: Logger,
    /// One identity for scraping and playback.
    pub cookies: Arc<dyn CookieJar>,
    pub settings: Arc<dyn SettingsStore>,
    pub media: Arc<MediaStore>,
    pub remote: Arc<RemoteConfig>,
    /// Anonymous usage stats: `graph.stats.record(..)` from anywhere; the consent gate is inside.
    /// Only `app_start` is recorded today (as in the JVM app); holding it keeps its queue task alive.
    #[allow(dead_code)]
    pub stats: StatUsage,
    pub player_source: Arc<BundleSource>,
    /// A newer published player bundle, verified, for the next playback session; cached once it boots.
    pub pending_player: Arc<Mutex<Option<VerifiedBundle>>>,
    /// A throwaway launch: no sign-in, no account, no YouTube play history (Top tracks, Play again).
    pub guest: bool,
    /// The jar kept in app.db; `None` for a guest.
    pub sealed_jar: Option<Arc<SealedJar>>,
    /// Offer to seal the jar under a password after a sign-in.
    pub can_protect: bool,
    /// A guest's cache root, removed at quit.
    pub guest_cache: Option<PathBuf>,
    /// The YouTube player session playback and downloads share.
    pub youtube: Arc<YoutubeSessions>,
    /// The player's media cache (`player-cache`); `None` when it could not be opened.
    pub player_cache: Option<Arc<ym_ytmedia::cache::MediaCache>>,
    pub downloads: DownloadService,
    pub history: Arc<ym_history::PlayHistory>,
    pub sponsor_block: Arc<ym_history::sponsor::SponsorBlockStore>,
    /// The self-updater; `None` in a build without the update key.
    pub updates: Option<Arc<ym_update::UpdateService>>,
}

impl Global for AppGraph {}

fn boot_error(code: &'static str, what: &'static str) -> impl Fn(String) -> EngineError {
    move |error| EngineError::site(code, format!("{what}: {error}"))
}

impl AppGraph {
    pub async fn boot(
        tokio: Handle,
        log: Logger,
        http: HttpClients,
        data_dir: PathBuf,
        app_db: Arc<AppDb>,
        session: CookieSession,
    ) -> Result<Self, EngineError> {
        let (guest, can_protect, guest_cache) = (session.is_guest(), session.can_protect(), session.guest_cache());
        let cache_dir = session.cache_root.join(ENGINE_CACHE_DIR);
        let cache_log = log.clone();
        let cache = tokio::task::spawn_blocking(move || {
            DiskCacheStore::open(&cache_dir.join("engine-cache.db"), cache_dir.join("blobs"), cache_log)
        })
        .await
        .map_err(|error| boot_error("R90001", "engine cache")(error.to_string()))?
        .map_err(|error| boot_error("R90001", "engine cache")(error.to_string()))?;
        let http_cache = Arc::new(HttpCache::new(
            session.cache_root.join(HTTP_CACHE_DIR),
            DEFAULT_HTTP_CACHE_BYTES,
            log.fork("http-cache"),
        ));
        let cookies = session.jar;
        let device_id = crate::telemetry::device_id(app_db.as_ref());
        let settings: Arc<dyn SettingsStore> = app_db;
        let media_path = data_dir.join("media-store.db");
        let media = tokio::task::spawn_blocking(move || MediaStore::open(&media_path))
            .await
            .map_err(|error| boot_error("R90010", "media store")(error.to_string()))?
            .map_err(|error| boot_error("R90010", "media store")(error.to_string()))?;

        // Debug: `YMUSIC_DEV_REMOTE_CONFIG_URL` points the config service (and the update feed) at a
        // test server.
        let base_url = cfg!(debug_assertions)
            .then(|| std::env::var("YMUSIC_DEV_REMOTE_CONFIG_URL").ok())
            .flatten()
            .or_else(|| option_env!("YMUSIC_REMOTE_CONFIG_URL").map(str::to_owned));
        let identity = base_url.map(|base_url| ApiIdentity {
            base_url,
            api_key: option_env!("YMUSIC_API_KEY").map(str::to_owned),
            os: ym_update::os_key().to_owned(),
            os_version: crate::desktop::os_version(),
            arch: ym_update::arch_key().to_owned(),
            app_version_code: APP_VERSION,
            app_version_name: APP_VERSION_NAME.to_owned(),
            device_id,
        });
        let remote_file = data_dir.join("remote-config.json");
        let remote_client = http.following.clone();
        let remote_log = log.clone();
        let remote = Arc::new(
            tokio::task::spawn_blocking(move || RemoteConfig::open(identity, remote_client, remote_file, remote_log))
                .await
                .map_err(|error| boot_error("R90011", "remote config")(error.to_string()))?,
        );
        let stats_settings = settings.clone();
        let stats = StatUsage::start(
            remote.clone(),
            move || stats_settings.boolean(crate::telemetry::STATS_KEY, true).unwrap_or(false),
            data_dir.join("stat-queue.json"),
            &tokio,
            log.fork("stats"),
        );
        stats.on_app_start();
        let public_key = option_env!("YMUSIC_BUNDLE_PUBLIC_KEY").unwrap_or_default();
        let source = |name: &str, embedded: &'static str, version: &str| {
            BundleSource::new(
                name,
                public_key,
                (embedded, version.parse().unwrap_or(0)),
                data_dir.join(format!("{name}-js")),
                http.following.clone(),
                log.clone(),
            )
        };
        let engine_source = Arc::new(source("engine", ENGINE_JS, ENGINE_JS_VERSION));
        let player_source = Arc::new(source("player", PLAYER_JS, PLAYER_JS_VERSION));

        let features_remote = remote.clone();
        let template = EngineConfig {
            app_version: APP_VERSION,
            http: http.clone(),
            logger: log.fork("engine"),
            cookies: Some(cookies.clone()),
            settings: Some(settings.clone()),
            remote_features: Some(Arc::new(move |key| features_remote.current().feature(key).cloned())),
            cache: Some(cache.clone()),
            http_cache: Some(http_cache.clone()),
            mse: None,
            main: None,
            media_proxy: None,
        };
        let mut candidates = Vec::new();
        let cached_source = engine_source.clone();
        if let Ok(Some((script, version))) = tokio::task::spawn_blocking(move || cached_source.cached()).await {
            candidates.push((script, format!("remote v{version}")));
        }
        candidates.push((ENGINE_JS.to_owned(), format!("embedded v{ENGINE_JS_VERSION}")));
        let engines = Arc::new(EngineManager::start(candidates, template).await?);
        remote.set_running_bundles(Some(i64::from(engines.backend_version())), None);
        ym_telemetry::set_engine_version(engines.backend_version());

        let pending_player: Arc<Mutex<Option<VerifiedBundle>>> = Arc::new(Mutex::new(None));
        let media = Arc::new(media);
        let mut player_config = template_for("player", &http, &http_cache, &log, &cookies, &settings, &cache, &remote);
        player_config.main = Some({
            let engines = engines.clone();
            Arc::new(move || Some(engines.current().runtime().clone()))
        });
        let (cache_dir, cache_settings, cache_log) = (data_dir.clone(), settings.clone(), log.clone());
        let player_cache = match tokio::task::spawn_blocking(move || {
            crate::player_cache::open(&cache_dir, cache_settings.as_ref(), &cache_log)
        })
        .await
        {
            Ok(cache) => cache,
            Err(error) => {
                log.warn("R90650 player cache did not open, playback streams uncached", Some(&error));
                None
            }
        };
        player_config.media_proxy =
            player_cache.clone().map(|cache| crate::player_cache::proxy(cache, tokio.clone(), &log, remote.clone()));
        let candidates: CandidateSource = {
            let (pending, source) = (pending_player.clone(), player_source.clone());
            Arc::new(move || player_candidates(&pending, &source))
        };
        let youtube =
            Arc::new(YoutubeSessions::new(player_config, candidates, player_source.clone(), log.fork("youtube")));
        let download_inputs = DownloadInputs {
            tokio: tokio.clone(),
            data_dir: data_dir.clone(),
            http: http.clone(),
            cookies: cookies.clone(),
            settings: settings.clone(),
            media: media.clone(),
            sessions: youtube.clone(),
            player_cache: player_cache.clone(),
            log: log.clone(),
        };
        let downloads = tokio::task::spawn_blocking(move || crate::downloads::open(download_inputs))
            .await
            .map_err(|error| boot_error("R90015", "downloads")(error.to_string()))?
            .map_err(boot_error("R90015", "downloads"))?;

        let history_path = data_dir.join("play-history.db");
        let history = Arc::new(
            tokio::task::spawn_blocking(move || ym_history::PlayHistory::open(&history_path))
                .await
                .map_err(|error| boot_error("R90016", "play history")(error.to_string()))?
                .map_err(|error| boot_error("R90016", "play history")(error.to_string()))?,
        );
        let sponsor_path = data_dir.join("sponsor-block.db");
        let sponsor_block = Arc::new(
            tokio::task::spawn_blocking(move || ym_history::sponsor::SponsorBlockStore::open(&sponsor_path))
                .await
                .map_err(|error| boot_error("R90018", "sponsorblock store")(error.to_string()))?
                .map_err(|error| boot_error("R90018", "sponsorblock store")(error.to_string()))?,
        );

        let updates = ym_update::UpdateService::start(
            ym_update::UpdateConfig {
                client: http.following.clone(),
                public_key: option_env!("YMUSIC_UPDATE_PUBLIC_KEY").unwrap_or_default().to_owned(),
                current_version_code: APP_VERSION,
                platform: ym_update::platform_key(),
                download_dir: data_dir.join("update"),
                installer: ym_update::installer_for_current(crate::app_update::relaunch_env()),
                prefs: crate::app_update::prefs(settings.clone(), log.fork("update")),
                log: log.clone(),
            },
            remote.subscribe(),
            tokio.clone(),
        );

        let graph = Self {
            engines,
            tokio,
            log,
            cookies,
            settings,
            media,
            remote,
            stats,
            player_source,
            pending_player,
            guest,
            sealed_jar: session.sealed,
            can_protect,
            guest_cache,
            youtube,
            player_cache,
            downloads,
            history,
            sponsor_block,
            updates,
        };
        graph.follow_published_bundles(engine_source);
        Ok(graph)
    }

    /// The engine new work goes to; hold it for one job. Each job also refreshes a stale config,
    /// which brings a newer bundle or app release to a running app.
    pub fn engine(&self) -> Arc<DynamicEngine> {
        let remote = self.remote.clone();
        self.tokio.spawn(async move { remote.check_update(BUNDLE_CONFIG_MAX_AGE).await });
        self.engines.current()
    }

    /// Refreshes the config, then swaps in a newer published engine bundle (cached once it
    /// booted) and stages a newer player bundle for the next playback session.
    fn follow_published_bundles(&self, engine_source: Arc<BundleSource>) {
        let (remote, engines, player_source, pending) =
            (self.remote.clone(), self.engines.clone(), self.player_source.clone(), self.pending_player.clone());
        let log = self.log.fork("bundles");
        self.tokio.spawn(async move {
            let mut published = remote.subscribe();
            remote.check_update(BUNDLE_CONFIG_MAX_AGE).await;
            loop {
                let config = published.borrow_and_update().clone();
                if let Some(remote_engine) =
                    config.engine_js.filter(|bundle| bundle.version > i64::from(engines.backend_version()))
                {
                    match engine_source.download(&remote_engine).await {
                        Ok(bundle) => match engines.swap(bundle.source.clone()).await {
                            Ok(version) => {
                                let source = engine_source.clone();
                                let _ = tokio::task::spawn_blocking(move || source.store(&bundle)).await;
                                remote.set_running_bundles(Some(i64::from(version)), None);
                                ym_telemetry::set_engine_version(version);
                            }
                            Err(error) => log
                                .warn(&format!("R90012 engine v{} did not boot", remote_engine.version), Some(&error)),
                        },
                        Err(error) => {
                            log.warn(&format!("R90012 engine v{} not taken", remote_engine.version), Some(&error))
                        }
                    }
                }
                let embedded_player = player_source.embedded().1;
                if let Some(remote_player) = config.player_js.filter(|bundle| bundle.version > embedded_player) {
                    match player_source.download(&remote_player).await {
                        Ok(bundle) => *pending.lock().unwrap() = Some(bundle),
                        Err(error) => {
                            log.warn(&format!("R90012 player v{} not taken", remote_player.version), Some(&error))
                        }
                    }
                }
                if published.changed().await.is_err() {
                    return;
                }
            }
        });
    }
}

/// The player bundles to try, best first: a staged newer one, the cached one, the embedded one.
fn player_candidates(
    pending: &Mutex<Option<VerifiedBundle>>,
    source: &BundleSource,
) -> Vec<(String, Option<VerifiedBundle>)> {
    let mut candidates = Vec::new();
    if let Some(bundle) = pending.lock().unwrap().take() {
        candidates.push((bundle.source.clone(), Some(bundle)));
    }
    if let Some((script, _)) = source.cached() {
        candidates.push((script, None));
    }
    candidates.push((PLAYER_JS.to_owned(), None));
    candidates
}

/// An engine config sharing the app's HTTP (and its disk cache), cookies, settings, cache and remote features.
#[allow(clippy::too_many_arguments)]
fn template_for(
    tag: &str,
    http: &HttpClients,
    http_cache: &Arc<HttpCache>,
    log: &Logger,
    cookies: &Arc<dyn CookieJar>,
    settings: &Arc<dyn SettingsStore>,
    cache: &Arc<DiskCacheStore>,
    remote: &Arc<RemoteConfig>,
) -> EngineConfig {
    let remote = remote.clone();
    EngineConfig {
        app_version: APP_VERSION,
        http: http.clone(),
        logger: log.fork(tag),
        cookies: Some(cookies.clone()),
        settings: Some(settings.clone()),
        remote_features: Some(Arc::new(move |key| remote.current().feature(key).cloned())),
        cache: Some(cache.clone()),
        http_cache: Some(http_cache.clone()),
        mse: None,
        main: None,
        media_proxy: None,
    }
}

const fn version_code(digits: &str) -> i32 {
    let (bytes, mut value, mut index) = (digits.as_bytes(), 0, 0);
    while index < bytes.len() {
        value = value * 10 + (bytes[index] - b'0') as i32;
        index += 1;
    }
    value
}

/// Opens app.db; blocking.
pub fn open_app_db(data_dir: &std::path::Path) -> Result<Arc<AppDb>, EngineError> {
    AppDb::open(&data_dir.join("app.db"))
        .map(Arc::new)
        .map_err(|error| boot_error("R90005", "app.db")(error.to_string()))
}

/// Runs a core future on the tokio runtime and resolves on any executor (gpui's included).
/// `None` when the runtime is shutting down.
pub fn on_core<F>(cx: &App, future: F) -> impl Future<Output = Option<F::Output>> + use<F>
where
    F: Future + Send + 'static,
    F::Output: Send + 'static,
{
    let handle = cx.global::<AppGraph>().tokio.spawn(future);
    async move { handle.await.ok() }
}
