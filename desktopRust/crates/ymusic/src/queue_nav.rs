//! Which queue item plays next: loop modes, and shuffle with a played-order history so
//! `previous` walks back through what actually played.

use rand::Rng;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum LoopMode {
    /// Stop at the end of the queue.
    #[default]
    None,
    /// Replay the current item when it ends (a user skip still moves on).
    One,
    /// Wrap around at the ends of the queue.
    All,
}

impl LoopMode {
    /// The JVM app's persisted names.
    pub fn name(self) -> &'static str {
        match self {
            Self::None => "None",
            Self::One => "One",
            Self::All => "All",
        }
    }

    pub fn from_name(name: &str) -> Self {
        match name {
            "One" => Self::One,
            "All" => Self::All,
            _ => Self::None,
        }
    }

    /// The order a repeat button cycles through.
    pub fn cycled(self) -> Self {
        match self {
            Self::None => Self::All,
            Self::All => Self::One,
            Self::One => Self::None,
        }
    }
}

#[derive(Default)]
pub struct QueueNav {
    pub loop_mode: LoopMode,
    pub shuffle: bool,
    /// Indices in the order they played; the last is the current item.
    played: Vec<usize>,
}

impl QueueNav {
    pub fn new(loop_mode: LoopMode, shuffle: bool) -> Self {
        Self { loop_mode, shuffle, played: Vec::new() }
    }

    /// A new queue starts its history at `index`.
    pub fn reset(&mut self, index: usize) {
        self.played.clear();
        self.played.push(index);
    }

    /// An item was inserted at `index`: history entries at or after it move up one.
    pub fn inserted_at(&mut self, index: usize) {
        for played in &mut self.played {
            if *played >= index {
                *played += 1;
            }
        }
    }

    /// The item at `from` moved to `to`: history entries follow the items they name.
    pub fn moved(&mut self, from: usize, to: usize) {
        for played in &mut self.played {
            *played = moved_index(*played, from, to);
        }
    }

    /// Toggling shuffle restarts the history from the current item.
    pub fn set_shuffle(&mut self, shuffle: bool, current: usize) {
        self.shuffle = shuffle;
        self.reset(current);
    }

    /// The item after `current` ends (`user` false) or is skipped (`user` true); none when the
    /// queue is exhausted.
    pub fn peek_next(&self, user: bool, current: usize, len: usize) -> Option<usize> {
        if len == 0 {
            return None;
        }
        if !user && self.loop_mode == LoopMode::One {
            return Some(current);
        }
        if !self.shuffle {
            return match current + 1 {
                next if next < len => Some(next),
                _ if self.loop_mode == LoopMode::All => Some(0),
                _ => None,
            };
        }
        let unplayed: Vec<usize> = (0..len).filter(|index| !self.played.contains(index)).collect();
        let mut random = rand::rng();
        if !unplayed.is_empty() {
            return Some(unplayed[random.random_range(0..unplayed.len())]);
        }
        if self.loop_mode != LoopMode::All {
            return None;
        }
        // Every item played: a new cycle, avoiding an immediate repeat when possible.
        let candidates: Vec<usize> = (0..len).filter(|index| len == 1 || *index != current).collect();
        Some(candidates[random.random_range(0..candidates.len())])
    }

    /// Makes `index` (from [`Self::peek_next`] or a jump) current.
    pub fn commit(&mut self, index: usize, len: usize) {
        if self.shuffle && (0..len).all(|candidate| self.played.contains(&candidate)) {
            self.played.clear();
        }
        if self.played.last() != Some(&index) {
            self.played.push(index);
        }
    }

    /// The item to go back to: the previously played one under shuffle, the previous index
    /// otherwise (wrapping under `All`); none at the start.
    pub fn previous(&mut self, current: usize, len: usize) -> Option<usize> {
        if len == 0 {
            return None;
        }
        if self.shuffle {
            if self.played.len() < 2 {
                return None;
            }
            self.played.pop();
            return self.played.last().copied();
        }
        let previous = match current {
            0 if self.loop_mode == LoopMode::All => len - 1,
            0 => return None,
            current => current - 1,
        };
        self.commit(previous, len);
        Some(previous)
    }
}

/// Where the item at `index` sits after the item at `from` moved to `to`.
pub fn moved_index(index: usize, from: usize, to: usize) -> usize {
    if index == from {
        to
    } else if from < to && (from + 1..=to).contains(&index) {
        index - 1
    } else if to < from && (to..from).contains(&index) {
        index + 1
    } else {
        index
    }
}
