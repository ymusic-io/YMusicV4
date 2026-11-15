//! `setTimeout` / `setInterval` scheduled on the host: JS opens the command, sends the delay as a
//! big-endian `i64` of milliseconds, receives one byte per fire, and closes the pipe to clear.

use std::time::Duration;

use crate::runtime::{BoxError, Pipe, QuickJsRuntime};

const TICK: &[u8] = &[1];
/// HTML5's `setInterval` floor; also keeps a 0 ms interval from hot-looping the engine thread.
const MIN_INTERVAL_MS: u64 = 4;

pub(crate) fn install(runtime: &QuickJsRuntime) {
    runtime.on_accept("setTimeout", |pipe| run_timer(pipe, false));
    runtime.on_accept("setInterval", |pipe| run_timer(pipe, true));
}

async fn run_timer(pipe: Pipe, repeat: bool) -> Result<(), BoxError> {
    // A clear before the delay fully arrives reads as 0.
    let millis = match pipe.read_exact(8).await? {
        Some(frame) => i64::from_be_bytes(frame[..8].try_into()?).max(0) as u64,
        None => 0,
    };
    let millis = if repeat { millis.max(MIN_INTERVAL_MS) } else { millis };
    {
        let cleared = pipe.wait_end();
        tokio::pin!(cleared);
        loop {
            tokio::select! {
                _ = &mut cleared => break,
                _ = tokio::time::sleep(Duration::from_millis(millis)) => {
                    pipe.send(TICK).await?;
                    if !repeat {
                        break;
                    }
                }
            }
        }
    }
    pipe.close_write(None).await?;
    pipe.close_read().await?;
    Ok(())
}
