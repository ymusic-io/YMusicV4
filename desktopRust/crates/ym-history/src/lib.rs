//! Play history (`play-history.db`, the JVM app's schema at version 5): watch-time rankings for
//! local files and YouTube videos, a display snapshot per YouTube video, and "Play again": whole
//! queues saved where they were left. Blocking SQLite: call from a worker.

pub mod sponsor;

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, Row, params};
use ym_common::log::now_millis;

pub use rusqlite::Error as StoreError;

const SCHEMA_VERSION: i64 = 5;
/// Rows kept per watch-time table.
pub const RETAIN: usize = 200;
const METADATA_QUOTA: usize = 100;
const METADATA_CLEANUP_EVERY: u32 = 10;
pub const MAX_SESSIONS: usize = 50;

const SCHEMA: &str = "
CREATE TABLE local_play_history (code TEXT PRIMARY KEY NOT NULL, watch_time INTEGER NOT NULL, tracked_at INTEGER NOT NULL);
CREATE INDEX local_play_history_watch_time ON local_play_history(watch_time);
CREATE INDEX local_play_history_tracked_at ON local_play_history(tracked_at);
CREATE TABLE yt_play_history (video_id TEXT PRIMARY KEY NOT NULL, watch_time INTEGER NOT NULL, tracked_at INTEGER NOT NULL);
CREATE INDEX yt_play_history_watch_time ON yt_play_history(watch_time);
CREATE INDEX yt_play_history_tracked_at ON yt_play_history(tracked_at);
CREATE TABLE yt_video_metadata (
    video_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, artist TEXT, thumbnail_url TEXT, duration TEXT,
    duration_ms INTEGER, last_updated INTEGER NOT NULL
);
CREATE INDEX yt_video_metadata_last_updated ON yt_video_metadata(last_updated);
CREATE TABLE play_again (
    queue_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, thumbnail_url TEXT, queue_index INTEGER NOT NULL,
    queue_size INTEGER NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, items TEXT NOT NULL,
    updated_at INTEGER NOT NULL, subtitle TEXT, strip_start INTEGER NOT NULL DEFAULT 0,
    strip_thumbs TEXT NOT NULL DEFAULT '', source_token TEXT
);
CREATE INDEX play_again_updated_at ON play_again(updated_at);
";

/// The JVM app's `PlayHistorySchema.migrate`.
fn migrate(connection: &Connection, found: i64) -> rusqlite::Result<()> {
    if found < 2 {
        connection.execute_batch(
            "
CREATE TABLE yt_video_metadata (
    video_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, artist TEXT, thumbnail_url TEXT, duration TEXT,
    duration_ms INTEGER, last_updated INTEGER NOT NULL
);
CREATE INDEX yt_video_metadata_last_updated ON yt_video_metadata(last_updated);
INSERT INTO yt_video_metadata(video_id, title, artist, thumbnail_url, duration, duration_ms, last_updated)
SELECT video_id, title, artist, thumbnail_url, duration, duration_ms, tracked_at FROM yt_play_history WHERE live = 0;
CREATE TABLE yt_play_history_new (video_id TEXT PRIMARY KEY NOT NULL, watch_time INTEGER NOT NULL, tracked_at INTEGER NOT NULL);
INSERT INTO yt_play_history_new(video_id, watch_time, tracked_at) SELECT video_id, watch_time, tracked_at FROM yt_play_history;
DROP TABLE yt_play_history;
ALTER TABLE yt_play_history_new RENAME TO yt_play_history;
CREATE INDEX yt_play_history_watch_time ON yt_play_history(watch_time);
CREATE INDEX yt_play_history_tracked_at ON yt_play_history(tracked_at);
",
        )?;
    }
    if found < 3 {
        connection.execute_batch(
            "
CREATE TABLE play_again (
    queue_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, thumbnail_url TEXT, queue_index INTEGER NOT NULL,
    queue_size INTEGER NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, items TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX play_again_updated_at ON play_again(updated_at);
",
        )?;
    }
    if found < 4 {
        connection.execute_batch(
            "
ALTER TABLE play_again ADD COLUMN subtitle TEXT;
ALTER TABLE play_again ADD COLUMN strip_start INTEGER NOT NULL DEFAULT 0;
ALTER TABLE play_again ADD COLUMN strip_thumbs TEXT NOT NULL DEFAULT '';
",
        )?;
    }
    if found < 5 {
        connection.execute_batch("ALTER TABLE play_again ADD COLUMN source_token TEXT;")?;
    }
    Ok(())
}

/// A YouTube video as the history shows it.
#[derive(Clone, Debug, PartialEq)]
pub struct VideoMetadata {
    pub video_id: String,
    pub title: String,
    pub artist: Option<String>,
    pub thumbnail_url: Option<String>,
    pub duration: Option<String>,
    pub duration_ms: Option<i64>,
}

/// One saved queue: the item at `queue_index` of `queue_size`, `position_ms` into it.
/// `strip_thumbs` is the art of the window starting at `strip_start` around the current item;
/// `source_token` is where the queue's playlist continues.
#[derive(Clone, Debug, PartialEq)]
pub struct PlayAgainSession {
    pub queue_id: String,
    pub title: String,
    pub subtitle: Option<String>,
    pub thumbnail_url: Option<String>,
    pub queue_index: usize,
    pub queue_size: usize,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub strip_start: usize,
    pub strip_thumbs: Vec<Option<String>>,
    pub source_token: Option<String>,
}

impl PlayAgainSession {
    pub const STRIP_BEFORE: usize = 2;
    pub const STRIP_SIZE: usize = 6;
    /// Anything with less left than this (every ordinary song) restarts instead of resuming.
    pub const RESUME_MIN_REMAINING_MS: i64 = 5 * 60 * 1000;

    pub fn resumes_mid_item(&self) -> bool {
        self.duration_ms - self.position_ms >= Self::RESUME_MIN_REMAINING_MS
    }

    /// First index of the strip shown for the item at `index` of `size`.
    pub fn strip_start(index: usize, size: usize) -> usize {
        index.saturating_sub(Self::STRIP_BEFORE).min(size.saturating_sub(Self::STRIP_SIZE))
    }
}

pub struct PlayHistory {
    connection: Mutex<Connection>,
    state: Mutex<CleanupState>,
}

#[derive(Default)]
struct CleanupState {
    history_cleaned: bool,
    metadata_saves: u32,
}

impl PlayHistory {
    pub fn open(path: &Path) -> rusqlite::Result<Self> {
        Self::with_connection(Connection::open(path)?)
    }

    pub fn in_memory() -> rusqlite::Result<Self> {
        Self::with_connection(Connection::open_in_memory()?)
    }

    fn with_connection(connection: Connection) -> rusqlite::Result<Self> {
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.pragma_update(None, "synchronous", "NORMAL")?;
        connection.busy_timeout(std::time::Duration::from_secs(5))?;
        ym_common::sqlite::open_schema(&connection, SCHEMA_VERSION, |db| db.execute_batch(SCHEMA), migrate)?;
        Ok(Self { connection: Mutex::new(connection), state: Mutex::new(CleanupState::default()) })
    }

    /// Adds watch time to a local file, keyed by its playlist code so a move keeps its rank.
    pub fn track_local(&self, code: &str, watch_ms: i64) -> rusqlite::Result<()> {
        self.track("local_play_history", "code", code, watch_ms)
    }

    pub fn track_youtube(&self, video_id: &str, watch_ms: i64) -> rusqlite::Result<()> {
        self.track("yt_play_history", "video_id", video_id, watch_ms)
    }

    fn track(&self, table: &str, id_column: &str, id: &str, watch_ms: i64) -> rusqlite::Result<()> {
        if watch_ms <= 0 || id.is_empty() {
            return Ok(());
        }
        let connection = self.connection.lock().unwrap();
        let mut state = self.state.lock().unwrap();
        if !state.history_cleaned {
            state.history_cleaned = true;
            cleanup(&connection, "local_play_history", "code", "tracked_at", RETAIN)?;
            cleanup(&connection, "yt_play_history", "video_id", "tracked_at", RETAIN)?;
        }
        let now = now_millis();
        connection.execute(
            &format!("INSERT OR IGNORE INTO {table}({id_column}, watch_time, tracked_at) VALUES (?, 0, ?)"),
            params![id, now],
        )?;
        connection.execute(
            &format!("UPDATE {table} SET watch_time = watch_time + ?, tracked_at = ? WHERE {id_column} = ?"),
            params![watch_ms, now, id],
        )?;
        Ok(())
    }

    /// A snapshot for the YouTube chart; a card without a duration only fills in what is missing.
    pub fn save_youtube_metadata(&self, video: &VideoMetadata) -> rusqlite::Result<()> {
        if video.video_id.is_empty() || video.title.is_empty() {
            return Ok(());
        }
        let connection = self.connection.lock().unwrap();
        let now = now_millis();
        if video.duration_ms.is_some_and(|duration| duration > 0) {
            connection.execute(
                "INSERT OR REPLACE INTO yt_video_metadata(video_id, title, artist, thumbnail_url, duration, duration_ms,
                 last_updated) VALUES (?, ?, ?, ?, ?, ?, ?)",
                params![
                    video.video_id,
                    video.title,
                    video.artist,
                    video.thumbnail_url,
                    video.duration,
                    video.duration_ms,
                    now
                ],
            )?;
        } else {
            let updated = connection.execute(
                "UPDATE yt_video_metadata SET last_updated = ? WHERE video_id = ?",
                params![now, video.video_id],
            )?;
            if updated == 0 {
                connection.execute(
                    "INSERT INTO yt_video_metadata(video_id, title, artist, thumbnail_url, duration, duration_ms,
                     last_updated) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    params![
                        video.video_id,
                        video.title,
                        video.artist,
                        video.thumbnail_url,
                        video.duration,
                        video.duration_ms,
                        now
                    ],
                )?;
            }
        }
        let mut state = self.state.lock().unwrap();
        state.metadata_saves += 1;
        if state.metadata_saves == 1 || state.metadata_saves.is_multiple_of(METADATA_CLEANUP_EVERY) {
            cleanup(&connection, "yt_video_metadata", "video_id", "last_updated", METADATA_QUOTA)?;
        }
        Ok(())
    }

    /// Local playlist codes, most watched first.
    pub fn local_top(&self, limit: usize) -> rusqlite::Result<Vec<String>> {
        self.ids("SELECT code FROM local_play_history ORDER BY watch_time DESC, tracked_at DESC LIMIT ?", limit)
    }

    /// Local playlist codes, most recently played first.
    pub fn local_recent(&self, limit: usize) -> rusqlite::Result<Vec<String>> {
        self.ids("SELECT code FROM local_play_history ORDER BY tracked_at DESC LIMIT ?", limit)
    }

    /// Most watched YouTube videos that have a card.
    pub fn youtube_top(&self, limit: usize) -> rusqlite::Result<Vec<VideoMetadata>> {
        let connection = self.connection.lock().unwrap();
        let mut statement = connection.prepare(
            "SELECT m.video_id, m.title, m.artist, m.thumbnail_url, m.duration, m.duration_ms
             FROM yt_play_history h JOIN yt_video_metadata m ON m.video_id = h.video_id
             ORDER BY h.watch_time DESC, h.tracked_at DESC LIMIT ?",
        )?;
        statement
            .query_map([limit as i64], |row| {
                Ok(VideoMetadata {
                    video_id: row.get(0)?,
                    title: row.get(1)?,
                    artist: row.get(2)?,
                    thumbnail_url: row.get(3)?,
                    duration: row.get(4)?,
                    duration_ms: row.get(5)?,
                })
            })?
            .collect()
    }

    pub fn delete_local(&self, code: &str) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute("DELETE FROM local_play_history WHERE code = ?", [code])?;
        Ok(())
    }

    pub fn delete_youtube(&self, video_id: &str) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute("DELETE FROM yt_play_history WHERE video_id = ?", [video_id])?;
        Ok(())
    }

    fn ids(&self, sql: &str, limit: usize) -> rusqlite::Result<Vec<String>> {
        let connection = self.connection.lock().unwrap();
        let mut statement = connection.prepare(sql)?;
        statement.query_map([limit as i64], |row| row.get(0))?.collect()
    }

    /// Inserts or replaces a session with its queue (`items_json`, the caller's encoding), keeping
    /// the newest [`MAX_SESSIONS`].
    pub fn save_session(&self, session: &PlayAgainSession, items_json: &str) -> rusqlite::Result<()> {
        let connection = self.connection.lock().unwrap();
        connection.execute(
            "INSERT OR REPLACE INTO play_again(queue_id, title, subtitle, thumbnail_url, queue_index, queue_size,
             position_ms, duration_ms, strip_start, strip_thumbs, source_token, items, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            params![
                session.queue_id,
                session.title,
                session.subtitle,
                session.thumbnail_url,
                session.queue_index as i64,
                session.queue_size as i64,
                session.position_ms,
                session.duration_ms,
                session.strip_start as i64,
                encode_thumbs(&session.strip_thumbs),
                session.source_token,
                items_json,
                now_millis()
            ],
        )?;
        connection.execute(
            "DELETE FROM play_again WHERE queue_id IN (
                 SELECT queue_id FROM play_again ORDER BY updated_at DESC LIMIT -1 OFFSET ?)",
            [MAX_SESSIONS as i64],
        )?;
        Ok(())
    }

    /// Moves the play-head of a saved queue whose items are unchanged; false when it isn't saved.
    pub fn save_progress(&self, session: &PlayAgainSession) -> rusqlite::Result<bool> {
        let updated = self.connection.lock().unwrap().execute(
            "UPDATE play_again SET title = ?, subtitle = ?, thumbnail_url = ?, queue_index = ?, position_ms = ?,
             duration_ms = ?, strip_start = ?, strip_thumbs = ?, source_token = ?, updated_at = ? WHERE queue_id = ?",
            params![
                session.title,
                session.subtitle,
                session.thumbnail_url,
                session.queue_index as i64,
                session.position_ms,
                session.duration_ms,
                session.strip_start as i64,
                encode_thumbs(&session.strip_thumbs),
                session.source_token,
                now_millis(),
                session.queue_id
            ],
        )?;
        Ok(updated > 0)
    }

    /// Newest first.
    pub fn sessions(&self) -> rusqlite::Result<Vec<PlayAgainSession>> {
        let connection = self.connection.lock().unwrap();
        let mut statement =
            connection.prepare(&format!("SELECT {SESSION_COLUMNS} FROM play_again ORDER BY updated_at DESC"))?;
        statement.query_map([], session_from_row)?.collect()
    }

    /// The session and its queue's JSON; none when it is no longer saved.
    pub fn load_session(&self, queue_id: &str) -> rusqlite::Result<Option<(PlayAgainSession, String)>> {
        self.connection
            .lock()
            .unwrap()
            .query_row(
                &format!("SELECT {SESSION_COLUMNS}, items FROM play_again WHERE queue_id = ?"),
                [queue_id],
                |row| Ok((session_from_row(row)?, row.get(11)?)),
            )
            .optional()
    }

    pub fn delete_session(&self, queue_id: &str) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute("DELETE FROM play_again WHERE queue_id = ?", [queue_id])?;
        Ok(())
    }
}

const SESSION_COLUMNS: &str = "queue_id, title, subtitle, thumbnail_url, queue_index, queue_size, position_ms, \
    duration_ms, strip_start, strip_thumbs, source_token";

fn session_from_row(row: &Row) -> rusqlite::Result<PlayAgainSession> {
    let thumbs: String = row.get(9)?;
    Ok(PlayAgainSession {
        queue_id: row.get(0)?,
        title: row.get(1)?,
        subtitle: row.get(2)?,
        thumbnail_url: row.get(3)?,
        queue_index: row.get::<_, i64>(4)?.max(0) as usize,
        queue_size: row.get::<_, i64>(5)?.max(0) as usize,
        position_ms: row.get(6)?,
        duration_ms: row.get(7)?,
        strip_start: row.get::<_, i64>(8)?.max(0) as usize,
        strip_thumbs: decode_thumbs(&thumbs).map_err(|error| {
            rusqlite::Error::FromSqlConversionFailure(9, rusqlite::types::Type::Text, Box::new(error))
        })?,
        source_token: row.get(10)?,
    })
}

fn encode_thumbs(thumbs: &[Option<String>]) -> String {
    serde_json::Value::from(thumbs.iter().map(|thumb| serde_json::Value::from(thumb.clone())).collect::<Vec<_>>())
        .to_string()
}

/// An empty string (a row from before the strip existed) holds no tiles.
fn decode_thumbs(json: &str) -> serde_json::Result<Vec<Option<String>>> {
    if json.is_empty() { Ok(Vec::new()) } else { serde_json::from_str(json) }
}

fn cleanup(
    connection: &Connection,
    table: &str,
    id_column: &str,
    order_column: &str,
    keep: usize,
) -> rusqlite::Result<()> {
    connection.execute(
        &format!(
            "DELETE FROM {table} WHERE {id_column} IN (
                 SELECT {id_column} FROM {table} ORDER BY {order_column} DESC LIMIT 999 OFFSET ?)"
        ),
        [keep as i64],
    )?;
    Ok(())
}
