//! Crash and error reporting to Sentry, as the JVM app reports: log lines stay in the app log,
//! `capture_error` is the one event of a failed job (tagged with its trail and error code, its trail
//! hops as breadcrumbs, grouped by that code, its run's newest unsent app-log lines sent as Sentry
//! Logs on its trace), panics are reported by the SDK with those lines too, and the user's switch
//! gates every event live.

mod exit_marker;
mod feedback;
mod log_shipper;

pub use exit_marker::{ExitMarker, UncleanExit};
pub use feedback::{FeedbackError, FeedbackReport, send_feedback};
pub use log_shipper::LogShipper;

use std::borrow::Cow;
use std::collections::BTreeMap;
use std::error::Error;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, UNIX_EPOCH};

use sentry::protocol::{
    Attachment, Breadcrumb, Context, DebugImage, DeviceContext, Event, Level as SentryLevel, OsContext, TraceContext,
    TraceId,
};
use ym_common::log::{CaptureExtras, Crumb, Level, LogSink};

const TRAIL_TAG: &str = "trail";
const CODE_TAG: &str = "error.code";
const DEVICE_ID_TAG: &str = "device_id";
const REPORT_TYPE_TAG: &str = "report_type";
const ENGINE_VERSION_TAG: &str = "engine.version";
const PLAYER_VERSION_TAG: &str = "player.version";
/// The launch an unclean-exit event is about: the event goes on that launch's trace, with its log lines.
const PREVIOUS_LAUNCH_TAG: &str = "previous_launch";
const UNCLEAN_EXIT_CODE: &str = "R90037";
/// Failures that are never defects: the UI already shows their localized category.
const IGNORED_CATEGORIES: &[&str] = &["network"];
/// The well-known categories an error's text starts with (`EngineError` shows `<code>: <message>`).
const CATEGORIES: &[&str] = &["network", "parse", "oom", "captcha", "disk_full", "playback_unavailable"];
/// How far down an error's sources the code is looked for (the JVM codedError's cycle guard).
const CODE_CAUSE_DEPTH: usize = 100;
/// As the JVM app keeps.
const MAX_BREADCRUMBS: usize = 200;
/// This workspace's crates: a panic's culprit is the topmost of their frames, never the SDK's or gpui's.
const IN_APP_CRATES: &[&str] = &["ymusic::", "ym_"];
/// How long a panic waits for the app log's connection before it goes without its lines.
const PANIC_CLAIM_WAIT: Duration = Duration::from_millis(200);

/// The user's crash-reports switch: while off, nothing leaves the process (feedback has its own client).
static REPORTS_ALLOWED: AtomicBool = AtomicBool::new(false);

/// Applies the crash-reports switch at once, as the JVM app does.
pub fn set_reports_allowed(allowed: bool) {
    REPORTS_ALLOWED.store(allowed, Ordering::Relaxed);
}

fn reports_allowed() -> bool {
    REPORTS_ALLOWED.load(Ordering::Relaxed)
}

/// Tags every event carries, whichever thread sends it: sentry-rust forks a scope per thread, so a
/// tag set on one thread's scope never reaches another thread's events.
static APP_TAGS: Mutex<BTreeMap<String, String>> = Mutex::new(BTreeMap::new());
/// The remote config's `sentryTags`, replaced whole as the config changes.
static REMOTE_TAGS: Mutex<BTreeMap<String, String>> = Mutex::new(BTreeMap::new());

/// Names the engine-js bundle that is live: set at boot and on every swap.
pub fn set_engine_version(version: i32) {
    set_app_tag(ENGINE_VERSION_TAG, version.to_string());
}

/// Names the player.js bundle of the newest playback session, once it passed its health check.
pub fn set_player_version(version: i32) {
    set_app_tag(PLAYER_VERSION_TAG, version.to_string());
}

/// The remote config's `sentryTags`; a key it drops leaves later events too.
pub fn set_remote_tags(tags: &BTreeMap<String, String>) {
    *REMOTE_TAGS.lock().unwrap_or_else(PoisonError::into_inner) = tags.clone();
}

fn set_app_tag(key: &str, value: String) {
    APP_TAGS.lock().unwrap_or_else(PoisonError::into_inner).insert(key.to_owned(), value);
}

/// Drops a panic's frames from its entry into the panic machinery on (the hooks, this crate's among
/// them, and the SDK's capture), which sentry-panic keeps: the top frame left is where it panicked.
fn trim_panic_frames(event: &mut Event<'_>) {
    for exception in &mut event.exception.values {
        let Some(stacktrace) = exception.stacktrace.as_mut().filter(|_| exception.ty == "panic") else { continue };
        let entry = stacktrace.frames.iter().position(|frame| frame.function.as_deref().is_some_and(is_panic_entry));
        if let Some(entry) = entry {
            stacktrace.frames.truncate(entry);
        }
    }
}

/// Frames are oldest first, and `std::panicking::catch_unwind` sits near the root, so only the
/// functions a panic starts in match.
fn is_panic_entry(function: &str) -> bool {
    function.starts_with("core::panicking::")
        || function.starts_with("std::panicking::begin_panic")
        || function.contains("rust_begin_unwind")
}

/// The JVM app's desktop context and no more: the OS name and version and the CPU arch, where the
/// contexts integration adds the OS build, the kernel, the device model and a Rust runtime.
fn lean_contexts(event: &mut Event<'_>) {
    event.contexts.remove("rust");
    if let Some(Context::Os(os)) = event.contexts.get_mut("os") {
        **os = OsContext { name: os.name.take(), version: os.version.take(), ..Default::default() };
    }
    if let Some(Context::Device(device)) = event.contexts.get_mut("device") {
        **device = DeviceContext { arch: device.arch.take(), ..Default::default() };
    }
}

/// Debug images name the files the app and its libraries load from; under the home folder that path
/// carries the user's name.
fn scrub_image_paths(event: &mut Event<'_>) {
    if event.debug_meta.images.is_empty() {
        return;
    }
    let Some(home) = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(|home| home.to_string_lossy().trim_end_matches(['/', '\\']).to_owned())
        .filter(|home| !home.is_empty())
    else {
        return;
    };
    let tilde = |path: &mut String| {
        if path.strip_prefix(home.as_str()).is_some_and(|rest| rest.starts_with(['/', '\\'])) {
            path.replace_range(..home.len(), "~");
        }
    };
    for image in &mut event.debug_meta.to_mut().images {
        match image {
            DebugImage::Symbolic(image) => {
                tilde(&mut image.name);
                if let Some(debug_file) = image.debug_file.as_mut() {
                    tilde(debug_file);
                }
            }
            DebugImage::Apple(image) => tilde(&mut image.name),
            _ => {}
        }
    }
}

/// Puts the event on its launch's trace, the one its log lines carry.
fn link_trace(event: &mut Event<'_>, trace_id: TraceId) {
    match event.contexts.get_mut("trace") {
        Some(Context::Trace(trace)) => trace.trace_id = trace_id,
        _ => {
            let trace = TraceContext { trace_id, ..Default::default() };
            event.contexts.insert("trace".to_owned(), Context::Trace(Box::new(trace)));
        }
    }
}

/// An event's own tags win over the app's, and those over the remote config's.
fn stamp_tags(event: &mut Event<'_>) {
    for tags in [&APP_TAGS, &REMOTE_TAGS] {
        for (key, value) in tags.lock().unwrap_or_else(PoisonError::into_inner).iter() {
            event.tags.entry(key.clone()).or_insert_with(|| value.clone());
        }
    }
}

/// What a report may carry, read at send time (the remote config can change it).
pub trait ReportPolicy: Send + Sync {
    /// How many of the newest log lines an error event takes; 0 = none.
    fn error_log_lines(&self) -> i64;
    /// The share of non-fatal errors sent, 0..=1.
    fn error_sample_rate(&self) -> f64;
    /// Regex patterns whose match keeps a non-fatal error out of Sentry.
    fn error_filters(&self) -> Vec<String> {
        Vec::new()
    }
}

/// How many errors down the source chain the filters look (the JVM ErrorPolicy's MAX_CAUSE_DEPTH).
const FILTER_CAUSE_DEPTH: usize = 5;
/// The longest text a filter runs on, bounding a careless pattern's backtracking (MAX_SUBJECT_CHARS).
const FILTER_SUBJECT_CHARS: usize = 512;

/// The filters as compiled regexes, compiled again only when the list changes (the JVM app builds
/// one ErrorPolicy per rule set).
fn compiled_filters(filters: &[String]) -> Arc<Vec<regex::Regex>> {
    type Compiled = (Vec<String>, Arc<Vec<regex::Regex>>);
    static COMPILED: Mutex<Option<Compiled>> = Mutex::new(None);
    let mut compiled = COMPILED.lock().unwrap_or_else(PoisonError::into_inner);
    if let Some((source, patterns)) = compiled.as_ref()
        && source.as_slice() == filters
    {
        return patterns.clone();
    }
    let patterns = Arc::new(filters.iter().filter_map(|pattern| regex::Regex::new(pattern).ok()).collect::<Vec<_>>());
    *compiled = Some((filters.to_vec(), patterns.clone()));
    patterns
}

/// The JVM ErrorPolicy.matchesFilter: any pattern found in the text of the error or one of its
/// sources. Rust errors have no class name, so the text alone is the subject; an invalid pattern is
/// skipped.
fn matches_filter(filters: &[String], error: &(dyn std::error::Error + 'static)) -> bool {
    let patterns = compiled_filters(filters);
    if patterns.is_empty() {
        return false;
    }
    let mut cause: Option<&(dyn std::error::Error + 'static)> = Some(error);
    let mut depth = 0;
    while let Some(current) = cause {
        if depth == FILTER_CAUSE_DEPTH {
            break;
        }
        depth += 1;
        let subject: String = current.to_string().chars().take(FILTER_SUBJECT_CHARS).collect();
        if patterns.iter().any(|pattern| pattern.is_match(&subject)) {
            return true;
        }
        cause = current.source();
    }
    false
}

pub struct Telemetry {
    _guard: sentry::ClientInitGuard,
}

pub struct TelemetryConfig {
    pub dsn: String,
    pub release: String,
    pub environment: String,
    pub device_id: String,
}

impl Telemetry {
    /// `None` when there is no DSN: nothing is ever sent. Runs whatever `allowed` says, which gates
    /// every event from then on ([`set_reports_allowed`]); sessions follow it as it stands at launch.
    pub fn init(config: TelemetryConfig, allowed: bool) -> Option<Self> {
        if config.dsn.trim().is_empty() {
            return None;
        }
        set_reports_allowed(allowed);
        let mut options = sentry::ClientOptions::default();
        options.release = Some(config.release.into());
        options.environment = Some(config.environment.into());
        options.send_default_pii = false;
        options.max_breadcrumbs = MAX_BREADCRUMBS;
        options.in_app_include = IN_APP_CRATES.to_vec();
        // Sessions have no send hook to gate, so the switch at launch decides them, as in the JVM app.
        options.auto_session_tracking = allowed;
        options.before_send = Some(Arc::new(|mut event| {
            if !reports_allowed() {
                return None;
            }
            // The contexts integration fills in the host name, which can carry the user's name.
            event.server_name = None;
            lean_contexts(&mut event);
            scrub_image_paths(&mut event);
            // The trail rides in last but happened first.
            event.breadcrumbs.values.sort_by_key(|breadcrumb| breadcrumb.timestamp);
            stamp_tags(&mut event);
            trim_panic_frames(&mut event);
            let previous_launch = event.tags.get(PREVIOUS_LAUNCH_TAG).and_then(|launch| launch.parse().ok());
            if let Some(trace_id) = previous_launch.or_else(log_shipper::launch_trace) {
                link_trace(&mut event, trace_id);
            }
            Some(event)
        }));
        // Before init: the session init starts takes its user from the scope. Sentry's user counts
        // must match the dashboard's active devices, which are keyed by this id.
        let user = sentry::User { id: Some(config.device_id.clone()), ..Default::default() };
        // The hub's own configure_scope: sentry::configure_scope does nothing until a client is bound.
        sentry::Hub::current().configure_scope(|scope| scope.set_user(Some(user)));
        set_app_tag(DEVICE_ID_TAG, config.device_id);
        let guard = sentry::init((config.dsn.as_str(), options));
        Some(Self { _guard: guard })
    }
}

/// The Sentry side of the logger.
pub struct SentrySink {
    pub logs: Arc<LogShipper>,
    pub policy: Arc<dyn ReportPolicy>,
}

impl SentrySink {
    /// Panics reported from now on take their run's newest unsent app-log lines too, as error events
    /// do. Call after [`Telemetry::init`], whose panic hook this wraps.
    pub fn attach_logs_to_panics(self: &Arc<Self>) {
        let sink = self.clone();
        let next = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let lines = if reports_allowed() { sink.policy.error_log_lines() } else { 0 };
            if lines > 0 {
                sink.logs.claim_now(lines, PANIC_CLAIM_WAIT);
            }
            next(info);
        }));
    }
}

impl SentrySink {
    /// Blocking. A launch that died without a clean quit or a reported panic (a native crash, a stack
    /// overflow, a kill): one fatal event, taking that launch's newest log lines.
    pub fn report_unclean_exit(&self, previous: &UncleanExit) {
        if previous.panicked || !reports_allowed() {
            return;
        }
        let lines = self.policy.error_log_lines();
        if lines > 0 {
            self.logs.claim_launch(&previous.launch, lines);
        }
        sentry::with_scope(
            |scope| {
                scope.set_tag(REPORT_TYPE_TAG, "unclean_exit");
                scope.set_tag(CODE_TAG, UNCLEAN_EXIT_CODE);
                scope.set_tag(PREVIOUS_LAUNCH_TAG, &previous.launch);
            },
            || {
                let event = Event {
                    message: Some(format!("{UNCLEAN_EXIT_CODE} the previous launch ended without a clean quit")),
                    level: SentryLevel::Fatal,
                    fingerprint: vec![Cow::Borrowed(UNCLEAN_EXIT_CODE)].into(),
                    ..Default::default()
                };
                sentry::capture_event(event);
            },
        );
    }
}

fn trail_path(trail: &[Crumb]) -> String {
    trail.iter().map(|crumb| crumb.tag.as_str()).collect::<Vec<_>>().join("/")
}

/// The code an error carries, as the JVM codedError: the deepest of its sources that has one wins,
/// the code closest to the root cause being the most specific.
fn error_code(error: &(dyn Error + 'static)) -> Option<String> {
    let mut deepest = None;
    let mut cause: Option<&(dyn Error + 'static)> = Some(error);
    let mut depth = 0;
    while let Some(current) = cause {
        if depth == CODE_CAUSE_DEPTH {
            break;
        }
        depth += 1;
        if let Some(code) = text_code(&current.to_string()) {
            deepest = Some(code);
        }
        cause = current.source();
    }
    deepest
}

/// A category word the text starts with, else the first site code in it.
fn text_code(text: &str) -> Option<String> {
    let leading = text.split(':').next().unwrap_or_default();
    if let Some(category) = CATEGORIES.iter().find(|category| **category == leading) {
        return Some((*category).to_owned());
    }
    let bytes = text.as_bytes();
    (0..bytes.len().saturating_sub(5)).find_map(|start| {
        let candidate = text.get(start..start + 6)?;
        let bounded = (start == 0 || !bytes[start - 1].is_ascii_alphanumeric())
            && bytes.get(start + 6).is_none_or(|next| !next.is_ascii_alphanumeric());
        (bounded && is_site_code(candidate)).then(|| candidate.to_owned())
    })
}

/// `E#####` (YMusicV4, engine-js, the server) or `R#####` (this app): one throw site.
fn is_site_code(code: &str) -> bool {
    let bytes = code.as_bytes();
    bytes.len() == 6 && matches!(bytes[0], b'E' | b'R') && bytes[1..].iter().all(u8::is_ascii_digit)
}

impl LogSink for SentrySink {
    /// Lines are not sent from here (the JVM SentryLogger.log): the app log keeps them, and an error
    /// event takes the newest along.
    fn log(&self, _level: Level, _trail: &[Crumb], _message: &str, _error: Option<&(dyn Error + 'static)>) {}

    fn capture_error(&self, trail: &[Crumb], message: &str, error: &(dyn Error + 'static)) {
        self.capture_error_with(trail, message, error, &CaptureExtras::default());
    }

    fn capture_error_with(
        &self,
        trail: &[Crumb],
        message: &str,
        error: &(dyn Error + 'static),
        extras: &CaptureExtras,
    ) {
        if !reports_allowed() {
            return;
        }
        let code = error_code(error).or_else(|| text_code(message));
        if code.as_deref().is_some_and(|code| IGNORED_CATEGORIES.contains(&code)) {
            return;
        }
        if matches_filter(&self.policy.error_filters(), error) {
            return;
        }
        let rate = self.policy.error_sample_rate().clamp(0.0, 1.0);
        if rate < 1.0 && fastrand_unit() >= rate {
            return;
        }
        let lines = self.policy.error_log_lines();
        if lines > 0 {
            self.logs.claim(lines);
        }
        let path = trail_path(trail);
        sentry::with_scope(
            |scope| {
                scope.set_tag(TRAIL_TAG, &path);
                scope.set_tag(REPORT_TYPE_TAG, "error");
                if let Some(code) = &code {
                    scope.set_tag(CODE_TAG, code);
                }
                if !extras.props.is_empty() {
                    let props = extras.props.iter().map(|(key, value)| (key.clone(), value.clone())).collect();
                    scope.set_context("capture", Context::Other(props));
                }
                for file in &extras.files {
                    scope.add_attachment(Attachment {
                        buffer: file.bytes.clone(),
                        filename: file.filename.clone(),
                        ..Default::default()
                    });
                }
            },
            || {
                // As the JVM app: each hop a breadcrumb stamped when it was entered, its props as data,
                // on this capture's scope only.
                for crumb in trail {
                    sentry::add_breadcrumb(Breadcrumb {
                        timestamp: UNIX_EPOCH + Duration::from_millis(crumb.at_millis.max(0) as u64),
                        message: Some(crumb.tag.clone()),
                        data: crumb.props.iter().flatten().map(|(key, value)| (key.clone(), value.clone())).collect(),
                        ..Default::default()
                    });
                }
                let mut event = sentry::event_from_error(error);
                event.message = Some(message.to_owned());
                event.level = SentryLevel::Error;
                if let Some(fingerprint) = fingerprint(code.as_deref(), &path, &event) {
                    event.fingerprint = fingerprint.into_iter().map(Cow::Owned).collect::<Vec<_>>().into();
                }
                sentry::capture_event(event);
            },
        );
    }
}

/// The JVM app's grouping: a site code is one throw site, one issue; a category code ("parse",
/// "oom") is shared by every subsystem, so it groups per trail, as an uncoded error groups by its type
/// within its trail: one failure shape in two subsystems stays two issues.
fn fingerprint(code: Option<&str>, trail: &str, event: &Event<'_>) -> Option<Vec<String>> {
    if let Some(code) = code {
        if is_site_code(code) || trail.is_empty() {
            return Some(vec![code.to_owned()]);
        }
        return Some(vec![code.to_owned(), trail.to_owned()]);
    }
    if trail.is_empty() {
        return None;
    }
    let kind = event.exception.values.last().map_or("error", |exception| exception.ty.as_str());
    Some(vec![kind.to_owned(), trail.to_owned()])
}

/// A uniform 0..1 draw for sampling; quality is irrelevant.
fn fastrand_unit() -> f64 {
    let nanos =
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |elapsed| elapsed.subsec_nanos());
    f64::from(nanos % 1_000_000) / 1_000_000.0
}
