//! The JVM `MemoryImageCache.kt`: an LRU of decoded bitmaps bounded by estimated pixel bytes. One
//! plain lock around a few map operations, never held across decode or I/O, so `peek` is cheap
//! enough for a frame.

use std::collections::HashSet;
use std::sync::Mutex;

use indexmap::IndexMap;

use crate::bitmap::Bitmap;

pub(crate) struct MemoryImageCache<B: Bitmap> {
    state: Mutex<State<B>>,
}

struct State<B> {
    /// Eldest first.
    map: IndexMap<String, B>,
    stand_ins: HashSet<String>,
    total_bytes: u64,
    max_bytes: u64,
}

impl<B: Bitmap> MemoryImageCache<B> {
    pub fn new(max_bytes: u64) -> Self {
        Self { state: Mutex::new(State { map: IndexMap::new(), stand_ins: HashSet::new(), total_bytes: 0, max_bytes }) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State<B>> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// The JVM `peek(key, includeStandIns)`: `include_stand_ins` false misses an entry put as a stand-in.
    pub fn peek(&self, key: &str, include_stand_ins: bool) -> Option<B> {
        let mut state = self.lock();
        if !include_stand_ins && state.stand_ins.contains(key) {
            return None;
        }
        let index = state.map.get_index_of(key)?;
        let last = state.map.len() - 1;
        state.map.move_index(index, last);
        state.map.get_index(last).map(|(_, bitmap)| bitmap.clone())
    }

    /// The smallest cached size of identity `key` (entries `<key>@<w>x<h><suffix>`) that is at least
    /// `width`×`height` with the same `suffix`; LRU order untouched.
    pub fn peek_covering(&self, key: &str, width: u32, height: u32, suffix: &str) -> Option<B> {
        let state = self.lock();
        let mut best: Option<(&B, u64)> = None;
        for_each_size(&state.map, key, |entry_width, entry_height, entry_suffix, bitmap| {
            let area = u64::from(entry_width) * u64::from(entry_height);
            if entry_suffix == suffix
                && entry_width >= width
                && entry_height >= height
                && best.is_none_or(|(_, best_area)| area < best_area)
            {
                best = Some((bitmap, area));
            }
        });
        best.map(|(bitmap, _)| bitmap.clone())
    }

    /// The largest cached decode of identity `key`, any size: a stand-in when every source failed.
    pub fn peek_largest(&self, key: &str) -> Option<B> {
        let state = self.lock();
        let mut best: Option<&B> = None;
        for_each_size(&state.map, key, |_, _, _, bitmap| {
            if best.is_none_or(|current| bitmap.byte_size() > current.byte_size()) {
                best = Some(bitmap);
            }
        });
        best.cloned()
    }

    pub fn put(&self, key: String, bitmap: B, stand_in: bool) {
        let mut state = self.lock();
        let size = bitmap.byte_size();
        if let Some(old) = state.map.shift_remove(&key) {
            state.total_bytes -= old.byte_size();
        }
        if stand_in {
            state.stand_ins.insert(key.clone());
        } else {
            state.stand_ins.remove(&key);
        }
        state.map.insert(key, bitmap);
        state.total_bytes += size;
        // The fresh insert is last, so it only evicts itself when it alone exceeds the budget.
        let max = state.max_bytes;
        evict_to(&mut state, max);
    }

    pub fn set_budget(&self, bytes: u64) {
        let mut state = self.lock();
        state.max_bytes = bytes;
        evict_to(&mut state, bytes);
    }

    pub fn trim_to(&self, bytes: u64) {
        evict_to(&mut self.lock(), bytes);
    }
}

fn evict_to<B: Bitmap>(state: &mut State<B>, target: u64) {
    while state.total_bytes > target {
        let Some((key, eldest)) = state.map.shift_remove_index(0) else { break };
        state.stand_ins.remove(&key);
        state.total_bytes -= eldest.byte_size();
    }
}

fn for_each_size<'a, B>(map: &'a IndexMap<String, B>, key: &str, mut block: impl FnMut(u32, u32, &str, &'a B)) {
    for (entry_key, bitmap) in map {
        let Some(rest) = entry_key.strip_prefix(key).and_then(|rest| rest.strip_prefix('@')) else { continue };
        let Some((width, rest)) = rest.split_once('x') else { continue };
        let digits_end = rest.find(|character: char| !character.is_ascii_digit()).unwrap_or(rest.len());
        let (height, suffix) = rest.split_at(digits_end);
        if !(suffix.is_empty() || suffix == "~fit") || !is_digits(width) || !is_digits(height) {
            continue;
        }
        let (Ok(width), Ok(height)) = (width.parse::<u32>(), height.parse::<u32>()) else { continue };
        block(width, height, suffix, bitmap);
    }
}

fn is_digits(text: &str) -> bool {
    !text.is_empty() && text.bytes().all(|byte| byte.is_ascii_digit())
}
