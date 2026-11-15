//! The viewer's YouTube playlists: add a video to one (or a new one), and create a playlist from
//! videos, which is also how the queue is saved. Signed out, each says it needs sign-in.

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::base::actions::Confirm;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, Icon, IconName, Selectable, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_engine::EngineError;
use ym_engine::yt::YtPlaylistTarget;
use ym_i18n::{Str, t, tf};

use crate::a11y::FocusList;
use crate::elevated_dialog::{
    DEFAULT_WIDTH, ROW_HORIZONTAL, ROW_VERTICAL, bar_spinner, button_bar, chrome, message, press, scroll_body,
    text_button, title_bar,
};
use crate::feed::error_text;
use crate::graph::{AppGraph, on_core};
use crate::settings_view::HOVER_ALPHA;
use crate::spinner::Spinner;

const PRIVACY: [(&str, Str); 3] = [
    ("PRIVATE", Str::YtPlaylistPrivacyPrivate),
    ("UNLISTED", Str::YtPlaylistPrivacyUnlisted),
    ("PUBLIC", Str::YtPlaylistPrivacyPublic),
];

enum Targets {
    Loading,
    SignedOut,
    Failed(EngineError),
    Ready(Vec<YtPlaylistTarget>),
}

/// The viewer's playlists plus New playlist; one already holding the video is checked and inert.
/// Picking one adds the video (a spinner on its row), then closes with a notice.
pub struct AddToPlaylist {
    video_id: String,
    targets: Targets,
    adding: Option<String>,
    focus: FocusList,
    scroll: ScrollHandle,
    focused_first: bool,
}

impl AddToPlaylist {
    pub fn open(video_id: String, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| {
            let scroll = ScrollHandle::new();
            let mut view = Self {
                video_id,
                targets: Targets::Loading,
                adding: None,
                focus: FocusList::modal(Some(scroll.clone()), cx),
                scroll,
                focused_first: false,
            };
            view.load(cx);
            view
        });
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, DEFAULT_WIDTH, window).on_ok(|_, _, _| false).child(view.clone())
        });
    }

    fn create_new(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.adding.is_none() {
            window.close_dialog(cx);
            CreatePlaylist::open(vec![self.video_id.clone()], false, window, cx);
        }
    }

    fn load(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log, video_id) = (graph.engine(), graph.log.fork("yt-playlist"), self.video_id.clone());
        let request = on_core(cx, async move {
            if !engine.account_info(false, &log).await?.signed_in {
                return Ok(None);
            }
            engine.playlist_targets(&video_id, &log).await.map(Some)
        });
        cx.spawn(async move |this, cx| {
            let result = request.await;
            this.update(cx, |view, cx| {
                view.targets = match result {
                    Some(Ok(Some(targets))) => Targets::Ready(targets),
                    Some(Ok(None)) => Targets::SignedOut,
                    Some(Err(error)) => Targets::Failed(error),
                    None => return,
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn add(&mut self, target: YtPlaylistTarget, window: &mut Window, cx: &mut Context<Self>) {
        if self.adding.is_some() || target.contains_video {
            return;
        }
        self.adding = Some(target.playlist_id.clone());
        let graph = cx.global::<AppGraph>();
        let (engine, log, video_id, playlist_id) =
            (graph.engine(), graph.log.fork("yt-playlist"), self.video_id.clone(), target.playlist_id.clone());
        let request = on_core(cx, async move { engine.add_to_playlist(&playlist_id, &video_id, &log).await });
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.adding = None;
                match result {
                    Some(Ok(())) => {
                        window.close_dialog(cx);
                        window.push_notification(tf(Str::YtPlaylistAdded, &[&target.title]), cx);
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

    fn row(id: impl Into<ElementId>, icon: Icon, label: String, faint: bool, busy: bool, cx: &App) -> Stateful<Div> {
        let theme = cx.theme();
        h_flex()
            .id(id)
            .role(Role::Button)
            .aria_label(label.clone())
            .w_full()
            .gap_4()
            .px(px(ROW_HORIZONTAL))
            .py(px(ROW_VERTICAL))
            .items_center()
            .cursor_pointer()
            .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
            .text_color(if faint { theme.muted_foreground } else { theme.foreground })
            .child(icon.size(px(22.)))
            .child(div().flex_1().min_w_0().truncate().text_size(px(14.)).child(label))
            .when(busy, |row| row.child(Spinner::new().small()))
    }
}

impl Render for AddToPlaylist {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let notice = |text: String, cx: &App| message(text, cx).text_color(theme.muted_foreground).into_any_element();
        let mut count = 0;
        let body = match &self.targets {
            Targets::Loading => h_flex().w_full().justify_center().py_6().child(Spinner::new()).into_any_element(),
            Targets::SignedOut => notice(t(Str::YtPlaylistSignIn).to_owned(), cx),
            Targets::Failed(error) => notice(error_text(error), cx),
            Targets::Ready(targets) => {
                if !self.focused_first {
                    self.focused_first = true;
                    let first = self.focus.handle(0, cx);
                    window.defer(cx, move |window, cx| window.focus(&first, cx));
                }
                let new_icon = Icon::new(IconName::Plus).text_color(theme.primary);
                let new_row =
                    Self::row("new-playlist", new_icon, t(Str::AddToPlaylistNew).to_owned(), false, false, cx)
                        .on_click(cx.listener(|view, _, window, cx| view.create_new(window, cx)))
                        .on_action(cx.listener(|view, _: &Confirm, window, cx| view.create_new(window, cx)));
                let mut list = v_flex().w_full().child(self.focus.item(0, new_row, cx));
                for (index, target) in targets.clone().into_iter().enumerate() {
                    let icon =
                        if target.contains_video { Icon::new(IconName::Check) } else { Icon::new(Lucide::ListPlus) };
                    let busy = self.adding.as_ref() == Some(&target.playlist_id);
                    let (label, faint, enter_target) = (target.title.clone(), target.contains_video, target.clone());
                    let row =
                        Self::row(("playlist-target", index), icon, label, faint, busy, cx)
                            .on_click(cx.listener(move |view, _, window, cx| view.add(target.clone(), window, cx)))
                            .on_action(cx.listener(move |view, _: &Confirm, window, cx| {
                                view.add(enter_target.clone(), window, cx)
                            }));
                    list = list.child(self.focus.item(index + 1, row, cx));
                }
                count = targets.len() + 1;
                scroll_body("playlist-targets", &self.scroll, list, window, cx).into_any_element()
            }
        };
        let idle = self.adding.is_none();
        let cancel = press(
            &self.focus,
            count,
            text_button("playlist-targets-cancel", t(Str::DialogCancel), false, idle, cx),
            idle,
            |_, window, cx| window.close_dialog(cx),
            cx,
        );
        self.focus
            .container(v_flex().w_full(), 0..count + 1, cx)
            .child(title_bar(t(Str::CdAddToPlaylist), cx))
            .child(body)
            .child(button_bar(cancel, cx))
    }
}

/// Name and privacy for a new playlist holding `video_ids`; closes with a notice once created and
/// stays open on failure.
pub struct CreatePlaylist {
    video_ids: Vec<String>,
    from_queue: bool,
    name: Entity<InputState>,
    privacy: &'static str,
    signed_in: Option<bool>,
    busy: bool,
    focus: FocusList,
    _subscription: Subscription,
}

const STOP_NAME: usize = 0;
const STOP_PRIVACY: usize = 1;
const STOP_CANCEL: usize = STOP_PRIVACY + PRIVACY.len();
const STOP_CREATE: usize = STOP_CANCEL + 1;

impl CreatePlaylist {
    pub fn open(video_ids: Vec<String>, from_queue: bool, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| {
            let name = cx.new(|cx| InputState::new(window, cx).placeholder(t(Str::PlaylistNameHint)));
            name.update(cx, |input, cx| input.focus(window, cx));
            let subscription = cx.subscribe_in(&name, window, |view: &mut Self, _, event: &InputEvent, window, cx| {
                if matches!(event, InputEvent::PressEnter { .. }) {
                    view.submit(window, cx);
                }
                cx.notify();
            });
            let mut view = Self {
                video_ids,
                from_queue,
                name,
                privacy: PRIVACY[0].0,
                signed_in: None,
                busy: false,
                focus: FocusList::modal(None, cx),
                _subscription: subscription,
            };
            view.check_account(cx);
            view
        });
        window.open_dialog(cx, move |dialog, window, _| {
            let submit = view.downgrade();
            chrome(dialog, DEFAULT_WIDTH, window).overlay_closable(false).child(view.clone()).on_ok(
                move |_, window, cx| {
                    submit.update(cx, |view, cx| view.submit(window, cx)).ok();
                    false
                },
            )
        });
    }

    fn check_account(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("yt-playlist"));
        let request = on_core(cx, async move { engine.account_info(false, &log).await });
        cx.spawn(async move |this, cx| {
            let result = request.await;
            this.update(cx, |view, cx| {
                view.signed_in = Some(match result {
                    Some(Ok(account)) => account.signed_in,
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.warn("account check failed", Some(&error));
                        false
                    }
                    None => return,
                });
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let title = self.name.read(cx).value().trim().to_owned();
        if self.busy || title.is_empty() || self.signed_in != Some(true) {
            return;
        }
        self.busy = true;
        let graph = cx.global::<AppGraph>();
        let (engine, log, privacy, video_ids) =
            (graph.engine(), graph.log.fork("yt-playlist"), self.privacy, self.video_ids.clone());
        let name = title.clone();
        let request = on_core(cx, async move { engine.create_playlist(&name, privacy, &video_ids, &log).await });
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match result {
                    Some(Ok(_)) => {
                        window.close_dialog(cx);
                        window.push_notification(tf(Str::YtPlaylistCreated, &[&title]), cx);
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
}

impl Render for CreatePlaylist {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let can_submit = !self.busy && self.signed_in == Some(true) && !self.name.read(cx).value().trim().is_empty();
        let cancel = press(
            &self.focus,
            STOP_CANCEL,
            text_button("create-playlist-cancel", t(Str::DialogCancel), false, !self.busy, cx),
            !self.busy,
            |_, window, cx| window.close_dialog(cx),
            cx,
        );
        let title = title_bar(t(Str::PlaylistCreateTitle), cx);
        if self.signed_in == Some(false) {
            let notice = message(t(Str::YtPlaylistSignIn), cx).text_color(theme.muted_foreground);
            return v_flex().w_full().child(title).child(notice).child(button_bar(cancel, cx));
        }
        let mut chips = h_flex().flex_wrap().gap_2();
        for (offset, (value, label)) in PRIVACY.into_iter().enumerate() {
            let selected = self.privacy == value;
            let chip = crate::action_icon::chip(Button::new(value)).label(t(label));
            let chip = if selected { chip.primary() } else { chip.outline() }.selected(selected);
            chips = chips.child(press(
                &self.focus,
                STOP_PRIVACY + offset,
                chip,
                true,
                cx.listener(move |view, _, _, cx| {
                    if !view.busy {
                        view.privacy = value;
                        cx.notify();
                    }
                }),
                cx,
            ));
        }
        let create = if self.busy {
            bar_spinner()
        } else {
            press(
                &self.focus,
                STOP_CREATE,
                text_button("create-playlist", t(Str::PlaylistCreateConfirm), true, can_submit, cx),
                can_submit,
                cx.listener(|view, _, window, cx| view.submit(window, cx)),
                cx,
            )
        };
        let field = Input::new(&self.name).aria_label(t(Str::PlaylistNameHint));
        v_flex()
            .w_full()
            .child(title)
            .child(
                v_flex()
                    .w_full()
                    .px(px(ROW_HORIZONTAL))
                    .pt_1()
                    .pb_2()
                    .gap_2()
                    .when(self.from_queue, |column| {
                        column.child(
                            div()
                                .text_size(px(13.))
                                .text_color(theme.muted_foreground)
                                .child(tf(Str::YtPlaylistFromQueue, &[&self.video_ids.len()])),
                        )
                    })
                    .child(div().py_2().child(self.focus.field(STOP_NAME, &self.name.focus_handle(cx), field, cx)))
                    .child(chips),
            )
            .child(button_bar(h_flex().child(cancel).child(create), cx))
    }
}
