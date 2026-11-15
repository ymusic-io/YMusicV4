use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{Map, Value};
use tokio::sync::watch;
use ym_common::log::{Logger, now_millis};

use crate::stats::{PULSE_MAX_EVENTS, PulseEvent};

/// Hammering `check_update` from every screen costs at most one request per interval.
const MIN_INTERVAL: Duration = Duration::from_secs(5 * 60);

fn null_default<'de, D: Deserializer<'de>, T: Default + Deserialize<'de>>(deserializer: D) -> Result<T, D::Error> {
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

fn null_true<'de, D: Deserializer<'de>>(deserializer: D) -> Result<bool, D::Error> {
    Ok(Option::<bool>::deserialize(deserializer)?.unwrap_or(true))
}

/// A published JS bundle; the document arrives unsigned, so `signature` authenticates the file.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BundleVersion {
    pub version: i64,
    pub url: String,
    pub signature: String,
}

/// A newer app build for this platform; `signature` covers `app-<platform>:<versionCode>:<sha256>`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppUpdate {
    pub version_code: i32,
    pub version_name: String,
    #[serde(default, deserialize_with = "null_default")]
    pub notes: String,
    pub url: String,
    pub size: i64,
    pub sha256: String,
    #[serde(default, deserialize_with = "null_default")]
    pub signature: String,
    #[serde(default)]
    pub changelog_url: Option<String>,
    #[serde(default)]
    pub download_page: Option<String>,
    #[serde(default = "default_true", deserialize_with = "null_true")]
    pub install_in_app: bool,
}

fn default_true() -> bool {
    true
}

/// `GET /v1/config`: every field defaults and unknown fields are skipped, so the service can add a
/// flag without breaking an older client. `features` keeps every key as sent.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RemoteConfigData {
    #[serde(deserialize_with = "null_default")]
    pub features: Map<String, Value>,
    #[serde(deserialize_with = "null_default")]
    pub app_update: Option<AppUpdate>,
    #[serde(deserialize_with = "null_default")]
    pub engine_js: Option<BundleVersion>,
    #[serde(deserialize_with = "null_default")]
    pub player_js: Option<BundleVersion>,
    #[serde(deserialize_with = "null_default")]
    pub sentry_tags: BTreeMap<String, String>,
    /// When the app last heard from the service (stamped at parse); a server value wins.
    pub updated_at: i64,
}

impl RemoteConfigData {
    pub fn feature(&self, key: &str) -> Option<&Value> {
        self.features.get(key).filter(|value| !value.is_null())
    }

    pub fn player_cache_enabled(&self) -> bool {
        self.feature("playerCacheEnabled").and_then(Value::as_bool).unwrap_or(true)
    }

    pub fn log_lines(&self) -> i64 {
        self.feature("logLines").and_then(Value::as_i64).unwrap_or(5_000)
    }

    pub fn error_log_lines(&self) -> i64 {
        self.feature("errorLogLines").and_then(Value::as_i64).unwrap_or(500)
    }

    pub fn error_sample_rate(&self) -> f64 {
        self.feature("errorSampleRate").and_then(Value::as_f64).unwrap_or(1.0)
    }
}

/// What every request to the service carries.
#[derive(Debug, Clone)]
pub struct ApiIdentity {
    pub base_url: String,
    pub api_key: Option<String>,
    /// windows, macos or linux.
    pub os: String,
    pub os_version: String,
    pub arch: String,
    pub app_version_code: i32,
    pub app_version_name: String,
    pub device_id: String,
}

#[derive(Debug, thiserror::Error)]
pub enum RelayError {
    #[error("this build has no config service")]
    NoService,
    #[error("config service unreachable: {0}")]
    Transport(String),
    #[error("config service answered HTTP {0}")]
    Status(u16),
}

/// The config service's call errors, relay or not.
pub type ServiceError = RelayError;

/// One span to skip, in seconds; the service already merged and filtered them.
#[derive(Clone, Debug, PartialEq)]
pub struct SkipSegment {
    pub start_time: f64,
    pub end_time: f64,
    pub category: String,
    pub video_duration: f64,
}

impl RelayError {
    fn transport(error: reqwest::Error) -> Self {
        Self::Transport(error.without_url().to_string())
    }
}

/// The policy around the service: the cache of the previous run, the throttle, what counts as
/// fresh. A failed fetch publishes nothing, so an outage never changes how the app behaves.
pub struct RemoteConfig {
    identity: Option<ApiIdentity>,
    client: reqwest::Client,
    cache_file: PathBuf,
    sender: watch::Sender<RemoteConfigData>,
    in_flight: tokio::sync::Mutex<()>,
    last_attempt: Mutex<i64>,
    /// Bundle revisions running now, reported as headers.
    running_bundles: Mutex<(Option<i64>, Option<i64>)>,
    log: Logger,
}

impl RemoteConfig {
    /// Loads the cache of the previous run (blocking). `identity: None` = no service in this build.
    pub fn open(identity: Option<ApiIdentity>, client: reqwest::Client, cache_file: PathBuf, log: Logger) -> Self {
        let log = log.fork("remote-config");
        let cached = match std::fs::read(&cache_file) {
            Ok(bytes) => serde_json::from_slice::<RemoteConfigData>(&bytes).unwrap_or_else(|error| {
                log.warn("R30001 config cache unreadable, dropped", Some(&error));
                let _ = std::fs::remove_file(&cache_file);
                RemoteConfigData::default()
            }),
            Err(_) => RemoteConfigData::default(),
        };
        let identity = identity.filter(|identity| !identity.base_url.trim().is_empty());
        let (sender, _) = watch::channel(cached);
        Self {
            identity,
            client,
            cache_file,
            sender,
            in_flight: tokio::sync::Mutex::new(()),
            last_attempt: Mutex::new(0),
            running_bundles: Mutex::new((None, None)),
            log,
        }
    }

    pub fn current(&self) -> RemoteConfigData {
        self.sender.borrow().clone()
    }

    pub fn subscribe(&self) -> watch::Receiver<RemoteConfigData> {
        self.sender.subscribe()
    }

    pub fn set_running_bundles(&self, engine: Option<i64>, player: Option<i64>) {
        *self.running_bundles.lock().unwrap() = (engine, player);
    }

    /// Fetches when the held config is older than `max_age` (throttled); otherwise returns.
    pub async fn check_update(&self, max_age: Duration) {
        let Some(identity) = &self.identity else { return };
        let now = now_millis();
        if now - self.sender.borrow().updated_at < max_age.as_millis() as i64 {
            return;
        }
        let Ok(_guard) = self.in_flight.try_lock() else { return };
        {
            let mut last = self.last_attempt.lock().unwrap();
            if now - *last < MIN_INTERVAL.as_millis() as i64 {
                return;
            }
            *last = now;
        }
        self.fetch_and_publish(identity).await;
    }

    /// For a person waiting on an answer ("Check for update"): fetches now, past the age check and
    /// the throttle. `None` when the fetch failed or this build has no service.
    pub async fn fetch_now(&self) -> Option<RemoteConfigData> {
        let identity = self.identity.as_ref()?;
        let _guard = self.in_flight.lock().await;
        *self.last_attempt.lock().unwrap() = now_millis();
        self.fetch_and_publish(identity).await
    }

    async fn fetch_and_publish(&self, identity: &ApiIdentity) -> Option<RemoteConfigData> {
        match self.fetch(identity).await {
            Ok(mut config) => {
                if config.updated_at == 0 {
                    config.updated_at = now_millis();
                }
                if let Ok(bytes) = serde_json::to_vec(&config) {
                    let cache_file = self.cache_file.clone();
                    let saved = tokio::task::spawn_blocking(move || {
                        if let Some(parent) = cache_file.parent() {
                            std::fs::create_dir_all(parent)?;
                        }
                        let staged = cache_file.with_extension("tmp");
                        std::fs::write(&staged, bytes)?;
                        std::fs::rename(&staged, &cache_file)
                    })
                    .await;
                    if let Ok(Err(error)) = saved {
                        self.log.warn("R30002 config cache not written", Some(&error));
                    }
                }
                self.sender.send_replace(config.clone());
                Some(config)
            }
            Err(error) => {
                self.log.warn(&format!("R30003 config fetch failed: {error}"), None);
                None
            }
        }
    }

    /// The service's base url and identity headers on a request to `path`; `None` without a service.
    fn request(&self, method: reqwest::Method, path: &str) -> Option<reqwest::RequestBuilder> {
        let identity = self.identity.as_ref()?;
        let url = format!("{}{path}", identity.base_url.trim_end_matches('/'));
        let mut request = self
            .client
            .request(method, url)
            .timeout(Duration::from_secs(10))
            .header("x-os", &identity.os)
            .header("x-os-ver", &identity.os_version)
            .header("x-app-ver", identity.app_version_code.to_string())
            .header("x-app-ver-name", &identity.app_version_name)
            .header("x-device-id", &identity.device_id)
            .header("x-arch", &identity.arch);
        if let Some(key) = &identity.api_key {
            request = request.header("x-api-key", key);
        }
        let (engine, player) = *self.running_bundles.lock().unwrap();
        if let Some(version) = engine {
            request = request.header("x-engine-js-ver", version.to_string());
        }
        if let Some(version) = player {
            request = request.header("x-player-js-ver", version.to_string());
        }
        Some(request)
    }

    /// This build has a config service.
    pub fn has_service(&self) -> bool {
        self.identity.is_some()
    }

    /// `POST /v1/pulse`: usage events, all or nothing, oldest first (the service credits a day only
    /// past its last credited one). More than [`PULSE_MAX_EVENTS`] is refused as the service would,
    /// with 413, before any request leaves.
    pub async fn pulse(&self, events: &[PulseEvent]) -> Result<(), ServiceError> {
        if events.len() > PULSE_MAX_EVENTS {
            return Err(ServiceError::Status(413));
        }
        let body = serde_json::to_vec(&serde_json::json!({ "events": events })).map_err(|_| ServiceError::Status(0))?;
        let request = self.request(reqwest::Method::POST, "/v1/pulse").ok_or(ServiceError::NoService)?;
        let response = request
            .header("content-type", "application/json")
            .body(body)
            .send()
            .await
            .map_err(ServiceError::transport)?;
        if response.status().is_success() { Ok(()) } else { Err(ServiceError::Status(response.status().as_u16())) }
    }

    /// The SponsorBlock spans to skip in a YouTube video, sorted; empty when it has none. HTTP 503
    /// while the service has no SponsorBlock data yet.
    pub async fn skip_segments(&self, video_id: &str) -> Result<Vec<SkipSegment>, ServiceError> {
        let video_id: String = url::form_urlencoded::byte_serialize(video_id.as_bytes()).collect();
        let request = self
            .request(reqwest::Method::GET, &format!("/v1/sponsorblock/segments?v={video_id}"))
            .ok_or(ServiceError::NoService)?;
        let response = request.send().await.map_err(ServiceError::transport)?;
        if !response.status().is_success() {
            return Err(ServiceError::Status(response.status().as_u16()));
        }
        let body: serde_json::Value = serde_json::from_slice(&response.bytes().await.map_err(ServiceError::transport)?)
            .map_err(|_| ServiceError::Status(0))?;
        let number = |segment: &serde_json::Value, key: &str| segment.get(key).and_then(serde_json::Value::as_f64);
        body.get("segments")
            .and_then(serde_json::Value::as_array)
            .into_iter()
            .flatten()
            .map(|segment| {
                Some(SkipSegment {
                    start_time: number(segment, "startTime")?,
                    end_time: number(segment, "endTime")?,
                    category: segment.get("category")?.as_str()?.to_owned(),
                    video_duration: number(segment, "videoDuration").unwrap_or(0.0),
                })
            })
            .collect::<Option<Vec<_>>>()
            .ok_or(ServiceError::Status(0))
    }

    /// Opens a one-shot relay slot for a device-link handoff; returns its id.
    pub async fn open_device_link(&self) -> Result<String, RelayError> {
        let request = self.request(reqwest::Method::POST, "/v1/link").ok_or(RelayError::NoService)?;
        let response = request.send().await.map_err(RelayError::transport)?;
        if !response.status().is_success() {
            return Err(RelayError::Status(response.status().as_u16()));
        }
        let body: serde_json::Value = serde_json::from_slice(&response.bytes().await.map_err(RelayError::transport)?)
            .map_err(|_| RelayError::Status(0))?;
        body.get("id").and_then(serde_json::Value::as_str).map(str::to_owned).ok_or(RelayError::Status(0))
    }

    /// The payload once the other device posted it (taking it deletes it); `None` while empty.
    pub async fn take_device_link(&self, id: &str) -> Result<Option<String>, RelayError> {
        let request = self.request(reqwest::Method::GET, &format!("/v1/link/{id}")).ok_or(RelayError::NoService)?;
        let response = request.send().await.map_err(RelayError::transport)?;
        match response.status().as_u16() {
            200 => Ok(Some(response.text().await.map_err(RelayError::transport)?)),
            204 => Ok(None),
            code => Err(RelayError::Status(code)),
        }
    }

    /// `false` when the slot is gone (expired or taken).
    pub async fn put_device_link(&self, id: &str, payload: String) -> Result<bool, RelayError> {
        let request = self.request(reqwest::Method::PUT, &format!("/v1/link/{id}")).ok_or(RelayError::NoService)?;
        let response =
            request.header("content-type", "text/plain").body(payload).send().await.map_err(RelayError::transport)?;
        match response.status().as_u16() {
            404 => Ok(false),
            code if (200..300).contains(&code) => Ok(true),
            code => Err(RelayError::Status(code)),
        }
    }

    pub async fn close_device_link(&self, id: &str) -> Result<(), RelayError> {
        let request = self.request(reqwest::Method::DELETE, &format!("/v1/link/{id}")).ok_or(RelayError::NoService)?;
        let response = request.send().await.map_err(RelayError::transport)?;
        if response.status().is_success() { Ok(()) } else { Err(RelayError::Status(response.status().as_u16())) }
    }

    async fn fetch(&self, identity: &ApiIdentity) -> Result<RemoteConfigData, String> {
        let url = format!("{}/v1/config", identity.base_url.trim_end_matches('/'));
        let (engine, player) = *self.running_bundles.lock().unwrap();
        let mut request = self
            .client
            .get(url)
            .timeout(Duration::from_secs(15))
            .header("x-os", &identity.os)
            .header("x-os-ver", &identity.os_version)
            .header("x-app-ver", identity.app_version_code.to_string())
            .header("x-app-ver-name", &identity.app_version_name)
            .header("x-device-id", &identity.device_id)
            .header("x-arch", &identity.arch);
        if let Some(key) = &identity.api_key {
            request = request.header("x-api-key", key);
        }
        if let Some(version) = engine {
            request = request.header("x-engine-js-ver", version.to_string());
        }
        if let Some(version) = player {
            request = request.header("x-player-js-ver", version.to_string());
        }
        let response = request.send().await.map_err(|error| error.without_url().to_string())?;
        if !response.status().is_success() {
            return Err(format!("HTTP {}", response.status().as_u16()));
        }
        let body = response.bytes().await.map_err(|error| error.without_url().to_string())?;
        serde_json::from_slice(&body).map_err(|error| format!("malformed config: {error}"))
    }
}
