//! Owns the current main engine and replaces it with a newer bundle without a restart: the new
//! engine boots and passes its health check on its own runtime while the old one keeps serving,
//! then new work goes to the new one and the old one is retired.

use std::sync::{Arc, RwLock};
use std::time::Duration;

use ym_common::log::Logger;

use crate::engine::{DynamicEngine, EngineConfig};
use crate::error::EngineError;

/// How long a retired engine gets to finish its in-flight work before it is closed regardless.
const DRAIN_DEADLINE: Duration = Duration::from_secs(10);
/// How long an untrusted (remote) bundle gets to boot and answer its health check (the JVM
/// DynamicEngineManager's remoteBootTimeoutMs).
const REMOTE_BOOT_DEADLINE: Duration = Duration::from_secs(10);

pub struct EngineManager {
    active: RwLock<(Arc<DynamicEngine>, i32)>,
    template: EngineConfig,
    swap_lock: tokio::sync::Mutex<()>,
    log: Logger,
}

impl EngineManager {
    /// Boots the first candidate (`(script, label)`, best first) that passes its health check. Every
    /// candidate but the last (the embedded bundle) is untrusted and boots under a deadline.
    pub async fn start(candidates: Vec<(String, String)>, template: EngineConfig) -> Result<Self, EngineError> {
        let log = template.logger.fork("engine-manager");
        let mut last_error = None;
        let trusted_at = candidates.len().saturating_sub(1);
        for (position, (script, label)) in candidates.into_iter().enumerate() {
            let boot = if position == trusted_at {
                Self::boot(script, template.clone()).await
            } else {
                Self::boot_untrusted(script, template.clone()).await
            };
            match boot {
                Ok((engine, version)) => {
                    log.info(&format!("engine {label} running (backend {version})"));
                    return Ok(Self {
                        active: RwLock::new((engine, version)),
                        template,
                        swap_lock: tokio::sync::Mutex::new(()),
                        log,
                    });
                }
                Err(error) => {
                    log.warn(&format!("engine {label} did not boot, trying the next"), Some(&error));
                    last_error = Some(error);
                }
            }
        }
        Err(last_error.unwrap_or_else(|| EngineError::site("R20022", "no engine bundle to boot")))
    }

    async fn boot(script: String, config: EngineConfig) -> Result<(Arc<DynamicEngine>, i32), EngineError> {
        let engine = DynamicEngine::create(script, config).await?;
        match engine.health_check().await {
            Ok(version) => Ok((Arc::new(engine), version)),
            Err(error) => {
                engine.close().await;
                Err(error)
            }
        }
    }

    /// Boots a remote bundle on its own task and stops waiting at [`REMOTE_BOOT_DEADLINE`]: a bundle
    /// stuck in its eval never reaches a point a timeout could interrupt, so the boot runs on and
    /// closes its engine itself if it finishes after the caller gave up (the JVM
    /// createEngineWithDeadline).
    async fn boot_untrusted(script: String, config: EngineConfig) -> Result<(Arc<DynamicEngine>, i32), EngineError> {
        let (sender, receiver) = tokio::sync::oneshot::channel();
        tokio::spawn(async move {
            if let Err(Ok((engine, _))) = sender.send(Self::boot(script, config).await) {
                engine.close().await;
            }
        });
        match tokio::time::timeout(REMOTE_BOOT_DEADLINE, receiver).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(EngineError::site("R20025", "the remote bundle boot task ended without a result")),
            Err(_) => {
                Err(EngineError::site("R20025", format!("remote bundle boot timed out after {REMOTE_BOOT_DEADLINE:?}")))
            }
        }
    }

    /// The engine new work goes to. Hold the clone only for one job: a retired engine closes once
    /// its drain deadline passes.
    pub fn current(&self) -> Arc<DynamicEngine> {
        self.active.read().unwrap().0.clone()
    }

    pub fn backend_version(&self) -> i32 {
        self.active.read().unwrap().1
    }

    /// Boots `script` and, when it is healthy, makes it current and retires the old engine in the
    /// background. A bundle that fails changes nothing.
    pub async fn swap(&self, script: String) -> Result<i32, EngineError> {
        let _serialized = self.swap_lock.lock().await;
        let (engine, version) = Self::boot_untrusted(script, self.template.clone()).await?;
        let (retired, old_version) = std::mem::replace(&mut *self.active.write().unwrap(), (engine, version));
        self.log.info(&format!("engine swapped: backend {old_version} -> {version}"));
        let log = self.log.clone();
        tokio::spawn(async move {
            let drained = tokio::time::timeout(DRAIN_DEADLINE, async {
                retired.signal_shutdown().await;
                retired.runtime().await_idle().await;
            })
            .await
            .is_ok();
            if !drained {
                log.warn(
                    &format!("retired engine {old_version} did not drain in {DRAIN_DEADLINE:?}, force-closing"),
                    None,
                );
            }
            retired.close().await;
        });
        Ok(version)
    }
}
