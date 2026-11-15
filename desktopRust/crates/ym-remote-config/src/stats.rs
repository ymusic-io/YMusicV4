use std::collections::{BTreeMap, VecDeque};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use serde::Serialize;
use serde_json::Value;
use tokio::runtime::Handle;
use tokio::sync::mpsc;
use tokio::time::Instant;
use ym_common::log::{Level, Logger, now_millis};

use crate::RemoteConfig;

/// Wire contract: one pulse carries at most this many events; the service answers 413 to more.
pub const PULSE_MAX_EVENTS: usize = 15;
/// At most one flush attempt per interval, whatever happens.
pub const STAT_FLUSH_INTERVAL: Duration = Duration::from_secs(5 * 60);
/// More queued than this means nobody is listening; the oldest are dropped.
const MAX_QUEUE: usize = 200;

/// One anonymous usage event; `at` is epoch ms when it happened, kept while it waits in the queue.
/// Serialized exactly as the JVM app writes it: `{"name":..,"at":..,"props":{..}}`, `props` only
/// when non-empty, one event per line of `stat-queue.json`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct PulseEvent {
    pub name: String,
    pub at: i64,
    #[serde(rename = "props", skip_serializing_if = "BTreeMap::is_empty")]
    pub properties: BTreeMap<String, String>,
}

impl PulseEvent {
    /// Strict, as the JVM app reads its own queue: a line without a string `name` and an integer
    /// `at` is a broken line.
    fn parse(line: &[u8]) -> Option<Self> {
        let Value::Object(object) = serde_json::from_slice(line).ok()? else { return None };
        let name = object.get("name")?.as_str()?.to_owned();
        let at = object.get("at")?.as_i64()?;
        let properties = match object.get("props") {
            Some(Value::Object(props)) => props
                .iter()
                .map(|(key, value)| {
                    let text = match value {
                        Value::String(text) => text.clone(),
                        Value::Array(_) | Value::Object(_) => return None,
                        primitive => primitive.to_string(),
                    };
                    Some((key.clone(), text))
                })
                .collect::<Option<_>>()?,
            _ => BTreeMap::new(),
        };
        Some(Self { name, at, properties })
    }

    fn line(&self) -> Vec<u8> {
        let mut line = serde_json::to_vec(self).unwrap_or_default();
        line.push(b'\n');
        line
    }
}

/// Anonymous usage stats, the JVM app's `StatUsage`: gated per event on "Send anonymous stats",
/// so turning it off applies at once and a disabled gate does no work.
///
/// The queue lives in memory, mirrored to an append-only file (one event per line; a torn line is
/// skipped on load), rewritten to the unsent remainder only after a send. Flushing: the first event
/// of a launch ships at once with any backlog; a later event ships everything when the last attempt
/// is [`STAT_FLUSH_INTERVAL`] behind; a full batch arms one timer for the end of the interval. A
/// failed attempt counts against the interval and never schedules its own retry.
pub struct StatUsage {
    sender: Option<mpsc::UnboundedSender<PulseEvent>>,
    enabled: Arc<dyn Fn() -> bool + Send + Sync>,
}

impl StatUsage {
    /// Nothing is ever recorded when `remote` has no service in this build.
    pub fn start(
        remote: Arc<RemoteConfig>,
        enabled: impl Fn() -> bool + Send + Sync + 'static,
        queue_file: PathBuf,
        tokio: &Handle,
        log: Logger,
    ) -> Self {
        let enabled: Arc<dyn Fn() -> bool + Send + Sync> = Arc::new(enabled);
        if !remote.has_service() {
            return Self { sender: None, enabled };
        }
        let (sender, receiver) = mpsc::unbounded_channel();
        let queue = Queue {
            remote,
            enabled: enabled.clone(),
            queue_file,
            log,
            pending: VecDeque::new(),
            loaded: false,
            flushed_once: false,
            last_flush_at: 0,
        };
        tokio.spawn(queue.run(receiver));
        Self { sender: Some(sender), enabled }
    }

    pub fn on_app_start(&self) {
        self.record("app_start", BTreeMap::new());
    }

    /// Callable from any thread; never blocks. `properties` must carry nothing personal.
    pub fn record(&self, name: &str, properties: BTreeMap<String, String>) {
        let Some(sender) = &self.sender else { return };
        if !(self.enabled)() {
            return;
        }
        // The queue task only ends with the runtime, when nothing is left to record.
        let _ = sender.send(PulseEvent { name: name.to_owned(), at: now_millis(), properties });
    }
}

/// Owned by one task, so events keep their order (the service credits days oldest first).
struct Queue {
    remote: Arc<RemoteConfig>,
    enabled: Arc<dyn Fn() -> bool + Send + Sync>,
    queue_file: PathBuf,
    log: Logger,
    pending: VecDeque<PulseEvent>,
    loaded: bool,
    flushed_once: bool,
    /// The last attempt, sent or not: the throttle compares against it.
    last_flush_at: i64,
}

impl Queue {
    async fn run(mut self, mut receiver: mpsc::UnboundedReceiver<PulseEvent>) {
        let mut flush_at: Option<Instant> = None;
        loop {
            let event = match flush_at {
                Some(deadline) => tokio::select! {
                    event = receiver.recv() => event,
                    () = tokio::time::sleep_until(deadline) => {
                        flush_at = None;
                        self.flush().await;
                        continue;
                    }
                },
                None => receiver.recv().await,
            };
            let Some(event) = event else { return };
            self.load_if_needed().await;
            let line = event.line();
            let file = self.queue_file.clone();
            if let Err(error) = blocking(move || append(&file, &line)).await {
                self.log.warn("R90750 stat queue append failed", Some(&error));
            }
            self.pending.push_back(event);
            let interval = STAT_FLUSH_INTERVAL.as_millis() as i64;
            let now = now_millis();
            if !self.flushed_once || now - self.last_flush_at >= interval {
                flush_at = None;
                self.flush().await;
            } else if self.pending.len() >= PULSE_MAX_EVENTS && flush_at.is_none() {
                let wait = (self.last_flush_at + interval - now).max(0) as u64;
                flush_at = Some(Instant::now() + Duration::from_millis(wait));
            }
            // After the flush had its chance: only what could not ship is dropped.
            while self.pending.len() > MAX_QUEUE {
                self.pending.pop_front();
            }
        }
    }

    /// Oldest first, in chunks the service accepts; the file forgets only what was sent.
    async fn flush(&mut self) {
        self.flushed_once = true;
        self.last_flush_at = now_millis();
        if !(self.enabled)() {
            return;
        }
        while !self.pending.is_empty() {
            let chunk: Vec<PulseEvent> = self.pending.iter().take(PULSE_MAX_EVENTS).cloned().collect();
            if let Err(error) = self.remote.pulse(&chunk).await {
                let message = format!("R90751 stat flush failed, {} queued", self.pending.len());
                self.log.log(Level::Info, &message, Some(&error));
                return;
            }
            self.pending.drain(..chunk.len());
            let remainder: Vec<PulseEvent> = self.pending.iter().cloned().collect();
            let file = self.queue_file.clone();
            if let Err(error) = blocking(move || rewrite(&file, &remainder)).await {
                self.log.warn("R90752 stat queue mirror not updated after a flush", Some(&error));
            }
        }
    }

    async fn load_if_needed(&mut self) {
        if self.loaded {
            return;
        }
        self.loaded = true;
        let file = self.queue_file.clone();
        match blocking(move || load(&file)).await {
            Ok(events) => {
                self.pending.extend(events);
                while self.pending.len() > MAX_QUEUE {
                    self.pending.pop_front();
                }
            }
            Err(error) => {
                self.log.log(Level::Info, "R90753 stat queue unreadable, starting over", Some(&error));
                let file = self.queue_file.clone();
                if let Err(error) = blocking(move || remove_if_present(&file)).await {
                    self.log.warn("R90754 unreadable stat queue not removed", Some(&error));
                }
            }
        }
    }
}

async fn blocking<T: Send + 'static>(work: impl FnOnce() -> io::Result<T> + Send + 'static) -> io::Result<T> {
    tokio::task::spawn_blocking(work).await.map_err(io::Error::other)?
}

fn load(file: &Path) -> io::Result<Vec<PulseEvent>> {
    let bytes = match std::fs::read(file) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error),
    };
    Ok(bytes
        .split(|byte| *byte == b'\n')
        .filter(|line| !line.trim_ascii().is_empty())
        .filter_map(PulseEvent::parse)
        .collect())
}

fn append(file: &Path, line: &[u8]) -> io::Result<()> {
    if let Some(parent) = file.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::OpenOptions::new().create(true).append(true).open(file)?.write_all(line)
}

fn rewrite(file: &Path, remainder: &[PulseEvent]) -> io::Result<()> {
    if remainder.is_empty() {
        return remove_if_present(file);
    }
    let mut staged_name = file.file_name().unwrap_or_default().to_owned();
    staged_name.push(".tmp");
    let staged = file.with_file_name(staged_name);
    let mut out = io::BufWriter::new(std::fs::File::create(&staged)?);
    for event in remainder {
        out.write_all(&event.line())?;
    }
    out.flush()?;
    drop(out);
    std::fs::rename(&staged, file)
}

fn remove_if_present(file: &Path) -> io::Result<()> {
    match std::fs::remove_file(file) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}
