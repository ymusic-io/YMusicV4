//! `setTimeout` / `setInterval` scheduled on the host: JS opens the command, sends the delay as a
//! big-endian `i64` of milliseconds, receives one byte per fire, and closes the pipe to clear.

use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use crate::runtime::{BoxError, Pipe, QuickJsRuntime};

const TICK: &[u8] = &[1];
/// HTML5's `setInterval` floor; also keeps a 0 ms interval from hot-looping the engine thread.
const MIN_INTERVAL_MS: u64 = 4;
const BACKGROUND_INTERVAL_MS: u64 = 1_000;

static IN_BACKGROUND: AtomicBool = AtomicBool::new(false);
static GRID_ORIGIN: OnceLock<Instant> = OnceLock::new();

pub(crate) fn set_in_background(background: bool) {
    IN_BACKGROUND.store(background, Ordering::Relaxed);
}

/// In the background an interval under a second waits for the next line of a shared 1 s grid, so
/// every runtime's intervals fire together in one wake-up a second.
fn interval_wait(millis: u64) -> u64 {
    if !IN_BACKGROUND.load(Ordering::Relaxed) || millis >= BACKGROUND_INTERVAL_MS {
        return millis;
    }
    let elapsed = GRID_ORIGIN.get_or_init(Instant::now).elapsed().as_millis() as u64;
    BACKGROUND_INTERVAL_MS - elapsed % BACKGROUND_INTERVAL_MS
}

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
                _ = tokio::time::sleep(Duration::from_millis(if repeat { interval_wait(millis) } else { millis })) => {
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
