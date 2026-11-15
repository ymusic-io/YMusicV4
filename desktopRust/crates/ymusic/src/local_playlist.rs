//! "Create local playlist" from a YouTube playlist page: a Library playlist of the page's videos
//! already saved offline, in playlist order. Nothing is downloaded.

use std::collections::HashSet;

use gpui_kit::component::button::Button;
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, Sizable, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_engine::yt::{YtFeedItem, YtFeedPage};
use ym_i18n::{Plural, Str, plural, t, tf};

use crate::a11y::FocusList;
use crate::downloads::offline_ids;
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, bar_spinner, button_bar, chrome, press, scroll_body, text_button, title_bar,
};
use crate::feed::{FeedSource, error_text, thumb_for};
use crate::graph::{AppGraph, on_core};
use crate::spinner::Spinner;

const STOP_LOAD_MORE: usize = 0;
const STOP_CREATE: usize = 1;
const STOP_NAME: usize = 2;
const STOP_CANCEL: usize = 3;

pub struct LocalPlaylistFromDownloads {
    source: FeedSource,
    videos: Vec<YtFeedItem>,
    continuation: Option<String>,
    loading_more: bool,
    offline: Option<HashSet<String>>,
    taken: Vec<String>,
    name: Entity<InputState>,
    busy: bool,
    focus: FocusList,
    scroll: ScrollHandle,
    _subscription: Subscription,
}

impl LocalPlaylistFromDownloads {
    pub fn open(source: FeedSource, title: String, page: &YtFeedPage, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| {
            let name = cx.new(|cx| {
                InputState::new(window, cx).placeholder(t(Str::PlaylistNameHint)).default_value(title.clone())
            });
            name.update(cx, |input, cx| input.focus(window, cx));
            let subscription = cx.subscribe_in(&name, window, |view: &mut Self, _, event: &InputEvent, window, cx| {
                if matches!(event, InputEvent::PressEnter { .. }) {
                    view.submit(window, cx);
                }
                cx.notify();
            });
            let scroll = ScrollHandle::new();
            let mut view = Self {
                source,
                videos: Vec::new(),
                continuation: page.continuation.clone(),
                loading_more: false,
                offline: None,
                taken: Vec::new(),
                name,
                busy: false,
                focus: FocusList::modal(Some(scroll.clone()), cx),
                scroll,
                _subscription: subscription,
            };
            view.add_videos(page);
            view.load_state(cx);
            view
        });
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, DEFAULT_WIDTH, window).overlay_closable(false).on_ok(|_, _, _| false).child(view.clone())
        });
    }

    fn load_state(&mut self, cx: &mut Context<Self>) {
        let offline = offline_ids(cx);
        let media = cx.global::<AppGraph>().media.clone();
        let playlists = on_core(cx, async move { tokio::task::spawn_blocking(move || media.playlists()).await });
        cx.spawn(async move |this, cx| {
            let (offline, playlists) = futures::join!(offline, playlists);
            this.update(cx, |view, cx| {
                view.offline = Some(offline);
                match playlists {
                    Some(Ok(Ok(playlists))) => {
                        view.taken = playlists.into_iter().map(|playlist| playlist.name).collect()
                    }
                    Some(Ok(Err(error))) => {
                        cx.global::<AppGraph>().log.warn("Library playlists could not be read", Some(&error))
                    }
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.warn("Library playlists could not be read", Some(&error))
                    }
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn add_videos(&mut self, page: &YtFeedPage) {
        let seen: HashSet<String> = self.videos.iter().map(|video| video.id.clone()).collect();
        let fresh = page.sections.iter().flat_map(|section| section.items.iter()).filter(|item| item.kind == "video");
        let mut seen = seen;
        for video in fresh {
            if seen.insert(video.id.clone()) {
                self.videos.push(video.clone());
            }
        }
    }

    fn downloaded(&self) -> Vec<&YtFeedItem> {
        let Some(offline) = &self.offline else { return Vec::new() };
        self.videos.iter().filter(|video| offline.contains(&video.id)).collect()
    }

    fn load_more(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(continuation) = self.continuation.clone() else { return };
        if self.loading_more {
            return;
        }
        self.loading_more = true;
        let graph = cx.global::<AppGraph>();
        let (engine, history, log) = (graph.engine(), graph.history.clone(), graph.log.fork("local-playlist"));
        let load = on_core(cx, self.source.clone().load(engine, history, Some(continuation), log));
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = load.await;
            this.update_in(cx, |view, window, cx| {
                view.loading_more = false;
                match result {
                    Some(Ok(page)) => {
                        view.continuation = page.continuation.clone();
                        view.add_videos(&page);
                    }
                    Some(Err(error)) => window.push_notification(error_text(&error), cx),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn trimmed(&self, cx: &App) -> String {
        self.name.read(cx).value().trim().to_owned()
    }

    fn duplicate(&self, cx: &App) -> bool {
        let name = self.trimmed(cx).to_lowercase();
        !name.is_empty() && self.taken.iter().any(|taken| taken.to_lowercase() == name)
    }

    fn can_submit(&self, cx: &App) -> bool {
        !self.busy && !self.trimmed(cx).is_empty() && !self.duplicate(cx) && !self.downloaded().is_empty()
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.can_submit(cx) {
            return;
        }
        self.busy = true;
        let name = self.trimmed(cx);
        let codes: Vec<String> = self.downloaded().iter().map(|video| video.id.clone()).collect();
        let count = codes.len();
        let media = cx.global::<AppGraph>().media.clone();
        let playlist_name = name.clone();
        let create = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                let playlist = media.create_playlist(&playlist_name)?;
                media.add_codes_to_playlist(playlist.id, &codes)
            })
            .await
        });
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = create.await;
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match result {
                    Some(Ok(Ok(()))) => {
                        window.close_dialog(cx);
                        window.push_notification(
                            plural(Plural::LocalPlaylistCreated, count as i64, &[&name, &count]),
                            cx,
                        );
                    }
                    Some(Ok(Err(error))) => {
                        cx.global::<AppGraph>()
                            .log
                            .warn("R92002 local playlist from downloads not created", Some(&error));
                        window.push_notification(tf(Str::ErrorGeneric, &[&"R92002"]), cx);
                    }
                    Some(Err(error)) => {
                        cx.global::<AppGraph>()
                            .log
                            .warn("R92002 local playlist from downloads not created", Some(&error));
                        window.push_notification(tf(Str::ErrorGeneric, &[&"R92002"]), cx);
                    }
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

impl Render for LocalPlaylistFromDownloads {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let downloaded: Vec<YtFeedItem> = self.downloaded().into_iter().cloned().collect();
        let duplicate = self.duplicate(cx);
        let can_submit = self.can_submit(cx);
        let load_more: AnyElement = if self.loading_more {
            Spinner::new().small().into_any_element()
        } else if self.continuation.is_some() {
            self.focus
                .button(
                    STOP_LOAD_MORE,
                    Button::new("local-load-more")
                        .small()
                        .rounded_full()
                        .outline()
                        .label(t(Str::PlaylistLoadMore))
                        .on_click(cx.listener(|view, _, window, cx| view.load_more(window, cx))),
                    cx,
                )
                .into_any_element()
        } else {
            div().into_any_element()
        };
        let mut rows = v_flex().w_full();
        if self.offline.is_some() && downloaded.is_empty() {
            rows = rows.child(
                div()
                    .px(px(ROW_HORIZONTAL))
                    .py_4()
                    .text_size(px(14.))
                    .text_color(theme.muted_foreground)
                    .child(t(Str::LocalPlaylistEmpty)),
            );
        }
        for (index, video) in downloaded.iter().enumerate() {
            rows = rows.child(
                h_flex()
                    .id(("local-video", index))
                    .w_full()
                    .gap_4()
                    .px(px(ROW_HORIZONTAL))
                    .py_1()
                    .items_center()
                    .child(
                        div()
                            .flex_shrink_0()
                            .w(px(64.))
                            .h(px(36.))
                            .rounded(px(4.))
                            .overflow_hidden()
                            .bg(theme.secondary)
                            .when_some(thumb_for(&video.thumbs, 128), |frame, url| {
                                frame.child(img(url).size_full().rounded(px(4.)).object_fit(ObjectFit::Cover))
                            }),
                    )
                    .child(
                        v_flex()
                            .flex_1()
                            .min_w_0()
                            .child(div().text_sm().truncate().child(video.title.clone()))
                            .when_some(video.subtitle_line1.clone(), |column, line| {
                                column.child(div().text_xs().text_color(theme.muted_foreground).truncate().child(line))
                            }),
                    ),
            );
        }
        let field = Input::new(&self.name)
            .aria_label(t(Str::PlaylistNameHint))
            .when(duplicate, |input| input.border_color(theme.danger));
        let body = v_flex()
            .w_full()
            .pb_2()
            .child(
                div()
                    .px(px(ROW_HORIZONTAL))
                    .pt_4()
                    .text_size(px(14.))
                    .text_color(theme.muted_foreground)
                    .child(t(Str::LocalPlaylistDesc)),
            )
            .child(
                v_flex()
                    .px(px(ROW_HORIZONTAL))
                    .py_2()
                    .gap_1()
                    .child(self.focus.field(STOP_NAME, &self.name.focus_handle(cx), field, cx))
                    .when(duplicate, |column| {
                        column.child(
                            div()
                                .id("local-playlist-taken")
                                .px_4()
                                .text_size(px(12.))
                                .text_color(theme.danger)
                                .role(Role::Alert)
                                .aria_label(t(Str::PlaylistNameExists))
                                .child(t(Str::PlaylistNameExists)),
                        )
                    }),
            )
            .child(
                h_flex()
                    .px(px(ROW_HORIZONTAL))
                    .py_1()
                    .gap_2()
                    .items_center()
                    .child(
                        div().px_3().py_1().rounded_full().border_1().border_color(theme.border).text_xs().child(
                            plural(Plural::LocalPlaylistDownloaded, downloaded.len() as i64, &[&downloaded.len()]),
                        ),
                    )
                    .child(load_more),
            )
            .child(rows);
        let create = if self.busy {
            bar_spinner()
        } else {
            press(
                &self.focus,
                STOP_CREATE,
                text_button("local-playlist-create", t(Str::PlaylistCreateConfirm), true, can_submit, cx),
                can_submit,
                cx.listener(|view, _, window, cx| view.submit(window, cx)),
                cx,
            )
        };
        let buttons = h_flex()
            .child(press(
                &self.focus,
                STOP_CANCEL,
                text_button("local-playlist-cancel", t(Str::DialogCancel), false, !self.busy, cx),
                !self.busy,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(create);
        v_flex()
            .w_full()
            .child(title_bar(t(Str::LocalPlaylistCreate), cx))
            .child(scroll_body("local-playlist-scroll", &self.scroll, body, window, cx))
            .child(button_bar(buttons, cx))
    }
}
