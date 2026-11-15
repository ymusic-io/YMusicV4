//! The scoped keyed blob cache behind `cache/*`: metadata indexed in SQLite, values as files named
//! `sha256(scope, key)`. Eviction is the bundle's job; this store is unbounded.
//!
//! Guarantees: no partial values (a put commits only after its body ends cleanly), keys never wait
//! on each other (no lock held while a body streams), and the last put/delete of a key to start
//! wins (an overtaken put fails with `CacheError::Superseded`). No blob outlives its row: delete
//! removes the blob first, and `get` heals a row whose blob is gone into a miss.

use std::collections::{BTreeMap, HashMap};
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::pin::pin;
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use futures::{Stream, StreamExt};
use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use tokio::sync::oneshot;
use ym_common::log::{Logger, now_millis};

/// `:dynamic-engine`'s `CacheDatabase` version.
const SCHEMA_VERSION: i64 = 1;
const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS cache_entry (
    scope     TEXT    NOT NULL,
    key       TEXT    NOT NULL,
    size      INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    lastUsed  INTEGER NOT NULL,
    custom    TEXT    NOT NULL,
    PRIMARY KEY (scope, key)
);
CREATE INDEX IF NOT EXISTS cache_entry_scope ON cache_entry(scope);
";
const BLOB_EXT: &str = "v";

#[derive(Debug, Clone, PartialEq)]
pub struct CacheMeta {
    pub key: String,
    pub size: i64,
    pub updated_at: i64,
    pub last_used: i64,
    pub custom: BTreeMap<String, String>,
}

#[derive(Debug, Clone)]
pub struct CacheEntry {
    pub meta: CacheMeta,
    pub path: PathBuf,
}

#[derive(Debug, thiserror::Error)]
pub enum CacheError {
    #[error("cache put {0} superseded by a newer put/delete")]
    Superseded(String),
    #[error("cache I/O: {0}")]
    Io(#[from] std::io::Error),
    #[error("cache index: {0}")]
    Db(#[from] rusqlite::Error),
}

#[derive(Debug)]
pub enum PutError<E> {
    /// The body stream failed; nothing was stored.
    Body(E),
    Cache(CacheError),
}

#[derive(Default)]
struct KeyTurn {
    latest: u64,
    in_flight: u32,
    streaming: Option<(u64, oneshot::Sender<()>)>,
}

#[derive(Default)]
struct Turns {
    last_ticket: u64,
    keys: HashMap<String, KeyTurn>,
}

/// Epoch-millis source for the `updatedAt` / `lastUsed` stamps.
pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

pub struct DiskCacheStore {
    db: Mutex<Connection>,
    directory: PathBuf,
    turns: Mutex<Turns>,
    now: Clock,
    log: Logger,
}

impl DiskCacheStore {
    /// Blocking.
    pub fn open(db_path: &Path, directory: PathBuf, log: Logger) -> Result<Arc<Self>, CacheError> {
        Self::open_with_clock(db_path, directory, log, Arc::new(now_millis))
    }

    /// Blocking.
    pub fn open_with_clock(
        db_path: &Path,
        directory: PathBuf,
        log: Logger,
        now: Clock,
    ) -> Result<Arc<Self>, CacheError> {
        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::create_dir_all(&directory)?;
        let db = Connection::open(db_path)?;
        ym_common::sqlite::open_schema(&db, SCHEMA_VERSION, |db| db.execute_batch(SCHEMA), |_, _| Ok(()))?;
        Ok(Arc::new(Self {
            db: Mutex::new(db),
            directory,
            turns: Mutex::default(),
            now,
            log: log.fork("engine-cache"),
        }))
    }

    /// Bumps `lastUsed`, so an LRU evictor sees the read. Blocking.
    pub fn get(&self, scope: &str, key: &str) -> Result<Option<CacheEntry>, CacheError> {
        let db = self.db.lock().unwrap();
        let Some(stored) = db
            .query_row(
                "SELECT key, size, updatedAt, lastUsed, custom FROM cache_entry WHERE scope = ?1 AND key = ?2",
                params![scope, key],
                |row| self.meta_from_row(row),
            )
            .optional()?
        else {
            return Ok(None);
        };
        let path = self.blob_path(scope, key);
        if !path.exists() {
            db.execute("DELETE FROM cache_entry WHERE scope = ?1 AND key = ?2", params![scope, key])?;
            return Ok(None);
        }
        let now = (self.now)();
        db.execute("UPDATE cache_entry SET lastUsed = ?1 WHERE scope = ?2 AND key = ?3", params![now, scope, key])?;
        Ok(Some(CacheEntry { meta: CacheMeta { last_used: now, ..stored }, path }))
    }

    /// Blocking.
    pub fn list(&self, scope: &str) -> Result<Vec<CacheMeta>, CacheError> {
        let db = self.db.lock().unwrap();
        let mut statement =
            db.prepare("SELECT key, size, updatedAt, lastUsed, custom FROM cache_entry WHERE scope = ?1")?;
        let rows = statement.query_map(params![scope], |row| self.meta_from_row(row))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Cancels the key's in-flight put. Blocking.
    pub fn delete(&self, scope: &str, key: &str) -> Result<(), CacheError> {
        let mut turns = self.turns.lock().unwrap();
        let next = turns.last_ticket + 1;
        if let Some(turn) = turns.keys.get_mut(&stem(scope, key)) {
            if let Some((_, cancel)) = turn.streaming.take() {
                let _ = cancel.send(());
            }
            turn.latest = next;
            turns.last_ticket = next;
        }
        remove_if_exists(&self.blob_path(scope, key))?;
        self.db
            .lock()
            .unwrap()
            .execute("DELETE FROM cache_entry WHERE scope = ?1 AND key = ?2", params![scope, key])?;
        Ok(())
    }

    /// Streams `body` into a staged file and commits it once the body ends cleanly.
    pub async fn put<E>(
        self: &Arc<Self>,
        scope: &str,
        key: &str,
        custom: BTreeMap<String, String>,
        body: impl Stream<Item = Result<Bytes, E>>,
    ) -> Result<CacheMeta, PutError<E>> {
        let id = stem(scope, key);
        let tmp = self.directory.join(format!("{id}.{:016x}.{BLOB_EXT}.tmp", rand_u64()));
        let (ticket, cancelled) = {
            let mut turns = self.turns.lock().unwrap();
            turns.last_ticket += 1;
            let ticket = turns.last_ticket;
            let turn = turns.keys.entry(id.clone()).or_default();
            turn.in_flight += 1;
            if let Some((_, previous)) = turn.streaming.take() {
                let _ = previous.send(());
            }
            let (cancel, cancelled) = oneshot::channel();
            turn.streaming = Some((ticket, cancel));
            turn.latest = ticket;
            (ticket, cancelled)
        };
        let label = format!("{scope}/{key}");
        let result = self.stage_and_commit(&tmp, &id, ticket, scope, key, custom, body, cancelled, &label).await;
        if result.is_err() {
            let _ = remove_if_exists(&tmp);
        }
        let mut turns = self.turns.lock().unwrap();
        if let Some(turn) = turns.keys.get_mut(&id) {
            if turn.streaming.as_ref().is_some_and(|(owner, _)| *owner == ticket) {
                turn.streaming = None;
            }
            turn.in_flight -= 1;
            if turn.in_flight == 0 {
                turns.keys.remove(&id);
            }
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    async fn stage_and_commit<E>(
        self: &Arc<Self>,
        tmp: &Path,
        id: &str,
        ticket: u64,
        scope: &str,
        key: &str,
        custom: BTreeMap<String, String>,
        body: impl Stream<Item = Result<Bytes, E>>,
        mut cancelled: oneshot::Receiver<()>,
        label: &str,
    ) -> Result<CacheMeta, PutError<E>> {
        let superseded = || PutError::Cache(CacheError::Superseded(label.to_owned()));
        let io = |error: std::io::Error| PutError::Cache(CacheError::Io(error));
        let mut file = tokio::fs::File::create(tmp).await.map_err(io)?;
        let mut body = pin!(body);
        let mut size: i64 = 0;
        loop {
            tokio::select! {
                biased;
                _ = &mut cancelled => return Err(superseded()),
                item = body.next() => match item {
                    None => break,
                    Some(Ok(chunk)) => {
                        file.write_all(&chunk).await.map_err(io)?;
                        size += chunk.len() as i64;
                    }
                    Some(Err(error)) => return Err(PutError::Body(error)),
                },
            }
        }
        file.flush().await.map_err(io)?;
        drop(file);

        let store = self.clone();
        let (id, tmp, scope, key) = (id.to_owned(), tmp.to_owned(), scope.to_owned(), key.to_owned());
        let label = label.to_owned();
        tokio::task::spawn_blocking(move || store.commit(&id, ticket, &tmp, &scope, &key, size, custom, &label))
            .await
            .map_err(|error| io(std::io::Error::other(error)))?
            .map_err(PutError::Cache)
    }

    #[allow(clippy::too_many_arguments)]
    fn commit(
        &self,
        id: &str,
        ticket: u64,
        tmp: &Path,
        scope: &str,
        key: &str,
        size: i64,
        custom: BTreeMap<String, String>,
        label: &str,
    ) -> Result<CacheMeta, CacheError> {
        let turns = self.turns.lock().unwrap();
        if turns.keys.get(id).map(|turn| turn.latest) != Some(ticket) {
            return Err(CacheError::Superseded(label.to_owned()));
        }
        let blob = self.blob_path(scope, key);
        std::fs::rename(tmp, &blob)?;
        let now = (self.now)();
        let custom_json = serde_json::to_string(&custom).unwrap_or_else(|_| "{}".into());
        let upsert = self.db.lock().unwrap().execute(
            "INSERT OR REPLACE INTO cache_entry(scope, key, size, updatedAt, lastUsed, custom) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![scope, key, size, now, now, custom_json],
        );
        if let Err(error) = upsert {
            let _ = remove_if_exists(&blob);
            return Err(error.into());
        }
        Ok(CacheMeta { key: key.to_owned(), size, updated_at: now, last_used: now, custom })
    }

    fn meta_from_row(&self, row: &rusqlite::Row<'_>) -> rusqlite::Result<CacheMeta> {
        let custom: String = row.get(4)?;
        let custom = serde_json::from_str(&custom).unwrap_or_else(|error| {
            self.log.warn(&format!("corrupt cache meta dropped: {error}"), None);
            BTreeMap::new()
        });
        Ok(CacheMeta { key: row.get(0)?, size: row.get(1)?, updated_at: row.get(2)?, last_used: row.get(3)?, custom })
    }

    fn blob_path(&self, scope: &str, key: &str) -> PathBuf {
        self.directory.join(format!("{}.{BLOB_EXT}", stem(scope, key)))
    }
}

/// Length-prefixed so `(scope, key)` hashes injectively, with no separator ambiguity.
fn stem(scope: &str, key: &str) -> String {
    hex::encode(Sha256::digest(format!("{}:{scope}:{key}", scope.len())))
}

fn remove_if_exists(path: &Path) -> std::io::Result<()> {
    match std::fs::remove_file(path) {
        Err(error) if error.kind() != ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

fn rand_u64() -> u64 {
    use std::hash::{BuildHasher, Hasher};
    let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
    hasher.write_i64(now_millis());
    hasher.finish()
}
