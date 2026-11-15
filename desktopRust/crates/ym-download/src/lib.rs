//! The offline download manager: a persistent task queue, SABR downloads via `ym-ytmedia`, and an
//! ffmpeg mux into the user's download folder. Without ffmpeg only M4A is offered: the raw stream.

pub mod error;
pub mod ffmpeg;
pub mod model;
pub mod names;
pub mod options;
pub mod pipeline;
pub mod selection;
pub mod service;
pub mod spec;
pub mod storage;
pub mod store;
pub mod streams;
pub mod tags;

pub use error::DownloadError;
pub use model::*;
pub use service::{DownloadConfig, DownloadService};
