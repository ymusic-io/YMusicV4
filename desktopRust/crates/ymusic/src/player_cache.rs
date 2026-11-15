//! The YouTube player's media cache, the JVM app's `player-cache` folder in the data dir: opened at
//! boot, capped by `playerCache.maxMb`, and handed to the player engine as its media proxy so
//! replays, seeks back and queue revisits play from disk.

use std::path::Path;
use std::sync::Arc;

use futures::FutureExt;
use futures::future::BoxFuture;
use tokio::runtime::Handle;
use ym_common::log::Logger;
use ym_engine::{MediaProxy, ProxiedRequest, ProxiedResponse};
use ym_i18n::{Str, t, tf};
use ym_remote_config::RemoteConfig;
use ym_settings::SettingsStore;
use ym_ytmedia::cache::MediaCache;
use ym_ytmedia::proxy::{LocalMediaProxy, ProxyRequest};

pub const MAX_MB_KEY: &str = "playerCache.maxMb";
const DEFAULT_MAX_MB: i32 = 2048;
pub const SIZE_CHOICES_MB: [i32; 8] = [0, 128, 256, 512, 1_024, 2_048, 5_120, 10_240];
const MIB: i64 = 1024 * 1024;

/// Blocking. `None` when the folder or its index cannot be opened: playback then streams uncached.
pub fn open(data_dir: &Path, settings: &dyn SettingsStore, log: &Logger) -> Option<Arc<MediaCache>> {
    let log = log.fork("player-cache");
    match MediaCache::open(data_dir, log.clone()) {
        Ok(cache) => {
            let cache = Arc::new(cache);
            apply_max_size(&cache, saved_max_mb(settings), &log);
            Some(cache)
        }
        Err(error) => {
            log.warn("R90650 player cache did not open, playback streams uncached", Some(&error));
            None
        }
    }
}

pub fn saved_max_mb(settings: &dyn SettingsStore) -> i32 {
    settings.int(MAX_MB_KEY, DEFAULT_MAX_MB).unwrap_or(DEFAULT_MAX_MB)
}

/// Blocking: evicts down to the new cap at once.
pub fn apply_max_size(cache: &MediaCache, max_mb: i32, log: &Logger) {
    if let Err(error) = cache.blobs.set_max_size(i64::from(max_mb) * MIB) {
        if error.is_disk_full() {
            log.warn("player cache cap: storage is full", Some(&error));
        } else {
            log.capture_error("R90651 player cache cap failed", &error);
        }
    }
}

/// The player engine's media proxy over `cache`; the remote config can turn caching off.
pub fn proxy(cache: Arc<MediaCache>, tokio: Handle, log: &Logger, remote: Arc<RemoteConfig>) -> Arc<dyn MediaProxy> {
    let proxy =
        LocalMediaProxy::new(cache, tokio, log.fork("player-cache"), move || remote.current().player_cache_enabled());
    Arc::new(PlayerMediaProxy(proxy))
}

struct PlayerMediaProxy(Arc<LocalMediaProxy>);

impl MediaProxy for PlayerMediaProxy {
    fn accepts(&self, method: &str, url: &str) -> bool {
        LocalMediaProxy::is_sabr_post(url, method)
    }

    fn serve(&self, request: ProxiedRequest) -> BoxFuture<'static, Result<ProxiedResponse, reqwest::Error>> {
        let proxy = self.0.clone();
        async move {
            let ProxiedRequest { method, url, headers, body, network, client, log } = request;
            let response = proxy
                .serve_request(ProxyRequest { url, method, headers, body }, &log, move || client.execute(network))
                .await?;
            Ok(ProxiedResponse {
                status: response.status,
                message: response.reason,
                url: response.url,
                headers: response.headers,
                body: response.body,
            })
        }
        .boxed()
    }
}

/// Binary units, like the size choices: "2 GB", "1.4 GB", "512 MB".
pub fn size_label(bytes: i64) -> String {
    let megabytes = bytes as f64 / MIB as f64;
    if megabytes < 1024.0 {
        return tf(Str::DownloadSizeMb, &[&(megabytes.round() as i64)]);
    }
    let tenths = (megabytes / 1024.0 * 10.0).round() as i64;
    let gigabytes =
        if tenths % 10 == 0 { (tenths / 10).to_string() } else { format!("{}.{}", tenths / 10, tenths % 10) };
    tf(Str::DownloadSizeGb, &[&gigabytes])
}

pub fn max_label(max_mb: i32) -> String {
    if max_mb > 0 { size_label(i64::from(max_mb) * MIB) } else { t(Str::SettingsPlayerCacheOff).to_owned() }
}
