//! The app log and crash reporting, set up at launch and handed the app graph once it booted.

use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use tokio::runtime::Handle;
use ym_common::log::{DeferredSink, Level, LogSink, Logger, StderrSink, TeeSink};
use ym_logstore::{LogStore, LogStoreSink};
use ym_remote_config::{RemoteConfig, RemoteConfigData};
use ym_settings::SettingsStore;
use ym_telemetry::{FeedbackReport, LogShipper, ReportPolicy, SentrySink, Telemetry, TelemetryConfig};

use crate::graph::AppGraph;

/// Shared with the JVM app: nothing is sent unless this is on.
pub const CRASH_REPORTS_KEY: &str = "telemetry.crashReports";
/// Shared with the JVM app: "Send anonymous stats"; nothing is recorded or sent while off.
pub const STATS_KEY: &str = "telemetry.stats";
/// Shared with the JVM app: the anonymous id the config service counts devices by.
const DEVICE_ID_KEY: &str = "telemetry.deviceId";
/// Debug builds: the feedback report's envelopes go to this file instead of Sentry.
const FEEDBACK_DRY_RUN_ENV: &str = "YMUSIC_DEV_FEEDBACK_ENVELOPE";
const FEEDBACK_EXPORT_TIMEOUT: Duration = Duration::from_secs(15);

/// The app log, for the feedback report.
pub struct AppLogs(pub Option<Arc<LogStore>>);

impl gpui_kit::Global for AppLogs {}

fn sentry_dsn() -> &'static str {
    option_env!("YMUSIC_SENTRY_DSN").unwrap_or_default()
}

/// A shipped build's Sentry environment: the `YMUSIC_SENTRY_ENVIRONMENT` build input (the JVM app's
/// `-PsentryEnvironment`, "beta" for a prerelease), else production.
fn sentry_environment() -> &'static str {
    option_env!("YMUSIC_SENTRY_ENVIRONMENT").filter(|environment| !environment.is_empty()).unwrap_or("production")
}

fn telemetry_config(dsn: &str, settings: &dyn SettingsStore) -> TelemetryConfig {
    TelemetryConfig {
        dsn: dsn.to_owned(),
        release: format!("ymusic-desktop@{}+{}", crate::graph::APP_VERSION_NAME, env!("YM_GIT_COMMIT")),
        environment: if cfg!(debug_assertions) { "debug" } else { sentry_environment() }.to_owned(),
        device_id: settings.string(DEVICE_ID_KEY, "").unwrap_or_default(),
    }
}

pub fn crash_reports_allowed(settings: &dyn SettingsStore) -> bool {
    settings.boolean(CRASH_REPORTS_KEY, true).unwrap_or(false)
}

fn feedback_dry_run() -> Option<PathBuf> {
    cfg!(debug_assertions).then(|| std::env::var_os(FEEDBACK_DRY_RUN_ENV)).flatten().map(PathBuf::from)
}

/// Feedback can go out: this build has a DSN (or a debug dry run is set).
pub fn feedback_available() -> bool {
    !sentry_dsn().is_empty() || feedback_dry_run().is_some()
}

/// Sends the whole app log with `subject` (and the user's `message`) as one anonymous Sentry report,
/// found in triage by the device id; without the log when its export fails or stalls.
pub async fn send_feedback(
    logs: Option<Arc<LogStore>>,
    settings: Arc<dyn SettingsStore>,
    subject: String,
    message: Option<String>,
    log: Logger,
) -> Result<(), ym_telemetry::FeedbackError> {
    let log_gzip = match logs {
        Some(logs) => {
            let export = tokio::task::spawn_blocking(move || {
                let mut gzip = Vec::new();
                logs.export_gzip(&mut gzip).map(|()| gzip)
            });
            match tokio::time::timeout(FEEDBACK_EXPORT_TIMEOUT, export).await {
                Ok(Ok(Ok(gzip))) => Some(gzip),
                Ok(Ok(Err(error))) => {
                    log.warn("log export for feedback failed", Some(&error));
                    None
                }
                Ok(Err(error)) => {
                    log.warn("log export for feedback failed", Some(&error));
                    None
                }
                Err(_) => {
                    log.warn("log export for feedback timed out", None);
                    None
                }
            }
        }
        None => None,
    };
    let dry_run = feedback_dry_run();
    let dsn = if dry_run.is_some() { "https://dryrun@localhost/0" } else { sentry_dsn() };
    let config = telemetry_config(dsn, settings.as_ref());
    let report = FeedbackReport { subject, message, log_gzip };
    tokio::task::spawn_blocking(move || ym_telemetry::send_feedback(&config, report, dry_run).map(|_| ()))
        .await
        .map_err(|_| ym_telemetry::FeedbackError::Dropped)?
}

pub struct LogSetup {
    pub log: Logger,
    pub logs: Option<Arc<LogStore>>,
    reports: Arc<DeferredSink>,
}

/// The logger every layer uses: stderr, the app log (`logs.db`), and Sentry once it joins.
pub fn logger(data_dir: &Path) -> LogSetup {
    let logs = LogStore::open(&data_dir.join("logs.db")).ok();
    let reports = Arc::new(DeferredSink::default());
    let stderr_level = if cfg!(debug_assertions) { Level::Info } else { Level::Warn };
    let mut sinks: Vec<Arc<dyn LogSink>> = vec![Arc::new(StderrSink { min_level: stderr_level })];
    if let Some(logs) = &logs {
        sinks.push(Arc::new(LogStoreSink { store: logs.clone() }));
    }
    sinks.push(reports.clone());
    LogSetup { log: Logger::new(Arc::new(TeeSink { sinks })), logs, reports }
}

/// The remote config's report policy, its defaults until the booted graph hands the config over.
#[derive(Default)]
struct RemotePolicy(OnceLock<Arc<RemoteConfig>>);

impl RemotePolicy {
    fn config(&self) -> RemoteConfigData {
        self.0.get().map(|remote| remote.current()).unwrap_or_default()
    }
}

impl ReportPolicy for RemotePolicy {
    fn error_log_lines(&self) -> i64 {
        self.config().error_log_lines()
    }

    fn error_sample_rate(&self) -> f64 {
        self.config().error_sample_rate()
    }
}

/// Keeps reporting alive for the app's lifetime; [`Self::follow`] hands it the booted graph.
pub struct Reporting {
    _guard: Option<Telemetry>,
    logs: Option<Arc<LogStore>>,
    policy: Option<Arc<RemotePolicy>>,
}

impl gpui_kit::Global for Reporting {}

impl Reporting {
    /// Once the graph booted: the app log's size cap, the error policy and the events' tags come
    /// from its remote config.
    pub fn follow(&self, graph: &AppGraph) {
        if let Some(logs) = &self.logs {
            logs.set_max_lines(graph.remote.current().log_lines());
        }
        if let Some(policy) = &self.policy {
            // One graph boots per launch; a second follow would keep the first's config.
            let _ = policy.0.set(graph.remote.clone());
        }
        if self._guard.is_some() {
            follow_remote_tags(graph);
        }
    }
}

/// The crash-reports switch applies at once: each value saved under [`CRASH_REPORTS_KEY`].
pub fn crash_reports_changed(value: &str) {
    ym_telemetry::set_reports_allowed(value != "false");
}

/// The anonymous id reports and stats carry, made on first use.
pub fn device_id(settings: &dyn SettingsStore) -> String {
    match settings.string(DEVICE_ID_KEY, "").ok().filter(|id| !id.is_empty()) {
        Some(id) => id,
        None => {
            let id = uuid::Uuid::new_v4().to_string();
            let _ = settings.put_raw(DEVICE_ID_KEY, id.clone());
            id
        }
    }
}

/// At launch, as soon as the settings are open (the JVM app's order): starts Sentry when this build
/// has a DSN, so a failure during boot or at the unlock screen is reported; the user's crash-reports
/// switch gates what leaves, live. Error events' log lines go out as Sentry Logs over `http`.
pub fn start_reporting(
    setup: &LogSetup,
    settings: &dyn SettingsStore,
    tokio: &Handle,
    http: reqwest::Client,
) -> Reporting {
    let mut reporting = Reporting { _guard: None, logs: setup.logs.clone(), policy: None };
    let (Some(logs), dsn) = (setup.logs.clone(), sentry_dsn()) else { return reporting };
    if dsn.is_empty() {
        return reporting;
    }
    device_id(settings);
    let shipper = LogShipper::start(logs, dsn, crate::graph::APP_VERSION, http, tokio, setup.log.fork("sentry-logs"));
    let Some(telemetry) = Telemetry::init(telemetry_config(dsn, settings), crash_reports_allowed(settings)) else {
        return reporting;
    };
    match shipper {
        Ok(shipper) => {
            let policy = Arc::new(RemotePolicy::default());
            let sink = Arc::new(SentrySink { logs: shipper, policy: policy.clone() });
            sink.attach_logs_to_panics();
            setup.reports.set(sink);
            reporting.policy = Some(policy);
        }
        Err(error) => {
            setup.log.warn("R90025 Sentry reporting runs without its sink: the DSN does not parse", Some(&error))
        }
    }
    reporting._guard = Some(telemetry);
    reporting
}

/// Keeps the events' remote tags equal to the config's `sentryTags`, from the cached config on.
fn follow_remote_tags(graph: &AppGraph) {
    let mut config = graph.remote.subscribe();
    graph.tokio.spawn(async move {
        loop {
            ym_telemetry::set_remote_tags(&config.borrow_and_update().sentry_tags);
            if config.changed().await.is_err() {
                return;
            }
        }
    });
}
