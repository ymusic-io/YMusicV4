//! The `http` protocol's hand-off for media requests the host serves itself (the player cache).
//! The bundle tags a cacheable SABR POST with `X-Ym-Af`; an accepted one is answered by the proxy,
//! and the tag never reaches the network either way.

use std::io;

use bytes::Bytes;
use futures::future::BoxFuture;
use futures::stream::BoxStream;
use ym_common::log::Logger;

/// The request header engine-js tags a cacheable SABR POST with.
pub const SABR_FORMATS_HEADER: &str = "X-Ym-Af";

pub struct ProxiedRequest {
    pub method: String,
    pub url: String,
    /// As the bundle sent them, the tag included.
    pub headers: Vec<(String, String)>,
    pub body: Bytes,
    /// The request as the host sends it without a proxy (no tag): what a miss forwards.
    pub network: reqwest::Request,
    pub client: reqwest::Client,
    pub log: Logger,
}

pub struct ProxiedResponse {
    pub status: u16,
    pub message: String,
    /// The final URL after redirects.
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: BoxStream<'static, io::Result<Bytes>>,
}

pub trait MediaProxy: Send + Sync {
    /// Whether a tagged request goes through [`Self::serve`].
    fn accepts(&self, method: &str, url: &str) -> bool;

    /// A network failure before the response head comes back as the error.
    fn serve(&self, request: ProxiedRequest) -> BoxFuture<'static, Result<ProxiedResponse, reqwest::Error>>;
}
