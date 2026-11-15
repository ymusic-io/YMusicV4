//! SponsorBlock cache and per-video whitelist (`sponsor-block.db`, the JVM app's schema).

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use ym_common::log::now_millis;

/// Rows unused this long are dropped.
const UNUSED_CACHE_MS: i64 = 20 * 24 * 60 * 60 * 1000;

/// The JVM app's `SponsorBlockSchema` version.
const SCHEMA_VERSION: i64 = 1;
const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS sponsorBlockCache (
    videoId TEXT NOT NULL PRIMARY KEY, segments TEXT NOT NULL, updatedAt INTEGER NOT NULL, usedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sponsorBlockWhiteList (videoId TEXT NOT NULL PRIMARY KEY);
";

/// One span to skip, in seconds.
#[derive(Clone, Debug, PartialEq)]
pub struct Segment {
    pub start_time: f64,
    pub end_time: f64,
    pub category: String,
    pub video_duration: f64,
}

pub struct Cached {
    pub segments: Vec<Segment>,
    pub updated_at: i64,
}

/// Blocking SQLite: call from a worker.
pub struct SponsorBlockStore {
    connection: Mutex<Connection>,
}

impl SponsorBlockStore {
    pub fn open(path: &Path) -> rusqlite::Result<Self> {
        let connection = Connection::open(path)?;
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.pragma_update(None, "synchronous", "NORMAL")?;
        connection.busy_timeout(std::time::Duration::from_secs(5))?;
        ym_common::sqlite::open_schema(&connection, SCHEMA_VERSION, |db| db.execute_batch(SCHEMA), |_, _| Ok(()))?;
        connection.execute("DELETE FROM sponsorBlockCache WHERE usedAt < ?", [now_millis() - UNUSED_CACHE_MS])?;
        Ok(Self { connection: Mutex::new(connection) })
    }

    pub fn cached(&self, video_id: &str) -> rusqlite::Result<Option<Cached>> {
        let connection = self.connection.lock().unwrap();
        let row: Option<(String, i64)> = connection
            .query_row("SELECT segments, updatedAt FROM sponsorBlockCache WHERE videoId = ?", [video_id], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })
            .optional()?;
        let Some((segments, updated_at)) = row else { return Ok(None) };
        connection
            .execute("UPDATE sponsorBlockCache SET usedAt = ? WHERE videoId = ?", params![now_millis(), video_id])?;
        let segments = decode(&segments).ok_or_else(|| {
            rusqlite::Error::FromSqlConversionFailure(0, rusqlite::types::Type::Text, "malformed segments".into())
        })?;
        Ok(Some(Cached { segments, updated_at }))
    }

    pub fn cache(&self, video_id: &str, segments: &[Segment]) -> rusqlite::Result<()> {
        let now = now_millis();
        self.connection.lock().unwrap().execute(
            "INSERT OR REPLACE INTO sponsorBlockCache(videoId, segments, updatedAt, usedAt) VALUES (?, ?, ?, ?)",
            params![video_id, encode(segments), now, now],
        )?;
        Ok(())
    }

    pub fn is_whitelisted(&self, video_id: &str) -> rusqlite::Result<bool> {
        self.connection
            .lock()
            .unwrap()
            .query_row("SELECT 1 FROM sponsorBlockWhiteList WHERE videoId = ?", [video_id], |_| Ok(()))
            .optional()
            .map(|row| row.is_some())
    }

    /// True when it changed anything.
    pub fn set_whitelisted(&self, video_id: &str, whitelisted: bool) -> rusqlite::Result<bool> {
        let connection = self.connection.lock().unwrap();
        let changed = if whitelisted {
            connection.execute("INSERT OR IGNORE INTO sponsorBlockWhiteList(videoId) VALUES (?)", [video_id])?
        } else {
            connection.execute("DELETE FROM sponsorBlockWhiteList WHERE videoId = ?", [video_id])?
        };
        Ok(changed > 0)
    }
}

fn encode(segments: &[Segment]) -> String {
    Value::Array(
        segments
            .iter()
            .map(|segment| {
                json!({
                    "startTime": segment.start_time,
                    "endTime": segment.end_time,
                    "category": segment.category,
                    "videoDuration": segment.video_duration,
                })
            })
            .collect(),
    )
    .to_string()
}

fn decode(text: &str) -> Option<Vec<Segment>> {
    if text.is_empty() {
        return Some(Vec::new());
    }
    let rows: Vec<Value> = serde_json::from_str(text).ok()?;
    rows.iter()
        .map(|row| {
            Some(Segment {
                start_time: row.get("startTime")?.as_f64()?,
                end_time: row.get("endTime")?.as_f64()?,
                category: row.get("category")?.as_str()?.to_owned(),
                video_duration: row.get("videoDuration").and_then(Value::as_f64).unwrap_or(0.0),
            })
        })
        .collect()
}
