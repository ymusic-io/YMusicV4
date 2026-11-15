//! What the app is asked to open from outside: later launches' hand-offs, the launch's own
//! arguments and macOS open-URL events. Links go to the engine, which says what each opens; pages
//! land on Discover's stack, a video plays in Now Playing. Media files play as the queue.

use std::path::PathBuf;

use gpui_kit::component::WindowExt;
use gpui_kit::*;
use ym_engine::yt::YtLinkTarget;
use ym_i18n::{Str, t, tf};

use super::{Section, Shell};
use crate::desktop::TrayCommand;
use crate::feed::FeedSource;
use crate::graph::{AppGraph, on_core};
use crate::instance::{Command, Inbox, Request};
use crate::playback::QueueItem;

impl Shell {
    /// Takes the requests queued since launch, then each later one as it arrives.
    pub(super) fn listen_incoming(window: &mut Window, cx: &mut Context<Self>) {
        if !cx.has_global::<Inbox>() {
            return;
        }
        let Inbox(mut requests) = cx.remove_global::<Inbox>();
        cx.spawn_in(window, async move |this, cx| {
            use futures::StreamExt;
            while let Some(request) = requests.next().await {
                if this.update_in(cx, |shell, window, cx| shell.incoming(request, window, cx)).is_err() {
                    break;
                }
            }
        })
        .detach();
    }

    fn incoming(&mut self, request: Request, window: &mut Window, cx: &mut Context<Self>) {
        match request.command {
            Command::Show => self.show_window(cx),
            Command::TogglePlay => self.tray_command(TrayCommand::TogglePlay, window, cx),
            Command::Stop => self.tray_command(TrayCommand::Stop, window, cx),
            Command::Quit => return self.tray_command(TrayCommand::Quit, window, cx),
        }
        if !request.files.is_empty() {
            self.open_files(request.files, window, cx);
        }
        for link in request.links {
            self.open_link(link, window, cx);
        }
    }

    fn open_link(&mut self, url: String, window: &mut Window, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("IncomingLink"));
        let resolve_log = log.clone();
        let resolve = on_core(cx, async move { engine.resolve_link(&url, &resolve_log).await });
        cx.spawn_in(window, async move |this, cx| {
            let Some(result) = resolve.await else { return };
            this.update_in(cx, |shell, window, cx| match result {
                Ok(Some(target)) => shell.open_link_target(target, window, cx),
                Ok(None) => window.push_notification(t(Str::LinkUnsupported), cx),
                Err(error) => {
                    log.warn("resolve link failed", Some(&error));
                    window.push_notification(crate::feed::error_text(&error), cx);
                }
            })
            .ok();
        })
        .detach();
    }

    fn open_link_target(&mut self, target: YtLinkTarget, window: &mut Window, cx: &mut Context<Self>) {
        let page = match target {
            YtLinkTarget::Video { item, playlist_url, start_ms } => {
                self.playback.update(cx, |playback, cx| playback.play_link(*item, playlist_url, start_ms, cx));
                self.now_playing_open = true;
                cx.notify();
                return;
            }
            YtLinkTarget::Search(query) => {
                self.select_section(Section::Discover, cx);
                self.show_results(query, cx);
                return;
            }
            YtLinkTarget::Playlist(url) | YtLinkTarget::Channel(url) | YtLinkTarget::MusicBrowse(url) => url,
        };
        match FeedSource::from_link(&page) {
            Some(source) => {
                self.select_section(Section::Discover, cx);
                self.push(source, None, cx);
            }
            None => window.push_notification(t(Str::LinkUnsupported), cx),
        }
    }

    /// Plays `paths` as the queue: a file in the Library as that item, any other described by its
    /// own tags (or its name when it can't be probed).
    fn open_files(&mut self, paths: Vec<PathBuf>, window: &mut Window, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (store, log) = (graph.media.clone(), graph.log.fork("open-file"));
        let count = paths.len();
        let describe = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                paths.into_iter().filter_map(|path| describe_file(&store, path, &log)).collect::<Vec<_>>()
            })
            .await
        });
        let log = cx.global::<AppGraph>().log.fork("open-file");
        cx.spawn_in(window, async move |this, cx| {
            let items = match describe.await {
                Some(Ok(items)) => items,
                Some(Err(error)) => {
                    log.warn("opened files could not be read", Some(&error));
                    Vec::new()
                }
                None => return,
            };
            this.update_in(cx, |shell, window, cx| {
                if items.is_empty() {
                    let error = OpenFilesFailed(count);
                    log.capture_error("opening files failed", &error);
                    window.push_notification(tf(Str::ErrorGeneric, &[&"R96002"]), cx);
                    return;
                }
                shell.playback.update(cx, |playback, cx| playback.play_queue(items, 0, cx));
                shell.now_playing_open = true;
                shell.show_window(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

#[derive(Debug, thiserror::Error)]
#[error("R96002 none of the {0} opened file(s) could be read")]
struct OpenFilesFailed(usize);

/// Blocking: the Library's item for `path`, else one probed from the file itself.
fn describe_file(store: &ym_store::MediaStore, path: PathBuf, log: &ym_common::log::Logger) -> Option<QueueItem> {
    let uri = path.to_string_lossy().into_owned();
    match store.find_item_by_uri(&uri) {
        Ok(Some((item, false))) => return Some(QueueItem::from_local(&item)),
        Ok(_) => {}
        Err(error) => log.warn("opened file: the Library lookup failed", Some(&error)),
    }
    match ym_store::describe_file(&path) {
        Ok(described) => {
            if let Some(code) = described.probe_error {
                log.warn(&format!("opened file probe failed (libav {code}): playing it under its name"), None);
            }
            Some(QueueItem::from_local(&described.item))
        }
        Err(error) => {
            log.warn(&format!("opened file could not be read: {}", path.display()), Some(&error));
            None
        }
    }
}
