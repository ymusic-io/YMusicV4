//! Calling a bundle command: one pipe, a JSON args head carrying the caller's parked logger key,
//! one JSON result back; a RESET decodes into the error the bundle minted.

use serde::de::DeserializeOwned;
use serde_json::{Map, Value};
use ym_common::log::Logger;
use ym_quickjs::PipeError;

use crate::engine::DynamicEngine;
use crate::error::EngineError;

impl DynamicEngine {
    /// `log` is parked for exactly this call, so the bundle's host callbacks join its trail.
    pub async fn json_command<T: DeserializeOwned>(
        &self,
        command: &str,
        args: Map<String, Value>,
        log: &Logger,
    ) -> Result<T, EngineError> {
        let key = self.parked_loggers().park(log.clone());
        let result = self.json_command_pipe(command, key, args).await;
        self.parked_loggers().unpark(key);
        let body = result?;
        serde_json::from_slice(&body)
            .map_err(|error| EngineError::site("R20017", format!("{command} returned a malformed result: {error}")))
    }

    async fn json_command_pipe(
        &self,
        command: &str,
        key: i64,
        mut args: Map<String, Value>,
    ) -> Result<Vec<u8>, EngineError> {
        let unreachable = |error: PipeError| EngineError::site("R20018", format!("{command}: {error}"));
        let pipe = self.runtime().open_pipe(command).await.map_err(unreachable)?;
        args.insert("logKey".into(), Value::from(key));
        pipe.send(Value::Object(args).to_string()).await.map_err(unreachable)?;
        pipe.close_write(None).await.map_err(unreachable)?;
        let body = pipe.read_to_end().await.map_err(|error| match error {
            PipeError::Reset(reason) => EngineError::decode_reset(&reason),
            other => unreachable(other),
        });
        let _ = pipe.close_read().await;
        Ok(body?.to_vec())
    }
}
