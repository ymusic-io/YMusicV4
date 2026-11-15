//! Player-only `host/main/<command>`: the player bundle's open is spliced onto `<command>` on the
//! main engine, bytes copied both ways as-is (END and RESET included).

use std::sync::Arc;

use ym_quickjs::{BoxError, PIPE_CAP, Pipe, PipeError, QuickJsRuntime};

use crate::engine::MainResolver;

use crate::error::WireError;
use crate::logs::ParkedLoggers;

const PREFIX: &str = "host/main/";

pub(crate) fn install(runtime: &QuickJsRuntime, main: MainResolver, parked: Arc<ParkedLoggers>) {
    runtime.on_accept_prefix(PREFIX, move |pipe| serve(pipe, main.clone(), parked.clone()));
}

async fn serve(pipe: Pipe, main: MainResolver, parked: Arc<ParkedLoggers>) -> Result<(), BoxError> {
    let command = pipe.command().trim_start_matches(PREFIX).to_owned();
    let log = parked.root().fork("main");
    let opened = match (command.is_empty(), main()) {
        (true, _) => Err(WireError::new("R20019", "main-proxy missing command")),
        (false, None) => Err(WireError::new("R20020", "no main engine")),
        (false, Some(main)) => main
            .open_pipe(&command)
            .await
            .map_err(|error| WireError::new("R20020", format!("main engine refused {command}: {error}"))),
    };
    let main_pipe = match opened {
        Ok(main_pipe) => main_pipe,
        Err(wire) => {
            log.warn(&format!("{} {}", wire.code, wire.message.as_deref().unwrap_or_default()), None);
            pipe.close_read().await?;
            pipe.close_write(Some(wire.to_reason())).await?;
            return Ok(());
        }
    };
    tokio::join!(copy(&pipe, &main_pipe), copy(&main_pipe, &pipe));
    let _ = main_pipe.close_read().await;
    pipe.close_read().await?;
    Ok(())
}

/// One direction: `from`'s END or RESET is passed on to `to`.
async fn copy(from: &Pipe, to: &Pipe) {
    loop {
        match from.receive(PIPE_CAP).await {
            Ok(Some(chunk)) => {
                if to.send(chunk).await.is_err() {
                    let _ = from.close_read().await;
                    return;
                }
            }
            Ok(None) => {
                let _ = to.close_write(None).await;
                return;
            }
            Err(PipeError::Reset(reason)) => {
                let _ = to.close_write(Some(reason)).await;
                return;
            }
            Err(error) => {
                let _ = to.close_write(Some(format!("main-proxy copy failed: {error}"))).await;
                return;
            }
        }
    }
}
