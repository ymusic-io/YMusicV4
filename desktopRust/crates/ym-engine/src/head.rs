use bytes::BytesMut;
use serde_json::Value;
use ym_quickjs::{PIPE_CAP, Pipe, PipeError};

const MAX_HEAD_BYTES: usize = 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub(crate) enum HeadError {
    #[error(transparent)]
    Pipe(#[from] PipeError),
    #[error("malformed head: {0}")]
    Malformed(String),
    #[error("head missing: the peer ended first")]
    Missing,
}

/// Reads one self-framing compact JSON head; returns it with whatever bytes followed it.
pub(crate) async fn read_json_head(pipe: &Pipe) -> Result<(Value, BytesMut), HeadError> {
    let mut buffer = BytesMut::new();
    loop {
        let Some(chunk) = pipe.receive(PIPE_CAP).await? else {
            return Err(if buffer.iter().all(u8::is_ascii_whitespace) {
                HeadError::Missing
            } else {
                HeadError::Malformed("truncated".into())
            });
        };
        buffer.extend_from_slice(&chunk);
        let mut values = serde_json::Deserializer::from_slice(&buffer).into_iter::<Value>();
        match values.next() {
            Some(Ok(head)) => {
                let end = values.byte_offset();
                return Ok((head, buffer.split_off(end)));
            }
            Some(Err(error)) if !error.is_eof() => return Err(HeadError::Malformed(error.to_string())),
            _ if buffer.len() > MAX_HEAD_BYTES => return Err(HeadError::Malformed("head too large".into())),
            _ => {}
        }
    }
}

/// Reads the next JSON value from a stream of concatenated values, keeping what follows it in
/// `buffer` for the next call. `None` at a clean END between values.
pub(crate) async fn read_json_value(pipe: &Pipe, buffer: &mut BytesMut) -> Result<Option<Value>, HeadError> {
    loop {
        if !buffer.iter().all(u8::is_ascii_whitespace) {
            let mut values = serde_json::Deserializer::from_slice(buffer).into_iter::<Value>();
            match values.next() {
                Some(Ok(value)) => {
                    let end = values.byte_offset();
                    let _ = buffer.split_to(end);
                    return Ok(Some(value));
                }
                Some(Err(error)) if !error.is_eof() => return Err(HeadError::Malformed(error.to_string())),
                _ if buffer.len() > MAX_HEAD_BYTES => return Err(HeadError::Malformed("value too large".into())),
                _ => {}
            }
        }
        match pipe.receive(PIPE_CAP).await? {
            Some(chunk) => buffer.extend_from_slice(&chunk),
            None if buffer.iter().all(u8::is_ascii_whitespace) => return Ok(None),
            None => return Err(HeadError::Malformed("truncated".into())),
        }
    }
}
