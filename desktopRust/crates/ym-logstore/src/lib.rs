//! The app log: every line from every layer in one SQLite table (`logs.db`, the JVM app's
//! schema), the source of the feedback report and of the lines an error event takes to Sentry.

use std::collections::VecDeque;
use std::error::Error;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, TryLockError};
use std::time::{Duration, Instant};

use rusqlite::{Connection, params};
use ym_common::log::{Crumb, Level, LogSink, now_millis};

/// `:log-store`'s `LogDatabase` version: `Log.sq` plus `1.sqm`.
const SCHEMA_VERSION: i64 = 2;
const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS log_line (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    level INTEGER NOT NULL,
    launch TEXT NOT NULL,
    trail TEXT NOT NULL,
    message TEXT NOT NULL,
    state INTEGER NOT NULL DEFAULT 0
);
";
const MIGRATIONS: [&str; 1] = ["
ALTER TABLE log_line ADD COLUMN state INTEGER NOT NULL DEFAULT 0;
UPDATE log_line SET state = 2 WHERE seq <= (SELECT seq FROM sent_mark WHERE id = 0);
DROP TABLE sent_mark;
"];
const QUEUE_CAPACITY: usize = 4_096;
const MIN_LINES: i64 = 100;
/// The JVM schema's states: 0 unsent, 1 claimed for an error event and waiting to go out, 2 sent.
const STATE_PENDING: i64 = 1;
const STATE_SENT: i64 = 2;
const CLAIM_RETRY: Duration = Duration::from_millis(5);
/// How long a claim waits for the lines queued before it to be written.
const FLUSH_WAIT: Duration = Duration::from_secs(2);

#[derive(Debug, Clone, PartialEq)]
pub struct LogRecord {
    pub seq: i64,
    pub epoch_ms: i64,
    pub level: i64,
    pub launch: String,
    pub trail: String,
    pub message: String,
}

struct Line {
    ts: i64,
    level: i64,
    trail: String,
    message: String,
}

struct Queue {
    lines: VecDeque<Line>,
    closed: bool,
    /// Lines ever queued, and those written or dropped since: a flush waits for the second to catch up.
    queued: u64,
    done: u64,
}

/// Lines are queued without blocking (the oldest queued line drops under a burst) and written one
/// at a time by a writer thread; a claim first waits for the lines queued before it.
pub struct LogStore {
    /// This app open's id on each of its lines: 32 hex chars, so Sentry takes it as a trace id.
    pub launch: String,
    db: Arc<Mutex<Connection>>,
    queue: Arc<(Mutex<Queue>, Condvar)>,
    max_lines: Arc<AtomicI64>,
}

impl LogStore {
    /// Blocking.
    pub fn open(path: &Path) -> rusqlite::Result<Arc<Self>> {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let db = Connection::open(path)?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        ym_common::sqlite::open_schema(
            &db,
            SCHEMA_VERSION,
            |db| db.execute_batch(SCHEMA),
            |db, found| ym_common::sqlite::run_migrations(db, found, &MIGRATIONS),
        )?;
        let launch = {
            let seed = now_millis() as u128 ^ (std::process::id() as u128) << 64;
            let hashed = seed.wrapping_mul(0x9e37_79b9_7f4a_7c15_f39c_c060_5ced_c835);
            format!("{hashed:032x}")
        };
        let store = Arc::new(Self {
            launch,
            db: Arc::new(Mutex::new(db)),
            queue: Arc::new((
                Mutex::new(Queue { lines: VecDeque::new(), closed: false, queued: 0, done: 0 }),
                Condvar::new(),
            )),
            max_lines: Arc::new(AtomicI64::new(0)),
        });
        let (db, queue, max_lines, launch) =
            (store.db.clone(), store.queue.clone(), store.max_lines.clone(), store.launch.clone());
        std::thread::Builder::new()
            .name("log-store".into())
            .spawn(move || writer(db, queue, max_lines, launch))
            .map_err(|error| rusqlite::Error::ToSqlConversionFailure(Box::new(error)))?;
        Ok(store)
    }

    /// Keep the newest `lines`, trimmed once the table holds 10% more. Nothing trims until set.
    pub fn set_max_lines(&self, lines: i64) {
        self.max_lines.store(lines.max(MIN_LINES), Ordering::Relaxed);
    }

    /// Never blocks or fails.
    pub fn enqueue(&self, level: Level, trail: String, message: String) {
        let (queue, wake) = &*self.queue;
        let mut queue = queue.lock().unwrap();
        if queue.lines.len() >= QUEUE_CAPACITY {
            queue.lines.pop_front();
            queue.done += 1;
        }
        queue.lines.push_back(Line { ts: now_millis(), level: level_code(level), trail, message });
        queue.queued += 1;
        wake.notify_one();
    }

    /// Blocking: returns once every line queued before the call is written (or dropped), or at
    /// `deadline`. It polls, so a panic hook on a thread holding the queue gives up at the deadline
    /// rather than deadlocking.
    fn flush_until(&self, deadline: Instant) {
        let mut target = None;
        loop {
            let queue = match self.queue.0.try_lock() {
                Ok(queue) => Some(queue),
                Err(TryLockError::Poisoned(poisoned)) => Some(poisoned.into_inner()),
                Err(TryLockError::WouldBlock) => None,
            };
            if let Some(queue) = queue {
                let target = *target.get_or_insert(queue.queued);
                if queue.done >= target || queue.closed {
                    return;
                }
            }
            if Instant::now() >= deadline {
                return;
            }
            std::thread::sleep(CLAIM_RETRY);
        }
    }

    /// Blocking: lines after `after_seq`, oldest first, at most `limit`.
    pub fn lines_after(&self, after_seq: i64, limit: i64) -> rusqlite::Result<Vec<LogRecord>> {
        let db = self.db.lock().unwrap();
        let mut statement = db.prepare(
            "SELECT seq, ts, level, launch, trail, message FROM log_line WHERE seq > ?1 ORDER BY seq LIMIT ?2",
        )?;
        let rows = statement.query_map(params![after_seq, limit], record)?;
        rows.collect()
    }

    /// Blocking: claims this launch's unsent lines among its newest `limit` for an error event, the
    /// lines queued before the call among them (it waits for their write, at most 2 s);
    /// they stay [`Self::pending`] until [`Self::mark_sent`]. Returns how many it claimed.
    pub fn claim_unsent(&self, limit: i64) -> rusqlite::Result<usize> {
        self.flush_until(Instant::now() + FLUSH_WAIT);
        claim(&self.db.lock().unwrap(), &self.launch, limit)
    }

    /// [`Self::claim_unsent`] for a panic hook, within `wait` in all: `None` when the connection stays
    /// busy that long, as it does when the panicking thread holds it; a poisoned lock still reads.
    pub fn try_claim_unsent(&self, limit: i64, wait: Duration) -> Option<rusqlite::Result<usize>> {
        let deadline = Instant::now() + wait;
        self.flush_until(deadline);
        loop {
            match self.db.try_lock() {
                Ok(db) => return Some(claim(&db, &self.launch, limit)),
                Err(TryLockError::Poisoned(poisoned)) => {
                    return Some(claim(&poisoned.into_inner(), &self.launch, limit));
                }
                Err(TryLockError::WouldBlock) if Instant::now() < deadline => std::thread::sleep(CLAIM_RETRY),
                Err(TryLockError::WouldBlock) => return None,
            }
        }
    }

    /// Blocking: claimed lines of any launch after `after_seq` not yet marked sent, oldest first.
    pub fn pending(&self, limit: i64, after_seq: i64) -> rusqlite::Result<Vec<LogRecord>> {
        let db = self.db.lock().unwrap();
        let mut statement = db.prepare(
            "SELECT seq, ts, level, launch, trail, message FROM log_line WHERE state = ?1 AND seq > ?2 \
             ORDER BY seq LIMIT ?3",
        )?;
        let rows = statement.query_map(params![STATE_PENDING, after_seq, limit], record)?;
        rows.collect()
    }

    /// Blocking: these lines reached Sentry; they are never pending again.
    pub fn mark_sent(&self, seqs: &[i64]) -> rusqlite::Result<()> {
        let db = self.db.lock().unwrap();
        let transaction = db.unchecked_transaction()?;
        for seq in seqs {
            transaction.execute("UPDATE log_line SET state = ?1 WHERE seq = ?2", params![STATE_SENT, seq])?;
        }
        transaction.commit()
    }

    /// Blocking: the whole log, gzipped text, for the feedback report.
    pub fn export_gzip(&self, out: &mut impl Write) -> std::io::Result<()> {
        let mut encoder = flate2::write::GzEncoder::new(out, flate2::Compression::default());
        let mut after = 0;
        loop {
            let page = self.lines_after(after, 1_000).map_err(std::io::Error::other)?;
            let Some(last) = page.last() else { break };
            after = last.seq;
            for line in &page {
                writeln!(
                    encoder,
                    "{} {} {} [{}] {}",
                    line.epoch_ms, line.launch, line.level, line.trail, line.message
                )?;
            }
        }
        encoder.finish()?;
        Ok(())
    }
}

fn claim(db: &Connection, launch: &str, limit: i64) -> rusqlite::Result<usize> {
    let transaction = db.unchecked_transaction()?;
    let claimed = transaction.execute(
        "UPDATE log_line SET state = ?1 WHERE state = 0 AND seq IN \
         (SELECT seq FROM log_line WHERE launch = ?2 ORDER BY seq DESC LIMIT ?3)",
        params![STATE_PENDING, launch, limit],
    )?;
    transaction.commit()?;
    Ok(claimed)
}

impl Drop for LogStore {
    fn drop(&mut self) {
        let (queue, wake) = &*self.queue;
        queue.lock().unwrap().closed = true;
        wake.notify_one();
    }
}

fn record(row: &rusqlite::Row<'_>) -> rusqlite::Result<LogRecord> {
    Ok(LogRecord {
        seq: row.get(0)?,
        epoch_ms: row.get(1)?,
        level: row.get(2)?,
        launch: row.get(3)?,
        trail: row.get(4)?,
        message: row.get(5)?,
    })
}

/// The JVM app's level ordinals: DEBUG 0, INFO 1, WARN 2, ERROR 3.
fn level_code(level: Level) -> i64 {
    match level {
        Level::Debug => 0,
        Level::Info => 1,
        Level::Warn => 2,
        Level::Error => 3,
    }
}

fn writer(db: Arc<Mutex<Connection>>, queue: Arc<(Mutex<Queue>, Condvar)>, max_lines: Arc<AtomicI64>, launch: String) {
    let (lock, wake) = &*queue;
    let mut rows: i64 =
        db.lock().unwrap().query_row("SELECT COUNT(*) FROM log_line", [], |row| row.get(0)).unwrap_or(0);
    loop {
        let batch: Vec<Line> = {
            let mut queue = lock.lock().unwrap();
            while queue.lines.is_empty() && !queue.closed {
                queue = wake.wait(queue).unwrap();
            }
            if queue.lines.is_empty() && queue.closed {
                return;
            }
            queue.lines.drain(..).collect()
        };
        let count = batch.len() as u64;
        let db = db.lock().unwrap();
        // A failed write loses its lines; the log is the last place to report that.
        for line in batch {
            if db
                .execute(
                    "INSERT INTO log_line (ts, level, launch, trail, message) VALUES (?1, ?2, ?3, ?4, ?5)",
                    params![line.ts, line.level, launch, line.trail, line.message],
                )
                .is_ok()
            {
                rows += 1;
            }
        }
        let cap = max_lines.load(Ordering::Relaxed);
        if cap > 0 && rows > cap + cap / 10 {
            let trimmed =
                db.execute("DELETE FROM log_line WHERE seq <= (SELECT MAX(seq) FROM log_line) - ?1", params![cap]);
            if trimmed.is_ok() {
                rows = cap;
            }
        }
        drop(db);
        lock.lock().unwrap().done += count;
    }
}

/// Writes every line to the app log.
pub struct LogStoreSink {
    pub store: Arc<LogStore>,
}

impl LogSink for LogStoreSink {
    fn log(&self, level: Level, trail: &[Crumb], message: &str, error: Option<&(dyn Error + 'static)>) {
        let path = trail.iter().map(|crumb| crumb.tag.as_str()).collect::<Vec<_>>().join("/");
        let text = match error {
            Some(error) => format!("{message}: {error}"),
            None => message.to_owned(),
        };
        self.store.enqueue(level, path, text);
    }
}
