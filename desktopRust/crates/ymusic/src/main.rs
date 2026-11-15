// Release builds launch without a console window; debug runs keep theirs for the log.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod a11y;
mod accent;
mod account_view;
mod action_icon;
mod app_update;
mod assets;
mod audio_effects;
mod audio_only;
mod caption_style;
mod cookie_guide;
mod cookie_lock;
mod cookie_session;
mod data_files;
mod desktop;
mod download_dialog;
mod downloads;
mod downloads_view;
mod elevated_dialog;
mod equalizer;
mod feed;
mod feedback_view;
mod file_rename;
mod fps_badge;
mod graph;
mod http_client;
mod instance;
#[cfg(target_os = "windows")]
mod jump_list;
mod launch;
mod library;
mod library_playlist;
mod library_search;
mod local_art;
mod local_engine;
mod local_playlist;
mod media_session;
mod mini_player;
mod now_playing;
mod offline;
mod play_again;
mod play_history;
mod playback;
mod player_cache;
mod playlist_download;
mod playlist_transfer;
mod queue_nav;
mod quick_download;
mod reorder;
mod search_filter;
mod search_view;
mod settings_lists;
mod settings_view;
mod shell;
mod shortcuts;
mod shortcuts_help;
mod signin;
mod signin_dialog;
mod sleep_timer;
mod speed_pitch;
mod spinner;
mod sponsor_block;
mod tab_row;
mod telemetry;
mod tray_popup;
mod video_details;
mod video_layer;
#[cfg(target_os = "linux")]
mod video_surface;
#[cfg(target_os = "windows")]
mod win_title_bar;
#[cfg(target_os = "windows")]
mod win_video_layer;
mod youtube_session;
mod yt_playlist;

use gpui_kit::*;
use ym_common::http::HttpClients;
use ym_i18n::{Str, t};

use crate::cookie_session::{CookieOpen, LockedJar};
use crate::graph::AppGraph;
use crate::http_client::ReqwestHttpClient;
use crate::launch::{BootInputs, Launch};

enum Start {
    Ready(Box<AppGraph>),
    Locked(std::sync::Arc<LockedJar>, BootInputs),
}

/// A launch that cannot boot ends here, its error captured.
pub fn boot_failed(log: &ym_common::log::Logger, error: &ym_engine::EngineError) -> ! {
    log.capture_error("engine boot failed", error);
    ym_common::log::stderr_line(format_args!("{}", crate::feed::error_text(error)));
    std::process::exit(1);
}

/// Debug builds' data dir as a launch argument, ahead of `YMUSIC_DATA_DIR`: a Jump List task is a
/// shell link, which sets no environment.
pub const DEV_DATA_DIR_ARG: &str = "--data-dir=";

fn main() {
    ym_i18n::use_system_locale();
    // Debug runs can be pointed at a throwaway data dir for automated checks.
    let data_dir = if cfg!(debug_assertions) {
        std::env::args_os()
            .skip(1)
            .find_map(|arg| arg.to_str()?.strip_prefix(DEV_DATA_DIR_ARG).map(std::path::PathBuf::from))
            .or_else(|| std::env::var_os("YMUSIC_DATA_DIR").map(std::path::PathBuf::from))
            .unwrap_or_else(|| dirs::data_local_dir().unwrap_or_else(std::env::temp_dir).join("YMusicRust"))
    } else {
        crate::data_files::release_data_dir()
    };
    let log_setup = crate::telemetry::logger(&data_dir);
    let log = log_setup.log.clone();
    // Before any window or Jump List: they take the process's ID from then on.
    #[cfg(target_os = "windows")]
    crate::desktop::claim_app_user_model_id(&log.fork("app-id"));
    let launch = crate::instance::Request::from_args(std::env::args_os().skip(1));
    let (inbox_sender, inbox) = futures::channel::mpsc::unbounded();
    let instance_log = log.fork("instance");
    match crate::instance::claim(&data_dir, &launch, inbox_sender.clone(), &instance_log) {
        Ok(crate::instance::Claim::HandedOver) => return,
        // A player task (Jump List) with no app running has nothing to control.
        Ok(crate::instance::Claim::Owner) if launch.command != crate::instance::Command::Show => return,
        Ok(crate::instance::Claim::Owner) => {}
        Err(error @ crate::instance::ClaimError::NoAnswer(_)) => {
            instance_log.capture_error("another instance holds the lock but did not answer", &error);
            std::process::exit(1);
        }
        Err(error) => instance_log.warn("single-instance setup failed, starting without it", Some(&error)),
    }
    crate::data_files::adopt_jvm_names(&data_dir, &log);
    if !launch.is_empty() {
        let _ = inbox_sender.unbounded_send(launch);
    }
    let tokio =
        tokio::runtime::Builder::new_multi_thread().enable_all().thread_name("ym-core").build().expect("tokio runtime");
    let http = HttpClients::new().expect("HTTP client");
    let local_art = std::sync::Arc::new(crate::local_art::LocalArt::new(data_dir.join("art-cache"), log.fork("art")));
    let app_db = graph::open_app_db(&data_dir).unwrap_or_else(|error| boot_failed(&log, &error));
    crate::data_files::import_legacy_files(&app_db, &data_dir, &log);
    let reporting =
        crate::telemetry::start_reporting(&log_setup, app_db.as_ref(), tokio.handle(), http.following.clone());
    let upgrade_notice = crate::app_update::UpgradeNotice::read(&app_db);
    let opened = crate::cookie_session::open(app_db.clone(), &data_dir, &log).unwrap_or_else(|error| {
        boot_failed(&log, &ym_engine::EngineError::site("R98000", format!("cookie store: {error}")))
    });
    let window_prefs = data_dir.join(crate::desktop::WINDOW_PREFS_FILE);
    #[cfg(target_os = "windows")]
    let jump_list_dir = data_dir.clone();
    crate::data_files::adopt_jvm_settings(&app_db, &log);
    crate::desktop::adopt_saved_bounds(&app_db, &window_prefs, &log.fork("window"));
    let saved_window = crate::desktop::load_window_prefs(&window_prefs);
    crate::settings_view::apply_language(app_db.as_ref());
    let inputs = crate::launch::BootInputs {
        tokio: tokio.handle().clone(),
        log: log.clone(),
        http: http.clone(),
        data_dir,
        app_db: app_db.clone(),
    };
    let app_logs = crate::telemetry::AppLogs(log_setup.logs.clone());
    let start = match opened {
        CookieOpen::Ready(session) => {
            let graph = tokio.block_on(inputs.boot(session)).unwrap_or_else(|error| boot_failed(&log, &error));
            reporting.follow(&graph);
            Start::Ready(Box::new(graph))
        }
        CookieOpen::Locked(locked) => {
            log.info("cookie jar is password-sealed: waiting for the unlock screen");
            Start::Locked(locked, inputs)
        }
    };
    let images = ReqwestHttpClient::new(http.following.clone(), tokio.handle().clone());
    #[cfg(target_os = "linux")]
    let tokio_handle = tokio.handle().clone();

    crate::a11y::install_debug_log();
    let assets = crate::assets::AppAssets { local_art: local_art.clone() };
    let app = application().with_assets(assets).with_http_client(images);
    // Opening the app again (Finder, Spotlight, the Dock) brings a window closed to the tray back.
    app.on_reopen(|cx| {
        if let Some(shell) = cx.try_global::<crate::shell::ShellHandle>().and_then(|handle| handle.0.upgrade()) {
            shell.update(cx, |shell, cx| shell.show_window(cx));
        }
    });
    // macOS delivers ymusic:// links and Finder's Open With as open-URL events, a cold launch's
    // included, before the window exists.
    app.on_open_urls(move |urls| {
        let _ = inbox_sender.unbounded_send(crate::instance::Request::from_urls(urls));
    });
    app.run(move |cx| {
        init(cx);
        cx.set_global(crate::instance::Inbox(inbox));
        cx.on_app_quit(|_| {
            crate::instance::stop_accepting();
            async {}
        })
        .detach();
        cx.set_global(crate::local_art::SharedLocalArt(local_art));
        crate::shortcuts::bind(cx);
        crate::a11y::init(cx);
        crate::reorder::SystemMotion::init(cx);
        #[cfg(target_os = "linux")]
        crate::reorder::SystemMotion::follow_portal(log.fork("motion"), cx);
        // No enter/exit animations anywhere: dialogs, popovers, menus and tabs change in one frame.
        cx.set_reduce_motion(true);
        // A guest's cache is fire-and-forget at quit: nothing else reads it.
        cx.on_app_quit(|cx| {
            if let Some(dir) = cx.try_global::<AppGraph>().and_then(|graph| graph.guest_cache.clone()) {
                let _ = std::fs::remove_dir_all(dir);
            }
            async {}
        })
        .detach();
        cx.on_app_quit(|cx| {
            if let Some(graph) = cx.try_global::<AppGraph>() {
                graph.youtube.clone().close_for_quit(&graph.tokio);
            }
            async {}
        })
        .detach();
        crate::settings_view::apply_saved_theme(app_db.as_ref(), cx);
        cx.set_global(crate::desktop::WindowPrefs(window_prefs.clone()));
        crate::desktop::set_dock_icon(&log);
        #[cfg(target_os = "windows")]
        crate::jump_list::JumpList::install(&jump_list_dir, log.fork("jump-list"), cx);
        #[cfg(all(target_os = "windows", debug_assertions))]
        crate::desktop::sync_dev_start_menu_entry(jump_list_dir.clone(), log.fork("start-menu"));
        #[cfg(target_os = "linux")]
        {
            let log = log.fork("desktop-entry");
            tokio_handle.spawn_blocking(move || {
                if let Err(error) = crate::desktop::sync_desktop_entry() {
                    log.warn("desktop entry install failed", Some(error.as_ref()));
                }
            });
        }
        let window_icon = crate::desktop::window_icon(&log);
        let window_bounds = match dev_window_size() {
            Some(dev) => WindowBounds::centered(dev, cx),
            None => crate::desktop::initial_bounds(saved_window, size(px(1180.), px(780.)), cx),
        };
        cx.set_global(reporting);
        let locked = match start {
            Start::Ready(graph) => {
                cx.set_global(*graph);
                None
            }
            Start::Locked(locked, inputs) => Some((locked, inputs)),
        };
        cx.set_global(app_logs);
        cx.set_global(upgrade_notice);
        let options = WindowOptions {
            window_bounds: Some(window_bounds),
            // Windows hides the native caption: the app draws its title bar (`win_title_bar`).
            titlebar: Some(TitlebarOptions {
                title: Some(t(Str::AppName).into()),
                appears_transparent: cfg!(target_os = "windows"),
                ..Default::default()
            }),
            // Debug dev-route runs float, so a window launched from a terminal still draws frames
            // (a covered window gets none on macOS and Wayland) for screenshots.
            kind: if cfg!(debug_assertions)
                && !cfg!(target_os = "windows")
                && std::env::var_os("YMUSIC_DEV_ROUTE").is_some()
                && std::env::var_os("YMUSIC_DEV_A11Y_DUMP").is_none()
            {
                WindowKind::PopUp
            } else {
                WindowKind::Normal
            },
            // The video sits behind gpui's view (Windows: a DirectComposition underlay), so Root
            // stays unpainted and the Shell paints everything but the video box. Windows stays
            // opaque, as its transparent mode (an accent policy) would blacken the native caption;
            // gpui clears to transparent there while the underlay shows.
            window_background: if cfg!(target_os = "windows") {
                WindowBackgroundAppearance::Opaque
            } else {
                WindowBackgroundAppearance::Transparent
            },
            app_id: Some(crate::desktop::APP_ID.into()),
            icon: window_icon,
            ..Default::default()
        };
        cx.open_window(options, |window, cx| {
            crate::desktop::install_pin_button(window, cx, &log.fork("pin"));
            crate::desktop::remember_main_window(window);
            let launch = cx.new(|cx| match locked {
                Some((locked, inputs)) => Launch::locked(locked, inputs, window, cx),
                None => Launch::shell(window, cx),
            });
            cx.new(|cx| gpui_kit::base::Root::new(launch, window, cx).bg(transparent_black()))
        })
        .expect("open main window");
        #[cfg(target_os = "linux")]
        crate::video_surface::follow_render_device(log.fork("video"));
        cx.activate(true);
    });
    drop(tokio);
}

/// Debug: `YMUSIC_DEV_WINDOW=WxH` opens the window at that size, for checking breakpoints.
fn dev_window_size() -> Option<Size<Pixels>> {
    if !cfg!(debug_assertions) {
        return None;
    }
    let spec = std::env::var("YMUSIC_DEV_WINDOW").ok()?;
    let (width, height) = spec.split_once('x')?;
    Some(size(px(width.parse().ok()?), px(height.parse().ok()?)))
}
