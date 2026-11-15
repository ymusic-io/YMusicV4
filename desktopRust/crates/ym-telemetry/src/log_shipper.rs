//! The app-log lines an error event takes, as Sentry Logs (the JVM app's `ErrorLogShipper`): an
//! event claims its launch's newest unsent lines, which wait in `logs.db` as pending, and one worker
//! sends every pending line, this launch's or one an earlier run claimed before it died. Each goes
//! with its body, level, timestamp, `app.version_code` and its launch as trace id, which the events
//! carry too, so an event and its run's lines share one trace.

use std::sync::{Arc, Mutex, OnceLock, PoisonError};
use std::time::{Duration, UNIX_EPOCH};

use sentry::protocol::{Envelope, ItemContainer, Log, LogAttribute, LogLevel, TraceId};
use sentry::types::{Dsn, ParseDsnError};
use tokio::sync::Notify;
use ym_common::log::Logger;
use ym_logstore::{LogRecord, LogStore};

const BATCH: i64 = 100;
/// As the JVM app caps a claim.
const MAX_LINES: i64 = 10_000;
const DELIVERY_TIMEOUT: Duration = Duration::from_secs(60);
const RETRY_MIN: Duration = Duration::from_secs(60);
const RETRY_MAX: Duration = Duration::from_secs(30 * 60);
const CLIENT: &str = concat!("ymusic-rust/", env!("CARGO_PKG_VERSION"));

/// This launch's log id as a trace id, which every event carries.
static LAUNCH_TRACE: OnceLock<TraceId> = OnceLock::new();

pub(crate) fn launch_trace() -> Option<TraceId> {
    LAUNCH_TRACE.get().copied()
}

pub struct LogShipper {
    store: Arc<LogStore>,
    http: reqwest::Client,
    url: String,
    auth: String,
    version_code: i32,
    /// The largest claim asked for since the worker last claimed.
    requested: Mutex<Option<i64>>,
    wake: Notify,
    log: Logger,
}

enum Delivery {
    Delivered,
    /// Sentry answered but took nothing (rate limit, rejected batch): sent again it only adds load.
    Refused(String),
    /// No answer: the batch stays pending.
    Unsent(reqwest::Error),
}

impl LogShipper {
    /// Starts the worker on `runtime`; its first pass sends what earlier runs left pending.
    pub fn start(
        store: Arc<LogStore>,
        dsn: &str,
        version_code: i32,
        http: reqwest::Client,
        runtime: &tokio::runtime::Handle,
        log: Logger,
    ) -> Result<Arc<Self>, ParseDsnError> {
        let dsn: Dsn = dsn.parse()?;
        // The launch is 32 hex digits by construction; a second start keeps the first's trace.
        if let Ok(trace) = store.launch.parse() {
            let _ = LAUNCH_TRACE.set(trace);
        }
        let shipper = Arc::new(Self {
            store,
            http,
            url: dsn.envelope_api_url().to_string(),
            auth: dsn.to_auth(Some(CLIENT)).to_string(),
            version_code,
            requested: Mutex::new(None),
            wake: Notify::new(),
            log,
        });
        runtime.spawn(shipper.clone().run());
        Ok(shipper)
    }

    /// An error event's claim, off the caller's thread: the worker claims this launch's newest `limit`
    /// unsent lines, the event's own among them, and sends them.
    pub fn claim(&self, limit: i64) {
        let limit = limit.min(MAX_LINES);
        {
            let mut requested = self.requested.lock().unwrap_or_else(PoisonError::into_inner);
            *requested = Some(requested.map_or(limit, |earlier| earlier.max(limit)));
        }
        self.wake.notify_one();
    }

    /// A panic's claim, made before the hook returns since the process may not outlive it: the lines
    /// go with the worker's next pass, or with the next start's.
    pub fn claim_now(&self, limit: i64, wait: Duration) {
        // A panic hook has nowhere to report a failed claim: the panic goes without its lines.
        if let Some(Ok(_)) = self.store.try_claim_unsent(limit.min(MAX_LINES), wait) {
            self.wake.notify_one();
        }
    }

    async fn run(self: Arc<Self>) {
        let mut retry = RETRY_MIN;
        loop {
            let requested = self.requested.lock().unwrap_or_else(PoisonError::into_inner).take();
            if let Some(limit) = requested {
                self.claim_requested(limit).await;
            }
            if self.send_pending().await {
                retry = RETRY_MIN;
                self.wake.notified().await;
            } else {
                // Woken by a new claim or timed out, the next pass retries.
                tokio::time::timeout(retry, self.wake.notified()).await.ok();
                retry = (retry * 2).min(RETRY_MAX);
            }
        }
    }

    async fn claim_requested(&self, limit: i64) {
        let store = self.store.clone();
        let claimed = tokio::task::spawn_blocking(move || store.claim_unsent(limit)).await;
        match claimed {
            Ok(Ok(_)) => {}
            Ok(Err(error)) => self.log.warn("R90026 log lines for an error event not claimed", Some(&error)),
            Err(error) => self.log.warn("R90026 log lines for an error event not claimed", Some(&error)),
        }
    }

    /// Sends the pending lines a batch at a time, each marked sent once Sentry answered for it;
    /// `false` when a batch got no answer and the pass is to be retried. While crash reports are off
    /// the lines wait.
    async fn send_pending(&self) -> bool {
        if !crate::reports_allowed() {
            return true;
        }
        let mut after_seq = 0;
        loop {
            let store = self.store.clone();
            let batch = match tokio::task::spawn_blocking(move || store.pending(BATCH, after_seq)).await {
                Ok(Ok(batch)) => batch,
                Ok(Err(error)) => {
                    self.log.warn("R90027 pending log lines not read", Some(&error));
                    return true;
                }
                Err(error) => {
                    self.log.warn("R90027 pending log lines not read", Some(&error));
                    return true;
                }
            };
            let Some(last) = batch.last().map(|line| line.seq) else { return true };
            match self.post(&batch).await {
                Delivery::Delivered => {}
                Delivery::Refused(reason) => {
                    self.log.warn(&format!("R90028 Sentry refused {} log lines, dropped: {reason}", batch.len()), None);
                }
                Delivery::Unsent(error) => {
                    self.log.info(&format!("{} log lines not delivered, retried later: {error}", batch.len()));
                    return false;
                }
            }
            let seqs: Vec<i64> = batch.iter().map(|line| line.seq).collect();
            let store = self.store.clone();
            match tokio::task::spawn_blocking(move || store.mark_sent(&seqs)).await {
                Ok(Ok(())) => {}
                Ok(Err(error)) => {
                    self.log.warn("R90029 sent log lines not marked sent", Some(&error));
                    return true;
                }
                Err(error) => {
                    self.log.warn("R90029 sent log lines not marked sent", Some(&error));
                    return true;
                }
            }
            after_seq = last;
        }
    }

    async fn post(&self, batch: &[LogRecord]) -> Delivery {
        let logs: Vec<Log> = batch.iter().map(|line| self.to_log(line)).collect();
        // Envelope::to_writer serializes the whole envelope; a batch is at most BATCH lines.
        let mut body = Vec::new();
        if let Err(error) = Envelope::from(ItemContainer::Logs(logs)).to_writer(&mut body) {
            return Delivery::Refused(error.to_string());
        }
        let request = self
            .http
            .post(&self.url)
            .header("X-Sentry-Auth", &self.auth)
            .header(reqwest::header::CONTENT_TYPE, "application/x-sentry-envelope")
            .timeout(DELIVERY_TIMEOUT)
            .body(body);
        match request.send().await {
            Ok(response) if response.status().is_success() => Delivery::Delivered,
            Ok(response) => Delivery::Refused(format!("HTTP {}", response.status())),
            Err(error) => Delivery::Unsent(error),
        }
    }

    /// Sentry bills logs by the byte: nothing rides per line that Sentry fills in itself (release,
    /// environment, SDK) or that the JVM app's lines do not carry.
    fn to_log(&self, line: &LogRecord) -> Log {
        Log {
            level: match line.level {
                0 => LogLevel::Debug,
                1 => LogLevel::Info,
                2 => LogLevel::Warn,
                _ => LogLevel::Error,
            },
            body: line.message.clone(),
            trace_id: line.launch.parse().ok(),
            timestamp: UNIX_EPOCH + Duration::from_millis(line.epoch_ms.max(0) as u64),
            severity_number: None,
            attributes: [("app.version_code".to_owned(), LogAttribute::from(self.version_code))].into(),
        }
    }
}
