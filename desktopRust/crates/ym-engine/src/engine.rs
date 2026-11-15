use std::any::Any;
use std::sync::{Arc, OnceLock};

use serde::Deserialize;
use ym_common::http::HttpClients;
use ym_common::log::{Level, Logger};
use ym_quickjs::{LogHandler, LogLevel, PipeError, QuickJsRuntime};
use ym_settings::SettingsStore;

use crate::cache::DiskCacheStore;
use crate::cookie::CookieJar;
use crate::error::EngineError;
use crate::http_cache::HttpCache;
use crate::logs::ParkedLoggers;
use crate::media_proxy::MediaProxy;
use crate::settings::FeatureLookup;

pub type MainResolver = Arc<dyn Fn() -> Option<QuickJsRuntime> + Send + Sync>;

/// A capability left `None` is not served: the bundle's open of it is refused.
#[derive(Clone)]
pub struct EngineConfig {
    /// The `versions.app` the bundle gates compatibility on: the desktop versionCode sequence.
    pub app_version: i32,
    pub http: HttpClients,
    pub logger: Logger,
    pub cookies: Option<Arc<dyn CookieJar>>,
    pub settings: Option<Arc<dyn SettingsStore>>,
    pub remote_features: Option<FeatureLookup>,
    pub cache: Option<Arc<DiskCacheStore>>,
    /// The `http` protocol's disk cache, one per session folder, shared by every engine.
    pub http_cache: Option<Arc<HttpCache>>,
    /// The player bundle's native MSE host, attached before the eval.
    pub mse: Option<MseAttachment>,
    /// Resolves the main engine at each call, for the player bundle's `host/main/*` proxy (so it
    /// follows engine swaps).
    pub main: Option<MainResolver>,
    /// Serves the bundle's tagged media requests (the player cache); `None` sends them to the
    /// network with the tag removed.
    pub media_proxy: Option<Arc<dyn MediaProxy>>,
}

/// A native MSE host and its `__mse*` vtable, for the player bundle's natives.
#[derive(Clone)]
pub struct MseAttachment {
    pub host: usize,
    pub vtable: usize,
    /// Keeps `host` and `vtable` valid; the engine holds it until its C engine is freed.
    pub owner: Arc<dyn Any + Send + Sync>,
}

/// One booted `engine.js` and the host protocols it is served.
pub struct DynamicEngine {
    runtime: QuickJsRuntime,
    parked: Arc<ParkedLoggers>,
    backend_version: OnceLock<i32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InitHead {
    backend_version: i32,
}

impl DynamicEngine {
    /// Boots `script` with every host protocol wired before the eval, so the bundle's top-level
    /// code can use them. Run [`Self::health_check`] afterwards to gate on the bundle.
    pub async fn create(script: String, config: EngineConfig) -> Result<Self, EngineError> {
        let console = config.logger.fork("js");
        let on_log: LogHandler = Arc::new(move |level, message| {
            let level = match level {
                LogLevel::Debug => Level::Debug,
                LogLevel::Info => Level::Info,
                LogLevel::Warn | LogLevel::Rejection => Level::Warn,
                LogLevel::Error => Level::Error,
            };
            console.log(level, message, None);
        });
        let runtime = QuickJsRuntime::new(config.app_version, None, Some(on_log)).map_err(load_failure)?;
        let parked = Arc::new(ParkedLoggers::new(config.logger));
        crate::http::install(&runtime, config.http, config.http_cache, config.media_proxy, parked.clone());
        if let Some(jar) = config.cookies {
            crate::cookie::install(&runtime, jar, parked.clone());
        }
        if let Some(store) = config.settings {
            crate::settings::install(&runtime, store, parked.clone());
        }
        if let Some(lookup) = config.remote_features {
            crate::settings::install_remote_config(&runtime, lookup, parked.clone());
        }
        crate::cache_protocol::install_capture(&runtime, config.cache.clone(), parked.clone());
        if let Some(store) = config.cache {
            crate::cache_protocol::install(&runtime, store, parked.clone());
        }
        if let Some(main) = config.main {
            crate::main_proxy::install(&runtime, main, parked.clone());
        }
        if let Some(mse) = config.mse {
            // SAFETY: `owner` keeps the host and vtable valid (MseAttachment::owner).
            unsafe { runtime.attach_mse_host(mse.host, mse.vtable, mse.owner) }.await.map_err(load_failure)?;
        }
        if let Err(error) = runtime.eval_engine(script).await {
            runtime.close().await;
            return Err(load_failure(error));
        }
        Ok(Self { runtime, parked, backend_version: OnceLock::new() })
    }

    /// Drives the bundle's `"init"`, proving open routing, handler dispatch, JSON encode and END,
    /// and records the bundle's self-declared revision. A RESET keeps the bundle's own code.
    pub async fn health_check(&self) -> Result<i32, EngineError> {
        let body = self.runtime.open_read_now("init").await.map_err(|error| match error {
            PipeError::Reset(reason) => EngineError::decode_reset(&reason),
            other => EngineError::site("R20004", format!("engine init failed: {other}")),
        })?;
        let head: InitHead = serde_json::from_str(&body).map_err(|_| {
            let preview: String = body.chars().take(200).collect();
            EngineError::site("R20004", format!("engine init returned a malformed head: {preview}"))
        })?;
        let _ = self.backend_version.set(head.backend_version);
        Ok(head.backend_version)
    }

    /// The bundle's revision; `None` until [`Self::health_check`] ran.
    pub fn backend_version(&self) -> Option<i32> {
        self.backend_version.get().copied()
    }

    pub fn parked_loggers(&self) -> &ParkedLoggers {
        &self.parked
    }

    pub fn runtime(&self) -> &QuickJsRuntime {
        &self.runtime
    }

    /// SIGINT as a pipe: the bundle stops its repeat work and ENDs; a bundle without the lifecycle
    /// module refuses the open, which the drain deadline backstops.
    pub async fn signal_shutdown(&self) {
        let Ok(pipe) = self.runtime.open_pipe("shutdown").await else { return };
        let _ = pipe.close_write(None).await;
        let _ = pipe.read_to_end().await;
        let _ = pipe.close_read().await;
    }

    pub async fn close(&self) {
        self.runtime.close().await;
    }
}

fn load_failure(error: PipeError) -> EngineError {
    EngineError::site("R20003", format!("engine load failed: {error}"))
}
