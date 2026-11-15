//! The one YouTube player session (player.js over the native MSE host), booted on first use by
//! playback or a download, kept until playback resets it (the JVM MsePlayer.reset).

use std::sync::Arc;
use std::time::Duration;

use tokio::runtime::Handle;
use ym_common::log::{Level, Logger};
use ym_engine::{DynamicEngine, EngineConfig, EngineError, MseAttachment};
use ym_mse::MseHost;
use ym_remote_config::{BundleSource, BundleVersion, VerifiedBundle};

/// The engine closes before its host: JS calls straight into the C host.
pub struct YoutubeSession {
    pub engine: Arc<DynamicEngine>,
    pub host: Arc<MseHost>,
}

pub type CandidateSource = Arc<dyn Fn() -> Vec<(String, Option<VerifiedBundle>)> + Send + Sync>;
/// Told the version of each player bundle that boots.
pub type VersionSink = Arc<dyn Fn(i64) + Send + Sync>;
/// Refreshes a stale config on each use of the session (the JVM MsePlayer's playerJs.checkUpdate).
pub type ConfigCheck = Arc<dyn Fn() + Send + Sync>;
/// Told each staged (newly downloaded) player bundle that would not boot: it is not downloaded again.
pub type RejectSink = Arc<dyn Fn(BundleVersion) + Send + Sync>;

/// How long player/init gets before the boot fails (the JVM MsePlayer's INIT_TIMEOUT_MS).
const PLAYER_INIT_DEADLINE: Duration = Duration::from_secs(30);

/// How long quit waits for the player engine to free its JS heap.
const QUIT_CLOSE_WAIT: Duration = Duration::from_secs(2);

pub struct YoutubeSessions {
    session: tokio::sync::Mutex<Option<Arc<YoutubeSession>>>,
    /// The next boot fetches the player config anew (set by `reset`).
    fresh_next: std::sync::atomic::AtomicBool,
    config: EngineConfig,
    candidates: CandidateSource,
    player_source: Arc<BundleSource>,
    player_running: VersionSink,
    check_config: ConfigCheck,
    reject_staged: RejectSink,
    log: Logger,
}

impl YoutubeSessions {
    pub fn new(
        config: EngineConfig,
        candidates: CandidateSource,
        player_source: Arc<BundleSource>,
        player_running: VersionSink,
        check_config: ConfigCheck,
        reject_staged: RejectSink,
        log: Logger,
    ) -> Self {
        Self {
            session: tokio::sync::Mutex::new(None),
            fresh_next: std::sync::atomic::AtomicBool::new(false),
            config,
            candidates,
            player_source,
            player_running,
            check_config,
            reject_staged,
            log,
        }
    }

    /// Closes the session; the next `get` boots a new one with a freshly fetched config and the
    /// newest player bundle (the JVM MsePlayer.reset: tearDownSession, then startSession(fresh)).
    pub async fn reset(&self) {
        let old = self.session.lock().await.take();
        self.fresh_next.store(true, std::sync::atomic::Ordering::Relaxed);
        if let Some(session) = old {
            session.engine.close().await;
        }
    }

    /// The session, booting it on the first call; concurrent callers share one boot, and a failed
    /// boot is retried by the next call.
    pub async fn get(&self) -> Result<Arc<YoutubeSession>, EngineError> {
        (self.check_config)();
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
        let candidates = (self.candidates)();
        let embedded_at = candidates.len().saturating_sub(1);
        let mut last_error = None;
        for (position, (script, staged)) in candidates.into_iter().enumerate() {
            let booted = match DynamicEngine::create(script, config.clone()).await {
                Ok(engine) => {
                    let engine = Arc::new(engine);
                    match engine.health_check().await {
                        Ok(version) => Ok((engine, version)),
                        Err(error) => {
                            engine.close().await;
                            Err(error)
                        }
                    }
                }
                Err(error) => Err(error),
            };
            let engine = match booted {
                Ok((engine, version)) => {
                    ym_telemetry::set_player_version(version);
                    (self.player_running)(i64::from(version));
                    engine
                }
                Err(error) => {
                    if position == embedded_at {
                        self.log.warn("the embedded player bundle did not boot", Some(&error));
                    } else if let Some(bundle) = staged {
                        self.log.capture_error(
                            &format!("player.js v{} failed to boot, discarded", bundle.remote.version),
                            &error,
                        );
                        (self.reject_staged)(bundle.remote);
                    } else {
                        self.log.capture_error("the cached player.js failed to boot, using the next one", &error);
                    }
                    last_error = Some(error);
                    continue;
                }
            };
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
            let fresh = self.fresh_next.load(std::sync::atomic::Ordering::Relaxed);
            match tokio::time::timeout(PLAYER_INIT_DEADLINE, engine.player_init(None, fresh, &self.log)).await {
                Ok(result) => {
                    result?;
                }
                Err(_) => {
                    engine.close().await;
                    return Err(EngineError::site(
                        "R90033",
                        format!("player/init not done within {PLAYER_INIT_DEADLINE:?}"),
                    ));
                }
            }
            self.fresh_next.store(false, std::sync::atomic::Ordering::Relaxed);
            return Ok(YoutubeSession { engine, host });
        }
        Err(last_error.unwrap_or_else(|| EngineError::site("R90002", "no player bundle booted")))
    }
}
