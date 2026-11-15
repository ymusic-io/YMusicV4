//! The one YouTube player session (player.js over the native MSE host), booted on first use by
//! playback or a download and kept for the app's lifetime.

use std::sync::Arc;
use std::time::Duration;

use tokio::runtime::Handle;
use ym_common::log::{Level, Logger};
use ym_engine::{DynamicEngine, EngineConfig, EngineError, MseAttachment};
use ym_mse::MseHost;
use ym_remote_config::{BundleSource, VerifiedBundle};

/// The engine closes before its host: JS calls straight into the C host.
pub struct YoutubeSession {
    pub engine: Arc<DynamicEngine>,
    pub host: Arc<MseHost>,
}

pub type CandidateSource = Arc<dyn Fn() -> Vec<(String, Option<VerifiedBundle>)> + Send + Sync>;

/// How long quit waits for the player engine to free its JS heap.
const QUIT_CLOSE_WAIT: Duration = Duration::from_secs(2);

pub struct YoutubeSessions {
    session: tokio::sync::Mutex<Option<Arc<YoutubeSession>>>,
    config: EngineConfig,
    candidates: CandidateSource,
    player_source: Arc<BundleSource>,
    log: Logger,
}

impl YoutubeSessions {
    pub fn new(
        config: EngineConfig,
        candidates: CandidateSource,
        player_source: Arc<BundleSource>,
        log: Logger,
    ) -> Self {
        Self { session: tokio::sync::Mutex::new(None), config, candidates, player_source, log }
    }

    /// The session, booting it on the first call; concurrent callers share one boot, and a failed
    /// boot is retried by the next call.
    pub async fn get(&self) -> Result<Arc<YoutubeSession>, EngineError> {
        let mut slot = self.session.lock().await;
        if let Some(session) = slot.as_ref() {
            return Ok(session.clone());
        }
        let session = Arc::new(self.boot().await?);
        *slot = Some(session.clone());
        Ok(session)
    }

    /// For app quit, from the UI thread: stops the session's JS, waiting up to [`QUIT_CLOSE_WAIT`],
    /// so the host the app drops next takes its decoders down before the process exits.
    pub fn close_for_quit(self: Arc<Self>, tokio: &Handle) {
        let (done, closed) = std::sync::mpsc::channel();
        let sessions = self.clone();
        tokio.spawn(async move {
            let session = sessions.session.lock().await.clone();
            if let Some(session) = session {
                session.engine.close().await;
            }
            let _ = done.send(());
        });
        if closed.recv_timeout(QUIT_CLOSE_WAIT).is_err() {
            self.log.warn("the player engine did not close in time at quit", None);
        }
    }

    async fn boot(&self) -> Result<YoutubeSession, EngineError> {
        let host = Arc::new(MseHost::create().ok_or_else(|| EngineError::site("R90002", "mse_host_create failed"))?);
        let mut config = self.config.clone();
        let (raw, vtable) = host.attach_pointers();
        config.mse = Some(MseAttachment { host: raw, vtable, owner: host.clone() });
        let mse_log = self.log.fork("mse");
        ym_mse::set_log_sink(move |level, message| {
            mse_log.log(if level >= 2 { Level::Warn } else { Level::Info }, message, None);
        });
        // A published bundle that will not boot must not take playback down: the next one boots.
        let mut last_error = None;
        for (script, staged) in (self.candidates)() {
            let engine = match DynamicEngine::create(script, config.clone()).await {
                Ok(engine) => Arc::new(engine),
                Err(error) => {
                    self.log.warn("a player bundle did not load", Some(&error));
                    last_error = Some(error);
                    continue;
                }
            };
            match engine.health_check().await {
                Ok(version) => ym_telemetry::set_player_version(version),
                Err(error) => {
                    self.log.warn("a player bundle failed its health check", Some(&error));
                    engine.close().await;
                    last_error = Some(error);
                    continue;
                }
            }
            if let Some(bundle) = staged {
                let source = self.player_source.clone();
                if let Ok(Err(error)) = tokio::task::spawn_blocking(move || source.store(&bundle)).await {
                    self.log.warn("the booted player bundle could not be cached", Some(&error));
                }
            }
            let weak = engine.runtime().downgrade();
            ym_mse::set_event_sink(move |name| {
                if let Some(runtime) = weak.upgrade() {
                    runtime.fire_event(name);
                }
            });
            engine.player_init(None, false, &self.log).await?;
            return Ok(YoutubeSession { engine, host });
        }
        Err(last_error.unwrap_or_else(|| EngineError::site("R90002", "no player bundle booted")))
    }
}
