//! OkHttp's cache policy: `CacheControl` parsing, `CacheStrategy` (freshness, conditional
//! validation), `isCacheable`, Vary matching and the 304 header merge.

use std::collections::BTreeSet;
use std::time::UNIX_EPOCH;

use super::entry::EntryMeta;
use super::headers::Headers;

const ONE_DAY_MILLIS: i64 = 24 * 60 * 60 * 1000;

#[derive(Default, Debug)]
pub(crate) struct CacheControl {
    pub no_cache: bool,
    pub no_store: bool,
    pub max_age_seconds: Option<i64>,
    pub is_private: bool,
    pub is_public: bool,
    pub must_revalidate: bool,
    pub max_stale_seconds: Option<i64>,
    pub min_fresh_seconds: Option<i64>,
    pub only_if_cached: bool,
}

/// `toNonNegativeInt`: clamped to `0..=i32::MAX`, `default` when not a number.
fn non_negative(parameter: Option<&str>, default: Option<i64>) -> Option<i64> {
    match parameter.map(str::parse::<i64>) {
        Some(Ok(value)) => Some(value.clamp(0, i64::from(i32::MAX))),
        _ => default,
    }
}

impl CacheControl {
    /// `CacheControl.parse(headers)`: every `Cache-Control` and `Pragma` value.
    pub fn parse(headers: &Headers) -> Self {
        let mut control = Self::default();
        for (name, value) in headers.iter() {
            if !name.eq_ignore_ascii_case("Cache-Control") && !name.eq_ignore_ascii_case("Pragma") {
                continue;
            }
            let bytes = value.as_bytes();
            let index_of = |from: usize, set: &[u8]| {
                bytes[from.min(bytes.len())..]
                    .iter()
                    .position(|byte| set.contains(byte))
                    .map_or(bytes.len(), |at| at + from)
            };
            let mut pos = 0;
            while pos < bytes.len() {
                let token_start = pos;
                pos = index_of(pos, b"=,;");
                let directive = value[token_start..pos].trim();
                let parameter;
                if pos == bytes.len() || bytes[pos] == b',' || bytes[pos] == b';' {
                    pos += 1;
                    parameter = None;
                } else {
                    pos += 1;
                    while pos < bytes.len() && (bytes[pos] == b' ' || bytes[pos] == b'\t') {
                        pos += 1;
                    }
                    if pos < bytes.len() && bytes[pos] == b'"' {
                        pos += 1;
                        let start = pos;
                        pos = index_of(pos, b"\"");
                        parameter = Some(&value[start..pos]);
                        pos += 1;
                    } else {
                        let start = pos.min(bytes.len());
                        pos = index_of(pos, b",;");
                        parameter = Some(value[start..pos].trim());
                    }
                }
                match directive.to_ascii_lowercase().as_str() {
                    "no-cache" => control.no_cache = true,
                    "no-store" => control.no_store = true,
                    "max-age" => control.max_age_seconds = non_negative(parameter, None),
                    "private" => control.is_private = true,
                    "public" => control.is_public = true,
                    "must-revalidate" => control.must_revalidate = true,
                    "max-stale" => control.max_stale_seconds = non_negative(parameter, Some(i64::from(i32::MAX))),
                    "min-fresh" => control.min_fresh_seconds = non_negative(parameter, None),
                    "only-if-cached" => control.only_if_cached = true,
                    _ => {}
                }
            }
        }
        control
    }
}

/// The request as the cache sees it: one hop, with the headers that go on the wire.
pub(crate) struct CacheRequest<'a> {
    pub url: &'a str,
    pub headers: &'a Headers,
}

impl CacheRequest<'_> {
    fn is_https(&self) -> bool {
        self.url.starts_with("https:")
    }
}

#[derive(Debug, PartialEq)]
pub(crate) enum Decision {
    /// No usable cached response: a plain network request.
    Network,
    /// Serve the cached response, with these `Warning` values added.
    Hit { warnings: Vec<&'static str> },
    /// Validate the cached response with this extra request header.
    Conditional { name: &'static str, value: String },
    /// `only-if-cached` and the cache cannot answer: OkHttp's synthetic 504.
    Unsatisfiable,
}

pub(crate) fn decide(now_millis: i64, request: &CacheRequest<'_>, cached: Option<&EntryMeta>) -> Decision {
    let candidate = candidate(now_millis, request, cached);
    let needs_network = matches!(candidate, Decision::Network | Decision::Conditional { .. });
    if needs_network && CacheControl::parse(request.headers).only_if_cached {
        return Decision::Unsatisfiable;
    }
    candidate
}

fn http_date_millis(value: &str) -> Option<i64> {
    let time = httpdate::parse_http_date(value).ok()?;
    match time.duration_since(UNIX_EPOCH) {
        Ok(since) => Some(since.as_millis() as i64),
        Err(before) => Some(-(before.duration().as_millis() as i64)),
    }
}

fn candidate(now_millis: i64, request: &CacheRequest<'_>, cached: Option<&EntryMeta>) -> Decision {
    let Some(cached) = cached else { return Decision::Network };
    if request.is_https() && cached.handshake.is_none() {
        return Decision::Network;
    }
    let request_caching = CacheControl::parse(request.headers);
    if !is_cacheable(cached.code, &cached.response_headers, &request_caching) {
        return Decision::Network;
    }
    if request_caching.no_cache
        || request.headers.get("If-Modified-Since").is_some()
        || request.headers.get("If-None-Match").is_some()
    {
        return Decision::Network;
    }

    let (mut served_date, mut last_modified, mut expires, mut etag, mut age_seconds) = (None, None, None, None, None);
    for (name, value) in cached.response_headers.iter() {
        if name.eq_ignore_ascii_case("Date") {
            served_date = Some((http_date_millis(value), value));
        } else if name.eq_ignore_ascii_case("Expires") {
            expires = http_date_millis(value);
        } else if name.eq_ignore_ascii_case("Last-Modified") {
            last_modified = Some((http_date_millis(value), value));
        } else if name.eq_ignore_ascii_case("ETag") {
            etag = Some(value);
        } else if name.eq_ignore_ascii_case("Age") {
            age_seconds = non_negative(Some(value), None);
        }
    }
    let served_millis = served_date.and_then(|(millis, _)| millis);
    let last_modified_millis = last_modified.and_then(|(millis, _)| millis);
    let response_caching = CacheControl::parse(&cached.response_headers);
    let (sent, received) = (cached.sent_millis, cached.received_millis);

    let apparent_received_age = served_millis.map_or(0, |served| (received - served).max(0));
    let received_age = match age_seconds {
        Some(age) => apparent_received_age.max(age * 1000),
        None => apparent_received_age,
    };
    let age_millis = received_age + (received - sent).max(0) + (now_millis - received).max(0);

    let mut fresh_millis = if let Some(max_age) = response_caching.max_age_seconds {
        max_age * 1000
    } else if let Some(expires) = expires {
        (expires - served_millis.unwrap_or(received)).max(0)
    } else if let (Some(last_modified), false) = (last_modified_millis, cached_url_has_query(&cached.url)) {
        (served_millis.unwrap_or(sent) - last_modified).max(0) / 10
    } else {
        0
    };
    if let Some(max_age) = request_caching.max_age_seconds {
        fresh_millis = fresh_millis.min(max_age * 1000);
    }
    let min_fresh_millis = request_caching.min_fresh_seconds.map_or(0, |seconds| seconds * 1000);
    let max_stale_millis = match request_caching.max_stale_seconds {
        Some(seconds) if !response_caching.must_revalidate => seconds * 1000,
        _ => 0,
    };

    if !response_caching.no_cache && age_millis + min_fresh_millis < fresh_millis + max_stale_millis {
        let mut warnings = Vec::new();
        if age_millis + min_fresh_millis >= fresh_millis {
            warnings.push("110 HttpURLConnection \"Response is stale\"");
        }
        let heuristic = response_caching.max_age_seconds.is_none() && expires.is_none();
        if age_millis > ONE_DAY_MILLIS && heuristic {
            warnings.push("113 HttpURLConnection \"Heuristic expiration\"");
        }
        return Decision::Hit { warnings };
    }

    if let Some(etag) = etag {
        Decision::Conditional { name: "If-None-Match", value: etag.to_owned() }
    } else if let Some((Some(_), value)) = last_modified {
        Decision::Conditional { name: "If-Modified-Since", value: value.to_owned() }
    } else if let Some((Some(_), value)) = served_date {
        Decision::Conditional { name: "If-Modified-Since", value: value.to_owned() }
    } else {
        Decision::Network
    }
}

fn cached_url_has_query(url: &str) -> bool {
    url::Url::parse(url).is_ok_and(|url| url.query().is_some())
}

/// `CacheStrategy.isCacheable`.
pub(crate) fn is_cacheable(code: u16, response_headers: &Headers, request_caching: &CacheControl) -> bool {
    let response_caching = CacheControl::parse(response_headers);
    match code {
        200 | 203 | 204 | 300 | 301 | 404 | 405 | 410 | 414 | 501 | 308 => {}
        302 | 307 => {
            if response_headers.get("Expires").is_none()
                && response_caching.max_age_seconds.is_none()
                && !response_caching.is_public
                && !response_caching.is_private
            {
                return false;
            }
        }
        _ => return false,
    }
    !response_caching.no_store && !request_caching.no_store
}

/// `Response.promisesBody()`.
pub(crate) fn promises_body(method: &str, code: u16, headers: &Headers) -> bool {
    if method == "HEAD" {
        return false;
    }
    if (!(100..200).contains(&code)) && code != 204 && code != 304 {
        return true;
    }
    let content_length = headers.get("Content-Length").and_then(|value| value.parse::<i64>().ok());
    content_length.is_some_and(|length| length != -1)
        || headers.get("Transfer-Encoding").is_some_and(|value| value.eq_ignore_ascii_case("chunked"))
}

/// `HttpMethod.invalidatesCache`.
pub(crate) fn invalidates_cache(method: &str) -> bool {
    matches!(method, "POST" | "PATCH" | "PUT" | "DELETE" | "MOVE")
}

/// The `Vary` field names, lowercased (OkHttp compares them case-insensitively).
fn vary_fields(response_headers: &Headers) -> BTreeSet<String> {
    response_headers
        .values("Vary")
        .into_iter()
        .flat_map(|value| value.split(','))
        .map(|field| field.trim().to_ascii_lowercase())
        .collect()
}

pub(crate) fn has_vary_all(response_headers: &Headers) -> bool {
    vary_fields(response_headers).contains("*")
}

/// The request headers a response varies on, as they were sent.
pub(crate) fn vary_headers(request_headers: &Headers, response_headers: &Headers) -> Headers {
    let fields = vary_fields(response_headers);
    if fields.is_empty() {
        return Headers::default();
    }
    let mut result = Headers::default();
    for (name, value) in request_headers.iter() {
        if fields.contains(&name.to_ascii_lowercase()) {
            result.add(name, value);
        }
    }
    result
}

pub(crate) fn vary_matches(cached: &EntryMeta, request_headers: &Headers) -> bool {
    vary_fields(&cached.response_headers)
        .iter()
        .all(|field| cached.vary_headers.values(field) == request_headers.values(field))
}

fn is_end_to_end(name: &str) -> bool {
    ![
        "Connection",
        "Keep-Alive",
        "Proxy-Authenticate",
        "Proxy-Authorization",
        "TE",
        "Trailers",
        "Transfer-Encoding",
        "Upgrade",
    ]
    .iter()
    .any(|hop| hop.eq_ignore_ascii_case(name))
}

fn is_content_specific(name: &str) -> bool {
    ["Content-Length", "Content-Encoding", "Content-Type"].iter().any(|content| content.eq_ignore_ascii_case(name))
}

/// `CacheInterceptor.combine`: a 304's headers over the cached ones.
pub(crate) fn combine(cached: &Headers, network: &Headers) -> Headers {
    let mut result = Headers::default();
    for (name, value) in cached.iter() {
        if name.eq_ignore_ascii_case("Warning") && value.starts_with('1') {
            continue;
        }
        if is_content_specific(name) || !is_end_to_end(name) || network.get(name).is_none() {
            result.add(name, value);
        }
    }
    for (name, value) in network.iter() {
        if !is_content_specific(name) && is_end_to_end(name) {
            result.add(name, value);
        }
    }
    result
}
