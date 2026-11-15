//! The one-shot serving shape shared by the store protocols (cookie, cache, settings, …): a JSON
//! head, the protocol's work, then a clean END or a structured RESET classified at the host.

use std::sync::Arc;

use bytes::BytesMut;
use serde_json::{Map, Value};
use ym_common::log::Logger;
use ym_quickjs::{BoxError, Pipe, PipeError};

use crate::error::WireError;
use crate::head::{HeadError, read_json_head};
use crate::logs::{ParkedLoggers, prop_of, protocol_logger};

/// A protocol's two site codes: a caller bug, and a store failure without a code of its own.
#[derive(Clone, Copy)]
pub(crate) struct Codes {
    pub request: &'static str,
    pub failure: &'static str,
}

#[derive(Debug)]
pub(crate) enum ServeError {
    /// The engine sent something unusable: `Codes::request`.
    Request(String),
    /// A failure that already knows its code.
    Coded(Box<WireError>),
    /// A store or I/O failure: an embedded site code, else `Codes::failure`.
    Failure(BoxError),
    /// The engine hung up or abandoned the call: no one left to answer.
    Hangup,
}

impl From<PipeError> for ServeError {
    fn from(error: PipeError) -> Self {
        match error {
            PipeError::BrokenPipe | PipeError::SendClosed(_) | PipeError::Reset(_) | PipeError::RuntimeClosed => {
                Self::Hangup
            }
            other => Self::Failure(other.into()),
        }
    }
}

impl From<tokio::task::JoinError> for ServeError {
    fn from(error: tokio::task::JoinError) -> Self {
        Self::Failure(error.into())
    }
}

pub(crate) struct Request<'a> {
    pub pipe: &'a Pipe,
    pub head: Value,
    /// Bytes after the head: the start of a request body.
    pub trailing: BytesMut,
    pub log: Logger,
}

impl Request<'_> {
    pub fn string(&self, field: &str) -> Result<String, ServeError> {
        self.head
            .get(field)
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
            .map(str::to_owned)
            .ok_or_else(|| ServeError::Request(format!("request missing '{field}'")))
    }

    pub async fn send_json(&self, value: &Value) -> Result<(), ServeError> {
        Ok(self.pipe.send(value.to_string()).await?)
    }
}

pub(crate) async fn serve(
    pipe: Pipe,
    parked: Arc<ParkedLoggers>,
    tag: &str,
    codes: Codes,
    props: &[&str],
    handle: impl AsyncFnOnce(&Request<'_>) -> Result<(), ServeError>,
) -> Result<(), BoxError> {
    let (head, trailing) = match read_json_head(&pipe).await {
        Ok((head, trailing)) if head.is_object() => (head, trailing),
        // The engine abandoned the call before it started.
        Err(HeadError::Pipe(PipeError::Reset(_))) => {
            pipe.close_read().await?;
            return Ok(());
        }
        other => {
            let detail = match other {
                Err(error) => error.to_string(),
                Ok(_) => "head is not a JSON object".to_owned(),
            };
            let message = format!("malformed {tag} head: {detail}");
            parked.root().fork(tag).warn(&format!("{} {message}", codes.request), None);
            pipe.close_write(Some(WireError::new(codes.request, message).to_reason())).await?;
            pipe.close_read().await?;
            return Ok(());
        }
    };
    let field_props: Map<String, Value> = props.iter().filter_map(|field| prop_of(&head, field)).flatten().collect();
    let log = protocol_logger(&parked, &head, tag, (!field_props.is_empty()).then_some(field_props));
    let request = Request { pipe: &pipe, head, trailing, log };
    match handle(&request).await {
        Ok(()) => pipe.close_write(None).await?,
        Err(ServeError::Hangup) => {}
        Err(error) => {
            let wire = classify(error, codes);
            request.log.warn(&format!("{} {}", wire.code, wire.message.as_deref().unwrap_or_default()), None);
            pipe.close_write(Some(wire.to_reason())).await?;
        }
    }
    pipe.close_read().await?;
    Ok(())
}

fn classify(error: ServeError, codes: Codes) -> WireError {
    match error {
        ServeError::Request(message) => WireError::new(codes.request, message),
        ServeError::Coded(wire) => *wire,
        ServeError::Failure(error) => {
            let message = error.to_string();
            let code = crate::error::find_site_code(&message).unwrap_or(codes.failure).to_owned();
            WireError::new(&code, message)
        }
        ServeError::Hangup => WireError::new(codes.failure, "hung up"),
    }
}
