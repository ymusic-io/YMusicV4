//! The download queue in SQLite: the single source of truth the service writes and the UI reads.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, Row, params};
use ym_common::log::Logger;

use crate::model::{AudioQuality, DownloadFormat, DownloadState, DownloadTask, StreamId, TaskError};

/// `:download`'s `DownloadDatabase` (version 10): `DownloadTask.sq` for a new file, `1.sqm`..`9.sqm`
/// for an older one from the JVM app.
const SCHEMA_VERSION: i64 = 10;
const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS downloadTask (
    id                TEXT    NOT NULL PRIMARY KEY,
    video_id          TEXT    NOT NULL,
    title             TEXT    NOT NULL,
    artist            TEXT,
    thumbnail_url     TEXT,
    format            TEXT    NOT NULL,
    requested_height  INTEGER,
    state             TEXT    NOT NULL,
    downloaded_bytes  INTEGER NOT NULL DEFAULT 0,
    total_bytes       INTEGER NOT NULL DEFAULT 0,
    file_path         TEXT,
    error_message     TEXT,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    file_name         TEXT,
    audio_track_id    TEXT,
    caption_langs     TEXT,
    audio_language    TEXT,
    caption_language  TEXT,
    error_code        TEXT,
    mp3_source_bitrate INTEGER NOT NULL DEFAULT 0,
    audio_quality     TEXT    NOT NULL DEFAULT 'BEST',
    audio_itag        INTEGER,
    audio_lmt         INTEGER,
    video_itag        INTEGER,
    video_lmt         INTEGER,
    loudness_gain_db  REAL
);
CREATE INDEX IF NOT EXISTS downloadTask_created ON downloadTask(created_at);
";
const MIGRATIONS: [&str; 9] = [
    "ALTER TABLE downloadTask ADD COLUMN file_name TEXT;
     ALTER TABLE downloadTask ADD COLUMN audio_track_id TEXT;
     ALTER TABLE downloadTask ADD COLUMN caption_langs TEXT;",
    "ALTER TABLE downloadTask ADD COLUMN audio_language TEXT;
     ALTER TABLE downloadTask ADD COLUMN caption_language TEXT;",
    "ALTER TABLE downloadTask ADD COLUMN error_code TEXT;",
    "ALTER TABLE downloadTask ADD COLUMN mp3_source_bitrate INTEGER NOT NULL DEFAULT 0;",
    "ALTER TABLE downloadTask ADD COLUMN audio_quality TEXT NOT NULL DEFAULT 'BEST';
     UPDATE downloadTask SET audio_quality = 'MEDIUM' WHERE mp3_source_bitrate != 0;",
    "UPDATE downloadTask SET audio_quality = 'BEST' WHERE mp3_source_bitrate != 0 AND format != 'MP3';",
    "ALTER TABLE downloadTask ADD COLUMN audio_itag INTEGER;",
    "ALTER TABLE downloadTask ADD COLUMN audio_lmt INTEGER;
     ALTER TABLE downloadTask ADD COLUMN video_itag INTEGER;
     ALTER TABLE downloadTask ADD COLUMN video_lmt INTEGER;",
    "ALTER TABLE downloadTask ADD COLUMN loudness_gain_db REAL;",
];
/// The queue as builds before the JVM schema kept it (unversioned), moved into `downloadTask` once.
const UNVERSIONED_MOVE: &str = "
INSERT OR IGNORE INTO downloadTask(
    id, video_id, title, artist, thumbnail_url, format, requested_height, state, downloaded_bytes, total_bytes,
    file_path, error_message, created_at, updated_at, file_name, audio_track_id, caption_langs, audio_language,
    caption_language, error_code, audio_quality, audio_itag, audio_lmt, video_itag, video_lmt, loudness_gain_db
)
SELECT id, video_id, title, artist, thumbnail_url, format, requested_height, state, downloaded_bytes, total_bytes,
    file_uri, error_message, created_at, updated_at, file_name, audio_track_id, caption_keys, audio_language,
    caption_language, error_code, audio_quality, audio_itag, audio_lmt, video_itag, video_lmt, loudness_gain_db
FROM download_task;
DROP TABLE download_task;
";

const COLUMNS: &str = "id, video_id, title, artist, thumbnail_url, format, requested_height, state, downloaded_bytes,
    total_bytes, file_path, error_message, error_code, created_at, updated_at, file_name, audio_track_id, caption_langs,
    audio_language, caption_language, audio_quality, audio_itag, audio_lmt, video_itag, video_lmt, loudness_gain_db";

/// Blocking SQLite: call from a worker, never the UI thread.
pub struct DownloadStore {
    connection: Mutex<Connection>,
    log: Logger,
}

impl DownloadStore {
    pub fn open(path: &Path, log: Logger) -> rusqlite::Result<Self> {
        Self::with_connection(Connection::open(path)?, log)
    }

    pub fn in_memory(log: Logger) -> rusqlite::Result<Self> {
        Self::with_connection(Connection::open_in_memory()?, log)
    }

    fn with_connection(connection: Connection, log: Logger) -> rusqlite::Result<Self> {
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.pragma_update(None, "synchronous", "NORMAL")?;
        connection.busy_timeout(std::time::Duration::from_secs(5))?;
        ym_common::sqlite::open_schema(
            &connection,
            SCHEMA_VERSION,
            |db| {
                db.execute_batch(SCHEMA)?;
                let unversioned: bool = db.query_row(
                    "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'download_task')",
                    [],
                    |row| row.get(0),
                )?;
                if unversioned { db.execute_batch(UNVERSIONED_MOVE) } else { Ok(()) }
            },
            |db, found| ym_common::sqlite::run_migrations(db, found, &MIGRATIONS),
        )?;
        Ok(Self { connection: Mutex::new(connection), log: log.fork("download-store") })
    }

    pub fn all(&self) -> rusqlite::Result<Vec<DownloadTask>> {
        let connection = self.connection.lock().unwrap();
        let mut statement = connection.prepare(&format!("SELECT {COLUMNS} FROM downloadTask ORDER BY created_at"))?;
        statement.query_map([], |row| map_row(row, &self.log))?.collect()
    }

    pub fn get(&self, id: &str) -> rusqlite::Result<Option<DownloadTask>> {
        self.connection
            .lock()
            .unwrap()
            .query_row(&format!("SELECT {COLUMNS} FROM downloadTask WHERE id = ?"), [id], |row| map_row(row, &self.log))
            .optional()
    }

    /// The oldest queued task: what a worker runs next.
    pub fn next_queued(&self) -> rusqlite::Result<Option<DownloadTask>> {
        self.connection
            .lock()
            .unwrap()
            .query_row(
                &format!("SELECT {COLUMNS} FROM downloadTask WHERE state = ? ORDER BY created_at LIMIT 1"),
                [DownloadState::Queued.name()],
                |row| map_row(row, &self.log),
            )
            .optional()
    }

    pub fn insert(&self, task: &DownloadTask) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute(
            &format!(
                "INSERT OR REPLACE INTO downloadTask({COLUMNS}) VALUES
                 (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ),
            params![
                task.id,
                task.video_id,
                task.title,
                task.artist,
                task.thumbnail_url,
                task.format.name(),
                task.requested_height,
                task.state.name(),
                task.downloaded_bytes,
                task.total_bytes,
                task.file_uri,
                task.error.as_ref().and_then(|error| error.user_message.clone()),
                task.error.as_ref().map(|error| error.code.clone()),
                task.created_at,
                task.updated_at,
                task.file_name,
                task.audio_track_id,
                task.captions.as_ref().map(|keys| keys.join(",")),
                task.audio_language,
                task.caption_language,
                task.audio_quality.name(),
                task.audio_stream.map(|stream| stream.itag),
                task.audio_stream.map(|stream| stream.lmt),
                task.video_stream.map(|stream| stream.itag),
                task.video_stream.map(|stream| stream.lmt),
                task.loudness_gain_db,
            ],
        )?;
        Ok(())
    }

    pub fn update_state(
        &self,
        id: &str,
        state: DownloadState,
        error: Option<&TaskError>,
        now: i64,
    ) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute(
            "UPDATE downloadTask SET state = ?, error_message = ?, error_code = ?, updated_at = ? WHERE id = ?",
            params![
                state.name(),
                error.and_then(|error| error.user_message.clone()),
                error.map(|error| error.code.clone()),
                now,
                id
            ],
        )?;
        Ok(())
    }

    pub fn update_streams(
        &self,
        id: &str,
        format: DownloadFormat,
        audio: StreamId,
        video: Option<StreamId>,
        now: i64,
    ) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute(
            "UPDATE downloadTask SET format = ?, audio_itag = ?, audio_lmt = ?, video_itag = ?, video_lmt = ?,
             updated_at = ? WHERE id = ?",
            params![
                format.name(),
                audio.itag,
                audio.lmt,
                video.map(|stream| stream.itag),
                video.map(|stream| stream.lmt),
                now,
                id
            ],
        )?;
        Ok(())
    }

    pub fn update_progress(&self, id: &str, done: i64, total: i64, now: i64) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute(
            "UPDATE downloadTask SET downloaded_bytes = ?, total_bytes = ?, updated_at = ? WHERE id = ?",
            params![done, total, now, id],
        )?;
        Ok(())
    }

    pub fn update_completed(
        &self,
        id: &str,
        file_uri: &str,
        bytes: i64,
        loudness_gain_db: Option<f64>,
        now: i64,
    ) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute(
            "UPDATE downloadTask SET state = ?, file_path = ?, downloaded_bytes = ?, total_bytes = ?,
             loudness_gain_db = ?, error_message = NULL, error_code = NULL, updated_at = ? WHERE id = ?",
            params![DownloadState::Completed.name(), file_uri, bytes, bytes, loudness_gain_db, now, id],
        )?;
        Ok(())
    }

    /// Startup recovery: everything running or waiting is held as paused.
    pub fn pause_active_and_queued(&self, now: i64) -> rusqlite::Result<usize> {
        let active: Vec<String> = DownloadState::ALL
            .into_iter()
            .filter(|state| state.is_active())
            .map(|state| format!("'{}'", state.name()))
            .collect();
        self.connection.lock().unwrap().execute(
            &format!("UPDATE downloadTask SET state = ?, updated_at = ? WHERE state IN ({})", active.join(",")),
            params![DownloadState::Paused.name(), now],
        )
    }

    pub fn delete(&self, id: &str) -> rusqlite::Result<()> {
        self.connection.lock().unwrap().execute("DELETE FROM downloadTask WHERE id = ?", [id])?;
        Ok(())
    }
}

fn map_row(row: &Row, log: &Logger) -> rusqlite::Result<DownloadTask> {
    let stream = |itag: Option<i32>, lmt: Option<i64>| match (itag, lmt) {
        (Some(itag), Some(lmt)) => Some(StreamId { itag, lmt }),
        _ => None,
    };
    let format: String = row.get(5)?;
    let state: String = row.get(7)?;
    let error_code: Option<String> = row.get(12)?;
    let caption_keys: Option<String> = row.get(17)?;
    let quality: String = row.get(20)?;
    let id: String = row.get(0)?;
    let format = DownloadFormat::from_name(&format).unwrap_or_else(|| {
        log.warn(&format!("task {id} has unknown format '{format}', taking M4A"), None);
        DownloadFormat::M4a
    });
    let state = DownloadState::from_name(&state).unwrap_or_else(|| {
        log.warn(&format!("task {id} has unknown state '{state}', taking FAILED"), None);
        DownloadState::Failed
    });
    Ok(DownloadTask {
        id,
        video_id: row.get(1)?,
        title: row.get(2)?,
        artist: row.get(3)?,
        thumbnail_url: row.get(4)?,
        format,
        requested_height: row.get(6)?,
        state,
        downloaded_bytes: row.get(8)?,
        total_bytes: row.get(9)?,
        file_uri: row.get(10)?,
        error: error_code.map(|code| TaskError { code, user_message: row.get(11).ok().flatten() }),
        created_at: row.get(13)?,
        updated_at: row.get(14)?,
        file_name: row.get(15)?,
        audio_track_id: row.get(16)?,
        captions: caption_keys.map(|keys| keys.split(',').filter(|key| !key.is_empty()).map(str::to_owned).collect()),
        audio_language: row.get(18)?,
        caption_language: row.get(19)?,
        audio_quality: AudioQuality::from_name(&quality).unwrap_or(AudioQuality::Best),
        audio_stream: stream(row.get(21)?, row.get(22)?),
        video_stream: stream(row.get(23)?, row.get(24)?),
        loudness_gain_db: row.get(25)?,
    })
}
