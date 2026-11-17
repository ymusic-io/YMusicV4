//! Debug builds run side by side, one per worktree: `tools/dev-slot.sh claim` gives a worktree a
//! numbered slot (`.ymusic-dev-slot` at its root, read at launch, so claiming needs no rebuild), whose
//! `rust` folder is this app's data dir. Its windows, tray, Dock tile and taskbar entry carry the
//! branch and the slot. Release builds have neither.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use ym_i18n::{Str, t};

/// At the worktree root: `slot=<N>` and `root=<absolute slot folder>` lines.
const SLOT_FILE: &str = ".ymusic-dev-slot";
/// This app's data dir inside the slot folder (the JVM app's is `jvm`).
const SLOT_DATA_DIR: &str = "rust";
/// Debug: prints the data dir and the slot, then exits before claiming the data dir.
pub const PRINT_DATA_DIR_ARG: &str = "--print-data-dir";

static SLOT: OnceLock<Option<u32>> = OnceLock::new();

/// A debug launch's data dir and the slot it belongs to, if any.
pub struct DevDataDir {
    pub dir: PathBuf,
    pub slot: Option<u32>,
    /// The slot file was there but unusable: the launch fell back to the default data dir.
    pub slot_file_problem: Option<String>,
}

impl DevDataDir {
    /// [`PRINT_DATA_DIR_ARG`]'s answer, after [`set_slot`].
    pub fn print(&self) {
        println!("data_dir={}", self.dir.display());
        println!("slot={}", slot().map(|slot| slot.to_string()).unwrap_or_default());
        println!("title={}", window_title());
        if let Some(problem) = &self.slot_file_problem {
            println!("slot_file_problem={problem}");
        }
    }
}

/// `--data-dir=` (a Jump List task is a shell link, which sets no environment), then
/// `YMUSIC_DATA_DIR`, then the worktree's slot, then `<local data dir>/YMusicRust`. A given dir is
/// the slot's only when it is the slot's own data dir.
pub fn resolve() -> DevDataDir {
    let given = std::env::args_os()
        .skip(1)
        .find_map(|arg| arg.to_str()?.strip_prefix(crate::DEV_DATA_DIR_ARG).map(PathBuf::from))
        .or_else(|| std::env::var_os("YMUSIC_DATA_DIR").map(PathBuf::from));
    let (claimed, slot_file_problem) = match read_slot_file() {
        Ok(claimed) => (claimed, None),
        Err(problem) => (None, Some(problem)),
    };
    match (given, claimed) {
        (Some(dir), claimed) => {
            let slot = claimed.filter(|(_, slot_dir)| *slot_dir == dir).map(|(slot, _)| slot);
            DevDataDir { dir, slot, slot_file_problem }
        }
        (None, Some((slot, dir))) => DevDataDir { dir, slot: Some(slot), slot_file_problem },
        (None, None) => DevDataDir {
            dir: dirs::data_local_dir().unwrap_or_else(std::env::temp_dir).join("YMusicRust"),
            slot: None,
            slot_file_problem,
        },
    }
}

/// The worktree's slot and its data dir; `Ok(None)` when the worktree has claimed none.
fn read_slot_file() -> Result<Option<(u32, PathBuf)>, String> {
    let Some(root) = option_env!("YM_WORKTREE_ROOT") else { return Ok(None) };
    let file = Path::new(root).join(SLOT_FILE);
    let text = match std::fs::read_to_string(&file) {
        Ok(text) => text,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("{}: {error}", file.display())),
    };
    let value = |key: &str| {
        text.lines()
            .filter_map(|line| line.split_once('='))
            .find(|(name, _)| name.trim() == key)
            .map(|(_, value)| value.trim())
    };
    let slot = value("slot").and_then(|slot| slot.parse::<u32>().ok()).filter(|slot| *slot > 0);
    let slot_root = value("root").map(PathBuf::from).filter(|root| root.is_absolute());
    match (slot, slot_root) {
        (Some(slot), Some(slot_root)) => Ok(Some((slot, slot_root.join(SLOT_DATA_DIR)))),
        _ => Err(format!("{} needs a slot=<N> line and an absolute root=<path> line", file.display())),
    }
}

/// Once, before anything reads [`slot`].
pub fn set_slot(slot: Option<u32>) {
    let _ = SLOT.set(slot);
}

pub fn slot() -> Option<u32> {
    SLOT.get().copied().flatten()
}

/// Slots after the first leave machine-wide resources (the OS media session and its media keys)
/// to slot 1, so one key press never reaches two instances.
pub fn owns_global_resources() -> bool {
    slot().is_none_or(|slot| slot == 1)
}

/// The branch a debug build was built from, or its commit when HEAD was detached.
pub fn branch() -> Option<&'static str> {
    option_env!("YM_GIT_BRANCH").filter(|branch| !branch.is_empty())
}

/// Every window's title: `YMusic · <branch> · #<slot>` in a debug build, the app's name in release.
pub fn window_title() -> String {
    let mut title = t(Str::AppName).to_owned();
    for part in branch().map(str::to_owned).into_iter().chain(slot().map(|slot| format!("#{slot}"))) {
        title.push_str(" · ");
        title.push_str(&part);
    }
    title
}
