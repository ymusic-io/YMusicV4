//! Our QuickJS binding: the vendored engine and C pipe transport from the YMusicV4 checkout, with
//! the multiplexed, flow-controlled pipe layer on a dedicated engine thread.

mod ffi;
mod runtime;
mod timers;

pub use runtime::{
    AcceptRegistration, BoxError, Handler, LogHandler, LogLevel, PIPE_CAP, Pipe, PipeError, QuickJsRuntime,
    WeakRuntime, compile,
};
