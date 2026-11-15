use std::error::Error;
use std::fmt;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Map, Value};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Level {
    Debug,
    Info,
    Warn,
    Error,
}

/// One hop of a logger's trail. `tag` is a stable grouping name; anything variable (ids, urls)
/// goes in `props`, whose values are JSON primitives.
#[derive(Debug, Clone, PartialEq)]
pub struct Crumb {
    pub tag: String,
    pub props: Option<Map<String, Value>>,
    pub at_millis: i64,
}

impl Crumb {
    pub fn new(tag: impl Into<String>, props: Option<Map<String, Value>>) -> Self {
        Self { tag: tag.into(), props, at_millis: now_millis() }
    }
}

pub fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |elapsed| elapsed.as_millis() as i64)
}

/// What a crash record carries beside its error, as the JVM app's `captureError` props: values for
/// the record's `capture` context, and files attached to it.
#[derive(Debug, Default)]
pub struct CaptureExtras {
    pub props: Map<String, Value>,
    pub files: Vec<CapturedFile>,
}

#[derive(Debug)]
pub struct CapturedFile {
    pub filename: String,
    /// Sentry's attachment takes the whole buffer.
    pub bytes: Vec<u8>,
}

/// Where log lines go: the app log, Sentry breadcrumbs, stderr.
pub trait LogSink: Send + Sync {
    fn log(&self, level: Level, trail: &[Crumb], message: &str, error: Option<&(dyn Error + 'static)>);

    fn capture_error(&self, trail: &[Crumb], message: &str, error: &(dyn Error + 'static)) {
        self.log(Level::Error, trail, message, Some(error));
    }

    /// [`Self::capture_error`] with props and files; a sink that keeps no crash records drops them.
    fn capture_error_with(
        &self,
        trail: &[Crumb],
        message: &str,
        error: &(dyn Error + 'static),
        _extras: &CaptureExtras,
    ) {
        self.capture_error(trail, message, error);
    }
}

/// An immutable scope-trail logger. Each layer forks its own hop and hands the child down, so a
/// line logged deep in the call graph carries the caller's context. Forking emits nothing.
#[derive(Clone)]
pub struct Logger {
    trail: Arc<[Crumb]>,
    sink: Arc<dyn LogSink>,
}

impl fmt::Debug for Logger {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.path())
    }
}

impl Logger {
    pub fn new(sink: Arc<dyn LogSink>) -> Self {
        Self { trail: Arc::from([]), sink }
    }

    /// Logging off.
    pub fn none() -> Self {
        Self::new(Arc::new(NoopSink))
    }

    pub fn trail(&self) -> &[Crumb] {
        &self.trail
    }

    pub fn path(&self) -> String {
        self.trail.iter().map(|crumb| crumb.tag.as_str()).collect::<Vec<_>>().join("/")
    }

    pub fn fork(&self, tag: impl Into<String>) -> Self {
        self.fork_crumb(Crumb::new(tag, None))
    }

    pub fn fork_with(&self, tag: impl Into<String>, props: Map<String, Value>) -> Self {
        self.fork_crumb(Crumb::new(tag, (!props.is_empty()).then_some(props)))
    }

    pub fn fork_crumb(&self, crumb: Crumb) -> Self {
        self.fork_all(std::iter::once(crumb))
    }

    pub fn fork_all(&self, crumbs: impl IntoIterator<Item = Crumb>) -> Self {
        let trail: Arc<[Crumb]> = self.trail.iter().cloned().chain(crumbs).collect();
        Self { trail, sink: self.sink.clone() }
    }

    pub fn log(&self, level: Level, message: &str, error: Option<&(dyn Error + 'static)>) {
        self.sink.log(level, &self.trail, message, error);
    }

    pub fn info(&self, message: &str) {
        self.log(Level::Info, message, None);
    }

    pub fn warn(&self, message: &str, error: Option<&(dyn Error + 'static)>) {
        self.log(Level::Warn, message, error);
    }

    /// A chatty line, built only outside production builds.
    pub fn debug(&self, message: impl FnOnce() -> String) {
        if !cfg!(feature = "production") {
            self.log(Level::Debug, &message(), None);
        }
    }

    /// The one crash record of a failed job, called where the job is declared dead.
    pub fn capture_error(&self, message: &str, error: &(dyn Error + 'static)) {
        self.sink.capture_error(&self.trail, message, error);
    }

    /// [`Self::capture_error`] carrying props and files with the record.
    pub fn capture_error_with(&self, message: &str, error: &(dyn Error + 'static), extras: &CaptureExtras) {
        self.sink.capture_error_with(&self.trail, message, error, extras);
    }
}

struct NoopSink;

impl LogSink for NoopSink {
    fn log(&self, _: Level, _: &[Crumb], _: &str, _: Option<&(dyn Error + 'static)>) {}
}

/// Development sink: one line per log on stderr.
pub struct StderrSink {
    pub min_level: Level,
}

impl LogSink for StderrSink {
    fn log(&self, level: Level, trail: &[Crumb], message: &str, error: Option<&(dyn Error + 'static)>) {
        if level < self.min_level {
            return;
        }
        let path = trail.iter().map(|crumb| crumb.tag.as_str()).collect::<Vec<_>>().join("/");
        match error {
            Some(error) => stderr_line(format_args!("{level:?} [{path}] {message}: {error}")),
            None => stderr_line(format_args!("{level:?} [{path}] {message}")),
        }
    }
}

/// One line on stderr, fire-and-forget: a closed reader (the updater that launched this process
/// exited) fails the write, which `eprintln!` would turn into a panic.
pub fn stderr_line(line: fmt::Arguments<'_>) {
    use std::io::Write;
    let _ = writeln!(std::io::stderr().lock(), "{line}");
}

/// Fans every line out to several sinks.
pub struct TeeSink {
    pub sinks: Vec<Arc<dyn LogSink>>,
}

impl LogSink for TeeSink {
    fn log(&self, level: Level, trail: &[Crumb], message: &str, error: Option<&(dyn Error + 'static)>) {
        for sink in &self.sinks {
            sink.log(level, trail, message, error);
        }
    }

    fn capture_error(&self, trail: &[Crumb], message: &str, error: &(dyn Error + 'static)) {
        for sink in &self.sinks {
            sink.capture_error(trail, message, error);
        }
    }

    fn capture_error_with(
        &self,
        trail: &[Crumb],
        message: &str,
        error: &(dyn Error + 'static),
        extras: &CaptureExtras,
    ) {
        for sink in &self.sinks {
            sink.capture_error_with(trail, message, error, extras);
        }
    }
}

/// A sink that joins later (once what it needs has booted); lines before then skip it.
#[derive(Default)]
pub struct DeferredSink {
    inner: std::sync::OnceLock<Arc<dyn LogSink>>,
}

impl DeferredSink {
    /// The first set wins.
    pub fn set(&self, sink: Arc<dyn LogSink>) {
        let _ = self.inner.set(sink);
    }
}

impl LogSink for DeferredSink {
    fn log(&self, level: Level, trail: &[Crumb], message: &str, error: Option<&(dyn Error + 'static)>) {
        if let Some(sink) = self.inner.get() {
            sink.log(level, trail, message, error);
        }
    }

    fn capture_error(&self, trail: &[Crumb], message: &str, error: &(dyn Error + 'static)) {
        if let Some(sink) = self.inner.get() {
            sink.capture_error(trail, message, error);
        }
    }

    fn capture_error_with(
        &self,
        trail: &[Crumb],
        message: &str,
        error: &(dyn Error + 'static),
        extras: &CaptureExtras,
    ) {
        if let Some(sink) = self.inner.get() {
            sink.capture_error_with(trail, message, error, extras);
        }
    }
}
