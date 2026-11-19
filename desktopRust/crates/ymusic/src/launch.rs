//! The main window's root: the shell, or first the unlock gate when the saved sign-in is
//! password-sealed (the graph boots once the launch knows whose cookies it runs on).

use std::path::PathBuf;
use std::sync::Arc;

use gpui_kit::*;
use tokio::runtime::Handle;
use ym_common::http::HttpClients;
use ym_common::log::Logger;
use ym_settings::AppDb;

use crate::cookie_lock::UnlockView;
use crate::cookie_session::{CookieSession, LockedJar};
use crate::graph::AppGraph;
use crate::shell::Shell;
use crate::startup_failure::StartupFailure;
use crate::telemetry::Reporting;

/// What the graph boots from, besides the cookie session.
pub struct BootInputs {
    pub tokio: Handle,
    pub log: Logger,
    pub http: HttpClients,
    pub data_dir: PathBuf,
    pub app_db: Arc<AppDb>,
}

impl BootInputs {
    pub async fn boot(self, session: CookieSession) -> Result<AppGraph, ym_engine::EngineError> {
        let graph = AppGraph::boot(self.tokio, self.log, self.http, self.data_dir, self.app_db, session).await?;
        crate::local_art::attach_media(graph.media.clone());
        Ok(graph)
    }
}

enum Stage {
    Unlock(Entity<UnlockView>),
    Shell(Entity<Shell>),
    Failed(Entity<StartupFailure>),
}

pub struct Launch {
    stage: Stage,
}

impl Launch {
    /// The graph is already a global.
    pub fn shell(window: &mut Window, cx: &mut Context<Self>) -> Self {
        Self { stage: Stage::Shell(Self::new_shell(window, cx)) }
    }

    pub fn locked(locked: Arc<LockedJar>, inputs: BootInputs, window: &mut Window, cx: &mut Context<Self>) -> Self {
        window.on_window_should_close(cx, |_, cx| {
            cx.quit();
            true
        });
        let (tokio, log) = (inputs.tokio.clone(), inputs.log.clone());
        let launch = cx.entity().downgrade();
        let unlock = cx.new(|cx| {
            UnlockView::new(
                locked,
                tokio,
                log,
                move |session, window, cx| {
                    launch.update(cx, |_, cx| Self::boot(inputs, session, window, cx)).ok();
                },
                window,
                cx,
            )
        });
        crate::shell::dev_keys(window, cx);
        Self { stage: Stage::Unlock(unlock) }
    }

    fn new_shell(window: &mut Window, cx: &mut Context<Self>) -> Entity<Shell> {
        crate::library_rescan::schedule(cx.global::<AppGraph>());
        cx.new(|cx| {
            let mut shell = Shell::new(window, cx);
            shell.apply_dev_route(window, cx);
            shell
        })
    }

    fn boot(inputs: BootInputs, session: CookieSession, window: &mut Window, cx: &mut Context<Self>) {
        let (tokio, log) = (inputs.tokio.clone(), inputs.log.clone());
        let booted = tokio.spawn(inputs.boot(session));
        cx.spawn_in(window, async move |this, cx| {
            let booted = match booted.await {
                Ok(booted) => booted,
                Err(error) => Err(ym_engine::EngineError::site("R98003", error.to_string())),
            };
            this.update_in(cx, |launch, window, cx| {
                launch.stage = match booted {
                    Ok(graph) => {
                        cx.global::<Reporting>().follow(&graph);
                        cx.set_global(graph);
                        Stage::Shell(Self::new_shell(window, cx))
                    }
                    Err(error) => {
                        log.capture_error("engine boot failed", &error);
                        let message = crate::feed::error_text(&error);
                        Stage::Failed(cx.new(|_| StartupFailure::new(message, false)))
                    }
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

impl Render for Launch {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let content = match &self.stage {
            Stage::Unlock(view) => view.clone().into_any_element(),
            Stage::Shell(shell) => shell.clone().into_any_element(),
            Stage::Failed(failure) => failure.clone().into_any_element(),
        };
        crate::desktop::frame(content, window, cx)
    }
}
