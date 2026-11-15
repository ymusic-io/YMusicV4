//! The taskbar button's Jump List tasks (JVM `WindowsJumpList`): Pause/Resume and Stop while there
//! is a queue, then Quit. Each relaunches the executable with a `--player=` command, which the single
//! instance hands to the running app. COM wants one apartment thread, so every call runs on its own.

use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, Sender};

use gpui_kit::{App, Global};
use windows::Win32::Foundation::PROPERTYKEY;
use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx};
use windows::Win32::UI::Shell::Common::{IObjectArray, IObjectCollection};
use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
use windows::Win32::UI::Shell::{
    DestinationList, EnumerableObjectCollection, ICustomDestinationList, IShellLinkW, ShellLink,
};
use windows::core::{GUID, HSTRING, Interface};
use ym_common::log::Logger;
use ym_i18n::{Str, t};

use crate::instance::Command;

const PKEY_TITLE: PROPERTYKEY = PROPERTYKEY { fmtid: GUID::from_u128(0xf29f85e0_4ff9_1068_ab91_08002b27b3d9), pid: 2 };
/// Windows menu icon slots across 100–200% DPI.
const ICON_SIZES: [u32; 6] = [16, 20, 24, 32, 40, 48];
/// The JVM app's `DefaultAccent`.
const ICON_TINT: &str = "#E8732A";
/// Material Pause, PlayArrow, Stop and ExitToApp, as the JVM app draws them; file stems are its own.
const GLYPHS: [(&str, &str); 4] = [
    ("toggle-true", "M6 19h4V5H6v14zm8-14v14h4V5h-4z"),
    ("toggle-false", "M8 5v14l11-7z"),
    ("stop", "M6 6h12v12H6z"),
    (
        "quit",
        "M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59zM19 3H5c-1.11 0-2 .9-2 \
         2v4h2V5h14v14H5v-4H3v4c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z",
    ),
];

#[derive(Clone, PartialEq)]
struct Task {
    title: String,
    command: Command,
    icon: &'static str,
}

enum Update {
    Tasks(Vec<Task>),
    Clear(futures::channel::oneshot::Sender<()>),
}

/// What a task runs: this executable, which in a debug build also needs this run's data dir to find
/// the running app.
struct Launcher {
    exe: PathBuf,
    data_dir_arg: Option<String>,
}

impl Launcher {
    fn arguments(&self, command: Command) -> String {
        match &self.data_dir_arg {
            Some(data_dir) => format!("{} {data_dir}", command.player_arg()),
            None => command.player_arg(),
        }
    }
}

/// The running app's Jump List; tasks change with [`JumpList::update`], and quitting clears them (a
/// pinned button keeps its list after exit, and tasks for a closed app would only relaunch it).
pub struct JumpList {
    updates: Sender<Update>,
}

impl Global for JumpList {}

impl JumpList {
    /// Starts the COM thread, which first writes the task icons into `<data dir>/jumplist`.
    pub fn install(data_dir: &Path, log: Logger, cx: &mut App) {
        let exe = match std::env::current_exe() {
            Ok(exe) => exe,
            Err(error) => {
                log.warn("jump list: the executable's path is unknown", Some(&error));
                return;
            }
        };
        let data_dir_arg =
            cfg!(debug_assertions).then(|| quoted(&format!("{}{}", crate::DEV_DATA_DIR_ARG, data_dir.display())));
        let launcher = Launcher { exe, data_dir_arg };
        let (updates, receiver) = std::sync::mpsc::channel();
        let icon_dir = data_dir.join("jumplist");
        let thread_log = log.clone();
        let spawned = std::thread::Builder::new()
            .name("jump-list".into())
            .spawn(move || run(&launcher, &icon_dir, &receiver, &thread_log));
        if let Err(error) = spawned {
            log.warn("jump list: its thread did not start", Some(&error));
            return;
        }
        let quit_updates = updates.clone();
        cx.on_app_quit(move |_| {
            let (done, cleared) = futures::channel::oneshot::channel();
            let sent = quit_updates.send(Update::Clear(done)).is_ok();
            async move {
                if sent {
                    let _ = cleared.await;
                }
            }
        })
        .detach();
        cx.set_global(Self { updates });
    }

    pub fn update(&self, has_queue: bool, playing: bool) {
        let mut tasks = Vec::new();
        if has_queue {
            let (title, icon) =
                if playing { (Str::TrayPause, "toggle-true") } else { (Str::TrayResume, "toggle-false") };
            tasks.push(Task { title: t(title).to_owned(), command: Command::TogglePlay, icon });
            tasks.push(Task { title: t(Str::TrayStop).to_owned(), command: Command::Stop, icon: "stop" });
        }
        tasks.push(Task { title: t(Str::TrayQuit).to_owned(), command: Command::Quit, icon: "quit" });
        // The thread is gone only after the quit's clear.
        let _ = self.updates.send(Update::Tasks(tasks));
    }
}

fn run(launcher: &Launcher, icon_dir: &Path, updates: &Receiver<Update>, log: &Logger) {
    // SAFETY: initializes COM for this thread, which makes every COM call below.
    if let Err(error) = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) }.ok() {
        log.warn("jump list: COM did not initialize", Some(&error));
        return;
    }
    let icons = write_icons(icon_dir, log);
    let mut shown: Option<Vec<Task>> = None;
    while let Ok(mut update) = updates.recv() {
        while let Ok(newer) = updates.try_recv() {
            update = newer;
        }
        match update {
            Update::Tasks(tasks) if shown.as_ref() != Some(&tasks) => match set(launcher, &tasks, &icons) {
                Ok(()) => shown = Some(tasks),
                Err(error) => log.warn("jump list update failed", Some(&error)),
            },
            Update::Tasks(_) => {}
            Update::Clear(done) => {
                if let Err(error) = clear() {
                    log.warn("jump list clear failed", Some(&error));
                }
                let _ = done.send(());
                return;
            }
        }
    }
}

fn set(launcher: &Launcher, tasks: &[Task], icons: &[(&'static str, PathBuf)]) -> windows::core::Result<()> {
    // SAFETY: COM calls on this apartment thread, with interfaces it created and owns.
    unsafe {
        let list: ICustomDestinationList = CoCreateInstance(&DestinationList, None, CLSCTX_INPROC_SERVER)?;
        let mut slots = 0;
        let _removed: IObjectArray = list.BeginList(&mut slots)?;
        let collection: IObjectCollection = CoCreateInstance(&EnumerableObjectCollection, None, CLSCTX_INPROC_SERVER)?;
        for task in tasks {
            let icon = icons.iter().find(|(name, _)| *name == task.icon).map(|(_, path)| path.as_path());
            collection.AddObject(&shell_link(launcher, task, icon)?)?;
        }
        list.AddUserTasks(&collection)?;
        list.CommitList()
    }
}

fn clear() -> windows::core::Result<()> {
    // SAFETY: COM calls on this apartment thread.
    unsafe {
        let list: ICustomDestinationList = CoCreateInstance(&DestinationList, None, CLSCTX_INPROC_SERVER)?;
        list.DeleteList(None)
    }
}

/// `icon`: an .ico for the task, else the executable's own icon.
fn shell_link(launcher: &Launcher, task: &Task, icon: Option<&Path>) -> windows::core::Result<IShellLinkW> {
    // SAFETY: COM calls on this apartment thread; the strings outlive each call.
    unsafe {
        let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
        link.SetPath(&HSTRING::from(launcher.exe.as_os_str()))?;
        link.SetArguments(&HSTRING::from(launcher.arguments(task.command)))?;
        link.SetIconLocation(&HSTRING::from(icon.unwrap_or(&launcher.exe).as_os_str()), 0)?;
        let store: IPropertyStore = link.cast()?;
        store.SetValue(&PKEY_TITLE, &PROPVARIANT::from(task.title.as_str()))?;
        store.Commit()?;
        Ok(link)
    }
}

/// `arg` as one command-line argument under `CommandLineToArgvW`'s rules; paths hold no `"`.
pub(crate) fn quoted(arg: &str) -> String {
    let trailing_backslashes = arg.len() - arg.trim_end_matches('\\').len();
    format!("\"{arg}{}\"", "\\".repeat(trailing_backslashes))
}

/// Each glyph as an .ico of PNGs, rewritten only when its bytes change; a failed one falls back to
/// the executable's icon.
fn write_icons(dir: &Path, log: &Logger) -> Vec<(&'static str, PathBuf)> {
    if let Err(error) = std::fs::create_dir_all(dir) {
        log.warn("jump list icons: the folder could not be made, using the exe's", Some(&error));
        return Vec::new();
    }
    let mut written = Vec::new();
    for (name, path) in GLYPHS {
        let file = dir.join(format!("{name}.ico"));
        let result = ico(path).and_then(|bytes| {
            if std::fs::read(&file).is_ok_and(|old| old == bytes) {
                return Ok(());
            }
            std::fs::write(&file, bytes).map_err(anyhow::Error::from)
        });
        match result {
            Ok(()) => written.push((name, file)),
            Err(error) => log.warn("jump list icon not written, using the exe's", Some(error.as_ref())),
        }
    }
    written
}

fn ico(path: &str) -> anyhow::Result<Vec<u8>> {
    let svg = format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="{ICON_TINT}" d="{path}"/></svg>"#
    );
    let tree = resvg::usvg::Tree::from_str(&svg, &resvg::usvg::Options::default())?;
    let mut pngs = Vec::new();
    for size in ICON_SIZES {
        let mut pixmap =
            resvg::tiny_skia::Pixmap::new(size, size).ok_or_else(|| anyhow::anyhow!("no {size} px pixmap"))?;
        let scale = size as f32 / 24.;
        resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(scale, scale), &mut pixmap.as_mut());
        let pixels = pixmap.pixels().iter().flat_map(|pixel| {
            let color = pixel.demultiply();
            [color.red(), color.green(), color.blue(), color.alpha()]
        });
        let image = image::RgbaImage::from_raw(size, size, pixels.collect())
            .ok_or_else(|| anyhow::anyhow!("{size} px icon buffer"))?;
        let mut png = Vec::new();
        image.write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)?;
        pngs.push((size, png));
    }
    let mut out = Vec::new();
    out.extend_from_slice(&[0, 0, 1, 0]);
    out.extend_from_slice(&(pngs.len() as u16).to_le_bytes());
    let mut offset = 6 + 16 * pngs.len() as u32;
    for (size, png) in &pngs {
        // 0 stands for 256 px.
        let side = if *size >= 256 { 0 } else { *size as u8 };
        out.extend_from_slice(&[side, side, 0, 0]);
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&32u16.to_le_bytes());
        out.extend_from_slice(&(png.len() as u32).to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        offset += png.len() as u32;
    }
    for (_, png) in &pngs {
        out.extend_from_slice(png);
    }
    Ok(out)
}
