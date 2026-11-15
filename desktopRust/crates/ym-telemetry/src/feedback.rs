//! The Feedback screen's report: one anonymous event titled "Feedback: <subject>" carrying the app
//! log, found in triage by its device id; a message also goes to the feedback inbox, linked to it.

use std::borrow::Cow;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use sentry::protocol::{Attachment, Envelope, Event, Level};
use sentry::types::Uuid;
use sentry::{Client, ClientOptions, Scope, Transport, TransportFactory, TransportOptions};

use crate::{DEVICE_ID_TAG, REPORT_TYPE_TAG, TelemetryConfig};

const LOG_NAME: &str = "ymusic-log.txt.gz";
const FLUSH_TIMEOUT: Duration = Duration::from_secs(30);

pub struct FeedbackReport {
    pub subject: String,
    pub message: Option<String>,
    /// The gzipped app log; Sentry's `Attachment` takes the whole buffer.
    pub log_gzip: Option<Vec<u8>>,
}

#[derive(Debug, thiserror::Error)]
pub enum FeedbackError {
    #[error("R99001 this build has no Sentry DSN")]
    NoDsn,
    #[error("R99002 feedback report not delivered within {FLUSH_TIMEOUT:?}")]
    NotFlushed,
    #[error("R99003 feedback item not serialized: {0}")]
    Serialize(#[from] std::io::Error),
    #[error("R99004 the Sentry client dropped the feedback report")]
    Dropped,
}

/// The feedback-inbox item for `message`, linked to the report event `event_id`.
fn feedback_item(config: &TelemetryConfig, id: Uuid, event_id: Uuid, message: &str) -> serde_json::Value {
    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0.0, |elapsed| elapsed.as_secs_f64());
    serde_json::json!({
        "event_id": id.simple().to_string(),
        "timestamp": timestamp,
        "platform": "other",
        "level": "info",
        "release": config.release,
        "environment": config.environment,
        "tags": { DEVICE_ID_TAG: config.device_id, REPORT_TYPE_TAG: "feedback" },
        "contexts": {
            "feedback": { "message": message, "associated_event_id": event_id.simple().to_string() }
        },
    })
}

/// An envelope of one item of a type the SDK has no variant for.
fn raw_envelope(id: Uuid, item_type: &str, payload: &serde_json::Value) -> Result<Envelope, FeedbackError> {
    let body = serde_json::to_vec(payload).map_err(std::io::Error::other)?;
    let mut bytes = Vec::with_capacity(body.len() + 128);
    bytes.extend_from_slice(format!(r#"{{"event_id":"{}"}}"#, id.simple()).as_bytes());
    bytes.push(b'\n');
    bytes.extend_from_slice(format!(r#"{{"type":"{item_type}","length":{}}}"#, body.len()).as_bytes());
    bytes.push(b'\n');
    bytes.extend_from_slice(&body);
    bytes.push(b'\n');
    Envelope::from_bytes_raw(bytes).map_err(|error| FeedbackError::Serialize(std::io::Error::other(error)))
}

/// Writes every envelope to a file instead of the network: a debug dry run of [`send_feedback`].
struct EnvelopeFileTransport(PathBuf);

impl TransportFactory for EnvelopeFileTransport {
    fn create_transport_with_options(&self, _: TransportOptions) -> Arc<dyn Transport> {
        Arc::new(EnvelopeFile(self.0.clone()))
    }
}

struct EnvelopeFile(PathBuf);

impl Transport for EnvelopeFile {
    fn send_envelope(&self, envelope: Envelope) {
        let written = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.0)
            .and_then(|mut file| envelope.to_writer(&mut file));
        if let Err(error) = written {
            eprintln!("feedback dry run: {}: {error}", self.0.display());
        }
    }
}

/// Sends `report` on its own client, whether or not crash reporting runs; blocks until delivered.
/// With `envelope_file` (debug dry runs) the envelopes are appended there instead of sent.
pub fn send_feedback(
    config: &TelemetryConfig,
    report: FeedbackReport,
    envelope_file: Option<PathBuf>,
) -> Result<Uuid, FeedbackError> {
    if config.dsn.trim().is_empty() {
        return Err(FeedbackError::NoDsn);
    }
    let mut options = ClientOptions::default();
    options.release = Some(config.release.clone().into());
    options.environment = Some(config.environment.clone().into());
    options.send_default_pii = false;
    // The contexts integration fills in the host name, which can carry the user's name.
    options.before_send = Some(Arc::new(|mut event| {
        event.server_name = None;
        crate::lean_contexts(&mut event);
        Some(event)
    }));
    options.default_integrations = false;
    options.integrations = vec![Arc::new(sentry::integrations::contexts::ContextIntegration::default())];
    options.transport = Some(match envelope_file {
        Some(path) => Arc::new(EnvelopeFileTransport(path)),
        None => Arc::new(sentry::transports::DefaultTransportFactory),
    });
    let client = Client::from((config.dsn.as_str(), options));

    let mut scope = Scope::default();
    scope.set_user(Some(sentry::User { id: Some(config.device_id.clone()), ..Default::default() }));
    scope.set_tag(DEVICE_ID_TAG, &config.device_id);
    scope.set_tag(REPORT_TYPE_TAG, "feedback");
    if let Some(log) = report.log_gzip {
        scope.add_attachment(Attachment {
            buffer: log,
            filename: LOG_NAME.into(),
            content_type: Some("application/gzip".into()),
            ..Default::default()
        });
    }
    let mut event =
        Event { message: Some(format!("Feedback: {}", report.subject)), level: Level::Info, ..Default::default() };
    // As the JVM app's: every report is its own issue, with the app's tags, on its launch's trace.
    event.fingerprint = vec![Cow::Borrowed("feedback"), Cow::Owned(event.event_id.simple().to_string())].into();
    crate::stamp_tags(&mut event);
    if let Some(trace_id) = crate::log_shipper::launch_trace() {
        crate::link_trace(&mut event, trace_id);
    }
    let event_id = client.capture_event(event, Some(&scope));
    if event_id.is_nil() {
        return Err(FeedbackError::Dropped);
    }
    if let Some(message) = report.message {
        // A fresh random id, as the SDK mints one (its uuid has no public v4 here).
        let id = Event::default().event_id;
        client.send_envelope(raw_envelope(id, "feedback", &feedback_item(config, id, event_id, &message))?);
    }
    if !client.flush(Some(FLUSH_TIMEOUT)) {
        return Err(FeedbackError::NotFlushed);
    }
    Ok(event_id)
}
