//! `setTimeout` / `setInterval` scheduled on the host: JS opens the command, sends the delay as a
//! big-endian `i64` of milliseconds, receives one byte per fire, and closes the pipe to clear.

use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use crate::runtime::{BoxError, Pipe, QuickJsRuntime};

const TICK: &[u8] = &[1];
/// HTML5's `setInterval` floor; also keeps a 0 ms interval from hot-looping the engine thread.
const MIN_INTERVAL_MS: u64 = 4;
const BACKGROUND_GRID_MS: u64 = 1_000;
/// Shorter timeouts keep their time in the background: media appends and task yields run on them.
const BACKGROUND_ALIGN_MIN_TIMEOUT_MS: u64 = 200;

static IN_BACKGROUND: AtomicBool = AtomicBool::new(false);
static GRID_ORIGIN: OnceLock<Instant> = OnceLock::new();

pub(crate) fn set_in_background(background: bool) {
    IN_BACKGROUND.store(background, Ordering::Relaxed);
}

/// In the background an interval, or a timeout of `BACKGROUND_ALIGN_MIN_TIMEOUT_MS` or more, fires on
/// the first line of a 1 s grid shared by every runtime at or after its due time: they all fire
/// together, an interval at most once a second.
fn timer_wait(millis: u64, repeat: bool) -> u64 {
    if !IN_BACKGROUND.load(Ordering::Relaxed) || (!repeat && millis < BACKGROUND_ALIGN_MIN_TIMEOUT_MS) {
        return millis;
    }
    let now = GRID_ORIGIN.get_or_init(Instant::now).elapsed().as_millis() as u64;
    (now + millis).div_ceil(BACKGROUND_GRID_MS) * BACKGROUND_GRID_MS - now
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
                _ = tokio::time::sleep(Duration::from_millis(timer_wait(millis, repeat))) => {
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
