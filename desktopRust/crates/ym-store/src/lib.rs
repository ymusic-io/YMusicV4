//! The local media library: `:media-store`'s schema on SQLite (buckets = folders, media items,
//! playlists) and the folder scan that reconciles a directory tree into it.

mod scan;

use std::collections::HashSet;
use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{Connection, OptionalExtension, Row, params};

pub use scan::{DescribedFile, ScanError, ScanReport, describe_file, is_media_file, scan_file, scan_folder};

/// `:media-store`'s schema (SQLDelight version 6): the `.sq` files for a new file, the five
/// `migrations/n.sqm` for an older one from the JVM app.
const SCHEMA_VERSION: i32 = 6;
const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS bucket (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parentId  INTEGER REFERENCES bucket(id) ON DELETE CASCADE,
    uri       TEXT    NOT NULL UNIQUE,
    name      TEXT    NOT NULL,
    aliasName TEXT,
    deleted   INTEGER NOT NULL DEFAULT 0,
    excluded  INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS bucket_parentId ON bucket(parentId);
CREATE TABLE IF NOT EXISTS mediaItem (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    bucketId             INTEGER REFERENCES bucket(id) ON DELETE CASCADE,
    uri                  TEXT    NOT NULL UNIQUE,
    title                TEXT    NOT NULL,
    mimeType             TEXT    NOT NULL,
    artist               TEXT,
    album                TEXT,
    genre                TEXT,
    trackNumber          INTEGER,
    year                 INTEGER,
    durationMs           INTEGER,
    audioCodec           TEXT,
    videoCodec           TEXT,
    subtitleCodec        TEXT,
    bitrate              INTEGER,
    width                INTEGER,
    height               INTEGER,
    thumbnailStreamIndex INTEGER,
    youtubeVid           TEXT,
    youtubeItags         TEXT,
    fileSize             INTEGER NOT NULL,
    lastModified         INTEGER NOT NULL,
    deleted              INTEGER NOT NULL DEFAULT 0,
    createdAt            INTEGER NOT NULL,
    updatedAt            INTEGER NOT NULL,
    loudnessGainDb       REAL
);
CREATE INDEX IF NOT EXISTS mediaItem_bucketId   ON mediaItem(bucketId);
CREATE INDEX IF NOT EXISTS mediaItem_fileSize   ON mediaItem(fileSize);
CREATE INDEX IF NOT EXISTS mediaItem_updatedAt  ON mediaItem(updatedAt);
CREATE INDEX IF NOT EXISTS mediaItem_youtubeVid ON mediaItem(youtubeVid);
CREATE TABLE IF NOT EXISTS playlist (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    name      TEXT    NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS playlistItem (
    playlistId INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
    code       TEXT    NOT NULL,
    sortKey    INTEGER NOT NULL,
    addedAt    INTEGER NOT NULL,
    PRIMARY KEY (playlistId, code)
);
CREATE INDEX IF NOT EXISTS playlistItem_order ON playlistItem(playlistId, sortKey);
";

const MIGRATIONS: [&str; 5] = [
    "ALTER TABLE mediaItem ADD COLUMN genre TEXT;",
    "ALTER TABLE bucket ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0;",
    "
DELETE FROM playlist WHERE id <= 0;
ALTER TABLE mediaItem ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE mediaItem ADD COLUMN lastPlayedAt INTEGER;
CREATE INDEX mediaItem_lastPlayedAt ON mediaItem(lastPlayedAt);
CREATE INDEX mediaItem_playCount ON mediaItem(playCount);
",
    "
CREATE TABLE mediaItem_new (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    bucketId             INTEGER REFERENCES bucket(id) ON DELETE CASCADE,
    uri                  TEXT    NOT NULL UNIQUE,
    title                TEXT    NOT NULL,
    mimeType             TEXT    NOT NULL,
    artist               TEXT,
    album                TEXT,
    genre                TEXT,
    trackNumber          INTEGER,
    year                 INTEGER,
    durationMs           INTEGER,
    audioCodec           TEXT,
    videoCodec           TEXT,
    subtitleCodec        TEXT,
    bitrate              INTEGER,
    width                INTEGER,
    height               INTEGER,
    thumbnailStreamIndex INTEGER,
    youtubeVid           TEXT,
    youtubeItags         TEXT,
    fileSize             INTEGER NOT NULL,
    lastModified         INTEGER NOT NULL,
    deleted              INTEGER NOT NULL DEFAULT 0,
    createdAt            INTEGER NOT NULL,
    updatedAt            INTEGER NOT NULL
);
INSERT INTO mediaItem_new (
    id, bucketId, uri, title, mimeType, artist, album, genre, trackNumber, year, durationMs,
    audioCodec, videoCodec, subtitleCodec, bitrate, width, height, thumbnailStreamIndex,
    youtubeVid, youtubeItags, fileSize, lastModified, deleted, createdAt, updatedAt
)
SELECT
    id, bucketId, uri, title, mimeType, artist, album, genre, trackNumber, year, durationMs,
    audioCodec, videoCodec, subtitleCodec, bitrate, width, height, thumbnailStreamIndex,
    youtubeVid, youtubeItags, fileSize, lastModified, deleted, createdAt, updatedAt
FROM mediaItem;
DROP TABLE mediaItem;
ALTER TABLE mediaItem_new RENAME TO mediaItem;
CREATE INDEX mediaItem_bucketId     ON mediaItem(bucketId);
CREATE INDEX mediaItem_fileSize     ON mediaItem(fileSize);
CREATE INDEX mediaItem_updatedAt    ON mediaItem(updatedAt);
CREATE INDEX mediaItem_youtubeVid   ON mediaItem(youtubeVid);
",
    "ALTER TABLE mediaItem ADD COLUMN loudnessGainDb REAL;",
];

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("media store: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("media store schema {found} is newer than this build ({SCHEMA_VERSION})")]
    NewerSchema { found: i32 },
    #[error("media store: another live item already has {0}")]
    UriTaken(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct Bucket {
    pub id: i64,
    pub parent_id: Option<i64>,
    pub uri: String,
    pub name: String,
    pub alias_name: Option<String>,
    pub excluded: bool,
    pub created_at: i64,
}

impl Bucket {
    pub fn display_name(&self) -> &str {
        self.alias_name.as_deref().filter(|alias| !alias.trim().is_empty()).unwrap_or(&self.name)
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct MediaItem {
    pub id: i64,
    pub bucket_id: Option<i64>,
    pub uri: String,
    pub title: String,
    pub mime_type: String,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub genre: Option<String>,
    pub track_number: Option<i32>,
    pub year: Option<i32>,
    pub duration_ms: Option<i64>,
    pub audio_codec: Option<String>,
    pub video_codec: Option<String>,
    pub subtitle_codec: Option<String>,
    pub bitrate: Option<i64>,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub thumbnail_stream_index: Option<i32>,
    pub youtube_vid: Option<String>,
    pub file_size: i64,
    pub last_modified: i64,
    pub loudness_gain_db: Option<f64>,
    pub created_at: i64,
}

impl MediaItem {
    /// The content fingerprint playlists and play history key a file by: its YouTube id when tagged,
    /// else `@fileSize:durationSec`, so moves, renames and rescans keep the link.
    pub fn playlist_code(&self) -> String {
        self.youtube_vid
            .clone()
            .unwrap_or_else(|| format!("@{}:{}", self.file_size, self.duration_ms.unwrap_or(0) / 1000))
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Playlist {
    pub id: i64,
    pub name: String,
    pub created_at: i64,
}

/// A playlist membership row; `item` is what its code resolves to now, if anything.
#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistMember {
    pub code: String,
    pub added_at: i64,
    pub item: Option<MediaItem>,
}

/// A playlist as a backup carries it: name, creation time and member codes in order.
#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistSnapshot {
    pub name: String,
    pub created_at: i64,
    pub items: Vec<PlaylistSnapshotItem>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistSnapshotItem {
    pub code: String,
    pub added_at: i64,
}

/// A playlist membership row with the media item its code resolves to now.
#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistItemDetail {
    pub code: String,
    pub added_at: i64,
    pub item: MediaItem,
}

const ITEM_COLUMNS: &str = "id, bucketId, uri, title, mimeType, artist, album, genre, trackNumber, year, durationMs, \
    audioCodec, videoCodec, subtitleCodec, bitrate, width, height, thumbnailStreamIndex, youtubeVid, fileSize, \
    lastModified, loudnessGainDb, createdAt";

fn item_from_row(row: &Row<'_>) -> rusqlite::Result<MediaItem> {
    Ok(MediaItem {
        id: row.get(0)?,
        bucket_id: row.get(1)?,
        uri: path_of(row.get(2)?),
        title: row.get(3)?,
        mime_type: row.get(4)?,
        artist: row.get(5)?,
        album: row.get(6)?,
        genre: row.get(7)?,
        track_number: row.get(8)?,
        year: row.get(9)?,
        duration_ms: row.get(10)?,
        audio_codec: row.get(11)?,
        video_codec: row.get(12)?,
        subtitle_codec: row.get(13)?,
        bitrate: row.get(14)?,
        width: row.get(15)?,
        height: row.get(16)?,
        thumbnail_stream_index: row.get(17)?,
        youtube_vid: row.get(18)?,
        file_size: row.get(19)?,
        last_modified: row.get(20)?,
        loudness_gain_db: row.get(21)?,
        created_at: row.get(22)?,
    })
}

fn bucket_from_row(row: &Row<'_>) -> rusqlite::Result<Bucket> {
    Ok(Bucket {
        id: row.get(0)?,
        parent_id: row.get(1)?,
        uri: path_of(row.get(2)?),
        name: row.get(3)?,
        alias_name: row.get(4)?,
        excluded: row.get::<_, i64>(5)? != 0,
        created_at: row.get(6)?,
    })
}

const BUCKET_COLUMNS: &str = "id, parentId, uri, name, aliasName, excluded, createdAt";

/// The URI a row holds for `path`: rows keep the JVM app's `file://` URIs, while this API speaks
/// filesystem paths.
pub fn file_uri(path: &str) -> String {
    ym_common::file_uri::to_file_uri(path)
}

fn uri_of(path: &str) -> String {
    file_uri(path)
}

fn path_of(uri: String) -> String {
    ym_common::file_uri::file_uri_path(&uri).unwrap_or(uri)
}

/// Rows earlier builds wrote with bare paths take the URI form; a path whose URI row already exists
/// loses its duplicate item row.
fn adopt_file_uris(db: &Connection) -> rusqlite::Result<()> {
    for table in ["bucket", "mediaItem"] {
        let bare: Vec<(i64, String)> = db
            .prepare(&format!("SELECT id, uri FROM {table} WHERE uri NOT LIKE 'file:%'"))?
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?
            .collect::<rusqlite::Result<_>>()?;
        for (id, path) in bare {
            db.execute(&format!("UPDATE OR IGNORE {table} SET uri = ?1 WHERE id = ?2"), params![uri_of(&path), id])?;
        }
    }
    db.execute("DELETE FROM mediaItem WHERE uri NOT LIKE 'file:%'", [])?;
    Ok(())
}

fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |elapsed| elapsed.as_millis() as i64)
}

/// Every call blocks on SQLite: call off the UI and engine threads.
pub struct MediaStore {
    db: Mutex<Connection>,
    changes: tokio::sync::watch::Sender<u64>,
}

impl MediaStore {
    pub fn open(path: &Path) -> Result<Self, StoreError> {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let db = Connection::open(path)?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        db.pragma_update(None, "synchronous", "NORMAL")?;
        db.busy_timeout(std::time::Duration::from_secs(5))?;
        let version: i32 = db.query_row("PRAGMA user_version", [], |row| row.get(0))?;
        if version > SCHEMA_VERSION {
            return Err(StoreError::NewerSchema { found: version });
        }
        db.execute_batch("PRAGMA foreign_keys = ON;")?;
        ym_common::sqlite::open_schema(
            &db,
            SCHEMA_VERSION as i64,
            |db| db.execute_batch(SCHEMA),
            |db, found| ym_common::sqlite::run_migrations(db, found, &MIGRATIONS),
        )?;
        adopt_file_uris(&db)?;
        Ok(Self { db: Mutex::new(db), changes: tokio::sync::watch::Sender::new(0) })
    }

    /// Ticks after every write (buckets, items, playlists), the JVM store's table-change flows.
    pub fn changes(&self) -> tokio::sync::watch::Receiver<u64> {
        self.changes.subscribe()
    }

    fn changed(&self) {
        self.changes.send_modify(|generation| *generation += 1);
    }

    /// The live bucket for the folder at `path`, excluded or not.
    pub fn find_bucket_by_uri(&self, path: &str) -> Result<Option<Bucket>, StoreError> {
        let db = self.db.lock().unwrap();
        let sql = format!("SELECT {BUCKET_COLUMNS} FROM bucket WHERE uri = ?1 AND deleted = 0");
        Ok(db.query_row(&sql, params![uri_of(path)], bucket_from_row).optional()?)
    }

    /// The bucket for `uri`, created (or revived from a tombstone) under `parent_id`.
    pub fn upsert_bucket(&self, uri: &str, name: &str, parent_id: Option<i64>) -> Result<i64, StoreError> {
        let uri = &uri_of(uri);
        let db = self.db.lock().unwrap();
        let now = now_millis();
        if let Some(id) =
            db.query_row("SELECT id FROM bucket WHERE uri = ?1", params![uri], |row| row.get::<_, i64>(0)).optional()?
        {
            db.execute(
                "UPDATE bucket SET parentId = ?1, name = ?2, deleted = 0, updatedAt = ?3 WHERE id = ?4",
                params![parent_id, name, now, id],
            )?;
            self.changed();
            return Ok(id);
        }
        db.execute(
            "INSERT INTO bucket(parentId, uri, name, createdAt, updatedAt) VALUES (?1, ?2, ?3, ?4, ?4)",
            params![parent_id, uri, name, now],
        )?;
        self.changed();
        Ok(db.last_insert_rowid())
    }

    pub fn root_buckets(&self) -> Result<Vec<Bucket>, StoreError> {
        self.buckets("SELECT {} FROM bucket WHERE parentId IS NULL AND deleted = 0 ORDER BY name COLLATE NOCASE", [])
    }

    pub fn child_buckets(&self, parent_id: i64) -> Result<Vec<Bucket>, StoreError> {
        self.buckets(
            "SELECT {} FROM bucket WHERE parentId = ?1 AND deleted = 0 ORDER BY name COLLATE NOCASE",
            [parent_id],
        )
    }

    fn buckets<P: rusqlite::Params>(&self, sql: &str, args: P) -> Result<Vec<Bucket>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut statement = db.prepare(&sql.replace("{}", BUCKET_COLUMNS))?;
        let rows = statement.query_map(args, bucket_from_row)?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    pub fn set_bucket_deleted(&self, bucket_id: i64, deleted: bool) -> Result<(), StoreError> {
        let db = self.db.lock().unwrap();
        db.execute(
            "UPDATE bucket SET deleted = ?1, updatedAt = ?2 WHERE id = ?3",
            params![i64::from(deleted), now_millis(), bucket_id],
        )?;
        self.changed();
        Ok(())
    }

    /// A live item by its row id (the JVM app's `art://pic/<id>`).
    pub fn item(&self, id: i64) -> Result<Option<MediaItem>, StoreError> {
        let db = self.db.lock().unwrap();
        let sql = format!("SELECT {ITEM_COLUMNS} FROM mediaItem WHERE id = ?1 AND deleted = 0");
        Ok(db.query_row(&sql, params![id], item_from_row).optional()?)
    }

    /// Including soft-deleted rows, so a returning file revives its item.
    pub fn find_item_by_uri(&self, uri: &str) -> Result<Option<(MediaItem, bool)>, StoreError> {
        let db = self.db.lock().unwrap();
        let sql = format!("SELECT {ITEM_COLUMNS}, deleted FROM mediaItem WHERE uri = ?1");
        Ok(db
            .query_row(&sql, params![uri_of(uri)], |row| Ok((item_from_row(row)?, row.get::<_, i64>(23)? != 0)))
            .optional()?)
    }

    /// Inserts `item` (its `id` ignored) or rewrites the row with its `uri`, reviving it.
    pub fn save_item(&self, item: &MediaItem) -> Result<i64, StoreError> {
        let uri = uri_of(&item.uri);
        let db = self.db.lock().unwrap();
        let now = now_millis();
        let changed = db.execute(
            "UPDATE mediaItem SET bucketId = ?1, title = ?2, mimeType = ?3, artist = ?4, album = ?5, genre = ?6, \
             trackNumber = ?7, year = ?8, durationMs = ?9, audioCodec = ?10, videoCodec = ?11, subtitleCodec = ?12, \
             bitrate = ?13, width = ?14, height = ?15, thumbnailStreamIndex = ?16, fileSize = ?17, \
             lastModified = ?18, youtubeVid = ?21, loudnessGainDb = ?22, deleted = 0, updatedAt = ?19 WHERE uri = ?20",
            params![
                item.bucket_id,
                item.title,
                item.mime_type,
                item.artist,
                item.album,
                item.genre,
                item.track_number,
                item.year,
                item.duration_ms,
                item.audio_codec,
                item.video_codec,
                item.subtitle_codec,
                item.bitrate,
                item.width,
                item.height,
                item.thumbnail_stream_index,
                item.file_size,
                item.last_modified,
                now,
                uri,
                item.youtube_vid,
                item.loudness_gain_db
            ],
        )?;
        if changed > 0 {
            self.changed();
            return Ok(db.query_row("SELECT id FROM mediaItem WHERE uri = ?1", params![uri], |row| row.get(0))?);
        }
        db.execute(
            "INSERT INTO mediaItem(bucketId, uri, title, mimeType, artist, album, genre, trackNumber, year, \
             durationMs, audioCodec, videoCodec, subtitleCodec, bitrate, width, height, thumbnailStreamIndex, \
             fileSize, lastModified, createdAt, updatedAt, youtubeVid, loudnessGainDb) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?20, ?21, ?22)",
            params![
                item.bucket_id,
                uri,
                item.title,
                item.mime_type,
                item.artist,
                item.album,
                item.genre,
                item.track_number,
                item.year,
                item.duration_ms,
                item.audio_codec,
                item.video_codec,
                item.subtitle_codec,
                item.bitrate,
                item.width,
                item.height,
                item.thumbnail_stream_index,
                item.file_size,
                item.last_modified,
                now,
                item.youtube_vid,
                item.loudness_gain_db
            ],
        )?;
        self.changed();
        Ok(db.last_insert_rowid())
    }

    /// Soft-deletes the bucket's items whose uri is not in `present`. Returns how many.
    pub fn delete_missing_items(&self, bucket_id: i64, present: &[String]) -> Result<usize, StoreError> {
        let present: HashSet<String> = present.iter().map(|path| uri_of(path)).collect();
        let db = self.db.lock().unwrap();
        let mut statement = db.prepare("SELECT id, uri FROM mediaItem WHERE bucketId = ?1 AND deleted = 0")?;
        let missing: Vec<i64> = statement
            .query_map(params![bucket_id], |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?
            .into_iter()
            .filter(|(_, uri)| !present.contains(uri))
            .map(|(id, _)| id)
            .collect();
        let now = now_millis();
        for id in &missing {
            db.execute("UPDATE mediaItem SET deleted = 1, updatedAt = ?1 WHERE id = ?2", params![now, id])?;
        }
        if !missing.is_empty() {
            self.changed();
        }
        Ok(missing.len())
    }

    pub fn items_in_bucket(&self, bucket_id: i64) -> Result<Vec<MediaItem>, StoreError> {
        let db = self.db.lock().unwrap();
        let sql = format!(
            "SELECT {ITEM_COLUMNS} FROM mediaItem WHERE bucketId = ?1 AND deleted = 0 \
             ORDER BY trackNumber IS NULL, trackNumber, title COLLATE NOCASE"
        );
        let mut statement = db.prepare(&sql)?;
        let rows = statement.query_map(params![bucket_id], item_from_row)?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    pub fn all_items(&self) -> Result<Vec<MediaItem>, StoreError> {
        let db = self.db.lock().unwrap();
        let sql = format!("SELECT {ITEM_COLUMNS} FROM mediaItem WHERE deleted = 0 ORDER BY title COLLATE NOCASE");
        let mut statement = db.prepare(&sql)?;
        let rows = statement.query_map([], item_from_row)?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    /// The YouTube video ids the Library holds a file of.
    pub fn youtube_video_ids(&self) -> Result<Vec<String>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut statement =
            db.prepare("SELECT DISTINCT youtubeVid FROM mediaItem WHERE youtubeVid IS NOT NULL AND deleted = 0")?;
        let rows = statement.query_map([], |row| row.get(0))?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    /// The live bucket `bucket_id` and its ancestors, root first; empty when it is gone.
    pub fn bucket_chain(&self, bucket_id: i64) -> Result<Vec<Bucket>, StoreError> {
        let db = self.db.lock().unwrap();
        let sql = format!("SELECT {BUCKET_COLUMNS} FROM bucket WHERE id = ?1 AND deleted = 0");
        let mut chain = Vec::new();
        let mut next = Some(bucket_id);
        while let Some(id) = next {
            let Some(bucket) = db.query_row(&sql, params![id], bucket_from_row).optional()? else { break };
            next = bucket.parent_id;
            chain.insert(0, bucket);
        }
        Ok(chain)
    }

    /// Tombstones the bucket's whole subtree, items included, so a later scan of it revives them.
    pub fn remove_bucket(&self, bucket_id: i64) -> Result<(), StoreError> {
        let mut db = self.db.lock().unwrap();
        let transaction = db.transaction()?;
        soft_delete_subtree(&transaction, bucket_id, now_millis())?;
        transaction.commit()?;
        self.changed();
        Ok(())
    }

    /// Excluding keeps the folder's row (flagged, so it can be included again) but drops its
    /// subfolders and tombstones every item below it; scans skip it until it is included.
    pub fn set_bucket_excluded(&self, bucket_id: i64, excluded: bool) -> Result<(), StoreError> {
        let mut db = self.db.lock().unwrap();
        let transaction = db.transaction()?;
        let now = now_millis();
        if excluded {
            delete_descendants(&transaction, bucket_id, now)?;
            transaction.execute(
                "UPDATE mediaItem SET deleted = 1, updatedAt = ?1 WHERE bucketId = ?2 AND deleted = 0",
                params![now, bucket_id],
            )?;
        }
        transaction.execute(
            "UPDATE bucket SET excluded = ?1, updatedAt = ?2 WHERE id = ?3",
            params![i64::from(excluded), now, bucket_id],
        )?;
        transaction.commit()?;
        self.changed();
        Ok(())
    }

    /// The excluded folders as stored: `file://` URIs, to match against [`file_uri`].
    pub fn excluded_bucket_uris(&self) -> Result<HashSet<String>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut statement = db.prepare("SELECT uri FROM bucket WHERE excluded = 1 AND deleted = 0")?;
        let rows = statement.query_map([], |row| row.get(0))?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    /// Points the item at its file's new `uri` (a rename or move); a soft-deleted row already holding
    /// `uri` gives way, a live one is an error.
    pub fn relocate_item(&self, item_id: i64, path: &str) -> Result<(), StoreError> {
        let uri = uri_of(path);
        let mut db = self.db.lock().unwrap();
        let transaction = db.transaction()?;
        let holder: Option<(i64, i64)> = transaction
            .query_row("SELECT id, deleted FROM mediaItem WHERE uri = ?1", params![uri], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })
            .optional()?;
        if let Some((other, deleted)) = holder.filter(|(other, _)| *other != item_id) {
            if deleted == 0 {
                return Err(StoreError::UriTaken(path.to_owned()));
            }
            transaction.execute("DELETE FROM mediaItem WHERE id = ?1", params![other])?;
        }
        transaction.execute(
            "UPDATE mediaItem SET uri = ?1, updatedAt = ?2 WHERE id = ?3",
            params![uri, now_millis(), item_id],
        )?;
        transaction.commit()?;
        self.changed();
        Ok(())
    }

    /// Drops the item row for good (its file is gone).
    pub fn delete_item(&self, item_id: i64) -> Result<(), StoreError> {
        self.db.lock().unwrap().execute("DELETE FROM mediaItem WHERE id = ?1", params![item_id])?;
        self.changed();
        Ok(())
    }

    pub fn create_playlist(&self, name: &str) -> Result<Playlist, StoreError> {
        let db = self.db.lock().unwrap();
        let now = now_millis();
        db.execute("INSERT INTO playlist(name, createdAt, updatedAt) VALUES (?1, ?2, ?2)", params![name, now])?;
        self.changed();
        Ok(Playlist { id: db.last_insert_rowid(), name: name.to_owned(), created_at: now })
    }

    pub fn rename_playlist(&self, playlist_id: i64, name: &str) -> Result<(), StoreError> {
        let db = self.db.lock().unwrap();
        db.execute(
            "UPDATE playlist SET name = ?1, updatedAt = ?2 WHERE id = ?3",
            params![name, now_millis(), playlist_id],
        )?;
        self.changed();
        Ok(())
    }

    /// Its membership rows go with it; the songs stay in the library.
    pub fn delete_playlist(&self, playlist_id: i64) -> Result<(), StoreError> {
        self.db.lock().unwrap().execute("DELETE FROM playlist WHERE id = ?1", params![playlist_id])?;
        self.changed();
        Ok(())
    }

    pub fn playlists(&self) -> Result<Vec<Playlist>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut statement = db.prepare("SELECT id, name, createdAt FROM playlist ORDER BY id")?;
        let rows = statement
            .query_map([], |row| Ok(Playlist { id: row.get(0)?, name: row.get(1)?, created_at: row.get(2)? }))?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    /// Appends `codes` in order; members already in the playlist keep their place. A YouTube id
    /// joins before its file exists and shows up once a Library item carries it.
    pub fn add_codes_to_playlist(&self, playlist_id: i64, codes: &[String]) -> Result<(), StoreError> {
        let mut db = self.db.lock().unwrap();
        let transaction = db.transaction()?;
        let now = now_millis();
        let last: i64 = transaction.query_row(
            "SELECT COALESCE(MAX(sortKey), 0) FROM playlistItem WHERE playlistId = ?1",
            params![playlist_id],
            |row| row.get(0),
        )?;
        let mut sort_key = last.max(now);
        for code in codes {
            sort_key += 1;
            transaction.execute(
                "INSERT OR IGNORE INTO playlistItem(playlistId, code, sortKey, addedAt) VALUES (?1, ?2, ?3, ?4)",
                params![playlist_id, code, sort_key, now],
            )?;
        }
        transaction.commit()?;
        self.changed();
        Ok(())
    }

    pub fn remove_from_playlist(&self, playlist_id: i64, code: &str) -> Result<(), StoreError> {
        let db = self.db.lock().unwrap();
        db.execute("DELETE FROM playlistItem WHERE playlistId = ?1 AND code = ?2", params![playlist_id, code])?;
        self.changed();
        Ok(())
    }

    /// Rewrites the order to `ordered_codes`; a code left out keeps its old key.
    pub fn reorder_playlist(&self, playlist_id: i64, ordered_codes: &[String]) -> Result<(), StoreError> {
        let mut db = self.db.lock().unwrap();
        let transaction = db.transaction()?;
        for (index, code) in ordered_codes.iter().enumerate() {
            transaction.execute(
                "UPDATE playlistItem SET sortKey = ?1 WHERE playlistId = ?2 AND code = ?3",
                params![index as i64, playlist_id, code],
            )?;
        }
        transaction.commit()?;
        self.changed();
        Ok(())
    }

    /// Every membership code in playlist order, resolvable or not.
    pub fn playlist_codes(&self, playlist_id: i64) -> Result<Vec<String>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut statement = db.prepare("SELECT code FROM playlistItem WHERE playlistId = ?1 ORDER BY sortKey")?;
        let rows = statement.query_map(params![playlist_id], |row| row.get(0))?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    /// Every membership row in playlist order, each with the item it resolves to now.
    pub fn playlist_members(&self, playlist_id: i64) -> Result<Vec<PlaylistMember>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut statement =
            db.prepare("SELECT code, addedAt FROM playlistItem WHERE playlistId = ?1 ORDER BY sortKey")?;
        let rows: Vec<(String, i64)> = statement
            .query_map(params![playlist_id], |row| Ok((row.get(0)?, row.get(1)?)))?
            .collect::<rusqlite::Result<_>>()?;
        let mut members = Vec::with_capacity(rows.len());
        for (code, added_at) in rows {
            let item = resolve_code(&db, &code)?;
            members.push(PlaylistMember { code, added_at, item });
        }
        Ok(members)
    }

    /// Writes backed-up playlists in one transaction. `replace_all` deletes every playlist first and
    /// recreates the snapshots exactly; otherwise each snapshot merges into the first playlist of the
    /// same name (trimmed, case-insensitive) or a new one, appending members it lacks.
    pub fn restore_playlists(&self, snapshots: &[PlaylistSnapshot], replace_all: bool) -> Result<(), StoreError> {
        let mut db = self.db.lock().unwrap();
        let transaction = db.transaction()?;
        let now = now_millis();
        if replace_all {
            transaction.execute("DELETE FROM playlist", [])?;
            for snapshot in snapshots {
                transaction.execute(
                    "INSERT INTO playlist(name, createdAt, updatedAt) VALUES (?1, ?2, ?3)",
                    params![snapshot.name, snapshot.created_at, now],
                )?;
                let id = transaction.last_insert_rowid();
                for (index, item) in snapshot.items.iter().enumerate() {
                    transaction.execute(
                        "INSERT OR IGNORE INTO playlistItem(playlistId, code, sortKey, addedAt) VALUES (?1, ?2, ?3, ?4)",
                        params![id, item.code, index as i64, item.added_at],
                    )?;
                }
            }
        } else {
            let mut existing: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
            {
                let mut statement = transaction.prepare("SELECT id, name FROM playlist ORDER BY id")?;
                let rows = statement.query_map([], |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)))?;
                for row in rows {
                    let (id, name) = row?;
                    existing.entry(name.trim().to_lowercase()).or_insert(id);
                }
            }
            for snapshot in snapshots {
                let name = snapshot.name.trim();
                let id = match existing.get(&name.to_lowercase()) {
                    Some(id) => *id,
                    None => {
                        transaction.execute(
                            "INSERT INTO playlist(name, createdAt, updatedAt) VALUES (?1, ?2, ?3)",
                            params![name, snapshot.created_at, now],
                        )?;
                        let id = transaction.last_insert_rowid();
                        existing.insert(name.to_lowercase(), id);
                        id
                    }
                };
                let last: i64 = transaction.query_row(
                    "SELECT COALESCE(MAX(sortKey), 0) FROM playlistItem WHERE playlistId = ?1",
                    params![id],
                    |row| row.get(0),
                )?;
                let mut sort_key = last.max(now);
                for item in &snapshot.items {
                    sort_key += 1;
                    transaction.execute(
                        "INSERT OR IGNORE INTO playlistItem(playlistId, code, sortKey, addedAt) VALUES (?1, ?2, ?3, ?4)",
                        params![id, item.code, sort_key, item.added_at],
                    )?;
                }
            }
        }
        transaction.commit()?;
        self.changed();
        Ok(())
    }

    /// The members the library can resolve now, in playlist order.
    pub fn playlist_item_details(&self, playlist_id: i64) -> Result<Vec<PlaylistItemDetail>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut statement =
            db.prepare("SELECT code, addedAt FROM playlistItem WHERE playlistId = ?1 ORDER BY sortKey")?;
        let rows: Vec<(String, i64)> = statement
            .query_map(params![playlist_id], |row| Ok((row.get(0)?, row.get(1)?)))?
            .collect::<rusqlite::Result<_>>()?;
        let mut details = Vec::with_capacity(rows.len());
        for (code, added_at) in rows {
            if let Some(item) = resolve_code(&db, &code)? {
                details.push(PlaylistItemDetail { code, added_at, item });
            }
        }
        Ok(details)
    }
}

/// The inverse of [`MediaItem::playlist_code`]; on a fingerprint collision the first match wins.
fn resolve_code(db: &Connection, code: &str) -> Result<Option<MediaItem>, StoreError> {
    if let Some(fingerprint) = code.strip_prefix('@') {
        let Some((size, seconds)) = fingerprint.split_once(':') else { return Ok(None) };
        let (Ok(size), Ok(seconds)) = (size.parse::<i64>(), seconds.parse::<i64>()) else { return Ok(None) };
        let sql = format!(
            "SELECT {ITEM_COLUMNS} FROM mediaItem WHERE fileSize = ?1 AND COALESCE(durationMs, 0) / 1000 = ?2 \
             AND deleted = 0 LIMIT 1"
        );
        return Ok(db.query_row(&sql, params![size, seconds], item_from_row).optional()?);
    }
    let sql = format!("SELECT {ITEM_COLUMNS} FROM mediaItem WHERE youtubeVid = ?1 AND deleted = 0 LIMIT 1");
    Ok(db.query_row(&sql, params![code], item_from_row).optional()?)
}

fn child_ids(db: &Connection, bucket_id: i64) -> rusqlite::Result<Vec<i64>> {
    let mut statement = db.prepare("SELECT id FROM bucket WHERE parentId = ?1 AND deleted = 0")?;
    let rows = statement.query_map(params![bucket_id], |row| row.get(0))?;
    rows.collect()
}

fn soft_delete_subtree(db: &Connection, bucket_id: i64, now: i64) -> rusqlite::Result<()> {
    for child in child_ids(db, bucket_id)? {
        soft_delete_subtree(db, child, now)?;
    }
    db.execute(
        "UPDATE mediaItem SET deleted = 1, updatedAt = ?1 WHERE bucketId = ?2 AND deleted = 0",
        params![now, bucket_id],
    )?;
    db.execute("UPDATE bucket SET deleted = 1, updatedAt = ?1 WHERE id = ?2", params![now, bucket_id])?;
    Ok(())
}

/// Post-order: each descendant's items are orphaned and tombstoned before its row is deleted, so
/// the cascade does not take them (playlists and a later scan still find them).
fn delete_descendants(db: &Connection, bucket_id: i64, now: i64) -> rusqlite::Result<()> {
    let mut statement = db.prepare("SELECT id FROM bucket WHERE parentId = ?1")?;
    let children: Vec<i64> =
        statement.query_map(params![bucket_id], |row| row.get(0))?.collect::<rusqlite::Result<_>>()?;
    for child in children {
        delete_descendants(db, child, now)?;
        db.execute(
            "UPDATE mediaItem SET bucketId = NULL, deleted = 1, updatedAt = ?1 WHERE bucketId = ?2",
            params![now, child],
        )?;
        db.execute("DELETE FROM bucket WHERE id = ?1", params![child])?;
    }
    Ok(())
}
