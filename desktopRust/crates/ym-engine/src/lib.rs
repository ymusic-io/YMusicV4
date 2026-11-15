//! The engine host: boots the remotely-updatable `engine.js` on QuickJS and serves its host
//! protocols (quickjs/HOST_PROTOCOLS.md in YMusicV4), wire-identical to the Kotlin host.

mod cache;
mod cache_protocol;
mod command;
mod cookie;
mod engine;
mod error;
mod head;
mod http;
mod http_cache;
mod logs;
mod main_proxy;
mod manager;
mod media_proxy;
pub mod player;
mod protocol;
mod settings;
pub mod yt;

pub use cache::{CacheEntry, CacheError, CacheMeta, Clock, DiskCacheStore, PutError};
pub use cookie::{CookieJar, CookiePersist, MemoryCookieJar, PersistentCookieJar};
pub use engine::{DynamicEngine, EngineConfig, MainResolver, MseAttachment};
pub use error::{EngineError, ErrorDetails, ErrorKind, WireError};
pub use http_cache::{DEFAULT_HTTP_CACHE_BYTES, HttpCache};
pub use logs::ParkedLoggers;
pub use manager::EngineManager;
pub use media_proxy::{MediaProxy, ProxiedRequest, ProxiedResponse, SABR_FORMATS_HEADER};
pub use settings::FeatureLookup;
