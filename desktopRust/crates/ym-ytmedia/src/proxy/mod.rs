//! The media router behind the player cache: the player engine's cacheable SABR POSTs (engine-js
//! tags them with `X-Ym-Af`) are answered from the cache when it holds the segments, else
//! forwarded and mirrored into it on the way through. Requests go out exactly as the host builds
//! them, so cookies and User-Agent stay the session's.

mod tee;

use std::io::{self, Read};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use bytes::Bytes;
use futures::StreamExt;
use tokio::runtime::Handle;
use ym_common::log::Logger;

use crate::cache::{CacheError, MediaCache, SabrServe, reader_stream, serve_sabr};
use crate::sabr::messages::{ADAPTIVE_FORMATS_HEADER, AbrRequest, parse_adaptive_formats_header};
use crate::ump::ByteStream;
use tee::SabrCacheTee;

pub struct ProxyRequest {
    pub url: String,
    pub method: String,
    /// As the engine sent them, `X-Ym-Af` included.
    pub headers: Vec<(String, String)>,
    pub body: Bytes,
}

/// A hit's body streams from pinned span files; a miss's is the network body.
pub struct ProxyResponse {
    pub status: u16,
    pub reason: String,
    /// The effective URL: the final one after redirects.
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: ByteStream,
}

pub struct LocalMediaProxy {
    cache: Arc<MediaCache>,
    tokio: Handle,
    log: Logger,
    cache_enabled: Box<dyn Fn() -> bool + Send + Sync>,
    /// Set once the on-disk cache proved broken (a span read or write failed; a deleted span file
    /// is only a miss): every later request of this process goes to the network uncached.
    bypass: AtomicBool,
}

enum Decision {
    Serve(ProxyResponse),
    Forward { fill: bool },
}

impl LocalMediaProxy {
    /// `tokio` runs the blocking cache work; `cache_enabled` is asked per request (false sends it
    /// straight to the network, neither reading nor filling the cache).
    pub fn new(
        cache: Arc<MediaCache>,
        tokio: Handle,
        log: Logger,
        cache_enabled: impl Fn() -> bool + Send + Sync + 'static,
    ) -> Arc<Self> {
        Arc::new(Self {
            cache,
            tokio,
            log: log.fork("mediaproxy"),
            cache_enabled: Box::new(cache_enabled),
            bypass: AtomicBool::new(false),
        })
    }

    pub fn is_sabr_post(url: &str, method: &str) -> bool {
        method.eq_ignore_ascii_case("POST") && url.contains("/videoplayback") && url.contains("sabr=1")
    }

    /// A hit is served from disk; anything else goes out through `network` (the request as the
    /// host would send it, without `X-Ym-Af`), mirrored into the cache when it is a VOD SABR
    /// response. A failing cache never fails the request.
    pub async fn serve_request<F, Fut>(
        self: &Arc<Self>,
        request: ProxyRequest,
        log: &Logger,
        network: F,
    ) -> Result<ProxyResponse, reqwest::Error>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Result<reqwest::Response, reqwest::Error>>,
    {
        let log = log.fork("mediaproxy");
        let url = request.url.clone();
        let (proxy, lookup_log) = (self.clone(), log.clone());
        let decision = match self.tokio.spawn_blocking(move || proxy.decide(&request, &lookup_log)).await {
            Ok(decision) => decision,
            Err(error) => {
                log.warn("cache lookup did not finish, forwarding", Some(&error));
                Decision::Forward { fill: false }
            }
        };
        match decision {
            Decision::Serve(response) => Ok(response),
            Decision::Forward { fill } => self.forward(&url, network, fill, log).await,
        }
    }

    fn decide(self: &Arc<Self>, request: &ProxyRequest, log: &Logger) -> Decision {
        if !(self.cache_enabled)() || self.bypass.load(Ordering::Relaxed) || self.cache.blobs.max_size() <= 0 {
            return Decision::Forward { fill: false };
        }
        match self.serve_from_cache(request, log) {
            Ok(Some(response)) => return Decision::Serve(response),
            Ok(None) => {}
            Err(error) => {
                log.warn(&format!("cache serve failed, forwarding: {}", short_url(&request.url)), Some(&error));
                if error.is_storage_fault() {
                    self.on_storage_fault(&error);
                }
            }
        }
        Decision::Forward { fill: !self.bypass.load(Ordering::Relaxed) }
    }

    fn on_storage_fault(&self, error: &(dyn std::error::Error + 'static)) {
        if !self.bypass.swap(true, Ordering::Relaxed) {
            self.log.warn("player cache storage broken — bypassing the cache for this session", Some(error));
        }
    }

    fn serve_from_cache(
        self: &Arc<Self>,
        request: &ProxyRequest,
        log: &Logger,
    ) -> Result<Option<ProxyResponse>, CacheError> {
        if !is_sabr_cache(&request.url) || request.method != "POST" {
            return Ok(None);
        }
        let Some(abr) = parse_sabr_request(request, log) else { return Ok(None) };
        log.debug(|| sabr_request_summary(&abr));
        let started = Instant::now();
        match serve_sabr(&self.cache, &abr)? {
            SabrServe::Hit { body, cached_bytes, segment_count, served_ranges } => {
                log.debug(|| {
                    format!(
                        "HIT sabr: served=[{served_ranges}] ({segment_count} segments, {cached_bytes} bytes, built in {:?})",
                        started.elapsed()
                    )
                });
                self.cache.add_hit_bytes(cached_bytes)?;
                Ok(Some(ump_response(&request.url, self.read_stream(body), None)))
            }
            SabrServe::Backoff { body } => {
                log.debug(|| format!("BACKOFF sabr: requester buffered ahead ({:?})", started.elapsed()));
                let length = body.len() as u64;
                Ok(Some(ump_response(
                    &request.url,
                    futures::stream::once(async { Ok(Bytes::from(body)) }).boxed(),
                    Some(length),
                )))
            }
            SabrServe::Miss { reason } => {
                log.debug(|| format!("MISS sabr: {reason} ({:?})", started.elapsed()));
                Ok(None)
            }
        }
    }

    /// Streams a hit off its pinned spans on a blocking worker; a read failure is a storage fault.
    fn read_stream(self: &Arc<Self>, reader: impl Read + Send + 'static) -> ByteStream {
        let proxy = self.clone();
        reader_stream(reader, &self.tokio, move |error| proxy.on_storage_fault(error))
    }

    async fn forward<F, Fut>(
        self: &Arc<Self>,
        url: &str,
        network: F,
        fill: bool,
        log: Logger,
    ) -> Result<ProxyResponse, reqwest::Error>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Result<reqwest::Response, reqwest::Error>>,
    {
        let response = network().await?;
        let status = response.status();
        let mut headers = Vec::new();
        if let Some(content_type) =
            response.headers().get(reqwest::header::CONTENT_TYPE).and_then(|value| value.to_str().ok())
        {
            headers.push(("Content-Type".to_owned(), content_type.to_owned()));
        }
        if let Some(length) = response.content_length() {
            headers.push(("Content-Length".to_owned(), length.to_string()));
        }
        let effective_url = response.url().to_string();
        log.debug(|| format!("cache-miss → network {}: {}", status.as_u16(), short_url(&effective_url)));
        let network_body =
            response.bytes_stream().map(|item| item.map_err(|error| io::Error::other(error.without_url())));
        let body = if fill && status.is_success() && is_sabr_cache(url) {
            self.tee(network_body, log)
        } else {
            network_body.boxed()
        };
        Ok(ProxyResponse {
            status: status.as_u16(),
            reason: status.canonical_reason().unwrap_or_default().to_owned(),
            url: effective_url,
            headers,
            body,
        })
    }

    /// The network body passes through untouched; a copy goes to a blocking worker that mirrors
    /// it into the cache and finishes when the body ends or is dropped.
    fn tee(
        self: &Arc<Self>,
        network: impl futures::Stream<Item = io::Result<Bytes>> + Send + 'static,
        log: Logger,
    ) -> ByteStream {
        let (sender, receiver) = std::sync::mpsc::channel::<Bytes>();
        let (cache, proxy) = (self.cache.clone(), self.clone());
        self.tokio.spawn_blocking(move || {
            let mut tee = SabrCacheTee::new(cache, log, Box::new(move |error| proxy.on_storage_fault(error)));
            for chunk in receiver {
                tee.feed(&chunk);
            }
            tee.finish();
        });
        network
            .map(move |item| {
                if let Ok(chunk) = &item {
                    // Fails only when the worker is gone; the pass-through goes on uncached.
                    let _ = sender.send(chunk.clone());
                }
                item
            })
            .boxed()
    }
}

fn ump_response(url: &str, body: ByteStream, length: Option<u64>) -> ProxyResponse {
    let mut headers = vec![("Content-Type".to_owned(), "application/vnd.yt-ump".to_owned())];
    if let Some(length) = length {
        headers.push(("Content-Length".to_owned(), length.to_string()));
    }
    ProxyResponse { status: 200, reason: "OK".to_owned(), url: url.to_owned(), headers, body }
}

/// VOD SABR: live and `noclen=1` streams stay uncached.
fn is_sabr_cache(url: &str) -> bool {
    url.contains("sabr=1") && !url.contains("live=1") && !url.contains("noclen=1")
}

/// Host and path: the query carries signatures and tokens.
fn short_url(url: &str) -> String {
    match url::Url::parse(url) {
        Ok(parsed) => format!("{}{}", parsed.host_str().unwrap_or_default(), parsed.path()),
        Err(_) => "<unparsable url>".to_owned(),
    }
}

/// A malformed catalog header is an engine-js bug: logged, and the request still plans without it.
fn parse_sabr_request(request: &ProxyRequest, log: &Logger) -> Option<AbrRequest> {
    let mut abr = match AbrRequest::parse(&request.body) {
        Ok(abr) => abr,
        Err(error) => {
            log.debug(|| format!("SABR request body not parsed, forwarding: {error}"));
            return None;
        }
    };
    let header = request.headers.iter().find(|(name, _)| name.eq_ignore_ascii_case(ADAPTIVE_FORMATS_HEADER));
    if let Some((_, value)) = header {
        match parse_adaptive_formats_header(value) {
            Ok(formats) => abr.adaptive_formats = formats,
            Err(error) => log.warn(&format!("ignoring malformed {ADAPTIVE_FORMATS_HEADER}: {error}"), None),
        }
    }
    Some(abr)
}

/// What the player asks for: empty `selV` (or tracks=1) is audio-only; `buffered` is what it holds.
fn sabr_request_summary(request: &AbrRequest) -> String {
    let itags = |formats: &[crate::sabr::messages::FormatId]| {
        formats.iter().map(|format| format.itag.to_string()).collect::<Vec<_>>().join(",")
    };
    let buffered = request
        .buffered_ranges
        .iter()
        .map(|range| {
            format!(
                "{}@{}-{}ms[seg{}-{}]",
                range.format_id.itag,
                range.start_ms,
                range.end_ms(),
                range.start_segment_index,
                range.end_segment_index
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "sabr-req t={}ms tracks={:?} selA=[{}] selV=[{}] avail={} buffered=[{buffered}]",
        request.player_time_ms,
        request.enabled_track_types_bitfield,
        itags(&request.selected_audio_formats),
        itags(&request.selected_video_formats),
        request.adaptive_formats.len()
    )
}
