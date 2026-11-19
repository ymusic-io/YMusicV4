// Release builds launch without a console window; debug runs keep theirs for the log.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod a11y;
mod accent;
mod account;
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
mod dev_instance;
#[cfg(target_os = "linux")]
mod dock_quicklist;
mod download_dialog;
mod downloads;
mod downloads_view;
mod elevated_dialog;
mod equalizer;
mod fallback_art;
mod feed;
mod feedback_view;
mod file_rename;
mod fps_badge;
mod graph;
mod images;
mod instance;
#[cfg(target_os = "windows")]
mod jump_list;
mod launch;
mod lazy_rows;
mod library;
mod library_playlist;
mod library_rescan;
mod library_search;
mod local_art;
mod local_captions;
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
mod queue_source;
mod quick_download;
mod recent_captions;
mod reorder;
mod search_filter;
mod search_view;
mod selection;
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
mod spring;
mod tab_row;
mod telemetry;
#[cfg(unix)]
mod termination;
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
mod youtube_thumbnails;
mod yt_playlist;

use gpui_kit::*;
use ym_common::http::HttpClients;

use crate::cookie_session::{CookieOpen, LockedJar};
use crate::graph::AppGraph;
use crate::launch::{BootInputs, Launch};

enum Start {
    Ready(Box<AppGraph>),
    Locked(std::sync::Arc<LockedJar>, BootInputs),
}

/// A launch that cannot boot ends here, its error captured.
pub fn boot_failed(log: &ym_common::log::Logger, error: &ym_engine::EngineError) -> ! {
    log.capture_error("engine boot failed", error);
    crate::telemetry::disarm_exit_marker(log);
    ym_common::log::stderr_line(format_args!("{}", crate::feed::error_text(error)));
    std::process::exit(1);
}

/// Debug builds' data dir as a launch argument, ahead of `YMUSIC_DATA_DIR`: a Jump List task is a
/// shell link, which sets no environment.
pub const DEV_DATA_DIR_ARG: &str = "--data-dir=";

/// Set when the app quits: the `cancel` of every Library scan, so none outlives the quit.
pub static QUITTING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Blocking tasks still running after the quit hooks are left to the process exit past this.
const RUNTIME_SHUTDOWN_WAIT: std::time::Duration = std::time::Duration::from_secs(2);

fn main() {
    ym_i18n::use_system_locale();
    let dev = cfg!(debug_assertions).then(crate::dev_instance::resolve);
    let data_dir = match &dev {
        Some(dev) => {
            crate::dev_instance::set_workspace(dev.workspace.clone());
            dev.dir.clone()
        }
        None => crate::data_files::release_data_dir(),
    };
    if let Some(dev) = &dev
        && std::env::args().any(|arg| arg == crate::dev_instance::PRINT_DATA_DIR_ARG)
    {
        return dev.print();
    }
    let log_setup = crate::telemetry::logger(&data_dir);
    let log = log_setup.log.clone();
    if let Some(problem) = dev.and_then(|dev| dev.workspace_file_problem) {
        log.warn(
            &format!("R96010 the worktree's dev workspace file is unusable, using the default data dir: {problem}"),
            None,
        );
    }
    if let Some(workspace) = crate::dev_instance::workspace() {
        log.info(&format!("debug workspace {} ({}): {}", workspace.name, workspace.id, data_dir.display()));
    }
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
    #[cfg(unix)]
    let terminated = crate::termination::install(&log.fork("signals"))
        .inspect_err(|error| {
            log.warn("R90046 SIGTERM and SIGINT stay fatal: their quit handling did not install", Some(error))
        })
        .ok();
    crate::data_files::adopt_jvm_names(&data_dir, &log);
    if !launch.is_empty() {
        let _ = inbox_sender.unbounded_send(launch);
    }
    let tokio =
        tokio::runtime::Builder::new_multi_thread().enable_all().thread_name("ym-core").build().expect("tokio runtime");
    let http = HttpClients::new().expect("HTTP client");
    let image_loader = crate::images::image_loader(&data_dir, http.following.clone(), tokio.handle().clone(), &log);
    let app_db = graph::open_app_db(&data_dir).unwrap_or_else(|error| boot_failed(&log, &error));
    crate::data_files::import_legacy_files(&app_db, &data_dir, &log);
    let reporting = crate::telemetry::start_reporting(
        &log_setup,
        &data_dir,
        app_db.as_ref(),
        tokio.handle(),
        http.following.clone(),
    );
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
    crate::settings_view::apply_language(app_db.as_ref()).unwrap_or_else(|error| {
        boot_failed(&log, &ym_engine::EngineError::site("R91100", format!("UI language not seeded: {error}")))
    });
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
    #[cfg(target_os = "linux")]
    let tokio_handle = tokio.handle().clone();

    crate::a11y::install_debug_log();
    let assets = crate::assets::AppAssets;
    let app = application().with_assets(assets);
    // Opening the app again (Finder, Spotlight, the Dock) brings a window closed to the tray back.
    app.on_reopen(|cx| {
        if let Some(shell) = cx.try_global::<crate::shell::ShellHandle>().and_then(|handle| handle.0.upgrade()) {
            shell.update(cx, |shell, cx| shell.show_window(cx));
        }
    });
    #[cfg(unix)]
    let quit_inbox = inbox_sender.clone();
    // macOS delivers ymusic:// links and Finder's Open With as open-URL events, a cold launch's
    // included, before the window exists.
    app.on_open_urls(move |urls| {
        let _ = inbox_sender.unbounded_send(crate::instance::Request::from_urls(urls));
    });
    app.run(move |cx| {
        crate::settings_view::apply_layout_direction(cx);
        init(cx);
        cx.set_global(crate::instance::Inbox(inbox));
        #[cfg(unix)]
        if let Some(mut terminated) = terminated {
            cx.spawn(async move |cx| {
                use futures::StreamExt;
                if terminated.next().await.is_some() {
                    cx.update(|cx| crate::termination::quit(&quit_inbox, cx));
                }
            })
            .detach();
        }
        cx.on_app_quit(|_| {
            QUITTING.store(true, std::sync::atomic::Ordering::Relaxed);
            crate::instance::stop_accepting();
            async {}
        })
        .detach();
        let quit_log = log.fork("quit");
        cx.on_app_quit(move |_| {
            crate::telemetry::disarm_exit_marker(&quit_log);
            async {}
        })
        .detach();
        ym_image_loader::async_image::init(image_loader, cx);
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
            // Same floor as the JVM app: small enough to float as a mini video player.
            window_min_size: Some(size(px(275.), px(170.))),
            // Windows hides the native caption: the app draws its title bar (`win_title_bar`).
            titlebar: Some(TitlebarOptions {
                title: Some(crate::dev_instance::window_title().into()),
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
            app_id: Some(crate::desktop::app_id().into()),
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
        crate::desktop::activate_app(cx);
    });
    tokio.shutdown_timeout(RUNTIME_SHUTDOWN_WAIT);
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
