//! The engine HTTP client's disk cache: OkHttp 5's `Cache` (what the JVM app's engine client runs
//! on), ported with its on-disk format so both apps share the session's `http-cache` folder.
//!
//! Entries are keyed by `md5(url)`; `<key>.0` holds the request/response metadata, `<key>.1` the
//! body exactly as it came off the wire (still gzip-encoded when the server sent it so).

mod disk_lru;
mod entry;
mod headers;
mod strategy;

use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::PathBuf;
use std::sync::Arc;

use md5::{Digest, Md5};
use tokio::io::AsyncWriteExt;
use ym_common::log::Logger;

pub(crate) use entry::{EntryMeta, Handshake};
pub(crate) use headers::Headers;
pub(crate) use strategy::{
    CacheControl, CacheRequest, Decision, combine, decide, has_vary_all, invalidates_cache, is_cacheable,
    promises_body, vary_headers, vary_matches,
};

use disk_lru::{DiskLruCache, Editor};

/// OkHttp `Cache.VERSION`: the journal's app version.
const OKHTTP_CACHE_VERSION: u32 = 201105;
const ENTRY_METADATA: usize = 0;
const ENTRY_BODY: usize = 1;

/// The JVM app's `DefaultHttpCacheBytes`.
pub const DEFAULT_HTTP_CACHE_BYTES: u64 = 32 * 1024 * 1024;

/// One per cache folder: a second instance over the same folder would corrupt its journal.
pub struct HttpCache {
    lru: Arc<DiskLruCache>,
    log: Logger,
}

/// A cached response that matched the request, its body file open.
pub(crate) struct CachedResponse {
    pub meta: EntryMeta,
    key: String,
    sequence: u64,
    pub body: File,
}

impl HttpCache {
    /// No I/O here: the journal is read on first use, off the calling thread.
    pub fn new(directory: PathBuf, max_size: u64, log: Logger) -> Self {
        let lru = Arc::new(DiskLruCache::new(directory, OKHTTP_CACHE_VERSION, max_size, log.clone()));
        Self { lru, log }
    }

    pub fn directory(&self) -> &std::path::Path {
        self.lru.directory()
    }

    /// `Cache.get` with `Entry.matches`. Blocking.
    pub(crate) fn get(&self, method: &str, url: &str, request_headers: &Headers) -> Option<CachedResponse> {
        let key = key(url);
        let snapshot = match self.lru.get(&key) {
            Ok(snapshot) => snapshot?,
            Err(error) => {
                self.log.warn("R90700 http cache read failed", Some(&error));
                return None;
            }
        };
        let [metadata, body] = snapshot.files;
        let meta = match EntryMeta::read(&mut BufReader::new(metadata)) {
            Ok(meta) => meta,
            Err(error) => {
                self.log.warn("R90707 http cache entry unreadable", Some(&error));
                return None;
            }
        };
        if meta.url != url || meta.request_method != method || !vary_matches(&meta, request_headers) {
            return None;
        }
        Some(CachedResponse { meta, key: snapshot.key, sequence: snapshot.sequence, body })
    }

    /// `Cache.put` for a GET response: the metadata is written now, the body through the returned
    /// editor. `None` when the entry is busy or the response varies on everything. Blocking.
    pub(crate) fn put(&self, meta: &EntryMeta) -> Option<(Editor, File)> {
        if meta.request_method != "GET" || has_vary_all(&meta.response_headers) {
            return None;
        }
        let written = (|| -> std::io::Result<Option<(Editor, File)>> {
            let Some(mut editor) = self.lru.edit(&key(&meta.url), None)? else { return Ok(None) };
            let Some(metadata) = editor.create(ENTRY_METADATA)? else { return Ok(None) };
            let mut sink = BufWriter::new(metadata);
            meta.write(&mut sink)?;
            sink.into_inner().map_err(|error| error.into_error())?;
            let Some(body) = editor.create(ENTRY_BODY)? else { return Ok(None) };
            Ok(Some((editor, body)))
        })();
        written.unwrap_or_else(|error| {
            self.log.warn("R90701 http cache write failed", Some(&error));
            None
        })
    }

    /// `Cache.remove`, for a request that invalidates its URL. Blocking.
    pub(crate) fn remove(&self, url: &str) {
        if let Err(error) = self.lru.remove(&key(url)) {
            self.log.warn("R90705 http cache invalidation failed", Some(&error));
        }
    }

    /// `Cache.update`: a 304 refreshed `cached`'s metadata; its body stays. Blocking.
    pub(crate) fn update(&self, cached_key: &str, sequence: u64, meta: &EntryMeta) {
        let updated = (|| -> std::io::Result<()> {
            let Some(mut editor) = self.lru.edit(cached_key, Some(sequence))? else { return Ok(()) };
            let Some(metadata) = editor.create(ENTRY_METADATA)? else { return editor.abort() };
            let mut sink = BufWriter::new(metadata);
            meta.write(&mut sink)?;
            sink.into_inner().map_err(|error| error.into_error())?.flush()?;
            editor.commit()
        })();
        if let Err(error) = updated {
            self.log.warn("R90706 http cache refresh after 304 failed", Some(&error));
        }
    }
}

impl CachedResponse {
    pub fn key(&self) -> (&str, u64) {
        (&self.key, self.sequence)
    }
}

/// OkHttp `Cache.key`: the hex MD5 of the URL string.
pub fn key(url: &str) -> String {
    hex::encode(Md5::digest(url.as_bytes()))
}

/// Streams one response body into its cache entry; the entry commits only on [`Self::finish`].
pub(crate) struct CacheWriter {
    editor: Option<Editor>,
    file: tokio::fs::File,
    written: u64,
    log: Logger,
}

impl CacheWriter {
    pub fn new(editor: Editor, file: File, log: Logger) -> Self {
        Self { editor: Some(editor), file: tokio::fs::File::from_std(file), written: 0, log }
    }

    /// A failed write abandons the entry (the response itself goes on).
    pub async fn write(slot: &mut Option<Self>, chunk: &[u8]) {
        let Some(writer) = slot.as_mut() else { return };
        match writer.file.write_all(chunk).await {
            Ok(()) => writer.written += chunk.len() as u64,
            Err(error) => {
                writer.log.warn("R90708 http cache body write failed", Some(&error));
                *slot = None;
            }
        }
    }

    /// Commits the entry; returns the body bytes stored.
    pub async fn finish(mut self) -> Option<u64> {
        if let Err(error) = self.file.flush().await {
            self.log.warn("R90709 http cache body flush failed", Some(&error));
            return None;
        }
        let editor = self.editor.take()?;
        let log = self.log.clone();
        match tokio::task::spawn_blocking(move || editor.commit()).await {
            Ok(Ok(())) => Some(self.written),
            Ok(Err(error)) => {
                log.warn("R90710 http cache commit failed", Some(&error));
                None
            }
            Err(error) => {
                log.warn("R90711 http cache commit task failed", Some(&error));
                None
            }
        }
    }
}

impl Drop for CacheWriter {
    fn drop(&mut self) {
        if let Some(editor) = self.editor.take() {
            match tokio::runtime::Handle::try_current() {
                Ok(runtime) => drop(runtime.spawn_blocking(move || drop(editor))),
                Err(_) => drop(editor),
            }
        }
    }
}

/// The blocking cache calls, run off the engine thread.
impl HttpCache {
    pub(crate) async fn get_async(
        self: &Arc<Self>,
        method: &str,
        url: &str,
        headers: &Headers,
    ) -> Option<CachedResponse> {
        let (cache, method, url, headers) = (self.clone(), method.to_owned(), url.to_owned(), headers.clone());
        self.run(move || cache.get(&method, &url, &headers)).await.flatten()
    }

    pub(crate) async fn put_async(self: &Arc<Self>, meta: EntryMeta, log: Logger) -> Option<CacheWriter> {
        let cache = self.clone();
        let (editor, file) = self.run(move || cache.put(&meta)).await.flatten()?;
        Some(CacheWriter::new(editor, file, log))
    }

    pub(crate) async fn remove_async(self: &Arc<Self>, url: &str) {
        let (cache, url) = (self.clone(), url.to_owned());
        self.run(move || cache.remove(&url)).await;
    }

    pub(crate) async fn update_async(self: &Arc<Self>, cached_key: String, sequence: u64, meta: EntryMeta) {
        let cache = self.clone();
        self.run(move || cache.update(&cached_key, sequence, &meta)).await;
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce() -> T + Send + 'static) -> Option<T> {
        match tokio::task::spawn_blocking(work).await {
            Ok(value) => Some(value),
            Err(error) => {
                self.log.warn("R90712 http cache task failed", Some(&error));
                None
            }
        }
    }
}

/// The TLS details OkHttp records for an https entry. reqwest exposes the leaf certificate and
/// the version, not the cipher suite, so that line holds a name OkHttp accepts as unknown.
pub(crate) fn handshake_of(response: &reqwest::Response) -> Handshake {
    use base64::Engine;
    let info = response.extensions().get::<reqwest::tls::TlsInfo>();
    let peer_certificates = info
        .and_then(|info| info.peer_certificate())
        .map(|der| vec![base64::engine::general_purpose::STANDARD.encode(der)])
        .unwrap_or_default();
    let tls_version = info.and_then(|info| info.version()).and_then(|version| {
        [
            (reqwest::tls::Version::TLS_1_3, "TLSv1.3"),
            (reqwest::tls::Version::TLS_1_2, "TLSv1.2"),
            (reqwest::tls::Version::TLS_1_1, "TLSv1.1"),
            (reqwest::tls::Version::TLS_1_0, "TLSv1"),
        ]
        .into_iter()
        .find(|(candidate, _)| *candidate == version)
        .map(|(_, name)| name.to_owned())
    });
    Handshake { cipher_suite: "UNKNOWN".to_owned(), peer_certificates, local_certificates: Vec::new(), tls_version }
}
