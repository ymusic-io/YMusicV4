//! Debug builds run side by side, one per worktree: the workspace setup gives a worktree its own
//! copy of the app data and names it in `.ymusic-dev-data` at the worktree root (read at launch, so
//! setting one up needs no rebuild); its `rust` folder is this app's data dir. Its windows carry the
//! branch, and its taskbar / dock entries and MPRIS name an id derived from the workspace name.
//! Release builds have neither.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use ym_i18n::{Str, t};

/// At the worktree root: `name=<workspace name>` and `root=<absolute workspace data folder>` lines.
const WORKSPACE_FILE: &str = ".ymusic-dev-data";
/// This app's data dir inside the workspace folder (the JVM app's is `jvm`).
const WORKSPACE_DATA_DIR: &str = "rust";
/// Debug: prints the data dir, the workspace and the title, then exits before claiming the data dir.
pub const PRINT_DATA_DIR_ARG: &str = "--print-data-dir";
/// Debug: `1` lets a workspace run start the OS media session (media keys, Now Playing).
const MEDIA_SESSION_ENV: &str = "YMUSIC_DEV_MEDIA_SESSION";

static WORKSPACE: OnceLock<Option<Workspace>> = OnceLock::new();

#[derive(Debug, Clone, PartialEq)]
pub struct Workspace {
    pub name: String,
    /// [`workspace_id`] of the name: the OS identity suffix, shared with the JVM app.
    pub id: String,
}

impl Workspace {
    fn new(name: &str) -> Self {
        Self { name: name.to_owned(), id: workspace_id(name) }
    }
}

/// FNV-1a (32-bit) over the name's UTF-8 bytes, as 8 lowercase hex digits; the JVM app derives the
/// same id, so both apps of one workspace share it.
pub fn workspace_id(name: &str) -> String {
    let hash = name.bytes().fold(0x811c_9dc5_u32, |hash, byte| (hash ^ u32::from(byte)).wrapping_mul(0x0100_0193));
    format!("{hash:08x}")
}

/// A debug launch's data dir and the workspace it belongs to, if any.
pub struct DevDataDir {
    pub dir: PathBuf,
    pub workspace: Option<Workspace>,
    /// The workspace file was there but unusable: the launch fell back to the default data dir.
    pub workspace_file_problem: Option<String>,
}

impl DevDataDir {
    /// [`PRINT_DATA_DIR_ARG`]'s answer, after [`set_workspace`].
    pub fn print(&self) {
        println!("data_dir={}", self.dir.display());
        println!("workspace={}", self.workspace.as_ref().map_or("", |workspace| workspace.name.as_str()));
        println!("title={}", window_title());
        if let Some(problem) = &self.workspace_file_problem {
            println!("workspace_file_problem={problem}");
        }
    }
}

/// `--data-dir=` (a Jump List task is a shell link, which sets no environment), then
/// `YMUSIC_DATA_DIR`, then the worktree's workspace, then `<local data dir>/YMusicRust`. A given dir
/// is the workspace's only when it is the workspace's own data dir.
pub fn resolve() -> DevDataDir {
    let given = std::env::args_os()
        .skip(1)
        .find_map(|arg| arg.to_str()?.strip_prefix(crate::DEV_DATA_DIR_ARG).map(PathBuf::from))
        .or_else(|| std::env::var_os("YMUSIC_DATA_DIR").map(PathBuf::from));
    let (found, workspace_file_problem) = match read_workspace_file() {
        Ok(found) => (found, None),
        Err(problem) => (None, Some(problem)),
    };
    match (given, found) {
        (Some(dir), found) => {
            let workspace = found.filter(|(_, workspace_dir)| *workspace_dir == dir).map(|(workspace, _)| workspace);
            DevDataDir { dir, workspace, workspace_file_problem }
        }
        (None, Some((workspace, dir))) => DevDataDir { dir, workspace: Some(workspace), workspace_file_problem },
        (None, None) => DevDataDir {
            dir: dirs::data_local_dir().unwrap_or_else(std::env::temp_dir).join("YMusicRust"),
            workspace: None,
            workspace_file_problem,
        },
    }
}

/// The worktree's workspace and its data dir; `Ok(None)` when the worktree has none.
fn read_workspace_file() -> Result<Option<(Workspace, PathBuf)>, String> {
    let Some(root) = option_env!("YM_WORKTREE_ROOT") else { return Ok(None) };
    let file = Path::new(root).join(WORKSPACE_FILE);
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
    let name = value("name").filter(|name| !name.is_empty());
    let workspace_root = value("root").map(PathBuf::from).filter(|root| root.is_absolute());
    match (name, workspace_root) {
        (Some(name), Some(workspace_root)) => Ok(Some((Workspace::new(name), workspace_root.join(WORKSPACE_DATA_DIR)))),
        _ => Err(format!("{} needs a name=<workspace> line and an absolute root=<path> line", file.display())),
    }
}

/// Once, before anything reads [`workspace`].
pub fn set_workspace(workspace: Option<Workspace>) {
    let _ = WORKSPACE.set(workspace);
}

pub fn workspace() -> Option<&'static Workspace> {
    WORKSPACE.get().and_then(Option::as_ref)
}

/// A workspace run leaves the OS media session (media keys, Now Playing) to the others unless
/// `YMUSIC_DEV_MEDIA_SESSION=1`, so one key press never reaches two instances.
pub fn media_session_allowed() -> bool {
    workspace().is_none() || std::env::var(MEDIA_SESSION_ENV).is_ok_and(|value| value == "1")
}

/// The branch a debug build was built from, or its commit when HEAD was detached.
pub fn branch() -> Option<&'static str> {
    option_env!("YM_GIT_BRANCH").filter(|branch| !branch.is_empty())
}

/// Every window's title: `YMusic · <branch>` in a debug build, the app's name in release.
pub fn window_title() -> String {
    let mut title = t(Str::AppName).to_owned();
    if let Some(branch) = branch() {
        title.push_str(" · ");
        title.push_str(branch);
    }
    title
}

/// The Linux desktop entry's name: [`window_title`] in a workspace run, which has its own entry; the
/// app's name in the shared one a release install uses too.
#[cfg(target_os = "linux")]
pub fn desktop_entry_name() -> String {
    if workspace().is_some() { window_title() } else { t(Str::AppName).to_owned() }
}
