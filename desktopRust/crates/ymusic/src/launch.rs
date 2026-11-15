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
        AppGraph::boot(self.tokio, self.log, self.http, self.data_dir, self.app_db, session).await
    }
}

enum Stage {
    Unlock(Entity<UnlockView>),
    Shell(Entity<Shell>),
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
            let graph = match booted.await {
                Ok(Ok(graph)) => graph,
                Ok(Err(error)) => crate::boot_failed(&log, &error),
                Err(error) => crate::boot_failed(&log, &ym_engine::EngineError::site("R98003", error.to_string())),
            };
            this.update_in(cx, |launch, window, cx| {
                cx.global::<Reporting>().follow(&graph);
                cx.set_global(graph);
                launch.stage = Stage::Shell(Self::new_shell(window, cx));
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
        };
        crate::desktop::frame(content, window, cx)
    }
}
