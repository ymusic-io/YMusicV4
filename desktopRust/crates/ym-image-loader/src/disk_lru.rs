//! kmpHttp's `DiskLruCache` (`kmphttp/cache/DiskLruCache.kt` + `Journal.kt`), file for file: the
//! `kmphttp.cache.DiskLruCache` journal (CM / RD / RM lines), uniquely named `<name>.data` values and
//! `temp/<name>.tmp` edits. The JVM app opens the same folders, so what is written here reads back
//! there and the other way round. Blocking throughout.

use std::collections::HashSet;
use std::fs::{self, File, OpenOptions};
use std::hash::{BuildHasher, Hasher};
use std::io::{self, BufRead, BufReader, ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, Weak};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use indexmap::IndexMap;
use ym_common::log::Logger;

const MAGIC: &str = "kmphttp.cache.DiskLruCache";
const VERSION_1: &str = "1";
const OP_COMMIT: &str = "CM";
const OP_READ: &str = "RD";
const OP_REMOVE: &str = "RM";
const TEMP_DIR: &str = "temp";
const JOURNAL_FILE: &str = "journal";
const JOURNAL_FILE_TEMP: &str = "journal.tmp";
const JOURNAL_FILE_BACKUP: &str = "journal.bkp";
const REDUNDANT_OP_COMPACT_THRESHOLD: usize = 2000;
const CLEAN_UP_THROTTLE: Duration = Duration::from_secs(60);
/// Jan 1, 2026 00:00:00 UTC in milliseconds.
const EPOCH_2026: u64 = 1_767_225_600_000;

#[derive(Clone)]
struct FileCacheEntry {
    key: String,
    clean_files: Vec<(String, u64)>,
    latest_commit_editor_id: u32,
}

impl FileCacheEntry {
    fn size(&self) -> u64 {
        self.clean_files.iter().map(|(_, size)| size).sum()
    }
}

#[derive(Default)]
struct State {
    initialized: bool,
    closed: bool,
    /// Eldest first.
    entries: IndexMap<String, FileCacheEntry>,
    size: u64,
    journal: Option<File>,
    journal_op_count: usize,
    journal_errors: bool,
    most_recent_trim_failed: bool,
    most_recent_rebuild_failed: bool,
    editor_id: u32,
}

pub struct DiskLruCache {
    directory: PathBuf,
    app_version: u32,
    value_count: usize,
    max_size: u64,
    log: Logger,
    tokio: tokio::runtime::Handle,
    state: Mutex<State>,
    clean_up_scheduled: AtomicBool,
    last_cleaned_up_at: Mutex<Option<Instant>>,
    this: Weak<Self>,
}

/// One entry's values, opened when it was read: open files survive an eviction.
pub struct Snapshot {
    pub files: Vec<File>,
    pub paths: Vec<PathBuf>,
}

pub struct Editor {
    cache: Arc<DiskLruCache>,
    key: String,
    id: u32,
    existing: Option<FileCacheEntry>,
    dirty_files: Vec<Option<PathBuf>>,
    done: bool,
}

impl DiskLruCache {
    /// `tokio` runs the throttled background trim.
    pub fn new(
        directory: PathBuf,
        value_count: usize,
        max_size: u64,
        log: Logger,
        tokio: tokio::runtime::Handle,
    ) -> Arc<Self> {
        Arc::new_cyclic(|this| Self {
            directory,
            app_version: 1,
            value_count,
            max_size,
            log,
            tokio,
            state: Mutex::new(State::default()),
            clean_up_scheduled: AtomicBool::new(false),
            last_cleaned_up_at: Mutex::new(None),
            this: this.clone(),
        })
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn current_size(&self) -> u64 {
        self.lock().size
    }

    /// The entry named `key`, or `None`; a hit moves it to the head of the LRU queue.
    pub fn get(self: &Arc<Self>, key: &str) -> io::Result<Option<Snapshot>> {
        let entry = {
            let mut state = self.lock();
            self.initialize(&mut state)?;
            if state.closed {
                return Ok(None);
            }
            let Some(entry) = self.touch_entry(&mut state, key) else { return Ok(None) };
            if journal_rebuild_required(&state) {
                self.schedule_clean_up();
            }
            entry
        };
        match self.open_snapshot(&entry) {
            Ok(snapshot) => Ok(Some(snapshot)),
            Err(error) => {
                self.log.warn("cache entry files unreadable, treated as a miss", Some(&error));
                let mut state = self.lock();
                self.remove_entry_in_state(&mut state, &entry);
                Ok(None)
            }
        }
    }

    pub fn edit(self: &Arc<Self>, key: &str) -> io::Result<Option<Editor>> {
        if !is_legal_key(key) {
            return Err(io::Error::new(ErrorKind::InvalidInput, format!("illegal cache key: {key:?}")));
        }
        let mut state = self.lock();
        self.initialize(&mut state)?;
        if state.closed || state.journal_errors || state.journal.is_none() {
            return Ok(None);
        }
        if state.most_recent_trim_failed || state.most_recent_rebuild_failed {
            drop(state);
            self.schedule_clean_up();
            return Ok(None);
        }
        state.editor_id += 1;
        let id = state.editor_id;
        let existing = state.entries.get(key).cloned();
        Ok(Some(Editor {
            cache: self.clone(),
            key: key.to_owned(),
            id,
            existing,
            dirty_files: vec![None; self.value_count],
            done: false,
        }))
    }

    /// Evict down to the budget now instead of waiting for the throttled background cleanup.
    pub fn trim_now(&self) -> io::Result<()> {
        let mut state = self.lock();
        self.trim_to_size(&mut state)
    }

    pub fn close(&self) {
        let mut state = self.lock();
        if state.closed {
            return;
        }
        state.closed = true;
        state.journal = None;
        state.entries.clear();
        state.size = 0;
    }

    fn open_snapshot(&self, entry: &FileCacheEntry) -> io::Result<Snapshot> {
        let paths: Vec<PathBuf> = entry.clean_files.iter().map(|(name, _)| self.directory.join(name)).collect();
        let files = paths.iter().map(File::open).collect::<io::Result<Vec<_>>>()?;
        Ok(Snapshot { files, paths })
    }

    fn initialize(&self, state: &mut State) -> io::Result<()> {
        if state.initialized || state.closed {
            return Ok(());
        }
        let temp_dir = self.directory.join(TEMP_DIR);
        fs::create_dir_all(&self.directory)?;
        if let Err(error) = remove_recursively(&temp_dir) {
            self.log.warn("cache temp dir cleanup failed", Some(&error));
        }
        fs::create_dir_all(&temp_dir)?;
        if !temp_dir.is_dir() {
            return Err(io::Error::other(format!("Can't create {} directory", temp_dir.display())));
        }
        let journal_file = self.directory.join(JOURNAL_FILE);
        let journal_backup = self.directory.join(JOURNAL_FILE_BACKUP);
        if journal_backup.exists() {
            if journal_file.exists() {
                remove_recursively(&journal_backup)?;
            } else {
                fs::rename(&journal_backup, &journal_file)?;
            }
        }

        let mut journal = if journal_file.exists() {
            match read_journal(&journal_file, self.app_version, self.value_count) {
                Ok(journal) => journal,
                Err(error) => {
                    self.log.warn("cache journal unreadable, starting empty", Some(&error));
                    remove_recursively(&journal_file)?;
                    JournalFileState::default()
                }
            }
        } else {
            JournalFileState::default()
        };
        let entry_file_names: HashSet<String> =
            journal.entries.values().flat_map(|entry| entry.clean_files.iter().map(|(name, _)| name.clone())).collect();
        let mut directory_file_names = HashSet::new();
        for child in fs::read_dir(&self.directory)? {
            directory_file_names.insert(child?.file_name().to_string_lossy().into_owned());
        }
        directory_file_names.remove(JOURNAL_FILE);
        directory_file_names.remove(TEMP_DIR);
        for name in directory_file_names.difference(&entry_file_names) {
            remove_recursively(&self.directory.join(name))?;
        }
        journal
            .entries
            .retain(|_, entry| entry.clean_files.iter().all(|(name, _)| directory_file_names.contains(name)));

        state.entries = journal.entries;
        state.size = state.entries.values().map(FileCacheEntry::size).sum();
        state.journal_op_count = journal.op_count;
        state.initialized = true;
        if journal.is_complete {
            self.set_journal_writer(state, Some(OpenOptions::new().append(true).open(&journal_file)?));
        } else {
            self.rebuild_journal(state)?;
        }
        Ok(())
    }

    fn set_journal_writer(&self, state: &mut State, writer: Option<File>) {
        if writer.is_some() {
            state.journal_errors = false;
        }
        state.journal = writer;
    }

    fn journal_error(&self, state: &mut State, error: &io::Error) {
        if !state.journal_errors {
            self.log.warn("cache journal write failed, cache persistence disabled until restart", Some(error));
        }
        state.journal_errors = true;
        state.journal = None;
    }

    fn write_journal(&self, state: &mut State, line: &str) {
        let Some(journal) = state.journal.as_mut() else { return };
        match journal.write_all(line.as_bytes()) {
            Ok(()) => state.journal_op_count += 1,
            Err(error) => self.journal_error(state, &error),
        }
    }

    fn touch_entry(&self, state: &mut State, key: &str) -> Option<FileCacheEntry> {
        let index = state.entries.get_index_of(key)?;
        let last = state.entries.len() - 1;
        state.entries.move_index(index, last);
        let entry = state.entries.get_index(last).map(|(_, entry)| entry.clone());
        self.write_journal(state, &format!("{OP_READ} {key}\n"));
        entry
    }

    fn commit_entry(&self, state: &mut State, entry: FileCacheEntry) {
        let old_size = state.entries.shift_remove(&entry.key).map_or(0, |old| old.size());
        state.size = state.size + entry.size() - old_size;
        let line = commit_line(&entry);
        state.entries.insert(entry.key.clone(), entry);
        self.write_journal(state, &line);
    }

    fn remove_entry_in_state(&self, state: &mut State, entry: &FileCacheEntry) -> bool {
        if !state.initialized || state.closed {
            return false;
        }
        // Only an entry still in the cache as it was read.
        if state.entries.get(&entry.key).is_none_or(|current| current.clean_files != entry.clean_files) {
            return false;
        }
        let Some(removed) = state.entries.shift_remove(&entry.key) else { return false };
        state.size -= removed.size();
        self.write_journal(state, &format!("{OP_REMOVE} {}\n", removed.key));
        for (name, _) in &removed.clean_files {
            if let Err(error) = remove_recursively(&self.directory.join(name)) {
                self.log.warn("cache entry file not deleted", Some(&error));
            }
        }
        if journal_rebuild_required(state) {
            self.schedule_clean_up();
        }
        true
    }

    fn trim_to_size(&self, state: &mut State) -> io::Result<()> {
        if state.closed {
            return Ok(());
        }
        self.initialize(state)?;
        while state.size > self.max_size {
            let Some(oldest) = state.entries.first().map(|(_, entry)| entry.clone()) else { return Ok(()) };
            if !self.remove_entry_in_state(state, &oldest) {
                return Ok(());
            }
        }
        state.most_recent_trim_failed = false;
        Ok(())
    }

    fn clean_up(&self) {
        let mut state = self.lock();
        if !state.initialized || state.closed {
            return;
        }
        if let Err(error) = self.trim_to_size(&mut state) {
            self.log.warn("cache trim failed", Some(&error));
            state.most_recent_trim_failed = true;
        }
        if journal_rebuild_required(&state)
            && let Err(error) = self.rebuild_journal(&mut state)
        {
            self.log.warn("cache journal rebuild failed", Some(&error));
            state.most_recent_rebuild_failed = true;
            state.journal = None;
        }
    }

    fn schedule_clean_up(&self) {
        if self.clean_up_scheduled.swap(true, Ordering::SeqCst) {
            return;
        }
        let tokio = self.tokio.clone();
        let wait = self
            .last_cleaned_up_at
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .map_or(Duration::ZERO, |at| CLEAN_UP_THROTTLE.saturating_sub(at.elapsed()));
        let cache = self.this.clone();
        tokio.spawn(async move {
            tokio::time::sleep(wait).await;
            let Some(cache) = cache.upgrade() else { return };
            *cache.last_cleaned_up_at.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(Instant::now());
            cache.clean_up_scheduled.store(false, Ordering::SeqCst);
            let worker = cache.clone();
            if let Err(error) = tokio::task::spawn_blocking(move || worker.clean_up()).await {
                cache.log.warn("cache clean-up task failed", Some(&error));
            }
        });
    }

    fn rebuild_journal(&self, state: &mut State) -> io::Result<()> {
        state.journal = None;
        let journal_file = self.directory.join(JOURNAL_FILE);
        let journal_temp = self.directory.join(JOURNAL_FILE_TEMP);
        let journal_backup = self.directory.join(JOURNAL_FILE_BACKUP);
        {
            let mut writer = io::BufWriter::new(File::create(&journal_temp)?);
            write!(writer, "{MAGIC}\n{VERSION_1}\n{}\n{}\n\n", self.app_version, self.value_count)?;
            for entry in state.entries.values() {
                writer.write_all(commit_line(entry).as_bytes())?;
            }
            writer.flush()?;
        }
        if journal_file.exists() {
            fs::rename(&journal_file, &journal_backup)?;
            fs::rename(&journal_temp, &journal_file)?;
            remove_recursively(&journal_backup)?;
        } else {
            fs::rename(&journal_temp, &journal_file)?;
        }
        self.set_journal_writer(state, Some(OpenOptions::new().append(true).open(&journal_file)?));
        state.journal_op_count = state.entries.len();
        state.most_recent_rebuild_failed = false;
        Ok(())
    }

    fn commit_edit(&self, editor: &mut Editor, success: bool) -> bool {
        let mut state = self.lock();
        self.commit_edit_in_state(&mut state, editor, success)
    }

    fn commit_edit_in_state(&self, state: &mut State, editor: &mut Editor, success: bool) -> bool {
        if !success || state.closed {
            for dirty in editor.dirty_files.iter().flatten() {
                if let Err(error) = remove_recursively(dirty) {
                    self.log.warn("cache edit file not deleted", Some(&error));
                }
            }
            return false;
        }
        if editor.dirty_files.iter().all(Option::is_none) {
            return true;
        }
        let current = state.entries.get(&editor.key);
        if current.is_some_and(|current| current.latest_commit_editor_id > editor.id) {
            return self.commit_edit_in_state(state, editor, false);
        }
        if current.is_none() && editor.existing.is_some() {
            return self.commit_edit_in_state(state, editor, false);
        }
        if editor.existing.is_none() {
            if editor.dirty_files.iter().any(Option::is_none) {
                self.log.warn("cache commit without every value, discarded", None);
                return self.commit_edit_in_state(state, editor, false);
            }
        } else if editor.existing.as_ref().map(|entry| &entry.clean_files) != current.map(|entry| &entry.clean_files)
            && editor.dirty_files.iter().any(Option::is_none)
        {
            return self.commit_edit_in_state(state, editor, false);
        }

        let mut staged = Vec::new();
        let mut clean_files = Vec::with_capacity(self.value_count);
        for index in 0..self.value_count {
            let file = match &editor.dirty_files[index] {
                Some(dirty) => {
                    let name = format!("{}.data", new_unique_file_name());
                    let path = self.directory.join(&name);
                    let moved = if path.exists() {
                        Err(io::Error::new(ErrorKind::AlreadyExists, format!("{} exists", path.display())))
                    } else {
                        fs::rename(dirty, &path).and_then(|()| fs::metadata(&path).map(|metadata| metadata.len()))
                    };
                    match moved {
                        Ok(size) => {
                            staged.push(path);
                            (name, size)
                        }
                        Err(error) => {
                            self.log.warn("cache commit failed, edit discarded", Some(&error));
                            for path in &staged {
                                if let Err(error) = remove_recursively(path) {
                                    self.log.warn("cache staged file not deleted", Some(&error));
                                }
                            }
                            return self.commit_edit_in_state(state, editor, false);
                        }
                    }
                }
                None => match editor.existing.as_ref().and_then(|entry| entry.clean_files.get(index)) {
                    Some(existing) => existing.clone(),
                    None => return self.commit_edit_in_state(state, editor, false),
                },
            };
            clean_files.push(file);
        }
        self.commit_entry(
            state,
            FileCacheEntry { key: editor.key.clone(), clean_files, latest_commit_editor_id: editor.id },
        );
        if state.size > self.max_size {
            self.schedule_clean_up();
        }
        true
    }
}

impl Editor {
    /// A fresh file for value `index`, committed by [`Editor::commit`].
    pub fn new_file(&mut self, index: usize) -> io::Result<(PathBuf, File)> {
        let temp_dir = self.cache.directory.join(TEMP_DIR);
        let path = match &self.dirty_files[index] {
            Some(path) => path.clone(),
            None => {
                let path = temp_dir.join(format!("{}.tmp", new_unique_file_name()));
                self.dirty_files[index] = Some(path.clone());
                path
            }
        };
        // The OS may wipe the cache dir while we run.
        fs::create_dir_all(&temp_dir)?;
        let file = File::create(&path)?;
        Ok((path, file))
    }

    /// True when the edit is on disk.
    pub fn commit(mut self) -> bool {
        self.done = true;
        let cache = self.cache.clone();
        cache.commit_edit(&mut self, true)
    }
}

/// An edit dropped without a commit is aborted.
impl Drop for Editor {
    fn drop(&mut self) {
        if !self.done {
            self.done = true;
            let cache = self.cache.clone();
            cache.commit_edit(self, false);
        }
    }
}

#[derive(Default)]
struct JournalFileState {
    op_count: usize,
    is_complete: bool,
    entries: IndexMap<String, FileCacheEntry>,
}

fn read_journal(path: &Path, app_version: u32, value_count: usize) -> io::Result<JournalFileState> {
    let mut reader = BufReader::new(File::open(path)?);
    let mut header = Vec::with_capacity(5);
    for _ in 0..5 {
        match read_line_strict(&mut reader)? {
            JournalLine::Line(line) => header.push(line),
            JournalLine::End | JournalLine::Partial => return Err(io::Error::from(ErrorKind::UnexpectedEof)),
        }
    }
    if header[0] != MAGIC
        || header[1] != VERSION_1
        || header[2] != app_version.to_string()
        || header[3] != value_count.to_string()
        || !header[4].is_empty()
    {
        return Err(io::Error::other(format!("unexpected journal header: {header:?}")));
    }
    let mut entries: IndexMap<String, FileCacheEntry> = IndexMap::new();
    let mut op_count = 0;
    let is_complete = loop {
        let line = match read_line_strict(&mut reader)? {
            JournalLine::Line(line) => line,
            JournalLine::End => break true,
            JournalLine::Partial => break false,
        };
        let parts: Vec<&str> = line.split(' ').collect();
        let parsed = parts.len() >= 2
            && match parts[0] {
                OP_COMMIT if parts.len() - 2 == value_count * 2 => {
                    let files: Option<Vec<(String, u64)>> = (0..value_count)
                        .map(|index| Some((parts[index * 2 + 2].to_owned(), parts[index * 2 + 3].parse().ok()?)))
                        .collect();
                    match files {
                        Some(clean_files) => {
                            entries.shift_remove(parts[1]);
                            entries.insert(
                                parts[1].to_owned(),
                                FileCacheEntry { key: parts[1].to_owned(), clean_files, latest_commit_editor_id: 0 },
                            );
                            true
                        }
                        None => false,
                    }
                }
                OP_READ => {
                    if let Some(index) = entries.get_index_of(parts[1]) {
                        let last = entries.len() - 1;
                        entries.move_index(index, last);
                    }
                    true
                }
                OP_REMOVE => {
                    entries.shift_remove(parts[1]);
                    true
                }
                _ => false,
            };
        if !parsed {
            return Err(io::Error::other(format!("unexpected journal line: {line}")));
        }
        op_count += 1;
    };
    Ok(JournalFileState { op_count, is_complete, entries })
}

enum JournalLine {
    Line(String),
    End,
    /// A last line without its newline: a write that did not finish.
    Partial,
}

/// okio's `readUtf8LineStrict`: a `\n`-terminated line, a `\r\n` end trimmed.
fn read_line_strict(reader: &mut impl BufRead) -> io::Result<JournalLine> {
    let mut bytes = Vec::new();
    if reader.read_until(b'\n', &mut bytes)? == 0 {
        return Ok(JournalLine::End);
    }
    if bytes.last() != Some(&b'\n') {
        return Ok(JournalLine::Partial);
    }
    bytes.pop();
    if bytes.last() == Some(&b'\r') {
        bytes.pop();
    }
    String::from_utf8(bytes).map(JournalLine::Line).map_err(|error| io::Error::new(ErrorKind::InvalidData, error))
}

fn commit_line(entry: &FileCacheEntry) -> String {
    let files: Vec<String> = entry.clean_files.iter().map(|(name, size)| format!("{name} {size}")).collect();
    format!("{OP_COMMIT} {} {}\n", entry.key, files.join(" "))
}

fn journal_rebuild_required(state: &State) -> bool {
    state.journal_op_count >= state.entries.len() + REDUNDANT_OP_COMPACT_THRESHOLD
}

/// `[a-z0-9_-]{1,120}`.
fn is_legal_key(key: &str) -> bool {
    (1..=120).contains(&key.len())
        && key.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_' || byte == b'-')
}

fn remove_recursively(path: &Path) -> io::Result<()> {
    let result = match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() => fs::remove_dir_all(path),
        Ok(_) => fs::remove_file(path),
        Err(error) => Err(error),
    };
    match result {
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(()),
        other => other,
    }
}

static TIME_START: LazyLock<(u64, Instant)> = LazyLock::new(|| {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_millis() as u64);
    (now.saturating_sub(EPOCH_2026), Instant::now())
});
static FILE_COUNTER: AtomicU32 = AtomicU32::new(0);

/// Short unique file names: timestamp + counter + random, in hex.
fn new_unique_file_name() -> String {
    let (start, mark) = *TIME_START;
    let timestamp = start + mark.elapsed().as_millis() as u64;
    let counter = (FILE_COUNTER.fetch_add(1, Ordering::SeqCst).wrapping_add(1)) & 0xFFFF;
    let random = std::collections::hash_map::RandomState::new().build_hasher().finish() & 0xFFFF;
    format!("{timestamp:x}{counter:x}{random:x}")
}
