//! `cookie/get` / `cookie/set` (HOST_PROTOCOLS §2). The bundle owns cookie policy and cooks every
//! request's `Cookie` header; the host owns parsing and storage.

use std::sync::{Arc, Mutex};

use serde_json::{Map, Value};
use url::Url;
use ym_quickjs::{BoxError, QuickJsRuntime};

use crate::logs::ParkedLoggers;
use crate::protocol::{Codes, Request, ServeError, serve};

const CODES: Codes = Codes { request: "R20006", failure: "R20007" };

/// The app's cookie store. Blocking: called off the engine thread.
pub trait CookieJar: Send + Sync {
    /// name → value pairs the store would send to `url`.
    fn load_for_request(&self, url: &Url) -> Result<Vec<(String, String)>, BoxError>;
    /// Parses and stores `Set-Cookie` values received from `url`; unparseable ones are dropped.
    fn save_from_response(&self, url: &Url, set_cookies: &[String]) -> Result<(), BoxError>;
    /// Forgets every cookie (sign-out).
    fn clear(&self) -> Result<(), BoxError>;
}

/// RFC 6265 domain / path / expiry / secure matching, in memory.
#[derive(Default)]
pub struct MemoryCookieJar {
    store: Mutex<cookie_store::CookieStore>,
}

impl CookieJar for MemoryCookieJar {
    fn load_for_request(&self, url: &Url) -> Result<Vec<(String, String)>, BoxError> {
        let store = self.store.lock().unwrap();
        Ok(store.get_request_values(url).map(|(name, value)| (name.to_owned(), value.to_owned())).collect())
    }

    fn save_from_response(&self, url: &Url, set_cookies: &[String]) -> Result<(), BoxError> {
        let mut store = self.store.lock().unwrap();
        for set_cookie in set_cookies {
            let _ = store.parse(set_cookie, url);
        }
        Ok(())
    }

    fn clear(&self) -> Result<(), BoxError> {
        self.store.lock().unwrap().clear();
        Ok(())
    }
}

/// Writes the jar's persistent cookies somewhere durable. Blocking.
pub type CookiePersist = Box<dyn Fn(&[u8]) -> Result<(), BoxError> + Send + Sync>;

/// [`MemoryCookieJar`] semantics, saved after every change in the JVM app's document and restored
/// at start. Session cookies (no expiry) are not saved, as a browser drops them at exit.
pub struct PersistentCookieJar {
    store: Mutex<cookie_store::CookieStore>,
    persist: CookiePersist,
}

impl PersistentCookieJar {
    /// `saved` is what `persist` last wrote (the JVM app's document, or the `cookie_store` JSON
    /// earlier builds wrote); unreadable bytes start an empty jar.
    pub fn new(saved: Option<&[u8]>, persist: CookiePersist) -> Self {
        let store = saved
            .and_then(|bytes| read_document(bytes).or_else(|| cookie_store::serde::json::load(bytes).ok()))
            .unwrap_or_default();
        Self { store: Mutex::new(store), persist }
    }
}

impl CookieJar for PersistentCookieJar {
    fn load_for_request(&self, url: &Url) -> Result<Vec<(String, String)>, BoxError> {
        let store = self.store.lock().unwrap();
        Ok(store.get_request_values(url).map(|(name, value)| (name.to_owned(), value.to_owned())).collect())
    }

    fn save_from_response(&self, url: &Url, set_cookies: &[String]) -> Result<(), BoxError> {
        let bytes = {
            let mut store = self.store.lock().unwrap();
            for set_cookie in set_cookies {
                let _ = store.parse(set_cookie, url);
            }
            write_document(&store)?
        };
        (self.persist)(&bytes)
    }

    fn clear(&self) -> Result<(), BoxError> {
        let bytes = {
            let mut store = self.store.lock().unwrap();
            store.clear();
            write_document(&store)?
        };
        (self.persist)(&bytes)
    }
}

/// The JVM app's jar document (`PersistentCookieStore`): `{"cookies": [{name, value, expiresAt (epoch
/// ms), domain, path, secure, httpOnly, hostOnly}]}`, persistent cookies only. `None` when `bytes`
/// is not one; a malformed entry is skipped, as the JVM app does.
fn read_document(bytes: &[u8]) -> Option<cookie_store::CookieStore> {
    let document: Value = serde_json::from_slice(bytes).ok()?;
    let entries = document.get("cookies")?.as_array()?;
    let mut store = cookie_store::CookieStore::default();
    for entry in entries {
        let text = |key: &str| entry.get(key).and_then(Value::as_str);
        let flag = |key: &str| entry.get(key).and_then(Value::as_bool).unwrap_or(false);
        let (Some(name), Some(value), Some(domain), Some(path), Some(expires_at)) =
            (text("name"), text("value"), text("domain"), text("path"), entry.get("expiresAt").and_then(Value::as_i64))
        else {
            continue;
        };
        let Ok(expires) = time::OffsetDateTime::from_unix_timestamp_nanos(i128::from(expires_at) * 1_000_000) else {
            continue;
        };
        let mut raw = cookie_store::RawCookie::build((name.to_owned(), value.to_owned()))
            .path(path.to_owned())
            .expires(expires)
            .secure(flag("secure"))
            .http_only(flag("httpOnly"));
        if !flag("hostOnly") {
            raw = raw.domain(domain.to_owned());
        }
        let Ok(url) = Url::parse(&format!("https://{domain}{path}")) else { continue };
        let _ = store.insert_raw(&raw.build(), &url);
    }
    Some(store)
}

fn write_document(store: &cookie_store::CookieStore) -> Result<Vec<u8>, BoxError> {
    let cookies: Vec<Value> = store
        .iter_unexpired()
        .filter_map(|cookie| {
            let cookie_store::CookieExpiration::AtUtc(expires) = cookie.expires else { return None };
            let (domain, host_only) = match &cookie.domain {
                cookie_store::CookieDomain::HostOnly(domain) => (domain.clone(), true),
                cookie_store::CookieDomain::Suffix(domain) => (domain.clone(), false),
                _ => return None,
            };
            Some(serde_json::json!({
                "name": cookie.name(),
                "value": cookie.value(),
                "expiresAt": (expires.unix_timestamp_nanos() / 1_000_000) as i64,
                "domain": domain,
                "path": String::from(&cookie.path),
                "secure": cookie.secure().unwrap_or(false),
                "httpOnly": cookie.http_only().unwrap_or(false),
                "hostOnly": host_only,
            }))
        })
        .collect();
    Ok(serde_json::to_vec(&serde_json::json!({ "cookies": cookies }))?)
}

pub(crate) fn install(runtime: &QuickJsRuntime, jar: Arc<dyn CookieJar>, parked: Arc<ParkedLoggers>) {
    let (get_jar, get_parked) = (jar.clone(), parked.clone());
    runtime.on_accept("cookie/get", move |pipe| {
        let (jar, parked) = (get_jar.clone(), get_parked.clone());
        async move {
            serve(pipe, parked, "cookie/get", CODES, &["url"], async |request: &Request<'_>| {
                let url = http_url(request)?;
                let cookies = tokio::task::spawn_blocking(move || jar.load_for_request(&url))
                    .await?
                    .map_err(ServeError::Failure)?;
                let body: Map<String, Value> =
                    cookies.into_iter().map(|(name, value)| (name, Value::String(value))).collect();
                request.send_json(&Value::Object(body)).await
            })
            .await
        }
    });
    runtime.on_accept("cookie/set", move |pipe| {
        let (jar, parked) = (jar.clone(), parked.clone());
        async move {
            serve(pipe, parked, "cookie/set", CODES, &["url"], async |request: &Request<'_>| {
                let url = http_url(request)?;
                let set_cookies: Vec<String> = request
                    .head
                    .get("setCookie")
                    .and_then(Value::as_array)
                    .map(|values| {
                        values.iter().filter_map(Value::as_str).filter(|v| !v.is_empty()).map(str::to_owned).collect()
                    })
                    .unwrap_or_default();
                if set_cookies.is_empty() {
                    return Err(ServeError::Request("cookie request missing 'setCookie'".into()));
                }
                // Names only: values are session credentials and never enter a log line.
                request.log.debug(|| {
                    let names: Vec<&str> = set_cookies.iter().filter_map(|c| c.split('=').next()).collect();
                    format!("{} set-cookie={}", url.host_str().unwrap_or_default(), names.join(","))
                });
                tokio::task::spawn_blocking(move || jar.save_from_response(&url, &set_cookies))
                    .await?
                    .map_err(ServeError::Failure)
            })
            .await
        }
    });
}

fn http_url(request: &Request<'_>) -> Result<Url, ServeError> {
    let raw = request.string("url").map_err(|_| ServeError::Request("cookie request missing 'url'".into()))?;
    Url::parse(&raw)
        .ok()
        .filter(|url| matches!(url.scheme(), "http" | "https") && url.host_str().is_some())
        .ok_or_else(|| ServeError::Request(format!("cookie request has an invalid 'url': {raw}")))
}
