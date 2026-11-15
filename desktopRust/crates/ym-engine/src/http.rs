//! The `"http"` host protocol (HOST_PROTOCOLS §1): one control pipe per exchange, plus a body
//! pipe the host opens lazily on the transport's first pull when the request has a body.
//!
//! Expected failures RESET the control pipe with an origin-classified `WireError` reason:
//! `R20001` for a request the host cannot build (caller bug), `network` for transport failures
//! before or after the head. Protocol violations error out of the handler (runtime backstop).
//!
//! With an [`HttpCache`], a GET follows its redirects here, hop by hop, each hop through the cache
//! (OkHttp's interceptor order), replaying the redirect rules reqwest applies so the wire is the
//! same: gzip is requested and decoded here, the cache keeping the bytes as they arrived.
//!
//! A request the bundle tags with `X-Ym-Af` goes to the [`MediaProxy`] when one accepts it, ahead
//! of the cache: the player cache answers or forwards it and the HTTP cache never sees it, as
//! Kotlin's `SabrProxyHttpClient` sits in front of OkHttp's cache.

use std::io::Write;
use std::sync::Arc;
use std::time::Duration;

use bytes::{Bytes, BytesMut};
use flate2::write::GzDecoder;
use futures::stream::BoxStream;
use futures::{Stream, StreamExt};
use reqwest::header::{
    ACCEPT, ACCEPT_ENCODING, AUTHORIZATION, CONTENT_ENCODING, CONTENT_LENGTH, CONTENT_TYPE, COOKIE, HeaderMap,
    HeaderName, HeaderValue, PROXY_AUTHORIZATION, REFERER, TRANSFER_ENCODING, WWW_AUTHENTICATE,
};
use serde_json::{Value, json};
use tokio::io::AsyncReadExt;
use url::Url;
use ym_common::http::{DEFAULT_EXECUTE_TIMEOUT, HttpClients};
use ym_common::log::{Logger, now_millis};
use ym_quickjs::{BoxError, PIPE_CAP, Pipe, PipeError, QuickJsRuntime, WeakRuntime};

use crate::error::WireError;
use crate::head::{HeadError, read_json_head};
use crate::http_cache::{
    CacheControl, CacheRequest, CacheWriter, CachedResponse, Decision, EntryMeta, Headers, HttpCache, combine, decide,
    handshake_of, invalidates_cache, is_cacheable, promises_body, vary_headers,
};
use crate::logs::{ParkedLoggers, prop_of, protocol_logger};
use crate::media_proxy::{MediaProxy, ProxiedRequest, SABR_FORMATS_HEADER};

pub(crate) fn install(
    runtime: &QuickJsRuntime,
    clients: HttpClients,
    cache: Option<Arc<HttpCache>>,
    proxy: Option<Arc<dyn MediaProxy>>,
    parked: Arc<ParkedLoggers>,
) {
    let weak = runtime.downgrade();
    runtime.on_accept("http", move |pipe| {
        serve(weak.clone(), pipe, clients.clone(), cache.clone(), proxy.clone(), parked.clone())
    });
}
/// reqwest's `Policy::limited(20)` in `HttpClients`.
const MAX_REDIRECTS: usize = 20;

struct Prepared {
    method: reqwest::Method,
    url: Url,
    /// As the bundle sent them, in order and spelling.
    headers: Vec<(String, String)>,
    follow_redirects: bool,
    body_command: Option<String>,
    timeout: Duration,
    /// The bundle's `X-Ym-Af` tag for the media proxy, never on the wire.
    media_tag: Option<String>,
}

/// A response ready for the client: the network's, or one the cache answered.
struct Delivery {
    status: u16,
    message: String,
    headers: Headers,
    final_url: String,
    cache: Option<&'static str>,
    body: DeliveryBody,
    /// The body is gzip as sent; decode it on the way out (reqwest's transparent gzip).
    gunzip: bool,
}

enum DeliveryBody {
    Network(NetworkBody),
    Cached(tokio::fs::File),
    Proxied(BoxStream<'static, std::io::Result<Bytes>>),
    Empty,
}

/// A network body, teed into its cache entry when it has one. Dropped before its end (the client
/// stopped reading), it reads on for up to [`DISCARD_TIMEOUT`] so the entry can still complete, as
/// OkHttp's `discard` on close does.
struct NetworkBody {
    response: Option<Box<reqwest::Response>>,
    writer: Option<CacheWriter>,
    label: String,
    log: Logger,
}

/// OkHttp's `DISCARD_STREAM_TIMEOUT_MILLIS`.
const DISCARD_TIMEOUT: Duration = Duration::from_millis(100);

impl NetworkBody {
    fn new(response: reqwest::Response, writer: Option<CacheWriter>, label: String, log: Logger) -> Self {
        Self { response: Some(Box::new(response)), writer, label, log }
    }

    async fn chunk(&mut self) -> reqwest::Result<Option<Bytes>> {
        let Some(response) = self.response.as_mut() else { return Ok(None) };
        match response.chunk().await {
            Ok(Some(chunk)) => {
                CacheWriter::write(&mut self.writer, &chunk).await;
                Ok(Some(chunk))
            }
            Ok(None) => {
                self.response = None;
                Ok(None)
            }
            Err(error) => {
                self.writer = None;
                self.response = None;
                Err(error)
            }
        }
    }

    /// Commits the cache entry in a task of its own: a client that leaves now cannot undo it.
    async fn finish(&mut self) {
        let Some(writer) = self.writer.take() else { return };
        let committed = tokio::spawn(commit(writer, self.label.clone(), self.log.clone()));
        if let Err(error) = committed.await {
            self.log.warn("R90715 http cache commit task failed", Some(&error));
        }
    }
}

async fn commit(writer: CacheWriter, label: String, log: Logger) {
    if let Some(stored) = writer.finish().await {
        log.info(&format!("cache stored {label} ({stored} bytes)"));
    }
}

impl Drop for NetworkBody {
    fn drop(&mut self) {
        let Some(writer) = self.writer.take() else { return };
        let Ok(runtime) = tokio::runtime::Handle::try_current() else { return };
        let (response, label, log) = (self.response.take(), self.label.clone(), self.log.clone());
        runtime.spawn(async move {
            let mut slot = Some(writer);
            if let Some(mut response) = response {
                let rest = tokio::time::timeout(DISCARD_TIMEOUT, async {
                    while let Some(chunk) = response.chunk().await? {
                        CacheWriter::write(&mut slot, &chunk).await;
                    }
                    Ok::<(), reqwest::Error>(())
                })
                .await;
                if !matches!(rest, Ok(Ok(()))) {
                    return;
                }
            }
            if let Some(writer) = slot {
                commit(writer, label, log).await;
            }
        });
    }
}

enum Watch {
    Ended,
    Cancelled,
    Data,
    Failed(PipeError),
}

async fn serve(
    runtime: WeakRuntime,
    pipe: Pipe,
    clients: HttpClients,
    cache: Option<Arc<HttpCache>>,
    proxy: Option<Arc<dyn MediaProxy>>,
    parked: Arc<ParkedLoggers>,
) -> Result<(), BoxError> {
    let (head, trailing) = match read_json_head(&pipe).await {
        Ok(parsed) => parsed,
        // The client abandoned the exchange before it started.
        Err(HeadError::Pipe(PipeError::Reset(_))) => return finish(&pipe).await,
        Err(error) => {
            let log = parked.root().fork("http");
            return reject(&pipe, &log, &format!("invalid request: {error}")).await;
        }
    };
    if !head.is_object() {
        return reject(&pipe, parked.root(), "invalid request: head is not a JSON object").await;
    }
    if !trailing.is_empty() {
        return Err("R20002 unexpected data on the control pipe".into());
    }
    let log = protocol_logger(&parked, &head, "http", prop_of(&head, "url"));
    let prepared = match prepare(&head) {
        Ok(prepared) => prepared,
        Err(error) => return reject(&pipe, &log, &format!("invalid request: {error}")).await,
    };

    // Armed right after the head: the control pipe carries nothing more, so a RESET (the client's
    // cancel) is observable at every stage, including a transport parked before the body pull.
    let watcher = async {
        match pipe.receive(1).await {
            Ok(None) => Watch::Ended,
            Ok(Some(_)) => Watch::Data,
            Err(PipeError::Reset(_)) => Watch::Cancelled,
            Err(error) => Watch::Failed(error),
        }
    };
    let exchange = exchange(&pipe, prepared, &clients, cache.as_ref(), proxy.as_ref(), runtime, &log);
    tokio::pin!(watcher, exchange);
    let mut watching = true;
    loop {
        tokio::select! {
            result = &mut exchange => {
                result?;
                break;
            }
            event = &mut watcher, if watching => match event {
                Watch::Ended => watching = false,
                Watch::Cancelled => break,
                Watch::Data => return Err("R20002 unexpected data on the control pipe".into()),
                Watch::Failed(error) => return Err(error.into()),
            },
        }
    }
    finish(&pipe).await
}

async fn finish(pipe: &Pipe) -> Result<(), BoxError> {
    pipe.close_write(None).await?;
    pipe.close_read().await?;
    Ok(())
}

async fn reject(pipe: &Pipe, log: &Logger, message: &str) -> Result<(), BoxError> {
    log.warn(&format!("R20001 {message}"), None);
    reset(pipe, "R20001", message).await
}

async fn reset(pipe: &Pipe, code: &str, message: &str) -> Result<(), BoxError> {
    pipe.close_write(Some(WireError::new(code, message).to_reason())).await?;
    pipe.close_read().await?;
    Ok(())
}

fn prepare(head: &Value) -> Result<Prepared, BoxError> {
    let method = head.get("method").and_then(Value::as_str).unwrap_or("GET");
    let url = head.get("url").and_then(Value::as_str).unwrap_or_default();
    let mut headers = Vec::new();
    let mut media_tag = None;
    if let Some(pairs) = head.get("headers").and_then(Value::as_array) {
        for pair in pairs {
            let name = pair.get(0).and_then(Value::as_str).ok_or("header entry without a name")?;
            let value = pair.get(1).and_then(Value::as_str).ok_or("header entry without a value")?;
            if name.eq_ignore_ascii_case(SABR_FORMATS_HEADER) {
                media_tag = Some(value.to_owned());
                continue;
            }
            HeaderName::from_bytes(name.as_bytes())?;
            HeaderValue::from_str(value)?;
            headers.push((name.to_owned(), value.to_owned()));
        }
    }
    let opts = head.get("opts");
    let follow_redirects = opts.and_then(|opts| opts.get("followRedirects")).and_then(Value::as_bool).unwrap_or(true);
    let timeout = opts
        .and_then(|opts| opts.get("timeoutMs"))
        .and_then(Value::as_u64)
        .map_or(DEFAULT_EXECUTE_TIMEOUT, Duration::from_millis);
    let url = Url::parse(url)?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err(format!("URL scheme is not allowed: {}", url.scheme()).into());
    }
    Ok(Prepared {
        method: reqwest::Method::from_bytes(method.as_bytes())?,
        url,
        headers,
        follow_redirects,
        body_command: head.get("bodyCmd").and_then(Value::as_str).map(str::to_owned),
        timeout,
        media_tag,
    })
}

fn header_map<'a>(headers: impl Iterator<Item = (&'a str, &'a str)>) -> Result<HeaderMap, BoxError> {
    let mut map = HeaderMap::new();
    for (name, value) in headers {
        map.append(HeaderName::from_bytes(name.as_bytes())?, HeaderValue::from_str(value)?);
    }
    Ok(map)
}

fn response_headers(response: &reqwest::Response) -> Headers {
    headers_of(response.headers())
}

fn headers_of(map: &HeaderMap) -> Headers {
    Headers::new(
        map.iter()
            .map(|(name, value)| (name.as_str().to_owned(), String::from_utf8_lossy(value.as_bytes()).into_owned()))
            .collect(),
    )
}

enum BodyState {
    Unopened(WeakRuntime, String),
    Open(Pipe),
}

/// The request body, read from its own pipe, opened on the transport's first pull: a response
/// that never needs the body (early error, redirect) never transfers it.
fn body_stream(runtime: WeakRuntime, command: String) -> impl Stream<Item = Result<Bytes, BoxError>> + Send + 'static {
    futures::stream::try_unfold(Some(BodyState::Unopened(runtime, command)), |state| async move {
        let pipe = match state {
            None => return Ok(None),
            Some(BodyState::Open(pipe)) => pipe,
            Some(BodyState::Unopened(runtime, command)) => {
                runtime.upgrade().ok_or(PipeError::RuntimeClosed)?.open_pipe(&command).await?
            }
        };
        match pipe.receive(PIPE_CAP).await? {
            Some(chunk) => Ok(Some((chunk, Some(BodyState::Open(pipe))))),
            None => {
                finish(&pipe).await?;
                Ok(None)
            }
        }
    })
}

async fn exchange(
    pipe: &Pipe,
    prepared: Prepared,
    clients: &HttpClients,
    cache: Option<&Arc<HttpCache>>,
    proxy: Option<&Arc<dyn MediaProxy>>,
    runtime: WeakRuntime,
    log: &Logger,
) -> Result<(), BoxError> {
    log.info(&format!("{} {}{}", prepared.method, prepared.url.host_str().unwrap_or_default(), prepared.url.path()));
    log.debug(|| {
        format!(
            "cookies={}",
            cookie_names(prepared.headers.iter().map(|(name, value)| (name.as_str(), value.as_str())))
        )
    });

    let timeout = prepared.timeout;
    let proxy = proxy
        .filter(|proxy| prepared.media_tag.is_some() && proxy.accepts(prepared.method.as_str(), prepared.url.as_str()));
    let delivery = match (proxy, cache) {
        (Some(proxy), _) => {
            tokio::time::timeout(timeout, execute_proxied(proxy, clients, prepared, runtime, log)).await
        }
        (None, Some(cache)) if prepared.method == reqwest::Method::GET && prepared.body_command.is_none() => {
            tokio::time::timeout(timeout, execute_cached(cache, clients, &prepared, log)).await
        }
        _ => tokio::time::timeout(timeout, execute_plain(clients, prepared, cache, runtime)).await,
    };
    let delivery = match delivery {
        Err(_) => {
            log.warn("request failed: timeout", None);
            return reset(pipe, "network", "request failed: timeout").await;
        }
        Ok(Err(message)) => {
            let message = format!("request failed: {message}");
            log.warn(&message, None);
            return reset(pipe, "network", &message).await;
        }
        Ok(Ok(delivery)) => delivery,
    };
    deliver(pipe, delivery, log).await
}

/// Through reqwest as configured: its redirects, its transparent gzip.
async fn execute_plain(
    clients: &HttpClients,
    prepared: Prepared,
    cache: Option<&Arc<HttpCache>>,
    runtime: WeakRuntime,
) -> Result<Delivery, String> {
    let client = clients.pick(prepared.follow_redirects);
    let headers = header_map(prepared.headers.iter().map(|(name, value)| (name.as_str(), value.as_str())))
        .map_err(|error| error.to_string())?;
    let mut builder = client.request(prepared.method.clone(), prepared.url.clone()).headers(headers);
    if let Some(body_command) = prepared.body_command {
        builder = builder.body(reqwest::Body::wrap_stream(body_stream(runtime, body_command)));
    }
    let request = builder.build().map_err(|error| error_chain(&error.without_url()))?;
    let response = client.execute(request).await.map_err(|error| error_chain(&error.without_url()))?;
    if let Some(cache) = cache.filter(|_| invalidates_cache(prepared.method.as_str())) {
        cache.remove_async(prepared.url.as_str()).await;
    }
    Ok(Delivery {
        status: response.status().as_u16(),
        message: response.status().canonical_reason().unwrap_or_default().to_owned(),
        headers: response_headers(&response),
        final_url: response.url().to_string(),
        cache: None,
        body: DeliveryBody::Network(NetworkBody::new(response, None, String::new(), Logger::none())),
        gunzip: false,
    })
}

async fn read_body(runtime: WeakRuntime, command: &str) -> Result<Bytes, BoxError> {
    let pipe = runtime.upgrade().ok_or(PipeError::RuntimeClosed)?.open_pipe(command).await?;
    let mut body = BytesMut::new();
    while let Some(chunk) = pipe.receive(PIPE_CAP).await? {
        body.extend_from_slice(&chunk);
    }
    finish(&pipe).await?;
    Ok(body.freeze())
}

/// Through the media proxy, which plans from the whole request body and forwards a miss as
/// [`execute_plain`] would send it (tag removed). Never cached here, never invalidating.
async fn execute_proxied(
    proxy: &Arc<dyn MediaProxy>,
    clients: &HttpClients,
    prepared: Prepared,
    runtime: WeakRuntime,
    log: &Logger,
) -> Result<Delivery, String> {
    let client = clients.pick(prepared.follow_redirects).clone();
    let headers = header_map(prepared.headers.iter().map(|(name, value)| (name.as_str(), value.as_str())))
        .map_err(|error| error.to_string())?;
    let mut builder = client.request(prepared.method.clone(), prepared.url.clone()).headers(headers);
    let body = match &prepared.body_command {
        Some(command) => {
            let body = read_body(runtime, command).await.map_err(|error| format!("request body: {error}"))?;
            builder = builder.body(body.clone());
            body
        }
        None => Bytes::new(),
    };
    let network = builder.build().map_err(|error| error_chain(&error.without_url()))?;
    let mut sent = prepared.headers;
    sent.extend(prepared.media_tag.map(|tag| (SABR_FORMATS_HEADER.to_owned(), tag)));
    let request = ProxiedRequest {
        method: prepared.method.to_string(),
        url: prepared.url.to_string(),
        headers: sent,
        body,
        network,
        client,
        log: log.clone(),
    };
    let response = proxy.serve(request).await.map_err(|error| error_chain(&error.without_url()))?;
    Ok(Delivery {
        status: response.status,
        message: response.message,
        headers: Headers::new(response.headers),
        final_url: response.url,
        cache: None,
        body: DeliveryBody::Proxied(response.body),
        gunzip: false,
    })
}

/// One hop's answer, before the redirect decision.
struct Hop {
    status: u16,
    message: String,
    headers: Headers,
    cache: Option<&'static str>,
    body: DeliveryBody,
}

/// A GET through the cache, following redirects hop by hop with the header edits reqwest's client
/// defaults, `Decompression` and `FollowRedirect` layers make, on a `HeaderMap` as they do (its
/// removals reorder, and the order goes on the wire): `Accept: */*` and `Accept-Encoding: gzip`
/// when absent; each hop starts from the request's headers, drops the sensitive ones when it
/// changes origin and sets `Referer` to the previous hop.
async fn execute_cached(
    cache: &Arc<HttpCache>,
    clients: &HttpClients,
    prepared: &Prepared,
    log: &Logger,
) -> Result<Delivery, String> {
    let mut url = prepared.url.clone();
    let mut base = header_map(prepared.headers.iter().map(|(name, value)| (name.as_str(), value.as_str())))
        .map_err(|error| error.to_string())?;
    base.entry(ACCEPT).or_insert(HeaderValue::from_static("*/*"));
    base.entry(ACCEPT_ENCODING).or_insert(HeaderValue::from_static("gzip"));
    let mut headers = base.clone();
    let mut previous: Vec<Url> = Vec::new();
    loop {
        let hop = cached_hop(cache, clients, &url, &headers, log).await?;
        let next = match hop.status {
            301 | 302 | 303 | 307 | 308 if prepared.follow_redirects => {
                hop.headers.get("Location").and_then(|location| url.join(location).ok())
            }
            _ => None,
        };
        let Some(next) = next else {
            let gunzip = hop.headers.values("Content-Encoding").first() == Some(&"gzip");
            let mut headers = hop.headers;
            if gunzip {
                headers.remove_all("Content-Encoding");
                headers.remove_all("Content-Length");
            }
            return Ok(Delivery {
                status: hop.status,
                message: hop.message,
                headers,
                final_url: url.to_string(),
                cache: hop.cache,
                body: hop.body,
                gunzip,
            });
        };
        drain(hop.body, log).await;
        previous.push(url.clone());
        if previous.len() > MAX_REDIRECTS {
            return Err("too many redirects".to_owned());
        }
        if !matches!(next.scheme(), "http" | "https") {
            return Err(format!("URL scheme is not allowed: {}", next.scheme()));
        }
        if hop.status == 303 {
            for payload in [CONTENT_TYPE, CONTENT_LENGTH, CONTENT_ENCODING, TRANSFER_ENCODING] {
                base.remove(payload);
            }
        }
        headers = base.clone();
        let cross_origin = next.host_str() != url.host_str()
            || next.port_or_known_default() != url.port_or_known_default()
            || next.scheme() != url.scheme();
        if cross_origin {
            headers.remove(AUTHORIZATION);
            headers.remove(COOKIE);
            headers.remove("cookie2");
            headers.remove(PROXY_AUTHORIZATION);
            headers.remove(WWW_AUTHENTICATE);
        }
        if !(next.scheme() == "http" && url.scheme() == "https") {
            let mut referer = url.clone();
            let _ = referer.set_username("");
            let _ = referer.set_password(None);
            referer.set_fragment(None);
            if let Ok(value) = HeaderValue::from_str(referer.as_str()) {
                headers.insert(REFERER, value);
            }
        }
        url = next;
    }
}

/// OkHttp's `CacheInterceptor` for one hop.
async fn cached_hop(
    cache: &Arc<HttpCache>,
    clients: &HttpClients,
    url: &Url,
    header_map: &HeaderMap,
    log: &Logger,
) -> Result<Hop, String> {
    let label = format!("{}{}", url.host_str().unwrap_or_default(), url.path());
    let headers = &headers_of(header_map);
    let candidate = cache.get_async("GET", url.as_str(), headers).await;
    let request = CacheRequest { url: url.as_str(), headers };
    let decision = decide(now_millis(), &request, candidate.as_ref().map(|cached| &cached.meta));
    let (cached, conditional) = match decision {
        Decision::Unsatisfiable => {
            return Ok(Hop {
                status: 504,
                message: "Unsatisfiable Request (only-if-cached)".to_owned(),
                headers: Headers::default(),
                cache: None,
                body: DeliveryBody::Empty,
            });
        }
        Decision::Hit { warnings } => {
            let cached = candidate.expect("a hit has a candidate");
            log.info(&format!("cache hit {label}"));
            let mut headers = cached.meta.response_headers.clone();
            for warning in warnings {
                headers.add("Warning", warning);
            }
            return Ok(Hop {
                status: cached.meta.code,
                message: canonical_reason(cached.meta.code),
                headers,
                cache: Some("hit"),
                body: DeliveryBody::Cached(tokio::fs::File::from_std(cached.body)),
            });
        }
        Decision::Conditional { name, value } => {
            log.info(&format!("cache conditional {label} ({name})"));
            (candidate, Some((name, value)))
        }
        Decision::Network => {
            log.debug(|| format!("cache miss {label}"));
            (None, None)
        }
    };

    let was_conditional = conditional.is_some();
    let mut sent_map = header_map.clone();
    if let Some((name, value)) = conditional {
        sent_map.append(name, HeaderValue::from_str(&value).map_err(|error| error.to_string())?);
    }
    let sent_headers = headers_of(&sent_map);
    let request =
        clients.raw.get(url.clone()).headers(sent_map).build().map_err(|error| error_chain(&error.without_url()))?;
    let sent_millis = now_millis();
    let response = clients.raw.execute(request).await.map_err(|error| error_chain(&error.without_url()))?;
    let received_millis = now_millis();
    let status = response.status().as_u16();
    let network_headers = response_headers(&response);

    if let Some(cached) = cached.filter(|_| status == 304) {
        log.info(&format!("cache 304 {label}: served from cache"));
        let combined = combine(&cached.meta.response_headers, &network_headers);
        let refreshed = refreshed_meta(&cached, &sent_headers, &combined, sent_millis, received_millis);
        let (key, sequence) = cached.key();
        cache.update_async(key.to_owned(), sequence, refreshed).await;
        return Ok(Hop {
            status: cached.meta.code,
            message: canonical_reason(cached.meta.code),
            headers: combined,
            cache: Some("conditional"),
            body: DeliveryBody::Cached(tokio::fs::File::from_std(cached.body)),
        });
    }
    let cache_state = was_conditional.then_some("conditional");

    let mut writer = None;
    if promises_body("GET", status, &network_headers)
        && is_cacheable(status, &network_headers, &CacheControl::parse(&sent_headers))
    {
        let meta = EntryMeta {
            url: url.to_string(),
            request_method: "GET".to_owned(),
            vary_headers: vary_headers(&sent_headers, &network_headers),
            http_1_0: response.version() == reqwest::Version::HTTP_10,
            code: status,
            message: wire_reason(&response),
            response_headers: network_headers.clone(),
            sent_millis,
            received_millis,
            handshake: (url.scheme() == "https").then(|| handshake_of(&response)),
        };
        writer = cache.put_async(meta, log.fork("http-cache")).await;
    }
    Ok(Hop {
        status,
        message: canonical_reason(status),
        headers: network_headers,
        cache: cache_state,
        body: DeliveryBody::Network(NetworkBody::new(response, writer, label, log.clone())),
    })
}

/// `Cache.update`'s entry: the cached request and status line, the merged headers, the new timing.
fn refreshed_meta(
    cached: &CachedResponse,
    sent_headers: &Headers,
    combined: &Headers,
    sent_millis: i64,
    received_millis: i64,
) -> EntryMeta {
    EntryMeta {
        vary_headers: vary_headers(sent_headers, combined),
        response_headers: combined.clone(),
        sent_millis,
        received_millis,
        ..cached.meta.clone()
    }
}

fn canonical_reason(code: u16) -> String {
    reqwest::StatusCode::from_u16(code).ok().and_then(|status| status.canonical_reason()).unwrap_or_default().to_owned()
}

/// The status line's reason as OkHttp records it: HTTP/2 and later carry none.
fn wire_reason(response: &reqwest::Response) -> String {
    match response.version() {
        reqwest::Version::HTTP_09 | reqwest::Version::HTTP_10 | reqwest::Version::HTTP_11 => {
            response.status().canonical_reason().unwrap_or_default().to_owned()
        }
        _ => String::new(),
    }
}

/// A redirect hop's body is read to its end so its cache entry completes (OkHttp discards it the
/// same way); a failure only costs that entry.
async fn drain(body: DeliveryBody, log: &Logger) {
    let DeliveryBody::Network(mut body) = body else { return };
    loop {
        match body.chunk().await {
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(error) => {
                log.debug(|| format!("redirect body not cached: {}", error_chain(&error.without_url())));
                return;
            }
        }
    }
    body.finish().await;
}

async fn deliver(pipe: &Pipe, delivery: Delivery, log: &Logger) -> Result<(), BoxError> {
    let final_url = Url::parse(&delivery.final_url).ok();
    let label = final_url
        .as_ref()
        .map_or_else(String::new, |url| format!("{}{}", url.host_str().unwrap_or_default(), url.path()));
    log.info(&format!("HTTP {} {label}", delivery.status));
    let set_cookie_names: Vec<&str> =
        delivery.headers.values("Set-Cookie").into_iter().filter_map(|value| value.split('=').next()).collect();
    if !set_cookie_names.is_empty() {
        log.debug(|| format!("set-cookie={}", set_cookie_names.join(",")));
    }
    let headers: Vec<Value> = delivery.headers.iter().map(|(name, value)| json!([name, value])).collect();
    let mut head = json!({
        "status": delivery.status,
        "message": delivery.message,
        "url": delivery.final_url,
        "headers": headers,
    });
    if let Some(cache) = delivery.cache {
        head["cache"] = json!(cache);
    }
    if delivery_ended(pipe.send(head.to_string()).await)? {
        return Ok(());
    }
    let (mut network, mut cached, mut proxied) = (None, None, None);
    match delivery.body {
        DeliveryBody::Network(body) => network = Some(body),
        DeliveryBody::Cached(file) => cached = Some(file),
        DeliveryBody::Proxied(stream) => proxied = Some(stream),
        DeliveryBody::Empty => {}
    }
    let mut decoder = delivery.gunzip.then(|| GzDecoder::new(Vec::new()));
    let mut raw_bytes = 0u64;
    let mut buffer = BytesMut::new();
    loop {
        let chunk = if let Some(body) = network.as_mut() {
            match body.chunk().await {
                Ok(chunk) => chunk,
                Err(error) => {
                    // The head is out: a clean END now would read as a complete, truncated body.
                    let error = error.without_url();
                    let message = format!("response body failed: {}", error_chain(&error));
                    log.warn(&message, Some(&error));
                    return reset(pipe, "network", &message).await;
                }
            }
        } else if let Some(file) = cached.as_mut() {
            buffer.reserve(PIPE_CAP);
            match file.read_buf(&mut buffer).await {
                Ok(0) => None,
                Ok(_) => Some(buffer.split().freeze()),
                Err(error) => {
                    let message = format!("response body failed: cached body: {error}");
                    log.warn(&format!("R90716 {message}"), Some(&error));
                    return reset(pipe, "network", &message).await;
                }
            }
        } else if let Some(stream) = proxied.as_mut() {
            match stream.next().await {
                Some(Ok(chunk)) => Some(chunk),
                Some(Err(error)) => {
                    // The head is out: a clean END now would read as a complete, truncated body.
                    let message = format!("response body failed: {}", error_chain(&error));
                    log.warn(&message, Some(&error));
                    return reset(pipe, "network", &message).await;
                }
                None => None,
            }
        } else {
            None
        };
        let Some(chunk) = chunk else { break };
        raw_bytes += chunk.len() as u64;
        let out = match decoder.as_mut() {
            None => chunk,
            Some(decoder) => match decoder.write_all(&chunk).and_then(|()| decoder.flush()) {
                Ok(()) => Bytes::from(std::mem::take(decoder.get_mut())),
                Err(error) => return gzip_failed(pipe, network, log, &error).await,
            },
        };
        if !out.is_empty() && delivery_ended(pipe.send(out).await)? {
            return Ok(());
        }
    }
    if let Some(mut decoder) = decoder.filter(|_| raw_bytes > 0) {
        match decoder.try_finish() {
            Ok(()) => {
                let rest = std::mem::take(decoder.get_mut());
                if !rest.is_empty() && delivery_ended(pipe.send(rest).await)? {
                    return Ok(());
                }
            }
            Err(error) => return gzip_failed(pipe, network, log, &error).await,
        }
    }
    if let Some(mut body) = network {
        body.finish().await;
    }
    if proxied.is_some() {
        log.debug(|| format!("proxied body {raw_bytes} bytes"));
    }
    pipe.close_write(None).await?;
    Ok(())
}

/// A body that does not decode is not cached either.
async fn gzip_failed(
    pipe: &Pipe,
    network: Option<NetworkBody>,
    log: &Logger,
    error: &std::io::Error,
) -> Result<(), BoxError> {
    if let Some(mut body) = network {
        body.writer = None;
    }
    let message = format!("response body failed: gzip: {error}");
    log.warn(&message, Some(error));
    reset(pipe, "network", &message).await
}

/// `true` when the client stopped reading (cancelled or torn down): no one is left to tell.
fn delivery_ended(sent: Result<(), PipeError>) -> Result<bool, PipeError> {
    match sent {
        Ok(()) => Ok(false),
        Err(PipeError::BrokenPipe | PipeError::SendClosed(_)) => Ok(true),
        Err(error) => Err(error),
    }
}

/// Cookie names only: values are session credentials and never enter a log line.
fn cookie_names<'a>(headers: impl Iterator<Item = (&'a str, &'a str)>) -> String {
    headers
        .filter(|(name, _)| name.eq_ignore_ascii_case("Cookie"))
        .map(|(_, value)| value)
        .flat_map(|value| value.split(';'))
        .filter_map(|pair| Some(pair.split('=').next()?.trim()).filter(|name| !name.is_empty()))
        .collect::<Vec<_>>()
        .join(",")
}

fn error_chain(error: &(dyn std::error::Error + 'static)) -> String {
    let mut text = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        text.push_str(": ");
        text.push_str(&cause.to_string());
        source = cause.source();
    }
    text
}
