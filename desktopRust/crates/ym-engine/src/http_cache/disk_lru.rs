//! OkHttp 5's `DiskLruCache`, file-for-file: the `journal` (`libcore.io.DiskLruCache`, CLEAN / DIRTY /
//! REMOVE / READ lines in access order), `<key>.<n>` values and `<key>.<n>.tmp` edits. The JVM app
//! opens the same folder, so every byte written here must read back there and the other way round.
//! Blocking throughout.
//!
//! Open files survive a delete or rename here (OkHttp's "civilized" file system), so readers never
//! pin an entry and there are no zombie entries.

use std::fs::{File, OpenOptions};
use std::io::{self, BufRead, BufReader, BufWriter, ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use indexmap::IndexMap;
use ym_common::log::Logger;

pub(crate) const VALUE_COUNT: usize = 2;
const JOURNAL: &str = "journal";
const JOURNAL_TMP: &str = "journal.tmp";
const JOURNAL_BACKUP: &str = "journal.bkp";
const MAGIC: &str = "libcore.io.DiskLruCache";
const VERSION: &str = "1";
const CLEAN: &str = "CLEAN";
const DIRTY: &str = "DIRTY";
const REMOVE: &str = "REMOVE";
const READ: &str = "READ";
const REDUNDANT_OP_COMPACT_THRESHOLD: usize = 2000;

pub(crate) struct DiskLruCache {
    directory: PathBuf,
    app_version: u32,
    max_size: u64,
    log: Logger,
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    initialized: bool,
    /// Access order, eldest first (OkHttp's access-ordered `LinkedHashMap`).
    entries: IndexMap<String, Entry>,
    size: u64,
    journal: Option<BufWriter<File>>,
    redundant_ops: usize,
    journal_errors: bool,
    trim_failed: bool,
    rebuild_failed: bool,
    next_sequence: u64,
    next_editor: u64,
}

#[derive(Default)]
struct Entry {
    lengths: [u64; VALUE_COUNT],
    readable: bool,
    editor: Option<u64>,
    sequence: u64,
}

/// One entry's values, opened when it was read.
pub(crate) struct Snapshot {
    pub key: String,
    pub sequence: u64,
    pub files: [File; VALUE_COUNT],
}

pub(crate) struct Editor {
    cache: Arc<DiskLruCache>,
    key: String,
    id: u64,
    /// `None` when the entry was readable at edit start: an update may leave values as they are.
    written: Option<[bool; VALUE_COUNT]>,
    done: bool,
}

impl DiskLruCache {
    pub fn new(directory: PathBuf, app_version: u32, max_size: u64, log: Logger) -> Self {
        Self { directory, app_version, max_size, log, state: Mutex::new(State::default()) }
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }

    fn lock(&self) -> io::Result<MutexGuard<'_, State>> {
        let mut state = self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        self.initialize(&mut state)?;
        Ok(state)
    }

    fn clean_file(&self, key: &str, index: usize) -> PathBuf {
        self.directory.join(format!("{key}.{index}"))
    }

    fn dirty_file(&self, key: &str, index: usize) -> PathBuf {
        self.directory.join(format!("{key}.{index}.tmp"))
    }

    fn initialize(&self, state: &mut State) -> io::Result<()> {
        if state.initialized {
            return Ok(());
        }
        let journal = self.directory.join(JOURNAL);
        let backup = self.directory.join(JOURNAL_BACKUP);
        if backup.exists() {
            if journal.exists() {
                std::fs::remove_file(&backup)?;
            } else {
                std::fs::rename(&backup, &journal)?;
            }
        }
        if journal.exists() {
            match self.read_journal(state).and_then(|()| self.process_journal(state)) {
                Ok(()) => {
                    state.initialized = true;
                    return Ok(());
                }
                Err(error) => {
                    self.log.warn("R90702 http cache journal is corrupt, the cache starts over", Some(&error));
                }
            }
            *state = State::default();
            delete_contents(&self.directory)?;
        }
        self.rebuild_journal(state)?;
        state.initialized = true;
        Ok(())
    }

    fn read_journal(&self, state: &mut State) -> io::Result<()> {
        let mut reader = BufReader::new(File::open(self.directory.join(JOURNAL))?);
        let header: Vec<String> = (0..5).map(|_| read_line_strict(&mut reader)).collect::<io::Result<_>>()?;
        if header[0] != MAGIC
            || header[1] != VERSION
            || header[2] != self.app_version.to_string()
            || header[3] != VALUE_COUNT.to_string()
            || !header[4].is_empty()
        {
            return Err(io::Error::new(ErrorKind::InvalidData, format!("unexpected journal header: {header:?}")));
        }
        let mut line_count: usize = 0;
        loop {
            match read_line_strict(&mut reader) {
                Ok(line) => {
                    read_journal_line(state, &line)?;
                    line_count += 1;
                }
                Err(error) if error.kind() == ErrorKind::UnexpectedEof => break,
                Err(error) => return Err(error),
            }
        }
        state.redundant_ops = line_count.saturating_sub(state.entries.len());
        if reader.fill_buf()?.is_empty() {
            state.journal = Some(self.journal_writer()?);
        } else {
            self.rebuild_journal(state)?;
        }
        Ok(())
    }

    fn process_journal(&self, state: &mut State) -> io::Result<()> {
        remove_if_exists(&self.directory.join(JOURNAL_TMP))?;
        let mut abandoned = Vec::new();
        for (key, entry) in &state.entries {
            if entry.editor.is_none() {
                state.size += entry.lengths.iter().sum::<u64>();
            } else {
                abandoned.push(key.clone());
            }
        }
        for key in abandoned {
            for index in 0..VALUE_COUNT {
                remove_if_exists(&self.clean_file(&key, index))?;
                remove_if_exists(&self.dirty_file(&key, index))?;
            }
            state.entries.shift_remove(&key);
        }
        Ok(())
    }

    fn journal_writer(&self) -> io::Result<BufWriter<File>> {
        Ok(BufWriter::new(OpenOptions::new().create(true).append(true).open(self.directory.join(JOURNAL))?))
    }

    fn rebuild_journal(&self, state: &mut State) -> io::Result<()> {
        if let Some(mut journal) = state.journal.take() {
            journal.flush()?;
        }
        std::fs::create_dir_all(&self.directory)?;
        let tmp = self.directory.join(JOURNAL_TMP);
        let mut writer = BufWriter::new(File::create(&tmp)?);
        write!(writer, "{MAGIC}\n{VERSION}\n{}\n{VALUE_COUNT}\n\n", self.app_version)?;
        for (key, entry) in &state.entries {
            if entry.editor.is_some() {
                writeln!(writer, "{DIRTY} {key}")?;
            } else {
                writeln!(writer, "{CLEAN} {key}{}", lengths_text(&entry.lengths))?;
            }
        }
        writer.into_inner().map_err(|error| error.into_error())?.sync_all()?;
        let journal = self.directory.join(JOURNAL);
        if journal.exists() {
            let backup = self.directory.join(JOURNAL_BACKUP);
            std::fs::rename(&journal, &backup)?;
            std::fs::rename(&tmp, &journal)?;
            remove_if_exists(&backup)?;
        } else {
            std::fs::rename(&tmp, &journal)?;
        }
        state.journal = Some(self.journal_writer()?);
        state.journal_errors = false;
        state.rebuild_failed = false;
        Ok(())
    }

    /// A journal write failure disables edits until the next rebuild, as OkHttp's fault-hiding sink.
    fn journal_line(&self, state: &mut State, line: &str) {
        let written = match state.journal.as_mut() {
            Some(journal) => journal.write_all(line.as_bytes()).and_then(|()| journal.write_all(b"\n")),
            None => Err(io::Error::other("journal closed")),
        };
        if let Err(error) = written.and_then(|()| state.journal.as_mut().map_or(Ok(()), |journal| journal.flush())) {
            if !state.journal_errors {
                self.log.warn("R90703 http cache journal write failed", Some(&error));
            }
            state.journal_errors = true;
        }
    }

    fn rebuild_required(state: &State) -> bool {
        state.redundant_ops >= REDUNDANT_OP_COMPACT_THRESHOLD && state.redundant_ops >= state.entries.len()
    }

    /// OkHttp runs this as a background task; here it runs at the end of the operation that needed it.
    fn cleanup(&self, state: &mut State) {
        if let Err(error) = self.trim_to_size(state) {
            self.log.warn("R90704 http cache trim failed", Some(&error));
            state.trim_failed = true;
        }
        if Self::rebuild_required(state) {
            match self.rebuild_journal(state) {
                Ok(()) => state.redundant_ops = 0,
                Err(error) => {
                    self.log.warn("R90713 http cache journal rebuild failed", Some(&error));
                    state.rebuild_failed = true;
                    state.journal = None;
                }
            }
        }
    }

    fn trim_to_size(&self, state: &mut State) -> io::Result<()> {
        while state.size > self.max_size {
            let Some(eldest) = state.entries.first().map(|(key, _)| key.clone()) else { break };
            self.remove_entry(state, &eldest)?;
        }
        state.trim_failed = false;
        Ok(())
    }

    /// Moves `key` to the most recently used end, as every OkHttp lookup does.
    fn touch<'a>(state: &'a mut State, key: &str) -> Option<&'a mut Entry> {
        let index = state.entries.get_index_of(key)?;
        let last = state.entries.len() - 1;
        state.entries.move_index(index, last);
        state.entries.get_index_mut(last).map(|(_, entry)| entry)
    }

    pub fn get(&self, key: &str) -> io::Result<Option<Snapshot>> {
        validate_key(key)?;
        let mut state = self.lock()?;
        let Some(entry) = Self::touch(&mut state, key) else { return Ok(None) };
        if !entry.readable {
            return Ok(None);
        }
        let sequence = entry.sequence;
        let opened = (File::open(self.clean_file(key, 0)), File::open(self.clean_file(key, 1)));
        let files = match opened {
            (Ok(metadata), Ok(body)) => [metadata, body],
            (Err(error), _) | (_, Err(error)) if error.kind() == ErrorKind::NotFound => {
                self.remove_entry(&mut state, key)?;
                return Ok(None);
            }
            (Err(error), _) | (_, Err(error)) => return Err(error),
        };
        state.redundant_ops += 1;
        self.journal_line(&mut state, &format!("{READ} {key}"));
        if Self::rebuild_required(&state) {
            self.cleanup(&mut state);
        }
        Ok(Some(Snapshot { key: key.to_owned(), sequence, files }))
    }

    /// `None` while another edit of `key` runs, when `expected_sequence` is stale, or when the
    /// journal cannot be written.
    pub fn edit(self: &Arc<Self>, key: &str, expected_sequence: Option<u64>) -> io::Result<Option<Editor>> {
        validate_key(key)?;
        let mut state = self.lock()?;
        let entry = Self::touch(&mut state, key);
        if let Some(expected) = expected_sequence
            && entry.as_ref().map(|entry| entry.sequence) != Some(expected)
        {
            return Ok(None);
        }
        if entry.as_ref().is_some_and(|entry| entry.editor.is_some()) {
            return Ok(None);
        }
        if state.trim_failed || state.rebuild_failed {
            self.cleanup(&mut state);
            return Ok(None);
        }
        self.journal_line(&mut state, &format!("{DIRTY} {key}"));
        if state.journal_errors {
            return Ok(None);
        }
        let id = state.next_editor;
        state.next_editor += 1;
        let entry = state.entries.entry(key.to_owned()).or_default();
        entry.editor = Some(id);
        let written = (!entry.readable).then_some([false; VALUE_COUNT]);
        Ok(Some(Editor { cache: self.clone(), key: key.to_owned(), id, written, done: false }))
    }

    pub fn remove(&self, key: &str) -> io::Result<bool> {
        validate_key(key)?;
        let mut state = self.lock()?;
        if Self::touch(&mut state, key).is_none() {
            return Ok(false);
        }
        self.remove_entry(&mut state, key)?;
        if state.size <= self.max_size {
            state.trim_failed = false;
        }
        Ok(true)
    }

    fn remove_entry(&self, state: &mut State, key: &str) -> io::Result<()> {
        if let Some(editor) = state.entries.get(key).and_then(|entry| entry.editor) {
            self.complete_edit(state, key, editor, None, false)?;
        }
        let lengths = state.entries.get(key).map(|entry| entry.lengths).unwrap_or_default();
        for (index, length) in lengths.iter().enumerate() {
            remove_if_exists(&self.clean_file(key, index))?;
            state.size = state.size.saturating_sub(*length);
        }
        state.redundant_ops += 1;
        self.journal_line(state, &format!("{REMOVE} {key}"));
        state.entries.shift_remove(key);
        if Self::rebuild_required(state) {
            self.cleanup(state);
        }
        Ok(())
    }

    fn complete_edit(
        &self,
        state: &mut State,
        key: &str,
        editor: u64,
        written: Option<&[bool; VALUE_COUNT]>,
        success: bool,
    ) -> io::Result<()> {
        let Some(entry) = state.entries.get(key) else { return Ok(()) };
        if entry.editor != Some(editor) {
            return Ok(());
        }
        let (readable, mut lengths) = (entry.readable, entry.lengths);
        if success && !readable {
            for index in 0..VALUE_COUNT {
                if !written.is_some_and(|written| written[index]) {
                    self.complete_edit(state, key, editor, written, false)?;
                    return Err(io::Error::other(format!("newly created entry didn't create value for index {index}")));
                }
                if !self.dirty_file(key, index).exists() {
                    return self.complete_edit(state, key, editor, written, false);
                }
            }
        }
        for (index, length) in lengths.iter_mut().enumerate() {
            let dirty = self.dirty_file(key, index);
            if success {
                if dirty.exists() {
                    let clean = self.clean_file(key, index);
                    std::fs::rename(&dirty, &clean)?;
                    let new_length = std::fs::metadata(&clean)?.len();
                    state.size = state.size.saturating_sub(*length) + new_length;
                    *length = new_length;
                }
            } else {
                remove_if_exists(&dirty)?;
            }
        }
        state.redundant_ops += 1;
        if readable || success {
            let sequence = state.next_sequence;
            if success {
                state.next_sequence += 1;
            }
            let entry = state.entries.get_mut(key).expect("entry checked above");
            entry.lengths = lengths;
            entry.editor = None;
            entry.readable = true;
            if success {
                entry.sequence = sequence;
            }
            self.journal_line(state, &format!("{CLEAN} {key}{}", lengths_text(&lengths)));
        } else {
            state.entries.shift_remove(key);
            self.journal_line(state, &format!("{REMOVE} {key}"));
        }
        if state.size > self.max_size || Self::rebuild_required(state) {
            self.cleanup(state);
        }
        Ok(())
    }
}

impl Editor {
    /// The value file to write `index` into; `None` once the edit was detached (the entry was
    /// removed meanwhile).
    pub fn create(&mut self, index: usize) -> io::Result<Option<File>> {
        let cache = self.cache.clone();
        let state = cache.lock()?;
        if state.entries.get(&self.key).and_then(|entry| entry.editor) != Some(self.id) {
            return Ok(None);
        }
        if let Some(written) = self.written.as_mut() {
            written[index] = true;
        }
        std::fs::create_dir_all(&cache.directory)?;
        Ok(Some(File::create(cache.dirty_file(&self.key, index))?))
    }

    pub fn commit(mut self) -> io::Result<()> {
        self.complete(true)
    }

    pub fn abort(mut self) -> io::Result<()> {
        self.complete(false)
    }

    fn complete(&mut self, success: bool) -> io::Result<()> {
        self.done = true;
        let cache = self.cache.clone();
        let mut state = cache.lock()?;
        cache.complete_edit(&mut state, &self.key, self.id, self.written.as_ref(), success)
    }
}

impl Drop for Editor {
    /// Blocking: an editor is dropped off the engine thread.
    fn drop(&mut self) {
        if !self.done
            && let Err(error) = self.complete(false)
        {
            self.cache.log.warn("R90714 http cache edit could not be aborted", Some(&error));
        }
    }
}

fn read_journal_line(state: &mut State, line: &str) -> io::Result<()> {
    let corrupt = || io::Error::new(ErrorKind::InvalidData, format!("unexpected journal line: {line}"));
    let first_space = line.find(' ').ok_or_else(corrupt)?;
    let rest = &line[first_space + 1..];
    let (key, lengths) = match rest.find(' ') {
        Some(second) => (&rest[..second], Some(&rest[second + 1..])),
        None => (rest, None),
    };
    let op = &line[..first_space];
    if lengths.is_none() && op == REMOVE {
        state.entries.shift_remove(key);
        return Ok(());
    }
    if DiskLruCache::touch(state, key).is_none() {
        state.entries.insert(key.to_owned(), Entry::default());
    }
    let entry = state.entries.get_mut(key).expect("inserted above");
    match (op, lengths) {
        (CLEAN, Some(lengths)) => {
            let parts: Vec<&str> = lengths.split(' ').collect();
            if parts.len() != VALUE_COUNT {
                return Err(corrupt());
            }
            for (index, part) in parts.iter().enumerate() {
                entry.lengths[index] = part.parse().map_err(|_| corrupt())?;
            }
            entry.readable = true;
            entry.editor = None;
        }
        (DIRTY, None) => entry.editor = Some(u64::MAX),
        (READ, None) => {}
        _ => return Err(corrupt()),
    }
    Ok(())
}

/// okio's `readUtf8LineStrict`: a line ends at `\n` (a `\r` before it is dropped); a last line
/// without one is an `UnexpectedEof`.
pub(crate) fn read_line_strict(reader: &mut impl BufRead) -> io::Result<String> {
    let mut bytes = Vec::new();
    reader.read_until(b'\n', &mut bytes)?;
    if bytes.last() != Some(&b'\n') {
        return Err(io::Error::new(ErrorKind::UnexpectedEof, "no newline before end of stream"));
    }
    bytes.pop();
    if bytes.last() == Some(&b'\r') {
        bytes.pop();
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn lengths_text(lengths: &[u64; VALUE_COUNT]) -> String {
    lengths.iter().map(|length| format!(" {length}")).collect()
}

fn validate_key(key: &str) -> io::Result<()> {
    let legal = (1..=120).contains(&key.len())
        && key.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_' || byte == b'-');
    if legal { Ok(()) } else { Err(io::Error::new(ErrorKind::InvalidInput, format!("illegal cache key {key:?}"))) }
}

fn remove_if_exists(path: &Path) -> io::Result<()> {
    match std::fs::remove_file(path) {
        Err(error) if error.kind() != ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

fn delete_contents(directory: &Path) -> io::Result<()> {
    let entries = match std::fs::read_dir(directory) {
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(()),
        entries => entries?,
    };
    for entry in entries {
        let path = entry?.path();
        if path.is_dir() { std::fs::remove_dir_all(&path)? } else { std::fs::remove_file(&path)? }
    }
    Ok(())
}
