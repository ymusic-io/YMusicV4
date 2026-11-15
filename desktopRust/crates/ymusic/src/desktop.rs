//! The desktop shell around the window: its remembered size and position, the Dock icon, and the
//! tray icon that controls playback and brings a window closed to the tray back.

use std::path::{Path, PathBuf};
use std::time::Duration;

use gpui_kit::*;
use ym_common::log::Logger;
use ym_i18n::{Str, t};
use ym_settings::SettingsStore;

/// The JVM app's window prefs: a Java properties file of `width`, `height`, `x`, `y` in points.
pub const WINDOW_PREFS_FILE: &str = "window.properties";
const MIN_WIDTH: f32 = 360.;
const MIN_HEIGHT: f32 = 480.;
/// Resizes settle this long before the bounds are written.
pub const BOUNDS_SAVE_DELAY: Duration = Duration::from_millis(500);

/// Where the window's bounds are kept.
pub struct WindowPrefs(pub PathBuf);

impl Global for WindowPrefs {}

/// The window's top-left and content size from the last run, if it saved one. Blocking.
pub fn load_window_prefs(path: &Path) -> Option<Bounds<Pixels>> {
    let text = std::fs::read_to_string(path).ok()?;
    let value = |key: &str| {
        text.lines()
            .filter_map(|line| line.split_once('='))
            .find(|(name, _)| name.trim() == key)
            .and_then(|(_, value)| value.trim().parse::<f32>().ok())
    };
    let (width, height, x, y) = (value("width")?, value("height")?, value("x")?, value("y")?);
    (width >= MIN_WIDTH && height >= MIN_HEIGHT).then(|| bounds(point(px(x), px(y)), size(px(width), px(height))))
}

/// The saved window bounds when they still fit a connected display, else `fallback` centered.
pub fn initial_bounds(saved: Option<Bounds<Pixels>>, fallback: Size<Pixels>, cx: &App) -> WindowBounds {
    // A title bar that lands on no screen (a monitor unplugged since) could never be grabbed back.
    let on_screen = |saved: &Bounds<Pixels>| {
        let grip = point(saved.origin.x + px(100.), saved.origin.y + px(20.));
        cx.displays().iter().any(|display| display.bounds().contains(&grip))
    };
    match saved {
        Some(saved) if on_screen(&saved) => WindowBounds::Windowed(saved),
        _ => WindowBounds::centered(fallback, cx),
    }
}

/// Writes the window's position and size while it floats; a maximized or fullscreen window keeps
/// the last floating ones.
pub fn save_bounds(window: &Window, log: Logger, cx: &App) {
    let WindowBounds::Windowed(frame) = window.window_bounds() else { return };
    // The window opens at a content size; its frame adds the title bar.
    let content = window.viewport_size();
    let path = cx.global::<WindowPrefs>().0.clone();
    let text = prefs_text(
        f32::from(frame.origin.x),
        f32::from(frame.origin.y),
        f32::from(content.width),
        f32::from(content.height),
    );
    cx.global::<crate::graph::AppGraph>().tokio.spawn_blocking(move || {
        if let Err(error) = std::fs::write(&path, text) {
            log.warn("window bounds could not be saved", Some(&error));
        }
    });
}

fn prefs_text(x: f32, y: f32, width: f32, height: f32) -> String {
    let float = ym_settings::kotlin_float;
    format!("#YMusic window\nheight={}\nwidth={}\nx={}\ny={}\n", float(height), float(width), float(x), float(y))
}

/// Moves the bounds earlier builds kept in app.db (`x,y,width,height`) into the JVM app's file.
/// Blocking.
pub fn adopt_saved_bounds(app_db: &ym_settings::AppDb, path: &Path, log: &Logger) {
    const LEGACY_KEY: &str = "window.bounds";
    let Ok(Some(raw)) = app_db.get_raw(LEGACY_KEY) else { return };
    let parts: Vec<f32> = raw.split(',').filter_map(|part| part.parse().ok()).collect();
    if let ([x, y, width, height], false) = (parts.as_slice(), path.exists())
        && let Err(error) = std::fs::write(path, prefs_text(*x, *y, *width, *height))
    {
        log.warn("R90201 saved window bounds could not move to window.properties", Some(&error));
        return;
    }
    if let Err(error) = app_db.delete_setting(LEGACY_KEY) {
        log.warn("R90202 the old window.bounds setting could not be removed", Some(&error));
    }
}

#[cfg_attr(target_os = "windows", allow(dead_code))]
const LOGO_PNG: &[u8] = include_bytes!("../assets/ymusic-512.png");

/// The JVM app's tray scales: a raster per display scale, since one upscaled by the OS turns soft.
#[cfg(not(target_os = "windows"))]
const TRAY_SCALES: [f32; 7] = [1., 1.25, 1.5, 1.75, 2., 2.5, 3.];

/// The JVM app's tray icon (AWT `CSystemTray`), in points: drawn at that size, as it fits the 21 pt
/// AWT leaves in the status bar.
#[cfg(target_os = "macos")]
const TRAY_POINTS: f32 = 20.;

/// The tray icon's sizes on Linux, which the StatusNotifierItem host picks from: GNOME's panel cell
/// and KDE's, across the scales.
#[cfg(target_os = "linux")]
const TRAY_LINUX_BASES: [f32; 2] = [16., 22.];

/// The tray icon as the JVM app draws it: the logo's disc (`ymusic_logo`), full color, a `side` px
/// square of straight RGBA. One mask cuts the disc, so its rim antialiases once.
fn tray_logo(side: u32) -> anyhow::Result<image::RgbaImage> {
    use resvg::tiny_skia::{FillRule, Mask, PathBuilder, Pixmap, Transform};
    let tree = resvg::usvg::Tree::from_str(crate::assets::LOGO_SVG, &resvg::usvg::Options::default())?;
    let mut pixmap = Pixmap::new(side, side).ok_or_else(|| anyhow::anyhow!("no {side} px tray pixmap"))?;
    let scale = side as f32 / tree.size().width();
    resvg::render(&tree, Transform::from_scale(scale, scale), &mut pixmap.as_mut());
    let radius = side as f32 / 2.;
    let disc = PathBuilder::from_circle(radius, radius, radius).ok_or_else(|| anyhow::anyhow!("no tray disc"))?;
    let mut mask = Mask::new(side, side).ok_or_else(|| anyhow::anyhow!("no {side} px tray mask"))?;
    mask.fill_path(&disc, FillRule::Winding, true, Transform::identity());
    pixmap.apply_mask(&mask);
    let pixels = pixmap.pixels().iter().flat_map(|pixel| {
        let color = pixel.demultiply();
        [color.red(), color.green(), color.blue(), color.alpha()]
    });
    image::RgbaImage::from_raw(side, side, pixels.collect()).ok_or_else(|| anyhow::anyhow!("{side} px tray buffer"))
}

/// The Dock shows the app's logo even when it runs outside an app bundle.
#[cfg(target_os = "macos")]
pub fn set_dock_icon(log: &Logger) {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::{AnyClass, AnyObject};
    let (Some(app_class), Some(image_class), Some(data_class)) =
        (AnyClass::get(c"NSApplication"), AnyClass::get(c"NSImage"), AnyClass::get(c"NSData"))
    else {
        log.warn("dock icon: AppKit classes missing", None);
        return;
    };
    // SAFETY: plain AppKit calls on the main thread; `init` hands back the one reference `alloc` made.
    unsafe {
        let data: Retained<AnyObject> =
            msg_send![data_class, dataWithBytes: LOGO_PNG.as_ptr().cast::<std::ffi::c_void>(), length: LOGO_PNG.len()];
        let allocated: *mut AnyObject = msg_send![image_class, alloc];
        let image: *mut AnyObject = msg_send![allocated, initWithData: &*data];
        let Some(image) = Retained::from_raw(image) else {
            log.warn("dock icon: the logo did not decode", None);
            return;
        };
        let app: Retained<AnyObject> = msg_send![app_class, sharedApplication];
        let _: () = msg_send![&*app, setApplicationIconImage: &*image];
    }
}

#[cfg(not(target_os = "macos"))]
pub fn set_dock_icon(_log: &Logger) {}

/// The app's AppUserModelID, which the installer's shortcuts carry too: the taskbar and the media
/// flyout find the name and icon through the Start menu shortcut carrying it, and a pin merges with
/// the running window only when they match. Debug builds take their own, so they never touch the
/// installed app's taskbar button.
#[cfg(target_os = "windows")]
const APP_USER_MODEL_ID: &str = if cfg!(debug_assertions) { "io.ymusic.windows.dev" } else { "io.ymusic.windows" };

#[cfg(target_os = "windows")]
pub fn claim_app_user_model_id(log: &Logger) {
    use windows::Win32::UI::Shell::SetCurrentProcessExplicitAppUserModelID;
    let id = windows::core::HSTRING::from(APP_USER_MODEL_ID);
    // SAFETY: a NUL-terminated UTF-16 string that outlives the call.
    if let Err(error) = unsafe { SetCurrentProcessExplicitAppUserModelID(&id) } {
        log.warn("SetCurrentProcessExplicitAppUserModelID failed", Some(&error));
    }
}

/// A debug build has no installer shortcut, so it keeps its own Start menu entry carrying its
/// AppUserModelID: without one the media flyout shows no name or logo for it. The entry launches
/// this executable on this run's data dir, as the Jump List's tasks do, and is rewritten only when
/// either changes. COM gets a thread of its own.
#[cfg(all(target_os = "windows", debug_assertions))]
pub fn sync_dev_start_menu_entry(data_dir: PathBuf, log: Logger) {
    let thread_log = log.clone();
    let spawned = std::thread::Builder::new().name("start-menu".into()).spawn(move || {
        if let Err(error) = dev_start_menu_entry(&data_dir) {
            thread_log.warn("the dev Start menu entry could not be written", Some(error.as_ref()));
        }
    });
    if let Err(error) = spawned {
        log.warn("the dev Start menu entry's thread did not start", Some(&error));
    }
}

#[cfg(all(target_os = "windows", debug_assertions))]
fn dev_start_menu_entry(data_dir: &Path) -> anyhow::Result<()> {
    use windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx, CoUninitialize};
    let exe = std::env::current_exe()?;
    let arguments = crate::jump_list::quoted(&format!("{}{}", crate::DEV_DATA_DIR_ARG, data_dir.display()));
    // SAFETY: balanced by the CoUninitialize below, on this thread.
    unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) }.ok()?;
    let synced = write_dev_start_menu_entry(&exe, &arguments);
    // SAFETY: pairs with the successful CoInitializeEx above.
    unsafe { CoUninitialize() };
    synced
}

#[cfg(all(target_os = "windows", debug_assertions))]
fn write_dev_start_menu_entry(exe: &Path, arguments: &str) -> anyhow::Result<()> {
    use windows::Win32::Foundation::PROPERTYKEY;
    use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
    use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance, CoTaskMemFree, IPersistFile, STGM_READ};
    use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
    use windows::Win32::UI::Shell::{FOLDERID_Programs, IShellLinkW, KF_FLAG_DEFAULT, SHGetKnownFolderPath, ShellLink};
    use windows::core::{GUID, HSTRING, Interface};
    const PKEY_APP_USER_MODEL_ID: PROPERTYKEY =
        PROPERTYKEY { fmtid: GUID::from_u128(0x9f4c2855_9f79_4b39_a8d0_e1d42de1d5f3), pid: 5 };
    const SLGP_RAWPATH: u32 = 4;
    // SAFETY: COM calls on this apartment thread with interfaces it created; the known folder's
    // buffer is freed once copied, and every string outlives its call.
    unsafe {
        let programs = SHGetKnownFolderPath(&FOLDERID_Programs, KF_FLAG_DEFAULT, None)?;
        let folder = programs.to_string();
        CoTaskMemFree(Some(programs.0 as *const _));
        let file = HSTRING::from(PathBuf::from(folder?).join("YMusic Dev.lnk").as_os_str());
        let saved: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
        if saved.cast::<IPersistFile>()?.Load(&file, STGM_READ).is_ok() {
            let mut target = [0u16; 2048];
            let mut saved_arguments = [0u16; 2048];
            saved.GetPath(&mut target, std::ptr::null_mut(), SLGP_RAWPATH)?;
            saved.GetArguments(&mut saved_arguments)?;
            let text = |buffer: &[u16]| {
                String::from_utf16_lossy(&buffer[..buffer.iter().position(|&unit| unit == 0).unwrap_or(buffer.len())])
            };
            let id = saved.cast::<IPropertyStore>()?.GetValue(&PKEY_APP_USER_MODEL_ID)?.to_string();
            if Path::new(&text(&target)) == exe && text(&saved_arguments) == arguments && id == APP_USER_MODEL_ID {
                return Ok(());
            }
        }
        let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
        link.SetPath(&HSTRING::from(exe.as_os_str()))?;
        link.SetArguments(&HSTRING::from(arguments))?;
        let store: IPropertyStore = link.cast()?;
        store.SetValue(&PKEY_APP_USER_MODEL_ID, &PROPVARIANT::from(APP_USER_MODEL_ID))?;
        store.Commit()?;
        link.cast::<IPersistFile>()?.Save(&file, true)?;
        Ok(())
    }
}

/// The Wayland app_id and X11 WM_CLASS of the window; GNOME takes its icon from the desktop entry
/// of the same name.
pub const APP_ID: &str = "io.ymusic.YMusic";

/// The logo as the X11 `_NET_WM_ICON` (Wayland has no window icon: the desktop entry gives it).
#[cfg(target_os = "linux")]
pub fn window_icon(log: &Logger) -> Option<std::sync::Arc<image::RgbaImage>> {
    match image::load_from_memory(LOGO_PNG) {
        Ok(logo) => {
            Some(std::sync::Arc::new(logo.resize(128, 128, image::imageops::FilterType::Triangle).into_rgba8()))
        }
        Err(error) => {
            log.warn("window icon: the logo did not decode", Some(&error));
            None
        }
    }
}

#[cfg(not(target_os = "linux"))]
pub fn window_icon(_log: &Logger) -> Option<std::sync::Arc<image::RgbaImage>> {
    None
}

/// GNOME Shell takes window icons only from a desktop entry matched by app_id / StartupWMClass, so
/// a hidden (NoDisplay) entry in the user's data dir maps the window to the logo. Rewritten only
/// when its contents change. An install under /opt or /usr ships its own entry, which a second one
/// with the same StartupWMClass would compete with, so there this one is removed.
#[cfg(target_os = "linux")]
pub fn sync_desktop_entry() -> anyhow::Result<()> {
    use std::path::PathBuf;
    let data_home = std::env::var_os("XDG_DATA_HOME")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .or_else(|| dirs::home_dir().map(|home| home.join(".local/share")))
        .ok_or_else(|| anyhow::anyhow!("no home directory"))?;
    let entry_file = data_home.join("applications").join(format!("{APP_ID}.desktop"));
    let icon_file = data_home.join("icons/hicolor/512x512/apps").join(format!("{APP_ID}.png"));
    // An AppImage runs from a temporary mount; `APPIMAGE` names the image file that launches it.
    let exe = match std::env::var_os("APPIMAGE").filter(|image| !image.is_empty()) {
        Some(image) => PathBuf::from(image),
        None => std::env::current_exe()?,
    };
    if exe.starts_with("/opt") || exe.starts_with("/usr") {
        for file in [&entry_file, &icon_file] {
            match std::fs::remove_file(file) {
                Err(error) if error.kind() != std::io::ErrorKind::NotFound => return Err(error.into()),
                _ => {}
            }
        }
        return Ok(());
    }
    write_if_changed(&icon_file, LOGO_PNG)?;
    let entry = format!(
        "[Desktop Entry]\nType=Application\nName={}\nExec={}\nIcon={}\nStartupWMClass={APP_ID}\nNoDisplay=true\n",
        t(Str::AppName),
        desktop_exec_quoted(&exe.to_string_lossy()),
        icon_file.display(),
    );
    write_if_changed(&entry_file, entry.as_bytes())
}

#[cfg(target_os = "linux")]
fn write_if_changed(file: &std::path::Path, contents: &[u8]) -> anyhow::Result<()> {
    if std::fs::read(file).is_ok_and(|current| current == contents) {
        return Ok(());
    }
    if let Some(parent) = file.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(file, contents)?;
    Ok(())
}

/// An Exec program path per the Desktop Entry spec: quoted, `"` `` ` `` `$` `\` backslash-escaped,
/// then string-escaped (each backslash doubled), and `%` doubled.
#[cfg(target_os = "linux")]
fn desktop_exec_quoted(path: &str) -> String {
    let mut quoted = String::from("\"");
    for character in path.chars() {
        match character {
            '\\' => quoted.push_str("\\\\\\\\"),
            '"' | '`' | '$' => {
                quoted.push_str("\\\\");
                quoted.push(character);
            }
            '%' => quoted.push_str("%%"),
            other => quoted.push(other),
        }
    }
    quoted.push('"');
    quoted
}

/// Hides the Dock icon while only the tray icon is left (macOS accessory activation).
#[cfg(target_os = "macos")]
pub fn set_dock_visible(visible: bool) {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::{AnyClass, AnyObject};
    let Some(app_class) = AnyClass::get(c"NSApplication") else { return };
    // NSApplicationActivationPolicyRegular = 0, Accessory = 1.
    let policy: isize = if visible { 0 } else { 1 };
    // SAFETY: a plain AppKit call on the main thread.
    unsafe {
        let app: Retained<AnyObject> = msg_send![app_class, sharedApplication];
        let _: bool = msg_send![&*app, setActivationPolicy: policy];
    }
}

#[cfg(not(target_os = "macos"))]
pub fn set_dock_visible(_visible: bool) {}

/// The app's accent as RGBA for the pinned title bar pin; 0 until the theme is applied.
static PIN_ACCENT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

#[cfg(target_os = "macos")]
thread_local! {
    static PIN_BUTTON: std::cell::RefCell<Option<objc2::rc::Retained<objc2::runtime::AnyObject>>> =
        const { std::cell::RefCell::new(None) };
}

/// The pin's color: the app's accent while pinned (the system accent before the theme is set),
/// secondary label color otherwise.
#[cfg(target_os = "macos")]
fn pin_tint(pinned: bool) -> Option<objc2::rc::Retained<objc2::runtime::AnyObject>> {
    use objc2::msg_send;
    let color_class = objc2::runtime::AnyClass::get(c"NSColor")?;
    let accent = PIN_ACCENT.load(std::sync::atomic::Ordering::Relaxed);
    let channel = |shift: u32| f64::from((accent >> shift) & 0xff) / 255.;
    // SAFETY: NSColor class constructors, on the main thread.
    unsafe {
        if !pinned {
            msg_send![color_class, secondaryLabelColor]
        } else if accent == 0 {
            msg_send![color_class, controlAccentColor]
        } else {
            msg_send![color_class, colorWithSRGBRed: channel(24), green: channel(16), blue: channel(8), alpha: channel(0)]
        }
    }
}

/// The pinned pin takes the app's accent; called whenever the theme or accent changes.
pub fn set_pin_accent(accent: Hsla) {
    let rgba = accent.to_rgb();
    let byte = |value: f32| (value.clamp(0., 1.) * 255.).round() as u32;
    let packed = (byte(rgba.r) << 24) | (byte(rgba.g) << 16) | (byte(rgba.b) << 8) | byte(rgba.a).max(1);
    PIN_ACCENT.store(packed, std::sync::atomic::Ordering::Relaxed);
    #[cfg(target_os = "macos")]
    PIN_BUTTON.with(|button| {
        let Some(button) = button.borrow().clone() else { return };
        use objc2::msg_send;
        // SAFETY: the retained pin button, on the main thread.
        unsafe {
            let state: isize = msg_send![&*button, state];
            if let Some(tint) = pin_tint(state != 0) {
                let _: () = msg_send![&*button, setContentTintColor: &*tint];
            }
        }
    });
}

/// The always-on-top pin at the title bar's trailing end (JVM title bar pin): toggles the window
/// between the normal and the floating level. Not kept across launches, like the JVM app.
#[cfg(target_os = "macos")]
pub fn install_pin_button(window: &Window, _cx: &App, log: &Logger) {
    use objc2::rc::Retained;
    use objc2::runtime::{AnyClass, AnyObject, ClassBuilder, NSObject, Sel};
    use objc2::{ClassType, msg_send, sel};
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};

    const PIN_SYMBOL: &str = "pin";
    const PINNED_SYMBOL: &str = "pin.fill";
    const NS_NORMAL_WINDOW_LEVEL: isize = 0;
    const NS_FLOATING_WINDOW_LEVEL: isize = 3;
    const PIN_WIDTH: f64 = 28.;
    const PIN_HEIGHT: f64 = 22.;
    const PIN_END_INSET: f64 = 6.;

    /// # Safety
    /// On the main thread, as every AppKit call.
    unsafe fn ns_string(text: &str) -> Option<Retained<AnyObject>> {
        let class = AnyClass::get(c"NSString")?;
        let text = std::ffi::CString::new(text).ok()?;
        // SAFETY: the caller's main thread; a NUL-terminated UTF-8 string that outlives the call.
        unsafe { msg_send![class, stringWithUTF8String: text.as_ptr()] }
    }

    /// # Safety
    /// On the main thread, with `button` a live NSButton.
    unsafe fn show_state(button: *mut AnyObject, pinned: bool) {
        let Some(image_class) = AnyClass::get(c"NSImage") else { return };
        let symbol = if pinned { PINNED_SYMBOL } else { PIN_SYMBOL };
        // SAFETY: the caller's contract; NSImage and NSButton messages with their declared types.
        unsafe {
            let (Some(name), Some(label)) = (ns_string(symbol), ns_string(t(Str::CdAlwaysOnTop))) else {
                return;
            };
            let image: Option<Retained<AnyObject>> =
                msg_send![image_class, imageWithSystemSymbolName: &*name, accessibilityDescription: &*label];
            if let Some(image) = image {
                let _: () = msg_send![button, setImage: &*image];
            }
            let _: () = msg_send![button, setState: isize::from(pinned)];
            if let Some(tint) = pin_tint(pinned) {
                let _: () = msg_send![button, setContentTintColor: &*tint];
            }
        }
    }

    unsafe extern "C-unwind" fn toggle_pin(_this: *mut AnyObject, _: Sel, button: *mut AnyObject) {
        // SAFETY: AppKit sends the action on the main thread with the live button that sent it.
        unsafe {
            let ns_window: *mut AnyObject = msg_send![button, window];
            if ns_window.is_null() {
                return;
            }
            // A push-on/push-off button has flipped its state before it sends the action.
            let state: isize = msg_send![button, state];
            let pinned = state != 0;
            let level = if pinned { NS_FLOATING_WINDOW_LEVEL } else { NS_NORMAL_WINDOW_LEVEL };
            let _: () = msg_send![ns_window, setLevel: level];
            show_state(button, pinned);
        }
    }

    static TARGET_CLASS: std::sync::OnceLock<Option<&'static AnyClass>> = std::sync::OnceLock::new();
    let target_class = TARGET_CLASS.get_or_init(|| {
        let mut builder = ClassBuilder::new(c"YMusicPinTarget", NSObject::class())?;
        // SAFETY: `togglePin:` takes the sending control and returns nothing, matching `toggle_pin`.
        unsafe {
            builder.add_method(
                sel!(togglePin:),
                toggle_pin as unsafe extern "C-unwind" fn(*mut AnyObject, Sel, *mut AnyObject),
            );
        }
        Some(builder.register())
    });
    let Ok(handle) = HasWindowHandle::window_handle(window) else { return };
    let RawWindowHandle::AppKit(appkit) = handle.as_raw() else { return };
    let view = appkit.ns_view.as_ptr().cast::<AnyObject>();
    let (Some(target_class), Some(button_class)) = (target_class, AnyClass::get(c"NSButton")) else {
        log.warn("always-on-top pin: AppKit classes missing", None);
        return;
    };
    // SAFETY: gpui's live NSView and its window, on the main thread.
    unsafe {
        let ns_window: Option<Retained<AnyObject>> = msg_send![view, window];
        let Some(ns_window) = ns_window else { return };
        // Hack: a titlebar accessory view controller collapses to zero height on gpui's window, so
        // the pin joins the traffic lights' superview, held to its right edge.
        let close: Option<Retained<AnyObject>> = msg_send![&*ns_window, standardWindowButton: 0usize];
        let titlebar: Option<Retained<AnyObject>> = close.and_then(|close| msg_send![&*close, superview]);
        let (Some(titlebar), Some(empty)) = (titlebar, ns_string("")) else {
            log.warn("always-on-top pin: no title bar", None);
            return;
        };
        let target: Retained<AnyObject> = msg_send![*target_class, new];
        let button: Retained<AnyObject> = msg_send![button_class, new];
        // NSButtonTypePushOnPushOff: VoiceOver reads it as a toggle with its state.
        let _: () = msg_send![&*button, setButtonType: 1usize];
        let _: () = msg_send![&*button, setBordered: false];
        let _: () = msg_send![&*button, setTitle: &*empty];
        if let Some(label) = ns_string(t(Str::CdAlwaysOnTop)) {
            let _: () = msg_send![&*button, setToolTip: &*label];
            let _: () = msg_send![&*button, setAccessibilityLabel: &*label];
        }
        show_state(Retained::as_ptr(&button).cast_mut(), false);
        let _: () = msg_send![&*button, setTarget: &*target];
        let _: () = msg_send![&*button, setAction: sel!(togglePin:)];
        let bounds: objc2_core_foundation::CGRect = msg_send![&*titlebar, bounds];
        let frame = objc2_core_foundation::CGRect::new(
            objc2_core_foundation::CGPoint::new(
                bounds.size.width - PIN_WIDTH - PIN_END_INSET,
                (bounds.size.height - PIN_HEIGHT) / 2.,
            ),
            objc2_core_foundation::CGSize::new(PIN_WIDTH, PIN_HEIGHT),
        );
        let _: () = msg_send![&*button, setFrame: frame];
        // NSViewMinXMargin: keeps the right inset as the window resizes.
        let _: () = msg_send![&*button, setAutoresizingMask: 1usize];
        let _: () = msg_send![&*titlebar, addSubview: &*button];
        PIN_BUTTON.with(|pin| *pin.borrow_mut() = Some(button.clone()));
        // Debug: `YMUSIC_DEV_PIN` pins the window at start, through the button's own action.
        if cfg!(debug_assertions) && std::env::var_os("YMUSIC_DEV_PIN").is_some() {
            let _: () = msg_send![&*button, performClick: std::ptr::null_mut::<AnyObject>()];
        }
        // A control's target is weak: the target lives as long as the window, the whole process.
        std::mem::forget(target);
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
pub fn install_pin_button(_window: &Window, _cx: &App, _log: &Logger) {}

/// Windows: the title bar pin (`win_title_bar`) and "Always on top" in the window's system menu
/// (Alt+Space, a right-click on the title bar) toggle the one state, the window's WS_EX_TOPMOST; a
/// toggle from the menu repaints the pin. Not kept across launches, like the JVM app.
#[cfg(target_os = "windows")]
pub fn install_pin_button(window: &Window, cx: &App, log: &Logger) {
    use futures::StreamExt;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::Shell::SetWindowSubclass;
    use windows::Win32::UI::WindowsAndMessaging::{AppendMenuW, GetSystemMenu, MF_SEPARATOR, MF_STRING};
    use windows::core::{HSTRING, PCWSTR};

    let Some(hwnd) = window_hwnd(window).map(HWND) else { return };
    let (changes, mut changed) = futures::channel::mpsc::unbounded();
    PIN.with(|pin| *pin.borrow_mut() = Some(PinState { log: log.clone(), changes }));
    window
        .spawn(cx, async move |cx| {
            while changed.next().await.is_some() {
                if cx.update(|window, _| window.refresh()).is_err() {
                    break;
                }
            }
        })
        .detach();
    // SAFETY: gpui's live HWND, on its thread; the label outlives the call that copies it.
    unsafe {
        let menu = GetSystemMenu(hwnd, false);
        if menu.is_invalid() {
            log.warn("always on top: the window has no system menu", None);
            return;
        }
        let label = HSTRING::from(t(Str::CdAlwaysOnTop));
        let appended = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null())
            .and_then(|()| AppendMenuW(menu, MF_STRING, PIN_COMMAND as usize, &label));
        if let Err(error) = appended {
            log.warn("always on top: the system menu item could not be added", Some(&error));
            return;
        }
        if !SetWindowSubclass(hwnd, Some(pin_subclass), PIN_SUBCLASS, 0).as_bool() {
            log.warn("always on top: the window could not be subclassed", None);
        }
    }
    // Debug: `YMUSIC_DEV_PIN` pins the window at start.
    if cfg!(debug_assertions) && std::env::var_os("YMUSIC_DEV_PIN").is_some() {
        apply_always_on_top(hwnd, true);
    }
}

#[cfg(target_os = "windows")]
struct PinState {
    log: Logger,
    changes: futures::channel::mpsc::UnboundedSender<()>,
}

#[cfg(target_os = "windows")]
thread_local! {
    static PIN: std::cell::RefCell<Option<PinState>> = const { std::cell::RefCell::new(None) };
}

/// A system command id: below 0xF000 with the low four bits clear, which Windows keeps for itself.
#[cfg(target_os = "windows")]
const PIN_COMMAND: u32 = 0x0110;
#[cfg(target_os = "windows")]
const PIN_SUBCLASS: usize = 1;

/// Whether the window floats above other apps' windows.
#[cfg(target_os = "windows")]
pub fn always_on_top(window: &Window) -> bool {
    window_hwnd(window).map(windows::Win32::Foundation::HWND).is_some_and(is_topmost)
}

#[cfg(target_os = "windows")]
fn is_topmost(hwnd: windows::Win32::Foundation::HWND) -> bool {
    use windows::Win32::UI::WindowsAndMessaging::{GWL_EXSTYLE, GetWindowLongPtrW, WS_EX_TOPMOST};
    // SAFETY: gpui's live HWND, on its thread.
    unsafe { GetWindowLongPtrW(hwnd, GWL_EXSTYLE) & WS_EX_TOPMOST.0 as isize != 0 }
}

#[cfg(target_os = "windows")]
pub fn set_always_on_top(window: &Window, on: bool) {
    if let Some(hwnd) = window_hwnd(window).map(windows::Win32::Foundation::HWND) {
        apply_always_on_top(hwnd, on);
    }
}

/// Moves the window in or out of the topmost band and checks the system menu item to match.
#[cfg(target_os = "windows")]
fn apply_always_on_top(hwnd: windows::Win32::Foundation::HWND, on: bool) {
    use windows::Win32::UI::WindowsAndMessaging::{
        CheckMenuItem, GetSystemMenu, HWND_NOTOPMOST, HWND_TOPMOST, MF_CHECKED, MF_UNCHECKED, SWP_NOACTIVATE,
        SWP_NOMOVE, SWP_NOSIZE, SetWindowPos,
    };
    let after = if on { HWND_TOPMOST } else { HWND_NOTOPMOST };
    // SAFETY: gpui's live HWND, on its thread.
    unsafe {
        if let Err(error) = SetWindowPos(hwnd, Some(after), 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE) {
            PIN.with(|pin| {
                if let Some(pin) = pin.borrow().as_ref() {
                    pin.log.warn("always on top: the window could not change band", Some(&error));
                }
            });
            return;
        }
        CheckMenuItem(GetSystemMenu(hwnd, false), PIN_COMMAND, if on { MF_CHECKED } else { MF_UNCHECKED }.0);
    }
}

#[cfg(target_os = "windows")]
unsafe extern "system" fn pin_subclass(
    hwnd: windows::Win32::Foundation::HWND,
    message: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
    subclass: usize,
    _data: usize,
) -> windows::Win32::Foundation::LRESULT {
    use windows::Win32::Foundation::LRESULT;
    use windows::Win32::UI::Shell::{DefSubclassProc, RemoveWindowSubclass};
    use windows::Win32::UI::WindowsAndMessaging::{WM_NCDESTROY, WM_SYSCOMMAND};
    if message == WM_SYSCOMMAND && (wparam.0 as u32 & 0xFFF0) == PIN_COMMAND {
        // A panic must not unwind into Windows: the panic hook has reported it, the click is lost.
        let _ = std::panic::catch_unwind(|| {
            apply_always_on_top(hwnd, !is_topmost(hwnd));
            PIN.with(|pin| {
                if let Some(pin) = pin.borrow().as_ref() {
                    // Fire-and-forget: the receiver lives as long as the window.
                    let _ = pin.changes.unbounded_send(());
                }
            });
        });
        return LRESULT(0);
    }
    if message == WM_NCDESTROY {
        // SAFETY: the window's last message, on its thread: this subclass comes off before it goes.
        // Fire-and-forget: the window is going away either way.
        let _ = unsafe { RemoveWindowSubclass(hwnd, Some(pin_subclass), subclass) };
    }
    // SAFETY: hands the window's message on, with the arguments Windows gave this subclass.
    unsafe { DefSubclassProc(hwnd, message, wparam, lparam) }
}

gpui_kit::actions!(ymusic_dock, [DockTogglePlay, DockStop]);

/// The Dock icon's menu (JVM `DockMenu`): Pause/Resume and Stop while there is a queue; the Dock
/// adds its own Quit. Its entries arrive as tray commands.
#[derive(Default)]
pub struct DockMenu {
    shown: Option<(bool, bool)>,
}

impl DockMenu {
    pub fn new(commands: futures::channel::mpsc::UnboundedSender<TrayCommand>, cx: &mut App) -> Self {
        let toggle = commands.clone();
        cx.on_action(move |_: &DockTogglePlay, _| {
            let _ = toggle.unbounded_send(TrayCommand::TogglePlay);
        });
        cx.on_action(move |_: &DockStop, _| {
            let _ = commands.unbounded_send(TrayCommand::Stop);
        });
        Self::default()
    }

    #[cfg(target_os = "macos")]
    pub fn update(&mut self, has_queue: bool, playing: bool, cx: &App) {
        if self.shown == Some((has_queue, playing)) {
            return;
        }
        self.shown = Some((has_queue, playing));
        let items = if has_queue {
            vec![
                MenuItem::action(t(if playing { Str::TrayPause } else { Str::TrayResume }), DockTogglePlay),
                MenuItem::action(t(Str::TrayStop), DockStop),
            ]
        } else {
            Vec::new()
        };
        cx.set_dock_menu(items);
    }

    /// Windows: the taskbar Jump List (gpui's dock menu tasks carry arguments this app does not take).
    #[cfg(target_os = "windows")]
    pub fn update(&mut self, has_queue: bool, playing: bool, cx: &App) {
        if self.shown == Some((has_queue, playing)) {
            return;
        }
        self.shown = Some((has_queue, playing));
        if let Some(jump_list) = cx.try_global::<crate::jump_list::JumpList>() {
            jump_list.update(has_queue, playing);
        }
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    pub fn update(&mut self, has_queue: bool, playing: bool, _cx: &App) {
        self.shown = Some((has_queue, playing));
    }
}

#[cfg(target_os = "macos")]
thread_local! {
    static MAIN_WINDOW: std::cell::RefCell<Option<objc2::rc::Retained<objc2::runtime::AnyObject>>> =
        const { std::cell::RefCell::new(None) };
}

/// Keeps the main window's NSWindow, so it can be hidden to the tray and shown again from places
/// that have no `Window` (the Dock's reopen, the tray popup) while other windows stay up.
#[cfg(target_os = "macos")]
pub fn remember_main_window(window: &Window) {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::AnyObject;
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    let Ok(handle) = HasWindowHandle::window_handle(window) else { return };
    let RawWindowHandle::AppKit(appkit) = handle.as_raw() else { return };
    let view = appkit.ns_view.as_ptr().cast::<AnyObject>();
    // SAFETY: gpui's live NSView, on the main thread.
    let ns_window: Option<Retained<AnyObject>> = unsafe { msg_send![view, window] };
    MAIN_WINDOW.with(|main| *main.borrow_mut() = ns_window);
}

#[cfg(target_os = "windows")]
thread_local! {
    static MAIN_HWND: std::cell::Cell<Option<usize>> = const { std::cell::Cell::new(None) };
}

/// The window's HWND, for Win32 calls gpui has no API for.
#[cfg(target_os = "windows")]
pub fn window_hwnd(window: &Window) -> Option<*mut std::ffi::c_void> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    let handle = HasWindowHandle::window_handle(window).ok()?;
    let RawWindowHandle::Win32(win32) = handle.as_raw() else { return None };
    Some(win32.hwnd.get() as *mut std::ffi::c_void)
}

/// Keeps the main window's HWND: gpui's app hide and activate do nothing on Windows.
#[cfg(target_os = "windows")]
pub fn remember_main_window(window: &Window) {
    MAIN_HWND.with(|main| main.set(window_hwnd(window).map(|hwnd| hwnd as usize)));
}

#[cfg(target_os = "windows")]
fn main_hwnd() -> Option<windows::Win32::Foundation::HWND> {
    MAIN_HWND.with(|main| main.get()).map(|hwnd| windows::Win32::Foundation::HWND(hwnd as *mut std::ffi::c_void))
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
pub fn remember_main_window(_window: &Window) {}

/// Takes the main window off screen. On macOS only that window goes (an app hide would take the
/// tray popup with it).
pub fn hide_main_window(cx: &mut App) {
    #[cfg(target_os = "macos")]
    {
        use objc2::msg_send;
        let hidden = MAIN_WINDOW.with(|main| {
            let main = main.borrow();
            let Some(ns_window) = main.as_ref() else { return false };
            // SAFETY: the retained main NSWindow, on the main thread.
            unsafe {
                let _: () = msg_send![&**ns_window, orderOut: std::ptr::null_mut::<objc2::runtime::AnyObject>()];
            }
            true
        });
        if hidden {
            return;
        }
    }
    #[cfg(target_os = "windows")]
    if let Some(hwnd) = main_hwnd() {
        use windows::Win32::UI::WindowsAndMessaging::{SW_HIDE, ShowWindow};
        // SAFETY: the main window's live HWND, on its thread.
        let _ = unsafe { ShowWindow(hwnd, SW_HIDE) };
        return;
    }
    cx.hide();
}

/// Brings the main window back after [`hide_main_window`]; the caller activates the app.
pub fn show_main_window() {
    #[cfg(target_os = "windows")]
    if let Some(hwnd) = main_hwnd() {
        use windows::Win32::UI::WindowsAndMessaging::{IsIconic, SW_RESTORE, SW_SHOW, SetForegroundWindow, ShowWindow};
        // SAFETY: the main window's live HWND, on its thread.
        unsafe {
            let _ = ShowWindow(hwnd, if IsIconic(hwnd).as_bool() { SW_RESTORE } else { SW_SHOW });
            let _ = SetForegroundWindow(hwnd);
        }
    }
    #[cfg(target_os = "macos")]
    MAIN_WINDOW.with(|main| {
        use objc2::msg_send;
        if let Some(ns_window) = main.borrow().as_ref() {
            // SAFETY: the retained main NSWindow, on the main thread.
            unsafe {
                let _: () =
                    msg_send![&**ns_window, makeKeyAndOrderFront: std::ptr::null_mut::<objc2::runtime::AnyObject>()];
            }
        }
    });
}

/// What a tray click or menu entry asks for.
pub enum TrayCommand {
    /// Toggle the mini player popup at the icon's logical screen bounds.
    #[cfg_attr(target_os = "linux", allow(dead_code))]
    Popup(Bounds<Pixels>),
    Open,
    TogglePlay,
    Stop,
    Quit,
    /// The tray host went away: a window hidden to it has no way back but this.
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    Lost,
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
pub struct Tray {
    icon: std::mem::ManuallyDrop<tray_icon::TrayIcon>,
    open: tray_icon::menu::MenuItem,
    toggle: tray_icon::menu::MenuItem,
    stop: tray_icon::menu::MenuItem,
    quit: tray_icon::menu::MenuItem,
    playing: Option<bool>,
    visible: bool,
    /// The side the icon was drawn at, in pixels.
    #[cfg(target_os = "windows")]
    side: u32,
    /// The status item's NSImage: the logo at [`TRAY_POINTS`], a representation per scale.
    #[cfg(target_os = "macos")]
    image: objc2::rc::Retained<objc2::runtime::AnyObject>,
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
impl Drop for Tray {
    fn drop(&mut self) {
        // Hidden on Windows, the icon is already out of the notification area; tray-icon's drop would
        // delete it again and print an error. Its hidden window goes with the process.
        if cfg!(target_os = "windows") && !self.visible {
            return;
        }
        // SAFETY: dropped once, here, and never used again.
        unsafe { std::mem::ManuallyDrop::drop(&mut self.icon) };
    }
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
impl Tray {
    /// A hidden tray icon whose commands arrive on the returned channel.
    pub fn new(
        _tokio: &tokio::runtime::Handle,
        _log: Logger,
    ) -> anyhow::Result<(Self, futures::channel::mpsc::UnboundedReceiver<TrayCommand>)> {
        use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
        use tray_icon::{TrayIconBuilder, TrayIconEvent};
        #[cfg(target_os = "windows")]
        let side = tray_icon_side()?;
        #[cfg(target_os = "macos")]
        let (side, image) = ((TRAY_POINTS * 2.) as u32, status_item_image()?);
        let icon = tray_icon_raster(side)?;
        let open = MenuItem::new(t(Str::CdOpenApp), true, None);
        let toggle = MenuItem::new(t(Str::TrayPause), true, None);
        let stop = MenuItem::new(t(Str::TrayStop), true, None);
        let quit = MenuItem::new(t(Str::TrayQuit), true, None);
        let menu = Menu::new();
        menu.append_items(&[
            &open,
            &PredefinedMenuItem::separator(),
            &toggle,
            &stop,
            &PredefinedMenuItem::separator(),
            &quit,
        ])?;
        let tray = TrayIconBuilder::new()
            .with_icon(icon)
            .with_tooltip(t(Str::AppName))
            .with_menu(Box::new(menu))
            .with_menu_on_left_click(false)
            .build()?;
        tray.set_visible(false)?;
        let (sender, receiver) = futures::channel::mpsc::unbounded();
        let ids = [(open.id().clone(), 0u8), (toggle.id().clone(), 1), (stop.id().clone(), 2), (quit.id().clone(), 3)];
        let menu_sender = sender.clone();
        MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
            let command = match ids.iter().find(|(id, _)| *id == event.id).map(|(_, which)| *which) {
                Some(0) => TrayCommand::Open,
                Some(1) => TrayCommand::TogglePlay,
                Some(2) => TrayCommand::Stop,
                Some(3) => TrayCommand::Quit,
                _ => return,
            };
            let _ = menu_sender.unbounded_send(command);
        }));
        // A click opens the mini player under the icon, a right-click the menu; a double-click on
        // Windows opens the window as well.
        TrayIconEvent::set_event_handler(Some(move |event: TrayIconEvent| match event {
            TrayIconEvent::Click {
                button: tray_icon::MouseButton::Left,
                button_state: tray_icon::MouseButtonState::Up,
                rect,
                ..
            } => {
                let scale = tray_scale_factor() as f64;
                let anchor = bounds(
                    point(px((rect.position.x / scale) as f32), px((rect.position.y / scale) as f32)),
                    size(px((rect.size.width as f64 / scale) as f32), px((rect.size.height as f64 / scale) as f32)),
                );
                let _ = sender.unbounded_send(TrayCommand::Popup(anchor));
            }
            TrayIconEvent::DoubleClick { .. } => {
                let _ = sender.unbounded_send(TrayCommand::Open);
            }
            _ => {}
        }));
        let tray = Self {
            icon: std::mem::ManuallyDrop::new(tray),
            open,
            toggle,
            stop,
            quit,
            playing: None,
            visible: false,
            #[cfg(target_os = "windows")]
            side,
            #[cfg(target_os = "macos")]
            image,
        };
        Ok((tray, receiver))
    }

    /// Shown while there is a queue to control or the window is hidden (the only way back then).
    /// Each time it appears it is drawn for the display as it is then, as the JVM app's.
    pub fn update(&mut self, has_queue: bool, playing: bool, window_hidden: bool) -> anyhow::Result<()> {
        let visible = has_queue || window_hidden;
        let appeared = visible && !self.visible;
        self.icon.set_visible(visible)?;
        self.visible = visible;
        self.toggle.set_enabled(has_queue);
        self.stop.set_enabled(has_queue);
        self.open.set_enabled(true);
        self.quit.set_enabled(true);
        if self.playing != Some(playing) {
            self.toggle.set_text(t(if playing { Str::TrayPause } else { Str::TrayResume }));
            self.playing = Some(playing);
        }
        if appeared {
            self.draw_icon()?;
        }
        Ok(())
    }

    /// Whether a window hidden now could be brought back from the tray.
    pub fn available(&self) -> bool {
        true
    }

    /// Redraws the icon when the primary display's DPI changed since it was drawn.
    #[cfg(target_os = "windows")]
    fn draw_icon(&mut self) -> anyhow::Result<()> {
        let side = tray_icon_side()?;
        if side != self.side {
            self.icon.set_icon(Some(tray_icon_raster(side)?))?;
            self.side = side;
        }
        Ok(())
    }

    /// tray-icon draws every icon 18 pt tall from one raster, the JVM app's is 20 pt with a raster
    /// per scale; tray-icon makes the status item anew, with its own image, each time it is shown.
    #[cfg(target_os = "macos")]
    fn draw_icon(&mut self) -> anyhow::Result<()> {
        use objc2::msg_send;
        use objc2::rc::Retained;
        use objc2::runtime::AnyObject;
        let Some(item) = self.icon.ns_status_item() else { return Ok(()) };
        // SAFETY: AppKit calls on the main thread, with the live status item and image.
        unsafe {
            let button: Option<Retained<AnyObject>> = msg_send![&*item, button];
            if let Some(button) = button {
                let _: () = msg_send![&*button, setImage: &*self.image];
            }
        }
        // Hack: tray-icon fits its click target to the button again only in its own setters.
        self.icon.set_tooltip(Some(t(Str::AppName)))?;
        Ok(())
    }
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
fn tray_icon_raster(side: u32) -> anyhow::Result<tray_icon::Icon> {
    Ok(tray_icon::Icon::from_rgba(tray_logo(side)?.into_raw(), side, side)?)
}

/// The notification area draws an icon at the small icon size for the primary display's DPI; the
/// JVM app's was 16 px scaled for that display, the same.
#[cfg(target_os = "windows")]
fn tray_icon_side() -> windows::core::Result<u32> {
    use windows::Win32::Foundation::POINT;
    use windows::Win32::Graphics::Gdi::{MONITOR_DEFAULTTOPRIMARY, MonitorFromPoint};
    use windows::Win32::UI::HiDpi::{GetDpiForMonitor, GetSystemMetricsForDpi, MDT_EFFECTIVE_DPI};
    use windows::Win32::UI::WindowsAndMessaging::SM_CXSMICON;
    let (mut dpi, mut vertical_dpi) = (0, 0);
    // SAFETY: plain Win32 queries; the out-pointers are live locals.
    unsafe {
        let primary = MonitorFromPoint(POINT::default(), MONITOR_DEFAULTTOPRIMARY);
        GetDpiForMonitor(primary, MDT_EFFECTIVE_DPI, &mut dpi, &mut vertical_dpi)?;
        Ok(GetSystemMetricsForDpi(SM_CXSMICON, dpi).max(16) as u32)
    }
}

/// The status item's image: the logo at [`TRAY_POINTS`] with a representation per scale, as AWT
/// builds it from the JVM app's multi-resolution image.
#[cfg(target_os = "macos")]
fn status_item_image() -> anyhow::Result<objc2::rc::Retained<objc2::runtime::AnyObject>> {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::{AnyClass, AnyObject};
    use objc2_core_foundation::CGSize;
    let (Some(image_class), Some(rep_class), Some(data_class)) =
        (AnyClass::get(c"NSImage"), AnyClass::get(c"NSBitmapImageRep"), AnyClass::get(c"NSData"))
    else {
        anyhow::bail!("tray icon: AppKit classes missing");
    };
    let points = CGSize { width: TRAY_POINTS.into(), height: TRAY_POINTS.into() };
    // SAFETY: plain AppKit calls on the main thread; `init` hands back the one reference `alloc` made.
    unsafe {
        let allocated: *mut AnyObject = msg_send![image_class, alloc];
        let image: *mut AnyObject = msg_send![allocated, initWithSize: points];
        let image = Retained::from_raw(image).ok_or_else(|| anyhow::anyhow!("tray icon: no NSImage"))?;
        for scale in TRAY_SCALES {
            let side = (TRAY_POINTS * scale).ceil() as u32;
            let mut png = Vec::new();
            tray_logo(side)?.write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)?;
            let data: Retained<AnyObject> =
                msg_send![data_class, dataWithBytes: png.as_ptr().cast::<std::ffi::c_void>(), length: png.len()];
            let representation: Option<Retained<AnyObject>> = msg_send![rep_class, imageRepWithData: &*data];
            let representation =
                representation.ok_or_else(|| anyhow::anyhow!("tray icon: the {side} px raster did not decode"))?;
            let _: () = msg_send![&*representation, setSize: points];
            let _: () = msg_send![&*image, addRepresentation: &*representation];
        }
        Ok(image)
    }
}

#[cfg(target_os = "linux")]
#[derive(Clone, Copy, Default)]
struct TrayState {
    has_queue: bool,
    playing: bool,
    window_hidden: bool,
}
/// The backing scale of the screen under the pointer: tray-icon reports physical pixels. Called
/// from the tray's event handler, on the main thread.
#[cfg(target_os = "macos")]
fn tray_scale_factor() -> f32 {
    use objc2::msg_send;
    use objc2::rc::Retained;
    use objc2::runtime::{AnyClass, AnyObject};
    use objc2_core_foundation::{CGPoint, CGRect};
    let (Some(event_class), Some(screen_class)) = (AnyClass::get(c"NSEvent"), AnyClass::get(c"NSScreen")) else {
        return 1.;
    };
    // SAFETY: AppKit queries on the main thread.
    unsafe {
        let mouse: CGPoint = msg_send![event_class, mouseLocation];
        let screens: Retained<AnyObject> = msg_send![screen_class, screens];
        let count: usize = msg_send![&*screens, count];
        for index in 0..count {
            let screen: *mut AnyObject = msg_send![&*screens, objectAtIndex: index];
            let frame: CGRect = msg_send![screen, frame];
            let inside = mouse.x >= frame.origin.x
                && mouse.x <= frame.origin.x + frame.size.width
                && mouse.y >= frame.origin.y
                && mouse.y <= frame.origin.y + frame.size.height;
            if inside {
                let scale: f64 = msg_send![screen, backingScaleFactor];
                return scale as f32;
            }
        }
    }
    1.
}

#[cfg(target_os = "windows")]
fn tray_scale_factor() -> f32 {
    // SAFETY: a plain Win32 query without arguments.
    unsafe { windows::Win32::UI::HiDpi::GetDpiForSystem() as f32 / 96. }
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
pub struct Tray;

/// The StatusNotifierItem; its properties read the shared state, so a poke re-reads the newest.
#[cfg(target_os = "linux")]
struct SniTray {
    state: std::sync::Arc<std::sync::Mutex<TrayState>>,
    commands: futures::channel::mpsc::UnboundedSender<TrayCommand>,
    available: std::sync::Arc<std::sync::atomic::AtomicBool>,
    icons: Vec<ksni::Icon>,
}

#[cfg(target_os = "linux")]
impl SniTray {
    fn state(&self) -> TrayState {
        self.state.lock().map(|state| *state).unwrap_or_default()
    }

    fn send(&self, command: TrayCommand) {
        let _ = self.commands.unbounded_send(command);
    }
}

#[cfg(target_os = "linux")]
impl ksni::Tray for SniTray {
    fn id(&self) -> String {
        "ymusic".into()
    }

    fn title(&self) -> String {
        t(Str::AppName).into()
    }

    fn status(&self) -> ksni::Status {
        let state = self.state();
        if state.has_queue || state.window_hidden { ksni::Status::Active } else { ksni::Status::Passive }
    }

    fn icon_pixmap(&self) -> Vec<ksni::Icon> {
        self.icons.clone()
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip { title: t(Str::AppName).into(), ..Default::default() }
    }

    fn activate(&mut self, _x: i32, _y: i32) {
        self.send(TrayCommand::Open);
    }

    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        use ksni::menu::{MenuItem, StandardItem};
        let state = self.state();
        let item = |label: Str, enabled: bool, command: fn() -> TrayCommand| -> MenuItem<Self> {
            StandardItem {
                label: t(label).into(),
                enabled,
                activate: Box::new(move |tray: &mut Self| tray.send(command())),
                ..Default::default()
            }
            .into()
        };
        vec![
            item(Str::CdOpenApp, true, || TrayCommand::Open),
            MenuItem::Separator,
            item(if state.playing { Str::TrayPause } else { Str::TrayResume }, state.has_queue, || {
                TrayCommand::TogglePlay
            }),
            item(Str::TrayStop, state.has_queue, || TrayCommand::Stop),
            MenuItem::Separator,
            item(Str::TrayQuit, true, || TrayCommand::Quit),
        ]
    }

    fn watcher_online(&self) {
        self.available.store(true, std::sync::atomic::Ordering::Relaxed);
    }

    fn watcher_offline(&self, _reason: ksni::OfflineReason) -> bool {
        self.available.store(false, std::sync::atomic::Ordering::Relaxed);
        self.send(TrayCommand::Lost);
        true
    }
}

/// A StatusNotifierItem over D-Bus (GNOME needs the AppIndicator extension, KDE has it built in).
#[cfg(target_os = "linux")]
pub struct Tray {
    state: std::sync::Arc<std::sync::Mutex<TrayState>>,
    handle: std::sync::Arc<std::sync::Mutex<Option<ksni::Handle<SniTray>>>>,
    available: std::sync::Arc<std::sync::atomic::AtomicBool>,
    tokio: tokio::runtime::Handle,
}

#[cfg(target_os = "linux")]
impl Tray {
    /// Registers with the StatusNotifierWatcher in the background; until that succeeds the tray is
    /// not [`available`](Self::available). Commands arrive on the returned channel.
    pub fn new(
        tokio: &tokio::runtime::Handle,
        log: Logger,
    ) -> anyhow::Result<(Self, futures::channel::mpsc::UnboundedReceiver<TrayCommand>)> {
        use ksni::TrayMethods;
        let mut sides: Vec<u32> = TRAY_LINUX_BASES
            .iter()
            .flat_map(|base| TRAY_SCALES.iter().map(move |scale| (base * scale).ceil() as u32))
            .collect();
        sides.sort_unstable();
        sides.dedup();
        let mut icons = Vec::with_capacity(sides.len());
        for side in sides {
            let logo = tray_logo(side)?;
            let argb = logo.pixels().flat_map(|pixel| {
                let [red, green, blue, alpha] = pixel.0;
                [alpha, red, green, blue]
            });
            icons.push(ksni::Icon { width: side as i32, height: side as i32, data: argb.collect() });
        }
        let (sender, receiver) = futures::channel::mpsc::unbounded();
        let state = std::sync::Arc::new(std::sync::Mutex::new(TrayState::default()));
        let available = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let handle = std::sync::Arc::new(std::sync::Mutex::new(None));
        let sni = SniTray { state: state.clone(), commands: sender, available: available.clone(), icons };
        let (slot, registered) = (handle.clone(), available.clone());
        tokio.spawn(async move {
            match sni.spawn().await {
                Ok(spawned) => {
                    if let Ok(mut slot) = slot.lock() {
                        *slot = Some(spawned);
                    }
                    registered.store(true, std::sync::atomic::Ordering::Relaxed);
                }
                Err(error) => log.warn("tray icon could not be registered", Some(&error)),
            }
        });
        Ok((Self { state, handle, available, tokio: tokio.clone() }, receiver))
    }

    /// Shown while there is a queue to control or the window is hidden (the only way back then).
    pub fn update(&mut self, has_queue: bool, playing: bool, window_hidden: bool) -> anyhow::Result<()> {
        if let Ok(mut state) = self.state.lock() {
            *state = TrayState { has_queue, playing, window_hidden };
        }
        let handle = self.handle.lock().ok().and_then(|handle| handle.clone());
        if let Some(handle) = handle {
            self.tokio.spawn(async move { handle.update(|_| ()).await });
        }
        Ok(())
    }

    /// Whether a window hidden now could be brought back from the tray.
    pub fn available(&self) -> bool {
        self.available.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// GNOME on Wayland leaves decorations to the app: a client-decorated window draws its own title
/// bar, border and resize edges. No drop shadow: its blur does not fade out on every GPU (a hard
/// dark band around the window on the NVIDIA box), and the margin it needs is dead space.
#[cfg(target_os = "linux")]
pub fn frame(content: AnyElement, window: &mut Window, cx: &mut App) -> AnyElement {
    use gpui_kit::component::{TitleBar, v_flex, window_border};
    if !matches!(window.window_decorations(), Decorations::Client { .. }) {
        return content;
    }
    let theme = gpui_kit::component::ActiveTheme::theme(cx).clone();
    // Centered on the whole bar as GNOME does: the left inset balances the three window buttons.
    let title = div()
        .flex_1()
        .flex()
        .justify_center()
        .pl(px(90.))
        .text_sm()
        .font_weight(FontWeight::BOLD)
        .child(t(Str::AppName));
    let bar = TitleBar::new()
        .bg(theme.title_bar)
        .border_color(theme.border)
        .on_close_window(|_, window, cx| close_from_title_bar(window, cx))
        .child(title);
    let content = v_flex().size_full().bg(theme.background).child(bar).child(div().flex_1().min_h_0().child(content));
    window_border().shadow_size(px(0.)).child(content).into_any_element()
}

#[cfg(target_os = "windows")]
pub fn frame(content: AnyElement, window: &mut Window, cx: &mut App) -> AnyElement {
    crate::win_title_bar::frame(content, window, cx)
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
pub fn frame(content: AnyElement, _: &mut Window, _: &mut App) -> AnyElement {
    content
}

/// The title bar's close button behaves as the window manager's would.
#[cfg(target_os = "linux")]
fn close_from_title_bar(window: &mut Window, cx: &mut App) {
    match cx.try_global::<crate::shell::ShellHandle>().and_then(|handle| handle.0.upgrade()) {
        Some(shell) => {
            shell.update(cx, |shell, cx| shell.close_requested(window, cx));
        }
        None => cx.quit(),
    }
}

/// The OS version as the JVM app reports it (`os.version`): the product version on macOS, the
/// kernel release on Linux.
#[cfg(target_os = "macos")]
pub fn os_version() -> String {
    let mut buffer = [0u8; 64];
    let mut length = buffer.len();
    // SAFETY: the name is NUL-terminated and `length` is the buffer's size; sysctl writes at most that.
    let status = unsafe {
        libc::sysctlbyname(
            c"kern.osproductversion".as_ptr(),
            buffer.as_mut_ptr().cast(),
            &mut length,
            std::ptr::null_mut(),
            0,
        )
    };
    if status != 0 {
        return String::new();
    }
    String::from_utf8_lossy(&buffer[..length]).trim_end_matches('\0').to_owned()
}

#[cfg(target_os = "linux")]
pub fn os_version() -> String {
    // SAFETY: uname fills a zeroed utsname whose fields are NUL-terminated.
    unsafe {
        let mut name: libc::utsname = std::mem::zeroed();
        if libc::uname(&mut name) != 0 {
            return String::new();
        }
        std::ffi::CStr::from_ptr(name.release.as_ptr()).to_string_lossy().into_owned()
    }
}

/// Hack: the JVM reports "10.0" on Windows 10 and 11 alike, and the Rust toolchain needs 10 or later.
#[cfg(target_os = "windows")]
pub fn os_version() -> String {
    "10.0".to_owned()
}

/// rfd's portal dialogs talk D-Bus through zbus, which runs on Tokio here (a dependency enables its
/// tokio feature), so the dialog is driven on the runtime; polled anywhere else it panics.
#[cfg(target_os = "linux")]
pub fn file_dialog<T: Send + 'static, F: Future<Output = Option<T>> + Send + 'static>(
    dialog: F,
    cx: &App,
) -> impl Future<Output = Option<T>> + use<T, F> {
    let graph = cx.global::<crate::graph::AppGraph>();
    let (task, log) = (graph.tokio.spawn(dialog), graph.log.fork("file-dialog"));
    async move {
        match task.await {
            Ok(picked) => picked,
            Err(error) => {
                log.warn("R90040 the file dialog task failed", Some(&error));
                None
            }
        }
    }
}

#[cfg(not(target_os = "linux"))]
pub fn file_dialog<T, F: Future<Output = Option<T>> + 'static>(dialog: F, _: &App) -> F {
    dialog
}
